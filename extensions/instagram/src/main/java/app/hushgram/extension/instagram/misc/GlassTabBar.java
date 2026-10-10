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
import android.os.Trace;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
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
import app.hushgram.extension.shared.settings.HushgramPause;

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
 * <p>What the pill blurs is Instagram's own screens, the views drawn under the bar, recorded into a render node of its own and blurred
 * on the GPU, never read back from the screen. It's the views as they draw, so photos, text and video
 * in a TextureView come through. The glass shows the strip just above the bar, upside down. With the
 * float switch on, the content container also runs to the bottom of the screen, so nothing of
 * Instagram's own colour is left round the pill.
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
     * (5% left clear each side, so it floats without touching the edges), with its tabs 7dp in from its
     * ends. It's as tall as the bar Instagram laid out, less a gap above and under. A wide screen keeps
     * the pill from growing past {@link #MAX_PILL_DP}.
     */
    static final float SIDE_SHARE = 0.05f;
    static final int MIN_SIDE_DP = 16;
    static final int MAX_PILL_DP = 420;
    static final int INNER_DP = 7;
    /**
     * The bar keeps the height Instagram laid every screen out for, floating or not, so nothing it keeps
     * clear above the bar, a reel's seek bar included, is covered. The pill sits this far in from the top
     * and the bottom of that height.
     */
    static final int FIT_GAP_DP = 1;
    static final int BLUR_DP = 18;
    /** The selected tab's capsule sits 4dp in from the pill's top and bottom and stands 4dp past its slot. */
    static final int HIGHLIGHT_INSET_DP = 4;
    static final int HIGHLIGHT_GROW_DP = 4;

    /** The blur is worked out at a quarter of the size, which also blurs it more for the same work. */
    static final int DOWNSAMPLE = 4;
    /**
     * A frame whose layout has already run this long is late: the glass records nothing in it and catches up in
     * a later frame. At 90 Hz a frame has 11 ms, and Instagram's own layout after a tab tap can take 50 ms or
     * more, which nothing here can shorten but nothing here should add to.
     */
    static final long LATE_FRAME_MS = 5;
    /** How long a video seen under the bar keeps the blur off before the views are walked again. */
    static final long VIDEO_HOLD_MS = 100;
    /** A blur this old is recorded even in a late frame, so the glass never trails what's under it for long. */
    static final long MAX_STALE_MS = 150;
    /**
     * The blur is recorded again at most this often. A blurred strip looks the same at 30 frames a second as
     * at 90, and each recording makes the GPU render the blur layer again for that frame: on a phone at 90 Hz
     * recording every frame doubled the GPU's busy time.
     */
    static final long RECORD_EVERY_MS = 33;
    /** How long the capsule takes to slide to a new tab. */
    static final long SLIDE_MS = 280;

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
            Glass glass = new Glass((ViewGroup) found);
            applied.put(found, glass);
            glass.watchScrolling(activity.getWindow());
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
        return tint(base, blurred, GlassOpacity.STANDARD);
    }

    /** {@link #tint(int, boolean)} with the strength of the fill scaled by the opacity step chosen. */
    static int tint(int base, boolean blurred, GlassOpacity opacity) {
        boolean dark = isDark(base);
        float scale = opacity == null ? 1f : opacity.scale;
        if (blurred) return (base & 0x00ffffff) | (scaledAlpha(dark ? 0x70 : 0xa6, scale) << 24);
        int solid = dark ? blend(base, Color.WHITE, 0.10f) : base;
        return (solid & 0x00ffffff) | (scaledAlpha(0xe6, scale) << 24);
    }

    static int scaledAlpha(int alpha, float scale) {
        return Math.max(0, Math.min(255, Math.round(alpha * scale)));
    }

    /** The shortest a compact pill is drawn, so the icons, the capsule round the selected one and badges fit. */
    static final int MIN_PILL_DP = 40;
    /** How much slimmer a compact pill is than the standard one, on each side. */
    static final int COMPACT_INSET_DP = 4;
    /** How much taller a tall bar is than the one Instagram laid out. */
    static final int TALL_EXTRA_DP = 8;
    /** A tall bar is never made taller than this, so the screens above it keep their room. */
    static final int MAX_BAR_DP = 72;

    /**
     * How far the pill is drawn in from the top and the bottom of the bar, beyond the gap: 0 unless the
     * height is compact, and then {@link #COMPACT_INSET_DP}, less if that would leave the pill shorter than
     * {@link #MIN_PILL_DP}. The bar and its tabs keep their size, so every touch target still fills the bar.
     */
    static int pillInsetPx(GlassHeight height, int barHeightPx, int gapPx, float density) {
        if (height != GlassHeight.COMPACT) return 0;
        int room = (barHeightPx - 2 * gapPx - Math.round(MIN_PILL_DP * density)) / 2;
        return Math.max(0, Math.min(Math.round(COMPACT_INSET_DP * density), room));
    }

    /**
     * The height the bar is given: the one Instagram laid out, or for a tall bar {@link #TALL_EXTRA_DP}
     * more, up to {@link #MAX_BAR_DP} (a bar already past that is left as it is). The tabs fill the bar, so
     * a taller bar only gives them more to tap.
     */
    static int barHeightPx(GlassHeight height, int originalPx, float density) {
        if (height != GlassHeight.TALL) return originalPx;
        int limit = Math.max(originalPx, Math.round(MAX_BAR_DP * density));
        return Math.min(originalPx + Math.round(TALL_EXTRA_DP * density), limit);
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

    /** Brings back every bar a scroll has hidden: the screen is going to the background. */
    static void restoreAll() {
        try {
            for (Glass glass : new java.util.ArrayList<>(applied.values())) glass.restoreScrolling();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "restore", failure);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            apply(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) {
            restoreAll();
        }
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
        private final boolean haptics;
        private final HapticStyle hapticStyle;
        private final GlassOpacity opacity;
        private final GlassHeight height;
        /** Hides the bar while a list scrolls down, when that switch is on; otherwise null. */
        @Nullable private final GlassScrollHide scrollHide;
        /** Whether this slide has had its tick: one tick for a change of tab, however many tabs the capsule crosses. */
        private boolean tickedThisSlide;
        private final boolean floating;
        private final PillDrawable pill = new PillDrawable();
        private int base;
        private boolean blurBroken;
        /** The padding Instagram gave the bar, and what this last put on it, to tell their changes from ours. */
        private int originalLeft, originalRight, originalTop, originalBottom;
        private int lastLeft = -1, lastRight = -1, lastTop = -1, lastBottom = -1;
        /** How far each side of the pill is kept clear, as of the last layout. */
        private int outer;
        /** The bottom margin Instagram gave the content, as first seen, to tell its changes from ours. */
        private int contentMargin = -1;
        /** The height Instagram gave the bar, before this changed it: what it sizes the screens above it by. */
        private int originalBarHeight = -1;
        /** The tab that was selected as of the last frame, to notice a change the bar's own drawing can't see. */
        @Nullable private WeakReference<View> lastSelected;
        /** The line above the bar and the screens' container, found once: looking them up costs a walk of the whole screen. */
        @Nullable private WeakReference<View> shadowRef;
        @Nullable private WeakReference<View> contentRef;

        Glass(ViewGroup bar) {
            this.bar = bar;
            this.density = bar.getResources().getDisplayMetrics().density;
            this.blurWanted = Settings.GLASS_TAB_BAR_BLUR.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            this.floating = Settings.GLASS_TAB_BAR_FLOAT.get();
            this.haptics = Settings.GLASS_TAB_BAR_HAPTICS.get();
            this.hapticStyle = Settings.GLASS_TAB_BAR_HAPTIC_STYLE.get();
            this.opacity = Settings.GLASS_TAB_BAR_OPACITY.get();
            this.height = Settings.GLASS_TAB_BAR_HEIGHT.get();
            this.scrollHide = Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get()
                    ? new GlassScrollHide(bar, () -> !HushgramPause.isPaused()) : null;
            this.base = baseColor(bar.getBackground());
            this.originalLeft = bar.getPaddingLeft();
            this.originalRight = bar.getPaddingRight();
            this.originalTop = bar.getPaddingTop();
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

        void watchScrolling(Window window) {
            if (scrollHide == null) return;
            try {
                GlassScrollHide.install(window, scrollHide);
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, "scroll hide watching");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "scroll hide", failure);
            }
        }

        void restoreScrolling() {
            if (scrollHide != null) scrollHide.restoreNow();
        }

        /** The clear space above and under the pill. */
        private int gap() {
            return dp(FIT_GAP_DP);
        }

        /** How far a compact pill is drawn in from the top and bottom, beyond the gap. */
        private int pillInset() {
            return pillInsetPx(height, bar.getHeight(), gap(), density);
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

            // Height: the bar's own, which is what Instagram keeps every screen's own controls above.
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params != null && params.height > 0) {
                if (originalBarHeight < 0) originalBarHeight = params.height;
                int wanted = barHeightPx(height, originalBarHeight, density);
                if (params.height != wanted) {
                    params.height = wanted;
                    bar.setLayoutParams(params);
                }
            }

            // Padding: a change nobody here made is Instagram's, and becomes the new starting point.
            if (bar.getPaddingLeft() != lastLeft || bar.getPaddingRight() != lastRight
                    || bar.getPaddingTop() != lastTop || bar.getPaddingBottom() != lastBottom) {
                originalLeft = bar.getPaddingLeft();
                originalRight = bar.getPaddingRight();
                originalTop = bar.getPaddingTop();
                originalBottom = bar.getPaddingBottom();
            }
            int left = originalLeft + outer + dp(INNER_DP);
            int right = originalRight + outer + dp(INNER_DP);
            int above = originalTop + gap();
            int under = originalBottom + gap();
            if (left != bar.getPaddingLeft() || right != bar.getPaddingRight()
                    || above != bar.getPaddingTop() || under != bar.getPaddingBottom()) {
                bar.setPadding(left, above, right, under);
            }
            lastLeft = left;
            lastRight = right;
            lastTop = above;
            lastBottom = under;

            if (parent != null) {
                View shadow = shadowRef == null ? null : shadowRef.get();
                if (shadow == null || !shadow.isAttachedToWindow()) {
                    shadow = find(parent, TAB_BAR_SHADOW);
                    shadowRef = shadow == null ? null : new WeakReference<>(shadow);
                }
                if (shadow != null && shadow.getVisibility() != View.GONE) shadow.setVisibility(View.GONE);
                View content = contentRef == null ? null : contentRef.get();
                if (content == null || !content.isAttachedToWindow()) {
                    content = find(parent, CONTENT);
                    contentRef = content == null ? null : new WeakReference<>(content);
                }
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
            Trace.beginSection("hushgram:preDraw");
            try {
                keep();
                // A tab's own selected state redraws that tab, not the bar behind it, so the capsule would
                // wait for something else to redraw the bar. Look at it every frame instead.
                View selected = selectedTab();
                View before = lastSelected == null ? null : lastSelected.get();
                if (selected != before) {
                    lastSelected = selected == null ? null : new WeakReference<>(selected);
                    if (scrollHide != null) scrollHide.show();
                    bar.invalidate();
                }
                // Something on screen is drawing a frame, so the glass records what's under it again, at most
                // every RECORD_EVERY_MS, and once more shortly after a frame it skipped so it never stays behind.
                // Only its own render node is recorded, not a view, so this asks for no frame of its own: the
                // screen stops drawing as soon as nothing else changes.
                if (!blurBroken && blurWanted && (scrollHide == null || !scrollHide.isOffScreen())) pill.recordIfDue();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "draw", failure);
                diagnose("pre-draw failed", failure);
                blurBroken = true;
            } finally {
                Trace.endSection();
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
            if (scrollHide != null) scrollHide.restoreNow();
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        /** What shows behind the tabs. */
        private final class PillDrawable extends Drawable {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final RectF capsule = new RectF();
            /** The pill's bounds as of the last recording, kept apart from what the drawing is using. */
            private final RectF area = new RectF();
            @Nullable private RenderNode node;
            private boolean recorded;
            private float nodeLeft, nodeTop;
            private int nodeWidth = -1;
            private int nodeHeight = -1;
            private boolean drawingBackdrop;
            private float sheenTop = Float.NaN;
            private float sheenBottom = Float.NaN;
            private boolean sheenDark;
            /** Where the capsule is sliding from and to, and when the slide started, on the frame clock. */
            private float fromLeft = Float.NaN, fromRight, toLeft, toRight, nowLeft, nowRight;
            private long slideStart;
            /** The tab the capsule's middle was over when it last ticked, or -1 before the first frame. */
            private int tickedTab = -1;
            private boolean reported;

            PillDrawable() {
                stroke.setStyle(Paint.Style.STROKE);
            }

            @Override public void draw(@NonNull Canvas canvas) {
                Trace.beginSection("hushgram:pillDraw");
                try {
                    drawPill(canvas);
                } finally {
                    Trace.endSection();
                }
            }

            /** Puts the pill's bounds in [into], in the bar's coordinates, and says whether it has any. */
            private boolean pillBounds(RectF into) {
                float left = outer;
                float top = gap() + pillInset();
                float right = bar.getWidth() - outer;
                float bottomEdge = bar.getHeight() - gap() - pillInset();
                if (right - left <= 0 || bottomEdge - top <= 0) return false;
                into.set(left, top, right, bottomEdge);
                return true;
            }

            private void drawPill(@NonNull Canvas canvas) {
                if (!pillBounds(rect)) return;
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
                fill.setColor(tint(base, blurred, opacity));
                canvas.drawRoundRect(rect, radius, radius, fill);

                if (sheenTop != rect.top || sheenBottom != rect.bottom || sheenDark != dark) {
                    sheenTop = rect.top;
                    sheenBottom = rect.bottom;
                    sheenDark = dark;
                    sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                            dark ? 0x22ffffff : 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                }
                canvas.drawRoundRect(rect, radius, radius, sheen);

                drawCapsule(canvas);

                stroke.setStrokeWidth(Math.max(1f, density));
                stroke.setColor(edge(base));
                rect.inset(density / 2f, density / 2f);
                canvas.drawRoundRect(rect, radius, radius, stroke);
            }

            /**
             * The lighter capsule behind the selected tab. When the tab changes it slides to the new one over
             * {@link #SLIDE_MS}, eased out, timed by the frame clock rather than by counting frames, so a
             * dropped frame doesn't make it jump and it takes as long at 120 Hz as at 60.
             */
            private void drawCapsule(Canvas canvas) {
                View selected = selectedTab();
                if (selected == null) return;
                float grow = dp(HIGHLIGHT_GROW_DP);
                float inset = dp(HIGHLIGHT_INSET_DP);
                float targetLeft = Math.max(rect.left + inset, selected.getLeft() + selected.getTranslationX() - grow);
                float targetRight = Math.min(rect.right - inset, selected.getRight() + selected.getTranslationX() + grow);
                long now = AnimationUtils.currentAnimationTimeMillis();
                if (Float.isNaN(fromLeft)) {
                    fromLeft = nowLeft = toLeft = targetLeft;
                    fromRight = nowRight = toRight = targetRight;
                    slideStart = now - SLIDE_MS;
                } else if (Math.abs(targetLeft - toLeft) > 0.5f || Math.abs(targetRight - toRight) > 0.5f) {
                    tickedThisSlide = false;
                    fromLeft = nowLeft;
                    fromRight = nowRight;
                    toLeft = targetLeft;
                    toRight = targetRight;
                    slideStart = now;
                }
                float progress = slideProgress(now - slideStart);
                nowLeft = fromLeft + (toLeft - fromLeft) * progress;
                nowRight = fromRight + (toRight - fromRight) * progress;
                capsule.set(nowLeft, rect.top + inset, nowRight, rect.bottom - inset);
                tick((nowLeft + nowRight) / 2f);
                fill.setColor(highlight(base));
                float capsuleRadius = capsule.height() / 2f;
                canvas.drawRoundRect(capsule, capsuleRadius, capsuleRadius, fill);
                if (progress < 1f) invalidateSelf();
            }

            /** A light tick each time the capsule's middle moves over a different tab. */
            private void tick(float middle) {
                int over = -1;
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE
                            && middle >= child.getLeft() && middle < child.getRight()) {
                        over = i;
                        break;
                    }
                }
                if (over < 0 || over == tickedTab) return;
                boolean first = tickedTab < 0;
                tickedTab = over;
                if (!first && haptics && !tickedThisSlide) {
                    tickedThisSlide = true;
                    Haptics.tick(bar, hapticStyle);
                }
            }

            /** Draws the backdrop node recorded for this frame into the pill, and says whether it did. */
            private boolean drawBackdrop(Canvas canvas) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) return false;
                if (node == null || !recorded) recordBackdrop();
                RenderNode backdrop = node;
                if (backdrop == null || !recorded) return false;
                canvas.save();
                canvas.translate(nodeLeft, nodeTop);
                canvas.scale(DOWNSAMPLE, DOWNSAMPLE);
                canvas.drawRenderNode(backdrop);
                canvas.restore();
                return true;
            }

            private long lastRecorded;
            private boolean catchUpPending;
            private final Runnable catchUp = () -> {
                catchUpPending = false;
                if (blurBroken || !bar.isAttachedToWindow()) return;
                recordBackdrop();
                bar.invalidate();
            };

            /** Records the backdrop if it's been long enough and this frame isn't late, or arranges to once it can. */
            void recordIfDue() {
                long now = SystemClock.uptimeMillis();
                long since = now - lastRecorded;
                // The animation clock holds the frame's start while it lays out and draws, so this is how far in we are.
                long inFrame = now - AnimationUtils.currentAnimationTimeMillis();
                if (shouldRecord(recorded, since, inFrame)) {
                    lastRecorded = now;
                    recordBackdrop();
                } else if (!catchUpPending) {
                    catchUpPending = true;
                    bar.postDelayed(catchUp, Math.max(RECORD_EVERY_MS - since, 0) + 4);
                }
            }

            /**
             * Records what's under the bar into this drawable's own render node, to be blurred into the pill.
             *
             * <p>The screen isn't copied: the views drawn under the bar (in 450 the tab pager, beside an empty
             * content container) are recorded once more into the node, which keeps a reference to each of
             * them, the way a transition draws a view in a second place. The blur and the colour run on that
             * node on the GPU, at a quarter of the size, and nothing is read back. It's called as each frame
             * is about to draw, so it moves with what's under it, and because it records a render node and
             * not a view, it never asks for a frame of its own. What it shows is the strip of screen just
             * above the bar, upside down, as if the glass reflected it: Instagram's screens stop above its
             * bar themselves, so that strip always has what you're looking at.
             */
            void recordBackdrop() {
                if (drawingBackdrop || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
                if (!(bar.getParent() instanceof ViewGroup) || !pillBounds(area)) return;
                ViewGroup parent = (ViewGroup) bar.getParent();
                int barIndex = parent.indexOfChild(bar);
                if (barIndex <= 0) return;
                if (videoUnder(parent, barIndex)) {
                    recorded = false;
                    return;
                }
                Trace.beginSection("hushgram:recordBackdrop");
                drawingBackdrop = true;
                try {
                    float scale = 1f / DOWNSAMPLE;
                    float pad = dp(BLUR_DP);
                    float radius = area.height() / 2f;
                    float wide = area.width() + 2 * pad;
                    float tall = area.height() + 2 * pad;
                    int width = Math.max(1, (int) Math.ceil(wide * scale));
                    int height = Math.max(1, (int) Math.ceil(tall * scale));
                    RenderNode backdrop = node;
                    if (backdrop == null) {
                        backdrop = new RenderNode("hushgram-glass");
                        backdrop.setClipToOutline(true);
                        float blur = Math.max(1f, dp(BLUR_DP) * scale);
                        ColorMatrix saturate = new ColorMatrix();
                        saturate.setSaturation(1.5f);
                        ColorFilter colour = new ColorMatrixColorFilter(saturate);
                        backdrop.setRenderEffect(RenderEffect.createChainEffect(
                                RenderEffect.createColorFilterEffect(colour),
                                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                        node = backdrop;
                    }
                    if (width != nodeWidth || height != nodeHeight) {
                        backdrop.setPosition(0, 0, width, height);
                        Outline outline = new Outline();
                        float inner = pad * scale;
                        outline.setRoundRect(Math.round(inner), Math.round(inner), Math.round(width - inner),
                                Math.round(height - inner), radius * scale);
                        backdrop.setOutline(outline);
                        nodeWidth = width;
                        nodeHeight = height;
                    }
                    nodeLeft = area.left - pad;
                    nodeTop = area.top - pad;

                    float x0 = bar.getLeft() + area.left - pad;
                    float edge = bar.getTop();
                    Canvas recording = backdrop.beginRecording(width, height);
                    try {
                        recording.scale(scale, scale);
                        recording.scale(1f, -1f);
                        recording.translate(-x0, -edge);
                        recording.clipRect(x0, edge - tall, x0 + wide, edge);
                        for (int i = 0; i < barIndex; i++) {
                            View sibling = parent.getChildAt(i);
                            if (sibling.getVisibility() != View.VISIBLE || sibling.getWidth() <= 0
                                    || sibling.getBottom() <= edge - tall || sibling.getTop() >= edge) continue;
                            recording.save();
                            recording.translate(sibling.getLeft(), sibling.getTop());
                            if (!sibling.getMatrix().isIdentity()) recording.concat(sibling.getMatrix());
                            sibling.draw(recording);
                            recording.restore();
                        }
                    } finally {
                        backdrop.endRecording();
                    }
                    recorded = true;
                    report("backdrop recorded under " + barIndex + " views: pill " + Math.round(area.width()) + "x"
                            + Math.round(area.height()) + " as " + width + "x" + height);
                } catch (Throwable failure) {
                    blurBroken = true;
                    HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "blur", failure);
                    diagnose("backdrop failed", failure);
                } finally {
                    drawingBackdrop = false;
                    Trace.endSection();
                }
            }

            private long videoSeen;

            /**
             * Whether a video is on screen under the bar. The blur draws the views under the bar a second time, and a
             * SurfaceView drawn in two places has its video layer moved and cropped to the second, so the reel
             * shrinks to a strip behind the bar and the rest of the screen is black. A TextureView is left out of
             * the copy for the same reason. A video can't be blurred from its own layer anyway, so while one shows
             * the pill is the frosted tint. A video that has been seen holds for {@link #VIDEO_HOLD_MS}, so the
             * views aren't walked again every frame.
             */
            private boolean videoUnder(ViewGroup parent, int barIndex) {
                long now = SystemClock.uptimeMillis();
                if (now - videoSeen < VIDEO_HOLD_MS) return true;
                for (int i = 0; i < barIndex; i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling.getVisibility() == View.VISIBLE && hasVideo(sibling, 0)) {
                        videoSeen = now;
                        return true;
                    }
                }
                return false;
            }

            private boolean hasVideo(View view, int depth) {
                if (view.getVisibility() != View.VISIBLE || depth > 40) return false;
                if (view instanceof android.view.SurfaceView || view instanceof android.view.TextureView) {
                    return view.getGlobalVisibleRect(videoRect);
                }
                if (!(view instanceof ViewGroup)) return false;
                ViewGroup group = (ViewGroup) view;
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    if (hasVideo(group.getChildAt(i), depth + 1)) return true;
                }
                return false;
            }

            private final android.graphics.Rect videoRect = new android.graphics.Rect();

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

    /**
     * Whether the glass records its backdrop in this frame: always the first time; otherwise once
     * {@link #RECORD_EVERY_MS} have passed since the last recording, unless the frame is already
     * {@link #LATE_FRAME_MS} into its work, which only waits as long as {@link #MAX_STALE_MS}.
     */
    static boolean shouldRecord(boolean recordedBefore, long sinceLastMs, long inFrameMs) {
        if (!recordedBefore) return true;
        if (sinceLastMs < RECORD_EVERY_MS) return false;
        return inFrameMs <= LATE_FRAME_MS || sinceLastMs >= MAX_STALE_MS;
    }

    /**
     * How far the capsule has got through a slide that started [elapsed] ms ago: eased out, so it starts
     * quick and settles, and 1 once {@link #SLIDE_MS} has passed.
     */
    static float slideProgress(long elapsed) {
        if (elapsed >= SLIDE_MS) return 1f;
        if (elapsed <= 0) return 0f;
        float t = elapsed / (float) SLIDE_MS;
        float rest = 1f - t;
        return 1f - rest * rest * rest;
    }

    /**
     * The tick when the capsule passes a tab, in the {@link HapticStyle} chosen.
     *
     * <p>{@link HapticStyle#SHORT} is a single pulse of the motor's own, {@link #SHORT_MS} long at
     * {@link #SHORT_AMPLITUDE} of 255: an iPhone's tick comes and goes in one quick tap, and a motor rings on
     * after a gentler signal of the same length, so the pulse is kept short rather than weak. A phone whose
     * motor can't be driven at a chosen strength plays the light tick instead. {@link HapticStyle#SYSTEM} plays the
     * phone's own click effect, which a maker tunes to the motor: crisper and stronger than the selection haptic
     * of a view, which the phone may soften to an ordinary buzz. {@link HapticStyle#SOFT} is the tick
     * primitive at {@link #SOFT_SCALE} of its strength and {@link HapticStyle#FULL} at all of it, or the built-in
     * tick on a phone that can't play primitives.
     *
     * <p>They play on a thread of their own, with the touch usage on Android 13 and newer, so they follow
     * the phone's touch feedback setting and never hold up a frame; a call to the system on the UI thread can take
     * a few milliseconds. Older Android uses the view's own haptics for the light and full ticks. Ticks closer
     * together than {@link #MIN_TICK_GAP_MS} are dropped.
     */
    static final class Haptics {
        /** How much of the phone's tick primitive the light tick plays. */
        static final float SOFT_SCALE = 0.45f;
        /** The short pulse: how long, and how hard of 255. */
        static final int SHORT_MS = 4;
        static final int SHORT_AMPLITUDE = 170;
        static final long MIN_TICK_GAP_MS = 35;

        private static Handler handler;
        private static long lastTick;
        private static int primitives = -1;

        private Haptics() {
        }

        /** Whether a tick at [now] is far enough after the one at [last] to play. */
        static boolean farEnough(long now, long last) {
            return now - last >= MIN_TICK_GAP_MS;
        }

        static void tick(View view, HapticStyle style) {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                if (!farEnough(now, lastTick)) return;
                lastTick = now;
                if (style == HapticStyle.SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return;
                }
                Context context = view.getContext().getApplicationContext();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && style != HapticStyle.SHORT
                        && style != HapticStyle.SYSTEM) {
                    view.performHapticFeedback(style == HapticStyle.FULL ? HapticFeedbackConstants.CLOCK_TICK
                            : HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                    return;
                }
                handler().post(() -> play(context, style));
            } catch (Throwable failure) {
                diagnose("haptic failed", failure);
            }
        }

        private static void play(Context context, HapticStyle style) {
            try {
                android.os.Vibrator vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? context.getSystemService(android.os.VibratorManager.class).getDefaultVibrator()
                        : (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator == null || !vibrator.hasVibrator()) return;
                android.os.VibrationEffect effect = null;
                // Before Android 10 the system tick never gets here: tick() plays it through the view.
                if (style == HapticStyle.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    effect = android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK);
                } else if (style == HapticStyle.SHORT && vibrator.hasAmplitudeControl()) {
                    effect = android.os.VibrationEffect.createOneShot(SHORT_MS, SHORT_AMPLITUDE);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (primitives < 0) {
                        primitives = vibrator.areAllPrimitivesSupported(
                                android.os.VibrationEffect.Composition.PRIMITIVE_TICK) ? 1 : 0;
                    }
                    if (primitives == 1) {
                        effect = android.os.VibrationEffect.startComposition().addPrimitive(
                                android.os.VibrationEffect.Composition.PRIMITIVE_TICK,
                                style == HapticStyle.FULL ? 1f : SOFT_SCALE).compose();
                    }
                }
                // Android 9 has no predefined effects, so a motor without amplitude control gets the short pulse.
                if (effect == null) {
                    effect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
                            : android.os.VibrationEffect.createOneShot(SHORT_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                            android.os.VibrationAttributes.USAGE_TOUCH));
                } else {
                    vibrator.vibrate(effect);
                }
            } catch (Throwable failure) {
                diagnose("haptic failed", failure);
            }
        }

        private static synchronized Handler handler() {
            if (handler == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("hushgram-haptics");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            return handler;
        }
    }
}
