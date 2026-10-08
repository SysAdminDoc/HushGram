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
 * A comment's own photo: the sizes Instagram supplied for it, copied as they are when the menu
 * opens, and saved through the same pipeline as a post's photo when Save is tapped.
 */
public final class CommentPhotoDownload {
    private CommentPhotoDownload() {}

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
