/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Window;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * The way back to the viewing history after its Open (#865). Open shows a post or a profile on
 * Instagram's own screen and closes the history and the settings so it shows, and Instagram has
 * nothing of its own to go back to from there: a post lands on Home, where Back only refreshes
 * Home, and a profile goes back to whatever was under the settings. So the next Back on that
 * screen opens the history again instead, on the tab and search it had, at the row it was on and
 * big again if it was. A link Instagram shows on a screen of its own over that one, as it does a
 * reel, keeps that screen's own Back, and the history comes back as that screen closes. The
 * screens a link only passes through show for a moment and close, and Instagram 457 names the
 * reel's own screen (ClipsUrlHandlerActivity) like them, so which screen is the link's goes by how
 * long it stayed up, never by its name.
 *
 * <p>It's spent once: the Back after that is Instagram's, and the history or the settings opening
 * some other way spends it too. Below Android 16 Back comes as a key through the screen's window
 * callback, wrapped here once per window; where Instagram takes Back through callbacks, a callback
 * over Instagram's own takes it.
 */
public final class ViewingHistoryReturn {
    /** How soon after Open a screen of Instagram's that comes up is the link's own. */
    static final long LINK_SCREEN_MS = 10_000;
    /**
     * How long a screen stays up before it counts as the link's own. The ones a link only passes
     * through are up for a few milliseconds; nobody opens a reel and leaves it this quickly.
     */
    static final long SHOWN_MS = 800;

    /** Where the history was as Open left it. */
    static final class Spot {
        /** The settings page the history sat over, "" for the top of the list, or null for none. */
        @Nullable final String settingsPage;
        /** The tab, null for All. */
        @Nullable final Kind tab;
        final String search;
        final Kind rowKind;
        final String rowId;
        /** Where the row was in the list, for when it's gone from it since. */
        final int position;
        /** Whether the row was shown big. */
        final boolean big;

        Spot(@Nullable String settingsPage, @Nullable Kind tab, String search, Kind rowKind, String rowId,
             int position, boolean big) {
            this.settingsPage = settingsPage;
            this.tab = tab;
            this.search = search;
            this.rowKind = rowKind;
            this.rowId = rowId;
            this.position = position;
            this.big = big;
        }
    }

    /** The windows already wrapped. Main thread only, as is everything here. */
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    @Nullable private static Spot spot;
    /** The screen Open was tapped over, where the link lands or that the link's own screen covers. */
    @Nullable private static WeakReference<Activity> host;
    /** The newest screen that came up over [host] since Open, the link's own once it stayed up. */
    @Nullable private static WeakReference<Activity> linkScreen;
    /** When [linkScreen] last came to the front, and how long it was up before that. */
    private static long linkUpSince;
    private static long linkUpFor;
    private static long armedAt;
    /** The callback over Instagram's, from Android 13. */
    @Nullable private static Object callback;
    /** The app the screens are followed in. */
    @Nullable private static WeakReference<Application> followed;

    private ViewingHistoryReturn() {
    }

    /** After Open over [activity]: the next Back there, or the link's own screen closing, opens [where] again. */
    static void arm(Activity activity, Spot where) {
        disarm();
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            wrap(window, activity);
            follow(activity.getApplication());
            spot = where;
            host = new WeakReference<>(activity);
            armedAt = SystemClock.elapsedRealtime();
            if (Build.VERSION.SDK_INT >= 33) callback = BackCallback.register(activity);
            Logger.printInfo(() -> "Viewing history: Back after Open comes back to it");
        } catch (Throwable failure) {
            disarm();
            Logger.printException(() -> "Viewing history: couldn't keep the way back after Open", failure);
        }
    }

    /** Spends it, so Back is Instagram's again. */
    static void disarm() {
        spot = null;
        host = null;
        linkScreen = null;
        linkUpFor = 0;
        Object registered = callback;
        callback = null;
        if (Build.VERSION.SDK_INT >= 33 && registered instanceof BackCallback) ((BackCallback) registered).unregister();
    }

    /** Whether [activity]'s next Back opens the history again. */
    static boolean armedOn(@Nullable Activity activity) {
        WeakReference<Activity> on = host;
        return spot != null && activity != null && on != null && on.get() == activity;
    }

    /** Back on [activity]: the history again, once. Whether it was armed there. */
    @SuppressWarnings("deprecation") // onBackPressed hands Back to Instagram's own handling.
    static boolean back(Activity activity) {
        Spot where = spot;
        if (where == null || !armedOn(activity)) return false;
        disarm();
        if (activity.isFinishing() || activity.isDestroyed()) return true;
        try {
            Logger.printInfo(() -> "Viewing history: Back after Open, showing it again");
            ViewingHistoryPage.reopen(activity, where);
        } catch (Throwable failure) {
            Logger.printException(() -> "Viewing history: couldn't come back after Open, Back is Instagram's", failure);
            activity.onBackPressed();
        }
        return true;
    }

    /** The callback [callback] passes calls on to when it's this wrapper, else [callback]. */
    public static Window.Callback innerOf(Window.Callback callback) {
        if (callback != null && Proxy.isProxyClass(callback.getClass())
                && Proxy.getInvocationHandler(callback) instanceof Watcher) {
            return ((Watcher) Proxy.getInvocationHandler(callback)).inner;
        }
        return callback;
    }

    private static void wrap(Window window, Activity activity) {
        if (WRAPPED.containsKey(window)) return;
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        window.setCallback((Window.Callback) Proxy.newProxyInstance(ViewingHistoryReturn.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, new Watcher(inner, activity)));
        WRAPPED.put(window, Boolean.TRUE);
    }

    private static void follow(Application app) {
        WeakReference<Application> seen = followed;
        if (app == null || (seen != null && seen.get() == app)) return;
        app.registerActivityLifecycleCallbacks(new Screens());
        followed = new WeakReference<>(app);
    }

    /**
     * Passes every call on to the callback it wraps. While armed for its screen it takes the Back
     * key, both halves, and a Back let go of opens the history.
     */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;
        private final WeakReference<Activity> activity;
        /** A Back pressed while armed, so its release is taken too. */
        private boolean pressed;

        Watcher(Window.Callback inner, Activity activity) {
            this.inner = inner;
            this.activity = new WeakReference<>(activity);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof KeyEvent
                    && "dispatchKeyEvent".equals(method.getName())) {
                try {
                    if (took((KeyEvent) args[0])) return Boolean.TRUE;
                } catch (Throwable failure) {
                    pressed = false;
                    Logger.printException(() -> "Viewing history: Back after Open failed", failure);
                }
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }

        private boolean took(KeyEvent key) {
            if (key.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
            Activity owner = activity.get();
            if (key.getAction() == KeyEvent.ACTION_DOWN) {
                if (!pressed && !armedOn(owner)) return false;
                pressed = true;
                return true;
            }
            if (key.getAction() != KeyEvent.ACTION_UP || !pressed) return false;
            pressed = false;
            if (!key.isCanceled() && owner != null) back(owner);
            return true;
        }
    }

    /** Follows the screens while armed: the link's own screen, and its closing. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity resumed) {
            try {
                WeakReference<Activity> on = host;
                Activity armedOn = on == null ? null : on.get();
                if (spot == null || armedOn == null) return;
                long now = SystemClock.elapsedRealtime();
                WeakReference<Activity> over = linkScreen;
                Activity link = over == null ? null : over.get();
                if (resumed != armedOn) {
                    if (resumed == link) {
                        linkUpSince = now;
                    } else if (!resumed.isFinishing() && now - armedAt <= LINK_SCREEN_MS
                            && (link == null || gone(link) || linkUpFor < SHOWN_MS)) {
                        // The first screen up since Open, or a newer one over a screen that only passed the link on.
                        linkScreen = new WeakReference<>(resumed);
                        linkUpSince = now;
                        linkUpFor = 0;
                        Logger.printDebug(() -> "Viewing history: " + resumed.getClass().getSimpleName()
                                + " came up after Open");
                    }
                    return;
                }
                if (over == null || (link != null && !gone(link))) return;
                linkScreen = null;
                if (linkUpFor < SHOWN_MS) {
                    // It only passed the link on, and the link landed here.
                    Logger.printDebug(() -> "Viewing history: the link passed through a screen and landed under it");
                    return;
                }
                // The link's own screen closed, by its own Back: the history shows once this screen has.
                Logger.printInfo(() -> "Viewing history: the screen Open showed closed");
                Utils.runOnMainThread(() -> back(resumed));
            } catch (Throwable failure) {
                Logger.printException(() -> "Viewing history: couldn't follow the screen Open showed", failure);
            }
        }

        @Override public void onActivityDestroyed(Activity gone) {
            WeakReference<Activity> on = host;
            if (on != null && on.get() == gone) disarm();
        }

        @Override public void onActivityPaused(Activity paused) {
            WeakReference<Activity> over = linkScreen;
            if (over != null && over.get() == paused) linkUpFor += SystemClock.elapsedRealtime() - linkUpSince;
        }

        private static boolean gone(Activity activity) {
            return activity.isFinishing() || activity.isDestroyed();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }

    /** Back where Instagram takes it through callbacks, over Instagram's own while armed. */
    @RequiresApi(33)
    static final class BackCallback implements OnBackInvokedCallback {
        private final WeakReference<Activity> activity;
        private final OnBackInvokedDispatcher dispatcher;

        private BackCallback(Activity activity, OnBackInvokedDispatcher dispatcher) {
            this.activity = new WeakReference<>(activity);
            this.dispatcher = dispatcher;
        }

        static BackCallback register(Activity activity) {
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            BackCallback callback = new BackCallback(activity, dispatcher);
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            return callback;
        }

        @SuppressWarnings("deprecation") // onBackPressed hands Back to Instagram's own handling.
        @Override public void onBackInvoked() {
            Activity owner = activity.get();
            if (owner != null && back(owner)) return;
            unregister();
            if (owner != null) owner.onBackPressed();
        }

        void unregister() {
            try {
                dispatcher.unregisterOnBackInvokedCallback(this);
            } catch (RuntimeException gone) {
                Logger.printDebug(() -> "Viewing history: the Back callback was already gone");
            }
        }
    }
}
