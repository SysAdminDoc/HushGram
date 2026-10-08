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
 * Helper for the "Hide Instants" patch.
 *
 * <p>Instagram asks one method whether your account has Instants, its no-edit camera for friends,
 * and every way in (the stack of photos in your messages, the camera's Instants mode, the archive)
 * goes by the answer. The patch asks {@link #hide} first, and while the switch is on that method
 * answers no, as it does for an account Instants hasn't reached.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram answers for itself.
 */
public final class Instants {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private Instants() {
    }

    /**
     * Asked at the start of Instagram's Instants check. True makes it answer that the account has
     * no Instants. False while the switch is off, HushGram is paused or the settings aren't ready.
     * Never throws.
     */
    public static boolean hide() {
        return hide(Instants::switchedOn);
    }

    static boolean hide(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.INSTANTS);
            boolean hide = on.getAsBoolean();
            if (hide && !logged) {
                logged = true;
                Logger.printDebug(() -> "Instants: told Instagram this account has no Instants");
            }
            return hide;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.INSTANTS, SWITCH, t);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_INSTANTS.get();
    }
}
