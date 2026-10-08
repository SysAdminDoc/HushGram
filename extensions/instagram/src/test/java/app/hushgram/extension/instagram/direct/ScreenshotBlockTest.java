/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Allow screenshots does to the windows Instagram marks secure, and when it leaves them to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ScreenshotBlockTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ALLOW_SCREENSHOTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.ALLOW_SCREENSHOTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnWindowsStayCapturable() {
        assertTrue(ScreenshotBlock.lift());
        assertTrue(HookStatus.missing(FamilyNames.SCREENSHOT_BLOCK).toString(), HookStatus.missing(FamilyNames.SCREENSHOT_BLOCK).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.ALLOW_SCREENSHOTS.resetToDefault();
        assertFalse(Settings.ALLOW_SCREENSHOTS.get());
        assertFalse(ScreenshotBlock.lift());
        Settings.ALLOW_SCREENSHOTS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ScreenshotBlock.lift());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ScreenshotBlock.lift()));
        assertTrue(ScreenshotBlock.lift());

        assertFalse(ScreenshotBlock.lift(THROWS));
        String missing = HookStatus.missing(FamilyNames.SCREENSHOT_BLOCK).toString();
        assertTrue(missing, missing.contains("'" + ScreenshotBlock.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    private static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;
    private static final int KEEP_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    private static Window window() {
        return Robolectric.buildActivity(Activity.class).setup().get().getWindow();
    }

    @Test
    public void withTheSwitchOnInstagramCantMarkAWindowSecure() {
        Window window = window();
        ScreenshotBlock.setFlags(window, SECURE | KEEP_ON, SECURE | KEEP_ON);
        ScreenshotBlock.addFlags(window, SECURE);
        assertEquals(0, window.getAttributes().flags & SECURE);
        assertEquals("the other flags go through", KEEP_ON, window.getAttributes().flags & KEEP_ON);
    }

    @Test
    public void offOrClearingLeavesTheFlagsAsInstagramAsked() {
        Window window = window();
        Settings.ALLOW_SCREENSHOTS.resetToDefault();
        ScreenshotBlock.addFlags(window, SECURE);
        assertEquals(SECURE, window.getAttributes().flags & SECURE);

        Settings.ALLOW_SCREENSHOTS.save(true);
        ScreenshotBlock.setFlags(window, 0, SECURE);
        assertEquals("clearing goes through", 0, window.getAttributes().flags & SECURE);

        Settings.ALLOW_SCREENSHOTS.resetToDefault();
        ScreenshotBlock.setFlags(window, SECURE, SECURE);
        assertEquals(SECURE, window.getAttributes().flags & SECURE);
        assertThrows("a missing window fails as it did", NullPointerException.class, () -> ScreenshotBlock.addFlags(null, KEEP_ON));
    }
}
