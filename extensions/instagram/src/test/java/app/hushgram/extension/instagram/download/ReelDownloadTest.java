/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What the reel menu's hooks answer, and what a tap on Download does with them. */
@RunWith(RobolectricTestRunner.class)
public class ReelDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() throws InterruptedException {
        // A save a test started ends before the next test, so none of them holds a slot.
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        MediaSave.policyForTests = null;
        Item.videos = null;
        Item.manifest = null;
        Item.pictures = null;
        Item.music = false;
        Item.trackUrl = null;
        Item.fastStartUrl = null;
        Item.startMs = null;
        Item.lengthMs = null;
        Item.rows.clear();
        HookStatus.clear();
        Settings.DOWNLOAD_REELS.save(true);
        Settings.DOWNLOAD_REEL_COVER.resetToDefault();
    }

    /** With the switch on, every reel gets the row, whatever Instagram and its flag say. */
    @Test
    public void withTheSwitchOnEveryReelOffersDownload() {
        assertTrue(ReelDownload.offer(false));
        assertTrue(ReelDownload.offer(true));
        assertFalse(ReelDownload.withhold(true));
        assertFalse(ReelDownload.withhold(false));
    }

    /** Off, the menu is Instagram's own. Pause turns the switch off for the whole process, as it does every switch. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(ReelDownload.offer(false));
        assertTrue(ReelDownload.offer(true));
        assertTrue(ReelDownload.withhold(true));
        assertFalse(ReelDownload.withhold(false));
        assertFalse("a tap was taken from Instagram", ReelDownload.save(Option.DOWNLOAD, new Object(), null));
        List<Object> reduced = options(Option.PLAYBACK_CONTROLS, Option.REPORT);
        ReelDownload.addTo(reduced, Option.DOWNLOAD);
        assertEquals(options(Option.PLAYBACK_CONTROLS, Option.REPORT), reduced);
    }

    /** The patch hands Instagram's answers over as ints, since a boolean method may return one. Non-zero is yes. */
    @Test
    public void thePatchsIntEntriesReadNonZeroAsYes() {
        assertTrue(ReelDownload.offer(0));
        assertTrue(ReelDownload.offer(2));
        assertFalse(ReelDownload.withhold(1));
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("with the switch off, 0 became a yes", ReelDownload.offer(0));
        assertTrue("with the switch off, 1 lost Instagram's yes", ReelDownload.offer(1));
        assertTrue("2 is a yes too", ReelDownload.offer(2));
        assertFalse(ReelDownload.withhold(0));
        assertTrue("with the switch off, Instagram's flag lost its hold", ReelDownload.withhold(1));
        assertTrue(ReelDownload.withhold(2));
    }

    /**
     * The filters of a builder that hands Download to the adder of one row answer as offer() and
     * withhold() do while Open in another player is off, ints and all, and hold nothing back for the
     * adder.
     */
    @Test
    public void theAdderBuildersFiltersMatchThePlainOnesWithThePlayerOff() {
        assertTrue(ReelDownload.offerRow(0, new Object()));
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertFalse(ReelDownload.withholdRow(1));
        assertFalse(ReelDownload.withholdRow(0));
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse(ReelDownload.offerRow(0, new Object()));
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertTrue("2 is a yes too", ReelDownload.offerRow(2, new Object()));
        assertTrue(ReelDownload.withholdRow(1));
        assertFalse(ReelDownload.withholdRow(0));
        assertFalse("Instagram's own Download row stays", ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue(Item.rows.isEmpty());
    }

    /** Instagram's option names, as the reduced reel menu's list holds them. */
    private enum Option { SHOP_SIMILAR, SAVE, UNSAVE, PLAYBACK_CONTROLS, DOWNLOAD, WHY_AM_I_SEEING_THIS, REPORT }

    private static List<Object> options(Object... options) {
        return new ArrayList<>(Arrays.asList(options));
    }

    /** The reduced menu gets Download after its save rows, which is above Playback, and only once. */
    @Test
    public void theReducedMenuGetsDownloadAboveItsRows() {
        List<Object> plain = options(Option.PLAYBACK_CONTROLS, Option.WHY_AM_I_SEEING_THIS, Option.REPORT);
        ReelDownload.addTo(plain, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD, Option.PLAYBACK_CONTROLS, Option.WHY_AM_I_SEEING_THIS, Option.REPORT), plain);

        List<Object> saved = options(Option.SHOP_SIMILAR, Option.UNSAVE, Option.PLAYBACK_CONTROLS, Option.REPORT);
        ReelDownload.addTo(saved, Option.DOWNLOAD);
        assertEquals(options(Option.SHOP_SIMILAR, Option.UNSAVE, Option.DOWNLOAD, Option.PLAYBACK_CONTROLS, Option.REPORT), saved);

        List<Object> already = options(Option.DOWNLOAD, Option.REPORT);
        ReelDownload.addTo(already, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD, Option.REPORT), already);

        List<Object> empty = options();
        ReelDownload.addTo(empty, Option.DOWNLOAD);
        assertEquals(options(Option.DOWNLOAD), empty);
        ReelDownload.addTo(null, Option.DOWNLOAD);
    }

    /**
     * A tap with the switch on is HushGram's, even when the reel gives nothing to save: an unpatched
     * build's bridges answer nothing, so the save can't start, and the toast says so rather than
     * Instagram's own download starting.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Option.DOWNLOAD, new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. Counted: no video versions 1, no manifest 1"),
                HookStatus.report());
    }

    /**
     * A photo the Reels viewer shows with its music has no video file and no manifest, only its
     * picture's sizes. A tap saves the picture rather than failing (#71), and the report says which
     * way it went.
     */
    @Test
    @Config(shadows = Item.class)
    public void aReelItemWithNoVideoSavesItsPicture() {
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0),
                new MediaSave.Rendition(META + "150.jpg", 150, 150, 0));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Option.DOWNLOAD, new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. "
                + "Counted: no video versions 1, no manifest 1, has image candidates 1, saved as photo 1"), HookStatus.report());
        assertTrue("the save didn't say it started", ShadowToast.showedToast("Saving...")
                || ShadowToast.showedToast("Saving... Cancel: Downloads in HushGram."));
    }

    /** A reel with a video saves the video, never the picture every video has as its cover. */
    @Test
    @Config(shadows = Item.class)
    public void aReelWithAVideoNeverSavesItsCover() {
        Item.videos = List.of(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Item.pictures = List.of(new MediaSave.Rendition(META + "cover.jpg", 720, 1280, 0));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Option.DOWNLOAD, new Object(), activity));

        assertEquals(List.of(FamilyNames.REEL_DOWNLOAD + ": invoked 1, 0 found, 0 missing. Counted: no manifest 1"),
                HookStatus.report());
    }

    /** The two options a photo with music gets, by the names the extension gives them. */
    private enum Ours { HUSHGRAM_DOWNLOAD_AS_VIDEO, HUSHGRAM_DOWNLOAD_AS_PHOTO, HUSHGRAM_DOWNLOAD_REEL, HUSHGRAM_DOWNLOAD_COVER }

    /** A photo the Reels viewer shows with music that has a track to fetch. */
    private static void photoWithMusic() {
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0));
        Item.music = true;
        Item.trackUrl = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/track.mp4";
        Item.startMs = 12_000;
        Item.lengthMs = 15_000;
    }

    /**
     * A photo with music gets Download as video and Download as photo in Download's place, in
     * that order, made by the bridges and labeled as the story menu labels them.
     */
    @Test
    @Config(shadows = Item.class)
    public void aPhotoWithMusicGetsTwoRows() {
        photoWithMusic();

        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));

        assertEquals(List.of("HUSHGRAM_DOWNLOAD_AS_VIDEO=Download as video", "HUSHGRAM_DOWNLOAD_AS_PHOTO=Download as photo"),
                Item.rows);
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains("has music 1"));
    }

    /** A video, a photo without music, music with no track to fetch, nothing at all, or the switch off: one Download row. */
    @Test
    @Config(shadows = Item.class)
    public void anythingElseKeepsTheOneRow() {
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0));
        assertFalse("a photo without music", ReelDownload.rows(null, new Object(), null, null, null));

        photoWithMusic();
        Item.videos = List.of(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        assertFalse("a video", ReelDownload.rows(null, new Object(), null, null, null));
        Item.videos = null;

        Item.trackUrl = null;
        assertFalse("music with no track", ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains("no audio url 1"));

        Item.fastStartUrl = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/fast.mp4";
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("the switch off", ReelDownload.rows(null, new Object(), null, null, null));
        Settings.DOWNLOAD_REELS.save(true);

        Item.pictures = null;
        assertFalse("no picture", ReelDownload.rows(null, new Object(), null, null, null));
        assertFalse("no media", ReelDownload.rows(null, null, null, null, null));
        assertTrue("a row went in", Item.rows.isEmpty());
    }

    /** The handler knows the two rows by name, and nothing else as ours. */
    @Test
    public void theHandlerKnowsOurRows() {
        assertTrue(ReelDownload.ours(Ours.HUSHGRAM_DOWNLOAD_AS_VIDEO));
        assertTrue(ReelDownload.ours(Ours.HUSHGRAM_DOWNLOAD_AS_PHOTO));
        assertTrue(ReelDownload.ours(Ours.HUSHGRAM_DOWNLOAD_REEL));
        assertTrue(ReelDownload.ours(Ours.HUSHGRAM_DOWNLOAD_COVER));
        assertFalse(ReelDownload.ours(Option.DOWNLOAD));
        assertFalse(ReelDownload.ours("HUSHGRAM_DOWNLOAD_AS_VIDEO"));
        assertFalse(ReelDownload.ours(null));
    }

    /**
     * A tap on one of our rows never goes on to Instagram, which doesn't know the option, even with
     * the switch turned off while the menu was open.
     */
    @Test
    public void ourRowsStayOursWithTheSwitchOff() {
        Settings.DOWNLOAD_REELS.save(false);
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_AS_VIDEO, new Object(), null));
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_AS_PHOTO, new Object(), null));
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_REEL, new Object(), null));
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_COVER, new Object(), null));
        assertFalse(ReelDownload.save(Option.DOWNLOAD, new Object(), null));
    }

    /**
     * With Download cover on, a reel with a video or a manifest and a picture gets Download and
     * Download cover in Download's place, in that order. Off, or with no picture, it keeps the one row.
     */
    @Test
    @Config(shadows = Item.class)
    public void aVideoReelGetsDownloadCoverWithItsSwitch() {
        Item.videos = List.of(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1920, 0));
        assertFalse("Download cover starts off", Settings.DOWNLOAD_REEL_COVER.get());
        assertFalse(ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue(Item.rows.isEmpty());

        Settings.DOWNLOAD_REEL_COVER.save(true);
        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(List.of("HUSHGRAM_DOWNLOAD_REEL=Download", "HUSHGRAM_DOWNLOAD_COVER=Download cover"), Item.rows);
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains("has cover 1"));

        Item.rows.clear();
        Item.videos = null;
        Item.manifest = "<MPD/>";
        assertTrue("a manifest alone", ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(2, Item.rows.size());

        Item.rows.clear();
        Item.pictures = null;
        assertFalse("no picture to save", ReelDownload.rows(null, new Object(), null, null, null));
        Settings.DOWNLOAD_REELS.save(false);
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1920, 0));
        assertFalse("the reel switch off", ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue(Item.rows.isEmpty());
    }

    /** Download cover saves the reel's picture, and our Download row saves its video. */
    @Test
    @Config(shadows = Item.class)
    public void downloadCoverSavesThePictureAndDownloadTheVideo() throws InterruptedException {
        Item.videos = List.of(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Item.pictures = List.of(new MediaSave.Rendition(META + "1080.jpg", 1080, 1920, 0),
                new MediaSave.Rendition(META + "640.jpg", 640, 1138, 0));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_COVER, new Object(), activity));
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("saved cover 1"));
        assertFalse(report, report.contains("saved as photo"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);

        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_REEL, new Object(), activity));
        report = String.valueOf(HookStatus.report());
        assertFalse("the reel row saved the cover", report.contains("saved cover 2"));
        assertFalse(report, report.contains("saved as photo"));
        assertFalse(report, report.contains("no video versions"));
    }

    /** Download as photo saves the picture of a photo with music. */
    @Test
    @Config(shadows = Item.class)
    public void downloadAsPhotoSavesThePicture() {
        photoWithMusic();
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_AS_PHOTO, new Object(), activity));

        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains("saved as photo 1"));
    }

    /** Download as video with music that has no track to fetch says so, and the toast says it failed. */
    @Test
    @Config(shadows = Item.class)
    public void downloadAsVideoWithNoTrackSaysSo() {
        photoWithMusic();
        Item.trackUrl = null;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_AS_VIDEO, new Object(), activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains("no audio url 1"));
    }

    /**
     * Download as video starts a save that fetches the picture first. A fetch that can't go out
     * ends it, counted, and leaves no file of its own behind in the work folder.
     */
    @Test
    @Config(shadows = Item.class)
    public void downloadAsVideoCleansUpAfterAFailedFetch() throws InterruptedException {
        photoWithMusic();
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.save(Ours.HUSHGRAM_DOWNLOAD_AS_VIDEO, new Object(), activity));
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);

        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("saved as video 1"));
        assertTrue(report, report.contains("picture fetch failed 1"));
        java.io.File[] left = DashSave.workFolder(activity.getApplicationContext()).listFiles();
        assertEquals("files left in the work folder", 0, left == null ? 0 : left.length);
    }

    /** An address on Meta's media servers, which the save takes. */
    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";

    /** Every lookup answers a private address, so a started save ends at once and nothing goes out. */
    private static void refuseEveryFetch() {
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("10.9.8.7")});
    }

    /** A reel item as the bridges read it: its video files, its manifest and its picture's sizes. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Item {
        static List<MediaSave.Rendition> videos;
        static String manifest;
        static List<MediaSave.Rendition> pictures;

        @Implementation protected static List<?> videoVersions(Object media) { return videos; }
        @Implementation protected static String dashManifest(Object media) { return manifest; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object imageVersions(Object media) { return pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static int candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static int candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }

        static boolean music;
        static String trackUrl;
        static String fastStartUrl;
        static Integer startMs;
        static Integer lengthMs;
        static final List<String> rows = new ArrayList<>();

        @Implementation protected static Object musicMetadata(Object media) { return music ? "metadata" : null; }
        @Implementation protected static Object metadataMusic(Object metadata) { return "music"; }
        @Implementation protected static Object clipsMetadata(Object media) { return null; }
        @Implementation protected static Object musicTrack(Object music) { return "track"; }
        @Implementation protected static Object musicConsumption(Object music) { return "part"; }
        @Implementation protected static String trackUrl(Object track) { return trackUrl; }
        @Implementation protected static String trackFastStartUrl(Object track) { return fastStartUrl; }
        @Implementation protected static Integer musicStartMs(Object part) { return startMs; }
        @Implementation protected static Integer musicLengthMs(Object part) { return lengthMs; }
        @Implementation protected static Object reelOption(String name) { return Ours.valueOf(name); }

        @Implementation
        protected static boolean addReelRow(Object menu, Object context, Object option, Object sheet, Object rowState, String label) {
            rows.add(option + "=" + label);
            return true;
        }
    }

    /** Without the bridges written, a reel has no files and no details, and neither read throws. */
    @Test
    public void anUnpatchedReelHasNothing() {
        Object reel = new Object();
        assertTrue(ReelDownload.renditions(reel).isEmpty());
        PostDetails details = ReelDownload.details(reel);
        assertFalse(details.hasVideoId());
        assertFalse(details.hasOwner());
        assertNull(details.posted);
    }
}
