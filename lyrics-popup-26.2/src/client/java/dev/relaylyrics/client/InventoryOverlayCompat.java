package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;

/** Optional EMI/REI geometry bridge. Reflection keeps both recipe viewers truly optional. */
final class InventoryOverlayCompat {
    private static boolean emiFailed;
    private static boolean reiFailed;
    private static boolean emiResolved;
    private static boolean reiResolved;
    private static Method emiGetPanel;
    private static Object[] emiSides = new Object[0];
    private static Method reiGetInstance;
    private static final Map<MemberKey, Optional<Method>> METHODS = new HashMap<>();
    private static final Map<MemberKey, Optional<Field>> FIELDS = new HashMap<>();
    private static Screen cachedScreen;
    private static Rect cachedHandledScreen;
    private static int cachedScreenWidth;
    private static int cachedScreenHeight;
    private static long cachedTickBucket = Long.MIN_VALUE;
    private static List<Rect> cachedAreas = List.of();

    private InventoryOverlayCompat() { }

    static List<Rect> occupiedAreas(Screen screen, Rect handledScreen) {
        long tickBucket = System.nanoTime() / 50_000_000L;
        if (screen == cachedScreen && Objects.equals(handledScreen, cachedHandledScreen)
                && screen.width == cachedScreenWidth && screen.height == cachedScreenHeight
                && tickBucket == cachedTickBucket) {
            return cachedAreas;
        }
        List<Rect> result = new ArrayList<>();
        if (handledScreen != null) result.add(handledScreen);
        if (CompatibilityManager.emi() && !emiFailed) collectEmi(result);
        if (CompatibilityManager.rei() && !reiFailed) collectRei(result);
        List<Rect> areas = result.stream().filter(rect -> rect.width() > 0 && rect.height() > 0
                && rect.right() > 0 && rect.bottom() > 0
                && rect.x() < screen.width && rect.y() < screen.height).distinct().toList();
        cachedScreen = screen;
        cachedHandledScreen = handledScreen;
        cachedScreenWidth = screen.width;
        cachedScreenHeight = screen.height;
        cachedTickBucket = tickBucket;
        cachedAreas = areas;
        return areas;
    }

    private static void collectEmi(List<Rect> result) {
        try {
            resolveEmi();
            for (Object sideValue : emiSides) {
                Object panel = emiGetPanel.invoke(null, sideValue);
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
            resolveRei();
            Object runtime = reiGetInstance.invoke(null);
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
        Class<?> type = target instanceof Class<?> targetType ? targetType : target.getClass();
        Method method = cachedMethod(type, name);
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
            Optional<Method> method = cachedOptionalMethod(type, name);
            if (method.isPresent()) {
                Object result = method.get().invoke(value);
                if (result instanceof Number number) return number.intValue();
            }
            Optional<Field> field = cachedOptionalField(type, name);
            if (field.isPresent()) {
                Object result = field.get().get(value);
                if (result instanceof Number number) return number.intValue();
            }
        }
        throw new NoSuchMethodException(type.getName() + " has no numeric " + String.join("/", names));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void resolveEmi() throws ReflectiveOperationException {
        if (emiResolved) return;
        Class<?> manager = Class.forName("dev.emi.emi.screen.EmiScreenManager");
        Class<?> side = Class.forName("dev.emi.emi.config.SidebarSide");
        emiGetPanel = manager.getMethod("getPanelFor", side);
        List<String> names = List.of("LEFT", "RIGHT", "TOP", "BOTTOM");
        emiSides = new Object[names.size()];
        for (int index = 0; index < names.size(); index++) {
            emiSides[index] = Enum.valueOf((Class<? extends Enum>) side.asSubclass(Enum.class), names.get(index));
        }
        emiResolved = true;
    }

    private static void resolveRei() throws ReflectiveOperationException {
        if (reiResolved) return;
        Class<?> runtimeType = Class.forName("me.shedaniel.rei.api.client.REIRuntime");
        reiGetInstance = runtimeType.getMethod("getInstance");
        reiResolved = true;
    }

    private static Method cachedMethod(Class<?> type, String name) throws NoSuchMethodException {
        return cachedOptionalMethod(type, name).orElseThrow(
                () -> new NoSuchMethodException(type.getName() + "." + name));
    }

    private static Optional<Method> cachedOptionalMethod(Class<?> type, String name) {
        MemberKey key = new MemberKey(type, name);
        Optional<Method> cached = METHODS.get(key);
        if (cached != null) return cached;
        try {
            cached = Optional.of(type.getMethod(name));
        } catch (NoSuchMethodException ignored) {
            cached = Optional.empty();
        }
        METHODS.put(key, cached);
        return cached;
    }

    private static Optional<Field> cachedOptionalField(Class<?> type, String name) {
        MemberKey key = new MemberKey(type, name);
        Optional<Field> cached = FIELDS.get(key);
        if (cached != null) return cached;
        try {
            cached = Optional.of(type.getField(name));
        } catch (NoSuchFieldException ignored) {
            cached = Optional.empty();
        }
        FIELDS.put(key, cached);
        return cached;
    }

    private record MemberKey(Class<?> type, String name) { }

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
