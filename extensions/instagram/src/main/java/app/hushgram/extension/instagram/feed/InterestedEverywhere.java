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
 * Helper for the Interested switch of the "Three-dot menu items" patch (#815).
 *
 * <p>Instagram puts Interested in a reel's menu only when you don't follow the reel's account, and in
 * a post's menu only on Home or a post's own page, and only when its server settings for your account
 * allow it. The patch hands the reel menu's follow check to {@link #reelFollowing} and the post
 * menu's decision to {@link #postOffered}, so with the switch on both menus offer it. Not interested
 * and the rest of the menu stay as they are.
 *
 * <p>Both answer a boolean because what they answer goes on as a boolean: the post menu's flags take
 * it as one. They take an int, read non-zero as yes, so any register Instagram tests can be handed in.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the menus are built as Instagram has them.
 */
public final class InterestedEverywhere {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "Interested everywhere";
    /** What each menu the switch added Interested to is counted under in Hook status. */
    static final String COUNT_ADDED = "Interested added";

    private static volatile boolean logged;

    private InterestedEverywhere() {
    }

    /**
     * Injected right before a reel's menu tests whether you follow the reel's account, with the
     * answer, non-zero for yes. Answers it, or false with the switch on, so the menu adds Interested
     * as it does for an account you don't follow. Read as the menu opens, so the switch needs no
     * restart. Never throws.
     */
    public static boolean reelFollowing(int following) {
        return reelFollowingWhen(following, InterestedEverywhere::switchedOn);
    }

    static boolean reelFollowingWhen(int following, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.MENU_ITEMS);
        if (following == 0) return false;
        if (!read(on)) return true;
        added();
        return false;
    }

    /**
     * Injected right before a post's menu makes its flags, with whether Instagram offers Interested
     * on it, non-zero for yes. Answers it, or true with the switch on. Never throws.
     */
    public static boolean postOffered(int offered) {
        return postOfferedWhen(offered, InterestedEverywhere::switchedOn);
    }

    static boolean postOfferedWhen(int offered, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.MENU_ITEMS);
        if (offered != 0) return true;
        if (!read(on)) return false;
        added();
        return true;
    }

    private static boolean read(BooleanSupplier on) {
        try {
            return on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_ITEMS, SWITCH, failure);
            return false;
        }
    }

    private static void added() {
        HookStatus.counted(FamilyNames.MENU_ITEMS, COUNT_ADDED);
        if (!logged) {
            logged = true;
            Logger.printInfo(() -> "Three-dot menu items: offered Interested where Instagram leaves it out");
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.INTERESTED_EVERYWHERE.get();
    }
}
