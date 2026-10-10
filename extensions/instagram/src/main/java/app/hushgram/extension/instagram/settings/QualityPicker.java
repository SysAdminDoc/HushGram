/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.AlertDialog;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A small list of playback qualities over Instagram, opened by the tab long press chosen in
 * {@link Settings#QUALITY_TAB_TARGET} (#93). A pick saves {@link Settings#PLAYBACK_QUALITY}, the
 * same choice the settings screen holds, so the next video that starts plays at it.
 *
 * <p>It offers itself only while the Default playback quality patch is in the build, its switch is
 * on and HushGram isn't paused. Otherwise {@link #show} answers false and the tab keeps what it
 * did before. Never throws.
 */
final class QualityPicker {
    private QualityPicker() {
    }

    /** Whether a pick would reach the player: the patch is in this build, its switch is on, no Pause. */
    static boolean offered() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused()
                    && PatchFamily.PLAYBACK_QUALITY.inBuild() && Settings.DEFAULT_PLAYBACK_QUALITY.get();
        } catch (Throwable t) {
            Logger.printException(() -> "Quality picker: could not decide whether to offer itself", t);
            return false;
        }
    }

    /** Shows the list over [activity]. False, showing nothing, when it isn't offered or can't be shown. */
    static boolean show(@Nullable Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || !offered()) return false;
            PlaybackQuality[] qualities = PlaybackQuality.values();
            CharSequence[] labels = new CharSequence[qualities.length];
            for (int i = 0; i < qualities.length; i++) labels[i] = HushgramPreferenceFragment.playbackQualityLabel(qualities[i]);
            int checked = Settings.PLAYBACK_QUALITY.savedValue().ordinal();
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Playback quality"))
                    .setSingleChoiceItems(labels, checked, (shown, which) -> {
                        choose(qualities[which]);
                        shown.dismiss();
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.show();
            ScreenColors.dialog(dialog);
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Quality picker: could not show the list", t);
            return false;
        }
    }

    /** Saves [quality] as the playback quality and says so. */
    static void choose(PlaybackQuality quality) {
        try {
            String label = HushgramPreferenceFragment.playbackQualityLabel(quality);
            if (Settings.PLAYBACK_QUALITY.save(quality)) {
                Utils.showToastShort(L10n.f("Playback quality: %1$s. The next video you open uses it.", label));
            } else {
                Utils.showToastShort(L10n.t("Couldn't save the playback quality. Open HushGram settings to set it."));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Quality picker: could not save the pick", t);
        }
    }
}
