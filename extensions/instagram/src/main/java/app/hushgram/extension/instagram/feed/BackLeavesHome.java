/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Back leaves Home" patch.
 *
 * <p>Home's feed fragment answers Back before the activity does. When the feed isn't already at the
 * top, it scrolls to the top with the reason BACK_BUTTON_PRESS, which can reload the feed too, and
 * says it handled the press, so it takes a second Back to leave. When the feed is at the top it says
 * it didn't handle it, and the activity goes on to leave. The patch asks {@link #leave} right before
 * that scroll starts.
 *
 * <p>While the switch is on, a 1 sends the fragment down its "not handled" path, the same one it takes
 * at the top of the feed, so Back runs what Instagram runs from there: the tab it came from, or out of
 * the app. Back on other tabs, inside a post or the comments and closing a sheet or a dialog never
 * reach this code.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Back does what Instagram does.
 */
public final class BackLeavesHome {
    /** The step a failure is reported under. */
    static final String BACK = "back on home";

    /** What's counted each time Back is left to the activity instead of scrolling Home up. */
    static final String LEFT = "back left home as it is";

    private BackLeavesHome() {
    }

    /**
     * Injected in Home's Back handler, in front of its scroll to the top. Answers 1 to answer "not
     * handled" in its place, or 0 to go on as Instagram does. Never throws.
     */
    public static int leave() {
        return leave(BackLeavesHome::switchedOn);
    }

    static int leave(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.BACK_LEAVES_HOME);
            if (!on.getAsBoolean()) return 0;
            HookStatus.counted(FamilyNames.BACK_LEAVES_HOME, LEFT);
            Logger.printDebug(() -> "Back leaves Home: left Back to the activity");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACK_LEAVES_HOME, BACK, failure);
            return 0;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.BACK_LEAVES_HOME.get();
    }
}
