/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;

/** A resource-free switch drawn with HushGram's monochrome settings palette. */
final class ExpressiveSwitch extends CompoundButton {
    private static final long STATE_ANIMATION_MS = 200L;
    private static final float TRACK_WIDTH_DP = 52f;
    private static final float TRACK_HEIGHT_DP = 32f;
    private static final float HANDLE_OFF_DP = 16f;
    private static final float HANDLE_ON_DP = 24f;
    private static final float HANDLE_PRESSED_DP = 28f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackBounds = new RectF();
    private final RectF strokeBounds = new RectF();
    private final Path checkMark = new Path();
    private float checkedFraction;
    private float pressedFraction;
    private ValueAnimator checkedAnimator;
    private ValueAnimator pressedAnimator;

    ExpressiveSwitch(Context context) {
        super(context);
        setButtonDrawable(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int preferredWidth = dp(64);
        int preferredHeight = dp(48);
        setMeasuredDimension(
                resolveSize(preferredWidth, widthMeasureSpec),
                resolveSize(preferredHeight, heightMeasureSpec));
    }

    @Override
    public void setChecked(boolean checked) {
        if (checked == isChecked()) return;
        super.setChecked(checked);
        // A listener can reject the tap and synchronously call setChecked with the saved value.
        // That nested call already animated the rollback; do not restart it toward the attempted value.
        if (isChecked() != checked) return;
        animateCheckedFraction(isChecked() ? 1f : 0f);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        animatePressedFraction(pressed && isEnabled() ? 1f : 0f);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) animatePressedFraction(0f);
        invalidate();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        // No drawable owns the focus effect, so this view paints it in onDraw.
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        invalidate();
    }

    private void animateCheckedFraction(float target) {
        cancelCheckedAnimator();
        if (mustSkipAnimation()) {
            checkedFraction = target;
            invalidate();
            return;
        }
        ValueAnimator animation = new ValueAnimator();
        animation.setFloatValues(checkedFraction, target);
        animation.setDuration(STATE_ANIMATION_MS);
        animation.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animation.addUpdateListener(value -> {
            checkedFraction = (float) value.getAnimatedValue();
            invalidate();
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator finished) {
                if (checkedAnimator == finished) checkedAnimator = null;
            }
        });
        checkedAnimator = animation;
        animation.start();
    }

    private void animatePressedFraction(float target) {
        cancelPressedAnimator();
        if (!isLaidOut() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            pressedFraction = target;
            invalidate();
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(pressedFraction, target);
        animation.setDuration(STATE_ANIMATION_MS);
        animation.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animation.addUpdateListener(value -> {
            pressedFraction = (float) value.getAnimatedValue();
            invalidate();
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator finished) {
                if (pressedAnimator == finished) pressedAnimator = null;
            }
        });
        pressedAnimator = animation;
        animation.start();
    }

    private boolean mustSkipAnimation() {
        return !isLaidOut() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled();
    }

    private void cancelCheckedAnimator() {
        if (checkedAnimator != null) {
            ValueAnimator previous = checkedAnimator;
            checkedAnimator = null;
            previous.cancel();
        }
    }

    private void cancelPressedAnimator() {
        if (pressedAnimator != null) {
            ValueAnimator previous = pressedAnimator;
            pressedAnimator = null;
            previous.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getResources().getDisplayMetrics().density;
        float left = (getWidth() - TRACK_WIDTH_DP * scale) / 2f;
        float centerX = left + TRACK_WIDTH_DP * scale / 2f;
        float centerY = getHeight() / 2f;
        trackBounds.set(left, centerY - TRACK_HEIGHT_DP * scale / 2f,
                left + TRACK_WIDTH_DP * scale, centerY + TRACK_HEIGHT_DP * scale / 2f);

        drawTrack(canvas, scale);
        float directionFraction = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? 1f - checkedFraction : checkedFraction;
        float handleX = left + (16f + 20f * directionFraction) * scale;
        drawFocusAndPress(canvas, scale, handleX, centerY);
        drawHandle(canvas, scale, handleX, centerY);
        drawCheck(canvas, scale, handleX, centerY);
    }

    private void drawTrack(Canvas canvas, float scale) {
        int alpha = isEnabled() ? 255 : 96;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(ScreenColors.SURFACE_CONTAINER_HIGH, ScreenColors.PRIMARY, checkedFraction));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(trackBounds, 16f * scale, 16f * scale, paint);

        strokeBounds.set(trackBounds);
        strokeBounds.inset(scale, scale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setColor(mix(ScreenColors.OUTLINE, ScreenColors.PRIMARY, checkedFraction));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(strokeBounds, 15f * scale, 15f * scale, paint);
    }

    private void drawFocusAndPress(Canvas canvas, float scale, float x, float y) {
        float focusFraction = isFocused() && isEnabled() ? 1f : 0f;
        float emphasis = Math.max(pressedFraction, focusFraction);
        if (emphasis <= 0f) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(31f * emphasis));
        canvas.drawCircle(x, y, 20f * scale, paint);
    }

    private void drawHandle(Canvas canvas, float scale, float x, float y) {
        float diameter = HANDLE_OFF_DP + (HANDLE_ON_DP - HANDLE_OFF_DP) * checkedFraction;
        diameter += (HANDLE_PRESSED_DP - diameter) * pressedFraction;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(ScreenColors.OUTLINE, ScreenColors.ON_PRIMARY, checkedFraction));
        paint.setAlpha(isEnabled() ? 255 : 96);
        canvas.drawCircle(x, y, diameter * scale / 2f, paint);
        paint.setAlpha(255);
    }

    private void drawCheck(Canvas canvas, float scale, float x, float y) {
        if (checkedFraction <= 0f) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(255f * checkedFraction));
        checkMark.reset();
        checkMark.moveTo(x - 4f * scale, y);
        checkMark.lineTo(x - scale, y + 3f * scale);
        checkMark.lineTo(x + 5f * scale, y - 4f * scale);
        canvas.drawPath(checkMark, paint);
        paint.setAlpha(255);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return "android.widget.Switch";
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(isChecked());
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        cancelCheckedAnimator();
        cancelPressedAnimator();
        checkedFraction = isChecked() ? 1f : 0f;
        pressedFraction = isPressed() && isEnabled() ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelCheckedAnimator();
        cancelPressedAnimator();
        checkedFraction = isChecked() ? 1f : 0f;
        pressedFraction = 0f;
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int mix(int start, int end, float amount) {
        float progress = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * progress),
                Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * progress),
                Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * progress));
    }
}
