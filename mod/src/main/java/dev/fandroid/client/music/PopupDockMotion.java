package dev.fandroid.client.music;

/** A gentler version of the existing control spring; retargeting preserves position and velocity. */
public final class PopupDockMotion {
    public record Frame(PopupDockLayout.Rect bounds,PopupDockLayout.Rect contents,PopupDockLayout.Rect track,PopupDockLayout.Rect grip,
                        double folded,double hover,boolean minimized,boolean horizontal,double audioPresence,double titlePresence) { }
    private final SpringValue fold=new SpringValue(0,4.5,.84),hover=new SpringValue(0,4.5,.72),audio=new SpringValue(1,4.5,.84);
    private PopupDockLayout.Anchor anchor=new PopupDockLayout.Anchor(PopupDockLayout.Edge.TOP,.5);
    public static final long TITLE_HOLD_NANOS=5_000_000_000L;
    private final java.util.function.LongSupplier clock;
    // Same damping as the liquid-glass controls, with a slower time scale for the shell.
    private final SpringValue title=new SpringValue(1,4.5,.72);
    private boolean wasPlaying;
    private long titleUntil;
    public PopupDockMotion(){this(System::nanoTime);}
    public PopupDockMotion(java.util.function.LongSupplier clock){this.clock=clock;revealTitle();}
    public void revealTitle(){titleUntil=clock.getAsLong()+TITLE_HOLD_NANOS;title.setGoal(1);}
    private boolean minimized;
    private PopupDockLayout.Rect lastTarget,dismissedArea;
    public void initialize(boolean minimized,PopupDockLayout.Edge edge,double position) { initialize(minimized,edge,position,true); }
    public void initialize(boolean minimized,PopupDockLayout.Edge edge,double position,boolean playing) {
        audio.snap(playing?1:0); title.snap(1); revealTitle(); wasPlaying=playing;
        this.minimized=minimized; anchor=new PopupDockLayout.Anchor(PopupDockLayout.Edge.TOP,.5); fold.snap(minimized?1:0); hover.snap(0); dismissedArea=null;
    }
    public PopupDockLayout.Anchor minimize(PopupDockLayout.Rect full,int width,int height) {
        return minimize(full,width,height,1);
    }
    public PopupDockLayout.Anchor minimize(PopupDockLayout.Rect full,int width,int height,double scale) {
        tuneReturn(full,width,height,scale);
        anchor=new PopupDockLayout.Anchor(PopupDockLayout.Edge.TOP,.5); minimized=true; dismissedArea=null; revealTitle(); return anchor;
    }
    public void restore() { minimized=false; dismissedArea=null; }
    public void restore(PopupDockLayout.Rect full,int width,int height,double scale) {
        tuneReturn(full,width,height,scale);restore();
    }
    private void tuneReturn(PopupDockLayout.Rect full,int width,int height,double scale) {
        var island=PopupDockLayout.returnTarget(width,height,scale);
        double distance=Math.hypot(full.centerX()-island.centerX(),full.centerY()-island.centerY())/Math.max(1,Math.hypot(width,height));
        double travel=Math.clamp((distance-.04)/.46,0,1);travel=travel*travel*(3-2*travel);
        // Existing glass damping endpoints: short trips settle gently; long trips carry more energy.
        fold.retune(4.5,.84-(.84-.56)*travel);
    }
    /** Keep the close button's pointer from immediately reopening the island. */
    public void collapseDetails() { dismissedArea=lastTarget; hover.setGoal(0); }
    public boolean active() { return minimized || fold.position()>.001 || !fold.isAtRest(); }
    public Frame step(PopupDockLayout.Rect full,int width,int height,double scale,double px,double py,
                      boolean interactive,boolean held,double delta,boolean animate) {
        return step(full,width,height,scale,px,py,interactive,held,delta,animate,true);
    }
    public Frame step(PopupDockLayout.Rect full,int width,int height,double scale,double px,double py,
                      boolean interactive,boolean held,double delta,boolean animate,boolean playing) {
        var target=PopupDockLayout.capsule(anchor,width,height,scale,Math.clamp(hover.position(),-.06,1.08),Math.clamp(audio.position(),0,1),title.position());
        if(dismissedArea!=null && interactive && !dismissedArea.near(px,py,6*scale)) dismissedArea=null;
        boolean over=minimized && interactive && dismissedArea==null && (held || target.near(px,py,6*scale));
        if(over || playing || wasPlaying && !playing) revealTitle();
        wasPlaying=playing;
        boolean showTitle=!minimized || playing || over || clock.getAsLong()<titleUntil;
        title.setGoal(showTitle?1:0);
        fold.setGoal(minimized?1:0); hover.setGoal(over?1:0); audio.setGoal(playing?1:0);
        if(animate) { fold.step(delta*.85); hover.step(delta*.85); audio.step(delta*.85); title.step(delta*.55); }
        else { fold.snap(minimized?1:0); hover.snap(over?1:0); audio.snap(playing?1:0); title.snap(showTitle?1:0); }
        double f=Math.clamp(fold.position(),0,1),h=Math.clamp(hover.position(),-.06,1.08);
        target=PopupDockLayout.capsule(anchor,width,height,scale,h,Math.clamp(audio.position(),0,1),title.position());
        lastTarget=target;
        var bounds=PopupDockLayout.clamped(PopupDockLayout.interpolate(full,target,f),width,height);
        double raw=fold.position();
        if(raw>1){
            double impact=Math.min(.14,raw-1),w=bounds.width()*(1+.9*impact),h2=bounds.height()*(1-1.6*impact);
            bounds=PopupDockLayout.clamped(new PopupDockLayout.Rect(bounds.centerX()-w/2,bounds.y(),w,h2),width,height);
        }else if(raw<0){
            double impact=Math.min(.14,-raw),w=bounds.width()*(1+.28*impact),h2=bounds.height()*(1+.28*impact);
            bounds=PopupDockLayout.clamped(new PopupDockLayout.Rect(bounds.centerX()-w/2,bounds.centerY()-h2/2,w,h2),width,height);
        }
        var contents=PopupDockLayout.contents(bounds,scale);
        return new Frame(bounds,contents,PopupDockLayout.track(contents,scale),PopupDockLayout.restore(contents,scale),f,Math.max(0,h),minimized,true,Math.clamp(audio.position(),0,1),Math.clamp(title.position(),0,1));
    }
}
