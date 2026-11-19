package dev.relaylyrics.client.ui;

import dev.lyrica.engine.LyricaReleaseLookup.Update;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.ReleaseSupport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

public final class UpdateIslandScreen extends FanDroidScreenBase {
    private static final String PREFIX = "config.relaylyrics.update_island.";
    private static final int TOP = 5, PAD = 13, BUTTON_HEIGHT = 24;
    private static final long NOTICE_NANOS = 15_000_000_000L, LEAVE_NANOS = 200_000_000L;
    private static UpdateIslandScreen notice;
    private final Screen parent;
    private final Update update;
    private final String installed;
    private final Supplier<String> status;
    private final Runnable primary;
    private final Runnable dismiss;
    private final boolean installable;
    private boolean hovered, keyboardControls, laterFocused;
    private long deadline, pressedAt, dismissedAt;
    private boolean laterPressed;
    private final SettingsMotion.Tween appearance = new SettingsMotion.Tween(0);
    private final SettingsMotion.Tween expansion = new SettingsMotion.Tween(0);
    private final SettingsMotion.Tween primaryHover = new SettingsMotion.Tween(0);
    private final SettingsMotion.Tween laterHover = new SettingsMotion.Tween(0);

    private UpdateIslandScreen(Screen parent, Update update, String installed, boolean installable,
                               Supplier<String> status, Runnable primary, Runnable dismiss) {
        super(Component.translatable(PREFIX + "title"));
        this.parent = parent;
        this.update = update;
        this.installed = installed;
        this.installable = installable;
        this.status = status;
        this.primary = primary;
        this.dismiss = dismiss;
    }

    public static boolean hasNotice() {
        if (notice != null && System.nanoTime() - notice.deadline >= (animations() ? LEAVE_NANOS : 0))
            notice = null;
        return notice != null;
    }

    private static boolean animations() { return RelayLyricsClient.preferences().isLyricsAnimations(); }

    public static void announce(ReleaseSupport support, Update update) {
        notice = create(null, support, update);
        notice.deadline = System.nanoTime() + NOTICE_NANOS;
    }

    public static void open(Screen parent) {
        var music = RelayLyricsClient.music();
        if (music == null || music.support().update() == null) return;
        setScreen(create(parent, music.support(), music.support().update()));
    }

    private static UpdateIslandScreen create(Screen parent, ReleaseSupport support, Update update) {
        boolean installable = support.canSchedule(update);
        return new UpdateIslandScreen(parent, update, support.installedVersion(), installable,
                support::installationStatus, installable ? () -> support.schedule(update) : () -> browse(update.page()),
                () -> support.dismissUpdate(update.version()));
    }

    public static void renderNotice(GuiGraphicsExtractor context, double mouseX, double mouseY) {
        if (hasNotice()) notice.draw(context, viewport(), mouseX, mouseY);
    }

    public static boolean clickNotice(double x, double y, int button) {
        return hasNotice() && System.nanoTime() < notice.deadline && notice.click(viewport(), x, y, button);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) { }
    @Override public void onClose() { setScreen(parent); }

    @Override public void tick() {
        if (dismissedAt != 0 && System.nanoTime() - dismissedAt >= (animations() ? LEAVE_NANOS : 0))
            onClose();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        draw(context, width, mouseX, mouseY);
    }

    private void draw(GuiGraphicsExtractor context, int viewport, double mouseX, double mouseY) {
        String state = status.get();
        long now = System.nanoTime();
        boolean motion = animations();
        boolean closing = dismissedAt != 0 || deadline != 0 && now >= deadline;
        var shortLabel = Component.translatable(PREFIX + primaryLabel(state, false));
        var longLabel = Component.translatable(PREFIX + primaryLabel(state, true));
        var later = Component.translatable(PREFIX + "later");
        Layout layout = layout(viewport, state);
        hovered = layout.compact().contains(mouseX, mouseY)
                || hovered && layout.expanded().contains(mouseX, mouseY);
        if (deadline != 0 && !closing && (hovered || state.equals("downloading"))) deadline = now + NOTICE_NANOS;
        boolean expanded = !closing && (hovered || keyboardControls);
        double visible = appearance.to(closing ? 0 : 1, closing ? 190 : 230,
                closing ? SettingsMotion.Curve.OUT : SettingsMotion.Curve.GLASS, now, motion);
        if (visible <= 0) return;
        double opacity = Math.clamp(visible, 0, 1);
        double amount = expansion.to(expanded ? 1 : 0, 250, SettingsMotion.Curve.GLASS, now, motion);
        double controls = Math.clamp(amount, 0, 1);
        Box island = layout.compact().toward(layout.expanded(), amount);
        Box activePrimary = controls < .5 ? layout.shortPrimary() : layout.primary();
        double glow = primaryHover.to(activePrimary.contains(mouseX, mouseY) || keyboardControls && !laterFocused ? 1 : 0,
                235, SettingsMotion.Curve.GLASS, now, motion);
        double otherGlow = laterHover.to(expanded && (layout.later().contains(mouseX, mouseY)
                        || keyboardControls && laterFocused) ? 1 : 0, 235, SettingsMotion.Curve.GLASS, now, motion);
        int slide = (int)Math.round((visible - 1) * 9);

        GuiTransform.nextLayer(context);
        RoundedGui.smoothFill(context, island.x(), island.y() + slide, island.w(), island.h(), 18,
                SettingsMotion.opacity(0xFF000000, opacity));
        int left = island.x() + PAD;
        drawText(context, getTitle(), left, TOP + 10 + slide, 1.26, layout.textWidth(), SettingsMotion.opacity(0xFFFFFFFF, opacity));
        drawText(context, Component.translatable(PREFIX + "versions", installed, update.version()),
                left, TOP + 25 + slide, 1, layout.textWidth(), SettingsMotion.opacity(0xFFB8B8C2, opacity));
        if (!state.equals("ready"))
            drawText(context, Component.translatable(PREFIX + "state." + state), left, TOP + 38 + slide, .9,
                    layout.textWidth(), SettingsMotion.opacity(state.equals("failed") ? 0xFFFFA5A5 : 0xFFB8B8C2, opacity));

        GuiTransform.enableScissor(context, island.x(), island.y() + slide, island.x() + island.w(), island.y() + slide + island.h());
        try {
            double press = motion ? pulse(now) : 0;
            button(context, layout.shortPrimary().shift(slide), shortLabel, true, glow,
                    laterPressed ? 0 : press, opacity * (1 - controls), mouseX);
            button(context, layout.primary().shift(slide), longLabel, true, glow,
                    laterPressed ? 0 : press, opacity * controls, mouseX);
            button(context, layout.later().shift(slide), later, false, otherGlow,
                    laterPressed ? press : 0, opacity * controls, mouseX);
        } finally { context.disableScissor(); }
    }

    private String primaryLabel(String state, boolean expanded) {
        if (!installable) return "github";
        return switch (state) {
            case "queued" -> "done";
            case "downloading" -> "waiting";
            default -> expanded ? "schedule" : "install";
        };
    }

    private void button(GuiGraphicsExtractor context, Box box, Component label, boolean primaryButton,
                        double glow, double pulse, double fade, double mouseX) {
        if (fade <= .001) return;
        int radius = BUTTON_HEIGHT / 2;
        float cx = box.x() + box.w() / 2f, cy = box.y() + box.h() / 2f;
        float scale = (float)(1 + .064 * glow + pulse);
        GuiTransform.push(context);
        GuiTransform.translate(context, cx, cy);
        GuiTransform.scale(context, scale, scale);
        GuiTransform.translate(context, -cx, -cy);
        try {
            if (primaryButton) RoundedGui.smoothFill(context, box.x(), box.y(), box.w(), box.h(), radius,
                    SettingsMotion.opacity(SettingsMotion.mix(0xFFCCD1DB, 0xFFE9EDF4, glow), fade));
            LiquidGlass.draw(context, box.x(), box.y(), box.w(), box.h(), radius, fade, glow);
            if (!primaryButton) RoundedGui.smoothOutline(context, box.x(), box.y(), box.w(), box.h(), radius,
                    SettingsMotion.opacity(SettingsMotion.mix(0xFF9D9DA8, 0xFFF8F8FF, glow), fade));
            if (glow > .01 && box.w() > 32) {
                int width = Math.min(24, box.w() / 3);
                double along = Math.clamp((mouseX - box.x()) / box.w(), 0, 1);
                int x = box.x() + 7 + (int)Math.round(along * (box.w() - width - 14));
                RoundedGui.smoothFill(context, x, box.y() + 3, width, 2, 1,
                        SettingsMotion.opacity(0x70FFFFFF, fade * Math.min(1, glow)));
            }
            int advance = textWidth(label);
            int x = box.x() + Math.max(5, (box.w() - advance) / 2);
            drawText(context, label, x, box.y() + 8, 1, box.w() - 10,
                    SettingsMotion.opacity(primaryButton ? 0xFF13151A : 0xFFFFFFFF, fade));
        } finally { GuiTransform.pop(context); }
    }

    private double pulse(long now) {
        if (pressedAt == 0) return 0;
        double elapsed = (now - pressedAt) / 1_000_000.0;
        if (elapsed < 55) return -.09 * elapsed / 55;
        double progress = (elapsed - 55) / 265;
        return progress >= 1 ? 0 : -.09 * (1 - SettingsMotion.Curve.GLASS.at(progress));
    }

    private void drawText(GuiGraphicsExtractor context, Component label, int x, int y, double scale, int available, int color) {
        double fitted = Math.min(scale, available / (double) Math.max(1, textWidth(label)));
        if (fitted <= 0) return;
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, (float) fitted, (float) fitted);
        try { context.text(net.minecraft.client.Minecraft.getInstance().font, label, 0, 0, color, false); }
        finally { GuiTransform.pop(context); }
    }

    private static int textWidth(Component label) { return net.minecraft.client.Minecraft.getInstance().font.width(label); }

    private Layout layout(int viewport, String state) {
        int room = Math.max(1, viewport - 12);
        int natural = Math.max((int)Math.ceil(textWidth(getTitle()) * 1.26),
                textWidth(Component.translatable(PREFIX + "versions", installed, update.version())));
        int shortWidth = Math.min(textWidth(Component.translatable(PREFIX + primaryLabel(state, false))) + 20, Math.max(18, room / 3));
        int text = Math.min(state.equals("ready") ? Math.max(175, natural) : Math.max(208, natural),
                Math.max(1, room - 2 * PAD - shortWidth - 9));
        int labelWidth = Math.max(textWidth(Component.translatable(PREFIX + "schedule")), textWidth(Component.translatable(PREFIX + primaryLabel(state, true))));
        int primaryWidth = labelWidth + 22;
        int laterWidth = Math.max(58, textWidth(Component.translatable(PREFIX + "later")) + 19);
        int buttons = primaryWidth + laterWidth + 6;
        int titleWidth = text + 2 * PAD;
        int compactWidth = titleWidth + shortWidth + 9;
        int compactHeight = state.equals("ready") ? 43 : 55;
        Box compact = new Box((viewport - compactWidth) / 2, TOP, compactWidth, compactHeight);
        Box shortPrimary = new Box(compact.x() + compactWidth - PAD - shortWidth,
                TOP + (compactHeight - BUTTON_HEIGHT) / 2, shortWidth, BUTTON_HEIGHT);
        boolean sameRow = titleWidth + buttons + 9 <= room;
        int expandedWidth = sameRow ? titleWidth + buttons + 9 : Math.min(room, Math.max(compactWidth, buttons + 2 * PAD));
        int x = (viewport - expandedWidth) / 2;
        int y = TOP + (sameRow ? (compactHeight - BUTTON_HEIGHT) / 2 : compactHeight - 1);
        int actionRoom = Math.max(2, expandedWidth - 2 * PAD - 6);
        laterWidth = Math.min(laterWidth, actionRoom / 2);
        primaryWidth = Math.min(primaryWidth, actionRoom - laterWidth);
        Box later = new Box(x + expandedWidth - PAD - laterWidth, y, laterWidth, BUTTON_HEIGHT);
        Box primary = new Box(later.x() - 6 - primaryWidth, y, primaryWidth, BUTTON_HEIGHT);
        Box expanded = new Box(x, TOP, expandedWidth, sameRow ? compactHeight : compactHeight + BUTTON_HEIGHT + 7);
        return new Layout(compact, expanded, shortPrimary, primary, later, text);
    }

    @Override protected boolean handleMouseClicked(double x, double y, int button) {
        return click(width, x, y, button);
    }

    private boolean click(int viewport, double x, double y, int button) {
        if (button != 0 || dismissedAt != 0) return false;
        Layout layout = layout(viewport, status.get());
        if (expansion.value(System.nanoTime()) >= .5 && layout.expanded().contains(x, y)) {
            if (layout.later().contains(x, y)) dismiss();
            else if (layout.primary().contains(x, y)) pressPrimary();
            return true;
        }
        if (layout.shortPrimary().contains(x, y)) { pressPrimary(); return true; }
        return layout.compact().contains(x, y);
    }

    @Override protected boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_TAB || key == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT || key == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT) {
            if (keyboardControls) laterFocused = !laterFocused;
            keyboardControls = true;
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE) {
            if (!keyboardControls) keyboardControls = true;
            else if (laterFocused) dismiss();
            else pressPrimary();
            return true;
        }
        return false;
    }

    private void pressPrimary() {
        if (dismissedAt != 0) return;
        pressedAt = System.nanoTime();
        laterPressed = false;
        if (deadline != 0) deadline = pressedAt + NOTICE_NANOS;
        String current = status.get();
        if (current.equals("ready") || current.equals("failed")) primary.run();
        else if (current.equals("queued") && activeScreen() == this) onClose();
    }

    private void dismiss() {
        if (dismissedAt != 0) return;
        dismiss.run();
        pressedAt = dismissedAt = System.nanoTime();
        laterPressed = true;
        if (notice != null && notice.update.version().equals(update.version())) notice.deadline = dismissedAt;
        if (!animations() && activeScreen() == this) onClose();
    }

    private static void browse(java.net.URI page) {
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(page.toString());
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                    java.awt.Desktop.getDesktop().browse(page);
            } catch (Exception ignored) { /* The release link is on the clipboard. */ }
        });
    }

    private static int viewport() { return net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth(); }
    private static Screen activeScreen() { return net.minecraft.client.Minecraft.getInstance().gui.screen(); }
    private static void setScreen(Screen screen) { net.minecraft.client.Minecraft.getInstance().gui.setScreen(screen); }

    private record Box(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
        Box shift(int dy) { return new Box(x, y + dy, w, h); }
        Box toward(Box other, double progress) {
            int width = (int)Math.round(w + (other.w - w) * progress);
            return new Box(x + (w - width) / 2, y, width, (int)Math.round(h + (other.h - h) * progress));
        }
    }
    private record Layout(Box compact, Box expanded, Box shortPrimary, Box primary, Box later, int textWidth) { }

    static UpdateIslandScreen preview(Supplier<String> status, Runnable schedule, Runnable dismiss) {
        var page = java.net.URI.create("https://github.com/fakemaluco/lyrica-mod/releases");
        var update = new Update("0.9.5", page, page, 0, "");
        notice = new UpdateIslandScreen(null, update, "0.9.4", true, status, schedule, dismiss);
        notice.deadline = System.nanoTime() + NOTICE_NANOS;
        return notice;
    }

    void pointerForPreview() { hovered = true; keyboardControls = true; expansion.snap(1); }

    boolean clickForPreview(boolean later) {
        Layout layout = layout(width, status.get());
        Box button = later ? layout.later() : layout.primary();
        return handleMouseClicked(button.x() + button.w() / 2.0, button.y() + button.h() / 2.0, 0);
    }
}
