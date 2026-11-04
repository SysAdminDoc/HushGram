/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A DASH track the way Meta serves one: a fragmented MP4 that holds a single track, a moov with
 * empty sample tables and an mvex, then moof and mdat pairs. {@link #plain} writes tracks as the
 * other kind instead, every sample listed in the moov. Every sample's bytes are a pattern of their
 * own ({@link #content}), so a join that copies the wrong bytes, or the right bytes to the wrong
 * sample, shows.
 */
final class FragmentedMp4ForTests {

    /** A sample no other depends on: a key frame, or any AAC frame. */
    static final int SYNC = 0x02000000;
    /** A picture that depends on others, flagged as no sync sample. */
    static final int NON_SYNC = 0x01010000;

    static final int DURATION = 0x100;
    static final int SIZE = 0x200;
    static final int FLAGS = 0x400;
    static final int OFFSET = 0x800;

    /** One sample: its size, duration, flags and composition offset. */
    static final class Sample {
        final int size;
        final long duration;
        final int flags;
        final long offset;

        Sample(int size, long duration, int flags, long offset) {
            this.size = size;
            this.duration = duration;
            this.flags = flags;
            this.offset = offset;
        }
    }

    /** One moof and its mdat. */
    static final class Fragment {
        /** The tfdt, or null to leave it out. */
        Long decodeTime;
        final List<Sample> samples = new ArrayList<>();
        int trunVersion;
        /** Which of {@link #DURATION}, {@link #SIZE}, {@link #FLAGS} and {@link #OFFSET} each run lists per sample. */
        int fields = DURATION | SIZE | FLAGS;
        /** A value a run doesn't list comes from tfhd when this is set, else from trex. */
        boolean tfhdDefaults;
        /** How many trun boxes the samples split into. */
        int runs = 1;
        /** tfhd names an absolute base, and the runs name no data offset: each follows the last. */
        boolean explicitBase;

        Fragment(Long decodeTime) {
            this.decodeTime = decodeTime;
        }

        Fragment add(int size, long duration, int flags, long offset) {
            samples.add(new Sample(size, duration, flags, offset));
            return this;
        }
    }

    final String handler;
    final String entry;
    final long timescale;
    final int seed;
    long movieTimescale = 1000;
    int width;
    int height;
    /** Each edit as {segment duration in the movie timescale, media time, rate}, or null for no edts. */
    long[][] edits;
    int trackId = 1;
    long trexDuration;
    int trexSize;
    int trexFlags;
    /** How many samples a chunk of this track holds in a {@link #plain} file. */
    int plainChunk = 10;
    /** The last mdat's size field says 0: to the end of the file. */
    boolean lastBoxToTheEnd;
    final List<Fragment> fragments = new ArrayList<>();

    FragmentedMp4ForTests(String handler, String entry, long timescale, int seed) {
        this.handler = handler;
        this.entry = entry;
        this.timescale = timescale;
        this.seed = seed;
    }

    static FragmentedMp4ForTests picture(String entry, int width, int height, long timescale) {
        FragmentedMp4ForTests track = new FragmentedMp4ForTests("vide", entry, timescale, 17);
        track.width = width;
        track.height = height;
        return track;
    }

    static FragmentedMp4ForTests sound(long rate) {
        return new FragmentedMp4ForTests("soun", "mp4a", rate, 91);
    }

    Fragment fragment(Long decodeTime) {
        Fragment fragment = new Fragment(decodeTime);
        fragments.add(fragment);
        return fragment;
    }

    /** Every sample in order. */
    List<Sample> samples() {
        List<Sample> all = new ArrayList<>();
        for (Fragment fragment : fragments) all.addAll(fragment.samples);
        return all;
    }

    /** The bytes of sample [index]: a pattern no other sample of either track shares. */
    byte[] content(int index) {
        byte[] bytes = new byte[samples().get(index).size];
        for (int k = 0; k < bytes.length; k++) bytes[k] = (byte) (seed * 131 + index * 7 + k * 3 + (k >> 8));
        return bytes;
    }

    /** Each sample's decode time, from tfdt where there is one and the durations before it elsewhere. */
    long[] decodeTimes() {
        List<Long> times = new ArrayList<>();
        long running = 0;
        boolean first = true;
        for (Fragment fragment : fragments) {
            if (fragment.decodeTime != null) {
                running = fragment.decodeTime;
            } else if (first) {
                running = 0;
            }
            first = false;
            for (Sample sample : fragment.samples) {
                times.add(running);
                running += sample.duration;
            }
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    /**
     * When sample [index] is shown, in seconds from the start of the movie, applying the edit list the
     * way a player does: the empty edits before the first media edit delay it, and that edit's media
     * time is where showing starts.
     */
    double presentation(int index) {
        long composed = decodeTimes()[index] + samples().get(index).offset;
        return PlainMp4ForTests.present(composed, timescale, edits, movieTimescale);
    }

    /** The stsd box's payload, as the join must copy it. */
    byte[] sampleDescriptions() {
        Box stsd = new Box();
        stsd.u32(0).u32(1);
        stsd.box(entry, sampleEntry());
        return stsd.bytes();
    }

    private byte[] sampleEntry() {
        Box entryBox = new Box();
        entryBox.zeros(6).u16(1);
        if (handler.equals("vide")) {
            entryBox.u16(0).u16(0).zeros(12).u16(width).u16(height).u32(0x00480000).u32(0x00480000).u32(0).u16(1)
                    .zeros(32).u16(0x18).u16(0xFFFF);
            if (entry.equals("vp09") || entry.equals("encv")) {
                // vpcC version 1: profile 0, level 4.0, 8 bits 4:2:0, BT.709 throughout.
                entryBox.box("vpcC", new Box().u32(0x01000000).u8(0).u8(40).u8(0x82).u8(1).u8(1).u8(1).u16(0).bytes());
            } else if (entry.equals("av01")) {
                entryBox.box("av1C", new Box().u8(0x81).u8(0x08).u8(0x0C).u8(0).bytes());
            } else {
                entryBox.box("avcC", new Box().u8(1).u8(0x64).u8(0).u8(0x1F).u8(0xFF).u8(0xE0).u8(0).bytes());
            }
            entryBox.box("pasp", new Box().u32(1).u32(1).bytes());
        } else {
            entryBox.zeros(8).u16(2).u16(16).u16(0).u16(0).u32(timescale << 16);
            // An ES descriptor for AAC-LC, 44.1 kHz stereo.
            byte[] esds = {0, 0, 0, 0, 3, 25, 0, 1, 0, 4, 17, 0x40, 0x15, 0, 0, 0, 0, 1, (byte) 0xF4, 0, 0, 1,
                (byte) 0xF4, 0, 5, 2, 0x12, 0x10, 6, 1, 2};
            entryBox.box("esds", esds);
        }
        return entryBox.bytes();
    }

    /** The whole file. */
    byte[] build() {
        Box file = new Box();
        file.box("ftyp", new Box().ascii("dash").u32(0).ascii("iso6").ascii("mp41").ascii("dash").bytes());
        file.box("moov", moov());
        // What else Meta's files carry at the top: a segment index, and a box nothing reads.
        file.box("sidx", new Box().u32(0).u32(1).u32((int) timescale).u32(0).u32(0).u32(0).bytes());
        file.box("free", new byte[5]);

        int index = 0;
        for (int f = 0; f < fragments.size(); f++) {
            Fragment fragment = fragments.get(f);
            long moofStart = file.size();
            int moofSize = moof(fragment, moofStart, 0).length + 8;
            long dataStart = moofStart + moofSize + 8;
            file.box("moof", moof(fragment, moofStart, dataStart));
            Box mdat = new Box();
            for (int i = 0; i < fragment.samples.size(); i++) mdat.put(content(index++));
            boolean last = f == fragments.size() - 1;
            if (last && lastBoxToTheEnd) {
                file.u32(0).ascii("mdat").put(mdat.bytes());
            } else {
                file.box("mdat", mdat.bytes());
            }
            if (!last) file.box("styp", new Box().ascii("msdh").u32(0).bytes());
        }
        return file.bytes();
    }

    private byte[] moov() {
        Box moov = new Box();
        moov.box("mvhd", mvhd(movieTimescale, trackId + 1));
        Box empty = new Box();
        empty.box("stts", new Box().u32(0).u32(0).bytes());
        empty.box("stsc", new Box().u32(0).u32(0).bytes());
        empty.box("stsz", new Box().u32(0).u32(0).u32(0).bytes());
        empty.box("stco", new Box().u32(0).u32(0).bytes());
        moov.box("trak", trak(trackId, empty.bytes()));
        moov.box("mvex", new Box().box("trex", new Box().u32(0).u32(trackId).u32(1).u32(trexDuration).u32(trexSize)
                .u32(trexFlags).bytes()).bytes());
        moov.box("udta", new byte[8]);
        return moov.bytes();
    }

    private static byte[] mvhd(long movieTimescale, int nextTrackId) {
        return new Box().u32(0).u32(0).u32(0).u32(movieTimescale).u32(0).u32(0x00010000).u16(0x0100)
                .zeros(10).put(matrix()).zeros(24).u32(nextTrackId).bytes();
    }

    /**
     * [tracks] as one plain MP4, the progressive file Instagram plays beside its DASH tracks: every
     * sample listed in the moov's tables and none in fragments, the tracks' chunks taking turns in
     * one mdat, {@link #plainChunk} samples each. Each track keeps its samples, edit list and sample
     * description, its decode times running on from zero; track ids go from 1 in the order given.
     * Chunk offsets are co64 when [wide], and the moov comes after the mdat when [moovLast], as in a
     * file written without faststart.
     */
    static byte[] plain(boolean wide, boolean moovLast, FragmentedMp4ForTests... tracks) {
        Box mdat = new Box();
        List<List<Long>> chunks = new ArrayList<>();
        for (int t = 0; t < tracks.length; t++) chunks.add(new ArrayList<>());
        int[] next = new int[tracks.length];
        for (boolean more = true; more; ) {
            more = false;
            for (int t = 0; t < tracks.length; t++) {
                int count = tracks[t].samples().size();
                if (next[t] >= count) continue;
                chunks.get(t).add((long) mdat.size());
                int end = Math.min(count, next[t] + tracks[t].plainChunk);
                for (int i = next[t]; i < end; i++) mdat.put(tracks[t].content(i));
                next[t] = end;
                more = true;
            }
        }
        byte[] ftyp = new Box().box("ftyp", new Box().ascii("isom").u32(0x200).ascii("isom").ascii("iso2")
                .ascii("avc1").ascii("mp41").bytes()).bytes();
        // The moov's size doesn't hang on where the samples start, so a first build measures it.
        long dataStart = ftyp.length + 8 + (moovLast ? 0 : plainMoov(tracks, chunks, 0, wide).length + 8);
        byte[] moov = plainMoov(tracks, chunks, dataStart, wide);
        Box file = new Box().put(ftyp);
        if (moovLast) file.box("mdat", mdat.bytes()).box("moov", moov);
        else file.box("moov", moov).box("mdat", mdat.bytes());
        return file.bytes();
    }

    private static byte[] plainMoov(FragmentedMp4ForTests[] tracks, List<List<Long>> chunks, long dataStart,
            boolean wide) {
        Box moov = new Box();
        moov.box("mvhd", mvhd(tracks[0].movieTimescale, tracks.length + 1));
        for (int t = 0; t < tracks.length; t++) {
            moov.box("trak", tracks[t].trak(t + 1, tracks[t].tables(chunks.get(t), dataStart, wide)));
        }
        return moov.bytes();
    }

    /** The tables that list every sample of this track, in chunks that start [chunks] bytes past [dataStart]. */
    private byte[] tables(List<Long> chunks, long dataStart, boolean wide) {
        List<Sample> samples = samples();
        int count = samples.size();
        Box tables = new Box();

        long[] durations = new long[count];
        long[] offsets = new long[count];
        boolean composed = false;
        boolean negative = false;
        for (int i = 0; i < count; i++) {
            durations[i] = samples.get(i).duration;
            offsets[i] = samples.get(i).offset;
            composed |= offsets[i] != 0;
            negative |= offsets[i] < 0;
        }
        List<long[]> times = runLengths(durations);
        Box stts = new Box().u32(0).u32(times.size());
        for (long[] pair : times) stts.u32(pair[0]).u32(pair[1]);
        tables.box("stts", stts.bytes());
        if (composed) {
            List<long[]> shown = runLengths(offsets);
            Box ctts = new Box().u32(negative ? 0x01000000 : 0).u32(shown.size());
            for (long[] pair : shown) ctts.u32(pair[0]).u32(pair[1]);
            tables.box("ctts", ctts.bytes());
        }

        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) if ((samples.get(i).flags & 0x00010000) == 0) keys.add(i + 1);
        if (keys.size() < count) {
            Box stss = new Box().u32(0).u32(keys.size());
            for (int key : keys) stss.u32(key);
            tables.box("stss", stss.bytes());
        }

        // A group for each change in how many samples a chunk holds: all full but perhaps the last.
        Box groups = new Box();
        int groupCount = 0;
        long previous = -1;
        for (int c = 0; c < chunks.size(); c++) {
            long holds = Math.min(plainChunk, count - (long) c * plainChunk);
            if (holds == previous) continue;
            groups.u32(c + 1).u32(holds).u32(1);
            groupCount++;
            previous = holds;
        }
        tables.box("stsc", new Box().u32(0).u32(groupCount).put(groups.bytes()).bytes());

        boolean uniform = true;
        for (Sample sample : samples) uniform &= sample.size == samples.get(0).size;
        Box stsz = new Box().u32(0).u32(uniform ? samples.get(0).size : 0).u32(count);
        if (!uniform) for (Sample sample : samples) stsz.u32(sample.size);
        tables.box("stsz", stsz.bytes());

        Box offsetsBox = new Box().u32(0).u32(chunks.size());
        for (long chunk : chunks) {
            if (wide) offsetsBox.u64(dataStart + chunk);
            else offsetsBox.u32(dataStart + chunk);
        }
        tables.box(wide ? "co64" : "stco", offsetsBox.bytes());
        return tables.bytes();
    }

    /** [values] as pairs of a count and a value, one pair for each run of equal values. */
    private static List<long[]> runLengths(long[] values) {
        List<long[]> pairs = new ArrayList<>();
        for (long value : values) {
            long[] last = pairs.isEmpty() ? null : pairs.get(pairs.size() - 1);
            if (last != null && last[1] == value) last[0]++;
            else pairs.add(new long[] {1, value});
        }
        return pairs;
    }

    /** This track's trak as track [id], its stbl holding [tables] after the stsd. */
    private byte[] trak(int id, byte[] tables) {
        Box trak = new Box();
        trak.box("tkhd", new Box().u32(7).u32(0).u32(0).u32(id).u32(0).u32(0).zeros(8).u16(0).u16(0)
                .u16(handler.equals("soun") ? 0x0100 : 0).u16(0).put(matrix()).u32((long) width << 16)
                .u32((long) height << 16).bytes());
        if (edits != null) {
            Box elst = new Box().u32(0).u32(edits.length);
            for (long[] edit : edits) elst.u32(edit[0]).u32(edit[1]).u32(edit[2]);
            trak.box("edts", new Box().box("elst", elst.bytes()).bytes());
        }

        Box stbl = new Box();
        stbl.box("stsd", sampleDescriptions());
        stbl.put(tables);
        Box minf = new Box();
        if (handler.equals("vide")) minf.box("vmhd", new Box().u32(1).zeros(8).bytes());
        else minf.box("smhd", new Box().u32(0).u32(0).bytes());
        minf.box("dinf", new Box().box("dref", new Box().u32(0).u32(1).box("url ", new Box().u32(1).bytes()).bytes())
                .bytes());
        minf.box("stbl", stbl.bytes());
        Box mdia = new Box();
        mdia.box("mdhd", new Box().u32(0).u32(0).u32(0).u32(timescale).u32(0).u16(0x55C4).u16(0).bytes());
        mdia.box("hdlr", new Box().u32(0).u32(0).ascii(handler).zeros(12).put("Facebook\0".getBytes(
                StandardCharsets.US_ASCII)).bytes());
        mdia.box("minf", minf.bytes());
        trak.box("mdia", mdia.bytes());
        return trak.bytes();
    }

    private static byte[] matrix() {
        return new Box().u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000)
                .bytes();
    }

    /** The moof's payload for [fragment], whose moof starts at [moofStart] and whose samples at [dataStart]. */
    private byte[] moof(Fragment fragment, long moofStart, long dataStart) {
        List<Sample> samples = fragment.samples;
        Sample first = samples.get(0);
        long duration = fragment.tfhdDefaults ? first.duration : trexDuration;
        int size = fragment.tfhdDefaults ? first.size : trexSize;
        int flags = fragment.tfhdDefaults ? (samples.size() > 1 ? samples.get(1).flags : first.flags) : trexFlags;

        int tfhdFlags = fragment.explicitBase ? 0x1 : 0x020000;
        Box tfhd = new Box();
        if (fragment.tfhdDefaults) tfhdFlags |= 0x8 | 0x10 | 0x20;
        tfhd.u32(tfhdFlags).u32(trackId);
        if (fragment.explicitBase) tfhd.u64(dataStart);
        if (fragment.tfhdDefaults) tfhd.u32(duration).u32(size).u32(flags);

        Box traf = new Box();
        traf.box("tfhd", tfhd.bytes());
        if (fragment.decodeTime != null) {
            traf.box("tfdt", new Box().u32(0x01000000).u64(fragment.decodeTime).bytes());
        }

        int perRun = (samples.size() + fragment.runs - 1) / fragment.runs;
        long runStart = dataStart;
        for (int from = 0; from < samples.size(); from += perRun) {
            List<Sample> run = samples.subList(from, Math.min(samples.size(), from + perRun));
            int trunFlags = fragment.fields;
            boolean firstFlags = (trunFlags & FLAGS) == 0 && run.get(0).flags != flags;
            if (firstFlags) trunFlags |= 0x4;
            if (!fragment.explicitBase) trunFlags |= 0x1;
            Box trun = new Box();
            trun.u32(((long) fragment.trunVersion << 24) | trunFlags).u32(run.size());
            if (!fragment.explicitBase) trun.u32(runStart - moofStart);
            if (firstFlags) trun.u32(run.get(0).flags);
            for (int i = 0; i < run.size(); i++) {
                Sample sample = run.get(i);
                check((trunFlags & DURATION) != 0 || sample.duration == duration, "a duration no default gives");
                check((trunFlags & SIZE) != 0 || sample.size == size, "a size no default gives");
                check((trunFlags & FLAGS) != 0 || (i == 0 && firstFlags) || sample.flags == flags,
                        "flags no default gives");
                check((trunFlags & OFFSET) != 0 || sample.offset == 0, "an offset the run doesn't list");
                if ((trunFlags & DURATION) != 0) trun.u32(sample.duration);
                if ((trunFlags & SIZE) != 0) trun.u32(sample.size);
                if ((trunFlags & FLAGS) != 0) trun.u32(sample.flags);
                if ((trunFlags & OFFSET) != 0) trun.u32(sample.offset);
                runStart += sample.size;
            }
            traf.box("trun", trun.bytes());
        }

        Box moof = new Box();
        moof.box("mfhd", new Box().u32(0).u32(fragments.indexOf(fragment) + 1).bytes());
        moof.box("traf", traf.bytes());
        return moof.bytes();
    }

    private static void check(boolean fine, String what) {
        if (!fine) throw new IllegalStateException("the test built " + what);
    }

    /** Big-endian bytes, boxes among them. */
    static final class Box {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Box u8(int value) {
            out.write(value);
            return this;
        }

        Box u16(int value) {
            return u8(value >> 8).u8(value);
        }

        Box u32(long value) {
            return u16((int) (value >> 16) & 0xFFFF).u16((int) value & 0xFFFF);
        }

        Box u64(long value) {
            return u32(value >>> 32).u32(value & 0xFFFFFFFFL);
        }

        Box zeros(int count) {
            return put(new byte[count]);
        }

        Box ascii(String text) {
            return put(text.getBytes(StandardCharsets.US_ASCII));
        }

        Box put(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
            return this;
        }

        Box box(String type, byte[] payload) {
            return u32(payload.length + 8L).ascii(type).put(payload);
        }

        int size() {
            return out.size();
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
