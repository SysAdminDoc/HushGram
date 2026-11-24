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
 * Helper for the AI label switch of the "Hide comments" patch (#787).
 *
 * <p>A comment sheet builds the line with a comment's name as a row of components: the name, the
 * time, and on some comments a dot and an AI label after it, "AI-generated profile" or "AI creator"
 * for an account Meta marks as AI. The row reads its own flag for whether the comment has that
 * label, and the patch hands the flag to {@link #shown} right after the read, so a no leaves the dot
 * and the label out. The rest of the line stays: the name, the time, Author, Edited and the rest.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the label is drawn as Instagram has it.
 */
public final class CommentAiLabel {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "comment AI label";

    /** What each answer for a labeled comment is counted under in Hook status. */
    static final String COUNT_HIDDEN = "AI label off";
    static final String COUNT_SHOWN = "AI label on";

    private static volatile boolean logged;

    private CommentAiLabel() {
    }

    /**
     * Injected right after a comment's name line reads whether it shows an AI label, with what it
     * read: non-zero when the comment has one. Answers whether to show it, false with the switch
     * on. Read as each line is built, so the switch needs no restart. Never throws.
     */
    public static boolean shown(int labeled) {
        return shown(labeled, CommentAiLabel::switchedOn);
    }

    static boolean shown(int labeled, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.HIDE_COMMENTS);
        if (labeled == 0) return false;
        boolean hide;
        try {
            hide = on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMENTS, SWITCH, failure);
            return true;
        }
        HookStatus.counted(FamilyNames.HIDE_COMMENTS, hide ? COUNT_HIDDEN : COUNT_SHOWN);
        if (hide && !logged) {
            logged = true;
            Logger.printInfo(() -> "Hide comments: took the AI label off a comment");
        }
        return !hide;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_COMMENT_AI_LABELS.get();
    }
}
