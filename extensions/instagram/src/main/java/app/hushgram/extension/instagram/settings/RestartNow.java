/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Process;

import app.hushgram.extension.instagram.download.SaveControl;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.shared.settings.preference.ExportStatus;
import app.hushgram.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * Restart now: ends Instagram's process and opens Instagram again in a new one, for the choices
 * Instagram only reads as it starts. Only ever on a tap. The sign-in is in Instagram's own storage
 * and comes through the restart, and every setting is already committed when it's saved. The
 * settings screen comes back on the page the card was tapped on ({@link ReopenSettingsAfterRestart}).
 */
final class RestartNow {
    private RestartNow() {}

    enum Outcome {
        RESTARTING,
        /** A save or an export is running, and a restart would cut it off. */
        BUSY,
        FAILED
    }

    /** Stands in for ending the process in a test, which would end the test run. */
    static volatile Runnable endProcessForTests;

    /** Whether a change made since Instagram started waits on a restart. */
    static boolean owed() {
        return !AbstractPreferenceFragment.restartPending.isEmpty();
    }

    static boolean busy() {
        return !SaveControl.running().isEmpty() || ExportStatus.CONFIGURATION.active()
                || ExportStatus.DIAGNOSTICS.active();
    }

    /** Opens Instagram's launcher activity in a fresh task, then ends this process. */
    static Outcome restart(Activity activity) {
        return restart(activity, null);
    }

    /**
     * {@link #restart(Activity)}, and the settings screen opens on [page] once Instagram is back:
     * a category's key, which opens expanded, or "" for the top of the list. Null opens nothing
     * afterwards.
     */
    static Outcome restart(Activity activity, String page) {
        if (busy()) return Outcome.BUSY;
        // Committed before the process ends. Dropped again if Instagram doesn't restart after all.
        if (page != null) ReopenSettingsAfterRestart.remember(page);
        try {
            String packageName = activity.getPackageName();
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null || launch.getComponent() == null) return failed(page);
            Intent again = Intent.makeRestartActivityTask(launch.getComponent());
            // Android 14 and later want the package named on an intent like this one.
            again.setPackage(packageName);
            activity.startActivity(again);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Restart now couldn't open Instagram again", failure);
            return failed(page);
        }
        Runnable end = endProcessForTests;
        if (end != null) end.run();
        // Not System.exit: Instagram's crash reporter forks a child on the way out and waits for it,
        // and with HushGram paused that child never finished, so the restart hung on "isn't
        // responding" (#146). Every setting is committed already, so nothing waits on exit handlers.
        else Process.killProcess(Process.myPid());
        return Outcome.RESTARTING;
    }

    /** A restart that didn't happen leaves no page for some later start to open. */
    private static Outcome failed(String page) {
        if (page != null) ReopenSettingsAfterRestart.forget();
        return Outcome.FAILED;
    }
}
