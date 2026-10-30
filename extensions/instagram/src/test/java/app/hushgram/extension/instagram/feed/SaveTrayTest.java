/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
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

/** piko #2210: when the collections tray check answers no, and that Diagnostics sees the hook run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SaveTrayTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SKIP_COLLECTIONS_TRAY.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SKIP_COLLECTIONS_TRAY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, so Instagram's own check decides; on, every save skips the tray, and the ask is counted. */
    @Test
    public void theSwitchDecidesAndTheAskIsCounted() {
        assertEquals(Boolean.FALSE, Settings.SKIP_COLLECTIONS_TRAY.defaultValue);
        assertFalse(SaveTray.skip());
        Settings.SKIP_COLLECTIONS_TRAY.save(true);
        assertTrue(SaveTray.skip());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.SAVE_TRAY));
        Settings.SKIP_COLLECTIONS_TRAY.save(false);
        assertFalse("a change applies at the next save", SaveTray.skip());
    }

    /** Paused, or asked before the settings are read, Instagram's check decides. */
    @Test
    public void pausedAndUnreadyLeaveInstagramsCheck() {
        Settings.SKIP_COLLECTIONS_TRAY.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(SaveTray.skip());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(SaveTray.skip()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(SaveTray.skip()));

        assertTrue(SaveTray.skip());
    }
}
