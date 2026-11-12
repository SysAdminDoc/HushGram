/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.instagram.clips.intf.ClipsViewerSource;

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
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.clips.viewer.actionbar.ClipsViewerActionBar;
import instagram.features.clips.viewer.feature.actionbar.feature.youralgo.ui.YourAlgoEntryPointView;

/**
 * Hide the top bar on the Reels tab (#14): the bar of the Reels tab's own viewer goes invisible,
 * keeping its place so a tap there reaches the reel, while a reel opened from anywhere else keeps
 * its bar and camera, and no frame or layout listener runs while the switch is off, while HushGram
 * is paused, or once the screen goes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsTopBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR_ID = 7201, CAMERA_ID = 7202;
    private static final int BAR_HEIGHT = 100, CAMERA_SIZE = 50;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View reel;
    private final List<String> touched = new ArrayList<>();
    private int baseFrames, baseLayouts, baseFocus;

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        Settings.HIDE_REELS_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_REELS_YOUR_ALGORITHM.resetToDefault();
        Settings.HIDE_REELS_FRIENDS_TAB.resetToDefault();
        HookStatus.clear();
        ReelsTopBar.idForTests(ReelsTopBar.BAR_ID, BAR_ID);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        reel = new View(activity);
        reel.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched.add("reel");
            return true;
        });
        root.addView(reel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout();
        baseFrames = listeners("mOnPreDrawListeners");
        baseLayouts = listeners("mOnGlobalLayoutListeners");
        baseFocus = listeners("mOnWindowFocusListeners");
    }

    @After public void close() {
        ReelsTopBar.forget(activity);
        controller.pause().stop().destroy();
        Settings.HIDE_REELS_TOP_BAR.resetToDefault();
        Settings.HIDE_REELS_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_REELS_YOUR_ALGORITHM.resetToDefault();
        Settings.HIDE_REELS_FRIENDS_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A bar like the Reels viewer's, its camera button in the corner, laid over the reel. */
    private ClipsViewerActionBar bar(ClipsViewerSource source) {
        ClipsViewerActionBar bar = new ClipsViewerActionBar(activity);
        bar.setId(BAR_ID);
        if (source != null) bar.bind(source);
        View camera = new View(activity);
        camera.setId(CAMERA_ID);
        camera.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched.add("camera");
            return true;
        });
        bar.addView(camera, new RelativeLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        return bar;
    }

    private ClipsViewerActionBar show(ClipsViewerSource source) {
        ClipsViewerActionBar bar = bar(source);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        layout();
        return bar;
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private ViewTreeObserver observer() {
        return activity.getWindow().getDecorView().getViewTreeObserver();
    }

    /** A frame of the window, as far as its pre-draw listeners can tell. */
    private void frame() {
        observer().dispatchOnPreDraw();
    }

    /** A layout pass of the window, as far as its layout listeners can tell. */
    private void laidOut() {
        layout();
        observer().dispatchOnGlobalLayout();
    }

    /** The window getting focus back, as when the settings dialog over it closes. */
    private void focusBack() {
        ReflectionHelpers.callInstanceMethod(observer(), "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }

    private void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** How many listeners of a kind the window's observer holds, from its own list. */
    private int listeners(String field) {
        Object list = ReflectionHelpers.getField(observer(), field);
        if (list == null) return 0;
        Integer size = ReflectionHelpers.callInstanceMethod(list, "size");
        return size;
    }

    private void assertWatching(String why, boolean watching) {
        assertEquals(why + ": frame listeners", baseFrames + (watching ? 1 : 0), listeners("mOnPreDrawListeners"));
        assertEquals(why + ": layout listeners", baseLayouts + (watching ? 1 : 0), listeners("mOnGlobalLayoutListeners"));
    }

    private ReelsTopBar.Session track() {
        ReelsTopBar.track(activity);
        ReelsTopBar.Session session = ReelsTopBar.sessionFor(activity);
        assertNotNull(session);
        return session;
    }

    private String tapTopLeft() {
        touched.clear();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, CAMERA_SIZE / 2f, CAMERA_SIZE / 2f, 0);
        root.dispatchTouchEvent(down);
        down.recycle();
        return touched.isEmpty() ? "nothing" : touched.get(0);
    }

    private static boolean counted(String count, int times) {
        return HookStatus.report().toString().contains(count + " " + times);
    }

    @Test public void offIsStockAndWatchesNoFrames() {
        assertFalse("starts off", Settings.HIDE_REELS_TOP_BAR.get());
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        frame();
        laidOut();
        frame();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.findViewById(CAMERA_ID).isShown());
        assertEquals("camera", tapTopLeft());
        assertFalse(session.isWatching());
        assertWatching("off", false);
        assertEquals("off, only the focus listener that hears the switch turn on", baseFocus + 1, listeners("mOnWindowFocusListeners"));
        assertEquals("never searched", 0, session.looks);
        assertEquals(List.of(), HookStatus.report());
    }

    @Test public void onHidesTheReelsTabBarAndItsCameraAndKeepsItsPlace() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        int top = bar.getTop(), height = bar.getHeight(), width = bar.getWidth();
        int reelTop = reel.getTop(), reelHeight = reel.getHeight();
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        assertEquals("invisible, not gone", View.INVISIBLE, bar.getVisibility());
        assertFalse("the camera button isn't shown", bar.findViewById(CAMERA_ID).isShown());
        layout();
        assertEquals("the bar keeps its place", top, bar.getTop());
        assertEquals(height, bar.getHeight());
        assertEquals(width, bar.getWidth());
        assertEquals("the reel isn't laid out again", reelTop, reel.getTop());
        assertEquals(reelHeight, reel.getHeight());
        assertTrue(session.isWatching());
        assertWatching("on", true);
        assertEquals(1, session.hiddenCount());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_HID, 1));
    }

    @Test public void aTapWhereTheBarWasReachesTheReel() {
        show(ClipsViewerSource.CLIPS_TAB);
        assertEquals("with the bar, the camera button takes the tap", "camera", tapTopLeft());
        Settings.HIDE_REELS_TOP_BAR.save(true);
        track();
        assertEquals("the reel under the hidden bar takes it", "reel", tapTopLeft());
    }

    @Test public void theHomecomingReelsTabCountsToo() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB_HOMECOMING);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        track();
        assertEquals(View.INVISIBLE, bar.getVisibility());
    }

    @Test public void theCameraGoesOnlyOnTheReelsTab() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerSource[] elsewhere = {ClipsViewerSource.PROFILE_CLIPS, ClipsViewerSource.FEED_TIMELINE,
                ClipsViewerSource.DIRECT_THREAD, ClipsViewerSource.CLIPS_FRIEND_LANE};
        List<ClipsViewerActionBar> kept = new ArrayList<>();
        for (ClipsViewerSource source : elsewhere) kept.add(show(source));
        ClipsViewerActionBar mixed = show(null);
        mixed.A01 = new ClipsViewerActionBar.Config(ClipsViewerSource.CLIPS_TAB);
        mixed.A02 = new ClipsViewerActionBar.State(ClipsViewerSource.PROFILE_CLIPS);
        kept.add(mixed);
        ClipsViewerActionBar tab = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        for (int i = 0; i < kept.size(); i++) {
            ClipsViewerActionBar bar = kept.get(i);
            assertEquals("bar " + i + " keeps showing", View.VISIBLE, bar.getVisibility());
            assertTrue("bar " + i + " keeps its camera", bar.findViewById(CAMERA_ID).isShown());
        }
        assertEquals(View.INVISIBLE, tab.getVisibility());
        assertFalse(tab.findViewById(CAMERA_ID).isShown());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void pausedIsStockWithNoListener() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.track(activity);
        frame();
        assertNull("paused, the window isn't watched at all", ReelsTopBar.sessionFor(activity));
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertWatching("paused", false);
        assertEquals("paused, no focus listener either", baseFocus, listeners("mOnWindowFocusListeners"));
        assertEquals(List.of(), HookStatus.report());
    }

    @Test public void aResumeWhilePausedPutsTheBarBackAndStopsWatching() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        ReelsTopBar.release(activity);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelsTopBar.track(activity);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertWatching("paused", false);
        assertEquals(baseFocus, listeners("mOnWindowFocusListeners"));
    }

    @Test public void withNoBarNothingHidesAndNothingThrows() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        laidOut();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertTrue("searched", session.looks >= 1);
        assertEquals(0, session.hiddenCount());
        assertEquals(View.VISIBLE, reel.getVisibility());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
        assertFalse(counted(ReelsTopBar.COUNT_HID, 1));
    }

    @Test public void aBarNotBoundYetIsHiddenOnceItIs() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(null);
        ReelsTopBar.Session session = track();
        assertEquals("its viewer isn't known yet, so it's left alone", View.VISIBLE, bar.getVisibility());
        bar.bind(ClipsViewerSource.CLIPS_TAB);
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        assertEquals("bound with no layout, it's still found", View.INVISIBLE, bar.getVisibility());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void aBarThatNeverBindsStopsBeingLookedFor() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(null);
        ReelsTopBar.Session session = track();
        for (int i = 0; i < ReelsTopBar.UNKNOWN_TRIES + 5; i++) {
            idle(ReelsTopBar.LOOK_MS * 2);
            frame();
        }
        idle(ReelsTopBar.LOOK_MS * 2);
        int looks = session.looks;
        assertTrue("a few more searches, not one a frame forever: " + looks, looks <= ReelsTopBar.UNKNOWN_TRIES + 2);
        for (int i = 0; i < 5; i++) {
            idle(ReelsTopBar.LOOK_MS * 2);
            frame();
        }
        assertEquals("no more searches without a layout", looks, session.looks);
        assertEquals(View.VISIBLE, bar.getVisibility());
    }

    @Test public void aWrapperWearingTheBarsIdIsSearchedForTheBarInside() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setId(BAR_ID);
        ClipsViewerActionBar bar = bar(ClipsViewerSource.CLIPS_TAB);
        bar.setId(View.NO_ID);
        wrapper.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        root.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        layout();
        track();
        assertEquals("the wrapper holds no source and stays", View.VISIBLE, wrapper.getVisibility());
        assertEquals(View.INVISIBLE, bar.getVisibility());
    }

    @Test public void aBarInstagramShowsAgainIsHiddenBeforeTheNextFrame() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        bar.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.INVISIBLE, bar.getVisibility());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_HID_AGAIN, 1));
        bar.setVisibility(View.GONE);
        frame();
        assertEquals("Instagram's own hide stays", View.GONE, bar.getVisibility());
    }

    @Test public void turningTheSwitchOffPutsTheBarBackAndStopsTheListenersAtTheNextFrame() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        assertWatching("on", true);
        Settings.HIDE_REELS_TOP_BAR.save(false);
        frame();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.findViewById(CAMERA_ID).isShown());
        assertFalse(session.isWatching());
        assertFalse(session.lookQueued());
        assertWatching("off", false);
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_PUT_BACK, 1));
        int looks = session.looks;
        laidOut();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals("off, no more searches", looks, session.looks);
        assertEquals(0, session.hiddenCount());
    }

    @Test public void aBarInstagramHidItselfIsLeftHiddenWhenTheSwitchGoesOff() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        track();
        bar.setVisibility(View.GONE);
        Settings.HIDE_REELS_TOP_BAR.save(false);
        frame();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test public void turningTheSwitchOnIsSeenWhenTheSettingsDialogCloses() {
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        assertWatching("off", false);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        focusBack();
        assertTrue(session.isWatching());
        assertWatching("on", true);
        assertEquals(View.INVISIBLE, bar.getVisibility());
        focusBack();
        assertWatching("listeners added once", true);
    }

    @Test public void aPausedActivityStopsListeningAndAGoneOneIsForgotten() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ClipsViewerActionBar bar = show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        ReelsTopBar.release(activity);
        assertWatching("paused activity", false);
        assertEquals("paused activity", baseFocus, listeners("mOnWindowFocusListeners"));
        assertFalse(session.lookQueued());
        assertEquals("no flash as the screen goes", View.INVISIBLE, bar.getVisibility());
        int looks = session.looks;
        laidOut();
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        assertEquals(looks, session.looks);

        Settings.HIDE_REELS_TOP_BAR.save(false);
        assertEquals("the same session again", session, track());
        assertEquals("resumed with the switch off, the bar is back", View.VISIBLE, bar.getVisibility());
        assertWatching("resumed off", false);
        assertEquals(baseFocus + 1, listeners("mOnWindowFocusListeners"));

        ReelsTopBar.forget(activity);
        assertNull(ReelsTopBar.sessionFor(activity));
        assertWatching("gone", false);
        assertEquals("gone", baseFocus, listeners("mOnWindowFocusListeners"));
    }

    @Test public void aBarAddedLaterIsFoundAfterTheLayoutThatBringsIt() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        ClipsViewerActionBar bar = bar(ClipsViewerSource.CLIPS_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        laidOut();
        frame();
        if (bar.getVisibility() == View.VISIBLE) {
            assertTrue("a search waits out the throttle", session.lookQueued());
            idle(ReelsTopBar.LOOK_MS);
        }
        assertEquals(View.INVISIBLE, bar.getVisibility());
        assertEquals(1, session.hiddenCount());
    }

    @Test public void framesWithoutALayoutDontSearch() {
        Settings.HIDE_REELS_TOP_BAR.save(true);
        show(ClipsViewerSource.CLIPS_TAB);
        ReelsTopBar.Session session = track();
        idle(ReelsTopBar.LOOK_MS * 2);
        int looks = session.looks;
        for (int i = 0; i < 10; i++) frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals(looks, session.looks);
        assertFalse(session.lookQueued());
    }

    @Test public void theSwitchStartsOffAndIsInTheCleanUpReelsFamily() {
        assertFalse(Settings.HIDE_REELS_TOP_BAR.defaultValue);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REELS_TOP_BAR));
    }

    /** Shaped like the kind Instagram tags each icon button of the bar with. */
    enum Kind { BACK, CAMERA, SEARCH }

    /** One row of a bar's buttons: Create, Your algorithm and Search, each a button wide, left to right. */
    private static final class Row {
        final ClipsViewerActionBar bar;
        final View create, algorithm, search;

        Row(ClipsViewerActionBar bar, View create, View algorithm, View search) {
            this.bar = bar;
            this.create = create;
            this.algorithm = algorithm;
            this.search = search;
        }
    }

    /** A bar like 451's: its buttons in a row, as Instagram adds them, each icon button tagged with its kind. */
    private Row showRow(ClipsViewerSource source) {
        ClipsViewerActionBar bar = new ClipsViewerActionBar(activity);
        bar.setId(BAR_ID);
        bar.bind(source);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View create = new View(activity);
        create.setTag(Kind.CAMERA);
        View algorithm = new YourAlgoEntryPointView(activity);
        View search = new View(activity);
        search.setTag(Kind.SEARCH);
        for (View button : new View[] {create, algorithm, search}) {
            row.addView(button, new LinearLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        }
        bar.addView(row, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, CAMERA_SIZE));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        layout();
        return new Row(bar, create, algorithm, search);
    }

    @Test public void createGoesFromEveryReelsBarAndItsRowClosesUp() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Row profile = showRow(ClipsViewerSource.PROFILE_CLIPS);
        assertEquals("Search starts two buttons in", 2 * CAMERA_SIZE, tab.search.getLeft());
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        ReelsTopBar.Session session = track();
        layout();
        for (Row row : new Row[] {tab, profile}) {
            assertEquals("gone, not just invisible", View.GONE, row.create.getVisibility());
            assertEquals("the bar stays", View.VISIBLE, row.bar.getVisibility());
            assertTrue("Your algorithm stays", row.algorithm.isShown());
            assertTrue("Search stays", row.search.isShown());
            assertEquals("the gap closes", 0, row.algorithm.getLeft());
            assertEquals(CAMERA_SIZE, row.search.getLeft());
        }
        assertEquals(2, session.hiddenCount());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_CREATE_HID, 2));
    }

    @Test public void yourAlgorithmGoesAloneAndTheRestStays() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Settings.HIDE_REELS_YOUR_ALGORITHM.save(true);
        ReelsTopBar.Session session = track();
        layout();
        assertEquals(View.GONE, tab.algorithm.getVisibility());
        assertTrue("Create stays", tab.create.isShown());
        assertTrue(tab.search.isShown());
        assertEquals(View.VISIBLE, tab.bar.getVisibility());
        assertEquals("Search moves up beside Create", CAMERA_SIZE, tab.search.getLeft());
        assertEquals(1, session.hiddenCount());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_YOUR_ALGORITHM_HID, 1));
    }

    @Test public void eachButtonComesBackWhenItsOwnSwitchGoesOffAndAllOnPause() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        Settings.HIDE_REELS_YOUR_ALGORITHM.save(true);
        ReelsTopBar.Session session = track();
        assertEquals(View.GONE, tab.create.getVisibility());
        assertEquals(View.GONE, tab.algorithm.getVisibility());

        Settings.HIDE_REELS_CREATE_BUTTON.save(false);
        focusBack();
        assertEquals("Create is back", View.VISIBLE, tab.create.getVisibility());
        assertEquals("Your algorithm, still on, stays gone", View.GONE, tab.algorithm.getVisibility());
        assertTrue(session.isWatching());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_BUTTON_PUT_BACK, 1));

        ReelsTopBar.release(activity);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelsTopBar.track(activity);
        assertEquals("paused, Your algorithm is back too", View.VISIBLE, tab.algorithm.getVisibility());
        assertEquals(View.VISIBLE, tab.create.getVisibility());
        assertWatching("paused", false);
    }

    @Test public void aButtonInstagramShowsAgainGoesAgainAndOneItHidItselfStays() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        track();
        tab.create.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.GONE, tab.create.getVisibility());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_BUTTON_HID_AGAIN, 1));
        tab.create.setVisibility(View.INVISIBLE);
        Settings.HIDE_REELS_CREATE_BUTTON.save(false);
        frame();
        assertEquals("Instagram's own hide stays", View.INVISIBLE, tab.create.getVisibility());
    }

    @Test public void aBarHiddenForNowLosesItsButtonsBeforeItShowsAgain() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Row profile = showRow(ClipsViewerSource.PROFILE_CLIPS);
        tab.bar.setVisibility(View.INVISIBLE);
        ((View) profile.search.getParent()).setVisibility(View.INVISIBLE);
        profile.algorithm.setVisibility(View.INVISIBLE);
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        Settings.HIDE_REELS_YOUR_ALGORITHM.save(true);
        ReelsTopBar.Session session = track();
        // Shown again with no layout, so no search comes after: they have to be gone already.
        tab.bar.setVisibility(View.VISIBLE);
        ((View) profile.search.getParent()).setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.GONE, tab.create.getVisibility());
        assertEquals(View.GONE, tab.algorithm.getVisibility());
        assertEquals(View.GONE, profile.create.getVisibility());
        assertEquals("Instagram's own hide stays its own", View.INVISIBLE, profile.algorithm.getVisibility());
        assertTrue(tab.search.isShown());
        assertTrue(profile.search.isShown());
        assertEquals(3, session.hiddenCount());
    }

    @Test public void anInvisibleBarIsLeftForTheWholeBarSwitch() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        tab.bar.setVisibility(View.INVISIBLE);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        ReelsTopBar.Session session = track();
        assertEquals(0, session.hiddenCount());
        Settings.HIDE_REELS_TOP_BAR.save(false);
        frame();
        assertEquals("Instagram's own hide stays", View.INVISIBLE, tab.bar.getVisibility());
    }

    @Test public void aButtonAddedLaterIsFoundAfterTheLayoutThatBringsIt() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        ReelsTopBar.Session session = track();
        View rebuilt = new View(activity);
        rebuilt.setTag(Kind.CAMERA);
        ((ViewGroup) tab.search.getParent()).addView(rebuilt, 0, new LinearLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        laidOut();
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals(View.GONE, rebuilt.getVisibility());
        assertEquals(2, session.hiddenCount());
    }

    @Test public void withTheWholeBarOnTheTabsBarHidesWholeAndOtherViewersLoseOnlyCreate() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Row profile = showRow(ClipsViewerSource.PROFILE_CLIPS);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        track();
        assertEquals(View.INVISIBLE, tab.bar.getVisibility());
        assertEquals("the tab's bar keeps its row as it was", View.VISIBLE, tab.create.getVisibility());
        assertEquals(View.VISIBLE, profile.bar.getVisibility());
        assertEquals(View.GONE, profile.create.getVisibility());
        assertTrue(profile.search.isShown());

        Settings.HIDE_REELS_TOP_BAR.save(false);
        focusBack();
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals("the tab's bar is back", View.VISIBLE, tab.bar.getVisibility());
        assertEquals("and loses only Create now", View.GONE, tab.create.getVisibility());
        assertTrue(tab.search.isShown());
    }

    @Test public void aKindOtherThanCreateAndAViewWithNoTagStay() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        View back = new View(activity);
        back.setTag(Kind.BACK);
        View plain = new View(activity);
        ((ViewGroup) tab.search.getParent()).addView(back, new LinearLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        ((ViewGroup) tab.search.getParent()).addView(plain, new LinearLayout.LayoutParams(CAMERA_SIZE, CAMERA_SIZE));
        layout();
        Settings.HIDE_REELS_CREATE_BUTTON.save(true);
        Settings.HIDE_REELS_YOUR_ALGORITHM.save(true);
        track();
        assertTrue(back.isShown());
        assertTrue(plain.isShown());
        assertTrue(tab.search.isShown());
        assertFalse(ReelsTopBar.isCreateButton(back));
        assertFalse(ReelsTopBar.isCreateButton(plain));
        assertTrue(ReelsTopBar.isCreateButton(tab.create));
        assertTrue(ReelsTopBar.isYourAlgorithmButton(tab.algorithm));
        assertFalse(ReelsTopBar.isYourAlgorithmButton(tab.search));
    }

    @Test public void theButtonSwitchesStartOffAndAreInTheCleanUpReelsFamily() {
        assertFalse(Settings.HIDE_REELS_CREATE_BUTTON.defaultValue);
        assertFalse(Settings.HIDE_REELS_YOUR_ALGORITHM.defaultValue);
        assertFalse(Settings.HIDE_REELS_FRIENDS_TAB.defaultValue);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REELS_CREATE_BUTTON));
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REELS_YOUR_ALGORITHM));
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.HIDE_REELS_FRIENDS_TAB));
    }

    /** The Reels and Friends tabs, each a view in a row inside a scroller, as Material's tab strip holds them. */
    private static final class Tabs {
        final ViewGroup strip;
        final View reels, friends;

        Tabs(ViewGroup strip, View reels, View friends) {
            this.strip = strip;
            this.reels = reels;
            this.friends = friends;
        }
    }

    private Tabs tabs(ViewGroup parent, boolean marked) {
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        LinearLayout strip = new LinearLayout(activity);
        View reels = new LinearLayout(activity);
        View friends = new LinearLayout(activity);
        strip.addView(reels, new LinearLayout.LayoutParams(CAMERA_SIZE * 2, CAMERA_SIZE));
        strip.addView(friends, new LinearLayout.LayoutParams(CAMERA_SIZE * 2, CAMERA_SIZE));
        scroller.addView(strip);
        parent.addView(scroller, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, CAMERA_SIZE));
        // As the patch does when Instagram builds the Friends tab and gives it its facepile icon.
        if (marked) ReelsTopBar.friendsTab(friends);
        layout();
        return new Tabs(strip, reels, friends);
    }

    @Test public void theFriendsTabGoesAndReelsHasTheStripToItself() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Tabs tabs = tabs(tab.bar, true);
        assertEquals("both tabs to start", CAMERA_SIZE * 4, tabs.strip.getWidth());
        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        ReelsTopBar.Session session = track();
        layout();
        assertEquals("gone, not just invisible", View.GONE, tabs.friends.getVisibility());
        assertTrue("Reels stays", tabs.reels.isShown());
        assertEquals("no space where Friends was", CAMERA_SIZE * 2, tabs.strip.getWidth());
        assertTrue("the buttons stay", tab.create.isShown());
        assertTrue(tab.algorithm.isShown());
        assertEquals(View.VISIBLE, tab.bar.getVisibility());
        assertEquals(1, session.hiddenCount());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_FRIENDS_SEEN, 1));
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_FRIENDS_HID, 1));
    }

    @Test public void theFriendsTabComesBackWhenItsSwitchGoesOffAndOnPause() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Tabs tabs = tabs(tab.bar, true);
        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        ReelsTopBar.Session session = track();
        assertEquals(View.GONE, tabs.friends.getVisibility());

        Settings.HIDE_REELS_FRIENDS_TAB.save(false);
        focusBack();
        assertEquals("off, Friends is back", View.VISIBLE, tabs.friends.getVisibility());
        assertFalse("and nothing else is on, so nothing watches", session.isWatching());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_BUTTON_PUT_BACK, 1));

        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        focusBack();
        assertEquals("on again, gone again", View.GONE, tabs.friends.getVisibility());

        ReelsTopBar.release(activity);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelsTopBar.track(activity);
        assertEquals("paused, Friends is back", View.VISIBLE, tabs.friends.getVisibility());
        assertWatching("paused", false);
    }

    @Test public void aTabThePatchDidNotHandOverStays() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Tabs tabs = tabs(tab.bar, false);
        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        ReelsTopBar.Session session = track();
        assertTrue(tabs.reels.isShown());
        assertTrue(tabs.friends.isShown());
        assertEquals(0, session.hiddenCount());
        assertFalse(ReelsTopBar.isFriendsTab(tabs.friends));
    }

    @Test public void aFriendsTabOutsideTheBarGoesTooAndInstagramShowingItAgainHidesItAgain() {
        Tabs tabs = tabs(root, true);
        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        track();
        assertEquals(View.GONE, tabs.friends.getVisibility());
        tabs.friends.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.GONE, tabs.friends.getVisibility());
        assertTrue(HookStatus.report().toString(), counted(ReelsTopBar.COUNT_BUTTON_HID_AGAIN, 1));
    }

    @Test public void withTheWholeBarOnTheTabsBarKeepsItsTabsAsTheyWere() {
        Row tab = showRow(ClipsViewerSource.CLIPS_TAB);
        Tabs tabs = tabs(tab.bar, true);
        Settings.HIDE_REELS_TOP_BAR.save(true);
        Settings.HIDE_REELS_FRIENDS_TAB.save(true);
        track();
        assertEquals(View.INVISIBLE, tab.bar.getVisibility());
        assertEquals("the hidden bar's row stays as it was", View.VISIBLE, tabs.friends.getVisibility());

        Settings.HIDE_REELS_TOP_BAR.save(false);
        focusBack();
        frame();
        idle(ReelsTopBar.LOOK_MS * 2);
        frame();
        assertEquals("the bar is back", View.VISIBLE, tab.bar.getVisibility());
        assertEquals("without Friends", View.GONE, tabs.friends.getVisibility());
        assertTrue(tabs.reels.isShown());
    }
}
