/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
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
 * Download cover in a feed post's menu: a video gets the row, a photo doesn't, and the short menu
 * keeps it under Download. The switch is the one Reels uses, so one answer covers both menus.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = CoverDownloadTest.Bridges.class)
public class CoverDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";

    @Before
    public void setUp() {
        Settings.DOWNLOAD_REEL_COVER.save(true);
        Bridges.label = null;
        ShadowToast.reset();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        Settings.DOWNLOAD_REEL_COVER.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.OPEN_IN_PLAYER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Bridges.label = null;
        ShadowToast.reset();
        HookStatus.clear();
    }

    /** A video post: a video file, and the cover picture Instagram shows before it plays. */
    private static Post video() {
        Post post = new Post();
        post.videos = Collections.singletonList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        post.pictures = Collections.singletonList(new MediaSave.Rendition(META + "cover.jpg", 1080, 1920, 0));
        return post;
    }

    /** A photo post: pictures only, no video at all. */
    private static Post photo() {
        Post post = new Post();
        post.pictures = Arrays.asList(new MediaSave.Rendition(META + "640.jpg", 640, 800, 0),
                new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0));
        return post;
    }

    /** A post that lists no picture to save as a cover, so there's nothing the row would save. */
    private static Post videoWithoutACover() {
        Post post = new Post();
        post.videos = Collections.singletonList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        return post;
    }

    @Test
    public void aVideoPostGetsTheRowUnderDownload() {
        ArrayList<Object> rows = new ArrayList<>();

        CoverDownload.offer(video(), rows);

        assertEquals(Arrays.asList(CoverDownload.OPTION), rows);
    }

    /** The row carries the same label the Reels menu uses, in the phone's language. */
    @Test
    public void theRowIsLabelledDownloadCover() {
        ArrayList<Object> rows = new ArrayList<>();

        CoverDownload.offer(video(), rows);

        assertEquals("Download cover", String.valueOf(Bridges.label));
    }

    @Test
    public void aPhotoPostDoesNotGetTheRow() {
        ArrayList<Object> rows = new ArrayList<>();

        CoverDownload.offer(photo(), rows);

        assertTrue("a photo's picture is what Download already saves", rows.isEmpty());
    }

    @Test
    public void aVideoWithNoCoverPictureDoesNotGetTheRow() {
        ArrayList<Object> rows = new ArrayList<>();

        CoverDownload.offer(videoWithoutACover(), rows);

        assertTrue("the row would have nothing to save", rows.isEmpty());
    }

    /** The switch is Reels', so turning it off leaves every video's menu as Instagram made it. */
    @Test
    public void theReelCoverSwitchGatesTheRow() {
        ArrayList<Object> rows = new ArrayList<>();
        Settings.DOWNLOAD_REEL_COVER.save(false);

        CoverDownload.offer(video(), rows);

        assertTrue(rows.isEmpty());
    }

    @Test
    public void aPauseAnswersTheSwitchOff() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ArrayList<Object> rows = new ArrayList<>();

        CoverDownload.offer(video(), rows);

        assertTrue("paused, the menu is Instagram's own", rows.isEmpty());
    }

    /** A null menu or row list, which Instagram's own paths can hand, changes nothing. */
    @Test
    public void nothingToOfferIsIgnored() {
        CoverDownload.offer(null, new ArrayList<>());
        CoverDownload.offer(video(), null);
    }

    /** The short feed menu keeps Download cover under Download, after Save all where that is too. */
    @Test
    public void theShortMenuKeepsTheRowUnderDownload() {
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        Object all = VideoDownload.allOption();

        // Save all and Download go in together, so Download cover follows Save all to stay under Download.
        assertEquals(Arrays.asList("DOWNLOAD", all, CoverDownload.option(), "WHY_AM_I_SEEING_THIS", "REPORT"),
                VideoDownload.allow(options, "DOWNLOAD"));
    }

    /** With Save all off, Download cover sits directly under Download. */
    @Test
    public void theShortMenuKeepsTheRowUnderDownloadWithoutSaveAll() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.DOWNLOAD_PHOTOS.save(false);
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");

        assertEquals(Arrays.asList(CoverDownload.option(), "WHY_AM_I_SEEING_THIS", "REPORT"),
                VideoDownload.allow(options, null));
    }

    /** A list that already keeps the row comes back as it came. */
    @Test
    public void theShortMenuKeepsOneRowOnlyOnce() {
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");

        List<?> once = VideoDownload.allow(options, "DOWNLOAD");

        assertTrue("a list that has it comes back as it came", once == VideoDownload.allow(once, "DOWNLOAD"));
    }

    /** With the switch off, the cover row is never added to the list the short menu keeps. */
    @Test
    public void theSwitchOffLeavesTheShortMenuWithoutTheRow() {
        Settings.DOWNLOAD_REEL_COVER.save(false);
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");

        List<?> allowed = VideoDownload.allow(options, "DOWNLOAD");

        assertFalse("no Download cover row", allowed.contains(CoverDownload.option()));
    }

    /** The row's option is a distinct one, made once, so Instagram's own Download isn't shadowed. */
    @Test
    public void theRowHasAnOptionOfItsOwn() {
        Object option = CoverDownload.option();

        assertFalse("the option isn't Instagram's Download", "DOWNLOAD".equals(option));
        assertEquals("the same option every time", option, CoverDownload.option());
    }

    /** A post as the bridges read it. */
    static final class Post {
        List<MediaSave.Rendition> videos;
        List<MediaSave.Rendition> pictures;
    }

    /** The bridges over [Post], with a menu that is its post. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Bridges {
        static CharSequence label;

        @Implementation protected static List<?> videoVersions(Object media) { return ((Post) media).videos; }
        @Implementation protected static Object imageVersions(Object media) { return ((Post) media).pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return ((Post) versions).pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static Integer candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static Integer candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object feedOption(String name) { return name; }
        @Implementation protected static Object saveAllOption() { return "SAVE_ALL"; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return menu; }
        @Implementation protected static Object feedMenuItemState(Object menu) { return null; }

        @Implementation
        protected static void addSaveAllRow(Object menu, ArrayList<Object> rows, Object option, CharSequence title) {
            rows.add(option);
            label = title;
        }
    }
}
