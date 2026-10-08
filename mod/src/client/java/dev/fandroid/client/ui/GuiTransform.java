package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/** Minecraft 1.21.1 implementation of the small GUI matrix surface FanDroid uses. */
public final class GuiTransform {
    private GuiTransform() { }

    public static boolean scissorTransformsPose() { return false; }
    public static boolean nativeTextShadow() { return false; }
    public static boolean dualGlow() { return false; }

    public static void push(DrawContext context) {
        context.getMatrices().push();
    }

    public static void pop(DrawContext context) {
        context.getMatrices().pop();
    }

    public static void translate(DrawContext context, float x, float y) {
        context.getMatrices().translate(x, y, 0);
    }

    public static void scale(DrawContext context, float x, float y) {
        context.getMatrices().scale(x, y, 1);
    }
}
