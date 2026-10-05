package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Small bridge from the former immediate text API to 26.2 render submits. */
@FunctionalInterface
interface MultiBufferSource {
    VertexConsumer getBuffer(RenderType type);
}
