package dev.fandroid.backend;

import dev.fandroid.backend.ApiModels.ApiError;
import dev.fandroid.backend.ApiModels.ContactRequest;
import dev.fandroid.backend.ApiModels.ConversationRequest;
import dev.fandroid.backend.ApiModels.IdResponse;
import dev.fandroid.backend.ApiModels.MemberRequest;
import dev.fandroid.backend.auth.AuthModels.ChallengeRequest;
import dev.fandroid.backend.auth.AuthModels.CompleteRequest;
import dev.fandroid.backend.auth.AuthModels.Principal;
import dev.fandroid.backend.auth.AuthService;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.ProtocolCodec;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackendServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendServer.class);
    private static final String PRINCIPAL = "fandroid.principal";
    private final BackendConfig config;
    private final BackendRepository repository;
    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final BackendMetrics metrics = new BackendMetrics();
    private final WebSocketHub webSockets = new WebSocketHub();
    private final Javalin app;

    public BackendServer(BackendConfig config, BackendRepository repository, AuthService auth) {
        this.config = config;
        this.repository = repository;
        this.auth = auth;
        this.rateLimiter = new RateLimiter(config.requestsPerMinute());
        this.app = Javalin.create(javalin -> {
            javalin.showJavalinBanner = false;
            javalin.useVirtualThreads = true;
            javalin.jsonMapper(new JavalinJackson(ProtocolCodec.mapper(), true));
        });
        routes();
    }

    public BackendServer start() {
        app.start(config.port());
        return this;
    }

    public int port() {
        return app.port();
    }

    private void routes() {
        app.before(ctx -> {
            metrics.request();
            String key = AuthService.bearerValue(ctx.header("Authorization"));
            if (key == null) key = ctx.ip();
            if (!rateLimiter.allow(key)) {
                ctx.header("Retry-After", "60").status(HttpStatus.TOO_MANY_REQUESTS)
                        .json(new ApiError("RATE_LIMITED", "too many requests"));
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/health", ctx -> {
            boolean healthy = repository.healthy();
            ctx.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                    .json(Map.of("status", healthy ? "ok" : "unhealthy"));
        });
        app.get("/metrics", ctx -> ctx.contentType("text/plain; version=0.0.4").result(metrics.prometheus()));

        app.post("/v1/auth/challenge", ctx -> {
            ChallengeRequest request = ctx.bodyAsClass(ChallengeRequest.class);
            ctx.json(auth.challenge(request.username()));
        });
        app.post("/v1/auth/complete", ctx -> ctx.json(auth.complete(ctx.bodyAsClass(CompleteRequest.class))));

        app.before("/v1/*", ctx -> {
            if (ctx.path().startsWith("/v1/auth/")) return;
            String token = AuthService.bearerValue(ctx.header("Authorization"));
            Principal principal = auth.authenticate(token).orElse(null);
            if (principal == null) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(new ApiError("UNAUTHORIZED", "a valid bearer token is required"));
                ctx.skipRemainingHandlers();
            } else {
                ctx.attribute(PRINCIPAL, principal);
            }
        });

        app.get("/v1/contacts", ctx -> ctx.json(repository.contacts(principal(ctx).accountId())));
        app.post("/v1/contacts/requests", ctx -> {
            repository.requestContact(principal(ctx).accountId(), ctx.bodyAsClass(ContactRequest.class).accountId());
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.post("/v1/contacts/requests/{accountId}/accept", ctx -> {
            repository.acceptContact(principal(ctx).accountId(), UUID.fromString(ctx.pathParam("accountId")));
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.post("/v1/blocks", ctx -> {
            repository.block(principal(ctx).accountId(), ctx.bodyAsClass(ContactRequest.class).accountId());
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.get("/v1/accounts/{accountId}/devices", ctx -> ctx.json(repository.devicesFor(
                principal(ctx).accountId(), UUID.fromString(ctx.pathParam("accountId")))));
        app.get("/v1/accounts/search", ctx -> ctx.json(repository.searchAccounts(
                principal(ctx).accountId(), ctx.queryParam("username"))));

        app.get("/v1/conversations", ctx -> ctx.json(repository.conversations(principal(ctx).accountId())));
        app.get("/v1/conversations/invites", ctx -> ctx.json(repository.groupInvites(principal(ctx).accountId())));
        app.post("/v1/conversations", ctx -> {
            ConversationRequest request = ctx.bodyAsClass(ConversationRequest.class);
            UUID id = repository.createConversation(principal(ctx).accountId(), request.kind(),
                    request.title(), request.members() == null ? Set.of() : request.members());
            ctx.status(HttpStatus.CREATED).json(new IdResponse(id));
        });
        app.post("/v1/conversations/{conversationId}/members", ctx -> {
            repository.addGroupMember(principal(ctx).accountId(), UUID.fromString(ctx.pathParam("conversationId")),
                    ctx.bodyAsClass(MemberRequest.class).accountId());
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.post("/v1/conversations/{conversationId}/invites/accept", ctx -> {
            repository.acceptGroupInvite(principal(ctx).accountId(), UUID.fromString(ctx.pathParam("conversationId")));
            ctx.status(HttpStatus.NO_CONTENT);
        });
        app.delete("/v1/conversations/{conversationId}/members/{accountId}", ctx -> {
            repository.removeGroupMember(principal(ctx).accountId(), UUID.fromString(ctx.pathParam("conversationId")),
                    UUID.fromString(ctx.pathParam("accountId")));
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.post("/v1/messages", ctx -> {
            MessageEnvelope envelope = ctx.bodyAsClass(MessageEnvelope.class);
            validateEnvelopeLifetime(envelope);
            Set<UUID> targetDevices = repository.storeEnvelope(principal(ctx), envelope, 96 * 1024);
            targetDevices.forEach(device -> webSockets.pending(device, envelope.messageId()));
            metrics.envelope();
            ctx.status(HttpStatus.ACCEPTED).json(new IdResponse(envelope.messageId()));
        });
        app.get("/v1/messages/pending", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
            ctx.json(repository.pending(principal(ctx).deviceId(), limit));
        });
        app.post("/v1/messages/{messageId}/ack", ctx -> {
            repository.acknowledge(principal(ctx).deviceId(), UUID.fromString(ctx.pathParam("messageId")));
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.ws("/v1/ws", ws -> {
            ws.onConnect(ctx -> {
                String token = AuthService.bearerValue(ctx.header("Authorization"));
                if (token == null) token = ctx.queryParam("token");
                Principal principal = auth.authenticate(token).orElse(null);
                if (principal == null) {
                    ctx.closeSession(4401, "unauthorized");
                    return;
                }
                ctx.attribute(PRINCIPAL, principal);
                ctx.enableAutomaticPings();
                webSockets.add(principal.deviceId(), ctx);
                metrics.websocketConnected();
                ctx.send("{\"type\":\"READY\"}");
            });
            ws.onMessage(ctx -> {
                if ("PING".equals(ctx.message())) ctx.send("PONG");
            });
            ws.onClose(ctx -> {
                Principal principal = ctx.attribute(PRINCIPAL);
                if (principal != null) {
                    webSockets.remove(principal.deviceId(), ctx);
                    metrics.websocketDisconnected();
                }
            });
        });

        app.exception(IllegalArgumentException.class, (error, ctx) ->
                ctx.status(HttpStatus.BAD_REQUEST).json(new ApiError("INVALID_REQUEST", error.getMessage())));
        app.exception(IllegalStateException.class, (error, ctx) ->
                ctx.status(HttpStatus.CONFLICT).json(new ApiError("CONFLICT", error.getMessage())));
        app.exception(Exception.class, (error, ctx) -> {
            LOGGER.error("Unhandled backend request failure", error);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new ApiError("INTERNAL", "internal server error"));
        });
    }

    private void validateEnvelopeLifetime(MessageEnvelope envelope) {
        Instant now = Instant.now();
        if (envelope.createdAt().isAfter(now.plusSeconds(60)) || envelope.createdAt().isBefore(now.minusSeconds(300))) {
            throw new IllegalArgumentException("createdAt is outside the accepted clock window");
        }
        if (envelope.expiresAt().isAfter(envelope.createdAt().plus(config.envelopeRetention()))
                || !envelope.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("invalid envelope expiry");
        }
    }

    private static Principal principal(Context ctx) {
        Principal value = ctx.attribute(PRINCIPAL);
        if (value == null) throw new IllegalStateException("authenticated principal missing");
        return value;
    }

    @Override
    public void close() {
        app.stop();
    }
}
