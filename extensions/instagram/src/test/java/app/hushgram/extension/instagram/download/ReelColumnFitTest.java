/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
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

import kotlin.jvm.functions.Function1;

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

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final Sizes sizes = new Sizes();
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
        ReelDownloadButton.bridge = originalBridge;
        resetAll();
        controller.pause().stop().destroy();
    }

    /** Keeps the size and margin each icon is made with. */
    private static final class Sizes implements ReelDownloadButton.Bridge {
        long size = -1;
        long margin = -1;

        @Override public Object item(Object column) {
            return null;
        }

        @Override public Object media(Object item) {
            return null;
        }

        @Override public String kind(Object item) {
            return null;
        }

        @Override public int moreTop(Object column, Context context) {
            return 0;
        }

        @Override public Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size,
                                     long margin) {
            this.size = size;
            this.margin = margin;
            return drawable;
        }
    }

    private static long px(int pixels) {
        return ReelColumnFit.PX_BITS | (pixels & 0xFFFFFFFFL);
    }

    /** Lays out one icon of [size] over [margin] pixels through the column's own route. */
    private void icon(int size, int margin) {
        ReelDownloadButton.bridge = sizes;
        ReelColumnFit.icon(new ColorDrawable(), "Full screen", tap -> null, px(size), px(margin));
    }

    /** How far a column [columnTall] high reaches past the top bar and its gap, in pixels. */
    private int over(int columnTall) {
        float density = activity.getResources().getDisplayMetrics().density;
        return BAR + Math.round(ReelColumnFit.GAP_DP * density) - (TALL - SEEK_ROOM - columnTall);
    }

    private int slack() {
        return Math.round(ReelColumnFit.SLACK_DP * activity.getResources().getDisplayMetrics().density);
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

    @Test
    public void scaledKeepsEachKindOfSize() {
        assertEquals(px(75), ReelColumnFit.scaled(px(100), 0.75f));
        assertEquals(ReelColumnFit.SP_BITS | Float.floatToRawIntBits(4f),
                ReelColumnFit.scaled(ReelColumnFit.SP_BITS | Float.floatToRawIntBits(8f), 0.5f));
        assertEquals(Double.doubleToRawLongBits(4.0), ReelColumnFit.scaled(Double.doubleToRawLongBits(8.0), 0.5f));
        assertEquals("none stays none", 0L, ReelColumnFit.scaled(0L, 0.5f));
        long unknown = 0x7ff8000000000000L;
        assertEquals("a size read some other way is left alone", unknown, ReelColumnFit.scaled(unknown, 0.5f));
        assertEquals("full size changes nothing", px(23), ReelColumnFit.scaled(px(23), 1f));
    }

    @Test
    public void pixelsReadsEachKindOfSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.density = 2f;
        metrics.scaledDensity = 3f;
        assertEquals(23, ReelColumnFit.pixels(px(23), metrics));
        assertEquals(16, ReelColumnFit.pixels(Double.doubleToRawLongBits(8.0), metrics));
        assertEquals(24, ReelColumnFit.pixels(ReelColumnFit.SP_BITS | Float.floatToRawIntBits(8f), metrics));
        assertEquals(0, ReelColumnFit.pixels(0L, metrics));
        assertEquals(0, ReelColumnFit.pixels(0x7ff8000000000000L, metrics));
    }

    @Test
    public void aColumnTooTallLaysTheNextIconsOutSmaller() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        assertEquals("full size until a column is too tall", px(300), sizes.size);
        assertEquals(px(200), sizes.margin);
        ReelColumnFit.watch(activity);

        frame();

        float wanted = 1f - (over(COLUMN_TALL) + slack()) / 500f;
        assertEquals(wanted, ReelColumnFit.shareForTests(), EPSILON);
        icon(300, 200);
        assertEquals(px(Math.round(300 * wanted)), sizes.size);
        assertEquals(px(Math.round(200 * wanted)), sizes.margin);
        assertTrue("the column on screen now is still drawn smaller", column.getScaleY() < 1f);
    }

    @Test
    public void aColumnIsReadOnceUntilItIsLaidOutAgain() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        ReelColumnFit.watch(activity);
        frame();
        float first = ReelColumnFit.shareForTests();
        assertTrue(first < 1f);

        frame();
        frame();
        assertEquals("the same columns don't lower it again", first, ReelColumnFit.shareForTests(), 0f);

        column.setLayoutParams(columnParams(COLUMN_TALL - 50));
        layout();
        frame();

        float wanted = first - (over(COLUMN_TALL - 50) + slack()) / 500f;
        assertEquals("a column laid out anew is read at the share it got", wanted, ReelColumnFit.shareForTests(), EPSILON);
    }

    @Test
    public void theShareNeverGoesUnderTheLeast() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        icon(30, 20);
        ReelColumnFit.watch(activity);

        frame();

        assertEquals(ReelColumnFit.LEAST_SHARE, ReelColumnFit.shareForTests(), 0f);
    }

    @Test
    public void aColumnThatFitsLeavesTheIconsAtFullSize() {
        Settings.SPEED_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        page.removeView(column);
        page.addView(column, columnParams(300));
        nextPage.removeView(nextColumn);
        layout();
        ReelColumnFit.watch(activity);

        frame();

        assertEquals(1f, ReelColumnFit.shareForTests(), 0f);
    }

    @Test
    public void theShareIsKeptAndStartsOverWhenTheIconsChange() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        ReelColumnFit.watch(activity);
        frame();
        float learned = ReelColumnFit.shareForTests();
        assertTrue(learned < 1f);
        ShadowLooper.idleMainLooper();

        ReelColumnFit.resetForTests();
        icon(300, 200);
        assertEquals("read back from HushGram's store", learned, ReelColumnFit.shareForTests(), 0f);
        assertEquals(px(Math.round(300 * learned)), sizes.size);

        Settings.SPEED_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        assertEquals("another icon switched on starts over", 1f, ReelColumnFit.shareForTests(), 0f);
        assertEquals(px(300), sizes.size);
        ShadowLooper.idleMainLooper();
        assertFalse(activity.getSharedPreferences(ReelColumnFit.STORE, Context.MODE_PRIVATE).contains(ReelColumnFit.KEY_SHARE));
    }

    @Test
    public void anotherIconSizeStartsTheShareOver() {
        Settings.FULL_SCREEN_BUTTON_ON_REELS.save(true);
        icon(300, 200);
        ReelColumnFit.watch(activity);
        frame();
        assertTrue(ReelColumnFit.shareForTests() < 1f);

        icon(320, 200);

        assertEquals("a new display size lays the icons out at full size again", 1f, ReelColumnFit.shareForTests(), 0f);
        assertEquals(px(320), sizes.size);
    }
}
