/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop videos looping" patch.
 *
 * <p>Instagram's video player, the one feed videos and reels play through, hands its player engine
 * a loop flag each time it starts a video and each time something changes it. The patch passes
 * that flag through {@link #loop(Object, int)} on its way in, so with the switch on the engine is
 * told not to loop, and a video stops on its last frame instead of playing again. Stories play
 * through another player and keep their own timer, so they move on as before.
 *
 * <p>Instagram's Reels auto scroll, and Move on when a reel ends, move to the next reel when the
 * engine reports that the reel looped. A reel that doesn't loop never reports it and would sit on
 * its last frame, so a reel in the Reels viewer keeps looping while either of them is on, and the
 * scroller moves on as before.
 */
public final class StopLooping {
    /** Whether auto scroll is moving Reels on, when a test says so instead of the settings. */
    @Nullable
    static volatile Boolean autoScrollsForTests;

    /** Whether a player is the Reels viewer's, when a test says so instead of {@link #reels(Object)}. */
    @Nullable
    static volatile Boolean reelsForTests;

    private StopLooping() {
    }

    /**
     * The loop flag for [player]'s engine: Instagram's ([loop] nonzero for yes), or no while the
     * switch is on, except for a reel in the Reels viewer while auto scroll is moving Reels on.
     * Instagram's flag while the switch is off, HushGram is paused or the settings aren't ready.
     * Never throws.
     */
    public static boolean loop(Object player, int loop) {
        boolean instagram = loop != 0;
        if (!instagram) {
            return false;
        }
        try {
            HookStatus.invoked(FamilyNames.STOP_LOOPING);
            if (!on()) {
                return true;
            }
            return autoScrolls() && inReels(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STOP_LOOPING, "video loop flag", failure);
            return instagram;
        }
    }

    /**
     * Whether [player] plays in the Reels viewer. The patch writes the body, an instance-of on the
     * video logger the player was made with; false in an unpatched build.
     */
    public static boolean reels(Object player) {
        return false;
    }

    /**
     * Whether Reels moves on by itself when a reel ends: Move on when a reel ends, or Instagram's own
     * auto scroll as Keep Reels auto scroll on last saw it. Both need that patch in the build.
     */
    static boolean autoScrolls() {
        Boolean forced = autoScrollsForTests;
        if (forced != null) {
            return forced;
        }
        return SettingsStatus.reelAutoScroll()
                && (Settings.REEL_AUTO_ADVANCE.get() || Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    private static boolean inReels(Object player) {
        Boolean forced = reelsForTests;
        return forced != null ? forced : reels(player);
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.STOP_VIDEO_LOOPING.get();
    }
}
