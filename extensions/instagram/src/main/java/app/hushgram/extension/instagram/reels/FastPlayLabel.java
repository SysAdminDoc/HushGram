/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.widget.Toast;

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
 *
 * <p>Locking the speed (hold the edge, then slide down) is another path: Reels' long press navigator
 * shows a toast, "Locked at 2x speed", and another when the lock ends. The patch hands each of those
 * toasts to {@link #lockToast(Toast)} in place of showing it, which shows it unless its own switch is
 * on (#702).
 */
public final class FastPlayLabel {
    private static final String HOOK = "reel fast play label";
    private static final String LOCK_HOOK = "reel speed lock message";

    /** Counted under the patch's name for each lock message kept off the screen. */
    static final String LOCK_HIDDEN = "speed lock message hidden";

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

    /**
     * Injected in place of the show of a speed lock toast, [toast]: shows it unless the lock message
     * switch is on. Anything thrown deciding shows it, as Instagram would.
     */
    public static void lockToast(Toast toast) {
        if (toast == null) return;
        try {
            HookStatus.invoked(FamilyNames.HIDE_REEL_2X_POPUP);
            if (lockHidden()) {
                HookStatus.counted(FamilyNames.HIDE_REEL_2X_POPUP, LOCK_HIDDEN);
                return;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_REEL_2X_POPUP, LOCK_HOOK, failure);
        }
        toast.show();
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_REEL_2X_POPUP.get();
    }

    private static boolean lockHidden() {
        return Utils.settingsReady() && Settings.HIDE_REEL_LOCK_MESSAGE.get();
    }
}
