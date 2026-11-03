package dev.fandroid.client.music;

import dev.fandroid.client.RelayLyricsClient;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;

/** Real world post-processing, after level rendering and before hand/HUD rendering. */
public final class WorldGeminiRenderer {
    private static GeminiShaderSession probe;
    private static String key = "";
    private static int program, vao, uniform, sourceTexture, sourceFbo, outputFbo, width, height;
    private static long frames;
    private static float lastTime;
    public static void probeSession(GeminiShaderSession value) {
        if (!Boolean.getBoolean("lyrica.geminiWorldSelfTest")) throw new IllegalStateException("Probe is opt-in");
        probe = value;
    }
    private static GeminiShaderSession session() { return probe != null ? probe : RelayLyricsClient.music() == null ? null : RelayLyricsClient.music().geminiShaders(); }
    public static long frames() { return frames; }
    public static float lastTime() { return lastTime; }
    public static void render() {
        var client = MinecraftClient.getInstance();
        var session = session(); var effect = session == null ? null : session.worldEffect();
        if (client.world == null || effect == null || effect.strength() <= 0) { close(); return; }
        var target = client.getFramebuffer();
        if (target.getColorAttachment() < 0 || target.getDepthAttachment() < 0) return;
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); int old0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1); int old1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldUniform = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING), oldBase = GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 0);
        IntBuffer viewport = BufferUtils.createIntBuffer(4); GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        ByteBuffer mask = BufferUtils.createByteBuffer(4); GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
        try {
            if (!key.equals(effect.key())) {
                int compiled = compile(effect.shader());
                if (program != 0) GL20.glDeleteProgram(program);
                program = compiled; key = effect.key();
            }
            if (vao == 0) {
                vao = GL30.glGenVertexArrays(); uniform = GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniform); GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 64L, GL15.GL_STREAM_DRAW);
                sourceTexture = GL11.glGenTextures(); sourceFbo = GL30.glGenFramebuffers(); outputFbo = GL30.glGenFramebuffers();
            }
            int w = target.textureWidth, h = target.textureHeight;
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            if (width != w || height != h) {
                width = w; height = h;
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sourceTexture, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.fbo);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0,0,w,h,0,0,w,h,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, outputFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.getColorAttachment(), 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("GPU");
            GL11.glViewport(0,0,w,h); GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDepthMask(false); GL11.glColorMask(true,true,true,true);
            GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniform); GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, effect.uniforms(w,h,false));
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, uniform);
            GL31.glUniformBlockBinding(program, GL31.glGetUniformBlockIndex(program,"WorldMusic"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"SceneSampler"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"DepthSampler"), 1);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getDepthAttachment());
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3); frames++; lastTime = effect.time(); session.rendered(effect.key());
        } catch (RuntimeException failure) { session.renderFailed(effect.key(), "glsl"); }
        finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);
            GL20.glUseProgram(oldProgram); GL30.glBindVertexArray(oldVao);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER,0,oldBase); GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,oldUniform);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D,old0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D,old1); GL13.glActiveTexture(oldActive);
            enable(GL11.GL_BLEND,blend); enable(GL11.GL_DEPTH_TEST,depth); enable(GL11.GL_CULL_FACE,cull);
            enable(GL11.GL_SCISSOR_TEST,scissor); enable(GL11.GL_STENCIL_TEST,stencil); GL11.glDepthMask(depthMask);
            GL11.glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);
            GL11.glViewport(viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3));
        }
    }
    private static void enable(int cap, boolean value) { if(value) GL11.glEnable(cap); else GL11.glDisable(cap); }
    private static int shader(int type, String code) {
        int id = GL20.glCreateShader(type); GL20.glShaderSource(id,code); GL20.glCompileShader(id);
        if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0) { GL20.glDeleteShader(id); throw new IllegalArgumentException("GLSL"); } return id;
    }
    private static int compile(GeminiShaderSpec spec) {
        int v=0,f=0,p=0;
        try {
            v=shader(GL20.GL_VERTEX_SHADER,GeminiShaderSpec.vertexSource(false)); f=shader(GL20.GL_FRAGMENT_SHADER,GeminiShaderSpec.fragmentSource(spec.glsl()));
            p=GL20.glCreateProgram(); GL20.glAttachShader(p,v); GL20.glAttachShader(p,f); GL20.glLinkProgram(p);
            if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0) throw new IllegalArgumentException("GLSL"); return p;
        } catch(RuntimeException e) { if(p!=0) GL20.glDeleteProgram(p); throw e; }
        finally { if(v!=0) GL20.glDeleteShader(v); if(f!=0) GL20.glDeleteShader(f); }
    }
    public static void close() {
        if(program!=0) GL20.glDeleteProgram(program); if(vao!=0) GL30.glDeleteVertexArrays(vao);
        if(uniform!=0) GL15.glDeleteBuffers(uniform); if(sourceTexture!=0) GL11.glDeleteTextures(sourceTexture);
        if(sourceFbo!=0) GL30.glDeleteFramebuffers(sourceFbo); if(outputFbo!=0) GL30.glDeleteFramebuffers(outputFbo);
        program=vao=uniform=sourceTexture=sourceFbo=outputFbo=width=height=0; key="";
    }
}
