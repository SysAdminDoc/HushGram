/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The colour and size choices Glass tab bar makes from Instagram's bar colour and the screen's width. */
@RunWith(RobolectricTestRunner.class)
public class GlassTabBarTest {
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
