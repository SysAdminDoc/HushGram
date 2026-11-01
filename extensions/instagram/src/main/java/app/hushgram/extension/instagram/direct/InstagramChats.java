/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/**
 * Instagram's chats, read and written for a chat marked read by hand. Instagram's names change
 * with every release, so none is named here: each method below answers null or does nothing as
 * built, and the "Read messages without the seen receipt" patch writes its body at patch time
 * ({@link #accountId} is written by "Lock your messages" too), as
 * the call or field read it finds in Instagram's own handler for marking a chat read, in its
 * handler that sends a chat's seen receipt, in its chat store, or in the summary its inbox rows
 * are drawn from. A method whose body wasn't written answers null, and
 * the chat is then left as it is, its receipt held.
 *
 * <p>Every argument is Instagram's own object, handed over as an Object, of the type the method
 * names. The written bodies cast it, so a wrong type throws, and the callers catch that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class InstagramChats {
    private InstagramChats() {
    }

    /**
     * The id of the account a session is signed in to, as the session's own getUserId() answers it.
     * Both "Lock your messages" and "Read messages without the seen receipt" need it, so whichever
     * of them is in the build writes this body, once, and the other leaves it as written.
     */
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

    /**
     * The id of the account that sent the message a queued seen receipt points at, the one Instagram's
     * sender stores on the receipt. Null when this build's receipt keeps none the patch could read.
     */
    public static String receiptSender(Object receipt) {
        return null;
    }

    /**
     * The thread id of the chat a composer sends into, read off the object the composer keeps its
     * chat in. Null when the patch didn't write it.
     */
    public static String composerThread(Object source) {
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

    /** The session of the account a chat store belongs to, the store that records which chats were seen. */
    public static Object storeSession(Object store) {
        return null;
    }

    /** The key of the chat an inbox row's summary is for. */
    public static Object chatKey(Object thread) {
        return null;
    }

    /**
     * The id of a chat's newest message, read off the inbox row's summary the way it reads it when
     * asked whether the chat shows unread. Null when the summary has none.
     */
    public static String newestMessage(Object thread) {
        return null;
    }

    /** The account's own cursor in a chat, as Instagram's reader of it keeps it while it works out the New Messages divider. */
    public static Object cursorOf(Object reader) {
        return null;
    }

    /** The session of the account that reader is for. */
    public static Object cursorSession(Object reader) {
        return null;
    }

    /** The items of the chat [key] names that the reader walks, a List, or null when the chat isn't loaded. */
    public static Object cursorItems(Object reader, Object key) {
        return null;
    }

    /** The id of the item a cursor points at. */
    public static String cursorItem(Object cursor) {
        return null;
    }

    /** Moves a cursor to an item, by its id. */
    public static void moveCursor(Object cursor, String item) {
    }
}
