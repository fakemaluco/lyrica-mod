package dev.fandroid.client.ui;

import dev.fandroid.client.ClientRuntime;
import dev.fandroid.client.ClientNotificationCenter;
import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.FanDroidClient;
import dev.fandroid.client.PhoneApp;
import dev.fandroid.client.MusicSource;
import dev.fandroid.client.chat.LocalChatStore;
import dev.fandroid.client.chat.GlobalChatClient;
import dev.fandroid.client.music.MusicService;
import dev.fandroid.client.music.YouTubeMusicCompanion;
import dev.fandroid.client.essential.EssentialAdapter;
import dev.fandroid.client.voice.VoiceNoteController;
import dev.fandroid.protocol.VoiceAttachment;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.network.CapabilityFlags;
import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.UuidV7;
import dev.fandroid.protocol.music.MusicAction;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.OptionalLong;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FanDroidScreen extends FanDroidScreenBase {
    private static final int LOGICAL_WIDTH = 768;
    private static final int LOGICAL_HEIGHT = 970;
    private static final double PHONE_WIDTH_FACTOR = 0.90;
    private int COLOR_BACKGROUND = RelayTheme.ICE;
    private int COLOR_SURFACE = 0xFFF4F9F9;
    private int COLOR_SURFACE_SECONDARY = 0xFFD9E5E7;
    private int COLOR_LABEL = RelayTheme.TEXT;
    private int COLOR_SECONDARY_LABEL = RelayTheme.TEXT_MUTED;
    private int COLOR_SEPARATOR = 0xFF93A5AA;
    private static final int COLOR_BLUE = RelayTheme.CYAN_DARK;
    private static final int COLOR_GREEN = RelayTheme.GREEN;
    private static final int COLOR_RED = RelayTheme.RED;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private final ClientRuntime runtime;
    private final LyricsOverlay lyrics;
    private final MusicService music;
    private final PhoneNavigation navigation = new PhoneNavigation();
    private ChatTransport messageTransport = ChatTransport.GLOBAL;
    private LocalChatStore.ConversationRow selectedConversation;
    private ClientRuntime.DirectoryEntry selectedPeer;
    private GlobalChatClient.GlobalContact selectedGlobalContact;
    private String draft = "";
    private UUID editingTarget;
    private UUID replyTarget;
    private boolean contactsGlobal = true;
    private String contactQuery = "";
    private List<GlobalChatClient.AccountSearchResult> contactResults = List.of();
    private Layout layout = new Layout(0, 0, 1);
    private String toast;
    private long toastUntil;
    private final EssentialAdapter essential = EssentialAdapter.detect();
    private final VoiceNoteController voiceNotes = new VoiceNoteController();
    private final GalleryThumbnailCache galleryThumbnails = new GalleryThumbnailCache();
    private boolean musicScrubbing;
    private long scrubPositionMillis;
    private final long openedNanos = System.nanoTime();
    private long appTransitionNanos = openedNanos;
    private PhoneApp renderedApp;
    private long stopwatchStartedMillis;
    private long stopwatchElapsedMillis;
    private boolean stopwatchRunning;
    private int galleryScrollRows;
    private SettingsSection settingsSection = SettingsSection.GENERAL;
    private ExperimentalSection experimentalSection = ExperimentalSection.REACTIVE;

    public FanDroidScreen(ClientRuntime runtime, LyricsOverlay lyrics) {
        super(Text.translatable("screen.fandroid.phone"));
        this.runtime = runtime;
        this.lyrics = lyrics;
        this.music = FanDroidClient.music();
        if (runtime.activeCall() != null || runtime.outgoingCall() != null) {
            navigation.open(PhoneApp.PHONE);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Layout target = layout();
        double opening = runtime.preferences().isLyricsAnimations()
                ? Math.clamp((System.nanoTime() - openedNanos) / 320_000_000.0, 0, 1) : 1;
        double easedOpening = 1 - Math.pow(1 - opening, 3);
        int openingOffset = (int) Math.round((1 - easedOpening) * 46);
        layout = new Layout(target.x() + openingOffset, target.y() + openingOffset,
                target.scale() * (.90 + .10 * easedOpening));
        GuiTransform.push(context);
        GuiTransform.translate(context, layout.x(), layout.y());
        GuiTransform.scale(context, (float) (layout.scale() * PHONE_WIDTH_FACTOR), (float) layout.scale());
        int logicalMouseX = (int) ((mouseX - layout.x()) / (layout.scale() * PHONE_WIDTH_FACTOR));
        int logicalMouseY = (int) ((mouseY - layout.y()) / layout.scale());
        RetroDraw.setDarkMode(runtime.preferences().isPhoneDarkMode());
        try {
            renderPhone(context, logicalMouseX, logicalMouseY);
            if (logicalMouseX >= 0 && logicalMouseX < LOGICAL_WIDTH
                    && logicalMouseY >= 0 && logicalMouseY < LOGICAL_HEIGHT) {
                PlatformCursor.request(context, PlatformCursor.Style.POINTER);
            }
        } finally {
            RetroDraw.setDarkMode(false);
        }
        GuiTransform.pop(context);

    }

    private void renderPhone(DrawContext context, int mouseX, int mouseY) {
        updateThemePalette();
        boolean dark = runtime.preferences().isPhoneDarkMode();
        RetroDraw.rounded(context, 0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT, 3,
                dark ? 0xFF354247 : 0xFFC2CFD3);
        RetroDraw.outlineRounded(context, 7, 7, LOGICAL_WIDTH - 14, LOGICAL_HEIGHT - 14,
                3, 2, dark ? 0xFF172125 : 0xFF66777D,
                dark ? 0xFF202B2F : 0xFFE5ECEE);
        RetroDraw.outlineRounded(context, 292, 34, 184, 18, 2, 2,
                dark ? 0xFF52636A : 0xFF71858B, 0xFF192226);
        RetroDraw.outlineRounded(context, 38, 72, 692, 876, 2, 2,
                dark ? 0xFF070B0D : 0xFF1A252A, COLOR_BACKGROUND);
        drawWallpaper(context);
        PhoneApp currentApp = navigation.app();
        if (currentApp == null) drawStandbyStatusBar(context);
        else drawStatusBar(context);

        if (currentApp != renderedApp) {
            renderedApp = currentApp;
            appTransitionNanos = System.nanoTime();
        }
        if (navigation.isStandby()) renderStandby(context, mouseX, mouseY);
        else if (navigation.isMenu()) renderHome(context, mouseX, mouseY);
        else {
            double progress = runtime.preferences().isLyricsAnimations()
                    ? Math.clamp((System.nanoTime() - appTransitionNanos) / 240_000_000.0, 0, 1) : 1;
            double eased = 1 - Math.pow(1 - progress, 3);
            GuiTransform.push(context);
            GuiTransform.translate(context, (float) ((1 - eased) * 44), 0);
            renderApp(context, mouseX, mouseY);
            GuiTransform.pop(context);
        }
        renderIncomingCall(context, mouseX, mouseY);

        if (toast != null && toastUntil > System.currentTimeMillis()) {
            int width = Math.min(560, Math.round(textRenderer.getWidth(toast) * RetroDraw.BODY_TEXT_SCALE) + 42);
            RetroDraw.rounded(context, (LOGICAL_WIDTH - width) / 2, 870, width, 50, 24, 0xF0282B2E);
            RetroDraw.centeredText(context, textRenderer, RetroDraw.fit(textRenderer, toast, width - 32),
                    LOGICAL_WIDTH / 2, 886, 0xFFF4F1E8);
        }
    }

    private void drawWallpaper(DrawContext context) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        context.fillGradient(42, 122, 726, 944,
                dark ? 0xFF243136 : 0xFFF1F8F8,
                dark ? 0xFF10171A : 0xFFCBDCE0);
        context.fill(46, 126, 722, 130, dark ? 0x334FC4D5 : 0x88FFFFFF);
    }

    private void updateThemePalette() {
        if (runtime.preferences().isPhoneDarkMode()) {
            COLOR_BACKGROUND = 0xFF11191C;
            COLOR_SURFACE = 0xFF1C272B;
            COLOR_SURFACE_SECONDARY = 0xFF2A383D;
            COLOR_LABEL = 0xFFE9F1F1;
            COLOR_SECONDARY_LABEL = 0xFFAAB9BD;
            COLOR_SEPARATOR = 0xFF53676D;
        } else {
            COLOR_BACKGROUND = RelayTheme.ICE;
            COLOR_SURFACE = 0xFFF4F9F9;
            COLOR_SURFACE_SECONDARY = 0xFFD9E5E7;
            COLOR_LABEL = RelayTheme.TEXT;
            COLOR_SECONDARY_LABEL = RelayTheme.TEXT_MUTED;
            COLOR_SEPARATOR = 0xFF93A5AA;
        }
    }

    private void drawStatusBar(DrawContext context) {
        context.fillGradient(42, 76, 726, 134, RelayTheme.GRAPHITE_SOFT, 0xFF141C20);
        RetroDraw.text(context, textRenderer, java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                60, 95, 0xFFF4FAFA, 1.2F);
        String state = tr(runtime.hasServerCapability(CapabilityFlags.SERVER_CHAT)
                ? "ui.fandroid.server" : "ui.fandroid.global").toUpperCase(relayLocale());
        RetroDraw.centeredText(context, textRenderer, state, LOGICAL_WIDTH / 2, 95,
                0xFFDDEBED, 1.15F);
        RetroDraw.text(context, textRenderer, "▮▮▮  ▣", 624, 95, 0xFF9BD7E0, 1.05F);
        renderNotificationCount(context);
    }

    private void drawStandbyStatusBar(DrawContext context) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        context.fillGradient(42, 76, 726, 134,
                dark ? 0xFF344348 : 0xFFD7E5E7, dark ? 0xFF172126 : 0xFFACBEC3);
        RetroDraw.text(context, textRenderer, "▮▮▮", 58, 96, COLOR_LABEL, 1.15F);
        RetroDraw.centeredText(context, textRenderer,
                tr(runtime.hasServerCapability(CapabilityFlags.SERVER_CHAT)
                        ? "ui.fandroid.server" : "ui.fandroid.global").toUpperCase(relayLocale()),
                LOGICAL_WIDTH / 2, 96, COLOR_LABEL, 1.15F);
        RetroDraw.text(context, textRenderer, "[▮▮▮]", 642, 96, COLOR_LABEL, 1.05F);
        renderNotificationCount(context);
    }

    private void renderStandby(DrawContext context, int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        context.fillGradient(42, 134, 726, 902,
                dark ? 0xFF202C30 : 0xFFF3FAFA, dark ? 0xFF10171A : 0xFFC9DBDF);
        String now = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("h.mm a", Locale.US));
        Locale locale = relayLocale();
        String date = java.time.LocalDate.now()
                .format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy", locale)).toUpperCase(locale);
        RetroDraw.centeredText(context, textRenderer, now, LOGICAL_WIDTH / 2, 264,
                COLOR_LABEL, 5.0F);
        RetroDraw.centeredText(context, textRenderer, date, LOGICAL_WIDTH / 2, 350,
                COLOR_SECONDARY_LABEL, 1.15F);
        context.fill(104, 398, 664, 401, dark ? 0xFF52666D : 0xFF7F9297);
        renderStandbyNotifications(context, mouseX, mouseY);

        int fill = hovered(mouseX, mouseY, 170, 800, 428, 68) ? 0xFF324047 : RelayTheme.GRAPHITE;
        RetroDraw.rounded(context, 170, 800, 428, 68, 7, fill);
        context.fill(184, 807, 584, 811, 0x446FCFE0);
        RetroDraw.centeredText(context, textRenderer, "▼   " + tr("ui.fandroid.menu"), LOGICAL_WIDTH / 2, 820,
                0xFFE8EBE4, 1.45F);

        context.fillGradient(42, 902, 726, 944,
                dark ? 0xFF263439 : 0xFFC3D3D6, dark ? 0xFF172126 : 0xFF9FB3B8);
        RetroDraw.text(context, textRenderer, tr("ui.fandroid.msg"), 58, 916, COLOR_LABEL, 1.05F);
        RetroDraw.centeredText(context, textRenderer, tr("ui.fandroid.ok"), LOGICAL_WIDTH / 2, 916,
                COLOR_LABEL, 1.05F);
        String tel = tr("ui.fandroid.tel");
        RetroDraw.text(context, textRenderer, tel,
                710 - Math.round(textRenderer.getWidth(tel) * 1.05F), 916, COLOR_LABEL, 1.05F);
    }

    private void renderStandbyNotifications(DrawContext context, int mouseX, int mouseY) {
        List<ClientNotificationCenter.Entry> notifications = runtime.notifications().recent(3);
        int y = 444;
        for (ClientNotificationCenter.Entry notification : notifications) {
            boolean selected = hovered(mouseX, mouseY, 90, y, 588, 82);
            int fill = runtime.preferences().isPhoneDarkMode()
                    ? (selected ? 0xFF304147 : 0xFF243237)
                    : (selected ? 0xFFE6F0F1 : 0xFFD9E5E7);
            RetroDraw.outlineRounded(context, 90, y, 588, 82, 3, 1,
                    notification.read() ? COLOR_SEPARATOR : notificationAccent(notification.kind()), fill);
            RetroDraw.rounded(context, 104, y + 15, 8, 52, 3, notificationAccent(notification.kind()));
            RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, notification.title(), 390),
                    130, y + 13, COLOR_LABEL, 1.35F);
            RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, notification.body(), 390),
                    130, y + 43, COLOR_SECONDARY_LABEL, 1.1F);
            String time = TIME.format(notification.createdAt());
            RetroDraw.text(context, textRenderer, time,
                    652 - Math.round(textRenderer.getWidth(time) * 1.5F), y + 14,
                    COLOR_SECONDARY_LABEL, 1.0F);
            if (!notification.read()) RetroDraw.circle(context, 650, y + 57, 6,
                    notificationAccent(notification.kind()));
            y += 92;
        }
    }

    private void renderNotificationCount(DrawContext context) {
        int unread = runtime.notifications().unreadCount();
        if (unread <= 0) return;
        RetroDraw.circle(context, 600, 105, 15, 0xFFD34F47);
        RetroDraw.centeredText(context, textRenderer, Integer.toString(Math.min(99, unread)),
                600, 96, 0xFFFFFFFF, 1.0F);
    }

    private static int notificationAccent(ClientNotificationCenter.Kind kind) {
        return switch (kind) {
            case SERVER_MESSAGE -> 0xFF45BFD2;
            case GLOBAL_MESSAGE -> 0xFF756A9D;
            case INCOMING_CALL -> 0xFF5F9277;
            case MISSED_CALL -> 0xFFA65D62;
            case CALL_STATUS -> 0xFF7A969C;
        };
    }

    private void renderHome(DrawContext context, int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        context.fill(42, 134, 726, 884, dark ? 0xFF151E22 : 0xFFE8EFEF);
        int gridLine = dark ? 0x385B6B70 : 0x386F8187;
        context.fill(270, 158, 271, 872, gridLine);
        context.fill(498, 158, 499, 872, gridLine);
        context.fill(54, 394, 714, 395, gridLine);
        context.fill(54, 638, 714, 639, gridLine);
        PhoneApp[] apps = PhoneApp.values();
        for (int i = 0; i < apps.length; i++) {
            int column = i % 3;
            int row = i / 3;
            int x = 42 + column * 228;
            int y = 150 + row * 244;
            PhoneApp phoneApp = apps[i];
            boolean hovered = inside(mouseX, mouseY, x, y, 228, 244);
            boolean selected = hovered || i == navigation.selectedIndex();
            if (selected) RetroDraw.outlineRounded(context, x + 12, y + 12, 204, 210,
                    3, 1, dark ? 0xFF69C9D8 : 0xFF70868C, dark ? 0xFF27373C : 0xFFD7E2E3);
            RelayTheme.appIcon(context, phoneApp.texture(), x + 62, y + 38, 104);
            RetroDraw.centeredText(context, textRenderer, phoneApp.title(), x + 114, y + 166,
                    COLOR_LABEL, 1.28F);
            if (phoneApp == PhoneApp.MESSAGES) {
                int unread = unreadMessages();
                if (unread > 0) badge(context, x + 174, y + 20, Integer.toString(Math.min(unread, 99)));
            }
        }
        context.fill(42, 884, 726, 944, dark ? 0xFF202D32 : 0xFFBECBCD);
        RetroDraw.text(context, textRenderer, tr("ui.fandroid.back"), 58, 905, COLOR_LABEL, 1.2F);
        RetroDraw.centeredText(context, textRenderer, tr("ui.fandroid.ok"), LOGICAL_WIDTH / 2, 905,
                COLOR_LABEL, 1.2F);
        String tel = tr("ui.fandroid.tel");
        RetroDraw.text(context, textRenderer, tel,
                710 - Math.round(textRenderer.getWidth(tel) * 1.2F), 905, COLOR_LABEL, 1.2F);
    }

    private void renderApp(DrawContext context, int mouseX, int mouseY) {
        PhoneApp app = navigation.app();
        if (app == null) return;
        boolean dark = runtime.preferences().isPhoneDarkMode();
        RelayTheme.chromePanel(context, 62, 140, 644, 774, COLOR_SURFACE, dark);
        RetroDraw.outlineRounded(context, 76, 154, 56, 48, 3, 1,
                dark ? 0xFF53666C : 0xFF87999E,
                hovered(mouseX, mouseY, 76, 154, 56, 48)
                        ? (dark ? 0xFF34474D : 0xFFE8F5F6) : COLOR_SURFACE_SECONDARY);
        RetroDraw.centeredText(context, textRenderer, "‹", 104, 158, COLOR_BLUE, 3.0F);
        RetroDraw.text(context, textRenderer, app.title(), 150, 158, COLOR_LABEL, 2.35F);
        context.fill(150, 195, 666, 198, 0x5566C3D2);
        switch (app) {
            case MESSAGES -> renderMessages(context, mouseX, mouseY);
            case PHONE -> renderPhoneApp(context, mouseX, mouseY);
            case CONTACTS -> renderContacts(context, mouseX, mouseY);
            case MUSIC -> renderMusic(context, mouseX, mouseY);
            case SETTINGS -> renderSettings(context, mouseX, mouseY);
            case CAMERA -> renderCamera(context, mouseX, mouseY);
            case GALLERY -> renderGallery(context, mouseX, mouseY);
            case MAPS -> renderMaps(context);
            case CLOCK -> renderClock(context, mouseX, mouseY);
        }
    }

    private void renderMessages(DrawContext context, int mouseX, int mouseY) {
        if (selectedConversation != null) {
            renderConversation(context, mouseX, mouseY);
            return;
        }
        String[] tabs = {tr("ui.fandroid.global"), tr("ui.fandroid.server"), "Essential"};
        ChatTransport[] transports = ChatTransport.values();
        for (int i = 0; i < tabs.length; i++) {
            int x = 82 + i * 202;
            boolean selected = messageTransport == transports[i];
            RetroDraw.rounded(context, x, 214, 186, 48, 4,
                    selected ? COLOR_LABEL : COLOR_SURFACE_SECONDARY);
            RetroDraw.centeredText(context, textRenderer, tabs[i], x + 93, 225,
                    selected ? COLOR_SURFACE : COLOR_LABEL);
        }
        if (messageTransport == ChatTransport.ESSENTIAL) {
            RetroDraw.text(context, textRenderer, tr("messages.fandroid.experimental"), 92, 282, 0xFF8E5E31);
            drawWrapped(context, tr("messages.fandroid.essential_privacy"),
                    92, 316, 540, 0xFF5D5851);
            RetroDraw.text(context, textRenderer, essential.status(), 92, 386, 0xFF8B8276);
            return;
        }
        List<LocalChatStore.ConversationRow> conversations = runtime.chatStore().recentConversations(messageTransport, 6);
        int y = 278;
        for (LocalChatStore.ConversationRow conversation : conversations) {
            conversationRow(context, conversation, y, mouseX, mouseY);
            y += 78;
        }
        if (messageTransport == ChatTransport.SERVER) {
            if (!runtime.hasServerCapability(CapabilityFlags.SERVER_CHAT)) {
                emptyState(context, tr("messages.fandroid.server_unavailable"),
                        tr("messages.fandroid.server_unavailable_hint"));
            } else {
                RetroDraw.text(context, textRenderer, tr("messages.fandroid.server_players"), 92, y + 10,
                        0xFF777066, RetroDraw.SMALL_TEXT_SCALE);
                y += 40;
                for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
                    if (entry.playerId().equals(runtime.accountId())) continue;
                    RetroDraw.rounded(context, 86, y, 584, 54, 5,
                            hovered(mouseX, mouseY, 86, y, 584, 54) ? 0xFFD1D1D6 : COLOR_SURFACE_SECONDARY);
                    avatar(context, entry.username(), 98, y + 8, 38);
                    RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, entry.username(), 360),
                            152, y + 18, 0xFF302E2B);
                    RetroDraw.text(context, textRenderer, tr("messages.fandroid.message"), 568, y + 18,
                            COLOR_BLUE, RetroDraw.SMALL_TEXT_SCALE);
                    y += 62;
                }
            }
        } else {
            GlobalChatClient global = runtime.globalChat();
            if (global == null || global.state() != GlobalChatClient.State.ONLINE) {
                emptyState(context, tr("messages.fandroid.global_state",
                                global == null ? tr("state.fandroid.disabled")
                                        : global.state().name().toLowerCase(Locale.ROOT)),
                        tr("messages.fandroid.global_hint"));
            } else {
                RetroDraw.text(context, textRenderer, tr("messages.fandroid.contacts"), 92, y + 10,
                        0xFF777066, RetroDraw.SMALL_TEXT_SCALE);
                y += 40;
                for (GlobalChatClient.GlobalContact contact : global.contacts()) {
                    if (!contact.status().equals("ACCEPTED")) continue;
                    RetroDraw.rounded(context, 86, y, 584, 54, 5,
                            hovered(mouseX, mouseY, 86, y, 584, 54) ? 0xFFD1D1D6 : COLOR_SURFACE_SECONDARY);
                    avatar(context, contact.username(), 98, y + 8, 38);
                    RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, contact.username(), 360),
                            152, y + 18, 0xFF302E2B);
                    RetroDraw.text(context, textRenderer, tr("ui.fandroid.global"), 580, y + 18,
                            COLOR_BLUE, RetroDraw.SMALL_TEXT_SCALE);
                    y += 62;
                }
            }
        }
    }

    private void conversationRow(DrawContext context, LocalChatStore.ConversationRow conversation, int y,
                                 int mouseX, int mouseY) {
        RetroDraw.outlineRounded(context, 86, y, 584, 70, 5, 1, COLOR_SEPARATOR,
                hovered(mouseX, mouseY, 86, y, 584, 70) ? 0xFFD1D1D6 : COLOR_SURFACE_SECONDARY);
        avatar(context, conversation.title(), 98, y + 10, 50);
        RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, conversation.title(), 410),
                166, y + 9, COLOR_LABEL, 1.65F);
        RetroDraw.text(context, textRenderer, conversationPreview(conversation), 166, y + 40,
                COLOR_SECONDARY_LABEL, 1.05F);
        String time = TIME.format(conversation.updatedAt());
        RetroDraw.text(context, textRenderer, time,
                640 - Math.round(textRenderer.getWidth(time) * 1.5F), y + 10,
                COLOR_SECONDARY_LABEL, 1.0F);
        if (conversation.muted()) RetroDraw.text(context, textRenderer, tr("messages.fandroid.muted"),
                548, y + 42, COLOR_SECONDARY_LABEL, 1.0F);
        if (conversation.unread() > 0) badge(context, 642, y + 34, Integer.toString(conversation.unread()));
    }

    private void renderConversation(DrawContext context, int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        RetroDraw.outlineRounded(context, 86, 214, 584, 94, 5, 1, COLOR_SEPARATOR, COLOR_SURFACE_SECONDARY);
        avatar(context, selectedConversation.title(), 102, 230, 60);
        RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, selectedConversation.title(), 340),
                178, 230, COLOR_LABEL, 1.8F);
        String state = transportLabel(messageTransport)
                + (selectedConversation.muted() ? " • " + tr("messages.fandroid.muted") : "");
        RetroDraw.text(context, textRenderer, state, 178, 268, COLOR_SECONDARY_LABEL, 1.05F);
        boolean canCall = selectedPeer != null && runtime.hasServerCapability(CapabilityFlags.CALLS)
                && runtime.activeCall() == null && runtime.outgoingCall() == null;
        RetroDraw.outlineRounded(context, 558, 232, 90, 52, 4, 1,
                canCall ? COLOR_GREEN : COLOR_SEPARATOR,
                hovered(mouseX, mouseY, 558, 232, 90, 52) && canCall
                        ? 0xFF4D785F : COLOR_SURFACE);
        RetroDraw.centeredText(context, textRenderer, tr("messages.fandroid.call"), 603, 246,
                canCall ? COLOR_LABEL : COLOR_SECONDARY_LABEL, 1.05F);

        for (MessageBubble bubble : conversationBubbles()) {
            int fill = bubble.message().outgoing()
                    ? (dark ? 0xFF25444D : 0xFFD5E9F0)
                    : (dark ? 0xFF2A383D : 0xFFE6EAEB);
            RetroDraw.rounded(context, bubble.x(), bubble.y(), bubble.width(), bubble.height(), 6, fill);
            int lineY = bubble.y() + 10;
            for (OrderedText line : bubble.lines()) {
                RetroDraw.text(context, textRenderer, line, bubble.x() + 14, lineY,
                        bubble.message().deleted() ? COLOR_SECONDARY_LABEL : COLOR_LABEL,
                        RetroDraw.BODY_TEXT_SCALE);
                lineY += 22;
            }
            RetroDraw.text(context, textRenderer, TIME.format(bubble.message().createdAt()),
                    bubble.x() + 14, bubble.y() + bubble.height() - 20,
                    COLOR_SECONDARY_LABEL, 1.0F);
        }

        if (editingTarget != null || replyTarget != null) {
            String action = tr(editingTarget != null ? "messages.fandroid.edit" : "messages.fandroid.reply");
            RetroDraw.rounded(context, 98, 786, 170, 26, 3,
                    editingTarget != null ? 0xFF9A643F : 0xFF5C7188);
            RetroDraw.centeredText(context, textRenderer, action, 183, 790,
                    0xFFF5F5EF, 1.0F);
        }
        RetroDraw.outlineRounded(context, 86, 816, 584, 72, 5, 1, COLOR_SEPARATOR, COLOR_SURFACE);
        String shown = draft.isEmpty() ? tr(editingTarget == null
                ? "messages.fandroid.write" : "messages.fandroid.edit_message") : draft;
        RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, shown, 390), 104, 842,
                draft.isEmpty() ? COLOR_SECONDARY_LABEL : COLOR_LABEL);
        RetroDraw.circle(context, 550, 852, 25,
                voiceNotes.recording() ? 0xFFD34F47
                        : !voiceNotes.available() ? 0xFF9B9488
                        : hovered(mouseX, mouseY, 525, 827, 50, 50) ? 0xFF3A5367 : 0xFF5E7182);
        RetroDraw.centeredText(context, textRenderer, voiceNotes.recording() ? "■" : "●", 550, 839,
                0xFFF3ECDD, 1.55F);
        RetroDraw.rounded(context, 588, 827, 72, 50, 5,
                hovered(mouseX, mouseY, 588, 827, 72, 50) ? 0xFF0051A8 : COLOR_BLUE);
        RetroDraw.centeredText(context, textRenderer, tr("messages.fandroid.send"), 624, 840,
                COLOR_SURFACE, RetroDraw.SMALL_TEXT_SCALE);
    }

    private void renderPhoneApp(DrawContext context, int mouseX, int mouseY) {
        boolean available = runtime.hasServerCapability(CapabilityFlags.CALLS);
        ClientRuntime.ActiveCall active = runtime.activeCall();
        if (active != null) {
            RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.in_call"), LOGICAL_WIDTH / 2, 234,
                    COLOR_GREEN, RetroDraw.TITLE_TEXT_SCALE);
            RetroDraw.outlineRounded(context, 104, 278, 560, 210, 5, 1,
                    COLOR_SEPARATOR, COLOR_SURFACE_SECONDARY);
            avatar(context, active.username(), 128, 306, 150);
            RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, active.username(), 320),
                    306, 316, COLOR_LABEL, RetroDraw.TITLE_TEXT_SCALE);
            long seconds = java.time.Duration.between(active.startedAt(), java.time.Instant.now()).toSeconds();
            RetroDraw.text(context, textRenderer, "%02d:%02d".formatted(seconds / 60, seconds % 60),
                    306, 362, COLOR_SECONDARY_LABEL, 1.65F);
            drawWrapped(context, tr("phone.fandroid.audio_hint"), 306, 402, 320, COLOR_SECONDARY_LABEL);

            int muteColor = active.muted() ? 0xFFD1A34E
                    : hovered(mouseX, mouseY, 230, 532, 96, 96) ? 0xFF596468 : 0xFF444E52;
            RetroDraw.circle(context, 278, 580, 48, muteColor);
            drawMuteIcon(context, 278, 578, 0xFFF5EEE1, active.muted());
            RetroDraw.circle(context, 490, 580, 48,
                    hovered(mouseX, mouseY, 442, 532, 96, 96) ? 0xFFBE3E45 : COLOR_RED);
            drawCallIcon(context, 490, 580, 0xFFF5EEE1);
            RetroDraw.centeredText(context, textRenderer,
                    tr(active.muted() ? "phone.fandroid.unmute" : "phone.fandroid.mute"), 278, 644,
                    COLOR_LABEL, 1.1F);
            RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.hang_up"), 490, 644,
                    COLOR_LABEL, 1.1F);
            RetroDraw.text(context, textRenderer, tr("phone.fandroid.add_participants"), 104, 712,
                    COLOR_SECONDARY_LABEL, RetroDraw.SMALL_TEXT_SCALE);
            int addY = 746;
            for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
                if (entry.playerId().equals(runtime.accountId()) || entry.playerId().equals(active.playerId())) continue;
                RetroDraw.outlineRounded(context, 104, addY, 560, 48, 4, 1,
                        COLOR_SEPARATOR, COLOR_SURFACE_SECONDARY);
                RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, entry.username(), 430),
                        120, addY + 13, COLOR_LABEL);
                RetroDraw.text(context, textRenderer, tr("phone.fandroid.add"), 592, addY + 13,
                        COLOR_BLUE, RetroDraw.SMALL_TEXT_SCALE);
                addY += 56;
                if (addY > 870) break;
            }
            return;
        }
        ClientRuntime.OutgoingCall outgoing = runtime.outgoingCall();
        if (outgoing != null) {
            RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.calling"),
                    LOGICAL_WIDTH / 2, 244, COLOR_GREEN, RetroDraw.TITLE_TEXT_SCALE);
            avatar(context, outgoing.username(), 294, 316, 180);
            RetroDraw.centeredText(context, textRenderer, outgoing.username(), LOGICAL_WIDTH / 2, 520,
                    COLOR_LABEL, RetroDraw.TITLE_TEXT_SCALE);
            RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.scope_hint"),
                    LOGICAL_WIDTH / 2, 560, COLOR_SECONDARY_LABEL, 1.1F);
            RetroDraw.circle(context, LOGICAL_WIDTH / 2, 654, 52,
                    hovered(mouseX, mouseY, 332, 602, 104, 104) ? 0xFFBE3E45 : COLOR_RED);
            drawCallIcon(context, LOGICAL_WIDTH / 2, 654, 0xFFF5EEE1);
            RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.cancel"),
                    LOGICAL_WIDTH / 2, 724, COLOR_LABEL, 1.25F);
            return;
        }
        RetroDraw.outlineRounded(context, 86, 218, 584, 70, 5, 1, COLOR_SEPARATOR, COLOR_SURFACE_SECONDARY);
        RetroDraw.circle(context, 112, 253, 8, available ? COLOR_GREEN : COLOR_RED);
        RetroDraw.text(context, textRenderer,
                tr(available ? "phone.fandroid.voice_connected" : "phone.fandroid.unavailable"),
                136, 230, available ? COLOR_GREEN : COLOR_RED, 1.55F);
        RetroDraw.text(context, textRenderer, tr("phone.fandroid.scope_hint"), 136, 260,
                COLOR_SECONDARY_LABEL, 1.0F);
        int y = 310;
        for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
            if (entry.playerId().equals(runtime.accountId())) continue;
            RetroDraw.outlineRounded(context, 86, y, 584, 72, 5, 1, COLOR_SEPARATOR, COLOR_SURFACE_SECONDARY);
            avatar(context, entry.username(), 100, y + 10, 52);
            RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, entry.username(), 380),
                    170, y + 10, COLOR_LABEL, 1.55F);
            RetroDraw.text(context, textRenderer, tr("phone.fandroid.online"),
                    170, y + 42, available ? COLOR_GREEN : COLOR_SECONDARY_LABEL, 1.0F);
            RetroDraw.circle(context, 620, y + 36, 25,
                    !available ? 0xFF8E8E93
                            : hovered(mouseX, mouseY, 595, y + 11, 50, 50) ? 0xFF4B8061 : COLOR_GREEN);
            drawCallIcon(context, 620, y + 36, 0xFFF5EEE1);
            y += 82;
        }
        if (runtime.directory().size() <= 1) emptyState(context, tr("phone.fandroid.nobody"),
                tr("phone.fandroid.nobody_hint"));
    }

    private void renderContacts(DrawContext context, int mouseX, int mouseY) {
        RetroDraw.rounded(context, 82, 214, 280, 48, 24,
                contactsGlobal ? COLOR_LABEL : COLOR_SURFACE_SECONDARY);
        RetroDraw.rounded(context, 374, 214, 280, 48, 24,
                !contactsGlobal ? COLOR_LABEL : COLOR_SURFACE_SECONDARY);
        RetroDraw.centeredText(context, textRenderer, tr("ui.fandroid.global"), 222, 225,
                contactsGlobal ? COLOR_SURFACE : COLOR_LABEL);
        RetroDraw.centeredText(context, textRenderer, tr("ui.fandroid.server"), 514, 225,
                !contactsGlobal ? COLOR_SURFACE : COLOR_LABEL);
        if (!contactsGlobal) {
            int y = 276;
            for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
                RetroDraw.rounded(context, 86, y, 584, 60, 24, COLOR_SURFACE_SECONDARY);
                avatar(context, entry.username(), 98, y + 10, 40);
                RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, entry.username(), 360),
                        154, y + 16, 0xFF302E2B);
                RetroDraw.text(context, textRenderer,
                        tr(entry.playerId().equals(runtime.accountId())
                                ? "contacts.fandroid.you" : "contacts.fandroid.online"),
                        574, y + 19, COLOR_GREEN, RetroDraw.SMALL_TEXT_SCALE);
                y += 70;
            }
            return;
        }
        RetroDraw.outlineRounded(context, 86, 274, 584, 52, 26, 2, COLOR_SEPARATOR, COLOR_SURFACE);
        RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer,
                        contactQuery.isEmpty() ? tr("contacts.fandroid.search_placeholder") : contactQuery, 460),
                104, 288, contactQuery.isEmpty() ? 0xFF999085 : 0xFF2E2D2A);
        RetroDraw.rounded(context, 598, 274, 64, 52, 26,
                hovered(mouseX, mouseY, 598, 274, 64, 52) ? 0xFF0051A8 : COLOR_BLUE);
        RetroDraw.centeredText(context, textRenderer, tr("contacts.fandroid.search"), 630, 287,
                COLOR_SURFACE, 1.15F);

        int y = 350;
        if (!contactResults.isEmpty()) {
            RetroDraw.text(context, textRenderer, tr("contacts.fandroid.results"), 92, y,
                    0xFF777066, RetroDraw.SMALL_TEXT_SCALE);
            y += 26;
            for (GlobalChatClient.AccountSearchResult result : contactResults) {
                RetroDraw.rounded(context, 86, y, 584, 52, 22, COLOR_SURFACE_SECONDARY);
                avatar(context, result.username(), 98, y + 7, 38);
                RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, result.username(), 390),
                        152, y + 16, 0xFF302E2B);
                RetroDraw.text(context, textRenderer, tr("contacts.fandroid.add"), 594, y + 17,
                        COLOR_BLUE, RetroDraw.SMALL_TEXT_SCALE);
                y += 60;
            }
        } else if (runtime.globalChat() != null) {
            RetroDraw.text(context, textRenderer, tr("contacts.fandroid.contacts_requests"), 92, y,
                    0xFF777066, RetroDraw.SMALL_TEXT_SCALE);
            y += 26;
            for (GlobalChatClient.GlobalContact contact : runtime.globalChat().contacts()) {
                RetroDraw.rounded(context, 86, y, 584, 52, 22, COLOR_SURFACE_SECONDARY);
                avatar(context, contact.username(), 98, y + 7, 38);
                RetroDraw.text(context, textRenderer, RetroDraw.fit(textRenderer, contact.username(), 360),
                        152, y + 14, 0xFF302E2B);
                boolean canAccept = !contact.status().equals("ACCEPTED") && !contact.outgoing();
                String state = tr(contact.status().equals("ACCEPTED") ? "contacts.fandroid.contact"
                        : contact.outgoing() ? "contacts.fandroid.requested" : "contacts.fandroid.accept");
                RetroDraw.text(context, textRenderer, state, 562, y + 17,
                        canAccept ? COLOR_BLUE : COLOR_SECONDARY_LABEL, RetroDraw.SMALL_TEXT_SCALE);
                y += 60;
            }
        }
    }

    private void renderMusic(DrawContext context, int mouseX, int mouseY) {
        PhoneApp app = navigation.app();
        var snapshot = music.snapshot();
        int accent = music.artworkAccent() == 0 ? app.color() : music.artworkAccent();
        RetroDraw.outlineRounded(context, 88, 214, 576, 188, 3, 1,
                0xFF414844, 0xFF242A27);
        RetroDraw.outlineRounded(context, 102, 226, 164, 164, 3, 1,
                0xFF59615C, 0xFF151A17);
        if (!music.drawArtwork(context, 108, 232, 152, 152, 4)) {
            RetroDraw.centeredText(context, textRenderer, "♪", 184, 278,
                    0xFFE9ECE5, 5.0F);
        }
        String title = snapshot.title() == null || snapshot.title().equals("Nothing playing")
                ? tr("music.fandroid.nothing_playing") : snapshot.title();
        RetroDraw.boldText(context, textRenderer, RetroDraw.fit(textRenderer, title, 342),
                286, 240, 0xFFF2F4EE, 1.8F);
        RetroDraw.regularText(context, textRenderer, RetroDraw.fit(textRenderer, snapshot.artist(), 342),
                286, 270, 0xFFC8CEC7, 1.35F);
        long duration = Math.max(1, snapshot.durationMillis());
        long position = musicScrubbing ? scrubPositionMillis : music.positionMillis();
        double progressFraction = musicScrubbing
                ? Math.clamp(position / (double) duration, 0, 1)
                : lyrics.visualPlaybackProgress();
        int progress = (int) Math.round(progressFraction * 348);
        var capabilities = snapshot.capabilities();
        boolean progressFocused = musicScrubbing || hovered(mouseX, mouseY, 286, 298, 348, 28);
        int railHeight = progressFocused && capabilities.seek() ? 8 : 6;
        int railY = 310 - railHeight / 2;
        if (runtime.preferences().isModernMode()) {
            RoundedGui.smoothFill(context, 286, railY, 348, railHeight,
                    railHeight / 2, 0x806C7570);
        } else {
            RetroDraw.rounded(context, 286, railY, 348, railHeight,
                    railHeight / 2, 0x806C7570);
        }
        if (progress > 0) {
            if (runtime.preferences().isModernMode()) {
                RoundedGui.smoothFill(context, 286, railY, Math.max(railHeight, progress), railHeight,
                        railHeight / 2, accent);
            } else {
                RetroDraw.rounded(context, 286, railY, Math.max(railHeight, progress), railHeight,
                        railHeight / 2, accent);
            }
        }
        int thumbRadius = progressFocused && capabilities.seek() ? 9 : 7;
        RetroDraw.outlinedCircle(context, 286 + progress, 310, thumbRadius, 2, 0xFFF9FBF8, accent);
        RetroDraw.regularText(context, textRenderer, formatTime(position), 286, 322, 0xFFBAC1BB, 1.0F);
        String remaining = "-" + formatTime(Math.max(0, duration - position));
        RetroDraw.regularText(context, textRenderer, remaining,
                634 - Math.round(textRenderer.getWidth(remaining)), 322, 0xFFBAC1BB, 1.0F);

        musicTransportIcon(context, MusicIcon.PREVIOUS, 376, 344, 40,
                capabilities.previous(), false, mouseX, mouseY);
        musicTransportIcon(context, snapshot.playing() ? MusicIcon.PAUSE : MusicIcon.PLAY,
                432, 336, 56, capabilities.playPause(), snapshot.playing(), mouseX, mouseY);
        musicTransportIcon(context, MusicIcon.NEXT, 504, 344, 40,
                capabilities.next(), false, mouseX, mouseY);

        renderMusicLyricsPanel(context, mouseX, mouseY);
    }

    private void renderMusicLyricsPanel(DrawContext context, int mouseX, int mouseY) {
        RetroDraw.rounded(context, 88, 410, 576, 480, 3, 0xFF151C20);
        RetroDraw.outlineRounded(context, 88, 410, 576, 480, 3, 1,
                0xFF71868C, 0x00000000);
        boolean dynamic = music.drawArtworkBackdrop(context, 90, 412, 572, 476,
                runtime.preferences().getLyricsBlurStrength(),
                runtime.preferences().getLyricsBackgroundSpeedMultiplier()
                        * lyrics.getLyricsBackgroundReactiveMultiplier(),
                runtime.preferences().getLyricsBackgroundOpacityMultiplier());
        if (!dynamic) context.fillGradient(90, 412, 662, 888, 0xFF263136, 0xFF151C20);
        if (!music.importCandidates().isEmpty()) renderImportedChoices(context, mouseX, mouseY);
        else lyrics.renderInPhone(context, 104, 426, 544, 414,
                    layout.x(), layout.y(), layout.scale() * PHONE_WIDTH_FACTOR, layout.scale(),
                    mouseX, mouseY);
        String footer = "Lyrics ready".equals(music.lyricsStatus()) && !music.lyricsProvider().isBlank()
                ? tr("music.fandroid.source", music.lyricsProvider()) : localizedLyricsStatus();
        RetroDraw.regularText(context, textRenderer, RetroDraw.fit(textRenderer, footer, 440),
                104, 862, 0xFFD6E0E1, 1.05F);
        musicIconButton(context, MusicIcon.RETRY, 604, 848, 40, true, false, mouseX, mouseY);
    }

    private void musicIconButton(DrawContext context, MusicIcon icon, int x, int y, int size,
                                 boolean enabled, boolean active, int mouseX, int mouseY) {
        int fill = !enabled ? 0x3044474C : active ? 0x803B6489
                : hovered(mouseX, mouseY, x, y, size, size) ? 0x70444950 : 0x282A2F35;
        RetroDraw.outlineRounded(context, x, y, size, size, 4, 1,
                enabled ? 0x70717780 : 0x30585B60, fill);
        drawMusicIcon(context, icon, x + size / 2, y + size / 2,
                enabled ? 0xFFF7F5EF : 0xFF777A7E);
    }

    private void musicTransportIcon(DrawContext context, MusicIcon icon, int x, int y, int size,
                                    boolean enabled, boolean active, int mouseX, int mouseY) {
        int color = !enabled ? 0xFF777A7E
                : hovered(mouseX, mouseY, x, y, size, size) || active ? 0xFFFFFFFF : 0xFFE1E6E2;
        drawMusicIcon(context, icon, x + size / 2, y + size / 2, color);
    }

    private static void drawMusicIcon(DrawContext context, MusicIcon icon, int centerX, int centerY, int color) {
        switch (icon) {
            case PLAY -> drawTriangle(context, centerX - 5, centerY - 9, 14, 18, false, color);
            case PAUSE -> {
                context.fill(centerX - 7, centerY - 9, centerX - 2, centerY + 9, color);
                context.fill(centerX + 3, centerY - 9, centerX + 8, centerY + 9, color);
            }
            case PREVIOUS -> {
                context.fill(centerX - 9, centerY - 8, centerX - 6, centerY + 8, color);
                drawTriangle(context, centerX - 5, centerY - 8, 12, 16, true, color);
            }
            case NEXT -> {
                context.fill(centerX + 6, centerY - 8, centerX + 9, centerY + 8, color);
                drawTriangle(context, centerX - 7, centerY - 8, 12, 16, false, color);
            }
            case RETRY -> {
                context.fill(centerX - 8, centerY - 7, centerX + 5, centerY - 4, color);
                context.fill(centerX - 8, centerY - 7, centerX - 5, centerY + 6, color);
                context.fill(centerX - 5, centerY + 4, centerX + 7, centerY + 7, color);
                drawTriangle(context, centerX + 2, centerY - 10, 8, 8, false, color);
            }
        }
    }

    private static void drawTriangle(DrawContext context, int x, int y, int width, int height,
                                     boolean left, int color) {
        for (int row = 0; row < height; row++) {
            double phase = 1 - Math.abs((row + .5) / height * 2 - 1);
            int rowWidth = Math.max(1, (int) Math.ceil(width * phase));
            int start = left ? x + width - rowWidth : x;
            context.fill(start, y + row, start + rowWidth, y + row + 1, color);
        }
    }

    private void renderImportedChoices(DrawContext context, int mouseX, int mouseY) {
        RetroDraw.regularText(context, textRenderer, tr("music.fandroid.choose_import_short"), 108, 464,
                0xFFE9ECE6, 1.25F);
        int rowY = 500;
        for (int index = 0; index < Math.min(3, music.importCandidates().size()); index++) {
            var candidate = music.importCandidates().get(index);
            int fill = hovered(mouseX, mouseY, 106, rowY, 540, 68) ? 0xFF414844 : 0xFF2C322F;
            RetroDraw.beveled(context, 106, rowY, 540, 68, 4, fill, 0xFF59605B, 0xFF151916);
            RetroDraw.regularText(context, textRenderer, RetroDraw.fit(textRenderer, candidate.title(), 400),
                    122, rowY + 10, 0xFFECEFE9, 1.35F);
            RetroDraw.regularText(context, textRenderer, RetroDraw.fit(textRenderer, candidate.artist(), 400),
                    122, rowY + 40, 0xFFAFB6B0, 1.05F);
            RetroDraw.regularText(context, textRenderer, Math.round(candidate.score() * 100) + "%",
                    584, rowY + 24, 0xFFC8D6C9, 1.05F);
            rowY += 76;
        }
    }

    private String localizedLyricsStatus() {
        return switch (music.lyricsStatus()) {
            case "Start playback to find lyrics" -> tr("music.fandroid.status_start");
            case "Spotify DJ voice segment - no lyrics" -> tr("music.fandroid.status_dj");
            case "Finding lyrics..." -> tr("music.fandroid.status_finding");
            case "Searching Spicy Lyrics..." -> tr("music.fandroid.status_searching_spicy");
            case "Choose an imported TTML match" -> tr("music.fandroid.status_choose_import");
            case "Loading lyrics..." -> tr("music.fandroid.status_loading");
            case "Lyrics ready" -> tr("music.fandroid.status_ready");
            case "No lyrics in Spicy Lyrics" -> tr("music.fandroid.status_not_found");
            default -> music.lyricsStatus();
        };
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private void renderSettings(DrawContext context, int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        String[] tabs = {tr("settings.fandroid.general"), tr("settings.fandroid.lyrics"),
                tr("settings.fandroid.connections"), tr("settings.fandroid.privacy"),
                tr("settings.fandroid.experimental")};
        for (int index = 0; index < tabs.length; index++) {
            int x = 64 + index * 134;
            boolean selected = settingsSection.ordinal() == index;
            int fill = selected ? RelayTheme.GRAPHITE_SOFT
                    : hovered(mouseX, mouseY, x, 214, 124, 46)
                    ? (dark ? 0xFF34474D : 0xFFD4E3E5) : (dark ? 0xFF263439 : 0xFFE5EEEE);
            RetroDraw.outlineRounded(context, x, 214, 124, 46, 5, 2,
                    dark ? 0xFF536A71 : 0xFF819298, fill);
            RetroDraw.centeredText(context, textRenderer, tabs[index], x + 62, 227,
                    selected ? 0xFFF3FAFA : COLOR_LABEL, 1.05F);
        }
        switch (settingsSection) {
            case GENERAL -> renderGeneralSettings(context, mouseX, mouseY);
            case LYRICS -> renderLyricsSettings(context, mouseX, mouseY);
            case CONNECTIONS -> renderConnectionSettings(context, mouseX, mouseY);
            case PRIVACY -> renderPrivacySettings(context, mouseX, mouseY);
            case EXPERIMENTAL -> renderExperimentalSettings(context, mouseX, mouseY);
        }
    }

    private void renderGeneralSettings(DrawContext context, int mouseX, int mouseY) {
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.phone_size"), 92, 292,
                COLOR_SECONDARY_LABEL, RetroDraw.SMALL_TEXT_SCALE);
        double value = runtime.preferences().getPhoneHeight();
        RetroDraw.rounded(context, 92, 328, 500, 18, 4,
                runtime.preferences().isPhoneDarkMode() ? 0xFF45565C : 0xFFA5B4B8);
        int knob = 92 + (int) ((value - .35) / .35 * 500);
        RetroDraw.rounded(context, 92, 328, Math.max(18, knob - 92), 18, 4, RelayTheme.CYAN_DARK);
        RetroDraw.beveled(context, knob - 12, 319, 24, 36, 3,
                RelayTheme.GRAPHITE_SOFT, 0xFF7F959C, 0xFF172126);
        RetroDraw.text(context, textRenderer, Math.round(value * 100) + "%",
                610, 326, COLOR_LABEL, 1.15F);
        settingToggle(context, tr("settings.fandroid.motion"), runtime.preferences().isLyricsAnimations(), 92, 390);
        settingToggle(context, tr("settings.fandroid.dark_mode"), runtime.preferences().isPhoneDarkMode(), 92, 454);
        RelayTheme.glassPanel(context, 92, 526, 560, 148,
                runtime.preferences().isPhoneDarkMode() ? 0xAA25353A : 0xAAE0ECEE);
        RetroDraw.text(context, textRenderer, "Relay", 116, 550, COLOR_LABEL, 2.0F);
        drawWrapped(context, tr("settings.fandroid.general_hint"), 116, 594, 500, COLOR_SECONDARY_LABEL);
    }

    private void renderLyricsSettings(DrawContext context, int mouseX, int mouseY) {
        settingToggle(context, tr("settings.fandroid.popup"), runtime.preferences().isLyricsVisible(), 92, 282);
        settingToggle(context, tr("settings.fandroid.pin"), runtime.preferences().isLyricsPinned(), 92, 330);
        settingToggle(context, tr("settings.fandroid.simple"),
                runtime.preferences().isSimpleLyricsMode(), 92, 378);
        settingToggle(context, tr("settings.fandroid.dynamic"),
                runtime.preferences().isLyricsDynamicBackground(), 92, 426);
        settingToggle(context, tr("settings.fandroid.translation"),
                runtime.preferences().isLyricsTranslation(), 92, 474);
        settingToggle(context, tr("settings.fandroid.romanization"),
                runtime.preferences().isLyricsRomanization(), 92, 522);
        settingToggle(context, tr("settings.fandroid.shadow"),
                runtime.preferences().isLyricsTextShadow(), 92, 570);
        settingToggle(context, tr("settings.fandroid.cover_shadow"),
                runtime.preferences().isLyricsCoverShadow(), 92, 618);
        settingToggle(context, tr("settings.fandroid.modern"),
                runtime.preferences().isModernMode(), 92, 666);
        settingStepper(context, tr("settings.fandroid.size"),
                Math.round(runtime.preferences().getLyricsTextScale() * 100) + "%", 92, 714, mouseX, mouseY);
        settingStepper(context, tr("settings.fandroid.opacity"),
                runtime.preferences().getLyricsBackgroundOpacity() + "%", 92, 762, mouseX, mouseY);
        settingsButton(context, tr("settings.fandroid.advanced"), 92, 826, 560, 46, mouseX, mouseY);
        settingsButton(context, tr("settings.fandroid.reload"), 92, 886, 560, 46, mouseX, mouseY);
    }

    private void renderConnectionSettings(DrawContext context, int mouseX, int mouseY) {
        settingStepper(context, tr("settings.fandroid.music_source"),
                tr("settings.fandroid.music_source."
                        + runtime.preferences().getMusicSource().name().toLowerCase(Locale.ROOT)),
                92, 282, mouseX, mouseY);
        boolean youtube = runtime.preferences().getMusicSource() == MusicSource.YOUTUBE_MUSIC;
        settingsButton(context, tr("settings.fandroid.spotify_account"),
                92, 342, 270, 46, mouseX, mouseY);
        settingsButton(context, tr("settings.fandroid.youtube_bridge"),
                382, 342, 270, 46, mouseX, mouseY);
        String globalState = runtime.globalChat() == null ? tr("state.fandroid.not_configured")
                : titleCase(runtime.globalChat().state().name());
        settingsInfoCard(context, tr("settings.fandroid.global_service"), globalState, 92, 404,
                runtime.globalChat() != null && runtime.globalChat().state() == GlobalChatClient.State.ONLINE);
        boolean musicConnected = youtube ? music.youtubeMusicConnected() : music.spotifyAccountConnected();
        String musicState = musicConnected ? tr("state.fandroid.connected")
                : music.bridgeAlive() ? tr("state.fandroid.starting") : tr("state.fandroid.offline");
        settingsInfoCard(context, youtube ? "YouTube Music" : "Spotify",
                musicState, 92, 512, musicConnected);
        if (youtube && music.pairingCode() != null) {
            RetroDraw.text(context, textRenderer, tr("settings.fandroid.pair_code"), 116, 626,
                    RelayTheme.TEXT_MUTED, 1.1F);
            RetroDraw.text(context, textRenderer, music.pairingCode(), 116, 658,
                    RelayTheme.TEXT, 2.6F);
        }
        if (youtube) {
            drawWrapped(context, tr("settings.fandroid.youtube_track_match") + ": "
                            + music.spotifyTrackMatchStatus(),
                    100, 704, 548, RelayTheme.TEXT_MUTED);
        }
        drawWrapped(context, tr(youtube ? "settings.fandroid.youtube_hint"
                        : "settings.fandroid.connection_hint"),
                100, youtube ? 784 : 742, 548, RelayTheme.TEXT_MUTED);
    }

    private void renderPrivacySettings(DrawContext context, int mouseX, int mouseY) {
        settingToggle(context, tr("settings.fandroid.read_receipts"),
                runtime.preferences().isReadReceipts(), 92, 290);
        RelayTheme.glassPanel(context, 92, 374, 560, 156, 0xAADBE7E9);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.device"), 116, 398,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer,
                RetroDraw.fit(textRenderer, runtime.identity().deviceId().toString(), 510),
                116, 438, RelayTheme.TEXT, 1.15F);
        drawWrapped(context, tr("settings.fandroid.privacy_hint"),
                116, 478, 500, RelayTheme.TEXT_MUTED);
    }

    private void renderExperimentalSettings(DrawContext context, int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        for (int index = 0; index < ExperimentalSection.values().length; index++) {
            ExperimentalSection section = ExperimentalSection.values()[index];
            int x = 92 + index * 114;
            boolean selected = experimentalSection == section;
            int fill = selected ? RelayTheme.GRAPHITE_SOFT
                    : hovered(mouseX, mouseY, x, 244, 104, 34)
                    ? (dark ? 0xFF34474D : 0xFFD4E3E5) : (dark ? 0xFF263439 : 0xFFE5EEEE);
            RetroDraw.outlineRounded(context, x, 244, 104, 34, 5, 2,
                    dark ? 0xFF536A71 : 0xFF819298, fill);
            RetroDraw.centeredText(context, textRenderer, tr("settings.fandroid.exp." + section.name().toLowerCase(Locale.ROOT)),
                    x + 52, 255, selected ? 0xFFF3FAFA : COLOR_LABEL, .95F);
        }
        switch (experimentalSection) {
            case REACTIVE -> renderExperimentalReactive(context, mouseX, mouseY);
            case COLOR -> renderExperimentalColor(context, mouseX, mouseY);
            case BLOOM -> renderExperimentalBloom(context, mouseX, mouseY);
            case VISUAL -> renderExperimentalVisual(context, mouseX, mouseY);
            case DEBUG -> renderExperimentalDebug(context, mouseX, mouseY);
        }
    }

    private void renderExperimentalReactive(DrawContext context, int mouseX, int mouseY) {
        settingStepper(context, tr("settings.fandroid.reactive_source"),
                tr("settings.fandroid.reactive_source."
                        + runtime.preferences().getLyricsReactiveSource().name().toLowerCase(Locale.ROOT)),
                92, 292, mouseX, mouseY);
        settingValue(context, tr("settings.fandroid.reactive_output_reload"),
                tr("settings.fandroid.reactive_output_reload.button"),
                92, 356, mouseX, mouseY);
        settingStepper(context, tr("settings.fandroid.reactive_intensity"),
                Math.round(runtime.preferences().getLyricsReactiveIntensity() * 100) + "%",
                92, 420, mouseX, mouseY);
        settingToggle(context, tr("settings.fandroid.reactive"),
                runtime.preferences().isLyricsReactiveBackground(), 92, 484);
    }

    private void renderExperimentalColor(DrawContext context, int mouseX, int mouseY) {
        settingToggle(context, tr("settings.fandroid.reactive_hue"),
                runtime.preferences().isLyricsReactiveHue(), 92, 292);
        settingStepper(context, tr("settings.fandroid.reactive_hue_amount"),
                Math.round(runtime.preferences().getLyricsReactiveHueAmount()) + "\u00B0",
                92, 356, mouseX, mouseY);
        settingToggle(context, tr("settings.fandroid.reactive_hue_auto"),
                runtime.preferences().isLyricsReactiveHueAuto(), 92, 420);
    }

    private void renderExperimentalBloom(DrawContext context, int mouseX, int mouseY) {
        settingToggle(context, tr("settings.fandroid.reactive_bloom"),
                runtime.preferences().isLyricsReactiveBloom(), 92, 292);
        settingStepper(context, tr("settings.fandroid.reactive_bloom_amount"),
                Math.round(runtime.preferences().getLyricsReactiveBloomAmount()) + "%",
                92, 356, mouseX, mouseY);
        drawWrapped(context, tr("settings.fandroid.experimental_bloom_hint"), 116, 430, 500,
                RelayTheme.TEXT_MUTED);
    }

    private void renderExperimentalVisual(DrawContext context, int mouseX, int mouseY) {
        settingToggle(context, tr("settings.fandroid.reactive_spectral"),
                runtime.preferences().isLyricsReactiveSpectral(), 92, 292);
        settingStepper(context, tr("settings.fandroid.reactive_spectral_amount"),
                Math.round(runtime.preferences().getLyricsReactiveSpectralAmount()) + "%",
                92, 356, mouseX, mouseY);
    }

    private void renderExperimentalDebug(DrawContext context, int mouseX, int mouseY) {
        RelayTheme.glassPanel(context, 92, 292, 560, 200, 0xAADBE7E9);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.volume"), 116, 316,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer, Math.round(music.snapshot().volume() * 100) + "%",
                540, 316, RelayTheme.TEXT, 1.15F);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.playing"), 116, 348,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer, music.snapshot().playing()
                ? tr("settings.fandroid.yes") : tr("settings.fandroid.no"),
                540, 348, music.snapshot().playing() ? RelayTheme.GREEN : RelayTheme.TEXT_MUTED, 1.15F);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.waveform"), 116, 380,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer, Math.round(music.snapshot().intensity() * 100) + "%",
                540, 380, RelayTheme.TEXT, 1.15F);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.bpm"), 116, 412,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer,
                music.snapshot().bpm() > 0 ? Math.round(music.snapshot().bpm()) + " BPM" : "--",
                540, 412, RelayTheme.TEXT, 1.15F);
        RetroDraw.text(context, textRenderer, tr("settings.fandroid.beat_phase"), 116, 444,
                RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.text(context, textRenderer, Math.round(music.snapshot().beatPhase() * 100) + "%",
                540, 444, RelayTheme.TEXT, 1.15F);
        drawWrapped(context, tr("settings.fandroid.experimental_hint"),
                116, 508, 500, RelayTheme.TEXT_MUTED);
    }

    private void settingsInfoCard(DrawContext context, String title, String value, int x, int y, boolean online) {
        RelayTheme.glassPanel(context, x, y, 560, 94, online ? 0xAACFE5D6 : 0xAADFE4E5);
        RetroDraw.text(context, textRenderer, title, x + 24, y + 18, RelayTheme.TEXT, 1.35F);
        RetroDraw.text(context, textRenderer, value, x + 24, y + 54,
                online ? RelayTheme.GREEN : RelayTheme.TEXT_MUTED, 1.1F);
        RetroDraw.rounded(context, x + 520, y + 35, 14, 14, 7,
                online ? 0xFF5FBD83 : 0xFF8A979B);
    }

    private void settingToggle(DrawContext context, String label, boolean value, int x, int y) {
        RetroDraw.text(context, textRenderer, label, x, y + 10, COLOR_LABEL);
        RetroDraw.beveled(context, 556, y, 96, 48, 4, value ? COLOR_GREEN : 0xFF8E8E93,
                value ? 0xFF76A87D : 0xFFB0B0B2, 0xFF525356);
        RetroDraw.beveled(context, value ? 610 : 562, y + 6, 36, 36, 3,
                COLOR_SURFACE, runtime.preferences().isPhoneDarkMode() ? 0xFF65767C : 0xFFFFFFFF,
                runtime.preferences().isPhoneDarkMode() ? 0xFF11191C : 0xFF8C8B85);
    }

    private void settingValue(DrawContext context, String label, String value, int x, int y,
                              int mouseX, int mouseY) {
        RetroDraw.text(context, textRenderer, label, x, y + 10, COLOR_LABEL);
        settingsButton(context, value, 478, y, 174, 48, mouseX, mouseY);
    }

    private void settingStepper(DrawContext context, String label, String value, int x, int y,
                                int mouseX, int mouseY) {
        RetroDraw.text(context, textRenderer, label, x, y + 10, COLOR_LABEL);
        settingsButton(context, "-", 478, y, 46, 48, mouseX, mouseY);
        settingsButton(context, value, 530, y, 70, 48, mouseX, mouseY);
        settingsButton(context, "+", 606, y, 46, 48, mouseX, mouseY);
    }

    private void settingsButton(DrawContext context, String label, int x, int y, int width, int height,
                                int mouseX, int mouseY) {
        boolean dark = runtime.preferences().isPhoneDarkMode();
        int fill = hovered(mouseX, mouseY, x, y, width, height)
                ? (dark ? 0xFF3B4E54 : 0xFFBDC8D1) : (dark ? 0xFF2A383D : 0xFFD2D5D6);
        RetroDraw.beveled(context, x, y, width, height, 4, fill,
                dark ? 0xFF5B6D73 : 0xFFFFFFFF, dark ? 0xFF121A1D : 0xFF85888A);
        RetroDraw.centeredText(context, textRenderer, label, x + width / 2, y + 13, COLOR_LABEL, 1.15F);
    }

    private void renderCamera(DrawContext context, int mouseX, int mouseY) {
        RelayTheme.chromePanel(context, 92, 232, 584, 470, 0xFF1D272C);
        context.fillGradient(106, 246, 662, 688, 0xFF34454D, 0xFF10171A);
        RetroDraw.outlineRounded(context, 314, 300, 140, 140, 7, 5,
                0xFF86D8E4, 0xFF172328);
        RetroDraw.outlineRounded(context, 344, 330, 80, 80, 7, 4,
                0xFFB8F3FA, 0xFF245D70);
        RetroDraw.centeredText(context, textRenderer, tr("camera.fandroid.mode"), LOGICAL_WIDTH / 2, 520,
                0xFFF0F8F8, RetroDraw.TITLE_TEXT_SCALE);
        RetroDraw.centeredText(context, textRenderer, tr("camera.fandroid.hint"), LOGICAL_WIDTH / 2, 566,
                0xFFC3D2D5, 1.15F);
        int fill = hovered(mouseX, mouseY, 244, 752, 280, 64) ? 0xFF31798A : RelayTheme.GRAPHITE_SOFT;
        RetroDraw.beveled(context, 244, 752, 280, 64, 5, fill, 0xFF84D3DE, 0xFF172126);
        RetroDraw.centeredText(context, textRenderer, tr("camera.fandroid.capture"), LOGICAL_WIDTH / 2, 770,
                0xFFF5F5EF, 1.35F);
    }

    private void renderGallery(DrawContext context, int mouseX, int mouseY) {
        RetroDraw.text(context, textRenderer, tr("gallery.fandroid.recent"), 94, 232,
                RelayTheme.TEXT_MUTED, 1.25F);
        List<Path> screenshots = recentScreenshots();
        if (screenshots.isEmpty()) {
            emptyState(context, tr("gallery.fandroid.empty"), tr("gallery.fandroid.empty_hint"));
        } else {
            int totalRows = (screenshots.size() + 1) / 2;
            int maximumScroll = Math.max(0, totalRows - 3);
            galleryScrollRows = Math.clamp(galleryScrollRows, 0, maximumScroll);
            int first = galleryScrollRows * 2;
            int visible = Math.min(6, screenshots.size() - first);
            for (int slot = 0; slot < visible; slot++) {
                Path screenshot = screenshots.get(first + slot);
                int x = 92 + slot % 2 * 288;
                int y = 270 + slot / 2 * 180;
                boolean over = hovered(mouseX, mouseY, x, y, 272, 166);
                RetroDraw.outlineRounded(context, x, y, 272, 166, 6, over ? 4 : 2,
                        over ? RelayTheme.CYAN_DARK : 0xFF87999E, 0xFF202A2E);
                if (!galleryThumbnails.draw(context, screenshot, x + 4, y + 4, 264, 132)) {
                    context.fillGradient(x + 4, y + 4, x + 268, y + 136,
                            0xFF41545B, 0xFF1B252A);
                    RetroDraw.centeredText(context, textRenderer, "▧", x + 136, y + 54,
                            0xFFB7D8DE, 3.0F);
                }
                context.fill(x + 4, y + 136, x + 268, y + 162,
                        over ? 0xF02C444B : 0xE91B2529);
                RetroDraw.regularText(context, textRenderer,
                        RetroDraw.fit(textRenderer, screenshot.getFileName().toString(), 244),
                        x + 12, y + 143, 0xFFF0F6F6, 1.0F);
            }
            if (maximumScroll > 0) {
                int trackY = 270;
                int trackHeight = 526;
                int thumbHeight = Math.max(42, trackHeight * 3 / totalRows);
                int thumbY = trackY + (trackHeight - thumbHeight) * galleryScrollRows / maximumScroll;
                RetroDraw.rounded(context, 666, trackY, 6, trackHeight, 3, 0xFFB5C2C5);
                RetroDraw.rounded(context, 666, thumbY, 6, thumbHeight, 3, RelayTheme.CYAN_DARK);
            }
        }
        int fill = hovered(mouseX, mouseY, 244, 828, 280, 52) ? 0xFF337C8B : RelayTheme.GRAPHITE_SOFT;
        RetroDraw.beveled(context, 244, 828, 280, 52, 5, fill, 0xFF8DD9E2, 0xFF172126);
        RetroDraw.centeredText(context, textRenderer, tr("gallery.fandroid.open_folder"), LOGICAL_WIDTH / 2, 842,
                0xFFF5F5EF, 1.2F);
    }

    private void renderMaps(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            emptyState(context, tr("maps.fandroid.no_position"), tr("maps.fandroid.no_position_hint"));
            return;
        }
        var position = client.player.getBlockPos();
        RetroDraw.centeredText(context, textRenderer, tr("maps.fandroid.position"), LOGICAL_WIDTH / 2, 246,
                COLOR_SECONDARY_LABEL, 1.35F);
        RetroDraw.centeredText(context, textRenderer,
                position.getX() + "  " + position.getY() + "  " + position.getZ(),
                LOGICAL_WIDTH / 2, 332, COLOR_LABEL, 3.0F);
        RelayTheme.chromePanel(context, 264, 440, 240, 240, 0xFFDCE9E9);
        RetroDraw.centeredText(context, textRenderer, "N", LOGICAL_WIDTH / 2, 462, RelayTheme.RED, 2.0F);
        RetroDraw.centeredText(context, textRenderer, "W       +       E", LOGICAL_WIDTH / 2, 548,
                COLOR_LABEL, 1.55F);
        RetroDraw.centeredText(context, textRenderer, "S", LOGICAL_WIDTH / 2, 638, COLOR_LABEL, 2.0F);
        RetroDraw.centeredText(context, textRenderer,
                tr("maps.fandroid.facing", client.player.getHorizontalFacing().asString().toUpperCase(Locale.ROOT)),
                LOGICAL_WIDTH / 2, 738, COLOR_SECONDARY_LABEL, 1.25F);
    }

    private void renderClock(DrawContext context, int mouseX, int mouseY) {
        String now = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        RelayTheme.glassPanel(context, 112, 252, 544, 172, 0xBBDCEBED);
        RetroDraw.centeredText(context, textRenderer, now, LOGICAL_WIDTH / 2, 286, COLOR_LABEL, 5.2F);
        RetroDraw.centeredText(context, textRenderer,
                java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM", relayLocale()))
                        .toUpperCase(relayLocale()),
                LOGICAL_WIDTH / 2, 360, COLOR_SECONDARY_LABEL, 1.4F);
        long elapsed = stopwatchElapsedMillis + (stopwatchRunning
                ? System.currentTimeMillis() - stopwatchStartedMillis : 0);
        RelayTheme.glassPanel(context, 112, 488, 544, 278, 0xAAD8E5E7);
        RetroDraw.centeredText(context, textRenderer, tr("clock.fandroid.stopwatch"), LOGICAL_WIDTH / 2, 520,
                COLOR_SECONDARY_LABEL, 1.2F);
        RetroDraw.centeredText(context, textRenderer, formatStopwatch(elapsed), LOGICAL_WIDTH / 2, 566,
                COLOR_LABEL, 2.7F);
        utilityButton(context, tr(stopwatchRunning ? "clock.fandroid.pause" : "clock.fandroid.start"),
                170, 690, 200, 58, mouseX, mouseY);
        utilityButton(context, tr("clock.fandroid.reset"), 398, 690, 200, 58, mouseX, mouseY);
    }

    private void utilityButton(DrawContext context, String label, int x, int y, int width, int height,
                               int mouseX, int mouseY) {
        int fill = hovered(mouseX, mouseY, x, y, width, height) ? 0xFF555D57 : 0xFF69706A;
        RetroDraw.beveled(context, x, y, width, height, 5, fill, 0xFF929992, 0xFF343834);
        RetroDraw.centeredText(context, textRenderer, label, x + width / 2, y + 16,
                0xFFF5F5EF, 1.2F);
    }

    private List<Path> recentScreenshots() {
        Path folder = MinecraftClient.getInstance().runDirectory.toPath().resolve("screenshots");
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .limit(100)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception ignored) { return 0; }
    }

    private static String formatStopwatch(long millis) {
        long totalSeconds = Math.max(0, millis) / 1_000;
        return "%02d:%02d:%02d".formatted(totalSeconds / 3_600,
                totalSeconds / 60 % 60, totalSeconds % 60);
    }

    private void renderIncomingCall(DrawContext context, int mouseX, int mouseY) {
        ClientRuntime.IncomingCall call = runtime.incomingCall();
        if (call == null) return;
        context.fill(48, 124, 720, 932, 0xAA111315);
        boolean dark = runtime.preferences().isPhoneDarkMode();
        RetroDraw.outlineRounded(context, 114, 248, 540, 454, 5, 2,
                dark ? 0xFF60747A : 0xFF87999E, COLOR_SURFACE);
        RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.incoming"), LOGICAL_WIDTH / 2, 280,
                COLOR_GREEN, RetroDraw.TITLE_TEXT_SCALE);
        avatar(context, call.username(), 304, 342, 160);
        RetroDraw.centeredText(context, textRenderer, call.username(), LOGICAL_WIDTH / 2, 524,
                COLOR_LABEL, RetroDraw.TITLE_TEXT_SCALE);
        RetroDraw.circle(context, 270, 620, 46,
                hovered(mouseX, mouseY, 224, 574, 92, 92) ? 0xFFBE3E45 : COLOR_RED);
        drawCallIcon(context, 270, 620, 0xFFF6EEE0);
        RetroDraw.circle(context, 498, 620, 46,
                hovered(mouseX, mouseY, 452, 574, 92, 92) ? 0xFF4B8061 : COLOR_GREEN);
        drawCallIcon(context, 498, 620, 0xFFF6EEE0);
        RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.decline"), 270, 674,
                COLOR_LABEL, 1.1F);
        RetroDraw.centeredText(context, textRenderer, tr("phone.fandroid.answer"), 498, 674,
                COLOR_LABEL, 1.1F);
    }

    @Override
    protected boolean handleMouseClicked(double mouseX, double mouseY, int button) {
        if (lyrics.mousePressed(mouseX, mouseY, button)) return true;
        int x = logicalX(mouseX);
        int y = logicalY(mouseY);
        if (!inside(x, y, 0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT)) return false;
        ClientRuntime.IncomingCall call = runtime.incomingCall();
        if (call != null) {
            if (inside(x, y, 224, 574, 92, 92)) runtime.rejectCall();
            if (inside(x, y, 452, 574, 92, 92)) runtime.answerCall();
            return true;
        }
        if (!navigation.isApp()) {
            if (navigation.isStandby()) {
                int notificationY = 444;
                for (ClientNotificationCenter.Entry notification : runtime.notifications().recent(3)) {
                    if (inside(x, y, 90, notificationY, 588, 82)) {
                        openNotification(notification);
                        return true;
                    }
                    notificationY += 92;
                }
                if (inside(x, y, 170, 800, 428, 68) || inside(x, y, 300, 902, 168, 42)) {
                    navigation.showMenu();
                } else if (inside(x, y, 42, 902, 150, 42)) {
                    openShortcut(PhoneApp.MESSAGES);
                } else if (inside(x, y, 576, 902, 150, 42)) {
                    openShortcut(PhoneApp.PHONE);
                }
                return true;
            }
            PhoneApp[] apps = PhoneApp.values();
            for (int i = 0; i < apps.length; i++) {
                int iconX = 42 + i % 3 * 228;
                int iconY = 150 + i / 3 * 244;
                if (inside(x, y, iconX, iconY, 228, 244)) {
                    navigation.select(i);
                    if (apps[i].available()) openShortcut(apps[i]);
                    else toast(tr("label.fandroid.coming_soon"));
                    return true;
                }
            }
            if (inside(x, y, 42, 884, 160, 60)) {
                navigation.showStandby();
            } else if (inside(x, y, 304, 884, 160, 60)) {
                activateSelection();
            } else if (inside(x, y, 566, 884, 160, 60)) {
                openShortcut(PhoneApp.PHONE);
            }
            return true;
        }
        if (inside(x, y, 76, 154, 56, 48)) {
            navigateBack();
            return true;
        }
        switch (navigation.app()) {
            case MESSAGES -> clickMessages(x, y, button);
            case PHONE -> clickPhone(x, y);
            case CONTACTS -> clickContacts(x, y, button);
            case MUSIC -> clickMusic(x, y);
            case SETTINGS -> clickSettings(x, y);
            case CAMERA -> clickCamera(x, y);
            case GALLERY -> clickGallery(x, y);
            case MAPS -> { }
            case CLOCK -> clickClock(x, y);
        }
        return true;
    }

    private void clickCamera(int x, int y) {
        if (!inside(x, y, 244, 752, 280, 64)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(null);
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> client.execute(() ->
                ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(),
                        message -> client.inGameHud.getChatHud().addMessage(message))));
    }

    private void clickGallery(int x, int y) {
        List<Path> screenshots = recentScreenshots();
        int first = Math.clamp(galleryScrollRows, 0,
                Math.max(0, (screenshots.size() + 1) / 2 - 3)) * 2;
        int visible = Math.min(6, screenshots.size() - first);
        for (int slot = 0; slot < visible; slot++) {
            Path screenshot = screenshots.get(first + slot);
            int tileX = 92 + slot % 2 * 288;
            int tileY = 270 + slot / 2 * 180;
            if (inside(x, y, tileX, tileY, 272, 166)) {
                try { net.minecraft.util.Util.getOperatingSystem().open(screenshot); }
                catch (Exception error) { toast(tr("toast.fandroid.screenshot_failed")); }
                return;
            }
        }
        if (!inside(x, y, 244, 828, 280, 52)) return;
        try {
            Path folder = MinecraftClient.getInstance().runDirectory.toPath().resolve("screenshots");
            Files.createDirectories(folder);
            net.minecraft.util.Util.getOperatingSystem().open(folder);
        } catch (Exception error) {
            toast(tr("toast.fandroid.folder_failed"));
        }
    }

    private void clickClock(int x, int y) {
        if (inside(x, y, 170, 690, 200, 58)) {
            if (stopwatchRunning) {
                stopwatchElapsedMillis += System.currentTimeMillis() - stopwatchStartedMillis;
                stopwatchRunning = false;
            } else {
                stopwatchStartedMillis = System.currentTimeMillis();
                stopwatchRunning = true;
            }
        } else if (inside(x, y, 398, 690, 200, 58)) {
            stopwatchElapsedMillis = 0;
            stopwatchStartedMillis = System.currentTimeMillis();
        }
    }

    private void activateSelection() {
        if (navigation.isApp()) return;
        if (navigation.isStandby()) {
            navigation.showMenu();
            return;
        }
        PhoneApp selected = navigation.selection();
        if (selected.available()) openShortcut(selected);
        else toast(tr("label.fandroid.coming_soon"));
    }

    private void moveSelection(int direction) {
        if (!navigation.isMenu()) return;
        navigation.move(direction);
    }

    private void openShortcut(PhoneApp shortcut) {
        clearConversationState();
        if (shortcut == PhoneApp.MESSAGES) {
            runtime.notifications().markKindRead(ClientNotificationCenter.Kind.SERVER_MESSAGE);
            runtime.notifications().markKindRead(ClientNotificationCenter.Kind.GLOBAL_MESSAGE);
        } else if (shortcut == PhoneApp.PHONE) {
            runtime.notifications().markKindRead(ClientNotificationCenter.Kind.INCOMING_CALL);
            runtime.notifications().markKindRead(ClientNotificationCenter.Kind.MISSED_CALL);
            runtime.notifications().markKindRead(ClientNotificationCenter.Kind.CALL_STATUS);
        }
        navigation.open(shortcut);
    }

    private void openNotification(ClientNotificationCenter.Entry notification) {
        runtime.notifications().markRead(notification.id());
        switch (notification.kind()) {
            case SERVER_MESSAGE -> {
                messageTransport = ChatTransport.SERVER;
                openShortcut(PhoneApp.MESSAGES);
            }
            case GLOBAL_MESSAGE -> {
                messageTransport = ChatTransport.GLOBAL;
                openShortcut(PhoneApp.MESSAGES);
            }
            case INCOMING_CALL, MISSED_CALL, CALL_STATUS -> openShortcut(PhoneApp.PHONE);
        }
    }

    private void clearConversationState() {
        if (selectedConversation != null) runtime.chatStore().saveDraft(selectedConversation.id(), draft);
        selectedConversation = null;
        selectedPeer = null;
        selectedGlobalContact = null;
        draft = "";
        editingTarget = null;
        replyTarget = null;
    }

    private void navigateBack() {
        if (selectedConversation != null) {
            clearConversationState();
        } else if (navigation.back()) {
            MinecraftClient.getInstance().setScreen(null);
        }
    }

    private void clickMessages(int x, int y, int button) {
        if (selectedConversation == null && y >= 214 && y <= 262) {
            for (int i = 0; i < 3; i++) if (x >= 82 + i * 202 && x <= 268 + i * 202) {
                messageTransport = ChatTransport.values()[i];
                selectedConversation = null;
                selectedPeer = null;
                selectedGlobalContact = null;
                draft = "";
                editingTarget = null;
                replyTarget = null;
                return;
            }
        }
        if (selectedConversation != null) {
            if (inside(x, y, 558, 232, 90, 52) && selectedPeer != null
                    && runtime.hasServerCapability(CapabilityFlags.CALLS)
                    && runtime.activeCall() == null && runtime.outgoingCall() == null) {
                runtime.ring(selectedPeer.playerId());
                toast(tr("toast.fandroid.calling", selectedPeer.username()));
                clearConversationState();
                openShortcut(PhoneApp.PHONE);
                return;
            }
            for (MessageBubble bubble : conversationBubbles()) {
                LocalChatStore.MessageRow message = bubble.message();
                if (inside(x, y, bubble.x(), bubble.y(), bubble.width(), bubble.height())) {
                    if (message.voicePayload() != null && button == 0) {
                        try { voiceNotes.play(ProtocolCodec.read(message.voicePayload(), VoiceAttachment.class)); }
                        catch (Exception error) { toast(tr("toast.fandroid.voice_playback_failed")); }
                        return;
                    }
                    if (message.outgoing() && selectedPeer != null && messageTransport == ChatTransport.SERVER) {
                        if (button == 1) {
                            runtime.deleteServerMessage(selectedPeer, selectedConversation.id(), message.id());
                            toast(tr("toast.fandroid.message_deleted"));
                        } else if (message.voicePayload() == null
                                && message.createdAt().isAfter(java.time.Instant.now().minusSeconds(15 * 60))) {
                            editingTarget = message.id();
                            draft = message.body();
                            toast(tr("toast.fandroid.editing_message"));
                        }
                        return;
                    }
                    if (message.outgoing() && selectedGlobalContact != null && messageTransport == ChatTransport.GLOBAL) {
                        if (button == 1) {
                            runtime.globalChat().delete(selectedConversation.id(), selectedGlobalContact.accountId(),
                                    runtime.accountId(), message.id()).thenRun(() ->
                                    runtime.chatStore().deleteMessage(message.id(), runtime.accountId()));
                            toast(tr("toast.fandroid.message_deleted"));
                        } else if (message.voicePayload() == null
                                && message.createdAt().isAfter(java.time.Instant.now().minusSeconds(15 * 60))) {
                            editingTarget = message.id();
                            draft = message.body();
                            toast(tr("toast.fandroid.editing_message"));
                        }
                        return;
                    }
                    if (!message.outgoing() && button == 0 && message.voicePayload() == null) {
                        replyTarget = message.id();
                        editingTarget = null;
                        toast(tr("toast.fandroid.replying"));
                        return;
                    }
                }
            }
            if (inside(x, y, 525, 827, 50, 50)) {
                toggleVoiceRecording();
                return;
            }
            if (inside(x, y, 588, 827, 72, 50)) sendDraft();
            return;
        }
        List<LocalChatStore.ConversationRow> conversations = runtime.chatStore().recentConversations(messageTransport, 6);
        int rowY = 278;
        for (LocalChatStore.ConversationRow conversation : conversations) {
            if (inside(x, y, 86, rowY, 584, 70)) {
                if (button == 1) {
                    runtime.chatStore().setMuted(conversation.id(), !conversation.muted());
                    toast(tr(conversation.muted()
                            ? "toast.fandroid.conversation_unmuted" : "toast.fandroid.conversation_muted"));
                    return;
                }
                selectedConversation = conversation;
                if (messageTransport == ChatTransport.SERVER) {
                    selectedPeer = runtime.directory().stream()
                            .filter(entry -> entry.username().equals(conversation.title())).findFirst().orElse(null);
                    runtime.notifications().markKindRead(ClientNotificationCenter.Kind.SERVER_MESSAGE);
                } else if (messageTransport == ChatTransport.GLOBAL && runtime.globalChat() != null) {
                    selectedGlobalContact = runtime.globalChat().contacts().stream()
                            .filter(contact -> contact.username().equals(conversation.title()))
                            .findFirst().orElse(null);
                    runtime.notifications().markKindRead(ClientNotificationCenter.Kind.GLOBAL_MESSAGE);
                }
                draft = runtime.chatStore().draft(conversation.id());
                editingTarget = null;
                replyTarget = null;
                runtime.chatStore().markRead(conversation.id());
                if (selectedPeer != null && runtime.preferences().isReadReceipts()) {
                    runtime.chatStore().messages(conversation.id(), 0, 50).stream()
                            .filter(message -> !message.outgoing()).reduce((first, second) -> second)
                            .ifPresent(message -> runtime.sendServerReadReceipt(selectedPeer, conversation.id(), message.id()));
                }
                return;
            }
            rowY += 78;
        }
        if (messageTransport == ChatTransport.SERVER && runtime.hasServerCapability(CapabilityFlags.SERVER_CHAT)) {
            rowY += 40;
            for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
                if (entry.playerId().equals(runtime.accountId())) continue;
                if (inside(x, y, 86, rowY, 584, 54)) {
                    selectedPeer = entry;
                    UUID id = UuidV7.randomUuid();
                    runtime.chatStore().ensureConversation(id, ChatTransport.SERVER, "DIRECT", entry.username(), null);
                    selectedConversation = new LocalChatStore.ConversationRow(id, entry.username(), "DIRECT", 0, false, java.time.Instant.now());
                    draft = "";
                    editingTarget = null;
                    replyTarget = null;
                    return;
                }
                rowY += 62;
            }
        } else if (messageTransport == ChatTransport.GLOBAL && runtime.globalChat() != null
                && runtime.globalChat().state() == GlobalChatClient.State.ONLINE) {
            rowY += 40;
            for (GlobalChatClient.GlobalContact contact : runtime.globalChat().contacts()) {
                if (!contact.status().equals("ACCEPTED")) continue;
                if (inside(x, y, 86, rowY, 584, 54)) {
                    toast(tr("toast.fandroid.opening_secure"));
                    runtime.globalChat().createDirect(contact.accountId()).thenAccept(id -> {
                        if (client == null) return;
                        client.execute(() -> {
                            selectedGlobalContact = contact;
                            runtime.chatStore().ensureConversation(id, ChatTransport.GLOBAL, "DIRECT", contact.username(), null);
                            selectedConversation = new LocalChatStore.ConversationRow(id, contact.username(), "DIRECT", 0, false, java.time.Instant.now());
                            draft = "";
                            editingTarget = null;
                            replyTarget = null;
                        });
                    }).exceptionally(error -> { toast(tr("toast.fandroid.global_open_failed")); return null; });
                    return;
                }
                rowY += 62;
            }
        }
    }

    private void clickPhone(int x, int y) {
        if (!runtime.hasServerCapability(CapabilityFlags.CALLS)) return;
        if (runtime.activeCall() != null) {
            if (inside(x, y, 230, 532, 96, 96)) {
                runtime.toggleMute();
                return;
            }
            if (inside(x, y, 442, 532, 96, 96)) {
                runtime.hangUp();
                return;
            }
            int addY = 746;
            for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
                if (entry.playerId().equals(runtime.accountId())
                        || entry.playerId().equals(runtime.activeCall().playerId())) continue;
                if (inside(x, y, 104, addY, 560, 48)) {
                    runtime.ring(entry.playerId());
                    toast(tr("toast.fandroid.inviting", entry.username()));
                    return;
                }
                addY += 56;
                if (addY > 870) break;
            }
            return;
        }
        if (runtime.outgoingCall() != null) {
            if (inside(x, y, 332, 602, 104, 104)) runtime.cancelOutgoingCall();
            return;
        }
        int rowY = 310;
        for (ClientRuntime.DirectoryEntry entry : runtime.directory()) {
            if (entry.playerId().equals(runtime.accountId())) continue;
            if (inside(x, y, 595, rowY + 11, 50, 50)) {
                runtime.ring(entry.playerId());
                toast(tr("toast.fandroid.calling", entry.username()));
                return;
            }
            rowY += 82;
        }
    }

    private void clickContacts(int x, int y, int button) {
        if (inside(x, y, 82, 214, 280, 48)) { contactsGlobal = true; return; }
        if (inside(x, y, 374, 214, 280, 48)) { contactsGlobal = false; return; }
        if (!contactsGlobal || runtime.globalChat() == null) return;
        if (inside(x, y, 598, 274, 64, 52)) { searchContacts(); return; }
        int rowY = 376;
        if (!contactResults.isEmpty()) {
            for (GlobalChatClient.AccountSearchResult result : contactResults) {
                if (inside(x, y, 86, rowY, 584, 52)) {
                    runtime.globalChat().requestContact(result.accountId());
                    contactResults = List.of();
                    contactQuery = "";
                    toast(tr("toast.fandroid.contact_sent"));
                    return;
                }
                rowY += 60;
            }
        } else {
            for (GlobalChatClient.GlobalContact contact : runtime.globalChat().contacts()) {
                if (inside(x, y, 86, rowY, 584, 52)) {
                    if (button == 1) {
                        runtime.globalChat().block(contact.accountId());
                        toast(tr("toast.fandroid.contact_blocked"));
                    } else if (contact.status().equals("PENDING") && !contact.outgoing()) {
                        runtime.globalChat().acceptContact(contact.accountId());
                        toast(tr("toast.fandroid.contact_accepted"));
                    }
                    return;
                }
                rowY += 60;
            }
        }
    }

    private void searchContacts() {
        if (runtime.globalChat() == null || contactQuery.length() < 2) return;
        runtime.globalChat().searchAccounts(contactQuery).thenAccept(results -> {
            if (client != null) client.execute(() -> contactResults = results);
        });
    }

    private void clickMusic(int x, int y) {
        var snapshot = music.snapshot();
        var capabilities = snapshot.capabilities();
        if (inside(x, y, 286, 302, 348, 34) && capabilities.seek()) {
            musicScrubbing = true;
            updateScrub(x);
            return;
        }
        if (inside(x, y, 376, 344, 40, 40) && capabilities.previous()) music.command(MusicAction.PREVIOUS);
        else if (inside(x, y, 432, 336, 56, 56) && capabilities.playPause()) music.command(MusicAction.PLAY_PAUSE);
        else if (inside(x, y, 504, 344, 40, 40) && capabilities.next()) music.command(MusicAction.NEXT);
        else if (inside(x, y, 604, 848, 40, 40)) {
            music.retryLyrics();
        }
        if (!music.importCandidates().isEmpty()) {
            int rowY = 500;
            for (int index = 0; index < Math.min(3, music.importCandidates().size()); index++) {
                if (inside(x, y, 106, rowY, 540, 68)) { music.selectImportCandidate(index); return; }
                rowY += 76;
            }
        } else if (capabilities.seek()) {
            OptionalLong lyricPosition = lyrics.seekTargetAt(x, y);
            if (lyricPosition.isPresent()) {
                music.command(MusicAction.SEEK, lyricPosition.getAsLong());
                return;
            }
        }
    }

    private void clickSettings(int x, int y) {
        if (y >= 214 && y <= 260) {
            for (int index = 0; index < SettingsSection.values().length; index++) {
                if (inside(x, y, 64 + index * 134, 214, 124, 46)) {
                    settingsSection = SettingsSection.values()[index];
                    return;
                }
            }
        }
        if (settingsSection == SettingsSection.GENERAL) {
            if (inside(x, y, 92, 310, 500, 58)) {
                runtime.preferences().setPhoneHeight(.35 + Math.clamp((x - 92) / 500.0, 0, 1) * .35);
                runtime.preferences().save();
            } else if (inside(x, y, 556, 390, 96, 48)) {
                runtime.preferences().setLyricsAnimations(!runtime.preferences().isLyricsAnimations());
                runtime.preferences().save();
            } else if (inside(x, y, 556, 454, 96, 48)) {
                runtime.preferences().setPhoneDarkMode(!runtime.preferences().isPhoneDarkMode());
                runtime.preferences().save();
            }
            return;
        }
        if (settingsSection == SettingsSection.PRIVACY) {
            if (inside(x, y, 556, 290, 96, 48)) {
                runtime.preferences().setReadReceipts(!runtime.preferences().isReadReceipts());
                runtime.preferences().save();
            }
            return;
        }
        if (settingsSection == SettingsSection.EXPERIMENTAL) {
            if (y >= 244 && y <= 278) {
                for (int index = 0; index < ExperimentalSection.values().length; index++) {
                    if (inside(x, y, 92 + index * 114, 244, 104, 34)) {
                        experimentalSection = ExperimentalSection.values()[index];
                        return;
                    }
                }
            }
            switch (experimentalSection) {
                case REACTIVE -> {
                    if (inside(x, y, 478, 292, 46, 48)) {
                        runtime.preferences().setLyricsReactiveSource(
                                runtime.preferences().getLyricsReactiveSource().previous());
                        runtime.preferences().save();
                    } else if (inside(x, y, 530, 292, 70, 48)) {
                        runtime.preferences().setLyricsReactiveSource(
                                ClientPreferences.ReactiveSource.WORDS);
                        runtime.preferences().save();
                    } else if (inside(x, y, 606, 292, 46, 48)) {
                        runtime.preferences().setLyricsReactiveSource(
                                runtime.preferences().getLyricsReactiveSource().next());
                        runtime.preferences().save();
                    } else if (inside(x, y, 478, 356, 174, 48)) {
                        toast(tr(music.reloadReactiveAudioOutput()
                                ? "toast.fandroid.reactive_output_reloaded"
                                : "toast.fandroid.reactive_output_unavailable"));
                    } else if (inside(x, y, 478, 420, 46, 48)) {
                        runtime.preferences().setLyricsReactiveIntensity(
                                runtime.preferences().getLyricsReactiveIntensity() - .25);
                        runtime.preferences().save();
                    } else if (inside(x, y, 530, 420, 70, 48)) {
                        runtime.preferences().setLyricsReactiveIntensity(1.0);
                        runtime.preferences().save();
                    } else if (inside(x, y, 606, 420, 46, 48)) {
                        runtime.preferences().setLyricsReactiveIntensity(
                                runtime.preferences().getLyricsReactiveIntensity() + .25);
                        runtime.preferences().save();
                    } else if (inside(x, y, 556, 484, 96, 48)) {
                        runtime.preferences().setLyricsReactiveBackground(
                                !runtime.preferences().isLyricsReactiveBackground());
                        runtime.preferences().save();
                    }
                }
                case COLOR -> {
                    if (inside(x, y, 556, 292, 96, 48)) {
                        runtime.preferences().setLyricsReactiveHue(
                                !runtime.preferences().isLyricsReactiveHue());
                        runtime.preferences().save();
                    } else if (inside(x, y, 478, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveHueAmount(
                                runtime.preferences().getLyricsReactiveHueAmount() - 10);
                        runtime.preferences().save();
                    } else if (inside(x, y, 530, 356, 70, 48)) {
                        runtime.preferences().setLyricsReactiveHueAmount(80);
                        runtime.preferences().save();
                    } else if (inside(x, y, 606, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveHueAmount(
                                runtime.preferences().getLyricsReactiveHueAmount() + 10);
                        runtime.preferences().save();
                    } else if (inside(x, y, 556, 420, 96, 48)) {
                        runtime.preferences().setLyricsReactiveHueAuto(
                                !runtime.preferences().isLyricsReactiveHueAuto());
                        runtime.preferences().save();
                    }
                }
                case BLOOM -> {
                    if (inside(x, y, 556, 292, 96, 48)) {
                        runtime.preferences().setLyricsReactiveBloom(
                                !runtime.preferences().isLyricsReactiveBloom());
                        runtime.preferences().save();
                    } else if (inside(x, y, 478, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveBloomAmount(
                                runtime.preferences().getLyricsReactiveBloomAmount() - 25);
                        runtime.preferences().save();
                    } else if (inside(x, y, 530, 356, 70, 48)) {
                        runtime.preferences().setLyricsReactiveBloomAmount(100);
                        runtime.preferences().save();
                    } else if (inside(x, y, 606, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveBloomAmount(
                                runtime.preferences().getLyricsReactiveBloomAmount() + 25);
                        runtime.preferences().save();
                    }
                }
                case VISUAL -> {
                    if (inside(x, y, 556, 292, 96, 48)) {
                        runtime.preferences().setLyricsReactiveSpectral(
                                !runtime.preferences().isLyricsReactiveSpectral());
                        runtime.preferences().save();
                    } else if (inside(x, y, 478, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveSpectralAmount(
                                runtime.preferences().getLyricsReactiveSpectralAmount() - 25);
                        runtime.preferences().save();
                    } else if (inside(x, y, 530, 356, 70, 48)) {
                        runtime.preferences().setLyricsReactiveSpectralAmount(100);
                        runtime.preferences().save();
                    } else if (inside(x, y, 606, 356, 46, 48)) {
                        runtime.preferences().setLyricsReactiveSpectralAmount(
                                runtime.preferences().getLyricsReactiveSpectralAmount() + 25);
                        runtime.preferences().save();
                    }
                }
                case DEBUG -> { }
            }
            return;
        }
        if (settingsSection == SettingsSection.CONNECTIONS) {
            if (inside(x, y, 478, 282, 46, 48)) {
                runtime.preferences().setMusicSource(runtime.preferences().getMusicSource().previous());
                music.syncMusicSource();
                runtime.preferences().save();
            } else if (inside(x, y, 530, 282, 70, 48)) {
                runtime.preferences().setMusicSource(MusicSource.AUTOMATIC);
                music.syncMusicSource();
                runtime.preferences().save();
            } else if (inside(x, y, 606, 282, 46, 48)) {
                runtime.preferences().setMusicSource(runtime.preferences().getMusicSource().next());
                music.syncMusicSource();
                runtime.preferences().save();
            } else if (inside(x, y, 92, 342, 270, 46)) {
                MinecraftClient.getInstance().setScreen(
                        new SpotifyAccountScreen(this, runtime.preferences(), music));
            } else if (inside(x, y, 382, 342, 270, 46)) {
                runtime.preferences().setMusicSource(MusicSource.YOUTUBE_MUSIC);
                music.syncMusicSource();
                runtime.preferences().save();
                try {
                    MinecraftClient.getInstance().keyboard.setClipboard(
                            YouTubeMusicCompanion.installAndOpen().toString());
                } catch (IOException exception) {
                    dev.fandroid.FanDroidMod.LOGGER.warn("Could not extract YouTube Music companion", exception);
                }
            }
            return;
        }
        if (settingsSection != SettingsSection.LYRICS) return;
        if (inside(x, y, 556, 282, 96, 48)) {
            runtime.preferences().setLyricsVisible(!runtime.preferences().isLyricsVisible());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 330, 96, 48)) {
            runtime.preferences().setLyricsPinned(!runtime.preferences().isLyricsPinned());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 378, 96, 48)) {
            runtime.preferences().setSimpleLyricsMode(!runtime.preferences().isSimpleLyricsMode());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 426, 96, 48)) {
            runtime.preferences().setLyricsDynamicBackground(!runtime.preferences().isLyricsDynamicBackground());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 474, 96, 48)) {
            runtime.preferences().setLyricsTranslation(!runtime.preferences().isLyricsTranslation());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 522, 96, 48)) {
            runtime.preferences().setLyricsRomanization(!runtime.preferences().isLyricsRomanization());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 570, 96, 48)) {
            runtime.preferences().setLyricsTextShadow(!runtime.preferences().isLyricsTextShadow());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 618, 96, 48)) {
            runtime.preferences().setLyricsCoverShadow(!runtime.preferences().isLyricsCoverShadow());
            runtime.preferences().save();
        } else if (inside(x, y, 556, 666, 96, 48)) {
            runtime.preferences().setModernMode(!runtime.preferences().isModernMode());
            runtime.preferences().save();
        } else if (inside(x, y, 478, 714, 46, 48)) {
            adjustLyricsTextScale(-.1);
        } else if (inside(x, y, 530, 714, 70, 48)) {
            runtime.preferences().setLyricsTextScale(1);
            runtime.preferences().save();
        } else if (inside(x, y, 606, 714, 46, 48)) {
            adjustLyricsTextScale(.1);
        } else if (inside(x, y, 478, 762, 46, 48)) {
            adjustBackgroundOpacity(-10);
        } else if (inside(x, y, 530, 762, 70, 48)) {
            runtime.preferences().setLyricsBackgroundOpacity(100);
            runtime.preferences().save();
        } else if (inside(x, y, 606, 762, 46, 48)) {
            adjustBackgroundOpacity(10);
            runtime.preferences().save();
        } else if (inside(x, y, 92, 826, 560, 46)) {
            MinecraftClient.getInstance().setScreen(
                    new LyricsAdvancedScreen(this, runtime.preferences(), lyrics));
        } else if (inside(x, y, 92, 886, 560, 46)) {
            music.reloadImports();
            toast(tr("toast.fandroid.lyrics_reloaded"));
        }
    }

    private void adjustLyricsTextScale(double change) {
        double current = runtime.preferences().getLyricsTextScale();
        runtime.preferences().setLyricsTextScale(Math.round((current + change) * 10) / 10.0);
        runtime.preferences().save();
    }

    private void adjustBackgroundOpacity(int change) {
        runtime.preferences().setLyricsBackgroundOpacity(
                runtime.preferences().getLyricsBackgroundOpacity() + change);
        runtime.preferences().save();
    }

    private void updateScrub(int logicalX) {
        long duration = Math.max(0, music.snapshot().durationMillis());
        scrubPositionMillis = Math.round(Math.clamp((logicalX - 286) / 348.0, 0, 1) * duration);
    }

    private void sendDraft() {
        if (draft.isBlank()) return;
        try {
            if (messageTransport == ChatTransport.SERVER && selectedPeer != null) {
                if (editingTarget != null) {
                    runtime.editServerMessage(selectedPeer, selectedConversation.id(), editingTarget, draft.strip());
                    editingTarget = null;
                } else {
                    runtime.sendServerDirect(selectedPeer, selectedConversation.id(), draft.strip(), replyTarget);
                }
                replyTarget = null;
                draft = "";
                runtime.chatStore().saveDraft(selectedConversation.id(), "");
            } else if (messageTransport == ChatTransport.GLOBAL && selectedGlobalContact != null
                    && runtime.globalChat() != null) {
                String body = draft.strip();
                UUID conversationId = selectedConversation.id();
                if (editingTarget != null) {
                    UUID target = editingTarget;
                    runtime.globalChat().edit(conversationId, selectedGlobalContact.accountId(), runtime.accountId(),
                            target, body).thenRun(() -> runtime.chatStore().editMessage(target, runtime.accountId(), body,
                            java.time.Instant.now())).exceptionally(error -> {
                                toast(tr("toast.fandroid.global_edit_failed")); return null;
                            });
                    editingTarget = null;
                } else {
                    runtime.globalChat().send(conversationId, selectedGlobalContact.accountId(),
                            runtime.accountId(), body, replyTarget).thenAccept(messageId ->
                            runtime.chatStore().createMessage(messageId, conversationId, runtime.accountId(),
                                    body, null, java.time.Instant.now(), true))
                            .exceptionally(error -> { toast(tr("toast.fandroid.global_message_failed")); return null; });
                }
                replyTarget = null;
                draft = "";
                runtime.chatStore().saveDraft(selectedConversation.id(), "");
            } else {
                toast(tr(messageTransport == ChatTransport.GLOBAL
                        ? "toast.fandroid.global_offline" : "toast.fandroid.read_only"));
            }
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void toggleVoiceRecording() {
        if (messageTransport != ChatTransport.SERVER || selectedPeer == null
                || !runtime.hasServerCapability(CapabilityFlags.VOICE_MESSAGES)) {
            toast(tr("toast.fandroid.voice_unavailable"));
            return;
        }
        try {
            if (!voiceNotes.recording()) {
                voiceNotes.start();
                toast(tr("toast.fandroid.recording"));
            } else {
                VoiceAttachment attachment = voiceNotes.finish();
                runtime.sendServerVoice(selectedPeer, selectedConversation.id(), attachment);
                toast(tr("toast.fandroid.voice_sent"));
            }
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    @Override
    protected boolean handleCharTyped(char chr, int modifiers) {
        if (navigation.app() == PhoneApp.CONTACTS && contactsGlobal && !Character.isISOControl(chr)
                && contactQuery.length() < 16 && (Character.isLetterOrDigit(chr) || chr == '_')) {
            contactQuery += chr;
            return true;
        }
        if (navigation.app() == PhoneApp.MESSAGES && selectedConversation != null && !Character.isISOControl(chr)
                && draft.length() < 8_192) {
            draft += chr;
            return true;
        }
        return super.handleCharTyped(chr, modifiers);
    }

    @Override
    protected boolean handleMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical == 0) {
            return super.handleMouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        int x = logicalX(mouseX);
        int y = logicalY(mouseY);
        if (navigation.app() == PhoneApp.MUSIC && music.importCandidates().isEmpty()
                && inside(x, y, 104, 426, 544, 414)) {
            return lyrics.scrollLyrics(true, vertical);
        }
        if (navigation.app() != PhoneApp.GALLERY) {
            return super.handleMouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        if (!inside(x, y, 82, 250, 600, 566)) return false;
        int rows = (recentScreenshots().size() + 1) / 2;
        int maximum = Math.max(0, rows - 3);
        int direction = vertical > 0 ? -1 : 1;
        galleryScrollRows = Math.clamp(galleryScrollRows + direction, 0, maximum);
        return true;
    }

    @Override
    protected boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (runtime.incomingCall() != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                runtime.answerCall();
                openShortcut(PhoneApp.PHONE);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                runtime.rejectCall();
                return true;
            }
        }
        if (navigation.isMenu() && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A)) {
            moveSelection(-1);
            return true;
        }
        if (navigation.isMenu() && (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D)) {
            moveSelection(1);
            return true;
        }
        if (navigation.isMenu() && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_W)) {
            moveSelection(-3);
            return true;
        }
        if (navigation.isMenu() && (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S)) {
            moveSelection(3);
            return true;
        }
        if (!navigation.isApp() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            activateSelection();
            return true;
        }
        if (navigation.app() == PhoneApp.CONTACTS && contactsGlobal) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !contactQuery.isEmpty()) {
                contactQuery = contactQuery.substring(0, contactQuery.length() - 1);
                contactResults = List.of();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchContacts();
                return true;
            }
        }
        if (navigation.app() == PhoneApp.MESSAGES && selectedConversation != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !draft.isEmpty()) {
                draft = draft.substring(0, draft.offsetByCodePoints(draft.length(), -1));
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                sendDraft();
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            navigateBack();
            return true;
        }
        return super.handleKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean handleMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (musicScrubbing) {
            updateScrub(logicalX(mouseX));
            return true;
        }
        return lyrics.mouseDragged(mouseX, mouseY)
                || super.handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    protected boolean handleMouseReleased(double mouseX, double mouseY, int button) {
        if (musicScrubbing) {
            updateScrub(logicalX(mouseX));
            musicScrubbing = false;
            music.command(MusicAction.SEEK, scrubPositionMillis);
            return true;
        }
        return lyrics.mouseReleased() || super.handleMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        if (voiceNotes.recording()) {
            try { voiceNotes.finish(); } catch (Exception ignored) { }
        }
        galleryThumbnails.close();
        super.removed();
    }

    private Layout layout() {
        double phoneHeight = height * runtime.preferences().getPhoneHeight();
        double maxByWidth = (width - 24) / (.75 * PHONE_WIDTH_FACTOR);
        phoneHeight = Math.min(phoneHeight, maxByWidth);
        double scale = phoneHeight / LOGICAL_HEIGHT;
        int phoneWidth = (int) Math.round(LOGICAL_WIDTH * scale * PHONE_WIDTH_FACTOR);
        int x = width - phoneWidth - 14;
        int y = height - (int) Math.round(LOGICAL_HEIGHT * scale) - 14;
        return new Layout(x, y, scale);
    }

    private int logicalX(double physical) {
        return (int) ((physical - layout.x()) / (layout.scale() * PHONE_WIDTH_FACTOR));
    }
    private int logicalY(double physical) { return (int) ((physical - layout.y()) / layout.scale()); }
    private boolean hovered(int mouseX, int mouseY, int x, int y, int w, int h) { return inside(mouseX, mouseY, x, y, w, h); }
    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int unreadMessages() {
        int server = runtime.chatStore().recentConversations(ChatTransport.SERVER, 50).stream()
                .mapToInt(LocalChatStore.ConversationRow::unread).sum();
        int global = runtime.chatStore().recentConversations(ChatTransport.GLOBAL, 50).stream()
                .mapToInt(LocalChatStore.ConversationRow::unread).sum();
        return server + global;
    }

    private String conversationPreview(LocalChatStore.ConversationRow conversation) {
        List<LocalChatStore.MessageRow> latest = runtime.chatStore().messages(conversation.id(), 0, 1);
        if (latest.isEmpty()) return transportLabel(messageTransport);
        LocalChatStore.MessageRow message = latest.getFirst();
        String body = message.deleted() ? tr("messages.fandroid.deleted") : message.body();
        String prefix = message.outgoing() ? "› " : "";
        return RetroDraw.fit(textRenderer, prefix + (body == null ? "" : body), 330);
    }

    private List<MessageBubble> conversationBubbles() {
        if (selectedConversation == null) return List.of();
        List<LocalChatStore.MessageRow> chronological = new ArrayList<>(
                runtime.chatStore().messages(selectedConversation.id(), 0, 12));
        Collections.reverse(chronological);
        List<BubbleDraft> drafts = new ArrayList<>();
        for (LocalChatStore.MessageRow message : chronological) {
            String body = message.deleted() ? tr("messages.fandroid.deleted") : message.body();
            String displayBody = (message.replyTo() == null ? "" : "↩ ")
                    + (message.voicePayload() == null ? "" : "▶ ")
                    + (body == null ? "" : body);
            List<OrderedText> wrapped = textRenderer.wrapLines(RetroDraw.uiText(displayBody), 190);
            List<OrderedText> lines = wrapped.isEmpty() ? List.of(RetroDraw.uiText(" ").asOrderedText())
                    : List.copyOf(wrapped.subList(0, Math.min(4, wrapped.size())));
            int textWidth = lines.stream().mapToInt(textRenderer::getWidth).max().orElse(55);
            int width = Math.min(430, Math.max(140,
                    Math.round(textWidth * RetroDraw.BODY_TEXT_SCALE) + 28));
            int height = 32 + lines.size() * 22;
            drafts.add(new BubbleDraft(message, width, height, lines));
        }
        int availableHeight = 478;
        int totalHeight = drafts.stream().mapToInt(draft -> draft.height() + 8).sum();
        while (totalHeight > availableHeight && drafts.size() > 1) {
            BubbleDraft removed = drafts.removeFirst();
            totalHeight -= removed.height() + 8;
        }
        int y = 806 - totalHeight;
        List<MessageBubble> result = new ArrayList<>();
        for (BubbleDraft draft : drafts) {
            int x = draft.message().outgoing() ? 662 - draft.width() : 92;
            result.add(new MessageBubble(draft.message(), x, y, draft.width(), draft.height(), draft.lines()));
            y += draft.height() + 8;
        }
        return List.copyOf(result);
    }

    private void badge(DrawContext context, int x, int y, String value) {
        int width = Math.max(34, Math.round(textRenderer.getWidth(value) * RetroDraw.SMALL_TEXT_SCALE) + 18);
        RetroDraw.rounded(context, x - width / 2, y, width, 32, 16, 0xFFD34F47);
        RetroDraw.centeredText(context, textRenderer, value, x, y + 7,
                0xFFFFFFFF, RetroDraw.SMALL_TEXT_SCALE);
    }

    private void avatar(DrawContext context, String name, int x, int y, int size) {
        int color = 0xFF000000 | (name.hashCode() & 0x005F5F5F) | 0xFF606060;
        RetroDraw.rounded(context, x, y, size, size, size / 3, color);
        String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        RetroDraw.centeredText(context, textRenderer, initial, x + size / 2,
                y + size / 2 - Math.round(textRenderer.fontHeight * 1.1F), 0xFFF8F1E4, 2.2F);
    }

    private void emptyState(DrawContext context, String title, String description) {
        RetroDraw.centeredText(context, textRenderer, title, LOGICAL_WIDTH / 2, 548,
                0xFF514C45, RetroDraw.TITLE_TEXT_SCALE);
        RetroDraw.centeredText(context, textRenderer, RetroDraw.fit(textRenderer, description, 560),
                LOGICAL_WIDTH / 2, 586, 0xFF857D72, RetroDraw.SMALL_TEXT_SCALE);
    }

    private void drawWrapped(DrawContext context, String value, int x, int y, int width, int color) {
        int line = y;
        int scaledWidth = Math.max(1, (int) (width / RetroDraw.SMALL_TEXT_SCALE));
        for (var text : textRenderer.wrapLines(RetroDraw.uiText(value), scaledWidth)) {
            RetroDraw.text(context, textRenderer, text, x, line, color, RetroDraw.SMALL_TEXT_SCALE);
            line += Math.round(textRenderer.fontHeight * RetroDraw.SMALL_TEXT_SCALE) + 5;
        }
    }

    private void toast(String value) {
        toast = value == null ? tr("label.fandroid.unavailable") : value;
        toastUntil = System.currentTimeMillis() + 2_500;
    }

    private static String tr(String key, Object... args) {
        return Text.translatable(key, args).getString();
    }

    private static Locale relayLocale() {
        return tr("app.fandroid.messages").equals("Mensagens")
                ? Locale.forLanguageTag("pt-BR") : Locale.US;
    }

    private static String transportLabel(ChatTransport transport) {
        return switch (transport) {
            case GLOBAL -> tr("ui.fandroid.global");
            case SERVER -> tr("ui.fandroid.server");
            case ESSENTIAL -> "Essential";
        };
    }

    private static String titleCase(String value) {
        String normalized = value == null ? "" : value.toLowerCase().replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static void drawMusicFrame(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + 1, 0x90676A73);
        context.fill(x, y + 1, x + 1, y + height, 0x90676A73);
        context.fill(x + 1, y + height - 1, x + width, y + height, 0x70313640);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, 0x70313640);
    }

    private static void drawCallIcon(DrawContext context, int centerX, int centerY, int color) {
        context.fill(centerX - 18, centerY - 4, centerX + 19, centerY + 4, color);
        context.fill(centerX - 21, centerY - 12, centerX - 12, centerY + 5, color);
        context.fill(centerX + 12, centerY - 12, centerX + 21, centerY + 5, color);
    }

    private static void drawMuteIcon(DrawContext context, int centerX, int centerY, int color, boolean muted) {
        RetroDraw.rounded(context, centerX - 6, centerY - 16, 12, 24, 5, color);
        context.fill(centerX - 12, centerY + 5, centerX - 8, centerY + 13, color);
        context.fill(centerX + 8, centerY + 5, centerX + 12, centerY + 13, color);
        context.fill(centerX - 9, centerY + 11, centerX + 9, centerY + 15, color);
        context.fill(centerX - 2, centerY + 14, centerX + 3, centerY + 21, color);
        if (muted) {
            for (int offset = -17; offset <= 17; offset++) {
                int x = centerX + offset;
                int y = centerY + offset / 2;
                context.fill(x, y - 2, x + 2, y + 2, 0xFF4A3418);
            }
        }
    }

    private static int mixMusicColor(int first, int second, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int red = (int) Math.round(((first >> 16) & 0xFF) * (1 - t) + ((second >> 16) & 0xFF) * t);
        int green = (int) Math.round(((first >> 8) & 0xFF) * (1 - t) + ((second >> 8) & 0xFF) * t);
        int blue = (int) Math.round((first & 0xFF) * (1 - t) + (second & 0xFF) * t);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private enum MusicIcon { PREVIOUS, PLAY, PAUSE, NEXT, RETRY }
    private enum SettingsSection { GENERAL, LYRICS, CONNECTIONS, PRIVACY, EXPERIMENTAL }

    private enum ExperimentalSection { REACTIVE, COLOR, BLOOM, VISUAL, DEBUG }
    private record Layout(int x, int y, double scale) { }
    private record BubbleDraft(LocalChatStore.MessageRow message, int width, int height,
                               List<OrderedText> lines) { }
    private record MessageBubble(LocalChatStore.MessageRow message, int x, int y, int width, int height,
                                 List<OrderedText> lines) { }
}
