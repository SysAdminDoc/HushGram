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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide 'Are you interested?' bubbles: the hook the patch puts where Home's row builder is about to
 * add the question's row answers yes only with the switch on, ready and not paused, and counts each
 * row it leaves out. The Reels footer's control comes back null on the same answer (#131).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedInterestTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void prepare() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_FEED_INTEREST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static boolean counted(int times) {
        return HookStatus.report().toString().contains(FeedInterest.COUNT_HIDDEN + " " + times);
    }

    private static boolean countedAnything() {
        return HookStatus.report().toString().contains(FeedInterest.COUNT_HIDDEN);
    }

    @Test
    public void withTheSwitchOnTheRowIsLeftOutAndEachOneCounted() {
        assertFalse("starts off", Settings.HIDE_FEED_INTEREST.defaultValue);
        Settings.HIDE_FEED_INTEREST.save(true);

        assertTrue(FeedInterest.hide());
        assertTrue(FeedInterest.hide());
        assertTrue(FeedInterest.hide());

        assertTrue(HookStatus.report().toString(), counted(3));
        assertEquals("interest questions hidden", FeedInterest.COUNT_HIDDEN);
    }

    /** The question's switch is its own: the survey and suggestion switches don't turn it on. */
    @Test
    public void onlyTheQuestionSwitchTurnsItOn() {
        assertFalse(FeedInterest.hide());
        Settings.HIDE_FEED_SURVEYS.save(true);
        Settings.HIDE_SUGGESTED_POSTS.save(true);
        Settings.HIDE_FEED_SHOPPING.save(true);
        try {
            assertFalse("the other switches don't hide the question", FeedInterest.hide());
            assertFalse(countedAnything());
        } finally {
            Settings.HIDE_FEED_SURVEYS.resetToDefault();
            Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
            Settings.HIDE_FEED_SHOPPING.resetToDefault();
        }
    }

    /** Off to start, paused and before the settings are read, Instagram adds its row and nothing is counted. */
    @Test
    public void offPausedAndUnreadyAddTheRowAndCountNothing() {
        assertFalse("off to start", FeedInterest.hide());
        assertFalse("off counts nothing", countedAnything());

        Settings.HIDE_FEED_INTEREST.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedInterest.hide());
        assertFalse("paused counts nothing", countedAnything());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("unready", FeedInterest.hide()));
        assertFalse("unready counts nothing", countedAnything());

        assertTrue("and on again once resumed and ready", FeedInterest.hide());
        assertTrue(counted(1));
    }

    /** The Reels footer keeps its control with the switch off or paused, and gets null with it on. No control isn't counted. */
    @Test
    public void theReelsControlIsDroppedOnlyWithTheSwitchOn() {
        Object control = new Object();
        assertSame("off keeps the control", control, FeedInterest.reelsControl(control));
        assertFalse("off counts nothing", countedAnything());

        Settings.HIDE_FEED_INTEREST.save(true);
        assertNull("on leaves the row without one", FeedInterest.reelsControl(control));
        assertNull("no control stays none", FeedInterest.reelsControl(null));
        assertTrue(HookStatus.report().toString(), counted(1));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused keeps the control", control, FeedInterest.reelsControl(control));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame("unready keeps the control", control, FeedInterest.reelsControl(control)));
        assertTrue("still counted once", counted(1));
    }
}
