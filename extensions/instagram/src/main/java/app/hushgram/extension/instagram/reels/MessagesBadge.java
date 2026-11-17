/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hide the Messages badge (#734): the unread count stays off the Messages button of the tab bar.
 *
 * <p>Instagram's tab badge controller keeps the unread count it's handed, then puts the count on the
 * Messages button when it's above zero and takes the badge away when it isn't. The patch hands the
 * count to {@link #count} right before the controller keeps it, and with the switch on the answer is
 * 0, so Instagram takes its own path for nothing unread. The inbox reads its own counts, so chats
 * still show as unread there, and notifications come as before.
 */
public final class MessagesBadge {
    /** What the report counts each time a count is held back. */
    static final String COUNT_HIDDEN = "Messages badge hidden";

    private MessagesBadge() {
    }

    /** The count the Messages button shows: 0 with the switch on, [unread] otherwise. Never throws. */
    public static int count(int unread) {
        try {
            if (unread <= 0 || !hiding()) return unread;
            HookStatus.counted(FamilyNames.REELS_TAB, COUNT_HIDDEN);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "messages badge", failure);
            return unread;
        }
    }

    /** Whether the count is held back: the switch on, HushGram not paused. */
    static boolean hiding() {
        return Utils.settingsReady() && Settings.HIDE_MESSAGES_BADGE.get();
    }
}
