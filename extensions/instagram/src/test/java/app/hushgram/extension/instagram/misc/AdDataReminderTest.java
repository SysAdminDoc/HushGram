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

/** When the three routes of the ad data reminder stay closed. */
@RunWith(RobolectricTestRunner.class)
public class AdDataReminderTest {
    private static final String[] ROUTES = {"quick promotion", "evergreen launcher", "trigger"};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SKIP_AD_DATA_REMINDER.save(true);
        HookStatus.clear();
        AdDataReminder.forget();
    }

    @After
    public void restore() {
        Settings.SKIP_AD_DATA_REMINDER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        AdDataReminder.forget();
    }

    /** The patch is in Manager's default selection, so its switch starts off, and it's read at each route. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.SKIP_AD_DATA_REMINDER.resetToDefault();
        assertFalse(Settings.SKIP_AD_DATA_REMINDER.get());
        assertFalse(Settings.SKIP_AD_DATA_REMINDER.rebootApp);
    }

    /** On, every route stays closed, each time it's asked, and the hook reports in. */
    @Test
    public void onEveryRouteStaysClosed() {
        for (String route : ROUTES) {
            assertTrue(route, AdDataReminder.skip(route));
            assertTrue(route + " again", AdDataReminder.skip(route));
        }
        assertTrue(HookStatus.missing(FamilyNames.AD_DATA_REMINDER).toString(), HookStatus.missing(FamilyNames.AD_DATA_REMINDER).isEmpty());
    }

    /** Off, paused or before the settings are read, the reminder opens as Instagram meant. */
    @Test
    public void offPausedAndUnreadyShowTheReminder() {
        Settings.SKIP_AD_DATA_REMINDER.save(false);
        for (String route : ROUTES) assertFalse("off, " + route, AdDataReminder.skip(route));
        Settings.SKIP_AD_DATA_REMINDER.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        for (String route : ROUTES) assertFalse("paused, " + route, AdDataReminder.skip(route));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", AdDataReminder.skip(ROUTES[2])));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", AdDataReminder.skip(ROUTES[1])));

        assertTrue("back on", AdDataReminder.skip(ROUTES[0]));
    }
}
