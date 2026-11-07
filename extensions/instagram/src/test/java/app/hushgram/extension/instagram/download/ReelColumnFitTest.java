/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The reel's button column drawn smaller when HushGram's added icons would push it past the top bar (#516). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelColumnFitTest {
    private static final int WIDE = 400;
    private static final int TALL = 800;
    private static final int BAR = 160;
    private static final int COLUMN_TALL = 700;
    private static final int SEEK_ROOM = 50;
    private static final float EPSILON = 0.002f;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View bar;
    private FrameLayout page;
    private View column;
    private FrameLayout nextPage;
    private View nextColumn;

    @Before
    public void prepare() {
        resetAll();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        int barId = View.generateViewId();
        int pagerId = View.generateViewId();
        int columnId = View.generateViewId();
        ReelColumnFit.idForTests(activity, ReelColumnFit.TOP_BAR, barId);
        ReelColumnFit.idForTests(activity, ReelColumnFit.PAGER, pagerId);
        ReelColumnFit.idForTests(activity, ReelColumnFit.COLUMN, columnId);

        root = new FrameLayout(activity);
        FrameLayout pager = new FrameLayout(activity);
        pager.setId(pagerId);
        FrameLayout list = new FrameLayout(activity);
        page = new FrameLayout(activity);
        column = columnView(columnId);
        page.addView(column, columnParams(COLUMN_TALL));
        nextPage = new FrameLayout(activity);
        nextColumn = columnView(columnId);
        nextPage.addView(nextColumn, columnParams(COLUMN_TALL));
        list.addView(page, new FrameLayout.LayoutParams(WIDE, TALL));
        FrameLayout.LayoutParams halfwayIn = new FrameLayout.LayoutParams(WIDE, TALL);
        halfwayIn.topMargin = TALL / 2;
        list.addView(nextPage, halfwayIn);
        pager.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = new View(activity);
        bar.setId(barId);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR, Gravity.TOP));
        activity.setContentView(root);
        layout();
    }

    @After
    public void restore() {
        resetAll();
        controller.pause().stop().destroy();
    }

    private static void resetAll() {
        ReelColumnFit.resetForTests();
        Settings.FULL_SCREEN_BUTTON_ON_REELS.resetToDefault();
        Settings.SPEED_BUTTON_ON_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private View columnView(int id) {
        View view = new View(activity);
        view.setId(id);
        return view;
    }

    private static FrameLayout.LayoutParams columnParams(int tall) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(100, tall, Gravity.BOTTOM | Gravity.END);
        params.bottomMargin = SEEK_ROOM;
        return params;
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDE, TALL);
    }

    private void frame() {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    /** The scale that puts the column's top [GAP_DP] under the bar, worked out from the layout above. */
    private float fittingScale(int columnTall) {
        int gap = Math.round(ReelColumnFit.GAP_DP * activity.getResources().getDisplayMetrics().density);
        int bottom = TALL - SEEK_ROOM;
        return (bottom - (BAR + gap)) / (float) columnTall;
    }

    @Test
    public void scaleForLeavesAFittingColumnAlone() {
        assertEquals(1f, ReelColumnFit.scaleFor(200, 900, 100), 0f);
        assertEquals(1f, ReelColumnFit.scaleFor(100, 900, 100), 0f);
        assertEquals("a column with no height is left alone", 1f, ReelColumnFit.scaleFor(10, 10, 100), 0f);
    }

    @Test
    public void scaleForShrinksJustEnoughAndNeverPastTheSmallest() {
        assertEquals(582f / 700f, ReelColumnFit.scaleFor(50, 750, 168), EPSILON);
        assertEquals(ReelColumnFit.SMALLEST, ReelColumnFit.scaleFor(0, 100, 90), 0f);
    }

    @Test
    public void settledTopReadsTheColumnsPlaceInItsOwnPage() {
        assertEquals(TALL - SEEK_ROOM - COLUMN_TALL, ReelColumnFit.settledTop(column, page));
        assertEquals("a page part way through a swipe reads the same",
                TALL - SEEK_ROOM - COLUMN_TALL, ReelColumnFit.settledTop(nextColumn, nextPage));
    }

    @Test
    public void allIconsOffLeavesEveryColumnAlone() {
        ReelColumnFit.watch(activity);
        assertTrue(ReelColumnFit.listeningForTests(activity));

        frame();

        assertEquals(1f, column.getScaleY(), 0f);
        assertEquals(1f, nextColumn.getScaleY(), 0f);
    }

    @Test
    public void aColumnPastTheTopBarIsDrawnSmallerFromItsBottom() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        ReelColumnFit.watch(activity);

        frame();

        float wanted = fittingScale(COLUMN_TALL);
        assertEquals(wanted, column.getScaleY(), EPSILON);
        assertEquals(wanted, column.getScaleX(), EPSILON);
        assertEquals("it stays over the seek bar", COLUMN_TALL, column.getPivotY(), 0f);
        assertEquals(50f, column.getPivotX(), 0f);
        int[] barAt = new int[2];
        bar.getLocationOnScreen(barAt);
        int[] columnAt = new int[2];
        column.getLocationOnScreen(columnAt);
        float drawnTop = columnAt[1] + COLUMN_TALL * (1f - column.getScaleY());
        assertTrue("first button below the bar: " + drawnTop, drawnTop >= barAt[1] + BAR);
        assertEquals("a reel still sliding in gets its settled size", wanted, nextColumn.getScaleY(), EPSILON);
    }

    @Test
    public void aColumnThatFitsKeepsItsOwnSize() {
        Settings.SPEED_BUTTON_ON_REELS.save(true);
        page.removeView(column);
        page.addView(column, columnParams(300));
        nextPage.removeView(nextColumn);
        layout();
        ReelColumnFit.watch(activity);

        frame();

        assertEquals(1f, column.getScaleY(), 0f);
    }

    @Test
    public void aShrunkColumnGrowsBackOnceItFits() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        ReelColumnFit.watch(activity);
        frame();
        assertTrue(column.getScaleY() < 1f);

        column.setLayoutParams(columnParams(300));
        layout();
        frame();

        assertEquals(1f, column.getScaleY(), 0f);
        assertEquals(1f, column.getScaleX(), 0f);
    }

    @Test
    public void pausedPutsTheColumnBack() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        ReelColumnFit.watch(activity);
        frame();
        assertTrue(column.getScaleY() < 1f);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertEquals(1f, column.getScaleY(), 0f);
        assertEquals(1f, nextColumn.getScaleY(), 0f);
    }

    @Test
    public void turningTheIconsOffPutsTheColumnBack() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        ReelColumnFit.watch(activity);
        frame();
        assertTrue(column.getScaleY() < 1f);

        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(false);
        root.getViewTreeObserver().dispatchOnPreDraw();

        assertEquals(1f, column.getScaleY(), 0f);
    }

    @Test
    public void aHiddenTopBarLetsTheColumnRunHigher() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        bar.setVisibility(View.GONE);
        layout();
        ReelColumnFit.watch(activity);

        frame();

        assertTrue("with the bar gone the column needs less shrinking: " + column.getScaleY(),
                column.getScaleY() > fittingScale(COLUMN_TALL));
    }
}
