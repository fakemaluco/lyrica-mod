package dev.fandroid.server;

import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.ProtocolCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ServerEnvelopeQueue implements AutoCloseable {
    private final Connection connection;

    ServerEnvelopeQueue(Path path) {
        try {
            Files.createDirectories(path.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS envelopes(
                            message_id TEXT PRIMARY KEY,
                            envelope_json BLOB NOT NULL,
                            expires_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS deliveries(
                            message_id TEXT NOT NULL REFERENCES envelopes(message_id) ON DELETE CASCADE,
                            device_id TEXT NOT NULL,
                            acknowledged INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(message_id, device_id)
                        )
                        """);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not open FanDroid server queue", e);
        }
    }

    synchronized void store(MessageEnvelope envelope) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO envelopes(message_id, envelope_json, expires_at) VALUES (?, ?, ?)")) {
                insert.setString(1, envelope.messageId().toString());
                insert.setBytes(2, ProtocolCodec.write(envelope));
                insert.setLong(3, envelope.expiresAt().toEpochMilli());
                insert.executeUpdate();
            }
            try (PreparedStatement delivery = connection.prepareStatement(
                    "INSERT INTO deliveries(message_id, device_id) VALUES (?, ?)")) {
                for (var recipient : envelope.recipients()) {
                    delivery.setString(1, envelope.messageId().toString());
                    delivery.setString(2, recipient.deviceId().toString());
                    delivery.addBatch();
                }
                delivery.executeBatch();
            }
            connection.commit();
        } catch (Exception e) {
            rollback();
            throw new IllegalStateException("could not queue server envelope", e);
        } finally {
            autoCommit();
        }
    }

    synchronized List<MessageEnvelope> pending(UUID deviceId) {
        purgeExpired();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.envelope_json FROM envelopes e JOIN deliveries d USING(message_id)
                WHERE d.device_id = ? AND d.acknowledged = 0 ORDER BY e.expires_at LIMIT 250
                """)) {
            statement.setString(1, deviceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<MessageEnvelope> values = new ArrayList<>();
                while (result.next()) values.add(ProtocolCodec.read(result.getBytes(1), MessageEnvelope.class));
                return values;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read server queue", e);
        }
    }

    synchronized void acknowledge(UUID deviceId, UUID messageId) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE deliveries SET acknowledged = 1 WHERE device_id = ? AND message_id = ?
                    """)) {
                update.setString(1, deviceId.toString());
                update.setString(2, messageId.toString());
                update.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM envelopes WHERE message_id = ? AND NOT EXISTS (
                      SELECT 1 FROM deliveries WHERE message_id = ? AND acknowledged = 0
                    )
                    """)) {
                delete.setString(1, messageId.toString());
                delete.setString(2, messageId.toString());
                delete.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            rollback();
        } finally {
            autoCommit();
        }
    }

    synchronized void purgeExpired() {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM envelopes WHERE expires_at <= ?")) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void rollback() {
        try { connection.rollback(); } catch (Exception ignored) { }
    }

    private void autoCommit() {
        try { connection.setAutoCommit(true); } catch (Exception ignored) { }
    }

    @Override
    public synchronized void close() {
        try { connection.close(); } catch (Exception ignored) { }
    }
}

