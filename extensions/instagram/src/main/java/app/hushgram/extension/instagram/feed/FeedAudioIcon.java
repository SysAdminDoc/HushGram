/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide the audio icon on posts patch (#464): keeps the small speaker circle Instagram
 * draws in the corner of a feed video off the video.
 *
 * <p>Instagram 453 builds one state object for each feed post it draws, and the state carries a flag
 * for hiding the audio icon, which Instagram's own code reads before it draws the icon or the icon's
 * slide-in view. The patch hands the flag to {@link #hides} as the state is built, and a yes sets it, so
 * both drawing paths take their own way past the icon. Nothing else about the video changes, and a
 * tap on it still turns the sound on or off. A post drawn before the switch changed keeps what it was
 * built with, until Instagram builds it again as the feed refreshes or the post scrolls back in.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, the flag stays what Instagram worked out.
 */
public final class FeedAudioIcon {
    /** The step a failed switch read is reported under. */
    static final String HOOK = "feed audio icon";
    static final String COUNT_HIDDEN = "feed audio icons hidden";

    private FeedAudioIcon() {
    }

    /**
     * Injected where a feed post's state stores whether the audio icon is hidden, handed over as an
     * int that reads any non-zero as yes (BooleanHookParametersTest). True when Instagram already
     * hides the icon or the switch is on. Never throws, and never waits for the settings.
     */
    public static boolean hides(int hidden) {
        if (hidden != 0) return true;
        try {
            HookStatus.invoked(FamilyNames.FEED_AUDIO_ICON);
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_AUDIO_ICON.get()) return false;
            HookStatus.counted(FamilyNames.FEED_AUDIO_ICON, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUDIO_ICON, HOOK, failure);
            return false;
        }
    }
}
