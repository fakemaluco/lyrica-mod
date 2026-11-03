package dev.lyrica.engine;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.junit.Assert.*;

public class VideoLoopTest {
    @Test public void hlsMasterChoosesAtMost720InsteadOf4kAndKeepsMediaPlaylists() {
        var master = java.net.URI.create("https://example.com/album/master.m3u8");
        assertEquals("https://example.com/album/small.m3u8", VideoLoop.chooseHlsVariant(master, """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160
                huge.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=512x512
                tiny.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=720x720
                small.m3u8
                """));
        assertEquals(master.toString(), VideoLoop.chooseHlsVariant(master, "#EXTM3U\n#EXTINF:2\npart.ts"));
        assertEquals("https://example.com/album/tiny.m3u8", VideoLoop.chooseHlsVariant(master, """
                #EXT-X-STREAM-INF:RESOLUTION=1920x1080
                huge.m3u8
                #EXT-X-STREAM-INF:RESOLUTION=960x540
                tiny.m3u8
                """));
    }
    @Test public void realMp4FramesLoopPauseHideAndReplaceWithoutExternalDecoder() throws Exception {
        Path fixture = Path.of("..", "build", "motion-video-fixture.mp4").toAbsolutePath().normalize();
        Files.createDirectories(fixture.getParent());
        makeFixture(fixture);
        try (VideoLoop loop = new VideoLoop(true)) {
            loop.setSource(fixture.toUri().toString());
            Thread.sleep(150);
            assertNull("A hidden video must not open or decode", loop.latest());
            awaitFrames(loop, 2);
            VideoLoop.PixelFrame first = loop.latest();
            assertEquals(160, first.width());
            assertEquals(96, first.height());
            assertEquals(160 * 96, first.argb().length);
            awaitFrames(loop, 14); // fixture has twelve frames: the decoder must cross EOF and loop.
            loop.setPlaying(false, true);
            Thread.sleep(100);
            long paused = loop.latest().sequence();
            for (int i = 0; i < 5; i++) { loop.touchVisible(); Thread.sleep(50); }
            assertEquals("Pause must hold the decoded frame", paused, loop.latest().sequence());
            // Hidden views release native decoding after three seconds. Reopening while
            // paused must preserve that held frame instead of jumping to the loop's start.
            Thread.sleep(4_200);
            for (int i = 0; i < 6; i++) { loop.touchVisible(); Thread.sleep(50); }
            assertEquals("A paused frame survives hiding and reopening the view", paused, loop.latest().sequence());
            loop.setPlaying(true, true);
            awaitFrames(loop, paused + 2);
            loop.setSource(null);
            assertNull("A song change clears the previous frame immediately", loop.latest());
            Thread.sleep(100);
            assertNull(loop.latest());
        }
    }

    @Test public void visibleAutoplayResumesPausedVideoAndOrdinaryRenderingRevokesIt() throws Exception {
        Path fixture=Path.of("..","build","motion-autoplay-fixture.mp4").toAbsolutePath().normalize();
        Files.createDirectories(fixture.getParent());makeFixture(fixture);
        try(var loop=new VideoLoop(true)){
            loop.setPlaying(false,true);loop.setSource(fixture.toUri().toString());awaitFrames(loop,1);
            long first=loop.latest().sequence();
            for(int i=0;i<12;i++){loop.setPlaying(false,true);loop.touchVisible(true);Thread.sleep(50);}
            assertTrue("Autoplay must win over repeated paused music updates",loop.latest().sequence()>first+2);
            loop.touchVisible(false);Thread.sleep(150);long paused=loop.latest().sequence();
            for(int i=0;i<5;i++){loop.touchVisible(false);Thread.sleep(50);}
            assertEquals("Ordinary rendering must restore pause",paused,loop.latest().sequence());
            for(int i=0;i<8;i++){loop.touchVisible(true);Thread.sleep(50);}
            assertTrue("A later collapsed view must resume the same stream",loop.latest().sequence()>paused+1);
            Thread.sleep(1100);long hidden=loop.latest().sequence();Thread.sleep(200);
            assertEquals("Autoplay must expire when no view is visible",hidden,loop.latest().sequence());
        }
    }
    @Test public void sourceProtocolsAreRestrictedOutsideOptInFixtures() {
        assertFalse(VideoLoop.validSource("file:///C:/private/file.mp4", false));
        assertFalse(VideoLoop.validSource("http://example.com/a.mp4", false));
        assertFalse(VideoLoop.validSource("concat:foo|bar", false));
        assertTrue(VideoLoop.validSource("https://video.scdn.co/a.mp4", false));
        assertTrue(VideoLoop.validSource("file:///tmp/fixture.mp4", true));
    }

    private static void awaitFrames(VideoLoop loop, long target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            loop.touchVisible();
            if (loop.latest() != null && loop.latest().sequence() >= target) return;
            if (loop.failed()) throw new AssertionError(loop.status(), loop.failure());
            Thread.sleep(20);
        }
        fail("Timed out waiting for video frames: " + loop.status());
    }

    static void makeFixture(Path path) throws Exception {
        makeFixture(path, 160, 96);
    }
    @Test public void portraitCanvasPreservesItsDecodedAspectRatio() throws Exception {
        Path portrait = Path.of("..", "build", "motion-portrait-fixture.mp4").toAbsolutePath().normalize();
        Files.createDirectories(portrait.getParent()); makeFixture(portrait, 96, 160);
        try (var loop = new VideoLoop(true)) {
            loop.setSource(portrait.toUri().toString()); awaitFrames(loop, 2);
            assertEquals(96, loop.latest().width()); assertEquals(160, loop.latest().height());
        }
    }
    static void makeFixture(Path path, int width, int height) throws Exception {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toFile(), width, height)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(12);
            recorder.start();
            try (Frame image = new Frame(width, height, Frame.DEPTH_UBYTE, 3)) {
                ByteBuffer pixels = (ByteBuffer) image.image[0];
                for (int index = 0; index < 12; index++) {
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int at = y * image.imageStride + x * 3;
                        pixels.put(at, (byte) (x * 255 / width));
                        pixels.put(at + 1, (byte) (index * 20));
                        pixels.put(at + 2, (byte) (y * 255 / height));
                    }
                    recorder.record(image, AV_PIX_FMT_BGR24);
                }
            }
        }
    }
}
