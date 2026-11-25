/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.DialogInterface;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for "Follow without the review sheet" (#867), a switch of the "Ask before a follow" patch.
 *
 * <p>For some accounts (new ones, ones that changed their name lately) a tap on Follow opens
 * Instagram's "Review this account before following" sheet first: when the account joined, its
 * former usernames and where it's based, over a Follow button that does the follow. Every place
 * that opens that sheet hands it the Follow button's listener. The patch asks {@link #skip} first
 * thing where the sheet is built, and with the switch on the listener runs right away, the way the
 * sheet's Follow button runs it (no dialog, the positive button), so one tap follows.
 */
public final class FollowReview {
    static final String SKIPPED = "review sheets skipped";
    private static final String SKIP = "follow review sheet";

    /** Whether a skip is running the Follow listener now, so a sheet opened from inside it shows. */
    private static boolean skipping;

    private FollowReview() {
    }

    /**
     * Injected first thing where Instagram builds the review sheet, with the sheet's Follow listener
     * ([follow]). True when the switch is on and the listener ran, so the sheet isn't shown; false
     * shows Instagram's sheet as usual. Never throws.
     */
    public static boolean skip(@Nullable Object follow) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_FOLLOW);
            if (skipping || !(follow instanceof DialogInterface.OnClickListener)) return false;
            if (!Utils.settingsReady() || !Settings.SKIP_FOLLOW_REVIEW.get()) return false;
            skipping = true;
            try {
                ((DialogInterface.OnClickListener) follow).onClick(null, DialogInterface.BUTTON_POSITIVE);
            } finally {
                skipping = false;
            }
            HookStatus.counted(FamilyNames.ASK_BEFORE_FOLLOW, SKIPPED);
            Logger.printDebug(() -> "Follow review sheet skipped, its Follow ran");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_FOLLOW, SKIP, failure);
            return false;
        }
    }
}
