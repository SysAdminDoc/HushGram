/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushgram.extension.instagram.media.TapClock;
import app.hushgram.extension.instagram.media.TapToPlay;
import app.hushgram.extension.instagram.media.TapToPlayForTests;
import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Tap to play's Explore choices read the screen the last tap landed on (#762): Explore's tab
 * selected in a shown tab bar, with no reel filling the window. A start off the main thread gets
 * the last answer, and a fresh read is posted for the next one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class TapToPlayExploreScreenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    // The ids ScrollBreakTest gives the same names, so the two never disagree in one run.
    private static final int VIEWER = 7301, BAR = 7302, REELS_TAB = 7303, HOME_TAB = 7304, SEARCH_TAB = 7305;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private View homeTab, searchTab, viewer;

    @Before public void prepare() {
        SystemClock.sleep(60_000);
        TapToPlayForTests.forget();
        HookStatus.clear();
        Settings.TAP_TO_PLAY.save(true);
        Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.ONLY_EXPLORE);
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        FullScreenBars.idForTests(FullScreenBars.SEARCH_TAB, SEARCH_TAB);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        LinearLayout bar = new LinearLayout(activity);
        bar.setId(BAR);
        homeTab = tab(bar, HOME_TAB);
        tab(bar, REELS_TAB);
        searchTab = tab(bar, SEARCH_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50, Gravity.BOTTOM));
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        viewer.setVisibility(View.GONE);
        root.addView(viewer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        select(homeTab);
    }

    @After public void close() {
        controller.close();
        Settings.TAP_TO_PLAY.resetToDefault();
        Settings.TAP_TO_PLAY_SCOPE.resetToDefault();
        TapToPlayForTests.forget();
        HookStatus.clear();
    }

    private View tab(LinearLayout bar, int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void select(View tab) {
        ViewGroup bar = (ViewGroup) tab.getParent();
        for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setSelected(bar.getChildAt(i) == tab);
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    /** A tap on the window, as IgFragmentActivity hands it over, then long enough for its own start to be over. */
    private void tapThenWait() {
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 20, 20, 0);
        MotionEvent up = MotionEvent.obtain(at, at + 80, MotionEvent.ACTION_UP, 20, 20, 0);
        TapClock.touch(activity, down);
        TapClock.touch(activity, up);
        down.recycle();
        up.recycle();
        SystemClock.sleep(5_000);
    }

    @Test
    public void aStartWhileExploresTabShowsWaitsAndHomesPlays() {
        tapThenWait();
        assertTrue("Home's tab: a feed video plays", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertTrue("and Instagram's own autoplay answer", TapToPlay.autoplayAllowed(true));

        select(searchTab);
        assertFalse("Explore's tab: a video waits", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertFalse("and Explore draws its play button", TapToPlay.autoplayAllowed(true));

        viewer.setVisibility(View.VISIBLE);
        select(searchTab);
        assertTrue("a reel filling the window over Explore isn't Explore", TapToPlay.allowStart(new Object(), "autoplay", 0));
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.TAP_TO_PLAY));
    }

    @Test
    public void noTapYetMeansNoWindowAndNotExplore() {
        select(searchTab);
        assertTrue("nothing tapped, so no window to read", TapToPlay.allowStart(new Object(), "autoplay", 0));
    }

    @Test
    public void aStartOffTheMainThreadGetsTheLastAnswerAndAsksForANewOne() throws InterruptedException {
        tapThenWait();
        select(searchTab);
        assertTrue("before any read, off the main thread it isn't Explore", startOffMain());
        ShadowLooper.idleMainLooper();
        assertFalse("the read it posted saw Explore", startOffMain());

        select(homeTab);
        assertFalse("still the last answer", startOffMain());
        ShadowLooper.idleMainLooper();
        assertTrue("and the next read saw Home", startOffMain());
    }

    private static boolean startOffMain() throws InterruptedException {
        AtomicBoolean answer = new AtomicBoolean();
        Thread player = new Thread(() -> answer.set(TapToPlay.allowStart(new Object(), "autoplay", 0)));
        player.start();
        player.join(5_000);
        assertFalse("the start came back", player.isAlive());
        return answer.get();
    }
}
