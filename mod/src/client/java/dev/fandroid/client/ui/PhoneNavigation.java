package dev.fandroid.client.ui;

import dev.fandroid.client.PhoneApp;

/** Pure navigation state so mouse, keyboard and physical keys share one behavior. */
public final class PhoneNavigation {
    public enum Layer { STANDBY, MENU, APP }

    private Layer layer = Layer.STANDBY;
    private PhoneApp app;
    private int selectedIndex;

    public Layer layer() { return layer; }
    public PhoneApp app() { return app; }
    public int selectedIndex() { return selectedIndex; }
    public boolean isStandby() { return layer == Layer.STANDBY; }
    public boolean isMenu() { return layer == Layer.MENU; }
    public boolean isApp() { return layer == Layer.APP; }

    public void showMenu() {
        app = null;
        layer = Layer.MENU;
    }

    public void showStandby() {
        app = null;
        layer = Layer.STANDBY;
    }

    public void open(PhoneApp value) {
        if (value == null) return;
        selectedIndex = value.ordinal();
        app = value;
        layer = Layer.APP;
    }

    public PhoneApp selection() { return PhoneApp.values()[selectedIndex]; }

    public void select(int index) {
        selectedIndex = Math.floorMod(index, PhoneApp.values().length);
    }

    public void move(int direction) { select(selectedIndex + direction); }

    /** Returns true only when Back at standby should close the whole screen. */
    public boolean back() {
        if (layer == Layer.APP) showMenu();
        else if (layer == Layer.MENU) showStandby();
        else return true;
        return false;
    }
}
