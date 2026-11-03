package dev.relaylyrics.mixin;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(GameRenderer.class)
public interface PresenceCameraMixin {
    @Invoker("getFov") float lyrica$presenceFov(Camera camera, float delta, boolean changing);
}
