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
import java.util.HashSet;
import java.util.List;
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

    /** Instagram letting the player go, or the screen closing, ends it. */
    @Test
    public void releaseOrCloseEndsIt() {
        background(player);
        BackgroundPlay.released(other);
        assertEquals(BackgroundPlay.Phase.BACKGROUND, BackgroundPlay.phase());
        BackgroundPlay.released(player);
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
        assertTrue("a released player isn't paused", players.calls.isEmpty());

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

    /** No hook throws into Instagram, whatever it's handed. */
    @Test
    public void nullsAreHarmless() {
        BackgroundPlay.started(null);
        BackgroundPlay.volume(null, 1f);
        BackgroundPlay.released(null);
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

        @Override
        public boolean playing(Object player) {
            return playing.contains(player);
        }

        @Override
        public void pause(Object player) {
            heldOwnPause.add(BackgroundPlay.hold(player, BackgroundPlay.PAUSE_REASON));
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
