/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** A swipe up past a chat's last reel goes back to the chat (#390). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w1080dp-h1920dp")
public class ChatReelsExitTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The Reels viewer's activity, counting each Back it's handed and every touch that reaches it. */
    public static class Viewer extends Activity {
        int backs;
        int touches;

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            touches++;
            return super.dispatchTouchEvent(event);
        }

        @SuppressWarnings("deprecation")
        @Override public void onBackPressed() {
            backs++;
        }
    }

    /** Stand-ins for Instagram's viewer sources, named as its enum names them. */
    private enum Source { DIRECT, CLIPS_TAB }

    private ActivityController<Viewer> controller;
    private Viewer activity;
    private FrameLayout root;
    /** The ViewPager2 stand-in, which holds its list as a ViewPager2 does. */
    private FrameLayout pager;
    private RecyclerView list;
    private long downTime;
    private long clock;

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_AFTER_CHAT_REELS.save(true);
        HookStatus.clear();
        ChatReelsExit.resetForTests();
        controller = Robolectric.buildActivity(Viewer.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        pager = new FrameLayout(activity);
        list = new RecyclerView(activity);
        list.scrollState = ChatReelsExit.DRAGGING;
        pager.addView(list, new FrameLayout.LayoutParams(1080, 1920));
        root.addView(pager, new FrameLayout.LayoutParams(1080, 1920));
        clock = SystemClock.uptimeMillis();
    }

    @After
    public void restore() {
        Settings.STOP_AFTER_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ChatReelsExit.resetForTests();
        controller.close();
    }

    private void show() {
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Sends a touch at [x], [y] on the screen through the window's callback, the way the system does. */
    private void dispatch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime = clock;
        MotionEvent event = MotionEvent.obtain(downTime, clock, action, x, y, 0);
        clock += 16;
        try {
            activity.getWindow().getCallback().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** A finger from [fromY] up to [toY], drifting [dx] sideways, then the posted Back if any. */
    private void swipe(float fromY, float toY, float dx) {
        dispatch(MotionEvent.ACTION_DOWN, 540, fromY);
        dispatch(MotionEvent.ACTION_MOVE, 540 + dx / 2, (fromY + toY) / 2);
        dispatch(MotionEvent.ACTION_UP, 540 + dx, toY);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void aSwipeUpPastAChatsLastReelGoesBack() {
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        swipe(1600, 1000, 0);
        assertEquals("Back", 1, activity.backs);
        assertEquals("Instagram had every touch first", 3, activity.touches);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("swiped past a chat's last reel"));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** A pager set up before its window shows is watched once it's attached, as a viewer's onViewCreated runs first. */
    @Test
    public void aPagerSetUpBeforeItShowsIsWatchedOnceItDoes() {
        Window.Callback before = activity.getWindow().getCallback();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        assertSame("not wrapped before it shows", before, activity.getWindow().getCallback());
        show();
        swipe(1600, 1000, 0);
        assertEquals(1, activity.backs);
    }

    /**
     * A pager Instagram set up again for a later viewer, carrying a context that isn't the viewer's
     * activity, still leaves: the window comes from the views it sits in (#390, the second viewer).
     */
    @Test
    public void aKeptPagerWithAnotherContextStillLeaves() {
        root.removeView(pager);
        pager = new FrameLayout(activity.getApplicationContext());
        list = new RecyclerView(activity.getApplicationContext());
        list.scrollState = ChatReelsExit.DRAGGING;
        pager.addView(list, new FrameLayout.LayoutParams(1080, 1920));
        root.addView(pager, new FrameLayout.LayoutParams(1080, 1920));
        ChatReelsExit.viewer(pager, Source.DIRECT);
        show();
        swipe(1600, 1000, 0);
        assertEquals("Back", 1, activity.backs);
    }

    /** One watcher per window, however many pagers, which another wrapper looks through to the activity. */
    @Test
    public void theWindowIsWrappedOnce() {
        show();
        Window window = activity.getWindow();
        Window.Callback before = window.getCallback();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        Window.Callback wrapped = window.getCallback();
        FrameLayout second = new FrameLayout(activity);
        root.addView(second);
        ChatReelsExit.viewer(second, Source.DIRECT);
        assertSame("wrapped once", wrapped, window.getCallback());
        assertSame(before, ChatReelsExit.innerOf(wrapped));
        assertSame(before, ChatReelsExit.innerOf(before));
        assertEquals("other calls still reach the activity", before.onSearchRequested(), wrapped.onSearchRequested());
    }

    /** With more reels below, the swipe is Instagram's page turn. */
    @Test
    public void moreReelsBelowStayInstagrams() {
        list.slack = 100;
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        swipe(1600, 1000, 0);
        assertEquals(0, activity.backs);
    }

    /** A short or sideways swipe, a list that isn't following a finger or two fingers stay where they are. */
    @Test
    public void shortSidewaysUndraggedAndTwoFingerSwipesStay() {
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        swipe(1600, 1600 - ChatReelsExit.EXIT_SHARE * 1920 + 20, 0);
        assertEquals("short", 0, activity.backs);
        swipe(1600, 1000, 400);
        assertEquals("sideways", 0, activity.backs);
        swipe(1600, 1400, 0);
        assertEquals("a drag down isn't up", 0, activity.backs);

        list.scrollState = 0;
        swipe(1600, 1000, 0);
        assertEquals("not dragging", 0, activity.backs);
        list.scrollState = ChatReelsExit.DRAGGING;

        dispatch(MotionEvent.ACTION_DOWN, 540, 1600);
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 540, 1500);
        dispatch(MotionEvent.ACTION_UP, 540, 1000);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("two fingers", 0, activity.backs);

        swipe(1600, 1000, 0);
        assertEquals("a real one still leaves", 1, activity.backs);
    }

    /** Off, paused, or a viewer that isn't a chat's: no swipe leaves. */
    @Test
    public void offPausedAndOtherViewersStay() {
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        Settings.STOP_AFTER_CHAT_REELS.save(false);
        swipe(1600, 1000, 0);
        assertEquals("off", 0, activity.backs);
        Settings.STOP_AFTER_CHAT_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        swipe(1600, 1000, 0);
        assertEquals("paused", 0, activity.backs);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        ChatReelsExit.viewer(pager, Source.CLIPS_TAB);
        swipe(1600, 1000, 0);
        assertEquals("the same pager reused for the Reels tab", 0, activity.backs);
    }

    /** A viewer that isn't a chat's leaves the window as it was. */
    @Test
    public void anotherViewerIsntWatched() {
        show();
        Window.Callback before = activity.getWindow().getCallback();
        ChatReelsExit.viewer(pager, Source.CLIPS_TAB);
        ChatReelsExit.viewer(pager, null);
        ChatReelsExit.viewer("not a view", Source.DIRECT);
        assertSame(before, activity.getWindow().getCallback());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
    }
}
