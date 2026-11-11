package dev.relaylyrics.client.music;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

final class TtmlFileDialog {
    private TtmlFileDialog() { }

    static String open(String title) {
        try (var stack = MemoryStack.stackPush()) {
            var patterns = stack.pointers(stack.UTF8("*.ttml"), stack.UTF8("*.TTML"));
            return TinyFileDialogs.tinyfd_openFileDialog(title, "", patterns, "TTML", false);
        }
    }
}
