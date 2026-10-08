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
 * Helper for the "Don't report screenshots" patch.
 *
 * <p>While it's open, Instagram looks for new screenshots one of two ways, by watching the phone's
 * media or by scanning the screenshot folders. Either way, a new one is handed to every screen
 * listening, and a chat with a disappearing photo or video tells the sender. The patch asks
 * {@link #hold} first where each of the two hands a screenshot on, so while the switch is on no
 * screen hears of it.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram does what it always does.
 */
public final class ScreenshotReports {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private ScreenshotReports() {
    }

    /**
     * Asked first when Instagram's screenshot detector reports a new screenshot. True drops the
     * report. False while the switch is off, HushGram is paused or the settings aren't ready.
     * Never throws.
     */
    public static boolean hold() {
        return hold(ScreenshotReports::switchedOn);
    }

    static boolean hold(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SCREENSHOT_REPORTS);
            boolean yes = on.getAsBoolean();
            if (yes && !logged) {
                logged = true;
                Logger.printDebug(() -> "Screenshots: kept a screenshot from Instagram");
            }
            return yes;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SCREENSHOT_REPORTS, SWITCH, t);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_SCREENSHOTS.get();
    }
}
