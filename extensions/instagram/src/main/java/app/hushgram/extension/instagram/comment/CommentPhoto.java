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
import app.hushgram.extension.instagram.download.MediaSave;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** An explicit Save action in the selected comment's native menu, for the comment's own photo. */
public final class CommentPhoto {
    private CommentPhoto() {}

    interface NativeRows {
        List<MediaSave.Rendition> photo(Object comment);
        /** Who wrote [comment] and when, for the save's name. */
        PostDetails details(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    interface Save {
        void photo(Context context, List<MediaSave.Rendition> snapshot, PostDetails details);
    }

    /** The native reads behind a comment's photo, one Instagram getter each, made in this order. */
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
    };

    // What the diagnostic report counts when a read finds no photo, one name per step. The names
    // are fixed text: nothing read from the comment goes in, bar a media_type kept to a small number.
    static final String NOT_SELECTED = "not a selected comment";
    static final String NO_RAW = "no raw comment";
    static final String COMMENT_GIF = "comment GIF";
    static final String NO_INFO = "no media_comment_info";
    static final String NO_MEDIA = "no media in media_comment_info";
    static final String NO_KIND_STILL = "no media_type, still image";
    static final String NO_KIND_VIDEO = "no media_type, has video";
    static final String MEDIA_GIF = "media GIF";
    /**
     * Instagram's media types are single digits (1 photo, 2 video, 8 carousel). Anything else shares
     * one name, so a strange value can't use up the sixteen names a family's counts keep.
     */
    private static final int KINDS_NAMED = 10;

    private static final NativeRows NATIVE = new NativeRows() {
        public List<MediaSave.Rendition> photo(Object comment) {
            return CommentPhotoDownload.snapshot(photoMedia(comment, READS));
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
            // Copied now, so the row saves the photo this menu was opened for.
            List<MediaSave.Rendition> read = nativeRows.photo(comment);
            List<MediaSave.Rendition> snapshot = read == null || read.isEmpty() ? null : CommentPhotoDownload.copy(read);
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
            if (owned == 1 && samePhoto(snapshot, existing.snapshot)) return rows;
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
     * comes from, for Name saves by account and post time. The comment's author is the photo's
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

    /**
     * The Media of the selected comment's own still photo, or null for anything else: another
     * object, a comment with no media, a GIF, a video. Each null is counted under the step that
     * found nothing, so a report from a phone says where a photo comment's read stopped.
     *
     * <p>The server leaves media_type out of a comment's own media. Without it, the media passes as
     * a still photo only with no GIF and no video, counted either way, and the sizes read then
     * decides whether there is a picture to save.
     */
    static Object photoMedia(Object comment, PhotoReads reads) {
        if (!reads.selected(comment)) return refused(NOT_SELECTED);
        Object raw = reads.raw(comment);
        if (raw == null) return refused(NO_RAW);
        if (reads.gif(raw) != null) return refused(COMMENT_GIF);
        Object info = reads.info(raw);
        if (info == null) return refused(NO_INFO);
        Object media = reads.media(info);
        if (media == null) return refused(NO_MEDIA);
        Object kind = reads.kind(media);
        if (kind == null) {
            if (reads.mediaGif(media) != null) return refused(MEDIA_GIF);
            if (hasVideo(reads, media)) return refused(NO_KIND_VIDEO);
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, NO_KIND_STILL);
            return media;
        }
        int value = kind instanceof Integer ? (Integer) kind : -1;
        if (value != reads.photoKind()) {
            return refused(value >= 0 && value < KINDS_NAMED ? "media_type " + value : "media_type other");
        }
        if (reads.mediaGif(media) != null) return refused(MEDIA_GIF);
        return media;
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

    private static Object refused(String step) {
        HookStatus.counted(FamilyNames.COMMENT_PHOTO, step);
        return null;
    }

    private static boolean enabled() {
        return Utils.settingsReady() && Settings.SAVE_COMMENT_PHOTOS.get();
    }

    private static boolean samePhoto(List<MediaSave.Rendition> current, List<MediaSave.Rendition> shown) {
        if (current.size() != shown.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            MediaSave.Rendition a = current.get(i);
            MediaSave.Rendition b = shown.get(i);
            if (!a.url.equals(b.url) || a.width != b.width || a.height != b.height) return false;
        }
        return true;
    }

    /** Holds only the copied sizes, who wrote the comment and when, and the short-lived menu's context. */
    public static final class PhotoAction implements Function0<Object> {
        final List<MediaSave.Rendition> snapshot;
        final PostDetails details;
        final Context context;
        final Save save;

        PhotoAction(List<MediaSave.Rendition> snapshot, PostDetails details, Context context, Save save) {
            this.snapshot = snapshot;
            this.details = details;
            this.context = context;
            this.save = save;
        }

        /** Instagram ignores this result, then dismisses its menu using the stock callback. */
        @Override public Object invoke() {
            try {
                if (enabled()) save.photo(context, snapshot, details);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save comment photo", failure);
                CommentPhotoDownload.failed(context);
            }
            return null;
        }
    }
}
