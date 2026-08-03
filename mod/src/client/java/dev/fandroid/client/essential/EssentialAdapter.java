package dev.fandroid.client.essential;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Experimental, version-gated bridge. It deliberately uses only classes from the installed
 * Essential artifact and never bundles Essential implementation code.
 */
public final class EssentialAdapter {
    private static final String TESTED_VERSION = "1.4.1.1";
    private final boolean installed;
    private final boolean testedVersion;
    private final Capabilities capabilities;

    private EssentialAdapter(boolean installed, boolean testedVersion, Capabilities capabilities) {
        this.installed = installed;
        this.testedVersion = testedVersion;
        this.capabilities = capabilities;
    }

    public static EssentialAdapter detect() {
        Optional<net.fabricmc.loader.api.ModContainer> container = FabricLoader.getInstance().getModContainer("essential");
        if (container.isEmpty()) container = FabricLoader.getInstance().getModContainer("essential-container");
        if (container.isEmpty()) return new EssentialAdapter(false, false, Capabilities.NONE);
        String version = container.get().getMetadata().getVersion().getFriendlyString();
        if (!version.equals(TESTED_VERSION)) return new EssentialAdapter(true, false, Capabilities.NONE);

        // Internal capabilities are feature-detected; a missing method disables only that action.
        try {
            Class<?> connectionManager = Class.forName("gg.essential.network.connectionmanager.ConnectionManager");
            boolean text = hasMethodContaining(connectionManager, "chat");
            boolean edit = hasMethodContaining(connectionManager, "edit");
            boolean delete = hasMethodContaining(connectionManager, "delete");
            return new EssentialAdapter(true, true, new Capabilities(text, text, edit, delete));
        } catch (ReflectiveOperationException e) {
            return new EssentialAdapter(true, true, Capabilities.NONE);
        }
    }

    private static boolean hasMethodContaining(Class<?> type, String fragment) {
        for (Method method : type.getMethods()) {
            if (method.getName().toLowerCase(java.util.Locale.ROOT).contains(fragment)) return true;
        }
        return false;
    }

    public String status() {
        if (!installed) return "Essential is not installed";
        if (!testedVersion) return "Essential version is untested; adapter disabled";
        if (!capabilities.text()) return "No compatible Essential chat API detected";
        return "Essential adapter ready";
    }

    public Capabilities capabilities() { return capabilities; }

    public record Capabilities(boolean text, boolean replies, boolean edit, boolean delete) {
        static final Capabilities NONE = new Capabilities(false, false, false, false);
    }
}
