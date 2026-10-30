/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "HushGram launch screen" patch: HushGram's logo on the screen Android shows while
 * Instagram starts, on Android 13 and newer.
 *
 * <p>Android draws that screen itself, in its own process, from the launch theme of the activity being
 * started, before any of Instagram's code runs, and takes it down when Instagram draws its first frame.
 * Instagram's launcher activities wear {@code Base.Theme.Launcher}, whose Android 12 version names the
 * icon. The patch adds a theme of its own, {@link #THEME}, which is that theme with HushGram's mark as
 * the icon and no "from Meta" line under it. Android 13 lets an app name the theme its starts use from
 * then on, and keeps that answer itself, so nothing here runs, waits or draws while Instagram starts.
 * Android simply draws a different picture in the time it already takes.
 *
 * <p>Instagram 451 can follow Android's screen with a logo animation of its own as its main screen
 * opens: a copy of its launch screen drawn over the app, with its logo pulsing and "from Meta" under
 * it, or its logo pulsing inside Android's screen. Either would put Instagram's logo right after
 * HushGram's, so {@link #instagramLogo} skips it while HushGram's screen is on.
 *
 * <p>This only tells Android which theme to use when the answer changes, on a background thread, once
 * an activity has resumed or is pausing, and the next start shows it. The answer is what the next start
 * will do: the switch as saved, unless that start runs paused (the Pause switch, safe mode or the marker
 * file). What Android was last told is kept in {@link #STORE}, so a start with nothing to change asks
 * Android for nothing. With the switch on, each process tells Android once anyway, in case it lost the
 * answer. Android ignores the call for an activity that's already gone, so an answer counts as given
 * only when the activity outlived the call. A build without the theme, Android 12 and older, or
 * anything thrown leaves Instagram's own screen.
 */
public final class LaunchScreen {
    /** The theme the patch adds next to Instagram's launch theme. */
    static final String THEME = "HushGramLaunchScreen";

    /** Where what Android was last told is kept, and the key it's under: true for HushGram's theme. */
    static final String STORE = "hushgram_launch_screen";
    static final String APPLIED = "applied";

    /** What's counted each time Android is told which launch screen to show. */
    static final String SET = "HushGram's launch screen handed to Android";
    static final String CLEARED = "Instagram's launch screen handed back";
    /** What's counted each time Instagram's own logo animation is kept from following HushGram's screen. */
    static final String LOGO_SKIPPED = "Instagram's logo after the launch screen skipped";

    /** Android's side, which a test replaces. */
    interface Splash {
        /** The number of {@link #THEME} in [activity]'s app, or 0 when this build has none. */
        int theme(Activity activity);

        /** Has Android show [theme] from the next start on; 0 gives Instagram's own back. */
        void use(Activity activity, int theme);
    }

    static Splash splash = new AndroidSplash();

    private static final Object LOCK = new Object();
    private static volatile boolean registered;
    /** What Android was last told, as kept in {@link #STORE}: null before it's read, or when it never was. */
    @Nullable private static Boolean applied;
    private static boolean appliedRead;
    /** Whether this process has told Android anything yet. */
    private static boolean toldThisProcess;

    private LaunchScreen() {
    }

    /**
     * Called as the application starts. Does nothing in a build that doesn't carry the patch or on
     * Android 12 and older, and watches every activity otherwise.
     */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.launchScreen()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LAUNCH_SCREEN, "start", failure);
        }
    }

    /** Has {@link #follow} run for [activity] on a background thread, so no frame waits on Android. */
    static void followLater(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        WeakReference<Activity> held = new WeakReference<>(activity);
        Utils.runOnBackgroundThread(() -> {
            Activity still = held.get();
            if (still != null && !still.isDestroyed()) follow(still);
        });
    }

    /**
     * Tells Android which launch screen the next start shows, when that has changed. Answers whether
     * Android was told anything. Never throws.
     */
    static boolean follow(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Utils.settingsReady()) return false;
            HookStatus.invoked(FamilyNames.LAUNCH_SCREEN);
            boolean wanted = wanted(activity);
            synchronized (LOCK) {
                SharedPreferences store = activity.getSharedPreferences(STORE, Context.MODE_PRIVATE);
                if (!appliedRead) {
                    applied = store.contains(APPLIED) ? store.getBoolean(APPLIED, false) : null;
                    appliedRead = true;
                }
                int theme = 0;
                if (wanted) {
                    theme = splash.theme(activity);
                    if (theme == 0) {
                        HookStatus.missingMember(FamilyNames.LAUNCH_SCREEN, "style", "resources", THEME);
                        wanted = false;
                    }
                }
                // HushGram's theme is handed over once in every process, in case Android lost it.
                // Instagram's own is handed back only when Android may still hold HushGram's.
                boolean unchanged = applied != null && applied == wanted && (!wanted || toldThisProcess);
                if (unchanged || activity.isDestroyed()) return false;
                splash.use(activity, theme);
                // Android drops the call without a word once the activity is gone, and marks it
                // destroyed before it lets go of it, so the answer is kept only when the activity
                // was still there after the call. Otherwise the next resume tells Android again.
                if (activity.isDestroyed()) return false;
                applied = wanted;
                toldThisProcess = true;
                store.edit().putBoolean(APPLIED, wanted).apply();
                HookStatus.counted(FamilyNames.LAUNCH_SCREEN, wanted ? SET : CLEARED);
                return true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LAUNCH_SCREEN, "launch theme", failure);
            return false;
        }
    }

    /**
     * Called twice as Instagram's main screen opens, each time with Instagram's answer to whether a
     * logo animation of its own follows Android's launch screen: first the one drawn over the app,
     * then the one inside Android's screen. Answers 0 while HushGram's launch screen is on, so
     * HushGram's mark stays until Instagram's first frame and no Instagram logo comes after it.
     * Answers Instagram's own on Android 12 and older, which show Instagram's launch screen anyway,
     * while paused, before settings can be read, and if anything throws. Never waits.
     */
    public static int instagramLogo(int shows) {
        if (shows == 0) return 0;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Utils.settingsReady()) return shows;
            HookStatus.invoked(FamilyNames.LAUNCH_SCREEN);
            if (!Settings.LAUNCH_SCREEN.get()) return shows;
            HookStatus.counted(FamilyNames.LAUNCH_SCREEN, LOGO_SKIPPED);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LAUNCH_SCREEN, "Instagram's logo", failure);
            return shows;
        }
    }

    /**
     * The same filter for a check that answers with an Integer object, where null means no animation
     * (452). Skipping the animation answers null; anything else is Instagram's own answer.
     */
    public static Integer instagramLogo(Integer shows) {
        return shows != null && instagramLogo(1) == 0 ? null : shows;
    }

    /**
     * Whether the next start shows HushGram's logo: the switch as saved, unless that start runs
     * paused by the Pause switch, safe mode or the marker file. It's the next start's answer, not
     * this one's, because that's the start Android draws the screen for.
     */
    static boolean wanted(Context context) {
        return Settings.LAUNCH_SCREEN.savedValue() && !HushgramPause.pausesNextStart(context);
    }

    /** Forgets what this process knows, as a new process would. */
    static void forgetForTests() {
        synchronized (LOCK) {
            applied = null;
            appliedRead = false;
            toldThisProcess = false;
        }
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            followLater(activity);
        }

        /** Leaving Instagram, or the settings screen, right after a change: the next start already shows it. */
        @Override public void onActivityPaused(@NonNull Activity activity) {
            if (!activity.isFinishing()) followLater(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    /**
     * Android 13's splash screen theme. The theme is found by name in the app's own resource package,
     * which is still Instagram's when Install next to Instagram renamed the app.
     */
    static final class AndroidSplash implements Splash {
        @Override public int theme(Activity activity) {
            Resources resources = activity.getResources();
            int theme = resources.getIdentifier(THEME, "style", activity.getPackageName());
            int own = activity.getApplicationInfo().theme;
            if (theme == 0 && own != 0) {
                theme = resources.getIdentifier(THEME, "style", resources.getResourcePackageName(own));
            }
            return theme;
        }

        @Override public void use(Activity activity, int theme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.getSplashScreen().setSplashScreenTheme(theme);
            }
        }
    }
}
