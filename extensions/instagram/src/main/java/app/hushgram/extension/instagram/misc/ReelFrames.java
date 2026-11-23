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
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import app.hushgram.extension.instagram.reels.ReelBlurBars;

/**
 * Small copies of the reels playing behind a floating pill, for the glass to blur where it left a Reels
 * viewer out (#583). Drawing the viewer again into the blur froze the reel on its cover while the sound
 * played on (#222), so the glass still never draws it, and with nothing in the viewer's place the pill was
 * clear over a reel. This asks the video for a copy instead, at 1/{@link #SCALE} of its size, into the spare
 * of two bitmaps so the one the last recording drew isn't the one written, and only once the video has a
 * new frame. A place with no copy yet stays clear, the way it was. Main thread, never while drawing.
 */
final class ReelFrames {
    /**
     * The least time between two copies of one reel read the old way, which holds the main thread while
     * the GPU copies the frame (#672).
     */
    static final long COPY_EVERY_MS = 125;
    /**
     * The least time between two copies of one reel read from its surface, which leave the main thread
     * alone: one each time the glass records, so the blur keeps up with the reel. Eight copies a second
     * stepped behind it (#583).
     */
    static final long COPY_LATER_EVERY_MS = GlassTabBar.RECORD_EVERY_MS;
    /** A copy is this many times smaller than the video on each side. */
    static final int SCALE = 8;
    /** How many views under a viewer are looked at for its videos. */
    private static final int MOST_VIEWS = 400;

    /** Reads a video's frame. Swapped in tests, since a stand-in video has no surface. */
    static volatile ReelBlurBars.FrameSource source = ReelBlurBars.TEXTURE;

    /**
     * Copies a video's newest frame into a bitmap without holding up the main thread, and answers on it.
     * Swapped in tests; null where there's no such copy and {@link #source} is read instead.
     */
    interface Copier {
        void copy(TextureView video, Bitmap into, Consumer<Boolean> done);
    }

    static volatile @Nullable Copier copier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ? ReelFrames::copyLatest : null;

    /** After this many copies in a row come back empty, a video is read the old way, which holds the main thread. */
    static final int MOST_MISSES = 3;
    /**
     * How long a video read the old way waits before its surface is asked again. A reel still loading gives
     * nothing back, so three misses came quickly, and the video it played in stayed on the old way for good:
     * the blur fell behind on about half the reels until a scroll let go of the video (#583).
     */
    static final long SURFACE_AGAIN_MS = 500;

    /**
     * When the frame a video shows was made, or 0 when that isn't known. A paused reel keeps its frame, so
     * its copy isn't taken again. Swapped in tests, since a stand-in video has no surface.
     */
    static volatile ToLongFunction<TextureView> frameAt = ReelFrames::shownFrameAt;

    private static final Paint CLEAR = new Paint();
    private static final Paint FRAME = new Paint(Paint.FILTER_BITMAP_FLAG);
    @Nullable private static Handler main;

    static {
        CLEAR.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    private static final class Held {
        @Nullable Bitmap shown;
        @Nullable Bitmap spare;
        long copiedAt;
        /** When the frame in {@link #shown} was made ({@link #frameAt}), 0 when that isn't known. */
        long frameMadeAt;
        /** A copy is on its way into {@link #spare}, which nothing else writes or draws until it lands. */
        boolean copying;
        int misses;
        /** When the last copy that came back empty was asked for. */
        long missedAt;
    }

    private final WeakHashMap<View, Map<TextureView, Held>> viewers = new WeakHashMap<>();

    /**
     * Copies each video in [viewer] that crosses the strip of [parent] from [top], [tall] high and is due
     * for one, and lets go of the rest. Says whether any video crosses the strip.
     *
     * <p>On Android 14 and newer the copy is read from the video's own surface and lands a little later, so
     * the next recording after it has it, and each recording asks for the next one ({@link #COPY_LATER_EVERY_MS}).
     * Before, {@link TextureView#getBitmap} held the main thread while the GPU copied the frame, up to 28 ms
     * each, and Reels stuttered under the glass (#672), so that copy is taken only {@link #COPY_EVERY_MS}
     * apart. A video whose surface gives nothing back {@link #MOST_MISSES} times in a row is read that way,
     * and its surface is asked again each {@link #SURFACE_AGAIN_MS}, so one that starts giving copies goes
     * back to them. Either way a video still showing the frame copied last isn't copied again.
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
            if (frames.copying) continue;
            Copier later = copier;
            boolean fromSurface = later != null
                    && (frames.misses < MOST_MISSES || now - frames.missedAt >= SURFACE_AGAIN_MS);
            long every = fromSurface ? COPY_LATER_EVERY_MS : COPY_EVERY_MS;
            if (frames.shown != null && now - frames.copiedAt < every) continue;
            long made = frameAt.applyAsLong(video);
            if (frames.shown != null && made != 0 && made == frames.frameMadeAt) continue;
            int width = Math.max(2, video.getWidth() / SCALE);
            int height = Math.max(2, video.getHeight() / SCALE);
            if (fromSurface) {
                copyLater(later, video, frames, width, height, now, made);
                continue;
            }
            Bitmap copy = source.frame(video, frames.spare, width, height);
            if (copy == null) continue;
            frames.spare = frames.shown;
            frames.shown = copy;
            frames.copiedAt = now;
            frames.frameMadeAt = made;
        }
        held.clear();
        held.putAll(kept);
        return !kept.isEmpty();
    }

    private static void copyLater(Copier later, TextureView video, Held frames, int width, int height, long now,
            long made) {
        Bitmap into = frames.spare;
        if (into == null || into.isRecycled() || into.getWidth() != width || into.getHeight() != height) {
            into = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frames.spare = into;
        }
        Bitmap target = into;
        frames.copying = true;
        frames.copiedAt = now;
        // A video let go of meanwhile has its copy land in a holder nothing reads anymore.
        later.copy(video, target, copied -> {
            frames.copying = false;
            if (!copied) {
                frames.misses++;
                frames.missedAt = now;
                return;
            }
            frames.misses = 0;
            frames.spare = frames.shown;
            frames.shown = target;
            frames.frameMadeAt = made;
        });
    }

    /** {@link SurfaceTexture#getTimestamp} of the frame [video] shows, or 0 without a live surface. */
    private static long shownFrameAt(TextureView video) {
        SurfaceTexture texture = video.getSurfaceTexture();
        if (texture == null || texture.isReleased()) return 0;
        try {
            return texture.getTimestamp();
        } catch (RuntimeException gone) {
            return 0;
        }
    }

    /**
     * The newest frame the video's player handed its surface, scaled into [into] on the render thread. The
     * main thread goes on meanwhile; [done] hears on it whether the copy worked.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static void copyLatest(TextureView video, Bitmap into, Consumer<Boolean> done) {
        SurfaceTexture texture = video.getSurfaceTexture();
        if (texture == null || texture.isReleased()) {
            done.accept(false);
            return;
        }
        Handler answer = main;
        if (answer == null) main = answer = new Handler(Looper.getMainLooper());
        // A second handle on the same queue of frames: it reads the last one queued, and connects to nothing,
        // so letting go of it leaves the player's own handle as it was.
        Surface surface = new Surface(texture);
        try {
            PixelCopy.request(PixelCopy.Request.Builder.ofSurface(surface).setDestinationBitmap(into).build(),
                    answer::post, result -> {
                        surface.release();
                        done.accept(result.getStatus() == PixelCopy.SUCCESS);
                    });
        } catch (RuntimeException refused) {
            surface.release();
            done.accept(false);
        }
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
