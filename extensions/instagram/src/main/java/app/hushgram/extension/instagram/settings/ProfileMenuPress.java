/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Opens HushGram from a long press on the menu button, the three lines at the top right of your own
 * profile (#84), with {@link Settings#PROFILE_MENU_LONG_PRESS} on.
 *
 * <p>Nothing in Instagram's code changes. Instagram 450 keeps its view ids' names, so the button is
 * found by them: your own profile's top bar has the Create New button ({@link #CREATE_BUTTON}),
 * which other people's profiles don't, and the bar's right-hand group ({@link #RIGHT_BUTTONS}) ends
 * with the menu. Only that last button counts, and only when it's a shown, clickable image. The bar
 * builds its buttons again now and then, so each Instagram screen is looked at after its layouts,
 * at most every {@link #LOOK_EVERY_MS}, while the switch is on.
 *
 * <p>Instagram gives the menu button a tap and no long press. A button that already has a long
 * press, Instagram's or anyone's, is left alone, so none is ever taken. With the switch off,
 * HushGram paused or the settings not read yet, a button given a long press loses it again, and a
 * press that comes in before that hands the button back: Android then treats it as a tap, the way
 * it does a long hold on a button with no long press, and Instagram's menu opens.
 */
public final class ProfileMenuPress {
    /** Instagram's name for the Create New button at the top left of your own profile. */
    static final String CREATE_BUTTON = "profile_header_create_button";
    /** Instagram's name for the group of buttons at the right of a profile's top bar. */
    static final String RIGHT_BUTTONS = "right_action_bar_buttons";
    /** How often a screen is looked at, at most. The last layout of a burst gets one more look. */
    static final long LOOK_EVERY_MS = 600;
    /** How far up from the Create New button the bar holding the right-hand group can be. */
    private static final int CLIMB = 8;

    private static volatile boolean registered;
    /** The Instagram screen in front, if any. Main thread only. */
    private static WeakReference<Activity> resumed;
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final WeakHashMap<Activity, Watch> watched = new WeakHashMap<>();
    /** The menu buttons that carry HushGram's long press. Neither side keeps a view alive. */
    private static final WeakHashMap<View, Press> bound = new WeakHashMap<>();
    /** The Create New button last found, so a look doesn't walk the whole screen each time. */
    private static WeakReference<View> create;

    private ProfileMenuPress() {
    }

    /** Called as the application starts. Watches each Instagram screen as it comes to the front. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application)) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable t) {
            Logger.printException(() -> "Profile menu long press: could not watch activities", t);
        }
    }

    /**
     * The switch changed. The screen in front takes the change at once: on, its menu button gets
     * the long press; off, every button that has it gives it back. Main thread. Never throws.
     */
    static void applyChoice() {
        try {
            if (!on()) release();
            Activity activity = resumed == null ? null : resumed.get();
            if (activity == null) return;
            watch(activity);
            look(activity);
        } catch (Throwable t) {
            Logger.printException(() -> "Profile menu long press: could not apply the switch", t);
        }
    }

    /**
     * Whether no screen needs watching: HushGram is paused for this run (the switch reads off until
     * the next start), or the switch is saved off.
     */
    private static boolean nothingToWatch() {
        return HushgramPause.isPaused()
                || (Utils.settingsReady() && !Settings.PROFILE_MENU_LONG_PRESS.savedValue());
    }

    /** Whether a long press on the menu opens HushGram right now. */
    static boolean on() {
        return Utils.settingsReady() && Settings.PROFILE_MENU_LONG_PRESS.get();
    }

    /**
     * Looks at [activity]'s screen after its layouts. With the switch saved off, or HushGram paused
     * for this run, there's nothing to look for. Before the settings are read it can't be told, so
     * the screen is watched and the first look that knows stops watching.
     */
    static void watch(Activity activity) {
        if (nothingToWatch()) return;
        Watch watch;
        synchronized (watched) {
            if (watched.containsKey(activity)) return;
            watch = new Watch(activity);
            watched.put(activity, watch);
        }
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(watch);
    }

    /** Gives the menu button on [activity]'s screen the long press, or takes it back. Never throws. */
    static void look(Activity activity) {
        try {
            if (!on()) {
                release();
                return;
            }
            View menu = menuButton(activity.getWindow().getDecorView());
            if (menu != null) bind(menu);
        } catch (Throwable t) {
            Logger.printException(() -> "Profile menu long press: could not look at the profile", t);
        }
    }

    /**
     * Your own profile's menu button on [decor]'s screen, or null: no profile of yours shown, or the
     * last shown button of its top bar's right-hand group isn't a clickable image.
     */
    static View menuButton(View decor) {
        Context context = decor.getContext();
        int createId = id(context, CREATE_BUTTON);
        int rightId = id(context, RIGHT_BUTTONS);
        if (createId == 0 || rightId == 0) return null;
        View plus = rememberedCreate(decor, createId);
        if (plus == null || !plus.isShown()) return null;
        ViewGroup right = null;
        View at = plus;
        for (int depth = 0; depth < CLIMB && right == null; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return null;
            at = (View) parent;
            View found = at.findViewById(rightId);
            if (found instanceof ViewGroup) right = (ViewGroup) found;
        }
        if (right == null || !right.isShown()) return null;
        for (int i = right.getChildCount() - 1; i >= 0; i--) {
            View child = right.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            return child instanceof ImageView && child.isClickable() && child.isEnabled() ? child : null;
        }
        return null;
    }

    private static View rememberedCreate(View decor, int createId) {
        WeakReference<View> held = create;
        View view = held == null ? null : held.get();
        if (view != null && view.getId() == createId && view.isAttachedToWindow() && view.getRootView() == decor) {
            return view;
        }
        view = decor.findViewById(createId);
        create = view == null ? null : new WeakReference<>(view);
        return view;
    }

    /** The number of the id called [name] in [context]'s app, or 0 when there isn't one. Looked up once. */
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

    /** Gives [menu] the long press, unless it has one already or one of its own. */
    static void bind(View menu) {
        synchronized (bound) {
            if (bound.containsKey(menu)) return;
        }
        // Something already answers a long press here. It isn't HushGram's to take.
        if (menu.isLongClickable()) return;
        Press press = new Press();
        menu.setOnLongClickListener(press);
        synchronized (bound) {
            bound.put(menu, press);
        }
    }

    /** Every button given the long press goes back to how Instagram made it: no long press. */
    static void release() {
        List<View> views;
        synchronized (bound) {
            if (bound.isEmpty()) return;
            views = new ArrayList<>(bound.keySet());
            bound.clear();
        }
        for (View view : views) {
            if (view == null) continue;
            view.setOnLongClickListener(null);
            view.setLongClickable(false);
        }
    }

    /**
     * Whether a long press on your profile's menu opens HushGram on this account: the switch is on and a
     * menu button carries the long press. Off, paused, or no button found yet (or one with a long press of
     * its own), it's false, so Instagram's menu keeps the HushGram row and there's still a way in (#148).
     */
    static boolean opensHushGram() {
        if (!on()) return false;
        synchronized (bound) {
            for (View view : bound.keySet()) {
                if (view != null) return true;
            }
        }
        return false;
    }

    /** Whether [view] carries HushGram's long press right now. */
    static boolean carries(View view) {
        synchronized (bound) {
            return view != null && bound.containsKey(view);
        }
    }

    /** Tests start with no buttons, no screens and no ids looked up; another test's views stay in its window. */
    static void forgetForTests() {
        synchronized (bound) {
            bound.clear();
        }
        synchronized (watched) {
            watched.clear();
        }
        synchronized (IDS) {
            IDS.clear();
        }
        create = null;
        resumed = null;
    }

    /** Names Instagram's ids for a test, whose app has none of them. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    static void resumedForTests(Activity activity) {
        resumed = activity == null ? null : new WeakReference<>(activity);
    }

    private static final class Press implements View.OnLongClickListener {
        @Override public boolean onLongClick(View view) {
            // Taken back, or a retained or programmatic press on a button that's gone: not ours.
            synchronized (bound) {
                if (bound.get(view) != this) return false;
            }
            if (!on()) return false;
            Activity owner = NavigationSettings.ownerOf(view);
            return owner != null && SettingsEntry.requestOpen(owner);
        }
    }

    /** One screen's look after its layouts, at most every {@link #LOOK_EVERY_MS}. */
    private static final class Watch implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> owner;
        private long last;
        private boolean pending;

        Watch(Activity activity) {
            owner = new WeakReference<>(activity);
        }

        @Override public void onGlobalLayout() {
            Activity activity = owner.get();
            if (activity == null) return;
            if (nothingToWatch()) {
                stop(activity);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - last < LOOK_EVERY_MS) {
                if (!pending) {
                    pending = true;
                    activity.getWindow().getDecorView().postDelayed(() -> {
                        pending = false;
                        last = SystemClock.uptimeMillis();
                        Activity later = owner.get();
                        if (later != null) look(later);
                    }, LOOK_EVERY_MS);
                }
                return;
            }
            last = now;
            look(activity);
        }

        /** The switch is off: the buttons go back to Instagram and this screen isn't watched. */
        private void stop(Activity activity) {
            release();
            synchronized (watched) {
                if (watched.get(activity) == this) watched.remove(activity);
            }
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            resumed = new WeakReference<>(activity);
            try {
                watch(activity);
            } catch (Throwable t) {
                Logger.printException(() -> "Profile menu long press: could not watch a screen", t);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            if (resumed != null && resumed.get() == activity) resumed = null;
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
