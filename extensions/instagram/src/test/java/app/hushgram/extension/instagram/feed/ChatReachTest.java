/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderNode;
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A chat's messages run on under its glass header and up behind the status bar (#684), padded so the oldest still
 * stops where it did, clipped below the header on screen and blurred in the glass, with a soft edge under it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class ChatReachTest {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int STATUS = 75;
    private static final int HEADER = 150;
    private static final int PADDING = 12;
    /** Where Instagram ends the list above the bottom of the view holding the messages, the navigation bar, the keyboard. */
    private static final int ENDS = 66;
    private static final int NAV = 48;
    private static final int KEYBOARD = 600;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final ToIntFunction<View> window = ChatReach.window;
    private final ToIntFunction<View> navigation = ChatReach.navigation;

    private Activity activity;
    private FrameLayout screen;
    private FrameLayout panel;
    private FrameLayout source;
    private FrameLayout header;
    private FrameLayout messages;
    private FrameLayout holder;
    private FrameLayout refresh;
    private FrameLayout list;
    private final AtomicBoolean on = new AtomicBoolean(true);

    @Before
    public void prepare() {
        resetSwitches();
        GlassReach.insets = view -> STATUS;
        ChatReach.window = view -> TALL;
        ChatReach.navigation = view -> 0;
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar);
        activity = controller.setup().get();
        build(new FrameLayout(activity), HEADER);
    }

    @After
    public void restore() {
        GlassReach.insets = GlassReach::statusBar;
        ChatReach.window = window;
        ChatReach.navigation = navigation;
        resetSwitches();
    }

    private static void resetSwitches() {
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();
        Settings.GLASS_CHAT_TOP_BAR_OPACITY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * Instagram's chat: the coordinator at the window's top lays the panel out below the status bar, and in the
     * chat the background, the header, then the messages [below] the header's top, the list in a refresh layout
     * in [around], itself in the view holding the messages.
     */
    private void build(FrameLayout around, int below) {
        build(new FrameLayout(activity), around, below);
    }

    /** {@link #build(FrameLayout, int)}, with [holding] between the view holding the messages and [around]. */
    private void build(FrameLayout holding, FrameLayout around, int below) {
        screen = new FrameLayout(activity);
        panel = new FrameLayout(activity);
        source = new FrameLayout(activity);
        header = new FrameLayout(activity);
        header.setBackgroundColor(Color.BLACK);
        header.addView(new View(activity), new FrameLayout.LayoutParams(100, 100));
        messages = new FrameLayout(activity);
        messages.setId(View.generateViewId());
        holder = holding;
        refresh = around;
        list = new FrameLayout(activity);
        list.setId(View.generateViewId());
        list.setPadding(0, PADDING, 0, 0);
        list.addView(new View(activity), new FrameLayout.LayoutParams(MATCH, 400));
        refresh.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        holder.addView(refresh, new FrameLayout.LayoutParams(MATCH, MATCH));
        messages.addView(holder, new FrameLayout.LayoutParams(MATCH, MATCH));
        View background = new View(activity);
        background.setBackgroundColor(Color.BLUE);
        source.addView(background, new FrameLayout.LayoutParams(MATCH, MATCH));
        source.addView(header, new FrameLayout.LayoutParams(MATCH, HEADER));
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(MATCH, MATCH);
        under.topMargin = below;
        source.addView(messages, under);
        panel.addView(source, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams belowStatus = new FrameLayout.LayoutParams(MATCH, MATCH);
        belowStatus.topMargin = STATUS;
        screen.addView(panel, belowStatus);
        activity.setContentView(screen);
        layout();
        assertEquals("the frame sits at the window's top", 0, windowTop(screen));
        assertEquals("the header starts below the status bar", STATUS, windowTop(header));
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        screen.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, WIDE, TALL);
    }

    /** A frame: the glass goes on, then the chat lays out again with whatever it moved. */
    private void frame() {
        header.getViewTreeObserver().dispatchOnPreDraw();
        layout();
        header.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void glaze() {
        GlassTopBar.glazeChat(header, messages.getId(), list.getId(), on::get);
        frame();
    }

    private static int windowTop(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }

    private static int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
    }

    private static int windowBottom(View view) {
        return windowTop(view) + view.getHeight();
    }

    private static int bottomMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }

    private static void bottomMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.bottomMargin = margin;
        view.setLayoutParams(params);
    }

    /**
     * Instagram's chat at its bottom: the coordinator ends the panel above the navigation bar, and the list ends
     * [ENDS] above the bottom of the view holding the messages, halfway down the message bar.
     */
    private void aboveTheNavigationBar() {
        ChatReach.navigation = view -> NAV;
        bottomMargin(panel, NAV);
        bottomMargin(refresh, ENDS);
        layout();
        assertEquals(TALL - NAV, windowBottom(messages));
        assertEquals(TALL - NAV - ENDS, windowBottom(list));
    }

    private void assertLeftAlone() {
        assertEquals(0, margin(list));
        assertEquals(0, margin(refresh));
        assertEquals(PADDING, list.getPaddingTop());
        assertTrue(list.getClipToPadding());
        assertNull(messages.getClipBounds());
        assertNull(messages.getForeground());
        for (ViewGroup group : new ViewGroup[] {screen, panel, source, holder, refresh, messages}) {
            assertTrue(group.getClipChildren());
        }
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
    }

    @Test
    public void theMessagesRunOnUnderTheHeaderAndBehindTheStatusBar() {
        glaze();
        assertTrue(GlassTopBar.showing(header));
        assertEquals("up to the window's top", 0, windowTop(list));
        assertEquals("the oldest message stops where it did", PADDING + STATUS + HEADER, list.getPaddingTop());
        assertFalse(list.getClipToPadding());
        assertEquals("the view holding them stays put", STATUS + HEADER, windowTop(messages));
        assertEquals("clipped below the header on screen",
                new Rect(0, 0, messages.getWidth(), messages.getHeight()), messages.getClipBounds());
        assertArrayEquals("the glass reaches up behind the status bar", new int[] {STATUS, 0}, GlassTopBar.reach(header));
        for (ViewGroup group : new ViewGroup[] {screen, panel, source, holder, refresh, messages}) {
            assertFalse(group.getClipChildren());
        }
        assertTrue("the soft edge", messages.getForeground() instanceof GlassTopBar.Edge);

        // Instagram setting the list's padding again keeps it under the header.
        list.setPadding(0, 30, 0, 0);
        frame();
        assertEquals(30 + STATUS + HEADER, list.getPaddingTop());
        assertEquals(0, windowTop(list));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));
    }

    @Test
    public void offEverythingGoesBack() {
        glaze();
        assertEquals(0, windowTop(list));
        on.set(false);
        frame();
        assertFalse(GlassTopBar.showing(header));
        assertLeftAlone();
        assertEquals(STATUS + HEADER, windowTop(list));

        on.set(true);
        frame();
        assertEquals("on again, under the header again", 0, windowTop(list));
    }

    @Test
    public void aHiddenHeaderPutsTheMessagesBack() {
        glaze();
        header.setVisibility(View.GONE);
        frame();
        assertEquals(0, margin(list));
        assertEquals(PADDING, list.getPaddingTop());
        assertNull(messages.getClipBounds());
        assertNull(messages.getForeground());
    }

    /**
     * The list runs on down behind the message bar and the navigation bar to the window's bottom, padded so the
     * newest message stops where it did, and only to the bottom of the view holding the messages while the
     * keyboard is up (#729). The window is the test's own size: the reach down measures against the window's
     * bottom each frame, and a traversal at another size between frames would move it.
     */
    @Test
    @Config(qualifiers = "w1080dp-h2000dp")
    public void theMessagesRunOnDownBehindTheMessageBarAndTheNavigationBar() {
        aboveTheNavigationBar();
        glaze();
        assertEquals("down to the window's bottom", TALL, windowBottom(list));
        assertEquals("up to its top still", 0, windowTop(list));
        assertEquals("the newest message stops where it did", ENDS + NAV, list.getPaddingBottom());
        assertEquals(PADDING + STATUS + HEADER, list.getPaddingTop());
        assertEquals("the view holding them stays put", TALL - NAV, windowBottom(messages));
        assertEquals("drawn on behind the navigation bar",
                new Rect(0, 0, messages.getWidth(), messages.getHeight() + NAV), messages.getClipBounds());
        for (ViewGroup group : new ViewGroup[] {screen, panel, source, holder, refresh, messages}) {
            assertFalse(group.getClipChildren());
        }

        // Instagram setting the margin and the padding again keeps it down.
        bottomMargin(refresh, ENDS);
        list.setPadding(0, list.getPaddingTop(), 0, 20);
        frame();
        assertEquals(TALL, windowBottom(list));
        assertEquals(20 + ENDS + NAV, list.getPaddingBottom());

        // The keyboard up: down to the bottom of the view holding the messages, above the keyboard.
        bottomMargin(panel, KEYBOARD);
        frame();
        frame();
        assertEquals(TALL - KEYBOARD, windowBottom(messages));
        assertEquals(TALL - KEYBOARD, windowBottom(list));
        assertEquals(20 + ENDS, list.getPaddingBottom());
        assertEquals(new Rect(0, 0, messages.getWidth(), messages.getHeight()), messages.getClipBounds());

        bottomMargin(panel, NAV);
        frame();
        frame();
        assertEquals("the keyboard down, down to the window's bottom again", TALL, windowBottom(list));
        assertEquals(20 + ENDS + NAV, list.getPaddingBottom());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));
    }

    /**
     * Instagram pads the messages again each time the message bar changes height, and the frame laid out then has
     * them without the room the reach gives them, a line or more off, which flashed as the messages jumped and
     * came back (#757). That frame isn't drawn, the next one is, with the room back, and never two in a row.
     */
    @Test
    @Config(qualifiers = "w1080dp-h2000dp")
    public void aFrameLaidOutWithoutTheReachIsNotDrawn() {
        aboveTheNavigationBar();
        glaze();
        assertFalse("a still chat draws", preDraw());

        // The message bar grows a line and Instagram pads the list for it.
        list.setPadding(0, list.getPaddingTop(), 0, 20);
        assertTrue("laid out without the room, not drawn", preDraw());
        assertEquals("the room is back for the next layout", 20 + ENDS + NAV, list.getPaddingBottom());
        layout();
        assertFalse("laid out with it, drawn", preDraw());
        assertEquals(TALL, windowBottom(list));

        // The same at the top, under the header.
        list.setPadding(0, 30, 0, list.getPaddingBottom());
        assertTrue(preDraw());
        layout();
        assertFalse(preDraw());
        assertEquals(30 + STATUS + HEADER, list.getPaddingTop());

        // Instagram padding it again on every frame still has every other one drawn.
        list.setPadding(0, list.getPaddingTop(), 0, 40);
        assertTrue(preDraw());
        list.setPadding(0, list.getPaddingTop(), 0, 50);
        assertFalse("never two frames in a row", preDraw());
        layout();
        assertFalse(preDraw());
        assertEquals(50 + ENDS + NAV, list.getPaddingBottom());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));
    }

    /** One pre-draw pass: true when it holds the frame back. */
    private boolean preDraw() {
        return header.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    @Config(qualifiers = "w1080dp-h2000dp")
    public void offTheMessagesComeBackUpFromTheBottom() {
        aboveTheNavigationBar();
        glaze();
        assertEquals(TALL, windowBottom(list));
        on.set(false);
        frame();
        assertEquals(ENDS, bottomMargin(refresh));
        assertEquals(0, list.getPaddingBottom());
        assertNull(messages.getClipBounds());
        for (ViewGroup group : new ViewGroup[] {screen, panel, source, holder, refresh, messages}) {
            assertTrue(group.getClipChildren());
        }
        assertEquals(TALL - NAV - ENDS, windowBottom(list));
        assertEquals(STATUS + HEADER, windowTop(list));
    }

    /** A parent laying the view out where it likes at the bottom: the list keeps its bottom, and its top stays up. */
    @Test
    @Config(qualifiers = "w1080dp-h2000dp")
    public void aParentIgnoringTheBottomMarginLeavesTheListsBottomAlone() {
        build(new Pinned(activity, ENDS), new FrameLayout(activity), HEADER);
        aboveTheNavigationBar();
        glaze();
        frame();
        assertEquals("the top still up", 0, windowTop(list));
        assertEquals(PADDING + STATUS + HEADER, list.getPaddingTop());
        assertEquals("the bottom where Instagram had it", TALL - NAV - ENDS, windowBottom(list));
        assertEquals(ENDS, bottomMargin(refresh));
        assertEquals(0, list.getPaddingBottom());
        assertEquals(new Rect(0, 0, messages.getWidth(), messages.getHeight()), messages.getClipBounds());
    }

    @Test
    public void messagesNotRightUnderTheHeaderAreLeftAlone() {
        build(new FrameLayout(activity), HEADER + 40);
        glaze();
        assertTrue(GlassTopBar.showing(header));
        assertEquals(0, margin(list));
        assertEquals(PADDING, list.getPaddingTop());
        assertNull(messages.getForeground());
        assertArrayEquals(new int[] {0, 0}, GlassTopBar.reach(header));
    }

    @Test
    public void withoutTheIdsTheMessagesAreLeftAlone() {
        GlassTopBar.glazeChat(header, on::get);
        frame();
        assertTrue(GlassTopBar.showing(header));
        assertEquals(0, margin(list));
        assertNull(messages.getForeground());
    }

    @Test
    @Config(sdk = 28)
    public void belowAndroid12WithNoBlurTheMessagesStayBelowTheHeader() {
        glaze();
        assertTrue(GlassTopBar.showing(header));
        assertEquals(0, margin(list));
        assertEquals(PADDING, list.getPaddingTop());
        assertTrue(screen.getClipChildren());
    }

    /** A refresh layout laying its list out where it likes, margin or not: the layout itself goes up instead. */
    @Test
    public void aParentIgnoringTheMarginHasTheNextViewUpPulled() {
        build(new Stubborn(activity), HEADER);
        glaze();
        frame();
        frame();
        assertEquals("the list let go", 0, margin(list));
        assertEquals("the refresh layout pulled", -(STATUS + HEADER), margin(refresh));
        assertEquals(0, windowTop(list));
        assertEquals(PADDING + STATUS + HEADER, list.getPaddingTop());
    }

    /** Home's reach and a chat's share parents near the window's top: one letting go leaves the other's hold. */
    @Test
    public void sharedParentsClipAgainOnlyOnceNothingHoldsThem() {
        FrameLayout shared = new FrameLayout(activity);
        shared.setPadding(0, 10, 0, 0);
        GlassReach.stopClipping(shared);
        GlassReach.stopClipping(shared);
        assertFalse(shared.getClipChildren());
        assertFalse(shared.getClipToPadding());
        GlassReach.clipAgain(shared);
        assertFalse("still held", shared.getClipChildren());
        GlassReach.clipAgain(shared);
        assertTrue(shared.getClipChildren());
        assertTrue(shared.getClipToPadding());
        GlassReach.clipAgain(shared);
        assertTrue("nothing held, nothing changes", shared.getClipChildren());
    }

    /**
     * On a phone's canvas the messages are recorded into the glass without the soft edge, which then draws over
     * their top: the glass's tint, faded out over 24dp.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void theSoftEdgeFadesTheGlassOutOverTheMessages() {
        glaze();
        assertTrue(GlassTopBar.recorded(header));
        int recorded = GlassTopBar.messagesRecorded(header);
        assertTrue("the messages are in the glass", recorded > 0);
        int draws = GlassTopBar.edgeDraws(messages);
        assertTrue(draws >= 0);

        // A new blur records the glass again, the messages with it, and the edge over them stays out of it.
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.save(Settings.GLASS_TAB_BAR_BLUR_RADIUS.get() + 4);
        header.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(GlassTopBar.messagesRecorded(header) > recorded);
        assertEquals("the edge never draws into the glass it shows", draws, GlassTopBar.edgeDraws(messages));

        RenderNode target = new RenderNode("target");
        target.setPosition(0, 0, messages.getWidth(), messages.getHeight());
        Canvas recording = target.beginRecording(messages.getWidth(), messages.getHeight());
        try {
            messages.draw(recording);
        } finally {
            target.endRecording();
        }
        assertEquals(draws + 1, GlassTopBar.edgeDraws(messages));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));

        // Off the GPU there's no blur, only the tint, which shows how it fades.
        Drawable edge = messages.getForeground();
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        edge.setBounds(0, 0, 100, 100);
        edge.draw(new Canvas(bitmap));
        int fade = Math.round(GlassTopBar.STRIP_FADE * activity.getResources().getDisplayMetrics().density);
        int tint = Color.alpha(GlassTabBar.tint(Color.BLACK, false, GlassTabBar.opacityScale()));
        assertEquals("whole at the header's edge", tint, Color.alpha(bitmap.getPixel(50, 0)), 12);
        int half = Color.alpha(bitmap.getPixel(50, fade / 2));
        assertTrue("half way out half way down: " + half, half > tint / 4 && half < tint * 3 / 4);
        assertEquals("gone below it", 0, Color.alpha(bitmap.getPixel(50, fade + 2)));
    }

    /**
     * A parent between the view holding the messages and the list cutting what it holds to its own bounds, as a
     * clip rectangle, an outline or an alpha layer does on a phone: the glass still has the messages that run
     * on above it, under the header and behind the status bar, and the ones below.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aParentCuttingToItsBoundsLeavesTheMessagesInTheGlass() {
        build(new Cutting(activity), new FrameLayout(activity), HEADER);
        View row = new View(activity);
        row.setBackgroundColor(Color.RED);
        FrameLayout.LayoutParams fromTop = new FrameLayout.LayoutParams(MATCH, STATUS + HEADER + 100);
        list.addView(row, fromTop);
        glaze();
        // The oldest message, scrolled up to the window's top.
        fromTop.topMargin = -list.getPaddingTop();
        row.setLayoutParams(fromTop);
        frame();
        assertEquals(0, windowTop(row));

        Bitmap glass = Bitmap.createBitmap(WIDE, TALL, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(glass);
        // Where the header's parent is in the window: the glass records in its coordinates.
        canvas.translate(0, windowTop(source));
        assertTrue(GlassTopBar.recordMessages(header, canvas));
        assertEquals("behind the status bar", Color.RED, glass.getPixel(WIDE / 2, STATUS / 2));
        assertEquals("under the header", Color.RED, glass.getPixel(WIDE / 2, STATUS + HEADER / 2));
        assertEquals("below it", Color.RED, glass.getPixel(WIDE / 2, STATUS + HEADER + 50));
        assertEquals("the oldest message's end", 0, Color.alpha(glass.getPixel(WIDE / 2, STATUS + HEADER + 150)));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));
    }

    @Test
    public void theDebugLogNamesWhatCutsTheMessagesShort() {
        assertEquals("nothing", ChatReach.cuts(list, messages));
        holder.setClipBounds(new Rect(0, 0, 10, 20));
        refresh.setAlpha(0.5f);
        String cuts = ChatReach.cuts(list, messages);
        assertTrue(cuts, cuts.contains("clip [0,0][10,20]"));
        assertTrue(cuts, cuts.contains("alpha 0.5"));
    }

    /** A FrameLayout cutting what it holds to its own bounds. */
    private static final class Cutting extends FrameLayout {
        Cutting(Context context) {
            super(context);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int saved = canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
        }
    }

    /** A FrameLayout ending its children [keep] above its bottom whatever their bottom margins. */
    private static final class Pinned extends FrameLayout {
        private final int keep;

        Pinned(Context context, int keep) {
            super(context);
            this.keep = keep;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int from = ((MarginLayoutParams) child.getLayoutParams()).topMargin;
                child.layout(0, from, getWidth(), getHeight() - keep);
            }
        }
    }

    /** A FrameLayout laying its children out at its top whatever their margins. */
    private static final class Stubborn extends FrameLayout {
        Stubborn(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            }
        }
    }
}
