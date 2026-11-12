/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Clean up Reels switch that hides the top bar of the Reels tab (#14): the row with
 * the Reels title, the camera button and whatever else Instagram puts up there.
 *
 * <p>It changes no Instagram code. Like Full screen Reels, it watches the activities once the
 * application is up. The bar is Instagram's {@code ClipsViewerActionBar}, a class whose name the
 * build keeps, with the view id {@code clips_viewer_action_bar}; both are the same on all seven 450
 * builds, and either one finds it. The bar keeps where its viewer was opened from in its config
 * objects, each holding one {@code ClipsViewerSource}, an enum that keeps its names. Only a bar
 * whose viewer is the Reels tab's own ({@link #REELS_TAB}) is hidden, so a reel opened from a
 * profile, a post or a chat keeps its bar and its back button, and the camera stays a swipe or the
 * plus button away on Home. A bar whose source can't be read is left alone.
 *
 * <p>The bar is made invisible, not gone: it keeps its size and place, so the reel under it is laid
 * out as before, and Android sends a touch where the bar was to the reel, since an invisible view
 * takes no touches. Instagram shows and hides the bar itself at times; one it shows again is made
 * invisible again before the next frame draws.
 *
 * <p>Two more switches take one button out of the bar and leave the rest (#177, #217). The bar
 * tags each icon button with its kind, an enum whose constant names the build keeps, and the
 * Create (+) button's is {@link #CREATE_KIND}; it goes from every viewer's bar, not just the Reels
 * tab's. Your algorithm is a view of its own class, {@link #YOUR_ALGORITHM_CLASS}. A button goes
 * gone, so the row it sits in closes up around it. Instagram's own bind only pads a button and
 * gives it its taps, and a list of buttons that changes is built again with new views, which the
 * next search finds.
 *
 * <p>A fourth takes the Friends tab off the Reels tab's top bar (#300), the lane of reels the people
 * you follow shared. Its title comes from a string pack and its tab has no id or tag, so the Clean up
 * Reels patch hands each Friends tab's view to {@link #friendsTab} as Instagram builds it, where the
 * tab gets its facepile icon. It goes gone like a button, and the Reels title is left alone in the
 * strip. A sideways swipe on a reel doesn't change lanes, so the tab is the only way there.
 *
 * <p>No frame or layout listener runs for nothing. With the switch off, HushGram paused or the
 * settings not read yet, an activity gets none, and a window focus listener, which only hears focus
 * changes, notices the switch turned on once the settings screen closes. While on, a pre-draw
 * listener keeps the hidden bars hidden, and the screen is searched for new bars only after a
 * layout, at most every {@link #LOOK_MS}. Turning the switch off takes both listeners away at the
 * next frame and shows each bar HushGram hid, if nothing else changed it since. An activity that
 * pauses takes its listeners off, and one that goes away takes its bars with it. Anything thrown
 * leaves the bar as Instagram has it.
 */
public final class ReelsTopBar {
    /** The id Instagram gives the bar, the same on all seven 450 builds. */
    static final String BAR_ID = "clips_viewer_action_bar";
    /** The bar's class, whose name Instagram's build keeps. */
    static final String BAR_CLASS = "instagram.features.clips.viewer.actionbar.ClipsViewerActionBar";
    /** Where a viewer was opened from, an enum whose class and constant names Instagram's build keeps. */
    static final String SOURCE_CLASS = "com.instagram.clips.intf.ClipsViewerSource";

    /** The kind the bar tags its Create (+) button with, which shows a plus or a camera and opens the camera. */
    static final String CREATE_KIND = "CAMERA";
    /** The Your algorithm button's class, whose name Instagram's build keeps. */
    static final String YOUR_ALGORITHM_CLASS =
            "instagram.features.clips.viewer.feature.actionbar.feature.youralgo.ui.YourAlgoEntryPointView";

    /** The sources of the Reels tab's own viewer, as Instagram 450 names them. */
    static final Set<String> REELS_TAB = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CLIPS_TAB", "CLIPS_TAB_HOMECOMING")));

    /** The most often the screen is searched for new bars, after a layout. */
    static final long LOOK_MS = 100;
    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 4000;
    /** How many more searches a bar whose viewer isn't known yet gets before it's left as it is. */
    static final int UNKNOWN_TRIES = 20;

    static final String COUNT_HID = "Reels tab top bar hidden";
    static final String COUNT_HID_AGAIN = "Reels tab top bar hidden again after Instagram showed it";
    static final String COUNT_PUT_BACK = "Reels tab top bar put back";
    static final String COUNT_CREATE_HID = "Reels top bar Create button hidden";
    static final String COUNT_YOUR_ALGORITHM_HID = "Reels top bar Your algorithm button hidden";
    static final String COUNT_BUTTON_HID_AGAIN = "Reels top bar button hidden again after Instagram showed it";
    static final String COUNT_BUTTON_PUT_BACK = "Reels top bar button put back";
    static final String COUNT_FRIENDS_SEEN = "Reels Friends tab built";
    static final String COUNT_FRIENDS_HID = "Reels Friends tab hidden";
    /** The most views looked at inside one bar for its buttons. */
    static final int MAX_BAR_VIEWS = 200;

    /** What a bar's config says about where its viewer was opened from. */
    enum Viewer { REELS_TAB, ELSEWHERE, UNKNOWN }

    private static final java.util.HashMap<String, Integer> IDS = new java.util.HashMap<>();
    private static volatile boolean registered;
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The source names the debug log has named, each once a run. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    /** For each class looked at as a bar, its fields leading to a source, looked up once. */
    private static final WeakHashMap<Class<?>, Field[][]> SOURCE_FIELDS = new WeakHashMap<>();

    /** The Friends tabs' views Instagram built, held weakly so a closed viewer's can go. */
    private static final Set<View> FRIENDS_TABS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private ReelsTopBar() {
    }

    /**
     * Called by the patch as Instagram builds the Friends tab at the top of Reels, with the tab's
     * view. Only marks it: the search hides it while the switch is on, so turning it off or pausing
     * puts it back.
     */
    public static void friendsTab(View tab) {
        try {
            if (tab == null) return;
            FRIENDS_TABS.add(tab);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_FRIENDS_SEEN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "Friends tab", failure);
        }
    }

    /** Whether [view] is a Friends tab Instagram built, as the patch handed it over. */
    static boolean isFriendsTab(View view) {
        return FRIENDS_TABS.contains(view);
    }

    /** Called as the application starts. Does nothing in a build without Clean up Reels. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelDeclutter()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar start", failure);
        }
    }

    /** Whether anything in a top bar is hidden now: one of the switches on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && (Settings.HIDE_REELS_TOP_BAR.get()
                || Settings.HIDE_REELS_CREATE_BUTTON.get() || Settings.HIDE_REELS_YOUR_ALGORITHM.get()
                || Settings.HIDE_REELS_FRIENDS_TAB.get());
    }

    /** Whether [view], inside a bar, is its Create (+) button, by the kind the bar tags it with. */
    static boolean isCreateButton(View view) {
        Object tag = view.getTag();
        return tag instanceof Enum && CREATE_KIND.equals(((Enum<?>) tag).name());
    }

    /** Whether [view], inside a bar, is its Your algorithm button, by the class name Instagram's build keeps. */
    static boolean isYourAlgorithmButton(View view) {
        return YOUR_ALGORITHM_CLASS.equals(view.getClass().getName());
    }

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

    /** Tests give the bar an id of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    /** Whether [view] is the bar's class, by the name Instagram's build keeps. */
    static boolean isBarClass(View view) {
        return BAR_CLASS.equals(view.getClass().getName());
    }

    /**
     * Where [bar]'s viewer was opened from: the Reels tab when every source its config objects hold
     * is one of {@link #REELS_TAB}, elsewhere when one isn't, and unknown when it holds none yet (the
     * bar binds its config after it's inflated) or isn't the bar at all.
     */
    static Viewer viewerOf(View bar) {
        Field[][] fields = sourceFieldsOf(bar.getClass());
        boolean any = false;
        for (Field[] path : fields) {
            Object value;
            try {
                value = path[0].get(bar);
                if (value != null && path.length > 1) value = path[1].get(value);
            } catch (IllegalAccessException | RuntimeException unreadable) {
                continue;
            }
            if (!(value instanceof Enum)) continue;
            String name = ((Enum<?>) value).name();
            if (LOGGED.add(name)) {
                boolean tab = REELS_TAB.contains(name);
                Logger.printDebug(() -> "Reels top bar: " + (tab ? "hides" : "keeps") + " the bar of a " + name + " viewer");
            }
            if (!REELS_TAB.contains(name)) return Viewer.ELSEWHERE;
            any = true;
        }
        return any ? Viewer.REELS_TAB : Viewer.UNKNOWN;
    }

    /**
     * The fields of [type] that lead to a {@link #SOURCE_CLASS}: one of its own of that type, or one
     * whose declared type holds one of that type. Each path is the field, then the inner field if
     * there is one. Looked up once for the bar's class.
     */
    static Field[][] sourceFieldsOf(Class<?> type) {
        synchronized (SOURCE_FIELDS) {
            Field[][] cached = SOURCE_FIELDS.get(type);
            if (cached != null) return cached;
        }
        List<Field[]> found = new ArrayList<>();
        for (Class<?> at = type; at != null && !isPlatform(at); at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    if (isSource(field.getType())) {
                        field.setAccessible(true);
                        found.add(new Field[] {field});
                        continue;
                    }
                    if (isPlatform(field.getType()) || field.getType().isArray() || field.getType().isInterface()) continue;
                    for (Field inner : field.getType().getDeclaredFields()) {
                        if (Modifier.isStatic(inner.getModifiers()) || !isSource(inner.getType())) continue;
                        field.setAccessible(true);
                        inner.setAccessible(true);
                        found.add(new Field[] {field, inner});
                    }
                } catch (Throwable unreadable) {
                    // A field whose type can't be read leads to no source; the others still might.
                }
            }
        }
        Field[][] fields = found.toArray(new Field[0][]);
        synchronized (SOURCE_FIELDS) {
            SOURCE_FIELDS.put(type, fields);
        }
        return fields;
    }

    private static boolean isSource(Class<?> type) {
        return SOURCE_CLASS.equals(type.getName());
    }

    /** Android's and Java's own classes, which hold no Instagram config. */
    private static boolean isPlatform(Class<?> type) {
        String name = type.getName();
        return name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("androidx.") || name.startsWith("kotlin.");
    }

    @Nullable
    static Session sessionFor(Activity activity) {
        return sessions.get(activity);
    }

    /** As [activity] resumes: watches it while the switch is on, or puts back what it hid while off. */
    static void track(Activity activity) {
        try {
            Session session = sessions.get(activity);
            // Paused the switch reads off for the whole process, so nothing would ever be hidden:
            // no listener, and anything hidden before goes back.
            if (!Utils.settingsReady() || HushgramPause.isPaused() || activity.getWindow() == null) {
                if (session != null) session.restore();
                return;
            }
            if (session == null) {
                session = new Session(activity);
                sessions.put(activity, session);
            }
            session.start();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar watch", failure);
        }
    }

    /** As [activity] pauses: takes its listeners off. Its bars stay as they are until it resumes. */
    static void release(Activity activity) {
        try {
            Session session = sessions.get(activity);
            if (session != null) session.stop();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar release", failure);
        }
    }

    /** As [activity] goes away: forgets it, and its bars with it. */
    static void forget(Activity activity) {
        try {
            Session session = sessions.remove(activity);
            if (session != null) session.stop();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar release", failure);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            track(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            release(activity);
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            forget(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    }

    /** A bar or a button HushGram hid, with the switch that hid it and how. */
    private static final class Held {
        /** Held weakly so a closed viewer's can go. */
        final WeakReference<View> view;
        final BooleanSetting setting;
        /** Invisible for the bar, which keeps its place, and gone for a button, whose row closes up. */
        final int as;

        Held(View view, BooleanSetting setting, int as) {
            this.view = new WeakReference<>(view);
            this.setting = setting;
            this.as = as;
        }

        boolean bar() {
            return as == View.INVISIBLE;
        }

        /** Shows [shown] again, if it's still the way HushGram left it. */
        void putBack(View shown) {
            if (shown.getVisibility() != as) return;
            shown.setVisibility(View.VISIBLE);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, bar() ? COUNT_PUT_BACK : COUNT_BUTTON_PUT_BACK);
        }
    }

    /** One activity's window: the bars and buttons HushGram hid in it, and its listeners while it's resumed. Main thread only. */
    static final class Session implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<Activity> activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable look = this::lookNow;
        private final List<Held> hidden = new ArrayList<>();
        /** Bars seen before their viewer was known, with the searches they have left. */
        private final WeakHashMap<View, Integer> unknown = new WeakHashMap<>();
        private boolean started;
        private boolean watching;
        private boolean queued;
        private boolean dirty;
        private long lastLook = Long.MIN_VALUE / 2;
        /** How many searches ran, so tests can see when they stop. */
        int looks;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        @Nullable
        private ViewTreeObserver observer() {
            Activity found = activity.get();
            if (found == null || found.getWindow() == null) return null;
            ViewTreeObserver observer = found.getWindow().getDecorView().getViewTreeObserver();
            return observer.isAlive() ? observer : null;
        }

        /** Whether the frame and layout listeners are on, for tests. */
        boolean isWatching() {
            return watching;
        }

        /** Whether a search waits on the main thread for {@link #LOOK_MS} to pass, for tests. */
        boolean lookQueued() {
            return queued;
        }

        /** How many bars and buttons this window has hidden right now, for tests. */
        int hiddenCount() {
            int count = 0;
            for (Held held : hidden) if (held.view.get() != null) count++;
            return count;
        }

        void start() {
            if (!started) {
                ViewTreeObserver observer = observer();
                if (observer == null) return;
                observer.addOnWindowFocusChangeListener(this);
                started = true;
            }
            refresh();
        }

        void stop() {
            unwatch();
            ViewTreeObserver observer = observer();
            if (observer != null) observer.removeOnWindowFocusChangeListener(this);
            started = false;
        }

        /** Takes every listener off and shows each bar HushGram hid. */
        void restore() {
            stop();
            putBack();
        }

        /** Watches the window while a switch is on, or puts everything back and watches nothing while all are off. */
        void refresh() {
            if (!started) return;
            if (wanted()) {
                watch();
                keepHidden();
                dirty = true;
                lookNow();
            } else {
                unwatch();
                putBack();
            }
        }

        private void watch() {
            if (watching) return;
            ViewTreeObserver observer = observer();
            if (observer == null) return;
            observer.addOnPreDrawListener(this);
            observer.addOnGlobalLayoutListener(this);
            watching = true;
        }

        private void unwatch() {
            handler.removeCallbacks(look);
            queued = false;
            if (!watching) return;
            watching = false;
            ViewTreeObserver observer = observer();
            if (observer == null) return;
            observer.removeOnPreDrawListener(this);
            observer.removeOnGlobalLayoutListener(this);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            // The settings screen is a dialog over the activity, so its window giving focus back is
            // when a switch turned on or off there is seen.
            if (!hasFocus) return;
            try {
                refresh();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar check", failure);
            }
        }

        @Override public void onGlobalLayout() {
            // A new bar only ever comes with a layout: inflated, attached, or its screen shown again.
            dirty = true;
        }

        @Override public boolean onPreDraw() {
            try {
                if (!watching) return true;
                if (!wanted()) {
                    unwatch();
                    putBack();
                    return true;
                }
                keepHidden();
                if (dirty && !queued) {
                    long since = SystemClock.uptimeMillis() - lastLook;
                    if (since >= LOOK_MS) {
                        lookNow();
                    } else {
                        queued = true;
                        handler.postDelayed(look, LOOK_MS - since);
                    }
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar check", failure);
            }
            return true;
        }

        /**
         * Puts back what a switch turned off since no longer hides, and hides again what HushGram hid
         * and Instagram has shown again.
         */
        private void keepHidden() {
            for (int i = hidden.size() - 1; i >= 0; i--) {
                Held held = hidden.get(i);
                View view = held.view.get();
                if (view == null) {
                    hidden.remove(i);
                } else if (!held.setting.get()) {
                    held.putBack(view);
                    hidden.remove(i);
                } else if (view.getVisibility() == View.VISIBLE) {
                    view.setVisibility(held.as);
                    HookStatus.counted(FamilyNames.REEL_DECLUTTER, held.bar() ? COUNT_HID_AGAIN : COUNT_BUTTON_HID_AGAIN);
                }
            }
        }

        /** Shows each bar and button HushGram hid, if it's still the way HushGram left it. */
        private void putBack() {
            for (Held held : hidden) {
                View view = held.view.get();
                if (view != null) held.putBack(view);
            }
            hidden.clear();
            unknown.clear();
        }

        void lookNow() {
            queued = false;
            dirty = false;
            lastLook = SystemClock.uptimeMillis();
            if (!watching) return;
            try {
                Activity found = activity.get();
                if (found == null || found.getWindow() == null || !wanted()) return;
                looks++;
                search(found.getWindow().getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "top bar search", failure);
            }
        }

        /**
         * Walks [root], past what's gone, for bars. Hides each one of the Reels tab's that shows while
         * its switch is on, and otherwise the buttons in it whose switches are on. A hidden bar's
         * buttons go too, since Instagram can show it again with no layout to search after. A Friends
         * tab outside any bar Instagram keeps the names of goes as well.
         */
        private void search(View root) {
            int barId = id(root.getContext(), BAR_ID);
            boolean wholeBar = Settings.HIDE_REELS_TOP_BAR.get();
            boolean friends = Settings.HIDE_REELS_FRIENDS_TAB.get();
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(root);
            int seen = 0;
            while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
                View view = pending.poll();
                if (view.getVisibility() == View.GONE) continue;
                if (friends && isFriendsTab(view)) {
                    if (view.getVisibility() == View.VISIBLE) {
                        hide(view, Settings.HIDE_REELS_FRIENDS_TAB, View.GONE, COUNT_FRIENDS_HID);
                    }
                    continue;
                }
                boolean byClass = isBarClass(view);
                if (byClass || (barId != 0 && view.getId() == barId)) {
                    Viewer viewer = viewerOf(view);
                    if (viewer == Viewer.REELS_TAB && wholeBar) {
                        if (view.getVisibility() == View.VISIBLE) {
                            hide(view, Settings.HIDE_REELS_TOP_BAR, View.INVISIBLE, COUNT_HID);
                        }
                        continue;
                    }
                    if (byClass || viewer != Viewer.UNKNOWN) hideButtons(view);
                    if (viewer == Viewer.UNKNOWN && byClass && wholeBar) waitFor(view);
                    // The bar's own buttons hold no other bar; something else wearing its id might.
                    if (byClass || viewer != Viewer.UNKNOWN) continue;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                }
            }
        }

        /**
         * A bar not bound to its viewer yet: looks again after the next frame, a few times, since
         * binding it needs no layout. A bar that never binds is left as Instagram has it.
         */
        private void waitFor(View bar) {
            Integer left = unknown.get(bar);
            int tries = left == null ? UNKNOWN_TRIES : left - 1;
            unknown.put(bar, tries);
            if (tries > 0) dirty = true;
        }

        /**
         * Hides the buttons of [bar] whose switches are on, wherever its viewer was opened from, and
         * even in a bar or row hidden for now. A button Instagram hid itself is left to it.
         */
        private void hideButtons(View bar) {
            boolean create = Settings.HIDE_REELS_CREATE_BUTTON.get();
            boolean algorithm = Settings.HIDE_REELS_YOUR_ALGORITHM.get();
            boolean friends = Settings.HIDE_REELS_FRIENDS_TAB.get();
            if ((!create && !algorithm && !friends) || !(bar instanceof ViewGroup)) return;
            ArrayDeque<View> pending = new ArrayDeque<>();
            ViewGroup group = (ViewGroup) bar;
            for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            int seen = 0;
            while (!pending.isEmpty() && seen++ < MAX_BAR_VIEWS) {
                View view = pending.poll();
                if (view.getVisibility() == View.GONE) continue;
                boolean shown = view.getVisibility() == View.VISIBLE;
                if (create && isCreateButton(view)) {
                    if (shown) hide(view, Settings.HIDE_REELS_CREATE_BUTTON, View.GONE, COUNT_CREATE_HID);
                } else if (algorithm && isYourAlgorithmButton(view)) {
                    if (shown) hide(view, Settings.HIDE_REELS_YOUR_ALGORITHM, View.GONE, COUNT_YOUR_ALGORITHM_HID);
                } else if (friends && isFriendsTab(view)) {
                    if (shown) hide(view, Settings.HIDE_REELS_FRIENDS_TAB, View.GONE, COUNT_FRIENDS_HID);
                } else if (view instanceof ViewGroup) {
                    ViewGroup inner = (ViewGroup) view;
                    for (int i = 0; i < inner.getChildCount(); i++) pending.add(inner.getChildAt(i));
                }
            }
        }

        private void hide(View view, BooleanSetting setting, int as, String count) {
            unknown.remove(view);
            view.setVisibility(as);
            for (Held held : hidden) if (held.view.get() == view) return;
            hidden.add(new Held(view, setting, as));
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, count);
        }
    }
}
