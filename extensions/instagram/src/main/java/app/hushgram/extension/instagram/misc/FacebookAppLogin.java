/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Part of "Restore trust on re-signed builds": where Instagram's Facebook login hands off to the
 * Facebook app.
 *
 * <p>Logging in with Facebook, Instagram first looks for the Facebook app (or Messenger, or the
 * Facebook beta) and, when it finds one signed by Meta, asks it to log in for Instagram. The
 * Facebook app checks who signed the app asking, and it turns a re-signed Instagram away: it shows
 * "The link you followed may be broken" and comes back cancelled, and Instagram ends the login
 * there, so its screen keeps spinning (#760). Instagram's own check only lets a Meta-signed app
 * through, so on a re-signed build every app it could pick would refuse it.
 *
 * <p>On a re-signed build the lookup answers that no such app is installed. Instagram then moves
 * on to its next way in, a Facebook login page in the browser, the same as on a phone without the
 * Facebook app. A build that still carries Meta's key, and any build whose key can't be read,
 * looks the app up as before. Like the rest of the patch, this reads no setting, so Pause doesn't
 * change it.
 */
public final class FacebookAppLogin {

    private FacebookAppLogin() {}

    /** The name Hook status counts a skipped hand-off under. */
    static final String SKIPPED = "facebook app login skipped";

    /**
     * Stands in for {@code packages.resolveActivity(intent, flags)} where Instagram looks for the
     * Facebook app to log in with: null on a re-signed build, the system's answer otherwise.
     */
    @Nullable
    public static ResolveInfo resolveActivity(PackageManager packages, Intent intent, int flags) {
        try {
            if (InstagramSignature.isReSigned(packages)) {
                HookStatus.counted(FamilyNames.RESTORE_TRUST, SKIPPED);
                return null;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RESTORE_TRUST, "facebook app login", failure);
        }
        return packages.resolveActivity(intent, flags);
    }
}
