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
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Full screen Reels and Home" patch: hides the status bar and the navigation bar
 * while Reels (or Home) is showing, and brings them back when it isn't.
 *
 * <p>Instagram's code changes in one place: its window insets listener asks {@link #barHeight} about each
 * bar height it keeps, since it keeps them while the bars are hidden and its screens would leave
 * the space empty (#147). Like the glass tab bar, the rest watches the activities once the
 * application is up and looks views up by the names Instagram gives them, which are the same on all
 * seven 450 builds: {@code clips_viewer_container} (the reels viewer, in the Reels tab and in the
 * full screen viewer a reel opens from anywhere), and the {@code clips_tab} and {@code feed_tab}
 * children of {@code tab_bar}. The viewer counts as showing when most of the window is its own, which
 * keeps a viewer sitting off screen in a pager from counting. Your messages ({@code
 * inbox_refreshable_thread_list_recyclerview}) and a chat ({@code thread_view_root}) count from a
 * smaller share, so the keyboard doesn't end them (#288). Without either, the tab bar's selected tab
 * answers.
 *
 * <p>The bars are hidden through the window's insets controller (Android 11 and newer) with the
 * "show transient bars by swipe" behaviour, and on Android 9 and 10 through the immersive sticky
 * system UI flags. Either way a swipe from the edge shows them for a moment, the back gesture works
 * as before, and the keyboard opens and pushes the screen up as it always did, because only the bars
 * are asked to hide. When Reels is left, the activity pauses or the switch is turned off, only what
 * HushGram asked for is undone: the immersive flags it added (anything else Instagram set meanwhile
 * stays), or on Android 11 and newer every bar it hid, with the window's own swipe behaviour back.
 *
 * <p>The check runs from a draw listener at most every {@link #CHECK_MS}, and acts only when the
 * answer changes, so a swipe that brought the bars back for a moment isn't fought. With both switches
 * off and nothing hidden, a frame looks at nothing and leaves no check behind it, and a paused
 * HushGram watches no activity at all. Anything that throws leaves the bars as the system has them.
 */
public final class FullScreenBars {
    static final String TAB_BAR = "tab_bar";
    static final String REELS_TAB = "clips_tab";
    static final String HOME_TAB = "feed_tab";
    static final String SEARCH_TAB = "search_tab";
    static final String VIEWER = "clips_viewer_container";
    /** Your messages' list of chats, and a chat's whole screen, as Instagram names them (#288). */
    static final String INBOX = "inbox_refreshable_thread_list_recyclerview";
    static final String CHAT = "thread_view_root";

    /** The viewer counts as showing when this share of the window is its own. */
    static final float FILL_SHARE = 0.8f;
    /** Your messages or a chat count as showing from this share, so the keyboard taking half doesn't end it. */
    static final float MESSAGES_SHARE = 0.4f;
    /** The most often the screen is looked at for a change. */
    static final long CHECK_MS = 100;
    static final String COUNT_HID = "hid the bars";
    static final String COUNT_SHOWED = "showed the bars";
    static final String COUNT_NO_BAR_SPACE = "kept no space for a hidden bar";

    @SuppressWarnings("deprecation")
    static final int IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private static final java.util.HashMap<String, Integer> IDS = new java.util.HashMap<>();
    private static volatile boolean registered;
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The heights above 0 Instagram's listener last offered for each bar. Main thread only. */
    private static int statusOffered;
    private static int navOffered;
    /** Whether Instagram's copy of each bar's height holds a 0 HushGram answered (#196). */
    private static boolean statusTaken;
    private static boolean navTaken;
    /** The status bar's answer of the listener pass in progress, -1 before one. */
    private static int statusAnswer = -1;
    /** The pair of heights the listener last kept, -1 before the first. */
    private static int keptStatus = -1;
    private static int keptNav = -1;
    /** How many of an activity's bar heights listeners are kept to be told a new pair (#200). */
    static final int ASKERS_KEPT = 16;
    static final String COUNT_TOLD = "screens told the bars' new heights";
    /**
     * Each activity's kept bar heights listeners, newest last. Held strongly, since Instagram lets a
     * listener go once it has called it, and each listener holds its screen, which holds the activity:
     * an activity's entry goes when it's destroyed. Main thread only.
     */
    private static final WeakHashMap<Activity, ArrayList<Object>> askers = new WeakHashMap<>();
    /** The activity last resumed, whose window Instagram's listener passes are for. */
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static Teller teller = FullScreenBars::tellHeights;
    static final String COUNT_KEYBOARD = "kept the navigation bar's space for the keyboard";
    private static Keyboard keyboard = FullScreenBars::keyboardInFront;

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
    enum Screen { REELS, HOME, MESSAGES, OTHER }

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

    /** Whether a view [FILL_SHARE] of the window is on screen: its visible rectangle against the window's. */
    static boolean fills(View view, View decor) {
        return covers(view, decor, FILL_SHARE);
    }

    private static boolean covers(View view, View decor, float share) {
        if (!view.isShown()) return false;
        Rect seen = new Rect();
        if (!view.getGlobalVisibleRect(seen)) return false;
        long window = (long) decor.getWidth() * decor.getHeight();
        return window > 0 && (long) seen.width() * seen.height() >= window * share;
    }

    /**
     * Whether your messages or a chat take [MESSAGES_SHARE] of the window (#288): in the Messages tab,
     * swiped to from Home, or a chat in its own activity, the keyboard up or not.
     */
    static boolean messagesShowing(View decor) {
        return anyCovers(decor, CHAT) || anyCovers(decor, INBOX);
    }

    /** Whether a view named [name] covers [MESSAGES_SHARE] of [decor]; a hidden one left behind doesn't hide another. */
    private static boolean anyCovers(View decor, String name) {
        int id = id(decor.getContext(), name);
        if (id == 0) return false;
        View first = decor.findViewById(id);
        if (first == null) return false;
        return covers(first, decor, MESSAGES_SHARE) || anyCovers(decor, id, decor);
    }

    private static boolean anyCovers(View view, int id, View decor) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view.getId() == id) return covers(view, decor, MESSAGES_SHARE);
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (anyCovers(group.getChildAt(i), id, decor)) return true;
        }
        return false;
    }

    /**
     * Whether [bar] sits half the window or more to the side: Home's panel swiped over to the story
     * camera or Add to story keeps its tab bar shown and Home selected, only moved off screen. Up and
     * down don't count, since the glass bar slides away on scroll and Home is still Home.
     */
    static boolean sidewaysOff(View bar, View decor) {
        int[] at = new int[2], origin = new int[2];
        bar.getLocationOnScreen(at);
        decor.getLocationOnScreen(origin);
        return Math.abs(at[0] - origin[0]) * 2 >= decor.getWidth();
    }

    /** The tab Instagram marks selected in its tab bar, as the name of its id, or null. */
    @Nullable
    private static String selectedTab(View decor) {
        View bar = find(decor, TAB_BAR);
        if (!(bar instanceof ViewGroup) || !bar.isShown() || sidewaysOff(bar, decor)) return null;
        ViewGroup tabs = (ViewGroup) bar;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE || !tab.isSelected()) continue;
            if (tab.getId() != View.NO_ID && tab.getId() == id(decor.getContext(), REELS_TAB)) return REELS_TAB;
            if (tab.getId() != View.NO_ID && tab.getId() == id(decor.getContext(), HOME_TAB)) return HOME_TAB;
            if (tab.getId() != View.NO_ID && tab.getId() == id(decor.getContext(), SEARCH_TAB)) return SEARCH_TAB;
            return null;
        }
        return null;
    }

    /** Whether [decor] has Explore's tab selected with no reel filling the window over it (#297). */
    static boolean exploreSelected(View decor) {
        View viewer = find(decor, VIEWER);
        if (viewer != null && fills(viewer, decor)) return false;
        return SEARCH_TAB.equals(selectedTab(decor));
    }

    /**
     * Where the tab bar sits in [decor] at rest, so a slide away on scroll doesn't move it, or null
     * while there's no tab bar on screen (#297).
     */
    @Nullable
    static Rect tabBarRest(View decor) {
        View bar = find(decor, TAB_BAR);
        if (bar == null || !bar.isShown() || sidewaysOff(bar, decor)) return null;
        int[] at = new int[2], origin = new int[2];
        bar.getLocationOnScreen(at);
        decor.getLocationOnScreen(origin);
        int left = at[0] - origin[0];
        int top = at[1] - origin[1] - Math.round(bar.getTranslationY());
        if (top <= 0 || top >= decor.getHeight()) return null;
        return new Rect(left, top, left + bar.getWidth(), Math.min(decor.getHeight(), top + bar.getHeight()));
    }

    /**
     * What [decor]'s screen is: the reels viewer filling the window, your messages or a chat, or the
     * Reels or Home tab selected.
     */
    static Screen screenOf(View decor) {
        View viewer = find(decor, VIEWER);
        if (viewer != null && fills(viewer, decor)) return Screen.REELS;
        if (messagesShowing(decor)) return Screen.MESSAGES;
        String tab = selectedTab(decor);
        // A viewer there but not showing: Home, or a screen over Reels, whatever tab is selected.
        if (viewer == null && REELS_TAB.equals(tab)) return Screen.REELS;
        return HOME_TAB.equals(tab) ? Screen.HOME : Screen.OTHER;
    }

    /** Whether the bars should be hidden on [screen], by the three switches. */
    static boolean wanted(Screen screen) {
        if (screen == Screen.REELS) return Settings.FULL_SCREEN_REELS.get();
        if (screen == Screen.MESSAGES) return Settings.FULL_SCREEN_MESSAGES.get();
        return screen == Screen.HOME && Settings.FULL_SCREEN_HOME.get();
    }

    /** Whether any of the three switches is on, so some screen may want the bars hidden. */
    static boolean eitherOn() {
        return Settings.FULL_SCREEN_REELS.get() || Settings.FULL_SCREEN_HOME.get()
                || Settings.FULL_SCREEN_MESSAGES.get();
    }

    /**
     * Whether screens' bar heights listeners are kept and told each new pair: with either switch on,
     * or with Glass tab bar's pill floating. The pill has the navigation bar's height answered 0 while
     * it shows, so a screen built just then, like a reel's comments or a chat opened over it, heard 0
     * and put its text box under the navigation bar, and Instagram never told it the real height (#209).
     */
    static boolean keepsListeners() {
        return eitherOn() || (Settings.GLASS_TAB_BAR.get() && Settings.GLASS_TAB_BAR_FLOAT.get());
    }

    /** Tests give the names ids of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    /** [now]'s flags without the immersive ones HushGram added to [before]; the rest stay as they are. */
    static int withoutAdded(int now, int before) {
        return withoutAdded(now, before, IMMERSIVE_FLAGS);
    }

    /** [now]'s flags without those of [added] that [before] didn't have. */
    static int withoutAdded(int now, int before, int added) {
        return now & ~(added & ~before);
    }

    /** The bars the Bars to hide choice picks, both when it reads nothing (#231). */
    static HiddenBars chosenBars() {
        HiddenBars bars = Settings.FULL_SCREEN_HIDES.get();
        return bars == null ? HiddenBars.BOTH : bars;
    }

    /** The immersive flags that hide [bars] on Android 9 and 10. */
    @SuppressWarnings("deprecation")
    static int immersiveFlags(HiddenBars bars) {
        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (bars.status()) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (bars.navigation()) flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        return flags;
    }

    /** The bar types a hide asks for on Android 11 and newer: [bars], or every system bar for both. */
    @RequiresApi(Build.VERSION_CODES.R)
    static int barsToHide(HiddenBars bars) {
        if (bars == HiddenBars.STATUS_BAR) return WindowInsets.Type.statusBars();
        if (bars == HiddenBars.NAVIGATION_BAR) return WindowInsets.Type.navigationBars();
        return WindowInsets.Type.systemBars();
    }

    /**
     * The bars a show asks for on Android 11 and newer: every one the hide took away. Asking the
     * insets which bars were showing doesn't work, since they lag a show by a frame or more, and a
     * hide right after one read nothing showing, so the bars never came back (S22, Android 16).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    static int barsToShow() {
        return WindowInsets.Type.systemBars();
    }

    /**
     * Called by Instagram's window insets listener with each bar height it's about to keep for its
     * screens, the status bar's and then the navigation bar's (#147). Instagram holds on to a bar's
     * height while the bar is hidden, so Reels and Home kept an empty strip where each bar was, black
     * in dark mode. While HushGram has the bars hidden the answer is 0 and the screen is Instagram's
     * to the edges. Otherwise, or if anything throws, the height goes through as it came.
     */
    public static int barHeight(int height) {
        int kept = hiddenBarHeight(height, true);
        if (height > 0) statusOffered = height;
        statusTaken = taken(height, kept, statusTaken);
        statusAnswer = kept;
        return kept;
    }

    /** [height], or 0 while HushGram has the status bar ([status]) or the navigation bar hidden. */
    private static int hiddenBarHeight(int height, boolean status) {
        try {
            if (height <= 0 || !anyHidden(status)) return height;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_NO_BAR_SPACE);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "bar height", failure);
            return height;
        }
    }

    /**
     * Called by Instagram's window insets listener with the navigation bar's height it's about to
     * keep for its screens. First as {@link #barHeight}, then, while a floating glass tab bar shows in
     * the front window, 0 as well: Instagram pads its screens by this height, which left a strip of its
     * own color under the pill around the gesture handle (#144), so the screens run to the bottom edge
     * and the pill keeps itself above the handle by the height it was given
     * ({@link GlassTabBar#navBarHeight}). If anything throws, the height goes through as it came.
     */
    public static int navBarHeight(int height) {
        // While the bar is hidden Instagram offers back the 0 it kept, so the keyboard coming up in a
        // chat that opened with the bars hidden had no height to let through (#288): the height
        // Instagram last offered stands in for it.
        int offered = height == 0 && navOffered > 0 ? navOffered : height;
        boolean keys = offered > 0 && keyboardUp();
        if (!keys) offered = height;
        int kept = keys ? offered : hiddenBarHeight(height, false);
        try {
            kept = keys ? GlassTabBar.navBarHeightOverTheKeyboard(kept) : GlassTabBar.navBarHeight(kept, hiddenNavHeight(height));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "navigation bar height", failure);
        }
        if (height > 0) navOffered = height;
        navTaken = taken(offered, kept, navTaken);
        heightsKept(statusAnswer, kept);
        return kept;
    }

    /**
     * The height of the navigation bar while HushGram hides it: the one Instagram offers, or for the 0 it
     * offers back while the bar is hidden, the one it last offered. 0 while the bar shows or for an unset
     * [height]. A floating glass pill keeps that much room under itself ({@link GlassTabBar#navBarHeight(int, int)}),
     * so it stays at the height it has where the bar shows rather than dropping on Reels (#285).
     */
    static int hiddenNavHeight(int height) {
        try {
            int bar = height > 0 ? height : height == 0 ? navOffered : 0;
            return bar > 0 && anyHidden(false) ? bar : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "hidden navigation bar height", failure);
            return 0;
        }
    }

    /**
     * Whether the keyboard shows in the front window. It brings the navigation bar back with it, hidden
     * or under a pill, and Instagram lifts a comment box or a chat's message box above the keyboard by
     * the keyboard's height less this bar's, then pads it by the height it kept. A 0 kept the box a
     * bar's height low, behind the keys (#209), so while the keyboard shows the height goes through,
     * unless a sheet over the glass pill makes that room itself ({@link GlassTabBar#navBarHeightOverTheKeyboard}).
     */
    static boolean keyboardUp() {
        try {
            if (!keyboard.shown()) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_KEYBOARD);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "keyboard", failure);
            return false;
        }
    }

    private static boolean keyboardInFront() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        Activity activity = resumed.get();
        if (activity == null) activity = GlassTabBar.activityInFront();
        Window window = activity == null ? null : activity.getWindow();
        WindowInsets insets = window == null ? null : window.getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    /** How the keyboard is looked for: the front window's insets outside tests. */
    interface Keyboard {
        boolean shown();
    }

    /**
     * Called by Instagram's registration of a screen's bar heights listener in [activity], before it
     * runs (#200). Instagram calls a listener once with the heights it keeps and then lets most of them
     * go, so a screen built before the bars were hidden, like the Reels viewer on a cold start into
     * Reels, kept the old heights as black strips until it resumed. While a full screen switch is on,
     * or the glass pill floats ({@link #keepsListeners}), the last {@link #ASKERS_KEPT} listeners of
     * each activity are kept and told each new pair ({@link #heightsKept}). Otherwise nothing is kept.
     */
    public static void heightsAsked(Activity activity, Object listener) {
        try {
            if (activity == null || listener == null || !registered || !Utils.settingsReady()
                    || HushgramPause.isPaused() || !keepsListeners()) return;
            ArrayList<Object> held = askers.get(activity);
            if (held == null) {
                held = new ArrayList<>();
                askers.put(activity, held);
            }
            forgetListener(held, listener);
            held.add(listener);
            while (held.size() > ASKERS_KEPT) held.remove(0);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "heights listener", failure);
        }
    }

    /** Called by Instagram's removal of a screen's bar heights listener in [activity]: it isn't told again. */
    public static void heightsDropped(Activity activity, Object listener) {
        try {
            ArrayList<Object> held = activity == null ? null : askers.get(activity);
            if (held != null) forgetListener(held, listener);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "heights listener", failure);
        }
    }

    private static void forgetListener(ArrayList<Object> held, Object listener) {
        for (int at = held.size() - 1; at >= 0; at--) {
            if (held.get(at) == listener) held.remove(at);
        }
    }

    /**
     * The pair of heights Instagram's listener just kept. When it changed and listeners are being kept
     * ({@link #keepsListeners}), the resumed activity's kept listeners are told it on the next turn of
     * the main loop, once Instagram's pass is over, with whatever pair is kept by then.
     */
    static void heightsKept(int status, int nav) {
        try {
            int wasStatus = keptStatus;
            int wasNav = keptNav;
            boolean changed = status != keptStatus || nav != keptNav;
            keptStatus = status;
            keptNav = nav;
            if (!changed || !registered || !keepsListeners()) return;
            Activity activity = resumed.get();
            ArrayList<Object> held = activity == null ? null : askers.get(activity);
            if (held == null || held.isEmpty()) return;
            List<Object> listeners = new ArrayList<>(held);
            // Telling a screen that shows a video can stop its frames (#222), so each tell is named (#244).
            Logger.printDebug(() -> "Full screen bars: telling " + listeners.size() + " screens in "
                    + activity.getClass().getSimpleName() + " the bars' heights " + status + "/" + nav
                    + " (were " + wasStatus + "/" + wasNav + "): " + names(listeners));
            new Handler(Looper.getMainLooper()).post(() -> tellAll(listeners));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "heights kept", failure);
        }
    }

    /** The listeners' class names without the package, like 0CAb, the screens a debug log can tell apart. */
    static String names(List<Object> listeners) {
        StringBuilder names = new StringBuilder();
        for (Object listener : listeners) {
            if (names.length() > 0) names.append(", ");
            String name = listener.getClass().getName();
            names.append(name.substring(name.lastIndexOf('.') + 1));
        }
        return names.toString();
    }

    /**
     * Tells each of [listeners] the kept pair of heights, then has a floating glass pill give its Reels
     * viewers their margin for that pair in the same pass, so the screens and the viewers in them move
     * in one layout ({@link GlassTabBar#heightsTold}, #222).
     */
    private static void tellAll(List<Object> listeners) {
        for (Object listener : listeners) {
            try {
                teller.tell(listener, keptStatus, keptNav);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "heights told", failure);
            }
        }
        GlassTabBar.heightsTold();
        HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_TOLD);
    }

    /** How a kept listener is told a pair of heights: the patch-filled stub outside tests. */
    interface Teller {
        void tell(Object listener, int status, int nav);
    }

    /**
     * Whether Instagram's copy of a bar's height holds HushGram's 0 after it offered [offered] and
     * was answered [kept]. An offer of 0 is Instagram handing back what it kept while the bar is
     * hidden, so whatever was true [before] still is.
     */
    static boolean taken(int offered, int kept, boolean before) {
        return offered > 0 ? kept != offered : before;
    }

    /**
     * Called as an activity pauses. Instagram's screens read the bar heights its listener kept when
     * they're built, and a screen in the next activity (a chat opens in its own) is built before that
     * activity's window hands the listener its insets. So the 0 the Reels viewer or a floating glass
     * pill was given stayed: a chat's message box sat a navigation bar's height too low, under the
     * buttons and under the keyboard (#196). Each bar answered 0 gets the height Instagram last offered
     * for it back, written where its listener writes it; the activity coming back asks again.
     */
    public static void giveBackBarHeights() {
        try {
            int[] heights = heightsToGiveBack();
            if (heights[0] > 0) {
                keepStatusBarHeight(heights[0]);
                keptStatus = heights[0];
            }
            if (heights[1] > 0) {
                keepNavBarHeight(heights[1]);
                keptNav = heights[1];
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "bar heights back", failure);
        }
    }

    /**
     * Called as a session hides the bars. Coming back to Reels from another window (HushGram's
     * settings, a chat), Instagram's copy holds the real heights the pause gave back, and its listener
     * keeps them while the bars are hidden, so the viewer kept a strip at the top and the bottom (#200).
     * Each height of a bar in [bars] not already 0 goes to 0 here, and the activity's kept listeners hear it.
     */
    static void takeHiddenHeights(HiddenBars bars) {
        try {
            boolean took = false;
            if (bars.status() && !statusTaken && statusOffered > 0) {
                keepStatusBarHeight(0);
                statusTaken = true;
                took = true;
            }
            if (bars.navigation() && !navTaken && navOffered > 0) {
                keepNavBarHeight(0);
                navTaken = true;
                took = true;
            }
            if (took) heightsKept(statusTaken ? 0 : statusOffered, navTaken ? 0 : navOffered);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "bar heights hidden", failure);
        }
    }

    /**
     * Called as a session shows the bars again for another screen in the same window. A reel opened
     * from its author's profile plays in the profile's window, and Back leaves it for the profile there.
     * The hide wrote 0 into Instagram's copy ({@link #takeHiddenHeights}) and no pass of its listener
     * came after the show, so the profile kept no room for the status bar and its name sat under the
     * clock (#385). Each height answered 0 that no window still hides gets the height Instagram last
     * offered back, the navigation bar's as a floating glass pill would have it, and the activity's
     * kept listeners hear it. A pass that comes after brings the same pair, so nothing is told twice.
     */
    static void giveBackShownHeights() {
        try {
            boolean gave = false;
            if (statusTaken && statusOffered > 0 && !anyHidden(true)) {
                keepStatusBarHeight(statusOffered);
                statusTaken = false;
                statusAnswer = statusOffered;
                gave = true;
            }
            if (navTaken && navOffered > 0 && !anyHidden(false)) {
                int answer = GlassTabBar.navBarHeight(navOffered, 0);
                if (answer > 0) {
                    keepNavBarHeight(answer);
                    navTaken = false;
                    gave = true;
                }
            }
            if (gave) heightsKept(statusTaken ? 0 : statusOffered, navTaken ? 0 : navOffered);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "bar heights shown", failure);
        }
    }

    /**
     * Called when the keyboard comes up ([shows]) or goes in a window with the navigation bar hidden
     * ([bars]). Instagram lifts a chat's message box above the keyboard by the keyboard's height less
     * the navigation bar's, and in a chat that opened with the bars hidden nothing told its screens
     * the bar's height again as the keyboard came up, so the box sat a bar's height behind the keys
     * (#288). While the keyboard shows, Instagram's copy gets the height back, unless a sheet over the
     * glass pill makes that room itself ({@link GlassTabBar#navBarHeightOverTheKeyboard}), and the kept
     * screens hear it. When the keyboard goes, the hidden bar takes it again.
     */
    static void keyboardMoved(boolean shows, HiddenBars bars) {
        try {
            if (!shows) {
                takeHiddenHeights(bars);
                return;
            }
            if (!navTaken || navOffered <= 0) return;
            int answer = GlassTabBar.navBarHeightOverTheKeyboard(navOffered);
            if (answer <= 0) return;
            keepNavBarHeight(answer);
            navTaken = false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_KEYBOARD);
            heightsKept(statusTaken ? 0 : statusOffered, answer);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "keyboard heights", failure);
        }
    }

    /**
     * Called as an activity with a floating glass pill showing resumes, before its screens do. The
     * window it came back from (a chat) stored the navigation bar's real height, and a screen resuming
     * under the pill (the inbox) read that before the pill's insets came round again, so it kept a
     * strip under the pill (#196). Instagram's copy goes back to the pill's 0 at once. The screens
     * resuming read that 0, so it's kept as the pair's: the pass that brings it next isn't a change to
     * tell them. Told it anyway, the Reels viewer back from a reel's profile laid out again and its
     * video stopped on the cover (#378).
     */
    public static void takeNavBarHeight() {
        try {
            if (navTaken || navOffered <= 0) return;
            keepNavBarHeight(0);
            navTaken = true;
            keptNav = 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "navigation bar height again", failure);
        }
    }

    /** The status bar's and the navigation bar's heights owed back, 0 for one not answered 0. Owed once. */
    static int[] heightsToGiveBack() {
        int[] heights = {statusTaken ? statusOffered : 0, navTaken ? navOffered : 0};
        statusTaken = false;
        navTaken = false;
        return heights;
    }

    /** Tests start with nothing offered, taken, kept or held, and tell their own [tells]. */
    static void resetHeightsForTests(@Nullable Teller tells) {
        statusOffered = 0;
        navOffered = 0;
        statusTaken = false;
        navTaken = false;
        statusAnswer = -1;
        keptStatus = -1;
        keptNav = -1;
        askers.clear();
        teller = tells == null ? FullScreenBars::tellHeights : tells;
        keyboard = FullScreenBars::keyboardInFront;
    }

    /** Tests say whether the keyboard shows. */
    static void keyboardForTests(Keyboard shows) {
        keyboard = shows;
    }

    static void nowResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
    }

    /** Tests stand in for the application start, which registers the watcher. */
    static void registeredForTests(boolean value) {
        registered = value;
    }

    /** The listeners held for [activity], for tests. */
    static List<Object> askersForTests(Activity activity) {
        ArrayList<Object> held = askers.get(activity);
        return held == null ? new ArrayList<>() : new ArrayList<>(held);
    }

    /**
     * Whether a watched activity has the status bar ([status]) or the navigation bar hidden. Main
     * thread, as Instagram's listener is.
     */
    static boolean anyHidden(boolean status) {
        for (Session session : sessions.values()) {
            if (session != null && session.hides(status)) return true;
        }
        return false;
    }

    @Nullable
    static Session sessionFor(Activity activity) {
        return sessions.get(activity);
    }

    static void track(Activity activity) {
        try {
            // Paused, both switches read off for the whole process, so no screen would ever hide a bar.
            if (!Utils.settingsReady() || HushgramPause.isPaused() || sessions.containsKey(activity)) return;
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
            nowResumed(activity);
            track(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            release(activity);
            giveBackBarHeights();
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            // Its listeners hold it, so the weak key alone would never let it go.
            askers.remove(activity);
        }
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
        private HiddenBars bars = HiddenBars.BOTH;
        private int savedUi;
        private int savedBehavior;
        private boolean saved;
        private boolean stopped;
        /** Whether the keyboard showed at the last frame with the navigation bar hidden (#288). */
        private boolean keys;

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

        /** Whether this activity has the status bar ([status]) or the navigation bar hidden. */
        boolean hides(boolean status) {
            return hidden && (status ? bars.status() : bars.navigation());
        }

        /** Whether a look is waiting on the main thread for a quiet moment after the last frame, for tests. */
        boolean checkQueued() {
            return queued;
        }

        void stop() {
            stopped = true;
            handler.removeCallbacks(check);
            queued = false;
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
            if (stopped) return true;
            watchKeyboard();
            if (queued) return true;
            // Both switches off and nothing hidden: nothing to look for, and no look left waiting
            // after the frame. A switch turned on is seen at the next frame.
            if (!hidden && !eitherOn()) return true;
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

        /** Each frame: the keyboard coming or going while this window has the navigation bar hidden. */
        void watchKeyboard() {
            boolean shows = false;
            try {
                shows = hidden && bars.navigation() && keyboard.shown();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "keyboard", failure);
            }
            if (shows == keys) return;
            keys = shows;
            if (hidden) keyboardMoved(shows, bars);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            // A dialog or the keyboard can take the bars back; when the window is ours again, ask again:
            // hide them once more if this screen still wants it, or undo the hide if the dialog led
            // somewhere else (the window still asks for hidden bars until something says otherwise).
            if (hasFocus && hidden) {
                hidden = false;
                evaluate();
                if (!hidden) {
                    show();
                    giveBackShownHeights();
                }
            }
        }

        void evaluate() {
            queued = false;
            lastCheck = SystemClock.uptimeMillis();
            if (stopped) return;
            try {
                Activity found = activity.get();
                if (found == null || !Utils.settingsReady() || (!hidden && !eitherOn())) return;
                View decor = found.getWindow().getDecorView();
                boolean want = wanted(screenOf(decor));
                // A Bars to hide choice changed while hidden takes over at the next look.
                if (want && (!hidden || bars != chosenBars())) hide(found.getWindow(), decor);
                else if (!want && hidden) {
                    show();
                    giveBackShownHeights();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "check", failure);
            }
        }

        @SuppressWarnings("deprecation")
        private void hide(Window window, View decor) {
            HiddenBars chosen = chosenBars();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller == null) return;
                if (!saved) savedBehavior = controller.getSystemBarsBehavior();
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                // A bar the earlier choice hid comes back before the new choice hides its own.
                if (saved && bars != chosen) controller.show(barsToShow());
                controller.hide(barsToHide(chosen));
            } else {
                int current = decor.getSystemUiVisibility();
                if (!saved) savedUi = current;
                else current = withoutAdded(current, savedUi, immersiveFlags(bars));
                decor.setSystemUiVisibility(current | immersiveFlags(chosen));
            }
            bars = chosen;
            saved = true;
            hidden = true;
            takeHiddenHeights(chosen);
            // A keyboard already up gets the height back again at the next frame.
            keys = false;
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
                    controller.show(barsToShow());
                    controller.setSystemBarsBehavior(savedBehavior);
                } else {
                    View decor = window.getDecorView();
                    decor.setSystemUiVisibility(withoutAdded(decor.getSystemUiVisibility(), savedUi, immersiveFlags(bars)));
                }
                HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_SHOWED);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "show", failure);
            }
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: stores [height] where Instagram's listener keeps the status bar's height. */
    public static void keepStatusBarHeight(int height) {
    }

    /** Filled in by the patch: stores [height] where Instagram's listener keeps the navigation bar's height. */
    public static void keepNavBarHeight(int height) {
    }

    /** Filled in by the patch: tells [listener], one of Instagram's bar heights listeners, a pair of heights. */
    public static void tellHeights(Object listener, int status, int nav) {
    }
}
