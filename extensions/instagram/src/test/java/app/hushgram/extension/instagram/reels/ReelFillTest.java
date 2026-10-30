/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When a reel is cropped to fill the screen instead of fitting with bars (#288). */
@RunWith(RobolectricTestRunner.class)
public class ReelFillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A 9:16 reel on a 20:9 screen with both bars hidden: (1080/2400 - 0.5625) / 0.5625 is 20% off. */
    private static final float TALL_SCREEN = 20f;

    @Before
    public void switchOn() {
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FILL_REEL_SCREEN.save(true);
    }

    @After
    public void switchBack() {
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FILL_REEL_SCREEN.resetToDefault();
        Settings.FIT_REEL_SCREEN.resetToDefault();
    }

    @Test
    public void aReelCloseToTheScreensShapeFillsIt() {
        assertTrue(ReelFill.fill(TALL_SCREEN));
        assertTrue(ReelFill.fill(0f));
        assertTrue(ReelFill.fill(ReelFill.MOST_CROP));
    }

    @Test
    public void squareAndWideReelsAndBadNumbersStayWithInstagram() {
        // A 4:5 video on that screen would lose 44% of its width, a square one more.
        assertFalse(ReelFill.fill(43.75f));
        assertFalse(ReelFill.fill(ReelFill.MOST_CROP + 0.01f));
        assertFalse(ReelFill.fill(-1f));
        assertFalse(ReelFill.fill(Float.NaN));
        assertFalse(ReelFill.fill(Float.POSITIVE_INFINITY));
    }

    @Test
    public void eitherSwitchOffLeavesItToInstagram() {
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse(ReelFill.fill(TALL_SCREEN));
        Settings.FILL_REEL_SCREEN.save(true);
        Settings.FULL_SCREEN_REELS.save(false);
        assertFalse(ReelFill.fill(TALL_SCREEN));
    }

    /** piko #2183: Show the whole reel answers on its own switch, with or without Full screen Reels. */
    @Test
    public void showTheWholeReelFitsOnItsOwnSwitch() {
        assertFalse("off to start", ReelFill.fit());
        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue(ReelFill.fit());
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertTrue("doesn't need Full screen Reels", ReelFill.fit());
        Settings.FIT_REEL_SCREEN.save(false);
        assertFalse(ReelFill.fit());
    }
}
