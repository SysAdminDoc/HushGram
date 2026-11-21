/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the tab bar on a reel opened from a link (#385). A reel link from another app opens in a
 * screen of Instagram's own, put on top of its tab screen, and that screen has no tab bar. The reel
 * fills it to the bottom edge, so its seek bar sits where the phone's gesture bar is. With the switch
 * on, the reel plays on the tab you were on instead, the way a reel opened inside Instagram does,
 * and Back goes back to that tab.
 *
 * <p>Instagram hands the link to {@link #open} before it builds the viewer. When its tab screen is
 * right under the link's screen, the link's screen closes and the reel waits for the tab screen to
 * come back. When the link's screen is alone (Instagram wasn't open), the tab screen opens first.
 * The reel then goes in through the same call of Instagram's, given the tab screen and a back stack.
 * Anything else under the link's screen, such as a profile left open, keeps Instagram's way.
 *
 * <p>A link's viewer config says to hide the tab bar, since the link's screen has none. That flag is
 * cleared on the config the reel takes to the tab, so it plays over the tab bar like a reel opened
 * from Explore. The flag is final, so it's cleared by reflection, by the name the patch read off the
 * config's toString. When that fails the link opens Instagram's way.
 *
 * <p>On some accounts the tab host hides the bar for anything pushed on a tab, Explore's reels
 * included. The reel's arguments carry a mark, and {@link #keepTabRoot} answers that host's check:
 * the link's reel on top counts as the tab's root, the way a reel's profile does.
 */
public final class ReelLinkTabBar {
    private ReelLinkTabBar() {
    }

    /** Instagram's tab screen. Its launcher alias is named here too, in case a task reports that. */
    static final String TAB_SCREEN = "com.instagram.mainactivity.InstagramMainActivity";
    static final String TAB_SCREEN_ALIAS = "com.instagram.android.activity.MainTabActivity";
    /** The argument the link route reads to keep the reel on a back stack, so Back closes it alone. */
    static final String BACK_STACK = "InstagramUrlLaunchConstants.EXTRA_SHOULD_INCLUDE_BACK_STACK";
    static final String TAB_BAR = "tab_bar";
    /** The reel viewer's config, which a link's arguments carry. */
    static final String VIEWER_CONFIG = "com.instagram.clips.intf.ClipsViewerConfig";
    /** How long a reel waits for the tab screen. Longer than a cold start. */
    static final long WAIT_MS = 15_000;
    /** How many times the reel looks again for a tab bar on a tab screen that's still starting. */
    static final int LOOKS = 20;
    static final long LOOK_GAP_MS = 100;
    /** The mark on the arguments of a reel the link put on the tab. */
    static final String ON_TAB = "hushgram_reel_link_on_tab";
    static final String WHAT = "open a reel link on the tab";
    static final String WHAT_SHOW = "show a reel link on the tab";
    static final String WHAT_ROOT = "keep the tab bar on a reel link";

    /** What the link's screen has under it. */
    enum Under { TAB_SCREEN, NOTHING, OTHER }

    interface Added {
        /** The screens added to a tab [host], the last one on top. */
        @Nullable List<?> of(Object host);
    }

    interface Screens {
        /** What's under [link] in its task. */
        Under under(Activity link);

        /** Opens Instagram's tab screen from [link]. False when there's nothing to open. */
        boolean openTabs(Activity link);
    }

    interface Show {
        void on(Object arguments, Object activity, Object session);
    }

    interface Clock {
        long now();
    }

    private static final class Waiting {
        final Bundle arguments;
        final Object session;
        final Show show;
        final long since;

        Waiting(Bundle arguments, Object session, Show show, long since) {
            this.arguments = arguments;
            this.session = session;
            this.show = show;
            this.since = since;
        }
    }

    /** The reel waiting for the tab screen. Main thread only. */
    @Nullable private static Waiting waiting;
    private static boolean watching;
    private static boolean loggedRoot;
    static Clock clock = SystemClock::uptimeMillis;
    /** Another class tests count as the tab screen. */
    @Nullable static String tabScreenForTests;
    /** Another class tests count as the viewer config. */
    @Nullable static String viewerConfigForTests;

    /**
     * Called first thing where Instagram puts a reel link's viewer in [activity], with the viewer's
     * [arguments] and the [session]. True when the reel goes onto the tab instead, and Instagram's
     * own viewer mustn't open.
     */
    public static boolean open(Object arguments, Object activity, Object session) {
        return open(arguments, activity, session, new TaskScreens(), ReelLinkTabBarTargets::show,
                ReelLinkTabBarTargets.tabBarHiddenFlag());
    }

    static boolean open(Object arguments, Object activity, Object session, Screens screens, Show show,
                        @Nullable String hiddenFlag) {
        try {
            if (!Settings.REEL_LINK_TAB_BAR.get()) return false;
            if (!(arguments instanceof Bundle) || !(activity instanceof Activity) || hiddenFlag == null) return false;
            Activity link = (Activity) activity;
            if (isTabScreen(link.getClass().getName()) || link.isFinishing() || link.isDestroyed()) return false;
            Under under = screens.under(link);
            if (under == Under.OTHER) {
                Logger.printDebug(() -> "ReelLinkTabBar: another screen is under the link, so it opens Instagram's way");
                return false;
            }
            Bundle onTab = new Bundle((Bundle) arguments);
            onTab.putBoolean(BACK_STACK, true);
            onTab.putBoolean(ON_TAB, true);
            if (showTabBar(onTab, hiddenFlag) == 0) {
                Logger.printDebug(() -> "ReelLinkTabBar: the link has no viewer config, so it opens Instagram's way");
                return false;
            }
            watch(link.getApplication());
            waiting = new Waiting(onTab, session, show, clock.now());
            if (under == Under.NOTHING && !screens.openTabs(link)) {
                waiting = null;
                return false;
            }
            link.finish();
            Logger.printDebug(() -> "ReelLinkTabBar: the reel waits for the tab screen, " + under);
            return true;
        } catch (Throwable t) {
            waiting = null;
            HookStatus.threw(FamilyNames.REEL_LINK_TAB_BAR, WHAT, t);
            return false;
        }
    }

    /**
     * Clears [flag] on each viewer config in [arguments], so the viewer keeps the tab bar. Returns
     * how many it cleared. Throws when a config has no such boolean field.
     */
    @SuppressWarnings("deprecation")
    static int showTabBar(Bundle arguments, String flag) throws ReflectiveOperationException {
        int cleared = 0;
        for (String key : arguments.keySet()) {
            Object value = arguments.get(key);
            if (value == null || !isViewerConfig(value.getClass().getName())) continue;
            Field field = value.getClass().getDeclaredField(flag);
            if (field.getType() != boolean.class) throw new NoSuchFieldException(flag + " isn't a flag");
            field.setAccessible(true);
            field.setBoolean(value, false);
            cleared++;
        }
        return cleared;
    }

    /** Called with a tab host's answer to whether it's at its root, which decides on the tab bar. Non-zero is yes. */
    public static int keepTabRoot(int atRoot, Object host) {
        return keepTabRoot(atRoot, host, ReelLinkTabBarTargets::fragments);
    }

    static int keepTabRoot(int atRoot, Object host, Added screens) {
        if (atRoot != 0) return atRoot;
        try {
            if (!Utils.settingsReady() || !Settings.REEL_LINK_TAB_BAR.get()) return atRoot;
            List<?> added = screens.of(host);
            if (added == null || added.isEmpty() || !isLinkReel(added.get(added.size() - 1))) return atRoot;
            if (!loggedRoot) {
                loggedRoot = true;
                Logger.printDebug(() -> "ReelLinkTabBar: the tab bar stays on the link's reel");
            }
            return 1;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_LINK_TAB_BAR, WHAT_ROOT, t);
            return atRoot;
        }
    }

    /** Whether [screen] is a reel a link put on the tab, by the mark on its arguments. */
    static boolean isLinkReel(@Nullable Object screen) throws ReflectiveOperationException {
        if (screen == null) return false;
        Method arguments;
        try {
            arguments = screen.getClass().getMethod("getArguments");
        } catch (NoSuchMethodException notAScreen) {
            return false;
        }
        Object bundle = arguments.invoke(screen);
        return bundle instanceof Bundle && ((Bundle) bundle).getBoolean(ON_TAB, false);
    }

    static boolean isViewerConfig(String name) {
        return VIEWER_CONFIG.equals(name) || (viewerConfigForTests != null && viewerConfigForTests.equals(name));
    }

    static boolean isTabScreen(@Nullable String name) {
        return TAB_SCREEN.equals(name) || TAB_SCREEN_ALIAS.equals(name)
                || (tabScreenForTests != null && tabScreenForTests.equals(name));
    }

    /** What a task of [activities] screens with [base] at the bottom has under its top screen. */
    static Under under(int activities, @Nullable String base) {
        if (activities == 1) return Under.NOTHING;
        if (activities == 2 && isTabScreen(base)) return Under.TAB_SCREEN;
        return Under.OTHER;
    }

    /** Called as any of Instagram's screens comes to the front. The tab screen gets the waiting reel. */
    static void resumed(Activity activity) {
        if (waiting == null || !isTabScreen(activity.getClass().getName())) return;
        resumed(activity, activity.getResources().getIdentifier(TAB_BAR, "id", Utils.resourcePackage(activity)));
    }

    /** The tab screen [activity] is back, with [bar] the id of its tab bar. */
    static void resumed(Activity activity, int bar) {
        Waiting reel = waiting;
        if (reel == null || !isTabScreen(activity.getClass().getName())) return;
        waiting = null;
        if (clock.now() - reel.since > WAIT_MS) {
            Logger.printDebug(() -> "ReelLinkTabBar: the tab screen came back too late for the reel");
            return;
        }
        showWhenReady(activity, bar, reel, LOOKS);
    }

    /**
     * Shows [reel] on [activity] once it has its tab bar, a frame after it resumes so Instagram has
     * set up its tabs. A tab screen still starting gets a few more looks, and then the reel anyway.
     */
    private static void showWhenReady(Activity activity, int bar, Waiting reel, int looks) {
        View decor = activity.getWindow().getDecorView();
        decor.postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (looks > 0 && (bar == 0 || activity.findViewById(bar) == null)) {
                showWhenReady(activity, bar, reel, looks - 1);
                return;
            }
            try {
                reel.show.on(reel.arguments, activity, reel.session);
                Logger.printDebug(() -> "ReelLinkTabBar: the reel is on the tab");
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.REEL_LINK_TAB_BAR, WHAT_SHOW, t);
            }
        }, looks == LOOKS ? 0 : LOOK_GAP_MS);
    }

    private static void watch(@Nullable Application application) {
        if (watching || application == null) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = true;
    }

    /** Each test has an application of its own, so the watcher goes on again. */
    static void resetForTests() {
        waiting = null;
        watching = false;
        loggedRoot = false;
        clock = SystemClock::uptimeMillis;
        tabScreenForTests = null;
        viewerConfigForTests = null;
    }

    static boolean isWaitingForTests() {
        return waiting != null;
    }

    /** Reads the link's own task from Android's list of Instagram's tasks. */
    static final class TaskScreens implements Screens {
        @Override
        @SuppressWarnings("deprecation")
        public Under under(Activity link) {
            ActivityManager manager = (ActivityManager) link.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return Under.OTHER;
            int id = link.getTaskId();
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                int taskId = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
                if (taskId != id) continue;
                if (info.topActivity == null || !link.getClass().getName().equals(info.topActivity.getClassName())) {
                    return Under.OTHER;
                }
                return ReelLinkTabBar.under(info.numActivities, info.baseActivity == null ? null : info.baseActivity.getClassName());
            }
            return Under.OTHER;
        }

        @Override
        public boolean openTabs(Activity link) {
            Intent start = link.getPackageManager().getLaunchIntentForPackage(link.getPackageName());
            if (start == null) return false;
            link.startActivity(start);
            return true;
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            try {
                resumed(activity);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.REEL_LINK_TAB_BAR, WHAT_SHOW, t);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
