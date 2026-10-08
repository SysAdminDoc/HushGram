/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
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
 * <p>What the pill blurs is a copy of the strip of screen just above it, read with {@link PixelCopy} a
 * few times a second while the screen changes, and drawn upside down so the glass carries on from the
 * edge it sits against. It's the screen as it's composed, so playing video, photos and text all come
 * through, which a copy of the view tree can't do for video. With the float switch off, the content
 * stops above the bar as before and the bar's own colour fills round the pill. On, the content
 * container runs to the bottom of the screen.
 *
 * <p>Instagram sets the bar's colour, padding and visibility from time to time, so a draw listener
 * puts the pieces back whenever one is changed. Anything that throws turns the blur off for the
 * bar's lifetime and leaves Instagram's own bar as it was.
 */
public final class GlassTabBar {
    /**
     * The names Instagram gives the bar, the line above it and the screens' container. Resource
     * numbers change with every build, the names don't, so each is looked up by name once.
     */
    static final String TAB_BAR = "tab_bar";
    static final String TAB_BAR_SHADOW = "tab_bar_shadow";
    static final String CONTENT = "layout_container_main";

    private static final java.util.HashMap<String, Integer> IDS = new java.util.HashMap<>();

    /** The number of the id called [name] in [context]'s app, or 0 when there isn't one. */
    static int id(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", context.getPackageName());
                IDS.put(name, found);
            }
            return found;
        }
    }

    @Nullable
    static View find(View root, String name) {
        int id = id(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * Proportions of the floating tab bar on the iPhone: the pill is about 90% of the screen's width
     * (5% left clear each side, so it floats without touching the edges), 52dp tall, with its tabs 7dp
     * in from its ends, and 8dp above whatever the phone keeps at the bottom. A wide screen keeps the
     * pill from growing past {@link #MAX_PILL_DP}.
     */
    static final float SIDE_SHARE = 0.05f;
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
            View found = find(activity.getWindow().getDecorView(), TAB_BAR);
            if (!(found instanceof ViewGroup) || !(found.getParent() instanceof FrameLayout)) return;
            if (applied.containsKey(found)) return;
            applied.put(found, new Glass((ViewGroup) found, activity.getWindow()));
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
     * How far the pill is kept clear of each side: 5% of the width, at least 16dp, and enough on a
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
        private final Window window;
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
        /** The bottom margin Instagram gave the content, as first seen, to tell its changes from ours. */
        private int contentMargin = -1;
        /** The height Instagram gave the bar, before this changed it: what it sizes the screens above it by. */
        private int originalBarHeight = -1;
        /** The tab that was selected as of the last frame, to notice a change the bar's own drawing can't see. */
        @Nullable private WeakReference<View> lastSelected;

        Glass(ViewGroup bar, Window window) {
            this.bar = bar;
            this.window = window;
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
                if (originalBarHeight < 0) originalBarHeight = params.height;
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
                View shadow = find(parent, TAB_BAR_SHADOW);
                if (shadow != null && shadow.getVisibility() != View.GONE) shadow.setVisibility(View.GONE);
                View content = find(parent, CONTENT);
                if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
                    if (contentMargin < 0) contentMargin = margins.bottomMargin;
                    if (floating) {
                        if (margins.bottomMargin != 0) {
                            margins.bottomMargin = 0;
                            content.setLayoutParams(margins);
                        }
                    } else if (params != null && params.height > contentMargin && contentMargin > 0
                            && margins.bottomMargin == contentMargin) {
                        // The bar is taller than the one Instagram laid the content out for; stop the content at its top.
                        margins.bottomMargin = params.height;
                        content.setLayoutParams(margins);
                    }
                }
                if (content instanceof ViewGroup) keepScreensAboveTheBar((ViewGroup) content, params);
            }
        }

        /**
         * Instagram sizes each main screen (Home's pager, Reels and the rest) to stop where its own tab bar
         * did, by a bottom margin as tall as that bar. The pill is taller, so a reel's seek bar and the
         * bottom of every other screen would run in under it. Any child of the content with exactly that
         * margin gets the pill's height instead, and none at all while the content runs behind the bar. A
         * margin of anything else, such as 0 while Instagram hides its bar, is left alone.
         */
        private void keepScreensAboveTheBar(ViewGroup content, @Nullable ViewGroup.LayoutParams params) {
            if (originalBarHeight <= 0 || params == null || params.height <= 0) return;
            int wanted = floating ? 0 : params.height;
            if (wanted == originalBarHeight) return;
            for (int i = 0; i < content.getChildCount(); i++) {
                View screen = content.getChildAt(i);
                if (!(screen.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
                if (margins.bottomMargin == originalBarHeight) {
                    margins.bottomMargin = wanted;
                    screen.setLayoutParams(margins);
                }
            }
        }

        @Override public boolean onPreDraw() {
            try {
                keep();
                // A tab's own selected state redraws that tab, not the bar behind it, so the capsule would
                // wait for something else to redraw the bar. Look at it every frame instead.
                View selected = selectedTab();
                View before = lastSelected == null ? null : lastSelected.get();
                if (selected != before) {
                    lastSelected = selected == null ? null : new WeakReference<>(selected);
                    bar.invalidate();
                }
                if (!blurBroken && blurWanted) pill.refresh();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "draw", failure);
                diagnose("pre-draw failed", failure);
                blurBroken = true;
            }
            return true;
        }

        /** The tab Instagram marks selected, or null. */
        @Nullable
        private View selectedTab() {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.isSelected()) return child;
            }
            return null;
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
            private final Handler handler = new Handler(Looper.getMainLooper());
            private final Rect source = new Rect();
            private final RectF target = new RectF();
            private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            @Nullable private RenderNode node;
            /** The strip drawn now, the one being copied into, and a fingerprint of the one drawn. */
            @Nullable private Bitmap strip;
            @Nullable private Bitmap scratch;
            private int[] pixels = new int[0];
            private int stripHash;
            private boolean copying;
            private int failures;
            private long lastRequest;
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
                    for (int i = 6; i >= 1; i--) {
                        float spread = i * density * 0.8f;
                        fill.setColor(0x06000000);
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
                View selected = selectedTab();
                if (selected == null) return;
                float grow = dp(HIGHLIGHT_GROW_DP);
                float inset = dp(HIGHLIGHT_INSET_DP);
                float targetLeft = Math.max(rect.left + inset, selected.getLeft() + selected.getTranslationX() - grow);
                float targetRight = Math.min(rect.right - inset, selected.getRight() + selected.getTranslationX() + grow);
                if (Float.isNaN(capsuleLeft)) {
                    capsuleLeft = targetLeft;
                    capsuleRight = targetRight;
                }
                capsuleLeft += (targetLeft - capsuleLeft) * 0.45f;
                capsuleRight += (targetRight - capsuleRight) * 0.45f;
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

            /**
             * Asks for a fresh copy of the strip of screen just above the pill, at most every 40 ms and
             * one at a time. Called as each frame is about to draw, so a screen that isn't changing asks
             * for nothing; the copy draws the bar again only when it differs from the last.
             */
            void refresh() {
                if (copying || blurBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastRequest < 40 || !bar.isAttachedToWindow() || rect.isEmpty()) return;
                int[] at = new int[2];
                bar.getLocationInWindow(at);
                int width = Math.round(rect.width());
                int height = Math.round(rect.height());
                source.set(at[0] + Math.round(rect.left), at[1] - height, at[0] + Math.round(rect.left) + width, at[1]);
                if (source.top < 0) source.top = 0;
                if (source.width() <= 0 || source.height() <= 0) return;
                int smallWidth = Math.max(8, width / 6);
                int smallHeight = Math.max(4, height / 6);
                if (scratch == null || scratch.getWidth() != smallWidth || scratch.getHeight() != smallHeight) {
                    scratch = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
                }
                final Bitmap into = scratch;
                copying = true;
                lastRequest = now;
                try {
                    PixelCopy.request(window, source, into, result -> {
                        copying = false;
                        if (result != PixelCopy.SUCCESS) {
                            if (++failures >= 8) {
                                blurBroken = true;
                                diagnose("pixel copy keeps failing, last result " + result, null);
                            }
                            return;
                        }
                        failures = 0;
                        stored(into);
                    }, handler);
                } catch (Throwable failure) {
                    copying = false;
                    blurBroken = true;
                    HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "pixel copy", failure);
                    diagnose("pixel copy failed", failure);
                }
            }

            /** A copy arrived: if the screen above the pill changed, it becomes the strip and the bar draws again. */
            private void stored(Bitmap copy) {
                int count = copy.getWidth() * copy.getHeight();
                if (pixels.length != count) pixels = new int[count];
                copy.getPixels(pixels, 0, copy.getWidth(), 0, 0, copy.getWidth(), copy.getHeight());
                int hash = Arrays.hashCode(pixels);
                if (strip != null && hash == stripHash) return;
                stripHash = hash;
                scratch = strip;
                strip = copy;
                bar.invalidate();
            }

            /** Draws the blurred strip, upside down, into the pill, and says whether it did. */
            private boolean drawBackdrop(Canvas canvas) {
                Bitmap copy = strip;
                if (copy == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
                    return false;
                }
                try {
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
                                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.MIRROR),
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
                    Canvas recording = backdrop.beginRecording(width, height);
                    try {
                        recording.translate(0, height);
                        recording.scale(1f, -1f);
                        target.set(0, 0, width, height);
                        recording.drawBitmap(copy, null, target, bitmapPaint);
                    } finally {
                        backdrop.endRecording();
                    }
                    canvas.save();
                    canvas.translate(rect.left, rect.top);
                    canvas.drawRenderNode(backdrop);
                    canvas.restore();
                    report("backdrop drawn: pill " + width + "x" + height + " from strip "
                            + copy.getWidth() + "x" + copy.getHeight() + " of " + source.toShortString());
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
