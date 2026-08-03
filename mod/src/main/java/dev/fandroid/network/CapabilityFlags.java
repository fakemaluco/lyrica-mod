package dev.fandroid.network;

public final class CapabilityFlags {
    public static final int SERVER_CHAT = 1;
    public static final int VOICE_MESSAGES = 1 << 1;
    public static final int CALLS = 1 << 2;
    public static final int LOCAL_GROUPS = 1 << 3;

    private CapabilityFlags() {
    }
}

