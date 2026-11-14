/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.hushgram.extension.shared.Logger;

/**
 * Fetches a small picture for one of HushGram's own lists, like the profile pictures in See who a
 * story mentions' list, the way View profile picture fetches one: HTTPS to Meta's media servers on
 * the first address and every redirect, checked by {@link MediaUrlPolicy}, and an answer that says
 * it's a picture and looks like one. It goes through a work file, which is gone once the picture
 * is read.
 */
public final class PictureFetch {
    /** A profile picture is tens of KB. Anything past this isn't one. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private PictureFetch() {
    }

    /**
     * The picture at [url], decoded at no less than [size] pixels across where it's that big, or
     * null when it can't be fetched or read. Blocks on the network: call it on a worker thread.
     * Never throws.
     */
    @Nullable
    public static Bitmap fetch(Context context, String url, int size) {
        File file = null;
        try {
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            File folder = DashSave.workFolder(application);
            if (folder == null) return null;
            file = File.createTempFile("image", ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, Downloader.Kind.IMAGE, file,
                    MediaSave.policyFor(application), MAX_BYTES, Downloader.SILENT);
            if (!fetched.ok()) {
                Logger.printDebug(() -> "A picture wasn't fetched: " + fetched.reason);
                return null;
            }
            return decode(file, size);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A picture wasn't fetched: " + failure);
            return null;
        } finally {
            DashSave.discard(file);
        }
    }

    /**
     * Fetches the picture at [url] into [into] byte for byte as the server sent it, replacing what
     * [into] held, so a copy kept from it is the size it came at and never compressed again.
     * Answers whether [into] holds it now. Blocks on the network: call it on a worker thread.
     * Never throws.
     */
    public static boolean fetchInto(Context context, String url, File into) {
        File file = null;
        try {
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            File folder = DashSave.workFolder(application);
            if (folder == null) return false;
            file = File.createTempFile("image", ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, Downloader.Kind.IMAGE, file,
                    MediaSave.policyFor(application), MAX_BYTES, Downloader.SILENT);
            if (!fetched.ok()) {
                Logger.printDebug(() -> "A picture wasn't fetched: " + fetched.reason);
                return false;
            }
            if (width(file) <= 0) return false;
            Files.move(file.toPath(), into.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A picture wasn't fetched: " + failure);
            return false;
        } finally {
            DashSave.discard(file);
        }
    }

    /** [file]'s width in pixels as a picture, or 0 when it isn't one or can't be read. Never throws. */
    static int width(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            decodeFile(file, bounds);
            return bounds.outHeight > 0 ? Math.max(bounds.outWidth, 0) : 0;
        } catch (IOException | RuntimeException failure) {
            return 0;
        }
    }

    /** The kind of picture [file] holds by its first bytes, or null when it doesn't start like one. */
    @Nullable
    static String mime(File file) throws IOException {
        byte[] head = new byte[64];
        int length = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while (length < head.length && (read = in.read(head, length, head.length - length)) > 0) length += read;
        }
        return length == 0 ? null : Downloader.sniff(Downloader.Kind.IMAGE, head, length);
    }

    /** [file] decoded at the smallest power-of-two step that's still [size] pixels across. */
    @Nullable
    static Bitmap decode(File file, int size) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decodeFile(file, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int step = 1;
        int smaller = Math.min(bounds.outWidth, bounds.outHeight);
        while (size > 0 && smaller / (step * 2) >= size) step *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = step;
        return decodeFile(file, options);
    }

    /**
     * [file] decoded with [options] through a stream that's closed before this returns, so the work
     * file can be deleted right after. Android 17's own decodeFile decodes from a file descriptor it
     * opens itself, and under the Windows test host that left the file open and undeletable.
     */
    @Nullable
    static Bitmap decodeFile(File file, BitmapFactory.Options options) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }
}
