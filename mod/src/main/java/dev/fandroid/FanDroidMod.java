package dev.fandroid;

import dev.fandroid.server.FanDroidServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FanDroidMod implements ModInitializer {
    public static final String MOD_ID = "fandroid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        FanDroidServer.register();
        LOGGER.info("FanDroid common services initialized");
    }
}

