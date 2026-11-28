/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Stop story side taps, the second switch of the "Stop swipe up to reply" patch (#887).
 *
 * <p>A tap on a story reaches the story viewer's item delegate once the finger is up and the tap
 * is confirmed. After the stickers, links and other parts of the story have had their turn, the
 * delegate hands what's left to the viewer: a tap on the left side to the viewer's tap back
 * handler, any other tap to its tap forward handler. The two handlers log the tap and move to the
 * story before or after. The patch asks {@link #keepsTap()} first thing in both, and while the
 * switch is on they return before anything moves. The story keeps playing, since holding a finger
 * on it pauses and resumes it on the touch itself, before the tap is confirmed.
 *
 * <p>Swiping between people's stories, the story ending on its own and Instagram's own next and
 * previous buttons call the viewer's moves directly, so they stay Instagram's. So do taps on a
 * sticker, a link or a mention.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the tap does what Instagram does.
 */
public final class StoryTaps {
    static final String TAP = "story side tap";

    private StoryTaps() {
    }

    /**
     * Injected first thing in the story viewer's tap forward and tap back handlers. True lets the
     * tap move to the story after or before; false keeps the story on screen. Never throws.
     */
    public static boolean keepsTap() {
        return keepsTap(StoryTaps::switchedOn);
    }

    static boolean keepsTap(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SWIPE_REPLY);
            if (!on.getAsBoolean()) return true;
            Logger.printDebug(() -> "Story side tap: kept the story on screen");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SWIPE_REPLY, TAP, failure);
            return true;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.STOP_STORY_SIDE_TAPS.get();
    }
}
