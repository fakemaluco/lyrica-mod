package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import net.minecraft.client.gui.screen.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Optional EMI/REI geometry bridge. Reflection keeps both recipe viewers truly optional. */
final class InventoryOverlayCompat {
    private static boolean emiFailed;
    private static boolean reiFailed;

    private InventoryOverlayCompat() { }

    static List<Rect> occupiedAreas(Screen screen, Rect handledScreen) {
        List<Rect> result = new ArrayList<>();
        if (handledScreen != null) result.add(handledScreen);
        if (CompatibilityManager.emi() && !emiFailed) collectEmi(result);
        if (CompatibilityManager.rei() && !reiFailed) collectRei(result);
        return result.stream().filter(rect -> rect.width() > 0 && rect.height() > 0
                && rect.right() > 0 && rect.bottom() > 0
                && rect.x() < screen.width && rect.y() < screen.height).distinct().toList();
    }

    private static void collectEmi(List<Rect> result) {
        try {
            Class<?> manager = Class.forName("dev.emi.emi.screen.EmiScreenManager");
            Class<?> side = Class.forName("dev.emi.emi.config.SidebarSide");
            Method getPanel = manager.getMethod("getPanelFor", side);
            for (String name : List.of("LEFT", "RIGHT", "TOP", "BOTTOM")) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object sideValue = Enum.valueOf((Class<? extends Enum>) side.asSubclass(Enum.class), name);
                Object panel = getPanel.invoke(null, sideValue);
                if (panel == null || !Boolean.TRUE.equals(invoke(panel, "isVisible"))) continue;
                addBounds(result, invoke(panel, "getBounds"));
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            emiFailed = true;
            FanDroidMod.LOGGER.warn("EMI overlay geometry integration disabled: {}", failure.toString());
        }
    }

    private static void collectRei(List<Rect> result) {
        try {
            Class<?> runtimeType = Class.forName("me.shedaniel.rei.api.client.REIRuntime");
            Object runtime = runtimeType.getMethod("getInstance").invoke(null);
            if (!Boolean.TRUE.equals(invoke(runtime, "isOverlayVisible"))) return;
            Object overlay = optionalValue(invoke(runtime, "getOverlay"));
            if (overlay == null) return;
            addBounds(result, invoke(invoke(overlay, "getEntryList"), "getBounds"));
            Object favorites = optionalValue(invoke(overlay, "getFavoritesList"));
            if (favorites != null) addBounds(result, invoke(favorites, "getBounds"));
            Object search = invoke(runtime, "getSearchTextField");
            if (search != null) addBounds(result, invoke(search, "getBounds"));
        } catch (ReflectiveOperationException | LinkageError failure) {
            reiFailed = true;
            FanDroidMod.LOGGER.warn("REI overlay geometry integration disabled: {}", failure.toString());
        }
    }

    private static Object optionalValue(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        Method method = target instanceof Class<?> type ? type.getMethod(name) : target.getClass().getMethod(name);
        return method.invoke(target instanceof Class<?> ? null : target);
    }

    private static void addBounds(List<Rect> result, Object bounds) throws ReflectiveOperationException {
        if (bounds == null) return;
        int x = number(bounds, "x", "left", "getX");
        int y = number(bounds, "y", "top", "getY");
        int width = number(bounds, "width", "getWidth");
        int height = number(bounds, "height", "getHeight");
        result.add(new Rect(x, y, width, height));
    }

    private static int number(Object value, String... names) throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                Object result = method.invoke(value);
                if (result instanceof Number number) return number.intValue();
            } catch (NoSuchMethodException ignored) { }
            try {
                Field field = type.getField(name);
                Object result = field.get(value);
                if (result instanceof Number number) return number.intValue();
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + " has no numeric " + String.join("/", names));
    }

    record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        Rect expanded(int pixels) {
            return new Rect(x - pixels, y - pixels, width + pixels * 2, height + pixels * 2);
        }
        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }
}
