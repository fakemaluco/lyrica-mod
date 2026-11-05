package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/** Minecraft 1.21.11 implementation backed by Matrix3x2fStack. */
public final class GuiTransform {
    private GuiTransform() { }
    public static boolean shiftDown() {
        long window = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    public static boolean scissorTransformsPose() { return true; }
    public static boolean nativeTextShadow() { return false; }
    public static boolean dualGlow() { return false; }

    /** Keep separately composed translucent surfaces out of the same sorted batch. */
    public static void nextLayer(DrawContext context) { context.createNewRootLayer(); }

    public static void enableScissor(DrawContext context, int left, int top, int right, int bottom) {
        context.enableScissor(left, top, right, bottom);
    }
    public static double screenY(DrawContext context, double x, double y) {
        var matrix = context.getMatrices();
        return matrix.m01() * x + matrix.m11() * y + matrix.m21();
    }

    /** Adjacent bands share one screen-space edge, even under a fractional GUI transform. */
    public static void enableScreenBand(DrawContext context, int top, int bottom, int width) {
        push(context);
        try {
            context.getMatrices().identity();
            context.enableScissor(0, top, width, bottom);
        } finally {
            pop(context);
        }
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
