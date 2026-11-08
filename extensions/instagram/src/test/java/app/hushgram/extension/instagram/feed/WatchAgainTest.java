/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Watch again in the feed (#568): the switch turns on Instagram's own in-feed replay for "Watch again". */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class WatchAgainTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.WATCH_AGAIN_IN_FEED.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.WATCH_AGAIN_IN_FEED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, Instagram's flag passes through as it came. */
    @Test
    public void startsOff() {
        Settings.WATCH_AGAIN_IN_FEED.resetToDefault();
        assertFalse(WatchAgain.inFeed(0));
        assertTrue(WatchAgain.inFeed(1));
    }

    /** On, "Watch again" replays in the feed whatever the server said, and the hook reports in. */
    @Test
    public void theSwitchTurnsTheReplayOn() {
        assertTrue(WatchAgain.inFeed(0));
        assertTrue(WatchAgain.inFeed(1));
        assertTrue(HookStatus.missing(FamilyNames.WATCH_AGAIN).toString(), HookStatus.missing(FamilyNames.WATCH_AGAIN).isEmpty());
    }

    /** Paused, Instagram's own choice stands. */
    @Test
    public void pausedLeavesInstagramsChoice() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(WatchAgain.inFeed(0));
        assertTrue(WatchAgain.inFeed(1));
    }

    /** Before HushGram's settings are ready, Instagram's own choice stands too. */
    @Test
    public void noContextLeavesInstagramsChoice() {
        SettingsContextRule.withoutContext(() -> assertFalse(WatchAgain.inFeed(0)));
    }

    /** A failing read keeps Instagram's flag and is reported. */
    @Test
    public void aFailureKeepsInstagramsFlag() {
        assertFalse(WatchAgain.inFeed(0, () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(WatchAgain.inFeed(2, () -> {
            throw new IllegalStateException("boom");
        }));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(WatchAgain.HOOK));
    }
}
