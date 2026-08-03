package dev.fandroid.protocol.crypto;

import dev.fandroid.protocol.DeviceKeyBundle;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.RecipientKeyEnvelope;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class EnvelopeCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] HPKE_INFO = "fandroid/hpke/content-key/v1".getBytes(StandardCharsets.UTF_8);
    private static final HPKE HPKE_SUITE = new HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_AES_GCM256
    );

    private EnvelopeCrypto() {
    }

    public static MessageEnvelope encrypt(EnvelopeMetadata metadata, byte[] plaintext,
                                          DeviceIdentity sender, List<DeviceKeyBundle> recipients) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient device is required");
        }
        try {
            byte[] contentKey = randomBytes(32);
            byte[] nonce = randomBytes(12);
            byte[] associatedData = associatedData(metadata, sender.deviceId());
            byte[] ciphertext = aesGcm(Cipher.ENCRYPT_MODE, contentKey, nonce, associatedData, plaintext);
            byte[] ciphertextHash = sha256(ciphertext);

            List<DeviceKeyBundle> ordered = recipients.stream()
                    .sorted(Comparator.comparing(bundle -> bundle.deviceId().toString()))
                    .toList();
            List<RecipientKeyEnvelope> wrappedKeys = new ArrayList<>(ordered.size());
            for (DeviceKeyBundle recipient : ordered) {
                HPKEContextWithEncapsulation context = HPKE_SUITE.setupBaseS(
                        HPKE_SUITE.deserializePublicKey(recipient.hpkePublicKey()), HPKE_INFO);
                wrappedKeys.add(new RecipientKeyEnvelope(
                        recipient.deviceId(),
                        context.getEncapsulation(),
                        context.seal(associatedData, contentKey)
                ));
            }

            byte[] signature = sign(sender, signatureMaterial(associatedData, nonce, ciphertext,
                    ciphertextHash, wrappedKeys));
            return new MessageEnvelope(
                    MessageEnvelope.CURRENT_VERSION,
                    metadata.messageId(),
                    metadata.conversationId(),
                    metadata.senderUuid(),
                    sender.deviceId(),
                    metadata.transport(),
                    metadata.eventType(),
                    metadata.targetMessageId(),
                    metadata.createdAt(),
                    metadata.expiresAt(),
                    nonce,
                    ciphertext,
                    ciphertextHash,
                    wrappedKeys,
                    signature
            );
        } catch (GeneralSecurityException | InvalidCipherTextException e) {
            throw new EnvelopeException("could not encrypt envelope", e);
        }
    }

    public static byte[] decrypt(MessageEnvelope envelope, DeviceIdentity recipient,
                                 byte[] senderSigningPublicKey) {
        if (envelope.version() != MessageEnvelope.CURRENT_VERSION) {
            throw new EnvelopeException("unsupported envelope version " + envelope.version());
        }
        EnvelopeMetadata metadata = new EnvelopeMetadata(
                envelope.messageId(), envelope.conversationId(), envelope.senderUuid(),
                envelope.transport(), envelope.eventType(), envelope.targetMessageId(),
                envelope.createdAt(), envelope.expiresAt());
        byte[] associatedData = associatedData(metadata, envelope.senderDeviceId());
        if (!MessageDigest.isEqual(sha256(envelope.ciphertext()), envelope.ciphertextHash())) {
            throw new EnvelopeException("ciphertext hash mismatch");
        }
        byte[] signed = signatureMaterial(associatedData, envelope.nonce(), envelope.ciphertext(),
                envelope.ciphertextHash(), envelope.recipients());
        if (!verify(senderSigningPublicKey, signed, envelope.signature())) {
            throw new EnvelopeException("invalid envelope signature");
        }

        RecipientKeyEnvelope wrapped = envelope.recipients().stream()
                .filter(value -> value.deviceId().equals(recipient.deviceId()))
                .findFirst()
                .orElseThrow(() -> new EnvelopeException("envelope is not addressed to this device"));
        try {
            HPKEContext context = HPKE_SUITE.setupBaseR(
                    wrapped.encapsulation(), recipient.hpkeKeyPair(), HPKE_INFO);
            byte[] contentKey = context.open(associatedData, wrapped.ciphertext());
            return aesGcm(Cipher.DECRYPT_MODE, contentKey, envelope.nonce(), associatedData,
                    envelope.ciphertext());
        } catch (AEADBadTagException e) {
            throw new EnvelopeException("encrypted payload was modified", e);
        } catch (GeneralSecurityException | InvalidCipherTextException e) {
            throw new EnvelopeException("could not decrypt envelope", e);
        }
    }

    private static byte[] associatedData(EnvelopeMetadata metadata, UUID senderDeviceId) {
        return encode(out -> {
            out.writeInt(MessageEnvelope.CURRENT_VERSION);
            writeUuid(out, metadata.messageId());
            writeUuid(out, metadata.conversationId());
            writeUuid(out, metadata.senderUuid());
            writeUuid(out, senderDeviceId);
            out.writeUTF(metadata.transport().name());
            out.writeUTF(metadata.eventType().name());
            writeNullableUuid(out, metadata.targetMessageId());
            out.writeLong(metadata.createdAt().toEpochMilli());
            out.writeLong(metadata.expiresAt().toEpochMilli());
        });
    }

    private static byte[] signatureMaterial(byte[] associatedData, byte[] nonce, byte[] ciphertext,
                                            byte[] hash, List<RecipientKeyEnvelope> recipients) {
        return encode(out -> {
            writeBytes(out, associatedData);
            writeBytes(out, nonce);
            writeBytes(out, ciphertext);
            writeBytes(out, hash);
            out.writeInt(recipients.size());
            for (RecipientKeyEnvelope recipient : recipients) {
                writeUuid(out, recipient.deviceId());
                writeBytes(out, recipient.encapsulation());
                writeBytes(out, recipient.ciphertext());
            }
        });
    }

    private static byte[] sign(DeviceIdentity identity, byte[] bytes) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, identity.signingKey());
        signer.update(bytes, 0, bytes.length);
        return signer.generateSignature();
    }

    private static boolean verify(byte[] publicKey, byte[] bytes, byte[] signature) {
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        verifier.update(bytes, 0, bytes.length);
        return verifier.verifySignature(signature);
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException e) {
            throw new EnvelopeException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeNullableUuid(DataOutputStream out, UUID value) throws Exception {
        out.writeBoolean(value != null);
        if (value != null) {
            writeUuid(out, value);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws Exception {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] encode(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new EnvelopeException("could not create canonical envelope data", e);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws Exception;
    }
}

