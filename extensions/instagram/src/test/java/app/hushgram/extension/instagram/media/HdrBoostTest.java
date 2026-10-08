/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Build;
import android.view.Window;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Turn off HDR brightness boosts: on, no headroom and no HDR color mode; off, Instagram's own values. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HdrBoostTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TURN_OFF_HDR_BOOSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * Every headroom Instagram asks for, a fixed 1.5, a server's 1.7, automatic 0 or the 3.0 its
     * extended range video layer wants (#85), becomes none.
     */
    @Test
    public void onEveryHeadroomBecomesNone() {
        assertEquals(1.0f, HdrBoost.headroom(1.5f), 0);
        assertEquals(1.0f, HdrBoost.headroom(1.7f), 0);
        assertEquals(1.0f, HdrBoost.headroom(0f), 0);
        assertEquals(1.0f, HdrBoost.headroom(3.0f), 0);
        assertEquals(1.0f, HdrBoost.headroom(1.0f), 0);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.HELD_BACK + " 4"));
    }

    /** HDR and HDR10 color modes become the default one. Wide color and the default stay. */
    @Test
    public void onHdrColorModesBecomeTheDefault() {
        assertEquals(0, HdrBoost.colorMode(2));
        assertEquals(0, HdrBoost.colorMode(3));
        assertEquals(1, HdrBoost.colorMode(1));
        assertEquals(0, HdrBoost.colorMode(0));
    }

    /** Off, paused or before the settings are ready, Instagram's values go through as they were. */
    @Test
    public void offPausedOrUnreadyKeepInstagramsValues() {
        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        assertEquals(1.5f, HdrBoost.headroom(1.5f), 0);
        assertEquals(2, HdrBoost.colorMode(2));

        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(1.7f, HdrBoost.headroom(1.7f), 0);
        assertEquals(3, HdrBoost.colorMode(3));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(1.5f, HdrBoost.headroom(1.5f), 0);
            assertEquals(2, HdrBoost.colorMode(2));
        });
        assertTrue(String.join("\n", HookStatus.report()), !String.join("\n", HookStatus.report()).contains(HdrBoost.HELD_BACK));
    }

    /** The window's stand-ins set what they answer on the window itself. */
    @Test
    public void theWindowGetsTheAnswer() {
        Window window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        HdrBoost.colorMode(window, 2);
        assertEquals(0, window.getColorMode());
        if (Build.VERSION.SDK_INT >= 35) {
            HdrBoost.windowHeadroom(window, 1.5f);
            assertEquals(1.0f, window.getDesiredHdrHeadroom(), 0);
        }

        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        HdrBoost.colorMode(window, 1);
        assertEquals(1, window.getColorMode());
        if (Build.VERSION.SDK_INT >= 35) {
            HdrBoost.windowHeadroom(window, 1.5f);
            assertEquals(1.5f, window.getDesiredHdrHeadroom(), 0);
        }
    }
}
