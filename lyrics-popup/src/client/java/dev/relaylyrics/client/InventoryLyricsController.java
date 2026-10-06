package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.GuiTransform;
import dev.relaylyrics.client.ui.LyricsOverlay;
import dev.relaylyrics.client.ui.PopupIcons;
import dev.relaylyrics.client.ui.LyricsOverlay.PopupBounds;
import dev.relaylyrics.client.ui.PlatformCursor;
import dev.relaylyrics.client.ui.RoundedGui;
import dev.relaylyrics.client.InventoryOverlayCompat.Rect;
import dev.relaylyrics.mixin.HandledScreenAccessor;
import dev.relaylyrics.protocol.music.MusicAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.WeakHashMap;

/** Shared inventory interaction logic; callback signatures live in version adapters. */
public final class InventoryLyricsController {
    private static final Set<Screen> REGISTERED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final InventoryPopupTransition POPUP_TRANSITION = new InventoryPopupTransition();
    private static Screen activeScreen;
    private static boolean selected;
    private static boolean dragging;
    private static boolean scrubbing;
    private static boolean volumeScrubbing;
    private static double volumeDragValue;
    private static double lastAudibleVolume = .5;
    private static double lastVolumeCommandValue = Double.NaN;
    private static long lastVolumeCommandNanos;
    private static long scrubPosition;
    private static long scrubDuration;
    private static Bounds progressBarBounds;
    private static Bounds previousButtonBounds;
    private static Bounds playPauseButtonBounds;
    private static Bounds heartButtonBounds;
    private static Bounds nextButtonBounds;
    private static long coverAnimationStartedNanos;
    private static long controlFadeNanos;
    private static double previousHoverFade;
    private static double playPauseHoverFade;
    private static double heartHoverFade;
    private static double nextHoverFade;
    private static double coverControlsFade;
    private static double progressHoverFade;
    private static long progressFadeNanos;
    private static long cachedPositionSecond = Long.MIN_VALUE;
    private static long cachedDurationSecond = Long.MIN_VALUE;
    private static String cachedPositionLabel = "0:00";
    private static String cachedDurationLabel = "0:00";

    private InventoryLyricsController() {
    }

    public static boolean supports(Screen screen) {
        return screen != null && RelayLyricsClient.preferences().getLyricsRenderType().rendersScreen(screen);
    }

    public static boolean attach(Screen screen) {
        POPUP_TRANSITION.setVisible(screenPopupEnabled(screen), System.nanoTime(),
                RelayLyricsClient.preferences().isLyricsAnimations());
        boolean firstRegistration = REGISTERED.add(screen);
        if (screen == activeScreen && !firstRegistration) {
            if (inventoryPopupEnabled() && screen instanceof HandledScreen<?>) {
                keepOutsideGui(screen, snapshotPreferences());
            }
            return false;
        }
        activeScreen = screen;
        selected = false;
        dragging = false;
        scrubbing = false;
        volumeScrubbing = false;
        if (RelayLyricsClient.overlay() != null) {
            RelayLyricsClient.overlay().cancelPopupProgressScrub();
            RelayLyricsClient.overlay().setPopupVolumeScrubbing(false);
        }
        clearNowPlayingInteractions();
        coverAnimationStartedNanos = System.nanoTime();
        controlFadeNanos = coverAnimationStartedNanos;
        progressFadeNanos = coverAnimationStartedNanos;
        progressHoverFade = 0;
        previousHoverFade = 0;
        playPauseHoverFade = 0;
        heartHoverFade = 0;
        nextHoverFade = 0;
        coverControlsFade = 0;
        if (inventoryPopupEnabled() && screen instanceof HandledScreen<?>) {
            keepOutsideGui(screen, snapshotPreferences());
        }
        return firstRegistration;
    }

    public static void render(Screen screen, DrawContext context, int mouseX, int mouseY) {
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (overlay == null) return;
        if (screen != activeScreen) {
            attach(screen);
        }
        boolean popupVisible = inventoryPopupAvailable();
        double transitionVisibility = popupTransitionVisibility();
        if (popupVisible && POPUP_TRANSITION.isFullyVisible()) {
            overlay.renderHudOverScreen(context, mouseX, mouseY);
        } else if (transitionVisibility > .001
                && (popupVisible || !screenPopupEnabled(activeScreen))) {
            overlay.renderHudTransition(context, transitionVisibility, true);
        }
        if (RelayLyricsClient.preferences().isInventoryNowPlaying()) {
            renderNowPlaying(screen, context, mouseX, mouseY);
        } else {
            clearNowPlayingInteractions();
        }
        if (popupVisible && selected) {
            PopupBounds popup = popupBounds();
            if (RelayLyricsClient.preferences().isModernMode()) {
                int radius = overlay.morphRadius(Math.clamp(Math.min(popup.width(), popup.height()) / 22, 9, 16));
                RoundedGui.smoothOutline(context, popup.x(), popup.y(), popup.width(), popup.height(),
                        radius, 0xFFE4F5F7);
            } else {
                outline(context, popup.x(), popup.y(), popup.width(), popup.height(), 0xFFE4F5F7);
            }
        }
        updateCursor(context, mouseX, mouseY, popupVisible);
    }

    public static boolean mouseClicked(Screen screen, double mouseX, double mouseY, int button) {
        if (screen != activeScreen) return false;
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        boolean popupVisible = inventoryPopupAvailable();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return popupVisible && overlay != null
                    && overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY);
        }
        if (popupVisible) {
            if (overlay != null && overlay.popupControlAt(mouseX, mouseY)
                    == LyricsOverlay.PopupControl.SETTINGS) {
                RelayLyricsClient.preferences().save();
                MinecraftClient.getInstance().setScreen(new LyricsConfigScreen(screen));
                return true;
            }
            OptionalDouble volume = overlay == null ? OptionalDouble.empty()
                    : overlay.popupVolumeTargetAt(mouseX, mouseY);
            if (volume.isPresent() && RelayLyricsClient.music() != null) {
                volumeScrubbing = true;
                overlay.setPopupVolumeScrubbing(true);
                updateVolumeDrag(volume.getAsDouble(), true);
                return true;
            }
            if (runPopupControl(overlay, mouseX, mouseY)) return true;
            if (overlay != null && overlay.beginPopupProgressScrub(mouseX, mouseY)) return true;
            if (overlay != null && overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY)) return true;
        }
        if (runNowPlayingControl(mouseX, mouseY)) return true;
        if (progressBarBounds != null && progressBarBounds.contains(mouseX, mouseY)) {
            dragging = false;
            scrubbing = true;
            updateScrub(mouseX);
            return true;
        }
        if (popupVisible && overlay != null && RelayLyricsClient.music() != null) {
            OptionalLong target = overlay.seekTargetAtHud(mouseX, mouseY);
            if (target.isPresent()) {
                RelayLyricsClient.music().command(MusicAction.SEEK, target.getAsLong());
                overlay.seekTo(target.getAsLong());
                return true;
            }
        }
        if (popupVisible && overlay != null && overlay.mousePressed(mouseX, mouseY, button)) {
            selected = true;
            dragging = true;
            return true;
        }
        selected = false;
        return false;
    }

    public static boolean mouseReleased(Screen screen, double mouseX, double mouseY, int button) {
        if (screen != activeScreen) return false;
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        boolean popupVisible = inventoryPopupAvailable();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return popupVisible && overlay != null
                    && overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY);
        }
        if (volumeScrubbing) {
            if (overlay != null) {
                overlay.popupVolumeTargetAtY(mouseY).ifPresent(value -> updateVolumeDrag(value, true));
                overlay.setPopupVolumeScrubbing(false);
            }
            volumeScrubbing = false;
            return true;
        }
        if (popupVisible && overlay != null && overlay.isPopupProgressScrubbing()) {
            overlay.updatePopupProgressScrub(mouseX);
            OptionalLong target = overlay.finishPopupProgressScrub();
            if (target.isPresent() && RelayLyricsClient.music() != null) {
                RelayLyricsClient.music().command(MusicAction.SEEK, target.getAsLong());
                overlay.seekTo(target.getAsLong());
            }
            return true;
        }
        if (scrubbing) {
            updateScrub(mouseX);
            finishScrub();
            return true;
        }
        if (popupVisible && overlay != null
                && overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY)) return true;
        if (!dragging) return false;
        dragging = false;
        return overlay != null && overlay.mouseReleased();
    }

    public static boolean mouseScrolled(Screen screen, double mouseX, double mouseY,
                                        double horizontal, double vertical) {
        if (screen != activeScreen || vertical == 0 || !inventoryPopupAvailable()) return false;
        PopupBounds popup = popupBounds();
        if (mouseX < popup.x() || mouseX >= popup.x() + popup.width()
                || mouseY < popup.y() || mouseY >= popup.y() + popup.height()) {
            return false;
        }
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (overlay == null) return false;
        if (overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY)) return true;
        return overlay.scrollLyrics(false, vertical);
    }

    /** Cancels handled-screen and Mouse Tweaks dragging while Relay owns the pointer. */
    public static boolean mouseDraggedEvent(Screen screen, double mouseX, double mouseY, int button) {
        if (screen != activeScreen || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        boolean popupVisible = inventoryPopupAvailable();
        if (scrubbing || volumeScrubbing || popupVisible && (dragging
                || overlay != null && overlay.isPopupProgressScrubbing())) return true;
        if (popupVisible) {
            PopupBounds popup = popupBounds();
            if (mouseX >= popup.x() && mouseX < popup.x() + popup.width()
                    && mouseY >= popup.y() && mouseY < popup.y() + popup.height()) return true;
        }
        return progressBarBounds != null && progressBarBounds.contains(mouseX, mouseY);
    }

    public static void tick(MinecraftClient client) {
        if (activeScreen == null && client.currentScreen != null
                && !(client.currentScreen instanceof LyricsConfigScreen)
                && screenPopupEnabled(client.currentScreen)) {
            attach(client.currentScreen);
        }
        if (activeScreen != null && client.currentScreen != activeScreen
                && !(client.currentScreen instanceof LyricsConfigScreen)) {
            POPUP_TRANSITION.setVisible(true, System.nanoTime(),
                    RelayLyricsClient.preferences().isLyricsAnimations());
            activeScreen = null;
            dragging = false;
            scrubbing = false;
            volumeScrubbing = false;
            if (RelayLyricsClient.overlay() != null) {
                RelayLyricsClient.overlay().setPopupVolumeScrubbing(false);
            }
            if (RelayLyricsClient.overlay() != null) {
                RelayLyricsClient.overlay().cancelPopupProgressScrub();
            }
            clearNowPlayingInteractions();
        }
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        boolean popupVisible = inventoryPopupAvailable();
        if (!popupVisible && dragging && overlay != null) {
            dragging = false;
            overlay.mouseReleased();
        }
        boolean popupScrubbing = popupVisible && overlay != null && overlay.isPopupProgressScrubbing();
        if ((!dragging && !scrubbing && !volumeScrubbing && !popupScrubbing)
                || client.currentScreen != activeScreen
                || overlay == null) return;
        long window = client.getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            if (popupScrubbing) {
                OptionalLong target = overlay.finishPopupProgressScrub();
                if (target.isPresent() && RelayLyricsClient.music() != null) {
                    RelayLyricsClient.music().command(MusicAction.SEEK, target.getAsLong());
                    overlay.seekTo(target.getAsLong());
                }
            }
            if (scrubbing) finishScrub();
            if (volumeScrubbing) updateVolumeDrag(volumeDragValue, true);
            volumeScrubbing = false;
            overlay.setPopupVolumeScrubbing(false);
            if (dragging) {
                dragging = false;
                overlay.mouseReleased();
            }
            return;
        }
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        if (popupScrubbing) {
            overlay.updatePopupProgressScrub(mouseX);
            return;
        }
        if (volumeScrubbing) {
            OptionalDouble volume = overlay.popupVolumeTargetAtY(mouseY);
            volume.ifPresent(value -> updateVolumeDrag(value, false));
            return;
        }
        if (scrubbing) {
            updateScrub(mouseX);
            return;
        }
        PreferenceBounds before = snapshotPreferences();
        overlay.mouseDragged(mouseX, mouseY);
        if (inventoryPopupEnabled()) keepOutsideGui(activeScreen, before);
    }

    /** Normal HUD pass, including the re-entry animation after an inventory closes. */
    public static void renderWorldHud(DrawContext context) {
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (overlay == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || !RelayLyricsClient.preferences().getLyricsRenderType()
                .rendersWorld(client.options.playerListKey.isPressed())) return;
        double visibility = popupTransitionVisibility();
        if (POPUP_TRANSITION.isFullyVisible()) {
            overlay.renderHud(context);
        } else if (visibility > .001) {
            overlay.renderHudTransition(context, visibility, false);
        }
    }

    private static boolean inventoryPopupEnabled() {
        return RelayLyricsClient.preferences().isInventoryPopup();
    }

    private static double popupTransitionVisibility() {
        return POPUP_TRANSITION.value(System.nanoTime(),
                RelayLyricsClient.preferences().isLyricsAnimations());
    }

    private static boolean inventoryPopupAvailable() {
        ClientPreferences preferences = RelayLyricsClient.preferences();
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (!screenPopupEnabled(activeScreen) || !preferences.isLyricsVisible()
                || !preferences.isLyricsPinned() || overlay == null) {
            selected = false;
            if (overlay != null) overlay.cancelPopupProgressScrub();
            return false;
        }
        boolean available = overlay.isHudVisible();
        if (!available) {
            selected = false;
            overlay.cancelPopupProgressScrub();
        }
        return available;
    }

    private static boolean screenPopupEnabled(Screen screen) {
        if (screen == null || !RelayLyricsClient.preferences().getLyricsRenderType().rendersScreen(screen)) {
            return false;
        }
        return !(screen instanceof HandledScreen<?>) || inventoryPopupEnabled();
    }

    private static PopupBounds popupBounds() {
        MinecraftClient client = MinecraftClient.getInstance();
        return RelayLyricsClient.overlay().popupBoundsSnapshot(
                client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
    }

    private static void updateCursor(DrawContext context, double mouseX, double mouseY,
                                     boolean popupVisible) {
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        PopupBounds popup = popupVisible ? popupBounds() : null;
        boolean resizeHover = popupVisible && selected
                && mouseX >= popup.x() + popup.width() - 12
                && mouseX <= popup.x() + popup.width() + 10
                && mouseY >= popup.y() + popup.height() - 12
                && mouseY <= popup.y() + popup.height() + 10;
        if (resizeHover) {
            PlatformCursor.request(context, PlatformCursor.Style.RESIZE);
            return;
        }
        if (dragging) {
            PlatformCursor.request(context, PlatformCursor.Style.GRABBING);
            return;
        }
        boolean interactive = contains(progressBarBounds, mouseX, mouseY)
                || contains(previousButtonBounds, mouseX, mouseY)
                || contains(playPauseButtonBounds, mouseX, mouseY)
                || contains(heartButtonBounds, mouseX, mouseY)
                || contains(nextButtonBounds, mouseX, mouseY);
        if (popupVisible) {
            interactive = interactive || overlay != null && (overlay.isPopupCoverInteractionActiveAt(mouseX, mouseY)
                    || overlay.seekTargetAtHud(mouseX, mouseY).isPresent());
        }
        boolean insidePopup = popupVisible
                && mouseX >= popup.x() && mouseX < popup.x() + popup.width()
                && mouseY >= popup.y() && mouseY < popup.y() + popup.height();
        if (interactive || insidePopup) PlatformCursor.request(context, PlatformCursor.Style.POINTER);
    }

    private static boolean contains(Bounds bounds, double mouseX, double mouseY) {
        return bounds != null && bounds.contains(mouseX, mouseY);
    }

    private static void renderNowPlaying(Screen screen, DrawContext context, int mouseX, int mouseY) {
        if (!RelayLyricsClient.preferences().isInventoryNowPlaying()
                || RelayLyricsClient.music() == null) {
            clearNowPlayingInteractions();
            return;
        }
        boolean creative = screen instanceof CreativeInventoryScreen;
        if (!creative && !(screen instanceof InventoryScreen)) {
            clearNowPlayingInteractions();
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        GuiBounds gui = guiBounds(screen);
        if (gui == null) {
            clearNowPlayingInteractions();
            return;
        }
        int guiLeft = gui.left;
        int guiTop = gui.top;
        int leftAvailable = guiLeft - 18;
        int rightAvailable = screenWidth - gui.right - 18;
        int available = Math.max(leftAvailable, rightAvailable);
        if (available < 38) {
            clearNowPlayingInteractions();
            return;
        }
        int cover = Math.clamp(Math.min(82, available - 8), 36, 82);
        int infoWidth = cover;
        int y = Math.max(8, guiTop);
        List<Rect> occupied = InventoryOverlayCompat.occupiedAreas(screen,
                new Rect(gui.left, gui.top, gui.right - gui.left, gui.bottom - gui.top));
        int leftX = guiLeft - cover - 10;
        int rightX = gui.right + 10;
        boolean useLeft = leftAvailable >= cover + 8 && areaFree(leftX, y, cover, cover, occupied);
        boolean useRight = rightAvailable >= cover + 8 && areaFree(rightX, y, cover, cover, occupied);
        if (!useLeft && !useRight) {
            clearNowPlayingInteractions();
            return;
        }
        int infoX = useLeft ? leftX : rightX;
        long animationElapsedNanos = System.nanoTime() - coverAnimationStartedNanos;
        double animationProgress = Math.clamp(animationElapsedNanos / 480_000_000.0, 0, 1);
        double eased = 1 - Math.pow(1 - animationProgress, 3);
        int startX = useLeft ? guiLeft + 4 : gui.right - cover - 4;
        int animatedX = (int) Math.round(startX + (infoX - startX) * eased);
        int coverRadius = RelayLyricsClient.overlay().morphRadius(
                RelayLyricsClient.preferences().isModernMode() ? Math.clamp(cover / 22, 9, 16) : 0);
        context.enableScissor(useLeft ? 0 : Math.min(screenWidth, gui.right), 0,
                useLeft ? Math.max(0, guiLeft) : screenWidth, screenHeight);
        try {
            if (RelayLyricsClient.preferences().isLyricsCoverShadow()) {
                RoundedGui.smoothFill(context, animatedX + 2, y + 6, cover, cover,
                        coverRadius, 0x14000000);
                RoundedGui.smoothFill(context, animatedX + 1, y + 4, cover, cover,
                        coverRadius, 0x1B000000);
                RoundedGui.smoothFill(context, animatedX, y + 2, cover, cover,
                        coverRadius, 0x24000000);
            }
            if (!RelayLyricsClient.music().drawArtworkTransition(
                    context, animatedX, y, cover, cover, coverRadius)) {
                if (coverRadius > 0) {
                    RoundedGui.smoothFill(context, animatedX, y, cover, cover,
                            coverRadius, 0xFF181B1D);
                } else {
                    RoundedGui.fill(context, animatedX, y, cover, cover,
                            coverRadius, 0xFF181B1D);
                }
            }
        } finally {
            context.disableScissor();
        }
        if (animationProgress < 1) {
            clearNowPlayingInteractions();
            return;
        }
        String title = RelayLyricsClient.music().snapshot().title();
        if (title == null || title.isBlank() || title.equals("Nothing playing")) {
            clearNowPlayingInteractions();
            return;
        }
        renderCoverControls(context, infoX, y, cover, mouseX, mouseY);
        double metadataFade = smoothStep(Math.clamp(
                (animationElapsedNanos - 480_000_000.0) / 340_000_000.0, 0, 1));
        String artist = RelayLyricsClient.music().snapshot().artist();
        long position = scrubbing ? scrubPosition : Math.max(0, RelayLyricsClient.music().positionMillis());
        long duration = Math.max(0, RelayLyricsClient.music().snapshot().durationMillis());
        if (duration > 0) position = Math.min(position, duration);
        int progressY = y + cover + 6;
        String currentLabel = cachedTimeLabel(position, false);
        String durationLabel = cachedTimeLabel(duration, true);
        float timeScale = .62F;
        int currentWidth = (int) Math.ceil(client.textRenderer.getWidth(currentLabel) * timeScale);
        int durationWidth = (int) Math.ceil(client.textRenderer.getWidth(durationLabel) * timeScale);
        int barLeft = infoX + currentWidth + 6;
        int barRight = infoX + infoWidth - durationWidth - 6;
        drawSmallText(context, currentLabel, infoX, progressY,
                fadeColor(0xFFD9E2E4, metadataFade), timeScale);
        drawSmallText(context, durationLabel, infoX + infoWidth - durationWidth,
                progressY, fadeColor(0xFFD9E2E4, metadataFade), timeScale);
        if (barRight - barLeft >= 8) {
            int barY = progressY + 1;
            progressBarBounds = metadataFade > .35
                    ? new Bounds(barLeft, barY - 3, barRight - barLeft, 10) : null;
            boolean hovered = progressBarBounds != null && progressBarBounds.contains(mouseX, mouseY);
            long progressNow = System.nanoTime();
            double progressDelta = Math.clamp((progressNow - progressFadeNanos) / 1_000_000_000.0, 0, .05);
            progressFadeNanos = progressNow;
            progressHoverFade = fade(progressHoverFade, hovered || scrubbing, progressDelta, 12);
            int drawnHeight = 4 + (int) Math.round(progressHoverFade * 3);
            int drawnY = barY - (drawnHeight - 4) / 2;
            int barRadius = RelayLyricsClient.overlay().morphRadius(
                    RelayLyricsClient.preferences().isModernMode() ? 2 : 0);
            int railColor = fadeColor(0xB0444C52, metadataFade);
            if (barRadius > 0) {
                RoundedGui.smoothFill(context, barLeft, drawnY, barRight - barLeft, drawnHeight,
                        barRadius, railColor);
            } else {
                RoundedGui.fill(context, barLeft, drawnY, barRight - barLeft, drawnHeight,
                        barRadius, railColor);
            }
            double progress = duration <= 0 ? 0 : scrubbing
                    ? Math.clamp(position / (double) duration, 0, 1)
                    : RelayLyricsClient.overlay().visualPlaybackProgress();
            int fillRight = barLeft + (int) Math.round((barRight - barLeft) * progress);
            if (fillRight > barLeft) {
                int fillColor = fadeColor(0xFFF2F5F5, metadataFade);
                if (barRadius > 0) {
                    RoundedGui.smoothFill(context, barLeft, drawnY,
                            fillRight - barLeft, drawnHeight, barRadius, fillColor);
                } else {
                    RoundedGui.fill(context, barLeft, drawnY,
                            fillRight - barLeft, drawnHeight, barRadius, fillColor);
                }
            }
        } else {
            progressBarBounds = null;
        }

        int titleY = Math.min(screenHeight - 20, progressY + 13);
        Text boldTitle = Text.literal(title).formatted(Formatting.BOLD);
        int titleWidth = client.textRenderer.getWidth(boldTitle);
        int titleX = titleWidth <= infoWidth ? infoX + (infoWidth - titleWidth) / 2
                : infoX - marqueeOffset(titleWidth - infoWidth, System.currentTimeMillis());
        context.enableScissor(infoX, titleY - 1, infoX + infoWidth, Math.min(screenHeight, titleY + 21));
        try {
            context.drawTextWithShadow(client.textRenderer, boldTitle, titleX, titleY,
                    fadeColor(0xFFFFFFFF, metadataFade));
            if (artist != null && !artist.isBlank()) {
                String fittedArtist = client.textRenderer.trimToWidth(artist, infoWidth);
                int artistX = infoX + (infoWidth - client.textRenderer.getWidth(fittedArtist)) / 2;
                context.drawTextWithShadow(client.textRenderer, Text.literal(fittedArtist), artistX,
                        titleY + 11, fadeColor(0xFFD1D1D1, metadataFade));
            }
        } finally {
            context.disableScissor();
        }
    }

    private static void renderCoverControls(DrawContext context, int x, int y, int cover,
                                            int mouseX, int mouseY) {
        Bounds coverBounds = new Bounds(x, y, cover, cover);
        boolean coverHovered = coverBounds.contains(mouseX, mouseY);
        int controlSize = Math.clamp((cover - 4) / 3, 10, 18);
        int gap = 2;
        int controlsWidth = controlSize * 3 + gap * 2;
        int controlsX = x + (cover - controlsWidth) / 2;
        int controlsY = y + cover - controlSize - Math.max(2, cover / 30);
        Bounds previousBounds = new Bounds(controlsX, controlsY, controlSize, controlSize);
        Bounds playPauseBounds = new Bounds(controlsX + controlSize + gap, controlsY, controlSize, controlSize);
        Bounds nextBounds = new Bounds(controlsX + (controlSize + gap) * 2,
                controlsY, controlSize, controlSize);

        int heartSize = cover >= 68 ? 30 : cover >= 46 ? 21 : 12;
        Bounds heartBounds = new Bounds(x + (cover - heartSize) / 2,
                y + (cover - heartSize) / 2 - Math.max(1, cover / 18), heartSize, heartSize);

        long now = System.nanoTime();
        double delta = Math.clamp((now - controlFadeNanos) / 1_000_000_000.0, 0, .05);
        controlFadeNanos = now;
        coverControlsFade = fade(coverControlsFade, coverHovered, delta, 10.0);
        previousHoverFade = fade(previousHoverFade,
                coverHovered && previousBounds.contains(mouseX, mouseY), delta, 13.0);
        playPauseHoverFade = fade(playPauseHoverFade,
                coverHovered && playPauseBounds.contains(mouseX, mouseY), delta, 13.0);
        heartHoverFade = fade(heartHoverFade,
                coverHovered && heartBounds.contains(mouseX, mouseY), delta, 13.0);
        nextHoverFade = fade(nextHoverFade,
                coverHovered && nextBounds.contains(mouseX, mouseY), delta, 13.0);

        previousButtonBounds = coverHovered ? previousBounds : null;
        playPauseButtonBounds = coverHovered ? playPauseBounds : null;
        heartButtonBounds = coverHovered ? heartBounds : null;
        nextButtonBounds = coverHovered ? nextBounds : null;
        if (coverControlsFade < .005) return;

        int shadeAlpha = (int) Math.round(142 * Math.clamp(coverControlsFade, 0, 1));
        int coverRadius = RelayLyricsClient.overlay().morphRadius(
                RelayLyricsClient.preferences().isModernMode() ? Math.clamp(cover / 10, 5, 10) : 0);
        if (coverRadius > 0) {
            RoundedGui.smoothFill(context, x, y, cover, cover, coverRadius, shadeAlpha << 24);
        } else {
            RoundedGui.fill(context, x, y, cover, cover, coverRadius, shadeAlpha << 24);
        }

        drawTransportControl(context, previousBounds, TransportIcon.PREVIOUS,
                controlColor(previousHoverFade, coverControlsFade));
        drawTransportControl(context, playPauseBounds,
                RelayLyricsClient.music().snapshot().playing() ? TransportIcon.PAUSE : TransportIcon.PLAY,
                controlColor(playPauseHoverFade, coverControlsFade));
        drawTransportControl(context, nextBounds, TransportIcon.NEXT,
                controlColor(nextHoverFade, coverControlsFade));
        boolean liked = RelayLyricsClient.music().snapshot().liked();
        drawHeart(context, heartBounds, liked,
                controlColor(heartHoverFade, coverControlsFade));
    }

    private static double fade(double current, boolean hovered, double delta, double speed) {
        double target = hovered ? 1 : 0;
        return current + (target - current) * (1 - Math.exp(-delta * speed));
    }

    private static int controlColor(double hover, double visibility) {
        int alpha = (int) Math.round((128 + 127 * Math.clamp(hover, 0, 1))
                * Math.clamp(visibility, 0, 1));
        return alpha << 24 | 0xFFFFFF;
    }

    private static int fadeColor(int color, double visibility) {
        int alpha = (int) Math.round((color >>> 24) * Math.clamp(visibility, 0, 1));
        return alpha << 24 | color & 0xFFFFFF;
    }

    private static void drawTransportControl(DrawContext context, Bounds bounds,
                                             TransportIcon icon, int color) {
        var image = switch (icon) {
            case PREVIOUS -> PopupIcons.PREVIOUS;
            case PLAY -> PopupIcons.PLAY;
            case PAUSE -> PopupIcons.PAUSE;
            case NEXT -> PopupIcons.NEXT;
        };
        PopupIcons.drawCentered(context, image, bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2, Math.max(8, bounds.height - 4), color);
    }

    private static void drawHeart(DrawContext context, Bounds bounds,
                                  boolean filled, int color) {
        PopupIcons.drawCentered(context, filled ? PopupIcons.HEART : PopupIcons.HEART_OUTLINE,
                bounds.x + bounds.width / 2, bounds.y + bounds.height / 2,
                bounds.width, color);
    }

    private static boolean runPopupControl(LyricsOverlay overlay, double mouseX, double mouseY) {
        if (overlay == null || RelayLyricsClient.music() == null) return false;
        LyricsOverlay.PopupControl control = overlay.popupControlAt(mouseX, mouseY);
        if (control == LyricsOverlay.PopupControl.TOGGLE_MUTE) {
            double current = overlay.popupVolumeLevel();
            if (current > .001) {
                lastAudibleVolume = current;
                updateVolumeDrag(0, true);
            } else {
                updateVolumeDrag(lastAudibleVolume, true);
            }
            return true;
        }
        MusicAction action = switch (control) {
            case TOGGLE_SHUFFLE -> MusicAction.TOGGLE_SHUFFLE;
            case PREVIOUS -> MusicAction.PREVIOUS;
            case PLAY_PAUSE -> MusicAction.PLAY_PAUSE;
            case TOGGLE_HEART -> MusicAction.TOGGLE_HEART;
            case NEXT -> MusicAction.NEXT;
            case CYCLE_REPEAT -> MusicAction.CYCLE_REPEAT;
            case NONE, SETTINGS, TOGGLE_MUTE -> null;
        };
        if (action == null) return false;
        RelayLyricsClient.music().command(action);
        return true;
    }

    private static void updateVolumeDrag(double value, boolean finalOrInitial) {
        volumeDragValue = Math.clamp(value, 0, 1);
        if (volumeDragValue > .001) lastAudibleVolume = volumeDragValue;
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (overlay != null) overlay.previewPopupVolume(volumeDragValue);
        if (RelayLyricsClient.music() == null) return;
        long now = System.nanoTime();
        if (!finalOrInitial && now - lastVolumeCommandNanos < 90_000_000L) return;
        if (!finalOrInitial && Math.abs(volumeDragValue - lastVolumeCommandValue) < .01) return;
        lastVolumeCommandNanos = now;
        lastVolumeCommandValue = volumeDragValue;
        RelayLyricsClient.music().command(MusicAction.SET_VOLUME, volumeDragValue);
    }

    private static boolean runNowPlayingControl(double mouseX, double mouseY) {
        if (!RelayLyricsClient.preferences().isInventoryNowPlaying()
                || RelayLyricsClient.music() == null) return false;
        MusicAction action = null;
        if (previousButtonBounds != null && previousButtonBounds.contains(mouseX, mouseY)) {
            action = MusicAction.PREVIOUS;
        } else if (playPauseButtonBounds != null && playPauseButtonBounds.contains(mouseX, mouseY)) {
            action = MusicAction.PLAY_PAUSE;
        } else if (heartButtonBounds != null && heartButtonBounds.contains(mouseX, mouseY)) {
            action = MusicAction.TOGGLE_HEART;
        } else if (nextButtonBounds != null && nextButtonBounds.contains(mouseX, mouseY)) {
            action = MusicAction.NEXT;
        }
        if (action == null) return false;
        RelayLyricsClient.music().command(action);
        return true;
    }

    private static void clearNowPlayingInteractions() {
        progressBarBounds = null;
        previousButtonBounds = null;
        playPauseButtonBounds = null;
        heartButtonBounds = null;
        nextButtonBounds = null;
    }

    private static void drawSmallText(DrawContext context, String value, int x, int y, int color, float scale) {
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, scale, scale);
        context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(value), 0, 0, color, true);
        GuiTransform.pop(context);
    }

    private static void updateScrub(double mouseX) {
        if (progressBarBounds == null || RelayLyricsClient.music() == null) return;
        scrubDuration = Math.max(0, RelayLyricsClient.music().snapshot().durationMillis());
        double fraction = Math.clamp((mouseX - progressBarBounds.x) / progressBarBounds.width, 0, 1);
        scrubPosition = Math.round(scrubDuration * fraction);
    }

    private static void finishScrub() {
        if (!scrubbing) return;
        scrubbing = false;
        if (scrubDuration > 0 && RelayLyricsClient.music() != null) {
            RelayLyricsClient.music().command(MusicAction.SEEK, scrubPosition);
        }
    }

    private static int marqueeOffset(int overflow, long nowMillis) {
        if (overflow <= 0) return 0;
        double travelMillis = Math.max(2_600, overflow * 76.0);
        double pauseMillis = 1_000;
        double cycle = pauseMillis * 2 + travelMillis * 2;
        double phase = nowMillis % cycle;
        if (phase < pauseMillis) return 0;
        phase -= pauseMillis;
        if (phase < travelMillis) {
            return (int) Math.round(overflow * smoothStep(phase / travelMillis));
        }
        phase -= travelMillis;
        if (phase < pauseMillis) return overflow;
        phase -= pauseMillis;
        return (int) Math.round(overflow * (1 - smoothStep(phase / travelMillis)));
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis / 1_000);
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds / 60 % 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        return minutes + ":" + twoDigits(seconds);
    }

    private static String cachedTimeLabel(long millis, boolean duration) {
        long second = Math.max(0, millis / 1_000);
        if (duration) {
            if (second != cachedDurationSecond) {
                cachedDurationSecond = second;
                cachedDurationLabel = formatTime(second * 1_000);
            }
            return cachedDurationLabel;
        }
        if (second != cachedPositionSecond) {
            cachedPositionSecond = second;
            cachedPositionLabel = formatTime(second * 1_000);
        }
        return cachedPositionLabel;
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private static double smoothStep(double value) {
        double clamped = Math.clamp(value, 0, 1);
        return clamped * clamped * (3 - 2 * clamped);
    }

    private static PreferenceBounds snapshotPreferences() {
        ClientPreferences preferences = RelayLyricsClient.preferences();
        return new PreferenceBounds(preferences.getLyricsX(), preferences.getLyricsY(),
                preferences.getLyricsWidth(), preferences.getLyricsHeight());
    }

    /** Makes the container plus EMI/REI sidebars solid obstacles during drag and resize. */
    private static void keepOutsideGui(Screen screen, PreferenceBounds previous) {
        if (!inventoryPopupEnabled() || RelayLyricsClient.preferences().isLyricsAllowGuiOverlap()) return;
        GuiBounds gui = guiBounds(screen);
        if (gui == null) return;
        Rect handled = new Rect(gui.left, gui.top, gui.right - gui.left, gui.bottom - gui.top);
        for (Rect area : InventoryOverlayCompat.occupiedAreas(screen, handled)) {
            keepOutsideObstacle(area, previous);
            previous = snapshotPreferences();
        }
    }

    private static void keepOutsideObstacle(Rect area, PreferenceBounds previous) {
        ClientPreferences preferences = RelayLyricsClient.preferences();
        MinecraftClient client = MinecraftClient.getInstance();
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        int x = preferences.getLyricsX();
        int y = preferences.getLyricsY();
        int width = Math.min(preferences.getLyricsWidth(), windowWidth);
        int height = Math.min(preferences.getLyricsHeight(), windowHeight);
        Rect expanded = area.expanded(5);
        GuiBounds obstacle = new GuiBounds(expanded.x(), expanded.y(), expanded.right(), expanded.bottom());
        boolean resizedOnly = x == previous.x && y == previous.y
                && (width != previous.width || height != previous.height);
        boolean overlapsVertically = y < obstacle.bottom && y + height > obstacle.top;
        boolean overlapsHorizontally = x < obstacle.right && x + width > obstacle.left;
        if (previous.x + previous.width <= obstacle.left && x + width > obstacle.left
                && overlapsVertically) {
            int allowed = obstacle.left - x;
            if (resizedOnly && allowed >= 160) {
                preferences.setLyricsWidth(Math.min(width, allowed));
                return;
            }
            preferences.setLyricsX(Math.clamp(obstacle.left - width, 0, Math.max(0, windowWidth - width)));
            return;
        }
        if (previous.x >= obstacle.right && x < obstacle.right && overlapsVertically) {
            preferences.setLyricsX(Math.clamp(obstacle.right, 0, Math.max(0, windowWidth - width)));
            return;
        }
        if (previous.y + previous.height <= obstacle.top && y + height > obstacle.top
                && overlapsHorizontally) {
            int allowed = obstacle.top - y;
            if (resizedOnly && allowed >= 100) {
                preferences.setLyricsHeight(Math.min(height, allowed));
                return;
            }
            preferences.setLyricsY(Math.clamp(obstacle.top - height, 0, Math.max(0, windowHeight - height)));
            return;
        }
        if (previous.y >= obstacle.bottom && y < obstacle.bottom && overlapsHorizontally) {
            preferences.setLyricsY(Math.clamp(obstacle.bottom, 0, Math.max(0, windowHeight - height)));
            return;
        }
        if (!intersects(x, y, width, height, obstacle)) return;

        if (resizedOnly && previous.x + previous.width <= obstacle.left) {
            int allowed = obstacle.left - x;
            if (allowed >= 160) {
                preferences.setLyricsWidth(Math.min(width, allowed));
                return;
            }
        }
        if (resizedOnly && previous.y + previous.height <= obstacle.top) {
            int allowed = obstacle.top - y;
            if (allowed >= 100) {
                preferences.setLyricsHeight(Math.min(height, allowed));
                return;
            }
        }

        if (previous.x + previous.width <= obstacle.left) {
            x = obstacle.left - width;
        } else if (previous.x >= obstacle.right) {
            x = obstacle.right;
        } else if (previous.y + previous.height <= obstacle.top) {
            y = obstacle.top - height;
        } else if (previous.y >= obstacle.bottom) {
            y = obstacle.bottom;
        } else {
            Placement closest = closestPlacement(x, y, width, height, obstacle, windowWidth, windowHeight);
            x = closest.x;
            y = closest.y;
        }
        preferences.setLyricsX(Math.clamp(x, 0, Math.max(0, windowWidth - width)));
        preferences.setLyricsY(Math.clamp(y, 0, Math.max(0, windowHeight - height)));
    }

    private static Placement closestPlacement(int x, int y, int width, int height, GuiBounds obstacle,
                                               int windowWidth, int windowHeight) {
        Placement[] candidates = {
                new Placement(obstacle.left - width, y),
                new Placement(obstacle.right, y),
                new Placement(x, obstacle.top - height),
                new Placement(x, obstacle.bottom)
        };
        Placement best = new Placement(x, y);
        long bestDistance = Long.MAX_VALUE;
        for (Placement candidate : candidates) {
            int candidateX = Math.clamp(candidate.x, 0, Math.max(0, windowWidth - width));
            int candidateY = Math.clamp(candidate.y, 0, Math.max(0, windowHeight - height));
            if (intersects(candidateX, candidateY, width, height, obstacle)) continue;
            long dx = candidateX - (long) x;
            long dy = candidateY - (long) y;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Placement(candidateX, candidateY);
            }
        }
        return best;
    }

    private static GuiBounds guiBounds(Screen screen) {
        if (!(screen instanceof HandledScreenAccessor accessor)) return null;
        int left = accessor.relaylyrics$getX();
        int top = accessor.relaylyrics$getY();
        return new GuiBounds(left, top, left + accessor.relaylyrics$getBackgroundWidth(),
                top + accessor.relaylyrics$getBackgroundHeight());
    }

    private static boolean intersects(int x, int y, int width, int height, GuiBounds bounds) {
        return x < bounds.right && x + width > bounds.left
                && y < bounds.bottom && y + height > bounds.top;
    }

    private static boolean areaFree(int x, int y, int width, int height, List<Rect> occupied) {
        for (Rect rect : occupied) {
            Rect obstacle = rect.expanded(4);
            if (x < obstacle.right() && x + width > obstacle.x()
                    && y < obstacle.bottom() && y + height > obstacle.y()) return false;
        }
        return true;
    }

    private static void outline(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record Bounds(int x, int y, int width, int height) {
        private boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private record PreferenceBounds(int x, int y, int width, int height) { }
    private record Placement(int x, int y) { }
    private enum TransportIcon { PREVIOUS, PLAY, PAUSE, NEXT }
    private record GuiBounds(int left, int top, int right, int bottom) {
        private GuiBounds expanded(int pixels) {
            return new GuiBounds(left - pixels, top - pixels, right + pixels, bottom + pixels);
        }
    }
}
