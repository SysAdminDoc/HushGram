/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Hide that you're typing" patch.
 *
 * <p>While you write in a chat, Instagram's typing status service tells the other person you're
 * typing. The patch asks {@link #hold} first in that service with its typing flag. While the switch
 * is on, a start returns before anything is sent. A stop sends nothing and only makes Instagram
 * forget the chat it last reported, so it always runs Instagram's code. Seeing the other person
 * type is a separate path and stays as it is.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram sends the indicator as usual.
 */
public final class TypingStatus {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private TypingStatus() {
    }

    /**
     * Asked at the start of Instagram's typing status service. {@code typing} is its flag, non-zero
     * while you type. True makes the service return without telling anyone. False for a stop, and
     * while the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean hold(int typing) {
        return hold(typing, TypingStatus::switchedOn);
    }

    static boolean hold(int typing, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.TYPING);
            boolean hold = typing != 0 && on.getAsBoolean();
            if (hold && !logged) {
                logged = true;
                Logger.printDebug(() -> "Messages: kept the typing indicator back");
            }
            return hold;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.TYPING, SWITCH, t);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_TYPING.get();
    }
}
