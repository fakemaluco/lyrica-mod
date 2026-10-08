package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.GoogleFontManager;
import dev.relaylyrics.client.LyricsSettingsCatalog;
import dev.relaylyrics.client.LyricsSettingsCatalog.Category;
import dev.relaylyrics.client.LyricsSettingsCatalog.Entry;
import dev.relaylyrics.client.LyricsSettingsCatalog.Kind;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.MusicService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Widget adapter for the version-independent settings catalog. */
public final class LyricsConfigScreen extends FanDroidScreenBase implements LyricsSettingsCatalog.Actions {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final LyricsSettingsCatalog catalog;
    private final List<Row> rows = new ArrayList<>();
    private Category category = Category.GENERAL;
    private String expandedChoice = "";
    private boolean confirmRestore, editing, dragging, resizing;
    private int dragX, dragY, editX, editY, editWidth, editHeight;
    private int rowLeft, rowWidth, listTop, listBottom, totalHeight, scroll;
    private boolean sidePreview, stacked;
    private int previewX, previewWidth;
    private int saveDelay;
    private String notice = "";

    public LyricsConfigScreen(Screen parent) { this(parent, RelayLyricsClient.preferences()); }
    LyricsConfigScreen(Screen parent, ClientPreferences preferences) {
        super(Component.translatable("screen.relaylyrics.config")); this.parent = parent;
        this.preferences = preferences; this.catalog = new LyricsSettingsCatalog(preferences, this);
    }
    private void savePreferences() { if (preferences == RelayLyricsClient.preferences()) preferences.save(); }
    @Override protected void init() { rebuild(); }

    private void rebuild() {
        clearWidgets(); rows.clear();
        if (category == Category.WORLD && !preferences.isWorldLyrics()) category = Category.GENERAL;
        if (confirmRestore) { buildRestoreConfirmation(); return; }
        if (editing) { buildEditor(); return; }
        int fullWidth = Math.min(1120, width - 32);
        int left = (width - fullWidth) / 2;
        sidePreview = width >= 1080 && height >= 440 && category != Category.CONNECTION;
        rowLeft = left;
        rowWidth = fullWidth;
        if (sidePreview) {
            previewWidth = Math.min(350, rowWidth / 3);
            rowWidth -= previewWidth + 20;
            previewX = rowLeft + rowWidth + 20;
        }
        stacked = rowWidth < 480;
        List<Category> categories = catalog.categories();
        int columns = Math.min(categories.size(), Math.max(2, fullWidth / 112));
        int tabWidth = (fullWidth - (columns - 1) * 4) / columns;
        for (int i = 0; i < categories.size(); i++) {
            Category candidate = categories.get(i);
            var tab = button(Component.translatable(candidate.key), left + (i % columns) * (tabWidth + 4),
                    33 + (i / columns) * 24, tabWidth, () -> changeCategory(candidate));
            tab.active = candidate != category;
        }
        listTop = 43 + ((categories.size() + columns - 1) / columns) * 24;
        int noticeLines = notice.isEmpty() ? 1 : font.split(text(notice), width - 48).size();
        listBottom = height - 48 - Math.max(0, noticeLines - 1) * 10;
        int offset = 0;
        for (Entry entry : catalog.entries(category)) {
            int labelWidth = stacked ? rowWidth - 12 : rowWidth - controlWidth() - 22;
            var labelLines = font.split(Component.translatable(entry.key), labelWidth);
            int labelHeight = Math.max(9, labelLines.size() * 10);
            int rowHeight = stacked ? labelHeight + 29 : Math.max(34, labelHeight + 12);
            boolean hint = !stacked && hasTranslation(entry.key + ".hint") && labelLines.size() == 1;
            boolean expanded = entry.kind == Kind.CHOICE && entry.key.equals(expandedChoice);
            Row row = new Row(entry, offset, rowHeight, expanded ? entry.options.size() * 24 + 4 : 0, labelWidth, hint);
            rows.add(row); createControl(row); offset += row.height + 4;
        }
        totalHeight = offset;
        scroll = Math.clamp(scroll, 0, maxScroll());
        positionRows();
        button(Component.translatable("gui.done"), left + fullWidth - 128, height - 28, 128, this::onClose);
    }

    private static boolean hasTranslation(String key) { return !I18n.get(key).equals(key); }
    private int controlWidth() { return stacked ? rowWidth - 12 : Math.clamp(rowWidth * 43 / 100, 160, 280); }
    private Button button(Component text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(text, ignored -> action.run()).bounds(x, y, w, 20).build());
    }
    private void createControl(Row row) {
        Entry e = row.entry;
        int x = stacked ? rowLeft + 6 : rowLeft + rowWidth - controlWidth() - 6;
        int w = controlWidth();
        if (e.kind == Kind.SLIDER) {
            row.add(button(Component.literal("−"), x, 0, 20, () -> setNumber(e, e.number.getAsDouble() - e.step, true)), 0);
            row.add(addRenderableWidget(new SettingSlider(x + 23, 0, w - 46, e)), 0);
            row.add(button(Component.literal("+"), x + w - 20, 0, 20, () -> setNumber(e, e.number.getAsDouble() + e.step, true)), 0);
        } else {
            Button control = button(valueText(e), x, 0, w, () -> {
                if (e.kind == Kind.CHOICE) openChoice(e);
                else { e.activate.run(); changed(e, true); }
            });
            control.active = e.kind != Kind.INFO;
            row.add(control, 0);
        }
        if (hasTranslation(e.key + ".desc"))
            for (var widget : row.widgets) widget.setTooltip(Tooltip.create(Component.translatable(e.key + ".desc")));
        if (e.key.equals(expandedChoice) && e.kind == Kind.CHOICE) {
            for (int i = 0; i < e.options.size(); i++) {
                int index = i;
                Component label = text(e.options.get(i));
                if (i == e.selected.getAsInt()) label = Component.literal("✓ ").append(label);
                row.add(button(label, x, 0, w, () -> {
                    expandedChoice = "";
                    e.select.accept(index); changed(e, true); rebuild();
                }), row.baseHeight + 4 + i * 24);
            }
        }
    }
    private Component valueText(Entry entry) {
        Component value = text(entry.value.get());
        return entry.kind == Kind.CHOICE ? value.copy().append(entry.key.equals(expandedChoice) ? "  ▴" : "  ▾") : value;
    }
    private static Component text(String value) {
        if (value == null || value.isBlank()) return Component.literal("—");
        return value.startsWith("@") ? Component.translatable(value.substring(1)) : Component.literal(value);
    }
    private void positionRows() {
        for (Row row : rows) {
            int top = listTop + row.offset - scroll;
            int controlY = stacked ? row.baseHeight - 24 : (row.baseHeight - 20) / 2;
            for (int i = 0; i < row.widgets.size(); i++) {
                var widget = row.widgets.get(i);
                int localY = row.widgetOffsets.get(i);
                int y = top + (localY == 0 ? controlY : localY);
                widget.setY(y); widget.visible = y >= listTop && y + widget.getHeight() <= listBottom;
            }
        }
    }
    private int maxScroll() { return Math.max(0, totalHeight - (listBottom - listTop)); }
    private void changeCategory(Category next) { category = next; scroll = 0; expandedChoice = ""; notice = ""; rebuild(); }
    private void openChoice(Entry entry) {
        expandedChoice = entry.key.equals(expandedChoice) ? "" : entry.key;
        rebuild();
        // Keep the clicked control and the beginning of its options together.
        for (Row row : rows) if (row.entry.key.equals(expandedChoice)) {
            int wanted = row.offset + Math.min(row.height, (listBottom - listTop) / 2) - (listBottom - listTop);
            scroll = Math.clamp(Math.max(scroll, wanted), 0, Math.min(row.offset, maxScroll()));
        }
        positionRows();
    }
    private void buildRestoreConfirmation() {
        int w = Math.min(440, width - 48), left = (width - w) / 2;
        button(Component.translatable("config.relaylyrics.restore"), left, height - 58, w, () -> {
            catalog.restore("all"); confirmRestore = false; scroll = 0; savePreferences(); rebuild();
        });
        button(Component.translatable("gui.cancel"), left, height - 28, w, () -> { confirmRestore = false; rebuild(); });
    }
    private void buildEditor() {
        int left = Math.max(12, (width - 500) / 2);
        button(Component.translatable("gui.back"), width - left - 128, height - 28, 128,
                () -> { editing = false; rebuild(); });
        if (editing) {
            editWidth = Math.min(preferences.getLyricsWidth(), width - 24);
            int top = height < 240 ? 30 : 52;
            editHeight = Math.min(preferences.getLyricsHeight(), Math.max(100, height - top - 44));
            editX = Math.clamp(preferences.getLyricsX(), 12, Math.max(12, width - editWidth - 12));
            editY = Math.clamp(preferences.getLyricsY(), top, Math.max(top, height - editHeight - 44));
            button(Component.translatable("config.relaylyrics.reset_layout"), left, height - 28, Math.min(180, width - left * 2 - 136),
                    () -> { preferences.resetLayout(); rebuild(); }).setTooltip(Tooltip.create(Component.translatable("config.relaylyrics.adjust_layout.hint")));
        }
    }
    private void setNumber(Entry e, double number, boolean immediate) {
        number = Math.clamp(number, e.min, e.max);
        double snapped = e.min + Math.round((number - e.min) / e.step) * e.step;
        e.setNumber.accept(Math.clamp(snapped, e.min, e.max)); changed(e, immediate);
        for (Row row : rows) if (row.entry == e)
            for (var widget : row.widgets) if (widget instanceof SettingSlider slider) slider.refresh();
    }
    private void changed(Entry e, boolean immediate) {
        if (immediate) { savePreferences(); saveDelay = 0; } else saveDelay = 10;
        if (e.changesStructure) rebuild(); else refreshValues();
    }
    private void refreshValues() {
        for (Row row : rows) {
            if (row.entry.kind == Kind.SLIDER) continue;
            var control = row.widgets.get(0);
            Component next = valueText(row.entry);
            if (!next.equals(control.getMessage())) control.setMessage(next);
            if (row.entry.kind == Kind.INFO) control.setTooltip(Tooltip.create(text(row.entry.value.get())));
        }
    }
    @Override public void tick() {
        if (saveDelay > 0 && --saveDelay == 0) savePreferences();
        refreshValues();
    }

    @Override public void run(String action) {
        MusicService music = RelayLyricsClient.music();
        switch (action) {
            case "font" -> Minecraft.getInstance().gui.setScreen(new GoogleFontScreen(this, preferences, this::fontChanged));
            case "weight" -> {
                String family = preferences.getGoogleFontFamily();
                if (!family.isEmpty()) GoogleFontManager.restore(family, preferences.getLyricsFontWeight().cssWeight(), this::fontChanged);
                fontChanged();
            }
            case "font_changed" -> fontChanged();
            case "layout" -> { editing = true; rebuild(); }
            case "custom_effects", "custom_world" -> { category = Category.CUSTOM; scroll = 0; expandedChoice = ""; rebuild(); }
            case "source" -> { if (music != null) music.syncMusicSource(); }
            case "spotify" -> { if (music != null) Minecraft.getInstance().gui.setScreen(new SpotifyAccountScreen(this, preferences, music)); }
            case "youtube" -> Minecraft.getInstance().gui.setScreen(new YouTubeMusicConnectionScreen(this, preferences, music));
            case "connection_setup" -> {
                if (preferences.getMusicSource() == MusicSource.YOUTUBE_MUSIC) run("youtube");
                else if (preferences.getMusicSource() == MusicSource.WINDOWS_MEDIA || preferences.getMusicSource() == MusicSource.SPICETIFY_BRIDGE) {
                    catalog.expand("connection_details"); notice = "@config.relaylyrics.connection.guide." + preferences.getMusicSource().name().toLowerCase(java.util.Locale.ROOT); rebuild();
                } else run("spotify");
            }
            case "retry" -> { if (music != null) { music.syncMusicSource(); music.retryLyrics(); } }
            case "audio" -> { if (music != null) music.reloadReactiveAudioOutput(); }
            case "restore_all" -> { confirmRestore = true; rebuild(); }
            default -> throw new IllegalArgumentException("Unknown settings action: " + action);
        }
    }
    private void fontChanged() {
        if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().fontChanged();
    }
    @Override public String status(String name) {
        MusicService music = RelayLyricsClient.music();
        if (music == null) return "@config.relaylyrics.offline";
        var playback = music.snapshot();
        return switch (name) {
            case "connection" -> RelayLyricsClient.disabledByRelay() ? "@config.relaylyrics.managed_by_relay"
                    : preferences.getMusicSource() == MusicSource.SPOTIFY_ACCOUNT
                    || preferences.getMusicSource() == MusicSource.AUTOMATIC && !music.bridgeAlive() && music.spotifyAccountConnected()
                    ? music.spotifyAccountStatus()
                    : (preferences.getMusicSource() == MusicSource.YOUTUBE_MUSIC ? music.youtubeMusicConnected()
                    : preferences.getMusicSource() == MusicSource.SPOTIFY_ACCOUNT ? music.spotifyAccountConnected()
                    : preferences.getMusicSource() == MusicSource.SPICETIFY_BRIDGE ? music.spicetifyConnected()
                    : music.bridgeAlive() || music.spotifyAccountConnected())
                    ? "@config.relaylyrics.connected" : "@config.relaylyrics.needs_setup";
            case "method" -> music.spotifyPlaybackActive() ? "@config.relaylyrics.connection.method.account"
                    : music.youtubeMusicConnected() ? "@config.relaylyrics.connection.method.youtube"
                    : music.spicetifyConnected() ? "@config.relaylyrics.connection.method.spicetify"
                    : "@config.relaylyrics.connection.method.bridge";
            case "device" -> playback.deviceName();
            case "playback" -> playback.title() == null || playback.title().equals("Nothing playing")
                    ? "@config.relaylyrics.waiting_music" : playback.title() + " — " + playback.artist();
            case "lyrics" -> music.lyricsStatusDetail();
            case "code" -> music.pairingCode();
            case "port" -> music.pairingPort() <= 0 ? "" : Integer.toString(music.pairingPort());
            case "extension" -> music.spicetifyExtensionVersion();
            case "track" -> playback.spotifyUri();
            case "match" -> music.spotifyTrackMatchStatus();
            default -> "";
        };
    }

    private void drawCurrentSong(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay != null && overlay.hasCurrentSong()) {
            // Share the playing song, clock, artwork and prepared effects. Settings
            // never create fake playback or own the live renderer's resources.
            overlay.renderCurrentSongPreview(context, x, y, w, h, preferences);
        } else {
            drawBorder(context, x, y, w, h, 0x446F7F96);
            int lineY = y + h / 2 - 10;
            for (var line : font.split(Component.translatable("config.relaylyrics.current_song.empty"), w - 24)) {
                context.text(font, line, x + (w - font.width(line)) / 2, lineY, 0xFFBEC9DA); lineY += 12;
            }
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        centered(context, title, width / 2, 14, 0xFFFFFFFF);
        if (confirmRestore) {
            int w = Math.min(440, width - 48), y = 66;
            for (var line : font.split(Component.translatable("config.relaylyrics.defaults.confirm"), w)) {
                context.text(font, line, (width - w) / 2, y, 0xFFE4E8F0); y += 13;
            }
            return;
        }
        if (editing) {
            drawCurrentSong(context, editX, editY, editWidth, editHeight);
            drawBorder(context, editX - 1, editY - 1, editWidth + 2, editHeight + 2, 0xFF95B8FF);
            context.fill(editX + editWidth - 12, editY + editHeight - 12, editX + editWidth, editY + editHeight, 0xFF95B8FF);
            if (height < 240) return;
            int lineY = height - 46;
            var hints = font.split(Component.translatable("config.relaylyrics.adjust_layout.hint"), width - 32);
            lineY -= Math.max(0, hints.size() - 1) * 10;
            for (var hint : hints) {
                context.text(font, hint, (width - font.width(hint)) / 2, lineY, 0xFFBEC9DA); lineY += 10;
            }
            return;
        }
        context.enableScissor(rowLeft, listTop, rowLeft + rowWidth, listBottom);
        for (Row row : rows) {
            int top = listTop + row.offset - scroll;
            if (top + row.height < listTop || top > listBottom) continue;
            if (row.entry.key.equals(expandedChoice)) {
                int x = stacked ? rowLeft + 3 : rowLeft + rowWidth - controlWidth() - 10;
                context.fill(x, top + row.baseHeight + 3, x + 2, top + row.height, 0xFF95B8FF);
            }
            context.fill(rowLeft + 6, top + row.height + 1, rowLeft + rowWidth - 6, top + row.height + 2, 0x225F6B80);
            int y = stacked ? top + 2 : top + 7;
            for (var line : font.split(Component.translatable(row.entry.key), row.labelWidth)) {
                context.text(font, line, rowLeft + 6, y, row.entry.kind == Kind.GROUP ? 0xFF95B8FF : 0xFFE2E6ED); y += 10;
            }
            if (row.hint) {
                String hint = I18n.get(row.entry.key + ".hint");
                context.text(font, font.plainSubstrByWidth(hint, row.labelWidth), rowLeft + 6, y + 2, 0xFF8E9EB6);
            }
        }
        context.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom - listTop, thumb = Math.max(16, track * track / totalHeight);
            int y = listTop + (track - thumb) * scroll / maxScroll();
            context.fill(rowLeft + rowWidth + 3, listTop, rowLeft + rowWidth + 5, listBottom, 0x554D586C);
            context.fill(rowLeft + rowWidth + 3, y, rowLeft + rowWidth + 5, y + thumb, 0xFF95B8FF);
        }
        if (sidePreview) {
            centered(context, Component.translatable("config.relaylyrics.current_song"), previewX + previewWidth / 2, listTop + 2, 0xFFBEC9DA);
            drawCurrentSong(context, previewX, listTop + 23, previewWidth, Math.min(270, height - listTop - 85));
        }
        if (!notice.isEmpty()) {
            var message = font.split(text(notice), width - 48);
            int y = height - 38 - Math.max(0, message.size() - 1) * 10;
            for (var line : message) { context.text(font, line, (width - font.width(line)) / 2, y, 0xFF95B8FF); y += 10; }
        }
    }
    private void centered(GuiGraphicsExtractor context, Component text, int x, int y, int color) {
        context.text(font, text, x - font.width(text) / 2, y, color);
    }
    private static void drawBorder(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color); context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color); context.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0) return false;
        if (editing || x < rowLeft || x > rowLeft + rowWidth + 10 || y < listTop || y > listBottom) return false;
        scroll = Math.clamp(scroll + (vertical > 0 ? -40 : 40), 0, maxScroll()); setFocused(null); positionRows(); return true;
    }
    @Override protected boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (!expandedChoice.isEmpty() || confirmRestore || editing)) {
            expandedChoice = ""; confirmRestore = false; editing = false; rebuild(); return true;
        }
        if (editing && key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_UP) {
            int dx = key == GLFW.GLFW_KEY_RIGHT ? 1 : key == GLFW.GLFW_KEY_LEFT ? -1 : 0;
            int dy = key == GLFW.GLFW_KEY_DOWN ? 1 : key == GLFW.GLFW_KEY_UP ? -1 : 0;
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) { editWidth += dx; editHeight += dy; }
            else { editX += dx; editY += dy; }
            persistEdit(); return true;
        }
        if (!editing && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            scroll = Math.clamp(scroll + (key == GLFW.GLFW_KEY_PAGE_UP ? -120 : 120), 0, maxScroll()); setFocused(null); positionRows(); return true;
        }
        return false;
    }
    @Override protected boolean handleMouseClicked(double x, double y, int button) {
        if (!editing || button != 0 || x < editX || y < editY || x > editX + editWidth || y > editY + editHeight) return false;
        resizing = x > editX + editWidth - 18 && y > editY + editHeight - 18;
        dragging = !resizing; dragX = (int) x; dragY = (int) y; return true;
    }
    @Override protected boolean handleMouseDragged(double x, double y, int button, double dx, double dy) {
        if (!editing || (!resizing && !dragging)) return false;
        int mx = (int) x, my = (int) y;
        if (resizing) { editWidth += mx - dragX; editHeight += my - dragY; }
        else { editX += mx - dragX; editY += my - dragY; }
        dragX = mx; dragY = my; persistEdit(); return true;
    }
    private void persistEdit() {
        editWidth = Math.clamp(editWidth, 160, Math.min(900, width - 24));
        int top = height < 240 ? 30 : 52;
        editHeight = Math.clamp(editHeight, 100, Math.min(600, Math.max(100, height - top - 44)));
        editX = Math.clamp(editX, 12, Math.max(12, width - editWidth - 12));
        editY = Math.clamp(editY, top, Math.max(top, height - editHeight - 44));
        preferences.setLyricsX(editX); preferences.setLyricsY(editY);
        preferences.setLyricsWidth(editWidth); preferences.setLyricsHeight(editHeight); saveDelay = 10;
    }
    @Override protected boolean handleMouseReleased(double x, double y, int button) {
        boolean handled = dragging || resizing; dragging = false; resizing = false;
        if (saveDelay > 0) { savePreferences(); saveDelay = 0; } return handled;
    }
    /** Inventory input adapter compatibility; values have explicit minus buttons now. */
    public boolean handleSecondaryClick(double x, double y, int button) { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { savePreferences(); Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void removed() {
        savePreferences();
        if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().releaseCurrentSongPreview();
        super.removed();
    }

    public static void verifyWorldTabVisibility() {
        var p = RelayLyricsClient.preferences(); boolean original = p.isWorldLyrics();
        try {
            LyricsConfigScreen screen = new LyricsConfigScreen(null);
            p.setWorldLyrics(false);
            if (screen.catalog.categories().contains(Category.WORLD)) throw new IllegalStateException("World category shown while disabled");
            screen.catalog.expand("experimental");
            Entry toggle = screen.catalog.entries(Category.ADVANCED).stream().filter(e -> e.key.endsWith(".world_lyrics")).findFirst().orElseThrow();
            toggle.activate.run();
            if (!screen.catalog.categories().contains(Category.WORLD)) throw new IllegalStateException("World category missing after activation");
            toggle.activate.run();
            if (screen.catalog.categories().contains(Category.WORLD)) throw new IllegalStateException("World category remains after deactivation");
        } finally { p.setWorldLyrics(original); }
    }
    /** Called only by the opt-in screenshot fixture, using disposable preferences. */
    void configureProbe(String stage) {
        category = switch (stage) {
            case "appearance", "choices" -> Category.APPEARANCE;
            case "connection" -> Category.CONNECTION;
            case "advanced", "experimental" -> Category.ADVANCED;
            case "custom", "effects" -> Category.CUSTOM;
            case "world", "world_fixed" -> Category.WORLD;
            default -> Category.GENERAL;
        };
        if (stage.equals("experimental")) catalog.expand("experimental");
        if (stage.equals("effects")) catalog.expand("effects");
        if (stage.equals("world") || stage.equals("world_fixed")) {
            preferences.setWorldLyrics(true); preferences.setWorldLyricsAutomatic(!stage.equals("world_fixed"));
        }
        editing = stage.equals("editor");
        rebuild();
        if (stage.equals("choices")) openChoice(catalog.entries(category).stream().filter(e -> e.key.endsWith(".effects_preset")).findFirst().orElseThrow());
        if (stage.equals("categories")) openChoice(catalog.entries(category).stream().filter(e -> e.kind == Kind.CHOICE).findFirst().orElseThrow());
    }
    void verifyLayoutForProbe() {
        for (Row row : rows) for (var widget : row.widgets) {
            if (!widget.visible) continue;
            if (widget.getX() < 0 || widget.getY() < listTop || widget.getX() + widget.getWidth() > width
                    || widget.getY() + widget.getHeight() > listBottom) throw new IllegalStateException("Setting outside viewport: " + row.entry.key);
            if (!hasTranslation(row.entry.key)) throw new IllegalStateException("Untranslated setting: " + row.entry.key);
        }
        if (maxScroll() > 0 && !editing) {
            int original = scroll;
            handleKeyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
            if (scroll <= original && original < maxScroll()) throw new IllegalStateException("Page Down did not scroll settings");
            scroll = original; positionRows();
        }
        if (editing) {
            int x = editX, w = editWidth;
            handleKeyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
            if (editX == x && editX + editWidth + 12 < width) throw new IllegalStateException("Keyboard move failed");
            handleKeyPressed(GLFW.GLFW_KEY_RIGHT, 0, GLFW.GLFW_MOD_SHIFT);
            if (editWidth == w && editWidth < Math.min(900, width - 24)) throw new IllegalStateException("Keyboard resize failed");
        }
    }
    private static final class Row {
        final Entry entry; final int offset, baseHeight, height, labelWidth; final boolean hint;
        final List<AbstractWidget> widgets = new ArrayList<>();
        final List<Integer> widgetOffsets = new ArrayList<>();
        Row(Entry entry, int offset, int baseHeight, int extensionHeight, int labelWidth, boolean hint) {
            this.entry = entry; this.offset = offset; this.baseHeight = baseHeight; this.height = baseHeight + extensionHeight; this.labelWidth = labelWidth; this.hint = hint;
        }
        void add(AbstractWidget widget, int offset) { widgets.add(widget); widgetOffsets.add(offset); }
    }
    private final class SettingSlider extends AbstractSliderButton {
        private final Entry entry;
        SettingSlider(int x, int y, int width, Entry entry) {
            super(x, y, width, 20, text(entry.value.get()), (entry.number.getAsDouble() - entry.min) / (entry.max - entry.min)); this.entry = entry;
        }
        @Override protected void updateMessage() { if (entry != null) setMessage(text(entry.value.get())); }
        @Override protected void applyValue() { setNumber(entry, entry.min + value * (entry.max - entry.min), false); }
        void refresh() { value = (entry.number.getAsDouble() - entry.min) / (entry.max - entry.min); updateMessage(); }
    }
}
