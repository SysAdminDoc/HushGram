/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** What the feed menu's hooks do with a post, and what a tap on Download does. */
@RunWith(RobolectricTestRunner.class)
public class VideoDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.DOWNLOAD_VIDEOS.save(true);
    }

    @After
    public void tearDown() {
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
    }

    /**
     * A post with no video gets no row, and the builder's list is left as it was. Without the
     * bridges written, no post has one.
     */
    @Test
    public void aPostWithoutAVideoGetsNoRow() {
        ArrayList<Object> rows = new ArrayList<>();
        rows.add("Report");

        VideoDownload.offer(new Object(), rows);
        VideoDownload.offer(null, rows);
        VideoDownload.offer(new Object(), null);

        assertEquals(Collections.singletonList("Report"), rows);
    }

    /** Off, the menu is Instagram's own, and so is a tap. */
    @Test
    public void offInstagramDecides() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        ArrayList<Object> rows = new ArrayList<>();

        VideoDownload.offer(new Object(), rows);

        assertTrue(rows.isEmpty());
        assertFalse("a tap was taken from Instagram", VideoDownload.save(new Object(), new Object(), null));
    }

    /**
     * A tap on a post without a video, your own photo for one, goes to Instagram's own download
     * rather than a failure toast.
     */
    @Test
    public void aTapOnAPostWithoutAVideoIsInstagrams() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertFalse(VideoDownload.save(new Object(), new Object(), activity));
        assertFalse(VideoDownload.save(new Object(), null, activity));
        assertFalse(VideoDownload.save(null, null, activity));
        assertEquals(null, ShadowToast.getTextOfLatestToast());
    }

    /**
     * A video saves as a video with its switch on and never as a photo, since its cover is a
     * picture too. A picture without a video saves only with the photo switch on.
     */
    @Test
    public void eachSwitchSavesItsOwnKind() {
        assertEquals(VideoDownload.Save.VIDEO, VideoDownload.what(true, false, true, false));
        assertEquals(VideoDownload.Save.VIDEO, VideoDownload.what(true, true, true, true));
        assertEquals("a video's cover", VideoDownload.Save.NONE, VideoDownload.what(true, true, false, true));
        assertEquals(VideoDownload.Save.PHOTO, VideoDownload.what(false, true, false, true));
        assertEquals(VideoDownload.Save.PHOTO, VideoDownload.what(false, true, true, true));
        assertEquals("photo switch off", VideoDownload.Save.NONE, VideoDownload.what(false, true, true, false));
        assertEquals("nothing to save", VideoDownload.Save.NONE, VideoDownload.what(false, false, true, true));
    }

    /**
     * Your own post keeps Instagram's yes, and gets one when a tap would save a video or a photo
     * (#57). With nothing to save, or a post the bridges can't read, it's Instagram's answer.
     */
    @Test
    public void yourOwnPostGetsTheRowWhenATapWouldSave() {
        assertEquals(1, VideoDownload.own(1, VideoDownload.Save.NONE));
        assertEquals(1, VideoDownload.own(0, VideoDownload.Save.VIDEO));
        assertEquals(1, VideoDownload.own(0, VideoDownload.Save.PHOTO));
        assertEquals(0, VideoDownload.own(0, VideoDownload.Save.NONE));
        assertEquals("an unbridged post", 0, VideoDownload.ownPost(0, new Object()));
        assertEquals("no state", 0, VideoDownload.ownPost(0, null));
        assertEquals("Instagram's yes", 1, VideoDownload.ownPost(1, null));
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals("both switches off", 0, VideoDownload.ownPost(0, new Object()));
    }

    /** Instagram's share sheet flag on your own post keeps the row in the menu whenever a tap would save. */
    @Test
    public void yourOwnPostKeepsTheRowInTheMenuWhenATapWouldSave() {
        assertEquals(0, VideoDownload.row(1, VideoDownload.Save.VIDEO));
        assertEquals(0, VideoDownload.row(1, VideoDownload.Save.PHOTO));
        assertEquals(1, VideoDownload.row(1, VideoDownload.Save.NONE));
        assertEquals(0, VideoDownload.row(0, VideoDownload.Save.NONE));
        assertEquals("an unbridged post", 1, VideoDownload.ownPostRow(1, new Object()));
        assertEquals("no state", 1, VideoDownload.ownPostRow(1, null));
        assertEquals("Instagram's row", 0, VideoDownload.ownPostRow(0, null));
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals("both switches off", 1, VideoDownload.ownPostRow(1, new Object()));
    }

    /** The photo switch starts off; with it on, a post with nothing to save still gets no row or tap. */
    @Test
    public void thePhotoSwitchStartsOff() {
        assertFalse(Settings.DOWNLOAD_PHOTOS.get());
        Settings.DOWNLOAD_PHOTOS.save(true);
        try {
            ArrayList<Object> rows = new ArrayList<>();
            VideoDownload.offer(new Object(), rows);
            assertTrue(rows.isEmpty());
            assertFalse(VideoDownload.save(new Object(), null, null));
            assertEquals(VideoDownload.Save.NONE, VideoDownload.what(new Object()));
        } finally {
            Settings.DOWNLOAD_PHOTOS.save(false);
        }
    }

    /** A carousel's page is the one at the index its feed state keeps, and none past either end. */
    @Test
    public void aCarouselPageIsTheOneOnScreen() {
        List<String> pages = Arrays.asList("photo", "video", "another photo");

        assertEquals("video", VideoDownload.page(pages, 1));
        assertEquals("photo", VideoDownload.page(pages, 0));
        assertNull("an index it couldn't read", VideoDownload.page(pages, -1));
        assertNull("past the last page", VideoDownload.page(pages, 3));
    }

    /**
     * Without the bridges written, no post is a carousel, so what's on screen is the post itself,
     * with or without a feed state. A save of the post itself keeps the post's details.
     */
    @Test
    public void anUnpatchedPostIsWhatsShown() {
        Object post = new Object();

        assertSame(post, VideoDownload.shown(post, new Object()));
        assertSame(post, VideoDownload.shown(post, null));
        assertNull(VideoDownload.shown(null, new Object()));
        assertSame(PostDetails.NONE, VideoDownload.details(post, post));
        assertSame(PostDetails.NONE, VideoDownload.details(new Object(), post));
    }

    /**
     * With the switch on, the short menu's list gets Download in front, so its row shows first; the
     * list Instagram made is left as it was. A list that has Download already comes back as is.
     */
    @Test
    public void theShortMenuKeepsDownloadFirst() {
        List<String> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT");

        List<?> allowed = VideoDownload.allow(options, "DOWNLOAD");

        assertEquals(Arrays.asList("DOWNLOAD", "WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT"), allowed);
        assertEquals(Arrays.asList("WHY_AM_I_SEEING_THIS", "SEE_MORE", "REPORT"), options);
        List<String> already = Arrays.asList("REPORT", "DOWNLOAD");
        assertSame(already, VideoDownload.allow(already, "DOWNLOAD"));
    }

    /** Off, or with nothing to go on, the short menu's list is Instagram's own. */
    @Test
    public void offTheShortMenuIsInstagrams() {
        List<String> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        assertSame(options, VideoDownload.allow(options, null));
        assertNull(VideoDownload.allow(null, "DOWNLOAD"));

        Settings.DOWNLOAD_VIDEOS.save(false);

        assertSame(options, VideoDownload.allow(options, "DOWNLOAD"));
    }

    /** Without the bridges written, a post has no video, and the read doesn't throw. */
    @Test
    public void anUnpatchedPostHasNoVideo() {
        assertFalse(VideoDownload.hasVideo(new Object()));
        assertFalse(VideoDownload.hasVideo(null));
    }
}
