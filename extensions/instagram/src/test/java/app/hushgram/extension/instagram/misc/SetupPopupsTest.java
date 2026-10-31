/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

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
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which Bloks screens the setup popups hook keeps closed. */
@RunWith(RobolectricTestRunner.class)
public class SetupPopupsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SKIP_SETUP_POPUPS.save(true);
        HookStatus.clear();
        SetupPopups.forget();
    }

    @After
    public void restore() {
        Settings.SKIP_SETUP_POPUPS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        SetupPopups.forget();
    }

    /** The patch is in Manager's default selection, so its switch starts off, and it's read at each screen. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.SKIP_SETUP_POPUPS.resetToDefault();
        assertFalse(Settings.SKIP_SETUP_POPUPS.get());
        assertFalse(Settings.SKIP_SETUP_POPUPS.rebootApp);
    }

    /** On, the contacts and location setup screens stay closed and the hook reports in. */
    @Test
    public void onTheTwoSetupScreensStayClosed() {
        assertTrue("contacts", SetupPopups.skip(SetupPopups.CONTACTS_SETUP));
        assertTrue("location", SetupPopups.skip(SetupPopups.LOCATION_SETUP));
        assertTrue("asked twice", SetupPopups.skip(SetupPopups.CONTACTS_SETUP));
        assertTrue(HookStatus.missing(FamilyNames.SETUP_POPUPS).toString(), HookStatus.missing(FamilyNames.SETUP_POPUPS).isEmpty());
    }

    /** Every other screen opens, including one whose id only starts or ends like a setup screen's. */
    @Test
    public void everyOtherScreenOpens() {
        String[] others = {
                SetupPopups.CONTACTS_SETUP + ".other",
                SetupPopups.LOCATION_SETUP + ".other",
                "other." + SetupPopups.CONTACTS_SETUP,
                SetupPopups.CONTACTS_SETUP.toUpperCase(java.util.Locale.ROOT),
                "com.bloks.www.bloks.ig.ndx.ci.entry",
                "com.bloks.www.ig.account_center",
                "",
                null,
        };
        for (String other : others) assertFalse(String.valueOf(other), SetupPopups.skip(other));
    }

    /** Off, paused or before the settings are read, the setup screens open as Instagram meant. */
    @Test
    public void offPausedAndUnreadyShowTheScreens() {
        Settings.SKIP_SETUP_POPUPS.save(false);
        assertFalse("off, contacts", SetupPopups.skip(SetupPopups.CONTACTS_SETUP));
        assertFalse("off, location", SetupPopups.skip(SetupPopups.LOCATION_SETUP));
        Settings.SKIP_SETUP_POPUPS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, contacts", SetupPopups.skip(SetupPopups.CONTACTS_SETUP));
        assertFalse("paused, location", SetupPopups.skip(SetupPopups.LOCATION_SETUP));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", SetupPopups.skip(SetupPopups.CONTACTS_SETUP)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", SetupPopups.skip(SetupPopups.LOCATION_SETUP)));

        assertTrue("back on", SetupPopups.skip(SetupPopups.CONTACTS_SETUP));
    }
}
