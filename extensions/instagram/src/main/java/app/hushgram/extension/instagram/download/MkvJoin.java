/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Joins a DASH picture track and its sound track into one Matroska file (.mkv), for
 * {@code Save videos as MKV} (#547). Like {@link Mp4Join} it copies samples and decodes nothing:
 * it reads both tracks with {@link Mp4Join.Track}, from a fragmented MP4 or a plain one, and writes
 * each sample as a SimpleBlock.
 *
 * <p>The whole layout is worked out before a byte is written, since every sample's size and time is
 * known by then, so the file goes out in one pass with every size exact: the EBML header, then one
 * Segment holding a SeekHead, the Info, the Tracks, {@link #TAGS_ROOM} bytes of Void kept for the
 * post's details ({@link #tag}), the Clusters and, last, the Cues.
 *
 * <p>Times are milliseconds, the TimestampScale every Matroska writer uses. A block's time is when
 * its sample is shown: its decode time plus its composition offset, less where the track's edit list
 * starts showing, plus the empty edits before that. Matroska has no negative times, so when a track
 * starts before zero, an AAC track's priming most often, every track moves later by the same amount
 * and the two stay together. A cluster starts at each picture key frame, and the Cues list each of
 * those, so a player can seek.
 *
 * <p>H.264, H.265, VP9 and AV1 pictures and AAC sound are written. Anything else, a track with more
 * than one sample description, an edit list that cuts a track into pieces or changes its speed, or a
 * turned picture, which Matroska has no way to say, fails as an {@link IOException} before the
 * output holds anything, and the save makes an MP4 instead.
 */
final class MkvJoin {

    private MkvJoin() {}

    /** What the gallery is told a saved MKV is. */
    static final String MIME = "video/x-matroska";

    /** The bytes kept free before the first cluster for the post's details, written once the save is out. */
    static final int TAGS_ROOM = 4096;

    private static final int EBML = 0x1A45DFA3;
    private static final int EBML_VERSION = 0x4286;
    private static final int EBML_READ_VERSION = 0x42F7;
    private static final int EBML_MAX_ID_LENGTH = 0x42F2;
    private static final int EBML_MAX_SIZE_LENGTH = 0x42F3;
    private static final int DOC_TYPE = 0x4282;
    private static final int DOC_TYPE_VERSION = 0x4287;
    private static final int DOC_TYPE_READ_VERSION = 0x4285;
    private static final int SEGMENT = 0x18538067;
    private static final int SEEK_HEAD = 0x114D9B74;
    private static final int SEEK = 0x4DBB;
    private static final int SEEK_ID = 0x53AB;
    private static final int SEEK_POSITION = 0x53AC;
    private static final int INFO = 0x1549A966;
    private static final int TIMESTAMP_SCALE = 0x2AD7B1;
    private static final int MUXING_APP = 0x4D80;
    private static final int WRITING_APP = 0x5741;
    private static final int DURATION = 0x4489;
    private static final int TRACKS = 0x1654AE6B;
    private static final int TRACK_ENTRY = 0xAE;
    private static final int TRACK_NUMBER = 0xD7;
    private static final int TRACK_UID = 0x73C5;
    private static final int TRACK_TYPE = 0x83;
    private static final int FLAG_LACING = 0x9C;
    private static final int LANGUAGE = 0x22B59C;
    private static final int CODEC_ID = 0x86;
    private static final int CODEC_PRIVATE = 0x63A2;
    private static final int DEFAULT_DURATION = 0x23E383;
    private static final int VIDEO = 0xE0;
    private static final int PIXEL_WIDTH = 0xB0;
    private static final int PIXEL_HEIGHT = 0xBA;
    private static final int DISPLAY_WIDTH = 0x54B0;
    private static final int DISPLAY_HEIGHT = 0x54BA;
    private static final int COLOUR = 0x55B0;
    private static final int MATRIX_COEFFICIENTS = 0x55B1;
    private static final int BITS_PER_CHANNEL = 0x55B2;
    private static final int RANGE = 0x55B9;
    private static final int TRANSFER_CHARACTERISTICS = 0x55BA;
    private static final int PRIMARIES = 0x55BB;
    private static final int MAX_CLL = 0x55BC;
    private static final int MAX_FALL = 0x55BD;
    private static final int MASTERING_METADATA = 0x55D0;
    private static final int AUDIO = 0xE1;
    private static final int SAMPLING_FREQUENCY = 0xB5;
    private static final int CHANNELS = 0x9F;
    private static final int VOID = 0xEC;
    private static final int CLUSTER = 0x1F43B675;
    private static final int TIMESTAMP = 0xE7;
    private static final int SIMPLE_BLOCK = 0xA3;
    private static final int CUES = 0x1C53BB6B;
    private static final int CUE_POINT = 0xBB;
    private static final int CUE_TIME = 0xB3;
    private static final int CUE_TRACK_POSITIONS = 0xB7;
    private static final int CUE_TRACK = 0xF7;
    private static final int CUE_CLUSTER_POSITION = 0xF1;
    private static final int TAGS = 0x1254C367;
    private static final int TAG = 0x7373;
    private static final int TARGETS = 0x63C0;
    private static final int TARGET_TYPE_VALUE = 0x68CA;
    private static final int SIMPLE_TAG = 0x67C8;
    private static final int TAG_NAME = 0x45A3;
    private static final int TAG_STRING = 0x4487;

    /** A SeekHead entry's position is written in this many bytes, so the SeekHead's size never moves. */
    private static final int POSITION_BYTES = 8;

    /** The color code that says nothing: an element holding it is left out. */
    private static final int UNSPECIFIED = 2;

    /** Whether the next video save is an MKV. Never throws, and never waits on settings. */
    static boolean wanted() {
        try {
            return Utils.settingsReady() && Settings.SAVE_VIDEOS_AS_MKV.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Joins the picture track of [video] and the sound track of [audio] into the MKV [out], as
     * {@link Mp4Join#join} does into an MP4. [audio] is null for a picture with no sound. Answers
     * false when [progress] was cancelled, which is read before the copy starts and before each
     * cluster.
     */
    static boolean join(File video, File audio, File out, Downloader.Progress progress) throws IOException {
        try (RandomAccessFile videoIn = new RandomAccessFile(video, "r");
             RandomAccessFile audioIn = audio == null ? null : new RandomAccessFile(audio, "r")) {
            List<Entry> entries = new ArrayList<>();
            entries.add(Entry.of(Mp4Join.Track.read(videoIn, Mp4Join.VIDE, "video", "picture"), 1));
            if (audioIn != null) entries.add(Entry.of(Mp4Join.Track.read(audioIn, Mp4Join.SOUN, "audio", "sound"), 2));
            if (progress.cancelled()) return false;
            progress.joining();
            return write(entries, out, progress);
        }
    }

    // ---------------------------------------------------------------- what each track becomes

    /** One track as the MKV describes it, and when each of its samples is shown. */
    private static final class Entry {
        final Mp4Join.Track track;
        final int number;
        final boolean picture;
        String codec;
        byte[] codecPrivate;
        int pixelWidth;
        int pixelHeight;
        /** Set when the pixels aren't square: the size the picture is shown at. */
        int displayWidth;
        int displayHeight;
        /** Each Colour value, or -1 for none: matrix, bits, range, transfer, primaries, MaxCLL, MaxFALL. */
        final long[] colour = {-1, -1, -1, -1, -1, -1, -1};
        /** The mastering display as Matroska lists it, R, G and B x and y, white x and y, max and min, or null. */
        double[] mastering;
        double sampleRate;
        int channels;
        String language = "und";
        /** The frame length in nanoseconds when every sample but the last has the same one, else 0. */
        long defaultDuration;

        /** When each sample is shown, in milliseconds, and when the last one shown ends. */
        long[] shown;
        long end;
        /** Where each sample's bytes start in the track's file. */
        long[] at;

        private Entry(Mp4Join.Track track, int number) {
            this.track = track;
            this.number = number;
            this.picture = track.handler == Mp4Join.VIDE;
        }

        static Entry of(Mp4Join.Track track, int number) throws IOException {
            Entry entry = new Entry(track, number);
            try {
                entry.describe();
            } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
                throw new IOException("the " + track.kind + " track's sample description is cut short", e);
            }
            entry.time();
            entry.locate();
            return entry;
        }

        private void describe() throws IOException {
            String kind = track.kind;
            if (track.descriptionCount != 1) {
                throw new IOException("the " + kind + " track has " + track.descriptionCount + " sample descriptions");
            }
            for (int r = 0; r < track.runDescriptions.size; r++) {
                if (track.runDescriptions.get(r) != 1) throw new IOException("the " + kind + " track names a missing description");
            }
            Mp4Join.Box sample = Mp4Join.children(Mp4Join.slice(track.descriptions, 8, track.descriptions.limit())).get(0);
            ByteBuffer b = sample.data;
            String type = fourcc(sample.type);
            language = language(track.language);
            if (picture) {
                if (track.matrix != null && !identity(track.matrix)) {
                    throw new IOException("the picture is turned, which an MKV has no way to say");
                }
                pixelWidth = b.getShort(24) & 0xFFFF;
                pixelHeight = b.getShort(26) & 0xFFFF;
                List<Mp4Join.Box> boxes = Mp4Join.children(Mp4Join.slice(b, 78, b.limit()));
                switch (type) {
                    case "avc1":
                    case "avc3":
                        codec = "V_MPEG4/ISO/AVC";
                        codecPrivate = payload(boxes, "avcC");
                        break;
                    case "hvc1":
                    case "hev1":
                        codec = "V_MPEGH/ISO/HEVC";
                        codecPrivate = payload(boxes, "hvcC");
                        break;
                    case "vp09":
                        codec = "V_VP9";
                        vp9(Mp4Join.find(boxes, Mp4Join.type("vpcC")));
                        break;
                    case "av01":
                        codec = "V_AV1";
                        codecPrivate = payload(boxes, "av1C");
                        break;
                    default:
                        throw new IOException("an MKV save doesn't write " + type + " pictures");
                }
                if (codecPrivate == null && !"V_VP9".equals(codec)) {
                    throw new IOException("the " + type + " picture has no decoder setup");
                }
                colr(Mp4Join.find(boxes, Mp4Join.type("colr")));
                light(Mp4Join.find(boxes, Mp4Join.type("clli")), Mp4Join.find(boxes, Mp4Join.type("mdcv")));
                pasp(Mp4Join.find(boxes, Mp4Join.type("pasp")));
            } else {
                if (!"mp4a".equals(type)) throw new IOException("an MKV save doesn't write " + type + " sound");
                int version = b.getShort(8) & 0xFFFF;
                if (version > 1) throw new IOException("the sound's description is of version " + version);
                channels = b.getShort(16) & 0xFFFF;
                long rate = (b.getInt(24) & 0xFFFFFFFFL) >>> 16;
                sampleRate = rate > 0 ? rate : track.timescale;
                List<Mp4Join.Box> boxes = Mp4Join.children(Mp4Join.slice(b, version == 1 ? 44 : 28, b.limit()));
                Mp4Join.Box esds = Mp4Join.find(boxes, Mp4Join.type("esds"));
                byte[] config = esds == null ? null : audioConfig(esds.data);
                if (config == null) throw new IOException("the sound isn't AAC with its decoder setup");
                codec = "A_AAC";
                codecPrivate = config;
            }
        }

        /** VP9's setup: profile, level, bit depth and chroma, and its colors, from vpcC version 1. */
        private void vp9(Mp4Join.Box vpcC) {
            if (vpcC == null || vpcC.data.limit() < 12 || vpcC.data.get(0) != 1) return;
            ByteBuffer v = vpcC.data;
            int profile = v.get(4) & 0xFF;
            int level = v.get(5) & 0xFF;
            int packed = v.get(6) & 0xFF;
            int bits = packed >> 4;
            int chroma = (packed >> 1) & 7;
            boolean full = (packed & 1) != 0;
            codecPrivate = new byte[] {1, 1, (byte) profile, 2, 1, (byte) level, 3, 1, (byte) bits, 4, 1, (byte) chroma};
            colour[0] = v.get(9) & 0xFF;
            colour[1] = bits;
            colour[2] = full ? 2 : 1;
            colour[3] = v.get(8) & 0xFF;
            colour[4] = v.get(7) & 0xFF;
        }

        /** The colors an nclx colr box names, over what vpcC said. */
        private void colr(Mp4Join.Box colr) {
            if (colr == null || colr.data.limit() < 11 || colr.data.getInt(0) != Mp4Join.type("nclx")) return;
            ByteBuffer c = colr.data;
            colour[4] = c.getShort(4) & 0xFFFF;
            colour[3] = c.getShort(6) & 0xFFFF;
            colour[0] = c.getShort(8) & 0xFFFF;
            colour[2] = (c.get(10) & 0x80) != 0 ? 2 : 1;
        }

        /** An HDR picture's light levels and mastering display. mdcv lists its primaries G, B, R. */
        private void light(Mp4Join.Box clli, Mp4Join.Box mdcv) {
            if (clli != null && clli.data.limit() >= 4) {
                colour[5] = clli.data.getShort(0) & 0xFFFF;
                colour[6] = clli.data.getShort(2) & 0xFFFF;
            }
            if (mdcv == null || mdcv.data.limit() < 24) return;
            ByteBuffer m = mdcv.data;
            double[] g = {chroma(m, 0), chroma(m, 2)};
            double[] bl = {chroma(m, 4), chroma(m, 6)};
            double[] r = {chroma(m, 8), chroma(m, 10)};
            mastering = new double[] {r[0], r[1], g[0], g[1], bl[0], bl[1], chroma(m, 12), chroma(m, 14),
                (m.getInt(16) & 0xFFFFFFFFL) / 10000.0, (m.getInt(20) & 0xFFFFFFFFL) / 10000.0};
        }

        private static double chroma(ByteBuffer b, int at) {
            return (b.getShort(at) & 0xFFFF) / 50000.0;
        }

        /** Pixels that aren't square: the picture is shown wider or narrower than it's stored. */
        private void pasp(Mp4Join.Box pasp) {
            if (pasp == null || pasp.data.limit() < 8) return;
            long h = pasp.data.getInt(0) & 0xFFFFFFFFL;
            long v = pasp.data.getInt(4) & 0xFFFFFFFFL;
            if (h == 0 || v == 0 || h == v || pixelWidth == 0) return;
            displayWidth = (int) Math.max(1, Math.round(pixelWidth * (double) h / v));
            displayHeight = pixelHeight;
        }

        /**
         * When each sample is shown, from its decode time, its composition offset and the edit list.
         * The empty edits before the media edit delay the track, and the media edit's time is where
         * showing starts. With no edit list, showing starts at media time zero.
         */
        private void time() throws IOException {
            String kind = track.kind;
            long lead = 0;
            long start = 0;
            boolean media = false;
            if (track.edits != null) {
                for (long[] edit : track.edits) {
                    if (edit[1] == -1) {
                        if (!media) lead += ms(edit[0], track.movieTimescale);
                        continue;
                    }
                    if (media) throw new IOException("the " + kind + " track's edit list cuts it in pieces");
                    if (edit[2] != 0x00010000) throw new IOException("the " + kind + " track's edit list changes its speed");
                    media = true;
                    start = edit[1];
                }
            }
            int count = track.count();
            shown = new long[count];
            long decode = track.firstDecodeTime;
            long first = -1;
            boolean uniform = true;
            for (int i = 0; i < count; i++) {
                long length = track.durations.get(i) & 0xFFFFFFFFL;
                long composed = decode + track.offsets.get(i) - start;
                shown[i] = lead + ms(composed, track.timescale);
                end = Math.max(end, lead + ms(composed + length, track.timescale));
                decode += length;
                if (i < count - 1) {
                    if (first < 0) first = length;
                    else if (length != first) uniform = false;
                }
            }
            if (uniform && first > 0) defaultDuration = (first * 1_000_000_000L + track.timescale / 2) / track.timescale;
        }

        /** Where each sample's bytes lie: each run's lie together, from where the run starts. */
        private void locate() {
            int count = track.count();
            at = new long[count];
            int i = 0;
            for (int r = 0; r < track.runEnds.size; r++) {
                long position = track.runStarts[r];
                for (int end = track.runEnds.get(r); i < end; i++) {
                    at[i] = position;
                    position += track.sizes.get(i) & 0xFFFFFFFFL;
                }
            }
        }

        boolean key(int sample) {
            return !picture || !track.nonSync.get(sample);
        }

        /** The sample's size as the track's table holds it, unsigned. */
        int size(int sample) {
            return track.sizes.get(sample);
        }

        byte[] entry() {
            List<byte[]> parts = new ArrayList<>();
            parts.add(uint(TRACK_NUMBER, number));
            parts.add(uint(TRACK_UID, number));
            parts.add(uint(TRACK_TYPE, picture ? 1 : 2));
            parts.add(uint(FLAG_LACING, 0));
            parts.add(string(LANGUAGE, language));
            parts.add(string(CODEC_ID, codec));
            if (codecPrivate != null) parts.add(element(CODEC_PRIVATE, codecPrivate));
            if (defaultDuration > 0) parts.add(uint(DEFAULT_DURATION, defaultDuration));
            if (picture) {
                List<byte[]> video = new ArrayList<>();
                video.add(uint(PIXEL_WIDTH, pixelWidth));
                video.add(uint(PIXEL_HEIGHT, pixelHeight));
                if (displayWidth > 0) {
                    video.add(uint(DISPLAY_WIDTH, displayWidth));
                    video.add(uint(DISPLAY_HEIGHT, displayHeight));
                }
                byte[] colours = colour();
                if (colours != null) video.add(colours);
                parts.add(element(VIDEO, join(video)));
            } else {
                parts.add(element(AUDIO, join(Arrays.asList(
                    dbl(SAMPLING_FREQUENCY, sampleRate), uint(CHANNELS, channels)))));
            }
            return element(TRACK_ENTRY, join(parts));
        }

        private byte[] colour() {
            int[] ids = {MATRIX_COEFFICIENTS, BITS_PER_CHANNEL, RANGE, TRANSFER_CHARACTERISTICS, PRIMARIES, MAX_CLL, MAX_FALL};
            List<byte[]> parts = new ArrayList<>();
            for (int k = 0; k < ids.length; k++) {
                long value = colour[k];
                if (value < 0) continue;
                // A matrix, transfer or primaries of 2 says nothing, and range 0 or depth 0 the same.
                boolean coded = k == 0 || k == 3 || k == 4;
                if (coded && value == UNSPECIFIED) continue;
                if ((k == 1 || k == 2) && value == 0) continue;
                parts.add(uint(ids[k], value));
            }
            if (mastering != null) {
                List<byte[]> display = new ArrayList<>();
                for (int k = 0; k < mastering.length; k++) display.add(dbl(MASTERING_METADATA + 1 + k, mastering[k]));
                parts.add(element(MASTERING_METADATA, join(display)));
            }
            return parts.isEmpty() ? null : element(COLOUR, join(parts));
        }
    }

    /** [value] in [timescale] units as milliseconds, rounded half up, below zero too. */
    static long ms(long value, long timescale) {
        return Math.floorDiv(value * 2000 + timescale, 2 * timescale);
    }

    private static String fourcc(int type) {
        return new String(new char[] {(char) (type >>> 24 & 0xFF), (char) (type >>> 16 & 0xFF),
            (char) (type >>> 8 & 0xFF), (char) (type & 0xFF)});
    }

    private static boolean identity(byte[] matrix) {
        ByteBuffer m = ByteBuffer.wrap(matrix);
        for (int value : Mp4Join.IDENTITY) {
            if (m.getInt() != value) return false;
        }
        return true;
    }

    /** An mdhd language, three letters packed five bits each, or "und". */
    private static String language(int packed) {
        if (packed == 0 || packed == 0x7FFF) return "und";
        char[] letters = new char[3];
        for (int k = 0; k < 3; k++) {
            int letter = (packed >> (10 - 5 * k)) & 0x1F;
            if (letter == 0 || letter > 26) return "und";
            letters[k] = (char) (letter + 0x60);
        }
        return new String(letters);
    }

    private static byte[] payload(List<Mp4Join.Box> boxes, String type) {
        Mp4Join.Box box = Mp4Join.find(boxes, Mp4Join.type(type));
        if (box == null) return null;
        ByteBuffer copy = box.data.duplicate();
        copy.position(0);
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    /**
     * The AudioSpecificConfig in an esds box's payload [esds], or null when its stream isn't
     * MPEG-4 or MPEG-2 AAC. The descriptors nest: ES (3) holds DecoderConfig (4), which holds
     * DecoderSpecificInfo (5).
     */
    static byte[] audioConfig(ByteBuffer esds) {
        ByteBuffer b = esds.duplicate();
        b.position(4);
        return descriptor(b, b.limit());
    }

    private static byte[] descriptor(ByteBuffer b, int limit) {
        while (b.position() + 2 <= limit) {
            int tag = b.get() & 0xFF;
            int length = 0;
            for (int k = 0; k < 4; k++) {
                int part = b.get() & 0xFF;
                length = (length << 7) | (part & 0x7F);
                if ((part & 0x80) == 0) break;
            }
            int start = b.position();
            int end = start + length;
            if (end > limit) return null;
            if (tag == 3) {
                b.position(start + 2);
                int flags = b.get() & 0xFF;
                if ((flags & 0x80) != 0) b.position(b.position() + 2);
                if ((flags & 0x40) != 0) b.position(b.position() + 1 + (b.get(b.position()) & 0xFF));
                if ((flags & 0x20) != 0) b.position(b.position() + 2);
                return descriptor(b, end);
            }
            if (tag == 4) {
                int object = b.get(start) & 0xFF;
                if (object != 0x40 && object != 0x66 && object != 0x67 && object != 0x68) return null;
                b.position(start + 13);
                return descriptor(b, end);
            }
            if (tag == 5) {
                byte[] config = new byte[length];
                b.get(config);
                return config;
            }
            b.position(end);
        }
        return null;
    }

    // ---------------------------------------------------------------- the layout

    /** Every block in the order it's written, and where each cluster starts. */
    private static final class Plan {
        final int[] blockEntry;
        final int[] blockSample;
        /** Each cluster's first block; one more entry holds the block count. */
        final int[] clusterFirst;
        final long[] clusterTime;
        final boolean[] clusterKey;
        /** The length of each cluster's content: its Timestamp and its blocks. */
        final long[] clusterContent;
        int clusters;

        Plan(int blocks) {
            blockEntry = new int[blocks];
            blockSample = new int[blocks];
            clusterFirst = new int[blocks + 1];
            clusterTime = new long[blocks];
            clusterKey = new boolean[blocks];
            clusterContent = new long[blocks];
        }

        long clusterSize(int c) {
            return idLength(CLUSTER) + vintLength(clusterContent[c]) + clusterContent[c];
        }
    }

    /**
     * The blocks by decode time, alternating between the tracks the way {@link Mp4Join} does, and
     * a new cluster at each picture key frame, or when a block's time is too far from its
     * cluster's for the two bytes a block holds it in.
     */
    private static Plan plan(List<Entry> entries) {
        int n = entries.size();
        int total = 0;
        for (Entry entry : entries) total += entry.track.count();
        Plan plan = new Plan(total);
        int[] next = new int[n];
        long[] decoded = new long[n];
        for (int b = 0; b < total; b++) {
            int pick = -1;
            double earliest = 0;
            for (int t = 0; t < n; t++) {
                Mp4Join.Track track = entries.get(t).track;
                if (next[t] >= track.count()) continue;
                double at = (track.firstDecodeTime + decoded[t]) / (double) track.timescale;
                if (pick < 0 || at < earliest) {
                    pick = t;
                    earliest = at;
                }
            }
            Entry entry = entries.get(pick);
            int sample = next[pick]++;
            decoded[pick] += entry.track.durations.get(sample) & 0xFFFFFFFFL;
            long time = entry.shown[sample];
            boolean key = entry.picture && entry.key(sample);
            int c = plan.clusters;
            long relative = c == 0 ? 0 : time - plan.clusterTime[c - 1];
            if (c == 0 || key || relative > Short.MAX_VALUE || relative < Short.MIN_VALUE) {
                plan.clusterFirst[c] = b;
                plan.clusterTime[c] = time;
                plan.clusterKey[c] = key;
                plan.clusters++;
            }
            plan.blockEntry[b] = pick;
            plan.blockSample[b] = sample;
        }
        plan.clusterFirst[plan.clusters] = total;
        for (int c = 0; c < plan.clusters; c++) {
            long content = uint(TIMESTAMP, plan.clusterTime[c]).length;
            for (int b = plan.clusterFirst[c]; b < plan.clusterFirst[c + 1]; b++) {
                content += blockElement(entries.get(plan.blockEntry[b]).size(plan.blockSample[b]));
            }
            plan.clusterContent[c] = content;
        }
        return plan;
    }

    /** A SimpleBlock's whole length for a sample of [size] bytes on track 1 or 2. */
    private static long blockElement(int size) {
        long block = 1 + 2 + 1 + (size & 0xFFFFFFFFL);
        return 1 + vintLength(block) + block;
    }

    private static boolean write(List<Entry> entries, File out, Downloader.Progress progress) throws IOException {
        // Matroska has no time below zero: every track moves later by as much as the earliest is early.
        long lowest = 0;
        for (Entry entry : entries) {
            for (long time : entry.shown) lowest = Math.min(lowest, time);
        }
        long duration = 0;
        for (Entry entry : entries) {
            for (int i = 0; i < entry.shown.length; i++) entry.shown[i] -= lowest;
            entry.end -= lowest;
            duration = Math.max(duration, entry.end);
        }
        Plan plan = plan(entries);

        byte[] info = element(INFO, join(Arrays.asList(
            uint(TIMESTAMP_SCALE, 1_000_000),
            string(MUXING_APP, "HushGram"),
            string(WRITING_APP, "HushGram"),
            dbl(DURATION, duration))));
        List<byte[]> trackEntries = new ArrayList<>();
        for (Entry entry : entries) trackEntries.add(entry.entry());
        byte[] tracks = element(TRACKS, join(trackEntries));
        byte[] room = voidOf(TAGS_ROOM);

        int seekHeadLength = seekHead(0, 0, 0).length;
        long infoAt = seekHeadLength;
        long tracksAt = infoAt + info.length;
        long clustersAt = tracksAt + tracks.length + room.length;
        long[] clusterAt = new long[plan.clusters];
        long cuesAt = clustersAt;
        for (int c = 0; c < plan.clusters; c++) {
            clusterAt[c] = cuesAt;
            cuesAt += plan.clusterSize(c);
        }
        List<byte[]> points = new ArrayList<>();
        for (int c = 0; c < plan.clusters; c++) {
            if (!plan.clusterKey[c]) continue;
            points.add(element(CUE_POINT, join(Arrays.asList(
                uint(CUE_TIME, plan.clusterTime[c]),
                element(CUE_TRACK_POSITIONS, join(Arrays.asList(
                    uint(CUE_TRACK, 1), uint(CUE_CLUSTER_POSITION, clusterAt[c]))))))));
        }
        // A file with no key frame listed has no Cues, and the SeekHead points nowhere for them.
        byte[] cues = points.isEmpty() ? new byte[0] : element(CUES, join(points));
        byte[] seekHead = seekHead(infoAt, tracksAt, points.isEmpty() ? -1 : cuesAt);
        long segment = cuesAt + cues.length;

        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            stream.write(header());
            stream.write(id(SEGMENT));
            stream.write(vint(segment, 8));
            stream.write(seekHead);
            stream.write(info);
            stream.write(tracks);
            stream.write(room);
            byte[] buffer = new byte[64 * 1024];
            for (int c = 0; c < plan.clusters; c++) {
                if (progress.cancelled()) return false;
                long content = plan.clusterContent[c];
                stream.write(id(CLUSTER));
                stream.write(vint(content, vintLength(content)));
                stream.write(uint(TIMESTAMP, plan.clusterTime[c]));
                for (int b = plan.clusterFirst[c]; b < plan.clusterFirst[c + 1]; b++) {
                    Entry entry = entries.get(plan.blockEntry[b]);
                    int sample = plan.blockSample[b];
                    long left = entry.size(sample) & 0xFFFFFFFFL;
                    long block = 1 + 2 + 1 + left;
                    stream.write(SIMPLE_BLOCK);
                    stream.write(vint(block, vintLength(block)));
                    stream.write(0x80 | entry.number);
                    int relative = (int) (entry.shown[sample] - plan.clusterTime[c]);
                    stream.write(relative >> 8);
                    stream.write(relative);
                    stream.write(entry.key(sample) ? 0x80 : 0);
                    // Copied a buffer at a time, so a track that claims a huge sample costs no memory for it.
                    entry.track.file.seek(entry.at[sample]);
                    while (left > 0) {
                        int part = (int) Math.min(left, buffer.length);
                        try {
                            entry.track.file.readFully(buffer, 0, part);
                        } catch (EOFException e) {
                            throw new IOException("the " + entry.track.kind + " file ended before its samples did", e);
                        }
                        stream.write(buffer, 0, part);
                        left -= part;
                    }
                }
            }
            stream.write(cues);
        }
        return true;
    }

    private static byte[] header() {
        return element(EBML, join(Arrays.asList(
            uint(EBML_VERSION, 1),
            uint(EBML_READ_VERSION, 1),
            uint(EBML_MAX_ID_LENGTH, 4),
            uint(EBML_MAX_SIZE_LENGTH, 8),
            string(DOC_TYPE, "matroska"),
            uint(DOC_TYPE_VERSION, 4),
            uint(DOC_TYPE_READ_VERSION, 2))));
    }

    /** The SeekHead for Info, Tracks and, at [cuesAt] unless that's below zero, Cues. Its length never changes with the values. */
    private static byte[] seekHead(long infoAt, long tracksAt, long cuesAt) {
        List<byte[]> seeks = new ArrayList<>();
        seeks.add(seek(INFO, infoAt));
        seeks.add(seek(TRACKS, tracksAt));
        if (cuesAt >= 0) seeks.add(seek(CUES, cuesAt));
        else seeks.add(voidOf(seek(CUES, 0).length));
        return element(SEEK_HEAD, join(seeks));
    }

    private static byte[] seek(int target, long position) {
        return element(SEEK, join(Arrays.asList(
            element(SEEK_ID, id(target)),
            element(SEEK_POSITION, fixed(position, POSITION_BYTES)))));
    }

    // ---------------------------------------------------------------- the post's details

    /**
     * Writes [items] ({@link VideoMetadata#items}) into the Void the join kept before the first
     * cluster of the MKV in [store], as one Tags element and a Void for what's left. False, with
     * the file untouched, when there's nothing to write, the file isn't laid out that way, or the
     * details don't fit.
     */
    static boolean tag(VideoMetadata.Store store, Map<Integer, String> items) throws IOException {
        if (items == null || items.isEmpty()) return false;
        byte[] tags = tags(items);
        long length = store.size();
        Element ebml = Element.at(store, 0, length);
        if (ebml == null || ebml.id != EBML) return false;
        Element segment = Element.at(store, ebml.end(), length);
        if (segment == null || segment.id != SEGMENT) return false;
        long at = segment.content;
        long end = Math.min(length, segment.end());
        while (at < end) {
            Element child = Element.at(store, at, end);
            if (child == null || child.id == CLUSTER || child.id == TAGS) return false;
            if (child.id == VOID) {
                long room = child.end() - child.start;
                long left = room - tags.length;
                if (left != 0 && left < 9) return false;
                byte[] bytes = new byte[(int) room];
                System.arraycopy(tags, 0, bytes, 0, tags.length);
                if (left > 0) System.arraycopy(voidOf((int) left), 0, bytes, tags.length, (int) left);
                store.write(child.start, bytes);
                store.sync();
                return true;
            }
            at = child.end();
        }
        return false;
    }

    /** The Tags element for [items]: the poster as ARTIST, the post time as DATE_RELEASED, the link as DESCRIPTION. */
    static byte[] tags(Map<Integer, String> items) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(element(TARGETS, uint(TARGET_TYPE_VALUE, 50)));
        for (Map.Entry<Integer, String> item : items.entrySet()) {
            String name;
            String value = item.getValue();
            int key = item.getKey();
            if (key == VideoMetadata.ARTIST) {
                name = "ARTIST";
            } else if (key == VideoMetadata.DAY) {
                name = "DATE_RELEASED";
                // Matroska writes a date as YYYY-MM-DD HH:MM:SS, in UTC.
                value = value.replace('T', ' ').replace("Z", "");
            } else if (key == VideoMetadata.DESCRIPTION) {
                name = "DESCRIPTION";
            } else {
                continue;
            }
            parts.add(element(SIMPLE_TAG, join(Arrays.asList(string(TAG_NAME, name), string(TAG_STRING, value)))));
        }
        return element(TAGS, element(TAG, join(parts)));
    }

    /** One element's place in a file: its id, where it starts, where its content starts and its size. */
    private static final class Element {
        final int id;
        final long start;
        final long content;
        final long size;

        Element(int id, long start, long content, long size) {
            this.id = id;
            this.start = start;
            this.content = content;
            this.size = size;
        }

        long end() {
            return content + size;
        }

        /** The element at [at], or null when its head doesn't fit before [limit] or isn't one. */
        static Element at(VideoMetadata.Store store, long at, long limit) throws IOException {
            int ahead = (int) Math.min(12, limit - at);
            if (ahead < 2) return null;
            byte[] head = store.read(at, ahead);
            int first = head[0] & 0xFF;
            int idLength = Integer.numberOfLeadingZeros(first) - 23;
            if (first == 0 || idLength > 4 || idLength >= ahead) return null;
            int id = 0;
            for (int k = 0; k < idLength; k++) id = (id << 8) | (head[k] & 0xFF);
            int lead = head[idLength] & 0xFF;
            int sizeLength = Integer.numberOfLeadingZeros(lead) - 23;
            if (lead == 0 || idLength + sizeLength > ahead) return null;
            long size = lead & (0xFF >> sizeLength);
            for (int k = 1; k < sizeLength; k++) size = (size << 8) | (head[idLength + k] & 0xFF);
            long content = at + idLength + sizeLength;
            if (size > limit - content) return null;
            return new Element(id, at, content, size);
        }
    }

    // ---------------------------------------------------------------- EBML

    private static int idLength(int id) {
        return (id & 0xFF000000) != 0 ? 4 : (id & 0xFF0000) != 0 ? 3 : (id & 0xFF00) != 0 ? 2 : 1;
    }

    private static byte[] id(int id) {
        return fixed(id & 0xFFFFFFFFL, idLength(id));
    }

    /** The fewest bytes a size of [value] takes; all ones is kept for "unknown". */
    static int vintLength(long value) {
        for (int k = 1; k < 8; k++) {
            if (value < (1L << (7 * k)) - 1) return k;
        }
        return 8;
    }

    /** [value] as a size field of [length] bytes, its marker bit set. */
    static byte[] vint(long value, int length) {
        return fixed(value | (1L << (7 * length)), length);
    }

    /** [value]'s low [length] bytes, big-endian. */
    private static byte[] fixed(long value, int length) {
        byte[] bytes = new byte[length];
        for (int k = length - 1; k >= 0; k--) {
            bytes[k] = (byte) value;
            value >>>= 8;
        }
        return bytes;
    }

    static byte[] element(int id, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 12);
        out.write(id(id), 0, idLength(id));
        byte[] size = vint(content.length, vintLength(content.length));
        out.write(size, 0, size.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] uint(int id, long value) {
        int length = 1;
        while (length < 8 && (value >>> (8 * length)) != 0) length++;
        return element(id, fixed(value, length));
    }

    private static byte[] string(int id, String value) {
        return element(id, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] dbl(int id, double value) {
        return element(id, fixed(Double.doubleToLongBits(value), 8));
    }

    /** A Void of [length] bytes in all, its size field eight bytes when there's room for that. */
    static byte[] voidOf(int length) {
        int sizeLength = length >= 9 ? 8 : 1;
        byte[] bytes = new byte[length];
        bytes[0] = (byte) VOID;
        byte[] size = vint(length - 1 - sizeLength, sizeLength);
        System.arraycopy(size, 0, bytes, 1, sizeLength);
        return bytes;
    }

    private static byte[] join(List<byte[]> parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] bytes = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, bytes, at, part.length);
            at += part.length;
        }
        return bytes;
    }
}
