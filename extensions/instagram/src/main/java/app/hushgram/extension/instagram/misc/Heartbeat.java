/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.AlarmManager;
import android.app.PendingIntent;

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
     * and the events go when Instagram next uploads while you're using it, or, with Disable analytics,
     * nowhere, as before. Off, or with the settings not ready, it sets the alarm as Instagram would, and
     * anything AlarmManager throws reaches Instagram's own handler as before.
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
}
