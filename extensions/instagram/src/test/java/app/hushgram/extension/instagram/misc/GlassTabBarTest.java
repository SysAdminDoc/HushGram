/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
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
        Settings.GLASS_TAB_BAR_HEIGHT.resetToDefault();
        Settings.GLASS_TAB_BAR_LIFT.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTICS.resetToDefault();
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    public void nativeGlassHasFullyRoundEndsAndASelectionWithoutMovingTouches() throws Exception {
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
                                int middle = plain.bitmap.getPixel(outer + host.dp(8), Math.round(drawn.centerY()));
                                if (base == Color.BLACK) {
                                    assertTrue("a fully round end leaves the corner empty", Color.red(corner) <= 5);
                                    assertTrue("and fills the middle of the end", Color.red(middle) > 5);
                                } else if (floating) {
                                    assertTrue("light glass fills the middle of the end", Color.alpha(middle) > 200);
                                }
                                if (floating && base == Color.BLACK) {
                                    assertEquals("the panel corner remains clear", 0, plain.bitmap.getPixel(outer, top));
                                }
                                for (int index : new int[]{0, touches.length - 1}) {
                                    View selected = host.bar.getChildAt(index);
                                    assertTrue("the host's real click handler still runs", selected.performClick());
                                    try (Pixels active = host.background()) {
                                        RectF slot = ReflectionHelpers.getField(host.bar.getBackground(), "capsule");
                                        int x = Math.round(slot.left) + host.dp(8);
                                        int y = Math.round(slot.centerY());
                                        int before = Color.red(plain.bitmap.getPixel(x, y));
                                        int after = Color.red(active.bitmap.getPixel(x, y));
                                        assertTrue("the selected slot is a round capsule", base == Color.BLACK ? after > before + 10 : after < before - 10);
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
                    RectF drawn = ReflectionHelpers.getField(host.bar.getBackground(), "rect");
                    int middle = pixels.bitmap.getPixel(outer + host.dp(8), Math.round(drawn.centerY()));
                    assertTrue("unsupported blur still paints the rounded surface", Color.alpha(middle) > 200);
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

    /** A native host strip. The tab bar and its listeners remain the host's views. */
    private static final class GlassHost implements AutoCloseable {
        private static final int BAR_ID = 0x7f0b0120;
        final ActivityController<Activity> controller;
        final Activity activity;
        final FrameLayout root;
        final LinearLayout bar;
        final float density;
        final AtomicInteger clicks = new AtomicInteger();
        private final Integer previousId;

        GlassHost(int base, boolean floating, GlassHeight height, boolean rtl, boolean blur) {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            Settings.GLASS_TAB_BAR.save(true);
            Settings.GLASS_TAB_BAR_FLOAT.save(floating);
            Settings.GLASS_TAB_BAR_HEIGHT.save(height);
            Settings.GLASS_TAB_BAR_OPACITY.save(GlassOpacity.STANDARD);
            Settings.GLASS_TAB_BAR_BLUR.save(blur);
            Settings.GLASS_TAB_BAR_HAPTICS.save(false);
            Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
            controller = Robolectric.buildActivity(Activity.class).setup();
            activity = controller.get();
            activity.getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
            density = activity.getResources().getDisplayMetrics().density;
            root = new FrameLayout(activity);
            root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            root.setBackgroundColor(base == Color.BLACK ? 0xff0c1014 : 0xffeff2f5);
            bar = new LinearLayout(activity);
            bar.setId(BAR_ID);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackgroundColor(base);
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
                bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM));
            activity.setContentView(root);
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) { previousId = ids.put(GlassTabBar.TAB_BAR, BAR_ID); }
            layout();
        }

        int dp(int value) { return Math.round(value * density); }

        void layout() {
            ShadowLooper.idleMainLooper();
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(104), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, dp(411), dp(104));
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
            root.removeView(bar);
            Map<View, ?> applied = ReflectionHelpers.getStaticField(GlassTabBar.class, "applied");
            applied.remove(bar);
            Map<String, Integer> ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
            synchronized (ids) {
                if (previousId == null) ids.remove(GlassTabBar.TAB_BAR);
                else ids.put(GlassTabBar.TAB_BAR, previousId);
            }
            controller.close();
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
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                assertEquals(GlassTabBar.tint(base, blurred), GlassTabBar.tint(base, blurred, GlassOpacity.STANDARD));
            }
        }
        assertEquals(0x70, Color.alpha(GlassTabBar.tint(Color.BLACK, true, GlassOpacity.STANDARD)));
        assertEquals(0xa6, Color.alpha(GlassTabBar.tint(Color.WHITE, true, GlassOpacity.STANDARD)));
        assertEquals(0xe6, Color.alpha(GlassTabBar.tint(Color.WHITE, false, GlassOpacity.STANDARD)));
    }

    @Test
    public void eachOpacityStepIsStrictlyMoreSolidAndStaysInRange() {
        for (int base : new int[] {Color.BLACK, Color.WHITE}) {
            for (boolean blurred : new boolean[] {true, false}) {
                int last = -1;
                for (GlassOpacity step : GlassOpacity.values()) {
                    int alpha = Color.alpha(GlassTabBar.tint(base, blurred, step));
                    assertTrue(alpha >= 0 && alpha <= 255);
                    assertTrue(step + " " + blurred, alpha >= last);
                    last = alpha;
                }
                assertTrue(Color.alpha(GlassTabBar.tint(base, blurred, GlassOpacity.CLEAR))
                        < Color.alpha(GlassTabBar.tint(base, blurred, GlassOpacity.STANDARD)));
            }
        }
        // Frosted never overflows the alpha byte, and a missing step reads as the standard one.
        assertEquals(255, Color.alpha(GlassTabBar.tint(Color.WHITE, false, GlassOpacity.FROSTED)));
        assertEquals(GlassTabBar.tint(Color.BLACK, true), GlassTabBar.tint(Color.BLACK, true, null));
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
        assertEquals("the feed's own background still shows", Color.RED, glass.getPixel(50, 30));
        assertEquals("a child draws where it sits, scrolled", Color.GREEN, glass.getPixel(50, 66));
        assertEquals(Color.RED, glass.getPixel(50, 86));
        assertEquals("a see-through child isn't drawn", Color.RED, glass.getPixel(50, 12));
        assertEquals("nothing above the feed", 0, glass.getPixel(50, 5));
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
     * A floating pill keeps each Reels viewer above it, so its seek bar, caption and the touch strip
     * Instagram lays over the bar's top don't sit under the pill and take its taps: the Reels tab's in the
     * swipe pager and one opened over another tab get the bar's height as their margin. A margin Instagram
     * set stays, the bar's own branch is never touched, a taller pill moves them with it, and a hidden bar
     * gives the room back.
     */
    @Test
    public void reelsViewersStopAtAFloatingBarsTopAndGetTheRoomBackWhenItHides() {
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

    private static int margin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
    }
}
