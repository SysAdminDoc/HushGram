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
 * Helper for the Reply switch of the "Hide comments" patch (#619).
 *
 * <p>Each comment in a post's or reel's comments draws the line under its text with Compose: the
 * time, the like count, Reply and the rest. The patch asks {@link #hidden} right before that line
 * draws Reply, as the text button or as the newer reply icon, and skips drawing it on a yes. The
 * rest of the line stays, and so does View more replies (Hide replies once open), which the
 * comment list draws as a row of its own under the comment.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Reply is drawn as Instagram has it.
 */
public final class CommentReply {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "comment reply";

    /** What each answer is counted under in Hook status. */
    static final String COUNT_HIDDEN = "reply off";
    static final String COUNT_SHOWN = "reply on";

    private static volatile boolean logged;

    private CommentReply() {
    }

    /**
     * Injected right before a comment's line draws Reply. True leaves Reply out of that line, false
     * draws it. Never throws.
     */
    public static boolean hidden() {
        return hidden(CommentReply::switchedOn);
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
            Logger.printInfo(() -> "Hide comments: took Reply off a comment");
        }
        return hide;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_COMMENT_REPLY.get();
    }
}
