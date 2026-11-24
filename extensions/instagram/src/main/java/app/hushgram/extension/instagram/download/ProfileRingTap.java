/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A story ring opens the story (#823): a tap on the picture at the top of a profile with a story ring
 * opens the story whenever Instagram has it, where Instagram sometimes opens the picture instead.
 *
 * <p>Instagram picks what a tap on that picture does from the profile header's state. Before the
 * header has the account's story, which on a slow connection is most of the time, that state says
 * the picture even while the ring shows. Instagram then has a second look in its own store of
 * stories and opens the story it finds there, but only where a server flag allows it. The patch
 * passes that one read of the flag through {@link #storyFirst}, which answers yes while the switch
 * is on. Instagram's own checks stay: with no ring, or no story in the store, the tap opens the
 * picture as before, and a long press is never touched.
 */
public final class ProfileRingTap {
    static final String ROUTE = FamilyNames.PROFILE_PICTURE;

    /** Counted when the switch turned Instagram's no into a look for the story. Fixed text. */
    static final String COUNT_STORY_FIRST = "ring taps that looked for the story";

    private static volatile boolean logged;

    private ProfileRingTap() {
    }

    /**
     * Injected right after the profile picture tap reads Instagram's flag for opening a stored story
     * first, with its answer as an int (non-zero is yes). Answers true while the switch is on, and
     * Instagram's answer otherwise: before the settings are ready, while paused, or when anything goes
     * wrong. Never throws.
     */
    public static boolean storyFirst(int enabled) {
        try {
            HookStatus.invoked(ROUTE);
            if (enabled != 0) return true;
            if (!Utils.settingsReady() || HushgramPause.isPaused() || !Settings.RING_TAP_OPENS_STORY.get()) return false;
            HookStatus.counted(ROUTE, COUNT_STORY_FIRST);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Profile ring tap: looking for the story before the picture");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "ring tap", failure);
            return enabled != 0;
        }
    }
}
