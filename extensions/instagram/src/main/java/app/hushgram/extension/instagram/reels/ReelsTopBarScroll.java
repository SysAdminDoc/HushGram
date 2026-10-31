/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Keep the Reels top bar patch (#411): keeps the row at the top of a reels viewer,
 * the Reels title, the Friends tab and the buttons, in place while you swipe through reels.
 *
 * <p>On each scroll the viewer's top bar ({@code ClipsViewerActionBar}) asks one flag whether its
 * title, tabs and buttons stay where they are, and each one that doesn't slides up with the reel
 * it sits over until it's off screen. Instagram sets the flag only for some reels. The patch passes
 * it through {@link #keepInPlace} as the scroll starts, and while the switch is on the answer is
 * yes, so the bar stays put, and one already slid away comes back at the next swipe. Instagram's
 * own reasons to hide the bar over a reel, the ones it checks even for a bar that stays, still win.
 *
 * <p>It's read on every scroll, so a flip shows at the next swipe with no restart. The hook fails
 * open: with the switch off, HushGram paused, the settings not read yet or anything thrown, the bar
 * gets Instagram's own answer.
 */
public final class ReelsTopBarScroll {
    static final String HOOK = "top bar scroll";
    static final String COUNT_KEPT = "top bar kept";

    private ReelsTopBarScroll() {
    }

    /**
     * Injected at the start of the top bar's onReelScroll, on its keep-in-place flag. The flag comes in
     * as an int, non-zero for yes, since a hook never takes a boolean register. Never throws.
     */
    public static boolean keepInPlace(int instagrams) {
        return keepInPlace(instagrams != 0, ReelsTopBarScroll::switchedOn);
    }

    static boolean keepInPlace(boolean instagrams, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.KEEP_REELS_TOP_BAR);
            if (instagrams || !on.getAsBoolean()) return instagrams;
            HookStatus.counted(FamilyNames.KEEP_REELS_TOP_BAR, COUNT_KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.KEEP_REELS_TOP_BAR, HOOK, failure);
            return instagrams;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_REELS_TOP_BAR.get();
    }
}
