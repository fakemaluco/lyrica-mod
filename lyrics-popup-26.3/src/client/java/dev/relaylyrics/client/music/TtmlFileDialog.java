package dev.relaylyrics.client.music;

import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

final class TtmlFileDialog {
    private static final AtomicReference<CompletableFuture<String>> PENDING = new AtomicReference<>();
    private static final SDL_DialogFileCallback CALLBACK = SDL_DialogFileCallback.create((userdata, files, selectedFilter) -> {
        var result = PENDING.getAndSet(null);
        if (result == null) return;
        if (files == 0) {
            result.completeExceptionally(new IllegalStateException("SDL file dialog failed"));
        } else {
            long selected = PointerBuffer.create(files, 1).get(0);
            result.complete(selected == 0 ? null : MemoryUtil.memUTF8(selected));
        }
    });

    private TtmlFileDialog() { }

    static String open(String title) {
        var result = new CompletableFuture<String>();
        if (!PENDING.compareAndSet(null, result)) throw new IllegalStateException("A file dialog is already open");
        var client = Minecraft.getInstance();
        var name = MemoryUtil.memUTF8("TTML");
        var extension = MemoryUtil.memUTF8("ttml");
        var filter = SDL_DialogFileFilter.calloc(1);
        filter.get(0).name(name).pattern(extension);
        try {
            client.execute(() -> {
                int properties = SDLProperties.SDL_CreateProperties();
                try {
                    if (properties == 0
                            || !SDLProperties.SDL_SetPointerProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, filter.address())
                            || !SDLProperties.SDL_SetNumberProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1)
                            || !SDLProperties.SDL_SetPointerProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, client.getWindow().handle())
                            || !SDLProperties.SDL_SetStringProperty(properties, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title))
                        throw new IllegalStateException("Could not configure SDL file dialog");
                    SDLDialog.SDL_ShowFileDialogWithProperties(SDLDialog.SDL_FILEDIALOG_OPENFILE, CALLBACK, 0, properties);
                } catch (RuntimeException | LinkageError failure) {
                    result.completeExceptionally(failure);
                } finally {
                    if (properties != 0) SDLProperties.SDL_DestroyProperties(properties);
                }
            });
            return result.join();
        } finally {
            PENDING.compareAndSet(result, null);
            filter.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(extension);
        }
    }
}
