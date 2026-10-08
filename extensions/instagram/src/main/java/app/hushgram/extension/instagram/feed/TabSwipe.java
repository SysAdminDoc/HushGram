/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop swiping between tabs" patch.
 *
 * <p>Instagram puts its main tabs (Home, Reels and the rest) in one sideways pager, and a swipe on
 * it moves to the next tab. The pager scrolls a list of its own, and that list asks the pager's
 * input flag at each touch before it takes a finger. The patch hands the main tabs' pager to
 * {@link #mainPager} once Instagram has set it up, and each of those asks to {@link #input}, with the
 * list. While the switch is on, the main tabs' list is told no, so a swipe there does nothing, and
 * taps on the tab bar still change tabs. Every other pager's list gets its own answer.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the list does what the pager says.
 */
public final class TabSwipe {
    /** The step a failed switch read in {@link #input} is reported under. */
    static final String SWITCH = "pager input";

    /** The main tabs' pager, once Instagram has set it up. */
    private static volatile WeakReference<View> main = new WeakReference<>(null);

    private static volatile boolean logged;

    private TabSwipe() {
    }

    /** Injected right after Instagram stores the main tabs' pager. Remembers it. Never throws. */
    public static void mainPager(View pager) {
        try {
            HookStatus.invoked(FamilyNames.TAB_SWIPE);
            if (pager != null) main = new WeakReference<>(pager);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAB_SWIPE, "main pager", failure);
        }
    }

    /**
     * Injected in a pager's list touch checks, right after the list reads its pager's input flag,
     * with the list and the flag as an int (non-zero is yes). Answers no for the main tabs' list
     * while the switch is on, and the flag otherwise, or when anything goes wrong. Never throws.
     */
    public static boolean input(View list, int enabled) {
        return input(list, enabled, TabSwipe::switchedOn);
    }

    static boolean input(View list, int enabled, BooleanSupplier on) {
        if (enabled == 0) return false;
        try {
            View pager = main.get();
            if (list == null || pager == null || list.getParent() != pager) return true;
            HookStatus.invoked(FamilyNames.TAB_SWIPE);
            if (!on.getAsBoolean()) return true;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Tab swipe: the main tabs didn't take a sideways swipe");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAB_SWIPE, SWITCH, failure);
            return true;
        }
    }

    /** Forgets the main tabs' pager. Tests only. */
    static void forgetForTests() {
        main = new WeakReference<>(null);
        logged = false;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.STOP_TAB_SWIPING.get();
    }
}
