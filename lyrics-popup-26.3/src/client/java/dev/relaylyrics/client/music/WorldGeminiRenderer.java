package dev.relaylyrics.client.music;

import dev.relaylyrics.client.RelayLyricsClient;
import net.minecraft.client.Minecraft;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.FilterMode;

import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.ShaderSource;

import java.util.Optional;

/** Backend-native full-resolution world pass; HUD and hand are submitted afterwards. */
public final class WorldGeminiRenderer {
    private static GeminiShaderSession probe;
    private static CompiledRenderPipeline pipeline;
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
        var client=Minecraft.getInstance();
        var session=probe!=null?probe:RelayLyricsClient.music()==null?null:RelayLyricsClient.music().geminiShaders();
        var effect=session==null?null:session.worldEffect();
        if(client.level==null || effect==null || effect.strength()<=0) { close(); return; }
        var target=client.gameRenderer.mainRenderTarget(); var color=target.getColorTexture();
        if(color==null || target.getDepthTextureView()==null) return;
        try {
            var device=RenderSystem.getDevice();
            if(!key.equals(effect.key())) {
                String hash=GeminiShaderSpec.hash(effect.key());
                var id=Identifier.fromNamespaceAndPath("relaylyrics","gemini_world/"+hash);
                var layout=BindGroupLayout.builder().withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("WorldMusic",UniformType.UNIFORM_BUFFER).build();
                var description=RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                        .withBindGroupLayout(layout).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                        .withColorTargetState(new ColorTargetState(Optional.empty(),color.getFormat(),ColorTargetState.WRITE_ALL)).build();
                var compiled = device.compilePipeline(description, new ShaderSource() {
                    @Override public String getShader(Identifier name, ShaderType type) {
                        return type == ShaderType.VERTEX ? GeminiShaderSpec.vertexSource(true)
                                : GeminiShaderSpec.fragmentSource(effect.shader().glsl(), true);
                    }
                    @Override public CachedIncludeSource getInclude(Identifier name) { return null; }
                    @Override public void close() { }
                }, Runnable::run).join().finishCompile();
                if (compiled == null) throw new IllegalArgumentException("GLSL");
                if (pipeline != null) pipeline.close();
                pipeline = compiled;
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
            encoder.writeToBuffer(uniforms.slice(),effect.uniforms(w,h,true));
            try(var pass=encoder.createRenderPass(()->"Lyrica Gemini world",target.getColorTextureView(),Optional.empty())) {
                pass.setPipeline(pipeline); pass.setUniform("WorldMusic",uniforms);
                var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.setUniform("SceneSampler",copyView,sampler); pass.setUniform("DepthSampler",target.getDepthTextureView(),sampler);
                pass.draw(3,1,0,0);
            }
            encoder.submit();
            frames++; lastTime=effect.time(); session.rendered(effect.key());
        } catch(RuntimeException failure) { session.renderFailed(effect.key(),"glsl"); }
    }
    public static void close() {
        if(copyView!=null) copyView.close(); if(copy!=null) copy.close(); if(uniforms!=null) uniforms.close();
        if (pipeline != null) pipeline.close();
        copyView=null;copy=null;uniforms=null;pipeline=null;key="";
    }
}
