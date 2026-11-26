/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Select all in Your activity (#762), on a screen built the way Instagram 457 lays out Your activity's
 * Likes grid: Instagram's top bar with its own Select at the end, and a Bloks container whose tiles turn
 * checkable when Select is on, over a button that counts the ticked ones ("Unlike (3)"). A tile's tick
 * lives with the item it shows, as Bloks keeps it, so a tile scrolled on to the next items shows their
 * ticks. The count works the way 457's does: a tap reads it as it lands and writes it back a moment later,
 * so taps closer together than that lose their additions.
 */
@RunWith(RobolectricTestRunner.class)
public class ActivitySelectAllTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BLOKS = View.generateViewId();
    private static final int ACTION_BAR = View.generateViewId();

    private Activity activity;
    private LinearLayout bar;
    private TextView select;

    @Before
    public void setUp() {
        ActivitySelectAll.bloksId = BLOKS;
        ActivitySelectAll.actionBarId = ACTION_BAR;
        Settings.ACTIVITY_SELECT_ALL.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void tearDown() {
        Settings.ACTIVITY_SELECT_ALL.resetToDefault();
        ActivitySelectAll.Pass pass = ActivitySelectAll.running;
        if (pass != null) pass.finish();
        ActivitySelectAll.running = null;
        ActivitySelectAll.bloksId = 0;
        ActivitySelectAll.actionBarId = 0;
    }

    /**
     * Select all shows beside Select only while the grid's tiles are checkboxes and Instagram counts a
     * tick, and goes with them.
     */
    @Test
    public void selectAllShowsBesideSelectOnlyWhileInstagramCountsTicks() {
        Grid grid = screen(9, 3, 9);
        ActivitySelectAll.look(root());
        assertNull("no button before Select is on", button());

        grid.selecting(true);
        ActivitySelectAll.look(root());
        assertNull("no button before Instagram counts a tick", button());

        handTick(grid, 0);
        ActivitySelectAll.look(root());
        TextView all = button();
        assertNotNull("the button shows once a tick is counted", all);
        assertEquals("Select all", all.getText().toString());
        assertEquals("it sits right before Instagram's Select", bar.indexOfChild(select) - 1, bar.indexOfChild(all));
        assertEquals("it takes Select's text size", select.getTextSize(), all.getTextSize(), 0.01f);

        ActivitySelectAll.look(root());
        assertEquals("a second look adds no second button", 1, buttons());

        handTick(grid, 0);
        ActivitySelectAll.look(root());
        assertNull("it goes when the count does", button());

        handTick(grid, 1);
        ActivitySelectAll.look(root());
        assertNotNull(button());
        grid.selecting(false);
        ActivitySelectAll.look(root());
        assertNull("it goes when Select is off", button());
    }

    /** A screen that shows no count gets no button, ticks or not. */
    @Test
    public void noCountNoButton() {
        Grid grid = screen(9, 3, 9);
        grid.counts = false;
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());
        assertNull(button());
    }

    /** Two different bracketed numbers on the screen leave no telling which is the count. */
    @Test
    public void twoCountsNoButton() {
        Grid grid = screen(9, 3, 9);
        View other = new CountLabel(activity, null, "Posts (12)");
        ((ViewGroup) activity.findViewById(BLOKS)).addView(other, new FrameLayout.LayoutParams(100, 40));
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());
        assertNull(button());
    }

    /** With the switch off the button never shows, and turning it off takes a shown one away. */
    @Test
    public void theSwitchDecides() {
        Grid grid = screen(9, 3, 9);
        grid.selecting(true);
        handTick(grid, 0);
        Settings.ACTIVITY_SELECT_ALL.save(false);
        ActivitySelectAll.look(root());
        assertNull(button());

        Settings.ACTIVITY_SELECT_ALL.save(true);
        ActivitySelectAll.look(root());
        assertNotNull(button());
        Settings.ACTIVITY_SELECT_ALL.save(false);
        ActivitySelectAll.look(root());
        assertNull(button());
    }

    /** Paused, HushGram adds nothing. */
    @Test
    public void pausedAddsNothing() {
        Grid grid = screen(9, 3, 9);
        grid.selecting(true);
        handTick(grid, 0);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            ActivitySelectAll.look(root());
            assertNull(button());
        } finally {
            PauseForTests.resume();
        }
    }

    /** A column of checkable rows, like a Bloks page of radio buttons, isn't a grid and gets no button. */
    @Test
    public void aColumnOfCheckableRowsGetsNoButton() {
        Grid list = screen(6, 1, 6);
        list.selecting(true);
        handTick(list, 0);
        ActivitySelectAll.look(root());
        assertNull(button());
    }

    /**
     * One tap ticks every tile, scrolling on to the end of the grid, leaves the one ticked by hand ticked,
     * and Instagram's own count ends up matching the ticks.
     */
    @Test
    public void selectAllTicksEveryTileDownToTheEndOfTheGrid() {
        Grid grid = screen(40, 3, 9);
        grid.selecting(true);
        handTick(grid, 4);
        ActivitySelectAll.look(root());

        button().performClick();
        settle();

        for (int i = 0; i < grid.count; i++) assertTrue("item " + i + " is ticked", grid.ticked[i]);
        assertEquals("each item was tapped once, the one ticked by hand not at all", 39, grid.taps);
        assertEquals("Instagram counts every tick", 40, grid.counted);
        assertTrue("the grid was scrolled to its end", !grid.canScrollVertically(1));
        assertNull("the pass is over", ActivitySelectAll.running);
    }

    /**
     * Ticks go one at a time, each after Instagram counted the last, so its count matches the ticks the
     * user sees. Taps in one go leave it short and Unlike would act on fewer than are ticked.
     */
    @Test
    public void instagramsCountMatchesTheTicksOnScreen() {
        Grid grid = screen(9, 3, 9);
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        settle();

        assertEquals(9, grid.tickedCount());
        assertEquals("Instagram's count matches the ticks", grid.tickedCount(), grid.counted);
        assertEquals("Unlike (9)", grid.label());
    }

    /** Items Instagram loads once the grid reaches its end get ticked too. */
    @Test
    public void itemsLoadedAtTheEndGetTickedToo() {
        Grid grid = screen(18, 3, 9);
        grid.loadsMore = 12;
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        settle();

        assertEquals(30, grid.count);
        for (int i = 0; i < grid.count; i++) assertTrue("item " + i + " is ticked", grid.ticked[i]);
        assertEquals(30, grid.counted);
        assertNull(ActivitySelectAll.running);
    }

    /** When Instagram turns a tick down it allows no more, so the pass stops instead of tapping on. */
    @Test
    public void aRefusedTickEndsThePass() {
        Grid grid = screen(40, 3, 9);
        grid.allows = 10;
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        settle();

        assertEquals(10, grid.tickedCount());
        assertEquals(10, grid.counted);
        assertEquals("the refused tile is tapped once, not again and again", 10, grid.taps);
        assertNull(ActivitySelectAll.running);
    }

    /**
     * A tick Instagram shows but doesn't count ends the pass right there, so the count is off by that one
     * tick at most and nothing more is ticked past it.
     */
    @Test
    public void aTickInstagramDoesntCountEndsThePass() {
        Grid grid = screen(40, 3, 9);
        grid.countsUpTo = 5;
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        settle();

        assertEquals(5, grid.counted);
        assertEquals("one tick past the count, then the pass stopped", 6, grid.tickedCount());
        assertNull(ActivitySelectAll.running);
    }

    /** Leaving selection ends the pass, and a tile that would open a post is never tapped. */
    @Test
    public void leavingSelectionEndsThePassWithoutOpeningAnything() {
        Grid grid = screen(40, 3, 9);
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        grid.selecting(false);
        settle();

        assertEquals("no post was opened", 0, grid.opened);
        assertNull(ActivitySelectAll.running);
        assertEquals("only the tap that started before leaving went in", 1, grid.taps);
    }

    /** A second tap while a pass is going starts nothing new. */
    @Test
    public void aSecondTapWhileGoingIsIgnored() {
        Grid grid = screen(9, 3, 9);
        grid.selecting(true);
        handTick(grid, 0);
        ActivitySelectAll.look(root());

        button().performClick();
        button().performClick();
        settle();

        assertEquals(8, grid.taps);
        assertEquals(9, grid.tickedCount());
        assertEquals(9, grid.counted);
    }

    /** Labels the count is read from, and ones it isn't. */
    @Test
    public void bracketedReadsTheCountOffALabel() {
        assertEquals(Integer.valueOf(3), ActivitySelectAll.bracketed("Unlike (3)"));
        assertEquals(Integer.valueOf(12), ActivitySelectAll.bracketed(" Delete (12) "));
        String arabic = "Delete (" + (char) 0x0663 + (char) 0x0662 + ")";
        assertEquals("Arabic-Indic digits", Integer.valueOf(32), ActivitySelectAll.bracketed(arabic));
        String wide = "Unlike" + ActivitySelectAll.WIDE_OPEN + "4" + ActivitySelectAll.WIDE_CLOSE;
        assertEquals("full-width brackets", Integer.valueOf(4), ActivitySelectAll.bracketed(wide));
        assertNull(ActivitySelectAll.bracketed("Unlike"));
        assertNull("no word before it", ActivitySelectAll.bracketed("(3)"));
        assertNull(ActivitySelectAll.bracketed("Unlike ()"));
        assertNull(ActivitySelectAll.bracketed("Unlike (3 items)"));
        assertNull(ActivitySelectAll.bracketed("Reel, 1 of 3, by @someone, shared November 3, 2026"));
        assertNull(ActivitySelectAll.bracketed(null));
    }

    // The screen: Instagram's top bar over a Bloks container holding the grid and its count.

    private Grid screen(int count, int columns, int shown) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        bar = new LinearLayout(activity);
        bar.setId(ACTION_BAR);
        TextView title = new TextView(activity);
        title.setText("Likes");
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        select = new TextView(activity);
        select.setText("Select");
        select.setTextSize(TypedValue.COMPLEX_UNIT_PX, 33);
        bar.addView(select, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 112));
        FrameLayout bloks = new FrameLayout(activity);
        bloks.setId(BLOKS);
        Grid grid = new Grid(activity, count, columns, shown);
        bloks.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Grid.ROW * 3));
        bloks.addView(new CountLabel(activity, grid, null), new FrameLayout.LayoutParams(300, 40));
        root.addView(bloks, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return grid;
    }

    /** A tap by hand on a tile, with time for Instagram to count it before anything else happens. */
    private static void handTick(Grid grid, int index) {
        grid.tiles.get(index).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        grid.taps = 0;
    }

    private View root() {
        return activity.getWindow().getDecorView();
    }

    private TextView button() {
        return (TextView) bar.findViewWithTag(ActivitySelectAll.TAG);
    }

    private int buttons() {
        int found = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (ActivitySelectAll.TAG.equals(bar.getChildAt(i).getTag())) found++;
        }
        return found;
    }

    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));
    }

    /**
     * A Bloks grid: [shown] tiles in rows of [columns], showing the items from [offset] on. Scrolling moves
     * the tiles on a screen of items at a time. Tapping a tile ticks or unticks its item while selecting,
     * and opens it otherwise. Instagram's count of the ticked items is read as a tap lands and written
     * back [COUNT_MS] later.
     */
    static final class Grid extends ViewGroup {
        static final int ROW = 100;
        static final long COUNT_MS = 60;
        final List<Tile> tiles = new ArrayList<>();
        final int columns;
        int count;
        boolean[] ticked;
        int offset;
        boolean selecting;
        /** Instagram's running count of ticked items, as its button shows it. */
        int counted;
        /** Whether the screen shows a count at all. */
        boolean counts = true;
        /** The count stops going up here, as if Instagram missed the later ticks. */
        int countsUpTo = Integer.MAX_VALUE;
        /** How many items the grid loads once it's scrolled to its end, one time. */
        int loadsMore;
        /** How many ticks Instagram allows at once. */
        int allows = Integer.MAX_VALUE;
        int taps;
        int opened;

        Grid(Context context, int count, int columns, int shown) {
            super(context);
            this.count = count;
            this.columns = columns;
            ticked = new boolean[count];
            for (int i = 0; i < shown; i++) {
                Tile tile = new Tile(context, this, i);
                tiles.add(tile);
                addView(tile);
            }
        }

        void selecting(boolean on) {
            selecting = on;
            if (!on) {
                ticked = new boolean[count];
                counted = 0;
            }
        }

        int tickedCount() {
            int n = 0;
            for (int i = 0; i < count; i++) if (ticked[i]) n++;
            return n;
        }

        String label() {
            return counts && counted > 0 ? "Unlike (" + counted + ")" : "Unlike";
        }

        void tap(int index) {
            int item = offset + index;
            if (item >= count) return;
            if (!selecting) {
                opened++;
                return;
            }
            taps++;
            if (!ticked[item] && tickedCount() >= allows) return;
            ticked[item] = !ticked[item];
            int seen = counted;
            int next = ticked[item] ? Math.min(seen + 1, Math.max(seen, countsUpTo)) : seen - 1;
            postDelayed(() -> counted = next, COUNT_MS);
        }

        @Override public boolean canScrollVertically(int direction) {
            if (direction > 0) {
                if (offset + tiles.size() < count) return true;
                if (loadsMore > 0) {
                    int more = loadsMore;
                    loadsMore = 0;
                    // Instagram fetches the next page and shows it a moment later.
                    postDelayed(() -> {
                        boolean[] grown = new boolean[count + more];
                        System.arraycopy(ticked, 0, grown, 0, count);
                        ticked = grown;
                        count += more;
                    }, 500);
                }
                return false;
            }
            return direction < 0 && offset > 0;
        }

        @Override public void scrollBy(int x, int y) {
            if (y <= 0) return;
            offset = Math.min(offset + tiles.size(), Math.max(0, count - tiles.size()));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            for (Tile tile : tiles) {
                tile.measure(MeasureSpec.makeMeasureSpec(ROW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(ROW, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < tiles.size(); i++) {
                int x = (i % columns) * ROW;
                int y = (i / columns) * ROW;
                tiles.get(i).layout(x, y, x + ROW, y + ROW);
            }
        }
    }

    /** One tile: a checkbox Bloks describes for accessibility while selecting. */
    static final class Tile extends View {
        private final Grid grid;
        private final int index;

        Tile(Context context, Grid grid, int index) {
            super(context);
            this.grid = grid;
            this.index = index;
            setOnClickListener(v -> grid.tap(index));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            int item = grid.offset + index;
            info.setCheckable(grid.selecting && item < grid.count);
            info.setChecked(grid.selecting && item < grid.count && grid.ticked[item]);
        }
    }

    /**
     * The label of Instagram's button under the grid, a Bloks text that tells accessibility what it says,
     * or a fixed label when there's no grid behind it.
     */
    static final class CountLabel extends View {
        private final Grid grid;
        private final String fixed;

        CountLabel(Context context, Grid grid, String fixed) {
            super(context);
            this.grid = grid;
            this.fixed = fixed;
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (grid == null) info.setText(fixed);
            else if (grid.selecting) info.setText(grid.label());
        }
    }
}
