package dev.relaylyrics.client.ui;

import java.awt.Color;
import java.util.HexFormat;

/** Draft HSV state. Retains the selected hue while moving through gray or black. */
final class SettingsColor {
    private final float[] hsv = new float[3];

    SettingsColor(int rgb) {
        setRgb(rgb);
    }

    float hue() { return hsv[0]; }
    float saturation() { return hsv[1]; }
    float brightness() { return hsv[2]; }

    void setHue(float hue) { hsv[0] = hue; }
    void setTone(float saturation, float brightness) {
        hsv[1] = saturation;
        hsv[2] = brightness;
    }

    void setRgb(int rgb) {
        float hue = hue(), saturation = saturation();
        Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, hsv);
        if (hsv[1] == 0) hsv[0] = hue;
        if (hsv[2] == 0) hsv[1] = saturation;
    }

    int rgb() { return argb(hue(), saturation(), brightness()) & 0xFFFFFF; }

    static int argb(float hue, float saturation, float brightness) {
        return Color.HSBtoRGB(hue, saturation, brightness);
    }

    static String hex(int rgb) {
        return "#" + HexFormat.of().withUpperCase().toHexDigits(rgb, 6);
    }
}
