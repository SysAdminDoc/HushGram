/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** See-through top bar behind the status bar (#462): Home's feed runs on under the clock and the glass covers it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GlassReachTest {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int STATUS = 75;
    private static final int HEADER = 150;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private FrameLayout screen;
    private FrameLayout panel;
    private FrameLayout source;
    private FrameLayout feed;
    private FrameLayout list;
    private FrameLayout header;
    private List<View> views;

    @Before
    public void prepare() {
        resetSwitches();
        GlassReach.insets = view -> STATUS;
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar);
        Activity activity = controller.setup().get();
        // Instagram's coordinator at the window's top lays the panel holding Home out below the status bar.
        screen = new FrameLayout(activity);
        panel = new FrameLayout(activity);
        source = new FrameLayout(activity);
        feed = new FrameLayout(activity);
        list = new FrameLayout(activity);
        list.addView(new View(activity), new FrameLayout.LayoutParams(MATCH, 400));
        feed.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        header = new FrameLayout(activity);
        header.setBackgroundColor(Color.BLACK);
        source.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
        source.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        panel.addView(source, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(MATCH, MATCH);
        below.topMargin = STATUS;
        screen.addView(panel, below);
        activity.setContentView(screen);
        layout();
        assertEquals("the frame sits at the window's top", 0, windowTop(screen));
        assertEquals("Home starts below the status bar", STATUS, windowTop(source));
        views = Collections.singletonList(header);
    }

    @After
    public void restore() {
        GlassReach.insets = GlassReach::statusBar;
        resetSwitches();
    }

    private static void resetSwitches() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.resetToDefault();
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        screen.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, WIDE, TALL);
    }

    /** A frame: the glass goes on, then Home lays out again with whatever it moved. */
    private void frame() {
        header.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        header.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static int windowTop(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }

    private void glaze() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();
    }

    private void assertLeftAlone() {
        assertEquals(0, ((ViewGroup.MarginLayoutParams) feed.getLayoutParams()).topMargin);
        assertEquals(STATUS, windowTop(feed));
        assertEquals(0, list.getPaddingTop());
        assertTrue(list.getClipToPadding());
        for (ViewGroup group : new ViewGroup[] {source, panel, screen}) assertTrue(group.getClipChildren());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
    }

    @Test
    public void theFeedRunsBehindTheStatusBarAndTheGlassCoversIt() {
        glaze();

        assertTrue(GlassTopBar.showing(header));
        assertEquals(-STATUS, ((ViewGroup.MarginLayoutParams) feed.getLayoutParams()).topMargin);
        assertEquals("the feed starts at the window's top", 0, windowTop(feed));
        assertEquals(TALL, feed.getHeight());
        assertEquals("the first post starts where it did", STATUS, list.getPaddingTop());
        assertFalse(list.getClipToPadding());
        for (ViewGroup group : new ViewGroup[] {source, panel, screen}) assertFalse(group.getClipChildren());
        assertEquals("the header stays below the clock", STATUS, windowTop(header));
        assertArrayEquals("the glass reaches up to the window's top", new int[] {STATUS, 0}, GlassTopBar.reach(header));
    }

    @Test
    public void aHeaderSlidAwayStillCoversTheStatusBar() {
        glaze();

        header.setTranslationY(-40f);
        frame();
        assertArrayEquals(new int[] {STATUS - 40, 0}, GlassTopBar.reach(header));

        header.setTranslationY(-HEADER - 30f);
        frame();
        assertArrayEquals("slid past its own height, it reaches down to the status bar's bottom",
                new int[] {0, 30}, GlassTopBar.reach(header));
    }

    @Test
    public void offPutsHomeBackBelowTheStatusBar() {
        list.setPadding(0, 10, 0, 0);
        glaze();
        assertEquals(10 + STATUS, list.getPaddingTop());

        Settings.SEE_THROUGH_HOME_TOP_BAR.save(false);
        frame();

        assertFalse(GlassTopBar.showing(header));
        assertEquals("the list keeps its own padding", 10, list.getPaddingTop());
        list.setPadding(0, 0, 0, 0);
        assertLeftAlone();
    }

    @Test
    public void pausedPutsHomeBack() {
        glaze();

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();

        assertLeftAlone();
    }

    @Test
    public void leavingTheWindowPutsEverythingBack() {
        glaze();

        panel.removeView(source);

        assertEquals(0, ((ViewGroup.MarginLayoutParams) feed.getLayoutParams()).topMargin);
        assertEquals(0, list.getPaddingTop());
        assertTrue(list.getClipToPadding());
        for (ViewGroup group : new ViewGroup[] {source, panel, screen}) assertTrue(group.getClipChildren());
    }

    @Test
    public void homeNotBelowTheStatusBarIsLeftAlone() {
        GlassReach.insets = view -> 60;
        glaze();
        assertTrue(GlassTopBar.showing(header));
        assertLeftAlone();

        GlassReach.insets = view -> 0;
        frame();
        assertLeftAlone();
    }

    @Test
    public void aChatsHeaderNeverMovesTheFeed() {
        GlassTopBar.glazeChat(header, () -> true);
        frame();

        assertTrue(GlassTopBar.showing(header));
        assertLeftAlone();
    }

    /** By the name it keeps in Instagram. */
    private static class RecyclerView extends FrameLayout {
        RecyclerView(Context context) {
            super(context);
        }
    }

    private static final class MainFeedRecyclerView extends RecyclerView {
        MainFeedRecyclerView(Context context) {
            super(context);
        }
    }

    /**
     * Home as 454 builds it: the sticky list holds a refreshable parent that holds the feed's RecyclerView,
     * and the header sits in a wrapper beside the sticky list. The feed has no posts on the first frame.
     */
    @Test
    public void theListItselfReachesTheTopWhenItHasNoPostsYet() {
        Context context = screen.getContext();
        source.removeAllViews();
        FrameLayout refreshable = new FrameLayout(context);
        FrameLayout feedList = new MainFeedRecyclerView(context);
        refreshable.addView(feedList, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout sticky = new FrameLayout(context);
        sticky.addView(refreshable, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout wrapper = new FrameLayout(context);
        header = new FrameLayout(context);
        wrapper.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        source.addView(sticky, new FrameLayout.LayoutParams(MATCH, MATCH));
        source.addView(wrapper, new FrameLayout.LayoutParams(MATCH, HEADER));
        layout();
        views = Collections.singletonList(header);

        glaze();

        assertEquals("the sticky list starts at the window's top", 0, windowTop(sticky));
        assertEquals("nothing pads the view around the list", 0, refreshable.getPaddingTop());
        assertEquals("the list starts at the window's top", 0, windowTop(feedList));
        assertEquals("the first post starts where it did", STATUS, feedList.getPaddingTop());
        assertFalse(feedList.getClipToPadding());
        assertEquals("the header stays below the clock", STATUS, windowTop(header));
        assertArrayEquals(new int[] {STATUS, 0}, GlassTopBar.reach(header));

        feedList.addView(new View(context), new FrameLayout.LayoutParams(MATCH, 400));
        frame();
        assertEquals("the posts coming in don't pad it again", STATUS, feedList.getPaddingTop());

        Settings.SEE_THROUGH_HOME_TOP_BAR.save(false);
        frame();
        assertEquals(STATUS, windowTop(sticky));
        assertEquals(STATUS, windowTop(feedList));
        assertEquals(0, feedList.getPaddingTop());
        assertTrue(feedList.getClipToPadding());
    }
}
