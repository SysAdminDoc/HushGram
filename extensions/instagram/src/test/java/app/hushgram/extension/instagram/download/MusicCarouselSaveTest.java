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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download as video on a music carousel in the Reels viewer saved its first page only (#78). Every
 * page saves now, in order: a page with a video of its own as that file, a photo page built into a
 * video with its music, and a page with no music to fetch as its photo.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = MusicCarouselSaveTest.Pages.class)
public class MusicCarouselSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";
    private static final String TRACK = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/track.mp4";

    /** One page as the bridges read it. */
    static final class Page {
        final String id;
        final String picture;
        final String video;
        final String track;

        Page(String id, String picture, String video, String track) {
            this.id = id;
            this.picture = picture;
            this.video = video;
            this.track = track;
        }
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        MediaSave.policyForTests = null;
        Pages.post = null;
        Pages.pages = null;
        Pages.single = null;
        Pages.postTrack = null;
        HookStatus.clear();
        Settings.DOWNLOAD_REELS.save(true);
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    private static Page photo(String id, String track) {
        return new Page(id, META + id + ".jpg", null, track);
    }

    /** A page with its own video, as a photo rendered with the song would come. */
    private static Page rendered(String id) {
        return new Page(id, META + id + ".jpg", META + id + ".mp4", null);
    }

    private static void carousel(String postTrack, Page... pages) {
        Pages.post = new Object();
        Pages.pages = Arrays.asList(pages);
        Pages.postTrack = postTrack;
    }

    private static void refuseEveryFetch() {
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("10.9.8.7")});
    }

    private static void assertBuilt(MediaSave.Item item, String url) {
        assertTrue("built as a video", item.video);
        assertEquals(url, item.music.url);
        assertTrue(item.picture.url.startsWith(META));
    }

    @Test
    public void everyPageIsReadInOrderWithItsOwnKind() {
        carousel(TRACK, photo("1", null), rendered("2"), photo("3", TRACK + "?own"), photo("4", null));
        HookStatus.clear();

        List<MediaSave.Item> items = ReelDownload.musicSnapshot(Pages.pages, Pages.post);

        assertEquals(4, items.size());
        assertBuilt(items.get(0), TRACK);
        assertTrue("a page with a video of its own keeps the file", items.get(1).video && items.get(1).music == null);
        assertEquals(META + "2.mp4", items.get(1).renditions.get(0).url);
        assertBuilt(items.get(2), TRACK + "?own");
        assertBuilt(items.get(3), TRACK);
        for (int index = 0; index < 4; index++) assertEquals("page " + (index + 1), index + 1, items.get(index).details.page);
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("music carousel pages saved as video 4"));
    }

    @Test
    public void aPageWithNoMusicToFetchSavesItsPhoto() {
        carousel(null, photo("1", null), photo("2", TRACK));
        HookStatus.clear();

        List<MediaSave.Item> items = ReelDownload.musicSnapshot(Pages.pages, Pages.post);

        assertFalse(items.get(0).video);
        assertNull(items.get(0).music);
        assertEquals(META + "1.jpg", items.get(0).renditions.get(0).url);
        assertEquals(TRACK, items.get(1).music.url);
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("saved as photo 1"));
        assertTrue(report, report.contains("music carousel pages saved as video 1"));
    }

    @Test
    public void aMissingPageStaysAGapAndOneWithNothingToSaveIsKept() {
        Pages.post = new Object();
        Pages.postTrack = TRACK;
        List<Object> list = Arrays.asList(photo("1", null), null, new Object());

        List<MediaSave.Item> items = ReelDownload.musicSnapshot(list, Pages.post);

        assertEquals(3, items.size());
        assertNull(items.get(1));
        assertTrue("it fails on the worker rather than disappearing", items.get(2).renditions.isEmpty());
    }

    /** The tap on Download as video starts one batch over every page, and each page is tried. */
    @Test
    public void downloadAsVideoTriesEveryPageOfTheCarousel() throws InterruptedException {
        carousel(TRACK, photo("1", null), rendered("2"), photo("3", null));
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.saveAsVideo(activity, Pages.post));
        waitForSaves();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved 0. Failed 3. Skipped 0.", String.valueOf(ShadowToast.getTextOfLatestToast()));
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("carousel 1"));
        assertTrue(report, report.contains("music carousel pages saved as video 3"));
        assertFalse(report, report.contains("saved as video 1"));
    }

    /** A single photo with music still builds its one video. */
    @Test
    public void aSinglePhotoStillSavesOneVideo() throws InterruptedException {
        Pages.post = new Object();
        Pages.single = photo("9", null);
        Pages.postTrack = TRACK;
        refuseEveryFetch();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HookStatus.clear();

        assertTrue(ReelDownload.saveAsVideo(activity, Pages.post));
        waitForSaves();

        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("saved as video 1"));
        assertFalse(report, report.contains("music carousel"));
    }

    /** The bridges over {@link Page}: the post is the carousel, its pages are the list. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Pages {
        static Object post;
        static List<?> pages;
        static Page single;
        static String postTrack;

        private static Page page(Object media) {
            return media instanceof Page ? (Page) media : media == post ? single : null;
        }

        @Implementation protected static List<?> carouselMedia(Object media) { return media == post ? pages : null; }
        @Implementation protected static List<?> videoVersions(Object media) {
            Page page = page(media);
            return page == null || page.video == null ? null : List.of(page.video);
        }
        @Implementation protected static String dashManifest(Object media) { return null; }
        @Implementation protected static String versionUrl(Object version) { return (String) version; }
        @Implementation protected static Integer versionWidth(Object version) { return 720; }
        @Implementation protected static Integer versionHeight(Object version) { return 1280; }
        @Implementation protected static Object imageVersions(Object media) {
            // A carousel's own picture is its first page's.
            return media == post && pages != null && !pages.isEmpty() ? pages.get(0) : page(media);
        }
        @Implementation protected static List<?> imageCandidates(Object versions) {
            return versions instanceof Page ? List.of(((Page) versions).picture) : null;
        }
        @Implementation protected static String candidateUrl(Object candidate) { return (String) candidate; }
        @Implementation protected static int candidateWidth(Object candidate) { return 1080; }
        @Implementation protected static int candidateHeight(Object candidate) { return 1350; }
        @Implementation protected static Object musicMetadata(Object media) {
            if (media == post) return postTrack;
            Page page = page(media);
            return page == null ? null : page.track;
        }
        @Implementation protected static Object metadataMusic(Object metadata) { return metadata; }
        @Implementation protected static Object clipsMetadata(Object media) { return null; }
        @Implementation protected static Object musicTrack(Object music) { return music; }
        @Implementation protected static Object musicConsumption(Object music) { return "part"; }
        @Implementation protected static String trackUrl(Object track) { return (String) track; }
        @Implementation protected static String trackFastStartUrl(Object track) { return null; }
        @Implementation protected static Integer musicStartMs(Object part) { return 0; }
        @Implementation protected static Integer musicLengthMs(Object part) { return 15_000; }
        @Implementation protected static Object owner(Object media) { return "poster"; }
        @Implementation protected static String username(Object user) { return (String) user; }
        @Implementation protected static Long takenAt(Object media) { return 1_700_000_000L; }
        @Implementation protected static String mediaId(Object media) { return media instanceof Page ? ((Page) media).id : "post"; }
    }
}
