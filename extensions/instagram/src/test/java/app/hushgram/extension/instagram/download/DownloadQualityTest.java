/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which rendition each download quality picks, among DASH tracks and among single files. A
 * ceiling takes the best at or under it and the nearest above it when there's nothing that low,
 * so a video always has an answer. These are plain JUnit: the ranking holds no Android type.
 */
public class DownloadQualityTest {

    // ---- The order a setting puts qualities in --------------------------------------------------

    @Test
    public void eachSettingOrdersQualitiesItsOwnWay() {
        // Best: higher first.
        assertTrue(DownloadQuality.BEST.compare(1080, 720) < 0);
        assertTrue(DownloadQuality.BEST.compare(360, 720) > 0);
        // A ceiling: under it the higher, and anything under beats anything above.
        assertTrue(DownloadQuality.P720.compare(720, 480) < 0);
        assertTrue(DownloadQuality.P720.compare(480, 1080) < 0);
        assertTrue(DownloadQuality.P720.compare(1080, 480) > 0);
        // Above it, the nearest.
        assertTrue(DownloadQuality.P360.compare(480, 720) < 0);
        assertTrue(DownloadQuality.P360.compare(1080, 480) > 0);
        // Exactly the ceiling fits.
        assertTrue(DownloadQuality.P480.compare(480, 540) < 0);
        // Smallest: lower first.
        assertTrue(DownloadQuality.SMALLEST.compare(240, 360) < 0);
        assertTrue(DownloadQuality.SMALLEST.compare(1080, 360) > 0);
        // A quality nobody stated comes last in every setting, and two of them are equal.
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertTrue(quality + " put an unstated quality first", quality.compare(0, 144) > 0);
            assertTrue(quality + " put an unstated quality first", quality.compare(4320, 0) < 0);
            assertEquals(0, quality.compare(0, 0));
            assertEquals(0, quality.compare(720, 720));
        }
    }

    @Test
    public void aFileValueNamesExactlyOneQuality() {
        Map<DownloadQuality, String> expected = new EnumMap<>(DownloadQuality.class);
        expected.put(DownloadQuality.BEST, "best");
        expected.put(DownloadQuality.P1080, "1080p");
        expected.put(DownloadQuality.P720, "720p");
        expected.put(DownloadQuality.P480, "480p");
        expected.put(DownloadQuality.P360, "360p");
        expected.put(DownloadQuality.SMALLEST, "smallest");
        assertEquals("a quality was added or renamed without its file value being decided",
                expected.keySet(), new java.util.HashSet<>(Arrays.asList(DownloadQuality.values())));
        for (Map.Entry<DownloadQuality, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().fileValue);
            assertSame(entry.getKey(), DownloadQuality.fromFile(entry.getValue()));
        }
        for (Object refused : new Object[]{null, "", "BEST", "Best", "720", "720P", " 720p", "1440p", 720, true}) {
            assertNull(String.valueOf(refused), DownloadQuality.fromFile(refused));
        }
        assertNull(DownloadQuality.BEST.ceilingLabel());
        assertNull(DownloadQuality.SMALLEST.ceilingLabel());
        assertEquals("480p", DownloadQuality.P480.ceilingLabel());
    }

    // ---- DASH tracks ----------------------------------------------------------------------------

    private static DashManifest.Track track(String codecs, int width, int height, long bandwidth, int label) {
        return new DashManifest.Track("video/mp4", codecs, width, height, bandwidth,
                "https://video.xx.fbcdn.net/v/" + codecs + "-" + label + "-" + bandwidth + ".mp4", label);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = DownloadQualityTest.class.getResourceAsStream(name)) {
            assertNotNull("no " + name + " beside the test", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Meta's own manifest, captured from a public video: eight AV1 tracks, seven of them 720 by
     * 1280, told apart only by their labels from 240p to 1080p. Each setting lands on the label it
     * names, and one past the ends lands on the nearest.
     */
    @Test
    public void onTheCapturedManifestEachSettingTakesItsLabel() throws IOException {
        List<DashManifest.Track> tracks = DashManifest.parse(resource("meta-public-dash.mpd"));
        Map<DownloadQuality, Integer> label = new EnumMap<>(DownloadQuality.class);
        label.put(DownloadQuality.BEST, 1080);
        label.put(DownloadQuality.P1080, 1080);
        label.put(DownloadQuality.P720, 720);
        label.put(DownloadQuality.P480, 480);
        label.put(DownloadQuality.P360, 360);
        label.put(DownloadQuality.SMALLEST, 240);
        for (DownloadQuality quality : DownloadQuality.values()) {
            DashManifest.Track picked = DashManifest.pickVideo(tracks, true, quality);
            assertNotNull(quality.toString(), picked);
            assertEquals(quality + " picked " + picked, (int) label.get(quality), picked.label);
            // Before Android 14 nothing in it can be written, whatever the setting.
            assertNull(quality.toString(), DashManifest.pickVideo(tracks, false, quality));
        }
        // The best keeps the pick it always made.
        assertSame(DashManifest.bestVideo(tracks, true), DashManifest.pickVideo(tracks, true, DownloadQuality.BEST));
        assertSame(DashManifest.bestVideo(tracks, true), DashManifest.pickVideo(tracks, true, null));
    }

    @Test
    public void aTrackWithNoLabelIsHeldToItsShortSide() {
        DashManifest.Track tall = track("avc1.64001f", 1080, 1920, 3_000_000, 0);
        DashManifest.Track mid = track("avc1.64001f", 720, 1280, 1_500_000, 0);
        DashManifest.Track low = track("avc1.64001f", 360, 640, 400_000, 0);
        List<DashManifest.Track> tracks = Arrays.asList(tall, mid, low);
        assertSame(mid, DashManifest.pickVideo(tracks, false, DownloadQuality.P720));
        assertSame(low, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(low, DashManifest.pickVideo(tracks, false, DownloadQuality.SMALLEST));
        assertSame(tall, DashManifest.pickVideo(tracks, false, DownloadQuality.P1080));
    }

    /** A ceiling under every track still saves: the nearest above it, never nothing. */
    @Test
    public void aCeilingUnderEveryTrackTakesTheNearestAboveIt() {
        DashManifest.Track hd = track("avc1.64001f", 1080, 1920, 3_000_000, 1080);
        DashManifest.Track sd = track("avc1.64001f", 720, 1280, 1_500_000, 720);
        List<DashManifest.Track> tracks = Arrays.asList(hd, sd);
        assertSame(sd, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(sd, DashManifest.pickVideo(tracks, false, DownloadQuality.P360));
        assertSame(hd, DashManifest.pickVideo(Collections.singletonList(hd), false, DownloadQuality.P360));
        // A ceiling over every track takes the best there is.
        assertSame(sd, DashManifest.pickVideo(Collections.singletonList(sd), false, DownloadQuality.P1080));
    }

    /**
     * At one label, H.264 still beats H.265, and a track the phone can't write is never picked.
     * Then the higher bitrate, except for the smallest file, which takes the lower.
     */
    @Test
    public void atOneLabelTheCodecThenTheBitrateDecide() {
        DashManifest.Track hevc = track("hvc1.1.6.L93.90", 720, 1280, 900_000, 480);
        DashManifest.Track avcHigh = track("avc1.64001f", 720, 1280, 800_000, 480);
        DashManifest.Track avcLow = track("avc1.64001f", 720, 1280, 500_000, 480);
        DashManifest.Track vp9 = track("vp09.00.40.08", 720, 1280, 100_000, 240);
        DashManifest.Track av1 = track("av01.0.05m.08", 720, 1280, 90_000, 240);
        List<DashManifest.Track> tracks = Arrays.asList(hevc, avcHigh, vp9, avcLow, av1);
        assertSame(avcHigh, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(avcLow, DashManifest.pickVideo(Arrays.asList(hevc, avcHigh, avcLow), false, DownloadQuality.SMALLEST));
        assertSame("the smallest file passed over the VP9 track", vp9,
                DashManifest.pickVideo(tracks, false, DownloadQuality.SMALLEST));
        assertSame("an AV1 track the phone can write was passed over", av1,
                DashManifest.pickVideo(tracks, true, DownloadQuality.SMALLEST));
        assertNull(DashManifest.pickVideo(Collections.singletonList(av1), false, DownloadQuality.SMALLEST));
    }

    /**
     * A story that comes only in VP9 saves its best VP9 track with its sound: the save writes that
     * MP4 itself. At one size VP9 comes after every format Android's own muxer writes, so a manifest
     * that also lists AV1 the phone can write keeps it. With saves other apps can open on, H.264 is
     * still the only picture, and a VP9 track outside an MP4 is never picked.
     */
    @Test
    public void aVp9OnlyStorySavesItsBestVp9Track() {
        DashManifest.Track vp9Best = track("vp09.00.40.08", 1080, 1920, 711_000, 1080);
        DashManifest.Track vp9Small = track("vp09.00.21.08", 360, 640, 150_000, 360);
        DashManifest.Track sound = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 64_000,
                "https://video.xx.fbcdn.net/v/sound.mp4");
        List<DashManifest.Track> story = Arrays.asList(vp9Small, vp9Best, sound);

        assertSame(vp9Best, DashManifest.bestVideo(story, false));
        assertSame(vp9Small, DashManifest.pickVideo(story, false, DownloadQuality.P360));
        DashManifest.Pick pick = DashManifest.pick(story, false, DownloadQuality.BEST, false);
        assertSame(vp9Best, pick.video);
        assertSame(sound, pick.audio);
        assertNull("VP9 counted as a picture other apps can open",
                DashManifest.pick(story, true, DownloadQuality.BEST, true));

        DashManifest.Track av1 = track("av01.0.08m.08", 1080, 1920, 600_000, 1080);
        assertSame(av1, DashManifest.bestVideo(Arrays.asList(vp9Best, av1), true));
        assertSame(vp9Best, DashManifest.bestVideo(Arrays.asList(vp9Best, av1), false));
        DashManifest.Track webm = new DashManifest.Track("video/webm", "vp09.00.40.08", 1080, 1920, 711_000,
                "https://video.xx.fbcdn.net/v/story.webm", 1080);
        assertNull(DashManifest.bestVideo(Collections.singletonList(webm), true));
    }

    /** A label reads only as three or four digits and a p; anything else leaves the short side. */
    @Test
    public void onlyAWholeLabelIsRead() {
        String[][] labels = {{"720p", "720"}, {"1080p", "1080"}, {" 480P ", "480"}, {"4320p", "4320"},
                {"720", "0"}, {"p720", "0"}, {"72p", "0"}, {"10800p", "0"}, {"7a0p", "0"}, {"", "0"}, {"hd", "0"}};
        for (String[] label : labels) {
            String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                    + "<Representation codecs=\"avc1.64001f\" width=\"720\" height=\"1280\" bandwidth=\"900000\""
                    + " FBQualityLabel=\"" + label[0] + "\"><BaseURL>https://video.xx.fbcdn.net/v/a.mp4</BaseURL>"
                    + "</Representation></AdaptationSet></Period></MPD>";
            DashManifest.Track parsed = DashManifest.parse(manifest).get(0);
            assertEquals(label[0], Integer.parseInt(label[1]), parsed.label);
            assertEquals(label[0], label[1].equals("0") ? 720 : Integer.parseInt(label[1]), parsed.quality());
        }
    }

    // ---- Single files ---------------------------------------------------------------------------

    private static final String HD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private static final String SD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_360p.mp4?oh=1&oe=2";
    /** A file that states no quality. */
    private static final String PLAIN = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip.mp4?oh=1&oe=2";

    @Test
    public void amongSingleFilesEachSettingTakesItsOwn() {
        List<String> both = Arrays.asList(HD, SD);
        assertEquals(720, RenditionPicker.qualityOf(HD));
        assertEquals(360, RenditionPicker.qualityOf(SD));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.BEST));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.P1080));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.P720));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.P480));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.P360));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.SMALLEST));
        // The order they arrive in changes nothing.
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertEquals(quality.toString(), RenditionPicker.bestVideo(both, quality),
                    RenditionPicker.bestVideo(Arrays.asList(SD, HD), quality));
        }
        // The best is the ranking it always was.
        assertEquals(RenditionPicker.bestOf(both, true), RenditionPicker.bestVideo(both, DownloadQuality.BEST));
    }

    /** One file, or files that state nothing: a ceiling never leaves the save without one. */
    @Test
    public void aCeilingNeverLeavesASaveWithNothing() {
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertEquals(quality.toString(), HD, RenditionPicker.bestVideo(Collections.singletonList(HD), quality));
            assertEquals(quality.toString(), PLAIN, RenditionPicker.bestVideo(Collections.singletonList(PLAIN), quality));
            assertNull(RenditionPicker.bestVideo(Collections.<String>emptyList(), quality));
            assertNull(RenditionPicker.bestVideo(null, quality));
            assertNull("a picture passed as a video",
                    RenditionPicker.bestVideo(Collections.singletonList("https://scontent.xx.fbcdn.net/v/p.jpg"), quality));
        }
        // A file that states its quality beats one that doesn't, below the best too.
        assertEquals(SD, RenditionPicker.bestVideo(Arrays.asList(PLAIN, SD), DownloadQuality.P1080));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(PLAIN, HD), DownloadQuality.P360));
    }

    // ---- The efg parameter Meta packs a file's variant into -------------------------------------

    /** A file whose only word on its quality is inside its efg, shaped the way Meta writes it. */
    private static String withEfg(String json) {
        String efg = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/AQN.mp4?efg=" + efg + "&oh=1&oe=2";
    }

    /**
     * The S22 diagnostic on 2026-09-26 called a 640x360 progressive MP4 "1485p": the file ran
     * 1485.47 seconds, and the efg's duration_s was read as a height. Its numeric fields are never
     * a quality, only the tag's text is.
     */
    @Test
    public void anEfgDurationIsNotAHeight() {
        String untagged = withEfg("{\"vencode_tag\":\"xpv_progressive.FACEBOOK..C3.dash_baseline_1_v1\","
                + "\"xpv_asset_id\":1234567890,\"vi_usecase_id\":10107,\"duration_s\":1485,\"urlgen_source\":\"www\"}");
        assertEquals(0, RenditionPicker.qualityOf(untagged));
        assertEquals("mp4 (unknown)", MediaSave.describe(untagged));

        String tagged = withEfg("{\"vencode_tag\":\"xpv_progressive.FACEBOOK..C3.360.sve_sd\","
                + "\"xpv_asset_id\":1234567890,\"duration_s\":1485,\"urlgen_source\":\"www\"}");
        assertEquals(360, RenditionPicker.qualityOf(tagged));
        assertEquals("mp4 (360p)", MediaSave.describe(tagged));

        // A duration that happens to be a real height is still a duration. (A tag ending in the
        // word sd would read 480 through the crude fallback; this one names no size at all.)
        String minutes = withEfg("{\"vencode_tag\":\"xpv_progressive.FACEBOOK..C3.dash_baseline_1_v1\",\"duration_s\":720}");
        assertEquals(0, RenditionPicker.qualityOf(minutes));
        // And the long file doesn't outrank a real 720p because of its length.
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(untagged, HD), DownloadQuality.BEST));
    }

    /** A width in the tag, such as the 640 of a 640x360 encode, isn't read as a height either. */
    @Test
    public void onlyAHeightMetaEncodesAtIsReadBare() {
        assertEquals(0, RenditionPicker.qualityOf(withEfg("{\"vencode_tag\":\"xpv_progressive.FACEBOOK..C3.640.dash_baseline_1_v1\"}")));
        assertEquals(720, RenditionPicker.qualityOf(withEfg("{\"vencode_tag\":\"xpv_progressive.FACEBOOK..C3.720.dash_high_1_v1\"}")));
        // A marker anywhere in the efg still counts as measured.
        assertEquals(1080, RenditionPicker.qualityOf(withEfg("{\"vencode_tag\":\"clip_1080p\",\"duration_s\":1485}")));
        // An efg that isn't JSON is read whole, as before.
        assertEquals(480, RenditionPicker.qualityOf(withEfg("xpv_progressive.C3.480.sve_sd")));
        assertEquals("xpv 360 ", RenditionPicker.tagTextOf("{\"vencode_tag\":\"xpv 360\",\"n\":1485,\"other\":\"999\"}"));
    }

    /** A single file still beats an address that only might be one, whatever quality it states. */
    @Test
    public void aSingleFileBeatsAPlausibleAddressFirst() {
        String plausible = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/stream_360p?oh=1";
        assertEquals(RenditionPicker.TIER_PLAUSIBLE, RenditionPicker.videoTier(plausible));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(plausible, HD), DownloadQuality.P360));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(plausible, HD), DownloadQuality.SMALLEST));
    }

    // ---- Renditions whose size the app states -----------------------------------------------------

    /** Where Instagram's single files live, as its video_versions list them. */
    private static final String IG = "https://scontent-lga3-1.cdninstagram.com/o1/v/t16/f2/m86/";

    /** A size the app states outranks the marker in the address, which is only a guess. */
    @Test
    public void aStatedSizeOutranksTheAddressesMarker() {
        // The address says 720p and the app says 480x854; the address says 360p and the app 1080x1920.
        MediaSave.Rendition says720 = new MediaSave.Rendition(IG + "a_720p.mp4?oh=1&oe=2", 480, 854, 0);
        MediaSave.Rendition says360 = new MediaSave.Rendition(IG + "b_360p.mp4?oh=1&oe=2", 1080, 1920, 0);
        assertEquals(480, RenditionPicker.qualityOf(says720));
        assertEquals(1080, RenditionPicker.qualityOf(says360));
        List<MediaSave.Rendition> both = Arrays.asList(says720, says360);
        assertSame(says360, RenditionPicker.pickVideo(both, DownloadQuality.BEST));
        assertSame(says360, RenditionPicker.pickVideo(both, null));
        assertSame(says720, RenditionPicker.pickVideo(both, DownloadQuality.P720));
        assertSame(says720, RenditionPicker.pickVideo(Arrays.asList(says360, says720), DownloadQuality.SMALLEST));

        // With no size stated, the address is read the way it always was, and half a size is none.
        assertEquals(720, RenditionPicker.qualityOf(MediaSave.Rendition.of(IG + "a_720p.mp4?oh=1&oe=2")));
        assertEquals(720, RenditionPicker.qualityOf(new MediaSave.Rendition(IG + "a_720p.mp4?oh=1&oe=2", 480, 0, 0)));
        assertEquals(720, RenditionPicker.qualityOf(new MediaSave.Rendition(IG + "a_720p.mp4?oh=1&oe=2", -480, -854, -1)));
        assertEquals(0, RenditionPicker.qualityOf((MediaSave.Rendition) null));
    }

    /** Two of one size: the stated bitrate decides, the lower one for the smallest file, then the address. */
    @Test
    public void aStatedBitrateBreaksATieBetweenTwoOfOneSize() {
        MediaSave.Rendition rich = new MediaSave.Rendition(IG + "z.mp4?oh=1&oe=2", 720, 1280, 2_400_000);
        MediaSave.Rendition lean = new MediaSave.Rendition(IG + "a.mp4?oh=1&oe=2", 720, 1280, 900_000);
        for (DownloadQuality quality : new DownloadQuality[]{DownloadQuality.BEST, DownloadQuality.P1080, DownloadQuality.P720}) {
            assertSame(quality.toString(), rich, RenditionPicker.pickVideo(Arrays.asList(lean, rich), quality));
            assertSame(quality.toString(), rich, RenditionPicker.pickVideo(Arrays.asList(rich, lean), quality));
        }
        assertSame(lean, RenditionPicker.pickVideo(Arrays.asList(rich, lean), DownloadQuality.SMALLEST));

        // A bitrate nobody stated says nothing either way, so the address decides.
        MediaSave.Rendition unstated = new MediaSave.Rendition(IG + "z.mp4?oh=1&oe=2", 720, 1280, 0);
        assertSame(lean, RenditionPicker.pickVideo(Arrays.asList(unstated, lean), DownloadQuality.BEST));

        // The size comes before the bitrate: a bigger bitrate on a smaller picture loses at the best.
        MediaSave.Rendition small = new MediaSave.Rendition(IG + "s.mp4?oh=1&oe=2", 480, 854, 5_000_000);
        assertSame(rich, RenditionPicker.pickVideo(Arrays.asList(small, rich), DownloadQuality.BEST));
        assertSame(small, RenditionPicker.pickVideo(Arrays.asList(small, rich), DownloadQuality.P480));
    }

    /** The largest picture by its stated size, never a thumbnail, never a picture for a video, and one address once. */
    @Test
    public void thePhotoPickIsTheLargestPictureThatIsNotAThumbnail() {
        String base = "https://scontent-lga3-1.cdninstagram.com/v/t51.2885-15/";
        MediaSave.Rendition full = new MediaSave.Rendition(base + "full.jpg?oh=1&oe=2", 1440, 1800, 0);
        MediaSave.Rendition medium = new MediaSave.Rendition(base + "medium.jpg?oh=1&oe=2", 1080, 1350, 0);
        MediaSave.Rendition thumb = new MediaSave.Rendition(base + "thumb.jpg?stp=dst-jpg_s150x150&oh=1&oe=2",
                3000, 3000, 0);
        assertSame(full, RenditionPicker.pickImage(Arrays.asList(medium, thumb, full)));
        assertNull("a thumbnail was saved as the photo", RenditionPicker.pickImage(Collections.singletonList(thumb)));
        assertNull(RenditionPicker.pickImage(null));
        assertNull("a picture was taken for a video", RenditionPicker.pickVideo(Arrays.asList(full, medium),
                DownloadQuality.BEST));

        // The same address listed twice counts once, as it came first.
        MediaSave.Rendition again = new MediaSave.Rendition(full.url, 100, 100, 0);
        assertSame(full, RenditionPicker.pickImage(Arrays.asList(full, again, medium)));
        assertNull(RenditionPicker.pickVideo(Arrays.asList(null, MediaSave.Rendition.of(null)), DownloadQuality.BEST));

        // A rendition in a report line says its size and never its address.
        assertEquals("Rendition(1440x1800)", full.toString());
        assertEquals("Rendition(720x1280, 2400kbps)",
                new MediaSave.Rendition(IG + "z.mp4?oh=1&oe=2", 720, 1280, 2_400_000).toString());
        assertEquals("Rendition(size unknown)", MediaSave.Rendition.of(IG + "z.mp4").toString());
    }

    // ---- Reading an object whose field names aren't known ------------------------------------------

    /** A media model's base class, holding the one field a caller may know by name. */
    static class BaseMedia {
        final String code = "C1a2B3";
    }

    /** A media model the way an obfuscated build keeps one: addresses in fields, lists and arrays. */
    static final class Media extends BaseMedia {
        static final String CONSTANT = IG + "static.mp4";
        final String video = IG + "v_720p.mp4?oh=1&oe=2";
        final List<String> versions = Arrays.asList(IG + "v_480p.mp4", "not an address", null);
        final String[] candidates = { "https://scontent-lga3-1.cdninstagram.com/v/t51.2885-15/p.jpg" };
        final String playlist = "<MPD><Period></Period></MPD>";
        final StringBuilder owner = new StringBuilder("Stevi Ous");
        /** Not the app's own class, so the walk doesn't go into it. */
        final Holder platform = new Holder();
    }

    static final class Holder {
        final String hidden = IG + "hidden.mp4";
    }

    /**
     * A caller that can't name a model's fields reads every address it holds, and its manifest.
     * The walk reads the object's own fields, one level into a list or an array, and never a
     * static field or an object of the platform.
     */
    @Test
    public void aModelIsReadForItsAddressesAndManifestWithoutFieldNames() {
        Media media = new Media();
        List<String> found = RenditionPicker.harvest(media, 3);
        assertEquals(Arrays.asList(media.video, media.versions.get(0), media.candidates[0]), sorted(found, media));
        assertEquals(media.playlist, RenditionPicker.manifestIn(media, 3));
        assertNull(RenditionPicker.manifestIn(new Holder(), 3));
        assertTrue(RenditionPicker.harvest(null, 3).isEmpty());

        // A field known by name, declared on a superclass.
        assertEquals("C1a2B3", RenditionPicker.fieldValue(media, "code"));
        assertNull(RenditionPicker.fieldValue(media, "renamed"));
        assertNull(RenditionPicker.fieldValue(null, "code"));

        // Ranked, the model's best video is its 720p file, and its best picture the jpg.
        assertEquals(media.video, RenditionPicker.bestVideo(found, DownloadQuality.BEST));
        assertEquals(media.candidates[0], RenditionPicker.bestOf(found, false));
    }

    /** [found] in the order [media] declares them, since the walk's field order isn't fixed. */
    private static List<String> sorted(List<String> found, Media media) {
        List<String> order = Arrays.asList(media.video, media.versions.get(0), media.candidates[0]);
        List<String> out = new java.util.ArrayList<>(found);
        out.sort(java.util.Comparator.comparingInt(order::indexOf));
        return out;
    }
}
