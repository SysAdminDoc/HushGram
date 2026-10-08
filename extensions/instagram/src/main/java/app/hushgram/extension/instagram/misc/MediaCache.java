/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Helper for the "Clear the media cache" patch.
 *
 * <p>Instagram keeps the images and videos it has shown in two caches in its cache folder, and they
 * grow to several gigabytes. While the switch is on, each time Instagram goes to the background and
 * those two hold more than {@link #LIMIT}, HushGram deletes the image files on a background thread
 * and asks for the video cache to go the next time Instagram starts. Nothing else in the cache
 * folder is touched: uploads, drafts being made, saved network answers, HushGram's own saves and the
 * rest stay, and so do sign-in and settings, which live elsewhere. An image written in the last
 * minute stays too, since Instagram may still be writing it. The folders themselves stay, so code
 * holding one keeps working.
 *
 * <p>The two caches, as Instagram 450 names them:
 * <ul>
 *   <li>Images: {@code image_scoped}, the path Meta's storage registry gives the image cache
 *       ({@code X.02ix.A00}, "cache/image_scoped"), and {@code images}, that cache's older name
 *       ({@code X.05Nm.A06}).
 *   <li>Video: {@code ExoPlayerCacheDir}. The video player's cache settings put it in the cache
 *       folder ({@code X.06jh.A0N}, {@code getCacheDir()}), and {@code X.08nf.A00} names its folders
 *       videocache, videoprefetchcache and videocachemetadata.
 * </ul>
 *
 * <p>The video player keeps a table of its cached spans in memory for as long as Instagram runs, so
 * a span deleted under it can be asked for later and fail to play until Instagram restarts. Videos
 * therefore only go at a start, in {@link #beforeVideoCache}, before the player has built its cache:
 * in Instagram 450 the one place that builds it ({@code X.07vu.A01}, CacheManager.initCache, the only
 * maker of {@code X.08pp}, itself the only maker of the cache {@code X.02k0}) asks {@code X.08nf.A00}
 * for its folder first.
 *
 * <p>The Clear now row in HushGram's settings does the same at once, whatever the size, and shows
 * what it freed. Its videos go at the next start whatever the switch says, while a clear over the
 * limit's go only if the switch is still on then.
 */
public final class MediaCache {
    /** The size the two caches may reach before a trip to the background clears them. */
    static final long LIMIT = 500L * 1024 * 1024;

    /** A file younger than this may still be open for writing, so it stays. */
    static final long SETTLE_MILLIS = 60_000;

    /** The image cache's folders in the cache folder (see the class comment). */
    static final List<String> IMAGE_FOLDERS = Collections.unmodifiableList(Arrays.asList("image_scoped", "images"));

    /** The video cache's folder in the cache folder (see the class comment). */
    static final String VIDEO_FOLDER = "ExoPlayerCacheDir";

    /** HushGram's note in the cache folder that Clear now asked for the video cache to go at the next start. */
    static final String VIDEOS_AT_START = "hushgram_clear_videos_at_start";

    /**
     * The note a clear over the limit leaves instead. The next start carries it out only while the
     * switch is still on, and drops it when the switch has been turned off since.
     */
    static final String VIDEOS_OVER_LIMIT = "hushgram_clear_videos_over_limit";

    /** The start of the name a start moves the video cache to, before deleting it there. */
    static final String OLD_VIDEOS = "hushgram_old_videos_";

    /**
     * The saved keys of the switch, Pause and safe mode, read straight from the settings file
     * before the settings can be loaded. Their settings carry the same keys, which a test holds.
     */
    static final String SWITCH_KEY = "hushgram_clear_media_cache";
    static final String PAUSED_KEY = "hushgram_paused";
    static final String SAFE_MODE_KEY = "hushgram_safe_mode";

    /** The step a failed clear is reported under. */
    static final String CLEAR = "clear";

    /** What's counted for each clear that found the cache over the limit. */
    static final String CLEARED = "cleared over the limit";

    /** What's counted for each start that cleared the video cache. */
    static final String VIDEOS_CLEARED = "videos cleared at a start";

    private static final AtomicBoolean clearing = new AtomicBoolean();
    private static volatile boolean watching;
    /** Instagram has asked for its video cache's folder in this process, so the player may hold it now. */
    private static volatile boolean videoCacheNamed;

    /** Whether a folder is a link, which a start never moves. Tests stand in for it, since making one may need rights they don't have. */
    static final Predicate<File> LINKED = file -> Files.isSymbolicLink(file.toPath());
    static volatile Predicate<File> linked = LINKED;

    private MediaCache() {
    }

    /**
     * Called once Instagram's application has started. With the patch in, HushGram hears each time
     * Instagram's screens leave the foreground and clears the cache then if it's over the limit.
     */
    public static void watch(Context context) {
        try {
            if (watching || !SettingsStatus.mediaCache()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            application.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level == TRIM_MEMORY_UI_HIDDEN) onBackground(application, MediaCache::switchedOn);
                }

                @Override
                public void onConfigurationChanged(Configuration configuration) {
                }

                @Override
                public void onLowMemory() {
                }
            });
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
        }
    }

    /** Queues a clear when the switch is on. Answers whether it queued one. */
    static boolean onBackground(Context context, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.MEDIA_CACHE);
            if (!on.getAsBoolean()) return false;
            return Utils.runOnBackgroundThread(() -> clearIfOver(context, LIMIT, System.currentTimeMillis()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            return false;
        }
    }

    /**
     * Clears the two caches when they hold more than [limit]: the images now, the videos at the next
     * start. Answers the bytes freed now: none under the limit, while another clear is running, or on
     * a failure.
     */
    static long clearIfOver(Context context, long limit, long now) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            File cache = context.getCacheDir();
            if (cache == null) return 0;
            long size = 0;
            for (File folder : folders(cache)) size += sizeOf(folder);
            if (size <= limit) return 0;
            long freed = clearMedia(cache, now, VIDEOS_OVER_LIMIT);
            HookStatus.counted(FamilyNames.MEDIA_CACHE, CLEARED);
            final long total = size;
            final long gone = freed;
            Logger.printDebug(() -> "Media cache: freed " + gone + " of " + total + " bytes, videos at the next start");
            return freed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            return 0;
        } finally {
            clearing.set(false);
        }
    }

    /** Clear now: the two caches whatever their size, by the same rules. Answers the bytes freed now. */
    public static long clearNow(Context context) {
        if (!clearing.compareAndSet(false, true)) return 0;
        try {
            File cache = context.getCacheDir();
            return cache == null ? 0 : clearMedia(cache, System.currentTimeMillis(), VIDEOS_AT_START);
        } finally {
            clearing.set(false);
        }
    }

    /** Whether the video cache is waiting to go at the next start. */
    public static boolean videosWaiting(Context context) {
        File cache = context.getCacheDir();
        return cache != null && (new File(cache, VIDEOS_AT_START).isFile() || new File(cache, VIDEOS_OVER_LIMIT).isFile());
    }

    /**
     * Injected first in the method that names the video cache's folders, with the folder they go
     * under. Instagram builds its video cache only after asking that method for its folder, so the
     * first call in a process comes before the player holds any of it. When a clear asked for it,
     * that call moves the video cache aside and deletes it on a background thread, and the player
     * starts on an empty one. Another thread asking meanwhile waits for the move. Every later call
     * returns at once. Never throws.
     */
    public static void beforeVideoCache(String folder) {
        beforeVideoCache(folder, MediaCache::switchAnswer);
    }

    static void beforeVideoCache(String folder, Supplier<Boolean> on) {
        if (videoCacheNamed) return;
        synchronized (MediaCache.class) {
            if (videoCacheNamed) return;
            try {
                HookStatus.invoked(FamilyNames.MEDIA_CACHE);
                if (folder != null && !folder.isEmpty() && mainProcess()) clearVideosAtStart(new File(folder), on.get());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            } finally {
                videoCacheNamed = true;
            }
        }
    }

    /** Lets a test start a new process's first call. */
    static void restartForTests() {
        videoCacheNamed = false;
        linked = LINKED;
        early = MediaCache::currentApplication;
    }

    /**
     * Moves the video cache under [cache] aside when Clear now asked for it, or a clear over the
     * limit did and the switch is still [on], then deletes on a background thread whatever an earlier
     * start moved aside and didn't get to delete. With the switch off, a clear over the limit's note
     * goes unheeded, and so do both notes when the video cache is a link. When the switch can't be
     * read at all, or this start may yet turn safe mode on, [on] is null and a clear over the
     * limit's note waits for a start that can tell.
     */
    private static void clearVideosAtStart(File cache, Boolean on) {
        File asked = new File(cache, VIDEOS_AT_START);
        File over = new File(cache, VIDEOS_OVER_LIMIT);
        if (Boolean.FALSE.equals(on)) drop(over);
        if (asked.isFile() || (Boolean.TRUE.equals(on) && over.isFile())) {
            File video = new File(cache, VIDEO_FOLDER);
            if (linked.test(video)) {
                drop(asked);
                drop(over);
            } else if (!video.exists() || video.renameTo(new File(cache, OLD_VIDEOS + System.currentTimeMillis()))) {
                drop(asked);
                drop(over);
                HookStatus.counted(FamilyNames.MEDIA_CACHE, VIDEOS_CLEARED);
            }
        }
        File[] old = cache.listFiles((parent, name) -> name.startsWith(OLD_VIDEOS));
        if (old == null || old.length == 0) return;
        Utils.runOnBackgroundThread(() -> {
            for (File moved : old) delete(moved);
        });
    }

    /** Deletes a note, if it's there. */
    private static void drop(File note) {
        if (note.isFile() && !note.delete()) Logger.printDebug(() -> "Media cache: the note " + note.getName() + " stayed");
    }

    /** The image folders and the video folder in [cache]. */
    static List<File> folders(File cache) {
        List<File> folders = new ArrayList<>(IMAGE_FOLDERS.size() + 1);
        for (String name : IMAGE_FOLDERS) folders.add(new File(cache, name));
        folders.add(new File(cache, VIDEO_FOLDER));
        return folders;
    }

    /** Deletes the settled images, and leaves [note] for the video cache to go at the next start when it holds any. */
    private static long clearMedia(File cache, long now, String note) {
        long freed = 0;
        for (String name : IMAGE_FOLDERS) freed += clear(new File(cache, name), now);
        if (sizeOf(new File(cache, VIDEO_FOLDER)) > 0) {
            try {
                File asked = new File(cache, note);
                if (!asked.createNewFile() && !asked.isFile()) throw new IllegalStateException("can't write " + asked);
            } catch (Exception failure) {
                HookStatus.threw(FamilyNames.MEDIA_CACHE, CLEAR, failure);
            }
        }
        return freed;
    }

    private static long sizeOf(File file) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (!file.isDirectory()) return file.isFile() ? file.length() : 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        long size = 0;
        for (File child : children) size += sizeOf(child);
        return size;
    }

    /** Deletes the files under [file] that weren't written in the last {@link #SETTLE_MILLIS}. */
    private static long clear(File file, long now) {
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return 0;
            long freed = 0;
            for (File child : children) freed += clear(child, now);
            return freed;
        }
        if (!file.isFile() || now - file.lastModified() < SETTLE_MILLIS) return 0;
        long length = file.length();
        return file.delete() ? length : 0;
    }

    /** Deletes [file] and everything under it, a link without what it points to. */
    private static void delete(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!file.delete()) Logger.printDebug(() -> "Media cache: couldn't delete " + file);
    }

    /** Whether this is Instagram's own process, where its video player runs, and not one of its helpers. */
    private static boolean mainProcess() {
        String name = Application.getProcessName();
        return name == null || name.indexOf(':') < 0;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.CLEAR_MEDIA_CACHE.get();
    }

    /** The switch, or null while the settings can't be read yet. */
    /**
     * The switch as this start reads it. Instagram can name its video cache before its application's
     * onCreate, where HushGram's settings come ready, and this runs once a process: waiting for a
     * start that can read the settings could wait forever. So before they're ready the saved value
     * is read straight from the settings file, through the application Android already has.
     */
    private static Boolean switchAnswer() {
        if (Utils.settingsReady()) return Settings.CLEAR_MEDIA_CACHE.get();
        return savedAnswer(early.get());
    }

    /** The application before HushGram is handed one. Tests swap it. */
    static volatile Supplier<Context> early = MediaCache::currentApplication;

    /** Android's application for this process, set before any provider or onCreate runs, or null. */
    static Context currentApplication() {
        try {
            Object application = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * The switch as its saved value in [context]'s settings file has it, the way a setting answers
     * once the settings are ready: off while HushGram is paused, by the Pause switch, safe mode or
     * the marker file. Null when there's no context or the file can't be read. Loads no setting,
     * which can't be done before HushGram has a context.
     *
     * <p>Null too while the last start's record is still there ({@link HushgramPause#START_RECORD_NAME}):
     * that start died young, so this one may be the third crash in a row, which turns safe mode on
     * only once HushGram has its context, after this has read the switch. A later start reads it.
     */
    static Boolean savedAnswer(Context context) {
        if (context == null) return null;
        try {
            File files = context.getExternalFilesDir(null);
            if (files != null && new File(files, HushgramPause.MARKER_FILE_NAME).exists()) return false;
            SharedPreferences saved = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (saved.getBoolean(PAUSED_KEY, false) || saved.getBoolean(SAFE_MODE_KEY, false)) return false;
            File own = context.getFilesDir();
            if (own != null && new File(own, HushgramPause.START_RECORD_NAME).exists()) return null;
            return saved.getBoolean(SWITCH_KEY, false);
        } catch (Throwable failure) {
            return null;
        }
    }
}
