/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.List;

/**
 * What a chat's preview reads and sends through Instagram. Instagram's names change with every
 * release, so none is named here: each method below answers null, false or does nothing as built,
 * and the "Preview chats" patch writes its body at patch time. The message readers take
 * Instagram's message object and the rest its account and chat key, all handed over as Objects; a
 * body casts them, so a wrong type throws and {@link ChatPreview} catches that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class PreviewMessages {
    private PreviewMessages() {
    }

    /**
     * The messages this phone keeps for the chat, oldest first when [sorted], read from Instagram's
     * chat store for the account. Nothing is fetched or marked seen.
     */
    public static List<Object> messages(Object session, Object key, boolean sorted) {
        return null;
    }

    /**
     * Sends [text] to the chat through Instagram's own composer sender, the one a message typed in
     * the inbox goes through. [spare] is the register the body loads Instagram's bucket name into.
     */
    public static void send(Object session, Object key, String text, String spare) {
    }

    /** Whether this object is a direct message. */
    public static boolean isMessage(Object candidate) {
        return false;
    }

    /** The message's id. */
    public static String id(Object message) {
        return null;
    }

    /** The id of the account that sent it. */
    public static String sender(Object message) {
        return null;
    }

    /** Its text, null for a message with none. */
    public static String text(Object message) {
        return null;
    }

    /** Its time as Instagram stores it, microseconds written as text, or null. */
    public static String time(Object message) {
        return null;
    }

    /** The name of its type, like TEXT or MEDIA, or null when it has none. */
    public static String kind(Object message) {
        return null;
    }

    /** Whether Instagram marked it as sent by the signed-in account. */
    public static boolean sentByViewer(Object message) {
        return false;
    }
}
