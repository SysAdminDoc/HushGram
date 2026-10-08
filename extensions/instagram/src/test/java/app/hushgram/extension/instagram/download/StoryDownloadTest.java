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

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** What the story menu's hooks answer, and what a tap on Download does with them. */
@RunWith(RobolectricTestRunner.class)
public class StoryDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
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
