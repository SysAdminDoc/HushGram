/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A comment's own photo or GIF: the sizes or files Instagram supplied for it, copied as they are
 * when the menu opens, and saved through the same pipeline as a post's photo when Save is tapped.
 */
public final class CommentPhotoDownload {
    private CommentPhotoDownload() {}

    /**
     * What a comment's Save row keeps from the moment its menu opens: what kind of file Instagram
     * listed and every size it listed in that kind, unchanged and in Instagram's order. Never empty.
     */
    public static final class Snapshot {
        /** A still photo, or a GIF kept as a GIF file, an animated WebP or an MP4. */
        public enum Format { PHOTO, GIF, WEBP, MP4 }

        public final Format format;
        /** An unmodifiable copy, so a later change to the source can't change what a row saves. */
        public final List<MediaSave.Rendition> sizes;

        Snapshot(Format format, List<MediaSave.Rendition> sizes) {
            this.format = format;
            this.sizes = copy(sizes);
        }

        /** A still photo's [sizes], or null when there are none. */
        public static Snapshot photo(List<MediaSave.Rendition> sizes) {
            return sizes == null || sizes.isEmpty() ? null : new Snapshot(Format.PHOTO, sizes);
        }

        /** Whether [other] is the same kind of file at the same addresses and sizes. */
        public boolean sameAs(Snapshot other) {
            if (other == null || other.format != format || other.sizes.size() != sizes.size()) return false;
            for (int i = 0; i < sizes.size(); i++) {
                MediaSave.Rendition a = sizes.get(i);
                MediaSave.Rendition b = other.sizes.get(i);
                if (!a.url.equals(b.url) || a.width != b.width || a.height != b.height) return false;
            }
            return true;
        }
    }

    /**
     * One rendition a comment's GIF lists, as Instagram's model holds it: the address of its GIF,
     * WebP and MP4 files, any of which may be missing, and the size it states, 0 when it states none.
     */
    public static final class GifFile {
        final String gif;
        final String webp;
        final String mp4;
        final int width;
        final int height;

        public GifFile(String gif, String webp, String mp4, int width, int height) {
            this.gif = gif;
            this.webp = webp;
            this.mp4 = mp4;
            this.width = width;
            this.height = height;
        }

        String address(Snapshot.Format format) {
            switch (format) {
                case GIF: return gif;
                case WEBP: return webp;
                case MP4: return mp4;
                default: return null;
            }
        }
    }

    /** The kinds of file a GIF is kept as, the one to prefer first. */
    private static final Snapshot.Format[] ANIMATED = { Snapshot.Format.GIF, Snapshot.Format.WEBP, Snapshot.Format.MP4 };

    // What the diagnostic report counts for a comment's GIF once its files are read: none had an
    // address, each refusal's reason, which never names the host, or that one was kept.
    static final String NO_GIF_FILE = "no GIF file";
    static final String GIF_FOUND = "GIF found";

    /**
     * The files of a comment's GIF to save: its GIF files on Meta's media servers, or without one
     * its WebP files there, or else its MP4 files, each kind in [files]' order with the size it
     * states. Nothing is fetched from anywhere else, Giphy included, and no address is made up.
     * Null when no file is on Meta's servers, counted by why.
     */
    public static Snapshot gif(List<GifFile> files) {
        List<String> refused = new ArrayList<>(2);
        if (files != null) {
            for (Snapshot.Format format : ANIMATED) {
                List<MediaSave.Rendition> kept = new ArrayList<>(files.size());
                for (GifFile file : files) {
                    String url = file == null ? null : file.address(format);
                    if (url == null || url.isEmpty()) continue;
                    String shape = MediaUrlPolicy.shapeRefusal(url);
                    if (shape != null) {
                        String why = "GIF refused (" + shape + ")";
                        if (!refused.contains(why)) refused.add(why);
                        continue;
                    }
                    kept.add(new MediaSave.Rendition(url, file.width, file.height, 0));
                }
                if (!kept.isEmpty()) {
                    HookStatus.counted(FamilyNames.COMMENT_PHOTO, GIF_FOUND);
                    return new Snapshot(format, kept);
                }
            }
        }
        if (refused.isEmpty()) {
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, NO_GIF_FILE);
        } else {
            for (String why : refused) HookStatus.counted(FamilyNames.COMMENT_PHOTO, why);
        }
        return null;
    }

    interface Images {
        Object versions(Object media);
        List<?> candidates(Object versions);
        String url(Object candidate);
        int width(Object candidate);
        int height(Object candidate);
    }

    private static final Images NATIVE = new Images() {
        public Object versions(Object media) { return InstagramMedia.imageVersions(media); }
        public List<?> candidates(Object versions) { return InstagramMedia.imageCandidates(versions); }
        public String url(Object candidate) { return InstagramMedia.candidateUrl(candidate); }
        public int width(Object candidate) { return InstagramMedia.candidateWidth(candidate); }
        public int height(Object candidate) { return InstagramMedia.candidateHeight(candidate); }
    };

    // What the diagnostic report counts for a comment's photo once its Media is read: why no size
    // was kept, or that one was. A refused size is counted by the URL policy's reason, which never
    // names the host, and never by its address.
    static final String NO_VERSIONS = "no image_versions2";
    static final String NO_CANDIDATES = "no candidates";
    static final String ANIMATED_OR_VIDEO = "size refused (animated or video)";
    static final String FOUND = "photo found";

    /**
     * The sizes [media]'s picture lists with an address on Meta's media servers, in Instagram's
     * order and unchanged. Empty, never null, when there is no media or no such size.
     */
    public static List<MediaSave.Rendition> snapshot(Object media) {
        return snapshot(media, NATIVE);
    }

    static List<MediaSave.Rendition> snapshot(Object media, Images images) {
        // No media was already counted by the read that came back without it.
        if (media == null) return Collections.emptyList();
        Object versions = images.versions(media);
        if (versions == null) return nothing(NO_VERSIONS);
        List<?> candidates = images.candidates(versions);
        if (candidates == null) return nothing(NO_CANDIDATES);
        List<MediaSave.Rendition> sizes = new ArrayList<>(candidates.size());
        List<String> refused = new ArrayList<>(2);
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String url = images.url(candidate);
            String shape = MediaUrlPolicy.shapeRefusal(url);
            String why = shape != null ? "size refused (" + shape + ")" : animatedOrVideo(url) ? ANIMATED_OR_VIDEO : null;
            if (why != null) {
                if (!refused.contains(why)) refused.add(why);
                continue;
            }
            sizes.add(new MediaSave.Rendition(url, images.width(candidate), images.height(candidate), 0));
        }
        if (!sizes.isEmpty()) {
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, FOUND);
        } else if (refused.isEmpty()) {
            // Only empty slots, which is no candidates at all.
            HookStatus.counted(FamilyNames.COMMENT_PHOTO, NO_CANDIDATES);
        } else {
            // Every size refused: each reason once per read, so the counts match the menus opened.
            for (String why : refused) HookStatus.counted(FamilyNames.COMMENT_PHOTO, why);
        }
        return copy(sizes);
    }

    private static List<MediaSave.Rendition> nothing(String step) {
        HookStatus.counted(FamilyNames.COMMENT_PHOTO, step);
        return Collections.emptyList();
    }

    /** An unmodifiable copy, so a later change to the source can't change what a row saves. */
    public static List<MediaSave.Rendition> copy(List<MediaSave.Rendition> sizes) {
        return Collections.unmodifiableList(new ArrayList<>(sizes));
    }

    /**
     * Starts the save of the largest of [snapshot], named and filed after [details], the comment's
     * author and time, as a post's photo is after its poster. A save that can't start says so.
     * Never throws.
     */
    public static void save(Context context, List<MediaSave.Rendition> snapshot, PostDetails details) {
        try {
            if (!MediaSave.savePhoto(context, snapshot, details)) failed(context);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save comment photo", t);
            failed(context);
        }
    }

    /**
     * Starts the save of [snapshot], named and filed after [details]: a photo as {@link
     * #save(Context, List, PostDetails)} saves it, a GIF or WebP as a picture at the largest size
     * it states, which keeps its own file type, and an MP4 as a video. A save that can't start
     * says so. Never throws.
     */
    public static void save(Context context, Snapshot snapshot, PostDetails details) {
        try {
            boolean started;
            if (snapshot == null) {
                started = false;
            } else if (snapshot.format == Snapshot.Format.PHOTO) {
                started = MediaSave.savePhoto(context, snapshot.sizes, details);
            } else if (snapshot.format == Snapshot.Format.MP4) {
                started = MediaSave.saveVideo(context, snapshot.sizes, null, details);
            } else {
                started = MediaSave.savePictureBySize(context, snapshot.sizes, details);
            }
            if (!started) failed(context);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save comment photo", t);
            failed(context);
        }
    }

    /** Download failed, in the phone's language. Never throws. */
    public static void failed(Context context) {
        try {
            Context application = context == null ? null : context.getApplicationContext();
            if (application != null) Feedback.show(application, L10n.t(application, "Download failed"), true);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "save feedback", t);
        }
    }

    // The media is a photo by its kind, or has no kind and no video. An address that names a GIF or
    // a video file still never stands in for the picture.
    private static boolean animatedOrVideo(String address) {
        try {
            String path = new URL(address).getPath().toLowerCase(Locale.US);
            return path.endsWith(".gif") || path.endsWith(".mp4") || path.endsWith(".m4v")
                    || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mpd");
        } catch (Throwable malformed) {
            return true;
        }
    }
}
