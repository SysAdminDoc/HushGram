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
 * Helper for the "Pinch to zoom on Reels" patch.
 *
 * <p>Instagram already zooms a reel under two fingers, behind two server flags it reads once a
 * session into its Reels zoom settings: one puts a reel's video in its zoomable component, and
 * the other has the Reels gesture detector start a zoom when a second finger lands. Lifting the
 * fingers lets the video go back, which is Instagram's own handling too. The patch answers both
 * reads through {@link #zoom(int)}, so a change shows after a restart.
 */
public final class ReelZoom {
    private ReelZoom() {
    }

    /**
     * Both zoom flags: Instagram's answer ([answer] nonzero for yes), or yes while the switch is
     * on. Instagram's answer while the switch is off, HushGram is paused or the settings aren't
     * ready. Never throws.
     */
    public static boolean zoom(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_ZOOM);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ZOOM, "reel zoom flag", failure);
            return instagram;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.PINCH_ZOOM_REELS.get();
    }
}
