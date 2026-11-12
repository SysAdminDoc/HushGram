/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

/** Which pagers the hooks hold still, that the pull-down layout lets touches by, and that they fail open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelScrollingTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private final Object reels = new Object();
    private final Object other = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_REELS_SCROLLING.save(true);
        ReelScrolling.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ReelScrolling.forget();
        HookStatus.clear();
    }

    /** On, a Reels pager's swipes go off as the viewer sets it up and stay off, and a pull lets the touch by. */
    @Test
    public void withTheSwitchOnAReelsPagerStaysPut() {
        assertEquals("turned off at setup", 0, ReelScrolling.pager(reels));
        assertEquals("Instagram turning it back on", 0, ReelScrolling.userInput(reels, 1));
        assertEquals("Instagram turning it off", 0, ReelScrolling.userInput(reels, 0));
        assertEquals("a pull", 0, ReelScrolling.pull());
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.REEL_SCROLLING));
    }

    /** Every other pager in Instagram takes what it's given, and a missing pager is left to Instagram. */
    @Test
    public void otherPagersAreLeftAlone() {
        ReelScrolling.pager(reels);
        assertEquals("another pager", 1, ReelScrolling.userInput(other, 1));
        assertEquals("another pager", 0, ReelScrolling.userInput(other, 0));
        assertEquals("no pager", 1, ReelScrolling.userInput(null, 1));
        assertEquals("no pager to turn off", 1, ReelScrolling.pager(null));
    }

    /** A pager set up while the switch was off is held once it's on. */
    @Test
    public void aPagerSetUpWhileOffIsHeldOnceOn() {
        Settings.STOP_REELS_SCROLLING.save(false);
        assertEquals(1, ReelScrolling.pager(reels));
        assertEquals(1, ReelScrolling.userInput(reels, 1));

        Settings.STOP_REELS_SCROLLING.save(true);
        assertEquals(0, ReelScrolling.userInput(reels, 1));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, Reels scroll and pull as before. */
    @Test
    public void offPausedAndUnreadyKeepReelsScrolling() {
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.STOP_REELS_SCROLLING.defaultValue);
        assertStock("off");

        Settings.STOP_REELS_SCROLLING.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertStock("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertStock("unready"));

        assertEquals(0, ReelScrolling.pager(reels));
        assertEquals(0, ReelScrolling.pull());
    }

    /** A switch that throws leaves the pager and the layout to Instagram and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsReelsScrollingAndIsReported() {
        assertEquals(1, ReelScrolling.pager(reels, THROWS));
        assertEquals(1, ReelScrolling.userInput(reels, 1, THROWS));
        assertEquals(0, ReelScrolling.userInput(reels, 0, THROWS));
        assertEquals(1, ReelScrolling.pull(THROWS));

        String missing = HookStatus.missing(FamilyNames.REEL_SCROLLING).toString();
        for (String part : new String[] {ReelScrolling.PAGER, ReelScrolling.INPUT, ReelScrolling.PULL}) {
            assertTrue(missing, missing.contains("'" + part + "'"));
        }
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    private void assertStock(String what) {
        assertEquals(what + ": the pager at setup", 1, ReelScrolling.pager(reels));
        assertEquals(what + ": Instagram turning it on", 1, ReelScrolling.userInput(reels, 1));
        assertEquals(what + ": Instagram turning it off", 0, ReelScrolling.userInput(reels, 0));
        assertEquals(what + ": a pull", 1, ReelScrolling.pull());
    }
}
