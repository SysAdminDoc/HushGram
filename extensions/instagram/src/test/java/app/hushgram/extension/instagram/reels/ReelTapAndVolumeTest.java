/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Reels tap hook answers for each choice, and that off, paused and not ready are Instagram's own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelTapAndVolumeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void clean() {
        Settings.REEL_TAP_CHOICE.resetToDefault();
        Settings.KEEP_REELS_MUTED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.REEL_TAP_CHOICE.resetToDefault();
        Settings.KEEP_REELS_MUTED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReelTapAndVolume.forget();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    /** The choice starts at Instagram's own tap, so a tap pauses as it always has and nothing is counted. */
    @Test
    public void theChoiceStartsAtInstagramsDefault() {
        assertEquals(ReelTapChoice.DEFAULT, Settings.REEL_TAP_CHOICE.get());
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(FamilyNames.REEL_TAP_AND_VOLUME + ": invoked 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_MUTED));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_PAUSED));
    }

    /** Mute turns the pause into a mute and counts it each time. */
    @Test
    public void muteSendsTheTapToTheAudioToggleAndCountsIt() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.TAP_MUTED + " 2"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_PAUSED));
    }

    /** Pause leaves Instagram's pause alone and counts the taps it left to it. */
    @Test
    public void pauseLeavesTheTapToInstagramsPauseAndCountsIt() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.PAUSE);
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.TAP_PAUSED + " 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_MUTED));
    }

    /** Paused, a saved Mute answers Instagram's own: the pause happens and nothing is counted. */
    @Test
    public void pausedHushGramIsInstagramsOwnTap() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        assertEquals("the saved choice is kept", ReelTapChoice.MUTE, Settings.REEL_TAP_CHOICE.savedValue());
        assertFalse(report(), report().contains(ReelTapAndVolume.TAP_MUTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
    }

    /** Before the settings are ready the hook can't read the choice and takes Instagram's own tap. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsOwnTap() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        SettingsContextRule.withoutContext(() -> assertFalse(ReelTapAndVolume.muteInsteadOfPause()));
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        SettingsContextRule.withoutContext(() -> assertFalse(ReelTapAndVolume.keepPlaying()));
    }

    /** Keep playing (#554): mute says no, keep playing says yes, so the tap neither mutes nor pauses, and it's counted. */
    @Test
    public void keepPlayingLeavesThePlayingReelAloneAndCountsIt() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        assertFalse("it doesn't mute", ReelTapAndVolume.muteInsteadOfPause());
        assertTrue("it keeps playing", ReelTapAndVolume.keepPlaying());
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.TAP_KEPT_PLAYING + " 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_PAUSED));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_MUTED));
    }

    /** Every other choice says no to keep playing, so Mute's toggle and the pause still happen. */
    @Test
    public void onlyKeepPlayingKeepsItPlaying() {
        for (ReelTapChoice choice : new ReelTapChoice[] {ReelTapChoice.DEFAULT, ReelTapChoice.PAUSE, ReelTapChoice.MUTE}) {
            Settings.REEL_TAP_CHOICE.save(choice);
            assertFalse(choice.name(), ReelTapAndVolume.keepPlaying());
        }
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, Instagram's own tap", ReelTapAndVolume.keepPlaying());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue("back on", ReelTapAndVolume.keepPlaying());
    }

    /** A reel's screen with Instagram's play icon over the middle of it, laid out and shown. */
    private static final class Screen {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        final Activity activity = controller.get();
        final FrameLayout reel = new FrameLayout(activity);
        final FrameLayout overlay = new FrameLayout(activity);
        final View icon = new View(activity);

        Screen() {
            int id = View.generateViewId();
            icon.setId(id);
            ReelTapAndVolume.idForTests(activity, id);
            overlay.addView(icon, new FrameLayout.LayoutParams(90, 90, Gravity.CENTER));
            reel.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(reel, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            idle();
        }

        /** The middle of the icon, on screen. */
        float[] onIcon() {
            int[] at = new int[2];
            icon.getLocationOnScreen(at);
            return new float[] {at[0] + icon.getWidth() / 2f, at[1] + icon.getHeight() / 2f};
        }

        /** A point on the reel well clear of the icon. */
        float[] offIcon() {
            int[] at = new int[2];
            reel.getLocationOnScreen(at);
            return new float[] {at[0] + 10, at[1] + 10};
        }

        void touch(int action, float[] point) {
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, action, point[0], point[1], 0);
            ReelTapAndVolume.touch(activity, event);
            event.recycle();
        }

        void tap(float[] point) {
            touch(MotionEvent.ACTION_DOWN, point);
            touch(MotionEvent.ACTION_UP, point);
        }

        void close() {
            controller.pause().stop().destroy();
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * #554: with Keep playing, a tap right on the play icon shown over the reel pauses it, once, and
     * is counted. A tap anywhere else on the reel still keeps it playing.
     */
    @Test
    public void aTapOnTheShownPlayIconPauses() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        Screen screen = new Screen();
        try {
            screen.tap(screen.onIcon());
            assertFalse("the icon's tap pauses", ReelTapAndVolume.keepPlaying());
            String report = report();
            assertTrue(report, report.contains(ReelTapAndVolume.TAP_ON_PLAY_ICON + " 1"));
            assertFalse(report, report.contains(ReelTapAndVolume.TAP_KEPT_PLAYING));
            assertTrue("it pauses only that once", ReelTapAndVolume.keepPlaying());

            screen.tap(screen.offIcon());
            assertTrue("off the icon it keeps playing", ReelTapAndVolume.keepPlaying());
            assertTrue("no tap at all keeps playing", ReelTapAndVolume.keepPlaying());
            assertTrue(HookStatus.missing(FamilyNames.REEL_TAP_AND_VOLUME).toString(),
                    HookStatus.missing(FamilyNames.REEL_TAP_AND_VOLUME).isEmpty());
        } finally {
            screen.close();
        }
    }

    /** An icon that's hidden or see-through, itself or under a parent, isn't there to tap. */
    @Test
    public void aHiddenPlayIconKeepsPlaying() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        Screen screen = new Screen();
        try {
            float[] point = screen.onIcon();
            screen.icon.setVisibility(View.INVISIBLE);
            screen.tap(point);
            assertTrue("an invisible icon", ReelTapAndVolume.keepPlaying());
            screen.icon.setVisibility(View.VISIBLE);

            screen.icon.setAlpha(0f);
            screen.tap(point);
            assertTrue("a see-through icon", ReelTapAndVolume.keepPlaying());
            screen.icon.setAlpha(1f);

            screen.overlay.setAlpha(0f);
            screen.tap(point);
            assertTrue("under a see-through parent", ReelTapAndVolume.keepPlaying());
            screen.overlay.setAlpha(1f);

            screen.overlay.setVisibility(View.INVISIBLE);
            screen.tap(point);
            assertTrue("under a hidden parent", ReelTapAndVolume.keepPlaying());
            screen.overlay.setVisibility(View.VISIBLE);

            screen.tap(point);
            assertFalse("shown again, it pauses", ReelTapAndVolume.keepPlaying());
        } finally {
            screen.close();
        }
    }

    /**
     * Only a finger that goes down and comes up on the icon counts, with no second finger, and only
     * for a second after it came up. A tap Keep playing wasn't picked for doesn't count later.
     */
    @Test
    public void onlyAFreshTapThatStaysOnTheIconPauses() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        Screen screen = new Screen();
        try {
            screen.touch(MotionEvent.ACTION_DOWN, screen.onIcon());
            screen.touch(MotionEvent.ACTION_UP, screen.offIcon());
            assertTrue("came up off the icon", ReelTapAndVolume.keepPlaying());

            screen.touch(MotionEvent.ACTION_DOWN, screen.offIcon());
            screen.touch(MotionEvent.ACTION_UP, screen.onIcon());
            assertTrue("went down off the icon", ReelTapAndVolume.keepPlaying());

            screen.touch(MotionEvent.ACTION_DOWN, screen.onIcon());
            screen.touch(MotionEvent.ACTION_POINTER_DOWN, screen.onIcon());
            screen.touch(MotionEvent.ACTION_UP, screen.onIcon());
            assertTrue("a second finger", ReelTapAndVolume.keepPlaying());

            screen.touch(MotionEvent.ACTION_DOWN, screen.onIcon());
            screen.touch(MotionEvent.ACTION_CANCEL, screen.onIcon());
            screen.touch(MotionEvent.ACTION_UP, screen.onIcon());
            assertTrue("a gesture taken over", ReelTapAndVolume.keepPlaying());

            screen.tap(screen.onIcon());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ReelTapAndVolume.TAP_FRESH_MS + 1));
            assertTrue("a stale tap", ReelTapAndVolume.keepPlaying());

            screen.tap(screen.onIcon());
            screen.touch(MotionEvent.ACTION_DOWN, screen.offIcon());
            assertTrue("a newer finger went down", ReelTapAndVolume.keepPlaying());

            Settings.REEL_TAP_CHOICE.save(ReelTapChoice.PAUSE);
            screen.tap(screen.onIcon());
            Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
            assertTrue("tapped before Keep playing was picked", ReelTapAndVolume.keepPlaying());

            screen.tap(screen.onIcon());
            assertFalse("a fresh tap on it", ReelTapAndVolume.keepPlaying());
        } finally {
            screen.close();
        }
    }

    /** No activity, no touch, or a build without the icon's id leaves every tap to Keep playing, and never throws. */
    @Test
    public void withoutTheIconEveryTapKeepsPlaying() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.KEEP_PLAYING);
        ReelTapAndVolume.touch(null, null);
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
        ReelTapAndVolume.touch(null, down);
        down.recycle();
        assertTrue(ReelTapAndVolume.keepPlaying());

        Screen screen = new Screen();
        try {
            ReelTapAndVolume.touch(screen.activity, null);
            float[] point = screen.onIcon();
            ReelTapAndVolume.forget();
            screen.tap(point);
            assertTrue("this build has no such id", ReelTapAndVolume.keepPlaying());
            String missing = HookStatus.missing(FamilyNames.REEL_TAP_AND_VOLUME).toString();
            assertTrue(missing, missing.contains(ReelTapAndVolume.PLAY_ICON));
            assertFalse(missing, missing.contains("threw"));
        } finally {
            screen.close();
        }
    }

    /** The volume key starts at Instagram's own: no unmute is skipped, and nothing is counted. */
    @Test
    public void keepingMutedStartsOff() {
        assertFalse(Settings.KEEP_REELS_MUTED.get());
        assertFalse(ReelTapAndVolume.keepMuted(1, 0));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.REEL_TAP_AND_VOLUME + ": invoked 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.VOLUME_KEPT_MUTED));
    }

    /** On, a volume up skips the unmute and counts it, and a volume down or any other direction is Instagram's own. */
    @Test
    public void onItSkipsTheUnmuteOnlyForVolumeUp() {
        Settings.KEEP_REELS_MUTED.save(true);
        assertTrue(ReelTapAndVolume.keepMuted(1, 0));
        assertTrue(ReelTapAndVolume.keepMuted(1, 0));
        assertFalse("volume down", ReelTapAndVolume.keepMuted(-1, 0));
        assertFalse("same level", ReelTapAndVolume.keepMuted(0, 0));
        assertFalse("toggle mute", ReelTapAndVolume.keepMuted(101, 0));
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.VOLUME_KEPT_MUTED + " 2"));
    }

    /**
     * A reel whose sound is already on keeps Instagram's unmute (a running fade-in finishes), and nothing is
     * counted. So does a build that couldn't read the state (-1).
     */
    @Test
    public void aReelWithItsSoundOnKeepsTheUnmute() {
        Settings.KEEP_REELS_MUTED.save(true);
        assertFalse("sound on", ReelTapAndVolume.keepMuted(1, 1));
        assertFalse("state unknown", ReelTapAndVolume.keepMuted(1, -1));
        assertFalse(report(), report().contains(ReelTapAndVolume.VOLUME_KEPT_MUTED));
        assertTrue("sound off", ReelTapAndVolume.keepMuted(1, 0));
        assertTrue(report(), report().contains(ReelTapAndVolume.VOLUME_KEPT_MUTED + " 1"));
    }

    /** Paused, a saved switch answers Instagram's own and counts nothing. */
    @Test
    public void pausedHushGramLeavesTheUnmuteAlone() {
        Settings.KEEP_REELS_MUTED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelTapAndVolume.keepMuted(1, 0));
        assertTrue("the saved choice is kept", Settings.KEEP_REELS_MUTED.savedValue());
        assertFalse(report(), report().contains(ReelTapAndVolume.VOLUME_KEPT_MUTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelTapAndVolume.keepMuted(1, 0));
    }

    /** Before the settings are ready the hook cannot read the switch and takes Instagram's own unmute. */
    @Test
    public void beforeTheSettingsAreReadyTheUnmuteHappens() {
        Settings.KEEP_REELS_MUTED.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(ReelTapAndVolume.keepMuted(1, 0)));
    }

    /**
     * The speaker icon (#608) toggles the reel the last started controller plays: before any reel has
     * started there's nothing to toggle and no sound to read, and a null handed over keeps the one held.
     */
    @Test
    public void theSpeakerIconTogglesTheReelLastStarted() {
        ReelTapAndVolume.forget();
        assertFalse("no reel has started", ReelTapAndVolume.toggle());
        assertEquals(-1, ReelTapAndVolume.soundOn());
        assertFalse(report(), report().contains(ReelTapAndVolume.ICON_TOGGLED));

        Object controller = new Object();
        ReelTapAndVolume.resuming(controller);
        assertTrue(ReelTapAndVolume.toggle());
        ReelTapAndVolume.resuming(null);
        assertTrue("a null keeps the controller held", ReelTapAndVolume.toggle());
        assertTrue(report(), report().contains(ReelTapAndVolume.ICON_TOGGLED + " 2"));
        // Unpatched, the read answers it can't tell, and the icon shows sound on.
        assertEquals(-1, ReelTapAndVolume.soundOn());
    }

    /** Paused or before the settings are ready, the speaker icon toggles nothing. */
    @Test
    public void pausedTheSpeakerIconTogglesNothing() {
        ReelTapAndVolume.resuming(new Object());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelTapAndVolume.toggle());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(ReelTapAndVolume.toggle()));
        assertFalse(report(), report().contains(ReelTapAndVolume.ICON_TOGGLED));
    }
}
