package dev.relaylyrics.mixin;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Camera.class)
public interface PresenceCameraMixin { @Invoker("getFov") float lyrica$presenceFov(); }
