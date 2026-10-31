/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which DASH tracks a save takes with saves other apps can open on, and how ordinary saves keep
 * their resolution while preferring widely supported sound (issues #11 and #14). A reel saved as 1080p AV1 with
 * xHE-AAC sound: Gallery and VLC played it, WhatsApp said "Can't send this video". Plain JUnit, as
 * the ranking holds no Android type.
 */
public class CompatibleTracksTest {

    private static final String BASE = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/";

    private static DashManifest.Track video(String codecs, int width, int height, long bandwidth, int label) {
        return new DashManifest.Track("video/mp4", codecs, width, height, bandwidth,
                BASE + codecs + "-" + label + "-" + bandwidth + ".mp4", label);
    }

    private static DashManifest.Track audio(String codecs, long bandwidth) {
        return new DashManifest.Track("audio/mp4", codecs, 0, 0, bandwidth, BASE + codecs + "-" + bandwidth + ".mp4");
    }

    /** The picture and sound tracks the S22's reel was saved from on 2026-09-27. */
    private static final DashManifest.Track AV1_1080 = video("av01.0.08m.08.0.111.01.01.01.0", 1080, 1920, 1_525_000, 1080);
    private static final DashManifest.Track XHE_84 = audio("mp4a.40.42", 84_000);
    /** What the same manifest offers that WhatsApp takes. */
    private static final DashManifest.Track H264_720 = video("avc1.64001f", 720, 1280, 1_200_000, 720);
    private static final DashManifest.Track LC_64 = audio("mp4a.40.2", 64_000);

    private static final List<DashManifest.Track> ISSUE_11 = Arrays.asList(AV1_1080, H264_720, XHE_84, LC_64);

    /** Safer sound must not cost the requested picture resolution (silent Gallery report #14). */
    @Test
    public void ordinarySavesPreferWidelySupportedAudioWithoutLoweringTheVideo() {
        DashManifest.Track he = audio("mp4a.40.5", 48_000);
        for (DownloadQuality quality : DownloadQuality.values()) {
            List<DashManifest.Track> tracks = Arrays.asList(AV1_1080, XHE_84, he);
            DashManifest.Pick picked = DashManifest.pick(tracks, true, quality, false);
            assertSame(quality.toString(), AV1_1080, picked.video);
            assertSame(quality.toString(), he, picked.audio);
            assertSame(LC_64, DashManifest.pick(ISSUE_11, true, quality, false).audio);
        }
        // If xHE-AAC is the only sound, retain it instead of discarding the audio.
        assertSame(XHE_84, DashManifest.pick(Arrays.asList(AV1_1080, XHE_84), true,
                DownloadQuality.BEST, false).audio);
    }

    @Test
    public void unsupportedSoundFallsBackToTheSingleFileInsteadOfMakingASilentVideo() {
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertNull(quality.toString(), DashManifest.pick(Arrays.asList(H264_720, audio("opus", 96_000)),
                    true, quality, false));
            assertNull(quality.toString(), DashManifest.pick(Arrays.asList(H264_720, audio("", 96_000)),
                    true, quality, false));
            assertNull(DashManifest.pick(Collections.singletonList(H264_720), true, quality, false).audio);
        }
    }

    @Test
    public void theIssue11ManifestTakesH264AndAacLcWithTheSwitchOn() {
        DashManifest.Pick on = DashManifest.pick(ISSUE_11, true, DownloadQuality.BEST, true);
        assertNotNull(on);
        assertSame(H264_720, on.video);
        assertSame(LC_64, on.audio);

        DashManifest.Pick off = DashManifest.pick(ISSUE_11, true, DownloadQuality.BEST, false);
        assertNotNull(off);
        assertSame(AV1_1080, off.video);
        assertSame(LC_64, off.audio);
        // Off keeps the higher-resolution picture with the safer sound.
        assertSame(DashManifest.bestVideo(ISSUE_11, true), off.video);
        assertSame(DashManifest.bestAudio(ISSUE_11), off.audio);

        // Where the muxer can't write AV1, the switch still picks the same pair.
        DashManifest.Pick noAv1 = DashManifest.pick(ISSUE_11, false, DownloadQuality.BEST, true);
        assertSame(H264_720, noAv1.video);
        assertSame(LC_64, noAv1.audio);
    }

    /** No H.264 track at all: nothing to pick, so the save goes to the single MP4. */
    @Test
    public void anAv1OnlyManifestHasNoCompatiblePick() {
        DashManifest.Track av1_720 = video("av01.0.05m.08", 720, 1280, 800_000, 720);
        List<DashManifest.Track> tracks = Arrays.asList(AV1_1080, av1_720, LC_64);
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertNull(quality.toString(), DashManifest.pick(tracks, true, quality, true));
        }
        assertSame(AV1_1080, DashManifest.pick(tracks, true, DownloadQuality.BEST, false).video);
        // H.265 isn't H.264 either.
        DashManifest.Track hevc = video("hvc1.1.6.L93.90", 720, 1280, 900_000, 720);
        assertNull(DashManifest.pick(Arrays.asList(hevc, LC_64), true, DownloadQuality.BEST, true));
        assertSame(hevc, DashManifest.pick(Arrays.asList(hevc, LC_64), true, DownloadQuality.BEST, false).video);
    }

    /**
     * Meta's own manifest, captured from a public video: AV1 pictures only and HE-AAC sound, as
     * stories often are. Nothing in it is H.264, so a compatible save takes the single file.
     */
    @Test
    public void theCapturedManifestHasNoCompatiblePick() throws IOException {
        List<DashManifest.Track> tracks = DashManifest.parse(resource("meta-public-dash.mpd"));
        assertEquals(tracks.toString(), 9, tracks.size());
        assertNull(DashManifest.pick(tracks, true, DownloadQuality.BEST, true));
        assertEquals("its sound is HE-AAC, which other apps take", "mp4a.40.5",
                DashManifest.bestCompatibleAudio(tracks).codecs);
        assertNotNull(DashManifest.pick(tracks, true, DownloadQuality.BEST, false));
    }

    /**
     * A manifest says HDR in its cicp properties, on the set for every picture in it or on one
     * representation alone, or in a codecs string's bit depth or transfer field (#352). Meta writes
     * the three cicp properties with value 1 on its SDR sets, which marks nothing, and the captured
     * manifest's tracks are all standard range.
     */
    @Test
    public void hdrIsReadFromTheManifestsColourPropertiesAndCodecs() throws IOException {
        String cicp = "urn:mpeg:mpegB:cicp:";
        String manifest = "<MPD><Period>"
                + "<AdaptationSet mimeType=\"video/mp4\">"
                + "<SupplementalProperty schemeIdUri=\"" + cicp + "TransferCharacteristics\" value=\"16\"/>"
                + "<SupplementalProperty schemeIdUri=\"" + cicp + "ColourPrimaries\" value=\"9\"/>"
                + representation("hvc1.2.4.L120.B0", 1080, 1920, 2_000_000, "hevc", "")
                + representation("avc1.64001f", 720, 1280, 1_200_000, "h264-in-the-hdr-set", "")
                + "</AdaptationSet>"
                + "<AdaptationSet mimeType=\"video/mp4\">"
                + "<SupplementalProperty schemeIdUri=\"" + cicp + "TransferCharacteristics\" value=\"1\"/>"
                + "<SupplementalProperty schemeIdUri=\"" + cicp + "ColourPrimaries\" value=\"1\"/>"
                + "<SupplementalProperty schemeIdUri=\"" + cicp + "MatrixCoefficients\" value=\"1\"/>"
                + representation("avc1.64001f", 720, 1280, 1_000_000, "sdr", "")
                + representation("avc1.640028", 1080, 1920, 1_800_000, "wide",
                        "<EssentialProperty schemeIdUri=\"" + cicp + "ColourPrimaries\" value=\"9\"/>")
                + representation("av01.0.08M.10.0.110.09.16.09.0", 1080, 1920, 1_500_000, "av1-hdr", "")
                + representation("vp09.00.40.08", 1080, 1920, 1_400_000, "vp9-sdr", "")
                + "</AdaptationSet></Period></MPD>";
        List<DashManifest.Track> tracks = DashManifest.parse(manifest);
        assertEquals(tracks.toString(), 6, tracks.size());
        assertTrue("a set's PQ marks every picture in it", named(tracks, "hevc").hdr);
        assertTrue(named(tracks, "h264-in-the-hdr-set").hdr);
        assertFalse("cicp value 1 is BT.709", named(tracks, "sdr").hdr);
        assertTrue("a representation's own BT.2020 primaries", named(tracks, "wide").hdr);
        assertFalse("one representation's properties don't mark its siblings", named(tracks, "vp9-sdr").hdr);
        assertTrue("PQ in the codecs", named(tracks, "av1-hdr").hdr);
        assertEquals("video/mp4 hvc1.2.4.l120.b0 1080x1920 2000kbps HDR", named(tracks, "hevc").toString());
        assertEquals("video/mp4 vp09.00.40.08 1080x1920 1400kbps", named(tracks, "vp9-sdr").toString());

        assertFalse(DashManifest.hdrSignalled(null));
        assertFalse(DashManifest.hdrSignalled("<SupplementalProperty schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"16\"/>"));
        assertTrue(DashManifest.hdrSignalled("<EssentialProperty value=\"18\" schemeIdUri=\"" + cicp + "TransferCharacteristics\"></EssentialProperty>"));

        for (DashManifest.Track track : DashManifest.parse(resource("meta-public-dash.mpd"))) {
            assertFalse(track.toString(), track.hdr);
        }
    }

    private static String representation(String codecs, int width, int height, long bandwidth, String name, String inside) {
        return "<Representation codecs=\"" + codecs + "\" width=\"" + width + "\" height=\"" + height
                + "\" bandwidth=\"" + bandwidth + "\">" + inside
                + "<BaseURL>" + BASE + name + ".mp4?oh=1&amp;oe=2</BaseURL></Representation>";
    }

    private static DashManifest.Track named(List<DashManifest.Track> tracks, String name) {
        for (DashManifest.Track track : tracks) {
            if (track.url.contains("/" + name + ".mp4")) return track;
        }
        throw new AssertionError(name + " isn't in " + tracks);
    }

    /** AAC-LC first, whatever its bitrate, then HE-AAC, then HE-AAC v2 at the same rank. */
    @Test
    public void heAacIsTakenWhenThereIsNoLc() {
        DashManifest.Track he48 = audio("mp4a.40.5", 48_000);
        DashManifest.Track heV2_32 = audio("mp4a.40.29", 32_000);
        DashManifest.Track lc32 = audio("mp4a.40.2", 32_000);

        DashManifest.Pick noLc = DashManifest.pick(Arrays.asList(H264_720, XHE_84, heV2_32, he48), false,
                DownloadQuality.BEST, true);
        assertSame(H264_720, noLc.video);
        assertSame(he48, noLc.audio);

        assertSame(heV2_32, DashManifest.bestCompatibleAudio(Arrays.asList(XHE_84, heV2_32)));
        assertSame("AAC-LC lost to a higher HE-AAC bitrate", lc32,
                DashManifest.bestCompatibleAudio(Arrays.asList(he48, XHE_84, lc32, heV2_32)));
        assertSame(LC_64, DashManifest.bestCompatibleAudio(Arrays.asList(lc32, LC_64)));
        // The ordinary save also prefers widely supported sound.
        assertSame(lc32, DashManifest.bestAudio(Arrays.asList(he48, XHE_84, lc32)));
    }

    @Test
    public void onlyH264AndTheListedAacProfilesCount() {
        String[][] ranks = {{"mp4a.40.2", "2"}, {"mp4a.40.02", "2"}, {"mp4a.40.5", "1"}, {"mp4a.40.29", "1"},
                {"mp4a.40.42", "0"}, {"mp4a.40.1", "0"}, {"mp4a.40.23", "0"}, {"mp4a.40.2x", "0"}, {"mp4a.40.", "0"},
                {"mp4a.67", "0"}, {"opus", "0"}, {"ec-3", "0"}, {"", "0"}};
        for (String[] rank : ranks) {
            assertEquals(rank[0], Integer.parseInt(rank[1]), DashManifest.compatibleAudioRank(audio(rank[0], 64_000)));
        }
        // A video track is never sound, whatever its codecs say.
        assertEquals(0, DashManifest.compatibleAudioRank(video("mp4a.40.2", 720, 1280, 1, 0)));

        for (String codecs : new String[]{"avc1.64001f", "avc1.4d401e", "avc3.640028"}) {
            assertTrue(codecs, DashManifest.isCompatibleVideo(video(codecs, 720, 1280, 1, 0)));
        }
        for (String codecs : new String[]{"av01.0.08m.08", "hvc1.1.6.l93.90", "hev1.1.6.l93.90", "vp09.00.40.08", ""}) {
            assertFalse(codecs, DashManifest.isCompatibleVideo(video(codecs, 720, 1280, 1, 0)));
        }
        assertFalse("sound passed as a picture", DashManifest.isCompatibleVideo(audio("avc1.64001f", 1)));
    }

    /**
     * A video with sound needs sound other apps take: an H.264 picture with only xHE-AAC or Opus
     * beside it has no pick, rather than a silent one. A video with no sound track needs none.
     */
    @Test
    public void soundOtherAppsTakeIsNeededOnlyWhenThereIsSound() {
        assertNull(DashManifest.pick(Arrays.asList(H264_720, XHE_84), true, DownloadQuality.BEST, true));
        assertNull(DashManifest.pick(Arrays.asList(H264_720, audio("opus", 96_000)), true, DownloadQuality.BEST, true));
        DashManifest.Pick off = DashManifest.pick(Arrays.asList(H264_720, XHE_84), true, DownloadQuality.BEST, false);
        assertSame(XHE_84, off.audio);

        DashManifest.Pick silent = DashManifest.pick(Collections.singletonList(H264_720), true, DownloadQuality.BEST, true);
        assertNotNull(silent);
        assertSame(H264_720, silent.video);
        assertNull(silent.audio);
        assertNull(DashManifest.pick(Collections.<DashManifest.Track>emptyList(), true, DownloadQuality.BEST, true));
    }

    /** The switch keeps within the Download quality: 480p asks for the best H.264 at or under 480p. */
    @Test
    public void theSwitchKeepsWithinTheDownloadQuality() {
        DashManifest.Track av1_480 = video("av01.0.05m.08", 720, 1280, 500_000, 480);
        DashManifest.Track h264_360 = video("avc1.64001e", 720, 1280, 400_000, 360);
        DashManifest.Track h264_240 = video("avc1.64001e", 720, 1280, 250_000, 240);
        List<DashManifest.Track> tracks = Arrays.asList(AV1_1080, H264_720, av1_480, h264_360, h264_240, XHE_84, LC_64);

        assertSame(h264_360, DashManifest.pick(tracks, true, DownloadQuality.P480, true).video);
        assertSame(av1_480, DashManifest.pick(tracks, true, DownloadQuality.P480, false).video);
        assertSame(h264_360, DashManifest.pick(tracks, true, DownloadQuality.P360, true).video);
        assertSame(H264_720, DashManifest.pick(tracks, true, DownloadQuality.P720, true).video);
        assertSame(H264_720, DashManifest.pick(tracks, true, DownloadQuality.P1080, true).video);
        assertSame(AV1_1080, DashManifest.pick(tracks, true, DownloadQuality.P1080, false).video);
        assertSame(h264_240, DashManifest.pick(tracks, true, DownloadQuality.SMALLEST, true).video);
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertSame(quality.toString(), LC_64, DashManifest.pick(tracks, true, quality, true).audio);
        }

        // H.264 only above the ceiling: the nearest above it, as the setting does for any track.
        List<DashManifest.Track> high = Arrays.asList(av1_480, H264_720, AV1_1080, LC_64);
        assertSame(H264_720, DashManifest.pick(high, true, DownloadQuality.P480, true).video);
        assertSame(av1_480, DashManifest.pick(high, true, DownloadQuality.P480, false).video);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CompatibleTracksTest.class.getResourceAsStream(name)) {
            assertNotNull("no " + name + " beside the test", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
