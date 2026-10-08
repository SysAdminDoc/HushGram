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

/** What Don't report screenshots tells Instagram's screenshot detector, and when it leaves it to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ScreenshotReportsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_SCREENSHOTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_SCREENSHOTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnScreenshotsAreKept() {
        assertTrue(ScreenshotReports.hold());
        assertTrue(HookStatus.missing(FamilyNames.SCREENSHOT_REPORTS).toString(), HookStatus.missing(FamilyNames.SCREENSHOT_REPORTS).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.HIDE_SCREENSHOTS.resetToDefault();
        assertFalse(Settings.HIDE_SCREENSHOTS.get());
        assertFalse(ScreenshotReports.hold());
        Settings.HIDE_SCREENSHOTS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ScreenshotReports.hold());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ScreenshotReports.hold()));
        assertTrue(ScreenshotReports.hold());

        assertFalse(ScreenshotReports.hold(THROWS));
        String missing = HookStatus.missing(FamilyNames.SCREENSHOT_REPORTS).toString();
        assertTrue(missing, missing.contains("'" + ScreenshotReports.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
