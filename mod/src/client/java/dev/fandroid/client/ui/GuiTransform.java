package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;

/** Minecraft 1.21.1 implementation of the small GUI matrix surface FanDroid uses. */
public final class GuiTransform {
    private GuiTransform() { }
    public static boolean shiftDown() {
        long window = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    public static boolean scissorTransformsPose() { return false; }
    public static boolean nativeTextShadow() { return false; }
    public static boolean dualGlow() { return false; }

    /** Immediate renderer already preserves paint order. */
    public static void nextLayer(DrawContext context) { }

    public static void enableScissor(DrawContext context, int left, int top, int right, int bottom) {
        var matrix = context.getMatrices().peek().getPositionMatrix();
        var a = matrix.transformPosition(new org.joml.Vector3f(left, top, 0));
        var b = matrix.transformPosition(new org.joml.Vector3f(right, bottom, 0));
        context.enableScissor((int) Math.floor(Math.min(a.x, b.x)), (int) Math.floor(Math.min(a.y, b.y)),
                (int) Math.ceil(Math.max(a.x, b.x)), (int) Math.ceil(Math.max(a.y, b.y)));
    }
    public static double screenY(DrawContext context, double x, double y) {
        var matrix = context.getMatrices().peek().getPositionMatrix();
        return matrix.m01() * x + matrix.m11() * y + matrix.m31();
    }

    /** Adjacent bands share one screen-space edge, even under a fractional GUI transform. */
    public static void enableScreenBand(DrawContext context, int top, int bottom, int width) {
        context.enableScissor(0, top, width, bottom);
    }

    public static void finishUiDraw(DrawContext context, int mouseX, int mouseY, float delta) {  }

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
