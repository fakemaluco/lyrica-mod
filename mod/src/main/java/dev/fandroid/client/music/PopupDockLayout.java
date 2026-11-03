package dev.fandroid.client.music;

/** Compact music island and expanded player geometry, independent of Minecraft rendering. */
public final class PopupDockLayout {
    public static final double PRESENTATION_SCALE=.85;
    public static final double IDLE_WIDTH=220, IDLE_HEIGHT=32, MEDIA_WIDTH=96, EXPANDED_WIDTH=344, EXPANDED_HEIGHT=116;
    public enum Edge { LEFT, RIGHT, TOP, BOTTOM }
    public record Rect(double x,double y,double width,double height) {
        public double right() { return x+width; }
        public double bottom() { return y+height; }
        public double centerX() { return x+width/2; }
        public double centerY() { return y+height/2; }
        public boolean contains(double px,double py) { return px>=x && px<right() && py>=y && py<bottom(); }
        public boolean near(double px,double py,double gap) { return px>=x-gap && px<right()+gap && py>=y-gap && py<bottom()+gap; }
    }
    public record Anchor(Edge edge,double position) { }
    public static Anchor nearest(Rect full,int width,int height) {
        double[] gaps={Math.max(0,full.x()),Math.max(0,width-full.right()),Math.max(0,full.y()),Math.max(0,height-full.bottom())};
        int best=0; for(int i=1;i<4;i++) if(gaps[i]<gaps[best]) best=i;
        return new Anchor(Edge.values()[best],Math.clamp(best<2?full.centerY()/Math.max(1,height):full.centerX()/Math.max(1,width),0,1));
    }
    public static boolean horizontal(Anchor anchor) { return anchor.edge()==Edge.TOP || anchor.edge()==Edge.BOTTOM; }
    public static Rect capsule(Anchor anchor,int width,int height,double scale,double hover) {
        return capsule(anchor,width,height,scale,hover,1);
    }
    public static Rect capsule(Anchor anchor,int width,int height,double scale,double hover,double audioPresence) {
        return capsule(anchor,width,height,scale,hover,audioPresence,1);
    }
    public static Rect capsule(Anchor anchor,int width,int height,double scale,double hover,double audioPresence,double titlePresence) {
        double namedWidth=IDLE_WIDTH-26*(1-Math.clamp(audioPresence,0,1));
        double idleWidth=MEDIA_WIDTH+(namedWidth-MEDIA_WIDTH)*Math.clamp(titlePresence,-.06,1.06);
        double margin=Math.max(1,6*scale), t=Math.clamp(hover,-.06,1.08);
        boolean horizontal=horizontal(anchor);
        double w=Math.min(Math.max(1,width-2*margin),(horizontal?idleWidth+(EXPANDED_WIDTH-idleWidth)*t:32+84*t)*scale);
        double h=Math.min(Math.max(1,height-2*margin),(horizontal?IDLE_HEIGHT+(EXPANDED_HEIGHT-IDLE_HEIGHT)*t:220+124*t)*scale);
        double position=Double.isFinite(anchor.position())?Math.clamp(anchor.position(),0,1):.5;
        double x,y;
        switch(anchor.edge()) {
            case LEFT -> { x=margin; y=position*height-h/2; }
            case RIGHT -> { x=width-margin-w; y=position*height-h/2; }
            case TOP -> { x=position*width-w/2; y=margin; }
            default -> { x=position*width-w/2; y=height-margin-h; }
        }
        return clamped(new Rect(x,y,w,h),width,height);
    }
    /** The current island lives at the top center. The same region works before and after folding. */
    public static Rect returnTarget(int width,int height,double scale) {
        return capsule(new Anchor(Edge.TOP,.5),width,height,scale,0);
    }
    /** Pointer capture stays with one drag; a wider exit zone prevents boundary flicker. */
    public static boolean dragAttachment(boolean attached,double pointerX,double pointerY,int width,int height,double scale) {
        if(!Double.isFinite(pointerX) || !Double.isFinite(pointerY) || width<=0 || height<=0) return false;
        return returnTarget(width,height,scale).near(pointerX,pointerY,(attached?36:18)*scale);
    }
    public static Rect clamped(Rect box,int width,int height) {
        double w=Math.clamp(box.width(),1,Math.max(1,width)),h=Math.clamp(box.height(),1,Math.max(1,height));
        return new Rect(Math.clamp(box.x(),0,Math.max(0,width-w)),Math.clamp(box.y(),0,Math.max(0,height-h)),w,h);
    }
    public static Rect interpolate(Rect a,Rect b,double progress) {
        double t=Math.clamp(progress,0,1);
        return new Rect(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.width()+(b.width()-a.width())*t,a.height()+(b.height()-a.height())*t);
    }
    public static Rect contents(Rect shell,double scale) {
        return new Rect(shell.centerX()-EXPANDED_WIDTH*scale/2,shell.y(),EXPANDED_WIDTH*scale,EXPANDED_HEIGHT*scale);
    }
    public static Rect artwork(Rect content,double scale) {
        return new Rect(content.x(),content.y(),108*scale,content.height());
    }
    public static Rect track(Rect content,double scale) {
        return new Rect(content.x()+124*scale,content.y()+57*scale,204*scale,Math.max(1,3*scale));
    }
    public static Rect close(Rect content,double scale) {
        return new Rect(content.right()-28*scale,content.y()+7*scale,18*scale,18*scale);
    }
    public static Rect restore(Rect content,double scale) {
        Rect close=close(content,scale);
        return new Rect(close.x()-24*scale,close.y(),close.width(),close.height());
    }
    public static Rect settings(Rect content,double scale) {
        Rect restore=restore(content,scale);
        return new Rect(restore.x()-24*scale,restore.y(),restore.width(),restore.height());
    }
    public static long seek(Rect track,double coordinate,long duration) {
        double start=track.width()>=track.height()?track.x():track.y();
        double length=track.width()>=track.height()?track.width():track.height();
        return duration<=0?0:Math.round(Math.clamp((coordinate-start)/Math.max(1,length),0,1)*duration);
    }
    private static Rect button(Rect content,double scale,int index) {
        double size=26*scale,gap=12*scale,center=track(content,scale).centerX();
        return new Rect(center-size/2+(index-1)*(size+gap),content.y()+78*scale,size,size);
    }
    public static Rect previous(Rect capsule,double scale) { return button(capsule,scale,0); }
    public static Rect play(Rect capsule,double scale) { return button(capsule,scale,1); }
    public static Rect next(Rect capsule,double scale) { return button(capsule,scale,2); }
    private PopupDockLayout() { }
}
