CREATE TABLE message_metadata (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES accounts(minecraft_uuid),
    event_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX message_metadata_expiry_idx ON message_metadata(expires_at);

