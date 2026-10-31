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
 * Helper for the "Skip the ad data reminder" patch (#331).
 *
 * <p>Where Meta asks consent for ads, Instagram brings up a modal asking you to review whether it
 * can process your data for ads, as the app comes to the foreground and after some actions. The
 * server sends the words, and three routes start it by themselves: a Quick Promotion's Bloks, the
 * evergreen launcher, and the trigger that asks for the flow by name. The patch asks {@link #skip}
 * first thing in each, and a yes leaves that route as if it had found nothing to show.
 *
 * <p>Nothing is answered for you. The screen itself, its settings links and the paid opt-out stay
 * where they are, your stored choice doesn't change, and no route reports the reminder as seen.
 */
public final class AdDataReminder {
    private static final String SOURCE = "AdDataReminder";

    /** What was logged this process, so each route's outcome shows in the log once. */
    private static final Set<String> LOGGED = new HashSet<>();

    private AdDataReminder() {
    }

    /**
     * Whether the [route], named for the log, keeps the reminder closed: yes while the switch is on.
     * While the switch is off, HushGram is paused or the settings aren't ready, Instagram goes on as
     * it meant to. Never throws.
     */
    public static boolean skip(String route) {
        try {
            HookStatus.invoked(FamilyNames.AD_DATA_REMINDER);
            if (!Utils.settingsReady() || !Settings.SKIP_AD_DATA_REMINDER.get()) {
                log(route, "shown, the switch is off or the settings weren't ready");
                return false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AD_DATA_REMINDER, "reminder route", failure);
            return false;
        }
        log(route, "skipped");
        return true;
    }

    /** Logs that [route]'s reminder was [what], once per process. */
    private static void log(String route, String what) {
        try {
            String line = route + " " + what;
            synchronized (LOGGED) {
                if (!LOGGED.add(line)) return;
            }
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> FamilyNames.AD_DATA_REMINDER + ": " + line);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AD_DATA_REMINDER, "log", failure);
        }
    }

    /** Forgets what was logged. For tests. */
    static void forget() {
        synchronized (LOGGED) {
            LOGGED.clear();
        }
    }
}
