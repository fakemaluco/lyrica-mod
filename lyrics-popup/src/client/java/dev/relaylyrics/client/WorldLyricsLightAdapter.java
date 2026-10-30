package dev.relaylyrics.client;

import net.fabricmc.loader.api.*;
import java.lang.reflect.Proxy;

/** Older optional lighting mods may request the same entrypoint key with an incompatible API. */
public final class WorldLyricsLightAdapter implements LanguageAdapter {
    @Override public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        var api=FabricLoader.getInstance().getModContainer("lambdynlights_api");
        boolean supported=false;
        if (api.isPresent()) try {
            var version=SemanticVersion.parse(api.get().getMetadata().getVersion().getFriendlyString());
            supported=version.getVersionComponent(0)==4 && version.getVersionComponent(1)>=8;
        } catch (VersionParsingException ignored) { }
        if (supported) return LanguageAdapter.getDefault().create(mod,value,type);
        dev.relaylyrics.FanDroidMod.LOGGER.info("World-word lighting needs LambDynamicLights API 4.8+; keeping the optional integration inactive");
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args) -> switch (method.getName()) {
            case "toString" -> "Inactive Lyrica lighting integration";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args!=null && args.length==1 && proxy==args[0];
            default -> null;
        }));
    }
}
