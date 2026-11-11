/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Puts a {@link SeekShape} on a SeekBar and takes it off again (#586). Only the bar's drawables
 * change, and its side padding for a line flush with the edges, so its touches and listeners stay
 * as they were. What a shape replaced is kept and given back when the bar goes back to
 * {@link SeekShape#STANDARD}. Main thread only.
 *
 * <p>The same choice draws the segments of a story's progress bar and the seek bar of a video opened
 * from a chat (#630), through the hooks below.
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

    static final String STORY_HOOK = "story progress shape";
    static final String CHAT_HOOK = "chat video seek bar shape";

    /** The shape the story progress bar drawing now takes, read at the top of its onDraw, and the screen's density. */
    private static SeekShape storyShape = SeekShape.STANDARD;
    private static float storyDensity = 1f;
    private static final RectF SEGMENT = new RectF();
    private static final Path SEGMENT_WAVE = new Path();
    /** Each header a story progress bar draws past, with whether it clipped its children before. */
    private static final Map<ViewGroup, Boolean> UNCLIPPED = new WeakHashMap<>();

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

    /**
     * First thing in the story progress bar's onDraw (#630): reads the shape its segments take this
     * frame and, while it isn't {@link SeekShape#STANDARD}, lets [bar] draw past its few pixels of
     * height into the header around it, which a thicker shape needs. Back on standard the header
     * clips again as it did. Never throws.
     */
    public static void storyBar(View bar) {
        try {
            SeekShape shape = wanted();
            storyDensity = bar.getResources().getDisplayMetrics().density;
            storyShape = shape;
            ViewParent parent = bar.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup header = (ViewGroup) parent;
            if (shape == SeekShape.STANDARD) {
                Boolean clipped = UNCLIPPED.remove(header);
                if (clipped != null && clipped != header.getClipChildren()) header.setClipChildren(clipped);
            } else if (!UNCLIPPED.containsKey(header)) {
                UNCLIPPED.put(header, header.getClipChildren());
                if (header.getClipChildren()) header.setClipChildren(false);
            }
        } catch (Throwable failure) {
            storyShape = SeekShape.STANDARD;
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
        }
    }

    /**
     * Stands in for each Canvas.drawRoundRect the story progress bar makes (#630), with the same
     * arguments. On {@link SeekShape#STANDARD} it makes that very call, so the bar looks as Instagram
     * drew it; otherwise it draws the segment in the shape {@link #storyBar} read.
     */
    public static void storySegment(Canvas canvas, RectF rect, float rx, float ry, Paint paint) {
        SeekShape shape = storyShape;
        if (shape != SeekShape.STANDARD) {
            try {
                SeekShapeDrawable.segment(canvas, rect, paint, shape, storyDensity, SEGMENT, SEGMENT_WAVE);
                return;
            } catch (Throwable failure) {
                storyShape = SeekShape.STANDARD;
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
            }
        }
        canvas.drawRoundRect(rect, rx, ry, paint);
    }

    /**
     * Once Instagram has built the seek bar of a video opened from a chat (#630): puts the chosen
     * shape on it, in the solid look the reels' bar takes. Standard leaves it as Instagram made it.
     */
    public static void chatVideo(SeekBar bar) {
        try {
            SeekShape shape = wanted();
            if (shape != SeekShape.STANDARD) apply(bar, shape, OverlayLook.SOLID, false);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, CHAT_HOOK, failure);
        }
    }

    static void resetForTests() {
        storyShape = SeekShape.STANDARD;
        storyDensity = 1f;
        UNCLIPPED.clear();
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
