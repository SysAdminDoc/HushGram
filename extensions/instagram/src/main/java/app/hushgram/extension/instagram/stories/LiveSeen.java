/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "View live anonymously" patch.
 *
 * <p>While you watch someone's live, Instagram's heartbeat manager tells the server every few
 * seconds that you're still there, and that's what puts you on the host's viewer list. The patch
 * asks {@link #hold} right before a viewer's or guest's heartbeat goes out, and the tick ends as if
 * it had. The host's own heartbeat, which counts the viewers of your lives, isn't touched.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram does what it always does.
 */
public final class LiveSeen {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private LiveSeen() {
    }

    /**
     * Asked before a viewer's or guest's live heartbeat is sent. True skips it. False while the switch
     * is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean hold() {
        return hold(LiveSeen::switchedOn);
    }

    static boolean hold(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.LIVE_SEEN);
            boolean yes = on.getAsBoolean();
            if (yes && !logged) {
                logged = true;
                Logger.printDebug(() -> "Live: held a viewer heartbeat");
            }
            return yes;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.LIVE_SEEN, SWITCH, t);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_LIVE_ANONYMOUSLY.get();
    }
}
