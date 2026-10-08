/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/**
 * Instagram's chats, read and written for a chat marked read by hand. Instagram's names change
 * with every release, so none is named here: each method below answers null or does nothing as
 * built, and the "Read messages without the seen receipt" patch writes its body at patch time, as
 * the call or field read it finds in Instagram's own handler for marking a chat read, or in its
 * handler that sends a chat's seen receipt. A method whose body wasn't written answers null, and
 * the chat is then left as it is, its receipt held.
 *
 * <p>Every argument is Instagram's own object, handed over as an Object, of the type the method
 * names. The written bodies cast it, so a wrong type throws, and the callers catch that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class InstagramChats {
    private InstagramChats() {
    }

    /** The id of the account a session is signed in to. */
    public static String accountId(Object session) {
        return null;
    }

    /** A chat key's thread id. */
    public static String threadId(Object key) {
        return null;
    }

    /** The key of the chat a queued seen receipt is for. */
    public static Object receiptKey(Object receipt) {
        return null;
    }

    /** The id of the message a queued seen receipt points at, the item its request names. */
    public static String receiptMessage(Object receipt) {
        return null;
    }

    /** The message a chat's seen receipt points at, picked the way Instagram's handler for marking it read picks it. */
    public static Object lastMessage(Object thread) {
        return null;
    }

    /** A message's id. */
    public static String messageId(Object message) {
        return null;
    }

    /** The id of the account that sent a message. */
    public static String senderId(Object message) {
        return null;
    }

    /**
     * Queues a chat's seen receipt through Instagram's own sender. {@code disappearing} is what
     * Instagram passes for a disappearing message, and its handler for marking a chat read passes
     * null.
     */
    public static void sendSeen(Object session, Object disappearing, String thread, String message, String sender) {
    }

    /** Puts a chat's unread mark on, or with false takes it off, through Instagram's own call. */
    public static void markUnread(Object session, Object key, boolean unread) {
    }
}
