package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/** Minecraft 1.21.11 implementation backed by Matrix3x2fStack. */
public final class GuiTransform {
    private GuiTransform() { }

    public static boolean scissorTransformsPose() { return true; }
    public static boolean nativeTextShadow() { return false; }
    public static boolean dualGlow() { return false; }

    public static void push(DrawContext context) {
        context.getMatrices().pushMatrix();
    }

    public static void pop(DrawContext context) {
        context.getMatrices().popMatrix();
    }

    public static void translate(DrawContext context, float x, float y) {
        context.getMatrices().translate(x, y);
    }

    public static void scale(DrawContext context, float x, float y) {
        context.getMatrices().scale(x, y);
    }
}
