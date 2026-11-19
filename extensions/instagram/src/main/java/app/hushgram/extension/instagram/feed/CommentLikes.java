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
 * Helper for the likes switch of the "Hide comments" patch (#762).
 *
 * <p>A comment sheet builds each comment as a row of components, and the column on the row's right
 * holds the heart and the like count under it as one component. The patch asks {@link #hidden}
 * right before the row adds that component to its column, and skips the add on a yes. The rest of
 * the row stays: the name, the text, Reply and See translation.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the heart and the count are drawn as Instagram has them.
 */
public final class CommentLikes {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "comment likes";

    /** What each answer is counted under in Hook status. */
    static final String COUNT_HIDDEN = "likes off";
    static final String COUNT_SHOWN = "likes on";

    private static volatile boolean logged;

    private CommentLikes() {
    }

    /**
     * Injected right before a comment row adds its heart and like count. True leaves them out of the
     * row, false adds them. Read as each row is built, so the switch needs no restart. Never throws.
     */
    public static boolean hidden() {
        return hidden(CommentLikes::switchedOn);
    }

    static boolean hidden(BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.HIDE_COMMENTS);
        boolean hide;
        try {
            hide = on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMENTS, SWITCH, failure);
            return false;
        }
        HookStatus.counted(FamilyNames.HIDE_COMMENTS, hide ? COUNT_HIDDEN : COUNT_SHOWN);
        if (hide && !logged) {
            logged = true;
            Logger.printInfo(() -> "Hide comments: took the heart and like count off a comment");
        }
        return hide;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_COMMENT_LIKES.get();
    }
}
