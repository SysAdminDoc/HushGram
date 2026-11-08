/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the split notes row hook answers for each read of Instagram's test (#558). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SplitNotesRowTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SPLIT_NOTES_ROW.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SPLIT_NOTES_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The patch is in Manager's default selection, so its switch starts off, and it takes a restart. */
    @Test
    public void theSwitchStartsOffAndTakesARestart() {
        Settings.SPLIT_NOTES_ROW.resetToDefault();
        assertFalse(Settings.SPLIT_NOTES_ROW.get());
        assertTrue("Instagram reads the test once when your messages are set up", Settings.SPLIT_NOTES_ROW.rebootApp);
    }

    /** With the switch on, every read says yes whatever Instagram's server said, and the hook counts as reached. */
    @Test
    public void onEveryReadSaysYes() {
        assertTrue("the server said no", SplitNotesRow.split(0));
        assertTrue("the server said yes", SplitNotesRow.split(1));
        assertTrue(HookStatus.missing(FamilyNames.SPLIT_NOTES_ROW).toString(),
                HookStatus.missing(FamilyNames.SPLIT_NOTES_ROW).isEmpty());
    }

    /** Off, paused or before the settings are read, each read keeps Instagram's answer, so an account in the test keeps it. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.SPLIT_NOTES_ROW.save(false);
        assertFalse("off", SplitNotesRow.split(0));
        assertTrue("off, Instagram's own yes", SplitNotesRow.split(1));
        Settings.SPLIT_NOTES_ROW.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", SplitNotesRow.split(0));
        assertTrue("paused, Instagram's own yes", SplitNotesRow.split(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", SplitNotesRow.split(0));
            assertTrue("no context, Instagram's own yes", SplitNotesRow.split(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", SplitNotesRow.split(0));
            assertTrue("pause undecided, Instagram's own yes", SplitNotesRow.split(1));
        });

        assertTrue("back on", SplitNotesRow.split(0));
    }
}
