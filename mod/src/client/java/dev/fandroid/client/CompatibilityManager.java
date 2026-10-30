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
    private static final IrisBridge IRIS_API = IrisBridge.discover(IRIS);

    private CompatibilityManager() { }

    public static void initialize() {
        List<String> active = new ArrayList<>();
        add(active, MOUSE_TWEAKS, "Mouse Tweaks");
        add(active, IRIS, "Iris");
        add(active, JI_AFK, "Ji AFK Cinematic");
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

    private static final boolean JI_AFK = loaded("ji-afk-cinematic");
    private static final java.lang.reflect.Method JI_STATE = jiStateMethod();
    private static java.lang.reflect.Method jiStateMethod() {
        if (JI_AFK) try {
            return Class.forName("com.ji.afkcinematic.cinematic.CinematicManager", false,
                    CompatibilityManager.class.getClassLoader()).getMethod("getState");
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        return null;
    }
    /** Optional public API; no timers that could confuse sleeping or a manually hidden HUD with AFK. */
    public static boolean jiAfkCinematicActive() {
        if (JI_STATE == null) return false;
        try { return "CINEMATIC_ACTIVE".equals(String.valueOf(JI_STATE.invoke(null))); }
        catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    /** Public Iris API only. Optional linkage never makes Iris a required dependency. */
    public static boolean worldShaderActive() { return IRIS_API.query(false); }
    public static boolean worldShadowPass() { return IRIS_API.query(true); }

    static final class IrisBridge {
        final Object instance;
        final java.lang.reflect.Method shaders, shadows;
        boolean failed;
        IrisBridge(Object instance, java.lang.reflect.Method shaders, java.lang.reflect.Method shadows) {
            this.instance = instance; this.shaders = shaders; this.shadows = shadows;
        }
        static IrisBridge discover(boolean present) {
            if (present) try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                return new IrisBridge(api.getMethod("getInstance").invoke(null),
                        api.getMethod("isShaderPackInUse"), api.getMethod("isRenderingShadowPass"));
            } catch (ReflectiveOperationException | LinkageError ignored) { }
            return new IrisBridge(null, null, null);
        }
        boolean query(boolean shadow) {
            if (instance == null || failed) return false;
            try { return Boolean.TRUE.equals((shadow ? shadows : shaders).invoke(instance)); }
            catch (ReflectiveOperationException | LinkageError ignored) { failed = true; return false; }
        }
    }

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
