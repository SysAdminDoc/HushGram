/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A reel's seek bar stays out of the strip at the window's foot, and goes back once it's clear (#450, #385). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSeekRoomTest {
    private static final int WINDOW = 2316;
    private static final int STRIP = 48;
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
}
