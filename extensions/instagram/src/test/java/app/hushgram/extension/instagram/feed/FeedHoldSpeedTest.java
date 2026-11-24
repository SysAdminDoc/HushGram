/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

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

/** What Instagram's feed hold for 2x flags answer through the hooks (#552). */
@RunWith(RobolectricTestRunner.class)
public class FeedHoldSpeedTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_LONG.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_NAME.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_BADGE.resetToDefault();
        FeedHoldSpeed.clock = android.os.SystemClock::uptimeMillis;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedHoldSpeed.inBuildForTests = null;
        Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.resetToDefault();
        FeedHoldSpeed.tapPauseInBuildForTests = null;
    }

    /** It starts off, and needs no restart: Instagram reads the flags again on every held press. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        assertFalse(Settings.HOLD_FEED_VIDEO_FAST.get());
        assertFalse(Settings.HOLD_FEED_VIDEO_FAST.rebootApp);
    }

    /** On, every held feed video plays fast, and a hold counts anywhere on it: half the width from each side, no fixed strip. */
    @Test
    public void onEveryHoldIsFastAnywhereOnTheVideo() {
        assertTrue("the server said no", FeedHoldSpeed.fast(0));
        assertTrue("the server said yes", FeedHoldSpeed.fast(1));
        assertFalse("a fixed strip the server asked for", FeedHoldSpeed.fixedStrip(1));
        assertFalse("no fixed strip", FeedHoldSpeed.fixedStrip(0));
        assertEquals(0.5d, FeedHoldSpeed.strip(0.2d), 0d);
        assertEquals(0.5d, FeedHoldSpeed.strip(0d), 0d);
        assertTrue(HookStatus.missing(FamilyNames.FEED_HOLD_FAST).toString(),
                HookStatus.missing(FamilyNames.FEED_HOLD_FAST).isEmpty());
    }

    /** Off, paused or before the settings are read, every answer stays Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswers() {
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertInstagrams("off");
        Settings.HOLD_FEED_VIDEO_FAST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagrams("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagrams("no context"));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertInstagrams("pause undecided"));

        assertTrue("back on", FeedHoldSpeed.fast(0));
        assertEquals("back on", 0.5d, FeedHoldSpeed.strip(0.2d), 0d);
    }

    /** A held press is Instagram's to speed up only when the patch is in and the switch is on. */
    @Test
    public void holdingNeedsThePatchAndTheSwitch() {
        FeedHoldSpeed.inBuildForTests = true;
        assertTrue("in and on", FeedHoldSpeed.holding());
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertFalse("in and off", FeedHoldSpeed.holding());
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        FeedHoldSpeed.inBuildForTests = false;
        assertFalse("out and on", FeedHoldSpeed.holding());
        FeedHoldSpeed.inBuildForTests = null;
        assertFalse("the stub says the patch is out", FeedHoldSpeed.holding());
        FeedHoldSpeed.inBuildForTests = true;
        SettingsContextRule.withoutContext(() -> assertFalse("no context", FeedHoldSpeed.holding()));
    }

    /**
     * #639: with Hold the middle to pause it on and the tap patch in, the 2x strips shrink to a third each, leaving the
     * middle third to the pause. Without the tap patch, with the switch off or with 2x off, the strips stay as they were.
     */
    @Test
    public void holdingTheMiddleToPauseLeavesTheSidesAThirdEach() {
        assertFalse(Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.defaultValue);
        assertEquals(Collections.singletonList(Settings.HOLD_FEED_VIDEO_FAST),
                Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.getParentSettings());
        FeedHoldSpeed.inBuildForTests = true;
        FeedHoldSpeed.tapPauseInBuildForTests = true;
        assertEquals("switch off", 0.5d, FeedHoldSpeed.strip(0.2d), 0d);
        Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.save(true);
        assertTrue(FeedHoldSpeed.middlePauses());
        assertEquals(1d / 3d, FeedHoldSpeed.strip(0.2d), 0d);
        FeedHoldSpeed.tapPauseInBuildForTests = false;
        assertFalse("no tap patch", FeedHoldSpeed.middlePauses());
        assertEquals("no tap patch", 0.5d, FeedHoldSpeed.strip(0.2d), 0d);
        FeedHoldSpeed.tapPauseInBuildForTests = null;
        assertFalse("the stub says the tap patch is out", FeedHoldSpeed.middlePauses());
        FeedHoldSpeed.tapPauseInBuildForTests = true;
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertFalse("2x off", FeedHoldSpeed.middlePauses());
        assertEquals("2x off", 0.2d, FeedHoldSpeed.strip(0.2d), 0d);
    }

    /** Keep 2x after you let go starts off, needs no restart and sits under the hold switch (#552). */
    @Test
    public void theKeepSwitchStartsOffUnderTheHoldSwitch() {
        assertFalse(Settings.HOLD_FEED_VIDEO_KEEP.get());
        assertFalse(Settings.HOLD_FEED_VIDEO_KEEP.rebootApp);
        assertEquals(Collections.singletonList(Settings.HOLD_FEED_VIDEO_FAST), Settings.HOLD_FEED_VIDEO_KEEP.getParentSettings());
        AtomicBoolean fast = new AtomicBoolean(true);
        FeedHoldSpeed.pressed(fast);
        assertFalse("off, the lift puts the speed back", FeedHoldSpeed.lifted(fast));
    }

    /**
     * On, a lift that ends a fast hold keeps the speed. The next hold resets the speed and plays fast
     * again, the way Instagram does, and its lift lets it go back; the hold after that keeps it again.
     */
    @Test
    public void aKeptLiftStaysFastUntilTheNextHoldsLift() {
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        AtomicBoolean fast = new AtomicBoolean(false);
        hold(fast);
        assertTrue("the first lift keeps 2x", FeedHoldSpeed.lifted(fast));
        hold(fast);
        assertFalse("the next lift lets it go", FeedHoldSpeed.lifted(fast));
        fast.set(false);
        hold(fast);
        assertTrue("and the one after keeps it again", FeedHoldSpeed.lifted(fast));
        String report = report();
        assertTrue(report, report.contains(FeedHoldSpeed.KEPT + " 2"));
        assertTrue(report, report.contains(FeedHoldSpeed.LET_GO + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_HOLD_FAST).toString(),
                HookStatus.missing(FamilyNames.FEED_HOLD_FAST).isEmpty());
    }

    /**
     * #552: with Only after a long hold on, a quick hold to peek at 2x goes back to normal on the
     * lift, and a hold that played at 2x for about 3 seconds keeps it, until the next hold's lift as
     * before. Without Keep 2x after you let go it keeps nothing.
     */
    @Test
    public void onlyALongHoldKeeps2x() {
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        Settings.HOLD_FEED_VIDEO_KEEP_LONG.save(true);
        long[] now = {10_000L};
        FeedHoldSpeed.clock = () -> now[0];
        AtomicBoolean fast = new AtomicBoolean(false);

        hold(fast);
        now[0] += FeedHoldSpeed.LONG_HOLD_MS - 1;
        assertFalse("a short hold lets 2x go", FeedHoldSpeed.lifted(fast));

        fast.set(false);
        hold(fast);
        now[0] += FeedHoldSpeed.LONG_HOLD_MS;
        assertTrue("a long one keeps it", FeedHoldSpeed.lifted(fast));
        hold(fast);
        now[0] += 100;
        assertFalse("and the next lift lets it go", FeedHoldSpeed.lifted(fast));

        fast.set(false);
        FeedHoldSpeed.lifted(fast);
        fast.set(true);
        assertFalse("a lift with no press told is short", FeedHoldSpeed.lifted(fast));

        Settings.HOLD_FEED_VIDEO_KEEP.save(false);
        fast.set(false);
        hold(fast);
        now[0] += FeedHoldSpeed.LONG_HOLD_MS * 2;
        assertFalse("it needs Keep 2x after you let go", FeedHoldSpeed.lifted(fast));
        assertEquals(Collections.singletonList(Settings.HOLD_FEED_VIDEO_KEEP), Settings.HOLD_FEED_VIDEO_KEEP_LONG.getParentSettings());
        assertFalse(Settings.HOLD_FEED_VIDEO_KEEP_LONG.rebootApp);
        String report = report();
        assertTrue(report, report.contains(FeedHoldSpeed.SHORT + " 2"));
        assertTrue(report, report.contains(FeedHoldSpeed.KEPT + " 1"));
    }

    /**
     * #552: Keep the name while you hold keeps the post's look on every hold while it and Hold for 2x
     * on feed videos are on, counting each, and lets Instagram show its hold look otherwise.
     */
    @Test
    public void keepTheNameKeepsTheLookOnlyWhileOn() {
        assertFalse("off to start", Settings.HOLD_FEED_VIDEO_KEEP_NAME.get());
        assertFalse("off", FeedHoldSpeed.keepsLook());

        Settings.HOLD_FEED_VIDEO_KEEP_NAME.save(true);
        assertTrue("on", FeedHoldSpeed.keepsLook());
        assertTrue("every hold", FeedHoldSpeed.keepsLook());

        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertFalse("it needs Hold for 2x on feed videos", FeedHoldSpeed.keepsLook());
        Settings.HOLD_FEED_VIDEO_FAST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedHoldSpeed.keepsLook());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("no context", FeedHoldSpeed.keepsLook()));

        assertEquals(Collections.singletonList(Settings.HOLD_FEED_VIDEO_FAST), Settings.HOLD_FEED_VIDEO_KEEP_NAME.getParentSettings());
        assertFalse(Settings.HOLD_FEED_VIDEO_KEEP_NAME.rebootApp);
        String report = report();
        assertTrue(report, report.contains(FeedHoldSpeed.LOOK_KEPT + " 2"));
    }

    /**
     * #860: Show 2x after you let go hands a kept lift Instagram's hold look only while it, Keep 2x after you let go
     * and Hold for 2x on feed videos are on, counting each, and puts the look to rest otherwise.
     */
    @Test
    public void showTwoTimesAfterTheLiftOnlyWhileOn() {
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        assertFalse("off to start", Settings.HOLD_FEED_VIDEO_KEEP_BADGE.get());
        assertFalse("off", FeedHoldSpeed.showsKept());

        Settings.HOLD_FEED_VIDEO_KEEP_BADGE.save(true);
        assertTrue("on", FeedHoldSpeed.showsKept());
        assertTrue("every kept lift", FeedHoldSpeed.showsKept());

        Settings.HOLD_FEED_VIDEO_KEEP.save(false);
        assertFalse("it needs Keep 2x after you let go", FeedHoldSpeed.showsKept());
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertFalse("it needs Hold for 2x on feed videos", FeedHoldSpeed.showsKept());
        Settings.HOLD_FEED_VIDEO_FAST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedHoldSpeed.showsKept());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("no context", FeedHoldSpeed.showsKept()));

        assertEquals(Collections.singletonList(Settings.HOLD_FEED_VIDEO_KEEP), Settings.HOLD_FEED_VIDEO_KEEP_BADGE.getParentSettings());
        assertFalse(Settings.HOLD_FEED_VIDEO_KEEP_BADGE.rebootApp);
        String report = report();
        assertTrue(report, report.contains(FeedHoldSpeed.BADGE_SHOWN + " 2"));
    }

    /**
     * A kept flag Instagram clears on its own, when the video leaves the screen, is forgotten at the
     * next press, so that hold's lift keeps 2x again instead of letting it go. Each feed has its own.
     */
    @Test
    public void aFlagInstagramClearedIsAFreshHold() {
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        AtomicBoolean fast = new AtomicBoolean(false);
        AtomicBoolean otherFeed = new AtomicBoolean(false);
        hold(fast);
        assertTrue(FeedHoldSpeed.lifted(fast));
        hold(otherFeed);
        assertTrue("another feed's first lift", FeedHoldSpeed.lifted(otherFeed));
        fast.set(false);
        hold(fast);
        assertTrue("kept again, not let go", FeedHoldSpeed.lifted(fast));
    }

    /** A lift whose hold never sped the video up, a missing flag, and every lift with a switch off or paused are Instagram's. */
    @Test
    public void aLiftThatWasntFastOrIsOffIsInstagrams() {
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        AtomicBoolean slow = new AtomicBoolean(false);
        FeedHoldSpeed.pressed(slow);
        assertFalse("not fast", FeedHoldSpeed.lifted(slow));
        FeedHoldSpeed.pressed(null);
        assertFalse("no flag", FeedHoldSpeed.lifted(null));

        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        AtomicBoolean fast = new AtomicBoolean(false);
        hold(fast);
        assertFalse("hold for 2x off", FeedHoldSpeed.lifted(fast));
        Settings.HOLD_FEED_VIDEO_FAST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        hold(fast);
        assertFalse("paused", FeedHoldSpeed.lifted(fast));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            hold(fast);
            assertFalse("no context", FeedHoldSpeed.lifted(fast));
        });
        hold(fast);
        assertTrue("back on", FeedHoldSpeed.lifted(fast));
    }

    /** What Instagram's press entry does with the flag: told first, then a reset, then 2x. */
    private static void hold(AtomicBoolean fast) {
        FeedHoldSpeed.pressed(fast);
        fast.set(false);
        assertTrue(fast.compareAndSet(false, true));
    }

    private static String report() {
        StringBuilder all = new StringBuilder();
        for (String line : HookStatus.report()) all.append(line).append('\n');
        return all.toString();
    }

    private static void assertInstagrams(String when) {
        assertFalse(when, FeedHoldSpeed.fast(0));
        assertTrue(when + ", Instagram's own yes", FeedHoldSpeed.fast(1));
        assertTrue(when + ", Instagram's fixed strip", FeedHoldSpeed.fixedStrip(1));
        assertFalse(when, FeedHoldSpeed.fixedStrip(0));
        assertEquals(when, 0.2d, FeedHoldSpeed.strip(0.2d), 0d);
    }
}
