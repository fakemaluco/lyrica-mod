package dev.fandroid.client;

import dev.fandroid.FanDroidMod;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes optional client-mod detection. Compatibility code must stay soft:
 * Relay never class-loads another mod unless Fabric reports that it is present.
 */
public final class CompatibilityManager {
    private static final FabricLoader LOADER = FabricLoader.getInstance();
    private static final boolean MOUSE_TWEAKS = loaded("mousetweaks");
    private static final boolean IRIS = loaded("iris");
    private static final boolean SODIUM = loaded("sodium");
    private static final boolean IMMEDIATELY_FAST = loaded("immediatelyfast");
    private static final boolean EMI = loaded("emi");
    private static final boolean REI = loaded("roughlyenoughitems");
    private static final boolean CONTROLIFY = loaded("controlify");
    private static final boolean XAERO_MINIMAP = loaded("xaerominimap");
    private static final boolean APPLE_SKIN = loaded("appleskin");
    private static final boolean MOD_MENU = loaded("modmenu");
    private static final boolean MINECRAFT_CURSOR = loaded("minecraft-cursor")
            || loaded("cursors_extended");

    private CompatibilityManager() { }

    public static void initialize() {
        List<String> active = new ArrayList<>();
        add(active, MOUSE_TWEAKS, "Mouse Tweaks");
        add(active, IRIS, "Iris");
        add(active, SODIUM, "Sodium");
        add(active, IMMEDIATELY_FAST, "ImmediatelyFast");
        add(active, EMI, "EMI");
        add(active, REI, "REI");
        add(active, CONTROLIFY, "Controlify");
        add(active, XAERO_MINIMAP, "Xaero's Minimap");
        add(active, APPLE_SKIN, "AppleSkin");
        add(active, MOD_MENU, "Mod Menu");
        add(active, MINECRAFT_CURSOR, "Minecraft Cursor/Cursors Extended");
        if (!active.isEmpty()) {
            FanDroidMod.LOGGER.info("Relay optional compatibility active: {}", String.join(", ", active));
        }
    }

    public static boolean mouseTweaks() { return MOUSE_TWEAKS; }
    public static boolean iris() { return IRIS; }
    public static boolean sodium() { return SODIUM; }
    public static boolean immediatelyFast() { return IMMEDIATELY_FAST; }
    public static boolean emi() { return EMI; }
    public static boolean rei() { return REI; }
    public static boolean controlify() { return CONTROLIFY; }
    public static boolean xaeroMinimap() { return XAERO_MINIMAP; }
    public static boolean appleSkin() { return APPLE_SKIN; }
    public static boolean modMenu() { return MOD_MENU; }
    public static boolean minecraftCursor() { return MINECRAFT_CURSOR; }

    /** Render mods batch GUI work aggressively, so framebuffer passes must flush explicitly. */
    public static boolean needsExplicitGuiFlush() {
        return IRIS || SODIUM || IMMEDIATELY_FAST;
    }

    private static boolean loaded(String id) {
        return LOADER.isModLoaded(id);
    }

    private static void add(List<String> active, boolean present, String name) {
        if (present) active.add(name);
    }
}
