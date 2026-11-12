/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.net.InetAddress;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Convert to keep the full size (#236). With saves other apps can open on, a reel whose full size
 * is only VP9 or AV1 had its smaller H.264 saved. With this switch on too, the full size picture is
 * taken and converted to H.264, when the phone can convert it and it suits the quality better than
 * what the save would keep. Every save here starts for real and is refused before any socket opens,
 * and the report says what it chose and what it falls back to.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ConvertSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private Boolean av1Before;

    private static final String HD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private static final String SD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_360p.mp4?oh=1&oe=2";
    private static final String FULL = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_1080p.mp4?oh=1&oe=2";

    private static final String AV1 = "av01.0.08m.08.0.111.01.01.01.0";

    /** The reel in #236: its 1080p only as AV1, its H.264 at 720p, with xHE-AAC and AAC-LC sound. */
    private static final String MANIFEST = manifest(
            representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080")
                    + representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "v720"),
            sound("mp4a.40.42", 84_000, "xhe") + sound("mp4a.40.2", 64_000, "lc"));

    /** AV1 pictures only, as stories often are. */
    private static final String AV1_ONLY = manifest(
            representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080"),
            sound("mp4a.40.2", 64_000, "lc"));

    private static final String AV1_TRACK = "video/mp4 " + AV1 + " 1080x1920 1525kbps 1080p";
    private static final String FULL_AV1 = AV1_TRACK + " + audio/mp4 mp4a.40.2 0x0 64kbps";

    /** VP9 profile 2, 10-bit, PQ: how Instagram codes an HDR reel. */
    private static final String HDR_VP9 = "vp09.02.10.10.01.09.16.09.01";

    /** The reel in #352: its 1080p only in HDR, its standard range H.264 at 720p. */
    private static final String HDR_MANIFEST = manifest(
            representation(HDR_VP9, 1080, 1920, 1_900_000, "1080p", "v1080hdr")
                    + representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "v720"),
            sound("mp4a.40.2", 64_000, "lc"));

    /** HDR pictures only. */
    private static final String HDR_ONLY = manifest(
            representation(HDR_VP9, 1080, 1920, 1_900_000, "1080p", "v1080hdr"),
            sound("mp4a.40.2", 64_000, "lc"));

    private static final String HDR_TRACK = "video/mp4 " + HDR_VP9 + " 1080x1920 1900kbps 1080p HDR";
    private static final String FULL_HDR = HDR_TRACK + " + audio/mp4 mp4a.40.2 0x0 64kbps";
    private static final String H264 = "video/mp4 avc1.64001f 720x1280 1200kbps 720p + audio/mp4 mp4a.40.2 0x0 64kbps";
    private static final String CONVERTED = ", converted to H.264 for other apps";
    private static final String KEPT = ", kept to files other apps can open";

    private static String manifest(String videos, String sounds) {
        return "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">" + videos
                + "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">" + sounds
                + "</AdaptationSet></Period></MPD>";
    }

    private static String representation(String codecs, int width, int height, long bandwidth, String label, String name) {
        return "<Representation codecs=\"" + codecs + "\" width=\"" + width + "\" height=\"" + height
                + "\" bandwidth=\"" + bandwidth + "\" FBQualityLabel=\"" + label + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    private static String sound(String codecs, long bandwidth, String name) {
        return "<Representation codecs=\"" + codecs + "\" bandwidth=\"" + bandwidth + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        // Every Meta name answers a private address, so each save is refused before it connects.
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        av1Before = ReflectionHelpers.getStaticField(DashSave.class, "canWriteAv1");
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", Boolean.TRUE);
        // Robolectric has no codecs. The S22 decodes VP9 and AV1 and encodes H.264 at 1080p.
        VideoConvert.convertibleForTests = track -> true;
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        VideoConvert.convertibleForTests = null;
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", av1Before);
        MediaSave.policyForTests = null;
        Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        Settings.DOWNLOAD_CONVERT.resetToDefault();
        Settings.DOWNLOAD_NO_AV1.resetToDefault();
        Settings.DOWNLOAD_NO_HDR.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        PauseForTests.resume();
        LogBufferManager.clearLogBuffer();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaSave.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** The report after one video save of [manifest] beside the single files [files]. */
    private String save(boolean compatible, boolean convert, String manifest, String... files)
            throws InterruptedException {
        Settings.DOWNLOAD_COMPATIBLE.save(compatible);
        Settings.DOWNLOAD_CONVERT.save(convert);
        LogBufferManager.clearLogBuffer();
        assertTrue(MediaSave.saveVideo(context, SavesForTests.renditions(files), manifest, null));
        waitForSaves();
        return LogBufferManager.buildExportText();
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse(Settings.DOWNLOAD_CONVERT.get());
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        assertFalse(MediaSave.convertSaves());
    }

    /** The reel in #236: the AV1 1080p is converted, and if that fails the H.264 720p is saved instead. */
    @Test
    public void theFullSizeAv1IsConvertedOverTheSmallerH264() throws Exception {
        String report = save(true, true, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + FULL_AV1
                + ", instead of mp4 (360p)" + CONVERTED + "\n"));
        assertFalse(report, report.contains(KEPT));
        assertFalse(report, report.contains("in a format other apps can open"));
        // Every save here is refused before it connects, so the fallback shows in the report.
        assertTrue(report, report.contains("saving the tracks as they are: " + H264));
    }

    /**
     * Never save AV1 video (#379): with it on, the AV1 1080p is passed over for the H.264 720p even
     * on a phone that decodes AV1, the report says the switch did it rather than calling the save
     * lower than the manifest's best, and Convert to keep the full size leaves it alone too, saying so.
     */
    @Test
    public void theAv1SwitchPassesAv1OverOnAPhoneThatDecodesIt() throws Exception {
        assertFalse(Settings.DOWNLOAD_NO_AV1.get());
        assertFalse(MediaSave.skipsAv1());
        String control = save(false, false, MANIFEST, SD);
        assertTrue(control, control.contains("saving the video from its DASH manifest: " + FULL_AV1
                + ", instead of mp4 (360p)\n"));

        Settings.DOWNLOAD_NO_AV1.save(true);
        assertTrue(MediaSave.skipsAv1());
        String report = save(false, false, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264
                + ", instead of mp4 (360p), passing over the manifest's " + AV1_TRACK
                + " as Never save AV1 video is on\n"));
        assertFalse(report, report.contains(FULL_AV1));
        assertFalse("a choice isn't a shortfall", report.contains("below the manifest's " + AV1_TRACK));
        // The refused DASH save falls back to the single file, which is below the H.264 the manifest offers.
        assertTrue(report, report.contains("below the manifest's " + H264.substring(0, H264.indexOf(" + "))));

        report = save(true, true, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264));
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains(": Never save AV1 video is on"));

        report = save(false, false, AV1_ONLY, SD);
        assertTrue(report, report.contains("has no track to save with Never save AV1 video on: "));
        assertTrue(report, report.contains("saving video mp4 (360p)"));
        assertFalse("a choice isn't a shortfall", report.contains("below the manifest's " + AV1_TRACK));

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, the switch is off", MediaSave.skipsAv1());
    }

    /** Details shows what the switch leaves: the H.264 720p, or the single file of an AV1-only manifest. */
    @Test
    public void detailsShowsThePictureTheAv1SwitchLeaves() {
        Settings.DOWNLOAD_NO_AV1.save(true);
        MediaSave.Planned planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), MANIFEST);
        assertEquals(720, planned.width());
        assertEquals("avc1.64001f", planned.joined.codecs);

        planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), AV1_ONLY);
        assertNull("the single file", planned.joined);
        assertEquals(SD, planned.file.url);

        Settings.DOWNLOAD_NO_AV1.save(false);
        planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), MANIFEST);
        assertEquals(1080, planned.width());
    }

    /**
     * Never save HDR video (#352): with it on, the HDR 1080p is passed over for the largest standard
     * range picture, the report says the switch did it rather than calling the save lower than the
     * manifest's best, Convert to keep the full size leaves the HDR picture alone and says so, and a
     * manifest with HDR pictures only falls back to the single file.
     */
    @Test
    public void theHdrSwitchPassesHdrOverForTheLargestSdrPicture() throws Exception {
        assertFalse(Settings.DOWNLOAD_NO_HDR.get());
        assertFalse(MediaSave.skipsHdr());
        String control = save(false, false, HDR_MANIFEST, SD);
        assertTrue(control, control.contains("saving the video from its DASH manifest: " + FULL_HDR
                + ", instead of mp4 (360p)\n"));

        Settings.DOWNLOAD_NO_HDR.save(true);
        assertTrue(MediaSave.skipsHdr());
        String report = save(false, false, HDR_MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264
                + ", instead of mp4 (360p), passing over the manifest's " + HDR_TRACK
                + " as Never save HDR video is on\n"));
        assertFalse(report, report.contains(FULL_HDR));
        assertFalse("a choice isn't a shortfall", report.contains("below the manifest's " + HDR_TRACK));
        // The refused DASH save falls back to the single file, which is below the H.264 the manifest offers.
        assertTrue(report, report.contains("below the manifest's " + H264.substring(0, H264.indexOf(" + "))));

        report = save(true, true, HDR_MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264));
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains(": Never save HDR video is on"));

        report = save(false, false, HDR_ONLY, SD);
        assertTrue(report, report.contains("has no track to save with Never save HDR video on: "));
        assertTrue(report, report.contains("saving video mp4 (360p)"));
        assertFalse("a choice isn't a shortfall", report.contains("below the manifest's " + HDR_TRACK));

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, the switch is off", MediaSave.skipsHdr());
    }

    /** Details shows what the switch leaves: the H.264 720p, or the single file of an HDR-only manifest. */
    @Test
    public void detailsShowsThePictureTheHdrSwitchLeaves() {
        Settings.DOWNLOAD_NO_HDR.save(true);
        MediaSave.Planned planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), HDR_MANIFEST);
        assertEquals(720, planned.width());
        assertEquals("avc1.64001f", planned.joined.codecs);
        assertFalse(planned.joined.hdr);

        planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), HDR_ONLY);
        assertNull("the single file", planned.joined);
        assertEquals(SD, planned.file.url);

        Settings.DOWNLOAD_NO_HDR.save(false);
        planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), HDR_MANIFEST);
        assertEquals(1080, planned.width());
        assertTrue(planned.joined.hdr);
    }

    /** Details shows the picture a Download saves: the full size it converts. */
    @Test
    public void detailsShowsThePictureItConverts() {
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        Settings.DOWNLOAD_CONVERT.save(true);
        MediaSave.Planned planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), MANIFEST);
        assertEquals(1080, planned.width());
        assertEquals(AV1, planned.joined.codecs);

        Settings.DOWNLOAD_CONVERT.save(false);
        planned = MediaSave.plannedVideo(SavesForTests.renditions(SD), MANIFEST);
        assertEquals(720, planned.width());
    }

    /**
     * #236: Convert on with Save videos other apps can open left off saved the AV1 as it was, so
     * the switch looked like it did nothing. It converts on its own now.
     */
    @Test
    public void convertAloneConvertsTheFullSize() throws Exception {
        String report = save(false, true, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + FULL_AV1
                + ", instead of mp4 (360p)" + CONVERTED + "\n"));
        assertFalse(report, report.contains(KEPT));
        assertTrue(MediaSave.compatibleSaves());
    }

    /** Off, or a phone that can't convert: nothing is converted. */
    @Test
    public void nothingIsConvertedWithoutTheSwitchAndCodecs() throws Exception {
        String report = save(true, false, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264));
        assertTrue(report, report.contains(KEPT));
        assertFalse(report, report.contains(CONVERTED));

        VideoConvert.convertibleForTests = track -> false;
        report = save(true, true, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264));
        assertFalse(report, report.contains(CONVERTED));
    }

    /** At 720p the H.264 720p already fits, so there's nothing to gain from a conversion. */
    @Test
    public void belowTheBestQualityTheH264ThatFitsIsKept() throws Exception {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        String report = save(true, true, MANIFEST, SD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + H264));
        assertFalse(report, report.contains(CONVERTED));
    }

    /**
     * With no H.264 picture, the conversion has to beat the single file. A 720p file is beaten and
     * saved when the conversion fails. A 1080p file ties, and the single file wins, one fetch and no
     * work on the phone. With no single file at all, the AV1 is converted, and joined as it is if that fails.
     */
    @Test
    public void withNoH264TheConversionHasToBeatTheSingleFile() throws Exception {
        String report = save(true, true, AV1_ONLY, HD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + FULL_AV1
                + ", instead of mp4 (720p)" + CONVERTED + "\n"));
        assertTrue(report, report.contains(", saving mp4 (720p)"));
        assertFalse(report, report.contains("saving the tracks as they are"));

        report = save(true, true, AV1_ONLY, FULL);
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains("has no H.264 video with AAC-LC or HE-AAC sound, saving the single file instead"));

        report = save(true, true, AV1_ONLY);
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + FULL_AV1
                + ", instead of nothing" + CONVERTED + "\n"));
        assertFalse(report, report.contains("in a format other apps can open"));
        // Its fallback is the same tracks, so a failed save doesn't fetch them again.
        assertFalse(report, report.contains("saving the tracks as they are"));
    }

    /**
     * The reel the S22 found (#236): its 1080p only as AV1 and its only DASH sound xHE-AAC, beside a
     * 720p single file. The AV1 is converted and joined with the single file's sound, the sound a
     * save of that file keeps anyway. Refused before it connects here, it saves the single file.
     */
    @Test
    public void withOnlyXheAacSoundTheConvertedPictureTakesTheSingleFilesSound() throws Exception {
        String xheOnly = manifest(representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080"),
                sound("mp4a.40.42", 84_000, "xhe"));
        String report = save(true, true, xheOnly, HD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + AV1
                + " 1080x1920 1525kbps 1080p + the sound of mp4 (720p), instead of mp4 (720p)" + CONVERTED
                + ", the manifest's sound being only xHE-AAC\n"));
        assertFalse(report, report.contains("saving the single file instead"));
        assertTrue(report, report.contains(", saving mp4 (720p)"));

        MediaSave.DashChoice choice = MediaSave.DashChoice.of(DashManifest.parse(xheOnly), SavesForTests.renditions(HD),
                DownloadQuality.BEST, true, true, track -> true);
        assertTrue(choice.converts);
        assertTrue(choice.soundFromFile);
        assertNull("the xHE-AAC track isn't joined", choice.taken.audio);
        choice = MediaSave.DashChoice.of(DashManifest.parse(MANIFEST), SavesForTests.renditions(SD),
                DownloadQuality.BEST, true, true, track -> true);
        assertFalse("the manifest's AAC-LC is used where there is one", choice.soundFromFile);

        // A 1080p single file ties, and is saved whole as before.
        report = save(true, true, xheOnly, FULL);
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains("saving the single file instead"));
    }

    /**
     * #242: the same reel with both switches off. A default save would mux the manifest's only
     * sound, xHE-AAC, which many gallery apps play silent. The best picture is kept as it is and
     * joined with the 720p file's AAC sound, no transcode and no new switch. Refused before it
     * connects here, it falls back to the single file, which carries that same sound anyway.
     */
    @Test
    public void defaultSavesTakeTheFilesSoundWhenTheManifestsIsOnlyXheAac() throws Exception {
        String xheOnly = manifest(representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080"),
                sound("mp4a.40.42", 84_000, "xhe"));
        String report = save(false, false, xheOnly, HD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + AV1
                + " 1080x1920 1525kbps 1080p + the sound of mp4 (720p), instead of mp4 (720p)"
                + ", the manifest's sound being only xHE-AAC\n"));
        assertFalse(report, report.contains(CONVERTED));
        assertFalse(report, report.contains(KEPT));
        assertFalse(report, report.contains("in a format other apps can open"));
        assertTrue(report, report.contains(", saving mp4 (720p)"));

        MediaSave.DashChoice choice = MediaSave.DashChoice.of(DashManifest.parse(xheOnly),
                SavesForTests.renditions(HD), DownloadQuality.BEST, false, true);
        assertFalse("the picture is kept as it is, not converted", choice.converts);
        assertTrue(choice.soundFromFile);
        assertNull("the xHE-AAC track isn't joined", choice.taken.audio);
        assertEquals(AV1, choice.taken.video.codecs);
        assertTrue("a single file is the sound source", choice.fallback != null);

        // A manifest that offers AAC-LC takes that, not the file's sound.
        choice = MediaSave.DashChoice.of(DashManifest.parse(MANIFEST), SavesForTests.renditions(SD),
                DownloadQuality.BEST, false, true);
        assertFalse("the manifest's AAC-LC is used where there is one", choice.soundFromFile);

        // A 1080p single file ties, so the default saves it whole, as it always did.
        report = save(false, false, xheOnly, FULL);
        assertFalse(report, report.contains("+ the sound of"));
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains("saving video mp4 (1080p)"));
    }

    /**
     * A conversion keeps the sound as it is, so with no single file to take the sound from, a
     * picture whose only sound is xHE-AAC isn't converted.
     */
    @Test
    public void aPictureWithOnlyXheAacSoundIsntConverted() throws Exception {
        String xheOnly = manifest(representation(AV1, 1080, 1920, 1_525_000, "1080p", "v1080"),
                sound("mp4a.40.42", 84_000, "xhe"));
        String report = save(true, true, xheOnly);
        assertFalse(report, report.contains(CONVERTED));
        assertTrue(report, report.contains("nothing of the video is in a format other apps can open"));

        assertNull(DashManifest.pickToConvert(DashManifest.parse(xheOnly), true, DownloadQuality.BEST, track -> true));
        List<DashManifest.Track> tracks = DashManifest.parse(MANIFEST);
        DashManifest.Pick pick = DashManifest.pickToConvert(tracks, true, DownloadQuality.BEST, track -> true);
        assertEquals(AV1, pick.video.codecs);
        assertEquals("mp4a.40.2", pick.audio.codecs);
        assertNull("an H.264 picture was offered for conversion",
                DashManifest.pickToConvert(DashManifest.parse(manifest(
                        representation("avc1.64001f", 720, 1280, 1_200_000, "720p", "v720"),
                        sound("mp4a.40.2", 64_000, "lc"))), true, DownloadQuality.BEST, track -> true));
    }

    /**
     * A video story as #236's reporter gets them: its 1080p only as VP9 and its only DASH sound
     * xHE-AAC, beside a 720p single file. The VP9 is converted like the AV1 is.
     */
    @Test
    public void aVp9StoryIsConvertedLikeAnAv1One() throws Exception {
        String vp9 = "vp09.00.40.08.01.01.01.01.00";
        String story = manifest(representation(vp9, 1080, 1920, 1_525_000, "1080p", "v1080"),
                sound("mp4a.40.42", 84_000, "xhe"));
        String report = save(true, true, story, HD);
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + vp9
                + " 1080x1920 1525kbps 1080p + the sound of mp4 (720p), instead of mp4 (720p)" + CONVERTED));
        assertFalse(report, report.contains("not converting"));
    }

    /** A save that converts says in the report why it passed the largest VP9 or AV1 picture over (#236). */
    @Test
    public void theReportSaysWhyNothingWasConverted() throws Exception {
        VideoConvert.convertibleForTests = track -> false;
        String report = save(true, true, MANIFEST, SD);
        assertTrue(report, report.contains("not converting the video's video/mp4 " + AV1
                + " 1080x1920 1525kbps 1080p: this phone has no AV1 decoder and H.264 encoder for 1080x1920"));

        VideoConvert.convertibleForTests = track -> true;
        report = save(true, true, AV1_ONLY, FULL);
        assertTrue(report, report.contains("not converting the video's video/mp4 " + AV1
                + " 1080x1920 1525kbps 1080p: what's saved suits the quality as well"));

        report = save(true, false, MANIFEST, SD);
        assertFalse("only a save that converts says so", report.contains("not converting"));

        VideoConvert.convertibleForTests = track -> false;
        String url = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/v.mp4?oh=1&oe=2";
        assertEquals("it's HDR", VideoConvert.whyNot(
                new DashManifest.Track("video/mp4", "vp09.02.10.10.01.09.16.09.01", 1080, 1920, 1, url), true));
        assertEquals("its VP9 isn't in an MP4", VideoConvert.whyNot(
                new DashManifest.Track("video/webm", "vp09.00.40.08", 1080, 1920, 1, url), true));
        assertEquals("this phone can't read AV1 tracks", VideoConvert.whyNot(
                new DashManifest.Track("video/mp4", AV1, 1080, 1920, 1, url), false));
        VideoConvert.convertibleForTests = track -> true;
        assertNull(VideoConvert.whyNot(new DashManifest.Track("video/mp4", "vp09.00.40.08", 1080, 1920, 1, url), true));
    }

    /** Before the settings can be read, and while paused, nothing is converted. */
    @Test
    public void beforeTheSettingsAreReadyOrPausedNothingIsConverted() {
        Settings.DOWNLOAD_COMPATIBLE.save(true);
        Settings.DOWNLOAD_CONVERT.save(true);
        assertTrue(MediaSave.convertSaves());
        SettingsContextRule.withoutContext(() -> assertFalse(MediaSave.convertSaves()));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(MediaSave.convertSaves());
        PauseForTests.resume();
        assertTrue(MediaSave.convertSaves());
    }
}
