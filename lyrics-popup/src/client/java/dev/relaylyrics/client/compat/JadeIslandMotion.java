package dev.relaylyrics.client.compat;

import dev.relaylyrics.client.music.PopupDockLayout.Rect;
import dev.relaylyrics.client.music.SpringValue;

/** Small, interruptible geometry springs shared with the music island. */
public final class JadeIslandMotion {
    public record Frame(Rect bounds,double opacity) { }
    private final SpringValue show=new SpringValue(0,4.5,.84),width=new SpringValue(1,4.5,.84),height=new SpringValue(1,4.5,.84),top=new SpringValue(0,4.5,.84);
    private boolean initialized;
    public Frame step(boolean visible,double w,double h,double y,double center,double delta,boolean animate) {
        w=Math.max(1,w);h=Math.max(1,h);
        if(!initialized) { width.snap(w*.94);height.snap(h*.94);top.snap(y-6);initialized=true; }
        show.setGoal(visible?1:0);width.setGoal(w*(visible?1:.94));height.setGoal(h*(visible?1:.94));top.setGoal(y-(visible?0:6));
        if(animate) { show.step(delta*.85);width.step(delta*.85);height.step(delta*.85);top.step(delta*.85); }
        else { show.snap(visible?1:0);width.snap(w);height.snap(h);top.snap(y); }
        double cw=Math.max(1,width.position()),ch=Math.max(1,height.position());
        return new Frame(new Rect(center-cw/2,top.position(),cw,ch),Math.clamp(show.position(),0,1));
    }
}
