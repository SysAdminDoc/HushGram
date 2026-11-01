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

    /** The same reel in a 1080x2201 viewer, with the comment bar gone and the status bar still up. */
    private static final float BAR_GONE = 12.8f;

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
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.resetToDefault();
        Settings.HIDE_REEL_COMMENT_BAR.resetToDefault();
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

    /**
     * #371: the comment bar's height goes to the viewer, and on a 1080x2400 screen a 9:16 reel then
     * came up with black bands above and below it, with neither Full screen switch on.
     */
    @Test
    public void hidingTheCommentBarOnAllReelsFillsTheRoomItLeaves() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        assertFalse("the bar's still there", ReelFill.fill(BAR_GONE));
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        assertTrue(ReelFill.fill(BAR_GONE));
        assertTrue(ReelFill.fill(ReelFill.MOST_CROP));
        assertFalse("a 4:5 reel still fits", ReelFill.fill(43.75f));
        assertFalse(ReelFill.fill(ReelFill.MOST_CROP + 0.01f));
        assertFalse(ReelFill.fill(Float.NaN));
    }

    /** Show the whole reel is asked first, so it keeps its bars with the comment bar gone. */
    @Test
    public void showTheWholeReelStillWinsWithTheCommentBarGone() {
        Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.save(true);
        Settings.FIT_REEL_SCREEN.save(true);
        assertTrue(ReelFill.fit());
    }

    /** The reposted reels switch hides the bar for one source, and this decision doesn't get told which. */
    @Test
    public void hidingTheBarOnRepostedReelsAloneLeavesItToInstagram() {
        Settings.FULL_SCREEN_REELS.save(false);
        Settings.FILL_REEL_SCREEN.save(false);
        Settings.HIDE_REEL_COMMENT_BAR.save(true);
        assertFalse(ReelFill.fill(BAR_GONE));
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
