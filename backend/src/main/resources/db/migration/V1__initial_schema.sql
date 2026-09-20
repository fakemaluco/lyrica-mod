CREATE TABLE accounts (
    minecraft_uuid UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX accounts_username_lower_idx ON accounts (lower(username));

CREATE TABLE devices (
    device_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    hpke_public_key BYTEA NOT NULL,
    signing_public_key BYTEA NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX devices_account_idx ON devices(account_id) WHERE revoked_at IS NULL;

CREATE TABLE sessions (
    token_hash BYTEA PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE contacts (
    requester_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    addressee_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_id, addressee_id),
    CHECK (requester_id <> addressee_id)
);

CREATE TABLE blocks (
    blocker_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE TABLE conversations (
    conversation_id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('DIRECT', 'GROUP')),
    title VARCHAR(80),
    owner_id UUID REFERENCES accounts(minecraft_uuid),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES accounts(minecraft_uuid) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, account_id)
);

CREATE TABLE envelopes (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES accounts(minecraft_uuid),
    sender_device_id UUID NOT NULL REFERENCES devices(device_id),
    event_type VARCHAR(24) NOT NULL,
    envelope_json JSONB NOT NULL,
    byte_size INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX envelopes_expiry_idx ON envelopes(expires_at);

CREATE TABLE deliveries (
    message_id UUID NOT NULL REFERENCES envelopes(message_id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    acknowledged_at TIMESTAMPTZ,
    PRIMARY KEY (message_id, device_id)
);

CREATE INDEX deliveries_pending_idx ON deliveries(device_id, acknowledged_at);

