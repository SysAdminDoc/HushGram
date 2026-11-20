/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Text size (#682): Instagram's text bigger or smaller than the phone's own font size setting makes
 * it, in whatever font it draws in. It ships with the Change the font patch.
 *
 * <p>Android sizes text from the font scale in each screen's resources. As the application starts,
 * and as each screen is made, before it lays out its views, that scale is set to the phone's times
 * the saved share. A change of the phone's settings puts the phone's scale back, so it's set again
 * then and as each screen comes back. The share is read once per process, so a change shows after a
 * restart. At 100%, while paused and on any failure, nothing is touched.
 *
 * <p>The resources the application and each screen hand out are Instagram's own, which keep a
 * configuration of their own. A change of the phone's settings, turning the phone among them, has
 * Instagram copy the phone's configuration into them again after this class has sized them, and the
 * captions it lays out next come back at the phone's size (#782). The Font patch hands every
 * configuration they take to {@link #sized} first.
 */
public final class TextSize {
    private static volatile boolean registered;

    /** The share text draws at once installed, 1 before and when it's the phone's own. */
    private static volatile float active = 1f;

    /** Whether a test says the patch is in this build, instead of asking {@link PatchFamily}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private TextSize() {
    }

    /** Lets a test's next application install again. */
    static void resetForTests() {
        registered = false;
        active = 1f;
        inBuildForTests = null;
    }

    private static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : PatchFamily.FONT.inBuild();
    }

    /** Called as the application starts. With a size other than 100%, sizes the text from here on. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !Utils.settingsReady() || !inBuild()
                    || !Utils.isMainProcess()) return;
            float scale = scale();
            if (scale == 1f) return;
            registered = true;
            Application application = (Application) context;
            apply(application.getResources(), scale);
            application.registerActivityLifecycleCallbacks(new Watcher(scale));
            application.registerComponentCallbacks(new PhoneChanges(application, scale));
            active = scale;
            Logger.printDebug(() -> "Text size: text draws at " + Settings.TEXT_SIZE.get() + "%");
        } catch (Throwable failure) {
            Logger.printException(() -> "Text size: could not size Instagram's text", failure);
        }
    }

    /**
     * Called by Instagram's own resources with each configuration they're about to take. Once
     * installed, answers a copy whose font scale is the phone's times the saved share, unless it's
     * that already. Before that, at 100%, for null and on any failure, answers {@code config} itself.
     * Never changes {@code config}, which can be the phone's own.
     */
    public static Configuration sized(@Nullable Configuration config) {
        float scale = active;
        if (config == null || scale == 1f) return config;
        try {
            float wanted = Resources.getSystem().getConfiguration().fontScale * scale;
            if (Math.abs(config.fontScale - wanted) < 0.001f) return config;
            Configuration sized = new Configuration(config);
            sized.fontScale = wanted;
            return sized;
        } catch (Throwable failure) {
            Logger.printException(() -> "Text size: could not size Instagram's resources", failure);
            return config;
        }
    }

    /** The saved share of the phone's text size, 1 for the phone's own. */
    static float scale() {
        int percent = Settings.TEXT_SIZE.get();
        return percent == 100 ? 1f : percent / 100f;
    }

    /**
     * Sets [resources]' font scale to the phone's times [scale] when it isn't that already. Answers
     * whether it changed. Never throws.
     */
    static boolean apply(@Nullable Resources resources, float scale) {
        try {
            if (resources == null) return false;
            float wanted = Resources.getSystem().getConfiguration().fontScale * scale;
            Configuration now = resources.getConfiguration();
            if (Math.abs(now.fontScale - wanted) < 0.001f) return false;
            Configuration sized = new Configuration(now);
            sized.fontScale = wanted;
            // Deprecated for apps, but the only way to change the scale of resources already made.
            //noinspection deprecation
            resources.updateConfiguration(sized, resources.getDisplayMetrics());
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Text size: could not size a screen's text", failure);
            return false;
        }
    }

    /** Sizes each screen before it makes its views, and again as it comes back. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        private final float scale;

        Watcher(float scale) {
            this.scale = scale;
        }

        @Override
        public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle state) {
            apply(activity.getResources(), scale);
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
            // Before Android 10 there's no call ahead of the screen's own set-up.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) apply(activity.getResources(), scale);
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            apply(activity.getResources(), scale);
        }

        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    /** A change of the phone's settings puts its own scale back, so it's set again after. */
    static final class PhoneChanges implements ComponentCallbacks {
        private final Application application;
        private final float scale;

        PhoneChanges(Application application, float scale) {
            this.application = application;
            this.scale = scale;
        }

        @Override
        public void onConfigurationChanged(@NonNull Configuration changed) {
            apply(application.getResources(), scale);
            // The screen on show takes the change after the application does.
            Utils.runOnMainThread(() -> {
                Activity shown = Utils.getActivity();
                if (shown != null) apply(shown.getResources(), scale);
            });
        }

        @Override
        public void onLowMemory() {
        }
    }
}
