/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Fetches the sprite images behind a seek bar's preview ({@code SeekPreview}, #567), through the save
 * pipeline's {@link Downloader}, so only from Meta's media servers. Nothing is fetched until someone
 * drags a bar with the preview on, and only the sprite that drag reaches. A sprite that failed isn't
 * asked for again until Instagram restarts, and the card goes on showing the time.
 */
public final class SeekSprites {
    private static final long MAX_BYTES = 4L * 1024L * 1024L;
    private static final int CACHE_BYTES = 16 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    /** The sprites being fetched, and the ones that failed. */
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "hushgram-seek-sprites");
        thread.setDaemon(true);
        return thread;
    });

    private SeekSprites() {
    }

    /** The sprite at [url], if it's been fetched. */
    @Nullable
    public static Bitmap cached(String url) {
        return CACHE.get(url);
    }

    /** Fetches the sprite at [url] unless that's been tried already, then runs [ready] on the main thread. */
    public static void load(Context context, String url, Runnable ready) {
        if (CACHE.get(url) != null || !ASKED.add(url)) return;
        File into = new File(context.getCacheDir(), "hushgram-seek-sprite-" + Integer.toHexString(url.hashCode()));
        LOADER.execute(() -> {
            try {
                Downloader.Result result = Downloader.fetch(url, Downloader.Kind.IMAGE, into, MediaUrlPolicy.META, MAX_BYTES, Downloader.SILENT);
                if (result.status != Downloader.Status.OK) {
                    Logger.printDebug(() -> "Seek preview: no picture strip: " + result.reason);
                    return;
                }
                Bitmap sprite = BitmapFactory.decodeFile(into.getPath());
                if (sprite == null) return;
                CACHE.put(url, sprite);
                ASKED.remove(url);
                Utils.runOnMainThread(ready);
            } catch (Throwable failure) {
                Logger.printException(() -> "Seek preview: could not fetch a picture strip", failure);
            } finally {
                Downloader.delete(into);
            }
        });
    }
}
