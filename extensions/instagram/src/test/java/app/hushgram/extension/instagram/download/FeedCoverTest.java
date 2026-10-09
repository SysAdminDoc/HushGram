/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
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
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Download cover in the feed menu (#94): the row a post, or a carousel page on screen, with a
 * video gets with Download video covers on, its place in the short menu, and a tap with nothing
 * to save. The save itself is in CarouselSaveTest, which has a server to fetch from.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = FeedCoverTest.Post.class)
public class FeedCoverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.DOWNLOAD_VIDEOS.save(true);
    }

    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";

    @After
    public void tearDown() {
        Settings.DOWNLOAD_FEED_COVER.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.OPEN_IN_PLAYER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Post.videos = null;
        Post.manifest = null;
        Post.pictures = null;
        Post.pages = null;
        Post.itemState = null;
        Post.label = null;
        ShadowToast.reset();
        HookStatus.clear();
    }

    private static void aVideoWithACover() {
        Post.videos = Collections.singletonList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Post.manifest = null;
        Post.pictures = Arrays.asList(new MediaSave.Rendition(META + "1080.jpg", 1080, 1920, 0),
                new MediaSave.Rendition(META + "640.jpg", 640, 1138, 0));
    }

    /**
     * The switch starts off. On, with Download feed videos on, a post with a video and a picture
     * gets the row, made with the reel cover's option name and labelled Download cover. A photo
     * post, a video with no picture, the video switch off or a pause leaves the menu as it was.
     */
    @Test
    public void aFeedVideoGetsDownloadCoverWithItsSwitch() {
        aVideoWithACover();
        ArrayList<Object> rows = new ArrayList<>();
        assertFalse("Download video covers starts off", Settings.DOWNLOAD_FEED_COVER.get());
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("off", rows.isEmpty());

        Settings.DOWNLOAD_FEED_COVER.save(true);
        VideoDownload.offerCover(new Object(), rows);
        assertEquals(Collections.singletonList(VideoDownload.coverOption()), rows);
        assertEquals(ReelDownload.COVER_OPTION, VideoDownload.coverOption());
        assertEquals("Download cover", String.valueOf(Post.label));

        rows.clear();
        Post.videos = null;
        Post.manifest = "<MPD/>";
        VideoDownload.offerCover(new Object(), rows);
        assertEquals("a manifest alone", 1, rows.size());

        rows.clear();
        Post.manifest = null;
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("a photo post", rows.isEmpty());

        aVideoWithACover();
        Post.pictures = null;
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("no picture to save", rows.isEmpty());

        aVideoWithACover();
        Settings.DOWNLOAD_VIDEOS.save(false);
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("Download feed videos off", rows.isEmpty());

        Settings.DOWNLOAD_VIDEOS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("paused", rows.isEmpty());
    }

    /** A carousel gets the row while a video page is on screen, and not on a photo page. */
    @Test
    public void aCarouselGetsTheRowOnAVideoPage() {
        aVideoWithACover();
        Settings.DOWNLOAD_FEED_COVER.save(true);
        Post.pages = Arrays.asList(Post.PHOTO_PAGE, "video page");
        ArrayList<Object> rows = new ArrayList<>();

        Post.itemState = 0;
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("a photo page on screen", rows.isEmpty());

        Post.itemState = 1;
        VideoDownload.offerCover(new Object(), rows);
        assertEquals(Collections.singletonList(VideoDownload.coverOption()), rows);

        rows.clear();
        Post.itemState = 5;
        VideoDownload.offerCover(new Object(), rows);
        assertTrue("no such page", rows.isEmpty());
    }

    /**
     * The short feed menu keeps Download cover right after Download and Save all, ahead of Open
     * in another player. Off, or with Download feed videos off, it isn't there.
     */
    @Test
    public void theShortMenuKeepsDownloadCoverAfterTheSaves() {
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        Object all = VideoDownload.allOption();
        Settings.DOWNLOAD_FEED_COVER.save(true);
        Object cover = VideoDownload.coverOption();
        assertEquals(Arrays.asList("DOWNLOAD", all, cover, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        List<?> already = VideoDownload.allow(options, "DOWNLOAD");
        assertTrue("a list that has it comes back as it came", already == VideoDownload.allow(already, "DOWNLOAD"));

        Settings.OPEN_IN_PLAYER.save(true);
        assertEquals(Arrays.asList("DOWNLOAD", all, cover, VideoDownload.playerOption(), "WHY_AM_I_SEEING_THIS", "REPORT"),
                VideoDownload.allow(options, "DOWNLOAD"));

        Settings.OPEN_IN_PLAYER.save(false);
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals("Download feed videos off", options, VideoDownload.allow(options, "DOWNLOAD"));
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.DOWNLOAD_FEED_COVER.save(false);
        assertEquals(Arrays.asList("DOWNLOAD", all, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        assertEquals("the list Instagram made", Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT"), options);
    }

    /**
     * A tap on a post with no picture to save says the download failed and starts nothing. With
     * the switch turned off since the menu opened, a tap does nothing at all.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Post.videos = Collections.singletonList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        Settings.DOWNLOAD_FEED_COVER.save(true);
        VideoDownload.saveCover(new Object(), null, activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals(0, MediaSave.savesInFlight());

        ShadowToast.reset();
        aVideoWithACover();
        Settings.DOWNLOAD_FEED_COVER.save(false);
        VideoDownload.saveCover(new Object(), null, activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("off", 0, ShadowToast.shownToastCount());
        assertEquals(0, MediaSave.savesInFlight());
    }

    /** A post, or a carousel's pages, as the bridges read them, and the rows the menu's adder was handed. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Post {
        static final Object ALL = "SAVE_ALL";
        static final Object PHOTO_PAGE = "photo page";
        static List<MediaSave.Rendition> videos;
        static String manifest;
        static List<MediaSave.Rendition> pictures;
        static List<?> pages;
        static Object itemState;
        static CharSequence label;

        @Implementation protected static List<?> videoVersions(Object media) { return media == PHOTO_PAGE ? null : videos; }
        @Implementation protected static String dashManifest(Object media) { return media == PHOTO_PAGE ? null : manifest; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object imageVersions(Object media) { return pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static int candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static int candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }
        @Implementation protected static List<?> carouselMedia(Object media) { return pages; }
        @Implementation protected static int carouselIndex(Object state) { return (Integer) state; }
        @Implementation protected static Object feedOption(String name) { return name; }
        @Implementation protected static Object saveAllOption() { return ALL; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return menu; }
        @Implementation protected static Object feedMenuItemState(Object menu) { return itemState; }

        @Implementation
        protected static void addSaveAllRow(Object menu, ArrayList<Object> rows, Object option, CharSequence title) {
            rows.add(option);
            label = title;
        }
    }
}
