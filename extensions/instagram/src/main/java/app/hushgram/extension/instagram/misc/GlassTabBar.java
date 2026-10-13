/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.animation.ValueAnimator;
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
import android.graphics.drawable.RippleDrawable;
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
 * in a TextureView come through. A video in a SurfaceView is left out and shows through the tint
 * instead, so the glass stays glass over videos. The glass shows the strip just above the bar, upside down. With the
 * float switch on, the content container also runs to the bottom of the screen, so nothing of
 * Instagram's own colour is left round the pill. Reels viewers still stop at the bar's top, over black,
 * so their seek bar, caption and touch strip stay clear of the pill.
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
    /** Home's pager on an account whose tabs swipe sideways. It sits beside the content, not in it. */
    static final String SWIPE_PAGER = "swipeable_tab_view_pager";
    /** The root of the Reels viewer, in the Reels tab and wherever a reel opens over another tab. */
    static final String REELS_ROOT = "root_clips_layout";
    /** The strip Instagram lays over a reel's seek bar to widen it for touches, and the seek bar. */
    static final String TOUCH_STRIP = "clips_expanded_touch_view";
    static final String SCRUBBER = "scrubber";

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

    /**
     * Each child of [content], and the sibling of [content] whose id is [pager] (0 for none), whose
     * bottom margin is [barHeight] gets [wanted] in its place. Any other margin is left alone.
     */
    static void screensAboveTheBar(ViewGroup content, int pager, int barHeight, int wanted) {
        for (int i = 0; i < content.getChildCount(); i++) aboveTheBar(content.getChildAt(i), barHeight, wanted);
        if (pager == 0 || !(content.getParent() instanceof ViewGroup)) return;
        ViewGroup holder = (ViewGroup) content.getParent();
        for (int i = 0; i < holder.getChildCount(); i++) {
            View sibling = holder.getChildAt(i);
            if (sibling.getId() == pager) aboveTheBar(sibling, barHeight, wanted);
        }
    }

    private static void aboveTheBar(View screen, int barHeight, int wanted) {
        if (!(screen.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
        if (margins.bottomMargin == barHeight) {
            margins.bottomMargin = wanted;
            screen.setLayoutParams(margins);
        }
    }

    /**
     * Gives each view under [root] whose id is [id], outside [bar]'s branch, a bottom margin of [wanted].
     * These are the Reels viewers on a floating bar. Run down behind the pill, a reel's seek bar and caption
     * sat under it, and so did the strip Instagram lays over the bar for the seek bar's touches, a later
     * sibling of the bar that took every tap on the pill's lower half: Home and Profile did nothing from
     * Reels. A margin that is neither 0 nor one this gave, which [given] remembers, is Instagram's and stays.
     * Returns how many changed.
     */
    static int reelsAboveTheBar(View root, View bar, int id, int wanted, java.util.Map<View, Integer> given) {
        return liftReels(root, bar, id, wanted, given, 0);
    }

    private static int liftReels(View view, View bar, int id, int wanted, java.util.Map<View, Integer> given, int depth) {
        if (view == bar || depth > 60) return 0;
        if (view.getId() == id && id != 0) {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return 0;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            Integer ours = given.get(view);
            if (margins.bottomMargin == wanted) {
                if (wanted != 0) given.put(view, wanted);
                return 0;
            }
            if (margins.bottomMargin != 0 && (ours == null || ours != margins.bottomMargin)) return 0;
            margins.bottomMargin = wanted;
            view.setLayoutParams(margins);
            if (wanted == 0) given.remove(view);
            else given.put(view, wanted);
            return 1;
        }
        if (!(view instanceof ViewGroup)) return 0;
        ViewGroup group = (ViewGroup) view;
        int changed = 0;
        for (int i = 0; i < group.getChildCount(); i++) changed += liftReels(group.getChildAt(i), bar, id, wanted, given, depth + 1);
        return changed;
    }

    /**
     * Moves [strip], the strip Instagram lays over a reel's seek bar ({@link #TOUCH_STRIP}), so its bottom
     * edge is the bottom edge of the seek bar ([scrubber]) showing in the one of [viewers] it lies across,
     * or of that viewer when no seek bar shows. Returns whether it moved.
     *
     * <p>Instagram places the strip by how far the reel stops above the bottom of the screens, measured
     * as it lays them out. Back from another screen (HushGram's settings, say) it measures while its own
     * margin under the screens is back on for a frame, with the viewer's margin from a floating pill
     * counted too: the strip sat a bar's height too high, over the reel's More button and name, and took
     * their taps. Measured before a viewer was kept above the pill, it sits over the pill's top instead.
     * A strip anchored from the top, or not laid out yet, is left alone.
     */
    static boolean stripOnTheReel(View strip, Iterable<View> viewers, int scrubber, Rect scratch) {
        if (strip == null || !strip.isShown() || !strip.isLaidOut() || strip.isLayoutRequested()) return false;
        if (!(strip.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) strip.getLayoutParams();
        if ((params.gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.BOTTOM) return false;
        int[] at = new int[2];
        strip.getLocationOnScreen(at);
        int left = at[0], right = at[0] + strip.getWidth(), bottom = at[1] + strip.getHeight();
        View viewer = null;
        int widest = 0;
        for (View candidate : viewers) {
            if (candidate == null || !candidate.isShown() || candidate.isLayoutRequested()
                    || !candidate.getGlobalVisibleRect(scratch)) continue;
            int across = Math.min(right, scratch.right) - Math.max(left, scratch.left);
            if (across > widest) {
                widest = across;
                viewer = candidate;
            }
        }
        if (viewer == null) return false;
        View seekBar = scrubber == 0 ? null : shownWithId(viewer, scrubber, scratch, 0);
        View anchor = seekBar != null ? seekBar : viewer;
        anchor.getLocationOnScreen(at);
        int off = bottom - (at[1] + anchor.getHeight());
        if (off == 0 || params.bottomMargin + off < 0) return false;
        params.bottomMargin += off;
        strip.setLayoutParams(params);
        return true;
    }

    /** Whether [drawable] is a ripple with nothing in it but its mask: a touch's feedback, which draws nothing at rest. */
    static boolean touchFeedbackOnly(Drawable drawable) {
        if (!(drawable instanceof RippleDrawable)) return false;
        RippleDrawable ripple = (RippleDrawable) drawable;
        for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
            if (ripple.getId(i) != android.R.id.mask) return false;
        }
        return true;
    }

    /** The view with [id] under [view] that shows on screen, or null. Pages scrolled off the screen are skipped. */
    @Nullable
    private static View shownWithId(View view, int id, Rect scratch, int depth) {
        if (depth > 60 || view.getVisibility() != View.VISIBLE) return null;
        boolean onScreen = view.getGlobalVisibleRect(scratch);
        if (view.getId() == id) return onScreen ? view : null;
        if (!onScreen || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = shownWithId(group.getChildAt(i), id, scratch, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Puts each SurfaceView under [parent]'s first [count] children in [into], with every view on the way
     * down to one, and says whether there was any. The glass draws those views itself so it can leave the
     * SurfaceViews out. A recording holds the views under the bar, not a picture of them, and a SurfaceView
     * drawn a second time, into the glass, has its picture moved there (#136): the video dropped to the
     * bottom of the screen, under the bar, and left black where it played.
     */
    static boolean surfacePaths(ViewGroup parent, int count, java.util.Set<View> into) {
        into.clear();
        boolean found = false;
        for (int i = 0; i < count; i++) found |= findSurfaces(parent.getChildAt(i), into, 0);
        return found;
    }

    private static boolean findSurfaces(View view, java.util.Set<View> into, int depth) {
        if (view instanceof android.view.SurfaceView) {
            into.add(view);
            return true;
        }
        if (!(view instanceof ViewGroup) || depth > 100) return false;
        ViewGroup group = (ViewGroup) view;
        boolean found = false;
        for (int i = 0, n = group.getChildCount(); i < n; i++) found |= findSurfaces(group.getChildAt(i), into, depth + 1);
        if (found) into.add(view);
        return found;
    }

    /**
     * Draws [view] into [canvas] where its parent would, leaving out the SurfaceViews in [paths]. A view on
     * the way down to one is drawn piece by piece, its background and then each child, and any other view
     * draws itself whole. A video in a SurfaceView is a layer under the window, never in the views, so
     * nothing of it is lost: the pill shows it through its tint, unblurred.
     */
    static void drawLeavingOutSurfaces(Canvas canvas, View view, java.util.Set<View> paths, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f || view instanceof android.view.SurfaceView) return;
        int saved = canvas.save();
        canvas.translate(view.getLeft(), view.getTop());
        if (!view.getMatrix().isIdentity()) canvas.concat(view.getMatrix());
        if (!(view instanceof ViewGroup) || !paths.contains(view) || depth > 100) {
            canvas.translate(-view.getScrollX(), -view.getScrollY());
            view.draw(canvas);
        } else {
            ViewGroup group = (ViewGroup) view;
            if (group.getClipChildren()) canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
            Drawable background = view.getBackground();
            if (background != null) background.draw(canvas);
            canvas.translate(-view.getScrollX(), -view.getScrollY());
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                drawLeavingOutSurfaces(canvas, group.getChildAt(i), paths, depth + 1);
            }
        }
        canvas.restoreToCount(saved);
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
    /** How long a missing shadow or content view stays unsearched before the parent is walked for it again. */
    static final long RESEARCH_MS = 500;
    /**
     * How many redraws a run of misses asks for, so a view that turns up after a screen switch is
     * found even when nothing else draws the bar, without a bar that never has one drawing forever.
     */
    static final int MISS_REDRAWS = 4;
    /** How often the views are walked for a Reels viewer to keep above a floating pill. */
    static final long REELS_LOOK_MS = 100;
    /** Counted in the glass family's Diagnostics line each time a Reels viewer is moved to clear the pill. */
    static final String COUNT_REELS_LIFTED = "Reels viewer kept above the pill";
    /** Counted each time a video in a SurfaceView shows up under the bar, with none there before, and is kept out of the blur. */
    static final String COUNT_VIDEO_LEFT_OUT = "Video kept out of the blur";
    /** Counted each time a tab's own background, Instagram's selected tab capsule, is taken off under the pill. */
    static final String COUNT_TAB_BACKGROUND = "Tab's own capsule taken off";
    /** Counted each time Instagram's touch strip for a reel's seek bar is moved back onto the seek bar. */
    static final String COUNT_STRIP_KEPT = "Seek bar's touch strip kept on the reel";

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

    /**
     * How much higher a floating pill sits, with the bar that much taller so the pill and its tabs keep
     * their size: [lift]'s dp, or 0 for a pill that doesn't float or no choice read.
     */
    static int liftPx(GlassLift lift, boolean floating, float density) {
        return floating && lift != null ? Math.round(lift.dp * density) : 0;
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
    /**
     * When the bar last searched for its shadow or content view and missed, and how many redraws the
     * misses since both were last found have asked for.
     */
    static final class Misses {
        private long missedAt = Long.MIN_VALUE / 2;
        private int redraws;

        /** Whether it's been [RESEARCH_MS] since the last miss. */
        boolean lookAgain(long now) {
            return now - missedAt >= RESEARCH_MS;
        }

        /**
         * Notes a draw's look: [missed] when a search found nothing, [bothKnown] when both views are
         * held. True when the bar should draw again after [RESEARCH_MS] to look once more.
         */
        boolean looked(long now, boolean missed, boolean bothKnown) {
            if (missed) {
                missedAt = now;
                if (redraws >= MISS_REDRAWS) return false;
                redraws++;
                return true;
            }
            if (bothKnown) redraws = 0;
            return false;
        }
    }

    private static final class Glass implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        private final ViewGroup bar;
        private final float density;
        private final boolean blurWanted;
        private final boolean haptics;
        private final HapticStyle hapticStyle;
        private final GlassOpacity opacity;
        private final GlassHeight height;
        /** How much higher the pill sits, in pixels: the room under a floating pill. */
        private final int lift;
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
        /** When the shadow or the content was last missed, so a miss isn't repeated every frame. */
        private final Misses misses = new Misses();
        /** The Reels viewers kept above a floating pill, with the margin each was given. */
        private final WeakHashMap<View, Integer> lifted = new WeakHashMap<>();
        /** Instagram's touch strip for the seek bar, found once: Instagram keeps one and moves it between reels. */
        @Nullable private WeakReference<View> stripRef;
        private long reelsLookedAt = Long.MIN_VALUE / 2;
        /** The stretch of the bar, in its own coordinates, under the Reels viewers kept above it, as of the last frame. */
        private int reelsLeft, reelsRight;
        private final Rect reelsRect = new Rect();
        private final int[] barOnScreen = new int[2];

        Glass(ViewGroup bar) {
            this.bar = bar;
            this.density = bar.getResources().getDisplayMetrics().density;
            this.blurWanted = Settings.GLASS_TAB_BAR_BLUR.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            this.floating = Settings.GLASS_TAB_BAR_FLOAT.get();
            this.haptics = Settings.GLASS_TAB_BAR_HAPTICS.get();
            this.hapticStyle = Settings.GLASS_TAB_BAR_HAPTIC_STYLE.get();
            this.opacity = Settings.GLASS_TAB_BAR_OPACITY.get();
            this.height = Settings.GLASS_TAB_BAR_HEIGHT.get();
            this.lift = liftPx(Settings.GLASS_TAB_BAR_LIFT.get(), floating, density);
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
            return pillInsetPx(height, bar.getHeight() - lift, gap(), density);
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
            // Instagram's newer bar marks the selected tab with a dark capsule, that tab's own
            // background. The pill draws its own capsule, and theirs would sit on top of it. A bare
            // ripple is the tab's touch feedback, not a capsule, and stays.
            for (int i = 0; i < bar.getChildCount(); i++) {
                View tab = bar.getChildAt(i);
                Drawable own = tab.getBackground();
                if (own == null || touchFeedbackOnly(own)) continue;
                tab.setBackground(null);
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_TAB_BACKGROUND);
                diagnose("tab background taken off: " + own.getClass().getName(), null);
            }
            View parent = bar.getParent() instanceof View ? (View) bar.getParent() : null;
            int width = parent != null && parent.getWidth() > 0 ? parent.getWidth()
                    : bar.getResources().getDisplayMetrics().widthPixels;
            outer = outerPx(width, density);

            // Height: the bar's own, which is what Instagram keeps every screen's own controls above, and
            // the room asked for under a floating pill.
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params != null && params.height > 0) {
                if (originalBarHeight < 0) originalBarHeight = params.height;
                int wanted = barHeightPx(height, originalBarHeight, density) + lift;
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
            int under = originalBottom + gap() + lift;
            if (left != bar.getPaddingLeft() || right != bar.getPaddingRight()
                    || above != bar.getPaddingTop() || under != bar.getPaddingBottom()) {
                bar.setPadding(left, above, right, under);
            }
            lastLeft = left;
            lastRight = right;
            lastTop = above;
            lastBottom = under;

            if (parent != null) {
                // A view that was never found is searched for again every RESEARCH_MS, not every
                // frame: a bar without a shadow or a content view would otherwise walk its parent on
                // each draw. One that was found and came off the window is looked for at once.
                long now = SystemClock.uptimeMillis();
                boolean lookAgain = misses.lookAgain(now);
                boolean missed = false;
                View shadow = shadowRef == null ? null : shadowRef.get();
                if (shadow == null || !shadow.isAttachedToWindow()) {
                    if (shadowRef != null || lookAgain) {
                        shadow = find(parent, TAB_BAR_SHADOW);
                        shadowRef = shadow == null ? null : new WeakReference<>(shadow);
                        missed |= shadow == null;
                    } else {
                        shadow = null;
                    }
                }
                if (shadow != null && shadow.getVisibility() != View.GONE) shadow.setVisibility(View.GONE);
                View content = contentRef == null ? null : contentRef.get();
                if (content == null || !content.isAttachedToWindow()) {
                    if (contentRef != null || lookAgain) {
                        content = find(parent, CONTENT);
                        contentRef = content == null ? null : new WeakReference<>(content);
                        missed |= content == null;
                    } else {
                        content = null;
                    }
                }
                if (misses.looked(now, missed, shadowRef != null && contentRef != null)) {
                    bar.postInvalidateDelayed(RESEARCH_MS);
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
                if (floating) keepReelsAboveThePill(parent, params, now);
            }
        }

        /**
         * With the screens running behind a floating pill, each Reels viewer still stops at the bar's top, as
         * it does over Instagram's own bar ({@link #reelsAboveTheBar}), and the bar paints black under it, as
         * Instagram paints its bar on Reels. The views are walked for viewers at most every
         * {@link #REELS_LOOK_MS}. While Instagram hides the bar, the viewers get the room back.
         */
        private void keepReelsAboveThePill(View holder, @Nullable ViewGroup.LayoutParams params, long now) {
            if (params == null || params.height <= 0 || now - reelsLookedAt < REELS_LOOK_MS) return;
            reelsLookedAt = now;
            int wanted = bar.getVisibility() == View.VISIBLE ? params.height : 0;
            if (reelsAboveTheBar(holder, bar, id(holder.getContext(), REELS_ROOT), wanted, lifted) > 0) {
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_REELS_LIFTED);
            }
            keepTheStripOnTheReel(holder);
        }

        /** Keeps Instagram's touch strip on the seek bar of the reel above the pill ({@link #stripOnTheReel}). */
        private void keepTheStripOnTheReel(View holder) {
            if (lifted.isEmpty()) return;
            if (holder.isLayoutRequested()) {
                // A margin changed this frame, so the reel and the strip are where they were. Look again after the layout.
                bar.postInvalidateDelayed(REELS_LOOK_MS);
                return;
            }
            View strip = stripRef == null ? null : stripRef.get();
            if (strip == null || !strip.isAttachedToWindow()) {
                strip = find(holder, TOUCH_STRIP);
                stripRef = strip == null ? null : new WeakReference<>(strip);
            }
            if (strip != null && stripOnTheReel(strip, lifted.keySet(), id(holder.getContext(), SCRUBBER), reelsRect)) {
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_STRIP_KEPT);
            }
        }

        /**
         * Where the Reels viewers kept above the bar sit across it, so the bar can paint black under them.
         * A swipe between tabs moves them without drawing the bar, so a change asks for a redraw.
         */
        private void followReels() {
            int left = 0, right = 0;
            if (floating && !lifted.isEmpty()) {
                bar.getLocationOnScreen(barOnScreen);
                for (View viewer : lifted.keySet()) {
                    if (viewer == null || !viewer.isShown() || !viewer.getGlobalVisibleRect(reelsRect)) continue;
                    int from = Math.max(0, reelsRect.left - barOnScreen[0]);
                    int to = Math.min(bar.getWidth(), reelsRect.right - barOnScreen[0]);
                    if (to <= from) continue;
                    if (right <= left) {
                        left = from;
                        right = to;
                    } else {
                        left = Math.min(left, from);
                        right = Math.max(right, to);
                    }
                }
            }
            if (left != reelsLeft || right != reelsRight) {
                reelsLeft = left;
                reelsRight = right;
                bar.invalidate();
            }
        }

        /**
         * Instagram sizes each main screen (Home's pager, Reels and the rest) to stop where its own tab bar
         * did, by a bottom margin as tall as that bar. The pill is taller, so a reel's seek bar and the
         * bottom of every other screen would run in under it. Any child of the content with exactly that
         * margin gets the pill's height instead, and none at all while the content runs behind the bar. A
         * margin of anything else, such as 0 while Instagram hides its bar, is left alone. Where the tabs
         * swipe sideways, Home's pager is the content's sibling, beside the bar, and gets the same.
         */
        private void keepScreensAboveTheBar(ViewGroup content, @Nullable ViewGroup.LayoutParams params) {
            if (originalBarHeight <= 0 || params == null || params.height <= 0) return;
            int wanted = floating ? 0 : params.height;
            if (wanted == originalBarHeight) return;
            screensAboveTheBar(content, id(content.getContext(), SWIPE_PAGER), originalBarHeight, wanted);
        }

        @Override public boolean onPreDraw() {
            Trace.beginSection("hushgram:preDraw");
            try {
                keep();
                followReels();
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
                // Off screen the bar is still drawn, so a video that starts under it is still kept out.
                if (!blurBroken && blurWanted) {
                    if (scrollHide == null || !scrollHide.isOffScreen()) pill.recordIfDue();
                    else pill.recordIfSurfaceAppeared();
                }
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
                float bottomEdge = bar.getHeight() - lift - gap() - pillInset();
                if (right - left <= 0 || bottomEdge - top <= 0) return false;
                into.set(left, top, right, bottomEdge);
                return true;
            }

            private void drawPill(@NonNull Canvas canvas) {
                if (!pillBounds(rect)) return;
                // Fully round ends: half the pill's height.
                float radius = rect.height() / 2f;
                boolean dark = isDark(base);

                // The content stops above the bar, so the bar's own colour fills round the pill.
                if (!floating) {
                    fill.setColor(base);
                    canvas.drawRect(0, 0, bar.getWidth(), bar.getHeight(), fill);
                } else if (reelsRight > reelsLeft) {
                    // A Reels viewer stops above the bar, and Reels is black whatever the theme.
                    fill.setColor(Color.BLACK);
                    canvas.drawRect(reelsLeft, 0, reelsRight, bar.getHeight(), fill);
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
                    slideStart = slideStartAt(now);
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
                if (shouldRecord(recorded, since, inFrame) || surfaceAppeared()) {
                    lastRecorded = now;
                    recordBackdrop();
                } else if (!catchUpPending) {
                    catchUpPending = true;
                    bar.postDelayed(catchUp, Math.max(RECORD_EVERY_MS - since, 0) + 4);
                }
            }

            /** Each SurfaceView under the bar, and the views down to it, that the last recording left out. */
            private final java.util.Set<View> leftOut = new java.util.HashSet<>();
            private final java.util.Set<View> surfaces = new java.util.HashSet<>();

            void recordIfSurfaceAppeared() {
                if (!surfaceAppeared()) return;
                lastRecorded = SystemClock.uptimeMillis();
                recordBackdrop();
            }

            /**
             * Whether a SurfaceView under the bar isn't one the last recording left out, such as a feed video
             * started with a tap. The recording draws the views under the bar as they are when the frame draws,
             * so it would draw that video too: it has to be recorded again in this frame, not when it's due.
             */
            private boolean surfaceAppeared() {
                if (!recorded || !(bar.getParent() instanceof ViewGroup)) return false;
                ViewGroup parent = (ViewGroup) bar.getParent();
                int barIndex = parent.indexOfChild(bar);
                if (barIndex <= 0) return false;
                surfacePaths(parent, barIndex, surfaces);
                return !leftOut.containsAll(surfaces);
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
                        boolean hadVideo = !leftOut.isEmpty();
                        if (surfacePaths(parent, barIndex, leftOut) && !hadVideo) {
                            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_VIDEO_LEFT_OUT);
                        }
                        for (int i = 0; i < barIndex; i++) {
                            View sibling = parent.getChildAt(i);
                            if (sibling.getWidth() <= 0 || sibling.getBottom() <= edge - tall || sibling.getTop() >= edge) continue;
                            drawLeavingOutSurfaces(recording, sibling, leftOut, 0);
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
     * When a slide that begins now counts as having started: now, or {@link #SLIDE_MS} ago when the
     * phone's animations are off (Remove animations under accessibility, or an animator scale of 0),
     * so the capsule lands on the new tab in one frame instead of sliding there.
     */
    static long slideStartAt(long now) {
        return animationsOn() ? now : now - SLIDE_MS;
    }

    @Nullable static Boolean animationsOnForTests;

    static boolean animationsOn() {
        Boolean forTests = animationsOnForTests;
        return forTests != null ? forTests : ValueAnimator.areAnimatorsEnabled();
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
