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

/** What Hide the Music for you card answers where Instagram reads the flag that lets the card in. */
@RunWith(RobolectricTestRunner.class)
public class MusicCardTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_MUSIC_CARD.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    @After
    public void restore() {
        Settings.HIDE_MUSIC_CARD.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    /** The switch starts off, and then Instagram's own answer goes through whichever it is. */
    @Test
    public void theSwitchStartsOffAndInstagramsAnswerStands() {
        assertFalse(Settings.HIDE_MUSIC_CARD.get());
        assertTrue("the server said yes", StoriesTray.musicCard(1));
        assertFalse("the server said no", StoriesTray.musicCard(0));
        assertTrue(HookStatus.missing(FamilyNames.STORIES_TRAY).toString(), HookStatus.missing(FamilyNames.STORIES_TRAY).isEmpty());
    }

    /** On, the card's flag answers no whatever the server said, and each no it turns down is counted. */
    @Test
    public void onTheCardIsLeftOut() {
        Settings.HIDE_MUSIC_CARD.save(true);
        assertFalse("the server said yes", StoriesTray.musicCard(1));
        assertFalse("the server said no", StoriesTray.musicCard(0));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(StoriesTray.MUSIC_CARD_ROUTE));
        assertTrue(report, report.contains("card left out 1"));
        assertTrue(HookStatus.missing(FamilyNames.STORIES_TRAY).toString(), HookStatus.missing(FamilyNames.STORIES_TRAY).isEmpty());
    }

    /** Paused, or before the settings are read, the flag keeps Instagram's answer. */
    @Test
    public void pausedAndUnreadyKeepInstagramsAnswer() {
        Settings.HIDE_MUSIC_CARD.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", StoriesTray.musicCard(1));
        assertFalse("paused, Instagram's own no", StoriesTray.musicCard(0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertTrue("no context", StoriesTray.musicCard(1));
            assertFalse("no context, Instagram's own no", StoriesTray.musicCard(0));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue("pause undecided", StoriesTray.musicCard(1));
            assertFalse("pause undecided, Instagram's own no", StoriesTray.musicCard(0));
        });

        assertFalse("back on", StoriesTray.musicCard(1));
    }
}
