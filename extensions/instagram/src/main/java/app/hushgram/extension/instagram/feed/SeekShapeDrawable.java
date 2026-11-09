/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * A seek bar's whole track in one of the {@link SeekShape}s. A SeekBar hands a progress drawable its
 * position as the drawable's level and its pressed state as the drawable's state, so this works on
 * any SeekBar without touching its touches or listeners. Held down it springs thicker (#592) and
 * eases back when let go.
 */
final class SeekShapeDrawable extends Drawable {
    /** A level at the end of the bar. */
    static final int LEVEL_MAX = 10000;

    /** Thickness at rest and held, in dp. */
    static final float LINE_DP = 2f, LINE_HELD_DP = 6f;
    static final float ROUNDED_DP = 6f, ROUNDED_HELD_DP = 12f;
    static final float EXPRESSIVE_DP = 6f, EXPRESSIVE_HELD_DP = 8f;
    static final float WAVE_DP = 3f, WAVE_HELD_DP = 4f;

    /** The tall handle's width, its height, the gap around it and the dot at the end of the bar, in dp. */
    private static final float HANDLE_DP = 4f, HANDLE_TALL_DP = 16f, GAP_DP = 3f, END_DOT_DP = 2f;
    /** How much taller the tall handle is while it's held. */
    private static final float HANDLE_GROWTH = 1.25f;
    /** Past this a SeekBar never makes its track taller, in dp: the tallest seek bar HushGram adds. */
    private static final float TRACK_ROOM_DP = 32f;
    /** How far the wave swings each way and how long one wave is, in dp. */
    private static final float WAVE_SWING_DP = 3f, WAVE_LENGTH_DP = 24f;

    private static final long SPRING_MS = 280;

    final SeekShape shape;
    final boolean flush;
    private final float density;
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path wave = new Path();

    private boolean held;
    private float expansion;
    @Nullable private ValueAnimator spring;

    /** Draws [shape], its colours [playedColor] and [restColor], along its bounds' bottom edge when [flush], else their middle. */
    SeekShapeDrawable(SeekShape shape, boolean flush, float density, int playedColor, int restColor) {
        this.shape = shape;
        this.flush = flush;
        this.density = density;
        colors(playedColor, restColor);
    }

    /** Whether this draws [wantedShape] in the same place. */
    boolean draws(SeekShape wantedShape, boolean wantedFlush) {
        return shape == wantedShape && flush == wantedFlush;
    }

    /** Takes these colours, unless it has them already. */
    void colors(int playedColor, int restColor) {
        if (played.getColor() == playedColor && rest.getColor() == restColor) return;
        played.setColor(playedColor);
        rest.setColor(restColor);
        invalidateSelf();
    }

    /** How thick the track is now, in pixels. */
    @VisibleForTesting
    float thickness() {
        float at;
        float heldAt;
        switch (shape) {
            case ROUNDED:
                at = ROUNDED_DP;
                heldAt = ROUNDED_HELD_DP;
                break;
            case EXPRESSIVE:
                at = EXPRESSIVE_DP;
                heldAt = EXPRESSIVE_HELD_DP;
                break;
            case WAVE:
                at = WAVE_DP;
                heldAt = WAVE_HELD_DP;
                break;
            default:
                at = LINE_DP;
                heldAt = LINE_HELD_DP;
        }
        return (at + (heldAt - at) * expansion) * density;
    }

    /** How far the shape reaches above and below its middle, in pixels. */
    private float reach() {
        return shape.tallHandle() ? HANDLE_TALL_DP * HANDLE_GROWTH * density / 2f : thickness() / 2f;
    }

    /** Where the played part ends, for a level. */
    @VisibleForTesting
    float playedEnd() {
        Rect bounds = getBounds();
        return bounds.left + bounds.width() * Math.max(0, Math.min(LEVEL_MAX, getLevel())) / (float) LEVEL_MAX;
    }

    @VisibleForTesting
    boolean held() {
        return held;
    }

    @VisibleForTesting
    float expansion() {
        return expansion;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        float thick = thickness();
        float middle = flush ? bounds.bottom - reach() : bounds.exactCenterY();
        float end = playedEnd();
        switch (shape) {
            case EXPRESSIVE:
                drawExpressive(canvas, bounds, middle, thick, end);
                break;
            case WAVE:
                drawWave(canvas, bounds, middle, thick, end);
                break;
            default:
                bar(canvas, rest, bounds.left, bounds.right, middle, thick);
                bar(canvas, played, bounds.left, end, middle, thick);
        }
    }

    private void drawExpressive(Canvas canvas, Rect bounds, float middle, float thick, float end) {
        float handle = HANDLE_DP * density;
        float gap = GAP_DP * density;
        float reach = handle / 2f + gap;
        bar(canvas, played, bounds.left, end - reach, middle, thick);
        bar(canvas, rest, end + reach, bounds.right, middle, thick);
        handle(canvas, end, middle, handle);
        float dot = END_DOT_DP * density;
        if (end + reach + dot * 2f < bounds.right) canvas.drawCircle(bounds.right - thick / 2f, middle, dot / 2f, played);
    }

    private void drawWave(Canvas canvas, Rect bounds, float middle, float thick, float end) {
        float swing = WAVE_SWING_DP * density;
        float length = WAVE_LENGTH_DP * density;
        float handle = HANDLE_DP * density;
        float reach = handle / 2f + GAP_DP * density;
        played.setStyle(Paint.Style.STROKE);
        played.setStrokeCap(Paint.Cap.ROUND);
        played.setStrokeWidth(thick);
        wave.rewind();
        float left = bounds.left + thick / 2f;
        float stop = end - reach;
        if (stop > left) {
            wave.moveTo(left, middle);
            for (float x = left + 2f; x < stop; x += 2f) {
                wave.lineTo(x, middle + (float) Math.sin((x - left) / length * 2.0 * Math.PI) * swing);
            }
            wave.lineTo(stop, middle + (float) Math.sin((stop - left) / length * 2.0 * Math.PI) * swing);
            canvas.drawPath(wave, played);
        }
        played.setStyle(Paint.Style.FILL);
        bar(canvas, rest, end + reach, bounds.right, middle, thick);
        handle(canvas, end, middle, handle);
    }

    private void handle(Canvas canvas, float x, float middle, float width) {
        float tall = HANDLE_TALL_DP * density * (1f + (HANDLE_GROWTH - 1f) * expansion);
        box.set(x - width / 2f, middle - tall / 2f, x + width / 2f, middle + tall / 2f);
        canvas.drawRoundRect(box, width / 2f, width / 2f, played);
    }

    private void bar(Canvas canvas, Paint paint, float left, float right, float middle, float thick) {
        if (right <= left) return;
        float radius = shape == SeekShape.LINE ? 0f : thick / 2f;
        box.set(left, middle - thick / 2f, right, middle + thick / 2f);
        canvas.drawRoundRect(box, radius, radius, paint);
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        for (int value : state) {
            if (value == android.R.attr.state_pressed) pressed = true;
        }
        if (pressed == held) return false;
        held = pressed;
        animate(pressed ? 1f : 0f);
        return true;
    }

    private void animate(float to) {
        if (spring != null) spring.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(expansion, to);
        animator.setDuration(SPRING_MS);
        animator.setInterpolator(new OvershootInterpolator(to > 0f ? 2.5f : 0f));
        animator.addUpdateListener(update -> {
            expansion = (float) update.getAnimatedValue();
            invalidateSelf();
        });
        spring = animator;
        animator.start();
    }

    @Override
    public void setAlpha(int alpha) {
        played.setAlpha(alpha);
        rest.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        played.setColorFilter(filter);
        rest.setColorFilter(filter);
        invalidateSelf();
    }

    /** A SeekBar's largest track grows to this, so a bar taller than Android's default has its bottom edge for the track's. */
    @Override
    public int getMinimumHeight() {
        return Math.round(TRACK_ROOM_DP * density);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
