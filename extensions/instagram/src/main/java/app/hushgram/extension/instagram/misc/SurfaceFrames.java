/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Small copies of the frames playing in the SurfaceViews under the glass (#144), for the pill to blur in
 * their place. A SurfaceView's video is a layer of its own under the window, never in the views, so the
 * recording the glass blurs can't hold it ({@link GlassTabBar#drawLeavingOutSurfaces}), and drawing the
 * SurfaceView a second time moved its video (#136). With nothing in its place, the views round the video
 * drew there instead, a feed post's black background, and the pill went black over a playing video.
 *
 * <p>Each copy is the whole frame at {@code downsample} of the view's size, at most {@link #MOST_PX} on its
 * long side, read with {@link PixelCopy} and handed back on the main thread. One copy is in flight for each
 * SurfaceView, at most one every {@link #COPY_EVERY_MS}, into the spare of two bitmaps, so the one the last
 * recording drew isn't the one being written. A frame that won't copy (a protected video, a surface going
 * away) leaves the place clear, and after {@link #TRIES} in a row that SurfaceView rests for {@link #REST_MS}.
 * Main thread.
 */
final class SurfaceFrames {
    /** The least time between two copies of one SurfaceView's frame. */
    static final long COPY_EVERY_MS = 66;
    /** Copies in a row that fail before a SurfaceView rests. */
    static final int TRIES = 3;
    /** How long a SurfaceView whose frames keep failing to copy rests before it's tried again. */
    static final long REST_MS = 2000;
    /** The longest side a copy gets, in pixels. */
    static final int MOST_PX = 480;

    /** Copies [view]'s frame into [into] and tells [done] on the main thread whether it worked. */
    interface Copier {
        void copy(SurfaceView view, Bitmap into, Done done);
    }

    interface Done {
        void copied(boolean worked);
    }

    /** How frames are copied. Tests stand in. */
    static volatile Copier copier = SurfaceFrames::pixelCopy;

    /** Whether a SurfaceView has a surface to copy from. Tests stand in. */
    static volatile Predicate<SurfaceView> hasSurface = view -> {
        Surface surface = view.getHolder().getSurface();
        return surface != null && surface.isValid();
    };

    @Nullable private static Handler main;

    private static final class Frames {
        @Nullable Bitmap shown;
        @Nullable Bitmap spare;
        boolean copying;
        boolean askedBefore;
        long askedAt;
        int failedInARow;
        long restUntil;
    }

    private final WeakHashMap<View, Frames> frames = new WeakHashMap<>();
    private final int downsample;

    SurfaceFrames(int downsample) {
        this.downsample = Math.max(1, downsample);
    }

    /** The last frame copied from [view], or null when none has been yet or its copies keep failing. */
    @Nullable
    Bitmap frameOf(View view) {
        Frames held = frames.get(view);
        return held == null ? null : held.shown;
    }

    /**
     * Asks for a fresh copy of [view]'s frame when it's showing with a surface, none is in flight, it isn't
     * resting and {@link #COPY_EVERY_MS} have passed since the last ask, and says whether it did. [fresh]
     * runs once a copy lands.
     */
    boolean ask(SurfaceView view, long now, Runnable fresh) {
        int width = view.getWidth(), height = view.getHeight();
        if (width <= 0 || height <= 0 || !view.isShown() || !hasSurface.test(view)) return false;
        Frames held = frames.get(view);
        if (held == null) {
            held = new Frames();
            frames.put(view, held);
        }
        if (held.copying || now < held.restUntil) return false;
        if (held.askedBefore && now - held.askedAt < COPY_EVERY_MS) return false;
        int[] size = copySize(width, height, downsample);
        Bitmap into = held.spare;
        if (into == null || into.getWidth() != size[0] || into.getHeight() != size[1]) {
            into = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        }
        held.spare = null;
        held.copying = true;
        held.askedBefore = true;
        held.askedAt = now;
        Frames target = held;
        Bitmap copy = into;
        try {
            copier.copy(view, into, worked -> landed(target, copy, worked, now, fresh));
        } catch (RuntimeException failure) {
            // PixelCopy refuses a surface that went away between the check and the ask.
            landed(target, copy, false, now, fresh);
        }
        return true;
    }

    private static void landed(Frames held, Bitmap copy, boolean worked, long askedAt, Runnable fresh) {
        held.copying = false;
        if (worked) {
            held.spare = held.shown;
            held.shown = copy;
            held.failedInARow = 0;
            fresh.run();
            return;
        }
        held.spare = copy;
        if (++held.failedInARow >= TRIES) {
            held.failedInARow = 0;
            held.shown = null;
            held.restUntil = askedAt + REST_MS;
        }
    }

    /** Lets go of the frames of every SurfaceView not in [present], the ones the last recording found. */
    void keepOnly(Set<View> present) {
        for (Iterator<Map.Entry<View, Frames>> it = frames.entrySet().iterator(); it.hasNext(); ) {
            if (!present.contains(it.next().getKey())) it.remove();
        }
    }

    void clear() {
        frames.clear();
    }

    /** Copies held, for tests. */
    int size() {
        return frames.size();
    }

    /** A copy's width and height: [width] by [height] over [downsample], at most {@link #MOST_PX} on the long side, at least 1. */
    static int[] copySize(int width, int height, int downsample) {
        float scale = 1f / Math.max(1, downsample);
        int longest = Math.max(width, height);
        if (longest * scale > MOST_PX) scale = (float) MOST_PX / longest;
        return new int[] {Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    private static void pixelCopy(SurfaceView view, Bitmap into, Done done) {
        Handler handler = main;
        if (handler == null) main = handler = new Handler(Looper.getMainLooper());
        PixelCopy.request(view, into, result -> done.copied(result == PixelCopy.SUCCESS), handler);
    }
}
