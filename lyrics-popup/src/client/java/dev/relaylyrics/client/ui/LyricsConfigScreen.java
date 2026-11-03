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
import dev.relaylyrics.client.music.PlatformTextures;
import dev.relaylyrics.client.music.ResponsiveLyricsLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Native, keyboard-accessible adaptation of Spicy Lyrics' centered purple settings panel.
 * Skin and proportions follow spicy-lyrics/settings-panel.css, not the vanilla game menu.
 */
public final class LyricsConfigScreen extends FanDroidScreenBase implements LyricsSettingsCatalog.Actions {
    private static final Identifier UI_FONT = Identifier.of("relaylyrics", "settings");
    private static final Identifier UI_BOLD = Identifier.of("relaylyrics", "settings_semibold");
    private static final Identifier UI_COMPACT = Identifier.of("relaylyrics", "settings_compact");
    private static final Identifier UI_COMPACT_BOLD = Identifier.of("relaylyrics", "settings_compact_semibold");
    private static final int INK = 0xFFD9CAFF, MUTED = 0xFFB9AFD7, TERTIARY = 0xFF87779F;
    private static final int FIELD = 0xF2342057, DEEP = 0xF523133E, HAIRLINE = 0x22D9CAFF;
    private final Screen parent;
    private final ClientPreferences preferences;
    private final LyricsSettingsCatalog catalog;
    private final List<Row> rows = new ArrayList<>();
    private final java.util.Map<String, Row> rowViews = new LinkedHashMap<>();
    private final java.util.Map<ClickableWidget, Row> rowOwners = new java.util.IdentityHashMap<>();
    private final SettingsListMotion listMotion = new SettingsListMotion();
    private final List<PanelButton> navigation = new ArrayList<>();
    private Category category = Category.GENERAL;
    private String expandedChoice = "", searchQuery = "", revealChoice = "";
    private boolean confirmRestore, editing, dragging, resizing, peeking, selectingSearch;
    private int dragX, dragY, editX, editY, editWidth, editHeight;
    private boolean resizeSquareVideo;
    private double resizeVideoAspect;
    private dev.relaylyrics.client.music.PopupResizeGesture.Start editorResizeStart;
    private int modalX, modalY, modalWidth, modalHeight, sidebarWidth, paneX, paneWidth;
    private int rowLeft, rowWidth, listTop, listBottom, totalHeight, scroll;
    private boolean compactNavigation, stacked;
    private int saveDelay;
    private String notice = "";
    private SearchField searchField, selectionField;
    private java.net.URI authorizationLink;
    private java.nio.file.Path companionDirectory;
    private String spicyKeyDraft;
    private String translationKeyDraft = "";
    private boolean connectingSpotify, installingCompanion;
    private PanelButton peekButton;
    private boolean probeSearch, drawingPanel;
    private int viewportWidth, viewportHeight;
    private double uiScale = 1;
    @Override protected double inputScale() { return uiScale; }
    private LyricsOverlay.PopupBounds previewBounds;
    private final java.util.Map<String, ButtonMotion> buttonMotions = new LinkedHashMap<>();
    private final SettingsMotion.Tween modalMotion = new SettingsMotion.Tween(0);
    private final SettingsMotion.Tween paneMotion = new SettingsMotion.Tween(1);
    private double modalAlpha = 1, paneAlpha = 1, paintAlpha = 1;
    private long frameNanos;
    private String pressedKey = "";
    private boolean pointerHeld;
    private static final class ButtonMotion {
        final SettingsMotion.Tween hover = new SettingsMotion.Tween(0), press = new SettingsMotion.Tween(1);
        final SettingsMotion.Tween toggle, expand;
        final SettingsMotion.Tween glass = new SettingsMotion.Tween(0);
        long pulseUntil;
        ButtonMotion(boolean on, boolean expanded) {
            toggle = new SettingsMotion.Tween(on ? 1 : 0);
            expand = new SettingsMotion.Tween(expanded ? 1 : 0);
        }
    }
    private long probeTime = Long.MIN_VALUE;
    private int probeMouseX = -1, probeMouseY = -1;
    private long now() { return probeTime == Long.MIN_VALUE ? System.nanoTime() : probeTime; }
    private boolean motionEnabled() { return preferences.isLyricsAnimations(); }
    private int ink(int color) { return SettingsMotion.opacity(color, paintAlpha); }
    private int textInk(int color) {
        int faded = ink(color);
        // Legacy text treats alpha 0..3 as unspecified/opaque. Zero-opacity text is skipped.
        return faded >>> 24 < 4 ? faded & 0xFFFFFF | 0x04000000 : faded;
    }
    // Minecraft TTF glyphs use a baseline seven pixels below the draw origin.
    // Center Inter's visible cap/x-height rather than assuming vanilla's 9px font.
    private int textY(double center, double scale) {
        return (int) Math.round(center - (compactNavigation ? 4.25 : 2.75) * scale);
    }
    private void enterPane() { paneMotion.snap(0); }
    private String motionKey(ButtonKind kind, Entry entry, String label) {
        return kind + ":" + (entry == null ? "" : entry.key) + ":" + label;
    }
    private ButtonMotion motion(String key, Entry entry) {
        return buttonMotions.computeIfAbsent(key, ignored -> new ButtonMotion(
                entry != null && entry.kind == Kind.TOGGLE && entry.value.get().equals("@options.on"),
                entry != null && entry.kind == Kind.GROUP && entry.value.get().contains("collapse")));
    }
    private void activate(String key, Entry entry, Runnable action) {
        motion(key, entry).pulseUntil = now() + SettingsMotion.PRESS * 1_000_000L;
        action.run();
    }

    public LyricsConfigScreen(Screen parent) { this(parent, RelayLyricsClient.preferences()); }
    LyricsConfigScreen(Screen parent, ClientPreferences preferences) {
        super(Text.translatable("config.relaylyrics.settings.title")); this.parent = parent;
        this.preferences = preferences; this.catalog = new LyricsSettingsCatalog(preferences, this);
        if (RelayLyricsClient.music() != null && !RelayLyricsClient.music().support().availableVersion().isBlank()) notice = status("updates");
    }
    private void savePreferences() { if (preferences == RelayLyricsClient.preferences()) preferences.save(); }
    @Override protected void init() {
        viewportWidth = width; viewportHeight = height;
        applyUiScale();
    }
    private void applyUiScale() {
        uiScale = ResponsiveLyricsLayout.scale(viewportWidth, viewportHeight) * preferences.getSettingsUiScale();
        width = (int) Math.ceil(viewportWidth / uiScale); height = (int) Math.ceil(viewportHeight / uiScale);
        rebuild();
    }

    private void rebuild() {
        boolean keepSearchFocus = searchField != null && searchField.isFocused();
        int searchCursor = searchField == null ? searchQuery.length() : searchField.getCursor();
        clearChildren(); rows.clear(); rowViews.clear(); rowOwners.clear(); listMotion.clear(); navigation.clear(); searchField = null; peekButton = null;
        modalWidth = Math.max(190, Math.min((int) Math.floor(width * .94), Math.min(940, (int) Math.floor(viewportWidth / ResponsiveLyricsLayout.scale(viewportWidth, viewportHeight) * .94))));
        modalHeight = Math.max(130, Math.min((int) Math.floor(height * .88), Math.min(700, (int) Math.floor(viewportHeight / ResponsiveLyricsLayout.scale(viewportWidth, viewportHeight) * .88))));
        modalX = (width - modalWidth) / 2; modalY = (height - modalHeight) / 2;
        compactNavigation = modalWidth < 560;
        sidebarWidth = compactNavigation ? 46 : modalWidth < 900 ? 184 : 216;
        paneX = modalX + sidebarWidth; paneWidth = modalWidth - sidebarWidth;
        rowLeft = paneX + (compactNavigation ? 14 : modalWidth < 900 ? 22 : 32);
        rowWidth = modalX + modalWidth - (compactNavigation ? 14 : modalWidth < 900 ? 22 : 32) - rowLeft;
        stacked = rowWidth < (compactNavigation ? 260 : 410);
        listTop = modalY + (modalHeight < 250 ? 58 : 82);
        listBottom = modalY + modalHeight - (notice.isEmpty() ? 20 : 52);
        if (confirmRestore) { buildRestoreConfirmation(); return; }
        if (editing) { buildEditor(); return; }

        int navY = modalY + (compactNavigation ? 57 : 68);
        if (!compactNavigation) {
            searchField = addDrawableChild(new SearchField(modalX + 14, modalY + 17, sidebarWidth - 28, 34));
        } else {
            searchField = addDrawableChild(new SearchField(paneX + 14, modalY + 15, Math.max(40, paneWidth - 116), 28));
        }
        searchField.setText(searchQuery);
        searchField.setCursor(Math.clamp(searchCursor, 0, searchQuery.length()), false);
        searchField.setChangedListener(value -> {
            searchQuery = value; expandedChoice = ""; scroll = 0; rebuildRows(false, true);
        });
        if (keepSearchFocus) { setFocused(searchField); searchField.setFocused(true); }
        List<Category> categories = catalog.categories();
        int navSpace = modalHeight - (navY - modalY) - 20 - Math.max(0, categories.size() - 1) * 4;
        int navHeight = Math.min(34, Math.max(10, navSpace / Math.max(1, categories.size())));
        for (Category candidate : categories) {
            PanelButton tab = addPanelButton(Text.translatable(candidate.key), modalX + (compactNavigation ? 6 : 12), navY,
                    sidebarWidth - (compactNavigation ? 12 : 24), navHeight,
                    () -> changeCategory(candidate), ButtonKind.NAV, null);
            tab.navigationCategory = candidate;
            tab.selected = candidate == category && searchQuery.isBlank();
            if (compactNavigation) tab.setTooltip(Tooltip.of(Text.translatable(candidate.key)));
            navigation.add(tab); navY += navHeight + 4;
        }
        int closeX = modalX + modalWidth - 48;
        addPanelButton(Text.translatable("config.relaylyrics.settings.close"), closeX, modalY + 18, 30, 30, this::close, ButtonKind.CLOSE, null).setTooltip(Tooltip.of(Text.translatable("config.relaylyrics.settings.zoom_hint")));
        peekButton = addPanelButton(Text.translatable("config.relaylyrics.settings.peek"), closeX - 38, modalY + 18,
                30, 30, () -> { }, ButtonKind.PEEK, null);
        peekButton.setTooltip(Tooltip.of(Text.translatable("config.relaylyrics.settings.peek")));

        rebuildRows(false, true);
    }

    private void rebuildRows(boolean animate, boolean replace) {
        if (replace) {
            for (Row old : rowViews.values()) discardRow(old);
            rowViews.clear(); rows.clear(); listMotion.clear();
        }
        for (PanelButton tab : navigation) tab.selected = tab.navigationCategory == category && searchQuery.isBlank();
        List<Row> desired = new ArrayList<>();
        List<Entry> entries;
        var sections = new LinkedHashMap<String, Category>();
        if (searchQuery.isBlank()) entries = catalog.entries(category);
        else {
            var matches = new LinkedHashMap<String, Entry>();
            String[] query = normalize(searchQuery).split("\\s+");
            for (Category source : catalog.categories()) for (Entry entry : catalog.searchEntries(source)) {
                if (entry.kind == Kind.GROUP) continue;
                String haystack = normalize(I18n.translate(entry.key) + " " + description(entry) + " " + I18n.translate(source.key));
                boolean found = true;
                for (String word : query) if (!haystack.contains(word)) { found = false; break; }
                if (found && !matches.containsKey(entry.key)) { matches.put(entry.key, entry); sections.put(entry.key, source); }
            }
            entries = List.copyOf(matches.values());
        }
        var sectionCounts = entries.stream().collect(java.util.stream.Collectors.groupingBy(entry -> entry.section,java.util.stream.Collectors.counting()));
        int offset = 0;
        String previousSection = "";
        for (Entry entry : entries) {
            boolean toggle = entry.kind == Kind.TOGGLE;
            int labelWidth = entry.kind == Kind.INFO ? rowWidth - 32 : entry.kind == Kind.GROUP ? rowWidth - 74
                    : toggle ? rowWidth - 94 : stacked ? rowWidth - 32 : rowWidth - controlWidth() - 48;
            labelWidth = Math.max(60, labelWidth);
            int labelLines = textRenderer.wrapLines(ui(Text.translatable(entry.key)), labelWidth).size();
            String detail = description(entry);
            if (!searchQuery.isBlank()) detail = I18n.translate(sections.get(entry.key).key) + (detail.isBlank() ? "" : " · " + detail);
            int detailLines = detail.isBlank() ? 0 : Math.min(3, textRenderer.wrapLines(ui(Text.literal(detail)), (int) (labelWidth / .9)).size());
            int labelStep = compactNavigation ? 12 : 16, detailStep = compactNavigation ? 10 : 14;
            int textHeight = Math.max(labelStep, labelLines * labelStep) + (detailLines == 0 ? 0 : (compactNavigation ? 3 : 4) + detailLines * detailStep);
            if (entry.kind == Kind.INFO) textHeight += Math.min(3, Math.max(1, textRenderer.wrapLines(ui(text(entry.value.get())), rowWidth - 32).size())) * (compactNavigation ? 10 : 14) + 5;
            boolean below = entry.kind == Kind.TEXT || stacked && !toggle && entry.kind != Kind.GROUP && entry.kind != Kind.INFO;
            int rowHeight = Math.max(compactNavigation ? 40 : entry.kind == Kind.GROUP ? 46 : 54,
                    textHeight + (compactNavigation ? 18 : 26) + (below ? compactNavigation ? 32 : 36 : 0));
            boolean expanded = entry.kind == Kind.CHOICE && entry.key.equals(expandedChoice);
            Row row = new Row(entry, offset, rowHeight, expanded ? entry.options.size() * 30 + 10 : 0,
                    labelWidth, detail, below);
            row.id = entry.section + "|" + entry.key;
            row.sectionStart = !entry.section.equals(previousSection);
            row.caption = row.sectionStart && entry.kind != Kind.GROUP && sectionCounts.getOrDefault(entry.section,0L) > 1 && I18n.hasTranslation("config.relaylyrics.section." + entry.section)
                    ? "config.relaylyrics.section." + entry.section : "";
            row.inset = row.sectionStart ? row.caption.isEmpty() ? (desired.isEmpty() ? 0 : 12) : 26 : 0;
            Row old = rowViews.get(row.id);
            if (old != null && old.baseHeight == row.baseHeight && old.height == row.height
                    && old.entry.kind == entry.kind && old.entry.options.equals(entry.options) && old.below == row.below
                    && old.labelWidth == row.labelWidth && old.detail.equals(row.detail)) {
                old.entry.refreshFrom(entry);
                old.offset = offset; old.sectionStart = row.sectionStart; old.caption = row.caption; old.inset = row.inset;
                row = old;
            } else {
                if (old != null) discardRow(old);
                createControl(row);
                for (var control : row.widgets) rowOwners.put(control,row);
                rowViews.put(row.id,row);
            }
            desired.add(row); previousSection = entry.section; offset += row.height + row.inset;
        }
        listMotion.retarget(desired.stream().map(row -> new SettingsListMotion.Item(row.id,row.height + row.inset)).toList(), now(), animate && motionEnabled(), replace);
        updateRowMotion();
        refreshValues();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
    private static String description(Entry entry) {
        if (I18n.hasTranslation(entry.key + ".desc")) return net.minecraft.util.Language.getInstance().get(entry.key + ".desc");
        return I18n.hasTranslation(entry.key + ".hint") ? net.minecraft.util.Language.getInstance().get(entry.key + ".hint") : "";
    }
    private int controlWidth() { return stacked ? Math.max(80, rowWidth - 32) : Math.clamp(rowWidth * 38 / 100, 120, 210); }
    private PanelButton addPanelButton(Text message, int x, int y, int width, int height, Runnable action, ButtonKind kind, Entry entry) {
        return addDrawableChild(new PanelButton(x, y, width, height, message, action, kind, entry));
    }
    private PanelButton button(Text message, int x, int y, int width, Runnable action) {
        return addPanelButton(message, x, y, width, 28, action, ButtonKind.ACTION, null);
    }
    private void createControl(Row row) {
        Entry entry = row.entry;
        if (entry.kind == Kind.TEXT) {
            SearchField field = new SearchField(rowLeft + 16, 0, rowWidth - 32, 28, entry);
            field.setMaxLength(entry.masked ? 256 : entry.key.endsWith("gemini.direction") ? 500 : 100);
            field.setText(entry.value.get());
            field.setChangedListener(value -> { entry.setText.accept(value); if (!entry.masked) saveDelay = 10; });
            row.add(addDrawableChild(field), 0);
            return;
        }
        if (entry.kind == Kind.INFO) {
            PanelButton info = addPanelButton(text(entry.value.get()), rowLeft + 12, 0, rowWidth - 24,
                    Math.max(24, row.baseHeight - 16), () -> { }, ButtonKind.INFO, entry);
            info.active = false;
            info.setTooltip(Tooltip.of(text(entry.value.get())));
            row.add(info, 4);
            return;
        }
        int controlWidth = entry.kind == Kind.TOGGLE ? 42 : entry.kind == Kind.GROUP ? 26 : controlWidth();
        int x = rowLeft + rowWidth - controlWidth - 16;
        if (row.below) x = rowLeft + 16;
        if (entry.kind == Kind.SLIDER) {
            row.add(addPanelButton(Text.literal("−"), x, 0, 24, 28, () -> setNumber(entry, entry.number.getAsDouble() - entry.step, true), ButtonKind.ACTION, entry), 0);
            row.add(addDrawableChild(new SettingSlider(x + 30, 0, controlWidth - 60, entry)), 0);
            row.add(addPanelButton(Text.literal("+"), x + controlWidth - 24, 0, 24, 28, () -> setNumber(entry, entry.number.getAsDouble() + entry.step, true), ButtonKind.ACTION, entry), 0);
        } else {
            ButtonKind kind = entry.kind == Kind.TOGGLE ? ButtonKind.TOGGLE : entry.kind == Kind.GROUP ? ButtonKind.GROUP : ButtonKind.ACTION;
            PanelButton control = addPanelButton(valueText(entry), x, 0, controlWidth, entry.kind == Kind.TOGGLE ? 24 : 28, () -> {
                if (entry.kind == Kind.CHOICE) openChoice(entry);
                else { entry.activate.run(); changed(entry, true); }
            }, kind, entry);
            row.add(control, 0);
        }
        // The label and help are already visible beside each control; avoid duplicate vanilla tooltips.
        if (entry.key.equals(expandedChoice) && entry.kind == Kind.CHOICE) {
            for (int index = 0; index < entry.options.size(); index++) {
                int choice = index;
                PanelButton control = addPanelButton(text(entry.options.get(index)), x, 0, controlWidth, 28, () -> {
                    expandedChoice = ""; entry.select.accept(choice); changed(entry, true); rebuildRows(true, false);
                }, ButtonKind.OPTION, entry);
                control.selected = index == entry.selected.getAsInt();
                row.add(control, row.baseHeight + 5 + index * 30);
            }
        }
    }
    private Text valueText(Entry entry) {
        Text value = text(entry.value.get());
        return entry.kind == Kind.CHOICE ? value.copy().append(entry.key.equals(expandedChoice) ? "  ▴" : "  ▾") : value;
    }
    private static Text text(String value) {
        if (value == null || value.isBlank()) return Text.literal("—");
        return value.startsWith("@") ? Text.translatable(value.substring(1)) : Text.literal(value);
    }
    private Text ui(Text value) { return PlatformTextStyles.withFont(value, compactNavigation ? UI_COMPACT : UI_FONT); }
    private Text bold(Text value) { return PlatformTextStyles.withFont(value, compactNavigation ? UI_COMPACT_BOLD : UI_BOLD); }
    private void discardRow(Row row) {
        for (var control : row.widgets) { if (getFocused() == control) setFocused(null); remove(control); rowOwners.remove(control); }
    }
    private void updateRowMotion() {
        var frames = listMotion.sample(now(),motionEnabled());
        var retained = new java.util.HashSet<String>(); rows.clear();
        double height = 0;
        for (var frame : frames) {
            Row row = rowViews.get(frame.id()); if (row == null) continue;
            retained.add(frame.id()); row.visualOffset = frame.y(); row.visualHeight = frame.height(); row.opacity = frame.opacity();
            if (frame.leaving() != row.leaving) {
                if (frame.leaving()) {
                    for (var control : row.widgets) { row.enabled.put(control,control.active); control.active = false; if (getFocused() == control) setFocused(null); }
                } else for (var control : row.widgets) control.active = row.enabled.getOrDefault(control,true);
                row.leaving = frame.leaving();
            }
            rows.add(row); height = frame.y() + frame.height();
        }
        var iterator = rowViews.entrySet().iterator();
        while (iterator.hasNext()) { var entry = iterator.next(); if (!retained.contains(entry.getKey())) { discardRow(entry.getValue()); iterator.remove(); } }
        totalHeight = (int) Math.ceil(height);
        scroll = Math.clamp(scroll,0,maxScroll());
        if (!revealChoice.isEmpty()) for (Row row : rows) if (row.entry.key.equals(revealChoice) && !row.leaving) {
            int wanted = Math.max(0,row.offset+row.inset+row.baseHeight+34-(listBottom-listTop));
            scroll = Math.clamp(Math.max(scroll,wanted),0,maxScroll());
            if (row.visualHeight >= row.height+row.inset-.1) revealChoice = "";
            break;
        }
        positionRows();
    }
    private int rowTop(Row row) { return listTop + (int) Math.round(row.visualOffset) - scroll; }
    private int contentTop(Row row) { return rowTop(row) + row.inset - (int) Math.round((1-row.opacity)*8); }
    private int rowBottom(Row row) { return rowTop(row) + (int) Math.ceil(row.visualHeight); }
    private double rowOpacity(ClickableWidget widget) { Row row = rowOwners.get(widget); return row == null ? 1 : row.opacity; }
    private boolean clipRowWidget(DrawContext context, ClickableWidget widget) {
        Row row = rowOwners.get(widget); if (row == null) return false;
        int top = Math.clamp(rowTop(row) + row.inset,listTop,listBottom);
        int bottom = Math.clamp(rowBottom(row),top,listBottom);
        GuiTransform.enableScissor(context,rowLeft,top,rowLeft+rowWidth,bottom); return true;
    }
    private void positionRows() {
        for (Row row : rows) {
            int top = contentTop(row);
            for (int index = 0; index < row.widgets.size(); index++) {
                ClickableWidget widget = row.widgets.get(index);
                int localY = row.widgetOffsets.get(index);
                int y = top + (localY == 0 ? row.below ? row.baseHeight - 40 : 13 : localY);
                widget.setY(y);
                widget.visible = row.opacity > .001 && y >= listTop && y + widget.getHeight() <= listBottom && y < rowBottom(row) && y + widget.getHeight() > rowTop(row) + row.inset;
            }
        }
    }
    private int maxScroll() { return Math.max(0, totalHeight - (listBottom - listTop)); }
    private void changeCategory(Category next) {
        category = next; searchQuery = ""; scroll = 0; expandedChoice = "";
        if (!notice.isEmpty()) { notice = ""; listBottom = modalY + modalHeight - 20; }
        if (searchField != null) searchField.setText("");
        rebuildRows(true, true);
    }
    private void openChoice(Entry entry) {
        expandedChoice = entry.key.equals(expandedChoice) ? "" : entry.key; revealChoice = expandedChoice;
        rebuildRows(true, false);
        if (!expandedChoice.isEmpty()) {
            for (Row row : rows) if (row.entry.key.equals(expandedChoice)) {
                int wanted = row.offset + row.baseHeight + 34 - (listBottom - listTop);
                scroll = Math.clamp(Math.max(scroll, wanted), 0, maxScroll()); positionRows(); break;
            }
        }
    }
    private void buildRestoreConfirmation() {
        rowLeft = paneX + 28; rowWidth = Math.max(100, paneWidth - 56);
        button(Text.translatable("gui.cancel"), rowLeft, modalY + modalHeight - 52, Math.max(60, (rowWidth - 8) / 2),
                () -> { confirmRestore = false; rebuild(); });
        button(Text.translatable("config.relaylyrics.restore"), rowLeft + (rowWidth + 8) / 2, modalY + modalHeight - 52,
                Math.max(60, (rowWidth - 8) / 2), () -> { catalog.restore("all"); savePreferences(); confirmRestore = false; rebuild(); });
        addPanelButton(Text.translatable("config.relaylyrics.settings.close"), modalX + modalWidth - 48, modalY + 18, 30, 30,
                () -> { confirmRestore = false; rebuild(); }, ButtonKind.CLOSE, null);
    }
    @Override public boolean videoPopupProfile() {
        var overlay = RelayLyricsClient.overlay();
        return (resizing ? resizeVideoAspect : overlay == null ? 0 : overlay.popupVideoAspect()) > 0;
    }
    @Override public boolean squareVideoPopupProfile() {
        var overlay = RelayLyricsClient.overlay();
        return resizing ? resizeSquareVideo : overlay != null && overlay.isSquareVideo();
    }
    private void buildEditor() {
        int left = Math.max(12, (width - Math.min(500, width - 24)) / 2);
        button(Text.translatable("gui.done"), width - left - 100, height - 36, 100,
                () -> { editing = false; rebuild(); });
        editWidth = Math.min(preferences.getPopupWidth(videoPopupProfile(), squareVideoPopupProfile()), width - 24);
        int top = height < 240 ? 30 : 52;
        editHeight = Math.min(preferences.getPopupHeight(videoPopupProfile(), squareVideoPopupProfile()), Math.max(100, height - top - 54));
        editX = Math.clamp(preferences.getLyricsX(), 12, Math.max(12, width - editSurfaceWidth() - 12));
        editY = Math.clamp(preferences.getLyricsY(), top, Math.max(top, height - editHeight - 44));
        button(Text.translatable("config.relaylyrics.reset_layout"), left, height - 36, Math.min(180, width - left * 2 - 112),
                () -> { preferences.resetLayout(); rebuild(); });
    }
    private void setNumber(Entry entry, double number, boolean immediate) {
        double snapped = entry.min + Math.round((Math.clamp(number, entry.min, entry.max) - entry.min) / entry.step) * entry.step;
        entry.setNumber.accept(Math.clamp(snapped, entry.min, entry.max)); changed(entry, immediate);
        for (Row row : rows) if (row.entry == entry)
            for (var widget : row.widgets) if (widget instanceof SettingSlider slider) slider.refresh();
    }
    private void changed(Entry entry, boolean immediate) {
        if (immediate) { savePreferences(); saveDelay = 0; } else saveDelay = 10;
        if (entry.changesStructure) rebuildRows(true, false); else refreshValues();
    }
    private void refreshValues() {
        for (Row row : rows) {
            if (row.leaving) continue;
            if (row.entry.kind == Kind.INFO) {
                if (!row.widgets.isEmpty()) row.widgets.get(0).setTooltip(Tooltip.of(text(row.entry.value.get())));
                continue;
            }
            if (row.entry.kind == Kind.SLIDER) { for (var widget : row.widgets) if (widget instanceof SettingSlider slider) slider.refresh(); continue; }
            if (row.entry.kind == Kind.TEXT || row.widgets.isEmpty()) continue;
            row.widgets.get(0).setMessage(valueText(row.entry));
            String key = row.entry.key;
            if (key.endsWith(".spotify.connect")) row.widgets.get(0).active = !connectingSpotify;
            if (key.endsWith(".spotify.disconnect")) row.widgets.get(0).active = RelayLyricsClient.music() != null && RelayLyricsClient.music().spotifyAccountConnected();
            if (key.endsWith(".spotify.setup.reopen")) row.widgets.get(0).active = authorizationLink != null;
            if (key.endsWith(".youtube.setup.prepare")) row.widgets.get(0).active = !installingCompanion;
        }
    }
    @Override public void tick() {
        if (saveDelay > 0 && --saveDelay == 0) savePreferences();
        refreshValues();
    }

    @Override public void run(String action) {
        MusicService music = RelayLyricsClient.music();
        switch (action) {
            case "languages_open" -> { category = Category.CUSTOM; scroll = 0; expandedChoice = ""; rebuild(); }
            case "translation_provider" -> { translationKeyDraft = ""; rebuild(); }
            case "translation_now" -> { if (music != null) music.translations().request(); }
            case "translation_key_save" -> {
                boolean saved = music != null && music.translations().saveKey(translationKeyDraft);
                if (saved) translationKeyDraft = "";
                notice = "@config.relaylyrics.translation." + (saved ? "key_saved" : "key_failed"); rebuild();
            }
            case "translation_key_remove" -> {
                boolean removed = music != null && music.translations().removeKey();
                if (removed) translationKeyDraft = "";
                notice = "@config.relaylyrics.translation." + (removed ? "key_removed" : "key_failed"); rebuild();
            }
            case "updates_check" -> { if (music != null) music.support().check(); }
            case "updates_open" -> browse(music == null ? java.net.URI.create("https://github.com/fakemaluco/lyrica-mod/releases") : music.support().releasePage());
            case "diagnostics_copy" -> { if (music != null) { copy(music.diagnosticReport()); notice = "@config.relaylyrics.support.copied"; } }
            case "bug_report" -> browse(java.net.URI.create("https://github.com/fakemaluco/lyrica-mod/issues/new"));
            case "font" -> MinecraftClient.getInstance().setScreen(new GoogleFontScreen(this, preferences, this::fontChanged));
            case "weight" -> {
                String family = preferences.getGoogleFontFamily();
                if (!family.isEmpty()) GoogleFontManager.restore(family, preferences.getLyricsFontWeight().cssWeight(), this::fontChanged);
                fontChanged();
            }
            case "font_changed" -> fontChanged();
            case "background_style" -> { if (RelayLyricsClient.music() != null) RelayLyricsClient.music().refreshBackgroundArtwork(); }
            case "layout" -> { editing = true; rebuild(); }
            case "custom_effects" -> { category = Category.CUSTOM; scroll = 0; expandedChoice = ""; rebuild(); }
            case "custom_world" -> {
                category = Category.CUSTOM; scroll = 0; expandedChoice = ""; rebuild();
                for (Row row : rows) if (row.entry.key.endsWith(".group.world")) {
                    scroll = Math.clamp(row.offset, 0, maxScroll()); positionRows(); break;
                }
            }
            case "source" -> { if (music != null) music.syncMusicSource(); if (preferences.getMusicSource() == MusicSource.SPOTIFY_ACCOUNT) catalog.expand("spotify_account"); if (preferences.getMusicSource() == MusicSource.YOUTUBE_MUSIC) catalog.expand("youtube_account"); }
            case "spotify" -> openConnectionGroup("spotify_account");
            case "youtube" -> openConnectionGroup("youtube_account");
            case "connection_auto" -> { preferences.setMusicSource(MusicSource.AUTOMATIC); if (music != null) music.syncMusicSource(); notice = "@config.relaylyrics.connection.auto.ready"; rebuild(); }
            case "spotify_connect" -> connectSpotify();
            case "spotify_disconnect" -> { if (music != null) music.disconnectSpotifyAccount(); authorizationLink = null; notice = "@config.relaylyrics.spotify.disconnected"; rebuild(); }
            case "spotify_reopen" -> { if (authorizationLink != null) browse(authorizationLink); else notice = "@config.relaylyrics.spotify.no_link"; rebuild(); }
            case "spotify_dashboard" -> browse(java.net.URI.create("https://developer.spotify.com/dashboard"));
            case "spotify_redirect" -> copy(dev.relaylyrics.client.music.SpotifyAccountClient.REDIRECT_URI);
            case "youtube_install" -> installCompanion();
            case "youtube_folder" -> { if (companionDirectory != null) copy(companionDirectory.toString()); else run("youtube_install"); }
            case "youtube_chrome" -> copy("chrome://extensions");
            case "youtube_edge" -> copy("edge://extensions");
            case "youtube_open" -> { if (preferences.getMusicSource() != MusicSource.AUTOMATIC) preferences.setMusicSource(MusicSource.YOUTUBE_MUSIC); if (music != null) music.syncMusicSource(); browse(java.net.URI.create("https://music.youtube.com/")); }
            case "youtube_pair" -> { if (music != null && music.pairingCode() != null) copy(music.pairingCode()); }
            case "gemini_key_save" -> {
                if (music == null) notice = "@config.relaylyrics.gemini.state.offline";
                else if (music.geminiShaders().saveKey(geminiKeyDraft)) { geminiKeyDraft = ""; notice = "@config.relaylyrics.gemini.key_saved"; }
                else notice = "@config.relaylyrics.gemini.state.key_invalid";
                rebuild();
            }
            case "gemini_key_remove" -> { geminiKeyDraft = ""; if (music != null) music.geminiShaders().removeKey(); savePreferences(); rebuild(); }
            case "gemini_regenerate" -> { if (music != null) music.geminiShaders().regenerate(); }
            case "gemini_clear_cache" -> { if (music != null) music.geminiShaders().clearCache(); }
            case "spicy_get" -> browse(java.net.URI.create("https://developers.spicylyrics.org/catalog/spicy-player"));
            case "spicy_apply" -> {
                String key = spicyKeyDraft == null ? preferences.getSpicyLyricsApiKey() : spicyKeyDraft.strip();
                if (!dev.relaylyrics.client.music.SpicyLyricsApiClient.isPublishableKey(key)) notice = "@config.relaylyrics.spicy.key_invalid";
                else { preferences.setSpicyLyricsApiKey(key); savePreferences(); if (music != null) music.refreshLyricsApiKey(); notice = "@config.relaylyrics.spicy.key_saved"; }
                rebuild();
            }
            case "spicy_remove" -> { spicyKeyDraft = ""; preferences.setSpicyLyricsApiKey(""); savePreferences(); if (music != null) music.refreshLyricsApiKey(); notice = "@config.relaylyrics.spicy.key_removed"; rebuild(); }
            case "connection_setup" -> {
                if (preferences.getMusicSource() == MusicSource.YOUTUBE_MUSIC) run("youtube");
                else if (preferences.getMusicSource() == MusicSource.AUTOMATIC || preferences.getMusicSource() == MusicSource.WINDOWS_MEDIA || preferences.getMusicSource() == MusicSource.SPICETIFY_BRIDGE) {
                    catalog.expand("connection_details"); notice = "@config.relaylyrics.connection.guide." + preferences.getMusicSource().name().toLowerCase(java.util.Locale.ROOT); rebuild();
                } else run("spotify");
            }
            case "motion_artwork" -> { if (music != null) music.refreshMotionArtwork(); }
            case "lyrics_sources" -> { if (music != null) music.retryLyrics(); }
            case "retry" -> { if (music != null) { music.syncMusicSource(); music.retryLyrics(); } }
            case "audio" -> { if (music != null) music.reloadReactiveAudioOutput(); }
            case "restore_all" -> { confirmRestore = true; rebuild(); }
            default -> throw new IllegalArgumentException("Unknown settings action: " + action);
        }
    }
    private String geminiKeyDraft = "";
    private boolean probeControl;
    private boolean controlDown() {
        if (probeControl && Boolean.getBoolean("lyrica.configSelfTest")) return true;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }
    private void openConnectionGroup(String group) {
        category = Category.CONNECTION; searchQuery = ""; expandedChoice = ""; catalog.expand(group); enterPane(); rebuild();
        for (Row row : rows) if (row.entry.key.endsWith("group." + group)) { scroll = Math.clamp(row.offset,0,maxScroll()); positionRows(); break; }
    }
    private void copy(String value) {
        MinecraftClient.getInstance().keyboard.setClipboard(value);
        notice = "@config.relaylyrics.connection.copied"; rebuild();
    }
    private void browse(java.net.URI link) {
        copy(link.toString());
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(link); }
            catch (Exception ignored) { /* The copied link remains available. */ }
        });
    }
    private void connectSpotify() {
        if (connectingSpotify) return;
        if (!preferences.getSpotifyClientId().matches("[A-Za-z0-9]{16,64}")) {
            catalog.expand("spotify_setup"); notice = "@config.relaylyrics.spotify.client_invalid"; rebuild(); return;
        }
        var music = RelayLyricsClient.music(); if (music == null) return;
        connectingSpotify = true; notice = "@config.relaylyrics.spotify.opening"; rebuild();
        music.connectSpotifyAccount(preferences.getSpotifyClientId()).whenComplete((link,error) -> MinecraftClient.getInstance().execute(() -> {
            connectingSpotify = false;
            if (error == null) { authorizationLink = link; MinecraftClient.getInstance().keyboard.setClipboard(link.toString()); notice = "@config.relaylyrics.spotify.waiting"; }
            else notice = "@config.relaylyrics.spotify.failed";
            rebuild();
        }));
    }
    private void installCompanion() {
        if (installingCompanion) return;
        installingCompanion = true; notice = "@config.relaylyrics.youtube.preparing"; rebuild();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return dev.relaylyrics.client.music.YouTubeMusicCompanion.installAndOpen(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }).whenComplete((directory,error) -> MinecraftClient.getInstance().execute(() -> {
            installingCompanion = false;
            if (error == null) { companionDirectory = directory; copy(directory.toString()); notice = "@config.relaylyrics.youtube.setup.folder_ready"; }
            else notice = "@config.relaylyrics.youtube.guide_failed";
            rebuild();
        }));
    }
    @Override public void input(String name, String value) { if (name.equals("translation_key")) translationKeyDraft = value; else if (name.equals("spicy_key")) spicyKeyDraft = value; else if (name.equals("gemini_key")) geminiKeyDraft = value; }
    private void fontChanged() {
        if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().fontChanged();
    }
    @Override public String status(String name) {
        if (name.equals("update_available")) return RelayLyricsClient.music() == null ? "" : RelayLyricsClient.music().support().availableVersion();
        if (name.equals("translation_key_input")) return translationKeyDraft;
        if (name.equals("translation_key_status")) return "@config.relaylyrics.translation." +
                (RelayLyricsClient.music() != null && RelayLyricsClient.music().translations().keyConfigured() ? "key_configured" : "key_missing");
        if (name.equals("translation")) return "@config.relaylyrics.translation.state." +
                (RelayLyricsClient.music() == null ? "idle" : RelayLyricsClient.music().translations().status());
        if (name.equals("updates")) {
            var service = RelayLyricsClient.music();
            if (service == null) return "@config.relaylyrics.updates.state.idle";
            String key = "config.relaylyrics.updates.state." + service.support().status();
            return net.minecraft.text.Text.translatable(key, service.support().availableVersion()).getString();
        }

        if (name.equals("gemini_key_input")) return geminiKeyDraft;
        if (name.equals("gemini_key_status")) return "@config.relaylyrics.gemini." +
                (RelayLyricsClient.music() != null && RelayLyricsClient.music().geminiShaders().keyConfigured() ? "key_configured" : "key_missing");
        if (name.equals("gemini_status")) return "@config.relaylyrics.gemini.state." +
                (RelayLyricsClient.music() == null ? "offline" : RelayLyricsClient.music().geminiShaders().status());
        if (name.equals("gemini_design")) {
            var music = RelayLyricsClient.music();
            return music == null || music.geminiShaders().shaderName().isBlank() ? "—"
                    : music.geminiShaders().shaderName() + " · " + music.geminiShaders().shaderDescription();
        }
        if (name.equals("spicy_key_input")) return spicyKeyDraft == null ? preferences.getSpicyLyricsApiKey() : spicyKeyDraft;
        if (name.equals("spicy_status")) return dev.relaylyrics.client.music.SpicyLyricsApiClient.configuredKey(preferences.getSpicyLyricsApiKey()).isBlank()
                ? "@config.relaylyrics.spicy.key_missing" : "@config.relaylyrics.spicy.key_configured";
        if (name.equals("spotify_status")) return connectingSpotify ? "@config.relaylyrics.spotify.opening" : RelayLyricsClient.music() == null ? "@config.relaylyrics.offline" : RelayLyricsClient.music().spotifyAccountConnected() ? "@config.relaylyrics.connected" : RelayLyricsClient.music().spotifyAccountStatus();
        if (name.equals("youtube_status")) return installingCompanion ? "@config.relaylyrics.youtube.preparing" : RelayLyricsClient.music() != null && RelayLyricsClient.music().youtubeMusicConnected() ? "@config.relaylyrics.connected" : "@config.relaylyrics.youtube.waiting";
        if (name.equals("music_presence")) return "@config.relaylyrics.music_presence.status." + dev.relaylyrics.client.MusicPresence.statusKey();
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
            case "method" -> "@config.relaylyrics.connection.method." + music.playbackMethod();
            case "sources" -> music.lyricsSourceDiagnostics();
            case "motion_artwork" -> music.motionArtworkStatus();
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

    private void drawCurrentSong(DrawContext context, int x, int y, int w, int h) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay != null && overlay.hasCurrentSong()) {
            // Share the playing song, clock, artwork and prepared effects. Settings
            // never create fake playback or own the live renderer's resources.
            overlay.renderCurrentSongPreview(context, x, y, w, h, preferences);
        } else {
            drawBorder(context, x, y, w, h, 0x446F7F96);
            int lineY = y + h / 2 - 10;
            for (var line : textRenderer.wrapLines(Text.translatable("config.relaylyrics.current_song.empty"), w - 24)) {
                context.drawText(textRenderer, line, x + (w - textRenderer.getWidth(line)) / 2, lineY, 0xFFBEC9DA, false); lineY += 12;
            }
        }
    }


    private void drawCards(DrawContext context) {
        for (int start = 0; start < rows.size();) {
            int end = start + 1;
            Row first = rows.get(start);
            while (end < rows.size() && rows.get(end).entry.section.equals(first.entry.section)) end++;
            Row last = rows.get(end-1);
            int top = rowTop(first) + first.inset, bottom = rowBottom(last);
            paintAlpha = modalAlpha * paneAlpha;
            if (bottom > listTop && top < listBottom && bottom > top) {
                RoundedGui.smoothFill(context,rowLeft,top,rowWidth,bottom-top,12,ink(0x14D9CAFF));
                RoundedGui.smoothOutline(context,rowLeft,top,rowWidth,bottom-top,12,ink(0x12D9CAFF));
                for (int i = start+1; i < end; i++) {
                    Row row = rows.get(i); paintAlpha = modalAlpha*paneAlpha*row.opacity;
                    int y = rowTop(row);
                    if (row.entry.kind != Kind.GROUP && y >= listTop && y < listBottom)
                        context.fill(rowLeft+16,y,rowLeft+rowWidth-16,y+1,ink(0x12D9CAFF));
                }
            }
            if (!first.caption.isEmpty()) {
                paintAlpha = modalAlpha*paneAlpha*first.opacity;
                drawUi(context,Text.translatable(first.caption),rowLeft+4,rowTop(first)+6,MUTED,.85,true);
            }
            start = end;
        }
        paintAlpha = modalAlpha*paneAlpha;
    }
    private void drawPopup(DrawContext context) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay == null || !overlay.hasCurrentSong()) return;
        var bounds = overlay.popupBoundsSnapshot(viewportWidth, viewportHeight);
        previewBounds = bounds;
        drawCurrentSong(context, ResponsiveLyricsLayout.design(bounds.x(), uiScale), ResponsiveLyricsLayout.design(bounds.y(), uiScale),
                ResponsiveLyricsLayout.design(bounds.width(), uiScale), ResponsiveLyricsLayout.design(bounds.height(), uiScale));
    }

    /** Draw the plate before native widgets, including on versions where Screen renders its own background. */
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Newer Minecraft versions ask for the background before this screen's render pass.
        // Compose the plate explicitly after the live preview and dim layer instead.
        if (!drawingPanel || peeking || editing) return;
        paintAlpha = modalAlpha;
        if (paintAlpha <= 0) return;
        for (int spread = 12; spread >= 3; spread -= 3)
            RoundedGui.smoothFill(context, modalX - spread, modalY - spread / 2, modalWidth + spread * 2,
                    modalHeight + spread, 16, ink(0x08000000));
        RoundedGui.smoothGradient(context, modalX, modalY, modalWidth, modalHeight, 16, FIELD, DEEP, sidebarWidth, 0x6B23133E, paintAlpha);
        context.fill(paneX, modalY, paneX + 1, modalY + modalHeight, ink(HAIRLINE));
        RoundedGui.smoothOutline(context, modalX, modalY, modalWidth, modalHeight, 16, ink(0x28D9CAFF));
        if (!compactNavigation && modalHeight > 220)
            drawUi(context, Text.literal("Lyrica"), modalX + 22, modalY + modalHeight - 28, TERTIARY, .85, false);
        if (!compactNavigation) {
            paintAlpha = modalAlpha * paneAlpha;
            if (paintAlpha <= 0) return;
            drawUi(context, Text.translatable(searchQuery.isBlank() ? category.key : "config.relaylyrics.settings.search"),
                    rowLeft, textY((modalY + listTop - 10) / 2.0, 1.65), INK, 1.65, true);
        }
        paintAlpha = modalAlpha;
        context.fill(paneX + 1, listTop - 10, modalX + modalWidth - 1, listTop - 9, ink(0x14D9CAFF));
        if (confirmRestore) {
            int y = modalY + 92;
            for (var line : textRenderer.wrapLines(ui(Text.translatable("config.relaylyrics.defaults.confirm")), Math.max(90, rowWidth))) {
                drawOrdered(context, line, rowLeft, y, INK, 1); y += 18;
            }
            return;
        }
        paintAlpha = modalAlpha * paneAlpha;
        GuiTransform.enableScissor(context, rowLeft, listTop, rowLeft + rowWidth, listBottom);
        drawCards(context);
        for (int index = 0; index < rows.size(); index++) {
            paintAlpha = modalAlpha * paneAlpha * rows.get(index).opacity;
            if (paintAlpha <= 0) continue;
            Row row = rows.get(index);
            int top = contentTop(row);
            if (top + row.height < listTop || top > listBottom) continue;
            int clipTop = Math.clamp(rowTop(row)+row.inset,listTop,listBottom);
            GuiTransform.enableScissor(context,rowLeft,clipTop,rowLeft+rowWidth,Math.clamp(rowBottom(row),clipTop,listBottom));
            int y = top + (compactNavigation ? 10 : 13);
            for (var line : textRenderer.wrapLines(ui(Text.translatable(row.entry.key)), row.labelWidth)) {
                drawOrdered(context, line, rowLeft + 16, y, row.entry.kind == Kind.GROUP ? MUTED : INK, 1); y += compactNavigation ? 12 : 16;
            }
            if (!row.detail.isBlank()) {
                int count = 0; y += compactNavigation ? 3 : 4;
                for (var line : textRenderer.wrapLines(ui(Text.literal(row.detail)), (int) (row.labelWidth / .9))) {
                    drawOrdered(context, line, rowLeft + 16, y, MUTED, .9); y += compactNavigation ? 10 : 14;
                    if (++count == 3) break;
                }
            }
            if (row.entry.kind == Kind.INFO) {
                y += 5; int count = 0;
                for (var line : textRenderer.wrapLines(ui(text(row.entry.value.get())), rowWidth - 32)) {
                    drawOrdered(context, line, rowLeft + 16, y, MUTED, .9); y += compactNavigation ? 10 : 14;
                    if (++count == 3) break;
                }
            }
            if (row.entry.key.equals(expandedChoice)) {
                int x = row.below ? rowLeft + 16 : rowLeft + rowWidth - controlWidth() - 16;
                RoundedGui.smoothFill(context, x - 3, top + row.baseHeight, controlWidth() + 6,
                        row.height - row.baseHeight, 9, ink(0xFF2A1B46));
            }
            context.disableScissor();
        }
        paintAlpha = modalAlpha * paneAlpha;
        if (rows.isEmpty()) drawUi(context, Text.translatable("config.relaylyrics.settings.empty"), rowLeft + 16, listTop + 22, MUTED, 1, false);
        context.disableScissor();
        paintAlpha = modalAlpha;
        if (maxScroll() > 0) {
            int track = listBottom - listTop, thumb = Math.max(18, track * track / totalHeight);
            int y = listTop + (track - thumb) * scroll / maxScroll();
            RoundedGui.smoothFill(context, rowLeft + rowWidth + 7, y, 3, thumb, 1, ink(0x60D9CAFF));
        }
        if (!notice.isEmpty()) {
            GuiTransform.enableScissor(context, rowLeft, listBottom + 5, modalX + modalWidth - 15, modalY + modalHeight - 6);
            int y = listBottom + 10;
            for (var line : textRenderer.wrapLines(ui(text(notice)), rowWidth)) {
                drawOrdered(context, line, rowLeft, y, MUTED, .85); y += 13;
            }
            context.disableScissor();
        }
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        mouseX = (int) Math.round(pointerX(mouseX)); mouseY = (int) Math.round(pointerY(mouseY));
        GuiTransform.push(context); GuiTransform.scale(context, (float) uiScale, (float) uiScale);
        double oldDensity = PlatformTextures.beginUiScale(uiScale);
        try {
            frameNanos = now();
            updateRowMotion();
            if (probeMouseX >= 0) { mouseX = probeMouseX; mouseY = probeMouseY; }
            modalAlpha = modalMotion.to(1, SettingsMotion.MODAL, SettingsMotion.Curve.OUT, frameNanos, motionEnabled());
            paneAlpha = paneMotion.to(1, SettingsMotion.PANE, SettingsMotion.Curve.OUT, frameNanos, motionEnabled());
            if (!editing) drawPopup(context);
            GuiTransform.nextLayer(context);
            context.fill(0, 0, width, height, SettingsMotion.opacity(0x73000000, peeking ? 0 : modalAlpha));
            GuiTransform.nextLayer(context);
            drawingPanel = true;
            try { renderBackground(context, mouseX, mouseY, delta); } finally { drawingPanel = false; }
            GuiTransform.nextLayer(context);
            renderVanillaScreen(context, mouseX, mouseY, delta);
            paintAlpha = 1;
            if (peeking) return;
            if (editing) {
                drawCurrentSong(context, editX, editY, editSurfaceWidth(), editHeight);
                drawBorder(context, editX - 1, editY - 1, editSurfaceWidth() + 2, editHeight + 2, 0xFFD9CAFF);
                context.fill(editX + editSurfaceWidth() - 12, editY + editHeight - 12, editX + editSurfaceWidth(), editY + editHeight, ink(0xFFD9CAFF));
                if (height >= 240) {
                    var lines = textRenderer.wrapLines(ui(Text.translatable("config.relaylyrics.adjust_layout.hint")), width - 40);
                    int y = height - 56 - lines.size() * 13;
                    for (var line : lines) { context.drawText(textRenderer, line, 20, y, textInk(INK), false); y += 13; }
                }
            }
        } finally {
            try { GuiTransform.finishUiDraw(context, mouseX, mouseY, delta); }
            finally { PlatformTextures.endUiScale(oldDensity); GuiTransform.pop(context); }
        }
    }
    private void drawUi(DrawContext context, Text value, int x, int y, int color, double scale, boolean strong) {
        if (paintAlpha <= 0) return;
        SettingsTypography.draw(context, value.getString(), x, y, textRenderer.getWidth(strong ? bold(value) : ui(value)) * scale,
                strong, compactNavigation, scale, ink(color));
    }
    private void drawOrdered(DrawContext context, net.minecraft.text.OrderedText value, int x, int y, int color, double scale) {
        if (paintAlpha <= 0) return;
        StringBuilder text = new StringBuilder(); value.accept((index, style, cp) -> { text.appendCodePoint(cp); return true; });
        SettingsTypography.draw(context, text.toString(), x, y, textRenderer.getWidth(value) * scale, false, compactNavigation, scale, ink(color));
    }
    private void centered(DrawContext context, Text value, int x, int y, int color) {
        if (paintAlpha <= 0) return;
        Text styled = ui(value); context.drawText(textRenderer, styled, x - textRenderer.getWidth(styled) / 2, y, textInk(color), false);
    }
    private void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, ink(color)); context.fill(x, y + h - 1, x + w, y + h, ink(color));
        context.fill(x, y, x + 1, y + h, ink(color)); context.fill(x + w - 1, y, x + w, y + h, ink(color));
    }

    @Override protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) {
        if (peeking || vertical == 0) return false;
        revealChoice = "";
        if (controlDown() && !editing) {
            preferences.setSettingsUiScale(preferences.getSettingsUiScale() * Math.pow(1.08, Math.clamp(vertical, -4, 4)));
            savePreferences(); setFocused(null); selectingSearch = false; selectionField = null;
            applyUiScale(); return true;
        }
        if (editing || x < rowLeft || x > rowLeft + rowWidth + 10 || y < listTop || y > listBottom) return false;
        scroll = Math.clamp(scroll + (vertical > 0 ? -40 : 40), 0, maxScroll()); setFocused(null); positionRows(); return true;
    }
    @Override protected boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        if (controlDown() && key == GLFW.GLFW_KEY_0 && !editing) {
            preferences.setSettingsUiScale(1); savePreferences(); applyUiScale(); return true;
        }
        if (!editing && !confirmRestore && key == GLFW.GLFW_KEY_F && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && searchField != null) {
            setFocused(searchField); searchField.setFocused(true); return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && !searchQuery.isBlank() && expandedChoice.isEmpty() && !confirmRestore && !editing) {
            searchQuery = ""; scroll = 0; rebuild(); return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && (!expandedChoice.isEmpty() || confirmRestore || editing)) {
            expandedChoice = ""; confirmRestore = false; editing = false; rebuild(); return true;
        }
        if (editing && key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_UP) {
            int dx = key == GLFW.GLFW_KEY_RIGHT ? 1 : key == GLFW.GLFW_KEY_LEFT ? -1 : 0;
            int dy = key == GLFW.GLFW_KEY_DOWN ? 1 : key == GLFW.GLFW_KEY_UP ? -1 : 0;
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                var overlay = RelayLyricsClient.overlay();
                if (overlay != null && overlay.isSquareVideo()) editHeight += dx + dy;
                else { editWidth += dx; editHeight += dy; }
            }
            else { editX += dx; editY += dy; }
            persistEdit(); return true;
        }
        if (getFocused() instanceof SearchField && key != GLFW.GLFW_KEY_ESCAPE) return false;
        if (!editing && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            scroll = Math.clamp(scroll + (key == GLFW.GLFW_KEY_PAGE_UP ? -120 : 120), 0, maxScroll()); setFocused(null); positionRows(); return true;
        }
        return false;
    }
    @Override protected boolean handleMouseClicked(double x, double y, int button) {
        for (Row row : rows) for (var control : row.widgets) {
            if (control.visible && control.isMouseOver(x,y) && (row.leaving || y < rowTop(row)+row.inset || y >= rowBottom(row))) return true;
        }
        if (!editing && button == 0) for (Row row : rows) {
            if (row.entry.kind == Kind.GROUP && !row.leaving && x >= rowLeft && x < rowLeft+rowWidth
                    && y >= Math.max(listTop,contentTop(row)) && y < Math.min(listBottom,rowBottom(row))
                    && row.widgets.get(0) instanceof PanelButton control) { setFocused(control); control.setFocused(true); control.invoke.run(); return true; }
        }
        if (!editing && button == 0) {
            pointerHeld = true;
            pressedKey = "";
            for (var child : children()) if (child instanceof PanelButton control && control.active && control.visible && control.isMouseOver(x, y)) {
                pressedKey = control.key; break;
            }
        }
        if (!editing && button == 0) for (var child : children()) {
            if (child instanceof SearchField field && field.visible && field.isMouseOver(x, y)) {
                setFocused(field); field.setFocused(true); selectingSearch = true; selectionField = field;
                field.setCursor(field.cursorAt(x), false); return true;
            }
        }
        if (!editing && button == 0 && peekButton != null && peekButton.isMouseOver(x, y)) {
            peeking = true; return true;
        }
        if (!editing && !confirmRestore && button == 0 && (x < modalX || x >= modalX + modalWidth || y < modalY || y >= modalY + modalHeight)) {
            close(); return true;
        }
        if (!editing || button != 0 || x < editX || y < editY || x > editX + editSurfaceWidth() || y > editY + editHeight) return false;
        int resizeEdges = dev.relaylyrics.client.music.PopupResizeGesture.edgesAt(x, y, editX, editY, editSurfaceWidth(), editHeight, 6);
        resizing = resizeEdges != 0;
        if (resizing) editorResizeStart = new dev.relaylyrics.client.music.PopupResizeGesture.Start(editX, editY, editSurfaceWidth(), editHeight, x, y, resizeEdges);
        if (resizing) { var overlay = RelayLyricsClient.overlay(); resizeSquareVideo = overlay != null && overlay.isSquareVideo(); resizeVideoAspect = overlay == null ? 0 : overlay.popupVideoAspect(); }
        dragging = !resizing; dragX = (int) x; dragY = (int) y; return true;
    }
    @Override protected boolean handleMouseDragged(double x, double y, int button, double dx, double dy) {
        if (selectingSearch && selectionField != null && button == 0) { selectionField.setCursor(selectionField.cursorAt(x), true); return true; }
        if (!editing || (!resizing && !dragging)) return false;
        int mx = (int) x, my = (int) y;
        if (resizing) {
            int top = height < 240 ? 30 : 52;
            var frame = editorResizeStart.resize(x, y, 12, top, width - 12, height - 44);
            editX = frame.x(); editY = frame.y(); editWidth = frame.width(); editHeight = frame.height();
        }
        else { editX += mx - dragX; editY += my - dragY; }
        dragX = mx; dragY = my; persistEdit(); return true;
    }
    private int editSurfaceWidth() {
        var overlay = RelayLyricsClient.overlay();
        if (resizing) return editWidth;
        return overlay == null ? editWidth : overlay.expandedPopupWidth(editWidth, editHeight);
    }
    private void persistEdit() {
        var overlay = RelayLyricsClient.overlay();
        if (resizing ? resizeSquareVideo : overlay != null && overlay.isSquareVideo()) editHeight = Math.min(editHeight, width - 24);
        int extra = Math.max(0, editSurfaceWidth() - editWidth);
        editWidth = Math.clamp(editWidth, 96, Math.min(900, Math.max(96, width - extra - 24)));
        int top = height < 240 ? 30 : 52;
        editHeight = Math.clamp(editHeight, 64, Math.min(600, Math.max(100, height - top - 44)));
        editX = Math.clamp(editX, 12, Math.max(12, width - editSurfaceWidth() - 12));
        editY = Math.clamp(editY, top, Math.max(top, height - editHeight - 44));
        preferences.setLyricsX(editX); preferences.setLyricsY(editY);
        preferences.setPopupWidth(videoPopupProfile(), squareVideoPopupProfile(), editWidth);
        preferences.setPopupHeight(videoPopupProfile(), squareVideoPopupProfile(), editHeight); saveDelay = 10;
    }
    @Override protected boolean handleMouseReleased(double x, double y, int button) {
        if (button == 0) { pointerHeld = false; pressedKey = ""; }
        boolean handled = dragging || resizing || peeking || selectingSearch; selectingSearch = false; peeking = false; dragging = false; resizing = false;
        if (saveDelay > 0) { savePreferences(); saveDelay = 0; } return handled;
    }
    /** Inventory input adapter compatibility; values have explicit minus buttons now. */
    public boolean handleSecondaryClick(double x, double y, int button) { return false; }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { savePreferences(); MinecraftClient.getInstance().setScreen(parent); }
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
            if (!screen.catalog.categories().contains(Category.WORLD)) throw new IllegalStateException("World category must remain discoverable");
            Entry toggle = screen.catalog.entries(Category.WORLD).stream().filter(e -> e.key.endsWith(".world_lyrics")).findFirst().orElseThrow();
            toggle.activate.run();
            if (screen.catalog.entries(Category.WORLD).size() != 8) throw new IllegalStateException("World page must have eight primary controls");
            toggle.activate.run();
            if (screen.catalog.entries(Category.WORLD).size() != 1) throw new IllegalStateException("Disabled world controls remain visible");
        } finally { p.setWorldLyrics(original); }
    }
    /** Called only by the opt-in screenshot fixture, using disposable preferences. */
    void configureProbe(String stage) {
        if (Boolean.getBoolean("lyrica.configAccordionSelfTest")) {
            preferences.setLyricsAnimations(true); probeTime = 1_000_000_000L;
            modalMotion.snap(1); paneMotion.snap(1);
            category = stage.equals("cards_style") ? Category.APPEARANCE : Category.CUSTOM;
            rebuild();
            if (stage.equals("cards_style")) return;
            var sidebar = navigation.get(0); var search = searchField;
            Row header = rows.stream().filter(row -> row.entry.key.endsWith(".group.typography")).findFirst().orElseThrow();
            PanelButton toggle = (PanelButton) header.widgets.get(0);
            if (stage.equals("reduced")) preferences.setLyricsAnimations(false);
            if (stage.equals("category_60")) {
                changeCategory(Category.APPEARANCE); probeTime += 60_000_000L;
            } else {
                toggle.invoke.run();
                if (stage.startsWith("collapse_") || stage.equals("reverse_40")) {
                    probeTime += 300_000_000L; updateRowMotion(); toggle.invoke.run();
                    probeTime += 60_000_000L; updateRowMotion();
                    if (stage.equals("reverse_40")) { toggle.invoke.run(); probeTime += 40_000_000L; }
                } else if (stage.startsWith("accordion_")) probeTime += Long.parseLong(stage.substring(10))*1_000_000L;
                else probeTime += 270_000_000L;
            }
            updateRowMotion();
            if (navigation.get(0) != sidebar || searchField != search) throw new IllegalStateException("Expansion rebuilt the settings chrome");
            if (!stage.equals("category_60") && rowViews.get(header.id).widgets.get(0) != toggle) throw new IllegalStateException("Disclosure trigger was recreated");
            if (paneMotion.value(probeTime) != 1) throw new IllegalStateException("Accordion faded the whole pane");
            if (stage.equals("accordion_80") && rows.stream().noneMatch(row -> row.opacity>0 && row.opacity<1)) throw new IllegalStateException("Missing intermediate item fade");
            if (stage.equals("collapse_60") && rows.stream().noneMatch(row -> row.leaving)) throw new IllegalStateException("Collapse removed content before its exit");
            if (stage.equals("reduced") && rows.stream().anyMatch(row -> row.opacity != 1)) throw new IllegalStateException("Reduced motion did not settle");
            return;
        }
        if (Boolean.getBoolean("lyrica.configConnectionSelfTest")) {
            preferences.setSettingsUiScale(1); applyUiScale();
            if (stage.equals("spicy_key")) {
                category = Category.SOURCES; catalog.expand("spicy_account"); rebuild();
                SearchField field = rows.stream().filter(row -> row.entry.kind == Kind.TEXT).flatMap(row -> row.widgets.stream()).filter(widget -> widget instanceof SearchField).map(widget -> (SearchField) widget).findFirst().orElseThrow();
                field.setText("sl_pk_fixture_client_123");
                if (field.displayInput().contains("fixture") || !"sl_pk_fixture_client_123".equals(spicyKeyDraft)) throw new IllegalStateException("Key field masking or draft update failed");
                rebuild();
                if (!"sl_pk_fixture_client_123".equals(spicyKeyDraft)) throw new IllegalStateException("Rebuilding lost the key draft");
                return;
            }
            category = Category.CONNECTION;
            String group = stage.startsWith("youtube") ? "youtube_account" : "spotify_account";
            if (stage.equals("spotify_setup")) catalog.expand("spotify_setup");
            openConnectionGroup(group);
            if (stage.equals("spotify_setup")) {
                for (Row row : rows) if (row.entry.key.endsWith(".group.spotify_setup")) { scroll = Math.clamp(row.offset,0,maxScroll()); positionRows(); break; }
            }
            if (stage.equals("zoom_small") || stage.equals("zoom_large")) {
                double old = uiScale;
                probeControl = true;
                try {
                    handleMouseScrolled(rowLeft + 2,listTop + 2,0,stage.equals("zoom_small") ? -4 : 4);
                } finally { probeControl = false; }
                if (stage.equals("zoom_small") ? uiScale >= old : uiScale <= old) throw new IllegalStateException("Control-wheel did not zoom settings");
            }
            return;
        }
        probeSearch = stage.equals("search");
        searchQuery = probeSearch ? "Canvas" : "";
        category = switch (stage) {
            case "appearance", "choices", "motion_artwork", "quality_choices" -> Category.APPEARANCE;
            case "connection" -> Category.CONNECTION;
            case "sources", "sources_end", "source_priority", "priority_choices", "compact_priority" -> Category.SOURCES;
            case "updates", "support", "advanced", "experimental", "gemini_controls", "gemini_controls_lower", "gemini_controls_creative", "gemini_controls_art", "gemini_controls_actions" -> Category.ADVANCED;
            case "custom", "effects", "languages", "translation_provider" -> Category.CUSTOM;
            case "world", "world_fixed" -> Category.WORLD;
            default -> Category.GENERAL;
        };
        if (stage.equals("motion_artwork")) catalog.expand("motion_artwork");
        if (stage.contains("priority")) catalog.expand("source_priority");
        if (stage.equals("experimental")) catalog.expand("experimental");
        if (stage.startsWith("gemini_controls")) { catalog.expand("experimental"); catalog.expand("gemini_shader"); }
        if (stage.equals("effects")) catalog.expand("effects");
        if (stage.equals("languages") || stage.equals("translation_provider")) catalog.expand("languages");
        if (stage.equals("translation_provider")) { catalog.expand("translation_provider"); preferences.setTranslationProvider("Gemini"); }
        if (stage.equals("updates")) catalog.expand("updates");
        if (stage.equals("support")) catalog.expand("support");
        if (stage.equals("world") || stage.equals("world_fixed")) {
            preferences.setWorldLyrics(true); preferences.setWorldLyricsAutomatic(!stage.equals("world_fixed"));
        }
        editing = stage.equals("languages_preview") || stage.equals("languages_replace") || stage.startsWith("gemini_visual_") || stage.equals("editor") || stage.equals("compact_tiny") || java.util.Set.of("kawarp", "kawarp_later", "artist", "artist_fallback", "static", "paused").contains(stage);
        rebuild();
        if (stage.equals("translation_provider")) {
            for (Row row : rows) if (row.entry.key.endsWith("group.translation_provider")) { scroll = Math.clamp(row.offset, 0, maxScroll()); positionRows(); break; }
        }
        if (stage.equals("sources_end")) { scroll = maxScroll(); positionRows(); }
        if (stage.equals("gemini_controls_creative")) {
            for(Row row:rows)if(row.entry.key.endsWith("gemini.song_context")){scroll=Math.clamp(row.offset,0,maxScroll());positionRows();break;}
        }
        if (stage.equals("gemini_controls_art") || stage.equals("gemini_controls_actions")) {
            String target=stage.equals("gemini_controls_art")?"gemini.art_recipe":"gemini.direction";
            for(Row row:rows)if(row.entry.key.endsWith(target)){scroll=Math.clamp(row.offset,0,maxScroll());positionRows();break;}
        }
        if (stage.equals("gemini_controls_lower")) {
            for (Row row : rows) if (row.entry.key.endsWith("gemini.model")) { scroll = Math.clamp(row.offset, 0, maxScroll()); positionRows(); break; }
        }
        if (stage.equals("motion_artwork")) {
            for (Row row : rows) if (row.entry.key.endsWith(".group.motion_artwork")) {
                scroll = Math.clamp(row.offset, 0, maxScroll()); positionRows(); break;
            }
        }
        if (stage.equals("priority_choices")) openChoice(catalog.entries(category).stream().filter(e -> e.kind == Kind.CHOICE).findFirst().orElseThrow());
        if (stage.equals("quality_choices")) openChoice(catalog.entries(category).stream().filter(e -> e.key.endsWith(".background_preset")).findFirst().orElseThrow());
        if (stage.equals("choices")) openChoice(catalog.entries(category).stream().filter(e -> e.key.endsWith(".effects_preset")).findFirst().orElseThrow());
        if (stage.equals("categories") || stage.equals("compact_choices")) openChoice(catalog.entries(category).stream().filter(e -> e.kind == Kind.CHOICE).findFirst().orElseThrow());
    }
    void prepareInteractionProbe(String stage) {
        if (!stage.startsWith("ui_")) return;
        preferences.setLyricsAnimations(true);
        probeTime = 1_000_000_000L;
        modalMotion.snap(1); paneMotion.snap(1);
        if (stage.startsWith("ui_open_")) {
            modalMotion.snap(0);
            modalMotion.to(1, SettingsMotion.MODAL, SettingsMotion.Curve.OUT, probeTime, true);
            probeTime += Long.parseLong(stage.substring(8)) * 1_000_000L;
            return;
        }
        if (stage.equals("ui_peek")) { peeking = true; return; }
        if (stage.startsWith("ui_pane_")) {
            changeCategory(Category.APPEARANCE);
            paneMotion.to(1, SettingsMotion.PANE, SettingsMotion.Curve.OUT, probeTime, true);
            probeTime += Long.parseLong(stage.substring(8)) * 1_000_000L;
            return;
        }
        PanelButton target = rows.stream().flatMap(row -> row.widgets.stream())
                .filter(widget -> widget instanceof PanelButton button && button.kind == ButtonKind.TOGGLE)
                .map(widget -> (PanelButton) widget).findFirst().orElseThrow();
        if (target.entry.value.get().equals("@options.on")) target.entry.activate.run();
        target.motion.toggle.snap(0);
        probeMouseX = target.getX() + target.getWidth() / 2;
        probeMouseY = target.getY() + target.getHeight() / 2;
        if (stage.startsWith("ui_toggle_")) {
            target.invoke.run();
            target.motion.toggle.to(1, SettingsMotion.TOGGLE, SettingsMotion.Curve.OUT, probeTime, true);
            probeTime += Long.parseLong(stage.substring(10)) * 1_000_000L;
            double state = target.motion.toggle.value(probeTime);
            if (stage.equals("ui_toggle_40") && !(state > 0 && state < 1)) throw new IllegalStateException("Toggle skipped its intermediate frame");
            if (stage.equals("ui_toggle_220") && state != 1) throw new IllegalStateException("Toggle did not settle");
        } else if (stage.startsWith("ui_hover_")) {
            target.motion.hover.to(1, SettingsMotion.FAST, SettingsMotion.Curve.STANDARD, probeTime, true);
            probeTime += Long.parseLong(stage.substring(9)) * 1_000_000L;
        } else if (stage.equals("ui_press_60")) {
            pointerHeld = true; pressedKey = target.key;
            target.motion.press.to(.97, SettingsMotion.PRESS, SettingsMotion.Curve.OUT, probeTime, true);
            probeTime += 60_000_000L;
            if (!(target.motion.press.value(probeTime) < 1)) throw new IllegalStateException("Press did not scale");
        }
    }
    void verifyLayoutForProbe() {
        var overlay = RelayLyricsClient.overlay();
        if (!editing && overlay != null && overlay.hasCurrentSong() && previewBounds != null
                && !previewBounds.equals(overlay.popupBoundsSnapshot(viewportWidth, viewportHeight)))
            throw new IllegalStateException("Settings changed the live popup bounds");
        if (probeSearch) {
            String savedQuery = searchQuery;
            boolean focusedBefore = searchField != null && searchField.isFocused();
            if (!handleKeyPressed(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_CONTROL) || searchField == null || !searchField.isFocused())
                throw new IllegalStateException("Ctrl+F did not focus settings search");
            if (!handleKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0) || !searchQuery.isEmpty())
                throw new IllegalStateException("Escape did not clear settings search");
            searchQuery = savedQuery; rebuild();
            if (!focusedBefore) setFocused(null);
        }
        if (!editing && (modalX < 0 || modalY < 0 || modalX + modalWidth > width || modalY + modalHeight > height))
            throw new IllegalStateException("Modal outside viewport");
        if (!searchQuery.isBlank() && rows.isEmpty()) throw new IllegalStateException("Global settings search found no rows");
        for (Row row : rows) for (var widget : row.widgets) {
            if (!widget.visible) continue;
            if (widget.getX() < 0 || widget.getY() < listTop || widget.getX() + widget.getWidth() > width
                    || widget.getY() + widget.getHeight() > listBottom) throw new IllegalStateException("Setting outside viewport: " + row.entry.key);
            if (!I18n.hasTranslation(row.entry.key)) throw new IllegalStateException("Untranslated setting: " + row.entry.key);
        }
        if (maxScroll() > 0 && !editing) {
            int original = scroll;
            mouseScrolled((rowLeft + rowWidth / 2.0) * uiScale, (listTop + 10) * uiScale, 0, original == maxScroll() ? 1 : -1);
            if (scroll == original) throw new IllegalStateException("Scaled pointer missed settings scroll area");
            scroll = original; positionRows();
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
        final Entry entry; int offset; final int baseHeight, height, labelWidth; final String detail; final boolean below;
        String id = "", caption = ""; int inset;
        boolean sectionStart, leaving;
        double visualOffset, visualHeight, opacity = 1;
        final java.util.Map<ClickableWidget, Boolean> enabled = new java.util.IdentityHashMap<>();
        final List<ClickableWidget> widgets = new ArrayList<>();
        final List<Integer> widgetOffsets = new ArrayList<>();
        Row(Entry entry, int offset, int baseHeight, int extensionHeight, int labelWidth, String detail, boolean below) {
            this.entry = entry; this.offset = offset; this.baseHeight = baseHeight; this.height = baseHeight + extensionHeight;
            this.labelWidth = labelWidth; this.detail = detail; this.below = below;
        }
        void add(ClickableWidget widget, int offset) { widgets.add(widget); widgetOffsets.add(offset); }
    }
    private enum ButtonKind { ACTION, NAV, CLOSE, PEEK, TOGGLE, GROUP, OPTION, INFO }
    private final class PanelButton extends SettingsButtonBase {
        private final ButtonKind kind;
        private final Entry entry;
        private Category navigationCategory;
        private final String key;
        private final ButtonMotion motion;
        private final Runnable invoke;
        private boolean selected;
        private double glassEngagement;
        PanelButton(int x, int y, int width, int height, net.minecraft.text.Text label, Runnable action, ButtonKind kind, Entry entry) {
            super(x, y, width, height, ui(label), () -> activate(motionKey(kind, entry, kind == ButtonKind.OPTION || entry == null || entry.kind == Kind.SLIDER ? label.getString() : ""), entry, action));
            this.kind = kind; this.entry = entry;
            this.key = motionKey(kind, entry, kind == ButtonKind.OPTION || entry == null || entry.kind == Kind.SLIDER ? label.getString() : "");
            this.motion = motion(key, entry);
            this.invoke = () -> activate(key, entry, action);
        }
        @Override protected void drawSurface(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean clipped = clipRowWidget(context,this);
            try { drawAnimatedControl(context,mouseX,mouseY,delta); }
            finally { if (clipped) context.disableScissor(); }
        }
        private void drawAnimatedControl(DrawContext context, int mouseX, int mouseY, float delta) {
            if (peeking || kind == ButtonKind.INFO) return;
            paintAlpha = modalAlpha * (kind == ButtonKind.NAV || kind == ButtonKind.CLOSE || kind == ButtonKind.PEEK ? 1 : paneAlpha) * rowOpacity(this);
            if (paintAlpha <= 0) return;
            boolean over = isHovered() || isFocused();
            double hover = motion.hover.to(over ? 1 : 0, SettingsMotion.FAST, SettingsMotion.Curve.STANDARD, frameNanos, motionEnabled());
            boolean held = pointerHeld && key.equals(pressedKey) || frameNanos < motion.pulseUntil;
            double pressedScale = kind == ButtonKind.NAV ? .98 : kind == ButtonKind.CLOSE || kind == ButtonKind.PEEK ? .94 : .97;
            boolean glassCircle = kind == ButtonKind.CLOSE || kind == ButtonKind.PEEK;
            double scale = motion.press.to(held ? pressedScale : 1, glassCircle ? 280 : SettingsMotion.PRESS,
                    glassCircle ? SettingsMotion.Curve.GLASS : SettingsMotion.Curve.OUT, frameNanos, motionEnabled());
            if (!motionEnabled()) scale = 1;
            glassEngagement = motion.glass.to(over || held ? 1 : 0, 300, SettingsMotion.Curve.STANDARD, frameNanos, motionEnabled());
            GuiTransform.push(context);
            GuiTransform.translate(context, getX() + getWidth() / 2f, getY() + getHeight() / 2f);
            GuiTransform.scale(context, (float) scale, (float) scale);
            GuiTransform.translate(context, -getX() - getWidth() / 2f, -getY() - getHeight() / 2f);
            try { paintButton(context, hover); } finally { GuiTransform.pop(context); paintAlpha = modalAlpha; }
        }
        private void paintButton(DrawContext context, double hover) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            if (kind == ButtonKind.CLOSE) {
                LiquidGlass.draw(context, x, y, w, h, h / 2, paintAlpha, glassEngagement);
                if (isFocused()) RoundedGui.smoothOutline(context, x - 2, y - 2, w + 4, h + 4, h / 2 + 2, ink(0xB3D9CAFF));
                cross(context, x + w / 2, y + h / 2, 5, INK); return;
            }
            if (kind == ButtonKind.PEEK) {
                LiquidGlass.draw(context, x, y, w, h, h / 2, paintAlpha, glassEngagement);
                if (isFocused()) RoundedGui.smoothOutline(context, x - 2, y - 2, w + 4, h + 4, h / 2 + 2, ink(0xB3D9CAFF));
                SettingsTypography.icon(context, "eye", x + 5, y + 5, 20, ink(MUTED)); return;
            }
            if (kind == ButtonKind.TOGGLE) {
                boolean on = entry.value.get().equals("@options.on");
                double state = motion.toggle.to(on ? 1 : 0, SettingsMotion.TOGGLE, SettingsMotion.Curve.OUT, frameNanos, motionEnabled());
                int track = SettingsMotion.mix(SettingsMotion.mix(0x6B000000, 0x80000000, hover),
                        SettingsMotion.mix(0x6BFFFFFF, 0x85FFFFFF, hover), state);
                RoundedGui.smoothFill(context, x, y, 42, 24, 12, ink(track));
                RoundedGui.smoothOutline(context, x, y, 42, 24, 12, ink(SettingsMotion.mix(0x0FFFFFFF, 0x3DFFFFFF, state)));
                int knob = x + 2 + (int) Math.round(18 * state);
                RoundedGui.smoothFill(context, knob, y + 3, 20, 20, 10, ink(0x38000000));
                RoundedGui.smoothFill(context, knob, y + 2, 20, 20, 10,
                        ink(SettingsMotion.mix(SettingsMotion.mix(0xC7F5F5F5, 0xE6FFFFFF, hover), 0xFFFFFFFF, state)));
                if (isFocused()) RoundedGui.smoothOutline(context, x - 2, y - 2, 46, 28, 14, ink(0xB3D9CAFF));
                return;
            }
            if (kind == ButtonKind.GROUP) {
                int cx = x + w / 2, cy = y + h / 2;
                boolean expanded = entry.value.get().contains("collapse");
                double rotation = motion.expand.to(expanded ? 1 : 0, SettingsMotion.FAST, SettingsMotion.Curve.OUT, frameNanos, motionEnabled());
                SettingsTypography.icon(context, "chevron:" + (int) Math.round(rotation * 16), cx - 7, cy - 7, 14, ink(SettingsMotion.mix(MUTED, INK, hover)));
                return;
            }
            int color = SettingsMotion.mix(selected ? 0x24D9CAFF : kind == ButtonKind.NAV ? 0x00D9CAFF : 0x14D9CAFF,
                    selected ? 0x30D9CAFF : 0x29D9CAFF, hover);
            if (kind == ButtonKind.NAV) {
                if (color != 0) RoundedGui.smoothFill(context, x, y, w, h, 9, ink(color));
            } else {
                if (selected) RoundedGui.smoothFill(context, x, y, w, h, h / 2, ink(0x20D9CAFF));
                LiquidGlass.draw(context, x, y, w, h, h / 2, paintAlpha * (active ? 1 : .45), glassEngagement);
            }
            if (isFocused()) RoundedGui.smoothOutline(context, x - 1, y - 1, w + 2, h + 2, 9, ink(0x9DD9CAFF));
            if (kind == ButtonKind.NAV) {
                drawCategoryIcon(context, navigationCategory, x + (compactNavigation ? w / 2 - 6 : 10), y + h / 2 - 6,
                        selected ? INK : MUTED);
                if (!compactNavigation) {
                    String value = textRenderer.trimToWidth(ui(getMessage()), Math.max(20, w - 42)).getString();
                    drawUi(context, net.minecraft.text.Text.literal(value), x + 34, textY(y + h / 2.0, .95), SettingsMotion.mix(selected ? INK : MUTED, INK, hover), .95, selected);
                }
            } else {
                net.minecraft.text.Text label = ui(getMessage());
                String value = textRenderer.trimToWidth(label, Math.max(20, w - 18)).getString();
                net.minecraft.text.Text visible = ui(net.minecraft.text.Text.literal((kind == ButtonKind.OPTION && selected ? "✓ " : "") + value));
                drawUi(context, visible, x + (w - textRenderer.getWidth(visible)) / 2,
                        textY(y + h / 2.0, 1), active ? INK : MUTED, 1, false);
            }
        }
    }
    private final class SettingSlider extends SliderWidget {
        private final Entry entry;
        private final SettingsMotion.Tween thumb = new SettingsMotion.Tween(1);
        SettingSlider(int x, int y, int width, Entry entry) {
            super(x, y, Math.max(24, width), 28, ui(text(entry.value.get())), (entry.number.getAsDouble() - entry.min) / (entry.max - entry.min)); this.entry = entry;
        }
        @Override protected void updateMessage() { if (entry != null) setMessage(ui(text(entry.value.get()))); }
        @Override protected void applyValue() { setNumber(entry, entry.min + value * (entry.max - entry.min), false); }
        void refresh() { value = (entry.number.getAsDouble() - entry.min) / (entry.max - entry.min); updateMessage(); }
        @Override public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean clipped = clipRowWidget(context,this);
            try { drawAnimatedControl(context,mouseX,mouseY,delta); }
            finally { if (clipped) context.disableScissor(); }
        }
        private void drawAnimatedControl(DrawContext context, int mouseX, int mouseY, float delta) {
            if (peeking) return;
            int x = getX(), y = getY(), w = getWidth();
            Text label = ui(getMessage());
            paintAlpha = modalAlpha * paneAlpha * rowOpacity(this);
            if (paintAlpha <= 0) return;
            drawUi(context, label, x + (int) ((w - textRenderer.getWidth(label) * .8) / 2), textY(y + 6, .8), MUTED, .8, false);
            RoundedGui.smoothFill(context, x + 4, y + 20, w - 8, 4, 2, ink(0x3BD9CAFF));
            int knob = x + 4 + (int) Math.round((w - 8) * value);
            RoundedGui.smoothFill(context, x + 4, y + 20, Math.max(1, knob - x - 4), 4, 2, ink(0xFFD9CAFF));
            boolean over = isHovered() || isFocused();
            double size = thumb.to(pointerHeld && over ? .92 : over ? 1.1 : 1, SettingsMotion.PRESS,
                    SettingsMotion.Curve.EASE, frameNanos, motionEnabled());
            int diameter = (int) Math.round(10 * size);
            RoundedGui.smoothFill(context, knob - diameter / 2, y + 22 - diameter / 2, diameter, diameter, diameter / 2, ink(0xFFF1EDFE));
            if (isFocused()) RoundedGui.smoothOutline(context, x - 2, y - 4, w + 4, 34, 8, ink(0x8FD9CAFF));
        }
    }
    private final class SearchField extends TextFieldWidget {
        private int visibleStart;
        private Entry binding;
        SearchField(int x, int y, int width, int height) {
            super(textRenderer, x, y, width, height, Text.translatable("config.relaylyrics.settings.search"));
            setMaxLength(96); setDrawsBackground(false);
        }
        SearchField(int x, int y, int width, int height, Entry binding) {
            this(x,y,width,height); this.binding = binding;
        }
        private String displayInput() {
            String raw = getText();
            return binding != null && binding.masked ? "•".repeat(raw.length()) : raw;
        }
        @Override public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean clipped = clipRowWidget(context,this);
            try { drawAnimatedControl(context,mouseX,mouseY,delta); }
            finally { if (clipped) context.disableScissor(); }
        }
        private void drawAnimatedControl(DrawContext context, int mouseX, int mouseY, float delta) {
            if (peeking) return;
            paintAlpha = modalAlpha * (binding == null ? 1 : paneAlpha * rowOpacity(this));
            if (paintAlpha <= 0) return;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            RoundedGui.smoothFill(context, x, y, w, h, 9, ink(0x3D000000));
            RoundedGui.smoothOutline(context, x, y, w, h, 9, ink(isFocused() ? 0x6BD9CAFF : 0x1FD9CAFF));
            SettingsTypography.icon(context, binding == null ? "search" : "CONNECTION", x + 8, y + h / 2 - 8, 16, ink(MUTED));
            int available = Math.max(20, (int) ((w - 40) / .9));
            String input = displayInput();
            int cursor = getCursor();
            visibleStart = Math.clamp(visibleStart, 0, input.length());
            if (cursor < visibleStart) visibleStart = cursor;
            while (visibleStart < cursor && textRenderer.getWidth(ui(Text.literal(input.substring(visibleStart, cursor)))) > available) visibleStart++;
            int end = visibleStart;
            while (end < input.length() && textRenderer.getWidth(ui(Text.literal(input.substring(visibleStart, end + 1)))) <= available) end++;
            String visible = input.substring(visibleStart, end);
            Text shown = input.isEmpty() ? net.minecraft.text.Text.translatable(binding == null ? "config.relaylyrics.settings.search" : binding.key + ".placeholder") : net.minecraft.text.Text.literal(visible);
            String trimmed = textRenderer.trimToWidth(ui(shown), available).getString();
            String selected = getSelectedText();
            if (!selected.isEmpty()) {
                int selectionStart = getText().startsWith(selected, cursor) ? cursor : cursor - selected.length();
                int selectionEnd = selectionStart + selected.length();
                int a = Math.clamp(selectionStart, visibleStart, end), b = Math.clamp(selectionEnd, visibleStart, end);
                int left = x + 29 + (int) Math.round(textRenderer.getWidth(ui(net.minecraft.text.Text.literal(input.substring(visibleStart, a)))) * .9);
                int right = x + 29 + (int) Math.round(textRenderer.getWidth(ui(net.minecraft.text.Text.literal(input.substring(visibleStart, b)))) * .9);
                if (right > left) context.fill(left, y + h / 2 - 7, right, y + h / 2 + 7, ink(0x55D9CAFF));
            }
            drawUi(context, net.minecraft.text.Text.literal(trimmed), x + 29, textY(y + h / 2.0, .9), input.isEmpty() ? TERTIARY : INK, .9, false);
            if (isFocused() && System.currentTimeMillis() / 500 % 2 == 0) {
                int at = x + 29 + (int) Math.round(textRenderer.getWidth(ui(net.minecraft.text.Text.literal(input.substring(visibleStart, Math.clamp(cursor, visibleStart, end))))) * .9);
                context.fill(at, y + h / 2 - 7, at + 1, y + h / 2 + 7, ink(INK));
            }
        }
        int cursorAt(double mouseX) {
            String value = displayInput();
            int start = Math.clamp(visibleStart, 0, value.length());
            double target = (mouseX - getX() - 29) / .9;
            for (int index = start; index < value.length(); index++) {
                int before = textRenderer.getWidth(ui(net.minecraft.text.Text.literal(value.substring(start, index))));
                int after = textRenderer.getWidth(ui(net.minecraft.text.Text.literal(value.substring(start, index + 1))));
                if (target < (before + after) / 2.0) return index;
            }
            return value.length();
        }
    }
    private void drawCategoryIcon(DrawContext context, Category source, int x, int y, int color) {
        if (source != null) SettingsTypography.icon(context, source.name(), x - 1, y - 1, 14, ink(color));
    }
    private void cross(DrawContext context, int x, int y, int radius, int color) {
        SettingsTypography.icon(context, "close", x - radius - 2, y - radius - 2, radius * 2 + 4, ink(color));
    }
}
