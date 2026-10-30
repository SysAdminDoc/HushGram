/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Swipe through a chat's reels" patch (#350).
 *
 * <p>Instagram already knows how to chain the reels shared in a chat, behind a server test. With
 * the test on, a reel opened from a chat takes the chat's other shared reels along into the Reels
 * viewer, so swiping up goes through the ones shared there before Instagram's suggested reels, and
 * Back still lands in the chat. The patch answers the test's one read through {@link #chain(int)}.
 *
 * <p>The viewer gets a chat's reels one of two ways: handed all of them when it opens, then asking
 * for more past them, which are suggested reels, or paging a source with two sides, the chat's own
 * shared reels and then Instagram's suggested ones. Near its last reel it also puts in reels
 * Instagram keeps cached for the Reels tab, without asking the server. The patch asks
 * {@link #stop()} before a viewer opened from a chat asks for more, and before it asks for that
 * second side, and {@link #stopCached(Object)} before a cached reel goes in, so with the second
 * switch on it ends after the chat's reels (#390). The viewer also puts a loading placeholder
 * after the reels it adds, for the next ones to replace, which {@link #placeholder(Object, int)}
 * leaves out there, so the last page is the chat's last reel and not a gray one that never loads.
 */
public final class ChatReels {
    /** The name of Instagram's viewer source for a reel opened from a chat. */
    private static final String CHAT_SOURCE = "DIRECT";

    private ChatReels() {
    }

    /**
     * The chat reels test: Instagram's answer ([answer] nonzero for yes), or yes while the switch is
     * on. Instagram's answer while the switch is off, HushGram is paused or the settings aren't
     * ready. Never throws.
     */
    public static boolean chain(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return instagram || (Utils.settingsReady() && Settings.SWIPE_CHAT_REELS.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels flag", failure);
            return instagram;
        }
    }

    /**
     * Whether the viewer of a chat's reels skips asking for suggested reels once the chat's own run
     * out: yes while the switch is on. No while it's off, HushGram is paused or the settings aren't
     * ready, so Instagram goes on to its suggestions. Never throws.
     */
    public static boolean stop() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return Utils.settingsReady() && Settings.STOP_AFTER_CHAT_REELS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels end", failure);
            return false;
        }
    }

    /**
     * Whether the viewer skips putting in one of Instagram's cached reels: yes while the switch is on
     * and [source], the viewer's ClipsViewerSource, is a chat's. No for any other viewer, and while
     * the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean stopCached(Object source) {
        return source instanceof Enum && CHAT_SOURCE.equals(((Enum<?>) source).name()) && stop();
    }

    /**
     * Whether the viewer puts its loading placeholder after the reels it adds: Instagram's [ghost]
     * (nonzero for yes), except no while {@link #stopCached(Object)} is yes for [source], since
     * nothing would replace it. Never throws.
     */
    public static boolean placeholder(Object source, int ghost) {
        return ghost != 0 && !stopCached(source);
    }
}
