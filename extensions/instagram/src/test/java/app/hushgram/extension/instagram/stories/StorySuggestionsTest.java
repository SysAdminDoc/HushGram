/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide Suggested for you between stories leaves out: the suggested netegos and the empty story state's reel. */
@RunWith(RobolectricTestRunner.class)
public class StorySuggestionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Named like 450's reel types. */
    enum ReelType { USER_REEL, MEMORY_REEL, HIGHLIGHT_REWIND_REEL, SUGGESTED_USER_REEL, EMPTY_STORY_STATE_REEL }

    /** A tray item keeping its reel type in an enum field, as 450's ReelResponseItem does. */
    static final class TrayItem {
        final ReelType reelType;

        TrayItem(ReelType reelType) {
            this.reelType = reelType;
        }
    }

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_STORY_SUGGESTIONS.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    @After
    public void restore() {
        Settings.HIDE_STORY_SUGGESTIONS.resetToDefault();
        Settings.HIDE_STORY_RECAPS.resetToDefault();
        Settings.HIDE_STORY_REWINDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    /** The switch starts off, and then every netego and the empty story state stay. */
    @Test
    public void theSwitchStartsOffAndEverythingStays() {
        assertFalse(Settings.HIDE_STORY_SUGGESTIONS.get());
        assertFalse(StoriesTray.netego("suggested_users_in_story"));
        TrayItem cards = new TrayItem(ReelType.EMPTY_STORY_STATE_REEL);
        assertSame(cards, StoriesTray.filter(cards));
        assertTrue(HookStatus.missing(FamilyNames.STORIES_TRAY).toString(), HookStatus.missing(FamilyNames.STORIES_TRAY).isEmpty());
    }

    /** On, each suggested netego type is left out and counted, and every other netego, and an ad, stays. */
    @Test
    public void onTheSuggestedNetegosAreLeftOut() {
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        assertTrue(StoriesTray.netego("suggested_users_in_story"));
        assertTrue(StoriesTray.netego("suggested_businesses_in_story"));
        assertTrue(StoriesTray.netego("cymf_in_stories"));
        assertFalse("an ad has no netego type", StoriesTray.netego(null));
        assertFalse(StoriesTray.netego("story_survey"));
        assertFalse(StoriesTray.netego("suggested_clips_story_netego"));
        assertFalse(StoriesTray.netego("ad4ad_in_story"));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(StoriesTray.SUGGESTION_ROUTE));
        assertTrue(report, report.contains("3 removed"));
        assertTrue(report, report.contains("suggested_users_in_story 1"));
        assertTrue(HookStatus.missing(FamilyNames.STORIES_TRAY).toString(), HookStatus.missing(FamilyNames.STORIES_TRAY).isEmpty());
    }

    /** Every unit the viewer checks is counted by its type, ads too, with the switch on or off. */
    @Test
    public void everyUnitTheViewerChecksIsCountedByType() {
        StoriesTray.netego(null);
        StoriesTray.netego("story_survey");
        StoriesTray.netego("suggested_users_in_story");
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        StoriesTray.netego(null);
        StoriesTray.netego("suggested_users_in_story");

        String units = null;
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(StoriesTray.UNITS_ROUTE + ":")) units = line;
        }
        assertTrue(FeedFilterCounters.report().toString(), units != null);
        assertTrue(units, units.contains("0 removed"));
        assertTrue(units, units.contains("ad 2"));
        assertTrue(units, units.contains("suggested_users_in_story 2"));
        assertTrue(units, units.contains("story_survey 1"));
    }

    /** On, the empty story state's reel goes and nothing else does; the other switches never take it. */
    @Test
    public void theEmptyStoryStateGoesOnlyWithThisSwitch() {
        TrayItem cards = new TrayItem(ReelType.EMPTY_STORY_STATE_REEL);
        Settings.HIDE_STORY_RECAPS.save(true);
        Settings.HIDE_STORY_REWINDS.save(true);
        assertSame("with recaps and rewinds on", cards, StoriesTray.filter(cards));
        Settings.HIDE_STORY_RECAPS.save(false);
        Settings.HIDE_STORY_REWINDS.save(false);

        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        assertNull(StoriesTray.filter(cards));
        TrayItem story = new TrayItem(ReelType.USER_REEL);
        TrayItem memory = new TrayItem(ReelType.MEMORY_REEL);
        TrayItem rewind = new TrayItem(ReelType.HIGHLIGHT_REWIND_REEL);
        assertSame(story, StoriesTray.filter(story));
        assertSame("a recap went with the suggestion cards", memory, StoriesTray.filter(memory));
        assertSame("a rewind went with the suggestion cards", rewind, StoriesTray.filter(rewind));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(StoriesTray.SUGGESTION_ROUTE));
        assertTrue(report, report.contains("EMPTY_STORY_STATE_REEL"));
    }

    /** Paused, or before the settings are read, the suggestions stay. */
    @Test
    public void pausedAndUnreadyLeaveTheSuggestions() {
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        TrayItem cards = new TrayItem(ReelType.EMPTY_STORY_STATE_REEL);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoriesTray.netego("suggested_users_in_story"));
        assertSame("paused", cards, StoriesTray.filter(cards));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", StoriesTray.netego("suggested_users_in_story"));
            assertSame("no context", cards, StoriesTray.filter(cards));
        });

        assertTrue("back on", StoriesTray.netego("suggested_users_in_story"));
    }
}
