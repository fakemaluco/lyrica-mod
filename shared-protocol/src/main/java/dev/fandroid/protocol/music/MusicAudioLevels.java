package dev.fandroid.protocol.music;

/** Five normalized loopback meter values, timestamped locally when received. No PCM is retained. */
public record MusicAudioLevels(double bass, double lowMid, double mid, double highMid, double treble,
                               long sampledNanos) {
    public static final MusicAudioLevels SILENT = new MusicAudioLevels(0,0,0,0,0,0);
    public static MusicAudioLevels fromMeter(int bass,int lowMid,int mid,int highMid,int treble,long now) {
        return new MusicAudioLevels(normalize(bass),normalize(lowMid),normalize(mid),normalize(highMid),normalize(treble),now);
    }
    private static double normalize(int value) { return Math.clamp(value,0,10_000)/10_000.0; }
    public boolean fresh(long now) { return sampledNanos!=0 && now-sampledNanos>=0 && now-sampledNanos<=160_000_000L; }
    public double level(int index) {
        return switch(index) { case 0 -> bass; case 1 -> lowMid; case 2 -> mid; case 3 -> highMid; case 4 -> treble; default -> 0; };
    }
}
