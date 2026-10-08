/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Dialog;
import android.view.View;
import android.view.animation.Animation;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask before a refresh" patch: a question before pulling down reloads Home, the
 * inbox, Reels or any other list built on one of Instagram's two pull-down refresh layouts, while
 * the switch is on.
 *
 * <p>Home, the inbox and most lists on 450 pull with Instagram's nested-scrolling layout. Once a
 * pull passes the point where it refreshes, the layout reads its refresh listener and calls it. The
 * patch hands that listener to {@link #pull} right after the read. While the question is up it
 * answers null, so Instagram's own null check skips the refresh and the spinner stays where the
 * pull left it. Refresh calls the listener through {@link #refresh}.
 *
 * <p>The Stories tab, and Reels on some of its server setups, pull with Instagram's fork of
 * AndroidX's SwipeRefreshLayout instead. Once its spinner settles after a pull, its end-of-pull
 * animation reads the layout's refresh listener and calls it. The patch hands that one to
 * {@link #listener}, which answers the same way, and Refresh runs the end of the pull again, which
 * this time gets the listener back.
 *
 * <p>On either layout, Cancel, Back or a tap outside stop the spinner through the layout's own
 * setRefreshing, which {@link #spinnerOff} calls, and leave what's on screen. A second pull while
 * the question is up doesn't put up a second one.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet, no screen
 * to ask on or anything thrown, the refresh goes ahead as it always did.
 */
public final class RefreshConfirm {
    /** The steps a failure is reported under. */
    static final String ASK = "ask before a refresh";
    static final String SPINNER = "refresh spinner";

    /** What's counted each time a pull waits for the question. */
    static final String ASKED = "asked before a refresh";

    /** Set while a pull the person said yes to ends again, so the hook lets it through. Main thread only. */
    private static boolean replaying;

    /**
     * The question each refresh layout has up, until it's gone. The question holds its activity,
     * which holds the layout, so it's held weakly here too, or the layout would never be let go.
     */
    private static final Map<View, WeakReference<Dialog>> OPEN = new WeakHashMap<>();

    private RefreshConfirm() {
    }

    /**
     * Injected where Instagram's nested-scrolling refresh layout [layout] reads its refresh listener
     * once a pull has gone far enough, right before it checks and calls it. Answers the listener, or
     * null while the question is up. Never throws.
     */
    @Nullable
    public static Object pull(View layout, @Nullable Object listener) {
        return ask(layout, listener, () -> refreshNow(listener));
    }

    /**
     * Injected where a SwipeRefreshLayout's end-of-pull animation [end] reads [layout]'s refresh
     * listener, right before it checks and calls it. Answers the listener, or null while the question
     * is up. Never throws.
     */
    @Nullable
    public static Object listener(View layout, @Nullable Object listener, Animation.AnimationListener end) {
        return ask(layout, listener, () -> endAgain(end));
    }

    /** Puts the question up over [layout], unless one is up already, and answers null while it's up. */
    @Nullable
    private static Object ask(View layout, @Nullable Object listener, Runnable refresh) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_REFRESH);
            if (listener == null || replaying || !Utils.settingsReady() || !Settings.ASK_BEFORE_REFRESH.get()) {
                return listener;
            }
            if (ConfirmDialog.up(open(layout), layout.getContext())) return null;
            Dialog question = ConfirmDialog.ask(layout.getContext(), L10n.t("Refresh this list?"), L10n.t("Refresh"),
                    refresh, () -> stopSpinner(layout), gone -> forget(layout, gone));
            if (question == null) return listener;
            OPEN.put(layout, new WeakReference<>(question));
            HookStatus.counted(FamilyNames.ASK_BEFORE_REFRESH, ASKED);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_REFRESH, ASK, failure);
            return listener;
        }
    }

    /** Refresh on the nested-scrolling layout: calls the listener the pull would have called. */
    private static void refreshNow(Object listener) {
        try {
            refresh(listener);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_REFRESH, ASK, failure);
        }
    }

    /** Refresh: ends the pull again, and this time the hook hands the listener back. */
    private static void endAgain(Animation.AnimationListener end) {
        replaying = true;
        try {
            end.onAnimationEnd(null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_REFRESH, ASK, failure);
        } finally {
            replaying = false;
        }
    }

    /** The question [layout] has up, or null. */
    @Nullable
    private static Dialog open(View layout) {
        WeakReference<Dialog> held = OPEN.get(layout);
        return held == null ? null : held.get();
    }

    /** Lets go of [gone], [layout]'s question, once it's gone, unless a newer one has taken its place. */
    private static void forget(View layout, Dialog gone) {
        Dialog open = open(layout);
        if (open == null || open == gone) OPEN.remove(layout);
    }

    /** Whether a question of [layout]'s is still held, for the tests. */
    static boolean holds(View layout) {
        return OPEN.containsKey(layout);
    }

    /** Stops a refresh layout's spinner. */
    static void stopSpinner(View layout) {
        try {
            spinnerOff(layout);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_REFRESH, SPINNER, failure);
        }
    }

    /**
     * Filled in by the patch with a direct call of [layout]'s public setRefreshing(false), on
     * whichever of the two refresh layouts it is, which the patch proves have one. Unpatched, it
     * finds the method by name.
     */
    static void spinnerOff(View layout) throws ReflectiveOperationException {
        layout.getClass().getMethod("setRefreshing", boolean.class).invoke(layout, false);
    }

    /**
     * Filled in by the patch with a direct call of the method the nested-scrolling layout calls on
     * [listener] to refresh. Unpatched, it calls the one method of the listener's interface that
     * takes nothing.
     */
    static void refresh(Object listener) throws ReflectiveOperationException {
        for (Class<?> type : listener.getClass().getInterfaces()) {
            Method[] methods = type.getMethods();
            if (methods.length == 1 && methods[0].getParameterCount() == 0) {
                methods[0].invoke(listener);
                return;
            }
        }
        throw new NoSuchMethodException(listener.getClass().getName() + " has no refresh method");
    }
}
