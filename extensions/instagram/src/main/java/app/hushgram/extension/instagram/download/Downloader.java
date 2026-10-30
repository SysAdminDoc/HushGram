/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/Downloader.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fetches one address into a file in the cache, checks what arrived, and only then hands it to a
 * {@link Sink}.
 *
 * <p>Holds no Android type, for the same reason as {@link RenditionPicker}: everything here can be
 * run against a local web server under plain JUnit. {@link Sink} is what keeps it that way. The
 * real sink writes through MediaStore. A test sink writes to an array.
 *
 * <p>The shape of this class comes from one measured fact. An address of Facebook carries an
 * {@code oh} and an {@code oe} parameter, the server signs them, and they stop working after a few
 * hours. So there is no queue, no scheduler and no retry anywhere in here. The fetch starts when
 * the user taps and either finishes or reports why not. That is also why this feature does not use
 * {@code DownloadManager}, which is otherwise the obvious choice. That class queues, and a queued
 * fetch of an address with hours to live is the one thing that cannot be allowed.
 *
 * <p>The address itself comes out of Facebook's objects by reflection, so nothing about it or its
 * answer is trusted. Every hop goes through {@link MediaUrlPolicy}; the answer has to say it is a
 * picture or a video and start like one; and it lands in the cache, whole and checked, before the
 * gallery hears of it. A refused or broken fetch never creates a gallery entry, pending or not.
 */
final class Downloader {

    private Downloader() {}

    /** What happened, in the terms that the message to the user needs. */
    enum Status {
        /** The file is written and published. */
        OK,
        /** The server refused the signature. The address is too old to use. */
        EXPIRED,
        /** The server answered, and not with the file. */
        HTTP_ERROR,
        /** The fetch never completed. */
        NETWORK_ERROR,
        /** The bytes arrived incomplete, or they cannot be stored. */
        WRITE_ERROR,
        /**
         * There was nothing to save, or what arrived couldn't be made into one file (tracks that
         * wouldn't join, a photo's music that couldn't be read). Checking the connection or the
         * storage won't help, so the message doesn't ask for it.
         */
        BUILD_ERROR,
        /** The address, a redirect or the answer is not a media file from Meta. */
        REFUSED,
        /** The file is bigger than a save will hold. */
        TOO_LARGE,
        /** The person saving stopped it. */
        CANCELLED
    }

    /**
     * How a running save says how far it has got, and learns that it was cancelled. Pure Java
     * like the rest of this class: {@link SaveControl} turns it into a notification, and a test
     * can drive it without one.
     */
    interface Progress {
        /** Bytes of the file being fetched so far, and its announced length or -1. Called often. */
        void transferred(long done, long total);

        /**
         * How to close the connection about to be read. A cancel closes it, which is what ends a
         * read that's waiting on the network; a flag alone is only seen between reads. A closer
         * rather than the connection, so only this class touches the network.
         */
        void reading(Runnable close);

        /** Whether the person saving asked to stop. */
        boolean cancelled();

        /** The fetched tracks are being joined into one file. */
        default void joining() {
        }

        /**
         * The fetched picture is being converted to H.264, [percent] of it so far, or -1 when its
         * length isn't known.
         */
        default void converting(int percent) {
        }

        /** The finished file is being copied into the gallery. */
        default void saving() {
        }
    }

    /** No one is watching and nothing can cancel. */
    static final Progress SILENT = new Progress() {
        @Override
        public void transferred(long done, long total) {
        }

        @Override
        public void reading(Runnable close) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /**
     * [progress] told of a second fetch of the same save as the continuation of one that moved
     * [before] bytes. A DASH save fetches its picture and then its sound, and told of each on its
     * own, its notification started over at the sound track and gave that track's size as the
     * whole save's.
     */
    static Progress after(long before, Progress progress) {
        return new Progress() {
            @Override
            public void transferred(long done, long total) {
                progress.transferred(before + done, total < 0 ? -1 : before + total);
            }

            @Override
            public void reading(Runnable close) {
                progress.reading(close);
            }

            @Override
            public boolean cancelled() {
                return progress.cancelled();
            }

            @Override
            public void joining() {
                progress.joining();
            }

            @Override
            public void converting(int percent) {
                progress.converting(percent);
            }

            @Override
            public void saving() {
                progress.saving();
            }
        };
    }

    /** A cancel seen between two reads of a body. */
    private static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    /** The cache file couldn't be opened or written: the phone's storage, not the network. */
    private static final class StorageFailure extends IOException {
        StorageFailure(IOException cause) {
            super(cause);
        }
    }

    /**
     * The file in the cache a fetch lands in, with each of its failures told apart from the
     * network's. A full or unwritable cache used to reach the person saving as a network failure.
     */
    private static final class CacheFile extends OutputStream {
        private final OutputStream file;

        CacheFile(File into) throws StorageFailure {
            try {
                file = new FileOutputStream(into);
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] { (byte) value }, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                file.write(bytes, offset, length);
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                file.close();
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }
    }

    /** What a save expects to get. The answer has to say so and look it. */
    enum Kind { VIDEO, AUDIO, IMAGE }

    /** A status, and for anything but OK a reason fit for the log (never the address). */
    static final class Result {
        final Status status;
        final String reason;
        /** The type of what was fetched, once it is known to be one. */
        final String mime;
        /**
         * The saved picture is below the best one the manifest offered within the quality setting,
         * the one Facebook's player can show, so the person saving is told.
         */
        final boolean lower;
        /**
         * A failure for want of free space on the phone: a work file or the joined file had no room
         * beyond what a save leaves free ({@code DashSave.KEEP_FREE}) (#348).
         */
        final boolean noRoom;
        /**
         * With [lower], the save fell back to a smaller picture because the phone's storage was low,
         * so the person saving is told storage was the reason (#348).
         */
        final boolean lowStorage;

        private Result(Status status, String reason, String mime, boolean lower, boolean noRoom, boolean lowStorage) {
            this.status = status;
            this.reason = reason;
            this.mime = mime;
            this.lower = lower;
            this.noRoom = noRoom;
            this.lowStorage = lowStorage;
        }

        static Result ok(String mime) {
            return new Result(Status.OK, null, mime, false, false, false);
        }

        static Result fail(Status status, String reason) {
            return new Result(status, reason, null, false, false, false);
        }

        /** A write failure for want of free space (#348). */
        static Result noRoom(String reason) {
            return new Result(Status.WRITE_ERROR, reason, null, false, true, false);
        }

        /** This result, told that the saved picture is below the manifest's. */
        Result lower() {
            return new Result(status, reason, mime, true, noRoom, lowStorage);
        }

        /** This result, told that the saved picture is below the manifest's because storage was low. */
        Result lowerForStorage() {
            return new Result(status, reason, mime, true, noRoom, true);
        }

        boolean ok() {
            return status == Status.OK;
        }

        @Override
        public String toString() {
            return reason == null ? status.name() : status + " (" + reason + ")";
        }
    }

    /**
     * Where the finished bytes go.
     *
     * <p>{@link #open} is called once, on the worker thread, and only after the whole file has
     * arrived and passed every check. Nothing is created before that, so a refused or broken fetch
     * leaves nothing behind.
     */
    interface Sink {
        OutputStream open(String mime) throws IOException;

        void commit() throws IOException;

        void abandon();
    }

    /** Headers and the already-opened response stream, claimed by exactly one fetch worker. */
    static final class Prepared implements AutoCloseable {
        final HttpURLConnection connection;
        private final DownloadPreparation owner;
        int code;
        String type;
        long length;
        InputStream stream;
        private boolean closed;

        Prepared(HttpURLConnection connection, DownloadPreparation owner) {
            this.connection = connection;
            this.owner = owner;
        }

        void disconnect() {
            try { connection.disconnect(); } catch (Throwable ignored) { /* The worker still closes its stream. */ }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (stream != null) stream.close();
            } finally {
                disconnect();
                owner.closed(this);
            }
        }

        void closeQuietly() {
            try { close(); } catch (Throwable ignored) { /* Preserve the result that required cleanup. */ }
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    /**
     * The read timeout. A test shortens it: the JDK's connection can't be closed under a read
     * that's waiting, so there a cancelled read ends only at the timeout. Android's can, and a
     * cancel ends it at once (see SaveControl.Save.cancel).
     */
    static volatile int readTimeoutMs = READ_TIMEOUT_MS;
    private static final int BUFFER = 64 * 1024;
    static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;

    /** The most of an error answer's body that's read, and the longest spent reading it. */
    private static final int DRAIN_BYTES = 64 * 1024;
    private static final long DRAIN_NANOS = 3_000_000_000L;

    /**
     * Enough of the start of a file to tell a container from a page, and to read the first twelve
     * compatible brands of an ftyp box. At 32 bytes only four fit, and an image can list miaf and a
     * profile or two before the brand that names its coding.
     */
    private static final int HEAD = 64;

    private static final String AVIF = "image/avif";
    private static final String HEIC = "image/heic";
    private static final String HEIF = "image/heif";

    /**
     * Fetch [url] into [into], reporting to [progress] and stopping with CANCELLED when it says so.
     * [maxBytes] is the most the file may hold. Blocking. Never throws. On anything but OK, [into]
     * is deleted.
     *
     * <p>No header is set on the request. A captured address was fetched from an unrelated machine
     * with none at all and answered 200, so a guessed {@code User-Agent} or {@code Referer} can
     * only make a refusal more likely.
     */
    static Result fetch(String url, Kind kind, File into, MediaUrlPolicy policy, long maxBytes, Progress progress) {
        boolean kept = false;
        Result result = null;
        try {
            Object opened = connect(url, kind, policy, maxBytes, progress);
            if (opened instanceof Result) return (Result) opened;
            try (Prepared response = (Prepared) opened) {
                result = receive(response, kind, into, maxBytes, progress);
            }
            // Closing the response is part of the fetch. A close failure must not keep cache bytes.
            kept = result.ok();
            return result;
        } catch (IOException e) {
            if (progress.cancelled()) return cancelled();
            if (result != null && !result.ok()) return result;
            if (e instanceof StorageFailure) {
                return Result.fail(Status.WRITE_ERROR, "the cache could not hold the file: "
                    + e.getCause().getClass().getSimpleName());
            }
            return Result.fail(Status.NETWORK_ERROR, "the fetch failed: " + e.getClass().getSimpleName());
        } catch (Throwable t) {
            if (progress.cancelled()) return cancelled();
            if (result != null && !result.ok()) return result;
            return Result.fail(Status.NETWORK_ERROR, "the fetch failed: " + t.getClass().getSimpleName());
        } finally {
            if (!kept) delete(into);
        }
    }

    private static Result receive(Prepared response, Kind kind, File into, long maxBytes, Progress progress) throws IOException {
        if (progress.cancelled()) return cancelled();
        int code = response.code;
        if (code == 401 || code == 403 || code == 410) {
            drain(response, progress);
            if (progress.cancelled()) return cancelled();
            return Result.fail(Status.EXPIRED, "the server answered " + code);
        }
        // No Range was sent. Only 200 names a complete file, whatever length a partial answer gives.
        if (code != 200) {
            drain(response, progress);
            if (progress.cancelled()) return cancelled();
            return Result.fail(Status.HTTP_ERROR, "the server answered " + code);
        }
        String declared = mimeOf(response.type);
        if (!declaresKind(declared, kind)) {
            return Result.fail(Status.REFUSED, "the server sent " + (declared == null ? "no type" : declared)
                + ", not " + kind.name().toLowerCase(Locale.US));
        }
        long expected = response.length;
        if (expected > maxBytes) {
            return Result.fail(Status.TOO_LARGE, "the server announced " + expected + " bytes, more than " + maxBytes);
        }
        InputStream in = response.stream;
        byte[] head = new byte[HEAD];
        int headLength = readHead(in, head);
        if (headLength == 0) return Result.fail(Status.WRITE_ERROR, "the body was empty");
        String sniffed = sniff(kind, head, headLength);
        if (sniffed == null) {
            return Result.fail(Status.REFUSED, "the body does not start like a " + kind.name().toLowerCase(Locale.US) + " file");
        }
        long total;
        try (OutputStream out = new CacheFile(into)) {
            out.write(head, 0, headLength);
            progress.transferred(headLength, expected);
            total = headLength + copy(in, out, maxBytes - headLength, headLength, expected, progress);
        }
        if (total > maxBytes) return Result.fail(Status.TOO_LARGE, "the body ran past " + maxBytes + " bytes");
        if (expected >= 0 && total != expected) {
            return Result.fail(Status.WRITE_ERROR, "the body was " + total + " of " + expected + " bytes");
        }
        // Name the checked bytes. DASH audio keeps its specific declared container for the join.
        if (kind == Kind.AUDIO && isSpecific(declared)) return Result.ok(declared);
        if (HEIF.equals(sniffed) && isHeifType(declared)) return Result.ok(declared);
        return Result.ok(sniffed);
    }

    /**
     * Copy a finished, checked [file] into [sink] as [mime]. If anything fails once the sink is
     * open, the entry is removed again, and a cancel before the commit leaves no row.
     */
    static Result publish(File file, String mime, Sink sink, Progress progress) {
        boolean committed = false;

        try (InputStream in = new FileInputStream(file)) {
            if (progress.cancelled()) return cancelled();
            progress.saving();
            OutputStream out = sink.open(mime);

            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (progress.cancelled()) return cancelled();
            }
            out.flush();

            if (progress.cancelled()) return cancelled();
            sink.commit();
            committed = true;
            return Result.ok(mime);
        } catch (Throwable t) {
            return Result.fail(Status.WRITE_ERROR, "the gallery refused the file: " + t.getClass().getSimpleName());
        } finally {
            // Anything not committed is abandoned, even when open() itself threw: the gallery's
            // open inserts its pending row before it asks for a stream, so a failure there has
            // already left one. Removing it here, rather than at each failure, is what makes it
            // impossible to leave one behind through a path nobody thought of. abandon() does
            // nothing on a sink that created nothing.
            if (!committed) {
                try {
                    sink.abandon();
                } catch (Throwable ignored) {
                    // Cleaning up must never replace the real failure.
                }
            }
        }
    }

    /**
     * Open [url], following redirects by hand and holding every hop to [policy]. Answers the
     * connection, or the {@link Result} that ends the fetch.
     *
     * <p>{@code HttpURLConnection} follows a redirect only while the protocol stays the same, and
     * quietly hands back the 3xx when it changes. Facebook redirects between hosts, so leaving
     * this to the default means an occasional unexplained failure, and it would also skip the
     * policy on the hop.
     */
    private static Object connect(String url, Kind kind, MediaUrlPolicy policy, long maxBytes, Progress progress) throws IOException {
        // Includes policy discovery and URLConnection's own proxy/DNS/header/stream callbacks.
        return DownloadPreparation.await(progress, (long) CONNECT_TIMEOUT_MS + readTimeoutMs,
                owner -> connectNow(url, kind, policy, maxBytes, owner));
    }

    private static Object connectNow(String url, Kind kind, MediaUrlPolicy policy, long maxBytes,
            DownloadPreparation progress) throws IOException {
        URL current;
        try {
            current = new URL(url);
        } catch (MalformedURLException e) {
            return Result.fail(Status.REFUSED, "the address is not well formed");
        }
        Set<String> visited = new HashSet<>();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (progress.cancelled()) return cancelled();
            MediaUrlPolicy.Refusal refusal = policy.refusal(current);
            if (progress.cancelled()) return cancelled();
            if (refusal != null) {
                return Result.fail(refusal.network ? Status.NETWORK_ERROR : Status.REFUSED,
                    (hop == 0 ? "the address: " : "redirect " + hop + ": ") + refusal.reason);
            }
            if (!visited.add(current.toExternalForm())) {
                return Result.fail(Status.HTTP_ERROR, "redirect " + hop + " loops back to an address already fetched");
            }
            Prepared response = new Prepared((HttpURLConnection) current.openConnection(), progress);
            boolean handedBack = false;
            String location;
            try {
                progress.reading(response);
                if (progress.cancelled()) return cancelled();
                HttpURLConnection connection = response.connection;
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(readTimeoutMs);
                connection.setUseCaches(false);
                connection.connect();
                if (progress.cancelled()) return cancelled();
                int code = connection.getResponseCode();
                if (progress.cancelled()) return cancelled();
                response.code = code;
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    if (code == 200) {
                        response.type = connection.getContentType();
                        response.length = connection.getContentLengthLong();
                        if (progress.cancelled()) return cancelled();
                        if (declaresKind(mimeOf(response.type), kind) && response.length <= maxBytes) {
                            response.stream = connection.getInputStream();
                        }
                    } else response.stream = connection.getErrorStream();
                    if (progress.cancelled()) return cancelled();
                    handedBack = true;
                    return response;
                }
                location = connection.getHeaderField("Location");
                response.stream = connection.getErrorStream();
                drain(response, progress);
            } finally {
                if (!handedBack) response.closeQuietly();
            }
            if (location == null || location.isEmpty()) {
                return Result.fail(Status.HTTP_ERROR, "a redirect named no address");
            }
            try {
                current = new URL(current, location);
            } catch (MalformedURLException e) {
                return Result.fail(Status.HTTP_ERROR, "redirect " + (hop + 1) + " is not well formed");
            }
        }
        return Result.fail(Status.HTTP_ERROR, "more than " + MAX_REDIRECTS + " redirects");
    }

    /** Whether the server's type claims [kind]. A generic binary type is left to the sniff. */
    private static boolean declaresKind(String mime, Kind kind) {
        if (mime == null) return false;
        if (!isSpecific(mime)) return true;
        switch (kind) {
            case VIDEO:
                return mime.startsWith("video/");
            case AUDIO:
                // A DASH sound track is an MP4 file, and a server can call it that.
                return mime.startsWith("audio/") || mime.equals("video/mp4");
            default:
                return mime.startsWith("image/");
        }
    }

    /** A type that names what the bytes are, rather than "some bytes". */
    private static boolean isSpecific(String mime) {
        return mime != null && !mime.equals("application/octet-stream") && !mime.equals("binary/octet-stream");
    }

    /** One of the types a HEIF file can be: AVIF, HEIC or HEIF itself. */
    private static boolean isHeifType(String mime) {
        return AVIF.equals(mime) || HEIC.equals(mime) || HEIF.equals(mime);
    }

    /**
     * The type these first bytes are, if they are a [kind] at all, else {@code null}.
     *
     * <p>An error page, a login wall or a JSON answer sent with a media type starts with text, and
     * no container does. A picture asked for as a video is refused too: saved into Movies with a
     * video name, it looks like a success and isn't.
     */
    static String sniff(Kind kind, byte[] head, int length) {
        String box = length >= 8 && boxSizeFits(head) ? ascii(head, 4, 4) : null;
        boolean isoMedia = box != null && (box.equals("ftyp") || box.equals("styp") || box.equals("moov")
            || box.equals("mdat") || box.equals("free") || box.equals("skip") || box.equals("wide")
            || box.equals("sidx") || box.equals("moof"));
        String brand = isoMedia && box.equals("ftyp") && length >= 12 ? ascii(head, 8, 4) : "";
        boolean imageBrand = isAvifBrand(brand) || isHeicBrand(brand)
            || brand.equals("mif1") || brand.equals("msf1");

        switch (kind) {
            case VIDEO:
                if (isoMedia && !imageBrand) {
                    if (brand.equals("qt  ")) return "video/quicktime";
                    if (brand.startsWith("3gp")) return "video/3gpp";
                    return "video/mp4";
                }
                if (starts(head, length, 0x1A, 0x45, 0xDF, 0xA3)) return "video/webm";
                return null;
            case AUDIO:
                return isoMedia && !imageBrand ? "audio/mp4" : null;
            default:
                if (starts(head, length, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
                if (starts(head, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
                if (length >= 6 && (ascii(head, 0, 6).equals("GIF87a") || ascii(head, 0, 6).equals("GIF89a"))) return "image/gif";
                if (length >= 12 && ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) return "image/webp";
                if (imageBrand) return imageType(brand, head, length);
                return null;
        }
    }

    /**
     * The type of a HEIF-family image, from its ftyp box. An AVIF or HEIC major brand names the
     * coding. mif1 and msf1 only say "a HEIF image" (or sequence), and the compatible brands after
     * them say how it's coded: an AVIF encoder can put mif1 first and list avif later, and read by
     * its first brand alone such a file saved as .heic. One that lists neither is a HEIF of some
     * other coding, and {@link #fetch} keeps a declared AVIF, HEIC or HEIF type for it.
     */
    private static String imageType(String major, byte[] head, int length) {
        if (isAvifBrand(major)) return AVIF;
        if (isHeicBrand(major)) return HEIC;
        // The compatible brands run from the 17th byte to the end of the box: past the size, the
        // name, the major brand and its version.
        long size = boxSize(head);
        long end = size == 0 ? length : Math.min(size, length);
        for (int at = 16; at + 4 <= end; at += 4) {
            String compatible = ascii(head, at, 4);
            if (isAvifBrand(compatible)) return AVIF;
            if (isHeicBrand(compatible)) return HEIC;
        }
        return HEIF;
    }

    /** An AV1 image or image sequence. */
    private static boolean isAvifBrand(String brand) {
        return brand.equals("avif") || brand.equals("avis");
    }

    /** An HEVC image or image sequence, the plain and the extended profiles. */
    private static boolean isHeicBrand(String brand) {
        return brand.equals("heic") || brand.equals("heix") || brand.equals("hevc") || brand.equals("hevx");
    }

    /**
     * Whether the first four bytes can be the size of an ISO media box: 0 (it runs to the end of
     * the file), 1 (a 64-bit size follows its name), or at least its own 8-byte header and at most
     * the cap. Four printable characters read as a size over 512 MB, so text that spells a box
     * name in its fifth to eighth bytes, as "The free trial has ended" does, isn't a container.
     */
    private static boolean boxSizeFits(byte[] head) {
        long size = boxSize(head);
        return size == 0 || size == 1 || (size >= 8 && size <= MAX_BYTES);
    }

    /** The size the first four bytes give an ISO media box, read as unsigned. */
    private static long boxSize(byte[] head) {
        return ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8)
            | (head[3] & 0xFFL);
    }

    private static boolean starts(byte[] head, int length, int... signature) {
        if (length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    private static String ascii(byte[] bytes, int from, int count) {
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) chars[i] = (char) (bytes[from + i] & 0xFF);
        return new String(chars);
    }

    /** Fill [head] as far as the body allows. Answers how many bytes came. */
    private static int readHead(InputStream in, byte[] head) throws IOException {
        int filled = 0;
        while (filled < head.length) {
            int read = in.read(head, filled, head.length - filled);
            if (read < 0) break;
            filled += read;
        }
        return filled;
    }

    /**
     * Copy the rest of the body across. Stops one byte past [allowed], so the caller can tell a
     * body that ran over from one that fit exactly. [already] bytes came before this, out of
     * [expected] (or -1), which is what [progress] is told.
     */
    private static long copy(InputStream in, OutputStream out, long allowed, long already, long expected,
            Progress progress) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long total = 0;

        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
            if (total > allowed) break;
            progress.transferred(already + total, expected);
            if (progress.cancelled()) throw new Cancelled();
        }

        out.flush();
        return total;
    }

    private static Result cancelled() {
        return Result.fail(Status.CANCELLED, "the person saving cancelled it");
    }

    /** The type that the server gave, lowercased and without its parameters. */
    private static String mimeOf(String contentType) {
        if (contentType == null) return null;

        String mime = contentType;
        int cut = mime.indexOf(';');
        if (cut >= 0) mime = mime.substring(0, cut);

        mime = mime.trim().toLowerCase(Locale.US);
        return mime.isEmpty() ? null : mime;
    }

    /**
     * Read and close the error body, so the connection can go back to the pool: at most
     * [DRAIN_BYTES] of it, for at most [DRAIN_NANOS], and nothing past a cancel. An error page that
     * never ended used to hold its save, and one of the three save slots, for as long as it went
     * on. A read that's waiting ends at the read timeout, or on a cancel, which closes the
     * connection ({@link Progress#reading}). A redirect has no error body, so none of it is read.
     */
    private static void drain(Prepared response, Progress progress) {
        InputStream stream = response.stream;
        if (stream == null) return;
        try {
            byte[] buffer = new byte[4096];
            long until = System.nanoTime() + DRAIN_NANOS;
            int left = DRAIN_BYTES;
            int read;
            while (left > 0 && System.nanoTime() - until < 0 && !progress.cancelled()
                    && (read = stream.read(buffer, 0, Math.min(buffer.length, left))) > 0) {
                left -= read;
            }
        } catch (Throwable ignored) {
            // The response's owner closes this stream, including when the drain failed.
        }
    }

    static void delete(File file) {
        if (file == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable ignored) {
            // The next save removes a file that stays, or the system clears the cache.
        }
    }
}
