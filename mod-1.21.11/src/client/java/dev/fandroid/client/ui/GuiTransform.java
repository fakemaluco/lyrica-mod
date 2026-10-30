package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/** Minecraft 1.21.11 implementation backed by Matrix3x2fStack. */
public final class GuiTransform {
    private GuiTransform() { }

    public static boolean scissorTransformsPose() { return true; }
    public static boolean nativeTextShadow() { return false; }
    public static boolean dualGlow() { return false; }

    /** Keep separately composed translucent surfaces out of the same sorted batch. */
    public static void nextLayer(DrawContext context) { context.createNewRootLayer(); }

    public static void enableScissor(DrawContext context, int left, int top, int right, int bottom) {
        context.enableScissor(left, top, right, bottom);
    }
    public static void finishUiDraw(DrawContext context, int mouseX, int mouseY, float delta) { context.drawDeferredElements(); }

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
