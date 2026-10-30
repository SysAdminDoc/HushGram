/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Burn-in guard (#162): on the Reels screen a reel's overlay, the top bar and the tab bar dim after
 * five seconds without a touch, a touch brings them back, and the shift moves the content a step
 * every two minutes. Off, Pause, another screen and the activity leaving the front put everything
 * back the way Instagram had it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BurnInGuardTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7201, BAR = 7202, REELS_TAB = 7203, HOME_TAB = 7204;
    private static final int OVERLAY = 7301, UFI = 7302, TOP_BAR = 7303;
    private static final int INFO = 7306, SAVE = 7401;
    /** The Dim after choice to start with. */
    private static final long IDLE = BurnInWait.FIVE_SECONDS.millis;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FrameLayout viewer;
    private View video;
    private FrameLayout overlay;
    private View ufi;
    private View topBar;
    private LinearLayout bar;
    private View reelsTab, homeTab;
    /** What {@link #layOutTheReelsButtons} adds: the column's Like, Save and Download, and the caption. */
    private View like, save, download, caption;

    @Before public void prepare() {
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        PauseForTests.resume();
        BurnInGuard.resetForTests();
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        for (String name : BurnInGuard.DIMMED_IDS) BurnInGuard.idForTests(name, 0);
        for (String name : BurnInGuard.WAKING_IDS) BurnInGuard.idForTests(name, 0);
        for (String name : BurnInGuard.QUIET_IDS) BurnInGuard.idForTests(name, 0);
        // No reels' pager to start with, so every finger is a touch, as it was before the quiet taps.
        BurnInGuard.idForTests(BurnInGuard.REEL_ID, 0);
        BurnInGuard.idForTests("clips_item_overlay_component", OVERLAY);
        BurnInGuard.idForTests("clips_ufi_component", UFI);
        BurnInGuard.idForTests("clips_viewer_action_bar", TOP_BAR);
        BurnInGuard.idForTests("tab_bar", BAR);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);

        root = new FrameLayout(activity);
        activity.setContentView(root);
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        video = new View(activity);
        viewer.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new FrameLayout(activity);
        overlay.setId(OVERLAY);
        ufi = new View(activity);
        ufi.setId(UFI);
        overlay.addView(ufi, new FrameLayout.LayoutParams(60, 300));
        viewer.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        topBar = new View(activity);
        topBar.setId(TOP_BAR);
        viewer.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        root.addView(viewer, new FrameLayout.LayoutParams(W, H));
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        reelsTab = tab(REELS_TAB);
        homeTab = tab(HOME_TAB);
        reelsTab.setSelected(true);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
        layout();
    }

    @After public void close() {
        BurnInGuard.resetForTests();
        controller.close();
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        PauseForTests.resume();
    }

    private View tab(int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** Lets [millis] go by, running whatever the main thread had due in them. */
    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /**
     * A finger event the way the system delivers it: to the decor view, which hands it to the window's
     * callback. It lands in the middle of the screen's content, clear of the action bar a plain test
     * activity has, which on API 35 and newer is drawn over the top of the content.
     */
    private void send(int action) {
        int[] at = new int[2];
        root.getLocationInWindow(at);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, at[0] + root.getWidth() / 2f, at[1] + root.getHeight() / 2f, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void tap() {
        send(MotionEvent.ACTION_DOWN);
        send(MotionEvent.ACTION_UP);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void focusBack() {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void relayout() {
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        after(BurnInGuard.LOOK_MS + 10);
    }

    private void assertDimmed(String why) {
        assertEquals(why + ": the overlay", BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
        assertEquals(why + ": the top bar", BurnInGuard.IDLE_LEVEL, topBar.getAlpha(), 0f);
        assertEquals(why + ": the tab bar", BurnInGuard.IDLE_LEVEL, bar.getAlpha(), 0f);
        assertEquals(why + ": inside the overlay it isn't dimmed twice", 1f, ufi.getAlpha(), 0f);
        assertEquals(why + ": the reel itself", 1f, video.getAlpha(), 0f);
    }

    private void assertUp(String why) {
        assertEquals(why + ": the overlay", 1f, overlay.getAlpha(), 0f);
        assertEquals(why + ": the top bar", 1f, topBar.getAlpha(), 0f);
        assertEquals(why + ": the tab bar", 1f, bar.getAlpha(), 0f);
    }

    @Test public void offToStartAndNothingDimsOrListens() {
        assertEquals(BurnInMode.OFF, Settings.BURN_IN_GUARD.get());
        Object before = activity.getWindow().getCallback();
        BurnInGuard.resumed(activity);
        after(IDLE * 3);
        assertUp("off");
        assertFalse("no layout listener while off", BurnInGuard.watchingLayoutsForTests());
        assertSame("no touch watcher while off", before, activity.getWindow().getCallback());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
        assertEquals(0f, root.getTranslationX(), 0f);
    }

    @Test public void theControlsDimAfterFiveSecondsWithoutATouch() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        assertTrue(BurnInGuard.watchingLayoutsForTests());
        after(IDLE - 100);
        assertUp("before five seconds");
        after(200);
        assertDimmed("after five seconds");
        assertEquals("Dim alone never moves the screen", 0f, root.getTranslationX(), 0f);
        assertEquals(0f, root.getTranslationY(), 0f);
    }

    /**
     * The seek bar and the Interested row sit outside the overlay on 451, and dim and come back with
     * it (#162). Laid out as the S22 showed it: a layer without an id holding the author and caption,
     * the button column, the row (no id, two buttons) and a strip under it.
     */
    @Test public void theSeekBarAndTheInterestRowDimToo() {
        BurnInGuard.idForTests("scrubber", 7304);
        BurnInGuard.idForTests("clips_media_info_component", 7306);
        android.widget.SeekBar scrubber = new android.widget.SeekBar(activity);
        scrubber.setId(7304);
        viewer.addView(scrubber, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28));
        FrameLayout layer = new FrameLayout(activity);
        View info = new View(activity);
        info.setId(7306);
        layer.addView(info, new FrameLayout.LayoutParams(200, 100));
        View column = new View(activity);
        column.setId(UFI);
        layer.addView(column, new FrameLayout.LayoutParams(60, 300));
        LinearLayout interest = new LinearLayout(activity);
        android.widget.Button notInterested = new android.widget.Button(activity);
        interest.addView(notInterested, new LinearLayout.LayoutParams(200, 60));
        interest.addView(new android.widget.Button(activity), new LinearLayout.LayoutParams(200, 60));
        layer.addView(interest, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        View named = new View(activity);
        named.setId(7307);
        layer.addView(named, new FrameLayout.LayoutParams(40, 40));
        viewer.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");
        assertEquals("the seek bar", BurnInGuard.IDLE_LEVEL, scrubber.getAlpha(), 0f);
        assertEquals("the author and caption", BurnInGuard.IDLE_LEVEL, info.getAlpha(), 0f);
        assertEquals("the button column", BurnInGuard.IDLE_LEVEL, column.getAlpha(), 0f);
        assertEquals("the Interested row beside it", BurnInGuard.IDLE_LEVEL, interest.getAlpha(), 0f);
        assertEquals("a button in the row isn't dimmed twice", 1f, notInterested.getAlpha(), 0f);
        assertEquals("a view beside the column with an id of its own stays", 1f, named.getAlpha(), 0f);
        assertEquals("the layer itself stays", 1f, layer.getAlpha(), 0f);
        relayout();
        assertEquals("a second look doesn't dim the row again", BurnInGuard.IDLE_LEVEL, interest.getAlpha(), 0f);
        tap();
        assertEquals("the seek bar back", 1f, scrubber.getAlpha(), 0f);
        assertEquals("the button column back", 1f, column.getAlpha(), 0f);
        assertEquals("the Interested row back", 1f, interest.getAlpha(), 0f);
    }

    @Test public void aTouchBringsThemBackAndAHeldFingerKeepsThemUpUntilItLifts() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");

        send(MotionEvent.ACTION_DOWN);
        assertUp("the finger went down");
        after(IDLE * 3);
        assertUp("a held finger is a touch");

        send(MotionEvent.ACTION_UP);
        after(IDLE - 100);
        assertUp("the wait starts at the lift");
        after(200);
        assertDimmed("five seconds after the lift");
    }

    @Test public void instagramsOwnAlphaIsWhatTheDimIsTakenFromAndAChangeItMakesIsKept() {
        overlay.setAlpha(0.8f);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertEquals(0.8f * BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
        tap();
        assertEquals("back to Instagram's own", 0.8f, overlay.getAlpha(), 0f);

        after(IDLE + 100);
        overlay.setAlpha(0.5f);
        tap();
        assertEquals("Instagram's change while dimmed stays", 0.5f, overlay.getAlpha(), 0f);
    }

    @Test public void onlyTheReelsScreenDimsAndLeavingItBringsTheControlsBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("on Reels");

        // Home: the viewer goes and Home's tab is selected, with no touch reaching this window.
        root.removeView(viewer);
        reelsTab.setSelected(false);
        homeTab.setSelected(true);
        relayout();
        assertEquals("the tab bar comes back off Reels", 1f, bar.getAlpha(), 0f);
        assertEquals(1f, overlay.getAlpha(), 0f);
        after(IDLE * 3);
        assertEquals("Home never dims", 1f, bar.getAlpha(), 0f);
    }

    @Test public void dimAndShiftMovesTheScreenAStepEveryTwoMinutesAndPutsItBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        float step = BurnInGuard.STEP_DP * activity.getResources().getDisplayMetrics().density;
        // Start right at a place's first moment, so the next one is two minutes away.
        long now = SystemClock.uptimeMillis();
        after(BurnInGuard.SHIFT_EVERY_MS - now % BurnInGuard.SHIFT_EVERY_MS);
        BurnInGuard.resumed(activity);
        View content = activity.findViewById(android.R.id.content);
        int[] at = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertEquals(at[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(at[1] * step, content.getTranslationY(), 0.01f);

        after(BurnInGuard.SHIFT_EVERY_MS);
        int[] next = BurnInGuard.placeAt(SystemClock.uptimeMillis());
        assertTrue("a different place", next[0] != at[0] || next[1] != at[1]);
        assertEquals(next[0] * step, content.getTranslationX(), 0.01f);
        assertEquals(next[1] * step, content.getTranslationY(), 0.01f);
        assertDimmed("two minutes untouched");

        BurnInGuard.paused(activity);
        assertEquals("leaving the front puts it back", 0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertUp("leaving the front");
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    @Test public void theShiftKeepsATranslationSomethingElseWrote() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        View content = activity.findViewById(android.R.id.content);
        BurnInGuard.resumed(activity);
        content.setTranslationY(40f);
        BurnInGuard.paused(activity);
        assertEquals("not HushGram's to put back", 40f, content.getTranslationY(), 0f);
    }

    @Test public void pauseHushGramPutsEverythingBack() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM_AND_SHIFT);
        BurnInGuard.resumed(activity);
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertDimmed("idle");

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        focusBack();
        assertUp("paused");
        View content = activity.findViewById(android.R.id.content);
        assertEquals(0f, content.getTranslationX(), 0f);
        assertEquals(0f, content.getTranslationY(), 0f);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        after(BurnInGuard.SHIFT_EVERY_MS);
        assertUp("still paused");
    }

    @Test public void turningItOnInTheSettingsIsSeenWhenTheWindowGetsFocusBack() {
        BurnInGuard.resumed(activity);
        assertFalse(BurnInGuard.watchingLayoutsForTests());
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        focusBack();
        assertTrue(BurnInGuard.watchingLayoutsForTests());
        assertNotSame("touches are watched now", activity, activity.getWindow().getCallback());
        after(IDLE + 100);
        assertDimmed("on after the settings closed");

        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        focusBack();
        assertUp("off again");
        assertFalse(BurnInGuard.watchingLayoutsForTests());
    }

    /**
     * Turned off in the settings, the focus coming back still asks for a pass, and a layout before
     * that pass runs (the settings screen closing brings one) doesn't put it off. Then the watcher
     * stays on the window, but a finger no longer asks for a pass.
     */
    @Test public void aTouchWithTheGuardOffAsksForNothing() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        observer.dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(BurnInGuard.watchingLayoutsForTests());

        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        observer.dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a layout put the focus pass off", BurnInGuard.watchingLayoutsForTests());
        assertTrue(Shadows.shadowOf(Looper.getMainLooper()).isIdle());

        // Not tap(), which runs what the main thread has due.
        send(MotionEvent.ACTION_DOWN);
        send(MotionEvent.ACTION_UP);
        assertTrue("no pass posted for a lift", Shadows.shadowOf(Looper.getMainLooper()).isIdle());
        assertEquals(-1L, BurnInGuard.nextPassAtForTests());
    }

    /** A finger event at [x, y] in the window, delivered as {@link #send} delivers one. */
    private void sendAt(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private float[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new float[]{at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }

    private void tapOn(View view) {
        float[] at = middleOf(view);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_UP, at[0], at[1]);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * The reel laid out as 451 has it (#162): the viewer is the reels' pager, and a layer over the
     * overlay holds the author and caption at the bottom left and the button column at the right,
     * with Like, Save and HushGram's Download in it, each taking taps. The middle of the screen is
     * the reel itself.
     */
    private void layOutTheReelsButtons() {
        BurnInGuard.idForTests(BurnInGuard.REEL_ID, VIEWER);
        BurnInGuard.idForTests("clips_media_info_component", INFO);
        BurnInGuard.idForTests("save_button", SAVE);
        FrameLayout layer = new FrameLayout(activity);
        FrameLayout info = new FrameLayout(activity);
        info.setId(INFO);
        caption = new View(activity);
        caption.setOnClickListener(view -> { });
        info.addView(caption, new FrameLayout.LayoutParams(100, 40));
        FrameLayout.LayoutParams infoAt = new FrameLayout.LayoutParams(100, 40);
        infoAt.topMargin = H - 60;
        layer.addView(info, infoAt);
        FrameLayout column = new FrameLayout(activity);
        column.setId(UFI);
        like = button(column, 0);
        save = button(column, 40);
        save.setId(SAVE);
        download = button(column, 80);
        download.setContentDescription("Download");
        FrameLayout.LayoutParams columnAt = new FrameLayout.LayoutParams(40, 120);
        columnAt.leftMargin = W - 40;
        columnAt.topMargin = H / 2 + 40;
        layer.addView(column, columnAt);
        viewer.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
    }

    private View button(FrameLayout column, int top) {
        View button = new View(activity);
        button.setOnClickListener(view -> { });
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(40, 40);
        at.topMargin = top;
        column.addView(button, at);
        return button;
    }

    @Test public void aTapOnTheReelOrOnSaveOrDownloadLeavesThemDimmedAndATapOnAButtonBringsThemBack() {
        layOutTheReelsButtons();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");

        tap();
        assertDimmed("a tap on the reel to pause it");
        tap();
        tap();
        assertDimmed("a double tap on the reel to like it");
        tapOn(save);
        assertDimmed("Save");
        tapOn(download);
        assertDimmed("HushGram's Download");

        tapOn(like);
        assertUp("Like");
        after(IDLE + 100);
        assertDimmed("idle again");
        tapOn(caption);
        assertUp("the caption");
    }

    @Test public void aTapOnTheReelDoesntStartTheWaitOver() {
        layOutTheReelsButtons();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE - 1_000);
        tap();
        assertUp("still waiting");
        after(1_100);
        assertDimmed("the wait the tap didn't start over ran out");
    }

    /** A swipe to the next reel counts from the moment it moves farther than a tap does. */
    @Test public void aSwipeOnTheReelBringsThemBack() {
        layOutTheReelsButtons();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        float[] at = middleOf(root);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] + 1);
        assertDimmed("a finger that hasn't moved past a tap");
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] - 150);
        assertUp("a swipe");
        after(IDLE * 2);
        assertUp("a held swipe is a touch");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1] - 150);
        after(IDLE - 100);
        assertUp("the wait starts at the lift");
        after(200);
        assertDimmed("the wait after the swipe ran out");
    }

    /**
     * Stay dim when I tap (#162, the 0.0.13 video): Like, the caption, a swipe to the next reel and a
     * sheet closing all leave the controls dimmed, and no tap starts the wait over. Leaving Reels still
     * brings them back, and with the switch off a tap on Like does again.
     */
    @Test public void stayDimKeepsThemDimmedWhateverYouTapOrSwipe() {
        layOutTheReelsButtons();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_STAY_DIM.save(true);
        BurnInGuard.resumed(activity);
        after(IDLE - 1_000);
        tapOn(like);
        assertUp("still waiting");
        after(1_100);
        assertDimmed("the wait a tap on Like didn't start over ran out");

        tapOn(like);
        assertDimmed("Like");
        tapOn(caption);
        assertDimmed("the caption");
        float[] at = middleOf(root);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        sendAt(MotionEvent.ACTION_MOVE, at[0], at[1] - 150);
        assertDimmed("a swipe to the next reel");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1] - 150);
        after(IDLE * 2);
        assertDimmed("after the swipe");
        focusBack();
        assertDimmed("the comments sheet closing");

        Settings.BURN_IN_STAY_DIM.save(false);
        tapOn(like);
        assertUp("switch off, Like brings them back");
        after(IDLE + 100);
        assertDimmed("idle again");
        Settings.BURN_IN_STAY_DIM.save(true);

        root.removeView(viewer);
        reelsTab.setSelected(false);
        homeTab.setSelected(true);
        relayout();
        assertEquals("leaving Reels brings the tab bar back", 1f, bar.getAlpha(), 0f);
    }

    /** While dimmed, the next reel's controls are dimmed as the layout that brings them in ends, before they're drawn (#162). */
    @Test public void aReelScrolledToWhileDimmedIsDimmedBeforeItsFirstFrame() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");

        FrameLayout next = new FrameLayout(activity);
        next.setId(OVERLAY);
        viewer.addView(next, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("the next reel's overlay, before any pass", BurnInGuard.IDLE_LEVEL, next.getAlpha(), 0f);

        tap();
        assertEquals("a touch brings it back", 1f, next.getAlpha(), 0f);
        next.setAlpha(1f);
        viewer.removeView(next);
        viewer.addView(next);
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("not dimmed, a layout dims nothing", 1f, next.getAlpha(), 0f);
    }

    @Test public void atOnceDimsThemAsSoonAsTheFingerLifts() {
        layOutTheReelsButtons();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_WAIT.save(BurnInWait.AT_ONCE);
        BurnInGuard.resumed(activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertDimmed("at once");
        float[] at = middleOf(like);
        sendAt(MotionEvent.ACTION_DOWN, at[0], at[1]);
        assertUp("a finger on Like");
        after(IDLE);
        assertUp("held");
        sendAt(MotionEvent.ACTION_UP, at[0], at[1]);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertDimmed("lifted");
    }

    /**
     * 451 puts the button column's alpha back by itself after a tap that pauses or mutes the reel,
     * with no layout (#162, S22 2026-10-16): within a second it's dimmed again, from Instagram's alpha.
     */
    @Test public void whatInstagramBringsBackWhileDimmedIsDimmedAgain() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");
        overlay.setAlpha(1f);
        after(BurnInGuard.KEEP_MS + 10);
        assertDimmed("a second later");
        tap();
        assertUp("a touch still brings it back to Instagram's own");
    }

    /**
     * The video in #162 (0.0.12): each tap that paused or played the reel flashed the buttons bright
     * for about a second. Dimmed again before the next frame is drawn, Instagram's write never shows.
     */
    @Test public void whatInstagramBringsBackIsDimmedBeforeTheNextFrame() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertDimmed("idle");
        overlay.setAlpha(1f);
        assertFalse("the frame is still drawn",
                activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw());
        assertDimmed("the frame Instagram's alpha would show in");
        tap();
        assertUp("a touch still brings it back to Instagram's own");
        Settings.BURN_IN_GUARD.save(BurnInMode.OFF);
        BurnInGuard.pass();
        overlay.setAlpha(0.6f);
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("off, a frame leaves Instagram's alpha alone", 0.6f, overlay.getAlpha(), 0f);
    }

    @Test public void tenSecondsWaitsTenSeconds() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        Settings.BURN_IN_WAIT.save(BurnInWait.TEN_SECONDS);
        BurnInGuard.resumed(activity);
        after(BurnInWait.TEN_SECONDS.millis - 100);
        assertUp("before ten seconds");
        after(200);
        assertDimmed("after ten seconds");
    }

    @Test public void theWatcherPassesTouchesOnToTheActivity() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        int[] seen = new int[1];
        root.setOnTouchListener((view, event) -> {
            seen[0]++;
            return true;
        });
        BurnInGuard.resumed(activity);
        tap();
        assertEquals("the down and the up both reached the screen", 2, seen[0]);
    }
}
