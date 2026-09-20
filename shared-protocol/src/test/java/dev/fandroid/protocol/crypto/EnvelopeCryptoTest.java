package dev.fandroid.protocol.crypto;

import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.MessageEventType;
import dev.fandroid.protocol.UuidV7;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeCryptoTest {
    @Test
    void wrapsContentKeyForEveryDeviceAndVerifiesEd25519Signature() {
        DeviceIdentity sender = DeviceIdentity.generate(UUID.randomUUID());
        DeviceIdentity phone = DeviceIdentity.generate(UUID.randomUUID());
        DeviceIdentity laptop = DeviceIdentity.generate(UUID.randomUUID());
        Instant now = Instant.now();
        EnvelopeMetadata metadata = new EnvelopeMetadata(UuidV7.randomUuid(), UuidV7.randomUuid(),
                UUID.randomUUID(), ChatTransport.GLOBAL, MessageEventType.CREATE, null,
                now, now.plus(Duration.ofDays(7)));

        MessageEnvelope envelope = EnvelopeCrypto.encrypt(metadata, "secret hello".getBytes(StandardCharsets.UTF_8),
                sender, List.of(phone.publicBundle(), laptop.publicBundle()));

        assertThat(new String(EnvelopeCrypto.decrypt(envelope, phone,
                sender.publicBundle().signingPublicKey()), StandardCharsets.UTF_8)).isEqualTo("secret hello");
        assertThat(new String(EnvelopeCrypto.decrypt(envelope, laptop,
                sender.publicBundle().signingPublicKey()), StandardCharsets.UTF_8)).isEqualTo("secret hello");
        assertThat(envelope.recipients()).hasSize(2);
    }

    @Test
    void rejectsTamperingAndWrongRecipient() {
        DeviceIdentity sender = DeviceIdentity.generate(UUID.randomUUID());
        DeviceIdentity recipient = DeviceIdentity.generate(UUID.randomUUID());
        DeviceIdentity stranger = DeviceIdentity.generate(UUID.randomUUID());
        Instant now = Instant.now();
        EnvelopeMetadata metadata = new EnvelopeMetadata(UuidV7.randomUuid(), UuidV7.randomUuid(),
                UUID.randomUUID(), ChatTransport.SERVER, MessageEventType.CREATE, null,
                now, now.plusSeconds(10));
        MessageEnvelope original = EnvelopeCrypto.encrypt(metadata, new byte[]{1, 2, 3}, sender,
                List.of(recipient.publicBundle()));
        byte[] changed = original.ciphertext().clone();
        changed[0] ^= 1;
        MessageEnvelope tampered = new MessageEnvelope(original.version(), original.messageId(),
                original.conversationId(), original.senderUuid(), original.senderDeviceId(), original.transport(),
                original.eventType(), original.targetMessageId(), original.createdAt(), original.expiresAt(),
                original.nonce(), changed, original.ciphertextHash(), original.recipients(), original.signature());

        assertThatThrownBy(() -> EnvelopeCrypto.decrypt(tampered, recipient,
                sender.publicBundle().signingPublicKey())).isInstanceOf(EnvelopeException.class);
        assertThatThrownBy(() -> EnvelopeCrypto.decrypt(original, stranger,
                sender.publicBundle().signingPublicKey())).isInstanceOf(EnvelopeException.class);
    }

    @Test
    void replayGuardAcceptsAnIdOnlyOnce() {
        ReplayGuard guard = new ReplayGuard(Duration.ofDays(1));
        UUID id = UuidV7.randomUuid();
        assertThat(guard.accept(id)).isTrue();
        assertThat(guard.accept(id)).isFalse();
    }
}

