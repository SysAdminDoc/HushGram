/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;

import java.net.InetAddress;
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
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A photo post with music in the feed saved its picture straight away (#71): Download in the post's
 * menu, the hold and the Download button now ask Download as video or Download as photo, as the
 * Reels viewer does, and a carousel page takes the post's music when it has none of its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = FeedMusicDownloadTest.Bridges.class)
public class FeedMusicDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";
    private static final String TRACK = "https://scontent.cdninstagram.com/o1/v/t2/f2/m69/track.mp4";

    private final VideoDownload.MusicChoice choice = VideoDownload.musicChoice;
    private final List<PostDetails> started = Collections.synchronizedList(new ArrayList<>());
    private Activity activity;
    private int asked;
    private Runnable video;
    private Runnable photo;

    /** One post or page as the bridges read it. */
    static final class Item {
        final String id;
        final String video;
        final String track;

        Item(String id, String video, String track) {
            this.id = id;
            this.video = video;
            this.track = track;
        }
    }

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("10.9.8.7")});
        MediaSave.detailsForTests = started::add;
        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.DOWNLOAD_VIDEOS.save(true);
        VideoDownload.musicChoice = (screen, video, photo) -> {
            asked++;
            this.video = video;
            this.photo = photo;
        };
        HookStatus.clear();
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        VideoDownload.musicChoice = choice;
        MediaSave.policyForTests = null;
        MediaSave.detailsForTests = null;
        Bridges.post = null;
        Bridges.pages = null;
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        HookStatus.clear();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private static Item single(String track) {
        Item post = new Item("post", null, track);
        Bridges.post = post;
        return post;
    }

    @Test
    public void downloadOnAPhotoWithMusicAsksBeforeSavingAnything() throws InterruptedException {
        Item post = single(TRACK);

        assertTrue("the tap is taken from Instagram", VideoDownload.save(post, null, activity));
        assertEquals("asked once", 1, asked);
        waitForSaves();
        assertTrue("nothing saved before the answer", started.isEmpty());
        assertTrue(report(), report().contains(VideoDownload.MUSIC_ASKED + " 1"));

        photo.run();
        waitForSaves();
        assertEquals("Download as photo saves the picture", 1, started.size());
        assertFalse(report(), report().contains(VideoDownload.SAVED_AS_VIDEO));

        video.run();
        waitForSaves();
        assertEquals("Download as video saves too", 2, started.size());
        assertTrue(report(), report().contains(VideoDownload.SAVED_AS_VIDEO + " 1"));
    }

    @Test
    public void aPhotoWithoutMusicSavesOnTheTap() throws InterruptedException {
        Item post = single(null);

        assertTrue(VideoDownload.save(post, null, activity));
        waitForSaves();
        assertEquals("not asked", 0, asked);
        assertEquals(1, started.size());
        assertFalse(VideoDownload.music(post, null));
    }

    /** The button asks on its own, so its photo answer saves without a second question. */
    @Test
    public void theButtonsPhotoAnswerSavesWithoutAskingAgain() throws InterruptedException {
        Item post = single(TRACK);
        assertTrue(VideoDownload.music(post, null));

        assertTrue(VideoDownload.saveNow(post, null, activity));
        waitForSaves();
        assertEquals(0, asked);
        assertEquals(1, started.size());

        assertTrue(VideoDownload.saveAsVideo(post, null, activity));
        waitForSaves();
        assertTrue(report(), report().contains(VideoDownload.SAVED_AS_VIDEO + " 1"));
    }

    /** A carousel page with no music of its own takes the post's, and a page's own wins. */
    @Test
    public void aCarouselPageTakesThePostsMusic() throws InterruptedException {
        Item post = new Item("post", null, TRACK);
        Item plain = new Item("1", null, null);
        Item own = new Item("2", null, TRACK + "?own");
        Item clip = new Item("3", META + "3.mp4", null);
        Bridges.post = post;
        Bridges.pages = Arrays.asList(plain, own, clip);

        assertTrue(VideoDownload.music(post, 0));
        assertEquals(TRACK, VideoDownload.musicOf(plain, post).url);
        assertEquals(TRACK + "?own", VideoDownload.musicOf(own, post).url);
        assertFalse("a page with a video saves its video", VideoDownload.music(post, 2));

        assertTrue(VideoDownload.save(post, 0, activity));
        assertEquals(1, asked);
        video.run();
        waitForSaves();
        assertTrue(report(), report().contains(VideoDownload.SAVED_AS_VIDEO + " 1"));
        assertEquals("the page's own id", "1", started.get(0).videoId);
    }

    /** With the photo switch off a photo with music is Instagram's, as any photo is. */
    @Test
    public void thePhotoSwitchOffLeavesItToInstagram() {
        Settings.DOWNLOAD_PHOTOS.save(false);
        Item post = single(TRACK);

        assertFalse(VideoDownload.save(post, null, activity));
        assertFalse(VideoDownload.music(post, null));
        assertEquals(0, asked);
    }

    /** The real question: Download as video, then Download as photo, over the menu's screen. */
    @Test
    public void theQuestionListsVideoThenPhoto() {
        List<String> ran = new ArrayList<>();
        choice.ask(activity, () -> ran.add("video"), () -> ran.add("photo"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no question was shown", dialog);
        CharSequence[] items = Shadows.shadowOf(dialog).getItems();
        assertEquals(2, items.length);
        assertEquals("Download as video", items[0].toString());
        assertEquals("Download as photo", items[1].toString());
        Shadows.shadowOf(dialog).clickOnItem(1);
        assertEquals(Collections.singletonList("photo"), ran);

        choice.ask(null, () -> ran.add("video"), () -> ran.add("photo"));
        assertEquals("with no screen to ask on, the photo saves as before", Arrays.asList("photo", "photo"), ran);
        assertNull(VideoDownload.musicOf(null, null));
    }

    /** The bridges over {@link Item}: [post] is the post, [pages] its carousel pages when it has some. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Bridges {
        static Item post;
        static List<?> pages;

        @Implementation protected static List<?> carouselMedia(Object media) { return media == post ? pages : null; }
        @Implementation protected static int carouselIndex(Object itemState) { return itemState instanceof Integer ? (Integer) itemState : -1; }
        @Implementation protected static List<?> videoVersions(Object media) {
            return media instanceof Item && ((Item) media).video != null ? List.of(((Item) media).video) : null;
        }
        @Implementation protected static String dashManifest(Object media) { return null; }
        @Implementation protected static String versionUrl(Object version) { return (String) version; }
        @Implementation protected static Integer versionWidth(Object version) { return 720; }
        @Implementation protected static Integer versionHeight(Object version) { return 1280; }
        @Implementation protected static Object imageVersions(Object media) { return media; }
        @Implementation protected static List<?> imageCandidates(Object versions) {
            return versions instanceof Item ? List.of(META + ((Item) versions).id + ".jpg") : null;
        }
        @Implementation protected static String candidateUrl(Object candidate) { return (String) candidate; }
        @Implementation protected static int candidateWidth(Object candidate) { return 1080; }
        @Implementation protected static int candidateHeight(Object candidate) { return 1350; }
        @Implementation protected static Object musicMetadata(Object media) { return media instanceof Item ? ((Item) media).track : null; }
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
        @Implementation protected static String mediaId(Object media) { return media instanceof Item ? ((Item) media).id : null; }
    }
}
