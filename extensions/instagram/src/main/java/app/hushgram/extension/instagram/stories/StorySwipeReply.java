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
 * Helper for the "Stop swipe up to reply" patch.
 *
 * <p>An upward swipe on someone's story reaches the story viewer's item delegate, which hands it to
 * the viewer's listeners. One of them, the reply composer's, focuses the "Send message" box and
 * opens the keyboard with the emoji reactions above it, and answers that it took the swipe. Behind a
 * server test, the delegate can open a reply sheet itself first. The patch asks {@link #keepsSwipe()}
 * at both places: first thing in the composer's listener, and right before the delegate opens the
 * sheet. While the switch is on, the listener answers that it didn't take the swipe and the delegate
 * goes on to the listeners instead of the sheet, which is what happens on a story nobody can reply
 * to. Nothing opens and the story keeps playing.
 *
 * <p>A tap on the box, the reply button and every other gesture take other paths, so they stay
 * Instagram's own. Your own story has no reply box, so its swipe up, which opens its viewers, never
 * reaches either place.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the swipe does what Instagram does.
 */
public final class StorySwipeReply {
    static final String SWIPE = "story swipe up";

    private static volatile boolean logged;

    private StorySwipeReply() {
    }

    /**
     * Injected at both places an upward swipe on a story opens a reply. True lets the swipe do what
     * Instagram does; false stops it there. Never throws.
     */
    public static boolean keepsSwipe() {
        return keepsSwipe(StorySwipeReply::switchedOn);
    }

    static boolean keepsSwipe(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SWIPE_REPLY);
            if (!on.getAsBoolean()) return true;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Story swipe up: left the reply box closed");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SWIPE_REPLY, SWIPE, failure);
            return true;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.STOP_STORY_SWIPE_REPLY.get();
    }
}
