package dev.fandroid.client.chat;

import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.MessageEventType;
import net.fabricmc.loader.api.FabricLoader;

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

public final class LocalChatStore implements AutoCloseable {
    private final Connection connection;

    public LocalChatStore(UUID accountId) {
        try {
            Path directory = FabricLoader.getInstance().getConfigDir().resolve("fandroid/cache");
            Files.createDirectories(directory);
            connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(accountId + ".db").toAbsolutePath());
            migrate();
        } catch (Exception e) {
            throw new IllegalStateException("could not open FanDroid local cache", e);
        }
    }

    private void migrate() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version < 1) {
                statement.execute("""
                        CREATE TABLE conversations(
                          conversation_id TEXT PRIMARY KEY,
                          transport TEXT NOT NULL,
                          kind TEXT NOT NULL,
                          title TEXT NOT NULL,
                          owner_id TEXT,
                          muted INTEGER NOT NULL DEFAULT 0,
                          unread_count INTEGER NOT NULL DEFAULT 0,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE messages(
                          message_id TEXT PRIMARY KEY,
                          conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                          sender_id TEXT NOT NULL,
                          event_type TEXT NOT NULL,
                          target_message_id TEXT,
                          body TEXT,
                          reply_to TEXT,
                          created_at INTEGER NOT NULL,
                          edited_at INTEGER,
                          deleted INTEGER NOT NULL DEFAULT 0,
                          outgoing INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX messages_page_idx ON messages(conversation_id, created_at DESC)");
                statement.execute("""
                        CREATE VIRTUAL TABLE message_search USING fts5(
                          message_id UNINDEXED, conversation_id UNINDEXED, body, tokenize='unicode61'
                        )
                        """);
                statement.execute("""
                        CREATE TABLE drafts(
                          conversation_id TEXT PRIMARY KEY REFERENCES conversations(conversation_id) ON DELETE CASCADE,
                          body TEXT NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE blocks(
                          account_id TEXT PRIMARY KEY,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("PRAGMA user_version=1");
            }
            if (version < 2) {
                statement.execute("ALTER TABLE messages ADD COLUMN voice_payload BLOB");
                statement.execute("PRAGMA user_version=2");
            }
        }
    }

    public synchronized void ensureConversation(UUID id, ChatTransport transport, String kind, String title, UUID ownerId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO conversations(conversation_id, transport, kind, title, owner_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(conversation_id) DO UPDATE SET updated_at=excluded.updated_at
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, transport.name());
            statement.setString(3, kind);
            statement.setString(4, title == null ? "Conversation" : title);
            statement.setString(5, ownerId == null ? null : ownerId.toString());
            statement.setLong(6, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("could not store conversation", e);
        }
    }

    public synchronized void createMessage(UUID messageId, UUID conversationId, UUID senderId, String body,
                                           UUID replyTo, Instant createdAt, boolean outgoing) {
        createMessage(messageId, conversationId, senderId, body, replyTo, createdAt, outgoing, null);
    }

    public synchronized void createMessage(UUID messageId, UUID conversationId, UUID senderId, String body,
                                           UUID replyTo, Instant createdAt, boolean outgoing, byte[] voicePayload) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO messages(message_id, conversation_id, sender_id, event_type,
                      body, reply_to, created_at, outgoing, voice_payload) VALUES (?, ?, ?, 'CREATE', ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, messageId.toString());
                statement.setString(2, conversationId.toString());
                statement.setString(3, senderId.toString());
                statement.setString(4, body);
                statement.setString(5, replyTo == null ? null : replyTo.toString());
                statement.setLong(6, createdAt.toEpochMilli());
                statement.setInt(7, outgoing ? 1 : 0);
                statement.setBytes(8, voicePayload);
                if (statement.executeUpdate() == 1 && body != null) {
                    try (PreparedStatement search = connection.prepareStatement(
                            "INSERT INTO message_search(message_id, conversation_id, body) VALUES (?, ?, ?)")) {
                        search.setString(1, messageId.toString());
                        search.setString(2, conversationId.toString());
                        search.setString(3, body);
                        search.executeUpdate();
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE conversations SET updated_at=?, unread_count=unread_count+? WHERE conversation_id=?
                            """)) {
                        update.setLong(1, createdAt.toEpochMilli());
                        update.setInt(2, outgoing ? 0 : 1);
                        update.setString(3, conversationId.toString());
                        update.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (Exception e) {
            rollback();
            throw new IllegalStateException("could not store message", e);
        } finally {
            autoCommit();
        }
    }

    public synchronized boolean editMessage(UUID targetId, UUID actor, String newBody, Instant eventTime) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE messages SET body=?, edited_at=?, event_type='EDIT'
                WHERE message_id=? AND sender_id=? AND deleted=0 AND created_at >= ?
                """)) {
            statement.setString(1, newBody);
            statement.setLong(2, eventTime.toEpochMilli());
            statement.setString(3, targetId.toString());
            statement.setString(4, actor.toString());
            statement.setLong(5, eventTime.minusSeconds(15 * 60).toEpochMilli());
            boolean changed = statement.executeUpdate() == 1;
            if (changed) reindex(targetId, newBody);
            return changed;
        } catch (Exception e) {
            throw new IllegalStateException("could not edit message", e);
        }
    }

    public synchronized boolean deleteMessage(UUID targetId, UUID actor) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE messages SET body=NULL, deleted=1, event_type='DELETE'
                WHERE message_id=? AND sender_id=?
                """)) {
            statement.setString(1, targetId.toString());
            statement.setString(2, actor.toString());
            boolean changed = statement.executeUpdate() == 1;
            if (changed) reindex(targetId, null);
            return changed;
        } catch (Exception e) {
            throw new IllegalStateException("could not delete message", e);
        }
    }

    public synchronized void markRead(UUID conversationId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversations SET unread_count=0 WHERE conversation_id=?")) {
            statement.setString(1, conversationId.toString());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public synchronized void setMuted(UUID conversationId, boolean muted) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversations SET muted=? WHERE conversation_id=?")) {
            statement.setInt(1, muted ? 1 : 0);
            statement.setString(2, conversationId.toString());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isMuted(UUID conversationId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT muted FROM conversations WHERE conversation_id=?")) {
            statement.setString(1, conversationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void saveDraft(UUID conversationId, String body) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO drafts(conversation_id, body, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(conversation_id) DO UPDATE SET body=excluded.body, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, conversationId.toString());
            statement.setString(2, body);
            statement.setLong(3, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    public synchronized String draft(UUID conversationId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT body FROM drafts WHERE conversation_id=?")) {
            statement.setString(1, conversationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized List<ConversationRow> recentConversations(ChatTransport transport, int limit) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation_id, title, kind, unread_count, muted, updated_at
                FROM conversations WHERE transport=? ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, transport.name());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ConversationRow> values = new ArrayList<>();
                while (result.next()) values.add(new ConversationRow(UUID.fromString(result.getString(1)),
                        result.getString(2), result.getString(3), result.getInt(4),
                        result.getInt(5) != 0, Instant.ofEpochMilli(result.getLong(6))));
                return values;
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    public synchronized List<MessageRow> messages(UUID conversationId, int beforeOffset, int limit) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_id, sender_id, body, reply_to, created_at, edited_at, deleted, outgoing, voice_payload
                FROM messages WHERE conversation_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, conversationId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, beforeOffset);
            try (ResultSet result = statement.executeQuery()) {
                List<MessageRow> values = new ArrayList<>();
                while (result.next()) values.add(message(result));
                return values.reversed();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    public synchronized List<SearchRow> search(String query, int limit) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_id, conversation_id, highlight(message_search, 2, '[', ']')
                FROM message_search WHERE message_search MATCH ? LIMIT ?
                """)) {
            statement.setString(1, query.replace("\"", "\"\"") + "*");
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<SearchRow> values = new ArrayList<>();
                while (result.next()) values.add(new SearchRow(UUID.fromString(result.getString(1)),
                        UUID.fromString(result.getString(2)), result.getString(3)));
                return values;
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private static MessageRow message(ResultSet result) throws Exception {
        String reply = result.getString(4);
        long edited = result.getLong(6);
        return new MessageRow(UUID.fromString(result.getString(1)), UUID.fromString(result.getString(2)),
                result.getString(3), reply == null ? null : UUID.fromString(reply),
                Instant.ofEpochMilli(result.getLong(5)), result.wasNull() || edited == 0 ? null : Instant.ofEpochMilli(edited),
                result.getInt(7) != 0, result.getInt(8) != 0, result.getBytes(9));
    }

    private void reindex(UUID messageId, String body) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM message_search WHERE message_id=?")) {
            delete.setString(1, messageId.toString());
            delete.executeUpdate();
        }
        if (body != null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO message_search(message_id, conversation_id, body)
                    SELECT message_id, conversation_id, ? FROM messages WHERE message_id=?
                    """)) {
                insert.setString(1, body);
                insert.setString(2, messageId.toString());
                insert.executeUpdate();
            }
        }
    }

    private void rollback() { try { connection.rollback(); } catch (Exception ignored) { } }
    private void autoCommit() { try { connection.setAutoCommit(true); } catch (Exception ignored) { } }

    @Override
    public synchronized void close() {
        try { connection.close(); } catch (Exception ignored) { }
    }

    public record ConversationRow(UUID id, String title, String kind, int unread, boolean muted, Instant updatedAt) { }
    public record MessageRow(UUID id, UUID senderId, String body, UUID replyTo, Instant createdAt,
                             Instant editedAt, boolean deleted, boolean outgoing, byte[] voicePayload) { }
    public record SearchRow(UUID messageId, UUID conversationId, String excerpt) { }
}
