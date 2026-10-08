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
import android.view.Window;
import android.view.WindowManager;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Allow screenshots" patch.
 *
 * <p>Where Instagram doesn't want a screen captured, like a disappearing photo or video, it marks
 * the window secure (FLAG_SECURE), which turns screenshots and screen recordings black. The patch
 * hands every Window.setFlags and Window.addFlags call in Instagram to {@link #setFlags} and
 * {@link #addFlags}, which leave that one flag off while the switch is on. Instagram's secure window
 * helper also asks {@link #lift} first, so it doesn't count a window it never marked.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram does what it always does.
 */
public final class ScreenshotBlock {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    /** What makes a window show black in screenshots and recordings. */
    static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;

    private static volatile boolean logged;

    private ScreenshotBlock() {
    }

    /**
     * Asked when Instagram is about to mark a window secure. True leaves the window capturable.
     * False while the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean lift() {
        return lift(ScreenshotBlock::switchedOn);
    }

    static boolean lift(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SCREENSHOT_BLOCK);
            boolean yes = on.getAsBoolean();
            if (yes && !logged) {
                logged = true;
                Logger.printDebug(() -> "Screenshots: left a window capturable");
            }
            return yes;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SCREENSHOT_BLOCK, SWITCH, t);
            return false;
        }
    }

    /**
     * Stands in for each Window.setFlags call in Instagram. While the switch is on, a call turning
     * FLAG_SECURE on sets the other flags and leaves that one alone. Clearing it, and every other
     * flag, goes through as Instagram asked.
     */
    public static void setFlags(Window window, int flags, int mask) {
        if ((flags & mask & SECURE) != 0 && lift()) {
            flags &= ~SECURE;
            mask &= ~SECURE;
        }
        window.setFlags(flags, mask);
    }

    /** Stands in for each Window.addFlags call in Instagram, leaving FLAG_SECURE off while the switch is on. */
    public static void addFlags(Window window, int flags) {
        if ((flags & SECURE) != 0 && lift()) {
            flags &= ~SECURE;
        }
        window.addFlags(flags);
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ALLOW_SCREENSHOTS.get();
    }
}
