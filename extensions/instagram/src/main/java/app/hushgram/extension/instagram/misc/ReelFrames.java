/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.reels.ReelBlurBars;

/**
 * Small copies of the reels playing behind a floating pill, for the glass to blur where it left a Reels
 * viewer out (#583). Drawing the viewer again into the blur froze the reel on its cover while the sound
 * played on (#222), so the glass still never draws it, and with nothing in the viewer's place the pill was
 * clear over a reel. This asks the video for a copy instead, about {@link #COPY_EVERY_MS} apart, at
 * 1/{@link #SCALE} of its size, into the spare of two bitmaps so the one the last recording drew isn't the
 * one written. A place with no copy yet stays clear, the way it was. Main thread, never while drawing.
 */
final class ReelFrames {
    /** The least time between two copies of one reel. A blur hides the steps. */
    static final long COPY_EVERY_MS = 125;
    /** A copy is this many times smaller than the video on each side. */
    static final int SCALE = 8;
    /** How many views under a viewer are looked at for its videos. */
    private static final int MOST_VIEWS = 400;

    /** Reads a video's frame. Swapped in tests, since a stand-in video has no surface. */
    static volatile ReelBlurBars.FrameSource source = ReelBlurBars.TEXTURE;

    private static final Paint CLEAR = new Paint();
    private static final Paint FRAME = new Paint(Paint.FILTER_BITMAP_FLAG);

    static {
        CLEAR.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    private static final class Held {
        @Nullable Bitmap shown;
        @Nullable Bitmap spare;
        long copiedAt;
    }

    private final WeakHashMap<View, Map<TextureView, Held>> viewers = new WeakHashMap<>();

    /**
     * Copies each video in [viewer] that crosses the strip of [parent] from [top], [tall] high and is due
     * for one, and lets go of the rest. Says whether any video crosses the strip.
     */
    boolean copy(View viewer, ViewGroup parent, float top, float tall, long now) {
        Map<TextureView, Held> held = viewers.get(viewer);
        if (held == null) {
            held = new HashMap<>();
            viewers.put(viewer, held);
        }
        Map<TextureView, Held> kept = new HashMap<>();
        float[] at = new float[2];
        for (TextureView video : videos(viewer)) {
            at[0] = at[1] = 0f;
            if (!offset(video, parent, at) || at[1] + video.getHeight() <= top || at[1] >= top + tall) continue;
            Held frames = held.get(video);
            if (frames == null) frames = new Held();
            kept.put(video, frames);
            if (frames.shown != null && now - frames.copiedAt < COPY_EVERY_MS) continue;
            Bitmap copy = source.frame(video, frames.spare, Math.max(2, video.getWidth() / SCALE),
                    Math.max(2, video.getHeight() / SCALE));
            if (copy == null) continue;
            frames.spare = frames.shown;
            frames.shown = copy;
            frames.copiedAt = now;
        }
        held.clear();
        held.putAll(kept);
        return !kept.isEmpty();
    }

    /**
     * Paints [viewer]'s place in [canvas], which is at the viewer's parent: cleared, then each video's
     * last copy where the video is, through the video's own crop.
     */
    void draw(Canvas canvas, View viewer) {
        int saved = canvas.save();
        canvas.translate(viewer.getLeft(), viewer.getTop());
        if (!viewer.getMatrix().isIdentity()) canvas.concat(viewer.getMatrix());
        RectF place = new RectF(0, 0, viewer.getWidth(), viewer.getHeight());
        canvas.clipRect(place);
        canvas.drawRect(place, CLEAR);
        Map<TextureView, Held> held = viewers.get(viewer);
        if (held != null) {
            float[] at = new float[2];
            Matrix crop = new Matrix();
            for (Map.Entry<TextureView, Held> entry : held.entrySet()) {
                TextureView video = entry.getKey();
                Bitmap frame = entry.getValue().shown;
                at[0] = at[1] = 0f;
                if (frame == null || frame.isRecycled() || !offset(video, viewer, at)) continue;
                int one = canvas.save();
                canvas.translate(at[0], at[1]);
                RectF whole = new RectF(0, 0, video.getWidth(), video.getHeight());
                canvas.clipRect(whole);
                video.getTransform(crop);
                canvas.concat(crop);
                canvas.drawBitmap(frame, null, whole, FRAME);
                canvas.restoreToCount(one);
            }
        }
        canvas.restoreToCount(saved);
    }

    /** Lets go of the copies of every viewer not in [present], the ones the last recording left out. */
    void keepOnly(Set<View> present) {
        for (Iterator<View> it = viewers.keySet().iterator(); it.hasNext(); ) {
            if (!present.contains(it.next())) it.remove();
        }
    }

    void clear() {
        viewers.clear();
    }

    /** Adds [view]'s place in [ancestor] to [at], and says whether [ancestor] is above it. */
    private static boolean offset(View view, View ancestor, float[] at) {
        View step = view;
        for (int depth = 0; depth < 100 && step != ancestor; depth++) {
            if (!(step.getParent() instanceof View)) return false;
            View up = (View) step.getParent();
            at[0] += step.getX() - up.getScrollX();
            at[1] += step.getY() - up.getScrollY();
            step = up;
        }
        return step == ancestor;
    }

    /** Every shown TextureView under [viewer]. */
    private static java.util.List<TextureView> videos(View viewer) {
        java.util.List<TextureView> found = new java.util.ArrayList<>(2);
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.push(viewer);
        for (int seen = 0; !pending.isEmpty() && seen < MOST_VIEWS; seen++) {
            View view = pending.pop();
            if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
            if (view instanceof TextureView) {
                found.add((TextureView) view);
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.push(group.getChildAt(i));
            }
        }
        return found;
    }
}
