/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the 2x popup on Reels" patch (#552).
 *
 * <p>While a reel plays faster under a held finger, Instagram 454 draws a "2x" label over it. The
 * reel's fast play part builds that label in one call and draws whatever comes back, or nothing
 * when it gets nothing. The patch hands the built label to {@link #label(Object)}, which keeps it
 * from the reel while the switch is on. The speed-up itself is untouched.
 */
public final class FastPlayLabel {
    private static final String HOOK = "reel fast play label";

    private FastPlayLabel() {
    }

    /** Nothing while the switch is on, so the reel draws no label; otherwise Instagram's label. Never throws. */
    public static Object label(Object label) {
        try {
            HookStatus.invoked(FamilyNames.HIDE_REEL_2X_POPUP);
            return on() ? null : label;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_REEL_2X_POPUP, HOOK, failure);
            return label;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_REEL_2X_POPUP.get();
    }
}
