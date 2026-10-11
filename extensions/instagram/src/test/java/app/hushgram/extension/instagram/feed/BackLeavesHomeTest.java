/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
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

/** When Back on Home is left to the activity, and that it goes Instagram's way when the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BackLeavesHomeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.BACK_LEAVES_HOME.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.BACK_LEAVES_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, every Back on Home is left to the activity, and Diagnostics counts each one. */
    @Test
    public void withTheSwitchOnBackIsLeftToTheActivityAndCounted() {
        assertEquals(1, BackLeavesHome.leave());
        assertEquals(1, BackLeavesHome.leave());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.BACK_LEAVES_HOME));
        assertTrue(report, report.contains(BackLeavesHome.LEFT));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, Back is Instagram's. */
    @Test
    public void offPausedAndUnreadyGoInstagramsWay() {
        Settings.BACK_LEAVES_HOME.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.BACK_LEAVES_HOME.defaultValue);
        assertEquals(0, BackLeavesHome.leave());

        Settings.BACK_LEAVES_HOME.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, BackLeavesHome.leave());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(0, BackLeavesHome.leave()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals(0, BackLeavesHome.leave()));

        assertEquals(1, BackLeavesHome.leave());
    }

    /** A switch that throws goes Instagram's way and says the hook threw. */
    @Test
    public void aThrowingSwitchGoesInstagramsWayAndIsReported() {
        assertEquals(0, BackLeavesHome.leave(THROWS));

        String missing = HookStatus.missing(FamilyNames.BACK_LEAVES_HOME).toString();
        assertTrue(missing, missing.contains("'" + BackLeavesHome.BACK + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** Changing the switch applies at the next Back, with nothing to restart. */
    @Test
    public void aChangeAppliesAtTheNextBack() {
        assertEquals(1, BackLeavesHome.leave());
        Settings.BACK_LEAVES_HOME.save(false);
        assertEquals(0, BackLeavesHome.leave());
        Settings.BACK_LEAVES_HOME.save(true);
        assertEquals(1, BackLeavesHome.leave());
    }
}
