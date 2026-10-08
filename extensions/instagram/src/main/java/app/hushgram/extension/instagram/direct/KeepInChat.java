/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Keep in chat" patch.
 *
 * <p>A photo or video message carries a view mode: "once" (view once), "replayable" (allow replay)
 * or "permanent" (Keep in chat). Instagram reads it to decide whether the message shows in the chat
 * or as a tap-to-view bubble that's gone after you've looked. The patch hands the view mode to
 * {@link #viewMode} as each message's media is read, so while the switch is on the first two read
 * as Keep in chat.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram gets the view mode the server sent.
 */
public final class KeepInChat {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    static final String ONCE = "once";
    static final String REPLAYABLE = "replayable";
    static final String PERMANENT = "permanent";

    private static volatile boolean logged;

    private KeepInChat() {
    }

    /**
     * Called with the view mode of each photo or video message as Instagram reads it. Returns
     * "permanent" for view once and replayable media while the switch is on, and the mode it was
     * given otherwise. Never throws.
     */
    public static String viewMode(String mode) {
        return viewMode(mode, KeepInChat::switchedOn);
    }

    static String viewMode(String mode, BooleanSupplier on) {
        if (!ONCE.equals(mode) && !REPLAYABLE.equals(mode)) {
            return mode;
        }
        try {
            HookStatus.invoked(FamilyNames.KEEP_IN_CHAT);
            if (!on.getAsBoolean()) {
                return mode;
            }
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Keep in chat: kept a " + mode + " photo or video in the chat");
            }
            return PERMANENT;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.KEEP_IN_CHAT, SWITCH, t);
            return mode;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_IN_CHAT.get();
    }
}
