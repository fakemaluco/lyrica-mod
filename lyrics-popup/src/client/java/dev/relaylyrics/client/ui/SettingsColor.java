package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.SungTextColor;
import dev.relaylyrics.client.SungTextColor.Mode;
import dev.relaylyrics.client.SungTextColor.Scope;
import dev.relaylyrics.client.SungTextColor.Stop;
import java.awt.Color;
import java.util.ArrayList;
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

    static final class SungDraft {
        private SungTextColor value;
        private int selected;

        SungDraft(SungTextColor value) { this.value = value; }

        SungTextColor value() { return value; }
        int selected() { return selected; }
        boolean gradient() { return value.mode() == Mode.GRADIENT; }
        int rgb() { return gradient() ? value.stops().get(selected).rgb() : value.solid(); }
        int position() { return value.stops().get(selected).position(); }
        boolean canRemove() { return selected > 0 && selected < value.stops().size() - 1; }

        void mode(Mode mode) { value = new SungTextColor(mode, value.solid(), value.stops(), value.scope()); }
        void scope(Scope scope) { value = new SungTextColor(value.mode(), value.solid(), value.stops(), scope); }

        void color(int rgb) {
            if (gradient()) replaceSelected(new Stop(position(), rgb));
            else value = new SungTextColor(value.mode(), rgb, value.stops(), value.scope());
        }

        boolean pick(int position, int tolerance) {
            var stops = value.stops();
            int closest = selected;
            for (int index = 0; index < stops.size(); index++) {
                if (Math.abs(stops.get(index).position() - position) < Math.abs(stops.get(closest).position() - position))
                    closest = index;
            }
            if (Math.abs(stops.get(closest).position() - position) <= tolerance) {
                selected = closest;
                return true;
            }
            if (stops.size() >= SungTextColor.MAX_STOPS) return false;
            var next = new ArrayList<>(stops);
            selected = 1;
            while (next.get(selected).position() < position) selected++;
            next.add(selected, new Stop(position, value.gradient(position / (double) SungTextColor.RANGE)));
            value = new SungTextColor(value.mode(), value.solid(), next, value.scope());
            return true;
        }

        void move(int position) {
            if (!canRemove()) return;
            var stops = value.stops();
            position = Math.clamp(position, stops.get(selected - 1).position() + 1, stops.get(selected + 1).position() - 1);
            if (position != position()) replaceSelected(new Stop(position, rgb()));
        }

        void selectAdjacent(int direction) {
            selected = Math.clamp(selected + direction, 0, value.stops().size() - 1);
        }

        void remove() {
            if (!canRemove()) return;
            var next = new ArrayList<>(value.stops());
            next.remove(selected);
            selected--;
            value = new SungTextColor(value.mode(), value.solid(), next, value.scope());
        }

        private void replaceSelected(Stop stop) {
            var next = new ArrayList<>(value.stops());
            next.set(selected, stop);
            value = new SungTextColor(value.mode(), value.solid(), next, value.scope());
        }
    }
}
