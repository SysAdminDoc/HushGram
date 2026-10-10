/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Opens Instagram on the tab the Start tab choice names, when the person started it from its icon.
 *
 * <p>Two things are needed, and both fail to Instagram's own start. The first activity this process
 * creates has to have come from the launcher: the MAIN action, the LAUNCHER category, no link and
 * no extras, and no saved state to restore. A notification, a link or a shortcut start its
 * activity with something else, so they open what they were meant for. And the first tab Instagram
 * is asked to open has to be a landing tab (Home, or Reels for accounts that open on Reels).
 * That one answer is swapped for the chosen tab, once per process, and only while the process is
 * still starting: until a moment after the first activity has resumed. A tap on Home or Reels after
 * that is the person's own and is never redirected.
 *
 * <p>The chosen tab has to be on the bar as the tab list hook last shaped it. A hidden tab, or one
 * Instagram left off the account's bar (Messages is on some), opens Home.
 */
public final class TabStart {
    /** The names of the tabs Instagram lands on when nothing asks for another. */
    private static final Set<String> LANDING = new HashSet<>(Arrays.asList("FEED", "FEED_SWITCHER", "CLIPS"));

    /** How long after the first activity resumes a landing can still be swapped, in milliseconds. */
    static final long STARTUP_MS = 1500;

    private static final int UNKNOWN = 0;
    private static final int LAUNCHER = 1;
    private static final int OTHER = 2;

    /** Whether this process's first activity came from the launcher. Decided once, by that activity. */
    private static volatile int firstStart = UNKNOWN;

    /** The names of the tabs on the bar, as the tab list hook last answered. Null until a list is built. */
    @Nullable
    private static volatile Set<String> onTheBar;

    /** Instagram's tab enum, from the last list the tab list hook answered with. Null until a list is built. */
    @Nullable
    private static volatile Class<?> tabEnum;

    /** Whether the one landing swap has been decided. */
    private static volatile boolean decided;

    /** When the first activity resumed, by the elapsed-realtime clock, or -1 before it has. */
    private static volatile long resumedAt = -1;

    private TabStart() {
    }

    /**
     * Called as the application starts. Does nothing in a build without the Reels tab patch, and
     * watches for the first activity otherwise, because the choice is only read later.
     */
    public static void install(Context context) {
        try {
            if (!(context instanceof Application) || !SettingsStatus.reelsTab() || !Utils.isMainProcess()) return;
            Application application = (Application) context;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {
                    try {
                        noteFirstStart(activity.getIntent(), state);
                    } catch (Throwable failure) {
                        HookStatus.threw(FamilyNames.REELS_TAB, "start", failure);
                    }
                }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityResumed(Activity activity) {
                    try {
                        noteResumed();
                    } finally {
                        application.unregisterActivityLifecycleCallbacks(this);
                    }
                }
                @Override public void onActivityPaused(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "start", failure);
        }
    }

    /** Records how the process's first activity was started. Only the first call counts. */
    static void noteFirstStart(@Nullable Intent intent, @Nullable Bundle savedState) {
        if (firstStart != UNKNOWN) return;
        firstStart = fromLauncher(intent, savedState) ? LAUNCHER : OTHER;
    }

    /** Records that the first activity has resumed, which starts the short window that ends the startup. */
    static void noteResumed() {
        if (resumedAt < 0) resumedAt = SystemClock.elapsedRealtime();
    }

    /** Whether the first activity resumed long enough ago that a request for a tab is the person's. */
    private static boolean startupOver() {
        long at = resumedAt;
        return at >= 0 && SystemClock.elapsedRealtime() - at > STARTUP_MS;
    }

    /** Whether [intent] is what the launcher sends, with no state being restored. */
    static boolean fromLauncher(@Nullable Intent intent, @Nullable Bundle savedState) {
        if (intent == null || savedState != null) return false;
        if (!Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getData() != null) return false;
        Set<String> categories = intent.getCategories();
        if (categories == null || !categories.contains(Intent.CATEGORY_LAUNCHER)) return false;
        Bundle extras = intent.getExtras();
        return extras == null || extras.isEmpty();
    }

    /** Remembers the tabs on the bar, as the tab list hook answers. */
    static void remember(List<?> shown) {
        Set<String> names = new HashSet<>();
        Class<?> kind = null;
        for (Object tab : shown) {
            if (tab instanceof Enum) {
                names.add(((Enum<?>) tab).name());
                kind = ((Enum<?>) tab).getDeclaringClass();
            }
        }
        if (kind != null) tabEnum = kind;
        onTheBar = names;
    }

    /** Whether the tab called [name] is on the bar as the tab list hook last answered. False before a list is built. */
    static boolean isOnTheBar(String name) {
        Set<String> bar = onTheBar;
        return bar != null && bar.contains(name);
    }

    /**
     * The tab called [name] in Instagram's tab enum, as the tab list hook last saw it, on the bar or
     * not. Null before a list is built or when the enum has no such tab.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object tabNamed(String name) {
        Class<?> kind = tabEnum;
        if (kind == null) return null;
        try {
            return Enum.valueOf((Class) kind, name);
        } catch (IllegalArgumentException none) {
            return null;
        }
    }

    /**
     * The tab to open in place of [asked], or null to leave it. Only the first landing tab Instagram
     * asks about in the process is a candidate, and only once the tab list has been built, so a
     * home tab asked about ahead of the list waits for the switch that follows it.
     */
    @Nullable
    static Object landing(Enum<?> asked) {
        if (decided) return null;
        if (startupOver()) {
            decided = true;
            return null;
        }
        if (!Utils.settingsReady()) {
            decided = true;
            return null;
        }
        StartTab choice = Settings.START_TAB.get();
        if (choice == StartTab.HOME) {
            decided = true;
            return null;
        }
        Set<String> bar = onTheBar;
        if (bar == null) return null;
        decided = true;
        if (!LANDING.contains(asked.name())) return null;
        if (firstStart != LAUNCHER) {
            HookStatus.counted(FamilyNames.REELS_TAB, "Start tab skipped, not from the icon");
            return null;
        }
        if (choice.tab.equals(asked.name())) return null;
        if (ReelsTab.hiddenByName(choice.tab) || !bar.contains(choice.tab)) {
            HookStatus.counted(FamilyNames.REELS_TAB, "Start tab not on the bar, opened Home");
            return ReelsTab.isReels(asked) ? ReelsTab.homeOf(asked) : null;
        }
        Object start = ReelsTab.named(asked, choice.tab);
        HookStatus.counted(FamilyNames.REELS_TAB, "Start tab applied");
        Logger.printDebug(() -> "Reels tab: opened " + choice.tab + " in place of " + asked.name());
        return start;
    }

    /** Takes the process back to before its first start, for tests. */
    static void forgetForTests() {
        firstStart = UNKNOWN;
        onTheBar = null;
        tabEnum = null;
        decided = false;
        resumedAt = -1;
    }
}
