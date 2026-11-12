/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** See-through top bar (#418): Home's header shows the feed blurred under it, and goes back when it should. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GlassTopBarTest {
    private static final int WIDE = 1080;
    private static final int TALL = 2000;
    private static final int HEADER = 150;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Activity activity;
    private FrameLayout screen;
    private View feed;
    private FrameLayout header;
    private FrameLayout logoRow;
    private View chip;
    private List<View> views;

    @Before
    public void prepare() {
        resetSwitches();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        screen = new FrameLayout(activity);
        feed = new View(activity);
        feed.setBackgroundColor(Color.RED);
        header = new FrameLayout(activity);
        header.setBackgroundColor(Color.BLACK);
        logoRow = new FrameLayout(activity);
        logoRow.setBackgroundColor(Color.BLACK);
        chip = new View(activity);
        chip.setBackgroundColor(Color.DKGRAY);
        logoRow.addView(chip, new FrameLayout.LayoutParams(40, 40));
        header.addView(logoRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(feed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        activity.setContentView(screen);
        layout();
        views = Collections.singletonList(header);
    }

    @After
    public void restore() {
        resetSwitches();
    }

    private static void resetSwitches() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.resetToDefault();
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT.resetToDefault();
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

    private void frame() {
        header.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static int colour(View view) {
        Drawable background = view.getBackground();
        assertTrue("expected a plain fill, found " + background, background instanceof ColorDrawable);
        return ((ColorDrawable) background).getColor();
    }

    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals(Boolean.FALSE, Settings.SEE_THROUGH_HOME_TOP_BAR.defaultValue);
        assertTrue(Settings.SEE_THROUGH_HOME_TOP_BAR.rebootApp);
    }

    @Test
    public void offLeavesInstagramsHeaderAlone() {
        assertSame(views, HomeTopBar.slidingViews(views));
        frame();

        assertFalse(GlassTopBar.showing(header));
        assertEquals(Color.BLACK, colour(header));
        assertEquals(Color.BLACK, colour(logoRow));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.COUNT_GLAZED));
    }

    @Test
    public void onGlazesTheHeaderAndTakesOffFillsAsWideAsIt() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);

        assertSame("with Keep off the header still slides", views, HomeTopBar.slidingViews(views));
        frame();

        assertTrue(GlassTopBar.showing(header));
        assertNull("the logo row's fill would cover the glass", logoRow.getBackground());
        assertEquals("a button's own chip stays", Color.DKGRAY, colour(chip));
        assertEquals("the feed under it is untouched", Color.RED, colour(feed));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTopBar.COUNT_GLAZED + " 1"));
    }

    @Test
    public void withKeepOnTheHeaderStaysAndGetsTheGlass() {
        Settings.KEEP_HOME_TOP_BAR.save(true);
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        header.setTranslationY(-40f);

        assertTrue(HomeTopBar.slidingViews(views).isEmpty());
        frame();

        assertEquals(0f, header.getTranslationY(), 0f);
        assertTrue(GlassTopBar.showing(header));
    }

    @Test
    public void handedOverTwiceItIsGlazedOnce() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);

        HomeTopBar.slidingViews(views);
        HomeTopBar.slidingViews(views);
        frame();

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTopBar.COUNT_GLAZED + " 1"));
    }

    @Test
    public void aFillInstagramPutsBackComesOffAndItsLatestGoesBack() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();

        header.setBackground(new ColorDrawable(Color.WHITE));
        logoRow.setBackgroundColor(Color.GRAY);
        frame();
        assertTrue("put back by Instagram, the glass goes on again", GlassTopBar.showing(header));
        assertNull(logoRow.getBackground());

        Settings.SEE_THROUGH_HOME_TOP_BAR.save(false);
        frame();
        assertEquals("Instagram's latest fill goes back", Color.WHITE, colour(header));
        assertEquals(Color.GRAY, colour(logoRow));
    }

    @Test
    public void pauseTakesItOffAndResumingPutsItBack() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals(Color.BLACK, colour(header));
        assertEquals(Color.BLACK, colour(logoRow));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        frame();
        assertTrue(GlassTopBar.showing(header));
        assertNull(logoRow.getBackground());
    }

    @Test
    public void leavingTheWindowPutsItAllBackAndComingBackGlazesAgain() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();

        screen.removeView(header);
        assertEquals(Color.BLACK, colour(header));
        assertEquals(Color.BLACK, colour(logoRow));

        screen.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        layout();
        frame();
        assertTrue(GlassTopBar.showing(header));
    }

    @Test
    public void aHeaderHandedOverBeforeItIsShownGlazesOnceItIs() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        screen.removeView(header);

        HomeTopBar.slidingViews(views);
        assertEquals("nothing changes off the window", Color.BLACK, colour(header));

        screen.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        layout();
        frame();
        assertTrue(GlassTopBar.showing(header));
    }

    @Test
    public void aThrowingSwitchFailsOpen() {
        GlassTopBar.glaze(views, () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(Color.BLACK, colour(header));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(GlassTopBar.HOOK));
    }

    @Test
    public void theHostIsTheHeaderOrTheViewWrappedRoundIt() {
        assertSame(header, GlassTopBar.host(header));

        FrameLayout wrapper = new FrameLayout(activity);
        View inner = new View(activity);
        wrapper.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        screen.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        layout();
        assertSame(wrapper, GlassTopBar.host(inner));

        assertNull("off the screen there's no host", GlassTopBar.host(new View(activity)));
    }

    @Test
    public void theTintComesFromInstagramsFillOrTheTheme() {
        List<Drawable> none = Collections.emptyList();
        assertEquals(Color.BLACK, GlassTopBar.base(new ColorDrawable(Color.BLACK), none, false));
        assertEquals(0xff123456, GlassTopBar.base(null, Collections.<Drawable>singletonList(new ColorDrawable(0xff123456)), false));
        assertEquals("a see-through fill says nothing", Color.WHITE, GlassTopBar.base(new ColorDrawable(0x80000000), none, false));
        assertEquals(Color.BLACK, GlassTopBar.base(null, none, true));
        assertEquals(Color.WHITE, GlassTopBar.base(null, none, false));

        assertEquals(GlassTopBar.HAIRLINE_DARK, GlassTopBar.hairline(Color.BLACK));
        assertEquals(GlassTopBar.HAIRLINE_LIGHT, GlassTopBar.hairline(Color.WHITE));

        assertFalse(GlassTopBar.fills(null));
        assertFalse(GlassTopBar.fills(new ColorDrawable(Color.TRANSPARENT)));
        assertTrue(GlassTopBar.fills(new ColorDrawable(Color.RED)));
    }

    /** On a phone's canvas the feed is recorded under the header and drawn blurred; with nothing under it, only the tint. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void theFeedUnderTheHeaderIsRecordedAndDrawn() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();
        String report = HookStatus.report().toString();
        assertTrue("no backdrop recorded: " + report, GlassTopBar.recorded(header));
        assertFalse(report, report.contains(GlassTopBar.HOOK));

        // A GPU canvas takes the recording as a node: drawing it there mustn't throw.
        RenderNode target = new RenderNode("target");
        target.setPosition(0, 0, header.getWidth(), header.getHeight());
        Canvas recording = target.beginRecording(header.getWidth(), header.getHeight());
        try {
            header.getBackground().setBounds(0, 0, header.getWidth(), header.getHeight());
            header.getBackground().draw(recording);
        } finally {
            target.endRecording();
        }
        assertTrue(target.hasDisplayList());

        // First in its parent, with nothing drawn before it, the header has no feed to show.
        screen.removeView(header);
        screen.addView(header, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        layout();
        frame();
        assertTrue(GlassTopBar.showing(header));
        assertFalse(GlassTopBar.recorded(header));
    }

    /** Off the GPU there's no blur: a firmer tint from the bar's colour, and a hairline along the bottom. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aSoftwareCanvasGetsThePlainTintAndTheHairline() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();

        Bitmap bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
        Drawable frost = header.getBackground();
        frost.setBounds(0, 0, 100, 50);
        frost.draw(new Canvas(bitmap));

        int middle = bitmap.getPixel(50, 20);
        int tint = GlassTabBar.tint(Color.BLACK, false, GlassTabBar.opacityScale());
        assertEquals(Color.alpha(tint), Color.alpha(middle), 1);
        assertEquals(Color.red(tint), Color.red(middle), 2);
        assertNotEquals("the hairline", middle, bitmap.getPixel(50, 49));
    }

    /** With the glass tab bar on, Home's header takes the pill's sheen, light at the top fading out (#513). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void withTheGlassTabBarOnTheHeaderTakesItsSheen() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();
        Bitmap plain = drawn(header.getBackground());
        assertFalse(GlassTopBar.sheened(header));

        Settings.GLASS_TAB_BAR.save(true);
        Bitmap shiny = drawn(header.getBackground());
        assertTrue(GlassTopBar.sheened(header));
        assertTrue("lighter at the top", Color.red(shiny.getPixel(50, 2)) > Color.red(plain.getPixel(50, 2)) + 4);
        assertEquals("faded out by the bottom", Color.red(plain.getPixel(50, 47)), Color.red(shiny.getPixel(50, 47)), 3);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        drawn(header.getBackground());
        assertFalse("paused, the tab bar's look rests", GlassTopBar.sheened(header));
    }

    /** A chat's header takes the glass tab bar's look too, its sheen and its rim bend along the bottom (#391). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aChatsHeaderTakesTheTabBarsLook() {
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_REFRACT.save(true);
        GlassTopBar.glazeChat(header, () -> true);
        frame();
        assertTrue(GlassTopBar.showing(header));
        assertTrue(GlassTopBar.recorded(header));

        drawn(header.getBackground());
        assertTrue(GlassTopBar.sheened(header));
        drawnOnTheGpu(header);
        assertEquals(1, GlassTopBar.bends(header));
    }

    /**
     * A message box takes the glass tab bar's sheen while that's on, and its rim bent all the way round while
     * the tab bar's bends (#391).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aMessageBoxTakesTheTabBarsLook() {
        screen.setId(View.generateViewId());
        FrameLayout holder = new FrameLayout(activity);
        FrameLayout box = new FrameLayout(activity);
        box.addView(new View(activity), new FrameLayout.LayoutParams(40, 40));
        holder.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        screen.addView(holder, 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER));
        layout();
        GlassTopBar.glazeBox(box, screen.getId(), true, () -> true, FamilyNames.GLASS_CHAT);
        frame();
        assertTrue(GlassTopBar.boxShowing(box));
        assertTrue(GlassTopBar.recorded(box));

        Bitmap plain = drawn(box.getBackground());
        drawnOnTheGpu(box);
        assertFalse(GlassTopBar.sheened(box));
        assertEquals("no glass tab bar, no bend", 0, GlassTopBar.bends(box));

        Settings.GLASS_TAB_BAR.save(true);
        Bitmap shiny = drawn(box.getBackground());
        assertTrue(GlassTopBar.sheened(box));
        assertTrue("lighter at the top", Color.red(shiny.getPixel(50, 8)) > Color.red(plain.getPixel(50, 8)) + 4);
        drawnOnTheGpu(box);
        assertEquals("the tab bar's bend is off", 0, GlassTopBar.bends(box));

        Settings.GLASS_TAB_BAR_REFRACT.save(true);
        drawnOnTheGpu(box);
        assertEquals(1, GlassTopBar.bends(box));
    }

    /** On a phone's canvas, the header bends along its bottom edge only while the tab bar's rim bends (#513). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void theHeaderBendsOnlyWhileTheTabBarsRimDoes() {
        Settings.SEE_THROUGH_HOME_TOP_BAR.save(true);
        HomeTopBar.slidingViews(views);
        frame();
        assertTrue(GlassTopBar.recorded(header));

        drawnOnTheGpu(header);
        assertEquals("no glass tab bar, no bend", 0, GlassTopBar.bends(header));

        Settings.GLASS_TAB_BAR.save(true);
        drawnOnTheGpu(header);
        assertEquals("the tab bar's bend is off", 0, GlassTopBar.bends(header));

        Settings.GLASS_TAB_BAR_REFRACT.save(true);
        drawnOnTheGpu(header);
        assertEquals(1, GlassTopBar.bends(header));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(GlassTopBar.HOOK));

        Settings.GLASS_TAB_BAR_BLUR.save(false);
        drawnOnTheGpu(header);
        assertEquals("no blur on the tab bar, nothing of its to bend", 1, GlassTopBar.bends(header));

        drawn(header.getBackground());
        assertEquals("off the GPU there's no blur to bend", 1, GlassTopBar.bends(header));
    }

    private static Bitmap drawn(Drawable frost) {
        Bitmap bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
        frost.setBounds(0, 0, 100, 50);
        frost.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Draws [view]'s glass at its size into a render node, as a phone's canvas does. */
    private static void drawnOnTheGpu(View view) {
        RenderNode target = new RenderNode("target");
        target.setPosition(0, 0, view.getWidth(), view.getHeight());
        Canvas recording = target.beginRecording(view.getWidth(), view.getHeight());
        try {
            Drawable frost = view.getBackground();
            frost.setBounds(0, 0, view.getWidth(), view.getHeight());
            frost.draw(recording);
        } finally {
            target.endRecording();
        }
    }
}
