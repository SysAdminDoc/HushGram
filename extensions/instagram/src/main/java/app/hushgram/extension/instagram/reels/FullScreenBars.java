/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Full screen Reels and Home" patch: hides the status bar and the navigation bar
 * while Reels (or Home) is showing, and brings them back when it isn't.
 *
 * <p>It changes no Instagram code. Like the glass tab bar, it watches the activities once the
 * application is up and looks views up by the names Instagram gives them, which are the same on all
 * seven 450 builds: {@code clips_viewer_container} (the reels viewer, in the Reels tab and in the
 * full screen viewer a reel opens from anywhere), and the {@code clips_tab} and {@code feed_tab}
 * children of {@code tab_bar}. The viewer counts as showing when most of the window is its own, which
 * keeps a viewer sitting off screen in a pager from counting. Without a viewer, the tab bar's
 * selected tab answers.
 *
 * <p>The bars are hidden through the window's insets controller (Android 11 and newer) with the
 * "show transient bars by swipe" behaviour, and on Android 9 and 10 through the immersive sticky
 * system UI flags. Either way a swipe from the edge shows them for a moment, the back gesture works
 * as before, and the keyboard opens and pushes the screen up as it always did, because only the bars
 * are asked to hide. Whatever the window had before is put back when Reels is left, when the
 * activity pauses, and when the switch is turned off.
 *
 * <p>The check runs from a draw listener at most every {@link #CHECK_MS}, and acts only when the
 * answer changes, so a swipe that brought the bars back for a moment isn't fought. Anything that
 * throws leaves the bars as the system has them.
 */
public final class FullScreenBars {
    static final String TAB_BAR = "tab_bar";
    static final String REELS_TAB = "clips_tab";
    static final String HOME_TAB = "feed_tab";
    static final String VIEWER = "clips_viewer_container";

    /** The viewer counts as showing when this share of the window is its own. */
    static final float FILL_SHARE = 0.8f;
    /** The most often the screen is looked at for a change. */
    static final long CHECK_MS = 100;
    static final String COUNT_HID = "hid the bars";
    static final String COUNT_SHOWED = "showed the bars";

    @SuppressWarnings("deprecation")
    static final int IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private static final java.util.HashMap<String, Integer> IDS = new java.util.HashMap<>();
    private static volatile boolean registered;
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();

    private FullScreenBars() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.fullScreenBars()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "start", failure);
        }
    }

    /** What the screen is showing, as far as the bars are concerned. */
    enum Screen { REELS, HOME, OTHER }

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
    private static View find(View root, String name) {
        int id = id(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    /** Whether a view [share] of the window is on screen: its visible rectangle against the window's. */
    static boolean fills(View view, View decor) {
        if (!view.isShown()) return false;
        Rect seen = new Rect();
        if (!view.getGlobalVisibleRect(seen)) return false;
        long window = (long) decor.getWidth() * decor.getHeight();
        return window > 0 && (long) seen.width() * seen.height() >= window * FILL_SHARE;
    }

    /** The tab Instagram marks selected in its tab bar, as the name of its id, or null. */
    @Nullable
    private static String selectedTab(View decor) {
        View bar = find(decor, TAB_BAR);
        if (!(bar instanceof ViewGroup) || !bar.isShown()) return null;
        ViewGroup tabs = (ViewGroup) bar;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE || !tab.isSelected()) continue;
            if (tab.getId() != View.NO_ID && tab.getId() == id(decor.getContext(), REELS_TAB)) return REELS_TAB;
            if (tab.getId() != View.NO_ID && tab.getId() == id(decor.getContext(), HOME_TAB)) return HOME_TAB;
            return null;
        }
        return null;
    }

    /** What [decor]'s screen is: the reels viewer filling the window, or the Reels or Home tab selected. */
    static Screen screenOf(View decor) {
        View viewer = find(decor, VIEWER);
        if (viewer != null) {
            if (fills(viewer, decor)) return Screen.REELS;
            // The viewer is there but not showing: Home, or a screen over Reels, whatever tab is selected.
            return HOME_TAB.equals(selectedTab(decor)) ? Screen.HOME : Screen.OTHER;
        }
        String tab = selectedTab(decor);
        if (REELS_TAB.equals(tab)) return Screen.REELS;
        return HOME_TAB.equals(tab) ? Screen.HOME : Screen.OTHER;
    }

    /** Whether the bars should be hidden on [screen], by the two switches. */
    static boolean wanted(Screen screen) {
        if (screen == Screen.REELS) return Settings.FULL_SCREEN_REELS.get();
        return screen == Screen.HOME && Settings.FULL_SCREEN_HOME.get();
    }

    /** Tests give the names ids of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    @Nullable
    static Session sessionFor(Activity activity) {
        return sessions.get(activity);
    }

    static void track(Activity activity) {
        try {
            if (!Utils.settingsReady() || sessions.containsKey(activity)) return;
            Window window = activity.getWindow();
            if (window == null) return;
            Session session = new Session(activity);
            sessions.put(activity, session);
            session.start();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "watch", failure);
        }
    }

    static void release(Activity activity) {
        try {
            Session session = sessions.remove(activity);
            if (session != null) session.stop();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "release", failure);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            track(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            release(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    /** One resumed activity, watched for Reels or Home coming and going. */
    static final class Session implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<Activity> activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable check = this::evaluate;
        private long lastCheck;
        private boolean queued;
        private boolean hidden;
        private int savedUi;
        private int savedBehavior;
        private boolean saved;
        private boolean stopped;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        void start() {
            Activity found = activity.get();
            if (found == null) return;
            ViewTreeObserver observer = found.getWindow().getDecorView().getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.addOnPreDrawListener(this);
            observer.addOnWindowFocusChangeListener(this);
            evaluate();
        }

        boolean isHidden() {
            return hidden;
        }

        void stop() {
            stopped = true;
            handler.removeCallbacks(check);
            Activity found = activity.get();
            if (found != null) {
                ViewTreeObserver observer = found.getWindow().getDecorView().getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                    observer.removeOnWindowFocusChangeListener(this);
                }
            }
            show();
        }

        @Override public boolean onPreDraw() {
            if (stopped || queued) return true;
            long since = SystemClock.uptimeMillis() - lastCheck;
            if (since >= CHECK_MS) {
                evaluate();
            } else {
                // A last change with no frame after it still gets looked at.
                queued = true;
                handler.postDelayed(check, CHECK_MS - since);
            }
            return true;
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            // A dialog or the keyboard can take the bars back; when the window is ours again, ask again.
            if (hasFocus && hidden) {
                hidden = false;
                evaluate();
            }
        }

        void evaluate() {
            queued = false;
            lastCheck = SystemClock.uptimeMillis();
            if (stopped) return;
            try {
                Activity found = activity.get();
                if (found == null || !Utils.settingsReady()) return;
                View decor = found.getWindow().getDecorView();
                boolean want = wanted(screenOf(decor));
                if (want && !hidden) hide(found.getWindow(), decor);
                else if (!want && hidden) show();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "check", failure);
            }
        }

        @SuppressWarnings("deprecation")
        private void hide(Window window, View decor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller == null) return;
                if (!saved) savedBehavior = controller.getSystemBarsBehavior();
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                if (!saved) savedUi = decor.getSystemUiVisibility();
                decor.setSystemUiVisibility(savedUi | IMMERSIVE_FLAGS);
            }
            saved = true;
            hidden = true;
            HookStatus.invoked(FamilyNames.FULL_SCREEN_BARS);
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_HID);
        }

        @SuppressWarnings("deprecation")
        void show() {
            if (!hidden && !saved) return;
            hidden = false;
            saved = false;
            try {
                Activity found = activity.get();
                if (found == null) return;
                Window window = found.getWindow();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = window.getInsetsController();
                    if (controller == null) return;
                    controller.show(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(savedBehavior);
                } else {
                    window.getDecorView().setSystemUiVisibility(savedUi);
                }
                HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_SHOWED);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "show", failure);
            }
        }
    }
}
