/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A running save for a test outside this package, driven step by step the way a save's worker
 * drives it: bytes arrive, the tracks are joined, the file goes into the gallery, and it ends.
 */
public final class SavesForTests {
    private SavesForTests() {
    }

    private static final Map<Integer, SaveControl.Save> STARTED = new ConcurrentHashMap<>();

    /** Starts watching a save, as a tap does, and answers its number. */
    public static int begin(Context context, boolean video) {
        SaveControl.Save save = SaveControl.begin(context, video);
        STARTED.put(save.id, save);
        return save.id;
    }

    public static int beginCarousel(Context context, int pages) {
        SaveControl.Save save = SaveControl.begin(context, false, pages);
        STARTED.put(save.id, save);
        return save.id;
    }

    public static void page(int id, int page, boolean video) {
        STARTED.get(id).page(page, video);
    }

    public static void finishCarousel(int id, int saved, int failed, int skipped, int lower, boolean cancelled) {
        SaveControl.batchFinished(new MediaSave.BatchResult(saved, failed, skipped, lower, cancelled));
        end(id);
    }

    public static void resetCarouselOutcome() {
        SaveControl.batchFinished(null);
    }

    public static void transferred(int id, long done, long total) {
        STARTED.get(id).transferred(done, total);
    }

    public static void converting(int id, int percent) {
        STARTED.get(id).converting(percent);
    }

    public static void joining(int id) {
        STARTED.get(id).joining();
    }

    public static void saving(int id) {
        STARTED.get(id).saving();
    }

    /** Whether the save saw a cancel, as its worker would at its next check. */
    public static boolean cancelled(int id) {
        return STARTED.get(id).cancelled();
    }

    /** The save is over, as its worker ends it. */
    public static void end(int id) {
        SaveControl.Save save = STARTED.remove(id);
        if (save != null) save.end();
    }

    /** Ends every save a test started and left running. */
    public static void endAll() {
        for (Integer id : STARTED.keySet()) end(id);
    }

    /** A job whose process ended before its terminal result, with no network or media identity. */
    public static void interrupt(Context context) throws Exception {
        SaveLeftovers.sweepOnce(context);
        SaveLeftovers.beginJob(context);
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.showInterrupted(context);
        app.hushgram.extension.shared.Utils.awaitBackgroundTasksForTests();
    }

    public static void resetInterruption() {
        SaveLeftovers.forgetSweepForTests();
    }

    /**
     * Writes down a video save of [mediaId] for the account in front, as a finished save does, at
     * [item] in Movies/Instagram.
     */
    public static void rememberSave(Context context, String mediaId, String item) throws Exception {
        String key = DownloadHistory.key(DownloadHistory.owner(), PostDetails.of(mediaId), DownloadHistory.Kind.VIDEO);
        DownloadHistory.remember(context, key, item, "Movies/Instagram", null);
    }

    /** How many saves Skip what's already saved remembers. */
    public static int historyCount(Context context) {
        return DownloadHistory.count(context);
    }

    /** Forgets what the history holds in memory, so the next ask reads its file again. */
    public static void forgetHistoryForTests() {
        DownloadHistory.forgetForTests();
    }

    /** The single files a caller hands a save, as addresses alone. A null address is left out. */
    public static List<MediaSave.Rendition> renditions(String... urls) {
        List<MediaSave.Rendition> renditions = new ArrayList<>();
        for (String url : urls) {
            if (url != null) renditions.add(MediaSave.Rendition.of(url));
        }
        return renditions;
    }
}
