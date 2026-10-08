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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide Instants tells Instagram's Instants check, and when it leaves the answer to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class InstantsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_INSTANTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_INSTANTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheCheckAnswersNo() {
        assertTrue(Instants.hide());
        assertTrue(HookStatus.missing(FamilyNames.INSTANTS).toString(), HookStatus.missing(FamilyNames.INSTANTS).isEmpty());
    }

    @Test
    public void offToStartAndOffLeaveItToInstagram() {
        Settings.HIDE_INSTANTS.resetToDefault();
        assertFalse(Settings.HIDE_INSTANTS.get());
        assertFalse(Instants.hide());
        Settings.HIDE_INSTANTS.save(false);
        assertFalse(Instants.hide());
    }

    @Test
    public void pausedAndUnreadyLeaveItToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Instants.hide());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(Instants.hide()));

        assertTrue(Instants.hide());
    }

    @Test
    public void aThrowingSwitchLeavesItToInstagramAndIsReported() {
        assertFalse(Instants.hide(THROWS));

        String missing = HookStatus.missing(FamilyNames.INSTANTS).toString();
        assertTrue(missing, missing.contains("'" + Instants.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
