/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Hide activity bubbles" patch.
 *
 * <p>Some posts and reels carry little round pictures in a corner showing that a friend liked,
 * commented on or reshared them. Instagram leaves them out for an account that turned on its own
 * Hide activity bubbles setting, and only some accounts get that setting. The patch hands each read
 * of the setting, and the check of whether the account has it, to {@link #hide} first, so while the
 * switch is on Instagram acts as if the setting were there and on.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer stands.
 */
public final class ActivityBubbles {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private ActivityBubbles() {
    }

    /**
     * Asked with what Instagram read (non-zero for yes): whether the account has the setting, or
     * whether the setting is on. True while the switch is on. Instagram's own answer while the switch
     * is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean hide(int instagram) {
        return hide(instagram, ActivityBubbles::switchedOn);
    }

    static boolean hide(int instagram, BooleanSupplier on) {
        boolean read = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.ACTIVITY_BUBBLES);
            if (!on.getAsBoolean()) return read;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Activity bubbles: hidden (Instagram read " + read + ")");
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.ACTIVITY_BUBBLES, SWITCH, t);
            return read;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_ACTIVITY_BUBBLES.get();
    }
}
