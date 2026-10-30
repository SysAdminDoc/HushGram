/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package androidx.recyclerview.widget;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.instagram.ui.widget.spinner.SpinnerImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Stands in for AndroidX's RecyclerView as a profile's Reels grid uses it, by the name it keeps:
 * rows of tiles, of which only the rows on screen are bound, moved by a finger's drag or by
 * scrollBy. Like 450's grid on a phone, it asks Instagram for more only at the end of a drag: a
 * scrollBy to the end loads nothing.
 */
public class RecyclerView extends LinearLayout {
    /** How tall a row is, how many tiles it holds, and how many rows show at once. */
    public static final int ROW = 400;
    public static final int PER_ROW = 3;
    public static final int SHOWN_ROWS = 2;

    /** The reels Instagram has loaded, in the grid's order. */
    public final List<String> reels = new ArrayList<>();
    /** Every scrollBy asked for, in pixels. */
    public final List<Integer> scrolls = new ArrayList<>();
    /** How far each drag pulled the grid on, in pixels, down positive. */
    public final List<Integer> drags = new ArrayList<>();
    /** What Instagram does when a drag ends at the grid's last loaded row, such as loading the next page. */
    public Runnable atEnd;
    /**
     * How many grey tiles with no name show while no reel is loaded, as Instagram shows nine before
     * a Reels tab's first page, as many as fill its screen.
     */
    public int placeholders;
    /**
     * How far past its loaded end the grid still says more lies below, as a phone's grid does when
     * its scroll range is an estimate (451, #30): a drag there moves nothing.
     */
    public int slack;
    /**
     * Whether the next page is loading, shown as 451 shows it: a row holding Instagram's spinner
     * below the last loaded row (#225).
     */
    public boolean loading;
    /** Makes the view of a tile for a reel. */
    public Function<String, View> tiles;
    private int offset;
    private float lastY;
    private int pulled;

    public RecyclerView(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    /** The first row on screen. */
    public int firstRow() {
        return offset / ROW;
    }

    /**
     * Binds the rows on screen, a tile named by its reel for each, as Instagram's binder does, or
     * the placeholders while no reel is loaded, then the spinner's row while a page loads.
     */
    public void bind() {
        removeAllViews();
        int count = count();
        int last = (offset + SHOWN_ROWS * ROW - 1) / ROW;
        for (int row = firstRow(); row <= last + 1; row++) {
            if (row * PER_ROW >= count) {
                if (loading) {
                    FrameLayout footer = new FrameLayout(getContext());
                    footer.addView(new SpinnerImageView(getContext()), new FrameLayout.LayoutParams(996, 135));
                    addView(footer, new LinearLayout.LayoutParams(1080, 219));
                }
                break;
            }
            if (row > last) break;
            LinearLayout line = new LinearLayout(getContext());
            for (int at = row * PER_ROW; at < Math.min(count, (row + 1) * PER_ROW); at++) {
                View tile;
                if (reels.isEmpty()) {
                    tile = new View(getContext());
                } else {
                    tile = tiles.apply(reels.get(at));
                    tile.setTransitionName(reels.get(at));
                }
                line.addView(tile, new LinearLayout.LayoutParams(360, ROW));
            }
            addView(line, new LinearLayout.LayoutParams(1080, ROW));
        }
    }

    /** How many tiles the grid holds: its reels, or its placeholders while it has none. */
    private int count() {
        return reels.isEmpty() ? placeholders : reels.size();
    }

    @Override public boolean canScrollVertically(int direction) {
        return direction > 0 ? offset < end() + slack : offset > 0;
    }

    @Override public int computeVerticalScrollOffset() {
        return offset;
    }

    @Override public void scrollBy(int x, int y) {
        scrolls.add(y);
        move(y);
    }

    /** A finger's drag: the grid follows it, and letting go at the loaded end asks for more. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                pulled = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                int by = Math.round(lastY - event.getY());
                lastY = event.getY();
                pulled += by;
                move(by);
                break;
            case MotionEvent.ACTION_UP:
                drags.add(pulled);
                if (offset >= end() && atEnd != null) atEnd.run();
                break;
            default:
                break;
        }
        return true;
    }

    private void move(int by) {
        offset = Math.max(0, Math.min(end(), offset + by));
        bind();
    }

    /** The furthest the grid scrolls with the reels loaded so far. */
    private int end() {
        int rows = (count() + PER_ROW - 1) / PER_ROW;
        return Math.max(0, (rows - SHOWN_ROWS) * ROW);
    }
}
