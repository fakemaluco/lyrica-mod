package dev.relaylyrics.client.compat;

import dev.relaylyrics.client.JadeIsland;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade owns this entrypoint and re-registers it whenever its plugin session is rebuilt. */
@WailaPlugin("lyrica")
public final class LyricaJadePlugin implements IWailaPlugin {
    @Override public void registerClient(IWailaClientRegistration registration) {
        JadeIsland.register(registration);
    }
}
