package dev.relaylyrics.mixin;

import dev.relaylyrics.client.MusicPresence;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(EntityRenderer.class)
public abstract class MusicPresenceNameMixin {
    @ModifyVariable(method = "renderLabelIfPresent", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Text lyrica$musicName(Text label, Entity entity, Text original,
            net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider buffers,
            int light, float delta) {
        return entity instanceof PlayerEntity && MusicPresence.track(entity.getUuid()) != null
                ? Text.empty().append(Text.literal("♫ ").formatted(Formatting.GREEN)).append(label) : label;
    }
}
