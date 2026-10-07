/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.privacy;

import java.io.IOException;
import java.net.URI;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * What the privacy patches ask Instagram's code to skip: telling people you read their chat, that
 * you're typing, that you took a screenshot, or that you're watching their live video.
 *
 * <p>Every answer is read as the hook runs, so a switch takes effect at once. Each one is no while
 * the switch is off, HushGram is paused, the settings aren't ready, or when anything in here fails,
 * so Instagram carries on exactly as before.
 */
public final class Ghost {
    /** The path of the request a live video's viewers send every few seconds to be counted. */
    static final String LIVE_HEARTBEAT = "/heartbeat_and_get_viewer_count/";

    private Ghost() {
    }

    /** The "mark this chat read" request is held back: the other person's chat shows no Seen. */
    public static boolean holdChatSeen() {
        return on(FamilyNames.VIEW_CHATS, Settings.VIEW_CHATS_ANONYMOUSLY::get);
    }

    /** The composer doesn't tell the chat you're typing. */
    public static boolean holdTyping() {
        return on(FamilyNames.TYPING_STATUS, Settings.DISABLE_TYPING_STATUS::get);
    }

    /** A screenshot of a chat or story isn't reported. */
    public static boolean holdScreenshots() {
        return on(FamilyNames.SCREENSHOT_DETECTION, Settings.DISABLE_SCREENSHOT_DETECTION::get);
    }

    /**
     * Handed the address of every request Instagram is about to start. Throws for a live video's
     * viewer heartbeat while the switch is on, so Instagram treats the request as one that couldn't
     * be sent, and answers normally for everything else. Only an {@link IOException} leaves it, the
     * kind a failed request already gives, and never anything for a request it doesn't hold back.
     */
    public static void gate(URI address) throws IOException {
        boolean hold;
        try {
            hold = heartbeat(address) && on(FamilyNames.VIEW_LIVE, Settings.VIEW_LIVE_ANONYMOUSLY::get);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIEW_LIVE, "request check", failure);
            return;
        }
        if (hold) {
            Logger.printDebug(() -> "Live: held back the viewer heartbeat");
            throw new IOException("HushGram held back a live viewer heartbeat");
        }
    }

    static boolean heartbeat(URI address) {
        String path = address == null ? null : address.getPath();
        return path != null && path.contains(LIVE_HEARTBEAT);
    }

    private static boolean on(String family, BooleanSupplier switchedOn) {
        try {
            HookStatus.invoked(family);
            return Utils.settingsReady() && switchedOn.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(family, "switch read", failure);
            return false;
        }
    }
}
