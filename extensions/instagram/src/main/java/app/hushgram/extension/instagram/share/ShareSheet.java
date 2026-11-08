/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import android.view.View;

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
 * before it shows that button. Apart from the group switch, it hands {@link #searchBox} the sheet's
 * search bar once the sheet has found it, for Hide the search bar.
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
     * Takes the share sheet's search bar away while Hide the search bar is on (#545). The patch calls
     * this once the sheet has found the bar, but a few lines later the sheet shows the bar itself, so
     * the bar also goes again each time the sheet lays it out. Leaves the bar as it is before the
     * settings are ready, with the switch off, or when anything goes wrong. Never throws.
     */
    public static void searchBox(View box) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (box == null || !Utils.settingsReady() || !Settings.HIDE_SHARE_SHEET_SEARCH.get()) return;
            box.setVisibility(View.GONE);
            box.removeOnLayoutChangeListener(KEEP_HIDDEN);
            box.addOnLayoutChangeListener(KEEP_HIDDEN);
            Logger.printDebug(() -> "Share sheet: hid the search bar");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "search bar", failure);
        }
    }

    /**
     * Hides the search bar again whenever the sheet lays it out while the switch is on. A gone view
     * is never laid out, so this only acts after something showed the bar.
     */
    private static final View.OnLayoutChangeListener KEEP_HIDDEN =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    if (view.getVisibility() == View.GONE || !Settings.HIDE_SHARE_SHEET_SEARCH.get()) return;
                    view.setVisibility(View.GONE);
                    Logger.printDebug(() -> "Share sheet: hid the search bar the sheet showed again");
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.SHARE_SHEET, "search bar", failure);
                }
            };

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
