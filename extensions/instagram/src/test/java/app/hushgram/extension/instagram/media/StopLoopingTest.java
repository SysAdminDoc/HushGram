/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

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

/** Stop videos looping: the loop flag Instagram's video player hands its engine (#248, #175). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StopLoopingTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object player = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_VIDEO_LOOPING.save(true);
        StopLooping.autoScrollsForTests = false;
        StopLooping.reelsForTests = false;
        HookStatus.clear();
    }

    @After
    public void restore() {
        StopLooping.autoScrollsForTests = null;
        StopLooping.reelsForTests = null;
        Settings.STOP_VIDEO_LOOPING.resetToDefault();
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, a video Instagram would loop plays once, and one it wouldn't loop stays that way. */
    @Test
    public void onAVideoPlaysOnce() {
        assertFalse(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.STOP_LOOPING));
    }

    /** A nonzero flag of any value counts as Instagram's yes: the register can hold an int. */
    @Test
    public void anyNonzeroFlagIsAYes() {
        Settings.STOP_VIDEO_LOOPING.save(false);
        assertTrue(StopLooping.loop(player, 1));
        assertTrue(StopLooping.loop(player, 0x100));
        assertTrue(StopLooping.loop(player, -1));
    }

    /** Off, the engine gets Instagram's flag either way. */
    @Test
    public void offInstagramDecides() {
        Settings.STOP_VIDEO_LOOPING.save(false);
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
    }

    /** Paused, the engine gets Instagram's flag. */
    @Test
    public void pausedInstagramDecides() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
    }

    /**
     * While auto scroll moves Reels on, a reel in the Reels viewer still loops, since the scroller
     * waits for the loop. A video anywhere else still stops.
     */
    @Test
    public void autoScrollKeepsReelsLooping() {
        StopLooping.autoScrollsForTests = true;
        StopLooping.reelsForTests = true;
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));

        StopLooping.reelsForTests = false;
        assertFalse(StopLooping.loop(player, 1));

        StopLooping.autoScrollsForTests = false;
        StopLooping.reelsForTests = true;
        assertFalse(StopLooping.loop(player, 1));
    }

    /**
     * Without Keep Reels auto scroll on in the build, nothing says Reels moves on by itself, so Move
     * on's saved switch alone doesn't count. The unpatched reels stub answers no.
     */
    @Test
    public void unpatchedBuildNeverKeepsTheLoop() {
        StopLooping.autoScrollsForTests = null;
        StopLooping.reelsForTests = null;
        Settings.REEL_AUTO_ADVANCE.save(true);
        assertFalse(StopLooping.autoScrolls());
        assertFalse(StopLooping.reels(player));
        assertFalse(StopLooping.loop(player, 1));
    }
}
