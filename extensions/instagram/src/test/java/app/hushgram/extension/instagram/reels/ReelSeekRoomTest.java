/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * A reel's seek bar stays out of the strip at the window's foot, and goes back once it's clear (#450, #385), with
 * the caption over it coming along (#633).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSeekRoomTest {
    private static final int WINDOW = 2316;
    private static final int STRIP = 48;
    private static final int INFO = 0x7f0b0633;
    private static final int TOUCH = 0x7f0b0b91;
    private View holder;
    private long now = 10_000;

    @Before
    public void holder() {
        holder = new FrameLayout(RuntimeEnvironment.getApplication());
    }

    private void look(int bottom) {
        now += ReelSeekRoom.LOOK_MS;
        assertTrue(ReelSeekRoom.due(holder, now));
        ReelSeekRoom.keep(holder, true, bottom, WINDOW, STRIP);
        ReelSeekRoom.along(holder, INFO);
    }

    /** Puts the holder in a layer beside a band whose caption's top is [top], as the reel's controls are laid out. */
    private View band(int top) {
        FrameLayout layer = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout band = new FrameLayout(RuntimeEnvironment.getApplication());
        View caption = new View(RuntimeEnvironment.getApplication());
        caption.setId(INFO);
        band.addView(caption);
        layer.addView(band);
        layer.addView(holder);
        band.layout(0, 0, 1080, WINDOW);
        caption.layout(0, top, 1080, top + 300);
        return band;
    }

    @Test
    public void aBarIsRaisedOnlyAsFarAsItReachesIntoTheStrip() {
        assertEquals(STRIP, ReelSeekRoom.liftFor(WINDOW, WINDOW, STRIP));
        assertEquals(16, ReelSeekRoom.liftFor(WINDOW - STRIP + 16, WINDOW, STRIP));
        assertEquals(0, ReelSeekRoom.liftFor(WINDOW - STRIP, WINDOW, STRIP));
        assertEquals(0, ReelSeekRoom.liftFor(1000, WINDOW, STRIP));
    }

    @Test
    public void noStripOrNoWindowMeansNoLift() {
        assertEquals(0, ReelSeekRoom.liftFor(WINDOW, WINDOW, 0));
        assertEquals(0, ReelSeekRoom.liftFor(WINDOW, 0, STRIP));
    }

    @Test
    public void theHolderGoesUpAndStaysPutOnceClear() {
        look(WINDOW);
        assertEquals(-STRIP, holder.getTranslationY(), 0f);

        // Already raised, the bar now ends at the strip's top: nothing more to do.
        look(WINDOW - STRIP);
        assertEquals(-STRIP, holder.getTranslationY(), 0f);
    }

    /** The viewer stopped short of the foot, as when a tab bar comes back. */
    @Test
    public void aBarThatNoLongerNeedsItGoesBack() {
        look(WINDOW);
        look(WINDOW - STRIP - 200);

        assertEquals(0f, holder.getTranslationY(), 0f);
    }

    @Test
    public void instagramsOwnTranslationStaysUnderTheLift() {
        holder.setTranslationY(-10f);

        look(WINDOW);
        assertEquals(-10f - STRIP, holder.getTranslationY(), 0f);

        look(WINDOW - STRIP - 300);
        assertEquals(-10f, holder.getTranslationY(), 0f);
    }

    /** Laid out again, the holder has no lift, and adding the old one again would climb on every look. */
    @Test
    public void aLiftInstagramWipedIsWorkedOutAfresh() {
        look(WINDOW);
        holder.setTranslationY(0f);

        look(WINDOW);
        assertEquals(-STRIP, holder.getTranslationY(), 0f);
        look(WINDOW - STRIP);
        assertEquals(-STRIP, holder.getTranslationY(), 0f);
    }

    /** Instagram's drag times sit beside the holder in this row, so the row has to go up with the bar (#567). */
    @Test
    public void aShortRowGoesUpInsteadOfTheHolder() {
        FrameLayout row = new FrameLayout(RuntimeEnvironment.getApplication());
        row.addView(holder);
        row.layout(0, 0, 1080, WINDOW / ReelSeekRoom.ROW_SHARE);

        assertSame(row, ReelSeekRoom.lifted(holder, WINDOW));
    }

    /** Moving a parent this tall would carry the rest of the page up with the bar. */
    @Test
    public void aParentAsTallAsThePageStaysPut() {
        FrameLayout page = new FrameLayout(RuntimeEnvironment.getApplication());
        page.addView(holder);
        page.layout(0, 0, 1080, WINDOW / ReelSeekRoom.ROW_SHARE + 1);

        assertSame(holder, ReelSeekRoom.lifted(holder, WINDOW));
    }

    /** With no size yet there's nothing to say it's a row. */
    @Test
    public void aParentNotLaidOutOrMissingLeavesTheHolder() {
        assertSame(holder, ReelSeekRoom.lifted(holder, WINDOW));

        FrameLayout unmeasured = new FrameLayout(RuntimeEnvironment.getApplication());
        unmeasured.addView(holder);
        assertSame(holder, ReelSeekRoom.lifted(holder, WINDOW));
    }

    @Test
    public void aBarIsMeasuredAtMostEveryLook() {
        look(WINDOW);

        assertFalse(ReelSeekRoom.due(holder, now + ReelSeekRoom.LOOK_MS - 1));
        assertTrue(ReelSeekRoom.due(holder, now + ReelSeekRoom.LOOK_MS));
    }

    @Test
    public void pausedPutsTheHolderBack() {
        look(WINDOW);

        ReelSeekRoom.keep(holder, false, 0, 0, 0);

        assertEquals(0f, holder.getTranslationY(), 0f);
    }

    /** A bar that's hidden, or sits on a window with nothing to clear, never moves its holder. */
    @Test
    public void aHolderNeverLiftedIsLeftAlone() {
        holder.setTranslationY(-7f);
        assertTrue(ReelSeekRoom.due(holder, now));

        ReelSeekRoom.keep(holder, false, 0, 0, 0);
        assertEquals(-7f, holder.getTranslationY(), 0f);

        look(WINDOW);
        ReelSeekRoom.keep(holder, true, WINDOW, 0, 0);
        assertEquals(-7f, holder.getTranslationY(), 0f);
    }

    /** A Samsung with its gesture hint hidden reports no strip, though a swipe up from there still leaves (#633). */
    @Test
    public void aPhoneReportingNoStripGetsTheHeightItLeftOut() {
        assertEquals(84, ReelSeekRoom.strip(63, 84, 135));
        assertEquals(126, ReelSeekRoom.strip(126, 0, 135));
        assertEquals(135, ReelSeekRoom.strip(0, 0, 135));
        assertEquals(0, ReelSeekRoom.strip(0, 0, 0));
    }

    /** Raised alone, the bar would land on the caption over it (#633). */
    @Test
    public void theCaptionGoesUpWithTheBarAndBackDown() {
        View band = band(1500);

        look(WINDOW);
        assertEquals(STRIP, band.getScrollY());
        look(WINDOW - STRIP);
        assertEquals(STRIP, band.getScrollY());

        look(WINDOW - STRIP - 200);
        assertEquals(0, band.getScrollY());
    }

    @Test
    public void pausedScrollsTheCaptionBack() {
        View band = band(1500);
        look(WINDOW);

        ReelSeekRoom.keep(holder, false, 0, 0, 0);
        ReelSeekRoom.along(holder, INFO);

        assertEquals(0, band.getScrollY());
    }

    /** Any further and the caption's top would be cut off at the band's top. */
    @Test
    public void theCaptionScrollsNoFurtherThanTheRoomOverIt() {
        View band = band(20);

        look(WINDOW);

        assertEquals(20, band.getScrollY());
    }

    /** The glass pill scrolls the same band, and what it did is its own. */
    @Test
    public void aScrollSomeoneElseGaveIsLeftAlone() {
        View band = band(1500);
        band.scrollTo(0, 30);

        look(WINDOW);
        assertEquals(30, band.getScrollY());
        look(WINDOW - STRIP - 200);
        assertEquals(30, band.getScrollY());
    }

    @Test
    public void aScrollTakenOverIsLeftToItsNewOwner() {
        View band = band(1500);
        look(WINDOW);
        band.scrollTo(0, 70);

        look(WINDOW - STRIP);
        assertEquals(70, band.getScrollY());
        look(WINDOW - STRIP - 200);
        assertEquals(70, band.getScrollY());
    }

    @Test
    public void onlyTheBandHoldingTheCaptionComesAlong() {
        View band = band(1500);
        FrameLayout other = new FrameLayout(RuntimeEnvironment.getApplication());
        other.addView(new View(RuntimeEnvironment.getApplication()));
        ((ViewGroup) holder.getParent()).addView(other);

        look(WINDOW);
        assertEquals(STRIP, band.getScrollY());
        assertEquals(0, other.getScrollY());
        assertEquals(0, holder.getScrollY());
    }

    /** On a build whose caption id can't be found, nothing beside the bar moves. */
    @Test
    public void noCaptionIdMovesNothing() {
        View band = band(1500);
        now += ReelSeekRoom.LOOK_MS;
        assertTrue(ReelSeekRoom.due(holder, now));
        ReelSeekRoom.keep(holder, true, WINDOW, WINDOW, STRIP);

        ReelSeekRoom.along(holder, 0);

        assertEquals(0, band.getScrollY());
    }

    /**
     * Instagram measured its touch strip for the bar where it was, so a drag on the raised bar reached the reel
     * (#633). The strip goes onto the raised bar, short of the system's strip, and is Instagram's to place
     * while the bar isn't raised.
     */
    @Test
    public void theTouchStripGoesOntoTheRaisedBar() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Activity activity = controller.get();
            FrameLayout screens = new FrameLayout(activity);
            View strip = new View(activity);
            strip.setId(TOUCH);
            FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104, Gravity.BOTTOM);
            stripParams.bottomMargin = 132;
            screens.addView(strip, stripParams);
            FrameLayout viewer = new FrameLayout(activity);
            screens.addView(viewer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            viewer.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 26, Gravity.BOTTOM));
            View bar = new View(activity);
            ((FrameLayout) holder).addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(screens);
            layOut(screens);

            assertTrue(ReelSeekRoom.due(holder, now));
            ReelSeekRoom.touches(bar, holder, TOUCH, 28, Integer.MAX_VALUE);
            layOut(screens);
            assertEquals("not raised", 132, stripParams.bottomMargin);

            look(WINDOW);
            int barBottom = bottomOnScreen(bar);
            ReelSeekRoom.touches(bar, holder, 0, 28, barBottom);
            layOut(screens);
            assertEquals("no strip id", 132, stripParams.bottomMargin);

            ReelSeekRoom.touches(bar, holder, TOUCH, 28, barBottom);
            layOut(screens);
            assertEquals(barBottom, bottomOnScreen(strip));
        } finally {
            controller.pause().stop().destroy();
        }
    }

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
}
