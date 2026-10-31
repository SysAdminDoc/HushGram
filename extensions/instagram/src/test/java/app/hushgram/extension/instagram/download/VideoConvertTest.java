/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What a conversion to H.264 takes on and what it asks of the encoder (#236). The conversion itself
 * needs a phone's codecs, which Robolectric doesn't have.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoConvertTest {
    private static final String AV1 = "av01.0.08m.08.0.111.01.01.01.0";

    private static MediaFormat picture(String type, int width, int height, long durationUs) {
        MediaFormat format = MediaFormat.createVideoFormat(type, width, height);
        if (durationUs >= 0) format.setLong(MediaFormat.KEY_DURATION, durationUs);
        return format;
    }

    @Test
    public void onlyVp9AndAv1AreConverted() {
        assertTrue(VideoConvert.needsConversion("vp09.00.40.08"));
        assertTrue(VideoConvert.needsConversion(AV1));
        assertFalse(VideoConvert.needsConversion("avc1.64001f"));
        assertFalse(VideoConvert.needsConversion("hvc1.1.6.l93.90"));
    }

    /** Without a decoder and an H.264 encoder that take the picture's size, nothing is offered for conversion. */
    @Test
    public void withoutCodecsNothingCanBeConverted() {
        VideoConvert.convertibleForTests = null;
        DashManifest.Track av1 = new DashManifest.Track("video/mp4", AV1, 1080, 1920, 1_525_000, "https://x/v.mp4");
        assertFalse(VideoConvert.canConvert(av1));
        DashManifest.Track h264 = new DashManifest.Track("video/mp4", "avc1.64001f", 720, 1280, 1_200_000, "https://x/h.mp4");
        DashManifest.Track sizeless = new DashManifest.Track("video/mp4", AV1, 0, 0, 1_525_000, "https://x/s.mp4");
        DashManifest.Track sound = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 64_000, "https://x/a.mp4");
        assertFalse(VideoConvert.canConvert(h264));
        assertFalse(VideoConvert.canConvert(sizeless));
        assertFalse(VideoConvert.canConvert(sound));
    }

    @Test
    public void shortSdrPicturesWithASizeAreConverted() {
        assertNull(VideoConvert.refusal(picture(MediaFormat.MIMETYPE_VIDEO_VP9, 1080, 1920, 30_000_000L)));
        assertNull("a picture that doesn't give its length is held to it as it goes",
                VideoConvert.refusal(picture("video/av01", 1080, 1920, -1)));
        assertNull(VideoConvert.refusal(picture("video/av01", 1080, 1920, VideoConvert.MAX_LENGTH_US)));
    }

    @Test
    public void longHdrAndSizelessPicturesAreRefused() {
        assertEquals("the file holds no picture", VideoConvert.refusal(null));
        assertEquals("the picture has no size", VideoConvert.refusal(picture("video/av01", 0, 1920, 1_000_000L)));
        assertEquals("the picture runs 181 s, longer than the 180 s a conversion takes",
                VideoConvert.refusal(picture("video/av01", 1080, 1920, 181_000_000L)));

        MediaFormat hdr = picture(MediaFormat.MIMETYPE_VIDEO_VP9, 1080, 1920, 30_000_000L);
        hdr.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
        assertEquals("the picture is HDR", VideoConvert.refusal(hdr));
        hdr.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
        assertEquals("the picture is HDR", VideoConvert.refusal(hdr));
        hdr.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        assertNull(VideoConvert.refusal(hdr));
    }

    /** An HDR picture is known from its codecs before it's fetched, so the save keeps the H.264 instead. */
    @Test
    public void hdrIsReadFromTheCodecs() {
        assertTrue("AV1 PQ", VideoConvert.hdr("av01.0.09M.10.0.110.09.16.09.0"));
        assertTrue("VP9 PQ", VideoConvert.hdr("vp09.02.10.10.01.09.16.09.01"));
        assertTrue("VP9 HLG", VideoConvert.hdr("vp09.02.10.10.01.09.18.09.01"));
        assertFalse("AV1 SDR", VideoConvert.hdr(AV1));
        assertFalse("VP9 without its color fields", VideoConvert.hdr("vp09.00.40.08"));
        // Meta codes 10-bit only for HDR, so a short codecs string with the depth alone says it too (#352).
        assertTrue("AV1 10-bit", VideoConvert.hdr("av01.0.08M.10"));
        assertTrue("VP9 10-bit", VideoConvert.hdr("vp09.02.10.10"));
        assertFalse("AV1 8-bit without its color fields", VideoConvert.hdr("av01.0.08M.08"));
        assertTrue("H.265 Main 10", VideoConvert.hdr("hvc1.2.4.L120.B0"));
        assertTrue("H.265 Main 10 with a profile space", VideoConvert.hdr("hev1.A2.4.L120.B0"));
        assertFalse("H.265 Main", VideoConvert.hdr("hvc1.1.6.L93.90"));
        assertFalse("H.264 is never HDR", VideoConvert.hdr("avc1.64001f"));
    }

    /** Twice the source's bitrate, or a tenth of a bit a pixel a frame when it isn't known, within 2 to 12 Mbps. */
    @Test
    public void theBitrateFollowsTheSourceWithinItsLimits() {
        assertEquals(3_050_000, VideoConvert.bitrate(1_525_000, 1080, 1920, 30));
        assertEquals(VideoConvert.MIN_BITRATE, VideoConvert.bitrate(400_000, 720, 1280, 30));
        assertEquals(VideoConvert.MAX_BITRATE, VideoConvert.bitrate(9_000_000, 1080, 1920, 30));
        assertEquals(6_220_800, VideoConvert.bitrate(0, 1080, 1920, 30));
        assertEquals(VideoConvert.MIN_BITRATE, VideoConvert.bitrate(0, 360, 640, 30));
    }

    /** The encoder is told to keep to the bitrate, variable first, since left alone it wrote 3 to 4 times it (#236). */
    @Test
    public void theEncoderIsToldHowToKeepTheBitrate() {
        int vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        int cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
        assertEquals(vbr, VideoConvert.bitrateMode(mode -> true));
        assertEquals(cbr, VideoConvert.bitrateMode(mode -> mode == cbr));
        assertEquals(-1, VideoConvert.bitrateMode(mode -> mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ));
        assertEquals("VBR", VideoConvert.modeName(vbr));
        assertEquals("CBR", VideoConvert.modeName(cbr));
    }

    /** The report gives what the frames came to, so a phone's overshoot shows in it. */
    @Test
    public void theWrittenBitrateIsMeasuredOverThePicture() {
        assertEquals(3_000, VideoConvert.achievedKbps(11_250_000L, 29_966_000L, 30_000_000L));
        assertEquals("without a length, the last frame's time", 4_000, VideoConvert.achievedKbps(5_000_000L, 10_000_000L, -1));
        assertEquals(0, VideoConvert.achievedKbps(5_000_000L, 0, 0));
    }

    /** The retry after an encoder refuses its listed mode asks again with no mode at all. */
    @Test
    public void theModeIsLeftOutWhenThereIsNone() {
        int vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        MediaFormat named = VideoConvert.encoderFormat(1080, 1920, 3_000_000, vbr, 30);
        assertEquals(vbr, named.getInteger(MediaFormat.KEY_BITRATE_MODE));
        assertEquals(3_000_000, named.getInteger(MediaFormat.KEY_BIT_RATE));
        assertEquals(1080, named.getInteger(MediaFormat.KEY_WIDTH));
        MediaFormat plain = VideoConvert.encoderFormat(1080, 1920, 3_000_000, -1, 30);
        assertFalse(plain.containsKey(MediaFormat.KEY_BITRATE_MODE));
        assertEquals(30, plain.getInteger(MediaFormat.KEY_FRAME_RATE));
        assertEquals("in the encoder's own mode", VideoConvert.modeName(-1));
    }

    /** The H.264 file is tagged BT.709 limited range SDR, so a phone's own encoder can't pick another matrix. */
    @Test
    public void theEncoderNamesItsColors() {
        MediaFormat format = VideoConvert.encoderFormat(1080, 1920, 12_000_000, -1, 30);
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, format.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE));
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
    }
}
