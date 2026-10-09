/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.text.SpannableString;

import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What the story menu's hooks answer, and what a tap on Download does with them. */
@RunWith(RobolectricTestRunner.class)
public class StoryDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        Story.media = null;
        Story.video = false;
        Story.manifest = null;
        Story.flagged = null;
        Story.posted = null;
        StoryDownload.building(null);
        HookStatus.clear();
        Settings.DOWNLOAD_STORIES.save(true);
    }

    /** With the switch on, Download goes at the end of any story's menu, and only once. */
    @Test
    public void withTheSwitchOnEveryStoryOffersDownload() {
        CharSequence[] menu = {"Report", "Mute"};
        assertArrayEquals(new CharSequence[] {"Report", "Mute", "Download"}, StoryDownload.labels(menu));

        CharSequence[] already = {"Report", new SpannableString("Download")};
        assertSame(already, StoryDownload.labels(already));

        assertArrayEquals(new CharSequence[] {"Download"}, StoryDownload.labels(new CharSequence[0]));
        assertNull(StoryDownload.labels(null));
    }

    /** Off, the menu and every tap are Instagram's own. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_STORIES.save(false);
        CharSequence[] menu = {"Report", "Mute"};
        assertSame(menu, StoryDownload.labels(menu));
        assertFalse("a tap was taken from Instagram", StoryDownload.save("Download", new Object()));
    }

    /** Any label but Download goes on to Instagram's handler. */
    @Test
    public void anotherLabelGoesOn() {
        assertFalse(StoryDownload.save("Report", new Object()));
        assertFalse(StoryDownload.save(null, new Object()));
    }

    /**
     * A tap on Download is HushGram's, even when the story gives nothing to save: an unpatched
     * build's bridges answer nothing, so the save can't start, and the toast says so. The label
     * counts however the menu styled it.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() {
        assertTrue(StoryDownload.save(new SpannableString("Download"), new Object()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    /** Without the bridges written, a story has no picture, and the read doesn't throw. */
    @Test
    public void anUnpatchedStoryHasNoPicture() {
        assertTrue(StoryDownload.pictures(new Object()).isEmpty());
    }

    /**
     * A photo story with music, which Instagram serves as a video, gets Download as video and
     * Download as photo instead of Download, once, and any other story keeps its one Download.
     */
    @Test
    public void aPhotoWithMusicOffersBothSaves() {
        CharSequence[] menu = {"Report", "Mute"};
        assertArrayEquals(new CharSequence[] {"Report", "Mute", "Download as video", "Download as photo"},
                StoryDownload.labels(menu, true));
        assertArrayEquals(new CharSequence[] {"Report", "Mute", "Download"}, StoryDownload.labels(menu, false));

        CharSequence[] already = {"Report", new SpannableString("Download as video"), "Download as photo"};
        assertSame(already, StoryDownload.labels(already, true));
    }

    /**
     * The menu a builder names comes back to labels(), and a story the bridges can't read, as in an
     * unpatched build, counts as no photo with music, so its menu keeps the one Download.
     */
    @Test
    public void aStoryThatCantBeReadKeepsOneDownload() {
        StoryDownload.building(new Object());
        assertArrayEquals(new CharSequence[] {"Report", "Download"}, StoryDownload.labels(new CharSequence[] {"Report"}));
        StoryDownload.building(null);
        assertArrayEquals(new CharSequence[] {"Report", "Download"}, StoryDownload.labels(new CharSequence[] {"Report"}));
        assertFalse(StoryDownload.photoWithMusic(null));
    }

    /**
     * A story the menu gets as a video is a photo with music when it was posted as a photo, which
     * is how Instagram's own story viewer tells one, or when its upload flagged it as one (#98).
     * A filmed video, or one that doesn't say how it was posted, is a video. Without a video there's
     * only the picture, whatever the story says, and with no story there's nothing to go on.
     */
    @Test
    @Config(shadows = Story.class)
    public void aStoryIsAPhotoWithMusicByHowItWasPosted() {
        Object media = new Object();
        assertEquals(StoryDownload.Kind.UNREAD, StoryDownload.kind(null));

        Story.video = true;
        Story.posted = StoryDownload.POSTED_PHOTO;
        assertEquals(StoryDownload.Kind.POSTED_AS_PHOTO, StoryDownload.kind(media));
        Story.posted = 2;
        assertEquals(StoryDownload.Kind.POSTED_AS_VIDEO, StoryDownload.kind(media));
        Story.posted = null;
        assertEquals(StoryDownload.Kind.NO_POSTED_TYPE, StoryDownload.kind(media));
        Story.flagged = true;
        assertEquals(StoryDownload.Kind.FLAGGED, StoryDownload.kind(media));

        Story.flagged = null;
        Story.posted = StoryDownload.POSTED_PHOTO;
        Story.video = false;
        Story.manifest = "<MPD/>";
        assertEquals("a video Instagram only streams", StoryDownload.Kind.POSTED_AS_PHOTO, StoryDownload.kind(media));

        Story.manifest = null;
        assertEquals(StoryDownload.Kind.PHOTO, StoryDownload.kind(media));
        Story.flagged = true;
        assertEquals("a flagged photo with no video", StoryDownload.Kind.PHOTO, StoryDownload.kind(media));
    }

    /**
     * The menu of a video posted as a photo gets Download as video and Download as photo, a filmed
     * video's keeps its one Download, and the report counts what each story was.
     */
    @Test
    @Config(shadows = Story.class)
    public void aVideoPostedAsAPhotoOffersBothSavesAndIsCounted() {
        StoryDownload.building(new Object());
        Story.media = new Object();
        Story.video = true;
        Story.posted = StoryDownload.POSTED_PHOTO;
        assertArrayEquals(new CharSequence[] {"Report", "Download as video", "Download as photo"},
                StoryDownload.labels(new CharSequence[] {"Report"}));

        Story.posted = 2;
        assertArrayEquals(new CharSequence[] {"Report", "Download"}, StoryDownload.labels(new CharSequence[] {"Report"}));

        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains("video posted as a photo 1"));
        assertTrue(report, report.contains("video posted as a video 1"));
    }

    /** A story's Media as the bridges read it: a video or not, its upload's flag and the type it was posted as. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Story {
        static Object media;
        static boolean video;
        static String manifest;
        static Boolean flagged;
        static Integer posted;

        @Implementation protected static Object storyMedia(Object menu) { return media; }
        @Implementation protected static List<?> videoVersions(Object media) { return video ? List.of("720.mp4") : null; }
        @Implementation protected static String versionUrl(Object version) { return "https://scontent.cdninstagram.com/" + version; }
        @Implementation protected static String dashManifest(Object media) { return manifest; }
        @Implementation protected static Boolean storyImageWithMusic(Object media) { return flagged; }
        @Implementation protected static Integer originalMediaType(Object media) { return posted; }
    }

    /** Each of HushGram's rows is HushGram's to handle, however the menu styled it, and Instagram's aren't. */
    @Test
    public void eachRowIsKnownByItsLabel() {
        assertEquals(StoryDownload.Choice.STORY, StoryDownload.choice("Download"));
        assertEquals(StoryDownload.Choice.VIDEO, StoryDownload.choice(new SpannableString("Download as video")));
        assertEquals(StoryDownload.Choice.PHOTO, StoryDownload.choice("Download as photo"));
        assertNull(StoryDownload.choice("Report"));
    }

    /** A tap on either of the music story's rows is HushGram's, and with nothing to save it says so. */
    @Test
    public void aTapOnEitherRowWithNothingToSaveSaysSo() {
        for (String row : new String[] {"Download as video", "Download as photo"}) {
            ShadowToast.reset();
            assertTrue(row, StoryDownload.save(row, new Object()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(row, "Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        }
        Settings.DOWNLOAD_STORIES.save(false);
        assertFalse("a tap was taken from Instagram", StoryDownload.save("Download as photo", new Object()));
    }
}
