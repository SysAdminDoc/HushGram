/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/MediaDownload.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Saves a photo or a video from the addresses the app already holds, without asking the app to do
 * it.
 *
 * <p>This is Hushfacebook's MediaDownload without its Facebook hooks. What a hook read out of the
 * app (the single files a post names, the DASH manifest its player streams, who posted it) is
 * handed in as plain values: a list of {@link Rendition}s, the manifest's text or null, and
 * {@link PostDetails} for the file name. So the hook that reads an Instagram reel's
 * {@code video_versions} and {@code video_dash_manifest} only has to call {@link #saveVideo}, and
 * everything after that (the pick against the Download quality, the join of a DASH picture and its
 * sound, the checks on every address, the gallery row, the notification with Cancel, the toast at
 * the end) is here and app-agnostic.
 *
 * <p>The caller hands over the item that was tapped, and nothing else. There is deliberately
 * <b>no static holding the last address seen</b>. Meta's apps prepare the next reels while the
 * current one plays, and anything remembered rather than passed in saves the wrong video and still
 * reports success.
 *
 * <p>No switch is read here. The patch that calls in owns its switch and checks it before it calls;
 * the Download quality, the folder, the file name and Saves other apps can open are read per save.
 *
 * <p>Every step goes to the diagnostic report under Downloads, and to logcat as
 * {@code morphe: MediaSave}. Addresses are logged as kind of file and quality only.
 */
public final class MediaSave {

    private MediaSave() {}

    /** The source every save event carries in the diagnostic report. */
    static final String SOURCE = "MediaSave";

    /** A step of a save, always kept: a save is rare and each one is worth a line. */
    static void info(Logger.LogMessage message) {
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, message);
    }

    /** A save that ended without a file, or a step that failed. */
    static void failure(Logger.LogMessage message, Throwable cause) {
        Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, message, cause);
    }

    /** A guard against a rapid tap, not a work queue. */
    private static final int MAX_IN_FLIGHT = 3;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /** Saves still running, and Download all requests waiting their turn. A test waits on this for the ones it started. */
    static int savesInFlight() {
        synchronized (WAITING) {
            return IN_FLIGHT.get() + WAITING.size() + handingOver;
        }
    }

    /**
     * Download all requests waiting for the one saving now, in the order they were asked for
     * (#115). Each post's pages are all saved before the next post's first, instead of two posts
     * running side by side and mixing in the gallery. At most this many wait.
     */
    static final int MAX_WAITING = 10;

    private static final ArrayDeque<BooleanSupplier> WAITING = new ArrayDeque<>();

    /** Whether a Download all holds the turn: it's saving, or the next one is being started. */
    private static boolean batchTurn;

    /** Requests taken off the line whose save isn't counted in flight yet, so a test never sees none. */
    private static int handingOver;

    /** Download all requests waiting their turn. */
    static int batchesWaiting() {
        synchronized (WAITING) {
            return WAITING.size();
        }
    }

    /** A batch is one in-flight save, with this many ordered pages at most. */
    public static final int MAX_BATCH_PAGES = 32;

    /** Plain values read on the tap. No Instagram object or Activity survives into the worker. */
    public static final class Item {
        final boolean video;
        final List<Rendition> renditions;
        final String manifest;
        final PostDetails details;
        /** A photo page built into a video with the post's music, else null. */
        final MusicVideo.Music music;
        final Rendition picture;

        public Item(boolean video, List<Rendition> renditions, String manifest, PostDetails details) {
            this(video, renditions, manifest, details, null, null);
        }

        /** A photo page saved as a video: [picture] held for [music]'s part of the track (#78). */
        static Item musicVideo(Rendition picture, MusicVideo.Music music, PostDetails details) {
            return new Item(true, Collections.emptyList(), null, details, music, picture);
        }

        private Item(boolean video, List<Rendition> renditions, String manifest, PostDetails details,
                MusicVideo.Music music, Rendition picture) {
            this.music = music;
            this.picture = picture;
            this.video = video;
            this.renditions = Collections.unmodifiableList(usable(renditions));
            this.manifest = video ? manifest : null;
            this.details = details == null ? PostDetails.NONE : PostDetails.of(details.videoId, details.owner,
                    details.posted == null ? null : new Date(details.posted.getTime())).onPage(details.page);
        }
    }

    /** Each selected page ends exactly once. Cancelled and unattempted pages count as skipped. */
    public static final class BatchResult {
        public final int saved;
        public final int failed;
        public final int skipped;
        public final int lower;
        public final boolean cancelled;

        BatchResult(int saved, int failed, int skipped, int lower, boolean cancelled) {
            this.saved = saved;
            this.failed = failed;
            this.skipped = skipped;
            this.lower = lower;
            this.cancelled = cancelled;
        }

        String message(Context application) {
            String text = cancelled
                    ? L10n.f(application, "Carousel cancelled. Saved %1$d. Failed %2$d. Skipped %3$d.", saved, failed, skipped)
                    : L10n.f(application, "Saved %1$d. Failed %2$d. Skipped %3$d.", saved, failed, skipped);
            return lower == 0 ? text : text + "\n" + L10n.f(application,
                    "%1$d saved in lower quality than on Instagram.", lower);
        }
    }

    /**
     * Saves a bounded snapshot in order, with one worker, Cancel and durable logical-job marker.
     * Null entries are skipped. Quality and compatibility choices are frozen for the whole batch
     * when it starts. The optional completion runs after cleanup, including the terminal marker and
     * in-flight slot. While another batch is saving, this one waits its turn and starts when that
     * one ends. Answers true when it started, is waiting, or said why it can't wait (a full line),
     * and false when it couldn't start.
     */
    public static boolean saveBatch(Context context, List<Item> pages, Consumer<BatchResult> completion) {
        try {
            if (pages == null || pages.isEmpty()) return false;
            if (pages.size() > MAX_BATCH_PAGES) {
                if (context != null) Feedback.show(context, L10n.f(context,
                        "Not saved: a carousel can have at most %1$d pages", MAX_BATCH_PAGES), true);
                return false;
            }
            List<Item> snapshot = new ArrayList<>(pages);
            // Check the copy too, if the caller changed its list while it was being read.
            if (snapshot.size() > MAX_BATCH_PAGES) {
                if (context != null) Feedback.show(context, L10n.f(context,
                        "Not saved: a carousel can have at most %1$d pages", MAX_BATCH_PAGES), true);
                return false;
            }
            if (snapshot.isEmpty()) return false;
            Context application = ready(context);
            if (application == null) return false;
            synchronized (WAITING) {
                if (batchTurn) {
                    if (WAITING.size() >= MAX_WAITING) {
                        info(() -> "carousel not queued: " + MAX_WAITING + " already waiting");
                        Feedback.show(application, L10n.f(application, "Not saved: %1$d posts are already waiting", MAX_WAITING), true);
                        return true;
                    }
                    WAITING.add(() -> {
                        // Whoever asked has moved on, so a waiting save that can't start says so itself.
                        boolean started = startBatch(application, snapshot, completion);
                        if (!started) Feedback.show(application, L10n.t(application, "Download failed"), true);
                        return started;
                    });
                    final int ahead = WAITING.size();
                    info(() -> "carousel queued, " + ahead + " waiting");
                    Feedback.show(application, L10n.t(application, "Queued. It starts once the post before it is saved."), true);
                    return true;
                }
                batchTurn = true;
            }
            if (startBatch(application, snapshot, completion)) return true;
            nextBatch();
            return false;
        } catch (Throwable failure) {
            failure(() -> "the carousel save could not start", failure);
            return false;
        }
    }

    /**
     * Starts the next waiting Download all, or gives up the turn when none waits. One that can't
     * start says so and the one after it gets the turn.
     */
    private static void nextBatch() {
        while (true) {
            BooleanSupplier next;
            synchronized (WAITING) {
                next = WAITING.poll();
                if (next == null) {
                    batchTurn = false;
                    return;
                }
                handingOver++;
            }
            boolean started;
            try {
                started = next.getAsBoolean();
            } catch (Throwable failure) {
                failure(() -> "a waiting carousel could not start", failure);
                started = false;
            } finally {
                synchronized (WAITING) {
                    handingOver--;
                }
            }
            if (started) return;
        }
    }

    /** Starts saving [snapshot] now and answers whether it started. */
    private static boolean startBatch(Context application, List<Item> snapshot, Consumer<BatchResult> completion) {
        try {
            DownloadQuality quality = quality();
            boolean compatible = compatibleSaves();
            BatchResult[] outcome = new BatchResult[1];
            boolean started = launch(application, false, snapshot.size(), save -> {
                int saved = 0, failed = 0, skipped = 0, lower = 0;
                boolean cancelled = false;
                for (int index = 0; index < snapshot.size(); index++) {
                    if (save.cancelled()) {
                        skipped += snapshot.size() - index;
                        cancelled = true;
                        break;
                    }
                    Item page = snapshot.get(index);
                    if (page == null) { skipped++; continue; }
                    save.page(index + 1, page.video);
                    if (save.cancelled()) {
                        skipped += snapshot.size() - index;
                        cancelled = true;
                        break;
                    }
                    Downloader.Result result;
                    try {
                        observeDetails(page.details);
                        MediaStoreWriter writer = new MediaStoreWriter(application, page.video, page.details);
                        Job job = page.music != null
                                ? (into, progress) -> MusicVideo.save(application, page.picture, page.music, into,
                                        policyFor(application), cap(), progress)
                                : page.manifest == null
                                ? singleJob(application, page.renditions, page.video, !page.video, quality)
                                : (into, progress) -> saveDash(application, "the carousel page", page.manifest,
                                        page.renditions, into, progress, quality, compatible);
                        result = job == null ? Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "nothing to save")
                                : job.run(writer, save);
                    } catch (Throwable failure) {
                        failure(() -> "a carousel page could not be saved", failure);
                        result = Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the page could not be saved");
                    }
                    final int number = index + 1;
                    final Downloader.Result ended = result;
                    info(() -> "carousel page " + number + " finished: " + ended);
                    if (result.ok()) { saved++; if (result.lower) lower++; }
                    else if (result.status == Downloader.Status.CANCELLED) {
                        skipped += snapshot.size() - index;
                        cancelled = true;
                        break;
                    } else failed++;
                }
                outcome[0] = new BatchResult(saved, failed, skipped, lower, cancelled || save.cancelled());
            }, save -> {
                BatchResult result = outcome[0] != null ? outcome[0]
                        : save.cancelled() ? new BatchResult(0, 0, snapshot.size(), 0, true)
                        : new BatchResult(0, snapshot.size(), 0, 0, false);
                outcome[0] = result;
                // The settings can show the complete outcome when Android clips a long toast.
                SaveControl.batchFinished(result);
                info(() -> "carousel finished: saved " + result.saved + ", failed " + result.failed
                        + ", skipped " + result.skipped + (result.cancelled ? ", cancelled" : ""));
                Feedback.show(application, result.message(application), true);
            }, () -> {
                try {
                    if (completion != null) completion.accept(outcome[0]);
                } finally {
                    nextBatch();
                }
            }) != null;
            return started;
        } catch (Throwable failure) {
            failure(() -> "the carousel save could not start", failure);
            return false;
        }
    }

    /**
     * One single file of a photo or a video as the app lists it: its address and, when the app
     * says, its size and bitrate. Instagram's {@code video_versions} and
     * {@code image_versions2.candidates} carry the width and height beside each address.
     *
     * <p>A size the app states outranks whatever the address hints at, since an address's marker
     * is a guess read out of its name. 0 means unknown, and then the address is read the way it
     * always was ({@link RenditionPicker#qualityOf(String)}).
     */
    public static final class Rendition {
        /** The address, fetched as it is. Checked by {@link MediaUrlPolicy} before any byte moves. */
        public final String url;
        /** Width and height in pixels, or 0 when the app doesn't say. */
        public final int width;
        public final int height;
        /** Bits per second, or 0 when the app doesn't say. Breaks a tie between two of one size. */
        public final long bitrate;

        public Rendition(String url, int width, int height, long bitrate) {
            this.url = url;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.bitrate = Math.max(0L, bitrate);
        }

        /** An address and nothing else about it. */
        public static Rendition of(String url) {
            return new Rendition(url, 0, 0, 0);
        }

        /** The short side the app states, in the {@code 720p} unit, or 0 when it states no size. */
        int declaredShortSide() {
            return width > 0 && height > 0 ? Math.min(width, height) : 0;
        }

        @Override
        public String toString() {
            // Never the address: a rendition can end up in a diagnostic line.
            return "Rendition(" + (width > 0 && height > 0 ? width + "x" + height : "size unknown")
                + (bitrate > 0 ? ", " + (bitrate / 1000) + "kbps" : "") + ")";
        }
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Save a video. [renditions] are the single files the app lists for it, in any order, and
     * [manifest] is its DASH manifest as text, or null. The manifest goes first when it lists a
     * better track than the single files, as it usually does; the single files are the fallback, and
     * the whole answer when there is no manifest. A picture is never saved in place of a video, so a
     * thumbnail among [renditions] can't look like a successful save. [details] names the file, or
     * null. [context] may be an Activity: only its application is kept.
     *
     * @return whether a save started. {@code false} means nothing was saved, and the report says
     *     why. Never throws.
     */
    public static boolean saveVideo(Context context, List<Rendition> renditions, String manifest,
            PostDetails details) {
        try {
            List<Rendition> usable = usable(renditions);
            if (manifest != null) return beginDash(context, "the video", manifest, usable, details);
            return begin(context, usable, true, false, details);
        } catch (Throwable t) {
            // Throwable and not Exception: a LinkageError that reaches the app's click handler
            // ends the app.
            failure(() -> "the video save could not start", t);
            return false;
        }
    }

    /**
     * Save a photo: the largest of [renditions], the candidates the app lists for one picture. A
     * thumbnail's address loses outright ({@link RenditionPicker#imageTier}). [details] names and
     * files it as a video's do, or null: without Name saves by account and post time, a photo's
     * name is {@link FileNameTemplate#PHOTO_PREFIX} and the time.
     *
     * @return whether a save started. Never throws.
     */
    public static boolean savePhoto(Context context, List<Rendition> renditions, PostDetails details) {
        try {
            return begin(context, usable(renditions), false, true, details);
        } catch (Throwable t) {
            failure(() -> "the photo save could not start", t);
            return false;
        }
    }

    /**
     * Save whichever an item is, for a caller that can't tell a photo from a video, such as a story
     * read by value: a video when [manifest] is there or any of [renditions] is one, else the best
     * picture.
     *
     * @return whether a save started. Never throws.
     */
    public static boolean saveItem(Context context, List<Rendition> renditions, String manifest,
            PostDetails details) {
        try {
            List<Rendition> usable = usable(renditions);
            if (manifest != null) return beginDash(context, "the item", manifest, usable, details);
            return begin(context, usable, true, true, details);
        } catch (Throwable t) {
            failure(() -> "the save could not start", t);
            return false;
        }
    }

    /**
     * Save a picture by the sizes the app states for it: the largest of [renditions] on Meta's
     * media servers. For a picture whose address names its size, such as a profile picture, which
     * {@link #savePhoto}'s ranking takes for a thumbnail's and turns down. A rendition with no
     * stated size loses to any with one.
     *
     * @return whether a save started. Never throws.
     */
    public static boolean savePictureBySize(Context context, List<Rendition> renditions, PostDetails details) {
        try {
            Context safe = ready(context);
            if (safe == null) return false;
            List<Rendition> found = metaOnly(usable(renditions));
            Rendition chosen = null;
            for (Rendition rendition : found) {
                if (chosen == null || pixels(rendition) > pixels(chosen)) chosen = rendition;
            }
            if (chosen == null) {
                failure(() -> "nothing to save: no picture address on Meta's media servers", null);
                return false;
            }
            saving(false, chosen, found, null, Dash.SINGLE_FILE, null);
            return start(safe, false, details, fileJob(safe, chosen.url, Downloader.Kind.IMAGE)) != null;
        } catch (Throwable t) {
            failure(() -> "the picture save could not start", t);
            return false;
        }
    }

    /**
     * Save a sound recording, such as a voice message, from [url] on Meta's media servers. It goes
     * to the phone's audio files ({@link MediaStoreWriter#audioDirectory}) as an M4A file, named as
     * a photo is, by [details] when Name saves by account and post time is on and it knows enough,
     * or else {@link MediaStoreWriter#AUDIO_PREFIX} and the time. [details] may be null.
     *
     * @return whether a save started. Never throws.
     */
    public static boolean saveAudio(Context context, String url, PostDetails details) {
        try {
            Context safe = ready(context);
            if (safe == null) return false;
            List<Rendition> found = url == null ? Collections.emptyList()
                : metaOnly(usable(Collections.singletonList(Rendition.of(url))));
            if (found.isEmpty()) {
                failure(() -> "nothing to save: no recording address on Meta's media servers", null);
                return false;
            }
            String address = found.get(0).url;
            info(() -> "saving a recording, " + describe(address));
            return startAudio(safe, details, fileJob(safe, address, Downloader.Kind.AUDIO)) != null;
        } catch (Throwable t) {
            failure(() -> "the recording save could not start", t);
            return false;
        }
    }

    /**
     * The single file a save of [renditions] would fetch: for a [video] the one that suits the
     * Download quality setting, and for a picture the largest. Only addresses on Meta's media
     * servers count. Null when none does. A video save can still take a better track from its
     * manifest; this is the file that has an address of its own.
     */
    static Rendition picked(List<Rendition> renditions, boolean video) {
        List<Rendition> found = metaOnly(usable(renditions));
        return video ? RenditionPicker.pickVideo(found, quality()) : RenditionPicker.pickImage(found);
    }

    private static long pixels(Rendition rendition) {
        return (long) rendition.width * rendition.height;
    }

    // ---------------------------------------------------------------- internals

    /** The renditions whose address {@code HttpURLConnection} can fetch at all. Never null. */
    private static List<Rendition> usable(List<Rendition> renditions) {
        List<Rendition> usable = new ArrayList<>();
        if (renditions == null) return usable;
        for (Rendition rendition : renditions) {
            if (rendition != null && RenditionPicker.isHttpUrl(rendition.url)) usable.add(rendition);
        }
        return usable;
    }

    /**
     * Pick the best address of those found and fetch it.
     *
     * <p>This ranks the item as a video when [videos] and as a picture when [images], and keeps a
     * video when there is one. It doesn't ask the app what the item is: an enum constant mistaken
     * for another saves the wrong file without a word. [details] is what the save knows of the post
     * for the file name.
     */
    private static boolean begin(Context context, List<Rendition> found, boolean videos, boolean images,
            PostDetails details) {
        Context safe = ready(context);
        if (safe == null) return false;
        DownloadQuality quality = quality();
        Job job = singleJob(safe, found, videos, images, quality);
        return job != null && start(safe, videos && RenditionPicker.pickVideo(metaOnly(found), quality) != null,
                details, job) != null;
    }

    /** The existing single-file selection and validation, shared by individual and batch saves. */
    private static Job singleJob(Context application, List<Rendition> found, boolean videos, boolean images,
            DownloadQuality quality) {
        if (found == null || found.isEmpty()) {
            failure(() -> "nothing to save: the item carried no address", null);
            return null;
        }

        // Only Meta's media servers are candidates, so a foreign address can't outrank a real one.
        final int count = found.size();
        List<Rendition> renditions = metaOnly(found);
        if (renditions.isEmpty()) {
            failure(() -> "nothing to save: none of the " + count + " addresses was on Meta's media servers", null);
            return null;
        }

        Rendition video = videos ? RenditionPicker.pickVideo(renditions, quality) : null;
        Rendition image = images && video == null ? RenditionPicker.pickImage(renditions) : null;

        boolean isVideo = video != null;
        Rendition chosen = isVideo ? video : image;

        if (chosen == null) {
            final int candidates = renditions.size();
            final String kind = videos && images ? "a file" : videos ? "a video file" : "a picture";
            failure(() -> "nothing to save: none of the " + candidates + " addresses was " + kind, null);
            return null;
        }

        saving(isVideo, chosen, renditions, quality, Dash.SINGLE_FILE, null);
        Downloader.Kind kind = isVideo ? Downloader.Kind.VIDEO : Downloader.Kind.IMAGE;
        return fileJob(application, chosen.url, kind, quality);
    }

    /**
     * The report line of a single file's save: [chosen] and every candidate of [renditions]. Every
     * one, so a saved file that is smaller than expected can be told apart from a ranking that chose
     * badly. Each as its kind of file and quality, never its name or address: a whole address is a
     * signed, working handle to the user's content, a CDN file name carries the object's id, and
     * the report is pasted into public issues. [better] is the manifest's picture the file is
     * below, or null.
     */
    private static void saving(boolean isVideo, Rendition chosen, List<Rendition> renditions, DownloadQuality quality,
            Dash dash, DashManifest.Track better) {
        StringBuilder all = new StringBuilder();
        for (Rendition rendition : renditions) {
            if (all.length() > 0) all.append(", ");
            all.append(describe(rendition));
        }

        final int candidates = renditions.size();
        info(() -> "saving " + (isVideo ? "video" : "image")
            + " " + describe(chosen)
            + " from " + candidates + " candidate(s): " + all
            + (isVideo ? qualityNote(quality) + singleFileNote(dash) + belowNote(better) : ""));
    }

    /** What the report adds to a save line whose picture is below the manifest's [better], or nothing. */
    private static String belowNote(DashManifest.Track better) {
        return better == null ? "" : ", below the manifest's " + better + ", the best it offers within the Download quality";
    }

    /**
     * The largest picture of [tracks] that fits [quality] and is larger than a saved one of quality
     * [saved], whatever its format, or null: the picture the app's player can show that the save
     * didn't keep. AV1 on a phone with no AV1 decoder is one, and so is any picture of a DASH save
     * that failed and fell back to the single file. For the smallest file there's never one, and a
     * saved quality nobody stated has none either. Purely informational: this doesn't ask whether
     * the phone could have written [better], only whether the manifest offered it.
     * {@link #noticeablyLower} is what decides whether the person saving is told.
     */
    static DashManifest.Track better(List<DashManifest.Track> tracks, int saved, DownloadQuality quality) {
        DashManifest.Track better = null;
        for (DashManifest.Track track : tracks) {
            if (!track.isVideo()) continue;
            int picture = picture(track, quality);
            if (saved <= 0 || picture <= saved || picture > quality.ceiling) continue;
            if (better == null || picture > picture(better, quality)) better = track;
        }
        return better;
    }

    /** A track's quality as [quality] weighs it: its short side at the best, else its label. */
    static int picture(DashManifest.Track track, DownloadQuality quality) {
        return quality == DownloadQuality.BEST ? track.shortSide() : track.quality();
    }

    /**
     * Whether a saved picture of [saved] pixels on its short side is far enough below [best], the
     * manifest's best within the Download quality, that the person saving should be told. Below
     * 720, or below two thirds of [best]: a 720p save whose only bigger rendition is a codec nothing
     * on the phone could turn into a file isn't told, since 720 is what the phone would have saved
     * either way, but a 360p save with a real 1080p rendition on offer is. The gap is what's judged,
     * never whether the phone could write [best]: {@link #better} already found it regardless of
     * that, and the report names it either way.
     */
    static boolean noticeablyLower(int saved, int best) {
        return saved > 0 && best > saved && (saved < 720 || (long) saved * 3 < (long) best * 2);
    }

    /**
     * The quality the save starting now asks for. Read once per save, when it starts, so a save
     * already running keeps the one it began with. Never throws: before the settings are ready,
     * or when they can't be read, it's the best.
     */
    static DownloadQuality quality() {
        try {
            if (!Utils.settingsReady()) return DownloadQuality.BEST;
            DownloadQuality chosen = Settings.DOWNLOAD_QUALITY.get();
            return chosen == null ? DownloadQuality.BEST : chosen;
        } catch (Throwable t) {
            return DownloadQuality.BEST;
        }
    }

    /** What the report adds to a save line for a quality below the best. */
    private static String qualityNote(DownloadQuality quality) {
        return quality == DownloadQuality.BEST ? "" : ", quality setting " + quality.fileValue;
    }

    /**
     * Whether the save starting now keeps to files other apps can open
     * ({@link Settings#DOWNLOAD_COMPATIBLE}). Read when the save starts, like the quality. Never
     * throws: before the settings are ready, or when they can't be read, it's off.
     */
    static boolean compatibleSaves() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_COMPATIBLE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** What the report adds to a DASH save line whose tracks were kept to what other apps can open. */
    private static String compatibleNote(boolean compatible) {
        return compatible ? ", kept to files other apps can open" : "";
    }

    /**
     * What the report adds to a DASH save line whose sound is xHE-AAC ({@code mp4a.40.42}).
     * {@link DashManifest#bestAudio} takes it only when the manifest offers no AAC-LC or HE-AAC, and
     * some players can't play it.
     */
    private static String soundNote(DashManifest.Track audio) {
        return audio != null && audio.codecs.trim().equals("mp4a.40.42")
            ? ", the sound is xHE-AAC because the manifest offers no AAC-LC or HE-AAC, and some players can't play xHE-AAC"
            : "";
    }

    /**
     * What the report adds to a single file's save line when saves other apps can open took it over
     * tracks the manifest would otherwise have saved. The single file's own formats aren't read, so
     * the line says why it was taken, not what it holds.
     */
    private static String singleFileNote(Dash dash) {
        return dash == Dash.SINGLE_FILE_FOR_OTHER_APPS
            ? ", taken over the manifest's better tracks, which aren't H.264 with AAC-LC or HE-AAC sound"
            : "";
    }

    /** Why a save with a manifest took the single file. */
    enum Dash {
        /** As it would with saves other apps can open off. */
        SINGLE_FILE,
        /** It's left to the single file because saves other apps can open ruled out the tracks that beat it. */
        SINGLE_FILE_FOR_OTHER_APPS
    }

    /** The save of one single file at [url]: the job every save of a single file runs. */
    static Job fileJob(Context application, String url, Downloader.Kind kind) {
        return fileJob(application, url, kind, quality());
    }

    private static Job fileJob(Context application, String url, Downloader.Kind kind, DownloadQuality quality) {
        return (writer, progress) -> saveFile(application, url, kind, null, Collections.emptyList(), null,
            quality, writer, progress);
    }

    /**
     * One checked file, fetched into the cache and then published. Its work file keeps, with those
     * of every other running save, to the free space ({@link DashSave#fetchWork}). A saved video's
     * report line says what the file holds, and [why] when a manifest's tracks were passed over
     * for it (bounded; null when the file was simply the pick). [offered] is what the person saving
     * is told against, once the file is down: the saved picture is measured from the file itself,
     * never guessed from its address, and named in the report whenever it beats what was saved,
     * whatever wrote it. [writable] is the one track, if any, the phone is known to be able to
     * write, such as the manifest's own pick passed over for this file: a shortfall against it, by
     * its measured pixels rather than a label, is flagged without {@link #noticeablyLower}'s
     * tolerance for a picture nothing could have written, since that excuse doesn't apply here, but
     * only when the file was actually read back, [writable] itself fits [quality]'s ceiling, and the
     * gap clears a small pixel margin a rounding or a container quirk could otherwise trip.
     * [quality] is the setting read when the save began, so a change while a long fetch runs can't
     * move what this file is judged against.
     */
    private static Downloader.Result saveFile(Context application, String url, Downloader.Kind kind, String why,
            List<DashManifest.Track> offered, DashManifest.Track writable, DownloadQuality quality,
            MediaStoreWriter writer, Downloader.Progress progress) {
        java.io.File folder = DashSave.workFolder(application);
        if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");
        java.io.File file = null;
        try {
            file = java.io.File.createTempFile(kind.name().toLowerCase(Locale.US), ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, kind, file, policyFor(application), cap(), progress);
            if (!fetched.ok()) return fetched;
            Downloader.Result published = Downloader.publish(file, fetched.mime, writer, progress);
            boolean lower = false;
            if (published.ok() && kind == Downloader.Kind.VIDEO) {
                String holds = DashSave.savedFormat(file);
                info(() -> "the saved file holds " + holds + (why == null ? "" : ". Saved in place of the manifest's "
                    + "tracks because " + bounded(why)));
                if (!offered.isEmpty() || writable != null) {
                    int savedShortSide = DashSave.savedVideoShortSide(file);
                    DashManifest.Track better = offered.isEmpty() ? null : better(offered, savedShortSide, quality);
                    if (better != null) {
                        final DashManifest.Track shown = better;
                        info(() -> "the save measures " + savedShortSide + "p, below the manifest's " + shown
                            + ", the best it offers within the Download quality");
                        lower = noticeablyLower(savedShortSide, better.shortSide());
                    }
                    // A file the phone couldn't read back measures 0, never a shortfall. A track
                    // above the ceiling isn't a real alternative for this setting, whatever its
                    // picture. And the compare itself is pixels on both sides: writable.quality()
                    // is picture()'s label below the best, which an unusually shaped video can
                    // carry far above what it actually measures. A small gap, kept to 16 pixels,
                    // is a rounding or a container quirk, not a shortfall worth a nag.
                    if (!lower && writable != null && quality != DownloadQuality.SMALLEST && savedShortSide > 0
                            && writable.quality() <= quality.ceiling
                            && writable.shortSide() - savedShortSide > 16) {
                        lower = true;
                    }
                }
            }
            return published.ok() && lower ? published.lower() : published;
        } catch (Throwable t) {
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the cache could not hold the file");
        } finally {
            DashSave.discard(file);
        }
    }

    /** The policy every save a test drives uses in place of the real one. Never set on a phone. */
    static volatile MediaUrlPolicy policyForTests;

    /**
     * Meta's address rules, with the lookup fence up only while the socket goes straight to the
     * answer: no proxy for the address and no VPN on the network. See {@link MediaUrlPolicy}.
     */
    static MediaUrlPolicy policyFor(Context application) {
        MediaUrlPolicy forced = policyForTests;
        if (forced != null) return forced;
        return new MediaUrlPolicy(MediaUrlPolicy.DNS, url -> !MediaUrlPolicy.proxied(url) && !onVpn(application));
    }

    /**
     * The cap every save a test drives holds its files to in place of the real one, so a test can
     * reach it with small files. Never set on a phone.
     */
    static volatile long capForTests;

    /** The most one save may put in the gallery: {@link Downloader#MAX_BYTES} on a phone. */
    static long cap() {
        long forced = capForTests;
        return forced > 0 ? forced : Downloader.MAX_BYTES;
    }

    /**
     * Whether the network a save would use runs through a VPN, a fake-IP proxy client's among
     * them. Instagram holds ACCESS_NETWORK_STATE. No answer keeps the fence up.
     */
    private static boolean onVpn(Context context) {
        try {
            android.net.ConnectivityManager manager =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            android.net.Network active = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Rendition> metaOnly(List<Rendition> renditions) {
        List<Rendition> kept = new ArrayList<>();
        for (Rendition rendition : renditions) {
            if (MediaUrlPolicy.shapeRefusal(rendition.url) == null) kept.add(rendition);
        }
        return kept;
    }

    /**
     * Save the best video track and audio track of a DASH manifest, if the video track is larger
     * than all single files of the item.
     *
     * <p>The manifest can list tracks that no single file has. The device joins the two tracks into
     * one file. If this fails, the save gets the best single file, so the user still gets a file.
     *
     * <p>Below the best quality, the track and the single file are each the one that suits the
     * setting ({@link DashManifest#pickVideo}, {@link RenditionPicker#pickVideo}), and the manifest
     * is used only when its track suits it better than the file does. On a tie the single file
     * wins: one fetch and no join.
     *
     * <p>With saves other apps can open on ({@link #compatibleSaves}), a known H.264 and AAC-LC or
     * HE-AAC pair ({@link DashManifest#pick}) wins over the unchecked single file, even if that
     * file has a higher quality label. A manifest with no such pair leaves the save to the single
     * file, the app's own MP4, whose formats aren't read here. With no single file either, the save
     * takes the tracks it would take with the switch off and the report says so, since a file some
     * apps turn down beats no file.
     *
     * <p>All of that happens on the save's own worker. Read on the tap, the manifest held the thread
     * that draws the app for as long as the parse took. One over {@link DashManifest#withinLimits}
     * isn't read at all, and the single file is saved.
     *
     * @return whether a save started. With a manifest there's always something to try, so only too
     *     many saves at once stops one. A manifest that turns out to offer nothing, beside no single
     *     file, then ends as a failed save.
     */
    private static boolean beginDash(Context context, String label, String manifest, List<Rendition> renditions,
            PostDetails details) {
        Context safe = ready(context);
        if (safe == null) return false;
        List<Rendition> candidates = new ArrayList<>(renditions);
        DownloadQuality quality = quality();
        boolean compatible = compatibleSaves();
        return start(safe, true, details, (writer, progress) -> saveDash(safe, label, manifest, candidates, writer,
                progress, quality, compatible)) != null;
    }

    /**
     * {@link #beginDash}'s save, on the worker: the manifest's tracks or the single file. The
     * quality and compatibility choices were read on the tap and are passed to every helper,
     * so a later setting change can't judge the fallback against a different quality.
     */
    private static Downloader.Result saveDash(Context application, String label, String manifest,
            List<Rendition> renditions, MediaStoreWriter writer, Downloader.Progress progress,
            DownloadQuality quality, boolean compatible) {
        if (!DashManifest.withinLimits(manifest)) {
            info(() -> "the manifest of " + label + " is over the limits a save reads (" + manifest.length()
                + " characters), saving the single file");
            return saveSingleVideo(application, renditions, Collections.emptyList(), Dash.SINGLE_FILE,
                "the manifest is over the limits a save reads", null, quality, writer, progress);
        }
        List<DashManifest.Track> tracks = new ArrayList<>();
        for (DashManifest.Track track : DashManifest.parse(manifest)) {
            if (MediaUrlPolicy.shapeRefusal(track.url) == null) tracks.add(track);
        }
        renditions = metaOnly(renditions);
        boolean allowAv1 = DashSave.canWriteAv1();
        // What a save below is weighed against when it tells the person it's lower. Not with saves
        // other apps can open on: that switch passes better pictures over by choice, and its own
        // report lines say so.
        List<DashManifest.Track> offered = compatible ? Collections.emptyList() : tracks;

        // What the pick below chose from, as each track's type, codec, size and bitrate. Never its
        // address: the report is pasted into public issues.
        Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE,
            () -> "the manifest of " + label + " offers " + tracks.size() + " track(s): " + tracks);

        DashChoice choice = DashChoice.of(tracks, renditions, quality, compatible, allowAv1);
        Rendition fallback = choice.fallback;
        DashManifest.Pick kept = choice.kept;
        DashManifest.Pick usual = choice.usual;
        // When the switch off's pick beats the single file and the kept pick doesn't, the switch is
        // why the single file is saved.
        Dash leftToFile = compatible && usual != null && beatsFile(usual.video, fallback, choice.fallbackQuality, quality)
            ? Dash.SINGLE_FILE_FOR_OTHER_APPS : Dash.SINGLE_FILE;

        if (choice.taken == null && kept == null && usual != null) {
            info(() -> "the manifest of " + label + " has no H.264 video with AAC-LC or HE-AAC sound, "
                + "saving the single file instead");
            return saveSingleVideo(application, renditions, offered, leftToFile,
                "the manifest has no H.264 video with AAC-LC or HE-AAC sound", null, quality, writer, progress);
        }
        if (choice.taken == null && kept == null) {
            info(() -> "the manifest of " + label + " has no track to save: " + tracks);
            return saveSingleVideo(application, renditions, offered, Dash.SINGLE_FILE,
                "the manifest has no track to save", null, quality, writer, progress);
        }
        if (choice.taken == null) {
            // kept.video already passed the writability check that picked it: if the fallback's
            // address overstated its own quality, the shortfall against it is held to a plain test,
            // not noticeablyLower's tolerance for a picture nothing could have written.
            return saveSingleVideo(application, renditions, offered, Dash.SINGLE_FILE, null, kept.video, quality,
                writer, progress);
        }
        if (choice.taken != kept) {
            info(() -> "nothing of " + label + " is in a format other apps can open, saving it as the switch "
                + "off would: " + usual.video + (usual.audio == null ? "" : " + " + usual.audio));
        }

        DashManifest.Track video = choice.taken.video;
        DashManifest.Track audio = choice.taken.audio;
        boolean keptCompatible = kept != null && compatible;

        // A track the muxer can't write can be a larger picture than the one saved.
        DashManifest.Track better = better(offered, picture(video, quality), quality);
        info(() -> "saving " + label + " from its DASH manifest: " + video
            + (audio == null ? ", no sound track" : " + " + audio)
            + ", instead of " + (fallback == null ? "nothing" : describe(fallback))
            + qualityNote(quality) + compatibleNote(keptCompatible) + soundNote(audio) + belowNote(better));

        Downloader.Result result = dashJob(application, video, audio, fallback, quality).run(writer, progress);
        boolean lower = better != null && noticeablyLower(picture(video, quality), better.shortSide());
        return result.ok() && lower ? result.lower() : result;
    }

    /**
     * The best single video file of [renditions], for a save whose manifest didn't win: [dash] says
     * why for the save line, and [why] for the line of what was saved, or null when the file was
     * simply the better pick. [writable] is the manifest's own picked track when this file was
     * chosen over it by a guess rather than for a deliberate compatibility trade-off:
     * {@link #saveFile} holds a shortfall against it to a plain test, since the phone could write it.
     * A video only, since the save was started as one. The save line below reports against the
     * file's stated or guessed quality, since nothing has been fetched yet to measure; once the file
     * is down, {@link #saveFile} weighs the "lower" note against what it actually measures.
     * [quality] is the choice already handed to {@link #saveDash}, not read again here.
     */
    private static Downloader.Result saveSingleVideo(Context application, List<Rendition> renditions,
            List<DashManifest.Track> tracks, Dash dash, String why, DashManifest.Track writable,
            DownloadQuality quality, MediaStoreWriter writer, Downloader.Progress progress) {
        List<Rendition> meta = metaOnly(renditions);
        Rendition video = RenditionPicker.pickVideo(meta, quality);
        if (video == null) {
            final int found = renditions.size();
            failure(() -> "nothing to save: the manifest gave no track and none of the " + found
                + " addresses was a video file on Meta's media servers", null);
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "nothing to save");
        }
        DashManifest.Track better = better(tracks, RenditionPicker.qualityOf(video), quality);
        saving(true, video, meta, quality, dash, better);
        return saveFile(application, video.url, Downloader.Kind.VIDEO, why, tracks, writable, quality, writer,
            progress);
    }

    /**
     * Which way a video save with a manifest goes: the manifest's tracks, [taken], or the single
     * file [fallback] when that's null. Decided in one place for the save and for Details, so what
     * Details says a Download saves is what it saves.
     */
    static final class DashChoice {
        /** The single file that suits the quality, or null. */
        final Rendition fallback;
        final int fallbackQuality;
        /** The manifest's pick for the settings as they stand, or null. */
        final DashManifest.Pick kept;
        /** The pick with saves other apps can open on switched off; [kept] when it's off. */
        final DashManifest.Pick usual;
        /** The tracks the save joins, or null when it saves [fallback]. */
        final DashManifest.Pick taken;

        private DashChoice(Rendition fallback, DashManifest.Pick kept, DashManifest.Pick usual, DashManifest.Pick taken) {
            this.fallback = fallback;
            this.fallbackQuality = fallback == null ? 0 : RenditionPicker.qualityOf(fallback);
            this.kept = kept;
            this.usual = usual;
            this.taken = taken;
        }

        /**
         * The choice between [tracks], the manifest's usable tracks, and [renditions], the single
         * files on Meta's servers. With saves other apps can open on and no pair they can, the
         * single file is saved, or with none, the tracks the switch off would take. The manifest's
         * own pick has to beat the single file, unless it's the pair other apps can open.
         */
        static DashChoice of(List<DashManifest.Track> tracks, List<Rendition> renditions, DownloadQuality quality,
                boolean compatible, boolean allowAv1) {
            Rendition fallback = RenditionPicker.pickVideo(renditions, quality);
            DashManifest.Pick kept = DashManifest.pick(tracks, allowAv1, quality, compatible);
            DashManifest.Pick usual = compatible ? DashManifest.pick(tracks, allowAv1, quality, false) : kept;
            DashManifest.Pick pick = kept != null || fallback != null ? kept : usual;
            int fallbackQuality = fallback == null ? 0 : RenditionPicker.qualityOf(fallback);
            boolean keptCompatible = kept != null && compatible;
            DashManifest.Pick taken = pick != null && (keptCompatible || beatsFile(pick.video, fallback, fallbackQuality, quality))
                ? pick : null;
            return new DashChoice(fallback, kept, usual, taken);
        }
    }

    /**
     * What a save of the video [renditions] and [manifest] writes, as {@link #saveVideo} decides it
     * with the settings as they stand: the manifest's video track when the save joins its tracks,
     * else the single file. For Details, which shows what a Download saves. A manifest is parsed,
     * so this is for a worker, not the thread that draws the app. Never null.
     */
    static Planned plannedVideo(List<Rendition> renditions, String manifest) {
        List<Rendition> files = metaOnly(usable(renditions));
        DownloadQuality quality = quality();
        if (manifest == null || !DashManifest.withinLimits(manifest)) {
            return new Planned(RenditionPicker.pickVideo(files, quality), null);
        }
        List<DashManifest.Track> tracks = new ArrayList<>();
        for (DashManifest.Track track : DashManifest.parse(manifest)) {
            if (MediaUrlPolicy.shapeRefusal(track.url) == null) tracks.add(track);
        }
        DashChoice choice = DashChoice.of(tracks, files, quality, compatibleSaves(), DashSave.canWriteAv1());
        return new Planned(choice.fallback, choice.taken == null ? null : choice.taken.video);
    }

    /** What {@link #plannedVideo} found: the single file, and the manifest's track when the save joins tracks. */
    static final class Planned {
        /** The single file, the one with an address of its own, or null. */
        final Rendition file;
        /** The video track a save joins with its sound, or null when it saves [file]. */
        final DashManifest.Track joined;

        Planned(Rendition file, DashManifest.Track joined) {
            this.file = file;
            this.joined = joined;
        }

        /** The width of what's saved, or 0 when it isn't known. */
        int width() {
            return joined != null ? joined.width : file != null ? file.width : 0;
        }

        /** The height of what's saved, or 0 when it isn't known. */
        int height() {
            return joined != null ? joined.height : file != null ? file.height : 0;
        }
    }

    /**
     * Whether [video] suits [quality] better than the single file [fallback] of quality
     * [fallbackQuality]: at the best quality a larger picture, below it a nearer fit. On a tie the
     * single file wins, one fetch and no join.
     */
    private static boolean beatsFile(DashManifest.Track video, Rendition fallback, int fallbackQuality,
            DownloadQuality quality) {
        if (quality == DownloadQuality.BEST) return video.shortSide() > fallbackQuality;
        return fallback == null || quality.compare(video.quality(), fallbackQuality) < 0;
    }

    /**
     * The DASH save of [video] and [audio], then the single file [fallback] when that fails. Not
     * when the person cancelled it, though: the fallback would start the save over. A fallback
     * below [video]'s picture says so, held to a plain test: [video] already passed the writability
     * check that picked it, so there's no picture-nothing-could-write excuse to make room for here.
     * The quality setting is read once, when this job is built, so a change while the DASH save
     * runs can't move what the fallback is judged against.
     */
    static Job dashJob(Context application, DashManifest.Track video, DashManifest.Track audio, String fallback) {
        return dashJob(application, video, audio, fallback == null ? null : Rendition.of(fallback), quality());
    }

    /** As above, with [quality] already handed to {@link #saveDash} rather than read again here. */
    private static Job dashJob(Context application, DashManifest.Track video, DashManifest.Track audio,
            Rendition fallback, DownloadQuality quality) {
        return (writer, progress) -> {
            Downloader.Result result = DashSave.save(application, video, audio, writer, policyFor(application), cap(),
                progress);
            if (result.ok() || fallback == null || result.status == Downloader.Status.CANCELLED) return result;

            DashManifest.Track better = better(Collections.singletonList(video),
                RenditionPicker.qualityOf(fallback), quality);
            failure(() -> "the DASH save ended with " + result + ", saving " + describe(fallback) + belowNote(better),
                null);
            return saveFile(application, fallback.url, Downloader.Kind.VIDEO, "the DASH save ended with " + result,
                Collections.singletonList(video), video, quality, writer, progress);
        };
    }

    /**
     * The context to save with, or {@code null} when a save cannot start now.
     *
     * <p>Never the Activity. A download outlives the screen that started it, and holding the
     * Activity across it is a leak.
     */
    private static Context ready(Context context) {
        if (context == null) return null;

        Context application = context.getApplicationContext();
        return application != null ? application : context;
    }

    /**
     * One save on the worker thread. It writes through [writer], tells [progress] how far it has
     * got, stops when [progress] says it was cancelled, and returns the result.
     */
    interface Job {
        Downloader.Result run(MediaStoreWriter writer, Downloader.Progress progress);
    }

    /**
     * Runs [job] on its own worker thread, and hands the thread back so a test can wait for it.
     * The save shows a notification with its progress and a Cancel button while it runs.
     */
    static Thread start(Context application, boolean video, Job job) {
        return start(application, video, PostDetails.NONE, job);
    }

    /** As above, naming a video from [videoId] when the file name asks for it. */
    static Thread start(Context application, boolean video, String videoId, Job job) {
        return start(application, video, PostDetails.of(videoId), job);
    }

    /** Hands each save's details to a test, which can't see the name of a save that fails. Never set on a phone. */
    static volatile java.util.function.Consumer<PostDetails> detailsForTests;

    private static void observeDetails(PostDetails known) {
        Consumer<PostDetails> watching = detailsForTests;
        if (watching != null) watching.accept(known);
    }

    /** As above, naming the video from whatever of the post [details] holds and the file name asks for. */
    static Thread start(Context application, boolean video, PostDetails details, Job job) {
        return start(application, video, false, details, job);
    }

    /** As above, for a sound recording, which goes to the phone's audio files. */
    static Thread startAudio(Context application, PostDetails details, Job job) {
        return start(application, false, true, details, job);
    }

    private static Thread start(Context application, boolean video, boolean audio, PostDetails details, Job job) {
        final PostDetails known = details == null ? PostDetails.NONE : details;
        return launch(application, video, audio, 0, save -> {
            observeDetails(known);
            MediaStoreWriter writer = audio ? MediaStoreWriter.forAudio(application, known)
                : new MediaStoreWriter(application, video, known);
            Downloader.Result result = job.run(writer, save);
            boolean cancelled = result.status == Downloader.Status.CANCELLED;
            if (result.ok() || cancelled) info(() -> "save finished: " + result);
            else failure(() -> "save finished: " + result, null);
            Feedback.show(application, message(application, result.status, writer.savedLocation(), result.lower),
                    !result.ok() && !cancelled);
        }, save -> {}, () -> {});
    }

    private interface Work { void run(SaveControl.Save save); }

    /** Atomically admits one logical save and retires it for every normal result. */
    private static Thread launch(Context application, boolean video, int pages, Work work,
            Consumer<SaveControl.Save> finishing, Runnable finished) {
        return launch(application, video, false, pages, work, finishing, finished);
    }

    private static Thread launch(Context application, boolean video, boolean audio, int pages, Work work,
            Consumer<SaveControl.Save> finishing, Runnable finished) {
        int running;
        do {
            running = IN_FLIGHT.get();
            if (running >= MAX_IN_FLIGHT) {
                failure(() -> "too many saves at once", null);
                return null;
            }
        } while (!IN_FLIGHT.compareAndSet(running, running + 1));
        SaveControl.Save save;
        try {
            save = audio ? SaveControl.beginAudio(application) : SaveControl.begin(application, video, pages);
        } catch (Throwable failure) {
            IN_FLIGHT.decrementAndGet();
            throw failure;
        }
        // With no notification to cancel it from, the list of saves in the settings is the only way
        // to stop it, so the start says where that is, for long enough to read.
        if (save.manager != null) {
            Feedback.show(application, L10n.t(application, "Saving..."), false);
        } else {
            Feedback.show(application,
                L10n.t(application, "Saving... You can cancel from Downloads in HushGram settings."), true);
        }

        Thread worker = new Thread(() -> {
            String marker = null;
            try {
                // What a save in a process Android ended left behind goes before this one makes
                // anything. It runs once per process.
                SaveLeftovers.sweepOnce(application);
                marker = SaveLeftovers.beginJob(application);
                work.run(save);
            } catch (Throwable t) {
                // Nothing can leave this thread. The app installs its own handler for uncaught
                // exceptions and reports them as its own crashes.
                failure(() -> "the save failed", t);
                if (pages == 0) Feedback.show(application, L10n.t(application, "Download failed"), true);
            } finally {
                SaveLeftovers.finishJob(application, marker);
                // Publish the batch outcome before the end notification asks the settings to redraw.
                if (pages > 0) {
                    try { finishing.accept(save); }
                    catch (Throwable failure) { failure(() -> "could not deliver a save's completion", failure); }
                }
                save.end();
                IN_FLIGHT.decrementAndGet();
                try { finished.run(); }
                catch (Throwable failure) { failure(() -> "could not deliver a save's completion", failure); }
            }
        }, "hushgram-save");

        // A thread that ends when the copy ends leaves nothing behind in a process that is not
        // ours. A pool parks a thread there for as long as the app runs.
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        try { worker.start(); }
        catch (Throwable failure) {
            save.end();
            IN_FLIGHT.decrementAndGet();
            throw failure;
        }
        return worker;
    }

    /** A reason for the report, cut to 160 characters. */
    private static String bounded(String reason) {
        return reason.length() <= 160 ? reason : reason.substring(0, 157) + "...";
    }

    /**
     * What the toast at the end of a save says, in the phone's language. [lower] when the saved
     * picture is below the best one Instagram offered within the quality setting.
     */
    static String message(Context application, Downloader.Status status, String location, boolean lower) {
        switch (status) {
            case OK:
                if (lower) {
                    return location == null
                        ? L10n.t(application, "Saved to the gallery in lower quality than on Instagram")
                        : L10n.f(application, "Saved to %1$s in lower quality than on Instagram", L10n.isolate(location));
                }
                return location == null
                    ? L10n.t(application, "Saved to the gallery")
                    : L10n.f(application, "Saved to %1$s", L10n.isolate(location));
            case EXPIRED:
                return L10n.t(application, "Link expired. Reopen the item and try again");
            case REFUSED:
                return L10n.t(application, "Not saved: that isn't an Instagram photo or video");
            case TOO_LARGE:
                return L10n.t(application, "Not saved: the file is over 512 MB");
            case CANCELLED:
                return L10n.t(application, "Save cancelled");
            default:
                return L10n.t(application, "Download failed");
        }
    }

    /**
     * Enough of an address to tell one candidate from another in a report: its kind of file and
     * its quality, and no more.
     *
     * <p>A whole address is a signed, working handle to the content of the user, and the log can
     * be read by anything else on the device. The file name isn't safe either: on Meta's CDN it
     * carries the object's own id ({@code 475148478_1134540631592283_..._n.jpg}), and the report
     * is pasted into public issues.
     */
    static String describe(String url) {
        return describe(Rendition.of(url));
    }

    /** As above, with the size the app stated for it, when it did, as its quality. */
    static String describe(Rendition rendition) {
        String url = rendition.url;
        int query = url.indexOf('?');
        String withoutQuery = query < 0 ? url : url.substring(0, query);

        int slash = withoutQuery.lastIndexOf('/');
        String file = slash < 0 ? withoutQuery : withoutQuery.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        String extension = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.US);
        if (!extension.matches("[a-z0-9]{1,5}")) extension = "file";

        int quality = RenditionPicker.qualityOf(rendition);
        return extension + " (" + (quality > 0 ? quality + "p" : "unknown") + ")";
    }
}
