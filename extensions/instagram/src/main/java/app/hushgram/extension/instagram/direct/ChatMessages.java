/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/**
 * Instagram's direct message, read field by field. Instagram's names change with every release, so
 * none is named here: each method below answers null, false or does nothing as built, and the "Chat
 * history tools" patch writes its body at patch time from the message parser's own stores. Every
 * argument is Instagram's message object, handed over as an Object; a body casts it, so a wrong
 * type throws and the callers catch that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class ChatMessages {
    private ChatMessages() {
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

    /** Its time as Instagram stores it, a number written as text, or null. */
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
