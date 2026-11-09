/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.drawable.Drawable;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Puts a {@link SeekShape} on a SeekBar and takes it off again (#586). Only the bar's drawables
 * change, and its side padding for a line flush with the edges, so its touches and listeners stay
 * as they were. What a shape replaced is kept and given back when the bar goes back to
 * {@link SeekShape#STANDARD}. Main thread only.
 */
public final class SeekShapes {
    /** No handle: the shape draws its own, and a SeekBar lays out around this one's nothing. */
    private static final class NoHandle extends android.graphics.drawable.ColorDrawable {
        NoHandle() {
            super(0);
        }

        @Override
        public int getIntrinsicWidth() {
            return 0;
        }

        @Override
        public int getIntrinsicHeight() {
            return 0;
        }
    }

    private static final class Stock {
        @Nullable final Drawable progress;
        @Nullable final Drawable thumb;
        final int thumbOffset;
        final int paddingLeft;
        final int paddingRight;

        Stock(SeekBar bar) {
            progress = bar.getProgressDrawable();
            thumb = bar.getThumb();
            thumbOffset = bar.getThumbOffset();
            paddingLeft = bar.getPaddingLeft();
            paddingRight = bar.getPaddingRight();
        }
    }

    private static final Map<SeekBar, Stock> STOCK = new WeakHashMap<>();

    private SeekShapes() {
    }

    /** The shape chosen for every seek bar: {@link SeekShape#STANDARD} while the settings can't be read or HushGram is paused. */
    public static SeekShape wanted() {
        return Utils.settingsReady() ? Settings.SEEK_BAR_SHAPE.get() : SeekShape.STANDARD;
    }

    /** Whether a shape other than the standard one is chosen. */
    public static boolean changed() {
        return wanted() != SeekShape.STANDARD;
    }

    /** Draws [bar] in [shape] with [look]'s colours, along its bottom edge when [flush]. {@link SeekShape#STANDARD} gives back what it had. */
    public static void apply(SeekBar bar, SeekShape shape, OverlayLook look, boolean flush) {
        if (shape == SeekShape.STANDARD) {
            restore(bar);
            return;
        }
        int played = GlassSeekBar.progress(look);
        int rest = GlassSeekBar.track(look);
        Drawable now = bar.getProgressDrawable();
        if (now instanceof SeekShapeDrawable && ((SeekShapeDrawable) now).draws(shape, flush)) {
            ((SeekShapeDrawable) now).colors(played, rest);
            return;
        }
        if (!STOCK.containsKey(bar)) STOCK.put(bar, new Stock(bar));
        float density = bar.getResources().getDisplayMetrics().density;
        bar.setProgressDrawable(new SeekShapeDrawable(shape, flush, density, played, rest));
        if (!(bar.getThumb() instanceof NoHandle)) bar.setThumb(new NoHandle());
        bar.setThumbOffset(0);
        boolean edgeToEdge = flush && shape == SeekShape.LINE;
        Stock stock = STOCK.get(bar);
        int left = edgeToEdge || stock == null ? 0 : stock.paddingLeft;
        int right = edgeToEdge || stock == null ? 0 : stock.paddingRight;
        bar.setPadding(left, bar.getPaddingTop(), right, bar.getPaddingBottom());
    }

    /** Gives [bar] back what a shape replaced, and does nothing to a bar with no shape on it. */
    public static void restore(SeekBar bar) {
        Stock stock = STOCK.remove(bar);
        if (stock == null) return;
        if (bar.getProgressDrawable() instanceof SeekShapeDrawable) bar.setProgressDrawable(stock.progress);
        if (bar.getThumb() instanceof NoHandle) {
            bar.setThumb(stock.thumb);
            bar.setThumbOffset(stock.thumbOffset);
        }
        bar.setPadding(stock.paddingLeft, bar.getPaddingTop(), stock.paddingRight, bar.getPaddingBottom());
    }
}
