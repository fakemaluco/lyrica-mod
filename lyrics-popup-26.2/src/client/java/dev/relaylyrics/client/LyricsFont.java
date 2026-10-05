package dev.relaylyrics.client;

import net.minecraft.resources.Identifier;

/** Font resource used by the synchronized lyrics renderer. */
public enum LyricsFont {
    MINECRAFT("Minecraft", Identifier.withDefaultNamespace("default")),
    UNIFORM("Uniform", Identifier.withDefaultNamespace("uniform")),
    GOOGLE("Google Fonts", Identifier.fromNamespaceAndPath("lyrica", "google"));

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
