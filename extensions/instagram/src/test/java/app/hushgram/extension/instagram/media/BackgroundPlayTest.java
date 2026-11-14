/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Keep playing in the background: which pauses are held, and what ends a session (#497). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BackgroundPlayTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object player = new Object();
    private final Object other = new Object();
    private final Object third = new Object();
    private final Players players = new Players();
    private ActivityController<Screen> controller;
    private Screen screen;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        HookStatus.clear();
        BackgroundPlay.resetForTests();
        BackgroundPlay.serviceForTests = true;
        BackgroundPlay.controlForTests = players;
        controller = Robolectric.buildActivity(Screen.class).setup();
        screen = controller.get();
    }

    @After
    public void restore() {
        BackgroundPlay.resetForTests();
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Leaving with a video playing with sound holds that player's pause and stop, and no other's. */
    @Test
    public void leavingHoldsTheAudiblePlayer() {
        playing(player, 1f);
        assertFalse("nothing is held before you leave", BackgroundPlay.hold(player, "fragment_paused"));

        BackgroundPlay.leaving(screen);

        assertEquals(BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
        assertTrue(BackgroundPlay.hold(player, "fragment_paused"));
        assertTrue("a stop is held too", BackgroundPlay.hold(player, null));
        assertFalse(BackgroundPlay.hold(other, "fragment_paused"));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.BACKGROUND_PLAY));
    }

    /** The newest player playing with sound is the one kept; a muted one never is. */
    @Test
    public void theMutedVideoIsPassedOver() {
        playing(other, 1f);
        playing(player, 0f);

        BackgroundPlay.leaving(screen);

        assertTrue(BackgroundPlay.hold(other, "fragment_paused"));
        assertFalse(BackgroundPlay.hold(player, "fragment_paused"));
    }

    /** Leaving with only muted or paused videos keeps nothing. */
    @Test
    public void silenceKeepsNothing() {
        playing(player, 0f);
        BackgroundPlay.started(other);

        BackgroundPlay.leaving(screen);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
    }

    /** With no volume seen yet, the phone's music stream says whether it has sound. */
    @Test
    public void noVolumeYetAsksTheAudioManager() {
        BackgroundPlay.started(player);
        players.playing.add(player);
        BackgroundPlay.musicActiveForTests = false;
        BackgroundPlay.leaving(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        BackgroundPlay.musicActiveForTests = true;
        BackgroundPlay.leaving(screen);
        assertEquals(BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
    }

    /** Off, leaving keeps nothing, and turning it off mid-session lets the next pause through. */
    @Test
    public void offKeepsNothing() {
        playing(player, 1f);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        BackgroundPlay.leaving(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        BackgroundPlay.leaving(screen);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        assertFalse(BackgroundPlay.hold(player, "fragment_paused"));
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
    }

    /** Turned off while the video plays on in the background, Instagram's next pause goes through. */
    @Test
    public void offInTheBackgroundLetsThePauseThrough() {
        background(player);

        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        assertFalse(BackgroundPlay.hold(player, "fragment_paused"));
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertTrue("the pause is Instagram's own, not the extension's", players.calls.isEmpty());
    }

    /** Picture in picture is Instagram's own way of playing on: no session starts, and one under way ends. */
    @Test
    public void pictureInPictureEndsIt() {
        playing(player, 1f);
        screen.pip = true;
        BackgroundPlay.leaving(screen);
        assertEquals("nothing is held leaving into picture in picture", BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        screen.pip = false;
        leaveWith(player);
        screen.pip = true;
        assertFalse("the pause on the way into picture in picture goes through", BackgroundPlay.hold(player, "fragment_paused"));
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        screen.pip = false;
        leaveWith(player);
        screen.pip = true;
        BackgroundPlay.stopped(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(0, BackgroundPlay.serviceStartsForTests);

        screen.pip = false;
        BackgroundPlay.resumed(screen);
        leaveWith(player);
        BackgroundPlay.paused(screen);
        screen.pip = true;
        BackgroundPlay.watchdog(screen);
        assertEquals("the watchdog drops a leave that went to picture in picture", BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertTrue("picture in picture keeps playing, so nothing is paused", players.calls.isEmpty());
    }

    /** A pause for the audio focus (a call, another app's sound) always goes through. */
    @Test
    public void anAudioFocusPauseGoesThrough() {
        leaveWith(player);

        assertFalse(BackgroundPlay.hold(player, "audio_focus_loss"));
        assertFalse(BackgroundPlay.hold(player, "AUDIO_FOCUS_LOSS_TRANSIENT"));
        assertTrue(BackgroundPlay.hold(player, "fragment_paused"));
    }

    /** In the background, a call's focus pause leaves the notification up as paused, and Play picks it up again. */
    @Test
    public void anAudioFocusPauseInTheBackgroundStaysPaused() {
        background(player);

        assertFalse(BackgroundPlay.hold(player, "audio_focus_loss"));
        players.playing.remove(player);

        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertTrue(BackgroundPlay.active());
        assertFalse(BackgroundPlay.heldPlaying());
        BackgroundPlay.playFromNotification();
        assertTrue(BackgroundPlay.heldPlaying());
    }

    /** The screen stopping with the video still going starts the notification's service. */
    @Test
    public void theScreenStoppingStartsTheService() {
        leaveWith(player);

        BackgroundPlay.stopped(screen);

        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertEquals(1, BackgroundPlay.serviceStartsForTests);
        assertTrue(BackgroundPlay.active());
        assertTrue(BackgroundPlay.heldPlaying());
    }

    /** A video Instagram stopped some other way as it left (or muted) gets no service. */
    @Test
    public void aVideoStoppedOrMutedOnTheWayOutEndsIt() {
        leaveWith(player);
        players.playing.remove(player);
        BackgroundPlay.stopped(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        leaveWith(player);
        BackgroundPlay.volume(player, 0f);
        BackgroundPlay.stopped(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(0, BackgroundPlay.serviceStartsForTests);
        assertEquals("the muted video is paused, not left running silent", List.of("pause"), players.calls);
    }

    /** Another screen of the app stopping changes nothing. */
    @Test
    public void onlyTheVideosScreenCounts() {
        leaveWith(player);

        BackgroundPlay.stopped(Robolectric.buildActivity(Activity.class).setup().get());

        assertEquals(BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
        assertEquals(0, BackgroundPlay.serviceStartsForTests);
    }

    /** Coming back to the same screen ends the session with the video still playing. */
    @Test
    public void comingBackLeavesItPlaying() {
        background(player);

        BackgroundPlay.resumed(screen);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertTrue(players.calls.isEmpty());
        assertTrue(players.playing.contains(player));
    }

    /** Another Instagram screen opening over the video pauses it, as Instagram meant to. */
    @Test
    public void anotherScreenPausesIt() {
        background(player);

        BackgroundPlay.resumed(Robolectric.buildActivity(Activity.class).setup().get());

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("pause"), players.calls);
        assertEquals("the extension's own pause isn't held", List.of(false), players.heldOwnPause);
    }

    /**
     * The Reels viewer releasing the held player as its fragment stops keeps it playing; another
     * player's release goes through. Stop in the notification then pauses it and lets the release
     * through with Instagram's own reason, which isn't held a second time.
     */
    @Test
    public void aHeldReleaseGoesThroughWhenTheSessionEnds() {
        background(player);
        assertFalse("another player's release goes through", BackgroundPlay.releasing(other, "fragment_stopped"));
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertTrue("a held release leaves the video playing", players.calls.isEmpty());
        assertTrue(players.playing.contains(player));

        BackgroundPlay.stopFromNotification();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("pause", "release fragment_stopped"), players.calls);
        assertEquals("the extension's own release isn't held", List.of(false), players.heldOwnRelease);
    }

    /**
     * Coming back to Instagram after a held release lets it through as the screen starts, before
     * its fragments start their players again, and leaves the video unpaused. A screen starting
     * with no release held, or another screen, changes nothing.
     */
    @Test
    public void comingBackLetsAHeldReleaseThrough() {
        background(player);
        BackgroundPlay.restarted(screen);
        assertEquals("nothing held, nothing to end", BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());

        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        BackgroundPlay.restarted(Robolectric.buildActivity(Activity.class).setup().get());
        assertEquals("another screen", BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());

        BackgroundPlay.restarted(screen);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("release fragment_stopped"), players.calls);
        assertEquals(List.of(false), players.heldOwnRelease);
    }

    /** The screen closing ends it with a pause. */
    @Test
    public void closingEndsIt() {
        background(player);
        BackgroundPlay.destroyed(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("pause"), players.calls);
    }

    /** A mute in the background pauses it, and the sound coming back soon plays it again. */
    @Test
    public void aMuteInTheBackgroundPausesUntilTheSoundComesBack() {
        background(player);

        BackgroundPlay.volume(player, 0f);
        idle();
        assertEquals(List.of("pause"), players.calls);
        assertEquals(List.of(false), players.heldOwnPause);
        assertFalse(BackgroundPlay.heldPlaying());

        BackgroundPlay.volume(player, 1f);
        idle();
        assertEquals(List.of("pause", "play"), players.calls);
        assertTrue(BackgroundPlay.heldPlaying());
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
    }

    /** The notification's Pause, Play and Stop act on the held video; Stop ends the session. */
    @Test
    public void theNotificationsButtons() {
        background(player);

        BackgroundPlay.pauseFromNotification();
        assertFalse(BackgroundPlay.heldPlaying());
        assertEquals(List.of(false), players.heldOwnPause);
        BackgroundPlay.playFromNotification();
        assertTrue(BackgroundPlay.heldPlaying());
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());

        BackgroundPlay.stopFromNotification();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("pause", "play", "pause"), players.calls);
        assertFalse(BackgroundPlay.hold(player, "fragment_paused"));
    }

    /** A reel moving on by itself in the background hands the session to the next one. */
    @Test
    public void theNextReelTakesOver() {
        background(player);
        players.playing.remove(player);

        BackgroundPlay.started(other);
        players.playing.add(other);
        idle();

        BackgroundPlay.pauseFromNotification();
        assertFalse(players.playing.contains(other));
        assertTrue(BackgroundPlay.hold(other, "fragment_paused"));
    }

    /**
     * A release held for a reel goes through on that reel as the next one takes over in the
     * background, so its decoder doesn't wait for the session's end. The new one, the reel on screen
     * when you come back, keeps playing, and coming back releases nothing a second time.
     */
    @Test
    public void aReplacedReelIsReleasedAsTheNextTakesOver() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.playing.remove(player);
        BackgroundPlay.started(other);
        players.playing.add(other);

        idle();

        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertEquals("released as the next took over", List.of(player), players.released);
        assertEquals(List.of("release fragment_stopped"), players.calls);
        assertTrue(players.playing.contains(other));

        BackgroundPlay.restarted(screen);
        assertEquals("nothing held, so the screen starts in the session", BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        BackgroundPlay.resumed(screen);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(player), players.released);
        assertEquals(List.of("release fragment_stopped"), players.calls);
        assertTrue("the reel on screen keeps playing", players.playing.contains(other));
    }

    /**
     * With two reels each held in turn, the first one's release goes through as the second takes
     * over, and Stop pauses the second and lets its own release through. Instagram asking a replaced
     * one's release again lets it through then, and Stop releases nothing more.
     */
    @Test
    public void eachHeldReleaseGoesThroughOnItsOwnPlayer() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.playing.remove(player);
        BackgroundPlay.started(other);
        players.playing.add(other);
        idle();
        assertTrue(BackgroundPlay.releasing(other, "fragment_destroyed"));

        BackgroundPlay.stopFromNotification();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(player, other), players.released);
        assertEquals(List.of("release fragment_stopped", "pause", "release fragment_destroyed"), players.calls);

        players.calls.clear();
        players.released.clear();
        background(third);
        assertTrue(BackgroundPlay.releasing(third, "fragment_stopped"));
        players.playing.remove(third);
        BackgroundPlay.started(player);
        players.playing.add(player);
        idle();
        assertEquals(List.of(third), players.released);
        assertFalse("a release asked again for the old one goes through now", BackgroundPlay.releasing(third, "destroy"));

        BackgroundPlay.stopFromNotification();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("not released by the end", List.of(third), players.released);
        assertEquals(List.of("release fragment_stopped", "pause"), players.calls);
    }

    /**
     * A release Instagram asks for while one of ours goes through, here from inside the pause Stop
     * passes on, goes through then, and the one held earlier for that player is dropped with it.
     */
    @Test
    public void aReleaseAskedWhileOursGoesThroughIsTheOnlyOne() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.releaseOnPause = true;

        BackgroundPlay.stopFromNotification();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("released once", List.of(player), players.released);
        assertEquals(List.of("release paused", "pause"), players.calls);
    }

    /**
     * Instagram letting the new held player go while the replaced one's release goes through, as one
     * screen's players go together, holds that release too: the new one plays on, and the session's
     * end pauses it and releases it once.
     */
    @Test
    public void aHeldPlayerLetGoWhileTheReplacedOneGoesThroughKeepsPlaying() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.playing.remove(player);
        BackgroundPlay.started(other);
        players.playing.add(other);
        players.releasesWith.put(player, other);

        idle();

        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertEquals(List.of(player), players.released);
        assertTrue("the new one plays on", players.playing.contains(other));

        BackgroundPlay.stopFromNotification();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("each released once", List.of(player, other), players.released);
        assertEquals(List.of("release fragment_stopped", "pause", "release along"), players.calls);
    }

    /**
     * Instagram asking the held player's release again once the switch is off, or once it went to
     * picture in picture, ends the session with that release as the only one.
     */
    @Test
    public void aReleaseThatEndsTheSessionIsTheOnlyOne() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        players.release(player, "destroy");

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("released once", List.of(player), players.released);
        assertEquals(List.of("release destroy"), players.calls);

        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        players.calls.clear();
        players.released.clear();
        background(other);
        assertTrue(BackgroundPlay.releasing(other, "fragment_stopped"));
        screen.pip = true;

        players.release(other, "destroy");

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("released once in picture in picture", List.of(other), players.released);
        assertEquals(List.of("release destroy"), players.calls);
    }

    /**
     * With the switch off, Instagram releasing the player that took over ends the session. The
     * release held for it, the one its replaced player's release brought on, is dropped, so
     * Instagram's own call is the only release it gets.
     */
    @Test
    public void aReleaseThatEndsTheSessionDropsTheOneTheReplacedPlayerBroughtOn() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.playing.remove(player);
        BackgroundPlay.started(other);
        players.playing.add(other);
        players.releasesWith.put(player, other);
        idle();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        players.release(other, "destroy");

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals("each released once", List.of(player, other), players.released);
        assertEquals(List.of("release fragment_stopped", "release destroy"), players.calls);
    }

    /**
     * With the switch off, Instagram pausing the player that took over ends the session, and the
     * release its replaced player's release brought on goes through: the pause returns at once, as
     * nothing is left to pause.
     */
    @Test
    public void aPauseThatEndsTheSessionSkipsAPlayerTheEndReleased() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        players.playing.remove(player);
        BackgroundPlay.started(other);
        players.playing.add(other);
        players.releasesWith.put(player, other);
        idle();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        players.pause(other);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(player, other), players.released);
        assertEquals("no pause on the released player", List.of("release fragment_stopped", "release along"), players.calls);
    }

    /**
     * A pause that ends the session on a held player whose release the end lets through returns at
     * once, and a pause on a player with no release held still goes through.
     */
    @Test
    public void aPauseThatEndsTheSessionSkipsAReleasedPlayer() {
        background(player);
        assertTrue(BackgroundPlay.releasing(player, "fragment_stopped"));
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        players.pause(player);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(player), players.released);
        assertEquals("no pause on the released player", List.of("release fragment_stopped"), players.calls);

        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        players.calls.clear();
        players.released.clear();
        background(other);
        assertTrue(BackgroundPlay.releasing(other, "fragment_stopped"));
        screen.pip = true;

        players.pause(other);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(other), players.released);
        assertEquals("no pause in picture in picture either", List.of("release fragment_stopped"), players.calls);

        screen.pip = false;
        players.calls.clear();
        players.released.clear();
        background(third);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);

        players.pause(third);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of(), players.released);
        assertEquals("a player with nothing held is paused", List.of("pause"), players.calls);
    }

    /** A session left paused for the limit ends by itself; a playing one doesn't. */
    @Test
    public void aLongPauseEnds() {
        background(player);
        BackgroundPlay.pausedSinceForTests(SystemClock.uptimeMillis() - BackgroundPlay.PAUSED_LIMIT_MS);
        BackgroundPlay.tick();
        assertEquals("still playing", BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());

        players.playing.remove(player);
        BackgroundPlay.tick();
        assertEquals("the pause just began", BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());

        BackgroundPlay.pausedSinceForTests(SystemClock.uptimeMillis() - BackgroundPlay.PAUSED_LIMIT_MS);
        BackgroundPlay.tick();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
    }

    /** A leave hint with the screen still in front after the watchdog drops the session. */
    @Test
    public void theWatchdogDropsASessionThatStayed() {
        BackgroundPlay.resumed(screen);
        leaveWith(player);

        BackgroundPlay.watchdog(screen);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        leaveWith(player);
        BackgroundPlay.paused(screen);
        BackgroundPlay.watchdog(screen);
        assertEquals("the screen did go", BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
    }

    /** Swiped away from recents, or the service gone, ends it with the video paused. */
    @Test
    public void swipedAwayEndsIt() {
        background(player);
        BackgroundPlay.taskRemoved();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());

        background(player);
        BackgroundPlay.serviceGone();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(List.of("pause", "pause"), players.calls);
    }

    /** The screen going off, through the real activity callbacks, then coming back. */
    @Test
    public void theScreenGoingOffKeepsItPlaying() {
        BackgroundPlay.watch(RuntimeEnvironment.getApplication());
        playing(player, 1f);
        BackgroundPlay.interactiveForTests = false;

        controller.pause();
        assertEquals(BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
        assertTrue(BackgroundPlay.hold(player, "fragment_paused"));
        controller.stop();
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        assertEquals(1, BackgroundPlay.serviceStartsForTests);

        BackgroundPlay.interactiveForTests = true;
        controller.start().resume();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertTrue(players.calls.isEmpty());
    }

    /** A pause with the screen on, like scrolling to another tab, keeps nothing. */
    @Test
    public void aPauseWithTheScreenOnKeepsNothing() {
        BackgroundPlay.watch(RuntimeEnvironment.getApplication());
        playing(player, 1f);
        BackgroundPlay.interactiveForTests = true;

        controller.pause();

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertFalse(BackgroundPlay.hold(player, "fragment_paused"));
    }

    /**
     * A spring that sets off in the background is stepped about once a frame, so a story's slide to
     * the next one ends with Instagram hidden, and for {@link SpringSteps#KEEP_MS} only.
     */
    @Test
    public void aSpringInTheBackgroundGetsFrames() {
        background(player);
        Frames frames = new Frames();

        BackgroundPlay.springMoving(frames);
        idleFor(100);

        assertTrue("stepped about every 16 ms: " + frames.steps, frames.steps >= 5 && frames.steps <= 7);
        idleFor(SpringSteps.KEEP_MS);
        int stepped = frames.steps;
        idleFor(500);
        assertEquals("no steps past the keep", stepped, frames.steps);
        assertEquals(0, SpringSteps.keptForTests());
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
    }

    /** With no session, or the switch off, a spring is Instagram's alone. */
    @Test
    public void aSpringOutsideASessionIsLeftAlone() {
        Frames frames = new Frames();
        BackgroundPlay.springMoving(frames);
        idleFor(100);
        assertEquals(0, frames.steps);

        background(player);
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(false);
        BackgroundPlay.springMoving(frames);
        idleFor(100);
        assertEquals(0, frames.steps);
        assertEquals(0, SpringSteps.keptForTests());
    }

    /** While Instagram is still leaving it still draws, so the steps wait for it to be hidden. */
    @Test
    public void aSpringSetOffAsYouLeaveIsSteppedOnceHidden() {
        leaveWith(player);
        Frames frames = new Frames();
        BackgroundPlay.springMoving(frames);
        idleFor(100);
        assertEquals("the screen's own frames step it", 0, frames.steps);

        BackgroundPlay.stopped(screen);
        idleFor(100);
        assertTrue(String.valueOf(frames.steps), frames.steps >= 5);
    }

    /** Coming back ends the steps with the session: the screen's frames take over. */
    @Test
    public void comingBackStopsTheSteps() {
        background(player);
        Frames frames = new Frames();
        BackgroundPlay.springMoving(frames);
        idleFor(50);
        assertTrue(frames.steps > 0);

        BackgroundPlay.resumed(screen);
        idle();
        int stepped = frames.steps;
        idleFor(200);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(stepped, frames.steps);
        assertEquals(0, SpringSteps.keptForTests());
    }

    /**
     * A step that throws is dropped and reported, and its frame goes back to the screen, so the system
     * isn't left started with no frame coming. The other springs keep going.
     */
    @Test
    public void aThrowingStepIsDropped() {
        background(player);
        Frames frames = new Frames();
        int[] thrown = {0};
        int[] screenFrames = {0};
        BackgroundPlay.springMoving((android.view.Choreographer.FrameCallback) time -> {
            if (thrown[0] == 0) {
                thrown[0]++;
                throw new IllegalStateException("test");
            }
            screenFrames[0]++;
        });
        BackgroundPlay.springMoving(frames);
        idleFor(100);

        assertEquals("stepped once, then dropped", 1, thrown[0]);
        assertEquals("its frame went back to the screen, once", 1, screenFrames[0]);
        assertTrue(frames.steps >= 5);
        assertEquals(1, SpringSteps.keptForTests());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("spring step"));
    }

    /** A looper like Rebound's, which asks the screen for its next frame last, keeps moving on the screen's frames after a step throws. */
    @Test
    public void aLooperWhoseStepThrowsStillGetsFrames() {
        background(player);
        SpringLooper looper = new SpringLooper();
        looper.throwNext = true;

        BackgroundPlay.springMoving(looper);
        idleFor(100);

        assertEquals(0, SpringSteps.keptForTests());
        assertEquals("the screen's frames carry it on to rest", SpringLooper.REST_AFTER, looper.steps);
    }

    /** A spring that sets itself off again on each step stops being stepped after {@link SpringSteps#RUN_MS}. */
    @Test
    public void aSpringThatKeepsSettingItselfOffStopsAfterARun() {
        background(player);
        Frames frames = new Frames();
        frames.onStep = () -> BackgroundPlay.springMoving(frames);

        BackgroundPlay.springMoving(frames);
        idleFor(SpringSteps.RUN_MS - 500);
        assertEquals("still stepped past the keep", 1, SpringSteps.keptForTests());
        idleFor(1_000);
        int stepped = frames.steps;
        idleFor(500);

        assertEquals("no steps past the run", stepped, frames.steps);
        assertEquals(0, SpringSteps.keptForTests());
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
    }

    /**
     * Stories that end one after another set the viewer's springs off from outside a step, and each
     * start begins a new run, so a slide set off just before a run would end still finishes.
     */
    @Test
    public void aStartFromOutsideAStepBeginsANewRun() {
        background(player);
        Frames frames = new Frames();

        BackgroundPlay.springMoving(frames);
        for (int story = 0; story < 4; story++) {
            idleFor(2_000);
            BackgroundPlay.springMoving(frames);
        }
        idleFor(SpringSteps.RUN_MS - 8_000 - 200);
        BackgroundPlay.springMoving(frames);
        idleFor(400);
        int stepped = frames.steps;
        idleFor(100);

        assertTrue("still stepped past the first run's end: " + stepped + " then " + frames.steps, frames.steps > stepped);
        idleFor(SpringSteps.KEEP_MS);
        assertEquals(0, SpringSteps.keptForTests());
    }

    /**
     * A looper that asks the screen for its next frame and then throws gets that frame once, not twice,
     * so its springs still come to rest on the step they would have.
     */
    @Test
    public void aLooperThatPostsThenThrowsGetsOneFrame() {
        background(player);
        SpringLooper looper = new SpringLooper();
        looper.throwNext = true;
        looper.postBeforeThrow = true;

        BackgroundPlay.springMoving(looper);
        idleFor(100);

        assertEquals(0, SpringSteps.keptForTests());
        assertEquals("one frame a step, to rest", SpringLooper.REST_AFTER, looper.steps);
    }

    /**
     * A start one system's step makes for another carries on the stepping system's run, so a spring
     * whose own run began long ago still moves when a fresh slide sets it off (#497).
     */
    @Test
    public void aStartFromAnotherSystemsStepTakesThatSystemsRun() {
        background(player);
        Frames old = new Frames();
        boolean[] restarting = {true};
        old.onStep = () -> {
            if (restarting[0]) BackgroundPlay.springMoving(old);
        };
        BackgroundPlay.springMoving(old);
        idleFor(SpringSteps.RUN_MS - 300);
        restarting[0] = false;

        Frames slide = new Frames();
        slide.onStep = () -> {
            if (slide.steps == 1) BackgroundPlay.springMoving(old);
        };
        BackgroundPlay.springMoving(slide);
        idleFor(600);
        int stepped = old.steps;
        idleFor(100);

        assertTrue("still stepped past its own run's end: " + stepped + " then " + old.steps, old.steps > stepped);
    }

    /** Two springs that keep setting each other off share one run, and stop after {@link SpringSteps#RUN_MS}. */
    @Test
    public void twoSpringsSettingEachOtherOffStopAfterARun() {
        background(player);
        Frames first = new Frames();
        Frames second = new Frames();
        first.onStep = () -> BackgroundPlay.springMoving(second);
        second.onStep = () -> BackgroundPlay.springMoving(first);

        BackgroundPlay.springMoving(first);
        idleFor(SpringSteps.RUN_MS + 500);
        int stepped = first.steps + second.steps;
        idleFor(500);

        assertEquals("no steps past the run", stepped, first.steps + second.steps);
        assertEquals(0, SpringSteps.keptForTests());
    }

    /**
     * A spring that sets itself off again on each frame the screen gives Instagram's own looper, as on
     * a phone that still sends a hidden app frames, stops being stepped {@link SpringSteps#RUN_MS}
     * after the last start made outside any frame, and stays stopped while those frames go on.
     */
    @Test
    public void aSpringThatKeepsSettingItselfOffOnTheScreensFramesStopsAfterARun() {
        background(player);
        Frames frames = new Frames();
        frames.onStep = () -> BackgroundPlay.springMoving(frames);

        BackgroundPlay.springMoving(frames);
        screenFrames(frames, 4_000);
        // The viewer moves on to the next story: a start from outside any frame begins a new run.
        BackgroundPlay.springMoving(frames);
        screenFrames(frames, SpringSteps.RUN_MS - 500);
        assertEquals("still stepped inside the new run", 1, SpringSteps.keptForTests());
        screenFrames(frames, 1_000);
        int stepped = SpringSteps.stepsForTests;
        screenFrames(frames, 2_000);

        assertEquals("no steps past the run while the screen's frames go on", stepped, SpringSteps.stepsForTests);
        assertEquals(0, SpringSteps.keptForTests());
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
    }

    /**
     * A step that throws still ends as a step, so the next start from outside one begins a new run
     * rather than taking on the run of the system that threw.
     */
    @Test
    public void aStartAfterAThrowingStepBeginsANewRun() {
        background(player);
        Frames thrower = new Frames();
        int[] throwOn = {-1};
        thrower.onStep = () -> {
            if (thrower.steps == throwOn[0]) throw new IllegalStateException("test");
            if (throwOn[0] < 0) BackgroundPlay.springMoving(thrower);
        };
        BackgroundPlay.springMoving(thrower);
        idleFor(SpringSteps.RUN_MS - 300);
        throwOn[0] = thrower.steps + 1;
        idleFor(450);
        assertEquals(0, SpringSteps.keptForTests());

        Frames slide = new Frames();
        BackgroundPlay.springMoving(slide);
        idleFor(100);

        assertTrue("the slide was stepped: " + slide.steps, slide.steps > 0);
    }

    /** A step that ends the session is the last one: the other springs aren't stepped after it. */
    @Test
    public void aStepThatEndsTheSessionIsTheLast() {
        background(player);
        Frames first = new Frames();
        Frames second = new Frames();
        first.onStep = () -> BackgroundPlay.resumed(screen);
        second.onStep = first.onStep;

        BackgroundPlay.springMoving(first);
        BackgroundPlay.springMoving(second);
        idleFor(100);

        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertEquals(1, first.steps + second.steps);
        assertEquals(0, SpringSteps.keptForTests());
    }

    /** No hook throws into Instagram, whatever it's handed. */
    @Test
    public void nullsAreHarmless() {
        BackgroundPlay.springMoving(null);
        BackgroundPlay.springMoving(new Object());
        BackgroundPlay.started(null);
        BackgroundPlay.volume(null, 1f);
        assertFalse(BackgroundPlay.releasing(null, null));
        BackgroundPlay.restarted(null);
        BackgroundPlay.leaving(null);
        assertFalse(BackgroundPlay.hold(null, null));
        BackgroundPlay.pauseFromNotification();
        BackgroundPlay.playFromNotification();
        BackgroundPlay.tick();
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
    }

    private void playing(Object video, float volume) {
        BackgroundPlay.started(video);
        BackgroundPlay.volume(video, volume);
        players.playing.add(video);
    }

    private void leaveWith(Object video) {
        playing(video, 1f);
        BackgroundPlay.leaving(screen);
        assertEquals(BackgroundPlay.Phase.LEAVING, BackgroundPlay.phase());
    }

    private void background(Object video) {
        leaveWith(video);
        BackgroundPlay.stopped(screen);
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void idleFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms));
    }

    /** For [ms], a frame every 100 ms from the screen to [frames], run as Instagram's own looper runs it. */
    private static void screenFrames(Frames frames, long ms) {
        for (long passed = 0; passed < ms; passed += 100) {
            frames.doFrame(System.nanoTime());
            idleFor(100);
        }
    }

    /** A spring system's frame callback that counts its steps, and runs [onStep] on each. */
    private static final class Frames implements android.view.Choreographer.FrameCallback {
        int steps;
        Runnable onStep;

        @Override
        public void doFrame(long frameTimeNanos) {
            steps++;
            if (onStep != null) onStep.run();
        }
    }

    /**
     * A started spring looper the way Rebound runs one: a step asks the screen for the next frame last,
     * until its springs come to rest after {@link #REST_AFTER} steps. Robolectric's screen frames can come
     * with no time passing, so one that never rested would keep idleFor from ever returning.
     */
    private static final class SpringLooper implements android.view.Choreographer.FrameCallback {
        static final int REST_AFTER = 5;
        int steps;
        boolean throwNext;
        boolean postBeforeThrow;

        @Override
        public void doFrame(long frameTimeNanos) {
            steps++;
            if (throwNext) {
                throwNext = false;
                if (postBeforeThrow) android.view.Choreographer.getInstance().postFrameCallback(this);
                throw new IllegalStateException("test");
            }
            if (steps < REST_AFTER) android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** A screen the test can put in picture in picture. */
    public static final class Screen extends Activity {
        boolean pip;

        @Override
        public boolean isInPictureInPictureMode() {
            return pip;
        }
    }

    /** Players the test plays and pauses. Its pause asks the hook first, as the patched one does. */
    private static final class Players implements BackgroundPlay.Control {
        final Set<Object> playing = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        final List<Boolean> heldOwnPause = new ArrayList<>();
        final List<Boolean> heldOwnRelease = new ArrayList<>();
        final List<Object> released = new ArrayList<>();
        /** Whether a pause lets the player go too, as Instagram's can while its screen is going away. */
        boolean releaseOnPause;
        /** For a player, another Instagram lets go along with it, as one screen's players go together. */
        final Map<Object, Object> releasesWith = new HashMap<>();

        @Override
        public boolean playing(Object player) {
            return playing.contains(player);
        }

        /** Asks the hook first and, like the patched release, returns at once when it holds it. */
        @Override
        public void release(Object player, String reason) {
            boolean heldIt = BackgroundPlay.releasing(player, reason);
            heldOwnRelease.add(heldIt);
            if (heldIt) return;
            playing.remove(player);
            released.add(player);
            calls.add("release " + reason);
            Object along = releasesWith.remove(player);
            if (along != null) release(along, "along");
        }

        @Override
        public void pause(Object player) {
            boolean heldIt = BackgroundPlay.hold(player, BackgroundPlay.PAUSE_REASON);
            heldOwnPause.add(heldIt);
            if (heldIt) return;
            if (releaseOnPause) release(player, "paused");
            playing.remove(player);
            calls.add("pause");
        }

        @Override
        public void play(Object player) {
            playing.add(player);
            calls.add("play");
        }
    }
}
