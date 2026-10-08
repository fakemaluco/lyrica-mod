package dev.relaylyrics.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 1.21.11 implementation backed by Matrix3x2fStack. */
public final class GuiTransform {
    private GuiTransform() { }

    public static boolean scissorTransformsPose() { return true; }

    public static void push(GuiGraphicsExtractor context) {
        context.pose().pushMatrix();
    }

    public static void pop(GuiGraphicsExtractor context) {
        context.pose().popMatrix();
    }

    public static void translate(GuiGraphicsExtractor context, float x, float y) {
        context.pose().translate(x, y);
    }

    public static void scale(GuiGraphicsExtractor context, float x, float y) {
        context.pose().scale(x, y);
    }
}
