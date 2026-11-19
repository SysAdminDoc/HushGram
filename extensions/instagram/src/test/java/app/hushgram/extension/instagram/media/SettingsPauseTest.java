/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.Collections;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The Home and Reels videos under the HushGram settings pause while they're open and play again
 * when they close (#762): the settings are a dialog, so Instagram never pauses them itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SettingsPauseTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object video = new Object();
    private final Object other = new Object();
    private SettingsPauseForTests.Players players;

    @Before
    public void install() {
        PauseForTests.resume();
        HookStatus.clear();
        TapToPlayForTests.forget();
        players = SettingsPauseForTests.install();
    }

    @After
    public void restore() {
        SettingsPauseForTests.forget();
        Settings.TAP_TO_PLAY.resetToDefault();
        TapToPlayForTests.forget();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void openingPausesWhatPlaysAndClosingPlaysItAgain() {
        players.start(video);
        players.start(other);
        players.stop(other);

        SettingsPause.opened();
        assertFalse("the video under the settings is paused", players.isPlaying(video));
        assertEquals("a video you'd paused yourself is left alone", Collections.singletonList(video), players.paused);

        SettingsPause.closed();
        assertTrue("closing the settings plays it again", players.isPlaying(video));
        assertFalse("and only it", players.isPlaying(other));
        assertEquals(Collections.singletonList(video), players.played);
    }

    @Test
    public void aStartUnderTheSettingsIsPausedOnceItsDone() {
        SettingsPause.opened();
        players.start(video);
        assertTrue("Instagram's start runs first", players.isPlaying(video));

        ShadowLooper.idleMainLooper();
        assertFalse("then it's paused", players.isPlaying(video));

        SettingsPause.closed();
        assertTrue(players.isPlaying(video));
        assertEquals(Collections.singletonList(video), players.played);
    }

    /**
     * The launcher shortcut pauses and restarts the feed as the settings open, so the pause can come
     * while Instagram still prepares the video. Once it's ready its state reads playing though it
     * stays paused, and closing the settings still has to play it (#762, seen on 456).
     */
    @Test
    public void aVideoPausedWhilePreparingPlaysWhenTheyClose() {
        SettingsPause.opened();
        players.start(video);
        ShadowLooper.idleMainLooper();
        players.prepared(video);
        assertTrue("Instagram's state says it plays", BackgroundPlay.playingNow(video));
        assertFalse("though it's paused", players.isPlaying(video));

        SettingsPause.closed();
        assertTrue("closing the settings plays it", players.isPlaying(video));
        assertEquals(Collections.singletonList(video), players.played);
    }

    /** Instagram's own play skips a video it already plays again, so closing never starts it twice. */
    @Test
    public void aVideoThatPlaysAgainAlreadyIsLeftAlone() {
        players.start(video);
        SettingsPause.opened();
        // Instagram starts it again right before they close, ahead of the posted pause.
        players.start(video);
        SettingsPause.closed();
        ShadowLooper.idleMainLooper();

        assertTrue(players.isPlaying(video));
        assertTrue("no second start", players.played.isEmpty());
        assertEquals("and no pause once they've closed", Collections.singletonList(video), players.paused);
    }

    @Test
    public void leavingInstagramWithTheSettingsOpenKeepsTheVideoForTheClose() {
        players.start(video);
        SettingsPause.opened();
        SettingsPause.hidden();
        assertFalse(SettingsPause.showing());

        // Back in Instagram it plays the feed again, then the settings show again over it.
        players.start(video);
        ShadowLooper.idleMainLooper();
        assertTrue("a start while they're hidden goes ahead", players.isPlaying(video));
        SettingsPause.opened();
        assertFalse(players.isPlaying(video));

        SettingsPause.closed();
        assertEquals("played again once", Collections.singletonList(video), players.played);
        assertEquals(Arrays.asList(video, video), players.paused);
    }

    @Test
    public void aFinishingScreenForgetsThem() {
        players.start(video);
        SettingsPause.opened();
        SettingsPause.dropped();

        SettingsPause.closed();
        assertFalse(players.isPlaying(video));
        assertTrue("nothing to play on a screen that's gone", players.played.isEmpty());
    }

    @Test
    public void nothingIsPausedWithoutTheSettings() {
        players.start(video);
        ShadowLooper.idleMainLooper();
        SettingsPause.closed();
        assertTrue(players.isPlaying(video));
        assertTrue(players.paused.isEmpty());
        assertTrue(players.played.isEmpty());
    }

    @Test
    public void openingThemAgainPausesItAgain() {
        players.start(video);
        SettingsPause.opened();
        SettingsPause.closed();
        SettingsPause.opened();
        assertFalse(players.isPlaying(video));
        SettingsPause.closed();
        assertTrue(players.isPlaying(video));
        assertEquals(Arrays.asList(video, video), players.paused);
        assertEquals(Arrays.asList(video, video), players.played);
    }

    /**
     * With Tap to play on, a video you tapped stays tapped through the pause under the settings, so
     * it plays on when they close and its play button stays hidden. A pause after they close still
     * makes it wait for a tap again.
     */
    @Test
    public void aTappedVideoStaysTappedUnderTheSettings() {
        Settings.TAP_TO_PLAY.save(true);
        TapToPlay.tappedFromNotification(video);
        assertTrue(TapToPlay.armed(video));
        players.start(video);

        SettingsPause.opened();
        assertFalse(players.isPlaying(video));
        assertTrue("still tapped under the settings", TapToPlay.armed(video));

        SettingsPause.closed();
        assertTrue(players.isPlaying(video));
        TapToPlay.paused(video, BackgroundPlay.PAUSE_REASON);
        assertFalse("a pause once they've closed waits for a tap", TapToPlay.armed(video));
    }
}
