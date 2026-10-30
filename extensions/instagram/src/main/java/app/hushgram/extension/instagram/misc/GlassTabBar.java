/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.reels.FullScreenBars;
import app.hushgram.extension.instagram.reels.ReelBlurBars;
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
 * marks selected gets a lighter capsule that slides to the next one. With Slide across the tabs on, a
 * finger slid along the pill carries the capsule and opens the tab it lifts over ({@link GlassTabSlide}).
 * Without blur, or with
 * Android's blur unavailable, the pill is the same tint, more opaque.
 *
 * <p>The liquid look (#65) rounds the pill and the capsule fully, moves Search into a glass circle of its own
 * beside the pill, and spreads the other tabs over what's left, all by translation, so nothing is laid out
 * again. Both shapes share the one blurred backdrop, clipped to them. With Hide the tab bar as you scroll off,
 * a list scrolled down shrinks the pill to a circle round the tab you're on, next to Search's circle
 * ({@link GlassScrollHide}), and the other tabs fade out and take no touches until it grows back.
 *
 * <p>Sizes follow the pill on Instagram's iPhone tab bar, as proportions of the screen's width
 * (see the constants), so they carry to any width, and are held to sensible limits on narrow and
 * wide ones.
 *
 * <p>What the pill blurs is Instagram's own screens, the views drawn under the bar, recorded into a render node of its own and blurred
 * on the GPU, never read back from the screen. It's the views as they draw, so photos, text and video
 * in a TextureView come through. A video in a SurfaceView can't be recorded, so the glass draws a small
 * copy of its frame in its place ({@link SurfaceFrames}, #144), and leaves the place clear until one lands,
 * so the glass stays glass over videos. A docked bar's glass shows the strip just above it, upside down.
 * With the float switch on, the content container also runs to the bottom of the screen and the glass shows
 * what's behind it (#262), so nothing of Instagram's own colour is left round the pill. Reels are the one
 * screen left out: the Reels viewer stops at the top of the pill's slot, at one size whether the pill
 * shows, slides away or Instagram hides its bar (#221, #280), and the glass never draws it (#222).
 *
 * <p>Instagram sets the bar's colour, padding and visibility from time to time, so a draw listener
 * puts the pieces back whenever one is changed. A failure there is reported and the bar carries on. A
 * blur that keeps failing rests on the plain tint for a while, then tries again ({@link BlurTries}, #262).
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
    /** A reel's author, caption and the buttons beside them, which share a layer over the video with the seek bar. */
    static final String REEL_INFO = "clips_media_info_component";
    /** What holds a reel's video. Only views beside it are raised, so the video never moves. */
    static final String REEL_MEDIA = "clips_media_component";
    /** Where Instagram puts what shows under a reel: a paused reel's controls, a comment field and the like (#260). */
    static final String REEL_FOOTERS = "clips_footer_mask_container";
    /** The Search tab, a child of the bar, which the liquid look puts in a circle of its own (#65). */
    static final String SEARCH_TAB = "search_tab";
    /** The Reels tab, a child of the bar. With it selected a reel keeps the pill's slot while the bar hides (#347). */
    static final String REELS_TAB = "clips_tab";
    /**
     * Where Instagram puts a bottom sheet (comments, share, a post's menu) over the screens and the bar.
     * It has a child only while a sheet is up (#209).
     */
    static final String SHEET_HOLDER = "layout_container_bottom_sheet";
    /**
     * The dimmed backdrop beside the sheet holder, which covers the whole window. The holder's other
     * neighbours, such as a sheet's footer, keep clear of the navigation bar with it ({@link Glass#liftSheets}).
     */
    static final String SHEET_DIMMER = "background_dimmer";
    /** How often the sheet holder is looked for again while there isn't one. */
    static final long SHEET_LOOK_MS = 250L;
    /**
     * The story viewer's root, which Instagram puts in the screens' container. Instagram hides its bar
     * while a story is open, but on Android 17 the pill stayed over the story's reply box, with the
     * navigation bar answered 0 under it (#224). It's looked for as often as the sheet holder.
     */
    static final String STORY_ROOT = "reel_viewer_root";

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
     * These are the Reels viewers on a floating bar, which keep one size whether the pill shows or not
     * (#221). A margin that is neither 0 nor one this gave, which [given] remembers, is Instagram's and
     * stays. Returns how many changed.
     */
    static int reelsAboveTheBar(View root, View bar, int id, int wanted, java.util.Map<View, Integer> given) {
        return liftReels(root, bar, id, wanted, given, null, 0);
    }

    /** {@link #reelsAboveTheBar}, also putting every viewer it meets in [seen]. */
    static int reelsAboveTheBar(View root, View bar, int id, int wanted, java.util.Map<View, Integer> given,
            @Nullable java.util.Collection<View> seen) {
        return liftReels(root, bar, id, wanted, given, seen, 0);
    }

    /**
     * Whether the Reels viewers keep the pill's slot under them: while [bar] shows, sliding away or not
     * (#170), or with the Reels tab ([reelsTab]) selected, where Instagram hides the bar for a paused reel
     * and the viewer keeps one size (#221). A reel opened from a feed post has Instagram hide the bar
     * over Home, and the slot kept there was a blank band under the reel (#347).
     */
    static boolean reelsKeepTheSlot(View bar, int reelsTab) {
        if (bar.getVisibility() == View.VISIBLE) return true;
        if (reelsTab == 0 || !(bar instanceof ViewGroup)) return false;
        ViewGroup tabs = (ViewGroup) bar;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            // A Reels tab tucked out of the bar (Tab order) is still the tab Instagram shows when selected.
            if (tab.getId() == reelsTab && tab.isSelected()) return true;
        }
        return false;
    }

    private static int liftReels(View view, View bar, int id, int wanted, java.util.Map<View, Integer> given,
            @Nullable java.util.Collection<View> seen, int depth) {
        if (view == bar || depth > 60) return 0;
        if (view.getId() == id && id != 0) {
            if (seen != null) seen.add(view);
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
        for (int i = 0; i < group.getChildCount(); i++) changed += liftReels(group.getChildAt(i), bar, id, wanted, given, seen, depth + 1);
        return changed;
    }

    /**
     * Gives each child of [sheets], Instagram's sheet holder and its neighbours, a bottom margin of
     * [wanted], all but the dimmed backdrop ([dimmer]), so a sheet stops above the navigation bar while
     * Instagram is answered 0 for it (#209, #222). As with the Reels viewers, a margin that is neither 0
     * nor one this gave, which [given] remembers, is Instagram's and stays. Returns how many changed.
     */
    static int sheetsAboveTheNavigationBar(ViewGroup sheets, int dimmer, int wanted, java.util.Map<View, Integer> given) {
        int changed = 0;
        for (int i = 0; i < sheets.getChildCount(); i++) {
            View view = sheets.getChildAt(i);
            if (dimmer != 0 && view.getId() == dimmer) continue;
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            Integer ours = given.get(view);
            if (margins.bottomMargin == wanted) {
                if (wanted != 0) given.put(view, wanted);
                continue;
            }
            if (margins.bottomMargin != 0 && (ours == null || ours != margins.bottomMargin)) continue;
            margins.bottomMargin = wanted;
            view.setLayoutParams(margins);
            if (wanted == 0) given.remove(view);
            else given.put(view, wanted);
            changed++;
        }
        return changed;
    }

    /**
     * The view to raise so [anchor], something at the bottom of a reel, clears a floating pill: the child
     * holding it of the reel's layer of controls, the last view up from [anchor] before one that holds the
     * video ([media]). Raising that moves the caption, the buttons or the seek bar and leaves the video,
     * stickers and play button where they are. Null with no [media] id, or when nothing up from [anchor]
     * short of [stop] holds the video.
     */
    @Nullable
    static View reelBand(View anchor, int media, View stop) {
        if (media == 0) return null;
        View below = null, at = anchor;
        for (int depth = 0; depth < 16 && at != stop; depth++) {
            if (!(at.getParent() instanceof ViewGroup)) return null;
            ViewGroup up = (ViewGroup) at.getParent();
            if (up.findViewById(media) != null) return below != null ? below : at;
            below = at;
            at = up;
        }
        return null;
    }

    /**
     * How far [band]'s content can scroll up before a shown child of it is cut off at the band's top: the
     * top of the highest one, or 0 with none.
     */
    static int roomAbove(ViewGroup band) {
        int room = Integer.MAX_VALUE;
        for (int i = 0; i < band.getChildCount(); i++) {
            View child = band.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
            room = Math.min(room, child.getTop());
        }
        return room == Integer.MAX_VALUE ? 0 : Math.max(0, room);
    }

    /**
     * Puts each shown view under [view] whose id is [info] or [scrubber] in [anchors], and each whose id
     * is [footers] in [footerViews], without looking inside those.
     */
    static void reelAnchors(View view, int info, int scrubber, int footers, java.util.List<View> anchors,
            java.util.List<View> footerViews, int depth) {
        if (depth > 60 || view.getVisibility() != View.VISIBLE) return;
        int own = view.getId();
        if (own != View.NO_ID && own != 0) {
            if (own == footers) {
                footerViews.add(view);
                return;
            }
            if (own == info || own == scrubber) {
                anchors.add(view);
                return;
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            reelAnchors(group.getChildAt(i), info, scrubber, footers, anchors, footerViews, depth + 1);
        }
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

    /**
     * Makes the ring round [badge] clear (#315): a GradientDrawable background gets a clear stroke
     * [width] wide. One not in [cleared] is cleared as usual and added, and that's the true answer.
     * One cleared before is cleared again only while the badge waits to redraw, and without asking for
     * a frame: Instagram strokes a dot again in place when a tab is picked, and asks for the redraw
     * itself, while a frame asked for here would come back here and ask for another.
     */
    static boolean clearRing(View badge, int width, java.util.Set<Drawable> cleared) {
        Drawable background = badge.getBackground();
        if (!(background instanceof GradientDrawable)) return false;
        // Mutated first: a dot's drawable comes from Instagram's resources, shared with every other dot.
        GradientDrawable ring = (GradientDrawable) background.mutate();
        if (cleared.add(ring)) {
            ring.setStroke(width, Color.TRANSPARENT);
            return true;
        }
        if (!badge.isDirty()) return false;
        Drawable.Callback callback = ring.getCallback();
        ring.setCallback(null);
        try {
            ring.setStroke(width, Color.TRANSPARENT);
        } finally {
            ring.setCallback(callback);
        }
        return false;
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
     * Sets [into] to map the strip of the bar's parent the glass shows onto the backdrop node, at [scale]
     * of its size, and returns the strip's top. The strip is [tall] high and starts at [x0]. A floating pill
     * has the screens running on behind it, so it shows what's right behind it, from [pillTop] less [pad]
     * (#262). A docked bar has only its own colour behind it, so it shows the strip just above [barTop],
     * upside down, as if the glass reflected it.
     */
    static float backdropSource(Matrix into, boolean floating, float scale, float x0, float barTop, float pillTop,
            float pad, float tall) {
        float top = floating ? pillTop - pad : barTop - tall;
        into.setTranslate(-x0, floating ? -top : -(top + tall));
        if (!floating) into.postScale(1f, -1f);
        into.postScale(scale, scale);
        return top;
    }

    /**
     * Puts [view] in [skipped], and it and every view up from it to [parent]'s child in [paths], when
     * [view] sits under [parent], and says whether it does. The glass then draws round [view] the way it
     * draws round a SurfaceView ({@link #drawLeavingOutSurfaces}) and never draws [view] itself. That keeps
     * a Reels viewer out of the blur, so the reel's video is drawn once, where it plays (#222).
     */
    static boolean leaveOut(View view, ViewGroup parent, java.util.Set<View> paths, java.util.Set<View> skipped) {
        if (!under(view, parent)) return false;
        for (View at = view; at != parent; at = (View) at.getParent()) paths.add(at);
        skipped.add(view);
        return true;
    }

    private static boolean under(View view, ViewGroup parent) {
        android.view.ViewParent up = view.getParent();
        for (int depth = 0; depth < 100 && up != null; depth++) {
            if (up == parent) return true;
            up = up.getParent();
        }
        return false;
    }

    /**
     * Draws [view] into [canvas] where its parent would, leaving out the SurfaceViews in [paths]. A view on
     * the way down to one is drawn piece by piece, its background and then each child, and any other view
     * draws itself whole. A video in a SurfaceView is a layer under the window, never in the views, so its
     * place is cleared, the way the SurfaceView clears its hole in the window, and the pill shows the video
     * through its tint. Before, the backgrounds round the video drew there, black on a feed post (#144).
     */
    static void drawLeavingOutSurfaces(Canvas canvas, View view, java.util.Set<View> paths, int depth) {
        drawLeavingOutSurfaces(canvas, view, paths, java.util.Collections.emptySet(), null, depth);
    }

    /** {@link #drawLeavingOutSurfaces}, leaving out the views in [skipped] too ({@link #leaveOut}). */
    static void drawLeavingOutSurfaces(Canvas canvas, View view, java.util.Set<View> paths, java.util.Set<View> skipped,
            int depth) {
        drawLeavingOutSurfaces(canvas, view, paths, skipped, null, depth);
    }

    /** {@link #drawLeavingOutSurfaces}, drawing the frame [frames] has for a SurfaceView in its place, when it has one ({@link SurfaceFrames}). */
    static void drawLeavingOutSurfaces(Canvas canvas, View view, java.util.Set<View> paths, java.util.Set<View> skipped,
            @Nullable java.util.function.Function<View, android.graphics.Bitmap> frames, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f || skipped.contains(view)) return;
        if (view instanceof android.view.SurfaceView) {
            drawSurfacePlace(canvas, view, frames == null ? null : frames.apply(view));
            return;
        }
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
                drawLeavingOutSurfaces(canvas, group.getChildAt(i), paths, skipped, frames, depth + 1);
            }
        }
        canvas.restoreToCount(saved);
    }

    private static final Paint SURFACE_CLEAR = new Paint();
    private static final Paint SURFACE_FRAME = new Paint(Paint.FILTER_BITMAP_FLAG);

    static {
        SURFACE_CLEAR.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
    }

    /** Draws [frame] over [surface]'s place, stretched to it, or clears the place when there's no frame. */
    private static void drawSurfacePlace(Canvas canvas, View surface, @Nullable android.graphics.Bitmap frame) {
        int saved = canvas.save();
        canvas.translate(surface.getLeft(), surface.getTop());
        if (!surface.getMatrix().isIdentity()) canvas.concat(surface.getMatrix());
        RectF place = new RectF(0, 0, surface.getWidth(), surface.getHeight());
        if (frame != null && !frame.isRecycled()) canvas.drawBitmap(frame, null, place, SURFACE_FRAME);
        else canvas.drawRect(place, SURFACE_CLEAR);
        canvas.restoreToCount(saved);
    }

    @Nullable
    static View find(View root, String name) {
        int id = id(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * Whether [story] is a story viewer that shows, laid out, with no screen showing after it in its
     * container. A screen opened from the story, such as a profile, goes after it there and has the bar.
     */
    static boolean storyOnTop(@Nullable View story) {
        if (story == null || !story.isShown() || story.getHeight() <= 0) return false;
        if (!(story.getParent() instanceof ViewGroup)) return true;
        ViewGroup container = (ViewGroup) story.getParent();
        for (int i = container.indexOfChild(story) + 1, n = container.getChildCount(); i < n; i++) {
            View later = container.getChildAt(i);
            if (later.getVisibility() == View.VISIBLE && later.getHeight() > 0) return false;
        }
        return true;
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
    /** The blur radius the glass has always drawn, and where its slider starts (#391). */
    public static final int BLUR_DP = 18;
    /** The selected tab's capsule sits 4dp in from the pill's top and bottom and stands 4dp past its slot. */
    static final int HIGHLIGHT_INSET_DP = 4;
    static final int HIGHLIGHT_GROW_DP = 4;
    /**
     * The ids of Instagram's count and dot badges on a tab, which it rings in the bar's own colour so a
     * badge stands off the icon. On the glass that ring shows as a solid outline (#315), so it goes clear.
     */
    static final String[] BADGES = {"notification", "led_badge"};
    /** The ring's width, as Instagram draws it for both badges. A clear stroke keeps it, so the badge keeps its size. */
    static final int BADGE_RING_DP = 2;

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
    /** Blur recordings that may fail in a row before the blur rests ({@link BlurTries}, #262). */
    static final int BLUR_TRIES = 3;
    /** How long the blur rests on the plain tint after {@link #BLUR_TRIES} failed recordings in a row. */
    static final long BLUR_REST_MS = 2000;
    /** How long the capsule takes to slide to a new tab. */
    static final long SLIDE_MS = 280;
    /** How long a missing shadow or content view stays unsearched before the parent is walked for it again. */
    static final long RESEARCH_MS = 500;
    /**
     * How many redraws a run of misses asks for, so a view that turns up after a screen switch is
     * found even when nothing else draws the bar, without a bar that never has one drawing forever.
     */
    static final int MISS_REDRAWS = 4;
    /**
     * How often the views are walked for a Reels viewer to keep above a floating pill's slot. A change of
     * the margin it wants is walked for at once.
     */
    static final long REELS_LOOK_MS = 100;
    /**
     * The least time between frames held back for a layout this asked for (a Reels viewer's or a sheet's
     * new margin), so views Instagram keeps putting back can never stop the window from drawing.
     */
    static final long HOLD_BACK_MS = 100;
    /** Counted in the glass family's Diagnostics line each time a Reels viewer's margin is set under the pill. */
    static final String COUNT_REELS_LIFTED = "Reels viewer kept above the pill's slot";
    /** Counted each time a reel's caption, buttons or seek bar is raised clear of a footer laid over the reel (#260). */
    static final String COUNT_REEL_RAISED = "Reel's caption or seek bar raised over its footer";
    /** Counted each time a footer under a reel is laid over the reel instead of shrinking it (#260). */
    static final String COUNT_FOOTER_OVERLAID = "Footer laid over the reel";
    /**
     * The least time between frames held back to lay out a footer laid over a reel again, so the
     * shrunk reel Instagram laid out never shows, without a footer that keeps changing holding every frame.
     */
    static final long FOOTER_REDRAW_MS = 500;
    /** Counted each time a video in a SurfaceView shows up under the bar, with none there before, and is kept out of the blur. */
    static final String COUNT_VIDEO_LEFT_OUT = "Video kept out of the blur";
    /** Counted the first time a frame of a video in a SurfaceView under the bar is copied into the blur, each time one comes under it (#144). */
    static final String COUNT_VIDEO_COPIED = "Video frame copied into the blur";
    /** Counted each time the blur fails {@link #BLUR_TRIES} recordings in a row and rests on the plain tint. */
    static final String COUNT_BLUR_RESTED = "Blur rested after failing";
    /** Counted each time a tab's own background, Instagram's selected tab capsule, is taken off under the pill. */
    static final String COUNT_TAB_BACKGROUND = "Tab's own capsule taken off";
    /** Counted each time Instagram's touch strip for a reel's seek bar is moved back onto the seek bar. */
    static final String COUNT_STRIP_KEPT = "Seek bar's touch strip kept on the reel";
    /** Counted each time the navigation bar's height is answered 0 so the screens run behind the gesture handle (#144). */
    static final String COUNT_NAV_BEHIND = "Screens run behind the gesture handle";
    /** Counted each time the pill shows or hides and the window's insets are asked for again. */
    static final String COUNT_INSETS_AGAIN = "Insets asked again for the pill";
    /** Counted each time Instagram's bottom sheets are moved to stop the navigation bar's height above the edge, or back (#209, #222). */
    static final String COUNT_SHEETS_LIFTED = "Sheets kept above the navigation bar";
    /** Counted each time the bar, left showing by Instagram over a story, is hidden for it (#224). */
    static final String COUNT_STORY_ASIDE = "Bar hidden over a story";
    /** Counted once for each bar whose Search tab the liquid look puts in a circle of its own (#65). */
    static final String COUNT_SEARCH_APART = "Search put in its own circle";
    /** Counted each time a tab badge's ring, new to the glass, is made clear (#315). */
    static final String COUNT_BADGE_RING = "Tab badge's ring made clear";

    /** Liquid look (#65): the clear gap between the pill and Search's circle beside it. */
    static final int SEARCH_GAP_DP = 8;
    /** How long the liquid bar takes to shrink to its compact row, and to grow back. */
    static final long COMPACT_MS = 260;

    /**
     * The activity in front, from its resume to its pause: the window whose screens Instagram pads by
     * the navigation bar's height when its insets listener runs. Main thread.
     */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /**
     * The navigation bar's height Instagram would have padded its screens by when it was last answered
     * 0 for a floating pill, or the height of the bar HushGram's full screen hides under one (#285), or 0
     * when it was given through. The pill makes this much room of its own
     * under itself, and keeps it while a bottom sheet over it has the height given through, so it stays
     * where it was as the sheet slides away. Main thread, as Instagram's insets listener is.
     */
    private static int navTaken;

    static final String TAG = "HushGlass";
    private static final boolean DIAGNOSE = false;

    private static volatile boolean registered;
    /**
     * Every restyled bar. The bar holds its Glass, as its background and its listeners, and the Glass
     * holds the bar, so a strong value here kept the bar's key from ever clearing: every bar, its
     * activity and its recorded backdrop stayed for good (audit 2026-10-12). Held weakly, a bar
     * Instagram drops leaves at the next collection, and one whose activity is destroyed leaves at
     * once ({@link #forget}). Main thread only.
     */
    private static final WeakHashMap<View, WeakReference<Glass>> applied = new WeakHashMap<>();

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
            if (!Utils.settingsReady()) return;
            if (!Settings.GLASS_TAB_BAR.get()) {
                // Instagram's own bar can still slide across its tabs (#201) and hide as you scroll (#343).
                PlainTabSlide.apply(activity);
                PlainScrollHide.apply(activity);
                return;
            }
            View found = find(activity.getWindow().getDecorView(), TAB_BAR);
            if (!(found instanceof ViewGroup) || !(found.getParent() instanceof FrameLayout)) return;
            if (applied.containsKey(found)) {
                WeakReference<Glass> held = applied.get(found);
                Glass known = held == null ? null : held.get();
                if (known != null) known.cameToTheFront();
                return;
            }
            Glass glass = new Glass((ViewGroup) found);
            applied.put(found, new WeakReference<>(glass));
            glass.watchScrolling(activity.getWindow());
            glass.watchSlides(activity.getWindow());
            HookStatus.invoked(FamilyNames.GLASS_TAB_BAR);
            Logger.printDebug(() -> "Glass tab bar: restyled the tab bar");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab bar", failure);
            diagnose("apply failed", failure);
        }
    }

    /**
     * Called through FullScreenBars with the navigation bar's height Instagram's insets listener is
     * about to keep for its screens (#144). Instagram pads its screens by it, which left a strip of its
     * own color under a floating pill around the gesture handle. While a floating glass bar shows in
     * the front window the answer is 0, so the screens run to the bottom edge, and the pill keeps itself
     * above the handle by [height] ({@link #navRoom}). Screens without the bar showing (a chat's
     * composer, settings, the camera), another activity in front, the float switch off, Pause or
     * settings not ready all get [height] as it came. A bottom sheet over the pill, such as a reel's
     * comments, keeps the 0 and stops above the navigation bar by its own room instead
     * ({@link Glass#liftSheets}, #209): Instagram moves its screens by the height it keeps, the Reels
     * viewer under the sheet among them, and a reel moved by every sheet that came and went stopped on
     * its cover (#222).
     */
    public static int navBarHeight(int height) {
        return navBarHeight(height, 0);
    }

    /**
     * {@link #navBarHeight(int)} with [hidden], the height of a navigation bar HushGram's full screen
     * hides, or 0. Instagram is answered 0 for a hidden bar and its screens already run to the edge, so a
     * floating pill gave up the bar's room there and dropped by the bar's height with every switch to
     * Reels with Full screen Reels on, then rose again on Home (#285). It keeps that room under itself
     * instead, so it sits at one height on every tab and stays clear of the bar when it's swiped in, and
     * the Reels viewer still stops at its top ({@link #reelsMargin}). A sheet over it keeps no room, as
     * there's no bar for it to stop above.
     */
    public static int navBarHeight(int height, int hidden) {
        Glass glass = height <= 0 && hidden <= 0 ? null : floatingGlassInFront();
        if (glass == null) {
            navTaken = 0;
            sheetRoom = 0;
            navGiven = Math.max(0, height);
            return height;
        }
        if (height <= 0) {
            navTaken = hidden;
            sheetRoom = 0;
            navGiven = 0;
            return height;
        }
        navTaken = height;
        sheetRoom = height;
        navGiven = 0;
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_NAV_BEHIND);
        return 0;
    }

    /**
     * Called through FullScreenBars in place of {@link #navBarHeight} while the keyboard shows. Instagram
     * lifts a text box above the keyboard by the keyboard's height less the navigation bar's, then pads
     * it by the height it keeps, so [height] goes through as it came (#209). A sheet over a floating pill
     * keeps the 0, since it already stops [height] above the edge ({@link Glass#liftSheets}) and its
     * comment box lands on the keys the same way, and nothing under it moves as the keyboard comes and
     * goes (#222).
     */
    public static int navBarHeightOverTheKeyboard(int height) {
        Glass glass = height <= 0 ? null : floatingGlassInFront();
        if (glass == null || !glass.underASheet()) {
            sheetRoom = 0;
            navGiven = Math.max(0, height);
            return height;
        }
        navTaken = height;
        sheetRoom = height;
        navGiven = 0;
        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_NAV_BEHIND);
        return 0;
    }

    /**
     * The room Instagram's bottom sheets keep under themselves for the navigation bar: the height
     * Instagram was last answered 0 for, or 0 when it was given the height and keeps its screens,
     * sheets included, above the bar itself. Main thread.
     */
    private static int sheetRoom;
    /**
     * The navigation bar's height Instagram was last given through and pads its screens by itself, or
     * 0 when it was answered 0. With the keyboard up both this and {@link #navTaken} hold the height.
     * Main thread.
     */
    private static int navGiven;

    /**
     * Called by FullScreenBars once Instagram's screens have been told new bar heights, which moves the
     * bottom of each of them, the Reels viewer's included, in the next layout. Each floating pill in the
     * front activity gives its Reels viewers their margin for the new heights now ({@link #reelsMargin}),
     * so both land in that one layout and the viewer keeps its size. Given its margin in the frame after,
     * a viewer was sized twice, and the reel in it could stop on its cover while its sound played on
     * (#222). Main thread.
     */
    public static void heightsTold() {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Activity activity = front.get();
            if (activity == null) return;
            for (Map.Entry<View, WeakReference<Glass>> entry : applied.entrySet()) {
                WeakReference<Glass> held = entry.getValue();
                Glass glass = held == null ? null : held.get();
                if (glass != null) glass.keepReelsForNewHeights(activity);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "heights told", failure);
        }
    }

    /** Whether the front activity shows a floating glass bar: restyled, floating, and shown. */
    static boolean floatingBarInFront() {
        return floatingGlassInFront() != null;
    }

    /** The floating glass bar the front activity shows, or null. */
    @Nullable
    private static Glass floatingGlassInFront() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return null;
        Activity activity = front.get();
        if (activity == null) return null;
        for (Map.Entry<View, WeakReference<Glass>> entry : applied.entrySet()) {
            WeakReference<Glass> held = entry.getValue();
            Glass glass = held == null ? null : held.get();
            if (glass != null && glass.floatsShownIn(activity)) return glass;
        }
        return null;
    }

    /** Whether [activity], resuming, has a floating glass pill set to show in it. */
    static boolean floatingBarResuming(Activity activity) {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
        for (Map.Entry<View, WeakReference<Glass>> entry : applied.entrySet()) {
            WeakReference<Glass> held = entry.getValue();
            Glass glass = held == null ? null : held.get();
            if (glass != null && glass.floatsIn(activity)) return true;
        }
        return false;
    }

    /**
     * Whether [bar] wears the glass, whose pill is its background. Blur the bars around a reel then
     * leaves the bar's background alone, and a docked pill paints the strip round itself ({@link #strip}).
     */
    public static boolean wearsGlass(@Nullable View bar) {
        WeakReference<Glass> held = bar == null ? null : applied.get(bar);
        return held != null && held.get() != null;
    }

    /**
     * Whether [bar] wears the glass and its pill floats, with no strip of its own round it, so Blur the
     * bars around a reel has none to fill.
     */
    public static boolean wearsFloatingGlass(@Nullable View bar) {
        WeakReference<Glass> held = bar == null ? null : applied.get(bar);
        Glass glass = held == null ? null : held.get();
        return glass != null && glass.floating;
    }

    /** Paints a strip of the bar, or says false so the bar's own colour goes there. */
    interface StripPainter {
        boolean paint(Canvas canvas, float left, float top, float right, float bottom);
    }

    /**
     * What a docked pill shows round itself on Reels: the reel running on, blurred, while Blur the bars
     * around a reel has one ({@link ReelBlurBars#drawStrip}, #258). Swapped in tests.
     */
    static volatile StripPainter strip = ReelBlurBars::drawStrip;

    /** The room a floating pill makes under itself for the navigation bar: the height Instagram was answered 0 for. */
    static int navRoom(boolean floating) {
        return floating ? navTaken : 0;
    }

    /**
     * The bottom margin a Reels viewer gets under a floating pill whose slot over the navigation bar,
     * its lift included, is [slot] tall: the viewer stops where the slot starts, so the reel sits where
     * it did before it ran on behind the pill, and its seek bar stays clear of the pill and the gesture
     * handle (#221, #280). Past the slot it adds the navigation bar's height Instagram was answered 0
     * for, less any it was given through since and pads its screens by itself ({@link #navGiven}), so the
     * viewer's bottom edge, and its size, stay put whichever answer Instagram last had: with the keyboard
     * up, under a sheet, or with its bar hidden for a paused reel (#222).
     */
    static int reelsMargin(int slot) {
        return Math.max(0, slot) + Math.max(0, navTaken - navGiven);
    }

    /** {@link #sheetRoom} for a pill that floats, 0 for one that doesn't. */
    static int sheetRoom(boolean floating) {
        return floating ? sheetRoom : 0;
    }

    /** The activity in front, or null: where FullScreenBars looks for the keyboard (#209). */
    @Nullable
    public static Activity activityInFront() {
        return front.get();
    }

    static void nowInFront(Activity activity) {
        front = new WeakReference<>(activity);
    }

    static void nowBehind(Activity activity) {
        if (front.get() == activity) front = new WeakReference<>(null);
    }

    /** Tests start with no activity in front and nothing taken from the navigation bar. */
    static void resetForTests() {
        front = new WeakReference<>(null);
        navTaken = 0;
        sheetRoom = 0;
        navGiven = 0;
        for (WeakReference<Glass> held : new java.util.ArrayList<>(applied.values())) {
            Glass glass = held.get();
            if (glass != null) glass.pill.cancelCatchUp();
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
        return tint(base, blurred, 1f);
    }

    /** {@link #tint(int, boolean)} with the strength of the fill scaled by [scale], the opacity slider's share. */
    static int tint(int base, boolean blurred, float scale) {
        boolean dark = isDark(base);
        if (blurred) return (base & 0x00ffffff) | (scaledAlpha(dark ? 0x70 : 0xa6, scale) << 24);
        int solid = dark ? blend(base, Color.WHITE, 0.10f) : base;
        return (solid & 0x00ffffff) | (scaledAlpha(0xe6, scale) << 24);
    }

    static int scaledAlpha(int alpha, float scale) {
        return Math.max(0, Math.min(255, Math.round(alpha * scale)));
    }

    /** The opacity slider as a share of the standard tint, read as the pill draws so a move shows at once (#391). */
    static float opacityScale() {
        return Settings.GLASS_TAB_BAR_OPACITY.get() / 100f;
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

    /**
     * The corner radius of a shape [heightPx] tall with [corners] picked (#144): its dp, the highlight's when
     * [highlight], never past half the height, so a fully round pill has half circle ends. Standard with no
     * choice read.
     */
    static float cornerPx(GlassCorners corners, boolean highlight, float heightPx, float density) {
        GlassCorners pick = corners == null ? GlassCorners.STANDARD : corners;
        int dp = highlight ? pick.highlightDp : pick.dp;
        return Math.min(Math.round(dp * density), Math.max(0f, heightPx / 2f));
    }

    /** The selected tab's capsule: a quarter of white on dark glass, a sixth of grey on light. */
    static int highlight(int base) {
        return isDark(base) ? 0x40ffffff : 0x2a787880;
    }

    static int edge(int base) {
        return isDark(base) ? 0x33ffffff : 0x1f000000;
    }

    /**
     * Splits [pill] for the liquid look (#65): Search's circle, as wide as the pill is tall, at the pill's left
     * end when [onLeft] (a right-to-left bar) or at its right end, and the rest, less [gap], for the other tabs.
     * Fills [holder] and [circle] and returns true. With no room beside the circle for two circles' width,
     * [holder] is the whole pill, [circle] is empty and it returns false.
     */
    static boolean splitOffSearch(RectF pill, float gap, boolean onLeft, RectF holder, RectF circle) {
        float size = pill.height();
        if (size <= 0f || pill.width() - size - gap < 2f * size) {
            holder.set(pill);
            circle.setEmpty();
            return false;
        }
        if (onLeft) {
            circle.set(pill.left, pill.top, pill.left + size, pill.bottom);
            holder.set(circle.right + gap, pill.top, pill.right, pill.bottom);
        } else {
            circle.set(pill.right - size, pill.top, pill.right, pill.bottom);
            holder.set(pill.left, pill.top, circle.left - gap, pill.bottom);
        }
        return true;
    }

    /**
     * The pill holding the tabs on its way to the compact row (#65), put in [into]: [holder] itself at [share]
     * 0, and at 1 a circle as wide as it is tall at its right end when [fromRight] (a right-to-left bar) or at
     * its left end.
     */
    static void shrunkHolder(RectF holder, boolean fromRight, float share, RectF into) {
        float size = Math.min(holder.height(), holder.width());
        float left = fromRight ? holder.right - size : holder.left;
        into.set(holder.left + (left - holder.left) * share, holder.top,
                holder.right + (left + size - holder.right) * share, holder.bottom);
    }

    /** The middle of the [index]th of [count] tabs spread evenly from [from] to [to]. */
    static float slotMiddle(float from, float to, int count, int index) {
        return from + (to - from) * (index + 0.5f) / count;
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
            for (WeakReference<Glass> held : new java.util.ArrayList<>(applied.values())) {
                Glass glass = held.get();
                if (glass != null) glass.restoreScrolling();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "restore", failure);
        }
        PlainScrollHide.restoreAll();
    }

    /**
     * Lets go of every bar [activity] restyled, as it's destroyed: its listeners come off, a backdrop
     * catch-up still waiting is dropped and the recorded backdrop is discarded. A bar that only came
     * off its window for a while isn't touched here, so it keeps its glass when it comes back.
     */
    static void forget(Activity activity) {
        try {
            for (Map.Entry<View, WeakReference<Glass>> entry : new java.util.ArrayList<>(applied.entrySet())) {
                View bar = entry.getKey();
                WeakReference<Glass> held = entry.getValue();
                if (bar == null || !ownedBy(bar, activity)) continue;
                applied.remove(bar);
                Glass glass = held == null ? null : held.get();
                if (glass != null) glass.dispose();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "forget", failure);
        }
    }

    /** Whether [view] belongs to [activity]: made with its context, or in its window. */
    static boolean ownedBy(View view, Activity activity) {
        Context context = view.getContext();
        while (context != null) {
            if (context == activity) return true;
            if (!(context instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        return decor != null && view.getRootView() == decor;
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            nowInFront(activity);
            apply(activity);
            // Ahead of the activity's screens, which read the navigation bar's height as they resume (#196).
            if (floatingBarResuming(activity)) FullScreenBars.takeNavBarHeight();
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) {
            nowBehind(activity);
            restoreAll();
            // A chat opening in its own activity reads the navigation bar's height before its window asks (#196).
            FullScreenBars.giveBackBarHeights();
        }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            forget(activity);
        }
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
            View.OnAttachStateChangeListener, GlassTabSlide.Host, GlassScrollHide.Shrinker {
        private final ViewGroup bar;
        private final float density;
        private final boolean blurWanted;
        private final boolean haptics;
        private final HapticStyle hapticStyle;
        private final GlassHeight height;
        /** How round the pill and its highlight are, outside the liquid look (#144). */
        private final GlassCorners corners;
        /** How much higher the pill sits, in pixels, by the lift chosen. */
        private final int lift;
        /**
         * The room under the pill, as of the last frame: the lift, plus the navigation bar's height while
         * Instagram is answered 0 for it, so the pill stays above the gesture handle (#144).
         */
        private int room;
        /**
         * Whether Instagram was last asked for its insets with this pill floating and shown in front, or
         * null when it's to be asked again. Instagram only reads the navigation bar's height when the
         * window's insets go round, so a pill that shows or hides has them sent round again.
         */
        @Nullable private Boolean insetsShown;
        /** Instagram's bottom sheet holder in the bar's window, as last found, and when it was last looked for. */
        private WeakReference<View> sheetHolder = new WeakReference<>(null);
        private long sheetLookedAt = -SHEET_LOOK_MS;
        /** The bottom margin {@link #liftSheets} gave the sheet holder and each of its neighbours. */
        private final WeakHashMap<View, Integer> sheetLifts = new WeakHashMap<>();
        /** Instagram's story viewer in the bar's window, as last found, and when it was last looked for (#224). */
        private WeakReference<View> storyRoot = new WeakReference<>(null);
        private long storyLookedAt = -SHEET_LOOK_MS;
        /** Whether this hid the bar over a story, so this shows it again, and only then (#224). */
        private boolean hidOverAStory;
        /**
         * Hides the bar while a list scrolls down, with that switch on, and on Reels after the wait picked
         * for it (#170). Null with the switch off and the wait on Never.
         */
        @Nullable private final GlassScrollHide scrollHide;
        /** Carries the capsule along with a finger slid across the pill, with that switch on (#136). Null with it off. */
        @Nullable private final GlassTabSlide slide;
        /** Where a sliding finger holds the capsule's middle, in the bar's pixels, or NaN while none does. */
        private float dragX = Float.NaN;
        private final RectF touchArea = new RectF();
        /** Whether this slide has had its tick: one tick for a change of tab, however many tabs the capsule crosses. */
        private boolean tickedThisSlide;
        private final boolean floating;
        private final PillDrawable pill = new PillDrawable();
        private int base;
        /** How the blur's recordings have gone lately, so one that fails is tried again ({@link BlurTries}, #262). */
        private final BlurTries tries = new BlurTries();
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
        /** The Reels viewers given a margin under a floating pill, with the margin each was given. */
        private final WeakHashMap<View, Integer> lifted = new WeakHashMap<>();
        /** Instagram's touch strip for the seek bar, found once: Instagram keeps one and moves it between reels. */
        @Nullable private WeakReference<View> stripRef;
        private long reelsLookedAt = Long.MIN_VALUE / 2;
        /** The margin the last look gave the Reels viewers, or -1 before the first. */
        private int reelsWanted = -1;
        /** Every Reels viewer the last look met. */
        private final java.util.Set<View> viewers = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        /** The views raised over a reel to clear the pill (#221), each with the translation it was given. */
        private final WeakHashMap<View, Float> raised = new WeakHashMap<>();
        /** The bands whose content is scrolled up over a reel to clear the pill, each with the scroll it was given. */
        private final WeakHashMap<View, Integer> scrolled = new WeakHashMap<>();
        /** The view to raise for each caption or seek bar, found once per view Instagram mounts. */
        private final WeakHashMap<View, View> bands = new WeakHashMap<>();
        /** The footers laid over a reel's bottom (#260), each with the top margin it was given. */
        private final WeakHashMap<View, Integer> overlapped = new WeakHashMap<>();
        private final java.util.ArrayList<View> anchors = new java.util.ArrayList<>();
        private final java.util.ArrayList<View> footerViews = new java.util.ArrayList<>();
        private final java.util.Set<View> raisedNow = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** Whether a footer's margin changed in this frame's look, so the frame is laid out again before it's drawn. */
        private boolean footerMoved;
        private long footerRedrawAt = Long.MIN_VALUE / 2;
        /** Whether a Reels viewer's margin changed in this frame's look, so the frame is laid out again before it's drawn. */
        private boolean reelsMoved;
        /** When a frame was last held back for a layout ({@link #HOLD_BACK_MS}). */
        private long heldBackAt = Long.MIN_VALUE / 2;
        /**
         * Puts back a raised view's translation and scroll as it comes off the window: Instagram mounts
         * the same views again elsewhere, and one would carry the lift there.
         */
        private final View.OnAttachStateChangeListener putBack = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(@NonNull View view) {
            }

            @Override public void onViewDetachedFromWindow(@NonNull View view) {
                lower(view);
            }
        };
        private final Rect reelsRect = new Rect();
        /** The liquid look (#65): a round pill and highlight, Search in a circle of its own, and the compact row on a scroll. */
        private final boolean liquid;
        /** How far the liquid bar has shrunk to its compact row: 0 is the whole bar, 1 the tab you're on and Search. */
        private float compact;
        @Nullable private ValueAnimator compacting;
        /** The translation and alpha the liquid look gave each tab, and the tabs it put out of a touch's reach, so only what it did is undone. */
        /** The badge backgrounds whose ring this bar has made clear. */
        private final java.util.Set<Drawable> rings = java.util.Collections.newSetFromMap(new WeakHashMap<>());
        private final WeakHashMap<View, Float> shifted = new WeakHashMap<>();
        private final WeakHashMap<View, Float> faded = new WeakHashMap<>();
        private final WeakHashMap<View, Boolean> tucked = new WeakHashMap<>();
        /** Where the liquid look puts the tabs, in the bar's pixels, as of the last frame: the pill holding them and Search's circle, empty without one. */
        private final RectF holder = new RectF();
        private final RectF circle = new RectF();
        private final RectF whole = new RectF();
        private final java.util.ArrayList<View> order = new java.util.ArrayList<>();
        private boolean countedApart;

        Glass(ViewGroup bar) {
            this.bar = bar;
            this.density = bar.getResources().getDisplayMetrics().density;
            this.blurWanted = Settings.GLASS_TAB_BAR_BLUR.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            this.floating = Settings.GLASS_TAB_BAR_FLOAT.get();
            this.haptics = Settings.GLASS_TAB_BAR_HAPTICS.get();
            this.hapticStyle = Settings.GLASS_TAB_BAR_HAPTIC_STYLE.get();
            this.height = Settings.GLASS_TAB_BAR_HEIGHT.get();
            this.corners = Settings.GLASS_TAB_BAR_CORNERS.get();
            this.lift = liftPx(Settings.GLASS_TAB_BAR_LIFT.get(), floating, density);
            this.room = lift;
            this.liquid = Settings.GLASS_TAB_BAR_LIQUID.get();
            boolean hideOnScroll = Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get();
            // The liquid look shrinks the bar as a list scrolls down, unless a scroll is to take it away altogether
            // or the pill is to keep its full size (#256).
            boolean shrinkOnScroll = liquid && !hideOnScroll && !Settings.GLASS_TAB_BAR_KEEP_SIZE.get();
            GlassReelsHide hideOnReels = Settings.GLASS_TAB_BAR_REELS_HIDE.get();
            long reelsMillis = hideOnReels == null ? 0L : hideOnReels.millis;
            this.scrollHide = hideOnScroll || shrinkOnScroll || reelsMillis > 0
                    ? new GlassScrollHide(bar, () -> !HushgramPause.isPaused(), hideOnScroll || shrinkOnScroll, reelsMillis,
                            shrinkOnScroll ? this : null) : null;
            this.slide = Settings.GLASS_TAB_BAR_SLIDE.get()
                    ? new GlassTabSlide(bar, this, () -> !HushgramPause.isPaused()) : null;
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

        void watchSlides(Window window) {
            if (slide == null) return;
            try {
                GlassTabSlide.install(window, slide);
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, GlassTabSlide.COUNT_WATCHING);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab slide", failure);
            }
        }

        @Override public boolean onPill(float x, float y) {
            // The compact row has no tabs to slide across.
            return compact <= 0f && pill.pillBounds(touchArea) && touchArea.contains(x, y);
        }

        /**
         * Shrinks the liquid bar to its compact row as a list scrolls down, or grows it back (#65), sliding over
         * {@link #COMPACT_MS} unless [now] or the phone's animations are off. It won't shrink without the look,
         * while paused, with no tab marked selected or with Search selected, which has no tab to shrink to.
         */
        @Override public boolean shrink(boolean on, boolean now) {
            if (on) {
                if (!liquidNow()) return false;
                View selected = selectedTab();
                if (selected == null || selected == searchTab()) return false;
            }
            float target = on ? 1f : 0f;
            ValueAnimator running = compacting;
            compacting = null;
            if (running != null) running.cancel();
            if (now || !animationsOn() || compact == target) {
                compact = target;
                bar.invalidate();
                return true;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(compact, target);
            animator.setDuration(COMPACT_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(step -> {
                compact = (float) step.getAnimatedValue();
                bar.invalidate();
            });
            compacting = animator;
            animator.start();
            return true;
        }

        /** Whether the liquid look shows right now: switched on, and HushGram not paused. */
        private boolean liquidNow() {
            return liquid && !HushgramPause.isPaused();
        }

        /** Instagram's Search tab among the bar's children while it shows, or null. */
        @Nullable
        private View searchTab() {
            int search = id(bar.getContext(), SEARCH_TAB);
            if (search == 0) return null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getId() == search) return child.getVisibility() == View.VISIBLE ? child : null;
            }
            return null;
        }

        /**
         * Works out the liquid look's shapes for the pill [pillArea] into {@link #holder} and {@link #circle}:
         * Search's circle beside the pill holding the other tabs when [search] shows and there's room, or the
         * whole pill holding every tab. Says whether Search got its circle.
         */
        private boolean liquidShapes(RectF pillArea, @Nullable View search) {
            boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (search != null && splitOffSearch(pillArea, dp(SEARCH_GAP_DP), rtl, holder, circle)) return true;
            holder.set(pillArea);
            circle.setEmpty();
            return false;
        }

        /**
         * Puts the tabs where the liquid look draws them (#65), by their translation only, so nothing is laid
         * out again: Search in its circle and the other tabs spread across the pill beside it. On the way to
         * the compact row the tab you're on slides into a circle at the pill's start while the others fade, and
         * once there they're put out of a touch's reach. With the look off or HushGram paused, whatever this
         * moved goes back.
         */
        private void arrangeTabs() {
            if (!liquidNow() || !pill.pillBounds(whole)) {
                if (!shifted.isEmpty() || !faded.isEmpty() || !tucked.isEmpty()) putTabsBack();
                return;
            }
            View search = searchTab();
            boolean apart = liquidShapes(whole, search);
            if (apart && !countedApart) {
                countedApart = true;
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_SEARCH_APART);
            }
            boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            View selected = selectedTab();
            order.clear();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View tab = bar.getChildAt(i);
                if (apart && tab == search) continue;
                if (tab.getVisibility() == View.VISIBLE || tucked.containsKey(tab)) order.add(tab);
            }
            java.util.Collections.sort(order, (one, other) -> Integer.compare(one.getLeft(), other.getLeft()));
            float inner = dp(INNER_DP);
            float from = apart && rtl ? holder.left + inner : bar.getPaddingLeft();
            float to = apart && !rtl ? holder.right - inner : bar.getWidth() - bar.getPaddingRight();
            float size = Math.min(holder.height(), holder.width());
            float start = rtl ? holder.right - size / 2f : holder.left + size / 2f;
            for (int k = 0; k < order.size(); k++) {
                View tab = order.get(k);
                float middle = (tab.getLeft() + tab.getRight()) / 2f;
                float wanted = apart ? slotMiddle(from, to, order.size(), k) : middle;
                float alpha = 1f;
                boolean tuck = false;
                if (compact > 0f) {
                    if (tab == selected) {
                        wanted += (start - wanted) * compact;
                    } else {
                        alpha = 1f - compact;
                        tuck = compact >= 1f;
                    }
                }
                place(tab, wanted - middle, alpha, tuck);
            }
            if (apart) place(search, circle.centerX() - (search.getLeft() + search.getRight()) / 2f, 1f, false);
            order.clear();
        }

        /** Gives [tab] the liquid look's [shift] and [alpha], and takes it out of a touch's reach when [tuck]. */
        private void place(View tab, float shift, float alpha, boolean tuck) {
            float x = Math.round(shift);
            if (tab.getTranslationX() != x) tab.setTranslationX(x);
            if (x == 0f) shifted.remove(tab);
            else shifted.put(tab, x);
            if (tab.getAlpha() != alpha) tab.setAlpha(alpha);
            if (alpha >= 1f) faded.remove(tab);
            else faded.put(tab, alpha);
            if (tuck) {
                if (tab.getVisibility() == View.VISIBLE) {
                    tab.setVisibility(View.INVISIBLE);
                    tucked.put(tab, Boolean.TRUE);
                }
            } else if (tucked.remove(tab) != null && tab.getVisibility() == View.INVISIBLE) {
                tab.setVisibility(View.VISIBLE);
            }
        }

        /** Undoes what the liquid look did to the tabs, wherever nothing else changed it since. */
        private void putTabsBack() {
            for (Map.Entry<View, Float> entry : new java.util.ArrayList<>(shifted.entrySet())) {
                View tab = entry.getKey();
                if (tab != null && tab.getTranslationX() == entry.getValue()) tab.setTranslationX(0f);
            }
            shifted.clear();
            for (Map.Entry<View, Float> entry : new java.util.ArrayList<>(faded.entrySet())) {
                View tab = entry.getKey();
                if (tab != null && tab.getAlpha() == entry.getValue()) tab.setAlpha(1f);
            }
            faded.clear();
            for (View tab : new java.util.ArrayList<>(tucked.keySet())) {
                if (tab != null && tab.getVisibility() == View.INVISIBLE) tab.setVisibility(View.VISIBLE);
            }
            tucked.clear();
        }

        @Override public void follow(float x) {
            dragX = x;
            // A slide is a touch like any other, so the wait to hide the bar on Reels starts over.
            if (scrollHide != null) scrollHide.arm();
            bar.invalidate();
        }

        /**
         * The bar's activity is in front again. Going to the background dropped the wait on Reels, and
         * a reel that plays on untouched would never start another.
         */
        void cameToTheFront() {
            if (scrollHide != null) scrollHide.arm();
            // Another window may have had the navigation bar's real height since, so Instagram is asked again.
            insetsShown = null;
        }

        /** Whether this is a floating pill that shows in [activity]'s window. */
        boolean floatsShownIn(Activity activity) {
            return floating && bar.isShown() && ownedBy(bar, activity);
        }

        /** Whether Instagram's bottom sheet holder in the bar's window shows a sheet. */
        boolean underASheet() {
            View holder = sheetHolder();
            if (!(holder instanceof ViewGroup) || !holder.isShown()) return false;
            ViewGroup sheets = (ViewGroup) holder;
            for (int i = 0; i < sheets.getChildCount(); i++) {
                View sheet = sheets.getChildAt(i);
                if (sheet.getVisibility() == View.VISIBLE && sheet.getHeight() > 0) return true;
            }
            return false;
        }

        /**
         * Instagram's bottom sheet holder in the bar's window, or null. Instagram adds it with the first
         * sheet and keeps it, hidden, from then on, so it's looked for at most every {@link #SHEET_LOOK_MS}
         * while there isn't one.
         */
        @Nullable
        private View sheetHolder() {
            View root = bar.getRootView();
            View holder = sheetHolder.get();
            if (holder == null || holder.getRootView() != root) {
                long now = SystemClock.uptimeMillis();
                if (holder == null && now - sheetLookedAt < SHEET_LOOK_MS) return null;
                sheetLookedAt = now;
                holder = find(root, SHEET_HOLDER);
                sheetHolder = new WeakReference<>(holder);
            }
            return holder;
        }

        /**
         * Keeps Instagram's bottom sheets (comments, share, a post's menu) the navigation bar's height
         * above the edge while Instagram is answered 0 for it ({@link #sheetRoom}). A sheet's own box,
         * like a reel's comment field, sat by the height Instagram kept, so it went under the navigation
         * bar (#209). Giving Instagram the real height while a sheet showed moved every screen under it,
         * and the Reels viewer's video stopped on its cover (#222), so the room goes on the sheet holder
         * and its neighbours, such as a sheet's footer, as a bottom margin. The dimmed backdrop still
         * covers the window. Set ahead of the next sheet, since the holder stays once Instagram adds it.
         * Returns whether anything moved.
         */
        private boolean liftSheets() {
            if (!floating && sheetLifts.isEmpty()) return false;
            View holder = sheetHolder();
            if (holder == null || !(holder.getParent() instanceof ViewGroup)) return false;
            int wanted = HushgramPause.isPaused() ? 0 : sheetRoom(floating);
            int dimmer = id(holder.getContext(), SHEET_DIMMER);
            if (sheetsAboveTheNavigationBar((ViewGroup) holder.getParent(), dimmer, wanted, sheetLifts) == 0) return false;
            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_SHEETS_LIFTED);
            return true;
        }

        /** Whether Instagram's story viewer shows on top in the bar's window ({@link #storyOnTop}, #224). */
        boolean overAStory() {
            View root = bar.getRootView();
            View story = storyRoot.get();
            if (story == null || story.getRootView() != root) {
                long now = SystemClock.uptimeMillis();
                if (story == null && now - storyLookedAt < SHEET_LOOK_MS) return false;
                storyLookedAt = now;
                story = find(root, STORY_ROOT);
                storyRoot = new WeakReference<>(story);
            }
            return storyOnTop(story);
        }

        /**
         * Hides the bar while a story shows on top in its window, when Instagram left the bar showing, so
         * the pill is off the story's reply box and Instagram is told the navigation bar's real height
         * again (#224). The bar shows again once the story goes, if it's still as this left it.
         */
        private void stepAsideForStories() {
            if (!HushgramPause.isPaused() && overAStory()) {
                if (bar.getVisibility() == View.VISIBLE) {
                    bar.setVisibility(View.INVISIBLE);
                    hidOverAStory = true;
                    HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_STORY_ASIDE);
                }
            } else {
                showAfterAStory();
            }
        }

        /** Shows the bar again if this hid it over a story and nothing changed it since. */
        private void showAfterAStory() {
            if (!hidOverAStory) return;
            hidOverAStory = false;
            if (bar.getVisibility() == View.INVISIBLE) bar.setVisibility(View.VISIBLE);
        }

        /**
         * Whether this is a floating pill set to show in [activity], whether or not its window shows yet:
         * a stopped activity's window is still hidden while it resumes.
         */
        boolean floatsIn(Activity activity) {
            return floating && bar.getVisibility() == View.VISIBLE && ownedBy(bar, activity);
        }

        /**
         * Sends the window's insets round again when this floating pill shows or hides in front, or its
         * activity came back, so Instagram reads the navigation bar's height again ({@link #navBarHeight}).
         * A bottom sheet coming up over it or going changes nothing Instagram reads (#222).
         */
        private void askForInsetsWhenShownChanges() {
            if (!floating) return;
            Activity now = front.get();
            boolean shown = now != null && !HushgramPause.isPaused() && floatsShownIn(now);
            if (insetsShown != null && insetsShown == shown) return;
            insetsShown = shown;
            View root = bar.getRootView();
            root.post(root::requestApplyInsets);
            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_INSETS_AGAIN);
        }

        /** The clear space above and under the pill. */
        private int gap() {
            return dp(FIT_GAP_DP);
        }

        /** How far a compact pill is drawn in from the top and bottom, beyond the gap. */
        private int pillInset() {
            return pillInsetPx(height, bar.getHeight() - room, gap(), density);
        }

        /** Makes the ring of each badge on [tab] clear, as {@link #clearRing} does. */
        private void clearBadgeRings(View tab) {
            for (String name : BADGES) {
                int badge = id(tab.getContext(), name);
                View found = badge == 0 ? null : tab.findViewById(badge);
                if (found != null && clearRing(found, dp(BADGE_RING_DP), rings)) {
                    HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_BADGE_RING);
                }
            }
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
                clearBadgeRings(tab);
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
            room = lift + navRoom(floating);

            // Height: the bar's own, which is what Instagram keeps every screen's own controls above, and
            // the room asked for under a floating pill.
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params != null && params.height > 0) {
                if (originalBarHeight < 0) originalBarHeight = params.height;
                int wanted = barHeightPx(height, originalBarHeight, density) + room;
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
            int under = originalBottom + gap() + room;
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
                if (floating) {
                    keepReelsAboveTheSlot(parent, now);
                    raiseReels(parent.getContext());
                    keepTheStripOnTheReel(parent);
                }
            }
            arrangeTabs();
        }

        /**
         * Under a floating pill each Reels viewer stops at the top of the pill's slot ({@link #reelsMargin})
         * and keeps that one size whether the pill shows, slides off (Hide the tab bar on Reels, #170) or
         * Instagram hides the bar for a paused reel. A viewer that grew and shrank with the pill rescaled
         * the video with every slide (#221). One run on behind the pill, down to the navigation bar, had
         * the reel centred that much lower, with a wide black band over it (#280), and had the glass
         * drawing the reel's video into its blur (#222). The views are walked for viewers at most every
         * {@link #REELS_LOOK_MS}, and at once when the margin they want changes, so a viewer is never left
         * a frame at a size Instagram's own layout just gave it. Where Instagram hides the bar outside the
         * Reels tab, as over a reel opened from a feed post, the viewers get Instagram's 0 back
         * ({@link #reelsKeepTheSlot}, #347).
         */
        private void keepReelsAboveTheSlot(View holder, long now) {
            if (originalBarHeight <= 0) return;
            int wanted = reelsKeepTheSlot(bar, id(holder.getContext(), REELS_TAB))
                    ? reelsMargin(barHeightPx(height, originalBarHeight, density) + lift) : 0;
            if (wanted == reelsWanted && now - reelsLookedAt < REELS_LOOK_MS) return;
            reelsLookedAt = now;
            reelsWanted = wanted;
            viewers.clear();
            if (reelsAboveTheBar(holder, bar, id(holder.getContext(), REELS_ROOT), wanted, lifted, viewers) > 0) {
                reelsMoved = true;
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_REELS_LIFTED);
            }
        }

        /**
         * Gives the Reels viewers their margin for the bar heights Instagram's screens were just told
         * ({@link GlassTabBar#heightsTold}), when this is a floating pill in [activity].
         */
        void keepReelsForNewHeights(Activity activity) {
            if (!floating || !bar.isAttachedToWindow() || !ownedBy(bar, activity) || !(bar.getParent() instanceof View)) return;
            keepReelsAboveTheSlot((View) bar.getParent(), SystemClock.uptimeMillis());
        }

        /**
         * Raises the caption, buttons and seek bar of each reel in the viewers ({@link #reelBand}) by the
         * height of any footer Instagram shows under that viewer, such as a paused reel's controls (#260).
         * The footer is laid over the reel's bottom ({@link #overlapFooter}) rather than shrinking it. A
         * view raised before and no longer met is put back. A band is raised by scrolling its content
         * ({@link #lift}).
         */
        private void raiseReels(Context context) {
            int info = id(context, REEL_INFO), scrubber = id(context, SCRUBBER);
            int footers = id(context, REEL_FOOTERS), media = id(context, REEL_MEDIA);
            raisedNow.clear();
            for (View viewer : viewers) {
                if (viewer == null || !viewer.isAttachedToWindow()) continue;
                anchors.clear();
                footerViews.clear();
                reelAnchors(viewer, info, scrubber, footers, anchors, footerViews, 0);
                int footer = 0;
                for (View view : footerViews) footer += overlapFooter(view);
                for (View anchor : anchors) {
                    View band = bands.get(anchor);
                    if (band == null || !holds(band, anchor)) {
                        band = reelBand(anchor, media, viewer);
                        if (band == null) continue;
                        bands.put(anchor, band);
                    }
                    if (raisedNow.add(band) && lift(band, -footer)) {
                        HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_REEL_RAISED);
                    }
                }
            }
            anchors.clear();
            footerViews.clear();
            if (!raisedNow.containsAll(raised.keySet()) || !raisedNow.containsAll(scrolled.keySet())) {
                java.util.ArrayList<View> gone = new java.util.ArrayList<>(raised.keySet());
                gone.addAll(scrolled.keySet());
                for (View view : gone) {
                    if (view != null && !raisedNow.contains(view)) lower(view);
                }
            }
            raisedNow.clear();
        }

        /**
         * Raises [band], a reel's caption, buttons or seek bar, by [by] up the screen (negative): its content
         * scrolls up as far as the room over its highest child allows ({@link #roomAbove}), and only the rest
         * is a translation. Says whether that changed it.
         *
         * <p>The bands are Litho's views, inside the reel's LithoView. As Litho animates one of its views, after
         * a sheet over the reel closes, say, it ends by putting it where its layout says inside the LithoView,
         * adding up the translations of the views on the way and not their scrolls. A band moved by translation
         * had each animated child pushed back down by the band's lift, so the caption and buttons dropped by the
         * pill's height and stayed there (#221).
         */
        private boolean lift(View band, int by) {
            int scroll = band instanceof ViewGroup ? Math.max(0, Math.min(-by, roomAbove((ViewGroup) band))) : 0;
            boolean changed = scrollUp(band, scroll);
            // A band Instagram scrolls itself is moved by translation alone.
            if (!scrolled.containsKey(band)) scroll = 0;
            return raise(band, by + scroll) | changed;
        }

        /**
         * Scrolls [view]'s content [by] up and says whether that changed it. A scroll that is neither 0 nor the
         * last one this gave is Instagram's: the view is left to it and taken up again once it's back at 0.
         */
        private boolean scrollUp(View view, int by) {
            Integer ours = scrolled.get(view);
            int now = view.getScrollY();
            if (ours == null ? now != 0 : now != ours) {
                if (ours != null) {
                    scrolled.remove(view);
                    unwatch(view);
                }
                return false;
            }
            if (by == 0) {
                if (ours != null) unscroll(view);
                return false;
            }
            watch(view);
            scrolled.put(view, by);
            if (now == by) return false;
            view.scrollTo(view.getScrollX(), by);
            return true;
        }

        /** Whether [view] is [anchor] or holds it, a few levels up at most. */
        private static boolean holds(View view, View anchor) {
            View at = anchor;
            for (int depth = 0; depth < 16 && at != null; depth++) {
                if (at == view) return true;
                at = at.getParent() instanceof View ? (View) at.getParent() : null;
            }
            return false;
        }

        /**
         * Lays [footer], Instagram's container for what shows under a reel (a paused reel's controls, a
         * comment box), over the reel's bottom by a top margin of minus its height, so the reel keeps its
         * size. A top margin that is neither 0 nor one this gave is Instagram's, and that footer is left
         * alone. Returns the footer's height when it's laid over the reel, or 0.
         */
        private int overlapFooter(View footer) {
            if (!(footer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return 0;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) footer.getLayoutParams();
            Integer ours = overlapped.get(footer);
            if (margins.topMargin != 0 && (ours == null || ours != margins.topMargin)) return 0;
            int height = footer.getHeight();
            if (margins.topMargin != -height) {
                margins.topMargin = -height;
                footer.setLayoutParams(margins);
                footerMoved = true;
                if (height > 0) HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_FOOTER_OVERLAID);
            }
            if (height == 0) overlapped.remove(footer);
            else overlapped.put(footer, -height);
            return height;
        }

        /**
         * Moves [view] by [lift] up the screen (a negative translation) and says whether that changed it.
         * A translation that is neither 0 nor the last one this gave is Instagram's: the view is left to it
         * and taken up again once Instagram puts it back to 0.
         */
        private boolean raise(View view, int lift) {
            Float ours = raised.get(view);
            float now = view.getTranslationY();
            if (ours == null ? now != 0f : now != ours) {
                if (ours != null) forget(view);
                return false;
            }
            if (lift == 0) {
                if (ours != null) unraise(view);
                return false;
            }
            watch(view);
            raised.put(view, (float) lift);
            if (now == lift) return false;
            view.setTranslationY(lift);
            return true;
        }

        /** Puts back the translation and the scroll [view] was given, where it still has them, and forgets it. */
        private void lower(View view) {
            unraise(view);
            unscroll(view);
        }

        private void unraise(View view) {
            Float ours = raised.get(view);
            forget(view);
            if (ours != null && view.getTranslationY() == ours) view.setTranslationY(0f);
        }

        private void unscroll(View view) {
            Integer ours = scrolled.remove(view);
            unwatch(view);
            if (ours != null && view.getScrollY() == ours) view.scrollTo(view.getScrollX(), 0);
        }

        private void forget(View view) {
            raised.remove(view);
            unwatch(view);
        }

        /** Has [view] put back as it comes off the window, before it's first raised or scrolled. */
        private void watch(View view) {
            if (!raised.containsKey(view) && !scrolled.containsKey(view)) view.addOnAttachStateChangeListener(putBack);
        }

        /** Stops watching [view] once it's neither raised nor scrolled. */
        private void unwatch(View view) {
            if (!raised.containsKey(view) && !scrolled.containsKey(view)) view.removeOnAttachStateChangeListener(putBack);
        }

        /**
         * Keeps Instagram's touch strip on the seek bar of the reel in front ({@link #stripOnTheReel}), or,
         * with no seek bar, on the viewer's bottom.
         */
        private void keepTheStripOnTheReel(View holder) {
            if (viewers.isEmpty()) return;
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
            if (strip != null && stripOnTheReel(strip, viewers, id(holder.getContext(), SCRUBBER), reelsRect)) {
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_STRIP_KEPT);
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
            boolean layOutAgain = false;
            long now = SystemClock.uptimeMillis();
            try {
                stepAsideForStories();
                footerMoved = false;
                reelsMoved = false;
                keep();
                if (footerMoved) {
                    // Instagram laid this frame out with the reel shrunk over the footer (#260). Hold the
                    // frame back for the layout the footer's new margin asks for, so the shrunk reel never shows.
                    if (now - footerRedrawAt >= FOOTER_REDRAW_MS) {
                        footerRedrawAt = now;
                        layOutAgain = true;
                    }
                }
                // A Reels viewer laid out at a size it's about to lose isn't shown at it either.
                if (reelsMoved) layOutAgain = true;
                askForInsetsWhenShownChanges();
                // Moved sheets hold the frame back for their new layout too, so a sheet never shows on the
                // navigation bar first. They move once when the holder first turns up and when the room changes.
                if (liftSheets()) layOutAgain = true;
                // A tab's own selected state redraws that tab, not the bar behind it, so the capsule would
                // wait for something else to redraw the bar. Look at it every frame instead.
                View selected = selectedTab();
                View before = lastSelected == null ? null : lastSelected.get();
                if (selected != before) {
                    lastSelected = selected == null ? null : new WeakReference<>(selected);
                    if (scrollHide != null) scrollHide.show();
                    bar.invalidate();
                }
            } catch (Throwable failure) {
                // Reported, and the frame and the blur go on: neither depends on what threw (#262).
                HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "draw", failure);
                diagnose("pre-draw failed", failure);
            } finally {
                Trace.endSection();
            }
            recordUnder();
            if (!layOutAgain) return true;
            if (now - heldBackAt < HOLD_BACK_MS) return true;
            heldBackAt = now;
            return false;
        }

        /**
         * Something on screen is drawing a frame, so the glass records what's under it again, at most every
         * RECORD_EVERY_MS, and once more shortly after a frame it skipped so it never stays behind. Only its
         * own render node is recorded, not a view, so this asks for no frame of its own: the screen stops
         * drawing as soon as nothing else changes. Off screen the bar is still drawn, so a video that starts
         * under it is still kept out. A recording that throws leaves the pill on its plain tint only until
         * one works again ({@link BlurTries}, #262).
         */
        private void recordUnder() {
            if (!blurWanted) return;
            if (HushgramPause.isPaused()) {
                pill.cancelCatchUp();
                return;
            }
            try {
                if (scrollHide == null || !scrollHide.isOffScreen()) pill.recordIfDue();
                else pill.recordIfSurfaceAppeared();
            } catch (Throwable failure) {
                blurFailed("blur", failure);
            }
        }

        /**
         * A blur recording that threw: reported, tried again shortly, and after {@link #BLUR_TRIES} in a row
         * left to rest on the plain tint for {@link #BLUR_REST_MS}. Before, one failure turned the blur off for
         * as long as the bar lived, and the pill sat on the dark tint from then on (#262).
         */
        private void blurFailed(String what, Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, what, failure);
            diagnose(what + " failed", failure);
            if (tries.failed(SystemClock.uptimeMillis())) HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_BLUR_RESTED);
            pill.retrySoon();
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
            showAfterAStory();
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
            // A catch-up waiting in the queue would hold this bar after it's gone; the next frame
            // after it comes back arranges another.
            pill.cancelCatchUp();
        }

        /** For good: the bar's activity is destroyed. Its listeners come off and the backdrop is let go. */
        void dispose() {
            bar.removeOnAttachStateChangeListener(this);
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
            ValueAnimator running = compacting;
            compacting = null;
            if (running != null) running.cancel();
            pill.discard();
        }

        /** What shows behind the tabs. */
        private final class PillDrawable extends Drawable {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final RectF capsule = new RectF();
            /** The liquid look's pill as drawn this frame, on its way to the compact row, the edge being drawn, and both shapes. */
            private final RectF shrunk = new RectF();
            private final RectF outline = new RectF();
            private final Path shapes = new Path();
            /** The pill's bounds as of the last recording, kept apart from what the drawing is using. */
            private final RectF area = new RectF();
            /** Where the last recording took the glass's backdrop from ({@link #backdropSource}). */
            private final Matrix source = new Matrix();
            @Nullable private RenderNode node;
            private boolean recorded;
            private float nodeLeft, nodeTop;
            private int nodeWidth = -1;
            private int nodeHeight = -1;
            /** The blur slider's dp the node's effect was made for (#391). */
            private int nodeBlurDp = -1;
            private boolean drawingBackdrop;
            private float sheenTop = Float.NaN;
            private float sheenBottom = Float.NaN;
            private boolean sheenDark;
            /** Where the capsule is sliding from and to, and when the slide started, on the frame clock. */
            private float fromLeft = Float.NaN, fromRight, toLeft, toRight, nowLeft, nowRight;
            private long slideStart;
            /** The size the liquid look's bubble is drawn at now, and at when the slide it is in began (#289). */
            private float bubbleNow = 1f, bubbleFrom = 1f;
            /** Whether the blurred backdrop was drawn into the pill this frame, which the bubble's lens needs. */
            private boolean blurShown;
            @Nullable private GlassLens lens;
            private final Paint bubbleSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint bubbleRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Matrix bubbleMatrix = new Matrix();
            private int bubbleShade;
            /** The tab the capsule's middle was over when it last ticked, or -1 before the first frame. */
            private int tickedTab = -1;
            private boolean reported;

            PillDrawable() {
                stroke.setStyle(Paint.Style.STROKE);
                WeakReference<PillDrawable> held = new WeakReference<>(this);
                catchUp = () -> {
                    PillDrawable current = held.get();
                    if (current != null) current.catchUp();
                };
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
                float bottomEdge = bar.getHeight() - room - gap() - pillInset();
                if (right - left <= 0 || bottomEdge - top <= 0) return false;
                into.set(left, top, right, bottomEdge);
                return true;
            }

            private void drawPill(@NonNull Canvas canvas) {
                if (!pillBounds(rect)) return;
                if (liquidNow()) {
                    drawLiquid(canvas);
                    return;
                }
                float radius = cornerPx(corners, false, rect.height(), density);
                boolean dark = isDark(base);
                underlay(canvas);
                // Light glass has nothing under it to stand out against, so it gets a soft shadow.
                if (!dark) shadow(canvas, rect, radius);
                boolean blurred = blurWanted && !tries.resting(SystemClock.uptimeMillis()) && drawBackdrop(canvas);
                fill.setColor(tint(base, blurred, opacityScale()));
                canvas.drawRoundRect(rect, radius, radius, fill);

                keepSheen(dark);
                canvas.drawRoundRect(rect, radius, radius, sheen);

                drawCapsule(canvas, rect, cornerPx(corners, true, rect.height() - 2 * dp(HIGHLIGHT_INSET_DP), density), 1f);

                stroke.setStrokeWidth(Math.max(1f, density));
                stroke.setColor(edge(base));
                rect.inset(density / 2f, density / 2f);
                canvas.drawRoundRect(rect, radius, radius, stroke);
            }

            /**
             * The liquid look (#65): the pill holding the tabs and Search's circle beside it, both fully round,
             * over the one blurred backdrop clipped to the two, with the standard pill's tint, sheen and edge. A
             * highlight as round marks the tab you're on. On the way to the compact row the pill shrinks to a
             * circle round that tab, and its highlight fades.
             */
            private void drawLiquid(@NonNull Canvas canvas) {
                boolean dark = isDark(base);
                View search = searchTab();
                boolean apart = liquidShapes(rect, search);
                shrunkHolder(holder, bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL, compact, shrunk);
                float round = shrunk.height() / 2f;
                underlay(canvas);
                if (!dark) {
                    shadow(canvas, shrunk, round);
                    if (apart) shadow(canvas, circle, circle.height() / 2f);
                }
                shapes.rewind();
                shapes.addRoundRect(shrunk, round, round, Path.Direction.CW);
                if (apart) shapes.addOval(circle, Path.Direction.CW);
                boolean blurred = false;
                if (blurWanted && !tries.resting(SystemClock.uptimeMillis())) {
                    int saved = canvas.save();
                    canvas.clipPath(shapes);
                    blurred = drawBackdrop(canvas);
                    canvas.restoreToCount(saved);
                }
                blurShown = blurred;
                fill.setColor(tint(base, blurred, opacityScale()));
                canvas.drawPath(shapes, fill);
                keepSheen(dark);
                canvas.drawPath(shapes, sheen);

                View selected = selectedTab();
                RectF within = apart && selected != null && selected == search ? circle : shrunk;
                drawCapsule(canvas, within, Math.max(0f, within.height() / 2f - dp(HIGHLIGHT_INSET_DP)), 1f - compact);

                stroke.setStrokeWidth(Math.max(1f, density));
                stroke.setColor(edge(base));
                float half = density / 2f;
                outline.set(shrunk);
                outline.inset(half, half);
                canvas.drawRoundRect(outline, outline.height() / 2f, outline.height() / 2f, stroke);
                if (apart) {
                    outline.set(circle);
                    outline.inset(half, half);
                    canvas.drawOval(outline, stroke);
                }
            }

            /**
             * What shows round the pill: over Instagram's bar, the reel blurred while Blur the bars around
             * a reel has one ({@link #strip}, #258) or else the bar's own colour, and nothing round a
             * floating pill.
             */
            private void underlay(@NonNull Canvas canvas) {
                // The content stops above the bar, so something has to fill round the pill. A floating
                // pill has the screens running on behind it, all but Reels, which stop above its slot.
                if (!floating && !strip.paint(canvas, 0, 0, bar.getWidth(), bar.getHeight())) {
                    fill.setColor(base);
                    canvas.drawRect(0, 0, bar.getWidth(), bar.getHeight(), fill);
                }
            }

            /** The soft shadow light glass gets round [shape], whose corners are [radius]. */
            private void shadow(@NonNull Canvas canvas, RectF shape, float radius) {
                for (int i = 6; i >= 1; i--) {
                    float spread = i * density * 0.8f;
                    fill.setColor(0x06000000);
                    capsule.set(shape.left - spread, shape.top - spread / 3f + density,
                            shape.right + spread, shape.bottom + spread);
                    canvas.drawRoundRect(capsule, radius + spread, radius + spread, fill);
                }
            }

            /** Makes the sheen's gradient again when the pill's height or the glass's shade changed. */
            private void keepSheen(boolean dark) {
                if (sheenTop != rect.top || sheenBottom != rect.bottom || sheenDark != dark) {
                    sheenTop = rect.top;
                    sheenBottom = rect.bottom;
                    sheenDark = dark;
                    sheen.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                            dark ? 0x22ffffff : 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                }
            }

            /**
             * The lighter capsule behind the selected tab, kept inside [within] with corners of [corner] and drawn
             * at [share] of its strength. When the tab changes it slides to the new one over {@link #SLIDE_MS},
             * eased out, timed by the frame clock rather than by counting frames, so a dropped frame doesn't make
             * it jump and it takes as long at 120 Hz as at 60. Below full strength, on the way to the liquid look's
             * compact row, it stays on the tab as the tab moves.
             */
            private void drawCapsule(Canvas canvas, RectF within, float corner, float share) {
                View selected = selectedTab();
                if (selected == null) return;
                float grow = dp(HIGHLIGHT_GROW_DP);
                float inset = dp(HIGHLIGHT_INSET_DP);
                float targetLeft = Math.max(within.left + inset, selected.getLeft() + selected.getTranslationX() - grow);
                float targetRight = Math.min(within.right - inset, selected.getRight() + selected.getTranslationX() + grow);
                long now = AnimationUtils.currentAnimationTimeMillis();
                if (!Float.isNaN(dragX)) {
                    // A finger slides it (#136): it sits on the finger, as wide as the selected tab's, and
                    // ticks for each tab it crosses. Once the finger lifts it slides from here to the tab.
                    float width = targetRight - targetLeft;
                    fromLeft = nowLeft = toLeft = followLeft(dragX, width, rect.left + inset, rect.right - inset);
                    fromRight = nowRight = toRight = toLeft + width;
                    slideStart = now - SLIDE_MS;
                    tickedThisSlide = false;
                    bubbleNow = bubbleFrom = 1f + BUBBLE_SWELL;
                } else if (Float.isNaN(fromLeft) || share < 1f) {
                    fromLeft = nowLeft = toLeft = targetLeft;
                    fromRight = nowRight = toRight = targetRight;
                    slideStart = now - SLIDE_MS;
                    bubbleNow = bubbleFrom = 1f;
                } else if (Math.abs(targetLeft - toLeft) > 0.5f || Math.abs(targetRight - toRight) > 0.5f) {
                    tickedThisSlide = false;
                    fromLeft = nowLeft;
                    fromRight = nowRight;
                    toLeft = targetLeft;
                    toRight = targetRight;
                    slideStart = slideStartAt(now);
                    bubbleFrom = bubbleNow;
                }
                float progress = slideProgress(now - slideStart);
                boolean bubble = liquidNow() && share > 0f;
                if (Float.isNaN(dragX)) bubbleNow = bubble ? bubbleScale(now - slideStart, bubbleFrom) : 1f;
                else if (!bubble) bubbleNow = bubbleFrom = 1f;
                nowLeft = fromLeft + (toLeft - fromLeft) * progress;
                nowRight = fromRight + (toRight - fromRight) * progress;
                capsule.set(nowLeft, within.top + inset, nowRight, within.bottom - inset);
                tick((nowLeft + nowRight) / 2f);
                int color = highlight(base);
                if (share < 1f) color = (color & 0x00ffffff) | (scaledAlpha(Color.alpha(color), Math.max(0f, share)) << 24);
                fill.setColor(color);
                if (bubble && bubbleNow != 1f) {
                    float swell = (bubbleNow - 1f) / 2f;
                    capsule.inset(-capsule.width() * swell, -capsule.height() * swell);
                    corner *= bubbleNow;
                }
                if (bubble) paintLens(canvas, corner);
                fill.setColor(color);
                if (share > 0f) canvas.drawRoundRect(capsule, corner, corner, fill);
                if (bubble) paintBubble(canvas, corner, share);
                if (progress < 1f) invalidateSelf();
            }

            /**
             * The lens under the bubble (#289): the blurred backdrop magnified a little, on Android 13 and newer and
             * only where the pill is blurred. The pill's tint goes back over it so the bubble reads as part of the pill.
             */
            private void paintLens(Canvas canvas, float corner) {
                if (!blurShown || !GlassLens.supported()) return;
                GlassLens made = lens;
                if (made == null) {
                    made = new GlassLens();
                    lens = made;
                }
                if (made.paint(canvas, node, nodeLeft, nodeTop, DOWNSAMPLE, bar.getWidth(), bar.getHeight(), capsule, corner)) {
                    fill.setColor(tint(base, true, opacityScale()));
                    canvas.drawRoundRect(capsule, corner, corner, fill);
                }
            }

            /**
             * The raised look of the liquid look's bubble (#289): light at its top edge fading down inside it, and a
             * brighter rim, bright at the top and faint at the bottom, drawn at [share] of their strength. Light and
             * dark glass each get the shades that read on them.
             */
            private void paintBubble(Canvas canvas, float corner, float share) {
                boolean dark = isDark(base);
                int shade = dark ? 1 : 2;
                if (bubbleShade != shade) {
                    bubbleShade = shade;
                    bubbleSheen.setShader(new LinearGradient(0, 0, 0, 1,
                            dark ? 0x40ffffff : 0x8cffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                    bubbleRim.setStyle(Paint.Style.STROKE);
                    bubbleRim.setShader(new LinearGradient(0, 0, 0, 1,
                            dark ? 0xb0ffffff : 0xf0ffffff, dark ? 0x28ffffff : 0x66ffffff, Shader.TileMode.CLAMP));
                }
                bubbleMatrix.setScale(1f, Math.max(1f, capsule.height()));
                bubbleMatrix.postTranslate(0f, capsule.top);
                bubbleSheen.getShader().setLocalMatrix(bubbleMatrix);
                bubbleRim.getShader().setLocalMatrix(bubbleMatrix);
                int alpha = scaledAlpha(255, Math.max(0f, Math.min(1f, share)));
                bubbleSheen.setAlpha(alpha);
                bubbleRim.setAlpha(alpha);
                canvas.drawRoundRect(capsule, corner, corner, bubbleSheen);
                float stroke = Math.max(1f, density * 1.25f);
                bubbleRim.setStrokeWidth(stroke);
                float half = stroke / 2f;
                capsule.inset(half, half);
                canvas.drawRoundRect(capsule, Math.max(0f, corner - half), Math.max(0f, corner - half), bubbleRim);
                capsule.inset(-half, -half);
            }

            /** A light tick each time the capsule's middle moves over a different tab, wherever the tab was moved to. */
            private void tick(float middle) {
                int over = -1;
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    float shift = child.getTranslationX();
                    if (child.getVisibility() == View.VISIBLE
                            && middle >= child.getLeft() + shift && middle < child.getRight() + shift) {
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
            /** A real frame permits at most three automatic captures, including one after a rest. */
            private int catchUpsLeft = BLUR_TRIES;
            private final Runnable catchUp;

            private void catchUp() {
                catchUpPending = false;
                if (!bar.isAttachedToWindow() || HushgramPause.isPaused() || catchUpsLeft <= 0) return;
                if (tries.resting(SystemClock.uptimeMillis())) {
                    retrySoon();
                    return;
                }
                catchUpsLeft--;
                recordBackdrop();
                // A permanent failure must not request frames that renew its own retry budget.
                if (recorded) bar.invalidate();
            }

            /** One bounded recovery chain, with a single wake at the end of any failure rest. */
            void retrySoon() {
                if (catchUpPending || catchUpsLeft <= 0 || !bar.isAttachedToWindow() || HushgramPause.isPaused()) return;
                catchUpPending = true;
                bar.postDelayed(catchUp, Math.max(RECORD_EVERY_MS, tries.waitLeft(SystemClock.uptimeMillis())));
            }

            /** Drops a catch-up still waiting, so the next frame can arrange a new one. */
            void cancelCatchUp() {
                bar.removeCallbacks(catchUp);
                catchUpPending = false;
            }

            /** Drops a waiting catch-up and lets go of the recorded backdrop and the views it left out. */
            void discard() {
                cancelCatchUp();
                catchUpsLeft = 0;
                RenderNode backdrop = node;
                node = null;
                recorded = false;
                if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) backdrop.discardDisplayList();
                if (lens != null) lens.discard();
                leftOut.clear();
                surfaces.clear();
                skipped.clear();
                frames.clear();
                hadSurfaces = false;
                videoCopied = false;
            }

            /** Copies of the frames playing in the SurfaceViews under the bar, drawn in their places (#144). */
            private final SurfaceFrames frames = new SurfaceFrames(DOWNSAMPLE);
            /** Whether a frame has been copied since a SurfaceView last came under the bar, for the report's count. */
            private boolean videoCopied;
            private final Runnable frameLanded = () -> {
                if (!videoCopied) {
                    videoCopied = true;
                    HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_VIDEO_COPIED);
                }
                // A SurfaceView's new frames don't redraw a view, so the landed copy asks for the frame that records it.
                if (bar.isAttachedToWindow()) bar.invalidate();
            };

            /**
             * Asks for a fresh copy of each SurfaceView's frame in [parent] that crosses the strip the glass shows,
             * from [top] and [tall] high, leaving out those inside a Reels viewer the glass doesn't draw (#222).
             */
            private void askFrames(ViewGroup parent, float top, float tall) {
                long now = SystemClock.uptimeMillis();
                Rect place = new Rect();
                for (View view : leftOut) {
                    if (!(view instanceof android.view.SurfaceView) || insideSkipped(view)) continue;
                    place.set(0, 0, view.getWidth(), view.getHeight());
                    try {
                        parent.offsetDescendantRectToMyCoords(view, place);
                    } catch (IllegalArgumentException gone) {
                        continue;
                    }
                    if (place.bottom <= top || place.top >= top + tall) continue;
                    frames.ask((android.view.SurfaceView) view, now, frameLanded);
                }
            }

            private boolean insideSkipped(View view) {
                for (View viewer : skipped) {
                    if (viewer instanceof ViewGroup && under(view, (ViewGroup) viewer)) return true;
                }
                return false;
            }

            /** Records the backdrop if it's been long enough and this frame isn't late, or arranges to once it can. */
            void recordIfDue() {
                catchUpsLeft = BLUR_TRIES;
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
            /** The Reels viewers the last recording left out ({@link #leaveOut}). */
            private final java.util.Set<View> skipped = new java.util.HashSet<>();
            /** Whether the last recording had a SurfaceView to leave out. */
            private boolean hadSurfaces;

            void recordIfSurfaceAppeared() {
                if (!surfaceAppeared()) return;
                catchUpsLeft = BLUR_TRIES;
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
             * not a view, it never asks for a frame of its own. A floating pill shows what's right behind it,
             * since the screens run on under it (#262). A docked bar shows the strip of screen just above it,
             * upside down, as if the glass reflected it: Instagram's screens stop above that bar, so the strip
             * has what you're looking at ({@link #backdropSource}).
             */
            void recordBackdrop() {
                if (drawingBackdrop || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
                if (!bar.isAttachedToWindow() || HushgramPause.isPaused()) {
                    cancelCatchUp();
                    return;
                }
                if (tries.resting(SystemClock.uptimeMillis())) return;
                if (!(bar.getParent() instanceof ViewGroup) || !pillBounds(area)) return;
                ViewGroup parent = (ViewGroup) bar.getParent();
                int barIndex = parent.indexOfChild(bar);
                if (barIndex <= 0) return;
                Trace.beginSection("hushgram:recordBackdrop");
                drawingBackdrop = true;
                try {
                    float scale = 1f / DOWNSAMPLE;
                    // The blur slider is read each recording, so a move shows on the next frame (#391).
                    int blurDp = Settings.GLASS_TAB_BAR_BLUR_RADIUS.get();
                    float pad = GlassBlur.radiusPx(blurDp, density);
                    float radius = cornerPx(corners, false, area.height(), density);
                    float wide = area.width() + 2 * pad;
                    float tall = area.height() + 2 * pad;
                    int width = Math.max(1, (int) Math.ceil(wide * scale));
                    int height = Math.max(1, (int) Math.ceil(tall * scale));
                    RenderNode backdrop = node;
                    if (backdrop == null) {
                        backdrop = new RenderNode("hushgram-glass");
                        backdrop.setClipToOutline(true);
                        node = backdrop;
                        nodeBlurDp = -1;
                    }
                    boolean newBlur = blurDp != nodeBlurDp;
                    if (newBlur) {
                        float blur = GlassBlur.effectRadius(blurDp, density, scale);
                        ColorMatrix saturate = new ColorMatrix();
                        saturate.setSaturation(1.5f);
                        ColorFilter colour = new ColorMatrixColorFilter(saturate);
                        backdrop.setRenderEffect(RenderEffect.createChainEffect(
                                RenderEffect.createColorFilterEffect(colour),
                                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                        nodeBlurDp = blurDp;
                    }
                    // The outline sits the blur's reach inside the node, so a new radius moves it too.
                    if (newBlur || width != nodeWidth || height != nodeHeight) {
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
                    float top = backdropSource(source, floating, scale, x0, bar.getTop(), bar.getY() + area.top, pad, tall);
                    Canvas recording = backdrop.beginRecording(width, height);
                    boolean found;
                    try {
                        recording.concat(source);
                        recording.clipRect(x0, top, x0 + wide, top + tall);
                        found = surfacePaths(parent, barIndex, leftOut);
                        if (found && !hadSurfaces) HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_VIDEO_LEFT_OUT);
                        hadSurfaces = found;
                        // A Reels viewer stops above a floating pill and is left out, so the glass never
                        // draws the reel's video a second time (#222).
                        skipped.clear();
                        for (View viewer : viewers) {
                            if (viewer != null) leaveOut(viewer, parent, leftOut, skipped);
                        }
                        frames.keepOnly(leftOut);
                        for (int i = 0; i < barIndex; i++) {
                            View sibling = parent.getChildAt(i);
                            if (sibling.getWidth() <= 0 || sibling.getBottom() <= top || sibling.getTop() >= top + tall) continue;
                            drawLeavingOutSurfaces(recording, sibling, leftOut, skipped, frames::frameOf, 0);
                        }
                    } finally {
                        backdrop.endRecording();
                    }
                    recorded = true;
                    tries.worked();
                    // A landed video copy requests a frame, so only successful recordings may ask for one.
                    if (found) askFrames(parent, top, tall);
                    else videoCopied = false;
                    report("backdrop recorded under " + barIndex + " views: pill " + Math.round(area.width()) + "x"
                            + Math.round(area.height()) + " as " + width + "x" + height);
                } catch (Throwable failure) {
                    // A recording cut short isn't shown: the pill draws its tint, or this frame tries again.
                    recorded = false;
                    blurFailed("blur", failure);
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
     * How a glass bar's blur recordings have gone lately (#262). A recording that throws, such as one
     * drawing a view Instagram is recycling as a list scrolls, leaves the pill on its plain tint only
     * until the next one works. After {@link #BLUR_TRIES} in a row the blur rests on the tint for
     * {@link #BLUR_REST_MS}, so a view that throws every time it's drawn doesn't cost a failure each
     * frame, and then tries again. Before, one failure turned the blur off for as long as the bar lived.
     * Main thread.
     */
    static final class BlurTries {
        private int failedInARow;
        private long restUntil = Long.MIN_VALUE;

        /** Whether the blur rests at [now], drawing the plain tint and recording nothing. */
        boolean resting(long now) {
            return now < restUntil;
        }

        long waitLeft(long now) {
            return resting(now) ? restUntil - now : 0;
        }

        /** A recording failed at [now]. Says whether that was one too many in a row and the blur rests. */
        boolean failed(long now) {
            if (++failedInARow < BLUR_TRIES) return false;
            failedInARow = 0;
            restUntil = now + BLUR_REST_MS;
            return true;
        }

        /** A recording worked, so the count of failures starts over. */
        void worked() {
            failedInARow = 0;
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

    /** How much larger the liquid look's bubble is while it moves between tabs, and under a finger (#289). */
    static final float BUBBLE_SWELL = 0.08f;

    /**
     * The size of the liquid look's bubble [elapsed] ms into a slide that began at size [from]: it swells to
     * {@link #BUBBLE_SWELL} over 1 mid-slide, eases back to 1 as it settles, and carries on from [from] when
     * it was already bigger, such as a finger letting go of it. 1 once the slide is over, or with none.
     */
    static float bubbleScale(long elapsed, float from) {
        if (elapsed >= SLIDE_MS) return 1f;
        float t = Math.max(0f, elapsed) / (float) SLIDE_MS;
        float swell = 1f + BUBBLE_SWELL * (float) Math.sin(Math.PI * t);
        float carried = 1f + Math.max(0f, from - 1f) * (1f - t);
        return Math.max(swell, carried);
    }

    /**
     * Where the capsule's left edge goes while a finger slides it (#136): [width] wide, centered on
     * [finger], and kept between [min] and [max]. With no room to move it stays at [min].
     */
    static float followLeft(float finger, float width, float min, float max) {
        if (max - min <= width) return min;
        return Math.max(min, Math.min(max - width, finger - width / 2f));
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
