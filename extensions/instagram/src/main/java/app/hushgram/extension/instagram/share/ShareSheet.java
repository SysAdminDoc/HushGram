/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide group buttons on the share sheet" patch.
 *
 * <p>The share sheet puts a New group button beside its search bar. Depending on a server value it's
 * a button of its own, built from a stub, or an action inside the search box, and either way one
 * method of the sheet reveals it once the sheet is set up. The patch asks {@link #hideGroupButton}
 * first thing in the method that builds the button, so the stub is never built and takes no room,
 * and {@link #hideGroupAction} right after each place the sheet shows the search box's own New
 * group action, which then turns that action off again. Once you pick people, the bar under the
 * sheet can also offer to send to them as a group, and the patch asks {@link #hideGroupSend}
 * before it shows that button.
 */
public final class ShareSheet {
    private static volatile boolean logged;

    private ShareSheet() {
    }

    /** Answers true while Hide group buttons is on, so the sheet doesn't build its New group button. */
    public static boolean hideGroupButton() {
        return hidden("group button");
    }

    /**
     * Answers true while Hide group buttons is on, right after the sheet shows the search box's New
     * group action, so the patch turns that action off again.
     */
    public static boolean hideGroupAction() {
        return hidden("group action");
    }

    /**
     * Answers true while Hide group buttons is on, so the bar under the sheet puts its send-as-group
     * button away instead of showing it for the people you picked. Send separately stays.
     */
    public static boolean hideGroupSend() {
        return hidden("group send");
    }

    /**
     * True while Hide group buttons is on, and false before the settings are ready or when anything goes
     * wrong, so the button stays. Never throws, and never waits for the settings.
     */
    private static boolean hidden(String where) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (!Utils.settingsReady() || !Settings.HIDE_SHARE_SHEET_GROUP.get()) return false;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Share sheet: left out the group buttons");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, where, failure);
            return false;
        }
    }
}
