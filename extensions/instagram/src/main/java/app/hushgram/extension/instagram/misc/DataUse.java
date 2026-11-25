/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.hushgram.extension.shared.Logger;

/**
 * What takes up Instagram's data, for the Space used row under Storage (#683). Android's User data
 * is everything Instagram keeps outside its cache folders, so this adds up the data folder and
 * Instagram's own folder in shared storage, each without {@code cache} and {@code code_cache}, and
 * names the biggest folders one level in, like {@code files/mqtt} or {@code databases}. HushGram's
 * own files, every name starting with "hushgram" at those two levels, are added up apart. Links
 * aren't followed, so the app's libraries aren't counted. Reads only.
 */
public final class DataUse {
    /** How many of the biggest folders the row names. */
    static final int BIGGEST = 3;

    /** A folder or file and the bytes under it. */
    public static final class Part {
        public final String name;
        public final long bytes;

        Part(String name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    /** Everything counted, in bytes. */
    public final long total;
    /** The biggest folders or files, biggest first, at most {@link #BIGGEST}. */
    public final List<Part> biggest;
    /** HushGram's own files, in bytes, a part of {@link #total}. */
    public final long hushgram;

    private DataUse(long total, List<Part> biggest, long hushgram) {
        this.total = total;
        this.biggest = biggest;
        this.hushgram = hushgram;
    }

    /** Instagram's data now. Walks the folders, so call it off the main thread. Null on a failure. Never throws. */
    @Nullable
    public static DataUse read(Context context) {
        try {
            File external = context.getExternalFilesDir(null);
            return of(context.getDataDir(), external == null ? null : external.getParentFile());
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Data use couldn't be read: " + failure);
            return null;
        }
    }

    /** [data], and [external] named under Android/data, added up the way {@link #read} does. */
    static DataUse of(@Nullable File data, @Nullable File external) {
        List<Part> parts = new ArrayList<>();
        long[] hushgram = {0};
        collect(data, "", parts, hushgram);
        collect(external, "Android/data/", parts, hushgram);
        long total = 0;
        for (Part part : parts) total += part.bytes;
        Collections.sort(parts, (a, b) -> Long.compare(b.bytes, a.bytes));
        List<Part> biggest = new ArrayList<>();
        for (Part part : parts) {
            if (biggest.size() == BIGGEST || part.bytes == 0) break;
            biggest.add(part);
        }
        return new DataUse(total, biggest, hushgram[0]);
    }

    /** Adds [root]'s folders one level in, and its loose files, to [parts], named after [prefix]. */
    private static void collect(@Nullable File root, String prefix, List<Part> parts, long[] hushgram) {
        File[] top = root == null ? null : root.listFiles();
        if (top == null) return;
        for (File folder : top) {
            String name = folder.getName();
            if (name.equals("cache") || name.equals("code_cache") || isLink(folder)) continue;
            if (!folder.isDirectory()) {
                long bytes = sizeOf(folder);
                parts.add(new Part(prefix + name, bytes));
                if (ours(name)) hushgram[0] += bytes;
                continue;
            }
            File[] children = folder.listFiles();
            if (children == null) continue;
            long loose = 0;
            for (File child : children) {
                long bytes = sizeOf(child);
                if (ours(child.getName())) hushgram[0] += bytes;
                if (child.isDirectory()) parts.add(new Part(prefix + name + "/" + child.getName(), bytes));
                else loose += bytes;
            }
            if (loose > 0) parts.add(new Part(prefix + name, loose));
        }
    }

    private static boolean ours(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith("hushgram");
    }

    private static boolean isLink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    private static long sizeOf(File file) {
        if (isLink(file)) return 0;
        if (!file.isDirectory()) return file.isFile() ? file.length() : 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        long size = 0;
        for (File child : children) size += sizeOf(child);
        return size;
    }
}
