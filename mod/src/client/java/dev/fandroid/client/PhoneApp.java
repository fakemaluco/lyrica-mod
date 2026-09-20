package dev.fandroid.client;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public enum PhoneApp {
    MESSAGES("app.fandroid.messages", 0xFF45BFD2, "messages"),
    PHONE("app.fandroid.phone", 0xFF70B8A4, "phone"),
    CONTACTS("app.fandroid.contacts", 0xFFC3A66F, "contacts"),
    MUSIC("app.fandroid.music", 0xFF8A79B4, "music"),
    SETTINGS("app.fandroid.settings", 0xFF77858E, "settings"),
    CAMERA("app.fandroid.camera", 0xFF4D96C2, "camera"),
    GALLERY("app.fandroid.gallery", 0xFFB78591, "gallery"),
    MAPS("app.fandroid.maps", 0xFF4E9498, "maps"),
    CLOCK("app.fandroid.clock", 0xFF46515A, "clock");

    private final String translationKey;
    private final int color;
    private final Identifier texture;

    PhoneApp(String translationKey, int color, String textureName) {
        this.translationKey = translationKey;
        this.color = color;
        this.texture = Identifier.of("fandroid", "textures/gui/relay/" + textureName + ".png");
    }

    public Text title() {
        return Text.translatable(translationKey);
    }

    public int color() {
        return color;
    }

    public boolean available() { return true; }

    public Identifier texture() { return texture; }
}
