/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

/**
 * Filled by Copy comment after every native boundary has been validated. On a build where the
 * comment's author or the menu's label read can't be told apart, they stay as they are, so no
 * Copy username row is ever added.
 */
public final class CommentAuthorNative {
    private CommentAuthorNative() {}

    /** The account that wrote this selected comment, Instagram's User; unsupported objects answer null. */
    public static Object author(Object comment) { return null; }

    /** A native display row using Instagram's Copy icon, with its label answered by {@link CommentAuthor#label}. */
    public static Object newRow(Object callback) { return null; }

    /** The callback of an owned Copy username row, or null for stock and unsupported rows. */
    public static Object callback(Object row) { return null; }
}
