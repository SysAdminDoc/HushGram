/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Pause and Play in Instagram's picture in picture window for reels (#873): the button joins the
 * list Instagram sets, pauses the video in the window and plays it again, and a video Instagram
 * starts itself turns it back into Pause.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsPipButtonsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object video = new Object();
    private final Object next = new Object();
    private final FakeWindow window = new FakeWindow();
    private SettingsPauseForTests.Players players;
    private Activity screen;
    private RemoteAction previous;
    private RemoteAction forward;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        players = SettingsPauseForTests.install();
        ReelsPipButtons.resetForTests();
        ReelsPipButtons.windowForTests = window;
        Settings.REELS_PIP_PLAY_BUTTON.save(true);
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        previous = instagramButton("Previous");
        forward = instagramButton("Next");
    }

    @After
    public void restore() {
        ReelsPipButtons.resetForTests();
        SettingsPauseForTests.forget();
        Settings.REELS_PIP_PLAY_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start: Instagram's flag decides, and its list goes in as it made it. */
    @Test
    public void startsOff() {
        Settings.REELS_PIP_PLAY_BUTTON.resetToDefault();
        assertFalse(ReelsPipButtons.withActions(screen, 0));
        assertTrue(ReelsPipButtons.withActions(screen, 1));
        List<RemoteAction> theirs = Arrays.asList(previous, forward);
        assertSame(theirs, ReelsPipButtons.actions(theirs));
    }

    /** On, the list is set even where Instagram's flag says no, with Pause alone in it. */
    @Test
    public void pauseGoesInWhereInstagramSetsNoButtons() {
        assertTrue(ReelsPipButtons.withActions(screen, 0));
        assertEquals(Collections.singletonList("Pause"), titles(ReelsPipButtons.actions(Arrays.asList(previous, forward))));
    }

    /** Where Instagram's flag says yes, Pause goes between its Previous and Next. */
    @Test
    public void pauseGoesBetweenInstagramsButtons() {
        assertTrue(ReelsPipButtons.withActions(screen, 1));
        assertEquals(Arrays.asList("Previous", "Pause", "Next"), titles(ReelsPipButtons.actions(Arrays.asList(previous, forward))));
    }

    /** Android refuses a list longer than the window takes, so Instagram's first button goes. */
    @Test
    public void aWindowWithLessRoomLosesInstagramsFirstButton() {
        window.room = 2;
        ReelsPipButtons.withActions(screen, 1);
        assertEquals(Arrays.asList("Pause", "Next"), titles(ReelsPipButtons.actions(Arrays.asList(previous, forward))));
    }

    /** Pause in the window pauses the video and turns into Play, and Play plays it again. */
    @Test
    public void pauseAndPlayFromTheWindow() throws Exception {
        players.start(video);
        window.showing = true;
        ReelsPipButtons.withActions(screen, 0);
        List<RemoteAction> buttons = cast(ReelsPipButtons.actions(new ArrayList<>()));

        tap(buttons.get(0));
        assertFalse("the video in the window is paused", players.isPlaying(video));
        assertEquals(Collections.singletonList(video), players.paused);
        assertEquals("the window shows Play", Collections.singletonList("Play"), titles(window.last()));

        tap(window.last().get(0));
        assertTrue("Play plays it again", players.isPlaying(video));
        assertEquals(Collections.singletonList(video), players.played);
        assertEquals("the window shows Pause", Collections.singletonList("Pause"), titles(window.last()));
    }

    /** A video Instagram starts itself, the next reel, turns Play back into Pause. */
    @Test
    public void aVideoStartingTurnsPlayBackIntoPause() throws Exception {
        players.start(video);
        window.showing = true;
        ReelsPipButtons.withActions(screen, 1);
        List<RemoteAction> buttons = cast(ReelsPipButtons.actions(Arrays.asList(previous, forward)));
        tap(buttons.get(1));
        assertEquals(Arrays.asList("Previous", "Play", "Next"), titles(window.last()));

        players.start(next);
        ShadowLooper.idleMainLooper();
        assertFalse(ReelsPipButtons.showsPlay());
        assertEquals(Arrays.asList("Previous", "Pause", "Next"), titles(window.last()));
    }

    /** Play with nothing paused from the window plays the newest video. */
    @Test
    public void playWithNothingPausedPlaysTheNewestVideo() {
        players.start(video);
        players.start(next);
        players.stop(next);
        ReelsPipButtons.playTapped();
        assertEquals(Collections.singletonList(next), players.played);
    }

    /** A window set up again while it isn't showing is a new one, where the reel plays. */
    @Test
    public void aNewWindowStartsWithPause() {
        players.start(video);
        window.showing = true;
        ReelsPipButtons.withActions(screen, 0);
        ReelsPipButtons.actions(new ArrayList<>());
        ReelsPipButtons.pauseTapped();
        assertTrue(ReelsPipButtons.showsPlay());

        window.showing = false;
        ReelsPipButtons.withActions(screen, 0);
        assertEquals(Collections.singletonList("Pause"), titles(ReelsPipButtons.actions(new ArrayList<>())));
    }

    /** Paused, or before HushGram's settings are ready, Instagram decides. */
    @Test
    public void pausedOrNotReadyLeavesItToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelsPipButtons.withActions(screen, 0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(ReelsPipButtons.withActions(screen, 0)));
    }

    /** A failure sets what Instagram would have, and is reported. */
    @Test
    public void aFailureLeavesInstagramsList() {
        window.broken = true;
        List<RemoteAction> theirs = Arrays.asList(previous, forward);
        ReelsPipButtons.withActions(screen, 1);
        assertSame(theirs, ReelsPipButtons.actions(theirs));
        ReelsPipButtons.withActions(screen, 0);
        assertTrue(ReelsPipButtons.actions(theirs).isEmpty());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(ReelsPipButtons.HOOK));
    }

    private static void tap(RemoteAction button) throws PendingIntent.CanceledException {
        button.getActionIntent().send();
        ShadowLooper.idleMainLooper();
    }

    private RemoteAction instagramButton(String title) {
        Context context = screen.getApplicationContext();
        Intent intent = new Intent("instagram.features.clips.pip.ACTION_" + title.toUpperCase(java.util.Locale.ROOT))
                .setPackage(context.getPackageName());
        PendingIntent tap = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(context, android.R.drawable.ic_media_next), title, title, tap);
    }

    private static List<String> titles(List<?> buttons) {
        List<String> titles = new ArrayList<>();
        for (Object button : buttons) titles.add(((RemoteAction) button).getTitle().toString());
        return titles;
    }

    @SuppressWarnings("unchecked")
    private static List<RemoteAction> cast(List<?> buttons) {
        return (List<RemoteAction>) buttons;
    }

    private static final class FakeWindow implements ReelsPipButtons.Window {
        boolean showing;
        boolean broken;
        int room = 3;
        final List<List<RemoteAction>> set = new ArrayList<>();

        @Override
        public boolean showing(Activity screen) {
            return showing;
        }

        @Override
        public int maxActions(Activity screen) {
            if (broken) throw new IllegalStateException("boom");
            return room;
        }

        @Override
        public void setActions(Activity screen, List<RemoteAction> actions) {
            set.add(actions);
        }

        List<RemoteAction> last() {
            assertFalse("the window's buttons were never set again", set.isEmpty());
            return set.get(set.size() - 1);
        }
    }
}
