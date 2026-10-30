/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * A tap on a feed video itself, not on its play button, starts it through Instagram's autoplay, so
 * the button's click never runs: the button under the tap is found and hidden while the video
 * plays, like a tapped one (#229).
 */
@RunWith(RobolectricTestRunner.class)
public class PlayButtonFinderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private View header;
    private FrameLayout container;
    private FrameLayout frame;
    private FrameLayout emptyHost;
    private ImageView button;
    private View sound;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
        Settings.TAP_TO_PLAY.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();

        // A post: its header, then the video's container with the frame the video and its button sit
        // in. Like Instagram 451's, the button is a clickable image next to a larger empty host that
        // isn't clickable, both in the middle, with the sound button in a corner.
        LinearLayout post = new LinearLayout(activity);
        post.setOrientation(LinearLayout.VERTICAL);
        header = new View(activity);
        post.addView(header, new LinearLayout.LayoutParams(300, 50));
        container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        PlayButtonFinder.useContainerId(container.getId());
        post.addView(container, new LinearLayout.LayoutParams(300, 400));
        frame = new FrameLayout(activity);
        container.addView(frame, new FrameLayout.LayoutParams(300, 400));
        frame.addView(new View(activity), new FrameLayout.LayoutParams(300, 400));
        emptyHost = new FrameLayout(activity);
        frame.addView(emptyHost, new FrameLayout.LayoutParams(112, 112, Gravity.CENTER));
        button = new ImageView(activity);
        button.setClickable(true);
        frame.addView(button, new FrameLayout.LayoutParams(96, 96, Gravity.CENTER));
        sound = new View(activity);
        sound.setClickable(true);
        frame.addView(sound, new FrameLayout.LayoutParams(40, 40, Gravity.BOTTOM | Gravity.END));
        activity.setContentView(post);
        idle(100);
    }

    @After
    public void restore() {
        Settings.TAP_TO_PLAY.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        PlayButtonFinder.useContainerId(0);
        TapToPlayForTests.forget();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** A tap at ([dx], [dy]) inside [view], through the activity's touch hook like a finger's. */
    private void tapOn(View view, int dx, int dy) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now - 60, now - 60, MotionEvent.ACTION_DOWN, at[0] + dx, at[1] + dy, 0);
        MotionEvent up = MotionEvent.obtain(now - 60, now, MotionEvent.ACTION_UP, at[0] + dx, at[1] + dy, 0);
        TapClock.touch(activity, down);
        TapClock.touch(activity, up);
        down.recycle();
        up.recycle();
    }

    /** The video's start, on the tap, hides the button until the start ends, and the log says so. */
    @Test
    public void aTapOnTheVideoHidesItsPlayButtonWhileItPlays() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        assertTrue("laid out", container.getWidth() == 300 && button.getWidth() == 96);
        Object player = new Object();

        tapOn(container, 40, 350);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        assertTrue(TapToPlay.allowDirectStart(player, "autoplay"));
        idle(PlayButtons.UNCLAIMED_MS + 100);

        assertEquals(View.INVISIBLE, button.getVisibility());
        assertEquals("the sound button stays", View.VISIBLE, sound.getVisibility());
        assertEquals("the empty host stays", View.VISIBLE, emptyHost.getVisibility());
        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("a tap on the video started it, so its play button is hidden while it plays"));
    }

    /** The button's own click, heard after the start found it, keeps it hidden for that start. */
    @Test
    public void theButtonsOwnClickAfterwardKeepsItHidden() {
        Object player = new Object();
        tapOn(button, 48, 48);
        assertTrue(TapToPlay.allowStart(player, "autoplay", 0));
        idle(0);
        TapToPlay.playButtonTapped(new PlayButtonsTest.ClickEvent(button));
        idle(PlayButtons.UNCLAIMED_MS + 100);
        assertEquals(View.INVISIBLE, button.getVisibility());

        TapToPlay.paused(player, "scroll");
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** Reels have no feed play button, so a start there hides nothing. */
    @Test
    public void aStartInReelsHidesNothing() {
        tapOn(container, 40, 350);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 1));
        idle(0);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertNull(PlayButtons.hiddenButton());
    }

    /** A tap that landed outside any video, on the post's header, hides nothing and says so. */
    @Test
    public void aTapOutsideTheVideoHidesNothing() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        tapOn(header, 20, 20);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        idle(0);
        assertEquals(View.VISIBLE, button.getVisibility());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("no play button was found under the tap"));
    }

    /** A newer tap by the time the main thread looks leaves the button for that tap's own start. */
    @Test
    public void aNewerTapLeavesTheButton() {
        tapOn(container, 40, 350);
        assertTrue(TapToPlay.allowStart(new Object(), "autoplay", 0));
        SystemClock.sleep(10);
        tapOn(header, 20, 20);
        idle(0);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /**
     * Only the clickable square in the middle counts: not the empty host beside it, which the first
     * device check hid while the icon stayed, not the sound button, not a plain view.
     */
    @Test
    public void onlyTheClickableSquareInTheMiddleCounts() {
        int[] at = new int[2];
        container.getLocationInWindow(at);
        float x = at[0] + 40;
        float y = at[1] + 350;
        assertSame(button, PlayButtonFinder.find(activity.getWindow().getDecorView(), x, y, container.getId()));
        assertEquals("the empty host has the button's shape", 0f, PlayButtonFinder.offMiddle(emptyHost, x, y), 0.01f);
        assertEquals(-1f, PlayButtonFinder.offMiddle(sound, x, y), 0f);

        frame.removeView(button);
        frame.addView(new View(activity), new FrameLayout.LayoutParams(96, 96, Gravity.CENTER));
        idle(100);
        assertNull(PlayButtonFinder.find(activity.getWindow().getDecorView(), x, y, container.getId()));
        assertNull("no container id", PlayButtonFinder.find(activity.getWindow().getDecorView(), x, y, 0));
    }

    /** In a carousel, the page the tap landed on gives its button, not the page beside it. */
    @Test
    public void aCarouselGivesTheTappedPagesButton() {
        container.removeAllViews();
        LinearLayout pages = new LinearLayout(activity);
        container.addView(pages, new FrameLayout.LayoutParams(300, 400));
        ImageView[] buttons = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            FrameLayout page = new FrameLayout(activity);
            pages.addView(page, new LinearLayout.LayoutParams(150, 400));
            buttons[i] = new ImageView(activity);
            buttons[i].setClickable(true);
            page.addView(buttons[i], new FrameLayout.LayoutParams(48, 48, Gravity.CENTER));
        }
        idle(100);

        int[] at = new int[2];
        container.getLocationInWindow(at);
        assertSame(buttons[1], PlayButtonFinder.find(activity.getWindow().getDecorView(), at[0] + 200, at[1] + 50, container.getId()));
        assertSame(buttons[0], PlayButtonFinder.find(activity.getWindow().getDecorView(), at[0] + 20, at[1] + 380, container.getId()));
    }
}
