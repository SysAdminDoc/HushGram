/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * #527: with Page dots on a pill, a carousel post's button row closes the band Instagram leaves over
 * its buttons for the dots, so the buttons sit as close to the photo as a single post's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class DotsGapTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** 454 on a 2.0 density screen: the button row is 132 tall, its dots 60, its buttons start 40 down. */
    private static final int WIDTH = 720, LIST = 1400, ROW_TOP = 300, ROW = 233, BUTTONS = 132, DOTS = 60, BAND = 40;

    /** The feed's list, as the helper knows one: by a class named RecyclerView. */
    public static class RecyclerView extends FrameLayout {
        public RecyclerView(Context context) {
            super(context);
        }
    }

    private Activity activity;
    private RecyclerView list;
    private FrameLayout row;
    private FrameLayout buttons;
    private View dots;
    private View like;

    @Before
    public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        CarouselArrows.resetForTests();
        list = new RecyclerView(activity);
        row = new FrameLayout(activity);
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(WIDTH, ROW);
        place.topMargin = ROW_TOP;
        list.addView(row, place);
        buttons = new FrameLayout(activity);
        row.addView(buttons, new FrameLayout.LayoutParams(WIDTH, BUTTONS));
        dots = new View(activity);
        dots.setId(View.generateViewId());
        FrameLayout.LayoutParams middle = new FrameLayout.LayoutParams(160, DOTS);
        middle.leftMargin = 280;
        buttons.addView(dots, middle);
        like = new View(activity);
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(56, BUTTONS - BAND);
        under.leftMargin = 24;
        under.topMargin = BAND;
        buttons.addView(like, under);
        activity.setContentView(list, new ViewGroup.LayoutParams(WIDTH, LIST));
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.CAROUSEL_DOTS_PILL.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        CarouselArrows.resetForTests();
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int margin() {
        return ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin;
    }

    @Test
    public void theRowMovesUpByTheBandOverItsButtons() {
        DotsGap.close(dots);
        idle();

        assertEquals(ROW_TOP - BAND, margin());
        assertEquals("the list lays the row out higher", ROW_TOP - BAND, row.getTop());
        assertTrue(DotsGap.liftedForTests(row));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + DotsGap.COUNT_CLOSED + " 1"));
    }

    /** Each frame closes the gap again, and a row already up stays where it is. */
    @Test
    public void aRowAlreadyUpIsNotMovedFurther() {
        DotsGap.close(dots);
        idle();
        DotsGap.close(dots);
        DotsGap.check();
        idle();

        assertEquals(ROW_TOP - BAND, margin());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + DotsGap.COUNT_CLOSED + " 1"));
    }

    @Test
    public void aRowWithItsButtonsAtTheTopIsLeftAlone() {
        ((FrameLayout.LayoutParams) like.getLayoutParams()).topMargin = 0;
        like.requestLayout();
        idle();

        DotsGap.close(dots);

        assertEquals(ROW_TOP, margin());
        assertFalse(DotsGap.liftedForTests(row));
    }

    /** A band taller than Instagram's own isn't a dots band, so nothing is pulled over the photo. */
    @Test
    public void aBandFarTallerThanInstagramsIsLeftAlone() {
        ViewGroup.LayoutParams tall = dots.getLayoutParams();
        tall.height = BUTTONS;
        ((FrameLayout.LayoutParams) like.getLayoutParams()).topMargin = 100;
        dots.requestLayout();
        idle();

        DotsGap.close(dots);

        assertEquals(ROW_TOP, margin());
    }

    @Test
    public void dotsOutsideAListAreLeftAlone() {
        FrameLayout plain = new FrameLayout(activity);
        list.removeView(row);
        plain.addView(row);
        activity.setContentView(plain);
        idle();
        int before = margin();

        DotsGap.close(dots);

        assertEquals(before, margin());
        assertFalse(DotsGap.liftedForTests(row));
    }

    /** The list hands the row another post with no dots: it gets its own margin back on the next frame. */
    @Test
    public void aRowHoldingAPostWithNoDotsGetsItsMarginBack() {
        DotsGap.close(dots);
        idle();

        buttons.removeView(dots);
        DotsGap.check();
        idle();

        assertEquals(ROW_TOP, margin());
        assertEquals(ROW_TOP, row.getTop());
        assertFalse(DotsGap.liftedForTests(row));
    }

    /** A row still holding its dots keeps its lift. */
    @Test
    public void aRowStillHoldingItsDotsKeepsItsLift() {
        DotsGap.close(dots);
        idle();

        DotsGap.check();

        assertEquals(ROW_TOP - BAND, margin());
    }

    /** Above the list's top Instagram takes the dots out of view on its own; the row mustn't jump then. */
    @Test
    public void aRowScrolledAboveTheListKeepsItsLiftWithoutItsDots() {
        DotsGap.close(dots);
        idle();
        row.offsetTopAndBottom(-ROW_TOP - 100);

        buttons.removeView(dots);
        DotsGap.check();

        assertEquals(ROW_TOP - BAND, margin());
        assertTrue(DotsGap.liftedForTests(row));
    }

    @Test
    public void theListLettingGoOfTheRowGivesItsMarginBack() {
        DotsGap.close(dots);
        idle();

        list.removeView(row);

        assertEquals(ROW_TOP, margin());
        assertFalse(DotsGap.liftedForTests(row));
    }

    @Test
    public void turningThePillOffGivesTheMarginBack() {
        DotsGap.close(dots);
        idle();

        Settings.CAROUSEL_DOTS_PILL.save(false);
        DotsGap.check();
        idle();

        assertEquals(ROW_TOP, margin());
        assertEquals(ROW_TOP, row.getTop());
    }

    @Test
    public void pausingHushGramGivesTheMarginBack() {
        DotsGap.close(dots);
        idle();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        DotsGap.check();

        assertEquals(ROW_TOP, margin());
    }

    /** Putting the carousels back, as when the feature stops, opens every gap. */
    @Test
    public void puttingTheCarouselsBackGivesEveryMarginBack() {
        DotsGap.close(dots);
        idle();

        CarouselArrows.putBack();

        assertEquals(ROW_TOP, margin());
        assertFalse(DotsGap.liftedForTests(row));
    }
}
