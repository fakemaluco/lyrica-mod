package dev.fandroid.backend;

import dev.fandroid.backend.auth.AuthModels.Principal;
import dev.fandroid.backend.auth.AuthModels.CompleteRequest;
import dev.fandroid.backend.auth.AuthService;
import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.ConversationKind;
import dev.fandroid.protocol.MessageEventType;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.UuidV7;
import dev.fandroid.protocol.crypto.DeviceIdentity;
import dev.fandroid.protocol.crypto.EnvelopeCrypto;
import dev.fandroid.protocol.crypto.EnvelopeMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BackendRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fandroid").withUsername("test").withPassword("test");

    private BackendRepository repository;
    private UUID alice;
    private UUID bob;
    private DeviceIdentity aliceDevice;
    private DeviceIdentity bobDevice;
    private BackendConfig config;

    @BeforeEach
    void setUp() {
        config = new BackendConfig(0, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), Duration.ofDays(7), Duration.ofDays(30), 100);
        Database database = new Database(config);
        database.migrate();
        repository = new BackendRepository(database.dataSource());
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        aliceDevice = DeviceIdentity.generate(UUID.randomUUID());
        bobDevice = DeviceIdentity.generate(UUID.randomUUID());
        repository.upsertAccountAndDevice(alice, "Alice", aliceDevice.publicBundle());
        repository.upsertAccountAndDevice(bob, "Bob", bobDevice.publicBundle());
    }

    @Test
    void authenticatesWithSimulatedMojangProofWithoutReceivingAccessToken() {
        UUID profileId = UUID.randomUUID();
        DeviceIdentity device = DeviceIdentity.generate(UUID.randomUUID());
        AuthService auth = new AuthService(repository,
                (username, serverId) -> java.util.Optional.of(
                        new dev.fandroid.backend.auth.MojangSessionVerifier.VerifiedProfile(profileId, username)), config);
        var challenge = auth.challenge("ProofUser");
        var session = auth.complete(new CompleteRequest(challenge.challengeId(), "ProofUser", device.publicBundle()));

        assertThat(auth.authenticate(session.token())).contains(
                new Principal(profileId, device.deviceId()));
    }

    @Test
    void contactGroupOfflineDeliveryAndAckLifecycle() {
        repository.requestContact(alice, bob);
        repository.acceptContact(bob, alice);
        UUID conversation = repository.createConversation(alice, ConversationKind.DIRECT, null, Set.of(bob));
        Instant now = Instant.now();
        var envelope = EnvelopeCrypto.encrypt(new EnvelopeMetadata(UuidV7.randomUuid(), conversation, alice,
                        ChatTransport.GLOBAL, MessageEventType.CREATE, null, now, now.plus(Duration.ofDays(7))),
                ProtocolCodec.write(java.util.Map.of("text", "encrypted")), aliceDevice,
                List.of(aliceDevice.publicBundle(), bobDevice.publicBundle()));

        repository.storeEnvelope(new Principal(alice, aliceDevice.deviceId()), envelope, 96 * 1024);
        assertThat(repository.pending(bobDevice.deviceId(), 100)).extracting(value -> value.messageId())
                .containsExactly(envelope.messageId());
        repository.acknowledge(bobDevice.deviceId(), envelope.messageId());
        assertThat(repository.pending(bobDevice.deviceId(), 100)).isEmpty();
        assertThat(repository.pending(aliceDevice.deviceId(), 100)).hasSize(1);
        repository.acknowledge(aliceDevice.deviceId(), envelope.messageId());
        assertThat(repository.pending(aliceDevice.deviceId(), 100)).isEmpty();
    }
}
