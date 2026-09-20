package dev.fandroid.client.ui;

import dev.fandroid.client.ClientRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.time.Duration;

public final class CallHudOverlay {
    private CallHudOverlay() {
    }

    public static void render(DrawContext context, ClientRuntime runtime) {
        ClientRuntime.IncomingCall call = runtime.incomingCall();
        MinecraftClient client = MinecraftClient.getInstance();
        if (call == null || client.currentScreen instanceof FanDroidScreen) return;
        int width = 264;
        int x = client.getWindow().getScaledWidth() - width - 16;
        int y = 18;
        RetroDraw.outlineRounded(context, x, y, width, 88, 5, 1, 0xFF607A68, 0xF026292B);
        context.fill(x + 10, y + 12, x + 15, y + 76, 0xFF76A665);
        context.drawText(client.textRenderer, Text.translatable("notification.fandroid.incoming_call"),
                x + 28, y + 14, 0xFFF2EBDD, false);
        context.drawText(client.textRenderer, call.username(), x + 28, y + 35, 0xFFFFFFFF, false);
        context.drawText(client.textRenderer, Text.translatable("notification.fandroid.answer_hint"),
                x + 28, y + 58, 0xFFAEB3B3, false);
        long remaining = Math.max(0, Duration.between(java.time.Instant.now(), call.expiresAt()).toSeconds());
        context.drawText(client.textRenderer, Long.toString(remaining), x + width - 28, y + 14,
                0xFFAEB3B3, false);
    }
}
