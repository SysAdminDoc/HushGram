/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download in the menu of anyone's story.
 *
 * <p>A story's menu is a list of labels, and a tap hands the tapped label to a handler that
 * compares it with each of Instagram's own. Instagram offers a save only on your own stories. The
 * patch adds a label of its own:
 *
 * <ul>
 *   <li>Each builder of the menu tells {@link #building} which story's menu it's building, then
 *       hands its labels through {@link #labels} before the menu shows them. With the switch on,
 *       the menu gets Download at the end. A photo story with music, which Instagram serves as a
 *       video, gets Download as video and Download as photo instead.
 *   <li>Each handler asks {@link #save} first. A tap on Download saves the story from the
 *       addresses its Media already holds, through {@link MediaSave}: a video at the Download
 *       quality, a photo at its largest size. Download as photo saves the picture even when the
 *       story has a video. Any other label goes on to Instagram.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with the switch
 * off, or when something throws, the menu is Instagram's own.
 */
public final class StoryDownload {
    private StoryDownload() {
    }

    /** The source a story save's lines carry in the diagnostic report. */
    private static final String SOURCE = "StoryDownload";

    /** The step a failed read of a story's music flag is reported under. */
    static final String MUSIC_CHECK = "story music check";

    /** What a tap on one of HushGram's rows saves. */
    enum Choice { STORY, VIDEO, PHOTO }

    /** Instagram's media type for a photo, which a story keeps as the type it was posted as. */
    static final int POSTED_PHOTO = 1;

    /**
     * What a story's menu is built for, under the fixed name the diagnostic report counts it by,
     * and whether it gets Download as video and Download as photo.
     */
    enum Kind {
        UNREAD("story not read", false),
        PHOTO("photo", false),
        FLAGGED("video flagged as a photo with music", true),
        POSTED_AS_PHOTO("video posted as a photo", true),
        POSTED_AS_VIDEO("video posted as a video", false),
        NO_POSTED_TYPE("video with no posted type", false);

        final String counted;
        final boolean bothSaves;

        Kind(String counted, boolean bothSaves) {
            this.counted = counted;
            this.bothSaves = bothSaves;
        }
    }

    /** The story menu whose labels are being built, held weakly so a closed menu can go. */
    private static volatile WeakReference<Object> building = new WeakReference<>(null);

    /**
     * Remembers [menu], the class running the story menu whose labels a builder is about to
     * return, for {@link #labels}. Never throws.
     */
    public static void building(Object menu) {
        try {
            building = new WeakReference<>(menu);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story menu", t);
        }
    }

    /**
     * The labels [labels] of a story's menu, with HushGram's rows at the end when the switch is on:
     * Download, or Download as video and Download as photo for a photo story with music. A menu
     * that has the rows already is left alone. Never throws.
     */
    public static CharSequence[] labels(CharSequence[] labels) {
        try {
            HookStatus.invoked(FamilyNames.STORY_DOWNLOAD);
            if (labels == null || !on()) return labels;
            return labels(labels, photoWithMusic(building.get()));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story menu", t);
            return labels;
        }
    }

    static CharSequence[] labels(CharSequence[] labels, boolean photoWithMusic) {
        String[] rows = photoWithMusic ? new String[] {label(Choice.VIDEO), label(Choice.PHOTO)} : new String[] {label(Choice.STORY)};
        for (CharSequence label : labels) {
            if (label != null && rows[0].contentEquals(label)) return labels;
        }
        CharSequence[] more = Arrays.copyOf(labels, labels.length + rows.length);
        System.arraycopy(rows, 0, more, labels.length, rows.length);
        return more;
    }

    /**
     * Whether the story [menu] is open on is a photo with music, which Instagram serves as a video,
     * counting what the story was for the diagnostic report. No when it can't tell, so the menu
     * keeps its one Download. Never throws.
     */
    static boolean photoWithMusic(Object menu) {
        try {
            Kind kind = kind(menu == null ? null : InstagramMedia.storyMedia(menu));
            HookStatus.counted(FamilyNames.STORY_DOWNLOAD, kind.counted);
            return kind.bothSaves;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, MUSIC_CHECK, t);
            return false;
        }
    }

    /**
     * What [media], a story's Media, is. A photo story with music reaches the menu as a video. Only
     * some uploads flag it as one, and Instagram's own story viewer tells it from a filmed video by
     * the type it was posted as, a photo (#98). Without a video there's only the picture to save,
     * whatever the story says, so it keeps its one Download.
     */
    static Kind kind(Object media) {
        if (media == null) return Kind.UNREAD;
        if (ReelDownload.renditions(media).isEmpty() && InstagramMedia.dashManifest(media) == null) return Kind.PHOTO;
        if (Boolean.TRUE.equals(InstagramMedia.storyImageWithMusic(media))) return Kind.FLAGGED;
        Integer posted = InstagramMedia.originalMediaType(media);
        if (posted == null) return Kind.NO_POSTED_TYPE;
        return posted == POSTED_PHOTO ? Kind.POSTED_AS_PHOTO : Kind.POSTED_AS_VIDEO;
    }

    /**
     * Saves the story [menu] is open on when [label], the tapped one, is one of HushGram's rows and
     * the switch is on, and answers whether it did, in which case Instagram's handler is skipped. A
     * save that can't start says so. Never throws.
     */
    public static boolean save(CharSequence label, Object menu) {
        try {
            if (label == null || !on()) return false;
            Choice choice = choice(label);
            if (choice == null) return false;
            Context context = Utils.getContext().getApplicationContext();
            Object media = InstagramMedia.storyMedia(menu);
            if (choice == Choice.STORY && media != null
                    && ExternalDownload.handOff(context, ExternalDownload.storyLink(media), FamilyNames.STORY_DOWNLOAD)) {
                return true;
            }
            boolean started = media != null && (choice == Choice.PHOTO ? savePhoto(context, media) : saveMedia(context, media));
            if (!started) {
                Feedback.show(context, L10n.t(context, "Download failed"), true);
            }
            return true;
        } catch (Throwable t) {
            // It runs inside Instagram's click dispatch, where a throw ends the app. Instagram's
            // handler goes ahead instead, and passes over a label it doesn't know.
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story menu", t);
            return false;
        }
    }

    /** Starts the save of [media]: its video when it has one, else its picture. */
    private static boolean saveMedia(Context context, Object media) {
        List<MediaSave.Rendition> videos = ReelDownload.renditions(media);
        String manifest = InstagramMedia.dashManifest(media);
        PostDetails details = ReelDownload.details(media);
        if (!videos.isEmpty() || manifest != null) {
            final int files = videos.size();
            final boolean dash = manifest != null;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "story video download tapped: " + files + " file(s)" + (dash ? " and a manifest" : ", no manifest"));
            return MediaSave.saveVideo(context, videos, manifest, details);
        }
        return savePhoto(context, media);
    }

    /** Starts the save of [media]'s picture, at its largest size, even when it has a video too. */
    private static boolean savePhoto(Context context, Object media) {
        List<MediaSave.Rendition> pictures = pictures(media);
        final int sizes = pictures.size();
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "story photo download tapped: " + sizes + " size(s)");
        return MediaSave.savePhoto(context, pictures, ReelDownload.details(media));
    }

    /** The sizes [media] lists for its picture, with the size each states. Never null. */
    static List<MediaSave.Rendition> pictures(Object media) {
        Object versions = InstagramMedia.imageVersions(media);
        List<?> candidates = versions == null ? null : InstagramMedia.imageCandidates(versions);
        if (candidates == null) return Collections.emptyList();
        List<MediaSave.Rendition> pictures = new ArrayList<>(candidates.size());
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String url = InstagramMedia.candidateUrl(candidate);
            if (url == null || url.isEmpty()) continue;
            pictures.add(new MediaSave.Rendition(url, InstagramMedia.candidateWidth(candidate),
                    InstagramMedia.candidateHeight(candidate), 0));
        }
        return pictures;
    }

    /** The row for [choice], in the app's language. */
    static String label(Choice choice) {
        switch (choice) {
            case VIDEO: return L10n.t(Utils.getContext(), "Download as video");
            case PHOTO: return L10n.t(Utils.getContext(), "Download as photo");
            default: return L10n.t(Utils.getContext(), "Download");
        }
    }

    /** Which of HushGram's rows [label] is, however the menu styled it, or null for Instagram's own. */
    static Choice choice(CharSequence label) {
        for (Choice choice : Choice.values()) {
            if (label(choice).contentEquals(label)) return choice;
        }
        return null;
    }

    private static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_STORIES.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story menu switch", t);
            return false;
        }
    }
}
