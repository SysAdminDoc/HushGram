/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Helper for the "Stop the background heartbeat" patch. */
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
}
