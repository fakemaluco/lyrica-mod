package dev.relaylyrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared logger holder for the sources extracted from Relay's lyrics renderer. */
public final class FanDroidMod {
    public static final String MOD_ID = "relaylyrics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private FanDroidMod() {
    }
}
