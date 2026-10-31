/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Skip the contacts and location popups" patch (#295, #329, #421).
 *
 * <p>Instagram's server sends its "Set up on new device" steps, the one asking to sync your
 * contacts and the one asking to turn on location services, as Bloks screens named by an app id.
 * It sends them again on a cold start for as long as the phone doesn't share contacts or location,
 * so skipping them in Instagram only lasts until the next start. The patch asks {@link #skip}
 * first thing wherever Instagram opens a Bloks screen, and a yes keeps that screen closed. Only
 * those two app ids are ever skipped; every other Bloks screen opens as Instagram meant.
 */
public final class SetupPopups {
    /** The app id of the setup step asking to sync your contacts. */
    static final String CONTACTS_SETUP = "com.bloks.www.bloks.ig.ndx.ci.entry.screen";

    /** The app id of the setup step asking to turn on location services. */
    static final String LOCATION_SETUP = "com.bloks.www.bloks.ig.ndx.ls.entry.screen";

    private static final String SOURCE = "SetupPopups";

    /** What was logged this process, so each screen's outcome shows in the log once. */
    private static final Set<String> LOGGED = new HashSet<>();

    private SetupPopups() {
    }

    /**
     * Whether the Bloks screen [appId] stays closed: yes for the contacts and location setup screens
     * while the switch is on. Every other screen, and every screen while the switch is off, HushGram
     * is paused or the settings aren't ready, opens as Instagram meant. Never throws.
     */
    public static boolean skip(String appId) {
        try {
            HookStatus.invoked(FamilyNames.SETUP_POPUPS);
            if (!CONTACTS_SETUP.equals(appId) && !LOCATION_SETUP.equals(appId)) return false;
            if (!Utils.settingsReady() || !Settings.SKIP_SETUP_POPUPS.get()) {
                log(appId, "shown, the switch is off or the settings weren't ready");
                return false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SETUP_POPUPS, "setup screen", failure);
            return false;
        }
        log(appId, "skipped");
        return true;
    }

    /** Logs that [appId]'s screen was [what], once per process. */
    private static void log(String appId, String what) {
        try {
            String line = appId + " " + what;
            synchronized (LOGGED) {
                if (!LOGGED.add(line)) return;
            }
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> FamilyNames.SETUP_POPUPS + ": " + line);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SETUP_POPUPS, "log", failure);
        }
    }

    /** Forgets what was logged. For tests. */
    static void forget() {
        synchronized (LOGGED) {
            LOGGED.clear();
        }
    }
}
