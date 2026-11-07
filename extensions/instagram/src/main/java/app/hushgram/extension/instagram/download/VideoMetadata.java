/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Writes where a saved video came from into the MP4 itself: the account that posted it as the
 * Artist, when the post went up as the content date and a link to the post as the Description.
 * They go in the moov's udta/meta/ilst, the iTunes item list that exiftool, Android's
 * MediaMetadataRetriever and most galleries read, and nowhere near the picture or the sound.
 *
 * <p>The media never moves. A new moov, the old one with that item list added, goes on the end of
 * the file, and only once it reads back right does the old moov's type change to {@code free},
 * which every reader skips. The chunk offsets inside the moov point into the mdat, which stays
 * where it was, so no offset changes, and the file plays at every step: before the append, with
 * both moovs (a reader takes the first) and after. A failure puts the old type back and, once that
 * reads back from the disk, cuts the file to its old length. When it doesn't, the new moov stays on
 * the end, so the file always holds one whole moov. The price is the old moov left behind as free
 * space, tens of KB on a long video.
 *
 * <p>Anything it doesn't fully understand is left as it is: a file that doesn't start with ftyp,
 * top-level boxes that don't tile it exactly, no mdat, no moov or more than one, a fragmented file
 * (moof, mfra, sidx, styp, emsg, or an mvex in the moov), and a user data meta that isn't an iTunes
 * item list in a full box.
 *
 * <p>The write reads only what {@link PostMetadata} hands it, on the save worker thread, after the
 * file is published. It never runs at Instagram's startup.
 */
final class VideoMetadata {

    private VideoMetadata() {}

    /** The largest moov read into memory. A long video's sample tables are a small part of this. */
    static final int MAX_MOOV = 16 << 20;

    /** More top-level boxes than any saved MP4 has. A file past it is left alone. */
    private static final int MAX_TOP_LEVEL = 1024;

    /** The item list's date form, ISO 8601 in UTC. */
    private static final String ISO_DATE = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    private static final int FTYP = type("ftyp");
    private static final int MOOV = type("moov");
    private static final int MDAT = type("mdat");
    private static final int MOOF = type("moof");
    private static final int MFRA = type("mfra");
    private static final int SIDX = type("sidx");
    private static final int STYP = type("styp");
    private static final int EMSG = type("emsg");
    private static final int MVEX = type("mvex");
    private static final int UDTA = type("udta");
    private static final int META = type("meta");
    private static final int HDLR = type("hdlr");
    private static final int ILST = type("ilst");
    private static final int DATA = type("data");
    private static final int MDIR = type("mdir");
    private static final int APPL = type("appl");

    /** The item types written: Artist, content date and Description. */
    static final int ARTIST = itunes("ART");
    static final int DAY = itunes("day");
    static final int DESCRIPTION = type("desc");

    private static final byte[] FREE_TYPE = {'f', 'r', 'e', 'e'};
    private static final byte[] MOOV_TYPE = {'m', 'o', 'o', 'v'};

    /** A box type as the int its four characters spell, first character highest. */
    static int type(String name) {
        return (name.charAt(0) << 24) | (name.charAt(1) << 16) | (name.charAt(2) << 8) | name.charAt(3);
    }

    /** An iTunes item type: the copyright sign, byte 0xA9, then three letters. */
    static int itunes(String letters) {
        return 0xA9000000 | (letters.charAt(0) << 16) | (letters.charAt(1) << 8) | letters.charAt(2);
    }

    /** A saved file, read and written at byte positions. */
    interface Store {
        long size() throws IOException;

        /** Exactly [length] bytes from [at], or an EOFException when the file ends first. */
        byte[] read(long at, int length) throws IOException;

        void write(long at, byte[] bytes) throws IOException;

        /** Puts what's been written on the disk. */
        void sync() throws IOException;

        void truncate(long size) throws IOException;
    }

    /** A store reading through [reader] and writing through [writer], the same channel for a path. */
    static Store over(FileChannel reader, FileChannel writer) {
        return new Store() {
            @Override public long size() throws IOException {
                return reader.size();
            }

            @Override public byte[] read(long at, int length) throws IOException {
                ByteBuffer buffer = ByteBuffer.allocate(length);
                while (buffer.hasRemaining()) {
                    if (reader.read(buffer, at + buffer.position()) < 0) {
                        throw new EOFException("the file ends at " + (at + buffer.position()));
                    }
                }
                return buffer.array();
            }

            @Override public void write(long at, byte[] bytes) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) writer.write(buffer, at + buffer.position());
            }

            @Override public void sync() throws IOException {
                writer.force(false);
            }

            @Override public void truncate(long size) throws IOException {
                writer.truncate(size);
            }
        };
    }

    /** One box: its type, where it starts, its header's length and its whole size. */
    private static final class Box {
        final int type;
        final long start;
        final int header;
        final long size;

        Box(int type, long start, int header, long size) {
            this.type = type;
            this.start = start;
            this.header = header;
            this.size = size;
        }

        long content() {
            return start + header;
        }

        long end() {
            return start + size;
        }
    }

    private static String stamp(Date posted) {
        SimpleDateFormat format = new SimpleDateFormat(ISO_DATE, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(posted);
    }

    /**
     * What the save knows about [details] as items, in the order they're written: the poster as
     * Artist, the post time as the content date and the post link as Description. Empty when the
     * save knows none of them.
     */
    static Map<Integer, String> items(PostDetails details) {
        Map<Integer, String> items = new LinkedHashMap<>();
        if (details == null) return items;
        if (details.hasOwner()) items.put(ARTIST, details.owner);
        if (details.hasPosted()) items.put(DAY, stamp(details.posted));
        String link = PostMetadata.permalink(details);
        if (link != null) items.put(DESCRIPTION, link);
        return items;
    }

    /**
     * Writes the post's details into the MP4 or MKV ([mime]) the save published at [published]: a
     * {@code file://} or bare path the save owns on Android 9, the {@code content://} it inserted
     * from Android 10, opened read-write. The switch is the caller's to check. Fail open: the video
     * is already saved, so a write that throws is logged and swallowed, and the file is as it was.
     */
    static void writeTo(Context context, String published, String mime, PostDetails details) {
        Map<Integer, String> items = items(details);
        if (context == null || published == null || items.isEmpty()) return;
        boolean mkv = MkvJoin.MIME.equals(mime);
        try {
            Uri uri = Uri.parse(published);
            String scheme = uri.getScheme();
            boolean wrote;
            if (scheme == null || "file".equals(scheme)) {
                String path = scheme == null ? published : uri.getPath();
                // "rw" makes a file that isn't there, so a save that's gone stays gone.
                if (path == null || !new File(path).isFile()) return;
                try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
                    Store store = over(file.getChannel(), file.getChannel());
                    wrote = mkv ? MkvJoin.tag(store, items) : write(store, items);
                }
                if (wrote) PostMetadata.rescan(context, path, mkv ? MkvJoin.MIME : "video/mp4");
            } else {
                try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (descriptor == null) return;
                    // Android's streams over a descriptor don't own it, so it closes once, last.
                    try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
                         FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor())) {
                        Store store = over(in.getChannel(), out.getChannel());
                        wrote = mkv ? MkvJoin.tag(store, items) : write(store, items);
                    }
                }
            }
            if (wrote) MediaSave.info(() -> "tagged the saved video with where it came from");
            else MediaSave.info(() -> "left the saved video untagged: not a layout the tag is written into");
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not tag a saved video with where it came from", t);
        }
    }

    /**
     * Writes [items] into the MP4 in [store]. False, with the file untouched, when there's nothing
     * to write or the file isn't one this understands. A read or write that fails is thrown, after
     * the old moov's type is put back and, when that holds, the old length.
     */
    static boolean write(Store store, Map<Integer, String> items) throws IOException {
        if (items == null || items.isEmpty()) return false;
        long length = store.size();
        Box moov = movie(store, length);
        if (moov == null) return false;
        byte[] next = tagged(store.read(moov.start, (int) moov.size), items);
        if (next == null) return false;
        try {
            store.write(length, next);
            if (!Arrays.equals(store.read(length, next.length), next)) {
                throw new IOException("the new moov read back different");
            }
            store.sync();
            store.write(moov.start + 4, FREE_TYPE);
            if (!Arrays.equals(store.read(moov.start + 4, 4), FREE_TYPE)) {
                throw new IOException("the old moov kept its type");
            }
            store.sync();
        } catch (Throwable failure) {
            // Back as it was: the old moov's own type first, so it's the file's moov again, then
            // the old length. The end is cut only once the old moov reads back as moov on the
            // disk. Until then the new moov on the end may be the file's only moov, and a file
            // with two whole moovs plays where a file with none doesn't.
            try {
                store.write(moov.start + 4, MOOV_TYPE);
            } catch (Throwable undo) {
                failure.addSuppressed(undo);
            }
            boolean restored = false;
            try {
                store.sync();
                restored = Arrays.equals(store.read(moov.start + 4, 4), MOOV_TYPE);
            } catch (Throwable undo) {
                failure.addSuppressed(undo);
            }
            if (restored) {
                try {
                    store.truncate(length);
                    store.sync();
                } catch (Throwable undo) {
                    failure.addSuppressed(undo);
                }
            }
            throw failure;
        }
        return true;
    }

    /**
     * The file's moov, or null when the file isn't a plain MP4 this writes into: the first box
     * isn't ftyp, the top-level boxes don't tile the file exactly, there's no mdat, no moov or more
     * than one, a box says the file is fragmented, or the moov is past {@link #MAX_MOOV}.
     */
    private static Box movie(Store store, long length) throws IOException {
        Box moov = null;
        boolean media = false;
        int count = 0;
        long at = 0;
        while (at < length) {
            if (length - at < 8 || ++count > MAX_TOP_LEVEL) return null;
            byte[] head = store.read(at, (int) Math.min(16, length - at));
            long size = u32(head, 0);
            int header = 8;
            if (size == 1) {
                if (head.length < 16) return null;
                size = u64(head, 8);
                header = 16;
            }
            // A size of 0, to the end of the file, falls out here too: nothing can go after it.
            if (size < header || size > length - at) return null;
            int type = s32(head, 4);
            if (at == 0 && type != FTYP) return null;
            if (type == MOOV) {
                if (moov != null) return null;
                moov = new Box(type, at, header, size);
            } else if (type == MDAT) {
                media = true;
            } else if (type == MOOF || type == MFRA || type == SIDX || type == STYP || type == EMSG) {
                return null;
            }
            at += size;
        }
        return moov != null && media && moov.size <= MAX_MOOV ? moov : null;
    }

    /**
     * [moov], the whole box, with [items] in its udta/meta/ilst, or null when it isn't a moov this
     * writes into. Every other box inside is kept byte for byte, in its place, and an item of a
     * type not written here stays in the list.
     */
    static byte[] tagged(byte[] moov, Map<Integer, String> items) {
        List<Box> top = children(moov, 0, moov.length);
        if (top == null || top.size() != 1 || top.get(0).type != MOOV) return null;
        Box movie = top.get(0);
        List<Box> inside = children(moov, movie.content(), movie.end());
        if (inside == null) return null;
        Box udta = null;
        for (Box box : inside) {
            if (box.type == MVEX) return null;
            if (box.type == UDTA) {
                if (udta != null) return null;
                udta = box;
            }
        }
        byte[] data = udta == null ? box(UDTA, newMeta(items)) : userData(moov, udta, items);
        if (data == null) return null;
        return box(MOOV, spliced(moov, movie, 0, inside, udta, data));
    }

    /** [udta] with [items] in its meta, a new one when it has none. */
    private static byte[] userData(byte[] bytes, Box udta, Map<Integer, String> items) {
        List<Box> kids = children(bytes, udta.content(), udta.end());
        if (kids == null) return null;
        Box meta = null;
        for (Box kid : kids) {
            if (kid.type == META) {
                if (meta != null) return null;
                meta = kid;
            }
        }
        byte[] next = meta == null ? newMeta(items) : itemMeta(bytes, meta, items);
        if (next == null) return null;
        return box(UDTA, spliced(bytes, udta, 0, kids, meta, next));
    }

    /** A meta holding only the iTunes handler and [items]. */
    private static byte[] newMeta(Map<Integer, String> items) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(0);
        content.write(0);
        content.write(0);
        content.write(0);
        byte[] handler = handler();
        content.write(handler, 0, handler.length);
        byte[] list = itemList(null, null, items);
        content.write(list, 0, list.length);
        return box(META, content.toByteArray());
    }

    /**
     * [meta] with [items] in its item list, or null when it isn't the iTunes item list's meta. That
     * meta is a full box, version and flags of 0 ahead of its children. QuickTime writes one without
     * them, which exiftool doesn't read as an item list under a moov's udta, so it's left alone.
     */
    private static byte[] itemMeta(byte[] bytes, Box meta, Map<Integer, String> items) {
        long from = meta.content();
        int skip = 4;
        if (meta.end() - from < skip || s32(bytes, from) != 0) return null;
        List<Box> kids = children(bytes, from + skip, meta.end());
        if (kids == null) return null;
        Box hdlr = null;
        Box ilst = null;
        for (Box kid : kids) {
            if (kid.type == HDLR) {
                if (hdlr != null) return null;
                hdlr = kid;
            } else if (kid.type == ILST) {
                if (ilst != null) return null;
                ilst = kid;
            }
        }
        // The handler is the full box's third word: version and flags, pre_defined, then the type.
        if (hdlr == null || hdlr.size < hdlr.header + 12 || s32(bytes, hdlr.content() + 8) != MDIR) return null;
        byte[] list = itemList(bytes, ilst, items);
        if (list == null) return null;
        return box(META, spliced(bytes, meta, skip, kids, ilst, list));
    }

    /**
     * The item list: [ilst]'s items in their order, less any of a type written here, then
     * [items]. Null when [ilst]'s items don't tile it.
     */
    private static byte[] itemList(byte[] bytes, Box ilst, Map<Integer, String> items) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        if (ilst != null) {
            List<Box> kept = children(bytes, ilst.content(), ilst.end());
            if (kept == null) return null;
            for (Box item : kept) {
                if (!items.containsKey(item.type)) content.write(bytes, (int) item.start, (int) item.size);
            }
        }
        for (Map.Entry<Integer, String> item : items.entrySet()) {
            byte[] written = item(item.getKey(), item.getValue());
            content.write(written, 0, written.length);
        }
        return box(ILST, content.toByteArray());
    }

    /** One item: a box of the item's type around a data box of UTF-8 text, type 1, no locale. */
    private static byte[] item(int type, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(8 + text.length);
        data.putInt(1).putInt(0).put(text);
        return box(type, box(DATA, data.array()));
    }

    /** The handler an iTunes item list sits under: type mdir, appl in its reserved word, no name. */
    private static byte[] handler() {
        ByteBuffer content = ByteBuffer.allocate(25);
        content.putInt(0).putInt(0).putInt(MDIR).putInt(APPL).putInt(0).putInt(0).put((byte) 0);
        return box(HDLR, content.array());
    }

    private static byte[] box(int type, byte[] content) {
        ByteBuffer box = ByteBuffer.allocate(8 + content.length);
        box.putInt(8 + content.length).putInt(type).put(content);
        return box.array();
    }

    /**
     * The content of the box [parent] in [bytes], with its child [old] swapped for [with], or
     * [with] added after the last child when [old] is null. The [skip] bytes ahead of the children,
     * a full box's version and flags, are kept, and so is anything after the last child.
     */
    private static byte[] spliced(byte[] bytes, Box parent, int skip, List<Box> kids, Box old, byte[] with) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        int from = (int) parent.content();
        content.write(bytes, from, skip);
        int at = from + skip;
        for (Box kid : kids) {
            if (kid == old) content.write(with, 0, with.length);
            else content.write(bytes, (int) kid.start, (int) kid.size);
            at = (int) kid.end();
        }
        if (old == null) content.write(with, 0, with.length);
        content.write(bytes, at, (int) parent.end() - at);
        return content.toByteArray();
    }

    /**
     * The boxes in [bytes] from [from] to [to], or null when they don't tile it. The list may end
     * in four zero bytes, which QuickTime writes after a user data list's last box. They aren't a
     * box and aren't listed.
     */
    private static List<Box> children(byte[] bytes, long from, long to) {
        List<Box> boxes = new ArrayList<>();
        long at = from;
        while (at < to) {
            if (to - at == 4 && s32(bytes, at) == 0) break;
            if (to - at < 8) return null;
            long size = u32(bytes, at);
            int header = 8;
            if (size == 1) {
                if (to - at < 16) return null;
                size = u64(bytes, at + 8);
                header = 16;
            }
            if (size < header || size > to - at) return null;
            boxes.add(new Box(s32(bytes, at + 4), at, header, size));
            at += size;
        }
        return boxes;
    }

    private static int s32(byte[] bytes, long at) {
        int i = (int) at;
        return ((bytes[i] & 0xFF) << 24) | ((bytes[i + 1] & 0xFF) << 16) | ((bytes[i + 2] & 0xFF) << 8)
                | (bytes[i + 3] & 0xFF);
    }

    private static long u32(byte[] bytes, long at) {
        return s32(bytes, at) & 0xFFFFFFFFL;
    }

    private static long u64(byte[] bytes, long at) {
        return (u32(bytes, at) << 32) | u32(bytes, at + 4);
    }
}
