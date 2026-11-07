package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.karaoke.KaraokeInvitationLayout.Action;

public final class KaraokePanelMotion {
    private final SettingsMotion.Tween visibility = new SettingsMotion.Tween(0);
    private double closingOpacity;
    private boolean closing;

    public boolean closing() { return closing; }

    public double opacity(long now, boolean enabled) {
        return visibility.to(closing ? 0 : 1, closing ? SettingsMotion.FAST : SettingsMotion.MODAL,
                SettingsMotion.Curve.OUT, now, enabled);
    }

    public void close(long now) {
        if (closing) return;
        closingOpacity = visibility.value(now);
        closing = true;
        visibility.to(0, SettingsMotion.FAST, SettingsMotion.Curve.OUT, now, true);
    }

    public double scale(double opacity) {
        return closing && closingOpacity > 0 ? SettingsMotion.modalScale(opacity / closingOpacity) : 1;
    }

    public static final class Buttons {
        private final SettingsMotion.Tween accept = new SettingsMotion.Tween(0);
        private final SettingsMotion.Tween decline = new SettingsMotion.Tween(0);
        private long acceptPressedAt;

        public void pressAccept(long now) { acceptPressedAt = now; }
        public boolean pressingAccept(long now) {
            return acceptPressedAt != 0 && now - acceptPressedAt < SettingsMotion.PRESS * 1_000_000L;
        }
        public double pressScale(long now) {
            return pressingAccept(now) ? .97 + .03 * SettingsMotion.Curve.OUT.at(
                    (now - acceptPressedAt) / (SettingsMotion.PRESS * 1_000_000.0)) : 1;
        }
        public double hover(Action action, Action hovered, long now, boolean enabled) {
            return (action == Action.ACCEPT ? accept : decline).to(
                    action == hovered ? 1 : 0, SettingsMotion.FAST, SettingsMotion.Curve.EASE, now, enabled);
        }
    }
}
