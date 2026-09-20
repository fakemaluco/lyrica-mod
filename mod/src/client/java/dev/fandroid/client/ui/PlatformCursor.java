package dev.fandroid.client.ui;

import dev.fandroid.FanDroidMod;
import dev.fandroid.client.CompatibilityManager;
import net.minecraft.client.gui.DrawContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minecraft 1.21.1 adapter for the optional Minecraft Cursor v3 Java API. */
public final class PlatformCursor {
    public enum Style { POINTER, GRABBING, RESIZE }

    private static boolean initialized;
    private static boolean unavailable;
    private static Object controller;
    private static Method setSingleCycleCursor;
    private static Object pointer;
    private static Object grabbing;
    private static Object resize;

    private PlatformCursor() { }

    public static void request(DrawContext context, Style style) {
        if (!CompatibilityManager.minecraftCursor() || unavailable) return;
        initialize();
        if (unavailable || controller == null) return;
        Object value = switch (style) {
            case POINTER -> pointer;
            case GRABBING -> grabbing;
            case RESIZE -> resize;
        };
        try {
            setSingleCycleCursor.invoke(controller, value);
        } catch (ReflectiveOperationException failure) {
            unavailable = true;
            FanDroidMod.LOGGER.warn("Minecraft Cursor integration disabled: {}", failure.toString());
        }
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> controllerType = Class.forName(
                    "io.github.fishstiz.minecraftcursor.api.CursorController");
            Class<?> cursorType = Class.forName("io.github.fishstiz.minecraftcursor.api.CursorType");
            controller = controllerType.getMethod("getInstance").invoke(null);
            setSingleCycleCursor = controllerType.getMethod("setSingleCycleCursor", cursorType);
            pointer = field(cursorType, "POINTER");
            grabbing = field(cursorType, "GRABBING");
            resize = field(cursorType, "RESIZE_NWSE");
        } catch (ReflectiveOperationException failure) {
            unavailable = true;
            FanDroidMod.LOGGER.warn("Minecraft Cursor API was detected but could not be linked: {}",
                    failure.toString());
        }
    }

    private static Object field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getField(name);
        return field.get(null);
    }
}
