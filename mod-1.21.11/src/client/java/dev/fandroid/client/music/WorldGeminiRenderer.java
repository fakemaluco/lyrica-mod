package dev.fandroid.client.music;

import dev.fandroid.client.RelayLyricsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.UniformType;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalInt;

/** Backend-native full-resolution world pass; HUD and hand are submitted afterwards. */
public final class WorldGeminiRenderer {
    private static GeminiShaderSession probe;
    private static RenderPipeline pipeline;
    private static GpuTexture copy;
    private static GpuTextureView copyView;
    private static GpuBuffer uniforms;
    private static String key="";
    private static long frames;
    private static float lastTime;
    public static void probeSession(GeminiShaderSession value) {
        if(!Boolean.getBoolean("lyrica.geminiWorldSelfTest")) throw new IllegalStateException("Probe is opt-in"); probe=value;
    }
    public static long frames() { return frames; }
    public static float lastTime() { return lastTime; }
    public static void render() {
        var client=MinecraftClient.getInstance();
        var session=probe!=null?probe:RelayLyricsClient.music()==null?null:RelayLyricsClient.music().geminiShaders();
        var effect=session==null?null:session.worldEffect();
        if(client.world==null || effect==null || effect.strength()<=0) { close(); return; }
        var target=client.getFramebuffer(); var color=target.getColorAttachment();
        if(color==null || target.getDepthAttachmentView()==null) return;
        try {
            var device=RenderSystem.getDevice();
            if(!key.equals(effect.key())) {
                String hash=GeminiShaderSpec.hash(effect.key());
                var id=Identifier.of("relaylyrics","gemini_world/"+hash);
                pipeline=RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                        .withSampler("SceneSampler").withSampler("DepthSampler").withUniform("WorldMusic",UniformType.UNIFORM_BUFFER)
                        .withVertexFormat(VertexFormats.EMPTY,VertexFormat.DrawMode.TRIANGLES)
                        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false).withoutBlend().build();
                if(!device.precompilePipeline(pipeline,(name,type)->type==ShaderType.VERTEX?GeminiShaderSpec.vertexSource(true):GeminiShaderSpec.fragmentSource(effect.shader().glsl(),true)).isValid())
                    throw new IllegalArgumentException("GLSL");
                key=effect.key();
            }
            int w=color.getWidth(0),h=color.getHeight(0);
            if(copy==null || copy.getWidth(0)!=w || copy.getHeight(0)!=h || copy.getFormat()!=color.getFormat()) {
                if(copyView!=null) copyView.close(); if(copy!=null) copy.close();
                copy=device.createTexture("Lyrica world source",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,color.getFormat(),w,h,1,1);
                copyView=device.createTextureView(copy);
            }
            if(uniforms==null) uniforms=device.createBuffer(()->"Lyrica world music",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64L);
            var encoder=device.createCommandEncoder();
            encoder.copyTextureToTexture(color,copy,0,0,0,0,0,w,h);
            encoder.writeToBuffer(uniforms.slice(),effect.uniforms(w,h,false));
            try(var pass=encoder.createRenderPass(()->"Lyrica Gemini world",target.getColorAttachmentView(),OptionalInt.empty())) {
                pass.setPipeline(pipeline); pass.setUniform("WorldMusic",uniforms);
                var sampler=RenderSystem.getSamplerCache().get(FilterMode.NEAREST);
                pass.bindTexture("SceneSampler",copyView,sampler); pass.bindTexture("DepthSampler",target.getDepthAttachmentView(),sampler);
                pass.draw(0,3);
            }
            frames++; lastTime=effect.time(); session.rendered(effect.key());
        } catch(RuntimeException failure) { session.renderFailed(effect.key(),"glsl"); }
    }
    public static void close() {
        if(copyView!=null) copyView.close(); if(copy!=null) copy.close(); if(uniforms!=null) uniforms.close();
        copyView=null;copy=null;uniforms=null;pipeline=null;key="";
    }
}
