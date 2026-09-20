CREATE TABLE group_invites (
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    invited_by UUID NOT NULL REFERENCES accounts(minecraft_uuid),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, account_id)
);

