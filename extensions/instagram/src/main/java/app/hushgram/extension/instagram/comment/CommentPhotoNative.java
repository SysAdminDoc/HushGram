/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

/**
 * Filled by the patch after every native boundary has been validated. Each read is one Instagram
 * getter, so {@link CommentPhoto} decides between them and can say which step found nothing.
 */
public final class CommentPhotoNative {
    private CommentPhotoNative() {}

    /** 1 when [comment] is Instagram's selected comment, else 0. */
    public static int selected(Object comment) { return 0; }

    /** The raw comment a selected comment keeps, or null. Only for a comment {@link #selected} took. */
    public static Object raw(Object selected) { return null; }

    /** Who wrote the raw comment, a User, or null. Only for a comment {@link #raw} answered. */
    public static Object author(Object raw) { return null; }

    /** When the raw comment was written, its created_at in seconds as a Long, or null. */
    public static Object createdAt(Object raw) { return null; }

    /** The raw comment's own GIF (giphy_media_info), or null. */
    public static Object gif(Object raw) { return null; }

    /** The raw comment's own media_comment_info, never its parent post's, or null. */
    public static Object info(Object raw) { return null; }

    /** The Media inside a media_comment_info, or null. */
    public static Object media(Object info) { return null; }

    /** The Media's media_type, an Integer, or null. */
    public static Object kind(Object media) { return null; }

    /** The Media's own GIF (giphy_media_info), or null. */
    public static Object mediaGif(Object media) { return null; }

    /** The Media's video_versions, a List, or null. */
    public static Object videoVersions(Object media) { return null; }

    /** The Media's video_duration, a Double, or null. */
    public static Object videoDuration(Object media) { return null; }

    /** The media_type value Instagram gives a still photo. */
    public static int photoKind() { return 0; }

    /** A native display row using Instagram's Save label, icon and normal style. */
    public static Object newRow(Object callback) { return null; }

    /** The callback of an owned Save row, or null for stock, Copy and unsupported rows. */
    public static Object callback(Object row) { return null; }
}
