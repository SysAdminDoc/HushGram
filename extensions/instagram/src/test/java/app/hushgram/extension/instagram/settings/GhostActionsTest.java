/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #876: with its switch on and Ghost mode on, a like or a story reply is held back with a toast saying why. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GhostActionsTest {
    /** A method, not a static field, so the settings load once the rule has given them a context. */
    private static BooleanSetting[] ghostSwitches() {
        return new BooleanSetting[] {Settings.VIEW_STORIES_ANONYMOUSLY, Settings.VIEW_LIVE_ANONYMOUSLY,
                Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.VIEW_DM_MEDIA_ANONYMOUSLY, Settings.HIDE_TYPING, Settings.HIDE_SCREENSHOTS,
                Settings.HIDE_BLEND_VISITS, Settings.DONT_SEND_FEED_VIEW_HISTORY};
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private HookStatus.Snapshot saved;

    @Before public void prepare() {
        saved = HookStatus.snapshotAndClear();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.STORY_SEEN, PatchFamily.LIVE_SEEN,
                PatchFamily.THREAD_SEEN, PatchFamily.DM_MEDIA_SEEN, PatchFamily.TYPING, PatchFamily.SCREENSHOT_REPORTS,
                PatchFamily.BLEND_VISITS, PatchFamily.FEED_VIEW_HISTORY, PatchFamily.GHOST_ACTIONS);
        for (BooleanSetting setting : ghostSwitches()) setting.save(true);
        Settings.GHOST_MODE_PICKS.resetToDefault();
        Settings.BLOCK_GHOST_ACTIONS.save(true);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ShadowToast.reset();
    }

    @After public void close() {
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : ghostSwitches()) setting.resetToDefault();
        Settings.GHOST_MODE_PICKS.resetToDefault();
        Settings.BLOCK_GHOST_ACTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.restore(saved);
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return ShadowToast.getTextOfLatestToast();
    }

    @Test public void itStartsOff() {
        assertFalse(Settings.BLOCK_GHOST_ACTIONS.defaultValue);
    }

    @Test public void inGhostModeALikeIsHeldBackAndSaysWhy() {
        assertTrue(GhostActions.like());
        assertEquals("Likes are off in Ghost mode.", toast());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(GhostActions.BLOCKED_LIKE + " 1"));
    }

    @Test public void inGhostModeAReplyIsHeldBackAndSaysWhy() {
        assertTrue(GhostActions.reply());
        assertEquals("Replies are off in Ghost mode.", toast());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(GhostActions.BLOCKED_REPLY + " 1"));
    }

    @Test public void withTheSwitchOffBothGoThrough() {
        Settings.BLOCK_GHOST_ACTIONS.save(false);
        assertFalse(GhostActions.like());
        assertFalse(GhostActions.reply());
        assertNull("no toast", toast());
    }

    @Test public void withGhostModeOffBothGoThrough() {
        Settings.HIDE_TYPING.save(false);
        assertFalse("one switch off turns Ghost mode off", GhostActions.like());
        assertFalse(GhostActions.reply());
        for (BooleanSetting setting : ghostSwitches()) setting.save(false);
        assertFalse(GhostActions.like());
        assertNull("no toast", toast());
    }

    @Test public void ghostModeIsTheSwitchesItsPicksTurn() {
        GhostMode.pick(GhostMode.switches(PatchFamily.inThisBuild()),
                new HashSet<>(Arrays.asList(Settings.VIEW_STORIES_ANONYMOUSLY.key, Settings.HIDE_TYPING.key)));
        Settings.HIDE_SCREENSHOTS.save(false);
        assertTrue("a switch it doesn't turn being off leaves it on", GhostActions.like());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("a picked switch off turns it off", GhostActions.like());
    }

    @Test public void aBuildWithNoGhostSwitchNeverHoldsOneBack() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.GHOST_ACTIONS);
        assertFalse(GhostActions.like());
        assertFalse(GhostActions.reply());
    }

    @Test public void pauseLetsBothThrough() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(GhostActions.like());
        assertFalse(GhostActions.reply());
        assertNull("no toast", toast());
    }

    @Test public void beforeTheSettingsAreReadBothGoThrough() {
        boolean[] answers = new boolean[2];
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            answers[0] = GhostActions.like();
            answers[1] = GhostActions.reply();
        });
        assertFalse(answers[0]);
        assertFalse(answers[1]);
    }
}
