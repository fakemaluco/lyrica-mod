package dev.fandroid.client.music;

import dev.fandroid.protocol.music.MusicAudioLevels;

/** Five cheap envelope followers. Capture/FFT work belongs to the existing native audio thread. */
public final class IslandAudioMotion {
    private volatile MusicAudioLevels latest=MusicAudioLevels.SILENT;
    private final double[] displayed=new double[5];

    public void accept(MusicAudioLevels levels) { latest=levels==null?MusicAudioLevels.SILENT:levels; }
    public void step(boolean playing,long now,double delta,boolean animate) {
        MusicAudioLevels levels=latest;
        boolean live=playing && levels.fresh(now);
        double dt=Math.clamp(delta,0,.1);
        double attack=1-Math.exp(-dt/0.025),release=1-Math.exp(-dt/0.065);
        for(int i=0;i<displayed.length;i++) {
            double raw=live?levels.level(i):0;
            double target=Double.isFinite(raw)?Math.clamp(raw,0,1):0;
            if(target<.025) target=0;
            if(!animate) displayed[i]=target;
            else displayed[i]+=(target-displayed[i])*(target>displayed[i]?attack:release);
            if(displayed[i]<.001) displayed[i]=0;
        }
    }
    public double level(int index) { return displayed[index]; }
}
