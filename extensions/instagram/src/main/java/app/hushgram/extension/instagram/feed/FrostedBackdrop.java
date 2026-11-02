/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.RequiresApi;

import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.misc.GlassBlur;
import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The frosted glass under one of HushGram's small overlays on a photo or video (#471), the way the glass
 * top bar makes its own: the views drawn before the overlay in its parent, under the overlay and the
 * blur's reach past it, are recorded into a render node at a quarter of their size and blurred there on
 * the GPU. The node keeps a reference to each view's own render node, so a video playing under the
 * overlay plays on in the glass, and nothing is read back from the screen.
 *
 * <p>Android 12 and later, on a hardware canvas. Anywhere else, and while recordings fail
 * ({@link GlassTabBar.BlurTries}), {@link #draw} draws nothing and says so, and the overlay draws its
 * see-through tint. Main thread.
 */
final class FrostedBackdrop {
    /** The blur's radius. Smaller than the bars', since the overlays are small and sit over the picture itself. */
    static final int BLUR_DP = 12;
    /** Worked out at a quarter of the size, which also blurs it more for the same work. */
    static final int DOWNSAMPLE = 4;

    private final String family;
    private final String hook;
    private final String name;
    private final GlassTabBar.BlurTries tries = new GlassTabBar.BlurTries();
    private final Set<View> leftOut = new HashSet<>();
    private final Path clip = new Path();
    private RenderNode node;
    private boolean recorded;
    private long recordedAt;
    private float lastX, lastY, nodePad;
    private int lastWidth, lastHeight;

    FrostedBackdrop(String family, String hook, String name) {
        this.family = family;
        this.hook = hook;
        this.name = name;
    }

    /**
     * Records what's under [overlay] at [now] when it moved or changed size since the last recording, or
     * {@link GlassTabBar#RECORD_EVERY_MS} after it, for views coming and going under it.
     */
    void record(View overlay, long now) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        recordUnder(overlay, now);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void recordUnder(View overlay, long now) {
        ViewParent up = overlay.getParent();
        int width = overlay.getWidth();
        int height = overlay.getHeight();
        if (!(up instanceof ViewGroup) || width <= 0 || height <= 0) {
            recorded = false;
            return;
        }
        ViewGroup source = (ViewGroup) up;
        int index = source.indexOfChild(overlay);
        if (index <= 0) {
            recorded = false;
            return;
        }
        float x = overlay.getX();
        float y = overlay.getY();
        boolean same = recorded && x == lastX && y == lastY && width == lastWidth && height == lastHeight;
        if (same && now - recordedAt < GlassTabBar.RECORD_EVERY_MS) return;
        if (tries.resting(now)) return;
        try {
            float density = overlay.getResources().getDisplayMetrics().density;
            float scale = 1f / DOWNSAMPLE;
            float pad = GlassBlur.radiusPx(BLUR_DP, density);
            float wide = width + 2 * pad;
            float tall = height + 2 * pad;
            int nodeWidth = Math.max(1, (int) Math.ceil(wide * scale));
            int nodeHeight = Math.max(1, (int) Math.ceil(tall * scale));
            RenderNode backdrop = node;
            if (backdrop == null) {
                backdrop = new RenderNode(name);
                float blur = GlassBlur.effectRadius(BLUR_DP, density, scale);
                ColorMatrix saturate = new ColorMatrix();
                saturate.setSaturation(1.5f);
                backdrop.setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate)),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                node = backdrop;
            }
            backdrop.setPosition(0, 0, nodeWidth, nodeHeight);
            float left = x - pad;
            float top = y - pad;
            Canvas recording = backdrop.beginRecording(nodeWidth, nodeHeight);
            try {
                recording.scale(scale, scale);
                recording.translate(-left, -top);
                recording.clipRect(left, top, left + wide, top + tall);
                GlassTabBar.surfacePaths(source, index, leftOut);
                for (int i = 0; i < index; i++) {
                    View sibling = source.getChildAt(i);
                    if (sibling.getWidth() <= 0 || sibling.getHeight() <= 0) continue;
                    GlassTabBar.drawLeavingOutSurfaces(recording, sibling, leftOut, 0);
                }
            } finally {
                backdrop.endRecording();
            }
            nodePad = pad;
            recorded = true;
            recordedAt = now;
            lastX = x;
            lastY = y;
            lastWidth = width;
            lastHeight = height;
            tries.worked();
        } catch (Throwable failure) {
            // A recording cut short isn't shown: the overlay draws its see-through tint until one works.
            recorded = false;
            if (tries.failed(now)) HookStatus.threw(family, hook, failure);
        }
    }

    /** Draws the blur inside [shape], a round rect with [radius] corners in the overlay's own coordinates, and says whether it did. */
    boolean draw(Canvas canvas, RectF shape, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !recorded || node == null
                || !canvas.isHardwareAccelerated()) return false;
        int saved = canvas.save();
        try {
            clip.rewind();
            clip.addRoundRect(shape, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.translate(-nodePad, -nodePad);
            canvas.scale(DOWNSAMPLE, DOWNSAMPLE);
            canvas.drawRenderNode(node);
        } finally {
            canvas.restoreToCount(saved);
        }
        return true;
    }

    /** Whether a recording is ready to draw. */
    boolean recorded() {
        return recorded;
    }

    /** Lets the recording go, for a look that isn't frosted any more. The next {@link #record} starts over. */
    void release() {
        recorded = false;
        RenderNode held = node;
        node = null;
        if (held != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) held.discardDisplayList();
    }
}
