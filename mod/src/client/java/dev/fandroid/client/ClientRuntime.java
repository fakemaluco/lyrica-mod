package dev.fandroid.client;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.fandroid.FanDroidMod;
import dev.fandroid.client.chat.LocalChatStore;
import dev.fandroid.client.chat.GlobalChatClient;
import dev.fandroid.network.CallSignalPayload;
import dev.fandroid.network.CapabilityFlags;
import dev.fandroid.network.ServerAckPayload;
import dev.fandroid.network.ServerDirectoryPayload;
import dev.fandroid.network.ServerEnvelopePayload;
import dev.fandroid.network.ServerHelloPayload;
import dev.fandroid.protocol.ChatMessagePayload;
import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.DeviceKeyBundle;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.MessageEventType;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.UuidV7;
import dev.fandroid.protocol.VoiceAttachment;
import dev.fandroid.protocol.crypto.DeviceIdentity;
import dev.fandroid.protocol.crypto.EnvelopeCrypto;
import dev.fandroid.protocol.crypto.EnvelopeMetadata;
import dev.fandroid.protocol.crypto.ReplayGuard;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClientRuntime implements AutoCloseable {
    private final ClientPreferences preferences = ClientPreferences.load();
    private final DeviceIdentity identity = IdentityStore.loadOrCreate();
    private final ReplayGuard replayGuard = new ReplayGuard(Duration.ofDays(8));
    private final CopyOnWriteArrayList<DirectoryEntry> directory = new CopyOnWriteArrayList<>();
    private final ClientNotificationCenter notifications = new ClientNotificationCenter();
    private volatile LocalChatStore chatStore;
    private volatile UUID accountId;
    private volatile int serverCapabilities;
    private volatile IncomingCall incomingCall;
    private volatile OutgoingCall outgoingCall;
    private volatile ActiveCall activeCall;
    private volatile GlobalChatClient globalChat;

    public void initializeForSession(MinecraftClient client) {
        if (chatStore != null) return;
        UUID id = client.getSession().getUuidOrNull();
        if (id == null) id = UUID.nameUUIDFromBytes(("offline:" + client.getSession().getUsername()).getBytes(StandardCharsets.UTF_8));
        this.accountId = id;
        this.chatStore = new LocalChatStore(id);
        this.globalChat = GlobalChatClient.startIfConfigured(client, identity, this::receiveGlobalEnvelope);
    }

    public void connected() {
        if (ClientPlayNetworking.canSend(ServerHelloPayload.ID)) {
            ClientPlayNetworking.send(new ServerHelloPayload(identity.deviceId(),
                    identity.publicBundle().hpkePublicKey(), identity.publicBundle().signingPublicKey()));
        }
    }

    public void disconnected() {
        serverCapabilities = 0;
        directory.clear();
        incomingCall = null;
        outgoingCall = null;
        activeCall = null;
    }

    public void setServerCapabilities(int serverCapabilities) {
        this.serverCapabilities = serverCapabilities;
    }

    public boolean hasServerCapability(int flag) {
        return (serverCapabilities & flag) != 0;
    }

    public void updateDirectory(ServerDirectoryPayload payload) {
        try {
            List<DirectoryEntry> entries = ProtocolCodec.mapper().readValue(payload.json(), new TypeReference<>() { });
            directory.clear();
            directory.addAll(entries);
        } catch (Exception e) {
            FanDroidMod.LOGGER.warn("Ignored invalid FanDroid server directory", e);
        }
    }

    public void receiveServerEnvelope(ServerEnvelopePayload payload) {
        try {
            MessageEnvelope envelope = ProtocolCodec.read(payload.json().getBytes(StandardCharsets.UTF_8), MessageEnvelope.class);
            if (!replayGuard.accept(envelope.messageId())) {
                ClientPlayNetworking.send(new ServerAckPayload(envelope.messageId()));
                return;
            }
            DirectoryEntry sender = directory.stream()
                    .filter(entry -> entry.keys().deviceId().equals(envelope.senderDeviceId()))
                    .findFirst().orElseThrow();
            byte[] decrypted = EnvelopeCrypto.decrypt(envelope, identity, sender.keys().signingPublicKey());
            ChatMessagePayload message = envelope.eventType() == MessageEventType.CREATE
                    || envelope.eventType() == MessageEventType.EDIT
                    ? ProtocolCodec.read(decrypted, ChatMessagePayload.class) : null;
            chatStore.ensureConversation(envelope.conversationId(), ChatTransport.SERVER, "DIRECT",
                    envelope.senderUuid().equals(accountId) ? "Sent from another device" : sender.username(), null);
            applyEnvelope(envelope, message, false);
            if (envelope.eventType() == MessageEventType.CREATE && !envelope.senderUuid().equals(accountId)
                    && !chatStore.isMuted(envelope.conversationId()))
                notifyPrivate(ClientNotificationCenter.Kind.SERVER_MESSAGE,
                        Text.translatable("notification.fandroid.new_message").getString(),
                        Text.translatable("notification.fandroid.server_from", sender.username()).getString());
            ClientPlayNetworking.send(new ServerAckPayload(envelope.messageId()));
        } catch (Exception e) {
            FanDroidMod.LOGGER.warn("Could not decrypt server envelope", e);
        }
    }

    private void receiveGlobalEnvelope(GlobalChatClient.DecryptedGlobalEnvelope decrypted) {
        MessageEnvelope envelope = decrypted.envelope();
        chatStore.ensureConversation(envelope.conversationId(), ChatTransport.GLOBAL, "DIRECT",
                envelope.senderUuid().equals(accountId) ? "Sent from another device" : decrypted.senderName(), null);
        applyEnvelope(envelope, decrypted.message(), false);
        if (envelope.eventType() == MessageEventType.CREATE && !envelope.senderUuid().equals(accountId)
                && !chatStore.isMuted(envelope.conversationId()))
            notifyPrivate(ClientNotificationCenter.Kind.GLOBAL_MESSAGE,
                    Text.translatable("notification.fandroid.new_message").getString(),
                    Text.translatable("notification.fandroid.global_from", decrypted.senderName()).getString());
    }

    public UUID sendServerDirect(DirectoryEntry recipient, UUID conversationId, String text, UUID replyTo) {
        return sendServerPayload(recipient, conversationId,
                MessageEventType.CREATE, null,
                new ChatMessagePayload(text, replyTo, null, Instant.now()));
    }

    public UUID sendServerVoice(DirectoryEntry recipient, UUID conversationId, VoiceAttachment voice) {
        return sendServerPayload(recipient, conversationId,
                MessageEventType.CREATE, null,
                new ChatMessagePayload(null, null, voice, Instant.now()));
    }

    public UUID editServerMessage(DirectoryEntry recipient, UUID conversationId, UUID target, String text) {
        return sendServerPayload(recipient, conversationId, MessageEventType.EDIT, target,
                new ChatMessagePayload(text, null, null, Instant.now()));
    }

    public UUID deleteServerMessage(DirectoryEntry recipient, UUID conversationId, UUID target) {
        return sendServerPayload(recipient, conversationId, MessageEventType.DELETE, target, null);
    }

    public UUID sendServerReadReceipt(DirectoryEntry recipient, UUID conversationId, UUID target) {
        return sendServerPayload(recipient, conversationId, MessageEventType.READ_RECEIPT, target, null);
    }

    private UUID sendServerPayload(DirectoryEntry recipient, UUID conversationId, MessageEventType eventType,
                                   UUID targetMessageId, ChatMessagePayload message) {
        if (!hasServerCapability(CapabilityFlags.SERVER_CHAT)) {
            throw new IllegalStateException("this server does not support Relay chat");
        }
        Instant now = message == null ? Instant.now() : message.clientCreatedAt();
        UUID messageId = UuidV7.randomUuid();
        MessageEnvelope envelope = EnvelopeCrypto.encrypt(
                new EnvelopeMetadata(messageId, conversationId, accountId, ChatTransport.SERVER,
                        eventType, targetMessageId, now, now.plus(Duration.ofDays(7))),
                message == null ? ProtocolCodec.write(java.util.Map.of("control", eventType.name())) : ProtocolCodec.write(message), identity,
                List.of(recipient.keys(), identity.publicBundle()));
        ClientPlayNetworking.send(new ServerEnvelopePayload(
                new String(ProtocolCodec.write(envelope), StandardCharsets.UTF_8)));
        chatStore.ensureConversation(conversationId, ChatTransport.SERVER, "DIRECT", recipient.username(), null);
        applyEnvelope(envelope, message, true);
        return messageId;
    }

    public void receiveCallSignal(CallSignalPayload payload) {
        try {
            var node = ProtocolCodec.mapper().readTree(payload.json());
            String type = node.path("type").asText();
            if (type.equals("RING")) {
                if (activeCall != null || outgoingCall != null) {
                    ClientPlayNetworking.send(new CallSignalPayload(payload.peerId(), "{\"type\":\"BUSY\"}"));
                    return;
                }
                String name = directory.stream().filter(entry -> entry.playerId().equals(payload.peerId()))
                        .map(DirectoryEntry::username).findFirst().orElse("Unknown caller");
                incomingCall = new IncomingCall(payload.peerId(), name, Instant.now().plusSeconds(30));
                notifyPrivate(ClientNotificationCenter.Kind.INCOMING_CALL,
                        Text.translatable("notification.fandroid.incoming_call").getString(),
                        Text.translatable("notification.fandroid.call_from", name).getString());
            } else if (type.equals("ANSWER")) {
                String name = directory.stream().filter(entry -> entry.playerId().equals(payload.peerId()))
                        .map(DirectoryEntry::username).findFirst().orElse("Call");
                activeCall = new ActiveCall(payload.peerId(), name, Instant.now(), false);
                incomingCall = null;
                notifications.markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
                outgoingCall = null;
                notifyPrivate(ClientNotificationCenter.Kind.CALL_STATUS,
                        Text.translatable("notification.fandroid.call_connected").getString(), name);
            } else if (type.equals("BUSY")) {
                String name = peerName(payload.peerId());
                outgoingCall = null;
                incomingCall = null;
                if (activeCall == null || activeCall.playerId().equals(payload.peerId())) activeCall = null;
                notifyPrivate(ClientNotificationCenter.Kind.CALL_STATUS,
                        Text.translatable("notification.fandroid.call_busy").getString(), name);
            } else if (type.equals("MISSED")) {
                String name = peerName(payload.peerId());
                outgoingCall = null;
                notifyPrivate(ClientNotificationCenter.Kind.MISSED_CALL,
                        Text.translatable("notification.fandroid.no_answer").getString(), name);
            } else if (type.equals("CANCEL")) {
                incomingCall = null;
                notifications.markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
            } else if (type.equals("HANG_UP")) {
                String name = peerName(payload.peerId());
                activeCall = null;
                notifyPrivate(ClientNotificationCenter.Kind.CALL_STATUS,
                        Text.translatable("notification.fandroid.call_ended").getString(), name);
            }
        } catch (Exception ignored) {
        }
    }

    public void ring(UUID playerId) {
        if (!hasServerCapability(CapabilityFlags.CALLS)) throw new IllegalStateException("calls are unavailable");
        if (activeCall == null) {
            outgoingCall = new OutgoingCall(playerId, peerName(playerId), Instant.now(), Instant.now().plusSeconds(30));
        }
        ClientPlayNetworking.send(new CallSignalPayload(playerId, "{\"type\":\"RING\"}"));
    }

    public void cancelOutgoingCall() {
        if (outgoingCall == null) return;
        ClientPlayNetworking.send(new CallSignalPayload(outgoingCall.playerId(), "{\"type\":\"CANCEL\"}"));
        outgoingCall = null;
    }

    public void answerCall() {
        if (incomingCall == null) return;
        IncomingCall answered = incomingCall;
        ClientPlayNetworking.send(new CallSignalPayload(answered.playerId(), "{\"type\":\"ANSWER\"}"));
        activeCall = new ActiveCall(answered.playerId(), answered.username(), Instant.now(), false);
        incomingCall = null;
        notifications.markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
    }

    public void rejectCall() {
        if (incomingCall == null) return;
        ClientPlayNetworking.send(new CallSignalPayload(incomingCall.playerId(), "{\"type\":\"BUSY\"}"));
        incomingCall = null;
        notifications.markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
    }

    public void toggleMute() {
        if (activeCall == null) return;
        activeCall = new ActiveCall(activeCall.playerId(), activeCall.username(), activeCall.startedAt(), !activeCall.muted());
        ClientPlayNetworking.send(new CallSignalPayload(activeCall.playerId(),
                "{\"type\":\"MUTE\",\"muted\":" + activeCall.muted() + "}"));
    }

    public void hangUp() {
        if (activeCall == null) return;
        ClientPlayNetworking.send(new CallSignalPayload(activeCall.playerId(), "{\"type\":\"HANG_UP\"}"));
        activeCall = null;
    }

    private void applyEnvelope(MessageEnvelope envelope, ChatMessagePayload message, boolean outgoing) {
        switch (envelope.eventType()) {
            case CREATE -> chatStore.createMessage(envelope.messageId(), envelope.conversationId(),
                    envelope.senderUuid(), message.voice() == null ? message.text()
                            : "Voice message • " + Math.max(1, message.voice().durationMillis() / 1000) + "s",
                    message.replyTo(), envelope.createdAt(), outgoing,
                    message.voice() == null ? null : ProtocolCodec.write(message.voice()));
            case EDIT -> chatStore.editMessage(envelope.targetMessageId(), envelope.senderUuid(), message.text(), envelope.createdAt());
            case DELETE -> chatStore.deleteMessage(envelope.targetMessageId(), envelope.senderUuid());
            case READ_RECEIPT -> { }
        }
    }

    private String peerName(UUID playerId) {
        return directory.stream().filter(entry -> entry.playerId().equals(playerId))
                .map(DirectoryEntry::username).findFirst().orElse("Call");
    }

    private void notifyPrivate(ClientNotificationCenter.Kind kind, String title, String body) {
        notifications.push(kind, title, body);
    }

    public List<DirectoryEntry> directory() {
        return new ArrayList<>(directory);
    }

    public ClientPreferences preferences() { return preferences; }
    public DeviceIdentity identity() { return identity; }
    public LocalChatStore chatStore() { return chatStore; }
    public UUID accountId() { return accountId; }
    public GlobalChatClient globalChat() { return globalChat; }
    public ClientNotificationCenter notifications() { return notifications; }
    public IncomingCall incomingCall() {
        if (incomingCall != null && incomingCall.expiresAt().isBefore(Instant.now())) {
            String name = incomingCall.username();
            ClientPlayNetworking.send(new CallSignalPayload(incomingCall.playerId(), "{\"type\":\"MISSED\"}"));
            incomingCall = null;
            notifications.markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
            notifyPrivate(ClientNotificationCenter.Kind.MISSED_CALL,
                    Text.translatable("notification.fandroid.missed_call").getString(), name);
        }
        return incomingCall;
    }
    public OutgoingCall outgoingCall() {
        if (outgoingCall != null && outgoingCall.expiresAt().isBefore(Instant.now())) {
            String name = outgoingCall.username();
            ClientPlayNetworking.send(new CallSignalPayload(outgoingCall.playerId(), "{\"type\":\"CANCEL\"}"));
            outgoingCall = null;
            notifyPrivate(ClientNotificationCenter.Kind.MISSED_CALL,
                    Text.translatable("notification.fandroid.no_answer").getString(), name);
        }
        return outgoingCall;
    }
    public void tick() {
        incomingCall();
        outgoingCall();
    }
    public ActiveCall activeCall() { return activeCall; }

    @Override
    public void close() {
        preferences.save();
        if (globalChat != null) globalChat.close();
        if (chatStore != null) chatStore.close();
    }

    public record DirectoryEntry(UUID playerId, String username, DeviceKeyBundle keys) { }
    public record IncomingCall(UUID playerId, String username, Instant expiresAt) { }
    public record OutgoingCall(UUID playerId, String username, Instant startedAt, Instant expiresAt) { }
    public record ActiveCall(UUID playerId, String username, Instant startedAt, boolean muted) { }
}
