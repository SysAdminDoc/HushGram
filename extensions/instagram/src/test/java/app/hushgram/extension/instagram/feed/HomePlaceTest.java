/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
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
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When a return to Home counts as a quick one, and that the real time goes through when the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomePlaceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Fifteen minutes, the wait in #354's report. */
    private static final long AWAY = 15 * 60 * 1000L;

    /** Stand-ins for the reload Instagram settled on and its "nothing to do" value. */
    private static final Object RELOAD = new Object();
    private static final Object NONE = new Object();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_HOME_PLACE.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_HOME_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, any time away reads as none and any reload as nothing to do, and Diagnostics counts each skipped reload. */
    @Test
    public void withTheSwitchOnEveryReturnIsAQuickOneAndCounted() {
        assertEquals(0L, HomePlace.timeAway(AWAY));
        assertEquals(0L, HomePlace.timeAway(Long.MAX_VALUE));
        assertSame(NONE, HomePlace.reload(NONE, NONE));
        String quiet = String.join("\n", HookStatus.report());
        assertTrue(quiet, quiet.contains(FamilyNames.HOME_PLACE));
        assertFalse(quiet, quiet.contains(HomePlace.KEPT));

        assertSame(NONE, HomePlace.reload(RELOAD, NONE));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HomePlace.KEPT));
        assertFalse(report, report.contains(HomePlace.HELD));

        assertTrue(HomePlace.skipBackgroundReload());
        String held = String.join("\n", HookStatus.report());
        assertTrue(held, held.contains(HomePlace.HELD));
        assertFalse(held, held.contains(HomePlace.RESET_SKIPPED));

        assertNull(HomePlace.appResetPlan(null));
        String noPlan = String.join("\n", HookStatus.report());
        assertFalse(noPlan, noPlan.contains(HomePlace.RESET_SKIPPED));
        assertNull(HomePlace.appResetPlan(RELOAD));
        assertTrue(HomePlace.skipAppReset());
        String reset = String.join("\n", HookStatus.report());
        assertTrue(reset, reset.contains(HomePlace.RESET_SKIPPED));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, the time is Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsTime() {
        Settings.KEEP_HOME_PLACE.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.KEEP_HOME_PLACE.defaultValue);
        assertEquals(AWAY, HomePlace.timeAway(AWAY));
        assertSame(RELOAD, HomePlace.reload(RELOAD, NONE));
        assertFalse(HomePlace.skipBackgroundReload());
        assertFalse(HomePlace.skipAppReset());
        assertSame(RELOAD, HomePlace.appResetPlan(RELOAD));

        Settings.KEEP_HOME_PLACE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(AWAY, HomePlace.timeAway(AWAY));
        assertSame(RELOAD, HomePlace.reload(RELOAD, NONE));
        assertFalse(HomePlace.skipBackgroundReload());
        assertFalse(HomePlace.skipAppReset());
        assertSame(RELOAD, HomePlace.appResetPlan(RELOAD));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(AWAY, HomePlace.timeAway(AWAY));
            assertSame(RELOAD, HomePlace.reload(RELOAD, NONE));
            assertFalse(HomePlace.skipBackgroundReload());
            assertFalse(HomePlace.skipAppReset());
            assertSame(RELOAD, HomePlace.appResetPlan(RELOAD));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertEquals(AWAY, HomePlace.timeAway(AWAY));
            assertSame(RELOAD, HomePlace.reload(RELOAD, NONE));
            assertFalse(HomePlace.skipBackgroundReload());
            assertFalse(HomePlace.skipAppReset());
            assertSame(RELOAD, HomePlace.appResetPlan(RELOAD));
        });

        assertEquals(0L, HomePlace.timeAway(AWAY));
        assertSame(NONE, HomePlace.reload(RELOAD, NONE));
        assertTrue(HomePlace.skipBackgroundReload());
        assertTrue(HomePlace.skipAppReset());
        assertNull(HomePlace.appResetPlan(RELOAD));
    }

    /** A switch that throws keeps Instagram's time, reload and background alarm, and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsInstagramsTimeAndIsReported() {
        assertEquals(AWAY, HomePlace.timeAway(AWAY, THROWS));
        assertSame(RELOAD, HomePlace.reload(RELOAD, NONE, THROWS));

        String missing = HookStatus.missing(FamilyNames.HOME_PLACE).toString();
        assertTrue(missing, missing.contains("'" + HomePlace.RETURN + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));

        HookStatus.clear();
        assertFalse(HomePlace.skipBackgroundReload(THROWS));
        String background = HookStatus.missing(FamilyNames.HOME_PLACE).toString();
        assertTrue(background, background.contains("'" + HomePlace.BACKGROUND + "'"));
        assertTrue(background, background.contains(IllegalStateException.class.getName()));

        HookStatus.clear();
        assertFalse(HomePlace.skipAppReset(THROWS));
        assertSame(RELOAD, HomePlace.appResetPlan(RELOAD, THROWS));
        String reset = HookStatus.missing(FamilyNames.HOME_PLACE).toString();
        assertTrue(reset, reset.contains("'" + HomePlace.RESET + "'"));
        assertTrue(reset, reset.contains(IllegalStateException.class.getName()));
    }

    /** Changing the switch applies at the next return or alarm, with nothing to restart. */
    @Test
    public void aChangeAppliesAtTheNextReturn() {
        assertEquals(0L, HomePlace.timeAway(AWAY));
        assertSame(NONE, HomePlace.reload(RELOAD, NONE));
        assertTrue(HomePlace.skipBackgroundReload());
        assertTrue(HomePlace.skipAppReset());
        assertNull(HomePlace.appResetPlan(RELOAD));
        Settings.KEEP_HOME_PLACE.save(false);
        assertEquals(AWAY, HomePlace.timeAway(AWAY));
        assertSame(RELOAD, HomePlace.reload(RELOAD, NONE));
        assertFalse(HomePlace.skipBackgroundReload());
        assertFalse(HomePlace.skipAppReset());
        assertSame(RELOAD, HomePlace.appResetPlan(RELOAD));
        Settings.KEEP_HOME_PLACE.save(true);
        assertEquals(0L, HomePlace.timeAway(AWAY));
        assertSame(NONE, HomePlace.reload(RELOAD, NONE));
        assertTrue(HomePlace.skipBackgroundReload());
        assertTrue(HomePlace.skipAppReset());
        assertNull(HomePlace.appResetPlan(RELOAD));
    }
}
