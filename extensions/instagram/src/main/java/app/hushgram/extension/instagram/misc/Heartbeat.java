/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Helper for the "Stop background wake-ups" patch. */
public final class Heartbeat {

    private Heartbeat() {}

    /**
     * Asked at the start of the method that sets Instagram's next heartbeat alarm. True makes it
     * return without setting one. The heartbeat is a note to itself that Instagram is still running,
     * woken every minute or two by an alarm, which lets it tell afterwards that Android killed it. Nothing
     * you see or receive depends on it. False while the switch is off, HushGram is paused or the settings
     * aren't ready. Never throws.
     */
    public static boolean stop() {
        HookStatus.invoked(FamilyNames.STOP_HEARTBEAT);
        try {
            return Utils.settingsReady() && Settings.STOP_HEARTBEAT.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STOP_HEARTBEAT, "switch read", t);
            return false;
        }
    }

    /**
     * Stands in for the AlarmManager.set call that sets Instagram's analytics upload alarm, which wakes the
     * phone five minutes on to send the usage events it has gathered. With the switch on the alarm isn't set,
     * and the events go when Instagram next uploads while you're using it. Off, or with the settings not
     * ready, it sets the alarm as Instagram would, and anything AlarmManager throws reaches Instagram's own
     * handler as before.
     */
    public static void setUploadAlarm(AlarmManager alarms, int type, long at, PendingIntent operation) {
        HookStatus.invoked(FamilyNames.STOP_HEARTBEAT);
        boolean skip;
        try {
            skip = Utils.settingsReady() && Settings.STOP_UPLOAD_ALARM.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STOP_HEARTBEAT, "upload alarm switch read", t);
            skip = false;
        }
        if (!skip) alarms.set(type, at, operation);
    }

    /**
     * Asked at the start of the method that queues Instagram's background prefetch jobs, handed the job
     * service they run in. True makes it return without queueing any. Those jobs wake the phone about every
     * half hour, Wi-Fi or mobile data permitting, to download the feed ahead of time. With the switch on it
     * also cancels the ones it queued earlier, so none is left to go off. False while the switch is off,
     * HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean stopBackgroundFetch(Class<?> service) {
        if (!backgroundFetchOff("prefetch scheduler switch read")) return false;
        try {
            Context context = Utils.getContext();
            JobScheduler jobs = context == null ? null : (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobs != null && service != null) {
                ComponentName component = new ComponentName(context, service);
                for (JobInfo job : jobs.getAllPendingJobs()) {
                    if (component.equals(job.getService())) jobs.cancel(job.getId());
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STOP_HEARTBEAT, "prefetch job cancel", t);
        }
        return true;
    }

    /**
     * Asked as a background prefetch job starts. True ends it at once, done and not to be retried, with
     * nothing downloaded: a job Instagram queued before the switch went on. Never throws.
     */
    public static boolean skipBackgroundFetch() {
        return backgroundFetchOff("prefetch job switch read");
    }

    private static boolean backgroundFetchOff(String where) {
        HookStatus.invoked(FamilyNames.STOP_HEARTBEAT);
        try {
            return Utils.settingsReady() && Settings.STOP_BACKGROUND_FETCH.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STOP_HEARTBEAT, where, t);
            return false;
        }
    }
}
