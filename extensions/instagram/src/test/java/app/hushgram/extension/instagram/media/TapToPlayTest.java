/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The rule Tap to play holds every start to: a start goes ahead on an armed player or within a
 * second of a tap, whatever reason Instagram gives. Everything else is held. Off, paused, before
 * the settings are ready, or when the rule throws, every start goes ahead, and the autoplay check
 * answers what Instagram decided.
 */
@RunWith(RobolectricTestRunner.class)
public class TapToPlayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Reasons Instagram 449 hands playInternal and IgGrootPlayer's play. */
    private static final List<String> REASONS = Arrays.asList(
            "autoplay", "resume", "start", "retry", "play_after_recovery", "seek_force_pause", "ig_live_pip");

    @Before
    public void start() {
        // Moves the clock well past zero, so "a tap five seconds ago" is a time the clock has seen.
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
        Settings.TAP_TO_PLAY.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAP_TO_PLAY.resetToDefault();
        Settings.TAP_TO_PLAY_SCOPE.resetToDefault();
        Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    /** Stands in for IgVideoPlayerImpl's state enum: only the constant names matter. */
    private enum State { IDLE, PREPARING, PREPARED, PLAYING, PAUSED, STOPPING }

    private static void tapAt(long upAt) {
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 50, upAt - 90, 8);
        TapClock.record(MotionEvent.ACTION_UP, 51, 52, upAt, 8);
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.TAP_TO_PLAY.resetToDefault();
        assertFalse("the patch is in the default selection, so the reader turns it on", Settings.TAP_TO_PLAY.get());
    }

    @Test
    public void noStartGoesAheadWithoutATap() {
        for (String reason : REASONS) {
            assertFalse(reason, TapToPlay.allowStart(new Object(), reason, 0));
            assertFalse(reason, TapToPlay.allowDirectStart(new Object(), reason));
        }
        assertFalse("no reason at all", TapToPlay.allowStart(new Object(), null, 0));
        assertEquals("a held start arms nothing", 0, TapToPlay.armedCount());
    }

    @Test
    public void aStartWithinASecondOfATapGoesAhead() {
        long now = 100_000;
        tapAt(now - TapToPlay.TAP_WINDOW_MS);
        assertTrue("that tap's own first start", TapToPlay.decide(new Object(), "autoplay", now - 900, ""));
        assertTrue("another start on it, exactly a second after it", TapToPlay.decide(new Object(), "autoplay", now, ""));
        tapAt(now - TapToPlay.TAP_WINDOW_MS - 1);
        assertTrue("that tap's own first start", TapToPlay.decide(new Object(), "autoplay", now - TapToPlay.TAP_WINDOW_MS + 9, ""));
        assertFalse("another start just over a second after it", TapToPlay.decide(new Object(), "autoplay", now, ""));
        tapAt(now + 5);
        assertFalse("a tap the clock hasn't reached yet", TapToPlay.decide(new Object(), "autoplay", now, ""));
    }

    /**
     * A story a tap opened can take seconds to load, so the tap's first start goes ahead up to
     * {@link TapToPlay#LOAD_WINDOW_MS} after it. The next start past the second doesn't, nor does a
     * first start later than that, nor one after a drag.
     */
    @Test
    public void aTapsFirstStartMayComeAsLateAsALoad() {
        long now = 400_000;
        tapAt(now - 1_700);
        assertTrue("the story, 1.7 seconds after the tap", TapToPlay.decide(new Object(), "autoplay", now, ""));
        assertFalse("the next story, advanced to on its own", TapToPlay.decide(new Object(), "autoplay", now + 5_000, ""));
        assertFalse("another start on the same tap", TapToPlay.decide(new Object(), "autoplay", now + 1, ""));

        tapAt(now - TapToPlay.LOAD_WINDOW_MS);
        assertTrue("a first start exactly a load after the tap", TapToPlay.decide(new Object(), "autoplay", now, ""));
        tapAt(now - TapToPlay.LOAD_WINDOW_MS - 1);
        assertFalse("a first start later than a load", TapToPlay.decide(new Object(), "autoplay", now, ""));

        tapAt(now - 1_700);
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, now - 1_000, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 50, 300, now - 900, 8);
        TapClock.record(MotionEvent.ACTION_UP, 50, 100, now - 800, 8);
        assertFalse("a drag since the tap", TapToPlay.decide(new Object(), "autoplay", now, ""));
    }

    /**
     * A tap that plays a video again on its still armed player uses the tap, so a second video
     * can't start on it a couple of seconds later. An armed restart more than a second after a tap
     * leaves that tap's first start to the video it opened.
     */
    @Test
    public void anArmedPlayersStartRightAfterATapUsesIt() {
        long now = 500_000;
        Object player = new Object();
        tapAt(now - 10_000);
        assertTrue(TapToPlay.decide(player, "autoplay", now - 9_900, ""));

        tapAt(now - 100);
        assertTrue("the video played again", TapToPlay.decide(player, "resume", now, ""));
        assertFalse("another video 1.5 seconds after the tap", TapToPlay.decide(new Object(), "autoplay", now + 1_400, ""));

        tapAt(now + 10_000);
        assertTrue("an armed restart 1.5 seconds after a tap", TapToPlay.decide(player, "resume", now + 11_500, ""));
        assertTrue("the story that tap opened", TapToPlay.decide(new Object(), "autoplay", now + 11_600, ""));
    }

    /** A story you tap starts with "autoplay", and playInternal only ever hears "autoplay" or "resume". */
    @Test
    public void anyReasonRightAfterATapGoesAhead() {
        long now = 200_000;
        tapAt(now - 300);
        for (String reason : REASONS) assertTrue(reason, TapToPlay.decide(new Object(), reason, now, ""));
    }

    @Test
    public void anArmedPlayerRestartsUntilItsPaused() {
        Object player = new Object();
        Object other = new Object();
        TapToPlayForTests.tapEnded(100);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        assertTrue(TapToPlay.armed(player));
        TapClock.forget();

        assertTrue("IgGrootPlayer's own play of the same player", TapToPlay.allowDirectStart(player, "autoplay"));
        assertTrue("a resume", TapToPlay.allowStart(player, "resume", 0));
        assertFalse("another player", TapToPlay.allowStart(other, "autoplay", 0));

        TapToPlay.paused(player, "scroll");
        assertFalse(TapToPlay.armed(player));
        assertFalse("the start after a pause", TapToPlay.allowStart(player, "resume", 0));

        TapToPlayForTests.tapEnded(10);
        assertTrue("a tap plays it again", TapToPlay.allowStart(player, "resume", 0));
    }

    /** A seek pauses and plays again, and a reel loops the same way: neither undoes the tap. */
    @Test
    public void momentaryPausesKeepThePlayerArmed() {
        for (String reason : TapToPlay.MOMENTARY) {
            Object player = new Object();
            TapToPlayForTests.tapEnded(10);
            assertTrue(TapToPlay.allowDirectStart(player, "start"));
            TapClock.forget();
            TapToPlay.paused(player, reason);
            assertTrue(reason, TapToPlay.armed(player));
            assertTrue(reason, TapToPlay.allowDirectStart(player, reason));
        }
        Object held = new Object();
        TapToPlay.paused(held, "seek_force_pause");
        assertFalse("a seek on a held player doesn't start it", TapToPlay.allowDirectStart(held, "seek_force_pause"));
        Object paused = new Object();
        TapToPlayForTests.tapEnded(10);
        assertTrue(TapToPlay.allowDirectStart(paused, "start"));
        TapToPlay.paused(paused, null);
        assertFalse("a pause with no reason", TapToPlay.armed(paused));
    }

    /**
     * The long video viewer's scrubber pauses a playing video with "Seek start" and plays it again
     * when the drag ends, with an automatic start and no tap, since a drag isn't one. The video
     * plays on from where it was dragged to.
     */
    @Test
    public void aDragOfTheLongVideoScrubberKeepsItPlaying() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(10);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapClock.forget();

        TapToPlay.paused(player, "Seek start");

        assertTrue("the seek's pause disarmed it", TapToPlay.armed(player));
        assertTrue("the play at the drag's end", TapToPlay.allowStart(player, "autoplay", 0));
    }

    /**
     * The music picker's clip slider, in Notes and stories, pauses the song with
     * "user_paused_video" a moment into a drag and plays it again right after the finger comes up,
     * with no tap, since a drag isn't one (#263). The song plays on. The same pause from the
     * picker's pause button, with no drag under way, still ends the start.
     */
    @Test
    public void aDragOfTheMusicSliderKeepsTheSongPlaying() {
        Object song = new Object();
        Object pressed = new Object();
        TapToPlayForTests.tapEnded(10);
        assertTrue(TapToPlay.allowDirectStart(song, "start"));
        assertTrue(TapToPlay.allowDirectStart(pressed, "start"));
        TapClock.forget();

        long drag = SystemClock.uptimeMillis();
        TapClock.record(MotionEvent.ACTION_DOWN, 760, 2195, drag, 8);
        TapToPlay.paused(pressed, TapToPlay.DRAG_PAUSE);
        assertFalse("a finger that hasn't moved yet isn't a drag", TapToPlay.armed(pressed));
        TapClock.record(MotionEvent.ACTION_MOVE, 735, 2195, drag + 200, 8);
        TapToPlay.paused(song, TapToPlay.DRAG_PAUSE);
        assertTrue("the slider's pause mid-drag", TapToPlay.armed(song));
        TapClock.record(MotionEvent.ACTION_UP, 380, 2195, drag + 3_000, 8);
        assertTrue("the play right after the release", TapToPlay.allowDirectStart(song, "start"));

        TapToPlay.paused(song, TapToPlay.DRAG_PAUSE);
        assertFalse("the pause button's pause", TapToPlay.armed(song));
        assertFalse(TapToPlay.allowDirectStart(song, "start"));
    }

    /** Debug logging says what ended a start, and a pause Instagram plays on from ends none. */
    @Test
    public void debugLoggingSaysWhatEndedAStart() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Object seeking = new Object();
        Object scrolled = new Object();
        Object rebound = new Object();
        TapToPlayForTests.tapEnded(10);
        for (Object player : Arrays.asList(seeking, scrolled, rebound)) assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapClock.forget();
        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);

        TapToPlay.paused(seeking, "Seek start");
        TapToPlay.paused(scrolled, "scroll");
        TapToPlay.paused(new Object(), "scroll");
        TapToPlay.rebound(rebound);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tap to play: a pause for scroll ends a start"));
        assertTrue(report, report.contains("Tap to play: a new video ends a start"));
        assertFalse(report, report.contains("Seek start ends"));
    }

    /** A new video disarms, but not a prepare right after the start the gate just let through. */
    @Test
    public void aNewVideoDisarmsButTheStartsOwnPrepareDoesnt() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapToPlay.rebound(player);
        assertTrue("the prepare right after the start", TapToPlay.armed(player));

        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);
        TapToPlay.rebound(player);
        assertFalse("the next video", TapToPlay.armed(player));
        assertFalse(TapToPlay.allowStart(player, "autoplay", 0));
    }

    @Test
    public void aRapidSwipeCannotReuseThePreviousTapOrThePlayersPrepareGrace() {
        Object player = new Object();
        tapAt(SystemClock.uptimeMillis());
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapToPlay.rebound(player);
        assertTrue("the tap's own prepare", TapToPlay.armed(player));

        SystemClock.sleep(10);
        long swipe = SystemClock.uptimeMillis();
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, swipe, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 50, 300, swipe, 8);
        assertFalse("another player cannot borrow the previous tap during a swipe",
                TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertTrue("scrolling alone doesn't interrupt the current video", TapToPlay.armed(player));
        TapToPlay.rebound(player);
        assertFalse("a new video after the swipe cannot borrow the previous start", TapToPlay.armed(player));
        TapClock.record(MotionEvent.ACTION_UP, 50, 100, swipe, 8);
        assertFalse(TapToPlay.allowStart(player, "autoplay", 0));
        assertFalse(TapToPlay.allowDirectStart(player, "start"));

        SystemClock.sleep(1);
        tapAt(SystemClock.uptimeMillis());
        assertTrue("the next video's own tap works", TapToPlay.allowStart(player, "autoplay", 0));
        TapToPlay.rebound(player);
        assertTrue("that tap keeps its own prepare grace", TapToPlay.armed(player));
    }

    /** Players are told apart by identity, so one whose equals claims another is still itself. */
    @Test
    public void playersAreToldApartByIdentity() {
        Object player = new AlwaysEqual();
        Object twin = new AlwaysEqual();
        TapToPlayForTests.tapEnded(20);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapClock.forget();
        assertFalse(TapToPlay.allowStart(twin, "autoplay", 0));
        TapToPlay.paused(twin, "scroll");
        assertTrue(TapToPlay.armed(player));
    }

    /** An armed player Instagram drops is forgotten with it: the gate holds no player in memory. */
    @Test
    public void aDroppedPlayerIsForgotten() throws InterruptedException {
        TapToPlayForTests.tapEnded(20);
        WeakReference<Object> dropped = armOne();
        for (int i = 0; i < 100 && dropped.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the gate kept the player alive", dropped.get());
        assertEquals(0, TapToPlay.armedCount());
    }

    private static WeakReference<Object> armOne() {
        Object player = new Object();
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        assertEquals(1, TapToPlay.armedCount());
        return new WeakReference<>(player);
    }

    @Test
    public void offPausedOrNotReadyEveryStartGoesAhead() {
        Settings.TAP_TO_PLAY.save(false);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertTrue(TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue("Instagram's own answer", TapToPlay.autoplayAllowed(true));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertTrue(TapToPlay.autoplayAllowed(true));
        PauseForTests.resume();
        assertFalse("the control: running, the same start is held", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertEquals("nothing armed while off or paused", 0, TapToPlay.armedCount());
    }

    @Test
    public void aStartBeforeTheSettingsAreReadyGoesAhead() {
        SettingsContextRule.withoutContext(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
            assertTrue(TapToPlay.autoplayAllowed(true));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
            assertTrue(TapToPlay.autoplayAllowed(true));
        });
        assertFalse("the control: ready, the same start is held", TapToPlay.allowStart(new Object(), "autoplay", 0));
    }

    @Test
    public void aFailureLetsTheStartGoAheadAndTheReportSaysSo() {
        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertEquals(Collections.singletonList("a working 'player start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY));
        assertFalse("only the one start went ahead", TapToPlay.allowStart(new Object(), "autoplay", 0));

        TapToPlay.failNext = new IllegalStateException("the rule failed");
        assertTrue(TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue(String.join("\n", HookStatus.missing(FamilyNames.TAP_TO_PLAY)),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY).contains("a working 'direct start' hook (it threw "
                        + IllegalStateException.class.getName() + ")"));
    }

    @Test
    public void eachHookCountsAndBindsInTheReport() {
        TapToPlay.allowStart(new Object(), "autoplay", 0);
        TapToPlay.allowDirectStart(new Object(), "autoplay");
        TapToPlay.paused(new Object(), "scroll");
        TapToPlay.rebound(new Object());
        TapToPlay.autoplayAllowed(true);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.TAP_TO_PLAY + ": invoked 5, 5 found, 0 missing"));
    }

    /**
     * Only a reel Instagram's resume can restart goes down it: prepared, where the gate leaves a held
     * reel, or paused. Idle, loading, stopping or playing, the tap does what Instagram decided.
     */
    @Test
    public void aTapOnAPreparedOrPausedReelStartsIt() {
        Object navigator = new Object();
        for (State state : State.values()) {
            TapToPlay.reelStates = asked -> {
                assertSame(navigator, asked);
                return state;
            };
            boolean resumable = state == State.PREPARED || state == State.PAUSED;
            assertEquals(state.name(), resumable, TapToPlay.resumeOnTap(false, navigator));
        }
        TapToPlay.reelStates = asked -> null;
        assertFalse("no player under the tap: Instagram's answer", TapToPlay.resumeOnTap(false, navigator));
        assertFalse(TapToPlay.resumeOnTap(false, null));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    /** A reel you paused yourself already resumes. The reader isn't asked, so it can't turn that yes into a no. */
    @Test
    public void instagramsOwnResumeNeverAsksTheReader() {
        TapToPlay.reelStates = asked -> {
            throw new AssertionError("asked about a tap Instagram already resumes");
        };
        assertTrue(TapToPlay.resumeOnTap(true, new Object()));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    /**
     * The sequence on the phone. Instagram's own start of the reel was held. A tap lands on it, the
     * tap takes the resume path, and the start that path makes on the same tap goes ahead and arms
     * the player. Without that tap, the same start is held.
     */
    @Test
    public void aTapOnAHeldReelResumesItAndThatStartGoesAhead() {
        long now = 300_000;
        Object player = new Object();
        assertFalse("Instagram's own start, with no tap", TapToPlay.decide(player, "autoplay", now - 5_000, ""));
        TapToPlay.reelStates = asked -> State.PREPARED;
        tapAt(now);
        assertTrue("the tap takes the resume path", TapToPlay.resumeOnTap(false, new Object()));
        assertTrue("the resume's start, on the tap", TapToPlay.decide(player, "resume", now + 20, ""));
        assertTrue("armed, so it plays on", TapToPlay.armed(player));
        assertFalse("the same start with the tap long gone",
                TapToPlay.decide(new Object(), "resume", now + TapToPlay.TAP_WINDOW_MS + 1, ""));
    }

    @Test
    public void offPausedOrNotReadyATapOnAReelDoesWhatInstagramDecided() {
        TapToPlay.reelStates = asked -> State.PREPARED;
        Settings.TAP_TO_PLAY.save(false);
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(TapToPlay.resumeOnTap(false, new Object())));
        assertTrue("the control: on, the same tap starts the reel", TapToPlay.resumeOnTap(false, new Object()));
    }

    @Test
    public void anUnfilledReaderOrAFailureLeavesTheTapToInstagram() {
        // forget() put back the stub the patch fills in.
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        List<String> missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("method ClipsVideoPlayerController#the reel's state"));

        TapToPlay.reelStates = asked -> {
            throw new IllegalStateException("the controller failed");
        };
        assertFalse(TapToPlay.resumeOnTap(false, new Object()));
        missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("a working 'Reels tap' hook (it threw "
                + IllegalStateException.class.getName() + ")"));
    }

    /** A press held [heldFor] ms that ended at [upAt]. */
    private static void holdEnded(long upAt, long heldFor) {
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 50, upAt - heldFor, 8);
        TapClock.record(MotionEvent.ACTION_UP, 51, 52, upAt, 8);
    }

    private static long aHold() {
        return ViewConfiguration.getLongPressTimeout() + 200;
    }

    /**
     * The sequence on the phone. Instagram advanced to a story by itself and the gate held its start.
     * A press, a hold and a release: the story player's resume answers no, since the story never
     * played, and the hook turns that into a yes. The start that resume makes goes ahead on the
     * release, and the story plays.
     */
    @Test
    public void theReleaseOfAHoldStartsAHeldStory() {
        Object groot = new Object();
        Object storyPlayer = new Object();
        TapToPlay.storyPlayers = asked -> {
            assertSame(storyPlayer, asked);
            return groot;
        };
        assertFalse("Instagram's advance, with no tap", TapToPlay.allowDirectStart(groot, "autoplay"));
        holdEnded(SystemClock.uptimeMillis(), aHold());
        assertTrue("the release takes the resume path", TapToPlay.resumeHeldStory(false, storyPlayer));
        assertTrue("the resume's start, on the release", TapToPlay.allowDirectStart(groot, "resume"));
        assertTrue("armed, so it plays on", TapToPlay.armed(groot));
        assertFalse("it played, so it's no longer held", TapToPlay.resumeHeldStory(false, storyPlayer));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    /**
     * A quick tap moves between stories, so its release gets Instagram's answer, and so do a release
     * long after the hold (a sheet closing), a story the gate never held, one with no player yet and
     * a held player that has been given a new story since.
     */
    @Test
    public void onlyAHoldsReleaseOnAHeldStoryStartsIt() {
        Object groot = new Object();
        TapToPlay.storyPlayers = asked -> groot;
        assertFalse(TapToPlay.allowDirectStart(groot, "autoplay"));
        long now = SystemClock.uptimeMillis();
        holdEnded(now, 90);
        assertFalse("a quick tap", TapToPlay.resumeHeldStory(false, new Object()));
        holdEnded(now - TapToPlay.TAP_WINDOW_MS - 1, aHold());
        assertFalse("a hold that ended too long ago", TapToPlay.resumeHeldStory(false, new Object()));

        holdEnded(now, aHold());
        TapToPlay.storyPlayers = asked -> new Object();
        assertFalse("a story the gate never held", TapToPlay.resumeHeldStory(false, new Object()));
        TapToPlay.storyPlayers = asked -> null;
        assertFalse("no player yet", TapToPlay.resumeHeldStory(false, new Object()));
        assertFalse(TapToPlay.resumeHeldStory(false, null));
        TapToPlay.storyPlayers = asked -> groot;
        assertTrue("the control: the same release on the held story", TapToPlay.resumeHeldStory(false, new Object()));
        TapToPlay.rebound(groot);
        assertFalse("the held player got a new story", TapToPlay.resumeHeldStory(false, new Object()));
    }

    /** A story Instagram paused while it played already resumes. The reader isn't asked. */
    @Test
    public void instagramsOwnStoryResumeNeverAsksTheReader() {
        TapToPlay.storyPlayers = asked -> {
            throw new AssertionError("asked about a resume Instagram already makes");
        };
        holdEnded(SystemClock.uptimeMillis(), aHold());
        assertTrue(TapToPlay.resumeHeldStory(true, new Object()));
        assertTrue(HookStatus.missing(FamilyNames.TAP_TO_PLAY).toString(), HookStatus.missing(FamilyNames.TAP_TO_PLAY).isEmpty());
    }

    @Test
    public void offPausedOrNotReadyAStoryReleaseDoesWhatInstagramDecided() {
        Object groot = new Object();
        TapToPlay.storyPlayers = asked -> groot;
        assertFalse(TapToPlay.allowDirectStart(groot, "autoplay"));
        holdEnded(SystemClock.uptimeMillis(), aHold());
        Settings.TAP_TO_PLAY.save(false);
        assertFalse(TapToPlay.resumeHeldStory(false, new Object()));
        Settings.TAP_TO_PLAY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(TapToPlay.resumeHeldStory(false, new Object()));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(TapToPlay.resumeHeldStory(false, new Object())));
        assertTrue("the control: on, the same release starts the story", TapToPlay.resumeHeldStory(false, new Object()));
    }

    @Test
    public void anUnfilledStoryReaderOrAFailureLeavesTheReleaseToInstagram() {
        holdEnded(SystemClock.uptimeMillis(), aHold());
        // forget() put back the stub the patch fills in.
        assertFalse(TapToPlay.resumeHeldStory(false, new Object()));
        List<String> missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("field the story player#its IgGrootPlayer"));

        TapToPlay.storyPlayers = asked -> {
            throw new IllegalStateException("the story player failed");
        };
        assertFalse(TapToPlay.resumeHeldStory(false, new Object()));
        missing = HookStatus.missing(FamilyNames.TAP_TO_PLAY);
        assertTrue(missing.toString(), missing.contains("a working 'story release' hook (it threw "
                + IllegalStateException.class.getName() + ")"));
    }

    /** While the switch is on, the check answers no, so the feed draws its play button. A no stays a no. */
    @Test
    public void theAutoplayCheckAnswersNoWhileTheSwitchIsOn() {
        assertFalse(TapToPlay.autoplayAllowed(true));
        assertFalse("Instagram's own no, data saver on", TapToPlay.autoplayAllowed(false));
        Settings.TAP_TO_PLAY.save(false);
        assertTrue(TapToPlay.autoplayAllowed(true));
        assertFalse(TapToPlay.autoplayAllowed(false));
    }

    /** The patch hands the check's answer over as an int, since a boolean method may return one. Non-zero is yes. */
    @Test
    public void theAutoplayChecksIntEntryReadsNonZeroAsYes() {
        assertFalse("a yes handed over as 1 stayed", TapToPlay.autoplayAllowed(1));
        assertFalse(TapToPlay.autoplayAllowed(2));
        assertFalse(TapToPlay.autoplayAllowed(0));
        Settings.TAP_TO_PLAY.save(false);
        assertTrue("with the switch off, 1 lost Instagram's yes", TapToPlay.autoplayAllowed(1));
        assertTrue("2 is a yes too", TapToPlay.autoplayAllowed(2));
        assertFalse(TapToPlay.autoplayAllowed(0));
    }

    /** The Reels tap's decision comes over as an int too, non-zero for resume. */
    @Test
    public void theReelTapsIntEntryReadsNonZeroAsResume() {
        TapToPlay.reelStates = asked -> State.PLAYING;
        assertFalse("a playing reel's pause, handed over as 0", TapToPlay.resumeOnTap(0, new Object()));
        assertTrue("Instagram's resume, handed over as 1", TapToPlay.resumeOnTap(1, new Object()));
        assertTrue("2 is a resume too", TapToPlay.resumeOnTap(2, new Object()));
        TapToPlay.reelStates = asked -> State.PREPARED;
        assertTrue("a held reel's tap, handed over as 0, still starts it", TapToPlay.resumeOnTap(0, new Object()));
    }

    /** The story player's resume flag comes over as an int too, non-zero for resume. */
    @Test
    public void theStoryReleasesIntEntryReadsNonZeroAsResume() {
        Object groot = new Object();
        TapToPlay.storyPlayers = asked -> groot;
        assertFalse("Instagram's advance, with no tap", TapToPlay.allowDirectStart(groot, "autoplay"));
        long now = SystemClock.uptimeMillis();
        holdEnded(now, 90);
        assertFalse("a quick tap's release, handed over as 0", TapToPlay.resumeHeldStory(0, new Object()));
        assertTrue("Instagram's resume, handed over as 1", TapToPlay.resumeHeldStory(1, new Object()));
        assertTrue("2 is a resume too", TapToPlay.resumeHeldStory(2, new Object()));
        holdEnded(now, aHold());
        assertTrue("a hold's release on the held story, handed over as 0, starts it", TapToPlay.resumeHeldStory(0, new Object()));
    }

    /**
     * Instagram's auto scroller moving on counts as a tap on the reel it moves to: that reel's first
     * start within a load window goes ahead and arms its player, so its own restarts go ahead too,
     * and the move from its end starts the reel after it.
     */
    @Test
    @Config(sdk = {28, 37})
    public void autoScrollStartsTheReelItMovesToAndArmsIt() {
        long now = 700_000;
        Object next = new Object();
        TapToPlay.scrolledAt(now - 1_500);
        assertTrue("the reel auto scroll moved to", TapToPlay.decide(next, "autoplay", now, "", true));
        assertTrue(TapToPlay.armed(next));
        assertTrue("its own play, long after the move", TapToPlay.decide(next, "autoplay", now + 30_000, " (direct)", true));

        Object after = new Object();
        TapToPlay.scrolledAt(now + 40_000);
        assertTrue("the move from that reel's end starts the one after it", TapToPlay.decide(after, "autoplay", now + 40_400, "", true));
        assertTrue(TapToPlay.armed(after));
    }

    /** A move covers one start: a second player starting in the same window waits for a tap. */
    @Test
    @Config(sdk = {28, 37})
    public void aMoveStartsOnePlayerOnly() {
        long now = 710_000;
        Object next = new Object();
        Object other = new Object();
        TapToPlay.scrolledAt(now);
        assertTrue(TapToPlay.decide(next, "autoplay", now + 300, "", true));
        assertFalse("a second player in the window", TapToPlay.decide(other, "autoplay", now + 600, "", true));
        assertFalse(TapToPlay.armed(other));
    }

    /** A first start later than a load after the move waits, and so does one the clock has before the move. */
    @Test
    @Config(sdk = {28, 37})
    public void aStartLaterThanALoadAfterTheMoveIsHeld() {
        long now = 720_000;
        TapToPlay.scrolledAt(now - TapToPlay.LOAD_WINDOW_MS - 1);
        assertFalse("just over a load after the move", TapToPlay.decide(new Object(), "autoplay", now, "", true));
        TapToPlay.scrolledAt(now - TapToPlay.LOAD_WINDOW_MS);
        assertTrue("exactly a load after it", TapToPlay.decide(new Object(), "autoplay", now, "", true));
        TapToPlay.scrolledAt(now + 5);
        assertFalse("a move the clock hasn't reached yet", TapToPlay.decide(new Object(), "autoplay", now, "", true));
    }

    /**
     * An armed player's start goes ahead without the move and leaves it to the reel the scroller
     * moves to, so a reel that loops before the pager settles doesn't use it up. Nor does a start
     * with no player, which plays nothing.
     */
    @Test
    @Config(sdk = {28, 37})
    public void anArmedPlayerOrNoPlayerLeavesTheMoveToTheNextReel() {
        long now = 730_000;
        Object playing = new Object();
        tapAt(now - 20_000);
        assertTrue(TapToPlay.decide(playing, "autoplay", now - 19_900, "", true));
        TapToPlay.scrolledAt(now);
        assertTrue("the reel that ended, looping", TapToPlay.decide(playing, "paused_for_replay", now + 100, " (direct)", true));
        assertFalse("a start with no player", TapToPlay.decide(null, "autoplay", now + 150, "", true));
        assertTrue("the reel auto scroll moved to", TapToPlay.decide(new Object(), "autoplay", now + 400, "", true));
        assertFalse("and nothing after it", TapToPlay.decide(new Object(), "autoplay", now + 500, "", true));
    }

    /** A swipe after the move ends it, so the reel you swipe to waits for a tap, as it would with auto scroll off. */
    @Test
    @Config(sdk = {28, 37})
    public void aSwipeEndsAMoveNoStartHasUsed() {
        long now = 740_000;
        TapToPlay.scrolledAt(now);
        TapClock.record(MotionEvent.ACTION_DOWN, 50, 500, now + 100, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 50, 300, now + 150, 8);
        TapClock.record(MotionEvent.ACTION_UP, 50, 100, now + 200, 8);
        assertFalse("the reel the swipe moved to", TapToPlay.decide(new Object(), "autoplay", now + 500, "", true));
    }

    /** Through the hooks Instagram calls, on its clock: the move, then the next reel's playInternal and its IgGrootPlayer's play. */
    @Test
    @Config(sdk = {28, 37})
    public void theAutoScrollHookStartsTheNextReelThroughTheGate() {
        Object next = new Object();
        TapToPlay.autoScrolled();
        SystemClock.sleep(800);
        assertTrue("playInternal of the reel auto scroll moved to", TapToPlay.allowStart(next, "autoplay", 1));
        assertTrue("its IgGrootPlayer's play", TapToPlay.allowDirectStart(next, "autoplay"));
        assertFalse("another player", TapToPlay.allowStart(new Object(), "autoplay", 1));

        TapToPlay.autoScrolled();
        SystemClock.sleep(TapToPlay.LOAD_WINDOW_MS + 1);
        assertFalse("a start later than a load after the move", TapToPlay.allowStart(new Object(), "autoplay", 1));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));
    }

    /** Off, paused or before the settings are ready, a move records nothing, so the next reel waits for a tap. */
    @Test
    @Config(sdk = {28, 37})
    public void offPausedOrNotReadyAMoveRecordsNothing() {
        Settings.TAP_TO_PLAY.save(false);
        TapToPlay.autoScrolled();
        Settings.TAP_TO_PLAY.save(true);
        assertFalse("after a move while off", TapToPlay.allowStart(new Object(), "autoplay", 1));

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        TapToPlay.autoScrolled();
        PauseForTests.resume();
        assertFalse("after a move while paused", TapToPlay.allowStart(new Object(), "autoplay", 1));

        SettingsContextRule.withoutContext(TapToPlay::autoScrolled);
        assertFalse("after a move before the settings were there", TapToPlay.allowStart(new Object(), "autoplay", 1));
        SettingsContextRule.beforeThePauseIsDecided(TapToPlay::autoScrolled);
        assertFalse("after a move before the pause was decided", TapToPlay.allowStart(new Object(), "autoplay", 1));

        TapToPlay.autoScrolled();
        assertTrue("the control: on, the same move starts the next reel", TapToPlay.allowStart(new Object(), "autoplay", 1));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));
    }

    /**
     * With Wait after auto scroll on, a move records nothing, so the reel it moves to waits for a
     * tap, and a tap on it plays it as any tap does (#175). Off, the same move starts the next reel,
     * the chain #21 needs, and that's where it starts.
     */
    @Test
    @Config(sdk = {28, 37})
    public void waitAfterAutoScrollLeavesTheNextReelForATap() {
        assertFalse("off to start, so #21's chain stays", Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.get());
        Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.save(true);
        Object next = new Object();
        TapToPlay.autoScrolled();
        SystemClock.sleep(800);
        assertFalse("playInternal of the reel auto scroll moved to", TapToPlay.allowStart(next, "autoplay", 1));
        assertFalse("its IgGrootPlayer's play", TapToPlay.allowDirectStart(next, "autoplay"));
        assertFalse(TapToPlay.armed(next));
        assertEquals("the hook still counts as working", Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));

        long now = SystemClock.uptimeMillis();
        tapAt(now - 100);
        assertTrue("a tap on it plays it", TapToPlay.allowStart(next, "autoplay", 1));

        Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.save(false);
        SystemClock.sleep(TapToPlay.LOAD_WINDOW_MS + 1);
        TapToPlay.autoScrolled();
        assertTrue("the control: off, the same move starts the next reel", TapToPlay.allowStart(new Object(), "autoplay", 1));
    }

    /** The choice starts as everywhere, what the switch did before there was one, so nobody's Tap to play changes. */
    @Test
    public void theChoiceStartsEverywhere() {
        assertSame(TapToPlayScope.EVERYWHERE, Settings.TAP_TO_PLAY_SCOPE.get());
        assertFalse("a reel waits", TapToPlay.allowStart(new Object(), "autoplay", 1));
        assertFalse("and so does anything else", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertFalse(TapToPlay.allowDirectStart(new Object(), "autoplay"));
    }

    /**
     * Everywhere but Reels: a Reels viewer player's start, and its IgGrootPlayer's own play after
     * it, go ahead without arming anything, and the Reels tap and auto scroller do what Instagram
     * decided. A feed video, and a story's play playInternal never saw, still wait.
     */
    @Test
    @Config(sdk = {28, 37})
    public void everywhereButReelsLetsReelsPlay() {
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.OUTSIDE_REELS);
        Object reel = new Object();
        assertTrue("a reel's playInternal", TapToPlay.allowStart(reel, "autoplay", 1));
        assertTrue("its IgGrootPlayer's play", TapToPlay.allowDirectStart(reel, "autoplay"));
        assertTrue("again, long after any tap", TapToPlay.allowDirectStart(reel, "retry"));
        assertEquals("nothing armed", 0, TapToPlay.armedCount());
        assertFalse("a feed video", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertFalse("a story", TapToPlay.allowDirectStart(new Object(), "autoplay"));

        TapToPlay.reelStates = asked -> {
            throw new AssertionError("asked about a reel the choice leaves out");
        };
        assertFalse("the Reels tap does what Instagram decided", TapToPlay.resumeOnTap(false, new Object()));
        TapToPlay.autoScrolled();
        assertFalse("the move recorded nothing", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertFalse("the feed still draws its play button", TapToPlay.autoplayAllowed(true));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));
    }

    /**
     * Only Reels: a Reels viewer player waits for a tap, and so does its IgGrootPlayer's own play,
     * while a feed video, a story and the autoplay check go as Instagram decides, and the story
     * release does what Instagram decided.
     */
    @Test
    @Config(sdk = {28, 37})
    public void onlyReelsHoldsReelsAndNothingElse() {
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.ONLY_REELS);
        Object reel = new Object();
        assertFalse("a reel waits", TapToPlay.allowStart(reel, "autoplay", 1));
        assertFalse("its IgGrootPlayer's play too", TapToPlay.allowDirectStart(reel, "autoplay"));
        assertTrue("a feed video plays", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertTrue("a story plays", TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue("Instagram's own autoplay answer", TapToPlay.autoplayAllowed(true));
        assertEquals("nothing armed", 0, TapToPlay.armedCount());

        Object groot = new Object();
        TapToPlay.storyPlayers = asked -> {
            throw new AssertionError("asked about a story the choice leaves out");
        };
        holdEnded(SystemClock.uptimeMillis(), aHold());
        assertFalse("the story release does what Instagram decided", TapToPlay.resumeHeldStory(false, groot));

        TapToPlay.reelStates = asked -> State.PREPARED;
        assertTrue("a tap on a held reel still starts it", TapToPlay.resumeOnTap(false, new Object()));
        tapAt(SystemClock.uptimeMillis());
        assertTrue("and that start goes ahead", TapToPlay.allowStart(reel, "resume", 1));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));
    }

    /** IgGrootPlayer's play goes by what playInternal last said of that player. */
    @Test
    public void aPlayerGoesByWhatPlayInternalLastSaidOfIt() {
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.OUTSIDE_REELS);
        Object player = new Object();
        assertTrue(TapToPlay.allowStart(player, "autoplay", 1));
        assertFalse("a feed player's start", TapToPlay.allowStart(player, "autoplay", 0));
        assertFalse("so its own play waits again", TapToPlay.allowDirectStart(player, "autoplay"));
        assertTrue(TapToPlay.allowStart(player, "autoplay", 1));
        assertTrue("a Reels start again", TapToPlay.allowDirectStart(player, "autoplay"));
    }

    /** Off, paused or before the settings are ready, the choice changes nothing: every start goes ahead. */
    @Test
    public void offOrPausedTheChoiceChangesNothing() {
        for (TapToPlayScope scope : TapToPlayScope.values()) {
            Settings.TAP_TO_PLAY_SCOPE.save(scope);
            Settings.TAP_TO_PLAY.save(false);
            assertTrue(scope + ": a reel", TapToPlay.allowStart(new Object(), "autoplay", 1));
            assertTrue(scope + ": a feed video", TapToPlay.allowStart(new Object(), "autoplay", 0));
            assertTrue(scope + ": Instagram's own answer", TapToPlay.autoplayAllowed(true));
            Settings.TAP_TO_PLAY.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertTrue(scope + ": paused, a reel", TapToPlay.allowStart(new Object(), "autoplay", 1));
            assertTrue(scope + ": paused, a feed video", TapToPlay.allowStart(new Object(), "autoplay", 0));
            PauseForTests.resume();
        }
    }

    /** Debug logging says once which starts the choice leaves out. */
    @Test
    public void debugLoggingSaysOnceWhatTheChoiceLeavesOut() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.OUTSIDE_REELS);
        TapToPlay.allowStart(new Object(), "autoplay", 1);
        TapToPlay.allowStart(new Object(), "autoplay", 1);
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.ONLY_REELS);
        TapToPlay.allowStart(new Object(), "autoplay", 0);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tap to play: starts in Reels go ahead, the choice of where leaves them out"));
        assertEquals(report, 1, occurrences(report, "Tap to play: starts outside Reels go ahead, the choice of where leaves them out"));
    }

    /** A move that throws stays in the hook, records nothing, and the report names it. */
    @Test
    @Config(sdk = {28, 37})
    public void aFailingMoveRecordsNothingAndTheReportSaysSo() {
        TapToPlay.failNext = new IllegalStateException("the move failed");
        TapToPlay.autoScrolled();
        assertEquals(Collections.singletonList("a working 'auto scroll' hook (it threw "
                        + IllegalStateException.class.getName() + ")"),
                HookStatus.missing(FamilyNames.TAP_TO_PLAY));
        assertFalse("nothing was recorded", TapToPlay.allowStart(new Object(), "autoplay", 1));

        TapToPlay.autoScrolled();
        assertTrue("the next move works", TapToPlay.allowStart(new Object(), "autoplay", 1));
    }

    /** Debug logging says when auto scroll moved on and which start that let through. */
    @Test
    @Config(sdk = {28, 37})
    public void debugLoggingSaysAutoScrollMovedOnAndWhatItStarted() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlay.autoScrolled();
        TapToPlay.allowStart(new Object(), "autoplay", 1);
        TapToPlay.allowStart(new Object(), "autoplay", 1);

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Tap to play: auto scroll moved on, so the next reel's first start goes ahead"));
        assertEquals(report, 1, occurrences(report, "after auto scroll"));
        assertTrue(report, report.contains("Tap to play: allowed autoplay no tap armed no after auto scroll"));
        assertTrue(report, report.contains("Tap to play: held autoplay no tap armed no"));
    }

    @Test
    public void debugLoggingSaysEachDecisionThenSumsThemUp() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        TapToPlayForTests.tapEnded(250);
        Object player = new Object();
        TapToPlay.allowStart(player, "autoplay", 0);
        TapToPlay.allowDirectStart(player, "autoplay");
        TapClock.forget();
        TapToPlay.allowStart(new Object(), "resume", 0);
        TapToPlay.allowDirectStart(new Object(), "start");
        TapToPlay.autoplayAllowed(true);
        TapToPlay.autoplayAllowed(true);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Tap to play: allowed autoplay 250 ms armed no"));
        assertTrue(report, report.contains("Tap to play: allowed autoplay 250 ms armed yes (direct)"));
        assertTrue(report, report.contains("Tap to play: held resume no tap armed no"));
        assertTrue(report, report.contains("Tap to play: held start no tap armed no (direct)"));
        assertEquals(report, 1, occurrences(report, "Tap to play: Instagram's autoplay check answers no"));

        // 4 so far: 36 more one by one, then 120 summed up in two lines, with 20 left over.
        for (int i = 0; i < 36 + 120; i++) TapToPlay.allowStart(new Object(), "autoplay", 0);
        report = LogBufferManager.buildExportText();
        assertEquals(TapToPlay.LOGGED_ONE_BY_ONE, occurrences(report, "Tap to play: allowed ")
                + occurrences(report, "Tap to play: held "));
        assertEquals(report, 2, occurrences(report, "Tap to play: 50 more starts, 0 allowed, 50 held"));
    }

    @Test
    public void withoutDebugLoggingNothingIsLogged() {
        LogBufferManager.clearLogBuffer();
        TapToPlay.allowStart(new Object(), "autoplay", 0);
        TapToPlay.autoplayAllowed(true);
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Tap to play: held"));
        assertFalse(report, report.contains("Tap to play: Instagram's autoplay check"));
    }

    /**
     * #229: page 1 of a carousel is tapped and plays, the person swipes to page 2 and taps it. Our
     * gate only answers yes or no to a start Instagram makes; it never starts or resumes a player,
     * so page 1 can only run on if Instagram leaves it running or starts it itself. The gate must
     * not let page 1 back in on page 2's tap once Instagram has paused it, and must keep page 2's
     * start armed.
     */
    @Test
    public void aLaterCarouselPagesTapDoesNotStartThePageBefore() {
        long now = 700_000;
        Object page1 = new Object();
        Object page2 = new Object();
        tapAt(now - 20_000);
        assertTrue("page 1's tap starts it", TapToPlay.decide(page1, "autoplay", now - 19_900, ""));
        assertTrue(TapToPlay.armed(page1));

        // The swipe: a drag, then page 2 binds and its autoplay start is held with no tap.
        TapClock.record(MotionEvent.ACTION_DOWN, 900, 500, now - 10_000, 8);
        TapClock.record(MotionEvent.ACTION_MOVE, 300, 500, now - 9_950, 8);
        TapClock.record(MotionEvent.ACTION_UP, 100, 500, now - 9_900, 8);
        assertFalse("page 2 waits for its tap", TapToPlay.decide(page2, "autoplay", now - 9_800, ""));
        // Instagram pauses page 1 when the page changes.
        TapToPlay.paused(page1, "carousel_page_changed");
        assertFalse("page 1 is no longer armed once Instagram paused it", TapToPlay.armed(page1));

        tapAt(now);
        assertTrue("page 2's tap starts page 2", TapToPlay.decide(page2, "autoplay", now + 50, ""));
        assertTrue(TapToPlay.armed(page2));
        assertFalse("page 1 stays stopped: nothing of ours started it", TapToPlay.armed(page1));
        assertFalse("a held start of page 1 from before the swipe isn't released by page 2's tap later on",
                TapToPlay.decide(page1, "autoplay", now + TapToPlay.TAP_WINDOW_MS + 500, ""));
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static final class AlwaysEqual {
        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    /** A Reels move only authorizes the Reel it moves to. A separate Feed player waits for its tap. */
    @Test
    @Config(sdk = {28, 37})
    public void anAutoScrollMoveDoesNotStartFeedOrSpendItsCreditThere() {
        TapToPlay.autoScrolled();
        SystemClock.sleep(800);
        assertFalse("a known Feed player's start is not the Reels move", TapToPlay.allowStart(new Object(), "autoplay", 0));
        Object reel = new Object();
        assertTrue("the intended Reel still has the move", TapToPlay.allowStart(reel, "autoplay", 1));
        assertTrue("its direct start keeps the known Reels classification", TapToPlay.allowDirectStart(reel, "autoplay"));
    }

    @Test
    @Config(sdk = {28, 37})
    public void anAutoScrollMoveDoesNotStartAnUnknownDirectPlayerOrSpendItsCreditThere() {
        TapToPlay.autoScrolled();
        SystemClock.sleep(800);
        assertFalse("a direct/story player not identified as Reels waits", TapToPlay.allowDirectStart(new Object(), "autoplay"));
        assertTrue("the intended Reel still has the move", TapToPlay.allowStart(new Object(), "autoplay", 1));
    }

    @Test
    @Config(sdk = {28, 37})
    public void aUserTapOnFeedDoesNotUseAnIndependentReelsMove() {
        TapToPlay.autoScrolled();
        tapAt(SystemClock.uptimeMillis());
        SystemClock.sleep(200);
        assertTrue("a genuine tap still starts Feed", TapToPlay.allowStart(new Object(), "resume", 0));
        SystemClock.sleep(1200);
        assertTrue("after the tap's general window, the Reel still has its move", TapToPlay.allowStart(new Object(), "autoplay", 1));
    }

    @Test
    @Config(sdk = {28, 37})
    public void aDirectPlayerAlreadyIdentifiedAsReelsCanUseItsMove() {
        Object reel = new Object();
        assertFalse("held at first, with no tap", TapToPlay.allowStart(reel, "autoplay", 1));
        TapToPlay.autoScrolled();
        SystemClock.sleep(800);
        assertTrue("its later direct start is still known to be Reels", TapToPlay.allowDirectStart(reel, "autoplay"));
    }
}
