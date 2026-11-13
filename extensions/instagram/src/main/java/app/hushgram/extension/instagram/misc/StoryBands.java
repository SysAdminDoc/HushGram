/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.feed.AmbientLight;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The strips under the status bar and the navigation bar round a story (#666).
 *
 * <p>Instagram draws its screens edge to edge and leaves those strips round a story to the layouts the
 * story viewer sits in, which wear the theme's colour. In light mode that's white, so the status bar's
 * white icons and the navigation buttons were lost on it. While a story fills a window from the status
 * bar down to the navigation bar, each strip it leaves gets a black cover on that window's overlay,
 * which draws over the app and under the bars' own icons, as a story looks in dark mode. That goes for
 * the main window and for the separate one a story opened from a profile or a link shows in.
 *
 * <p>The covers come off the moment no story shows on top, and stay off while Ambient light in stories
 * glows in the strips, which then glows on black itself. A story that reaches under a bar keeps that
 * bar's strip, and one that doesn't come up to the bars, such as a sheet, isn't covered at all. HushGram
 * paused leaves the strips as Instagram draws them.
 */
public final class StoryBands {
    /** Status count for each time a story's strips are covered. */
    static final String COVERED = "Strips round a story painted black";

    /** The watched windows and their covers. Main thread only. */
    private static final Map<View, Bands> WATCHED = new WeakHashMap<>();
    private static volatile boolean registered;
    /** The status bar's and the navigation bar's heights, for tests. */
    @Nullable private static int[] insetsForTests;
    @Nullable private static Boolean litForTests;

    private StoryBands() {
    }

    /** Called as the application starts. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application)) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_BANDS, "start", failure);
        }
    }

    /** Starts checking [decor] before each frame, once. */
    static void track(@Nullable View decor) {
        if (decor == null || WATCHED.containsKey(decor)) return;
        Bands bands = new Bands(decor);
        WATCHED.put(decor, bands);
        decor.getViewTreeObserver().addOnPreDrawListener(bands);
        bands.check();
    }

    static void release(@Nullable View decor) {
        Bands bands = decor == null ? null : WATCHED.remove(decor);
        if (bands == null) return;
        if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(bands);
        bands.hide();
    }

    /** Checks [decor] again now, as a frame would. */
    static void refresh(View decor) {
        Bands bands = WATCHED.get(decor);
        if (bands != null) bands.check();
    }

    /** Where [decor]'s covers are, top first, empty while they're off. */
    static List<Rect> covers(View decor) {
        List<Rect> found = new ArrayList<>(2);
        Bands bands = WATCHED.get(decor);
        if (bands == null) return found;
        if (bands.topShown) found.add(new Rect(bands.top.getBounds()));
        if (bands.bottomShown) found.add(new Rect(bands.bottom.getBounds()));
        return found;
    }

    /**
     * The strips [story] leaves in [decor], top then bottom, each null where there's none to cover:
     * none at all unless the story spans the window's width and comes up to both bars, and none where
     * it reaches under a bar itself.
     */
    static Rect[] strips(View decor, View story, int statusBar, int navigationBar) {
        Rect[] strips = new Rect[2];
        int width = decor.getWidth(), height = decor.getHeight();
        if (width <= 0 || height <= 0 || story.getWidth() <= 0 || story.getHeight() <= 0) return strips;
        int[] at = new int[2];
        story.getLocationInWindow(at);
        int left = at[0], top = at[1], right = left + story.getWidth(), bottom = top + story.getHeight();
        if (left > 0 || right < width || top > statusBar || bottom < height - navigationBar) return strips;
        if (top > 0) strips[0] = new Rect(0, 0, width, top);
        if (bottom < height) strips[1] = new Rect(0, bottom, width, height);
        return strips;
    }

    /** The status bar's height at the top of [decor] and the navigation bar's at the bottom, each 0 while hidden. */
    static int[] insets(View decor) {
        int[] forced = insetsForTests;
        if (forced != null) return forced;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return new int[2];
        if (Build.VERSION.SDK_INT >= 30) {
            return new int[] {
                    insets.getInsets(WindowInsets.Type.statusBars()).top,
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
            };
        }
        // Before Android 11 the stable inset stays while a bar is hidden and the window inset grows with
        // the keyboard, so the smaller one is the bar.
        return new int[] {
                Math.min(insets.getStableInsetTop(), insets.getSystemWindowInsetTop()),
                Math.min(insets.getStableInsetBottom(), insets.getSystemWindowInsetBottom()),
        };
    }

    private static boolean glowing(View decor) {
        Boolean forced = litForTests;
        return forced != null ? forced : AmbientLight.storyBandsLit(decor);
    }

    static void resetForTests(@Nullable int[] insets) {
        insetsForTests = insets;
        litForTests = null;
        for (View decor : new ArrayList<>(WATCHED.keySet())) release(decor);
    }

    static void litForTests(@Nullable Boolean lit) {
        litForTests = lit;
    }

    /** One window's covers, checked before each frame. */
    private static final class Bands implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> decor;
        private final GlassTabBar.ShownOnTop story = new GlassTabBar.ShownOnTop(GlassTabBar.STORY_ROOT);
        final ColorDrawable top = new ColorDrawable(Color.BLACK);
        final ColorDrawable bottom = new ColorDrawable(Color.BLACK);
        boolean topShown, bottomShown;

        Bands(View decor) {
            this.decor = new WeakReference<>(decor);
        }

        @Override
        public boolean onPreDraw() {
            check();
            return true;
        }

        void check() {
            try {
                View window = decor.get();
                if (window == null) return;
                Rect[] strips = new Rect[2];
                View shown = HushgramPause.isPaused() ? null : story.shownIn(window);
                if (shown != null && !glowing(window)) {
                    int[] bars = insets(window);
                    strips = strips(window, shown, bars[0], bars[1]);
                }
                boolean wasOff = !topShown && !bottomShown;
                topShown = place(window, top, strips[0], topShown);
                bottomShown = place(window, bottom, strips[1], bottomShown);
                if (wasOff && (topShown || bottomShown)) HookStatus.counted(FamilyNames.STORY_BANDS, COVERED);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_BANDS, "check", failure);
            }
        }

        /** Puts [cover] on [window]'s overlay over [strip], or takes it off with none. Answers whether it's on. */
        private static boolean place(View window, ColorDrawable cover, @Nullable Rect strip, boolean shown) {
            if (strip == null) {
                if (shown) window.getOverlay().remove(cover);
                return false;
            }
            if (!strip.equals(cover.getBounds())) cover.setBounds(strip);
            if (!shown) window.getOverlay().add(cover);
            return true;
        }

        void hide() {
            View window = decor.get();
            if (window != null) {
                if (topShown) window.getOverlay().remove(top);
                if (bottomShown) window.getOverlay().remove(bottom);
            }
            topShown = false;
            bottomShown = false;
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) track(window.getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_BANDS, "watch", failure);
            }
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) release(window.peekDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_BANDS, "release", failure);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    }
}
