/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
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

/** The midcards the stories viewer builds between stories, by Instagram 451's type names (#154). */
@RunWith(RobolectricTestRunner.class)
public class StoryMidcardsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String SUGGESTED = "story_interstitial_suggested_users_midcard";
    private static final String MUSIC = "story_interstitial_music_midcard";
    private static final String CHAINING = "story_interstitial_chaining";

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_STORY_SUGGESTIONS.resetToDefault();
        Settings.HIDE_MUSIC_CARD.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    @After
    public void restore() {
        Settings.HIDE_STORY_SUGGESTIONS.resetToDefault();
        Settings.HIDE_MUSIC_CARD.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    /** Off, the Suggested for you page stays, and the call still counts as the hook running. */
    @Test
    public void offTheSuggestedPageStays() {
        assertFalse(Settings.HIDE_STORY_SUGGESTIONS.get());
        assertFalse(StoriesTray.midcard(SUGGESTED));
        assertTrue(HookStatus.missing(FamilyNames.STORIES_TRAY).toString(), HookStatus.missing(FamilyNames.STORIES_TRAY).isEmpty());
    }

    /** On, the Suggested for you page goes and is counted with the switch's other cards; every other midcard stays. */
    @Test
    public void onOnlyTheSuggestedPageGoes() {
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        Settings.HIDE_MUSIC_CARD.save(true);
        assertTrue(StoriesTray.midcard(SUGGESTED));
        assertFalse("the music card has its own flag", StoriesTray.midcard(MUSIC));
        assertFalse(StoriesTray.midcard(CHAINING));
        assertFalse(StoriesTray.midcard("story_interstitial_restyle_midcard"));
        assertFalse("no type", StoriesTray.midcard(null));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(StoriesTray.SUGGESTION_ROUTE));
        assertTrue(report, report.contains(SUGGESTED + " 1"));
    }

    /** Every midcard built is counted by its type, with the switch on or off, so a report names what showed. */
    @Test
    public void everyMidcardIsCountedByType() {
        StoriesTray.midcard(SUGGESTED);
        StoriesTray.midcard(MUSIC);
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        StoriesTray.midcard(SUGGESTED);

        String midcards = null;
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(StoriesTray.MIDCARD_ROUTE + ":")) midcards = line;
        }
        assertTrue(FeedFilterCounters.report().toString(), midcards != null);
        assertTrue(midcards, midcards.contains(SUGGESTED + " 2"));
        assertTrue(midcards, midcards.contains(MUSIC + " 1"));
    }

    /** Paused, or before the settings are read, the page stays. */
    @Test
    public void pausedAndUnreadyLeaveThePage() {
        Settings.HIDE_STORY_SUGGESTIONS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoriesTray.midcard(SUGGESTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", StoriesTray.midcard(SUGGESTED)));

        assertTrue("back on", StoriesTray.midcard(SUGGESTED));
    }
}
