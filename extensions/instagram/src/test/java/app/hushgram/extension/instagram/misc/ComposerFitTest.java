/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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

import app.hushgram.extension.shared.SettingsContextRule;

/**
 * A chat's message box keeps the height it has at the phone's own text size while Text size makes text
 * bigger (#806): its padding gives way first, then, only when that runs out, its text gets smaller, never
 * as small as the phone's own. A box of several lines is left to grow, and what Instagram sets again is
 * taken as its own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ComposerFitTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Instagram's message box text at the phone's own size, 14sp at xhdpi. */
    private static final float TEXT = 28f;
    private static final int DENSITY = 2;

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout row;
    private EditText box;

    @Before
    public void prepare() {
        ComposerFit.resetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After
    public void restore() {
        ComposerFit.resetForTests();
        if (controller != null) controller.close();
    }

    /** A message bar like Instagram's: the camera and the box in a row, the box [padding] dp above and below. */
    private void bar(int padding, int leastDp) {
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout camera = new FrameLayout(activity);
        row.addView(camera, new LinearLayout.LayoutParams(38 * DENSITY, 38 * DENSITY));
        box = new EditText(activity);
        box.setId(View.generateViewId());
        box.setBackground(null);
        box.setSingleLine(false);
        box.setHint("Message...");
        box.setPadding(8 * DENSITY, padding * DENSITY, 8 * DENSITY, padding * DENSITY);
        box.setMinHeight(leastDp * DENSITY);
        box.setMinimumHeight(leastDp * DENSITY);
        size(1f);
        row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        activity.setContentView(row);
        layout();
    }

    /** Instagram's text at [scale] times the phone's, as its resources give it once Text size is set. */
    private void size(float scale) {
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT * scale);
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        row.measure(View.MeasureSpec.makeMeasureSpec(411 * DENSITY, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    @Test
    public void aBigSizeKeepsTheBoxItsOwnHeightByTakingFromThePadding() {
        bar(14, 50);
        int own = box.getHeight();
        assertFalse("at the phone's own size nothing gives", ComposerFit.fit(box, 1f));

        size(1.75f);
        layout();
        assertTrue("the bigger line made the box taller", box.getHeight() > own + 4);

        assertTrue(ComposerFit.fit(box, 1.75f));
        layout();

        assertEquals(own, box.getHeight(), 1);
        assertEquals("the text keeps the size Text size gave it", TEXT * 1.75f, box.getTextSize(), 0.01f);
        assertTrue(box.getPaddingTop() < 14 * DENSITY && box.getPaddingTop() >= ComposerFit.FLOOR_DP * DENSITY);
        assertEquals("taken from above and below alike", box.getPaddingTop(), box.getPaddingBottom(), 1);
        assertFalse("it settles", ComposerFit.fit(box, 1.75f));
    }

    @Test
    public void aSizeTheBoxsOwnLeastHeightTakesInChangesNothing() {
        bar(14, 50);
        int own = box.getHeight();
        size(1.1f);
        layout();
        assertEquals("the box's least height still holds the line", own, box.getHeight());

        assertFalse(ComposerFit.fit(box, 1.1f));
        assertEquals(14 * DENSITY, box.getPaddingTop());
        assertEquals(14 * DENSITY, box.getPaddingBottom());
    }

    @Test
    public void whenThePaddingRunsOutTheTextShrinksToTheLargestThatFits() {
        // A tall font in a box with little padding and no least height: it grows at once.
        bar(6, 0);
        int own = box.getHeight();
        size(2f);
        layout();
        assertTrue(box.getHeight() > own + 20);

        assertTrue(ComposerFit.fit(box, 2f));
        layout();

        assertTrue("kept at most its own height, was " + box.getHeight() + " for " + own, box.getHeight() <= own + 1);
        assertEquals(ComposerFit.FLOOR_DP * DENSITY, box.getPaddingTop());
        assertEquals(ComposerFit.FLOOR_DP * DENSITY, box.getPaddingBottom());
        assertTrue("still bigger than the phone's own size, was " + box.getTextSize(), box.getTextSize() > TEXT + 2);
        assertTrue(box.getTextSize() < TEXT * 2f);
        float fitted = box.getTextSize();
        assertFalse("it settles", ComposerFit.fit(box, 2f));
        layout();
        assertFalse(ComposerFit.fit(box, 2f));
        assertEquals(fitted, box.getTextSize(), 0f);
    }

    @Test
    public void paddingInstagramSetsAgainIsFittedAgainFromItsOwn() {
        bar(14, 50);
        size(1.75f);
        layout();
        ComposerFit.fit(box, 1.75f);
        layout();
        int top = box.getPaddingTop();
        int bottom = box.getPaddingBottom();

        box.setPadding(8 * DENSITY, 14 * DENSITY, 8 * DENSITY, 14 * DENSITY);
        layout();
        assertTrue(ComposerFit.fit(box, 1.75f));
        assertEquals(top, box.getPaddingTop());
        assertEquals(bottom, box.getPaddingBottom());
    }

    @Test
    public void aMessageOfSeveralLinesGrowsTheBoxAsBefore() {
        bar(14, 50);
        size(1.75f);
        layout();
        ComposerFit.fit(box, 1.75f);
        layout();
        int top = box.getPaddingTop();
        int oneLine = box.getHeight();

        box.setText("one\ntwo\nthree");
        layout();

        assertFalse(ComposerFit.fit(box, 1.75f));
        assertEquals(top, box.getPaddingTop());
        assertTrue(box.getHeight() > oneLine * 2);
    }

    @Test
    public void instagramsOwnPaddingComesBackOnceNothingNeedsToGive() {
        bar(14, 50);
        size(1.75f);
        layout();
        ComposerFit.fit(box, 1.75f);
        layout();
        assertTrue(box.getPaddingTop() < 14 * DENSITY);

        // Instagram draws the box's text smaller of its own accord.
        size(1f);
        layout();

        assertTrue(ComposerFit.fit(box, 1.75f));
        assertEquals(14 * DENSITY, box.getPaddingTop());
        assertEquals(14 * DENSITY, box.getPaddingBottom());
    }

    @Test
    public void textSizeFitsTheBoxBeforeTheFrameIsDrawnAndStopsWhenTheScreenGoes() {
        bar(14, 50);
        int own = box.getHeight();
        ComposerFit.idForTests = box.getId();
        size(1.75f);
        layout();

        TextSize.Watcher watcher = new TextSize.Watcher(1.75f);
        watcher.onActivityResumed(activity);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        observer.dispatchOnGlobalLayout();
        assertTrue("the frame is put off while the box lays out again", observer.dispatchOnPreDraw());
        layout();
        observer.dispatchOnGlobalLayout();
        assertFalse(observer.dispatchOnPreDraw());
        assertEquals(own, box.getHeight(), 1);

        watcher.onActivityPaused(activity);
        box.setPadding(8 * DENSITY, 14 * DENSITY, 8 * DENSITY, 14 * DENSITY);
        layout();
        observer.dispatchOnGlobalLayout();
        assertFalse(observer.dispatchOnPreDraw());
        assertEquals("left alone once the screen goes", 14 * DENSITY, box.getPaddingTop());
    }
}
