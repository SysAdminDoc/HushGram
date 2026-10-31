/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.IntegerSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The colour and size choices Glass tab bar makes from Instagram's bar colour and the screen's width. */
@RunWith(RobolectricTestRunner.class)
public class GlassTabBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @After public void resetGlassSettings() {
        GlassTabBar.animationsOnForTests = null;
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR.resetToDefault();
        Settings.GLASS_TAB_BAR_FLOAT.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_BEHIND.resetToDefault();
        Settings.GLASS_TAB_BAR_HEIGHT.resetToDefault();
        Settings.GLASS_TAB_BAR_CORNERS.resetToDefault();
        Settings.GLASS_TAB_BAR_LIFT.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTICS.resetToDefault();
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.resetToDefault();
        Settings.GLASS_TAB_BAR_SLIDE.resetToDefault();
        Settings.GLASS_TAB_BAR_LIQUID.resetToDefault();
        Settings.GLASS_TAB_BAR_KEEP_SIZE.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GlassTabBar.resetForTests();
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void nativeGlassHasTwelveDpCornersAndEightDpSelectionWithoutMovingTouches() throws Exception {
        GlassTabBar.animationsOnForTests = false;
        for (int base : new int[]{Color.BLACK, Color.WHITE}) {
            for (boolean floating : new boolean[]{false, true}) {
                for (GlassHeight height : new GlassHeight[]{GlassHeight.STANDARD, GlassHeight.COMPACT}) {
                    for (boolean rtl : new boolean[]{false, true}) {
                        try (GlassHost host = new GlassHost(base, floating, height, rtl, false)) {
                            GlassTabBar.apply(host.activity);
                            host.layout();
                            Rect[] touches = new Rect[host.bar.getChildCount()];
                            for (int i = 0; i < touches.length; i++) {
                                View tab = host.bar.getChildAt(i);
                                touches[i] = new Rect(tab.getLeft(), tab.getTop(), tab.getRight(), tab.getBottom());
                                assertTrue("native tab remains at least 48dp wide", tab.getWidth() >= host.dp(48));
                                assertTrue("compact styling preserves the 48dp touch height", tab.getHeight() >= host.dp(48));
                            }
                            try (Pixels plain = host.background()) {
                                int outer = GlassTabBar.outerPx(host.bar.getWidth(), host.density);
                                int top = host.dp(1) + GlassTabBar.pillInsetPx(height, host.bar.getHeight(), host.dp(1), host.density);
                                RectF drawn = ReflectionHelpers.getField(host.bar.getBackground(), "rect");
                                assertEquals(outer + host.density / 2f, drawn.left, 0.01f);
                                assertEquals(top + host.density / 2f, drawn.top, 0.01f);
                                int corner = plain.bitmap.getPixel(outer + host.dp(3), top + host.dp(6));
                                if (base == Color.BLACK) assertTrue("a 12dp corner fills where an oval did not", Color.red(corner) > 5);
                                else if (floating) assertTrue("light glass has the same corner footprint", Color.alpha(corner) > 200);
                                if (floating && base == Color.BLACK) {
                                    assertEquals("the panel corner remains clear", 0, plain.bitmap.getPixel(outer, top));
                                }
                                for (int index : new int[]{0, touches.length - 1}) {
                                    View selected = host.bar.getChildAt(index);
                                    assertTrue("the host's real click handler still runs", selected.performClick());
                                    try (Pixels active = host.background()) {
                                        RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                                        int x = Math.round(slot.left) + host.dp(2);
                                        int y = Math.round(slot.top) + host.dp(4);
                                        int before = Color.red(plain.bitmap.getPixel(x, y));
                                        int after = Color.red(active.bitmap.getPixel(x, y));
                                        assertTrue("the selected slot has an 8dp corner", base == Color.BLACK ? after > before + 10 : after < before - 10);
                                        assertEquals(Math.max(outer + host.dp(4), selected.getLeft() - host.dp(4)), slot.left, 0.01f);
                                        assertEquals(top + host.dp(4), slot.top, 0.01f);
                                    }
                                    for (int i = 0; i < touches.length; i++) {
                                        View tab = host.bar.getChildAt(i);
                                        assertEquals("only the clicked native tab is selected", i == index, tab.isSelected());
                                        assertEquals(touches[i], new Rect(tab.getLeft(), tab.getTop(), tab.getRight(), tab.getBottom()));
                                    }
                                    host.capture((base == Color.BLACK ? "dark" : "light") + (floating ? "-floating" : "-inset")
                                            + "-" + height.name().toLowerCase(java.util.Locale.ROOT) + (rtl ? "-rtl" : "-ltr") + "-selected-" + index);
                                }
                            }
                            assertEquals("selection keeps the host's callbacks", 2, host.clicks.get());
                        }
                    }
                }
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void unavailableBlurUsesTheSameCornerFootprintAndOffOrPauseKeepsTheNativeBar() throws Exception {
        for (int base : new int[]{Color.BLACK, Color.WHITE}) {
            try (GlassHost host = new GlassHost(base, true, GlassHeight.STANDARD, false, true)) {
                GlassTabBar.apply(host.activity);
                host.layout();
                assertTrue(host.bar.getChildAt(2).performClick());
                try (Pixels pixels = host.background()) {
                    int outer = GlassTabBar.outerPx(host.bar.getWidth(), host.density);
                    int corner = pixels.bitmap.getPixel(outer + host.dp(3), host.dp(7));
                    assertTrue("unsupported blur still paints the rounded surface", Color.alpha(corner) > 200);
                }
                host.capture((base == Color.BLACK ? "dark" : "light") + "-blur-unavailable");
            }
        }
        for (boolean paused : new boolean[]{false, true}) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.COMPACT, false, false)) {
                Object nativeBackground = host.bar.getBackground();
                Rect padding = new Rect(host.bar.getPaddingLeft(), host.bar.getPaddingTop(), host.bar.getPaddingRight(), host.bar.getPaddingBottom());
                Settings.GLASS_TAB_BAR.save(paused);
                BaseSettings.PAUSED.save(paused);
                if (paused) PauseForTests.pause(HushgramPause.Reason.SWITCH);
                GlassTabBar.apply(host.activity);
                assertSame(nativeBackground, host.bar.getBackground());
                assertEquals(padding, new Rect(host.bar.getPaddingLeft(), host.bar.getPaddingTop(), host.bar.getPaddingRight(), host.bar.getPaddingBottom()));
                assertTrue(host.bar.getChildAt(0).performClick());
                assertEquals(1, host.clicks.get());
                host.capture(paused ? "glass-paused" : "glass-off");
            }
        }
    }

    /**
     * Space under a floating pill makes the bar that much taller and raises the pill and its tabs by it,
     * so neither gets shorter and the room under the pill shows what's behind. Over Instagram's own bar
     * the choice changes nothing.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void spaceUnderAFloatingPillRaisesItWithoutShrinkingIt() throws Exception {
        for (boolean floating : new boolean[]{true, false}) {
            for (GlassLift lift : GlassLift.values()) {
                try (GlassHost host = new GlassHost(Color.BLACK, floating, GlassHeight.STANDARD, false, false)) {
                    Settings.GLASS_TAB_BAR_LIFT.save(lift);
                    GlassTabBar.apply(host.activity);
                    host.layout();
                    int extra = floating ? host.dp(lift.dp) : 0;
                    String where = lift + (floating ? " floating" : " inset");
                    assertEquals(where, host.dp(52) + extra, host.bar.getHeight());
                    for (int i = 0; i < host.bar.getChildCount(); i++) {
                        View tab = host.bar.getChildAt(i);
                        assertEquals(where + ": the tab keeps its height", host.dp(52) - 2 * host.dp(1), tab.getHeight());
                        assertEquals(where + ": the tab sits above the space", host.dp(1), tab.getTop());
                    }
                    try (Pixels pixels = host.background()) {
                        RectF drawn = ReflectionHelpers.getField(host.bar.getBackground(), "rect");
                        assertEquals(where, host.dp(1) + host.density / 2f, drawn.top, 0.01f);
                        assertEquals(where, host.bar.getHeight() - extra - host.dp(1) - host.density / 2f, drawn.bottom, 0.01f);
                        if (extra > 0) {
                            assertEquals(where + ": the space under the pill is clear", 0,
                                    Color.alpha(pixels.bitmap.getPixel(host.bar.getWidth() / 2, host.bar.getHeight() - extra / 2)));
                        }
                    }
                    host.capture("lift-" + lift.name().toLowerCase(java.util.Locale.ROOT) + (floating ? "-floating" : "-inset"));
                }
            }
        }
    }

    /**
     * #197: Blur the bars around a reel asks which bar wears the glass, so it leaves the pill alone,
     * floating or not (#221).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aBarSaysWhetherItWearsTheGlass() throws Exception {
        for (boolean floating : new boolean[]{true, false}) {
            try (GlassHost host = new GlassHost(Color.BLACK, floating, GlassHeight.STANDARD, false, false)) {
                assertFalse("not restyled yet", GlassTabBar.wearsGlass(host.bar));
                assertFalse(GlassTabBar.wearsFloatingGlass(host.bar));
                GlassTabBar.apply(host.activity);
                assertTrue(GlassTabBar.wearsGlass(host.bar));
                assertEquals(floating, GlassTabBar.wearsFloatingGlass(host.bar));
            }
        }
        assertFalse(GlassTabBar.wearsGlass(null));
        assertFalse(GlassTabBar.wearsFloatingGlass(null));
    }

    /**
     * #258: with Show content behind the tab bar off, Reels stops above the pill, and the bar round it
     * stayed black with Blur the bars around a reel on. A docked pill shows the reel strip there now, and
     * its own colour when there's none. A floating pill has the reel itself behind it.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aDockedPillShowsTheReelStripRoundItself() throws Exception {
        try {
            for (boolean floating : new boolean[]{false, true}) {
                try (GlassHost host = new GlassHost(Color.BLACK, floating, GlassHeight.STANDARD, false, false)) {
                    GlassTabBar.apply(host.activity);
                    host.layout();
                    GlassTabBar.strip = (canvas, left, top, right, bottom) -> {
                        Paint red = new Paint();
                        red.setColor(Color.RED);
                        canvas.drawRect(left, top, right, bottom, red);
                        return true;
                    };
                    try (Pixels pixels = host.background()) {
                        int corner = pixels.bitmap.getPixel(1, 1);
                        if (floating) assertEquals("nothing round a floating pill", 0, Color.alpha(corner));
                        else assertEquals("the strip round a docked pill", Color.RED, corner);
                    }
                    GlassTabBar.strip = (canvas, left, top, right, bottom) -> false;
                    try (Pixels pixels = host.background()) {
                        int corner = pixels.bitmap.getPixel(1, 1);
                        if (floating) assertEquals(0, Color.alpha(corner));
                        else assertEquals("no strip leaves the bar's own colour", Color.BLACK, corner);
                    }
                }
            }
        } finally {
            GlassTabBar.strip = app.hushgram.extension.instagram.reels.ReelBlurBars::drawStrip;
        }
    }

    /**
     * Glass tab bar turned off without the restart it asks for (#201): the bar still wears the pill, so
     * Instagram's-own-bar slide leaves it to the pill's slide rather than wrapping the window twice.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aBarStillWearingTheGlassGetsNoSecondSlide() {
        try (GlassHost host = new GlassHost(Color.BLACK, false, GlassHeight.STANDARD, false, false)) {
            Settings.GLASS_TAB_BAR_SLIDE.save(true);
            GlassTabBar.apply(host.activity);
            assertTrue(GlassTabBar.wearsGlass(host.bar));
            Window.Callback wrapped = host.activity.getWindow().getCallback();
            Settings.GLASS_TAB_BAR.save(false);
            GlassTabBar.apply(host.activity);
            assertNull(PlainTabSlide.of(host.bar));
            assertSame(wrapped, host.activity.getWindow().getCallback());
        }
    }

    /**
     * #144: while a floating pill shows in the front window, the navigation bar's height Instagram pads
     * its screens by is answered 0, so no strip of Instagram's color is left under the pill, and the
     * pill makes that much more room under itself to stay above the gesture handle. When the pill shows
     * or hides, or its activity comes back, the window's insets go round again so Instagram reads the
     * height anew. Hidden, with another activity in front, or paused, the real height goes through.
     */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aFloatingPillShownInFrontTakesTheNavigationBarsHeight() {
        GlassTabBar.resetForTests();
        HookStatus.clear();
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            int nav = host.dp(24);
            assertEquals("not restyled yet", nav, GlassTabBar.navBarHeight(nav));
            AtomicInteger insets = new AtomicInteger();
            host.activity.getWindow().getDecorView().setOnApplyWindowInsetsListener((view, given) -> {
                insets.incrementAndGet();
                return view.onApplyWindowInsets(given);
            });
            host.controller.pause().resume();
            host.frame();
            int height = host.bar.getLayoutParams().height;
            int under = host.bar.getPaddingBottom();

            assertEquals("shown in front", 0, GlassTabBar.navBarHeight(nav));
            assertEquals("a Reels viewer stops above the slot and the handle", 100 + nav, GlassTabBar.reelsMargin(100));
            host.frame();
            assertEquals("the pill makes room for the handle", height + nav, host.bar.getLayoutParams().height);
            assertEquals("and sits above it", under + nav, host.bar.getPaddingBottom());

            int asked = insets.get();
            host.bar.setVisibility(View.GONE);
            host.frame();
            assertTrue("hidden, the insets went round again", insets.get() > asked);
            assertEquals("hidden", nav, GlassTabBar.navBarHeight(nav));
            assertEquals("Instagram keeps the handle's room itself, so the viewer stops at the slot", 100, GlassTabBar.reelsMargin(100));
            host.frame();
            assertEquals("hidden, no room for the handle", height, host.bar.getLayoutParams().height);

            asked = insets.get();
            host.bar.setVisibility(View.VISIBLE);
            host.frame();
            assertTrue("shown again, the insets went round again", insets.get() > asked);
            assertEquals("shown again", 0, GlassTabBar.navBarHeight(nav));

            // A screen without the bar, in an activity of its own, comes to the front.
            host.controller.pause();
            ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
            try {
                assertEquals("another activity in front", nav, GlassTabBar.navBarHeight(nav));
                assertEquals(100, GlassTabBar.reelsMargin(100));
            } finally {
                other.pause().stop().destroy();
            }
            asked = insets.get();
            host.controller.resume();
            host.frame();
            assertTrue("back in front, the insets went round again", insets.get() > asked);
            assertEquals("back in front", 0, GlassTabBar.navBarHeight(nav));

            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            asked = insets.get();
            assertEquals("paused", nav, GlassTabBar.navBarHeight(nav));
            assertEquals(100, GlassTabBar.reelsMargin(100));
            host.frame();
            assertTrue("paused, the insets went round again", insets.get() > asked);
            assertEquals("paused, no room for the handle", height, host.bar.getLayoutParams().height);

            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(GlassTabBar.COUNT_NAV_BEHIND));
            assertTrue(report, report.contains(GlassTabBar.COUNT_INSETS_AGAIN));
        }
    }

    /**
     * #196: a stopped activity's window stays hidden through its resume, and its screens read the
     * navigation bar's height as they resume. A floating pill set to show in it counts already, so
     * Instagram's copy goes back to the pill's 0 ahead of them. A hidden bar or another activity doesn't.
     */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aFloatingPillCountsWhileItsWindowIsStillHidden() {
        GlassTabBar.resetForTests();
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            host.controller.pause().resume();
            host.frame();
            View decor = host.activity.getWindow().getDecorView();
            decor.setVisibility(View.INVISIBLE);
            assertFalse("its window doesn't show yet", GlassTabBar.floatingBarInFront());
            assertTrue("but the pill is set to float in it", GlassTabBar.floatingBarResuming(host.activity));
            host.bar.setVisibility(View.GONE);
            assertFalse("a hidden bar", GlassTabBar.floatingBarResuming(host.activity));
            host.bar.setVisibility(View.VISIBLE);
            decor.setVisibility(View.VISIBLE);
            ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
            try {
                assertFalse("another activity", GlassTabBar.floatingBarResuming(other.get()));
            } finally {
                other.pause().stop().destroy();
            }
        }
    }

    /**
     * #209, #222: a bottom sheet over a floating pill, such as a reel's comments, sat its comment field
     * by the navigation bar's height Instagram keeps, so it went under the navigation bar. Giving
     * Instagram the real height under a sheet moved every screen under it, and the Reels viewer's video
     * stopped on its cover. Now Instagram keeps the 0, the insets don't go round for a sheet, and the
     * sheet holder and its footer stop the navigation bar's height above the edge while the dimmed
     * backdrop still covers the window. The keyboard over a sheet keeps the 0 too. A margin Instagram
     * gives them itself stays, and anything that hands Instagram the real height takes the room back.
     */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aBottomSheetOverTheFloatingPillStopsAboveTheNavigationBarAndMovesNothingUnder() {
        GlassTabBar.resetForTests();
        HookStatus.clear();
        int holderId = 0x7f0b0122;
        int dimmerId = 0x7f0b0124;
        int full = ViewGroup.LayoutParams.MATCH_PARENT;
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        Integer previousHolder;
        Integer previousDimmer;
        synchronized (ids) {
            previousHolder = ids.put(GlassTabBar.SHEET_HOLDER, holderId);
            previousDimmer = ids.put(GlassTabBar.SHEET_DIMMER, dimmerId);
        }
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            int nav = host.dp(24);
            AtomicInteger insets = new AtomicInteger();
            host.activity.getWindow().getDecorView().setOnApplyWindowInsetsListener((view, given) -> {
                insets.incrementAndGet();
                return view.onApplyWindowInsets(given);
            });
            host.controller.pause().resume();
            host.frame();
            assertEquals("no sheet holder yet", 0, GlassTabBar.navBarHeight(nav));
            assertEquals("a reel stops above the pill's slot and the handle (#221, #280)", nav, GlassTabBar.reelsMargin(0));
            host.frame();
            int height = host.bar.getLayoutParams().height;

            // Instagram's sheets container, laid over the screens and the bar: the dimmed backdrop, the
            // holder with no sheet in it yet, and a footer at the bottom.
            FrameLayout sheets = new FrameLayout(host.activity);
            View dimmer = new View(host.activity);
            dimmer.setId(dimmerId);
            sheets.addView(dimmer, new FrameLayout.LayoutParams(full, full));
            FrameLayout holder = new FrameLayout(host.activity);
            holder.setId(holderId);
            sheets.addView(holder, new FrameLayout.LayoutParams(full, full));
            View footer = new View(host.activity);
            sheets.addView(footer, new FrameLayout.LayoutParams(full, host.dp(40), Gravity.BOTTOM));
            host.root.addView(sheets, new FrameLayout.LayoutParams(full, full));
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals("the holder stops above the navigation bar ahead of a sheet", nav, bottomMargin(holder));
            assertEquals("so does the footer", nav, bottomMargin(footer));
            assertEquals("the backdrop covers the window", 0, bottomMargin(dimmer));

            int asked = insets.get();
            View sheet = new View(host.activity);
            holder.addView(sheet, new FrameLayout.LayoutParams(full, host.dp(80), Gravity.BOTTOM));
            host.frame();
            host.frame();
            assertEquals("a sheet came up, and the insets didn't go round", asked, insets.get());
            assertEquals("under a sheet Instagram keeps the 0", 0, GlassTabBar.navBarHeight(nav));
            assertEquals("the keyboard over the sheet too", 0, GlassTabBar.navBarHeightOverTheKeyboard(nav));
            assertEquals("the Reels viewer under the sheet keeps its size", nav, GlassTabBar.reelsMargin(0));
            host.frame();
            assertEquals("the sheet still stops above the navigation bar", nav, bottomMargin(holder));
            assertEquals("the pill keeps its room under the sheet", height, host.bar.getLayoutParams().height);

            holder.removeView(sheet);
            host.frame();
            assertEquals("the sheet went, and the insets didn't go round", asked, insets.get());
            assertEquals("the keyboard with no sheet gets the height", nav, GlassTabBar.navBarHeightOverTheKeyboard(nav));
            assertEquals("Instagram pads the Reels viewer by it, so the viewer gives up as much (#222)", 0, GlassTabBar.reelsMargin(0));
            host.frame();
            assertEquals("and Instagram keeps the sheets above it itself", 0, bottomMargin(holder));
            assertEquals(0, bottomMargin(footer));
            assertEquals("the keyboard closed", 0, GlassTabBar.navBarHeight(nav));
            assertEquals(nav, GlassTabBar.reelsMargin(0));
            host.frame();
            assertEquals(nav, bottomMargin(holder));

            // A margin Instagram gives the footer itself is left alone.
            ViewGroup.MarginLayoutParams own = (ViewGroup.MarginLayoutParams) footer.getLayoutParams();
            own.bottomMargin = host.dp(7);
            footer.setLayoutParams(own);
            host.frame();
            assertEquals("Instagram's own margin", host.dp(7), bottomMargin(footer));

            host.bar.setVisibility(View.GONE);
            host.frame();
            assertEquals("a screen without the bar gets the height", nav, GlassTabBar.navBarHeight(nav));
            assertEquals(0, GlassTabBar.reelsMargin(0));
            host.frame();
            assertEquals("and the sheets give the room back", 0, bottomMargin(holder));
            assertEquals("full screen, no navigation bar to stop above", 0, GlassTabBar.navBarHeight(0));
            assertEquals(0, GlassTabBar.reelsMargin(0));

            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(GlassTabBar.COUNT_SHEETS_LIFTED));
        } finally {
            synchronized (ids) {
                if (previousHolder == null) ids.remove(GlassTabBar.SHEET_HOLDER);
                else ids.put(GlassTabBar.SHEET_HOLDER, previousHolder);
                if (previousDimmer == null) ids.remove(GlassTabBar.SHEET_DIMMER);
                else ids.put(GlassTabBar.SHEET_DIMMER, previousDimmer);
            }
        }
    }

    private static int bottomMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }

    /**
     * #224: on Android 17 the pill stayed over an open story's reply box, with the navigation bar answered
     * 0 under it. While Instagram's story viewer shows on top in the bar's window the bar is hidden and
     * the real height goes through, and the bar shows again once the story goes. A bar Instagram hides
     * itself, or changes while it's hidden, is left as Instagram has it.
     */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aStoryOnTopHidesThePillAndGetsTheNavigationBarsHeight() {
        GlassTabBar.resetForTests();
        HookStatus.clear();
        int storyId = 0x7f0b0123;
        Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        Integer previous;
        synchronized (ids) { previous = ids.put(GlassTabBar.STORY_ROOT, storyId); }
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            int nav = host.dp(24);
            int full = ViewGroup.LayoutParams.MATCH_PARENT;
            host.controller.pause().resume();
            host.frame();
            assertEquals("no story", 0, GlassTabBar.navBarHeight(nav));

            // The screens' container under the bar, with a story opened in it.
            FrameLayout content = new FrameLayout(host.activity);
            host.root.addView(content, 0, new FrameLayout.LayoutParams(full, full));
            FrameLayout story = new FrameLayout(host.activity);
            story.setId(storyId);
            content.addView(story, new FrameLayout.LayoutParams(full, full));
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals("hidden over the story", View.INVISIBLE, host.bar.getVisibility());
            assertEquals("the story's reply box stops above the navigation bar", nav, GlassTabBar.navBarHeight(nav));

            // A profile opened from the story goes after it in the container, and has the bar.
            View profile = new View(host.activity);
            content.addView(profile, new FrameLayout.LayoutParams(full, full));
            host.frame();
            assertEquals("a screen over the story", View.VISIBLE, host.bar.getVisibility());
            assertEquals(0, GlassTabBar.navBarHeight(nav));
            content.removeView(profile);
            host.frame();
            assertEquals("back on the story", View.INVISIBLE, host.bar.getVisibility());

            content.removeView(story);
            host.frame();
            assertEquals("the story went", View.VISIBLE, host.bar.getVisibility());
            assertEquals(0, GlassTabBar.navBarHeight(nav));

            // Instagram hiding its own bar for a story, as it does on Android 16, is left to it.
            host.bar.setVisibility(View.GONE);
            content.addView(story);
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals("Instagram's own hide", View.GONE, host.bar.getVisibility());
            content.removeView(story);
            host.bar.setVisibility(View.VISIBLE);
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals(View.VISIBLE, host.bar.getVisibility());

            // A change Instagram makes while the bar is hidden over a story stands.
            content.addView(story);
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals(View.INVISIBLE, host.bar.getVisibility());
            host.bar.setVisibility(View.GONE);
            content.removeView(story);
            for (int i = 0; i < 4; i++) host.frame();
            assertEquals("Instagram's change since", View.GONE, host.bar.getVisibility());

            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(GlassTabBar.COUNT_STORY_ASIDE));
        } finally {
            synchronized (ids) {
                if (previous == null) ids.remove(GlassTabBar.STORY_ROOT);
                else ids.put(GlassTabBar.STORY_ROOT, previous);
            }
        }
    }

    /** A pill over Instagram's own place, not floating, leaves the navigation bar's height alone and never asks for insets. */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aPillThatDoesNotFloatLeavesTheNavigationBarsHeight() {
        GlassTabBar.resetForTests();
        HookStatus.clear();
        try (GlassHost host = new GlassHost(Color.BLACK, false, GlassHeight.STANDARD, false, false)) {
            int nav = host.dp(24);
            host.controller.pause().resume();
            host.frame();
            int height = host.bar.getLayoutParams().height;
            assertFalse("nothing to take the height for", GlassTabBar.floatingBarResuming(host.activity));
            assertEquals(nav, GlassTabBar.navBarHeight(nav));
            host.frame();
            assertEquals(height, host.bar.getLayoutParams().height);
            String report = String.join("\n", HookStatus.report());
            assertFalse(report, report.contains(GlassTabBar.COUNT_NAV_BEHIND));
            assertFalse(report, report.contains(GlassTabBar.COUNT_INSETS_AGAIN));
        }
    }

    /**
     * #315: a tab badge's ring goes clear at the width it had, once as usual, then again only while the
     * badge waits to redraw after Instagram strokes it in place, without asking for a frame. The drawable
     * it shares a state with, another background and no background stay as they are.
     */
    @Test
    @Config(sdk = 29)
    public void aBadgesRingGoesClearAndStaysClearWhenInstagramStrokesItAgain() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger asked = new AtomicInteger();
        View badge = new View(context) {
            @Override public void invalidateDrawable(Drawable drawable) {
                asked.incrementAndGet();
                super.invalidateDrawable(drawable);
            }
        };
        GradientDrawable shared = new GradientDrawable();
        shared.setColor(Color.RED);
        shared.setStroke(4, Color.WHITE);
        Drawable dot = shared.getConstantState().newDrawable();
        badge.setBackground(dot);
        badge.layout(0, 0, 40, 40);
        Canvas canvas = new Canvas(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        badge.draw(canvas);
        Set<Drawable> cleared = new HashSet<>();

        assertTrue("a new ring is cleared", GlassTabBar.clearRing(badge, 4, cleared));
        assertEquals(Color.TRANSPARENT, strokeColor(dot));
        assertEquals("the ring keeps its width", 4, (int) ReflectionHelpers.getField(ReflectionHelpers.getField(dot, "mGradientState"), "mStrokeWidth"));
        assertEquals("the shared state keeps its ring", Color.WHITE, strokeColor(shared));

        badge.draw(canvas);
        assertFalse("a badge with nothing to redraw is left alone", GlassTabBar.clearRing(badge, 4, cleared));

        ((GradientDrawable) badge.getBackground()).setStroke(4, Color.WHITE);
        assertTrue("Instagram's stroke asks for the redraw", badge.isDirty());
        int before = asked.get();
        assertFalse("a ring is counted once", GlassTabBar.clearRing(badge, 4, cleared));
        assertEquals("cleared again before the redraw", Color.TRANSPARENT, strokeColor(dot));
        assertEquals("without asking for a frame", before, asked.get());
        assertSame("the badge still hears its drawable", badge, dot.getCallback());

        View plain = new View(context);
        plain.setBackground(new ColorDrawable(Color.RED));
        assertFalse(GlassTabBar.clearRing(plain, 4, cleared));
        assertFalse(GlassTabBar.clearRing(new View(context), 4, cleared));
    }

    private static int strokeColor(Drawable drawable) {
        ColorStateList colors = ReflectionHelpers.getField(ReflectionHelpers.getField(drawable, "mGradientState"), "mStrokeColors");
        return colors.getDefaultColor();
    }

    /** Only a floating pill is lifted, by its step's dp; Standard, no choice and a bar that doesn't float stay put. */
    @Test
    public void onlyAFloatingPillIsLiftedByItsStep() {
        float density = 2.625f;
        assertEquals(0, GlassTabBar.liftPx(GlassLift.STANDARD, true, density));
        assertEquals(Math.round(8 * density), GlassTabBar.liftPx(GlassLift.MORE, true, density));
        assertEquals(Math.round(16 * density), GlassTabBar.liftPx(GlassLift.MOST, true, density));
        for (GlassLift lift : GlassLift.values()) assertEquals(lift.name(), 0, GlassTabBar.liftPx(lift, false, density));
        assertEquals(0, GlassTabBar.liftPx(null, true, density));
        assertEquals(GlassLift.STANDARD, Settings.GLASS_TAB_BAR_LIFT.defaultValue);
    }

    /** Tab bar corners (#144): each step keeps its dp up to half the height, and no choice read is Standard. */
    @Test
    public void cornersKeepTheirDpUpToHalfTheHeight() {
        float density = 2.625f;
        float tall = 50 * density;
        assertEquals(Math.round(12 * density), GlassTabBar.cornerPx(GlassCorners.STANDARD, false, tall, density), 0f);
        assertEquals(Math.round(8 * density), GlassTabBar.cornerPx(GlassCorners.STANDARD, true, tall, density), 0f);
        assertEquals(Math.round(20 * density), GlassTabBar.cornerPx(GlassCorners.ROUNDER, false, tall, density), 0f);
        assertEquals(Math.round(14 * density), GlassTabBar.cornerPx(GlassCorners.ROUNDER, true, tall, density), 0f);
        assertEquals(tall / 2f, GlassTabBar.cornerPx(GlassCorners.ROUND, false, tall, density), 0f);
        assertEquals(tall / 2f, GlassTabBar.cornerPx(GlassCorners.ROUND, true, tall, density), 0f);
        assertEquals("never past half the height", 10f, GlassTabBar.cornerPx(GlassCorners.ROUNDER, false, 20f, density), 0f);
        assertEquals(0f, GlassTabBar.cornerPx(GlassCorners.ROUND, false, -4f, density), 0f);
        assertEquals(Math.round(12 * density), GlassTabBar.cornerPx(null, false, tall, density), 0f);
        assertEquals(GlassCorners.STANDARD, Settings.GLASS_TAB_BAR_CORNERS.defaultValue);
    }

    /**
     * Fully round (#144) gives the pill half circle ends: the spot a 12dp corner fills stays clear and the
     * middle of the end is glass. The selected tab's highlight is as round, so its corner spot stays unlit.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void fullyRoundCornersGiveThePillAndItsHighlightRoundEnds() throws Exception {
        GlassTabBar.animationsOnForTests = false;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            Settings.GLASS_TAB_BAR_CORNERS.save(GlassCorners.ROUND);
            GlassTabBar.apply(host.activity);
            host.layout();
            int outer = GlassTabBar.outerPx(host.bar.getWidth(), host.density);
            int top = host.dp(1);
            try (Pixels plain = host.background()) {
                assertEquals("where a 12dp corner fills, a round end is clear", 0,
                        plain.bitmap.getPixel(outer + host.dp(3), top + host.dp(6)));
                RectF drawn = ReflectionHelpers.getField(host.bar.getBackground(), "rect");
                int middle = Math.round((drawn.top + drawn.bottom) / 2f);
                assertTrue("the round end's middle is glass", Color.red(plain.bitmap.getPixel(outer + host.dp(3), middle)) > 5);
                assertTrue(host.bar.getChildAt(0).performClick());
                try (Pixels active = host.background()) {
                    RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                    int x = Math.round(slot.left) + host.dp(2);
                    int y = Math.round(slot.top) + host.dp(4);
                    assertEquals("an 8dp corner would light this spot; a round one leaves it",
                            Color.red(plain.bitmap.getPixel(x, y)), Color.red(active.bitmap.getPixel(x, y)), 2);
                    int inside = Math.round((slot.top + slot.bottom) / 2f);
                    assertTrue("the highlight itself is lit", Color.red(active.bitmap.getPixel(x, inside))
                            > Color.red(plain.bitmap.getPixel(x, inside)) + 10);
                }
            }
            host.capture("dark-floating-round-selected-0");
        }
    }

    /**
     * Instagram's newer bar marks the selected tab with a dark capsule, as that tab's background (seen on
     * the S22's account). The pill draws its own capsule, so each tab's background comes off under the
     * glass, a ripple holding a capsule's included, and comes off again on the next frame when Instagram
     * puts it back. A bare ripple, a tab's touch feedback, stays.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void theTabsOwnCapsulesComeOffUnderTheGlass() throws Exception {
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            for (int i = 0; i < host.bar.getChildCount(); i++) host.bar.getChildAt(i).setBackgroundColor(0xff25292e);
            host.bar.getChildAt(3).setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), new ColorDrawable(0xff25292e), null));
            RippleDrawable feedback = new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, new ColorDrawable(Color.WHITE));
            host.bar.getChildAt(4).setBackground(feedback);
            GlassTabBar.apply(host.activity);
            host.layout();
            for (int i = 0; i < 4; i++) assertNull("tab " + i, host.bar.getChildAt(i).getBackground());
            assertSame("a bare ripple is the tab's touch feedback", feedback, host.bar.getChildAt(4).getBackground());

            assertTrue(host.bar.getChildAt(2).performClick());
            host.bar.getChildAt(2).setBackgroundColor(0xff25292e);
            host.bar.getViewTreeObserver().dispatchOnPreDraw();
            assertNull("put back by Instagram, it comes off again", host.bar.getChildAt(2).getBackground());
            assertTrue("the click still reached Instagram", host.bar.getChildAt(2).isSelected());
        }
    }

    /**
     * The registry held every restyled bar, its activity and its recorded backdrop for good, since
     * the bar's Glass held the bar (audit 2026-10-12). A destroyed activity's bar leaves it at once,
     * with no backdrop catch-up left waiting and no backdrop kept, round after round.
     */
    @Test
    public void aDestroyedActivitysBarLeavesTheRegistry() {
        for (int round = 0; round < 3; round++) {
            GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false);
            Object pill;
            try {
                GlassTabBar.apply(host.activity);
                assertTrue(registry().containsKey(host.bar));
                pill = host.bar.getBackground();
                arrangeCatchUp(pill);
                // Robolectric draws on no hardware canvas, so no backdrop is ever recorded here. One
                // is planted, so that letting go of it is what's checked.
                ReflectionHelpers.setField(pill, "node", new android.graphics.RenderNode("planted"));
                ReflectionHelpers.setField(pill, "recorded", true);
            } finally {
                host.close();
            }
            assertFalse("a catch-up still waits for a destroyed bar", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));
            assertNull("the backdrop was kept", ReflectionHelpers.getField(pill, "node"));
            assertFalse((boolean) ReflectionHelpers.getField(pill, "recorded"));
        }
    }

    /**
     * Instagram rebuilding its tab bar in a living activity: the new bar is restyled, and the old one
     * isn't held by the registry, so it goes once nothing else holds it.
     */
    @Test
    public void aReplacedBarIsRestyledAndTheOldOneCanBeCollected() throws Exception {
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            GlassTabBar.apply(host.activity);
            arrangeCatchUp(host.bar.getBackground());
            java.lang.ref.WeakReference<View> old = new java.lang.ref.WeakReference<>(host.bar);
            Class<?> pillType = host.bar.getBackground().getClass();

            host.replaceBar();
            GlassTabBar.apply(host.activity);

            assertTrue("the new bar wasn't restyled", registry().containsKey(host.bar));
            assertSame(pillType, host.bar.getBackground().getClass());
            for (int i = 0; i < 50 && old.get() != null; i++) {
                ShadowLooper.idleMainLooper();
                System.gc();
                Thread.sleep(5);
            }
            assertNull("the registry kept the replaced bar", old.get());
        }
    }

    /**
     * A bar taken off its window for a while keeps its glass: the backdrop catch-up waiting on it is
     * dropped, and back on the window it's restyled each frame again.
     */
    @Test
    public void aBarOffItsWindowForAWhileKeepsItsGlass() {
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            GlassTabBar.apply(host.activity);
            Object pill = host.bar.getBackground();
            arrangeCatchUp(pill);

            ViewGroup.LayoutParams params = host.bar.getLayoutParams();
            host.root.removeView(host.bar);
            assertFalse("a catch-up still waits on a bar off its window", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));
            assertTrue("off its window for a while, the bar lost its glass", registry().containsKey(host.bar));

            host.root.addView(host.bar, params);
            host.layout();
            host.bar.setBackground(new ColorDrawable(Color.RED));
            host.bar.getViewTreeObserver().dispatchOnPreDraw();
            assertSame("back on its window, the bar wasn't restyled", pill, host.bar.getBackground());
            arrangeCatchUp(pill);
        }
    }

    /**
     * Slide across the tabs (#136): a finger slid along the pill carries the capsule, centered on the
     * finger, nothing opens while it's down, and lifting it over a tab opens that tab, where the capsule
     * then settles.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aFingerSlidAlongThePillCarriesTheCapsuleAndOpensTheTabItLiftsOver() {
        GlassTabBar.animationsOnForTests = false;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            Settings.GLASS_TAB_BAR_SLIDE.save(true);
            assertTrue(host.bar.getChildAt(0).performClick());
            GlassTabBar.apply(host.activity);
            host.layout();
            Window.Callback callback = host.activity.getWindow().getCallback();
            int[] at = new int[2];
            host.bar.getLocationOnScreen(at);
            float y = at[1] + host.bar.getHeight() / 2f;
            View first = host.bar.getChildAt(0);
            View fourth = host.bar.getChildAt(3);
            float from = (first.getLeft() + first.getRight()) / 2f;
            float to = (fourth.getLeft() + fourth.getRight()) / 2f;
            long down = SystemClock.uptimeMillis();
            send(callback, down, down, MotionEvent.ACTION_DOWN, at[0] + from, y);
            send(callback, down, down + 16, MotionEvent.ACTION_MOVE, at[0] + from + host.dp(30), y);
            send(callback, down, down + 32, MotionEvent.ACTION_MOVE, at[0] + to, y);
            try (Pixels ignored = host.background()) {
                RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                assertEquals("the capsule sits on the finger", to, slot.centerX(), 0.5f);
            }
            assertEquals("nothing opens while the finger is down", 1, host.clicks.get());

            send(callback, down, down + 48, MotionEvent.ACTION_UP, at[0] + to, y);
            assertEquals(2, host.clicks.get());
            assertTrue(fourth.isSelected());
            try (Pixels ignored = host.background()) {
                RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                assertEquals("the capsule settles on the tab", to, slot.centerX(), 0.5f);
            }
        }
    }

    /** Off, the same slide is Instagram's: the window isn't wrapped and the capsule stays on the open tab. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void withTheSwitchOffASlideMovesNothing() {
        GlassTabBar.animationsOnForTests = false;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            Window.Callback before = host.activity.getWindow().getCallback();
            assertTrue(host.bar.getChildAt(0).performClick());
            GlassTabBar.apply(host.activity);
            host.layout();
            assertSame("no watcher without the switch", before, host.activity.getWindow().getCallback());
            View first = host.bar.getChildAt(0);
            int[] at = new int[2];
            host.bar.getLocationOnScreen(at);
            float y = at[1] + host.bar.getHeight() / 2f;
            float from = (first.getLeft() + first.getRight()) / 2f;
            long down = SystemClock.uptimeMillis();
            send(before, down, down, MotionEvent.ACTION_DOWN, at[0] + from, y);
            send(before, down, down + 16, MotionEvent.ACTION_MOVE, at[0] + from + host.dp(200), y);
            try (Pixels ignored = host.background()) {
                RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                assertTrue("the capsule stays on the open tab", slot.centerX() < first.getRight());
            }
            send(before, down, down + 32, MotionEvent.ACTION_CANCEL, at[0] + from + host.dp(200), y);
        }
    }

    @Test
    public void aSlidCapsuleCentersOnTheFingerInsideThePill() {
        assertEquals(150f, GlassTabBar.followLeft(200f, 100f, 10f, 500f), 0f);
        assertEquals("held at the left end", 10f, GlassTabBar.followLeft(20f, 100f, 10f, 500f), 0f);
        assertEquals("held at the right end", 400f, GlassTabBar.followLeft(490f, 100f, 10f, 500f), 0f);
        assertEquals("no room to move", 10f, GlassTabBar.followLeft(200f, 600f, 10f, 500f), 0f);
    }

    /** The liquid look's shapes (#65): Search's circle at the pill's end, and the pill shrinking to a circle at its start. */
    @Test
    public void theLiquidLookSplitsSearchOffAndShrinksThePillToACircle() {
        RectF pill = new RectF(40, 2, 780, 102);
        RectF holder = new RectF();
        RectF circle = new RectF();
        assertTrue(GlassTabBar.splitOffSearch(pill, 16, false, holder, circle));
        assertEquals(new RectF(680, 2, 780, 102), circle);
        assertEquals(new RectF(40, 2, 664, 102), holder);
        assertTrue("right to left, Search goes to the left", GlassTabBar.splitOffSearch(pill, 16, true, holder, circle));
        assertEquals(new RectF(40, 2, 140, 102), circle);
        assertEquals(new RectF(156, 2, 780, 102), holder);
        RectF narrow = new RectF(0, 0, 300, 100);
        assertFalse("no room for two tabs beside the circle", GlassTabBar.splitOffSearch(narrow, 16, false, holder, circle));
        assertEquals(narrow, holder);
        assertTrue(circle.isEmpty());

        RectF from = new RectF(40, 2, 664, 102);
        RectF into = new RectF();
        GlassTabBar.shrunkHolder(from, false, 0f, into);
        assertEquals(from, into);
        GlassTabBar.shrunkHolder(from, false, 0.5f, into);
        assertEquals(new RectF(40, 2, 402, 102), into);
        GlassTabBar.shrunkHolder(from, false, 1f, into);
        assertEquals(new RectF(40, 2, 140, 102), into);
        GlassTabBar.shrunkHolder(from, true, 1f, into);
        assertEquals("right to left, the circle is at the right", new RectF(564, 2, 664, 102), into);

        assertEquals(50f, GlassTabBar.slotMiddle(0f, 400f, 4, 0), 0f);
        assertEquals(350f, GlassTabBar.slotMiddle(0f, 400f, 4, 3), 0f);
    }

    /** Where [tab] shows across the bar: its middle as laid out, plus how far it was moved. */
    private static float shownMiddle(View tab) {
        return (tab.getLeft() + tab.getRight()) / 2f + tab.getTranslationX();
    }

    /**
     * #65: the liquid look puts Search in a round glass circle of its own at the pill's end (the left, right to
     * left), spreads the other tabs over the round pill beside it in their order, and leaves the gap between
     * the two clear. On dark and light glass. Opened, Search gets a round highlight inside its circle.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void theLiquidLookPutsSearchInItsOwnCircleBesideARoundPill() throws Exception {
        GlassTabBar.animationsOnForTests = false;
        for (int base : new int[]{Color.BLACK, Color.WHITE}) {
            for (boolean rtl : new boolean[]{false, true}) {
                try (GlassHost host = new GlassHost(base, true, GlassHeight.STANDARD, rtl, false)) {
                    host.nameSearch(1);
                    Settings.GLASS_TAB_BAR_LIQUID.save(true);
                    assertTrue(host.bar.getChildAt(0).performClick());
                    GlassTabBar.apply(host.activity);
                    host.frame();
                    host.frame();
                    String where = (base == Color.BLACK ? "dark" : "light") + (rtl ? " rtl" : " ltr");
                    Object glass = host.glass();
                    RectF holder = new RectF(ReflectionHelpers.<RectF>getField(glass, "holder"));
                    RectF circle = new RectF(ReflectionHelpers.<RectF>getField(glass, "circle"));
                    assertFalse(where, circle.isEmpty());
                    assertEquals(where + ": a circle", circle.width(), circle.height(), 0.01f);
                    assertTrue(where + ": at the pill's end", rtl ? circle.right < holder.left : circle.left > holder.right);
                    View search = host.bar.getChildAt(1);
                    assertEquals(where + ": Search sits in its circle", circle.centerX(), shownMiddle(search), 1f);
                    java.util.List<View> others = new java.util.ArrayList<>();
                    for (int i : new int[]{0, 2, 3, 4}) others.add(host.bar.getChildAt(i));
                    others.sort((one, other) -> Integer.compare(one.getLeft(), other.getLeft()));
                    float gap = -1f;
                    for (int k = 0; k < others.size(); k++) {
                        float middle = shownMiddle(others.get(k));
                        assertTrue(where + ": tab " + k + " sits on the pill", middle > holder.left && middle < holder.right);
                        if (k > 0) {
                            float step = middle - shownMiddle(others.get(k - 1));
                            assertTrue(where + ": order kept", step > 0f);
                            if (gap >= 0f) assertEquals(where + ": evenly spread", gap, step, 1.5f);
                            gap = step;
                        }
                    }
                    try (Pixels pixels = host.background()) {
                        int y = Math.round(circle.centerY());
                        int clear = base == Color.BLACK ? 1 : 60;
                        int between = Math.round(rtl ? (circle.right + holder.left) / 2f : (holder.right + circle.left) / 2f);
                        assertTrue(where + ": the gap shows what's behind", Color.alpha(pixels.bitmap.getPixel(between, y)) < clear);
                        assertTrue(where + ": the pill is drawn", Color.alpha(pixels.bitmap.getPixel(Math.round(holder.centerX()), y)) > 200);
                        assertTrue(where + ": the circle is drawn", Color.alpha(pixels.bitmap.getPixel(Math.round(circle.centerX()), y)) > 200);
                        int cornerX = Math.round(holder.left) + host.dp(6);
                        int cornerY = Math.round(holder.top) + host.dp(3);
                        assertTrue(where + ": the pill's ends are round, past where a 12dp corner fills",
                                Color.alpha(pixels.bitmap.getPixel(cornerX, cornerY)) < clear);
                    }
                    host.capture("liquid-" + (base == Color.BLACK ? "dark" : "light") + (rtl ? "-rtl" : "-ltr"));

                    assertTrue(search.performClick());
                    try (Pixels ignored = host.background()) {
                        RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                        assertEquals(where + ": the highlight sits in Search's circle", circle.centerX(), slot.centerX(), 1f);
                        assertEquals(where + ": and is round", slot.width(), slot.height(), 1f);
                    }
                }
            }
        }
    }

    /**
     * #65: the compact row keeps the tab you're on, in a circle at the pill's start, and Search in its circle. The
     * other tabs fade and take no touches, the middle shows what's behind, and a slide can't start there. Grown
     * back, every tab is where it was. With Search open there's nothing to shrink to.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void theCompactRowKeepsTheOpenTabAndSearchAndGrowsBackWhole() throws Exception {
        GlassTabBar.animationsOnForTests = false;
        for (boolean rtl : new boolean[]{false, true}) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, rtl, false)) {
                host.nameSearch(1);
                Settings.GLASS_TAB_BAR_LIQUID.save(true);
                View reels = host.bar.getChildAt(3);
                View search = host.bar.getChildAt(1);
                assertTrue(reels.performClick());
                GlassTabBar.apply(host.activity);
                host.frame();
                host.frame();
                String where = rtl ? "rtl" : "ltr";
                Object glass = host.glass();
                RectF holder = new RectF(ReflectionHelpers.<RectF>getField(glass, "holder"));
                RectF circle = new RectF(ReflectionHelpers.<RectF>getField(glass, "circle"));
                float[] spread = new float[host.bar.getChildCount()];
                for (int i = 0; i < spread.length; i++) spread[i] = shownMiddle(host.bar.getChildAt(i));

                GlassScrollHide.Shrinker shrinker = (GlassScrollHide.Shrinker) glass;
                assertTrue(shrinker.shrink(true, false));
                host.frame();
                float size = holder.height();
                float start = rtl ? holder.right - size / 2f : holder.left + size / 2f;
                assertEquals(where + ": the open tab sits in the circle at the start", start, shownMiddle(reels), 1f);
                assertEquals(where + ": Search stays in its circle", circle.centerX(), shownMiddle(search), 1f);
                for (int i : new int[]{0, 2, 4}) {
                    View tab = host.bar.getChildAt(i);
                    assertEquals(where + ": tab " + i + " faded", 0f, tab.getAlpha(), 0f);
                    assertEquals(where + ": tab " + i + " takes no touch", View.INVISIBLE, tab.getVisibility());
                }
                assertFalse(where + ": no slide on the compact row", ((GlassTabSlide.Host) glass).onPill(start, holder.centerY()));
                try (Pixels pixels = host.background()) {
                    int y = Math.round(holder.centerY());
                    assertEquals(where + ": the middle shows what's behind", 0,
                            Color.alpha(pixels.bitmap.getPixel(Math.round(holder.centerX()), y)));
                    assertTrue(where + ": the open tab's circle is drawn", Color.alpha(pixels.bitmap.getPixel(Math.round(start), y)) > 200);
                    assertTrue(where + ": Search's circle is drawn", Color.alpha(pixels.bitmap.getPixel(Math.round(circle.centerX()), y)) > 200);
                }
                host.capture("liquid-compact-" + where);

                assertTrue(shrinker.shrink(false, false));
                host.frame();
                for (int i = 0; i < spread.length; i++) {
                    View tab = host.bar.getChildAt(i);
                    assertEquals(where + ": tab " + i + " back in place", spread[i], shownMiddle(tab), 1f);
                    assertEquals(1f, tab.getAlpha(), 0f);
                    assertEquals(View.VISIBLE, tab.getVisibility());
                }
                assertTrue(search.performClick());
                host.frame();
                assertFalse(where + ": Search open, nothing to shrink to", shrinker.shrink(true, false));
            }
        }
    }

    /** Off, or paused after it showed, the liquid look moves no tab, and the bar is the floating pill of before. */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void offOrPausedTheLiquidLookLeavesEveryTabWhereItWas() {
        GlassTabBar.animationsOnForTests = false;
        for (boolean on : new boolean[]{false, true}) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
                host.nameSearch(1);
                Settings.GLASS_TAB_BAR_LIQUID.save(on);
                assertTrue(host.bar.getChildAt(0).performClick());
                GlassTabBar.apply(host.activity);
                host.frame();
                host.frame();
                GlassScrollHide.Shrinker shrinker = (GlassScrollHide.Shrinker) host.glass();
                if (on) {
                    assertTrue("on, Search moved", host.bar.getChildAt(1).getTranslationX() != 0f);
                    assertTrue(shrinker.shrink(true, true));
                    host.frame();
                    BaseSettings.PAUSED.save(true);
                    PauseForTests.pause(HushgramPause.Reason.SWITCH);
                    host.frame();
                }
                for (int i = 0; i < host.bar.getChildCount(); i++) {
                    View tab = host.bar.getChildAt(i);
                    assertEquals((on ? "paused" : "off") + ": tab " + i, 0f, tab.getTranslationX(), 0f);
                    assertEquals(1f, tab.getAlpha(), 0f);
                    assertEquals(View.VISIBLE, tab.getVisibility());
                }
                assertFalse("nothing shrinks", shrinker.shrink(true, true));
                BaseSettings.PAUSED.save(false);
                PauseForTests.resume();
            }
        }
    }

    /**
     * Keep the pill full size (#256): with the liquid look on, a scroll down still shrinks the bar unless the
     * switch is on, and then nothing watches the scroll at all while the slide-away and Reels wait are off.
     */
    @Test
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void keepThePillFullSizeLeavesTheScrollAlone() {
        GlassTabBar.animationsOnForTests = false;
        for (boolean keep : new boolean[]{false, true}) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
                host.nameSearch(1);
                Settings.GLASS_TAB_BAR_LIQUID.save(true);
                Settings.GLASS_TAB_BAR_KEEP_SIZE.save(keep);
                GlassTabBar.apply(host.activity);
                host.frame();
                GlassScrollHide hide = ReflectionHelpers.getField(host.glass(), "scrollHide");
                if (keep) {
                    assertNull("kept full size, no scroll watcher", hide);
                } else {
                    assertNotNull("liquid, the scroll shrinks the bar", hide);
                    assertSame(host.glass(), ReflectionHelpers.getField(hide, "shrinker"));
                }
            }
        }
    }

    private static void send(Window.Callback callback, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try {
            callback.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static Map<View, ?> registry() {
        return ReflectionHelpers.getStaticField(GlassTabBar.class, "applied");
    }

    /** Has the pill arrange a backdrop catch-up, as a frame drawn just after a recording does. */
    private static void arrangeCatchUp(Object pill) {
        ReflectionHelpers.setField(pill, "recorded", true);
        ReflectionHelpers.setField(pill, "lastRecorded", android.os.SystemClock.uptimeMillis());
        ReflectionHelpers.callInstanceMethod(pill, "recordIfDue");
        assertTrue("no catch-up arranged", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));
    }

    /** A native host strip. The tab bar and its listeners remain the host's views. */
    private static final class GlassHost implements AutoCloseable {
        private static final int BAR_ID = 0x7f0b0120;
        private static final int SEARCH_ID = 0x7f0b0121;
        private Integer previousSearchId;
        private boolean searchNamed;
        final ActivityController<Activity> controller;
        final Activity activity;
        final FrameLayout root;
        LinearLayout bar;
        final float density;
        final AtomicInteger clicks = new AtomicInteger();
        Runnable barInvalidated;
        private final Integer previousId;
        private final int base;
        private final GlassTabBar.Watcher watcher = new GlassTabBar.Watcher();

        GlassHost(int base, boolean floating, GlassHeight height, boolean rtl, boolean blur) {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            Settings.GLASS_TAB_BAR.save(true);
            Settings.GLASS_TAB_BAR_FLOAT.save(floating);
            Settings.GLASS_TAB_BAR_HEIGHT.save(height);
            Settings.GLASS_TAB_BAR_OPACITY.save(100);
            Settings.GLASS_TAB_BAR_BLUR.save(blur);
            Settings.GLASS_TAB_BAR_HAPTICS.save(false);
            Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
            Settings.GLASS_TAB_BAR_SLIDE.save(false);
            Settings.GLASS_TAB_BAR_LIQUID.save(false);
            Settings.GLASS_TAB_BAR_KEEP_SIZE.save(false);
            Settings.GLASS_TAB_BAR_REFRACT.save(false);
            controller = Robolectric.buildActivity(Activity.class).setup();
            activity = controller.get();
            activity.getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
            density = activity.getResources().getDisplayMetrics().density;
            root = new FrameLayout(activity);
            root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            root.setBackgroundColor(base == Color.BLACK ? 0xff0c1014 : 0xffeff2f5);
            this.base = base;
            bar = newBar();
            root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM));
            activity.setContentView(root);
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) { previousId = ids.put(GlassTabBar.TAB_BAR, BAR_ID); }
            // Production's own lifecycle watcher, so a destroyed activity lets go of its bar the way it
            // does in Instagram. Added after setup, so it doesn't restyle the bar before a test asks.
            activity.getApplication().registerActivityLifecycleCallbacks(watcher);
            layout();
        }

        private LinearLayout newBar() {
            LinearLayout made = new LinearLayout(activity) {
                @Override public void invalidate() {
                    super.invalidate();
                    if (barInvalidated != null) barInvalidated.run();
                }
            };
            made.setId(BAR_ID);
            made.setOrientation(LinearLayout.HORIZONTAL);
            made.setBackgroundColor(base);
            int[] icons = {android.R.drawable.ic_menu_view, android.R.drawable.ic_menu_search,
                    android.R.drawable.ic_input_add, android.R.drawable.ic_media_play, android.R.drawable.ic_menu_myplaces};
            String[] labels = {"Home", "Search", "Create", "Reels", "Profile"};
            for (int i = 0; i < icons.length; i++) {
                ImageView tab = new ImageView(activity);
                tab.setImageResource(icons[i]);
                tab.setContentDescription(labels[i]);
                tab.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                        new int[]{0xffcb2861, base == Color.BLACK ? Color.WHITE : Color.BLACK}));
                tab.setPadding(dp(12), dp(10), dp(12), dp(10));
                tab.setOnClickListener(view -> {
                    clicks.incrementAndGet();
                    for (int child = 0; child < bar.getChildCount(); child++) bar.getChildAt(child).setSelected(bar.getChildAt(child) == view);
                });
                made.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            return made;
        }

        /** Swaps a new bar in where the bar was, the way Instagram can rebuild its tab bar in a living activity. */
        void replaceBar() {
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            int at = root.indexOfChild(bar);
            root.removeView(bar);
            bar = newBar();
            root.addView(bar, at, params);
            layout();
        }

        int dp(int value) { return Math.round(value * density); }

        /** Gives the tab at [index] the id Instagram's Search tab has, as the liquid look looks it up. */
        void nameSearch(int index) {
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) {
                if (!searchNamed) previousSearchId = ids.put(GlassTabBar.SEARCH_TAB, SEARCH_ID);
            }
            searchNamed = true;
            bar.getChildAt(index).setId(SEARCH_ID);
        }

        /** The glass restyling this host's bar. */
        Object glass() {
            Object held = registry().get(bar);
            Object glass = held == null ? null : ((java.lang.ref.WeakReference<?>) held).get();
            assertTrue("the bar wasn't restyled", glass != null);
            return glass;
        }

        /** One frame: the bar's pre-draw, what it posted and the window's own pass, then the test's layout. */
        void frame() {
            bar.getViewTreeObserver().dispatchOnPreDraw();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
            layout();
        }

        void layout() {
            ShadowLooper.idleMainLooper();
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(104), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, dp(411), dp(104));
        }

        /** Draws the bar's background into a canvas recorded for the GPU, the way the bar draws on a phone. */
        void recordBackground() {
            android.graphics.RenderNode target = new android.graphics.RenderNode("target");
            target.setPosition(0, 0, bar.getWidth(), bar.getHeight());
            Canvas canvas = target.beginRecording(bar.getWidth(), bar.getHeight());
            try {
                bar.getBackground().setBounds(0, 0, bar.getWidth(), bar.getHeight());
                bar.getBackground().draw(canvas);
            } finally {
                target.endRecording();
            }
        }

        Pixels background() {
            Pixels pixels = new Pixels(bar.getWidth(), bar.getHeight());
            bar.getBackground().setBounds(0, 0, bar.getWidth(), bar.getHeight());
            bar.getBackground().draw(new Canvas(pixels.bitmap));
            return pixels;
        }

        void capture(String name) throws Exception {
            try (Pixels pixels = new Pixels(root.getWidth(), root.getHeight())) {
                root.draw(new Canvas(pixels.bitmap));
                String output = System.getenv("HUSHGRAM_UI_CAPTURES");
                if (output == null || output.isEmpty()) output = System.getProperty("HUSHGRAM_UI_CAPTURES");
                if (output == null || output.isEmpty()) return;
                File directory = new File(output, "overlays");
                assertTrue(directory.mkdirs() || directory.isDirectory());
                try (FileOutputStream stream = new FileOutputStream(new File(directory, "glass-" + name + ".png"))) {
                    assertTrue(pixels.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
            }
        }

        @Override public void close() {
            barInvalidated = null;
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) {
                if (previousId == null) ids.remove(GlassTabBar.TAB_BAR);
                else ids.put(GlassTabBar.TAB_BAR, previousId);
                if (searchNamed && previousSearchId == null) ids.remove(GlassTabBar.SEARCH_TAB);
                else if (searchNamed) ids.put(GlassTabBar.SEARCH_TAB, previousSearchId);
            }
            controller.close();
            activity.getApplication().unregisterActivityLifecycleCallbacks(watcher);
            // No cleanup here takes the bar out: the destroyed activity's watcher has to.
            assertFalse("a destroyed activity's bar stayed in the registry", registry().containsKey(bar));
        }
    }

    private static final class Pixels implements AutoCloseable {
        final Bitmap bitmap;
        Pixels(int width, int height) { bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); }
        @Override public void close() { bitmap.recycle(); }
    }

    /**
     * A missing shadow or content view is looked for again only every RESEARCH_MS, and a run of misses
     * asks for a few redraws so a view that turns up after a screen switch is found without anything
     * else drawing the bar, never more than MISS_REDRAWS until both views are found again.
     */
    @Test
    public void missesAreLookedForAgainOnAScheduleWithAFewRedraws() {
        GlassTabBar.Misses misses = new GlassTabBar.Misses();
        long now = 1_000_000;
        assertTrue("nothing missed yet", misses.lookAgain(now));
        for (int i = 0; i < GlassTabBar.MISS_REDRAWS; i++) {
            assertTrue("miss " + i + " asks for a redraw", misses.looked(now, true, false));
            assertFalse("not again within RESEARCH_MS", misses.lookAgain(now + GlassTabBar.RESEARCH_MS - 1));
            // A draw in between that didn't look must not restart the count.
            assertFalse(misses.looked(now + 1, false, false));
            now += GlassTabBar.RESEARCH_MS;
            assertTrue(misses.lookAgain(now));
        }
        assertFalse("a bar that never has the view stops asking", misses.looked(now, true, false));
        assertFalse(misses.looked(now + 1, false, true));
        assertTrue("finding both starts a new run", misses.looked(now + GlassTabBar.RESEARCH_MS, true, false));
    }

    @Test
    public void blackAndWhiteBarsReadAsDarkAndLight() {
        assertTrue(GlassTabBar.isDark(Color.BLACK));
        assertTrue(GlassTabBar.isDark(0xff0c1014));
        assertFalse(GlassTabBar.isDark(Color.WHITE));
    }

    @Test
    public void darkGlassIsTheBarsOwnDarkUnderHalfOverTheBlurAndLiftedWithout() {
        int blurred = GlassTabBar.tint(Color.BLACK, true);
        assertEquals(0x70, Color.alpha(blurred));
        assertEquals(0, Color.red(blurred));
        int plain = GlassTabBar.tint(Color.BLACK, false);
        assertEquals(0xe6, Color.alpha(plain));
        assertTrue(Color.red(plain) > 0);
    }

    @Test
    public void lightGlassKeepsItsColourAndIsMoreOpaqueWithoutBlur() {
        assertEquals(0xffffff, GlassTabBar.tint(Color.WHITE, true) & 0xffffff);
        assertTrue(Color.alpha(GlassTabBar.tint(Color.WHITE, false)) > Color.alpha(GlassTabBar.tint(Color.WHITE, true)));
    }

    @Test
    public void highlightAndEdgeAreWhiteOnDarkAndGreyOrBlackOnLight() {
        assertEquals(0xff, Color.red(GlassTabBar.highlight(Color.BLACK)));
        assertEquals(0x78, Color.red(GlassTabBar.highlight(Color.WHITE)));
        assertEquals(0xff, Color.red(GlassTabBar.edge(Color.BLACK)));
        assertEquals(0x00, Color.red(GlassTabBar.edge(Color.WHITE)));
    }

    @Test
    public void theStandardOpacityIsExactlyTodaysTint() {
        assertEquals(100, (int) Settings.GLASS_TAB_BAR_OPACITY.defaultValue);
        assertEquals(1f, GlassTabBar.opacityScale(), 0f);
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                assertEquals(GlassTabBar.tint(base, blurred), GlassTabBar.tint(base, blurred, GlassTabBar.opacityScale()));
            }
        }
        assertEquals(0x70, Color.alpha(GlassTabBar.tint(Color.BLACK, true, 1f)));
        assertEquals(0xa6, Color.alpha(GlassTabBar.tint(Color.WHITE, true, 1f)));
        assertEquals(0xe6, Color.alpha(GlassTabBar.tint(Color.WHITE, false, 1f)));
    }

    /** #391: every notch of the opacity slider is at least as solid as the one before and stays a real alpha. */
    @Test
    public void moreOpacityIsNeverLessSolidAndStaysInRange() {
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                int last = -1;
                for (int percent = Settings.GLASS_TAB_BAR_OPACITY.minimum(); percent <= Settings.GLASS_TAB_BAR_OPACITY.maximum(); percent += 5) {
                    int alpha = Color.alpha(GlassTabBar.tint(base, blurred, percent / 100f));
                    assertTrue(alpha >= 0 && alpha <= 255);
                    assertTrue(percent + " " + blurred, alpha >= last);
                    last = alpha;
                }
                assertEquals("none at all is see-through", 0, Color.alpha(GlassTabBar.tint(base, blurred, 0f)));
                assertTrue(Color.alpha(GlassTabBar.tint(base, blurred, 0.5f))
                        < Color.alpha(GlassTabBar.tint(base, blurred, 1f)));
            }
        }
        // The far end never overflows the alpha byte, and the steps older builds saved still draw what they drew.
        assertEquals(255, Color.alpha(GlassTabBar.tint(Color.WHITE, false, 1.5f)));
        assertEquals(Integer.valueOf(50), GlassOpacity.percentOf("CLEAR"));
        assertEquals(Integer.valueOf(75), GlassOpacity.percentOf("LIGHT"));
        assertEquals(Integer.valueOf(100), GlassOpacity.percentOf("STANDARD"));
        assertEquals(Integer.valueOf(135), GlassOpacity.percentOf("FROSTED"));
        assertNull(GlassOpacity.percentOf("SOLID"));
        assertNull(GlassOpacity.percentOf(null));
    }

    /** #391: the bar reads the slider on every draw, so a new spot shows without a restart, and the range holds. */
    @Test
    public void theOpacitySliderIsReadLiveAndKeptInRange() {
        assertFalse(Settings.GLASS_TAB_BAR_OPACITY.rebootApp);
        Settings.GLASS_TAB_BAR_OPACITY.save(40);
        assertEquals(0.4f, GlassTabBar.opacityScale(), 0.0001f);
        Settings.GLASS_TAB_BAR_OPACITY.save(500);
        assertEquals(150, (int) Settings.GLASS_TAB_BAR_OPACITY.get());
        Settings.GLASS_TAB_BAR_OPACITY.save(-20);
        assertEquals(0, (int) Settings.GLASS_TAB_BAR_OPACITY.get());
    }

    @Test
    public void theStandardHeightChangesNothing() {
        float density = 2.625f;
        int bar = Math.round(52 * density);
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.STANDARD, bar, 3, density));
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.TALL, bar, 3, density));
        assertEquals(bar, GlassTabBar.barHeightPx(GlassHeight.STANDARD, bar, density));
        assertEquals(bar, GlassTabBar.barHeightPx(GlassHeight.COMPACT, bar, density));
    }

    @Test
    public void aCompactPillIsSlimmerButNeverShorterThanFortyDp() {
        float density = 3f;
        int gap = 3;
        // A 52dp bar has room to lose 4dp a side: the pill goes from 50dp to 42dp.
        int bar = Math.round(52 * density);
        int inset = GlassTabBar.pillInsetPx(GlassHeight.COMPACT, bar, gap, density);
        assertEquals(Math.round(4 * density), inset);
        assertTrue(bar - 2 * gap - 2 * inset >= 40 * density);
        // A 44dp bar can only lose what keeps the pill at 40dp, and a 40dp one loses nothing.
        for (int dp = 20; dp <= 90; dp++) {
            int height = Math.round(dp * density);
            int slim = GlassTabBar.pillInsetPx(GlassHeight.COMPACT, height, gap, density);
            assertTrue(dp + "dp", slim >= 0 && slim <= Math.round(4 * density));
            int pill = height - 2 * gap - 2 * slim;
            assertTrue(dp + "dp", slim == 0 || pill >= 40 * density - 1);
        }
        assertEquals(0, GlassTabBar.pillInsetPx(GlassHeight.COMPACT, Math.round(40 * density), gap, density));
    }

    @Test
    public void aTallBarGrowsBy8dpUpToSeventyTwo() {
        float density = 2f;
        assertEquals(Math.round(60 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(52 * density), density));
        assertEquals(Math.round(72 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(68 * density), density));
        // A bar already taller than the limit is left as Instagram made it.
        assertEquals(Math.round(80 * density), GlassTabBar.barHeightPx(GlassHeight.TALL, Math.round(80 * density), density));
        for (int dp = 30; dp <= 100; dp++) {
            int original = Math.round(dp * density);
            int tall = GlassTabBar.barHeightPx(GlassHeight.TALL, original, density);
            assertTrue(tall >= original && tall <= Math.max(original, Math.round(72 * density)));
        }
    }

    /** A 411dp phone (1080px at 2.625) keeps 5% clear each side: a pill about 90% as wide as the screen. */
    @Test
    public void aPhoneKeepsFivePercentClearEachSide() {
        int outer = GlassTabBar.outerPx(1080, 2.625f);
        assertEquals(54, outer, 1);
        assertEquals(0.90f, (1080 - 2f * outer) / 1080f, 0.01f);
    }

    @Test
    public void aNarrowScreenKeepsAtLeastSixteenDp() {
        assertEquals(Math.round(16 * 3f), GlassTabBar.outerPx(200, 3f));
    }

    @Test
    public void aWideScreenNeverGrowsThePillPastFourHundredTwentyDp() {
        float density = 2f;
        int width = 2400;
        int outer = GlassTabBar.outerPx(width, density);
        assertEquals(420 * density, width - 2f * outer, 1f);
    }

    /** With the phone's animations off, a slide that starts now is already over, so the capsule jumps. */
    @Test
    public void withAnimationsOffTheCapsuleJumpsToTheNewTab() {
        try {
            GlassTabBar.animationsOnForTests = false;
            assertEquals(1f, GlassTabBar.slideProgress(1000L - GlassTabBar.slideStartAt(1000L)), 0f);
            GlassTabBar.animationsOnForTests = true;
            assertEquals(0f, GlassTabBar.slideProgress(1000L - GlassTabBar.slideStartAt(1000L)), 0f);
        } finally {
            GlassTabBar.animationsOnForTests = null;
        }
    }

    /**
     * #289, #391: the blur slider starts at exactly the radius the glass has always used (18dp, worked out
     * at a quarter size) at any density, each notch reaches at least as far as the one before, and the
     * steps older builds saved keep the radius they drew.
     */
    @Test
    public void blurRadiusStartsAtTodaysRadiusAndGrowsWithTheSlider() {
        IntegerSetting slider = Settings.GLASS_TAB_BAR_BLUR_RADIUS;
        assertEquals(18, GlassTabBar.BLUR_DP);
        assertEquals(GlassTabBar.BLUR_DP, (int) slider.defaultValue);
        assertFalse("the radius is read on every draw", slider.rebootApp);
        assertEquals(2, slider.minimum());
        assertEquals(48, slider.maximum());
        float quarter = 1f / GlassTabBar.DOWNSAMPLE;
        for (float density : new float[]{1f, 1.5f, 2.625f, 3f, 3.5f}) {
            float todays = Math.max(1f, Math.round(GlassTabBar.BLUR_DP * density) * quarter);
            assertEquals("standard at " + density, todays, GlassBlur.effectRadius(GlassTabBar.BLUR_DP, density, quarter), 0f);
            assertEquals(Math.round(GlassTabBar.BLUR_DP * density), GlassBlur.radiusPx(GlassTabBar.BLUR_DP, density));
            int last = -1;
            for (int dp = slider.minimum(); dp <= slider.maximum(); dp++) {
                int px = GlassBlur.radiusPx(dp, density);
                assertTrue(dp + "dp at " + density, px >= last);
                last = px;
            }
            assertTrue(GlassBlur.radiusPx(slider.minimum(), density) < GlassBlur.radiusPx(GlassTabBar.BLUR_DP, density));
            assertTrue(GlassBlur.radiusPx(GlassTabBar.BLUR_DP, density) < GlassBlur.radiusPx(slider.maximum(), density));
        }
        assertEquals(Integer.valueOf(9), GlassBlur.dpOf("LIGHT"));
        assertEquals(Integer.valueOf(18), GlassBlur.dpOf("STANDARD"));
        assertEquals(Integer.valueOf(30), GlassBlur.dpOf("STRONG"));
        assertNull(GlassBlur.dpOf("HEAVY"));
        assertNull(GlassBlur.dpOf(null));
        assertEquals(0, GlassBlur.radiusPx(-4, 3f));
        assertEquals("never under one pixel", 1f, GlassBlur.effectRadius(slider.minimum(), 0.1f, 0.25f), 0f);
    }

    /** #289: the bubble swells to about 1.08 mid-slide, settles back to exactly 1, and a finger's size eases out. */
    @Test
    public void theBubbleSwellsWhileItMovesAndSettlesAtOne() {
        assertEquals(1f, GlassTabBar.bubbleScale(0, 1f), 0f);
        assertEquals(1f + GlassTabBar.BUBBLE_SWELL, GlassTabBar.bubbleScale(GlassTabBar.SLIDE_MS / 2, 1f), 0.001f);
        assertEquals(1f, GlassTabBar.bubbleScale(GlassTabBar.SLIDE_MS, 1f), 0f);
        assertEquals(1f, GlassTabBar.bubbleScale(GlassTabBar.SLIDE_MS * 4, 1.08f), 0f);
        assertEquals(1f, GlassTabBar.bubbleScale(-30, 1f), 0f);
        assertEquals("a released finger doesn't pop", 1.08f, GlassTabBar.bubbleScale(0, 1.08f), 0.001f);
        for (long t = 0; t < GlassTabBar.SLIDE_MS; t += 20) {
            float now = GlassTabBar.bubbleScale(t, 1.08f);
            assertTrue("never above the swell", now <= 1.0801f);
            assertTrue("never under 1", now >= 1f);
        }
    }

    private static void settle(Object pill, long ago) {
        ReflectionHelpers.setField(pill, "slideStart", android.view.animation.AnimationUtils.currentAnimationTimeMillis() - ago);
    }

    /**
     * #289: with the liquid look on, the selected tab's capsule is a bubble: bigger than at rest while it
     * slides between tabs, exactly the rest size once it settles, lit at its top edge and rimmed. With the
     * liquid look off the capsule is flat and never changes size or draws the rim. On dark and light glass,
     * on Android 10, where there is no lens, so the rim and gradient are all the bubble has.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void theLiquidLooksCapsuleIsABubbleThatSwellsWhileItMoves() {
        GlassTabBar.animationsOnForTests = true;
        for (int base : new int[]{Color.BLACK, Color.WHITE}) {
            for (boolean liquid : new boolean[]{true, false}) {
                try (GlassHost host = new GlassHost(base, true, GlassHeight.STANDARD, false, false)) {
                    String where = (base == Color.BLACK ? "dark" : "light") + (liquid ? " liquid" : " flat");
                    Settings.GLASS_TAB_BAR_LIQUID.save(liquid);
                    assertTrue(host.bar.getChildAt(0).performClick());
                    GlassTabBar.apply(host.activity);
                    host.frame();
                    Object pill = host.bar.getBackground();
                    RectF capsule = ReflectionHelpers.getField(pill, "capsule");
                    try (Pixels ignored = host.background()) { }
                    settle(pill, GlassTabBar.SLIDE_MS * 4);
                    try (Pixels ignored = host.background()) { }
                    float restHeight = capsule.height();
                    assertEquals(where + ": settled", 1f, (float) ReflectionHelpers.getField(pill, "bubbleNow"), 0f);

                    assertTrue(host.bar.getChildAt(3).performClick());
                    try (Pixels ignored = host.background()) { }
                    settle(pill, GlassTabBar.SLIDE_MS / 2);
                    try (Pixels pixels = host.background()) {
                        float now = ReflectionHelpers.getField(pill, "bubbleNow");
                        if (liquid) {
                            assertEquals(where + ": swollen mid-slide", 1.08f, now, 0.01f);
                            assertTrue(where + ": taller", capsule.height() > restHeight * 1.05f);
                            assertTrue(where + ": the rim and gradient were made", (int) ReflectionHelpers.getField(pill, "bubbleShade") != 0);
                            if (base == Color.BLACK) {
                                int x = Math.round(capsule.centerX());
                                int top = Color.red(pixels.bitmap.getPixel(x, Math.round(capsule.top) + host.dp(3)));
                                int bottom = Color.red(pixels.bitmap.getPixel(x, Math.round(capsule.bottom) - host.dp(6)));
                                assertTrue(where + ": lit at the top edge (" + top + " vs " + bottom + ")", top > bottom + 15);
                            }
                        } else {
                            assertEquals(where + ": flat stays 1", 1f, now, 0f);
                            assertEquals(where + ": same height", restHeight, capsule.height(), 0.5f);
                            assertEquals(where + ": no rim", 0, (int) ReflectionHelpers.getField(pill, "bubbleShade"));
                        }
                    }
                    settle(pill, GlassTabBar.SLIDE_MS * 4);
                    try (Pixels ignored = host.background()) { }
                    assertEquals(where + ": settled again", 1f, (float) ReflectionHelpers.getField(pill, "bubbleNow"), 0f);
                    assertEquals(where + ": back to its rest height", restHeight, capsule.height(), 0.5f);
                }
            }
        }
    }

    /** #289: a finger sliding the liquid bubble holds it swollen, and the slide after the lift starts from that size. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aFingerHoldsTheBubbleSwollen() {
        GlassTabBar.animationsOnForTests = true;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, false)) {
            Settings.GLASS_TAB_BAR_LIQUID.save(true);
            assertTrue(host.bar.getChildAt(0).performClick());
            GlassTabBar.apply(host.activity);
            host.frame();
            Object pill = host.bar.getBackground();
            Object glass = host.glass();
            ReflectionHelpers.setField(glass, "dragX", host.bar.getWidth() / 2f);
            try (Pixels ignored = host.background()) { }
            assertEquals(1.08f, (float) ReflectionHelpers.getField(pill, "bubbleNow"), 0.001f);
            ReflectionHelpers.setField(glass, "dragX", Float.NaN);
            try (Pixels ignored = host.background()) { }
            assertEquals("the lift starts the slide at the held size", 1.08f, (float) ReflectionHelpers.getField(pill, "bubbleFrom"), 0.001f);
            assertTrue((float) ReflectionHelpers.getField(pill, "bubbleNow") > 1.05f);
        }
    }

    /**
     * #289: the lens only runs on Android 13 and newer. Before, it answers false without trying, and the bubble
     * has its rim and gradient alone.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29)
    public void theLensDoesNothingBeforeAndroid13() {
        GlassLens lens = new GlassLens();
        android.graphics.RenderNode backdrop = new android.graphics.RenderNode("backdrop");
        backdrop.setPosition(0, 0, 40, 40);
        android.graphics.RenderNode target = new android.graphics.RenderNode("target");
        Canvas canvas = target.beginRecording(200, 100);
        try {
            assertFalse(GlassLens.supported());
            assertFalse(lens.paint(canvas, backdrop, 0f, 0f, 4f, 200, 100, new RectF(20, 20, 80, 60), 20f));
            assertEquals(1, lens.asked);
            assertEquals(0, lens.painted);
        } finally {
            target.endRecording();
        }
    }

    /**
     * #289: on Android 13 the lens paints into a recording canvas, or, where this machine can't run the shader,
     * turns itself off for good and answers false with nothing thrown. A canvas that isn't recorded for the GPU
     * and a missing backdrop get no lens either way.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void theLensRunsOnAndroid13AndFailsQuietly() {
        GlassLens lens = new GlassLens();
        android.graphics.RenderNode backdrop = new android.graphics.RenderNode("backdrop");
        backdrop.setPosition(0, 0, 40, 40);
        android.graphics.RenderNode target = new android.graphics.RenderNode("target");
        assertTrue(GlassLens.supported());
        Canvas bitmapCanvas = new Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888));
        assertFalse("a canvas that isn't recorded for the GPU gets no lens",
                lens.paint(bitmapCanvas, backdrop, 0f, 0f, 4f, 200, 100, new RectF(20, 20, 80, 60), 20f));
        Canvas canvas = target.beginRecording(200, 100);
        try {
            assertFalse("no backdrop, no lens", lens.paint(canvas, null, 0f, 0f, 4f, 200, 100, new RectF(20, 20, 80, 60), 20f));
            boolean drew = lens.paint(canvas, backdrop, 0f, 0f, 4f, 200, 100, new RectF(20, 20, 80, 60), 20f);
            assertEquals(drew ? 1 : 0, lens.painted);
            boolean again = lens.paint(canvas, backdrop, 0f, 0f, 4f, 200, 100, new RectF(30, 20, 90, 60), 20f);
            assertEquals("a lens that failed stays off, one that worked keeps working", drew, again);
        } finally {
            target.endRecording();
        }
        lens.discard();
    }

    /**
     * #369: the rim mirrors the band at the pill's edge. The very edge shows what's two bands in, the order runs
     * backwards across the band, it meets the plain backdrop at the band's inner line, and past it nothing moves.
     */
    @Test
    public void theRimMirrorsTheBandAtTheEdge() {
        float band = 10f;
        assertEquals("the edge shows what's two bands in", 20f, GlassLens.rimSource(0f, band), 0f);
        float last = Float.MAX_VALUE;
        for (float depth = 0f; depth < band; depth += 0.5f) {
            float source = GlassLens.rimSource(depth, band);
            assertTrue("upside down across the band at " + depth, source < last);
            assertTrue("drawn from deeper in at " + depth, source > depth);
            last = source;
        }
        assertEquals("meets the backdrop at the band's inner line", band, GlassLens.rimSource(band - 0.001f, band), 0.01f);
        assertEquals(band, GlassLens.rimSource(band, band), 0f);
        assertEquals("past the band, as it is", 17f, GlassLens.rimSource(17f, band), 0f);
    }

    /**
     * #369: Bend light at the edges paints a rim over the liquid pill and over Search's circle beside it, where
     * the pill is blurred on Android 13 and newer. Off, no rim is made. Paused, the bar is the plain pill and
     * no rim is asked for.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void bendLightAtTheEdgesPaintsTheRimsOnlyWhenOn() {
        GlassTabBar.animationsOnForTests = false;
        for (boolean refract : new boolean[]{true, false}) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
                FrameLayout feed = new FrameLayout(host.activity);
                feed.setBackgroundColor(Color.RED);
                host.root.addView(feed, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                host.nameSearch(1);
                Settings.GLASS_TAB_BAR_LIQUID.save(true);
                Settings.GLASS_TAB_BAR_REFRACT.save(refract);
                GlassTabBar.apply(host.activity);
                host.frame();
                host.frame();
                Object pill = host.bar.getBackground();
                assertTrue("no backdrop recorded", (boolean) ReflectionHelpers.getField(pill, "recorded"));
                host.recordBackground();
                assertTrue("the pill wasn't blurred", (boolean) ReflectionHelpers.getField(pill, "blurShown"));
                GlassLens rim = ReflectionHelpers.getField(pill, "rimLens");
                GlassLens circle = ReflectionHelpers.getField(pill, "circleRim");
                if (!refract) {
                    assertNull("off, no rim", rim);
                    assertNull("off, no rim on Search", circle);
                    continue;
                }
                assertNotNull(rim);
                assertNotNull("Search's circle gets a rim of its own", circle);
                assertEquals(1, rim.asked);
                assertEquals(1, circle.asked);

                PauseForTests.pause(HushgramPause.Reason.SWITCH);
                try {
                    host.recordBackground();
                    assertEquals("paused, the plain pill", 1, rim.asked);
                } finally {
                    PauseForTests.resume();
                }
            }
        }
    }

    @Test
    public void theCapsuleSlideIsTimedNotCountedAndEasesOut() {
        assertEquals(0f, GlassTabBar.slideProgress(0), 0f);
        assertEquals(0f, GlassTabBar.slideProgress(-5), 0f);
        assertEquals(1f, GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS), 0f);
        assertEquals(1f, GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS * 3), 0f);
        float half = GlassTabBar.slideProgress(GlassTabBar.SLIDE_MS / 2);
        // Eased out: more than halfway at half time, and always moving forward.
        assertTrue(half > 0.8f && half < 1f);
        float last = 0f;
        for (long t = 0; t <= GlassTabBar.SLIDE_MS; t += 7) {
            float now = GlassTabBar.slideProgress(t);
            assertTrue(now >= last);
            last = now;
        }
    }

    @Test
    public void aLateFrameGetsNoRecordingUntilTheBlurIsTooOld() {
        // The first recording is always made.
        assertTrue(GlassTabBar.shouldRecord(false, 0, 40));
        // Not yet due.
        assertFalse(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS - 1, 0));
        // Due, and the frame is on time.
        assertTrue(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS, GlassTabBar.LATE_FRAME_MS));
        // Due, but the frame is late: it waits.
        assertFalse(GlassTabBar.shouldRecord(true, GlassTabBar.RECORD_EVERY_MS + 20, GlassTabBar.LATE_FRAME_MS + 1));
        // Too old to keep waiting, so it records even in a late frame.
        assertTrue(GlassTabBar.shouldRecord(true, GlassTabBar.MAX_STALE_MS, 60));
    }

    /**
     * #262: a blur recording that throws leaves the pill on its plain tint only until one works again.
     * Before, one failure turned the blur off for as long as the bar lived, and the pill stayed on the
     * dark tint. A blur that fails too many times in a row rests a while, then tries again, and one that
     * works in between starts the count over.
     */
    @Test
    public void aFailedBlurRecordingIsTriedAgain() {
        GlassTabBar.BlurTries tries = new GlassTabBar.BlurTries();
        long now = 10_000;
        assertFalse(tries.resting(now));
        for (int i = 1; i < GlassTabBar.BLUR_TRIES; i++) {
            assertFalse("a failure is tried again", tries.failed(now));
            assertFalse(tries.resting(now));
        }
        tries.worked();
        for (int i = 1; i < GlassTabBar.BLUR_TRIES; i++) {
            assertFalse("one that worked started the count over", tries.failed(now));
        }
        assertTrue("one too many in a row, and the blur rests", tries.failed(now));
        assertTrue(tries.resting(now));
        assertTrue(tries.resting(now + GlassTabBar.BLUR_REST_MS - 1));
        assertFalse("then tries again", tries.resting(now + GlassTabBar.BLUR_REST_MS));
        assertFalse("with the count started over", tries.failed(now + GlassTabBar.BLUR_REST_MS));
        assertFalse(tries.resting(now + GlassTabBar.BLUR_REST_MS));
    }

    @Test
    public void hapticTicksCloserThanTheMinimumGapAreDropped() {
        assertTrue(GlassTabBar.Haptics.farEnough(1000, 1000 - GlassTabBar.Haptics.MIN_TICK_GAP_MS));
        assertFalse(GlassTabBar.Haptics.farEnough(1000, 1000 - GlassTabBar.Haptics.MIN_TICK_GAP_MS + 1));
        assertTrue(GlassTabBar.Haptics.SOFT_SCALE > 0.2f && GlassTabBar.Haptics.SOFT_SCALE < 0.7f);
        // The short pulse is short and clearly felt, not a long weak buzz.
        assertTrue(GlassTabBar.Haptics.SHORT_MS >= 3 && GlassTabBar.Haptics.SHORT_MS <= 10);
        assertTrue(GlassTabBar.Haptics.SHORT_AMPLITUDE > 100 && GlassTabBar.Haptics.SHORT_AMPLITUDE <= 255);
    }

    /**
     * Where the tabs swipe sideways, Home's pager is the content's sibling with the bar's height as its
     * bottom margin. Floating, it runs down behind the pill like the content's own screens; a sibling
     * that isn't the pager, and a margin that isn't the bar's height, are left alone.
     */
    @Test
    public void homesSwipePagerBesideTheContentRunsBehindThePillToo() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout content = new FrameLayout(context);
        View screen = withMargin(new View(context), 126);
        View hiddenBar = withMargin(new View(context), 0);
        content.addView(screen);
        content.addView(hiddenBar);
        View pager = withMargin(new View(context), 126);
        pager.setId(0x7f0b0001);
        View other = withMargin(new View(context), 126);
        other.setId(0x7f0b0002);
        holder.addView(content);
        holder.addView(pager);
        holder.addView(other);

        GlassTabBar.screensAboveTheBar(content, 0x7f0b0001, 126, 0);

        assertEquals("the content's screen", 0, margin(screen));
        assertEquals("a screen Instagram already let run down", 0, margin(hiddenBar));
        assertEquals("Home's swipe pager", 0, margin(pager));
        assertEquals("another sibling", 126, margin(other));

        View tallerPager = withMargin(new View(context), 126);
        tallerPager.setId(0x7f0b0003);
        holder.addView(tallerPager);
        GlassTabBar.screensAboveTheBar(content, 0x7f0b0003, 126, 150);
        assertEquals("not floating, the pager stops at a taller pill's top", 150, margin(tallerPager));
        assertEquals("a margin already changed stays", 0, margin(pager));

        View noPager = withMargin(new View(context), 126);
        holder.addView(noPager);
        GlassTabBar.screensAboveTheBar(content, 0, 126, 0);
        assertEquals("no pager id, no sibling touched", 126, margin(noPager));
    }

    /**
     * #136 and #144: the glass finds each SurfaceView under the bar and the views on the way down to it, and
     * nothing else. A TextureView draws like any view, so it isn't one, and the bar's own branch isn't looked at.
     */
    @Test
    public void theGlassFindsEachSurfaceViewUnderTheBarAndTheWayDownToIt() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout pager = new FrameLayout(context);
        FrameLayout page = new FrameLayout(context);
        FrameLayout media = new FrameLayout(context);
        SurfaceView video = new SurfaceView(context);
        View cover = new View(context);
        View caption = new View(context);
        FrameLayout other = new FrameLayout(context);
        TextureView texture = new TextureView(context);
        FrameLayout bar = new FrameLayout(context);
        SurfaceView inBar = new SurfaceView(context);
        media.addView(video);
        media.addView(cover);
        page.addView(media);
        page.addView(caption);
        pager.addView(page);
        other.addView(texture);
        bar.addView(inBar);
        holder.addView(pager);
        holder.addView(other);
        holder.addView(bar);
        Set<View> paths = new HashSet<>();
        paths.add(caption);

        assertTrue(GlassTabBar.surfacePaths(holder, 2, paths));
        assertEquals(new HashSet<>(java.util.Arrays.asList(pager, page, media, video)), paths);

        assertFalse("only the views under the bar count", GlassTabBar.surfacePaths(holder, 0, paths));
        assertTrue(paths.isEmpty());
        pager.removeView(page);
        assertFalse("a TextureView isn't left out", GlassTabBar.surfacePaths(holder, 2, paths));
        assertTrue(paths.isEmpty());
    }

    /**
     * #136 and #144: the glass draws the views under the bar without their SurfaceViews, everything else where
     * it sits. Drawn into the glass a second time, a SurfaceView had its video moved under the pill and left
     * black where it played, so the glass gave up its blur whenever a video was on screen anywhere, and the
     * pill went flat and nearly opaque as a feed of videos scrolled. Drawing the views whole, the old way,
     * draws the SurfaceView, so the count below proves it's seen.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theGlassDrawsTheViewsUnderTheBarWithoutTheirSurfaceViews() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout feed = new FrameLayout(context);
        feed.setBackground(new ColorDrawable(Color.RED));
        CountingSurface video = new CountingSurface(context);
        View caption = new View(context);
        caption.setBackground(new ColorDrawable(Color.GREEN));
        View faded = new View(context);
        faded.setBackground(new ColorDrawable(Color.BLUE));
        faded.setAlpha(0f);
        feed.addView(video);
        feed.addView(caption);
        feed.addView(faded);
        holder.addView(feed);
        holder.layout(0, 0, 100, 100);
        feed.layout(0, 10, 100, 100);
        video.layout(0, 0, 100, 50);
        caption.layout(0, 60, 100, 80);
        faded.layout(0, 0, 100, 20);
        feed.scrollTo(0, 5);
        Set<View> paths = new HashSet<>();
        assertTrue(GlassTabBar.surfacePaths(holder, 1, paths));

        Bitmap old = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas oldCanvas = new Canvas(old);
        oldCanvas.translate(feed.getLeft(), feed.getTop());
        feed.draw(oldCanvas);
        assertTrue("drawn whole, the feed draws its SurfaceView", video.drawn > 0);

        video.drawn = 0;
        Bitmap glass = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        GlassTabBar.drawLeavingOutSurfaces(new Canvas(glass), feed, paths, 0);

        assertEquals("the SurfaceView is left out", 0, video.drawn);
        assertEquals("#144: the video's place is cleared, not the feed's background", 0, glass.getPixel(50, 30));
        assertEquals("the feed's own background still shows round it", Color.RED, glass.getPixel(50, 58));
        assertEquals("a child draws where it sits, scrolled", Color.GREEN, glass.getPixel(50, 66));
        assertEquals(Color.RED, glass.getPixel(50, 86));
        assertEquals("a see-through child isn't drawn", 0, glass.getPixel(50, 12));
        assertEquals("nothing above the feed", 0, glass.getPixel(50, 5));

        // With a copy of the video's frame, the frame draws in its place, under what's drawn after it.
        Bitmap frame = Bitmap.createBitmap(10, 5, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.YELLOW);
        Bitmap withFrame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        GlassTabBar.drawLeavingOutSurfaces(new Canvas(withFrame), feed, paths, new HashSet<>(),
                view -> view == video ? frame : null, 0);
        assertEquals("the copied frame fills the video's place", Color.YELLOW, withFrame.getPixel(50, 30));
        assertEquals("stretched to the whole of it", Color.YELLOW, withFrame.getPixel(98, 53));
        assertEquals(Color.RED, withFrame.getPixel(50, 58));
        assertEquals(Color.GREEN, withFrame.getPixel(50, 66));
        assertEquals(0, video.drawn);
    }

    /**
     * #222: the glass leaves a Reels viewer out of what it draws, the way it leaves out a SurfaceView, so a
     * reel's video is drawn once, where it plays, and never a second time into the blur. What's round the
     * viewer still draws. Drawn the old way, the viewer and its video go into the glass, so the count
     * below proves it's seen.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theGlassLeavesTheReelsViewerOutOfWhatItDraws() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout holder = new FrameLayout(context);
        FrameLayout screens = new FrameLayout(context);
        screens.setBackground(new ColorDrawable(Color.RED));
        FrameLayout viewer = new FrameLayout(context);
        AtomicInteger drawn = new AtomicInteger();
        View video = new View(context) {
            @Override public void draw(Canvas canvas) {
                drawn.incrementAndGet();
                super.draw(canvas);
            }
        };
        video.setBackground(new ColorDrawable(Color.BLUE));
        viewer.addView(video);
        View caption = new View(context);
        caption.setBackground(new ColorDrawable(Color.GREEN));
        screens.addView(viewer);
        screens.addView(caption);
        holder.addView(screens);
        holder.layout(0, 0, 100, 100);
        screens.layout(0, 0, 100, 100);
        viewer.layout(0, 0, 100, 60);
        video.layout(0, 0, 100, 60);
        caption.layout(0, 70, 100, 90);

        Set<View> paths = new HashSet<>();
        Set<View> skipped = new HashSet<>();
        assertFalse("a view elsewhere isn't left out", GlassTabBar.leaveOut(new View(context), holder, paths, skipped));
        assertTrue(paths.isEmpty());
        assertTrue(skipped.isEmpty());
        assertTrue(GlassTabBar.leaveOut(viewer, holder, paths, skipped));
        assertTrue("the way down to the viewer", paths.contains(screens) && paths.contains(viewer));
        assertFalse(paths.contains(holder));
        assertTrue(skipped.contains(viewer));

        // The old way, with no SurfaceView under the bar: the screens draw whole, the viewer among them.
        Bitmap old = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        GlassTabBar.drawLeavingOutSurfaces(new Canvas(old), screens, new HashSet<>(), 0);
        assertTrue("drawn the old way, the reel's video goes into the glass", drawn.get() > 0);
        assertEquals(Color.BLUE, old.getPixel(50, 30));

        drawn.set(0);
        Bitmap glass = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        GlassTabBar.drawLeavingOutSurfaces(new Canvas(glass), screens, paths, skipped, 0);
        assertEquals("the viewer is left out", 0, drawn.get());
        assertEquals("what's behind it still shows", Color.RED, glass.getPixel(50, 30));
        assertEquals("and so does what's round it", Color.GREEN, glass.getPixel(50, 80));
    }

    private static final class CountingSurface extends SurfaceView {
        int drawn;

        CountingSurface(Context context) {
            super(context);
        }

        @Override public void draw(Canvas canvas) {
            drawn++;
            super.draw(canvas);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            drawn++;
            super.dispatchDraw(canvas);
        }
    }

    /**
     * Home videos played out of place under a scroll-hidden bar (reported on 0.0.9, #136's double draw). The
     * bar slid away is still drawn, and the glass recorded before a video started holds the feed as it
     * draws now, video and all. So a SurfaceView that starts under the hidden bar is left out of a new
     * recording in that frame, not once the bar is back, while a frame with nothing new records nothing.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aVideoStartingUnderTheHiddenBarIsLeftOutOfTheGlassAtOnce() {
        GlassTabBar.animationsOnForTests = false;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
            FrameLayout feed = new FrameLayout(host.activity);
            feed.setBackgroundColor(Color.RED);
            host.root.addView(feed, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(true);
            GlassTabBar.apply(host.activity);
            host.frame();
            Object pill = host.bar.getBackground();
            assertTrue("no backdrop recorded", (boolean) ReflectionHelpers.getField(pill, "recorded"));
            Set<View> leftOut = ReflectionHelpers.getField(pill, "leftOut");
            assertTrue(leftOut.isEmpty());

            GlassScrollHide hide = ReflectionHelpers.getField(host.glass(), "scrollHide");
            ReflectionHelpers.setField(hide, "hidden", true);
            host.bar.setTranslationY(host.dp(200));
            assertTrue(hide.isOffScreen());
            ReflectionHelpers.callInstanceMethod(pill, "cancelCatchUp");
            ReflectionHelpers.setField(pill, "lastRecorded", 1L);
            host.frame();
            assertEquals("hidden with nothing new under it, the glass recorded", 1L, (long) ReflectionHelpers.getField(pill, "lastRecorded"));
            assertFalse("hidden, the glass arranged a catch-up", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));

            CountingSurface video = new CountingSurface(host.activity);
            feed.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            host.layout();
            host.frame();
            assertTrue("a video under the hidden bar waited for the bar to come back", (long) ReflectionHelpers.getField(pill, "lastRecorded") != 1L);
            assertTrue("the video isn't left out: " + leftOut, leftOut.contains(video) && leftOut.contains(feed));
            assertEquals("the glass drew the video", 0, video.drawn);

            ReflectionHelpers.setField(pill, "lastRecorded", 1L);
            host.frame();
            assertEquals("the same video recorded again", 1L, (long) ReflectionHelpers.getField(pill, "lastRecorded"));
            assertTrue("still hidden", hide.isOffScreen());
        }
    }

    /**
     * #262: a view under the bar that throws as the glass records it, as one Instagram is recycling can
     * while a list scrolls, costs the blur that recording and no more. The glass tries again shortly, so
     * the blur comes back even on a still screen, rests on the plain tint after too many failures in a
     * row, and records again once the rest is over. Before, the first throw turned the blur off for as
     * long as the bar lived, and the pill sat on the dark tint.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aBlurRecordingThatThrowsIsTriedAgainInsteadOfTurningTheBlurOff() {
        GlassTabBar.animationsOnForTests = false;
        HookStatus.clear();
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
            boolean[] throwing = {false};
            Object[] pill = {null};
            View feed = new View(host.activity) {
                @Override public void draw(Canvas canvas) {
                    // Only as the glass records it: the window's own draw goes on.
                    if (throwing[0] && pill[0] != null && (boolean) ReflectionHelpers.getField(pill[0], "drawingBackdrop")) {
                        throw new IllegalStateException("recycled");
                    }
                    super.draw(canvas);
                }
            };
            feed.setBackgroundColor(Color.RED);
            host.root.addView(feed, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            GlassTabBar.apply(host.activity);
            host.frame();
            pill[0] = host.bar.getBackground();
            assertTrue("no backdrop recorded", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
            GlassTabBar.BlurTries tries = ReflectionHelpers.getField(host.glass(), "tries");

            throwing[0] = true;
            ReflectionHelpers.callInstanceMethod(pill[0], "cancelCatchUp");
            ReflectionHelpers.callInstanceMethod(pill[0], "recordBackdrop");
            assertFalse("a recording cut short isn't shown", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
            assertFalse("one failure doesn't rest the blur", tries.resting(SystemClock.uptimeMillis()));
            assertTrue("it's tried again shortly", (boolean) ReflectionHelpers.getField(pill[0], "catchUpPending"));
            throwing[0] = false;
            ShadowLooper.idleMainLooper(GlassTabBar.RECORD_EVERY_MS * 2, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue("the blur came back on a still screen", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains("IllegalStateException"));

            // A view that throws every time it's drawn.
            throwing[0] = true;
            for (int i = 0; i < GlassTabBar.BLUR_TRIES; i++) ReflectionHelpers.callInstanceMethod(pill[0], "recordBackdrop");
            assertTrue("too many in a row, and the blur rests", tries.resting(SystemClock.uptimeMillis()));
            report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(GlassTabBar.COUNT_BLUR_RESTED));
            throwing[0] = false;
            host.frame();
            assertFalse("resting, nothing is recorded", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));

            ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse(tries.resting(SystemClock.uptimeMillis()));
            assertTrue("the rest over, a still screen recovers without another frame",
                    (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void aPermanentCaptureFailureHasABoundedRecoveryAndAFreshFrameCanTryAgain() {
        GlassTabBar.animationsOnForTests = false;
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
            boolean[] throwing = {false};
            Object[] pill = {null};
            AtomicInteger captures = new AtomicInteger();
            View feed = new View(host.activity) {
                @Override public void draw(Canvas canvas) {
                    if (pill[0] != null && (boolean) ReflectionHelpers.getField(pill[0], "drawingBackdrop")) {
                        captures.incrementAndGet();
                        if (throwing[0]) throw new IllegalStateException("still recycled");
                    }
                    super.draw(canvas);
                }
            };
            host.root.addView(feed, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            GlassTabBar.apply(host.activity);
            host.frame();
            // This small host has no native content/shadow ids. Let their bounded lookup redraws
            // finish before asking whether capture retries can keep an otherwise still screen awake.
            ShadowLooper.idleMainLooper(GlassTabBar.RESEARCH_MS * (GlassTabBar.MISS_REDRAWS + 1), java.util.concurrent.TimeUnit.MILLISECONDS);
            pill[0] = host.bar.getBackground();
            ReflectionHelpers.callInstanceMethod(pill[0], "cancelCatchUp");
            AtomicInteger frames = new AtomicInteger();
            host.bar.getViewTreeObserver().addOnPreDrawListener(() -> {
                frames.incrementAndGet();
                return true;
            });
            throwing[0] = true;
            ReflectionHelpers.setField(pill[0], "recorded", false);
            ReflectionHelpers.callInstanceMethod(pill[0], "recordIfDue");
            ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals("failed captures never request their own frames", 0, frames.get());
            assertEquals("one frame, two short retries and one retry after resting", GlassTabBar.BLUR_TRIES + 1, captures.get());
            assertFalse("a permanent failure stops waking the main thread", (boolean) ReflectionHelpers.getField(pill[0], "catchUpPending"));
            int stoppedAt = captures.get();
            ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals("no timer polls forever", stoppedAt, captures.get());

            throwing[0] = false;
            host.frame();
            assertTrue("new screen activity permits recovery", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
            assertTrue(captures.get() > stoppedAt);
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void successfulVideoCopiesCannotKeepAFailingBackdropRetrying() {
        GlassTabBar.animationsOnForTests = false;
        SurfaceFrames.Copier previousCopier = SurfaceFrames.copier;
        java.util.function.Predicate<SurfaceView> previousSurface = SurfaceFrames.hasSurface;
        boolean[] canCopy = {false};
        AtomicInteger copies = new AtomicInteger();
        SurfaceFrames.hasSurface = view -> canCopy[0];
        SurfaceFrames.copier = (view, into, done) -> {
            copies.incrementAndGet();
            into.eraseColor(Color.YELLOW);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> done.copied(true), 1);
        };
        try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
            SurfaceView video = new SurfaceView(host.activity);
            host.root.addView(video, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            boolean[] throwing = {false};
            Object[] pill = {null};
            AtomicInteger captures = new AtomicInteger();
            View feed = new View(host.activity) {
                @Override public void draw(Canvas canvas) {
                    if (pill[0] != null && (boolean) ReflectionHelpers.getField(pill[0], "drawingBackdrop")) {
                        captures.incrementAndGet();
                        if (throwing[0]) throw new IllegalStateException("still recycled beside video");
                    }
                    super.draw(canvas);
                }
            };
            host.root.addView(feed, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            GlassTabBar.apply(host.activity);
            host.frame();
            ShadowLooper.idleMainLooper(GlassTabBar.RESEARCH_MS * (GlassTabBar.MISS_REDRAWS + 1), java.util.concurrent.TimeUnit.MILLISECONDS);
            pill[0] = host.bar.getBackground();
            SurfaceFrames held = ReflectionHelpers.getField(pill[0], "frames");
            assertTrue("the visible video crosses the captured strip", video.isShown() && video.getHeight() > 0);
            ReflectionHelpers.callInstanceMethod(pill[0], "cancelCatchUp");
            AtomicInteger frames = new AtomicInteger();
            host.bar.getViewTreeObserver().addOnPreDrawListener(() -> {
                frames.incrementAndGet();
                return true;
            });
            canCopy[0] = true;
            // The small host normally drives frames by hand. Coalesce invalidations into window
            // frames here, so PixelCopy's actual refresh callback can renew the retry budget.
            boolean[] framePending = {false};
            Runnable drawFrame = () -> {
                framePending[0] = false;
                host.bar.getViewTreeObserver().dispatchOnPreDraw();
            };
            host.barInvalidated = () -> {
                if (!framePending[0]) {
                    framePending[0] = true;
                    host.bar.postDelayed(drawFrame, 16);
                }
            };
            try {
                throwing[0] = true;
                ReflectionHelpers.setField(pill[0], "recorded", false);
                ReflectionHelpers.callInstanceMethod(pill[0], "recordIfDue");
                ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertEquals("failed captures beside video never request their own frames (captures=" + captures.get()
                        + ", copies=" + copies.get() + ")", 0, frames.get());
                assertEquals("one frame, two short retries and one retry after resting", GlassTabBar.BLUR_TRIES + 1, captures.get());
                assertEquals("a failed capture never starts a video refresh", 0, copies.get());
                assertFalse("video cannot keep the failure timer alive", (boolean) ReflectionHelpers.getField(pill[0], "catchUpPending"));
                int stoppedAt = captures.get();
                ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertEquals("no capture polls forever", stoppedAt, captures.get());
                assertEquals("no copy polls forever", 0, copies.get());
                assertEquals("no induced frame polls forever", 0, frames.get());

                throwing[0] = false;
                host.frame();
                assertTrue("a real frame recovers the backdrop", (boolean) ReflectionHelpers.getField(pill[0], "recorded"));
                assertNotNull("the recovered backdrop still copies video", held.frameOf(video));
                assertEquals(Color.YELLOW, held.frameOf(video).getPixel(0, 0));
                int copied = copies.get();
                int refreshed = frames.get();
                ShadowLooper.idleMainLooper(SurfaceFrames.COPY_EVERY_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                host.frame();
                assertTrue("another real frame can refresh the video", copies.get() > copied);
                assertTrue("landed video asks for a frame beyond the real frame", frames.get() > refreshed + 1);
            } finally {
                host.barInvalidated = null;
                host.bar.removeCallbacks(drawFrame);
            }
        } finally {
            SurfaceFrames.copier = previousCopier;
            SurfaceFrames.hasSurface = previousSurface;
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 33)
    public void aRestingRecoveryIsCancelledByResetDetachDiscardAndPause() {
        GlassTabBar.animationsOnForTests = false;
        for (int ending = 0; ending < 4; ending++) {
            try (GlassHost host = new GlassHost(Color.BLACK, true, GlassHeight.STANDARD, false, true)) {
                GlassTabBar.apply(host.activity);
                host.frame();
                Object pill = host.bar.getBackground();
                GlassTabBar.BlurTries tries = ReflectionHelpers.getField(host.glass(), "tries");
                long now = SystemClock.uptimeMillis();
                for (int i = 0; i < GlassTabBar.BLUR_TRIES; i++) tries.failed(now);
                ReflectionHelpers.callInstanceMethod(pill, "cancelCatchUp");
                ReflectionHelpers.setField(pill, "recorded", false);
                ReflectionHelpers.callInstanceMethod(pill, "retrySoon");
                assertTrue("resting recovery was scheduled", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));

                if (ending == 0) GlassTabBar.resetForTests();
                else if (ending == 1) host.root.removeView(host.bar);
                else if (ending == 2) ReflectionHelpers.callInstanceMethod(pill, "discard");
                else PauseForTests.pause(HushgramPause.Reason.SWITCH);

                if (ending != 3) assertFalse("the pending task was removed", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));
                ShadowLooper.idleMainLooper(GlassTabBar.BLUR_REST_MS * 2, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertFalse("nothing stays queued", (boolean) ReflectionHelpers.getField(pill, "catchUpPending"));
                assertFalse("the cancelled task never captures", (boolean) ReflectionHelpers.getField(pill, "recorded"));
            } finally {
                PauseForTests.resume();
            }
        }
    }

    /**
     * Each Reels viewer under a floating pill gets the margin asked for (the navigation bar's room, #221):
     * the Reels tab's in the swipe pager and one opened over another tab. A margin Instagram set stays,
     * the bar's own branch is never touched, a new margin moves them with it, and 0 gives the room back.
     */
    @Test
    public void reelsViewersUnderAFloatingBarGetTheMarginAskedForAndInstagramsStays() {
        Context context = RuntimeEnvironment.getApplication();
        int reels = 0x7f0b0010;
        FrameLayout holder = new FrameLayout(context);
        FrameLayout pager = new FrameLayout(context);
        FrameLayout page = new FrameLayout(context);
        View tab = withMargin(new FrameLayout(context), 0);
        tab.setId(reels);
        page.addView(tab);
        pager.addView(page);
        View overHome = withMargin(new FrameLayout(context), 0);
        overHome.setId(reels);
        pager.addView(overHome);
        View instagrams = withMargin(new View(context), 40);
        instagrams.setId(reels);
        pager.addView(instagrams);
        FrameLayout bar = new FrameLayout(context);
        View insideBar = withMargin(new View(context), 0);
        insideBar.setId(reels);
        bar.addView(insideBar);
        holder.addView(pager);
        holder.addView(bar);
        Map<View, Integer> given = new HashMap<>();

        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 126, given));
        assertEquals("the Reels tab", 126, margin(tab));
        assertEquals("a reel opened over Home", 126, margin(overHome));
        assertEquals("Instagram's own margin", 40, margin(instagrams));
        assertEquals("the bar's own branch", 0, margin(insideBar));
        assertEquals("settled, nothing changes", 0, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 126, given));

        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 150, given));
        assertEquals("a taller pill", 150, margin(tab));
        assertEquals(2, GlassTabBar.reelsAboveTheBar(holder, bar, reels, 0, given));
        assertEquals("a hidden bar", 0, margin(tab));
        assertEquals(40, margin(instagrams));
        assertTrue(given.isEmpty());
        assertEquals("no id, nothing found", 0, GlassTabBar.reelsAboveTheBar(holder, bar, 0, 126, given));
    }

    /**
     * #347: a reel opened from a feed post has Instagram hide the bar with Home selected, and the
     * pill's slot kept under that viewer was a blank band. The slot stays while the bar shows, and with
     * the Reels tab selected, where Instagram hides the bar for a paused reel (#221).
     */
    @Test
    public void reelsKeepTheSlotWhileTheBarShowsOrReelsIsTheTab() {
        Context context = RuntimeEnvironment.getApplication();
        int reelsTab = 0x7f0b0c68;
        FrameLayout bar = new FrameLayout(context);
        View home = new View(context);
        home.setId(0x7f0b17ff);
        View reels = new View(context);
        reels.setId(reelsTab);
        bar.addView(home);
        bar.addView(reels);
        home.setSelected(true);

        assertTrue("the bar shows over Home", GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
        bar.setTranslationY(200f);
        assertTrue("the bar slid away still holds its slot (#170)", GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
        bar.setVisibility(View.GONE);
        assertFalse("a feed post's reel with the bar hidden", GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
        bar.setVisibility(View.INVISIBLE);
        assertFalse(GlassTabBar.reelsKeepTheSlot(bar, reelsTab));

        home.setSelected(false);
        reels.setSelected(true);
        bar.setVisibility(View.GONE);
        assertTrue("a paused reel in the Reels tab", GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
        assertFalse("no Reels tab id", GlassTabBar.reelsKeepTheSlot(bar, 0));
        reels.setVisibility(View.INVISIBLE);
        assertTrue("a Reels tab tucked out of the bar", GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
        reels.setSelected(false);
        assertFalse(GlassTabBar.reelsKeepTheSlot(bar, reelsTab));
    }

    /**
     * #372: with the switch on, the viewer fills the screen behind the pill and its caption, buttons and
     * seek bar are raised by the slot; off, today's layout. A slot not kept (bar hidden) changes nothing.
     */
    @Test
    public void reelsBehindThePillRaiseTheControlsInsteadOfStoppingTheViewer() {
        assertFalse("off by default", Settings.GLASS_TAB_BAR_REELS_BEHIND.defaultValue);
        assertEquals(126, GlassTabBar.reelsViewerMargin(true, false, 126));
        assertEquals(0, GlassTabBar.reelsBandLift(true, false, 126));
        assertEquals(0, GlassTabBar.reelsViewerMargin(true, true, 126));
        assertEquals(126, GlassTabBar.reelsBandLift(true, true, 126));
        assertEquals("no slot kept, nothing raised", 0, GlassTabBar.reelsBandLift(false, true, 126));
        assertEquals(0, GlassTabBar.reelsViewerMargin(false, true, 126));
        assertEquals(0, GlassTabBar.reelsViewerMargin(false, false, 126));
        assertEquals(0, GlassTabBar.reelsBandLift(true, true, -5));
    }

    /**
     * #335: with the Reels auto-hide holding the pill all the way off, the reel gets the pill's height
     * instead of a black band. Only the navigation bar's room stays under it (#221).
     */
    @Test
    public void reelsGiveUpThePillsHeightWhileTheAutoHideHasItOff() {
        assertEquals("pill showing", GlassTabBar.reelsMargin(150), GlassTabBar.reelsSlot(150, false));
        assertEquals("pill slid all the way off", GlassTabBar.reelsMargin(0), GlassTabBar.reelsSlot(150, true));
        assertEquals(GlassTabBar.reelsMargin(0), GlassTabBar.reelsSlot(0, false));
    }

    /**
     * #221: what's raised clear of a floating pill is the child of a reel's layer of controls that holds
     * the caption or the seek bar, as in Instagram 451's Reels, never the layer or the reel, so the video,
     * stickers and play button stay where they are. The footer under the viewer (#260) is found apart
     * from them, and nothing inside a hidden view is.
     */
    @Test
    public void aReelsCaptionAndSeekBarRiseByTheirOwnBandOfTheControls() {
        Context context = RuntimeEnvironment.getApplication();
        int media = 0x7f0b0020, info = 0x7f0b0021, scrubber = 0x7f0b0022, footers = 0x7f0b0023;
        FrameLayout viewer = new FrameLayout(context);
        LinearLayout column = new LinearLayout(context);
        viewer.addView(column);
        FrameLayout page = new FrameLayout(context);
        column.addView(page);
        View video = new View(context);
        video.setId(media);
        page.addView(video);
        FrameLayout layer = new FrameLayout(context);
        page.addView(layer);
        FrameLayout infoBand = new FrameLayout(context);
        layer.addView(infoBand);
        FrameLayout infoHost = new FrameLayout(context);
        infoBand.addView(infoHost);
        View caption = new View(context);
        caption.setId(info);
        infoHost.addView(caption);
        FrameLayout scrubBand = new FrameLayout(context);
        layer.addView(scrubBand);
        View seekBar = new View(context);
        seekBar.setId(scrubber);
        scrubBand.addView(seekBar);
        View sticker = new View(context);
        layer.addView(sticker);
        LinearLayout footer = new LinearLayout(context);
        footer.setId(footers);
        column.addView(footer);
        View insideFooter = new View(context);
        insideFooter.setId(scrubber);
        footer.addView(insideFooter);

        java.util.List<View> anchors = new java.util.ArrayList<>(), found = new java.util.ArrayList<>();
        GlassTabBar.reelAnchors(viewer, info, scrubber, footers, anchors, found, 0);
        assertEquals(java.util.Arrays.asList(caption, seekBar), anchors);
        assertEquals("not looked inside", java.util.Collections.singletonList(footer), found);
        assertSame("the caption's band", infoBand, GlassTabBar.reelBand(caption, media, viewer));
        assertSame("the seek bar's", scrubBand, GlassTabBar.reelBand(seekBar, media, viewer));
        assertSame("a band right in the layer is itself", sticker, GlassTabBar.reelBand(sticker, media, viewer));
        assertNull("no video id", GlassTabBar.reelBand(caption, 0, viewer));
        assertNull("no video short of where it stops", GlassTabBar.reelBand(caption, media, layer));

        infoBand.setVisibility(View.GONE);
        anchors.clear();
        found.clear();
        GlassTabBar.reelAnchors(viewer, info, scrubber, footers, anchors, found, 0);
        assertEquals("a hidden caption isn't raised", java.util.Collections.singletonList(seekBar), anchors);
    }

    /**
     * #221: a reel's band is raised by scrolling its content, as far as the highest child it shows has room
     * above it. A hidden or empty child doesn't count, and a band showing nothing has no room.
     */
    @Test
    public void aBandScrollsAsFarAsItsHighestShownChildHasRoom() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout band = new FrameLayout(context);
        assertEquals("nothing in it", 0, GlassTabBar.roomAbove(band));
        View buttons = new View(context), caption = new View(context), hidden = new View(context), empty = new View(context);
        band.addView(buttons);
        band.addView(caption);
        band.addView(hidden);
        band.addView(empty);
        buttons.layout(900, 954, 1080, 1700);
        caption.layout(0, 1807, 900, 2000);
        hidden.layout(0, 100, 1080, 200);
        hidden.setVisibility(View.GONE);
        empty.layout(0, 50, 1080, 50);
        assertEquals("the buttons are highest", 954, GlassTabBar.roomAbove(band));
        buttons.setVisibility(View.INVISIBLE);
        assertEquals(1807, GlassTabBar.roomAbove(band));
    }

    /**
     * Instagram's touch strip for a reel's seek bar is placed by how far the reel stops above the screens'
     * bottom. Measured with its own margin back on for a frame and the pill's counted too (back from
     * HushGram's settings on the S22), it sat a bar's height too high, over the reel's More button, and
     * took its taps. It goes back so its bottom edge is the seek bar's, or the viewer's with no seek bar
     * showing. A strip hung from the top, or a reel off the screen, is left alone.
     */
    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void theSeekBarsTouchStripStaysOnTheReelAboveThePill() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            View strip = new View(activity);
            FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 112, Gravity.BOTTOM);
            stripParams.bottomMargin = 287;
            root.addView(strip, stripParams);
            FrameLayout viewer = new FrameLayout(activity);
            FrameLayout.LayoutParams viewerParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            viewerParams.bottomMargin = 180;
            root.addView(viewer, viewerParams);
            int scrubberId = 0x7f0b0011;
            View seekBar = new View(activity);
            seekBar.setId(scrubberId);
            FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28, Gravity.BOTTOM);
            viewer.addView(seekBar, seekParams);
            FrameLayout nextTab = new FrameLayout(activity);
            nextTab.setTranslationX(10000);
            View nextSeekBar = new View(activity);
            nextSeekBar.setId(scrubberId);
            nextTab.addView(nextSeekBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28, Gravity.TOP));
            root.addView(nextTab, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(root);
            java.util.List<View> viewers = java.util.Arrays.asList(nextTab, viewer);
            Rect scratch = new Rect();
            layOut(root);

            assertTrue("a bar's height too high", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            layOut(root);
            assertEquals("on the seek bar of the reel on screen", bottomOnScreen(seekBar), bottomOnScreen(strip));
            assertEquals(180, stripParams.bottomMargin);
            assertFalse("settled, nothing moves", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));

            stripParams.bottomMargin = 107;
            strip.setLayoutParams(stripParams);
            assertFalse("not laid out again yet", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            layOut(root);
            assertTrue("measured before the reel was kept above the pill", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            assertEquals(180, stripParams.bottomMargin);

            seekParams.bottomMargin = 300;
            seekBar.setLayoutParams(seekParams);
            layOut(root);
            assertTrue("a seek bar higher up", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            layOut(root);
            assertEquals(bottomOnScreen(seekBar), bottomOnScreen(strip));

            seekBar.setVisibility(View.GONE);
            layOut(root);
            assertTrue("no seek bar showing", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            layOut(root);
            assertEquals("the viewer's bottom edge", bottomOnScreen(viewer), bottomOnScreen(strip));

            stripParams.gravity = Gravity.TOP;
            stripParams.topMargin = 40;
            strip.setLayoutParams(stripParams);
            layOut(root);
            assertFalse("hung from the top", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            stripParams.gravity = Gravity.BOTTOM;
            stripParams.bottomMargin = 287;
            strip.setLayoutParams(stripParams);
            viewer.setTranslationX(-10000);
            layOut(root);
            assertFalse("no reel on the screen", GlassTabBar.stripOnTheReel(strip, viewers, scrubberId, scratch));
            assertEquals(287, stripParams.bottomMargin);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    /** Lays [root] out at the size of the window's content, where the screen shows all of it. */
    private static void layOut(View root) {
        ShadowLooper.idleMainLooper();
        View content = (View) root.getParent();
        root.measure(View.MeasureSpec.makeMeasureSpec(content.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(content.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, content.getWidth(), content.getHeight());
    }

    private static int bottomOnScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1] + view.getHeight();
    }

    private static View withMargin(View view, int bottom) {
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.bottomMargin = bottom;
        view.setLayoutParams(params);
        return view;
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29)
    public void aFloatingPillBlursWhatsBehindItAndADockedBarReflectsTheStripAboveIt() {
        // A bar at y 2000 whose pill starts 30 px down, 20 px of blur room round it, a 140 px strip, a quarter size.
        Matrix source = new Matrix();
        float top = GlassTabBar.backdropSource(source, true, 0.25f, 100f, 2000f, 2030f, 20f, 140f);
        assertEquals("a floating pill's strip starts behind its own top, less the blur room", 2010f, top, 0f);
        float[] behind = {100f, 2010f, 100f, 2150f};
        source.mapPoints(behind);
        assertArrayEquals("what's behind the pill lands in the node the right way up (#262)",
                new float[]{0f, 0f, 0f, 35f}, behind, 0.001f);

        top = GlassTabBar.backdropSource(source, false, 0.25f, 100f, 2000f, 2030f, 20f, 140f);
        assertEquals("a docked bar's strip ends at the bar's top", 1860f, top, 0f);
        float[] above = {100f, 2000f, 100f, 1860f};
        source.mapPoints(above);
        assertArrayEquals("the strip above a docked bar lands upside down",
                new float[]{0f, 0f, 0f, 35f}, above, 0.001f);
    }

    private static int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }
}
