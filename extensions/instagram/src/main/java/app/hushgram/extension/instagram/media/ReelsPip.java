/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Picture in picture for reels, the second switch of the Keep playing in the background patch
 * (#572). Instagram has a small window for reels already: as you leave the Reels viewer, the reel
 * shrinks into a window over your other apps and keeps playing. It only sets that up when a server
 * check and its own switch (a preference that starts off) both say yes, and both are off for most
 * accounts. The patch asks {@link #on()} at the top of each check and answers yes for it while the
 * switch is on. The viewer reads them as it opens, so the switch takes effect the next time Reels
 * or a reel opens.
 */
public final class ReelsPip {
    static final String HOOK = "picture in picture";

    private ReelsPip() {
    }

    /** Whether Instagram's reels picture in picture checks answer yes without asking further. */
    public static boolean on() {
        return on(ReelsPip::wanted);
    }

    static boolean on(BooleanSupplier wanted) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            return wanted.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, failure);
            return false;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.REELS_PICTURE_IN_PICTURE.get();
    }
}
