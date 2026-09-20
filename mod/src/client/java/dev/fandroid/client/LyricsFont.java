package dev.fandroid.client;

import net.minecraft.util.Identifier;

/** Font resource used by the synchronized lyrics renderer. */
public enum LyricsFont {
    MINECRAFT("Minecraft", Identifier.ofVanilla("default")),
    UNIFORM("Uniform", Identifier.ofVanilla("uniform")),
    GOOGLE("Google Fonts", Identifier.of("lyrica", "google"));

    private final String label;
    private final Identifier id;

    LyricsFont(String label, Identifier id) {
        this.label = label;
        this.id = id;
    }

    public String label() {
        return label;
    }

    public Identifier id() {
        return id;
    }

    public LyricsFont next() {
        return MINECRAFT;
    }
}
