/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Use the highest refresh rate (#401, #396): asks Android to run each Instagram window at the
 * display's fastest mode at the resolution it is on now.
 *
 * <p>Android picks a rate for every window, and a phone with a 120 Hz panel often settles an app on
 * 60 or 90 Hz. A window can ask for a display mode by id ({@code preferredDisplayModeId}, API 23),
 * which is a request, not an order: a phone's own rules (battery saver, a per-app cap in the maker's
 * settings) can still hold Instagram lower. The mode asked for has the same pixel size as the current
 * one, so the screen never changes resolution, only its rate.
 *
 * <p>The request is made even when the display already reports that mode. A Galaxy S25 reports its
 * 120 Hz mode as the current one while it draws Instagram at 60 Hz from a slower base mode, so
 * asking only for a mode faster than the reported one asked for nothing there (#401).
 *
 * <p>The request is made as each activity resumes, and again the moment the switch is flipped, on
 * the activity under HushGram settings: the settings open over Instagram's screen, which never
 * resumes when they close, so without that the request waited for the next screen or a cold start
 * (#401). Turning it off, pausing HushGram or a settings read that isn't ready puts back the
 * no-preference value, but only on windows this class asked for.
 */
public final class RefreshRate {
    /** Windows this class set a mode on, so turning it off only undoes its own request. */
    private static final Map<Activity, Integer> asked = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean registered;

    /** Where an activity's display modes come from. Tests stand in. */
    interface ModeSource {
        @Nullable Mode[] modes(Activity activity, int[] currentId);
    }

    static volatile ModeSource source = RefreshRate::modesOf;

    private RefreshRate() {
    }

    /** A display mode: its id, its pixel size and its rate. */
    public static final class Mode {
        final int id;
        final int width;
        final int height;
        final float hertz;

        public Mode(int id, int width, int height, float hertz) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.hertz = hertz;
        }
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.refreshRate()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REFRESH_RATE, "start", failure);
        }
    }

    /** Called as the switch is flipped in HushGram settings, with the activity under them. */
    public static void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> apply(activity));
    }

    /**
     * The id of the fastest mode among those with [current]'s pixel size, [current] itself when
     * nothing runs faster, or 0 (no preference) when [current] isn't in [modes]. The first of equal
     * rates wins, so the answer doesn't move between calls.
     */
    public static int fastestId(int current, @Nullable Mode[] modes) {
        if (modes == null) return 0;
        Mode now = null;
        for (Mode mode : modes) if (mode != null && mode.id == current) now = mode;
        if (now == null) return 0;
        Mode best = now;
        for (Mode mode : modes) {
            if (mode == null || mode.width != now.width || mode.height != now.height) continue;
            if (mode.hertz > best.hertz + 0.5f) best = mode;
        }
        return best.id;
    }

    /** Whether the switch answers yes right now. Off while paused or before the settings are read. */
    static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIGHEST_REFRESH_RATE.get();
    }

    /** What [activity]'s display can run, or null when it can't be read. */
    @Nullable
    static Mode[] modesOf(Activity activity, int[] currentId) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        if (display == null) return null;
        Display.Mode[] supported = display.getSupportedModes();
        Display.Mode now = display.getMode();
        if (supported == null || now == null) return null;
        currentId[0] = now.getModeId();
        Mode[] out = new Mode[supported.length];
        for (int i = 0; i < supported.length; i++) {
            out[i] = new Mode(supported[i].getModeId(), supported[i].getPhysicalWidth(),
                    supported[i].getPhysicalHeight(), supported[i].getRefreshRate());
        }
        return out;
    }

    /** Sets or puts back the mode for [activity]'s window. Never throws. */
    static void apply(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams params = window.getAttributes();
            if (!on()) {
                Integer ours = asked.remove(activity);
                // Only a window this class set, and only while it still holds this class's request.
                if (ours != null && params.preferredDisplayModeId == ours) {
                    params.preferredDisplayModeId = 0;
                    window.setAttributes(params);
                }
                return;
            }
            HookStatus.invoked(FamilyNames.REFRESH_RATE);
            int[] current = new int[1];
            Mode[] modes = source.modes(activity, current);
            int id = fastestId(current[0], modes);
            if (id == 0 || params.preferredDisplayModeId == id) return;
            params.preferredDisplayModeId = id;
            window.setAttributes(params);
            asked.put(activity, id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REFRESH_RATE, "mode request", failure);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            apply(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            asked.remove(activity);
        }
    }
}
