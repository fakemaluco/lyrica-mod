package dev.relaylyrics.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 1.21.11 implementation backed by Matrix3x2fStack. */
public final class GuiTransform {
    private GuiTransform() { }
    public static boolean shiftDown() {
        return dev.relaylyrics.client.PlatformInput.shiftDown();
    }

    public static boolean scissorTransformsPose() { return true; }

    /** Keep separately composed translucent surfaces out of the same sorted batch. */
    public static void nextLayer(GuiGraphicsExtractor context) { context.nextStratum(); }

    public static void enableScissor(GuiGraphicsExtractor context, int left, int top, int right, int bottom) {
        context.enableScissor(left, top, right, bottom);
    }
    public static double screenY(GuiGraphicsExtractor context, double x, double y) {
        var matrix = context.pose();
        return matrix.m01() * x + matrix.m11() * y + matrix.m21();
    }

    /** Adjacent bands share one screen-space edge, even under a fractional GUI transform. */
    public static void enableScreenBand(GuiGraphicsExtractor context, int top, int bottom, int width) {
        push(context);
        try {
            context.pose().identity();
            context.enableScissor(0, top, width, bottom);
        } finally {
            pop(context);
        }
    }

    public static void finishUiDraw(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) { context.extractDeferredElements(mouseX, mouseY, delta); }

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
