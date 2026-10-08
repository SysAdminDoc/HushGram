/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The parts of a photo-with-music video that need no encoder: the music's part, the frames and the picture's size and colors. */
@RunWith(RobolectricTestRunner.class)
public class MusicVideoTest {
    private static final long S = 1_000_000L;

    /** The post's part of the track, on a track 60 seconds long. */
    @Test
    public void thePartIsThePostsSnippet() {
        assertArrayEquals("start and length", new long[] { 12 * S, 27 * S }, MusicVideo.window(12_000, 15_000, 60 * S));
        assertArrayEquals("no start", new long[] { 0, 15 * S }, MusicVideo.window(-1, 15_000, 60 * S));
        assertArrayEquals("no length runs to the end", new long[] { 12 * S, 60 * S }, MusicVideo.window(12_000, -1, 60 * S));
        assertArrayEquals("never past the end", new long[] { 50 * S, 60 * S }, MusicVideo.window(50_000, 15_000, 60 * S));
        assertArrayEquals("a start past the end starts at the top", new long[] { 0, 15 * S }, MusicVideo.window(90_000, 15_000, 60 * S));
    }

    /** A track whose length isn't known, and one longer than the cap, never give a part past ten minutes. */
    @Test
    public void thePartIsCapped() {
        assertArrayEquals(new long[] { 5 * S, 5 * S + MusicVideo.MAX_LENGTH_US }, MusicVideo.window(5_000, -1, -1));
        assertArrayEquals(new long[] { 5 * S, 20 * S }, MusicVideo.window(5_000, 15_000, -1));
        assertArrayEquals(new long[] { 0, MusicVideo.MAX_LENGTH_US }, MusicVideo.window(0, -1, 3_600 * S));
    }

    /** About a frame a second, never fewer than two, from 0 and evenly spaced. */
    @Test
    public void framesHoldThePictureForTheSound() {
        long[] snippet = MusicVideo.frameTimes(15 * S);
        assertEquals(15, snippet.length);
        assertEquals(0, snippet[0]);
        assertEquals(S, snippet[1]);
        assertEquals(14 * S, snippet[14]);
        assertArrayEquals(new long[] { 0, S / 4 }, MusicVideo.frameTimes(S / 2));
        assertEquals(600, MusicVideo.frameTimes(MusicVideo.MAX_LENGTH_US).length);
        long[] times = MusicVideo.frameTimes(7_300_000L);
        assertEquals(7, times.length);
        for (int i = 1; i < times.length; i++) assertTrue(times[i] > times[i - 1]);
    }

    /** A bit a pixel each second: a 1024 by 1360 picture asks for about 1.4 Mbps, not the 2.8 that made a 90 second save 37 MB. */
    @Test
    public void theBitRateIsABitAPixel() {
        assertEquals(1024 * 1360, MusicVideo.bitrate(1024, 1360));
        assertEquals(1080 * 1920, MusicVideo.bitrate(1080, 1920));
        assertEquals(4, MusicVideo.bitrate(2, 2));
    }

    /** The video keeps the picture's shape within 1920 by 1080, with even sides. */
    @Test
    public void theVideoSizeFitsAndIsEven() {
        assertArrayEquals(new int[] { 1080, 1350 }, MusicVideo.videoSize(1080, 1350, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
        assertArrayEquals(new int[] { 1080, 1350 }, MusicVideo.videoSize(1440, 1800, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
        assertArrayEquals(new int[] { 640, 480 }, MusicVideo.videoSize(641, 481, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
        assertArrayEquals(new int[] { 1920, 480 }, MusicVideo.videoSize(4000, 1000, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
        assertNull(MusicVideo.videoSize(0, 100, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
        assertNull(MusicVideo.videoSize(1, 1, MusicVideo.MAX_LONG_SIDE, MusicVideo.MAX_SHORT_SIDE));
    }

    /** The picture decodes at the largest power of two down that still covers the video. */
    @Test
    public void thePictureDecodesSmallerOnlyWhileItCovers() {
        assertEquals(1, MusicVideo.sampleSize(1080, 1350, 1080, 1350));
        assertEquals(2, MusicVideo.sampleSize(4000, 3000, 1920, 1440));
        assertEquals(4, MusicVideo.sampleSize(4000, 4000, 1000, 1000));
    }

    /** White, black and a pure red, in video range, with each color sample the average of its four pixels. */
    @Test
    public void colorsAreVideoRange() {
        assertEquals(235, MusicVideo.luma(255, 255, 255));
        assertEquals(16, MusicVideo.luma(0, 0, 0));
        assertEquals(128, MusicVideo.blue(255, 255, 255));
        assertEquals(128, MusicVideo.red(0, 0, 0));
        assertEquals(240, MusicVideo.red(255, 0, 0));

        int white = 0xFFFFFFFF;
        int black = 0xFF000000;
        MusicVideo.Planes planes = MusicVideo.planes(new int[] { white, white, black, black, white, white, black, black }, 4, 2);
        assertEquals(8, planes.luma.length);
        assertEquals(2, planes.blue.length);
        assertEquals(235, planes.luma[0] & 0xFF);
        assertEquals(16, planes.luma[2] & 0xFF);
        assertEquals(128, planes.blue[0] & 0xFF);
        assertEquals(128, planes.red[1] & 0xFF);
    }

    /** The picture is the largest on Meta's servers; any other address is passed over. */
    @Test
    public void thePictureComesFromMeta() {
        MediaSave.Rendition small = new MediaSave.Rendition("https://scontent.cdninstagram.com/v/t51.2885-15/150.jpg", 150, 150, 0);
        MediaSave.Rendition large = new MediaSave.Rendition("https://scontent.cdninstagram.com/v/t51.2885-15/1080.jpg", 1080, 1350, 0);
        MediaSave.Rendition elsewhere = new MediaSave.Rendition("https://example.com/4000.jpg", 4000, 5000, 0);
        assertEquals(large.url, MusicVideo.picture(List.of(small, elsewhere, large)).url);
        assertNull(MusicVideo.picture(List.of(elsewhere)));
        assertNull(MusicVideo.picture(List.of()));
    }

    /** The music's description, which can end up in a diagnostic line, never holds the track's address. */
    @Test
    public void theMusicNeverPrintsItsAddress() {
        String text = new MusicVideo.Music("https://scontent.cdninstagram.com/o1/track.mp4?secret=1", 12_000, 15_000).toString();
        assertFalse(text, text.contains("scontent"));
        assertTrue(text, text.contains("an address"));
        assertTrue(new MusicVideo.Music(null, -1, -1).toString().contains("no address"));
    }

    /** Without the bridges written, a post has no music, and reading it doesn't throw. */
    @Test
    public void anUnpatchedPostHasNoMusic() {
        assertNull(MusicVideo.music(new Object()));
    }
}
