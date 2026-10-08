/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** What Hide suggested stories takes out of Home's stories tray, and when it hides the tray. */
@RunWith(RobolectricTestRunner.class)
public class StoriesTrayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Named like 450's reel types. */
    enum ReelType {
        USER_REEL, HIGHLIGHT_REEL, SUGGESTED_USER, SUGGESTED_USER_REEL, SUGGESTED_CREATOR_REEL, HIGHLIGHT_REWIND_REEL,
        MEMORY_REEL, MY_WEEK_REEL, END_OF_YEAR, FOLLOW_VERSARIES, BIRTHDAY_HIGHLIGHTS, PROMPT_STICKER_REEL
    }

    /** A tray item keeping its reel type in an enum field, as 449's ReelResponseItem does. */
    static final class TrayItem {
        final ReelType reelType;

        TrayItem(ReelType reelType) {
            this.reelType = reelType;
        }
    }

    @Test
    public void suggestedStoriesAreTakenOut() {
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_USER_REEL)));
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_USER)));
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_CREATOR_REEL)));
    }

    @Test
    public void storiesFromAccountsYouFollowStay() {
        TrayItem followed = new TrayItem(ReelType.USER_REEL);
        TrayItem highlight = new TrayItem(ReelType.HIGHLIGHT_REEL);
        assertSame(followed, StoriesTray.filter(followed));
        assertSame(highlight, StoriesTray.filter(highlight));
        assertNull(StoriesTray.filter(null));
    }

    @Test
    public void withTheSwitchOffSuggestedStoriesStay() {
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        try {
            TrayItem suggested = new TrayItem(ReelType.SUGGESTED_USER_REEL);
            assertSame(suggested, StoriesTray.filter(suggested));
        } finally {
            Settings.HIDE_SUGGESTED_STORIES.save(true);
        }
    }

    /** Rewinds and recaps stay until their own switches, off to start, are turned on, each for its own kind. */
    @Test
    public void rewindsAndRecapsGoOnlyWithTheirSwitches() {
        ReelType[] recaps = {ReelType.MEMORY_REEL, ReelType.MY_WEEK_REEL, ReelType.END_OF_YEAR, ReelType.FOLLOW_VERSARIES,
                ReelType.BIRTHDAY_HIGHLIGHTS};
        TrayItem rewind = new TrayItem(ReelType.HIGHLIGHT_REWIND_REEL);
        assertFalse(Settings.HIDE_STORY_REWINDS.get());
        assertFalse(Settings.HIDE_STORY_RECAPS.get());
        assertSame(rewind, StoriesTray.filter(rewind));
        for (ReelType kind : recaps) {
            TrayItem recap = new TrayItem(kind);
            assertSame(kind.name(), recap, StoriesTray.filter(recap));
        }

        Settings.HIDE_STORY_REWINDS.save(true);
        try {
            assertNull(StoriesTray.filter(rewind));
            TrayItem memory = new TrayItem(ReelType.MEMORY_REEL);
            assertSame("a recap went with the rewinds", memory, StoriesTray.filter(memory));
        } finally {
            Settings.HIDE_STORY_REWINDS.save(false);
        }

        Settings.HIDE_STORY_RECAPS.save(true);
        try {
            for (ReelType kind : recaps) assertNull(kind.name(), StoriesTray.filter(new TrayItem(kind)));
            assertSame("a rewind went with the recaps", rewind, StoriesTray.filter(rewind));
            TrayItem story = new TrayItem(ReelType.USER_REEL);
            TrayItem prompt = new TrayItem(ReelType.PROMPT_STICKER_REEL);
            assertSame(story, StoriesTray.filter(story));
            assertSame("a story someone posted", prompt, StoriesTray.filter(prompt));
        } finally {
            Settings.HIDE_STORY_RECAPS.save(false);
        }
    }

    /** Stop loading stories drops every item, yours included, and the reels left to fetch, while it's on. */
    @Test
    public void stopLoadingStoriesDropsEveryItemAndTheReelsLeftToFetch() {
        ArrayList<Object> ids = new ArrayList<>(Arrays.asList("1", "2"));
        TrayItem story = new TrayItem(ReelType.USER_REEL);
        assertFalse(Settings.STOP_LOADING_STORIES.get());
        assertSame(story, StoriesTray.filter(story));
        assertSame(ids, StoriesTray.remaining(ids));

        Settings.STOP_LOADING_STORIES.save(true);
        try {
            for (ReelType kind : ReelType.values()) assertNull(kind.name(), StoriesTray.filter(new TrayItem(kind)));
            assertTrue(StoriesTray.remaining(ids).isEmpty());
            assertEquals("the list Instagram read is left as it was", 2, ids.size());
            assertNull(StoriesTray.remaining(null));
        } finally {
            Settings.STOP_LOADING_STORIES.save(false);
        }
        assertSame(story, StoriesTray.filter(story));
    }

    /** The whole tray stays until its own switch, off to start, is turned on. */
    @Test
    public void theTrayStaysUntilItsSwitchIsOn() {
        assertFalse(StoriesTray.hideTray());
        Settings.HIDE_STORIES_TRAY.save(true);
        try {
            assertTrue(StoriesTray.hideTray());
        } finally {
            Settings.HIDE_STORIES_TRAY.save(false);
        }
    }
}
