/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Where the video a SurfaceView shows really is, for the copies the glass and the Lens buttons make of it
 * (#462, #583, #801).
 *
 * <p>Instagram's Groot player can play a SurfaceView's video into a Surface it makes from a SurfaceControl
 * of its own, set under the SurfaceView's. The SurfaceView's own surface then never gets a frame, so a
 * PixelCopy of the SurfaceView fails: the glass kept a frame from long ago or none, and Lens got a black
 * reel. The patch hands each such Surface here, with its SurfaceView, the moment the player makes it,
 * and {@link #copy} reads that one first.
 *
 * <p>The player sizes its buffer to cover the SurfaceView and centers it there, so the view shows the
 * middle of the frame. From Android 14 a copy learns the frame's size and takes just that middle; before
 * that a copy has no way to learn it and stretches the whole frame over the view.
 */
public final class PlayerSurfaces {
    /** How long a frame size learned from a whole copy is trusted before the next copy learns it again. */
    static final long SIZE_FOR_MS = 1000;

    private static final Map<SurfaceView, WeakReference<Surface>> DRAWN = new WeakHashMap<>();
    private static final Map<Surface, Size> SIZES = new WeakHashMap<>();
    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final class Size {
        final int width;
        final int height;
        final long learnedAt;

        Size(int width, int height, long learnedAt) {
            this.width = width;
            this.height = height;
            this.learnedAt = learnedAt;
        }
    }

    private PlayerSurfaces() {
    }

    /**
     * Injected right after Instagram's Groot listener sets the Surface its player draws [view]'s video
     * into. Never throws.
     */
    public static void drawsInto(@Nullable SurfaceView view, @Nullable Surface surface) {
        try {
            if (view == null) return;
            synchronized (DRAWN) {
                if (surface == null) DRAWN.remove(view);
                else DRAWN.put(view, new WeakReference<>(surface));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "player surface", failure);
        }
    }

    /** The Surface the player draws [view]'s video into, while it's valid, or null. */
    @Nullable
    static Surface player(SurfaceView view) {
        WeakReference<Surface> held;
        synchronized (DRAWN) {
            held = DRAWN.get(view);
        }
        Surface surface = held == null ? null : held.get();
        return surface != null && surface.isValid() ? surface : null;
    }

    /** Whether [view] has a valid surface to copy its video from, the player's or its own. */
    public static boolean hasFrame(SurfaceView view) {
        return player(view) != null || own(view) != null;
    }

    @Nullable
    private static Surface own(SurfaceView view) {
        Surface surface = view.getHolder().getSurface();
        return surface != null && surface.isValid() ? surface : null;
    }

    /**
     * Copies what [view] shows into [into], which has [view]'s shape, and tells [done] through [main]
     * whether it worked. PixelCopy may throw for a surface that went away since the check.
     */
    public static void copy(SurfaceView view, Bitmap into, Handler main, Consumer<Boolean> done) {
        Surface player = player(view);
        if (player != null && Build.VERSION.SDK_INT >= 34) {
            copyShown(player, view.getWidth(), view.getHeight(), into, main, done);
            return;
        }
        Surface source = player != null ? player : own(view);
        if (source == null) {
            done.accept(false);
            return;
        }
        PixelCopy.request(source, into, result -> done.accept(result == PixelCopy.SUCCESS), main);
    }

    @RequiresApi(34)
    private static void copyShown(Surface player, int width, int height, Bitmap into, Handler main, Consumer<Boolean> done) {
        Size size;
        synchronized (SIZES) {
            size = SIZES.get(player);
        }
        long now = SystemClock.uptimeMillis();
        if (size != null && now - size.learnedAt < SIZE_FOR_MS) {
            PixelCopy.request(PixelCopy.Request.Builder.ofSurface(player)
                            .setSourceRect(shownPart(size.width, size.height, width, height))
                            .setDestinationBitmap(into).build(),
                    main::post, result -> done.accept(result.getStatus() == PixelCopy.SUCCESS));
            return;
        }
        PixelCopy.request(PixelCopy.Request.Builder.ofSurface(player).build(), main::post, result -> {
            Bitmap frame = result.getStatus() == PixelCopy.SUCCESS ? result.getBitmap() : null;
            if (frame == null) {
                done.accept(false);
                return;
            }
            boolean drawn = false;
            try {
                synchronized (SIZES) {
                    SIZES.put(player, new Size(frame.getWidth(), frame.getHeight(), SystemClock.uptimeMillis()));
                }
                drawShown(frame, width, height, into);
                drawn = true;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "player surface", failure);
            } finally {
                frame.recycle();
            }
            done.accept(drawn);
        });
    }

    /** Draws the part of [frame] a [width] by [height] view shows over the whole of [into]. */
    static void drawShown(Bitmap frame, int width, int height, Bitmap into) {
        Rect shown = shownPart(frame.getWidth(), frame.getHeight(), width, height);
        new Canvas(into).drawBitmap(frame, shown, new Rect(0, 0, into.getWidth(), into.getHeight()), FILTER);
    }

    /**
     * The part of a [frameWidth] by [frameHeight] frame a [width] by [height] view shows, the frame
     * scaled to cover the view and centered on it, as Instagram's player places it. The whole frame
     * when the view has no size yet.
     */
    static Rect shownPart(int frameWidth, int frameHeight, int width, int height) {
        if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0) return new Rect(0, 0, Math.max(frameWidth, 0), Math.max(frameHeight, 0));
        double scale = Math.max((double) width / frameWidth, (double) height / frameHeight);
        int shownWidth = (int) Math.min(frameWidth, Math.max(1, Math.round(width / scale)));
        int shownHeight = (int) Math.min(frameHeight, Math.max(1, Math.round(height / scale)));
        int left = (frameWidth - shownWidth) / 2;
        int top = (frameHeight - shownHeight) / 2;
        return new Rect(left, top, left + shownWidth, top + shownHeight);
    }

    static void resetForTests() {
        synchronized (DRAWN) {
            DRAWN.clear();
        }
        synchronized (SIZES) {
            SIZES.clear();
        }
    }
}
