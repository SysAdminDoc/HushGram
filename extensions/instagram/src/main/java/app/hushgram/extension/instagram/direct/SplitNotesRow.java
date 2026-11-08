/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Split the notes row" patch (#558).
 *
 * <p>Instagram is testing a new top for your messages: the row of notes becomes three stacks side
 * by side, Instants, Notes and Stories, each opening its own list. It's behind one server test,
 * read when your messages open, when the notes row is set up and in two places that get its
 * contents ready. The patch answers every read of that test through {@link #split(int)}, so all of
 * them agree, the way they would for an account in the test.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer goes through untouched, so an account already in the
 * test keeps the split row.
 */
public final class SplitNotesRow {
    private SplitNotesRow() {
    }

    /** The test's answer: Instagram's ([answer] nonzero for yes), or yes while the switch is on. Never throws. */
    public static boolean split(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.SPLIT_NOTES_ROW);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPLIT_NOTES_ROW, "split notes row flag", failure);
            return instagram;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SPLIT_NOTES_ROW.get();
    }
}
