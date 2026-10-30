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
 * Helper for the Open comments without the keyboard patch (#248): the comment sheet opens with the
 * keyboard down.
 *
 * <p>As the comment sheet starts, Instagram decides once whether to bring the keyboard up on its
 * own: when the post's comments open from a feed post with an empty box, from a notification, or
 * from a link that asks for it. That one place asks for the keyboard and marks the reason as an
 * automatic show. The patch puts {@link #keepsKeyboardDown} in front of it, and a yes takes
 * Instagram's own way past it, the one it takes when it doesn't want the keyboard.
 *
 * <p>A tap in the comment box, Reply on a comment and the other ways to start typing ask for the
 * keyboard elsewhere, so they still bring it up. The hook fails open: with the switch off,
 * HushGram paused, the settings not read yet or anything thrown, the keyboard comes up as before.
 */
public final class CommentKeyboard {
    static final String HOOK = "comment keyboard";
    static final String COUNT_KEPT_DOWN = "comment keyboards kept down";

    private CommentKeyboard() {
    }

    /**
     * Injected where the comment sheet is about to bring the keyboard up on its own. True, skip it,
     * while the switch is on. False, as Instagram planned, with the switch off, HushGram paused, the
     * settings not read or anything going wrong. Read each time a sheet opens, so the switch needs
     * no restart. Never throws.
     */
    public static boolean keepsKeyboardDown() {
        return keepsKeyboardDown(CommentKeyboard::switchedOn);
    }

    static boolean keepsKeyboardDown(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.COMMENT_KEYBOARD);
            if (!on.getAsBoolean()) return false;
            HookStatus.counted(FamilyNames.COMMENT_KEYBOARD, COUNT_KEPT_DOWN);
            Logger.printDebug(() -> "Comment keyboard: kept down as the comment sheet opened");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_KEYBOARD, HOOK, failure);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.COMMENT_KEYBOARD_DOWN.get();
    }
}
