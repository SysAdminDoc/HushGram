/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

/**
 * Filled by Save comment photo after every native boundary has been validated. On a build where the
 * comment screen's Reply or GIF send can't be told apart, they stay as they are, so no Reply with
 * this GIF row is ever added.
 */
public final class CommentGifReplyNative {
    private CommentGifReplyNative() {}

    /** A native display row using Instagram's own GIF icon and its "send a GIF reply" label. */
    public static Object newRow(Object callback) { return null; }

    /** The callback of an owned Reply with this GIF row, or null for stock and unsupported rows. */
    public static Object callback(Object row) { return null; }

    /** Instagram's own Reply on the comment screen [screen], for the comment its menu was opened on. */
    public static void reply(Object screen, String id, String parent) {}

    /** What the comment box of [screen] replies to now, or null when it replies to nothing. */
    public static Object target(Object screen) { return null; }

    /** The send model of a comment's GIF, or null for anything else. */
    public static Object info(Object gif) { return null; }

    /** Instagram's own GIF send on [screen], to whatever its comment box replies to. */
    public static void send(Object screen, Object activity, Object info) {}
}
