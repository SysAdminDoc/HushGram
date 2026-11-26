/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.Collection;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the (i) switch of the "Hide comments" patch (#869).
 *
 * <p>Some comment sheets draw an (i) button at the right of the row above the comments. It opens a
 * short note, a title and a text the comment sheet's view model makes, and the row draws it only
 * when the sheet has that note to show. The patch hands the note to {@link #disclosure} right after the row
 * reads it, so with the switch on the row finds none and leaves the button out. The comments and
 * everything else in the row stay.
 *
 * <p>A reel's comments can also start with a row of their own with an (i), "About comments on this
 * reel" in English, that opens the same note. The patch hands the list and that row to
 * {@link #aboutRow} in place of the list's add of it, so with the switch on the row stays out.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the button is drawn as Instagram has it.
 */
public final class CommentAboutButton {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "comment (i) button";
    /** What each answer for a row with an (i) is counted under in Hook status. */
    static final String COUNT_HIDDEN = "comment (i) off";
    static final String COUNT_SHOWN = "comment (i) on";
    /** What each answer for the row with an (i) in a reel's comments is counted under. */
    static final String COUNT_ROW_HIDDEN = "reel (i) row off";
    static final String COUNT_ROW_SHOWN = "reel (i) row on";

    private static volatile boolean logged;
    private static volatile boolean rowLogged;

    private CommentAboutButton() {
    }

    /**
     * Injected right after the row above a sheet's comments reads what its (i) button explains, with
     * what it read, null when the sheet has nothing to explain. Answers it, or null with the switch
     * on so the row leaves the button out. Read as the row is built, so the switch needs no restart.
     * Never throws.
     */
    public static Object disclosure(Object explained) {
        return disclosure(explained, CommentAboutButton::switchedOn);
    }

    static Object disclosure(Object explained, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.HIDE_COMMENTS);
        if (explained == null) return null;
        boolean hide;
        try {
            hide = on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMENTS, SWITCH, failure);
            return explained;
        }
        HookStatus.counted(FamilyNames.HIDE_COMMENTS, hide ? COUNT_HIDDEN : COUNT_SHOWN);
        if (hide && !logged) {
            logged = true;
            Logger.printInfo(() -> "Hide comments: took the (i) button off a comment sheet");
        }
        return hide ? null : explained;
    }

    /**
     * Injected in place of the add that puts the row with an (i) a reel's comments can start with
     * ("About comments on this reel" in English) into the comment list, handed the list and the
     * row. Adds the row as Instagram does, or with the switch on leaves it out, so the comments start
     * with the first one. Read as the list is built, so the switch needs no restart. A failed switch
     * read adds the row; the add itself throws only what Instagram's own add would.
     */
    public static void aboutRow(Object list, Object row) {
        addAboutRow(list, row, CommentAboutButton::switchedOn);
    }

    @SuppressWarnings("unchecked")
    static void addAboutRow(Object list, Object row, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.HIDE_COMMENTS);
        boolean hide;
        try {
            hide = on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_COMMENTS, SWITCH, failure);
            hide = false;
        }
        HookStatus.counted(FamilyNames.HIDE_COMMENTS, hide ? COUNT_ROW_HIDDEN : COUNT_ROW_SHOWN);
        if (hide) {
            if (!rowLogged) {
                rowLogged = true;
                Logger.printInfo(() -> "Hide comments: left the row with an (i) out of a reel's comments");
            }
            return;
        }
        ((Collection<Object>) list).add(row);
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_COMMENT_ABOUT_BUTTON.get();
    }
}
