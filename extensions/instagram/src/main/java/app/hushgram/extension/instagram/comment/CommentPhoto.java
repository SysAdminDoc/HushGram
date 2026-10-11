/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import kotlin.jvm.functions.Function0;
import app.hushgram.extension.instagram.download.CommentPhotoDownload;
import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** An explicit Save action in the selected comment's native menu, for the comment's own photo or GIF. */
public final class CommentPhoto {
    private CommentPhoto() {}

    interface NativeRows {
        /** What [comment]'s Save row would save, its own photo's sizes or its GIF's files, or null. */
        CommentPhotoDownload.Snapshot media(Object comment);
        /** Who wrote [comment] and when, for the save's name. */
        PostDetails details(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    interface Save {
        void save(Context context, CommentPhotoDownload.Snapshot snapshot, PostDetails details);
    }

    /** The native reads behind a comment's photo or GIF, one Instagram getter each, made in this order. */
    interface PhotoReads {
        boolean selected(Object comment);
        Object raw(Object selected);
        Object gif(Object raw);
        Object info(Object raw);
        Object media(Object info);
        Object kind(Object media);
        int photoKind();
        Object mediaGif(Object media);
        Object videoVersions(Object media);
        Object videoDuration(Object media);
        Object author(Object raw);
        Object createdAt(Object raw);
        String username(Object user);
        Object gifSticker(Object gif);
        Object gifProxied(Object gif);
        Object gifImages(Object gif);
        Object gifRendition(Object images);
        Object gifUrl(Object rendition);
        Object gifWebp(Object rendition);
        Object gifMp4(Object rendition);
        Object gifWidth(Object rendition);
        Object gifHeight(Object rendition);
    }

    private static final PhotoReads READS = new PhotoReads() {
        public boolean selected(Object comment) { return CommentPhotoNative.selected(comment) != 0; }
        public Object raw(Object selected) { return CommentPhotoNative.raw(selected); }
        public Object gif(Object raw) { return CommentPhotoNative.gif(raw); }
        public Object info(Object raw) { return CommentPhotoNative.info(raw); }
        public Object media(Object info) { return CommentPhotoNative.media(info); }
        public Object kind(Object media) { return CommentPhotoNative.kind(media); }
        public int photoKind() { return CommentPhotoNative.photoKind(); }
        public Object mediaGif(Object media) { return CommentPhotoNative.mediaGif(media); }
        public Object videoVersions(Object media) { return CommentPhotoNative.videoVersions(media); }
        public Object videoDuration(Object media) { return CommentPhotoNative.videoDuration(media); }
        public Object author(Object raw) { return CommentPhotoNative.author(raw); }
        public Object createdAt(Object raw) { return CommentPhotoNative.createdAt(raw); }
        public String username(Object user) { return InstagramMedia.username(user); }
        public Object gifSticker(Object gif) { return CommentPhotoNative.gifSticker(gif); }
        public Object gifProxied(Object gif) { return CommentPhotoNative.gifProxied(gif); }
        public Object gifImages(Object gif) { return CommentPhotoNative.gifImages(gif); }
        public Object gifRendition(Object images) { return CommentPhotoNative.gifRendition(images); }
        public Object gifUrl(Object rendition) { return CommentPhotoNative.gifUrl(rendition); }
        public Object gifWebp(Object rendition) { return CommentPhotoNative.gifWebp(rendition); }
        public Object gifMp4(Object rendition) { return CommentPhotoNative.gifMp4(rendition); }
        public Object gifWidth(Object rendition) { return CommentPhotoNative.gifWidth(rendition); }
        public Object gifHeight(Object rendition) { return CommentPhotoNative.gifHeight(rendition); }
    };

    // What the diagnostic report counts as a read goes, one name per step. The names are fixed
    // text: nothing read from the comment goes in, bar a media_type kept to a small number.
    static final String NOT_SELECTED = "not a selected comment";
    static final String NO_RAW = "no raw comment";
    /** The comment carries a GIF of its own, read next as a GIF. */
    static final String COMMENT_GIF = "comment GIF";
    static final String NO_INFO = "no media_comment_info";
    static final String NO_MEDIA = "no media in media_comment_info";
    static final String NO_KIND_STILL = "no media_type, still image";
    static final String NO_KIND_VIDEO = "no media_type, has video";
    /** The comment's media carries a GIF, read next as a GIF. */
    static final String MEDIA_GIF = "media GIF";
    static final String GIF_STICKER = "GIF sticker";
    /**
     * Instagram's media types are single digits (1 photo, 2 video, 8 carousel). Anything else shares
     * one name, so a strange value can't use up the sixteen names a family's counts keep.
     */
    private static final int KINDS_NAMED = 10;

    private static final NativeRows NATIVE = new NativeRows() {
        public CommentPhotoDownload.Snapshot media(Object comment) {
            Carried carried = carried(comment, READS);
            if (carried == null) return null;
            if (carried.gif != null) return CommentPhotoDownload.gif(gifFiles(carried.gif, READS));
            return CommentPhotoDownload.Snapshot.photo(CommentPhotoDownload.snapshot(carried.photo));
        }
        public PostDetails details(Object comment) { return CommentPhoto.details(comment, READS); }
        public Object row(Object callback) { return CommentPhotoNative.newRow(callback); }
        public Object callback(Object row) { return CommentPhotoNative.callback(row); }
    };

    private static final Save SAVE = CommentPhotoDownload::save;

    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE, SAVE);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows, Save save) {
        try {
            HookStatus.invoked(FamilyNames.COMMENT_PHOTO);
            if (rows == null || context == null || comment == null || !enabled()) return rows;
            // Copied now, so the row saves the photo or GIF this menu was opened for.
            CommentPhotoDownload.Snapshot snapshot = nativeRows.media(comment);
            PostDetails details = snapshot == null ? PostDetails.NONE : details(nativeRows, comment);
            int owned = 0;
            PhotoAction existing = null;
            for (Object row : rows) {
                Object callback = nativeRows.callback(row);
                if (callback instanceof PhotoAction) {
                    owned++;
                    existing = (PhotoAction) callback;
                }
            }
            if (snapshot == null) {
                if (owned == 0) return rows;
                List<Object> cleaned = new ArrayList<>(rows.size() - owned);
                for (Object row : rows) {
                    if (!(nativeRows.callback(row) instanceof PhotoAction)) cleaned.add(row);
                }
                return cleaned;
            }
            if (owned == 1 && snapshot.sameAs(existing.snapshot)) return rows;
            Object photo = nativeRows.row(new PhotoAction(snapshot, details, context, save));
            if (photo == null) return rows;
            List<Object> augmented = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof PhotoAction)) augmented.add(row);
            }
            augmented.add(photo);
            return augmented;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "comment menu", failure);
            return rows;
        }
    }

    /** [comment]'s author and time, or nothing known when reading them fails: the save still goes ahead. */
    private static PostDetails details(NativeRows nativeRows, Object comment) {
        try {
            PostDetails details = nativeRows.details(comment);
            return details == null ? PostDetails.NONE : details;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "comment author", failure);
            return PostDetails.NONE;
        }
    }

    /**
     * Who wrote the selected comment and when it was written, read from the raw comment its photo
     * or GIF comes from, for Name saves by account and post time. The comment's author is the
     * poster. Nothing known for anything but a selected comment.
     */
    static PostDetails details(Object comment, PhotoReads reads) {
        if (!reads.selected(comment)) return PostDetails.NONE;
        Object raw = reads.raw(comment);
        if (raw == null) return PostDetails.NONE;
        Object user = reads.author(raw);
        String author = user == null ? null : reads.username(user);
        Object created = reads.createdAt(raw);
        long seconds = created instanceof Long ? (Long) created : 0;
        Date written = seconds > 0 ? new Date(seconds * 1000L) : null;
        return PostDetails.of(null, author, written);
    }

    /** What the selected comment carries of its own: the Media of a still photo, or a GIF. */
    static final class Carried {
        final Object photo;
        final Object gif;

        private Carried(Object photo, Object gif) {
            this.photo = photo;
            this.gif = gif;
        }
    }

    /**
     * What the selected comment carries of its own, or null for anything else: another object, a
     * comment with no media, a video, a GIF sticker. Each null is counted under the step that found
     * nothing, so a report from a phone says where a comment's read stopped. A GIF, the comment's
     * own or its media's, is counted where it was found and goes on to be read as a GIF.
     *
     * <p>The server leaves media_type out of a comment's own media. Without it, the media passes as
     * a still photo only with no GIF and no video, counted either way, and the sizes read then
     * decides whether there is a picture to save.
     */
    static Carried carried(Object comment, PhotoReads reads) {
        if (!reads.selected(comment)) return refused(NOT_SELECTED);
        Object raw = reads.raw(comment);
        if (raw == null) return refused(NO_RAW);
        Object gif = reads.gif(raw);
        if (gif != null) return gif(gif, COMMENT_GIF, reads);
        Object info = reads.info(raw);
        if (info == null) return refused(NO_INFO);
        Object media = reads.media(info);
        if (media == null) return refused(NO_MEDIA);
        Object kind = reads.kind(media);
        if (kind == null) {
            Object mediaGif = reads.mediaGif(media);
            if (mediaGif != null) return gif(mediaGif, MEDIA_GIF, reads);
            if (hasVideo(reads, media)) return refused(NO_KIND_VIDEO);
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, NO_KIND_STILL);
            return new Carried(media, null);
        }
        int value = kind instanceof Integer ? (Integer) kind : -1;
        if (value != reads.photoKind()) {
            return refused(value >= 0 && value < KINDS_NAMED ? "media_type " + value : "media_type other");
        }
        Object mediaGif = reads.mediaGif(media);
        if (mediaGif != null) return gif(mediaGif, MEDIA_GIF, reads);
        return new Carried(media, null);
    }

    /**
     * [gif], counted at [where] it was found, unless it's a sticker: an is_sticker of anything but
     * false or nothing keeps it out, counted as one.
     */
    private static Carried gif(Object gif, String where, PhotoReads reads) {
        HookStatus.counted(FamilyNames.COMMENT_PHOTO, where);
        Object sticker = reads.gifSticker(gif);
        if (sticker != null && !Boolean.FALSE.equals(sticker)) return refused(GIF_STICKER);
        return new Carried(null, gif);
    }

    /**
     * Each rendition [gif] lists as plain values, the one in Instagram's own copy of its images
     * (first_party_cdn_proxied_images) before the one in Giphy's (images). A set or rendition that
     * isn't there is left out, and a value of the wrong type reads as missing.
     */
    static List<CommentPhotoDownload.GifFile> gifFiles(Object gif, PhotoReads reads) {
        List<CommentPhotoDownload.GifFile> files = new ArrayList<>(2);
        for (Object images : new Object[]{ reads.gifProxied(gif), reads.gifImages(gif) }) {
            Object rendition = images == null ? null : reads.gifRendition(images);
            if (rendition == null) continue;
            files.add(new CommentPhotoDownload.GifFile(text(reads.gifUrl(rendition)), text(reads.gifWebp(rendition)),
                    text(reads.gifMp4(rendition)), size(reads.gifWidth(rendition)), size(reads.gifHeight(rendition))));
        }
        return files;
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static int size(Object value) {
        return value instanceof Integer ? (Integer) value : 0;
    }

    /**
     * Whether [media] has a video: video_versions that aren't an empty list, or a video_duration that
     * isn't zero or less. Anything else read there counts as a video too, so only plain absence passes.
     */
    private static boolean hasVideo(PhotoReads reads, Object media) {
        Object versions = reads.videoVersions(media);
        if (versions != null && !(versions instanceof List && ((List<?>) versions).isEmpty())) return true;
        Object duration = reads.videoDuration(media);
        return duration != null && !(duration instanceof Number && ((Number) duration).doubleValue() <= 0);
    }

    private static <T> T refused(String step) {
        HookStatus.counted(FamilyNames.COMMENT_PHOTO, step);
        return null;
    }

    private static boolean enabled() {
        return Utils.settingsReady() && Settings.SAVE_COMMENT_PHOTOS.get();
    }

    /** Holds only the copied photo sizes or GIF files, who wrote the comment and when, and the short-lived menu's context. */
    public static final class PhotoAction implements Function0<Object> {
        final CommentPhotoDownload.Snapshot snapshot;
        final PostDetails details;
        final Context context;
        final Save save;

        PhotoAction(CommentPhotoDownload.Snapshot snapshot, PostDetails details, Context context, Save save) {
            this.snapshot = snapshot;
            this.details = details;
            this.context = context;
            this.save = save;
        }

        /** Instagram ignores this result, then dismisses its menu using the stock callback. */
        @Override public Object invoke() {
            try {
                if (enabled()) save.save(context, snapshot, details);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save comment photo", failure);
                CommentPhotoDownload.failed(context);
            }
            return null;
        }
    }
}
