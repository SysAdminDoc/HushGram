/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Part of the "Pure black dark mode" patch: the strip behind the navigation bar (#571).
 *
 * <p>Instagram draws its screens edge to edge and leaves that strip to whatever is under it. With a
 * comments sheet up, or a reel opened from the feed with its comment box at the bottom and no tab
 * bar, that's the reel, so the video showed under the bar's handle. While either is on screen in
 * dark mode, the strip gets a black cover on the window's overlay, which draws over the app and
 * under the bar's own handle and buttons. It comes off as soon as neither shows, so a screen that
 * runs content behind the bar on purpose (the glass tab bar's) is left alone.
 */
public final class PureBlackNav {
    /** The reels viewer, in the Reels tab and wherever a reel opens full screen. */
    static final String VIEWER = "clips_viewer_container";
    /** Instagram's tab bar. */
    static final String TAB_BAR = "tab_bar";
    /** How much of the window a viewer has to fill to count as the screen. */
    static final float FILL_SHARE = 0.8f;
    /** Status count for each time the cover goes on. */
    static final String COVERED = "navigation bar painted black";

    /** The comments screens' roots, weakly, from {@link CommentsSheet}. */
    private static final Map<View, Boolean> COMMENTS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The watched windows and their covers. Main thread only. */
    private static final Map<View, Cover> COVERS = new WeakHashMap<>();
    private static final HashMap<String, Integer> IDS = new HashMap<>();
    private static volatile boolean registered;
    @Nullable private static Integer insetForTests;

    private PureBlackNav() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.pureBlack()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "navigation bar start", failure);
        }
    }

    /** A comments screen's root is in a window. Watches that window too, in case it's a sheet's own. */
    static void commentsShown(View root) {
        COMMENTS.put(root, Boolean.TRUE);
        track(root.getRootView());
    }

    /** Starts watching [decor]'s layouts, once. */
    static void track(View decor) {
        if (decor == null || COVERS.containsKey(decor)) return;
        Cover cover = new Cover(decor);
        COVERS.put(decor, cover);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(cover);
        cover.onGlobalLayout();
    }

    static void release(View decor) {
        Cover cover = COVERS.remove(decor);
        if (cover == null) return;
        if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnGlobalLayoutListener(cover);
        cover.hide();
    }

    /** Checks [decor] again now, as a layout would. */
    static void refresh(View decor) {
        Cover cover = COVERS.get(decor);
        if (cover != null) cover.onGlobalLayout();
    }

    /** Where [decor]'s cover is, or null while it's off. */
    @Nullable
    static Rect cover(View decor) {
        Cover cover = COVERS.get(decor);
        return cover == null || !cover.shown ? null : new Rect(cover.strip.getBounds());
    }

    /** Whether the strip should be black now. */
    static boolean wanted(View decor) {
        return isNight(decor) && (commentsShowing(decor) || bareViewer(decor));
    }

    static boolean commentsShowing(View decor) {
        List<View> roots;
        synchronized (COMMENTS) {
            roots = new ArrayList<>(COMMENTS.keySet());
        }
        for (View root : roots) {
            if (root != null && root.isShown() && root.getRootView() == decor) return true;
        }
        return false;
    }

    /** Whether a reels viewer fills the window with no tab bar drawn over it. */
    static boolean bareViewer(View decor) {
        int viewerId = id(decor.getContext(), VIEWER);
        if (viewerId == 0) return false;
        View viewer = filling(decor, viewerId, decor);
        if (viewer == null) return false;
        int barId = id(decor.getContext(), TAB_BAR);
        View bar = barId == 0 ? null : decor.findViewById(barId);
        return bar == null || !bar.isShown() || !bar.getGlobalVisibleRect(new Rect()) || !drawsOver(bar, viewer);
    }

    /** The first view named [id] under [view] that fills the window. A hidden one left behind doesn't count. */
    @Nullable
    private static View filling(View view, int id, View decor) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == id) return fills(view, decor) ? view : null;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = filling(group.getChildAt(i), id, decor);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean fills(View view, View decor) {
        if (!view.isShown()) return false;
        Rect seen = new Rect();
        if (!view.getGlobalVisibleRect(seen)) return false;
        long window = (long) decor.getWidth() * decor.getHeight();
        return window > 0 && (long) seen.width() * seen.height() >= window * FILL_SHARE;
    }

    /**
     * Whether [top] is drawn after [under]: below the parent they share, the branch with the higher Z,
     * or on a tie the later child, draws last. A view inside the other counts as over it.
     */
    static boolean drawsOver(View top, View under) {
        List<View> upper = branch(top);
        List<View> lower = branch(under);
        if (upper.contains(under)) return true;
        if (lower.contains(top)) return false;
        for (int i = 1; i < upper.size(); i++) {
            View ancestor = upper.get(i);
            int at = lower.indexOf(ancestor);
            if (at <= 0) continue;
            ViewGroup shared = (ViewGroup) ancestor;
            View mine = upper.get(i - 1);
            View theirs = lower.get(at - 1);
            if (mine.getZ() != theirs.getZ()) return mine.getZ() > theirs.getZ();
            return shared.indexOfChild(mine) > shared.indexOfChild(theirs);
        }
        return false;
    }

    /** [view] and its ancestors, nearest first. */
    private static List<View> branch(View view) {
        List<View> chain = new ArrayList<>();
        View at = view;
        while (at != null) {
            chain.add(at);
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return chain;
    }

    /** The navigation bar's height at the bottom of [decor] while it shows, else 0. */
    static int navInset(View decor) {
        Integer forced = insetForTests;
        if (forced != null) return forced;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= 30) return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        // Before Android 11 the stable inset stays while the bar is hidden and the window inset grows
        // with the keyboard, so the smaller one is the bar.
        return Math.min(insets.getStableInsetBottom(), insets.getSystemWindowInsetBottom());
    }

    private static boolean isNight(View view) {
        int night = view.getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int id(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", context.getPackageName());
                IDS.put(name, found);
            }
            return found;
        }
    }

    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    static void resetForTests(@Nullable Integer inset) {
        insetForTests = inset;
        synchronized (IDS) {
            IDS.clear();
        }
        COMMENTS.clear();
        for (View decor : new ArrayList<>(COVERS.keySet())) release(decor);
    }

    /** One window's cover, checked after each layout. */
    private static final class Cover implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> decor;
        final ColorDrawable strip = new ColorDrawable(Color.BLACK);
        boolean shown;

        Cover(View decor) {
            this.decor = new WeakReference<>(decor);
        }

        @Override
        public void onGlobalLayout() {
            try {
                View window = decor.get();
                if (window == null) return;
                int inset = wanted(window) ? navInset(window) : 0;
                if (inset <= 0) {
                    hide();
                    return;
                }
                strip.setBounds(0, window.getHeight() - inset, window.getWidth(), window.getHeight());
                if (shown) {
                    strip.invalidateSelf();
                    return;
                }
                window.getOverlay().add(strip);
                shown = true;
                HookStatus.counted(FamilyNames.PURE_BLACK, COVERED);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PURE_BLACK, "navigation bar", failure);
            }
        }

        void hide() {
            if (!shown) return;
            shown = false;
            View window = decor.get();
            if (window != null) window.getOverlay().remove(strip);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) track(window.getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PURE_BLACK, "navigation bar watch", failure);
            }
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) release(window.getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PURE_BLACK, "navigation bar release", failure);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    }
}
