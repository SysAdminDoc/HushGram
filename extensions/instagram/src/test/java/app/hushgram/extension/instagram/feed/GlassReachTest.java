/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
import org.robolectric.annotation.GraphicsMode;
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
        list = new MainFeedRecyclerView(activity);
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

    private static int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
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
    public void aHeaderThatStillShowsSomethingKeepsItsBand() {
        View logo = logoInHeader();
        glaze();
        assertFalse(GlassTopBar.hollow(header));

        logo.setAlpha(0.3f);
        frame();
        assertFalse("half faded it still shows", GlassTopBar.hollow(header));

        logo.setAlpha(1f);
        logo.setTranslationY(HEADER - 1f);
        frame();
        assertFalse("one row of it is still inside", GlassTopBar.hollow(header));
    }

    @Test
    public void aHeaderWithNoViewsInItKeepsItsBand() {
        glaze();

        assertEquals(0, header.getChildCount());
        assertFalse(GlassTopBar.hollow(header));
    }

    @Test
    public void aHeaderWithNothingShowingIsHollowUntilItShowsSomethingAgain() {
        View logo = logoInHeader();
        glaze();

        logo.setVisibility(View.INVISIBLE);
        frame();
        assertTrue("hidden", GlassTopBar.hollow(header));

        logo.setVisibility(View.VISIBLE);
        frame();
        assertFalse(GlassTopBar.hollow(header));

        logo.setAlpha(0f);
        frame();
        assertTrue("faded out", GlassTopBar.hollow(header));

        logo.setAlpha(1f);
        ((View) logo.getParent()).setTranslationY(-HEADER);
        frame();
        assertTrue("moved out through its parent", GlassTopBar.hollow(header));

        ((View) logo.getParent()).setTranslationY(0f);
        frame();
        assertFalse(GlassTopBar.hollow(header));
    }

    /** A hollow header leaves its own band out and the strip behind the clock in, wherever it has slid to. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aHollowHeaderDrawsOnlyTheStripBehindTheClock() {
        View logo = logoInHeader();
        glaze();

        int[] shown = drawnAcross(0);
        assertNotEquals("the strip", 0, shown[0]);
        assertNotEquals("the band", 0, shown[1]);

        logo.setAlpha(0f);
        frame();
        int[] hollow = drawnAcross(0);
        assertNotEquals("the strip stays", 0, hollow[0]);
        assertEquals("the band goes", 0, hollow[1]);

        // Slid up a little: the header's top is 35 under the window's top, and the strip hasn't moved.
        header.setTranslationY(-40f);
        frame();
        int[] slid = drawnAcross(-40);
        assertNotEquals("the strip stays", 0, slid[0]);
        assertEquals("the band goes", 0, slid[1]);
    }

    /** The glass's alpha halfway down the strip and halfway down the header at rest, with the header slid by [moved]. */
    private int[] drawnAcross(int moved) {
        Bitmap bitmap = Bitmap.createBitmap(WIDE, STATUS + HEADER, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(0, STATUS + moved);
        Drawable frost = header.getBackground();
        frost.setBounds(0, 0, WIDE, HEADER);
        frost.draw(canvas);
        return new int[] {Color.alpha(bitmap.getPixel(WIDE / 2, STATUS / 2)),
                Color.alpha(bitmap.getPixel(WIDE / 2, STATUS + HEADER / 2 + moved))};
    }

    /** A logo two views deep in the header, the way Instagram's sits. */
    private View logoInHeader() {
        FrameLayout row = new FrameLayout(header.getContext());
        View logo = new View(header.getContext());
        row.addView(logo, new FrameLayout.LayoutParams(200, 80));
        header.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH));
        layout();
        return logo;
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
        assertFalse("the wrapper lets the header's glass past its top edge", wrapper.getClipChildren());

        wrapper.setClipChildren(true);
        frame();
        assertFalse("and keeps doing so if it gets its clip back", wrapper.getClipChildren());

        feedList.addView(new View(context), new FrameLayout.LayoutParams(MATCH, 400));
        frame();
        assertEquals("the posts coming in don't pad it again", STATUS, feedList.getPaddingTop());

        Settings.SEE_THROUGH_HOME_TOP_BAR.save(false);
        frame();
        assertTrue("off, the wrapper clips again", wrapper.getClipChildren());
        assertEquals(STATUS, windowTop(sticky));
        assertEquals(STATUS, windowTop(feedList));
        assertEquals(0, feedList.getPaddingTop());
        assertTrue(feedList.getClipToPadding());
    }

    /** Profile swapped into the panel Home was in keeps its own header below the clock, and Home coming back reaches up again. */
    @Test
    public void anotherTabInHomesPanelPutsHomeBackUntilHomeShowsAgain() {
        glaze();
        assertEquals(-STATUS, margin(feed));

        Context context = screen.getContext();
        feed.removeView(list);
        FrameLayout profile = new RecyclerView(context);
        profile.addView(new View(context), new FrameLayout.LayoutParams(MATCH, 400));
        feed.addView(profile, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame();
        frame();

        assertEquals("another tab isn't moved", 0, margin(feed));
        assertEquals(STATUS, windowTop(profile));
        assertEquals(0, profile.getPaddingTop());
        assertEquals("Home's list gets its own padding back", 0, list.getPaddingTop());
        assertTrue(list.getClipToPadding());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));

        feed.removeView(profile);
        feed.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame();
        frame();

        assertEquals(-STATUS, margin(feed));
        assertEquals(STATUS, list.getPaddingTop());
        assertArrayEquals(new int[] {STATUS, 0}, GlassTopBar.reach(header));
    }

    /** Reels moves the panel up to the window's top and hides the header: Home mustn't stay pulled up past it. */
    @Test
    public void thePanelMovingOrTheHeaderGoingPutsHomeBack() {
        glaze();

        margins(panel, 0);
        frame();
        frame();
        assertEquals("the panel at the window's top isn't pulled past it", 0, margin(feed));
        assertEquals(0, windowTop(feed));
        assertEquals(0, list.getPaddingTop());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));

        margins(panel, STATUS);
        frame();
        frame();
        assertEquals("back below the status bar, it reaches up again", -STATUS, margin(feed));

        header.setVisibility(View.GONE);
        frame();
        frame();
        assertEquals("no header, no glass to make room for", 0, margin(feed));
        assertEquals(0, list.getPaddingTop());
    }

    private void margins(View view, int top) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = top;
        view.setLayoutParams(params);
        layout();
    }

    /**
     * Home in a tab pager that comes in after the header, beside an empty panel: the pager is what's
     * pulled up, Home's list in its page on screen is what's padded, and swiping to the next tab puts it back.
     */
    @Test
    public void thePagerHoldingHomeIsPulledUpAndNotTheEmptyPanelBesideIt() {
        Context context = screen.getContext();
        source.removeAllViews();
        FrameLayout empty = new FrameLayout(context);
        source.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        source.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        layout();

        glaze();
        assertEquals("an empty panel isn't moved", 0, margin(empty));
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));

        FrameLayout pager = new FrameLayout(context);
        FrameLayout pages = new RecyclerView(context);
        FrameLayout homePage = new FrameLayout(context);
        FrameLayout homeList = new MainFeedRecyclerView(context);
        homePage.addView(homeList, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout nextPage = new FrameLayout(context);
        nextPage.addView(new RecyclerView(context), new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(homePage, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams beside = new FrameLayout.LayoutParams(WIDE, MATCH);
        beside.leftMargin = WIDE;
        pages.addView(nextPage, beside);
        pager.addView(pages, new FrameLayout.LayoutParams(MATCH, MATCH));
        source.addView(pager, 1, new FrameLayout.LayoutParams(MATCH, MATCH));
        layout();
        frame();

        assertEquals("the pager holding Home is pulled up", -STATUS, margin(pager));
        assertEquals(0, margin(empty));
        assertEquals("Home's list is padded", STATUS, homeList.getPaddingTop());
        assertEquals("the pages aren't, or Home would start below the clock again", 0, pages.getPaddingTop());
        assertEquals(0, windowTop(homeList));
        assertArrayEquals(new int[] {STATUS, 0}, GlassTopBar.reach(header));

        pages.scrollTo(WIDE, 0);
        frame();
        frame();
        assertEquals("the next tab puts Home back", 0, margin(pager));
        assertEquals(0, homeList.getPaddingTop());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
    }

    /**
     * Home's whole page beside the tab showing in Instagram's tab pager, still laid out and visible, the way
     * 454 keeps it under the inbox: Home goes back below the clock and stays there, not pulled up and let go
     * again frame after frame.
     */
    @Test
    public void homesPageBesideAnotherTabStaysPut() {
        glaze();
        assertEquals(-STATUS, margin(feed));

        panel.setTranslationX(-2 * WIDE);
        for (int i = 0; i < 3; i++) frame();
        assertTrue(header.isShown());
        assertEquals("Home goes back below the status bar", 0, margin(feed));
        assertEquals(0, list.getPaddingTop());
        header.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse("and isn't moved again on the next frame", feed.isLayoutRequested());
        assertEquals(0, margin(feed));

        panel.setTranslationX(0f);
        frame();
        frame();
        assertEquals("Home showing again reaches up again", -STATUS, margin(feed));
        assertEquals(STATUS, list.getPaddingTop());
    }

    /**
     * Instagram already running Home's feed behind the status bar, with only the header below it: nothing
     * moves and the glass reaches up over the posts, wherever the header slides.
     */
    @Test
    public void instagramsOwnFeedBehindTheClockGetsTheGlassUpThere() {
        margins(panel, 0);
        margins(header, STATUS);
        assertEquals(0, windowTop(feed));
        assertEquals(STATUS, windowTop(header));

        glaze();

        assertEquals("nothing moves", 0, margin(feed));
        assertEquals(0, list.getPaddingTop());
        assertTrue(list.getClipToPadding());
        assertArrayEquals("the glass reaches up to the window's top", new int[] {STATUS, 0}, GlassTopBar.reach(header));
        assertFalse("the header's parent lets the glass past its top edge", source.getClipChildren());

        header.setTranslationY(-HEADER - 30f);
        frame();
        assertArrayEquals(new int[] {0, 30}, GlassTopBar.reach(header));

        Settings.SEE_THROUGH_HOME_TOP_BAR.save(false);
        frame();
        assertTrue(source.getClipChildren());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
    }

    /** A header further down than the status bar's height, over Instagram's own feed, keeps its glass to itself. */
    @Test
    public void aHeaderFarBelowTheClockOverInstagramsOwnFeedIsLeftAlone() {
        margins(panel, 0);
        margins(header, STATUS + 50);

        glaze();

        assertTrue(GlassTopBar.showing(header));
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
        assertTrue(source.getClipChildren());
    }

    /** Wrappers round the header and many parents between Home and the window's top: the glass still gets there. */
    @Test
    public void aLongChainOfParentsStillReachesTheWindowsTop() {
        Context context = screen.getContext();
        source.removeView(header);
        FrameLayout outer = new FrameLayout(context);
        FrameLayout at = outer;
        for (int depth = 0; depth < GlassTopBar.HOST_DEPTH - 2; depth++) {
            FrameLayout inner = new FrameLayout(context);
            at.addView(inner, new FrameLayout.LayoutParams(MATCH, HEADER));
            at = inner;
        }
        at.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        source.addView(outer, new FrameLayout.LayoutParams(MATCH, HEADER));
        panel.removeView(source);
        FrameLayout down = panel;
        for (int depth = 0; depth < 13; depth++) {
            FrameLayout next = new FrameLayout(context);
            down.addView(next, new FrameLayout.LayoutParams(MATCH, MATCH));
            down = next;
        }
        down.addView(source, new FrameLayout.LayoutParams(MATCH, MATCH));
        layout();

        glaze();

        assertEquals(0, windowTop(feed));
        assertArrayEquals(new int[] {STATUS, 0}, GlassTopBar.reach(header));
        for (ViewGroup group = (ViewGroup) header.getParent(); group != screen; group = (ViewGroup) group.getParent()) {
            assertFalse(group.getClipChildren());
        }
    }
}
