# Protocol and threat model

## Envelope v1

Each message/event has a time-sortable UUIDv7 and immutable metadata: message,
conversation, sender account/device, transport, event type, target, creation and
expiry. This metadata becomes AEAD associated data.

The sender generates a random 256-bit content key and 96-bit nonce, encrypts
the payload with AES-256-GCM, and wraps the content key independently for every
recipient device using RFC 9180 base-mode HPKE with X25519, HKDF-SHA256 and
AES-256-GCM. Ed25519 signs associated data, ciphertext, hash, nonce and every
wrapped-key record. The receiver verifies hash and signature before HPKE or
payload decryption, then applies a replay guard.

New devices receive only envelopes created after registration; the backend
cannot reconstruct old plaintext or content keys. Device private keys and
decrypted history stay in the local Minecraft configuration directory.

## Mojang proof

1. The backend creates a random, two-minute `serverId` challenge.
2. The client sends its access token, profile and `serverId` directly to
   `sessionserver.mojang.com/session/minecraft/join`.
3. The backend calls Mojang `hasJoined` with username and `serverId`.
4. A successful profile match creates a random FanDroid bearer session. The
   FanDroid service never receives the Minecraft access token.

## Metadata and retention

The service can observe participants, device routing, timestamps and envelope
sizes. It cannot see message bodies, replies, voice media or keys. Encrypted
deliveries are deleted after every target device acknowledges them or after
seven days. Minimal message metadata is kept only until its seven-day expiry so
the service can enforce sender-only edits/deletes and the 15-minute edit window.

## Optional transports

Server Chat uses the same envelope format in a world-local SQLite queue. The
capability payload is versioned; clients send nothing to unsupported servers.
Simple Voice Chat and Essential code paths are optional entrypoints/version-
gated adapters so absent mods do not enter class loading.

