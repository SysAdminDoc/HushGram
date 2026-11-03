/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Phone layout on big screens" patch.
 *
 * <p>Instagram sorts every window by its width into compact (under 600 dp), medium and expanded,
 * the way Android's window size classes do, and a window that isn't compact gets its big screen
 * layout: the tab bar moves to a rail down the side and the feed sits in a narrow column. On a
 * foldable it also checks for a hinge sensor and treats the phone as a foldable from then on. The
 * patch hands the width to {@link #width} first thing in the sorting, and the hinge check's answer
 * to {@link #foldable}, so while the switch is on every window sorts as compact and the phone as no
 * foldable, and Instagram keeps its phone layout across the whole screen.
 *
 * <p>Both hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer stands.
 */
public final class PhoneLayout {
    /** The widest window, in dp, Instagram still sorts as compact. */
    static final int PHONE_WIDTH = 599;

    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean loggedWidth;
    private static volatile boolean loggedFoldable;

    private PhoneLayout() {
    }

    /**
     * Asked with the window width in dp Instagram is about to sort. At most {@link #PHONE_WIDTH}
     * while the switch is on, so the window sorts as compact. The width itself while the switch is
     * off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static int width(int dp) {
        return width(dp, PhoneLayout::switchedOn);
    }

    static int width(int dp, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.PHONE_LAYOUT);
            if (dp <= PHONE_WIDTH || !on.getAsBoolean()) return dp;
            if (!loggedWidth) {
                loggedWidth = true;
                Logger.printDebug(() -> "Phone layout: a " + dp + " dp window sorted as compact");
            }
            return PHONE_WIDTH;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHONE_LAYOUT, SWITCH, t);
            return dp;
        }
    }

    /**
     * Asked with whether the phone has a hinge sensor (non-zero for yes), as Instagram checks it to
     * decide the phone is a foldable. False while the switch is on. Instagram's own answer while the
     * switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean foldable(int instagram) {
        return foldable(instagram, PhoneLayout::switchedOn);
    }

    static boolean foldable(int instagram, BooleanSupplier on) {
        boolean read = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.PHONE_LAYOUT);
            if (!read || !on.getAsBoolean()) return read;
            if (!loggedFoldable) {
                loggedFoldable = true;
                Logger.printDebug(() -> "Phone layout: the hinge sensor left out, so no foldable layout");
            }
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHONE_LAYOUT, SWITCH, t);
            return read;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.PHONE_LAYOUT.get();
    }
}
