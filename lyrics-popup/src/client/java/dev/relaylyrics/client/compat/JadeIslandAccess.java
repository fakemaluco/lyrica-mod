package dev.relaylyrics.client.compat;

import dev.relaylyrics.FanDroidMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Optional bridge to Jade's public before-render callback. No Jade classes are linked without Jade. */
public final class JadeIslandAccess {
    @FunctionalInterface public interface SizeReader { double[] read(Object animation) throws ReflectiveOperationException; }
    public record Content(Object root,Object animation,Object accessor,double width,double height,double scale,long receivedNanos) { }
    private static final String CALLBACK="snownee.jade.api.callback.JadeBeforeRenderCallback";
    private Content latest;
    private Field shown,legacyAlpha;
    private boolean installed,failed;
    private long captured,drawn,attempts;
    private String lastDecision="waiting for Jade";
    private final ClassValue<RootAccess> roots=new ClassValue<>() {
        @Override protected RootAccess computeValue(Class<?> type) { return new RootAccess(type); }
    };
    private final ClassValue<StyleAccess> styles=new ClassValue<>() {
        @Override protected StyleAccess computeValue(Class<?> type) { return new StyleAccess(type); }
    };
    public void install(Object instance,BooleanSupplier eligible,SizeReader sizeReader) {
        if(failed) return;
        latest=null;
        try {
            Class<?> callback=Class.forName(CALLBACK);
            Class<?> registration=Class.forName("snownee.jade.api.IWailaClientRegistration");
            Class<?> overlay=Class.forName("snownee.jade.overlay.OverlayRenderer");
            shown=overlay.getField("shown");
            try { legacyAlpha=overlay.getField("alpha"); } catch(NoSuchFieldException modern) { legacyAlpha=null; }
            Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(self,method,args)->{
                if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {
                    case "toString" -> "Lyrica Jade island callback";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self==args[0];
                    default -> null;
                };
                attempts++;
                if(failed || !eligible.getAsBoolean()) {lastDecision="not eligible";return false;}
                try {
                    double[] size=sizeReader.read(args[1]);
                    RootAccess access=roots.get(args[0].getClass());
                    Object style=access.style.invoke(args[0]);
                    if(!styles.get(style.getClass()).supported || size[0]<=0 || size[1]<=0) {lastDecision="unsupported style or size: "+style.getClass().getName()+" "+java.util.Arrays.toString(size);return false;}
                    latest=new Content(args[0],args[1],args[3],size[0],size[1],size[2],System.nanoTime());
                    captured++;lastDecision="captured";
                    // Both supported Jade renderers return immediately when this callback returns true.
                    return true;
                } catch(ReflectiveOperationException | RuntimeException error) { fail(error);return false; }
            });
            registration.getMethod("addBeforeRenderCallback",int.class,callback).invoke(instance,2000,proxy);
            installed=true;
            FanDroidMod.LOGGER.info("Jade island callback registered");
        } catch(ReflectiveOperationException | LinkageError error) { fail(error); }
    }
    public Content content() { return failed?null:latest; }
    public void clear() { latest=null; }
    public boolean ready() { return installed && !failed; }
    public long capturedFrames() { return captured; }
    public long drawnFrames() { return drawn; }
    public String diagnostics() { return "attempts="+attempts+", decision="+lastDecision; }
    public void fail(Throwable error) {
        if(!failed) FanDroidMod.LOGGER.warn("Jade island unavailable; keeping Jade's normal overlay",error);
        failed=true;latest=null;
    }
    /** Temporarily remove only the root box material; nested providers keep their complete styling. */
    public void render(Content content,double opacity,Runnable nativeDraw) throws ReflectiveOperationException {
        RootAccess root=roots.get(content.root().getClass());
        Object style=root.style.invoke(content.root());
        StyleAccess material=styles.get(style.getClass());
        Field alpha=legacyAlpha!=null?legacyAlpha:content.animation().getClass().getField("alpha");
        Object owner=legacyAlpha!=null?null:content.animation();
        float oldAlpha=alpha.getFloat(owner);
        Object[] previous=material.clear(style);
        try {
            alpha.setFloat(owner,(float)Math.clamp(opacity,0,1));
            if(root.before!=null) root.before.invoke(content.root(),content.accessor(),0f);
            if(root.widgetAlpha!=null) root.widgetAlpha.invoke(content.root(),(float)opacity);
            nativeDraw.run();shown.setBoolean(null,true);drawn++;
        } finally {
            try {
                alpha.setFloat(owner,oldAlpha);
                if(root.widgetAlpha!=null) root.widgetAlpha.invoke(content.root(),oldAlpha);
            } finally { material.restore(style,previous); }
        }
    }
    public void renderLegacy(Content content,Object graphics) {
        try { roots.get(content.root().getClass()).legacyRender.invoke(content.root(),graphics,0f,0f,(float)content.width(),(float)content.height()); }
        catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static final class RootAccess {
        final Method style,before,widgetAlpha,legacyRender;
        RootAccess(Class<?> type) {
            try {
                style=type.getMethod("getStyle");
                Method b=null,w=null,r=null;
                for(Method method:type.getMethods()) {
                    if(method.getName().equals("beforeRender") && method.getParameterCount()==2) b=method;
                    if(method.getName().equals("setWidgetAlpha") && method.getParameterCount()==1) w=method;
                    if(method.getName().equals("render") && method.getParameterCount()==5 && method.getParameterTypes()[1]==float.class) r=method;
                }
                before=b;widgetAlpha=w;legacyRender=r;
            } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
    private static final class StyleAccess {
        final List<Field> fields=new ArrayList<>();
        final boolean supported;
        StyleAccess(Class<?> type) {
            for(String name:new String[]{"bgColor","borderColor","borderWidth","roundCorner","sprite","withIconSprite","tooltip"}) {
                try { fields.add(type.getField(name)); } catch(NoSuchFieldException absent) { }
            }
            supported=fields.stream().anyMatch(f->f.getName().equals("bgColor") || f.getName().equals("tooltip"));
        }
        Object[] clear(Object style) throws IllegalAccessException {
            Object[] old=new Object[fields.size()];
            for(int i=0;i<fields.size();i++) {
                Field field=fields.get(i);old[i]=field.get(style);
                switch(field.getName()) {
                    case "bgColor" -> field.setInt(style,-1);
                    case "borderColor" -> field.set(style,new int[]{-1,-1,-1,-1});
                    case "borderWidth" -> { if(field.getType()==float.class) field.setFloat(style,0);else field.setInt(style,0); }
                    case "roundCorner","tooltip" -> field.set(style,false);
                    default -> field.set(style,null);
                }
            }
            return old;
        }
        void restore(Object style,Object[] previous) throws IllegalAccessException {
            for(int i=0;i<fields.size();i++) fields.get(i).set(style,previous[i]);
        }
    }
}
