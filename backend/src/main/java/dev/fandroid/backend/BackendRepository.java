package dev.fandroid.backend;

import dev.fandroid.backend.auth.AuthModels.Principal;
import dev.fandroid.protocol.ConversationKind;
import dev.fandroid.protocol.DeviceKeyBundle;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.ProtocolCodec;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BackendRepository {
    private final DataSource dataSource;

    public BackendRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean healthy() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.executeQuery();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public void upsertAccountAndDevice(UUID accountId, String username, DeviceKeyBundle device) {
        inTransaction(connection -> {
            try (PreparedStatement account = connection.prepareStatement("""
                    INSERT INTO accounts(minecraft_uuid, username) VALUES (?, ?)
                    ON CONFLICT (minecraft_uuid) DO UPDATE SET username = excluded.username, updated_at = now()
                    """)) {
                account.setObject(1, accountId);
                account.setString(2, username);
                account.executeUpdate();
            }
            try (PreparedStatement key = connection.prepareStatement("""
                    INSERT INTO devices(device_id, account_id, hpke_public_key, signing_public_key)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (device_id) DO UPDATE SET
                        hpke_public_key = excluded.hpke_public_key,
                        signing_public_key = excluded.signing_public_key,
                        revoked_at = NULL
                    WHERE devices.account_id = excluded.account_id
                    """)) {
                key.setObject(1, device.deviceId());
                key.setObject(2, accountId);
                key.setBytes(3, device.hpkePublicKey());
                key.setBytes(4, device.signingPublicKey());
                if (key.executeUpdate() != 1) {
                    throw new IllegalStateException("device belongs to a different account");
                }
            }
            return null;
        });
    }

    public void createSession(byte[] tokenHash, UUID accountId, UUID deviceId, Instant expiresAt) {
        execute("INSERT INTO sessions(token_hash, account_id, device_id, expires_at) VALUES (?, ?, ?, ?)",
                statement -> {
                    statement.setBytes(1, tokenHash);
                    statement.setObject(2, accountId);
                    statement.setObject(3, deviceId);
                    statement.setObject(4, expiresAt);
                });
    }

    public Optional<Principal> findSession(byte[] tokenHash) {
        return queryOne("""
                        SELECT account_id, device_id FROM sessions
                        WHERE token_hash = ? AND expires_at > now()
                        """,
                statement -> statement.setBytes(1, tokenHash),
                result -> new Principal(result.getObject(1, UUID.class), result.getObject(2, UUID.class)));
    }

    public List<DeviceKeyBundle> devicesFor(UUID requester, UUID accountId) {
        if (!requester.equals(accountId) && !areAcceptedContacts(requester, accountId)) {
            return List.of();
        }
        return queryMany("""
                        SELECT device_id, hpke_public_key, signing_public_key FROM devices
                        WHERE account_id = ? AND revoked_at IS NULL
                        """,
                statement -> statement.setObject(1, accountId),
                result -> new DeviceKeyBundle(result.getObject(1, UUID.class), result.getBytes(2), result.getBytes(3)));
    }

    public void requestContact(UUID requester, UUID addressee) {
        if (requester.equals(addressee)) throw new IllegalArgumentException("cannot add yourself");
        ensureAccountExists(addressee);
        if (isBlockedEitherWay(requester, addressee)) throw new IllegalStateException("contact request is blocked");
        execute("""
                        INSERT INTO contacts(requester_id, addressee_id, status) VALUES (?, ?, 'PENDING')
                        ON CONFLICT (requester_id, addressee_id) DO UPDATE SET updated_at = now()
                        """,
                statement -> {
                    statement.setObject(1, requester);
                    statement.setObject(2, addressee);
                });
    }

    public void acceptContact(UUID acceptingUser, UUID requester) {
        int changed = execute("""
                        UPDATE contacts SET status = 'ACCEPTED', updated_at = now()
                        WHERE requester_id = ? AND addressee_id = ? AND status = 'PENDING'
                        """,
                statement -> {
                    statement.setObject(1, requester);
                    statement.setObject(2, acceptingUser);
                });
        if (changed != 1) throw new IllegalStateException("pending contact request not found");
    }

    public List<ContactRow> contacts(UUID accountId) {
        return queryMany("""
                        SELECT c.requester_id, c.addressee_id, c.status,
                               a.minecraft_uuid, a.username
                        FROM contacts c
                        JOIN accounts a ON a.minecraft_uuid = CASE
                            WHEN c.requester_id = ? THEN c.addressee_id ELSE c.requester_id END
                        WHERE c.requester_id = ? OR c.addressee_id = ?
                        ORDER BY a.username
                        """,
                statement -> {
                    statement.setObject(1, accountId);
                    statement.setObject(2, accountId);
                    statement.setObject(3, accountId);
                },
                result -> new ContactRow(
                        result.getObject(4, UUID.class), result.getString(5), result.getString(3),
                        result.getObject(1, UUID.class).equals(accountId)));
    }

    public List<AccountRow> searchAccounts(UUID requester, String query) {
        if (query == null || query.length() < 2 || query.length() > 16) return List.of();
        return queryMany("""
                        SELECT a.minecraft_uuid, a.username FROM accounts a
                        WHERE a.minecraft_uuid <> ? AND lower(a.username) LIKE lower(?)
                          AND NOT EXISTS (
                            SELECT 1 FROM blocks b WHERE
                            (b.blocker_id = ? AND b.blocked_id = a.minecraft_uuid) OR
                            (b.blocker_id = a.minecraft_uuid AND b.blocked_id = ?)
                          )
                        ORDER BY CASE WHEN lower(a.username) = lower(?) THEN 0 ELSE 1 END, a.username
                        LIMIT 20
                        """,
                statement -> {
                    statement.setObject(1, requester);
                    statement.setString(2, query + "%");
                    statement.setObject(3, requester);
                    statement.setObject(4, requester);
                    statement.setString(5, query);
                }, result -> new AccountRow(result.getObject(1, UUID.class), result.getString(2)));
    }

    public void block(UUID blocker, UUID blocked) {
        if (blocker.equals(blocked)) throw new IllegalArgumentException("cannot block yourself");
        inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
                insert.setObject(1, blocker);
                insert.setObject(2, blocked);
                insert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM contacts WHERE
                    (requester_id = ? AND addressee_id = ?) OR
                    (requester_id = ? AND addressee_id = ?)
                    """)) {
                delete.setObject(1, blocker);
                delete.setObject(2, blocked);
                delete.setObject(3, blocked);
                delete.setObject(4, blocker);
                delete.executeUpdate();
            }
            return null;
        });
    }

    public UUID createConversation(UUID owner, ConversationKind kind, String title, Set<UUID> requestedMembers) {
        Set<UUID> requested = new HashSet<>(requestedMembers);
        Set<UUID> members = new HashSet<>(requested);
        members.add(owner);
        if (kind == ConversationKind.DIRECT) {
            if (members.size() != 2) throw new IllegalArgumentException("a direct conversation needs two members");
            UUID other = members.stream().filter(id -> !id.equals(owner)).findFirst().orElseThrow();
            if (!areAcceptedContacts(owner, other)) throw new IllegalStateException("direct messages require an accepted contact");
        }
        if (kind == ConversationKind.GROUP && members.size() < 2) {
            throw new IllegalArgumentException("a group needs at least two members");
        }
        members.forEach(this::ensureAccountExists);
        if (kind == ConversationKind.GROUP) members = Set.of(owner);
        Set<UUID> finalMembers = members;
        UUID id = dev.fandroid.protocol.UuidV7.randomUuid();
        inTransaction(connection -> {
            try (PreparedStatement conversation = connection.prepareStatement("""
                    INSERT INTO conversations(conversation_id, kind, title, owner_id) VALUES (?, ?, ?, ?)
                    """)) {
                conversation.setObject(1, id);
                conversation.setString(2, kind.name());
                conversation.setString(3, title);
                conversation.setObject(4, kind == ConversationKind.GROUP ? owner : null);
                conversation.executeUpdate();
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO conversation_members(conversation_id, account_id) VALUES (?, ?)
                    """)) {
                for (UUID account : finalMembers) {
                    member.setObject(1, id);
                    member.setObject(2, account);
                    member.addBatch();
                }
                member.executeBatch();
            }
            if (kind == ConversationKind.GROUP && !requested.isEmpty()) {
                try (PreparedStatement invite = connection.prepareStatement("""
                        INSERT INTO group_invites(conversation_id, account_id, invited_by)
                        VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                        """)) {
                    for (UUID account : requested) {
                        if (account.equals(owner)) continue;
                        invite.setObject(1, id);
                        invite.setObject(2, account);
                        invite.setObject(3, owner);
                        invite.addBatch();
                    }
                    invite.executeBatch();
                }
            }
            return null;
        });
        return id;
    }

    public void addGroupMember(UUID actor, UUID conversationId, UUID accountId) {
        requireOwner(actor, conversationId);
        ensureAccountExists(accountId);
        execute("INSERT INTO group_invites(conversation_id, account_id, invited_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setObject(2, accountId);
                    statement.setObject(3, actor);
                });
    }

    public void acceptGroupInvite(UUID accountId, UUID conversationId) {
        inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM group_invites WHERE conversation_id = ? AND account_id = ?
                    """)) {
                delete.setObject(1, conversationId);
                delete.setObject(2, accountId);
                if (delete.executeUpdate() != 1) throw new IllegalStateException("group invite not found");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO conversation_members(conversation_id, account_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
                insert.setObject(1, conversationId);
                insert.setObject(2, accountId);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public void removeGroupMember(UUID actor, UUID conversationId, UUID accountId) {
        boolean actorIsOwner = isOwner(actor, conversationId);
        if (!actor.equals(accountId) && !actorIsOwner) throw new IllegalStateException("only the owner can remove another member");
        inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM conversation_members WHERE conversation_id = ? AND account_id = ?
                    """)) {
                delete.setObject(1, conversationId);
                delete.setObject(2, accountId);
                if (delete.executeUpdate() != 1) throw new IllegalStateException("member not found");
            }
            try (PreparedStatement transfer = connection.prepareStatement("""
                    UPDATE conversations SET owner_id = (
                        SELECT account_id FROM conversation_members
                        WHERE conversation_id = ? ORDER BY joined_at, account_id LIMIT 1
                    ) WHERE conversation_id = ? AND owner_id = ?
                    """)) {
                transfer.setObject(1, conversationId);
                transfer.setObject(2, conversationId);
                transfer.setObject(3, accountId);
                transfer.executeUpdate();
            }
            return null;
        });
    }

    public List<ConversationRow> conversations(UUID accountId) {
        return queryMany("""
                        SELECT c.conversation_id, c.kind, c.title, c.owner_id, c.created_at
                        FROM conversations c JOIN conversation_members m USING(conversation_id)
                        WHERE m.account_id = ? ORDER BY c.created_at DESC
                        """,
                statement -> statement.setObject(1, accountId),
                result -> new ConversationRow(result.getObject(1, UUID.class),
                        ConversationKind.valueOf(result.getString(2)), result.getString(3),
                        result.getObject(4, UUID.class), result.getObject(5, java.time.OffsetDateTime.class).toInstant()));
    }

    public List<GroupInviteRow> groupInvites(UUID accountId) {
        return queryMany("""
                        SELECT i.conversation_id, c.title, i.invited_by, a.username, i.created_at
                        FROM group_invites i
                        JOIN conversations c USING(conversation_id)
                        JOIN accounts a ON a.minecraft_uuid = i.invited_by
                        WHERE i.account_id = ? ORDER BY i.created_at DESC
                        """,
                statement -> statement.setObject(1, accountId),
                result -> new GroupInviteRow(result.getObject(1, UUID.class), result.getString(2),
                        result.getObject(3, UUID.class), result.getString(4),
                        result.getObject(5, java.time.OffsetDateTime.class).toInstant()));
    }

    public Set<UUID> storeEnvelope(Principal principal, MessageEnvelope envelope, int maxBytes) {
        byte[] json = ProtocolCodec.write(envelope);
        if (json.length > maxBytes) throw new IllegalArgumentException("envelope exceeds size limit");
        if (!envelope.senderUuid().equals(principal.accountId())
                || !envelope.senderDeviceId().equals(principal.deviceId())) {
            throw new IllegalStateException("sender does not match authenticated device");
        }
        Set<UUID> deliveryDevices = envelope.recipients().stream().map(value -> value.deviceId()).collect(java.util.stream.Collectors.toSet());
        return inTransaction(connection -> {
            if (!isMember(connection, envelope.conversationId(), principal.accountId())) {
                throw new IllegalStateException("sender is not a conversation member");
            }
            validateEvent(connection, principal, envelope);
            for (UUID deviceId : deliveryDevices) {
                if (!deviceIsConversationMember(connection, deviceId, envelope.conversationId())) {
                    throw new IllegalStateException("recipient device is not in the conversation");
                }
            }
            try (PreparedStatement metadata = connection.prepareStatement("""
                    INSERT INTO message_metadata(message_id, conversation_id, sender_id, event_type, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                metadata.setObject(1, envelope.messageId());
                metadata.setObject(2, envelope.conversationId());
                metadata.setObject(3, envelope.senderUuid());
                metadata.setString(4, envelope.eventType().name());
                metadata.setObject(5, envelope.createdAt());
                metadata.setObject(6, envelope.expiresAt());
                metadata.executeUpdate();
            }
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(new String(json, java.nio.charset.StandardCharsets.UTF_8));
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO envelopes(message_id, conversation_id, sender_id, sender_device_id,
                        event_type, envelope_json, byte_size, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setObject(1, envelope.messageId());
                insert.setObject(2, envelope.conversationId());
                insert.setObject(3, envelope.senderUuid());
                insert.setObject(4, envelope.senderDeviceId());
                insert.setString(5, envelope.eventType().name());
                insert.setObject(6, jsonb);
                insert.setInt(7, json.length);
                insert.setObject(8, envelope.createdAt());
                insert.setObject(9, envelope.expiresAt());
                insert.executeUpdate();
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO deliveries(message_id, device_id) VALUES (?, ?)
                    """)) {
                for (UUID deviceId : deliveryDevices) {
                    delivery.setObject(1, envelope.messageId());
                    delivery.setObject(2, deviceId);
                    delivery.addBatch();
                }
                delivery.executeBatch();
            }
            return deliveryDevices;
        });
    }

    public List<MessageEnvelope> pending(UUID deviceId, int limit) {
        return queryMany("""
                        SELECT e.envelope_json::text FROM envelopes e
                        JOIN deliveries d USING(message_id)
                        WHERE d.device_id = ? AND d.acknowledged_at IS NULL AND e.expires_at > now()
                        ORDER BY e.created_at LIMIT ?
                        """,
                statement -> {
                    statement.setObject(1, deviceId);
                    statement.setInt(2, Math.min(Math.max(limit, 1), 500));
                },
                result -> ProtocolCodec.read(result.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8), MessageEnvelope.class));
    }

    public void acknowledge(UUID deviceId, UUID messageId) {
        inTransaction(connection -> {
            try (PreparedStatement ack = connection.prepareStatement("""
                    UPDATE deliveries SET acknowledged_at = now()
                    WHERE device_id = ? AND message_id = ?
                    """)) {
                ack.setObject(1, deviceId);
                ack.setObject(2, messageId);
                if (ack.executeUpdate() != 1) throw new IllegalStateException("delivery not found");
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM envelopes e WHERE e.message_id = ? AND NOT EXISTS (
                        SELECT 1 FROM deliveries d
                        WHERE d.message_id = e.message_id AND d.acknowledged_at IS NULL
                    )
                    """)) {
                delete.setObject(1, messageId);
                delete.executeUpdate();
            }
            return null;
        });
    }

    public int deleteExpired() {
        return inTransaction(connection -> {
            int deleted;
            try (PreparedStatement envelopes = connection.prepareStatement("DELETE FROM envelopes WHERE expires_at <= now()")) {
                deleted = envelopes.executeUpdate();
            }
            try (PreparedStatement metadata = connection.prepareStatement("DELETE FROM message_metadata WHERE expires_at <= now()")) {
                metadata.executeUpdate();
            }
            return deleted;
        });
    }

    private void ensureAccountExists(UUID accountId) {
        if (queryOne("SELECT minecraft_uuid FROM accounts WHERE minecraft_uuid = ?",
                statement -> statement.setObject(1, accountId), result -> result.getObject(1, UUID.class)).isEmpty()) {
            throw new IllegalArgumentException("account does not exist");
        }
    }

    private boolean areAcceptedContacts(UUID first, UUID second) {
        return queryOne("""
                        SELECT 1 FROM contacts WHERE status = 'ACCEPTED' AND
                        ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))
                        """,
                statement -> {
                    statement.setObject(1, first);
                    statement.setObject(2, second);
                    statement.setObject(3, second);
                    statement.setObject(4, first);
                }, result -> true).orElse(false);
    }

    private boolean isBlockedEitherWay(UUID first, UUID second) {
        return queryOne("""
                        SELECT 1 FROM blocks WHERE
                        (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
                        """,
                statement -> {
                    statement.setObject(1, first);
                    statement.setObject(2, second);
                    statement.setObject(3, second);
                    statement.setObject(4, first);
                }, result -> true).orElse(false);
    }

    private void requireOwner(UUID actor, UUID conversationId) {
        if (!isOwner(actor, conversationId)) throw new IllegalStateException("only the group owner can do that");
    }

    private boolean isOwner(UUID actor, UUID conversationId) {
        return queryOne("SELECT 1 FROM conversations WHERE conversation_id = ? AND owner_id = ?",
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setObject(2, actor);
                }, result -> true).orElse(false);
    }

    private static boolean isMember(Connection connection, UUID conversationId, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM conversation_members WHERE conversation_id = ? AND account_id = ?
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, accountId);
            return statement.executeQuery().next();
        }
    }

    private static boolean deviceIsConversationMember(Connection connection, UUID deviceId, UUID conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM devices d JOIN conversation_members m ON m.account_id = d.account_id
                WHERE d.device_id = ? AND d.revoked_at IS NULL AND m.conversation_id = ?
                """)) {
            statement.setObject(1, deviceId);
            statement.setObject(2, conversationId);
            return statement.executeQuery().next();
        }
    }

    private static void validateEvent(Connection connection, Principal principal, MessageEnvelope envelope) throws SQLException {
        if (envelope.eventType() == dev.fandroid.protocol.MessageEventType.CREATE) {
            if (envelope.targetMessageId() != null) throw new IllegalArgumentException("CREATE cannot target another envelope");
            return;
        }
        if (envelope.targetMessageId() == null) throw new IllegalArgumentException("message event requires a target");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sender_id, conversation_id, created_at FROM message_metadata WHERE message_id = ?
                """)) {
            statement.setObject(1, envelope.targetMessageId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("target message is not pending on the service");
                UUID originalSender = result.getObject(1, UUID.class);
                UUID conversation = result.getObject(2, UUID.class);
                Instant created = result.getObject(3, java.time.OffsetDateTime.class).toInstant();
                if (!conversation.equals(envelope.conversationId())) throw new IllegalStateException("event target belongs to another conversation");
                if ((envelope.eventType() == dev.fandroid.protocol.MessageEventType.EDIT
                        || envelope.eventType() == dev.fandroid.protocol.MessageEventType.DELETE)
                        && !originalSender.equals(principal.accountId())) {
                    throw new IllegalStateException("only the original sender can modify a message");
                }
                if (envelope.eventType() == dev.fandroid.protocol.MessageEventType.EDIT
                        && created.isBefore(envelope.createdAt().minusSeconds(15 * 60))) {
                    throw new IllegalStateException("the 15 minute edit window has elapsed");
                }
            }
        }
    }

    private int execute(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("database operation failed", e);
        }
    }

    private <T> Optional<T> queryOne(String sql, SqlBinder binder, SqlMapper<T> mapper) {
        List<T> results = queryMany(sql, binder, mapper);
        return results.stream().findFirst();
    }

    private <T> List<T> queryMany(String sql, SqlBinder binder, SqlMapper<T> mapper) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (result.next()) values.add(mapper.map(result));
                return values;
            }
        } catch (SQLException e) {
            throw new RepositoryException("database query failed", e);
        }
    }

    private <T> T inTransaction(SqlTransaction<T> transaction) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = transaction.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new RepositoryException("database transaction failed", e);
            }
        } catch (SQLException e) {
            throw new RepositoryException("database transaction failed", e);
        }
    }

    public record ContactRow(UUID accountId, String username, String status, boolean outgoing) {
    }

    public record ConversationRow(UUID id, ConversationKind kind, String title, UUID ownerId, Instant createdAt) {
    }

    public record AccountRow(UUID accountId, String username) { }
    public record GroupInviteRow(UUID conversationId, String title, UUID invitedBy,
                                 String inviterUsername, Instant createdAt) { }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet result) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTransaction<T> {
        T run(Connection connection) throws Exception;
    }

    public static final class RepositoryException extends RuntimeException {
        RepositoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
