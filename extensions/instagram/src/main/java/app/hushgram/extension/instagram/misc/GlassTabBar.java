/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Glass tab bar" patch.
 *
 * <p>Instagram 449's main screen is one frame layout. Its content container (id 0x7f0b2289) is
 * inset from the bottom by the height of the tab bar, a horizontal linear layout (id 0x7f0b4024)
 * that sits at the bottom of the same frame with a one-line gradient (id 0x7f0b4025) above it. Both
 * are looked up by id, the way Instagram's own code finds them, so nothing in the app's bytecode is
 * changed: the patch only has the extension watch the activities once the application is up.
 *
 * <p>The bar keeps its place, its children and its listeners. It gets padding so its tabs sit
 * inside a pill, a little extra height for the gap under the pill, and a background that draws the
 * pill: the content behind it blurred ({@link RenderEffect}, Android 12 and newer), a tint taken
 * from the colour Instagram gave the bar, a soft highlight and a hairline edge. The tab Instagram
 * marks selected gets a lighter capsule that slides to the next one. Without blur, or with
 * Android's blur unavailable, the pill is the same tint, more opaque.
 *
 * <p>Sizes follow the pill on Instagram's iPhone tab bar, as proportions of the screen's width
 * (see the constants), so they carry to any width, and are held to sensible limits on narrow and
 * wide ones.
 *
 * <p>With the float switch off, the content stops above the bar as before, so the pill blurs a mirror
 * of the strip of content just above it, and the bar's own colour fills round the pill. On, the
 * content container runs to the bottom of the screen and the pill blurs what is really behind it.
 *
 * <p>Instagram sets the bar's colour, padding and visibility from time to time, so a draw listener
 * puts the pieces back whenever one is changed. Anything that throws turns the blur off for the
 * bar's lifetime and leaves Instagram's own bar as it was.
 */
public final class GlassTabBar {
    static final int TAB_BAR = 0x7f0b4024;
    static final int TAB_BAR_SHADOW = 0x7f0b4025;
    static final int CONTENT = 0x7f0b2289;

    /**
     * Proportions taken from Instagram's iPhone tab bar: the pill is about 74% of the screen's width
     * (13% left clear each side), 52dp tall, with its tabs 7dp in from its ends, and 8dp above
     * whatever the phone keeps at the bottom. A wide screen keeps the pill from growing past
     * {@link #MAX_PILL_DP}.
     */
    static final float SIDE_SHARE = 0.13f;
    static final int MIN_SIDE_DP = 16;
    static final int MAX_PILL_DP = 420;
    static final int INNER_DP = 7;
    static final int PILL_DP = 52;
    static final int BOTTOM_DP = 8;
    static final int BLUR_DP = 18;
    /** The selected tab's capsule sits 4dp in from the pill's top and bottom and stands 4dp past its slot. */
    static final int HIGHLIGHT_INSET_DP = 4;
    static final int HIGHLIGHT_GROW_DP = 4;

    static final String TAG = "HushGlass";
    private static final boolean DIAGNOSE = false;

    private static volatile boolean registered;
    private static final WeakHashMap<View, Glass> applied = new WeakHashMap<>();

    private GlassTabBar() {
    }

    /**
     * Called as the application starts. Does nothing in a build that doesn't carry the patch, and
     * watches every activity otherwise, because whether the switch is on is only known later.
     */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.glassTabBar()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "start", failure);
        }
    }

    static void apply(Activity activity) {
        try {
            if (!Utils.settingsReady() || !Settings.GLASS_TAB_BAR.get()) return;
            View found = activity.findViewById(TAB_BAR);
            if (!(found instanceof ViewGroup) || !(found.getParent() instanceof FrameLayout)) return;
            if (applied.containsKey(found)) return;
            applied.put(found, new Glass((ViewGroup) found));
            HookStatus.invoked(FamilyNames.GLASS_TAB_BAR);
            Logger.printDebug(() -> "Glass tab bar: restyled the tab bar");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab bar", failure);
            diagnose("apply failed", failure);
        }
    }

    static void diagnose(String what, @Nullable Throwable failure) {
        if (DIAGNOSE) Log.w(TAG, what, failure);
    }

    /** Whether a colour is dark enough that a lighter tint reads against it. */
    static boolean isDark(int color) {
        double luminance = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color);
        return luminance < 128;
    }

    static int blend(int from, int to, float share) {
        return Color.argb(255,
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * share),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * share),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * share));
    }

    /**
     * The pill's fill over the blur, or over the bar's colour when there is none, from that colour.
     * Dark glass is the bar's own dark at a little under half over the blur, as on the iPhone; light
     * glass is a frosted white. Without the blur there is nothing to see through, so the fill is more
     * solid and a dark one is lifted a little so the pill still shows against the bar.
     */
    static int tint(int base, boolean blurred) {
        boolean dark = isDark(base);
        if (blurred) return (base & 0x00ffffff) | ((dark ? 0x70 : 0xa6) << 24);
        int solid = dark ? blend(base, Color.WHITE, 0.10f) : base;
        return (solid & 0x00ffffff) | (0xe6 << 24);
    }

    /** The selected tab's capsule: a quarter of white on dark glass, a sixth of grey on light. */
    static int highlight(int base) {
        return isDark(base) ? 0x40ffffff : 0x2a787880;
    }

    static int edge(int base) {
        return isDark(base) ? 0x33ffffff : 0x1f000000;
    }

    /**
     * How far the pill is kept clear of each side: 13% of the width, at least 16dp, and enough on a
     * wide screen that the pill is no wider than {@link #MAX_PILL_DP}.
     */
    static int outerPx(int widthPx, float density) {
        int minimum = Math.round(MIN_SIDE_DP * density);
        int byShare = Math.round(widthPx * SIDE_SHARE);
        int byWidth = Math.round((widthPx - MAX_PILL_DP * density) / 2f);
        return Math.max(minimum, Math.max(byShare, byWidth));
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            apply(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    /** One restyled bar. */
    private static final class Glass implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final ViewGroup bar;
        private final float density;
        private final boolean blurWanted;
        private final boolean floating;
        private final PillDrawable pill = new PillDrawable();
        private int base;
        private boolean blurBroken;
        /** The padding Instagram gave the bar, and what this last put on it, to tell their changes from ours. */
        private int originalLeft, originalRight, originalBottom;
        private int lastLeft = -1, lastRight = -1, lastBottom = -1;
        /** How far each side of the pill is kept clear, as of the last layout. */
        private int outer;

        Glass(ViewGroup bar) {
            this.bar = bar;
            this.density = bar.getResources().getDisplayMetrics().density;
            this.blurWanted = Settings.GLASS_TAB_BAR_BLUR.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            this.floating = Settings.GLASS_TAB_BAR_FLOAT.get();
            this.base = baseColor(bar.getBackground());
            this.originalLeft = bar.getPaddingLeft();
            this.originalRight = bar.getPaddingRight();
            this.originalBottom = bar.getPaddingBottom();
            diagnose("glass on: blurWanted=" + blurWanted + " floating=" + floating + " base="
                    + Integer.toHexString(base) + " height=" + bar.getLayoutParams().height
                    + " density=" + density + " sdk=" + Build.VERSION.SDK_INT, null);
            if (bar instanceof LinearLayout) ((LinearLayout) bar).setGravity(Gravity.CENTER_VERTICAL);
            bar.setClipChildren(false);
            keep();
            bar.setBackground(pill);
            bar.addOnAttachStateChangeListener(this);
            if (bar.isAttachedToWindow()) bar.getViewTreeObserver().addOnPreDrawListener(this);
        }

        private int dp(int value) {
            return Math.round(value * density);
        }

        /** The colour Instagram gave the bar, or white or black by the theme when it wasn't a plain one. */
        private int baseColor(@Nullable Drawable background) {
            if (background instanceof ColorDrawable) return ((ColorDrawable) background).getColor() | 0xff000000;
            int night = bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES ? Color.BLACK : Color.WHITE;
        }

        /** Puts back what Instagram changed since the last frame. */
        private void keep() {
            Drawable background = bar.getBackground();
            if (background != pill && background != null) {
                base = baseColor(background);
                bar.setBackground(pill);
            }
            View parent = bar.getParent() instanceof View ? (View) bar.getParent() : null;
            int width = parent != null && parent.getWidth() > 0 ? parent.getWidth()
                    : bar.getResources().getDisplayMetrics().widthPixels;
            outer = outerPx(width, density);

            // Height: the pill and the gap under it.
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params != null && params.height > 0) {
                int wanted = dp(PILL_DP + BOTTOM_DP);
                if (params.height != wanted) {
                    params.height = wanted;
                    bar.setLayoutParams(params);
                }
            }

            // Padding: a change nobody here made is Instagram's, and becomes the new starting point.
            if (bar.getPaddingLeft() != lastLeft || bar.getPaddingRight() != lastRight
                    || bar.getPaddingBottom() != lastBottom) {
                originalLeft = bar.getPaddingLeft();
                originalRight = bar.getPaddingRight();
                originalBottom = bar.getPaddingBottom();
            }
            int left = originalLeft + outer + dp(INNER_DP);
            int right = originalRight + outer + dp(INNER_DP);
            int under = originalBottom + dp(BOTTOM_DP);
            if (left != bar.getPaddingLeft() || right != bar.getPaddingRight() || under != bar.getPaddingBottom()) {
                bar.setPadding(left, bar.getPaddingTop(), right, under);
            }
            lastLeft = left;
            lastRight = right;
            lastBottom = under;

            if (parent != null) {
                View shadow = parent.findViewById(TAB_BAR_SHADOW);
                if (shadow != null && shadow.getVisibility() != View.GONE) shadow.setVisibility(View.GONE);
                if (floating) {
                    View content = parent.findViewById(CONTENT);
                    if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
                        if (margins.bottomMargin != 0) {
                            margins.bottomMargin = 0;
                            content.setLayoutParams(margins);
                        }
                    }
                }
            }
        }

        @Override public boolean onPreDraw() {
            try {
                keep();
                if (!blurBroken && blurWanted) bar.invalidate();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "draw", failure);
                diagnose("pre-draw failed", failure);
                blurBroken = true;
            }
            return true;
        }

        @Override public void onViewAttachedToWindow(@NonNull View view) {
            bar.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override public void onViewDetachedFromWindow(@NonNull View view) {
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        /** What shows behind the tabs. */
        private final class PillDrawable extends Drawable {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final RectF capsule = new RectF();
            @Nullable private RenderNode node;
            @Nullable private WeakReference<View> content;
            private int nodeWidth = -1;
            private int nodeHeight = -1;
            private float capsuleLeft = Float.NaN;
            private float capsuleRight = Float.NaN;
            private boolean reported;

            PillDrawable() {
                stroke.setStyle(Paint.Style.STROKE);
            }

            @Override public void draw(@NonNull Canvas canvas) {
                float left = outer;
                float top = 0;
                float right = bar.getWidth() - outer;
                float bottomEdge = bar.getHeight() - dp(BOTTOM_DP);
                if (right - left <= 0 || bottomEdge - top <= 0) return;
                rect.set(left, top, right, bottomEdge);
                float radius = rect.height() / 2f;
                boolean dark = isDark(base);

                // The content stops above the bar, so the bar's own colour fills round the pill.
                if (!floating) {
                    fill.setColor(base);
                    canvas.drawRect(0, 0, bar.getWidth(), bar.getHeight(), fill);
                }
                // Light glass has nothing under it to stand out against, so it gets a soft shadow.
                if (!dark) {
                    for (int i = 4; i >= 1; i--) {
                        float spread = i * density * 1.5f;
                        fill.setColor(0x0d000000);
                        capsule.set(rect.left - spread, rect.top - spread / 3f + density,
                                rect.right + spread, rect.bottom + spread);
                        canvas.drawRoundRect(capsule, radius + spread, radius + spread, fill);
                    }
                }
                boolean blurred = !blurBroken && blurWanted && drawBackdrop(canvas);
                fill.setColor(tint(base, blurred));
                canvas.drawRoundRect(rect, radius, radius, fill);

                sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        dark ? 0x22ffffff : 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, sheen);

                drawCapsule(canvas);

                stroke.setStrokeWidth(Math.max(1f, density));
                stroke.setColor(edge(base));
                rect.inset(density / 2f, density / 2f);
                canvas.drawRoundRect(rect, radius, radius, stroke);
            }

            /** The lighter capsule behind the selected tab, easing to the next one when the tab changes. */
            private void drawCapsule(Canvas canvas) {
                View selected = null;
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE && child.isSelected()) {
                        selected = child;
                        break;
                    }
                }
                if (selected == null) return;
                float grow = dp(HIGHLIGHT_GROW_DP);
                float inset = dp(HIGHLIGHT_INSET_DP);
                float targetLeft = Math.max(rect.left + inset, selected.getLeft() + selected.getTranslationX() - grow);
                float targetRight = Math.min(rect.right - inset, selected.getRight() + selected.getTranslationX() + grow);
                if (Float.isNaN(capsuleLeft)) {
                    capsuleLeft = targetLeft;
                    capsuleRight = targetRight;
                }
                capsuleLeft += (targetLeft - capsuleLeft) * 0.35f;
                capsuleRight += (targetRight - capsuleRight) * 0.35f;
                boolean settled = Math.abs(targetLeft - capsuleLeft) < 0.5f && Math.abs(targetRight - capsuleRight) < 0.5f;
                if (settled) {
                    capsuleLeft = targetLeft;
                    capsuleRight = targetRight;
                }
                capsule.set(capsuleLeft, rect.top + inset, capsuleRight, rect.bottom - inset);
                fill.setColor(highlight(base));
                float capsuleRadius = capsule.height() / 2f;
                canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, fill);
                if (!settled) invalidateSelf();
            }

            /** Draws the blurred backdrop into the pill, and says whether it did. */
            private boolean drawBackdrop(Canvas canvas) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
                    report("backdrop skipped: sdk=" + Build.VERSION.SDK_INT + " hw=" + canvas.isHardwareAccelerated());
                    return false;
                }
                try {
                    View container = content == null ? null : content.get();
                    if (container == null && bar.getParent() instanceof View) {
                        container = ((View) bar.getParent()).findViewById(CONTENT);
                        content = container == null ? null : new WeakReference<>(container);
                    }
                    if (container == null || container.getWidth() == 0 || container.getHeight() == 0) {
                        report("backdrop skipped: no content container (" + container + ")");
                        return false;
                    }
                    int width = Math.round(rect.width());
                    int height = Math.round(rect.height());
                    RenderNode backdrop = node;
                    if (backdrop == null) {
                        backdrop = new RenderNode("hushgram-glass");
                        backdrop.setClipToOutline(true);
                        float blur = dp(BLUR_DP);
                        ColorMatrix saturate = new ColorMatrix();
                        saturate.setSaturation(1.5f);
                        ColorFilter colour = new ColorMatrixColorFilter(saturate);
                        backdrop.setRenderEffect(RenderEffect.createChainEffect(
                                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
                                RenderEffect.createColorFilterEffect(colour)));
                        node = backdrop;
                    }
                    if (width != nodeWidth || height != nodeHeight) {
                        backdrop.setPosition(0, 0, width, height);
                        Outline outline = new Outline();
                        outline.setRoundRect(0, 0, width, height, height / 2f);
                        backdrop.setOutline(outline);
                        nodeWidth = width;
                        nodeHeight = height;
                    }
                    // Where the pill sits in the frame the content and the bar share.
                    float pillLeft = bar.getLeft() + bar.getTranslationX() + rect.left;
                    float pillTop = bar.getTop() + bar.getTranslationY() + rect.top;
                    Canvas recording = backdrop.beginRecording(width, height);
                    try {
                        recording.drawColor(base);
                        if (floating) {
                            recording.translate(container.getLeft() - pillLeft, container.getTop() - pillTop);
                        } else {
                            // Mirrored across the content's bottom edge: the strip just above the bar.
                            recording.translate(container.getLeft() - pillLeft,
                                    2f * container.getBottom() - container.getTop() - pillTop);
                            recording.scale(1f, -1f);
                        }
                        container.draw(recording);
                    } finally {
                        backdrop.endRecording();
                    }
                    canvas.save();
                    canvas.translate(rect.left, rect.top);
                    canvas.drawRenderNode(backdrop);
                    canvas.restore();
                    report("backdrop drawn: pill " + width + "x" + height + " at (" + pillLeft + "," + pillTop
                            + ") content " + container.getLeft() + "," + container.getTop() + "-"
                            + container.getRight() + "," + container.getBottom() + " floating=" + floating);
                    return true;
                } catch (Throwable failure) {
                    blurBroken = true;
                    HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "blur", failure);
                    diagnose("backdrop failed", failure);
                    return false;
                }
            }

            private void report(String what) {
                if (reported) return;
                reported = true;
                diagnose(what, null);
            }

            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
            @Override public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }
    }
}
