/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Make ephemeral media permanent" patch.
 *
 * <p>A photo or video someone sends as view once or replay once arrives with a view mode saying so
 * and a time it stops being viewable. The patch hands both to {@link #mode} as Instagram reads the
 * message, and it answers "permanent" for one that hasn't expired, so the chat shows it like any
 * other photo or video. A message that has expired, one with no expiry, and every other view mode
 * go through as they came.
 *
 * <p>Instagram's server still holds the sender's setting: this changes how the app on this phone
 * treats the message it was sent, not what the sender chose.
 */
public final class EphemeralMedia {
    /** The view mode Instagram gives a message that can be viewed any number of times. */
    static final String PERMANENT = "permanent";

    private EphemeralMedia() {
    }

    /**
     * @param expiresAt when the message stops being viewable, in seconds since 1970, or null
     * @param viewMode  how many times it may be viewed, as Instagram wrote it
     */
    public static String mode(Long expiresAt, String viewMode) {
        return mode(expiresAt, viewMode, System.currentTimeMillis(), () -> Settings.KEEP_EPHEMERAL_MEDIA.get());
    }

    static String mode(Long expiresAt, String viewMode, long now, BooleanSupplier on) {
        try {
            if (expiresAt == null || viewMode == null || PERMANENT.equals(viewMode)) return viewMode;
            HookStatus.invoked(FamilyNames.EPHEMERAL_MEDIA);
            if (now > expiresAt * 1000L) return viewMode;
            return Utils.settingsReady() && on.getAsBoolean() ? PERMANENT : viewMode;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EPHEMERAL_MEDIA, "view mode", failure);
            return viewMode;
        }
    }
}
