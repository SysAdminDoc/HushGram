/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
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

/** What Instagram's double tap to skip flags answer through the hooks (#554). */
@RunWith(RobolectricTestRunner.class)
public class ReelSkipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.DOUBLE_TAP_SKIP_REELS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.DOUBLE_TAP_SKIP_REELS.resetToDefault();
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** It starts off, and needs no restart: Instagram asks again for the next reel and the next double tap. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.DOUBLE_TAP_SKIP_REELS.resetToDefault();
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS.get());
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS.rebootApp);
    }

    /** On, every reel skips, 10 seconds a double tap, whatever the server said. */
    @Test
    public void onEveryReelSkipsTenSeconds() {
        assertTrue("the server said no", ReelSkip.skip(0));
        assertTrue("the server said yes", ReelSkip.skip(1));
        assertEquals(10f, ReelSkip.seconds(5f), 0f);
        assertEquals(10f, ReelSkip.seconds(0f), 0f);
        assertEquals(10d, ReelSkip.secondsDouble(15d), 0d);
        assertTrue(HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).toString(),
                HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).isEmpty());
    }

    /** #645: Skip length's seconds are what every reel skips, read at each answer, and kept to 5 through 30. */
    @Test
    public void skipLengthSetsTheSeconds() {
        assertEquals(10, (int) Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.defaultValue);
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.rebootApp);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(25);
        assertEquals(25f, ReelSkip.seconds(5f), 0f);
        assertEquals(25d, ReelSkip.secondsDouble(15d), 0d);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(5);
        assertEquals("no restart", 5f, ReelSkip.seconds(10f), 0f);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(90);
        assertEquals("a value from an old export is kept in range", 30f, ReelSkip.seconds(10f), 0f);

        Settings.DOUBLE_TAP_SKIP_REELS.save(false);
        assertEquals("off, Instagram's own", 7f, ReelSkip.seconds(7f), 0f);
    }

    /** On, Instagram's strip stays when it leaves a middle for the like, and a third of the reel replaces it otherwise. */
    @Test
    public void onTheStripLeavesAMiddleForTheLike() {
        assertEquals(0.25f, ReelSkip.edge(0.25f), 0f);
        assertEquals(0.25d, ReelSkip.edgeDouble(0.25d), 0d);
        for (float unusable : new float[] {0f, -1f, 0.5f, 0.9f, 1f, Float.NaN}) {
            assertEquals("float " + unusable, 1f / 3f, ReelSkip.edge(unusable), 0f);
            assertEquals("double " + unusable, 1f / 3f, ReelSkip.edgeDouble(unusable), 0d);
        }
    }

    /** Off, paused or before the settings are read, every answer stays Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswers() {
        Settings.DOUBLE_TAP_SKIP_REELS.save(false);
        assertInstagrams("off");
        Settings.DOUBLE_TAP_SKIP_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagrams("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagrams("no context"));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertInstagrams("pause undecided"));

        assertTrue("back on", ReelSkip.skip(0));
        assertEquals("back on", 10f, ReelSkip.seconds(5f), 0f);
    }

    private static void assertInstagrams(String when) {
        assertFalse(when, ReelSkip.skip(0));
        assertTrue(when + ", Instagram's own yes", ReelSkip.skip(1));
        assertEquals(when, 5f, ReelSkip.seconds(5f), 0f);
        assertEquals(when, 5d, ReelSkip.secondsDouble(5d), 0d);
        assertEquals(when, 0.9f, ReelSkip.edge(0.9f), 0f);
        assertEquals(when, 0.9d, ReelSkip.edgeDouble(0.9d), 0d);
    }
}
