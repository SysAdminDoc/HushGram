/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Shorter Active now" patch.
 *
 * <p>Instagram shows someone as Active now under their name in your messages and in a chat's
 * header until six and a half minutes after its presence service last saw them, a limit the app
 * sets once, when its presence formatter's class first loads, and every check of Active now in
 * the app reads. The
 * website lets go after about a minute. The patch hands that limit to {@link #window} as it's set,
 * and the app keeps whatever comes back for the rest of the process, which is why the switch needs
 * a restart.
 *
 * <p>With the switch on the limit is one minute. Past it Instagram's own wording takes over, like
 * Active 2m ago. Someone the server itself still reports as active keeps Active now, since that
 * answer comes with their presence and isn't a limit on this phone. Nothing is requested or sent.
 */
public final class ActiveNow {
    /** The step the shortened limit is counted under. */
    static final String STEP = "shortened Active now";

    /** How long someone stays Active now with the switch on, in milliseconds: a minute, as on the website. */
    static final long WINDOW = 60_000L;

    private ActiveNow() {
    }

    /** Whether the limit is shortened: the settings are read and the switch is on, which a pause answers off. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SHORT_ACTIVE_NOW.get();
    }

    /**
     * Injected where Instagram sets how long after someone was last seen it still says Active now,
     * with its own limit in milliseconds. Answers {@link #WINDOW} while the switch is on, and the
     * limit as given while it's off, HushGram is paused, the settings aren't ready yet, or the given
     * limit is already a minute or less. Never throws.
     */
    public static long window(long stock) {
        return window(stock, ActiveNow::on);
    }

    /** {@link #window(long)} with the switch given. */
    static long window(long stock, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SHORT_ACTIVE_NOW);
            if (stock <= WINDOW || !on.getAsBoolean()) return stock;
            HookStatus.counted(FamilyNames.SHORT_ACTIVE_NOW, STEP);
            return WINDOW;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHORT_ACTIVE_NOW, STEP, failure);
            return stock;
        }
    }
}
