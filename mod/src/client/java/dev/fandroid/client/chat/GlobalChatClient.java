package dev.fandroid.client.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.ChatMessagePayload;
import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.ConversationKind;
import dev.fandroid.protocol.DeviceKeyBundle;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.MessageEventType;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.UuidV7;
import dev.fandroid.protocol.crypto.DeviceIdentity;
import dev.fandroid.protocol.crypto.EnvelopeCrypto;
import dev.fandroid.protocol.crypto.EnvelopeMetadata;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class GlobalChatClient implements AutoCloseable {
    private static final URI MOJANG_JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    private final URI backend;
    private final MinecraftClient minecraft;
    private final DeviceIdentity identity;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7)).build();
    private final Consumer<DecryptedGlobalEnvelope> receiver;
    private final CopyOnWriteArrayList<GlobalContact> contacts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String token;
    private volatile State state = State.DISABLED;

    private GlobalChatClient(URI backend, MinecraftClient minecraft, DeviceIdentity identity,
                             Consumer<DecryptedGlobalEnvelope> receiver) {
        this.backend = backend;
        this.minecraft = minecraft;
        this.identity = identity;
        this.receiver = receiver;
    }

    public static GlobalChatClient startIfConfigured(MinecraftClient client, DeviceIdentity identity,
                                                      Consumer<DecryptedGlobalEnvelope> receiver) {
        String configured = System.getProperty("fandroid.backendUrl");
        if (configured == null || configured.isBlank()) configured = System.getenv("FANDROID_BACKEND_URL");
        if (configured == null || configured.isBlank()) return new GlobalChatClient(null, client, identity, receiver);
        GlobalChatClient global = new GlobalChatClient(URI.create(configured.replaceAll("/+$", "")),
                client, identity, receiver);
        Thread.ofVirtual().name("fandroid-global-chat").start(global::run);
        return global;
    }

    private void run() {
        state = State.CONNECTING;
        try {
            token = loadToken();
            if (token == null || !refreshContacts().join()) authenticate();
            state = State.ONLINE;
            while (!closed.get()) {
                pollPending();
                refreshContacts().join();
                Thread.sleep(2_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            state = State.OFFLINE;
            FanDroidMod.LOGGER.warn("Global Chat is offline", e);
        }
    }

    private void authenticate() throws Exception {
        String username = minecraft.getSession().getUsername();
        var challenge = post("/v1/auth/challenge", java.util.Map.of("username", username), null);
        UUID challengeId = UUID.fromString(challenge.path("challengeId").asText());
        String serverId = challenge.path("serverId").asText();
        UUID profile = minecraft.getSession().getUuidOrNull();
        if (profile == null) throw new IllegalStateException("online Minecraft profile is required for Global Chat");

        // The access token is sent only to Mojang's official session server.
        byte[] joinBody = ProtocolCodec.write(java.util.Map.of(
                "accessToken", minecraft.getSession().getAccessToken(),
                "selectedProfile", profile.toString().replace("-", ""),
                "serverId", serverId));
        HttpResponse<byte[]> join = http.send(HttpRequest.newBuilder(MOJANG_JOIN)
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(joinBody)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (join.statusCode() != 204) throw new IllegalStateException("Mojang rejected FanDroid session proof");

        var complete = post("/v1/auth/complete", java.util.Map.of(
                "challengeId", challengeId,
                "username", username,
                "device", identity.publicBundle()), null);
        token = complete.path("token").asText();
        saveToken(token);
    }

    private CompletableFuture<Boolean> refreshContacts() {
        if (backend == null || token == null) return CompletableFuture.completedFuture(false);
        return request("GET", "/v1/contacts", null, token).thenApply(response -> {
            if (response.statusCode() == 401) return false;
            if (response.statusCode() != 200) throw new IllegalStateException("contacts returned " + response.statusCode());
            try {
                List<GlobalContact> values = ProtocolCodec.mapper().readValue(response.body(), new TypeReference<>() { });
                contacts.clear();
                contacts.addAll(values);
                return true;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void pollPending() {
        try {
            HttpResponse<byte[]> response = request("GET", "/v1/messages/pending?limit=100", null, token).join();
            if (response.statusCode() != 200) return;
            List<MessageEnvelope> values = ProtocolCodec.mapper().readValue(response.body(), new TypeReference<>() { });
            for (MessageEnvelope envelope : values) {
                try {
                    List<DeviceKeyBundle> senderDevices = devices(envelope.senderUuid()).join();
                    DeviceKeyBundle sender = senderDevices.stream()
                            .filter(device -> device.deviceId().equals(envelope.senderDeviceId())).findFirst().orElseThrow();
                    byte[] plaintext = EnvelopeCrypto.decrypt(envelope, identity, sender.signingPublicKey());
                    ChatMessagePayload message = envelope.eventType() == MessageEventType.CREATE
                            || envelope.eventType() == MessageEventType.EDIT
                            ? ProtocolCodec.read(plaintext, ChatMessagePayload.class) : null;
                    receiver.accept(new DecryptedGlobalEnvelope(envelope, message, contactName(envelope.senderUuid())));
                    request("POST", "/v1/messages/" + envelope.messageId() + "/ack", new byte[0], token).join();
                } catch (Exception e) {
                    FanDroidMod.LOGGER.warn("Ignored an undecryptable global envelope", e);
                }
            }
        } catch (Exception e) {
            state = State.OFFLINE;
        }
    }

    public CompletableFuture<UUID> createDirect(UUID contactId) {
        return request("POST", "/v1/conversations", ProtocolCodec.write(java.util.Map.of(
                "kind", ConversationKind.DIRECT,
                "title", "",
                "members", Set.of(contactId))), token).thenApply(response -> {
            if (response.statusCode() != 201) throw new IllegalStateException("could not create conversation");
            try { return UUID.fromString(ProtocolCodec.mapper().readTree(response.body()).path("id").asText()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    public CompletableFuture<List<AccountSearchResult>> searchAccounts(String username) {
        String encoded = java.net.URLEncoder.encode(username, StandardCharsets.UTF_8);
        return request("GET", "/v1/accounts/search?username=" + encoded, null, token).thenApply(response -> {
            if (response.statusCode() != 200) return List.of();
            try { return ProtocolCodec.mapper().readValue(response.body(), new TypeReference<>() { }); }
            catch (Exception e) { return List.of(); }
        });
    }

    public CompletableFuture<Void> requestContact(UUID accountId) {
        return request("POST", "/v1/contacts/requests", ProtocolCodec.write(java.util.Map.of("accountId", accountId)), token)
                .thenAccept(response -> { if (response.statusCode() != 204) throw new IllegalStateException("contact request failed"); });
    }

    public CompletableFuture<Void> acceptContact(UUID accountId) {
        return request("POST", "/v1/contacts/requests/" + accountId + "/accept", new byte[0], token)
                .thenAccept(response -> { if (response.statusCode() != 204) throw new IllegalStateException("contact acceptance failed"); });
    }

    public CompletableFuture<Void> block(UUID accountId) {
        return request("POST", "/v1/blocks", ProtocolCodec.write(java.util.Map.of("accountId", accountId)), token)
                .thenAccept(response -> { if (response.statusCode() != 204) throw new IllegalStateException("block failed"); });
    }

    public CompletableFuture<UUID> send(UUID conversationId, UUID recipientId, UUID senderId,
                                        String text, UUID replyTo) {
        return sendEvent(conversationId, recipientId, senderId, MessageEventType.CREATE, null,
                new ChatMessagePayload(text, replyTo, null, Instant.now()));
    }

    public CompletableFuture<UUID> edit(UUID conversationId, UUID recipientId, UUID senderId,
                                        UUID target, String text) {
        return sendEvent(conversationId, recipientId, senderId, MessageEventType.EDIT, target,
                new ChatMessagePayload(text, null, null, Instant.now()));
    }

    public CompletableFuture<UUID> delete(UUID conversationId, UUID recipientId, UUID senderId, UUID target) {
        return sendEvent(conversationId, recipientId, senderId, MessageEventType.DELETE, target, null);
    }

    private CompletableFuture<UUID> sendEvent(UUID conversationId, UUID recipientId, UUID senderId,
                                               MessageEventType eventType, UUID target,
                                               ChatMessagePayload message) {
        return devices(recipientId).thenCombine(devices(senderId), (recipient, own) -> {
            List<DeviceKeyBundle> all = new ArrayList<>(recipient);
            all.addAll(own);
            Instant now = Instant.now();
            UUID messageId = UuidV7.randomUuid();
            MessageEnvelope envelope = EnvelopeCrypto.encrypt(new EnvelopeMetadata(messageId, conversationId,
                            senderId, ChatTransport.GLOBAL, eventType, target,
                            now, now.plus(Duration.ofDays(7))),
                    message == null ? ProtocolCodec.write(java.util.Map.of("control", eventType.name()))
                            : ProtocolCodec.write(message), identity, all);
            return envelope;
        }).thenCompose(envelope -> request("POST", "/v1/messages", ProtocolCodec.write(envelope), token)
                .thenApply(response -> {
                    if (response.statusCode() != 202) throw new IllegalStateException("global message was rejected");
                    return envelope.messageId();
                }));
    }

    private CompletableFuture<List<DeviceKeyBundle>> devices(UUID accountId) {
        return request("GET", "/v1/accounts/" + accountId + "/devices", null, token).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("device directory unavailable");
            try { return ProtocolCodec.mapper().readValue(response.body(), new TypeReference<>() { }); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private com.fasterxml.jackson.databind.JsonNode post(String path, Object body, String bearer) throws Exception {
        HttpResponse<byte[]> response = request("POST", path, ProtocolCodec.write(body), bearer).join();
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("backend returned " + response.statusCode());
        return ProtocolCodec.mapper().readTree(response.body());
    }

    private CompletableFuture<HttpResponse<byte[]>> request(String method, String path, byte[] body, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(backend.resolve(path)).timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        else request.method(method, HttpRequest.BodyPublishers.noBody());
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Path tokenFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("fandroid/global-session-"
                + Integer.toHexString(backend.toString().hashCode()) + ".txt");
    }

    private String loadToken() {
        try { return Files.exists(tokenFile()) ? Files.readString(tokenFile()).strip() : null; }
        catch (Exception e) { return null; }
    }

    private void saveToken(String value) {
        try { Files.createDirectories(tokenFile().getParent()); Files.writeString(tokenFile(), value); }
        catch (Exception ignored) { }
    }

    private String contactName(UUID id) {
        return contacts.stream().filter(contact -> contact.accountId().equals(id)).map(GlobalContact::username)
                .findFirst().orElse(id.toString().substring(0, 8));
    }

    public List<GlobalContact> contacts() { return List.copyOf(contacts); }
    public State state() { return state; }

    @Override
    public void close() { closed.set(true); }

    public enum State { DISABLED, CONNECTING, ONLINE, OFFLINE }
    public record GlobalContact(UUID accountId, String username, String status, boolean outgoing) { }
    public record AccountSearchResult(UUID accountId, String username) { }
    public record DecryptedGlobalEnvelope(MessageEnvelope envelope, ChatMessagePayload message, String senderName) { }
}
