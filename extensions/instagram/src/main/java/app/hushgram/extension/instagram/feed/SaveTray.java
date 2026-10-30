/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Helper for the "Skip the collections tray" patch. */
public final class SaveTray {

    private SaveTray() {}

    /**
     * Asked first in Instagram's check of whether a save opens the tray that asks you to start a
     * collection. True makes the check answer no, so the save shows the Saved message instead. False
     * while the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean skip() {
        HookStatus.invoked(FamilyNames.SAVE_TRAY);
        try {
            return Utils.settingsReady() && Settings.SKIP_COLLECTIONS_TRAY.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SAVE_TRAY, "switch read", t);
            return false;
        }
    }
}
