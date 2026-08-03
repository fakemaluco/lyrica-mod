package dev.fandroid.client.ui;

import dev.fandroid.client.ClientNotificationCenter;
import dev.fandroid.client.ClientRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.time.Duration;
import java.time.Instant;

public final class NotificationHudOverlay {
    private NotificationHudOverlay() { }

    public static void render(DrawContext context, ClientRuntime runtime) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof FanDroidScreen || runtime.incomingCall() != null) return;
        var recent = runtime.notifications().recent(1);
        if (recent.isEmpty()) return;
        ClientNotificationCenter.Entry notification = recent.getFirst();
        if (notification.kind() == ClientNotificationCenter.Kind.INCOMING_CALL
                || Duration.between(notification.createdAt(), Instant.now()).toMillis() > 5_000) return;
        int width = 264;
        int x = client.getWindow().getScaledWidth() - width - 16;
        int y = 18;
        int accent = switch (notification.kind()) {
            case SERVER_MESSAGE -> 0xFF45BFD2;
            case GLOBAL_MESSAGE -> 0xFF756A9D;
            case MISSED_CALL -> 0xFFA65D62;
            case CALL_STATUS -> 0xFF7A969C;
            case INCOMING_CALL -> 0xFF5F9277;
        };
        RetroDraw.outlineRounded(context, x, y, width, 76, 5, 1, accent, 0xF026292B);
        context.fill(x + 10, y + 12, x + 15, y + 64, accent);
        context.drawText(client.textRenderer, notification.title(), x + 28, y + 16,
                0xFFFFFFFF, false);
        String body = client.textRenderer.trimToWidth(notification.body(), width - 54);
        context.drawText(client.textRenderer, body, x + 28, y + 42, 0xFFBAC4C5, false);
    }
}
