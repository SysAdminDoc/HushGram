/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The feed's Litho play button, hidden while the video a tap on it started plays: it goes on the
 * tap, stays through Instagram's own restarts, and comes back when that start ends, when no start
 * comes, or when Litho takes its view away.
 */
@RunWith(RobolectricTestRunner.class)
public class PlayButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FrameLayout post;
    private ImageView button;

    /** Stands in for Litho's click event: the view is a field, under whatever name the build gave it. */
    static class ClickEvent {
        public View A00;
        public final int A01 = 1;

        ClickEvent(View view) {
            A00 = view;
        }
    }

    /** An event whose view is a private field of its superclass. */
    static final class WrappedClickEvent extends PrivateClickEvent {
        WrappedClickEvent(View view) {
            super(view);
        }
    }

    static class PrivateClickEvent {
        private static View unrelated;
        private final View view;

        PrivateClickEvent(View view) {
            this.view = view;
        }
    }

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
        Settings.TAP_TO_PLAY.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        post = new FrameLayout(activity);
        button = new ImageView(activity);
        post.addView(button);
        activity.setContentView(post);
    }

    @After
    public void restore() {
        Settings.TAP_TO_PLAY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    /** Taps [view]'s button: the tap clock hears it, then the click tells the hook. */
    private static void tap(View view) {
        TapToPlayForTests.tapEnded(0);
        TapToPlay.playButtonTapped(new ClickEvent(view));
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /**
     * The tap hides the button, the start it asked for keeps it hidden through the restarts and a
     * momentary pause, and a pause for a scroll brings it back.
     */
    @Test
    public void theButtonHidesWhileItsVideoPlays() {
        Object player = new Object();

        tap(button);
        assertEquals(View.INVISIBLE, button.getVisibility());
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        assertTrue(TapToPlay.allowDirectStart(player, "autoplay"));
        idle(PlayButtons.UNCLAIMED_MS + 100);
        TapToPlay.paused(player, "paused_for_replay");
        assertEquals("while it plays and loops", View.INVISIBLE, button.getVisibility());

        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
    }

    /**
     * Instagram starts the video inside the button's click, before the hook hears of the click, so
     * that start is the button's too: it stays hidden past the wait for a start, and the log says
     * why it went and why it came back.
     */
    @Test
    public void aStartTheClickItselfSetOffIsTheButtons() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Object player = new Object();

        TapToPlayForTests.tapEnded(0);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapToPlay.playButtonTapped(new ClickEvent(button));
        idle(PlayButtons.UNCLAIMED_MS + 100);
        assertEquals(View.INVISIBLE, button.getVisibility());

        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the play button is hidden while its video plays"));
        assertTrue(report, report.contains("the play button is back, its video stopped"));
    }

    /**
     * A start decided on the player's thread just before the tap, that reaches the button after its
     * click, isn't the button's.
     */
    @Test
    public void aStartDecidedBeforeTheTapThatArrivesAfterTheClickIsntTheButtons() {
        long now = SystemClock.uptimeMillis();
        PlayButtons.hide(button, now, now - 80);
        PlayButtons.started(new Object(), now - 120);
        idle(PlayButtons.UNCLAIMED_MS + 1);

        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
    }

    /** A tap on the button of a video whose player is still armed starts it again: that start is the button's. */
    @Test
    public void anArmedPlayersStartOnTheTapIsTheButtons() {
        Object player = new Object();
        TapToPlayForTests.tapEnded(0);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        SystemClock.sleep(50);

        tap(button);
        assertTrue(TapToPlay.allowStart(player, "resume", 0));
        idle(PlayButtons.UNCLAIMED_MS + 100);
        assertEquals(View.INVISIBLE, button.getVisibility());

        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** A start an earlier tap let through isn't the button's, so the button comes back when its wait runs out. */
    @Test
    public void aStartBeforeTheTapIsntTheButtons() {
        TapToPlayForTests.tapEnded(0);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        SystemClock.sleep(50);

        tap(button);
        idle(PlayButtons.UNCLAIMED_MS + 1);

        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
    }

    /** Another video on the button's player, once the start's own prepare has passed, brings it back. */
    @Test
    public void aNewVideoOnItsPlayerBringsTheButtonBack() {
        Object player = new Object();
        tap(button);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        TapToPlay.rebound(player);
        assertEquals("the start's own prepare", View.INVISIBLE, button.getVisibility());

        SystemClock.sleep(TapToPlay.BIND_GRACE_MS + 1);
        TapToPlay.rebound(player);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** A tap no start comes of, a data dialog left open for one, gets the button back. */
    @Test
    public void aTapNoStartComesOfGetsTheButtonBack() {
        tap(button);
        idle(PlayButtons.UNCLAIMED_MS - 100);
        assertEquals(View.INVISIBLE, button.getVisibility());

        idle(200);
        assertEquals(View.VISIBLE, button.getVisibility());
        SystemClock.sleep(10);
        assertFalse("a late start isn't the button's", TapToPlay.decide(new Object(), "autoplay", SystemClock.uptimeMillis() + 5_000, ""));
    }

    /** A start later than a load after the tap isn't the tap's, so the button comes back when its wait runs out. */
    @Test
    public void aStartAfterTheTapWindowIsntTheButtons() {
        tap(button);
        long late = SystemClock.uptimeMillis() + PlayButtons.CLAIM_WINDOW_MS + 1;
        PlayButtons.started(new Object(), late);
        idle(PlayButtons.UNCLAIMED_MS + 1);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** Litho hands a view it took off the screen to the next thing it draws, so the view comes back first. */
    @Test
    public void aButtonTakenOffTheScreenComesBack() {
        Object player = new Object();
        tap(button);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));

        post.removeView(button);

        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** A tap on a second video's button brings the first back: Instagram plays one feed video at a time. */
    @Test
    public void aSecondButtonBringsTheFirstBack() {
        ImageView second = new ImageView(post.getContext());
        post.addView(second);
        Object player = new Object();
        tap(button);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));

        tap(second);

        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(View.INVISIBLE, second.getVisibility());
        assertSame(second, PlayButtons.hiddenButton());
    }

    /** A button Instagram had set otherwise goes back to what it was. */
    @Test
    public void theButtonGoesBackToWhatItWas() {
        button.setVisibility(View.GONE);
        tap(button);
        assertEquals(View.INVISIBLE, button.getVisibility());
        idle(PlayButtons.UNCLAIMED_MS + 1);
        assertEquals(View.GONE, button.getVisibility());
    }

    /** Off, a tap on the button is Instagram's alone. */
    @Test
    public void offTheButtonIsInstagrams() {
        Settings.TAP_TO_PLAY.save(false);
        tap(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
    }

    /** The view is found in the event whatever its field is called, private or inherited. */
    @Test
    public void theEventsViewIsFoundByItsType() throws IllegalAccessException {
        assertSame(button, PlayButtons.viewOf(new ClickEvent(button)));
        assertSame(button, PlayButtons.viewOf(new WrappedClickEvent(button)));
        assertSame(button, PlayButtons.viewOf(button));
        assertNull(PlayButtons.viewOf(new ClickEvent(null)));
        assertNull(PlayButtons.viewOf(new Object()));
        assertNull(PlayButtons.viewOf(null));
    }

    /** A click with no view leaves the button, says so once in the log, and counts in the report. */
    @Test
    public void aClickWithoutAViewLeavesTheButton() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();

        TapToPlay.playButtonTapped(new Object());
        TapToPlay.playButtonTapped(null);

        assertEquals(View.VISIBLE, button.getVisibility());
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, report.split("click carries no view", -1).length - 1);
        String status = String.join("\n", HookStatus.report());
        assertTrue(status, status.contains(FamilyNames.TAP_TO_PLAY + ": invoked 2, 1 found, 0 missing"));
    }
}
