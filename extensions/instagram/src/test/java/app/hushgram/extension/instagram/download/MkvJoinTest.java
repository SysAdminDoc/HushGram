/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static app.hushgram.extension.instagram.download.FragmentedMp4ForTests.DURATION;
import static app.hushgram.extension.instagram.download.FragmentedMp4ForTests.NON_SYNC;
import static app.hushgram.extension.instagram.download.FragmentedMp4ForTests.SIZE;
import static app.hushgram.extension.instagram.download.FragmentedMp4ForTests.SYNC;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The MKV join behind {@code Save videos as MKV} (#547). Its inputs are built the way Meta's DASH
 * tracks come ({@link FragmentedMp4ForTests}), and its output is read back with an EBML reader of
 * this test's own, so nothing here trusts the writer's helpers. Where FFmpeg is installed, FFmpeg
 * reads, seeks and decodes an MKV join of real VP9, AV1, H.264 and H.265 too.
 */
public class MkvJoinTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final int EBML = 0x1A45DFA3;
    private static final int DOC_TYPE = 0x4282;
    private static final int SEGMENT = 0x18538067;
    private static final int SEEK_HEAD = 0x114D9B74;
    private static final int SEEK = 0x4DBB;
    private static final int SEEK_ID = 0x53AB;
    private static final int SEEK_POSITION = 0x53AC;
    private static final int INFO = 0x1549A966;
    private static final int TIMESTAMP_SCALE = 0x2AD7B1;
    private static final int DURATION_ID = 0x4489;
    private static final int TRACKS = 0x1654AE6B;
    private static final int TRACK_ENTRY = 0xAE;
    private static final int TRACK_NUMBER = 0xD7;
    private static final int TRACK_TYPE = 0x83;
    private static final int LANGUAGE = 0x22B59C;
    private static final int CODEC_ID = 0x86;
    private static final int CODEC_PRIVATE = 0x63A2;
    private static final int DEFAULT_DURATION = 0x23E383;
    private static final int VIDEO = 0xE0;
    private static final int PIXEL_WIDTH = 0xB0;
    private static final int PIXEL_HEIGHT = 0xBA;
    private static final int COLOUR = 0x55B0;
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

    private static final Set<Integer> MASTERS = new HashSet<>(Arrays.asList(EBML, SEGMENT, SEEK_HEAD, SEEK, INFO,
            TRACKS, TRACK_ENTRY, VIDEO, AUDIO, COLOUR, 0x55D0, CLUSTER, CUES, CUE_POINT, CUE_TRACK_POSITIONS, TAGS,
            TAG, TARGETS, SIMPLE_TAG));

    // ---------------------------------------------------------------- reading an MKV back

    /** One element: its id, where it starts, where its content starts and how long that is. */
    private static final class Node {
        final int id;
        final long start;
        final long content;
        final long size;
        final List<Node> children = new ArrayList<>();

        Node(int id, long start, long content, long size) {
            this.id = id;
            this.start = start;
            this.content = content;
            this.size = size;
        }

        long end() {
            return content + size;
        }

        Node child(int id) {
            for (Node node : children) {
                if (node.id == id) return node;
            }
            return null;
        }

        List<Node> all(int id) {
            List<Node> found = new ArrayList<>();
            for (Node node : children) {
                if (node.id == id) found.add(node);
            }
            return found;
        }
    }

    /** Every element from [from] to [to] of [file], each master's children read too. Fails on a size that overruns. */
    private static List<Node> parse(byte[] file, long from, long to) {
        List<Node> nodes = new ArrayList<>();
        long at = from;
        while (at < to) {
            int first = file[(int) at] & 0xFF;
            int idLength = Integer.numberOfLeadingZeros(first) - 23;
            assertTrue("no element id at " + at, first != 0 && idLength <= 4);
            int id = 0;
            for (int k = 0; k < idLength; k++) id = (id << 8) | (file[(int) at + k] & 0xFF);
            int lead = file[(int) at + idLength] & 0xFF;
            int sizeLength = Integer.numberOfLeadingZeros(lead) - 23;
            assertTrue("no size at " + at, lead != 0);
            long size = lead & (0xFF >> sizeLength);
            for (int k = 1; k < sizeLength; k++) size = (size << 8) | (file[(int) at + idLength + k] & 0xFF);
            Node node = new Node(id, at, at + idLength + sizeLength, size);
            assertTrue(String.format("element %X at %d runs past its parent", id, at), node.end() <= to);
            if (MASTERS.contains(id)) node.children.addAll(parse(file, node.content, node.end()));
            nodes.add(node);
            at = node.end();
        }
        assertEquals(to, at);
        return nodes;
    }

    private static byte[] bytes(byte[] file, Node node) {
        return Arrays.copyOfRange(file, (int) node.content, (int) node.end());
    }

    private static long uint(byte[] file, Node node) {
        long value = 0;
        for (byte b : bytes(file, node)) value = (value << 8) | (b & 0xFF);
        return value;
    }

    private static String string(byte[] file, Node node) {
        return new String(bytes(file, node), StandardCharsets.UTF_8);
    }

    private static double dbl(byte[] file, Node node) {
        assertEquals(8, node.size);
        return Double.longBitsToDouble(uint(file, node));
    }

    /** The file read whole: the EBML header and the Segment, with nothing after it. */
    private static final class Mkv {
        final byte[] file;
        final Node segment;

        Mkv(byte[] file) {
            this.file = file;
            List<Node> top = parse(file, 0, file.length);
            assertEquals(2, top.size());
            assertEquals(EBML, top.get(0).id);
            assertEquals("matroska", string(file, top.get(0).child(DOC_TYPE)));
            segment = top.get(1);
            assertEquals(SEGMENT, segment.id);
        }

        List<Integer> order() {
            List<Integer> ids = new ArrayList<>();
            for (Node node : segment.children) {
                if (ids.isEmpty() || ids.get(ids.size() - 1) != node.id) ids.add(node.id);
            }
            return ids;
        }

        List<Node> tracks() {
            return segment.child(TRACKS).all(TRACK_ENTRY);
        }

        /** Where [node] is from the start of the Segment's content, as SeekHead and Cues count. */
        long position(Node node) {
            return node.start - segment.content;
        }

        List<Block> blocks() {
            List<Block> blocks = new ArrayList<>();
            for (Node cluster : segment.all(CLUSTER)) {
                long time = uint(file, cluster.child(TIMESTAMP));
                for (Node block : cluster.all(SIMPLE_BLOCK)) {
                    byte[] content = bytes(file, block);
                    assertEquals("a track number of one byte", 0x80, content[0] & 0x80);
                    Block b = new Block();
                    b.cluster = cluster;
                    b.track = content[0] & 0x7F;
                    b.time = time + (short) (((content[1] & 0xFF) << 8) | (content[2] & 0xFF));
                    b.key = (content[3] & 0x80) != 0;
                    assertEquals("no lacing", 0, content[3] & 0x06);
                    b.frame = Arrays.copyOfRange(content, 4, content.length);
                    blocks.add(b);
                }
            }
            return blocks;
        }

        List<Block> blocks(int track) {
            List<Block> found = new ArrayList<>();
            for (Block block : blocks()) {
                if (block.track == track) found.add(block);
            }
            return found;
        }
    }

    private static final class Block {
        Node cluster;
        int track;
        long time;
        boolean key;
        byte[] frame;
    }

    // ---------------------------------------------------------------- joining

    private File write(FragmentedMp4ForTests track, String name) throws IOException {
        // A folder of its own, since a test can join more than once.
        File file = new File(temp.newFolder(), name + ".mp4");
        Files.write(file.toPath(), track.build());
        return file;
    }

    private Mkv join(FragmentedMp4ForTests picture, FragmentedMp4ForTests sound) throws IOException {
        File out = temp.newFile();
        assertTrue("the join stopped", MkvJoin.join(write(picture, "video"),
                sound == null ? null : write(sound, "audio"), out, Downloader.SILENT));
        return new Mkv(Files.readAllBytes(out.toPath()));
    }

    /**
     * A VP9 picture and its AAC sound, the way a reel's DASH tracks come: the EBML header, then one
     * Segment of SeekHead, Info, Tracks, the Void kept for the post's details, the clusters and the
     * Cues. Each SeekHead entry points at its element, every sample is a block of its own bytes in
     * decode order with its key flag, and each picture key frame starts a cluster the Cues list.
     */
    @Test
    public void writesEverySampleAsABlockInTheMatroskaLayout() throws IOException {
        FragmentedMp4ForTests picture = Mp4JoinTest.vp9Picture();
        FragmentedMp4ForTests sound = Mp4JoinTest.aacSound();
        Mkv mkv = join(picture, sound);
        byte[] file = mkv.file;

        assertEquals(Arrays.asList(SEEK_HEAD, INFO, TRACKS, VOID, CLUSTER, CUES), mkv.order());
        assertEquals(MkvJoin.TAGS_ROOM, mkv.segment.child(VOID).end() - mkv.segment.child(VOID).start);
        assertEquals(file.length, mkv.segment.end());

        Map<Integer, Long> seeks = new LinkedHashMap<>();
        for (Node seek : mkv.segment.child(SEEK_HEAD).all(SEEK)) {
            seeks.put((int) uint(file, seek.child(SEEK_ID)), uint(file, seek.child(SEEK_POSITION)));
        }
        assertEquals(Arrays.asList(INFO, TRACKS, CUES), new ArrayList<>(seeks.keySet()));
        assertEquals(mkv.position(mkv.segment.child(INFO)), (long) seeks.get(INFO));
        assertEquals(mkv.position(mkv.segment.child(TRACKS)), (long) seeks.get(TRACKS));
        assertEquals(mkv.position(mkv.segment.child(CUES)), (long) seeks.get(CUES));

        Node info = mkv.segment.child(INFO);
        assertEquals(1_000_000, uint(file, info.child(TIMESTAMP_SCALE)));
        // The sound ends last: 130 frames of 1,024 past its priming, 3,018.6 ms, plus the 23 ms shift.
        assertEquals(3_042.0, dbl(file, info.child(DURATION_ID)), 0);

        List<Node> tracks = mkv.tracks();
        assertEquals(2, tracks.size());
        Node video = tracks.get(0);
        assertEquals(1, uint(file, video.child(TRACK_NUMBER)));
        assertEquals(1, uint(file, video.child(TRACK_TYPE)));
        assertEquals("V_VP9", string(file, video.child(CODEC_ID)));
        assertEquals("und", string(file, video.child(LANGUAGE)));
        // vpcC's profile 0, level 4.0, 8 bits and 4:2:0 as VP9's CodecPrivate features.
        assertArrayEquals(new byte[] {1, 1, 0, 2, 1, 40, 3, 1, 8, 4, 1, 1}, bytes(file, video.child(CODEC_PRIVATE)));
        assertEquals(33_333_333, uint(file, video.child(DEFAULT_DURATION)));
        Node pixels = video.child(VIDEO);
        assertEquals(1080, uint(file, pixels.child(PIXEL_WIDTH)));
        assertEquals(1920, uint(file, pixels.child(PIXEL_HEIGHT)));
        assertNull("square pixels say no display size", pixels.child(0x54B0));
        Map<Integer, Long> colours = new LinkedHashMap<>();
        for (Node colour : pixels.child(COLOUR).children) colours.put(colour.id, uint(file, colour));
        Map<Integer, Long> bt709 = new LinkedHashMap<>();
        bt709.put(0x55B1, 1L);
        bt709.put(0x55B2, 8L);
        bt709.put(0x55B9, 1L);
        bt709.put(0x55BA, 1L);
        bt709.put(0x55BB, 1L);
        assertEquals(bt709, colours);

        Node audio = tracks.get(1);
        assertEquals(2, uint(file, audio.child(TRACK_NUMBER)));
        assertEquals(2, uint(file, audio.child(TRACK_TYPE)));
        assertEquals("A_AAC", string(file, audio.child(CODEC_ID)));
        assertArrayEquals(new byte[] {0x12, 0x10}, bytes(file, audio.child(CODEC_PRIVATE)));
        assertEquals(44_100.0, dbl(file, audio.child(AUDIO).child(SAMPLING_FREQUENCY)), 0);
        assertEquals(2, uint(file, audio.child(AUDIO).child(CHANNELS)));

        // The sound's priming starts it 1,024 / 44,100 s early, -23 ms, so both tracks move 23 ms later.
        assertBlocks(picture, mkv.blocks(1), 23);
        assertBlocks(sound, mkv.blocks(2), 23);
        for (Block block : mkv.blocks()) assertTrue("a block before zero", block.time >= 0);

        List<Node> clusters = mkv.segment.all(CLUSTER);
        assertEquals("a cluster at each of the three key frames", 3, clusters.size());
        List<Node> points = mkv.segment.child(CUES).all(CUE_POINT);
        assertEquals(3, points.size());
        for (int c = 0; c < 3; c++) {
            Node point = points.get(c);
            Node positions = point.child(CUE_TRACK_POSITIONS);
            assertEquals(1, uint(file, positions.child(CUE_TRACK)));
            assertEquals(mkv.position(clusters.get(c)), uint(file, positions.child(CUE_CLUSTER_POSITION)));
            assertEquals(uint(file, clusters.get(c).child(TIMESTAMP)), uint(file, point.child(CUE_TIME)));
            Block first = null;
            for (Block block : mkv.blocks(1)) {
                if (block.cluster == clusters.get(c)) {
                    first = block;
                    break;
                }
            }
            assertNotNull(first);
            assertTrue("cluster " + c + " doesn't start at a key frame", first.key);
            assertEquals(uint(file, point.child(CUE_TIME)), first.time);
        }
    }

    /**
     * Every sample of [in] is one of [blocks], in order, with its own bytes and its key flag, shown
     * when it was shown before plus [shift] ms, to within the millisecond a block's time is rounded to.
     */
    private static void assertBlocks(FragmentedMp4ForTests in, List<Block> blocks, long shift) {
        List<FragmentedMp4ForTests.Sample> samples = in.samples();
        assertEquals(samples.size(), blocks.size());
        boolean picture = in.handler.equals("vide");
        for (int i = 0; i < samples.size(); i++) {
            Block block = blocks.get(i);
            assertArrayEquals("the bytes of sample " + i, in.content(i), block.frame);
            boolean sync = (samples.get(i).flags & 0x00010000) == 0;
            assertEquals("the key flag of sample " + i, !picture || sync, block.key);
            assertEquals("when sample " + i + " is shown", in.presentation(i) * 1000 + shift, block.time, 0.5 + 1e-9);
        }
    }

    /**
     * B-frames are shown in their own order, whether their offsets are negative or held back by an
     * edit list, and both ways give the same times: frame k is shown at its place in showing order.
     */
    @Test
    public void bFramesAreShownAtTheirCompositionTimes() throws IOException {
        FragmentedMp4ForTests negative = Mp4JoinTest.bFramePicture(1, 0);
        FragmentedMp4ForTests delayed = Mp4JoinTest.bFramePicture(0, 1_024);
        delayed.edits = new long[][] {{0, 1_024, 0x00010000}};
        List<Block> a = join(negative, null).blocks(1);
        List<Block> b = join(delayed, null).blocks(1);
        assertEquals(30, a.size());
        for (int k = 0; k < 30; k++) {
            int shown = k == 0 ? 0 : 3 * ((k - 1) / 3) + new int[] {3, 1, 2}[(k - 1) % 3];
            assertEquals("frame " + k, Math.round(shown * 512 * 1000.0 / 15_360), a.get(k).time);
            assertEquals("frame " + k, a.get(k).time, b.get(k).time);
        }
        assertBlocks(negative, a, 0);
    }

    /** An empty edit delays its track, and the other track doesn't move. */
    @Test
    public void anEmptyEditDelaysItsTrack() throws IOException {
        FragmentedMp4ForTests picture = Mp4JoinTest.vp9Picture();
        picture.edits = new long[][] {{250, -1, 0x00010000}, {3_000, 0, 0x00010000}};
        FragmentedMp4ForTests sound = Mp4JoinTest.aacSound();
        Mkv mkv = join(picture, sound);
        assertBlocks(picture, mkv.blocks(1), 23);
        assertEquals(273, mkv.blocks(1).get(0).time);
        assertBlocks(sound, mkv.blocks(2), 23);
    }

    /**
     * A block holds its time as two bytes from its cluster's, so a picture with no key frame for
     * longer than 32.767 s gets a new cluster the Cues don't list, since it can't be seeked to.
     */
    @Test
    public void aLongRunWithoutAKeyFrameStartsAnUnlistedCluster() throws IOException {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("avc1", 320, 240, 15_360);
        FragmentedMp4ForTests.Fragment fragment = picture.fragment(0L);
        fragment.fields = DURATION | SIZE | FragmentedMp4ForTests.FLAGS;
        for (int i = 0; i < 1_200; i++) fragment.add(20 + i % 7, 512, i == 0 ? SYNC : NON_SYNC, 0);
        Mkv mkv = join(picture, null);
        List<Node> clusters = mkv.segment.all(CLUSTER);
        assertEquals(2, clusters.size());
        assertEquals(1, mkv.segment.child(CUES).all(CUE_POINT).size());
        assertTrue(uint(mkv.file, clusters.get(1).child(TIMESTAMP)) > Short.MAX_VALUE);
        assertBlocks(picture, mkv.blocks(1), 0);
        assertEquals("V_MPEG4/ISO/AVC", string(mkv.file, mkv.tracks().get(0).child(CODEC_ID)));
    }

    /** Cancel is read before the copy and before each cluster, and a cancelled join says so. */
    @Test
    public void aCancelStopsTheCopyBetweenClusters() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger joining = new AtomicInteger();
        Downloader.Progress progress = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            @Override public boolean cancelled() {
                return asked.incrementAndGet() > 2;
            }

            @Override public void joining() {
                joining.incrementAndGet();
            }
        };
        File out = temp.newFile();
        assertFalse(MkvJoin.join(write(Mp4JoinTest.vp9Picture(), "video"), write(Mp4JoinTest.aacSound(), "audio"),
                out, progress));
        assertEquals("cancel wasn't read once before the copy and once before each cluster", 3, asked.get());
        assertEquals(1, joining.get());
    }

    // ---------------------------------------------------------------- what it won't write

    private void assertRefused(FragmentedMp4ForTests picture, String why) throws IOException {
        File video = write(picture, "refused-video");
        File audio = write(Mp4JoinTest.aacSound(), "refused-audio");
        File out = temp.newFile();
        IOException thrown = assertThrows(IOException.class,
                () -> MkvJoin.join(video, audio, out, Downloader.SILENT));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains(why));
        assertEquals("the output was written before the refusal", 0, out.length());
        video.delete();
        audio.delete();
    }

    /**
     * What Matroska has no way to say, or a codec it isn't written for, fails before the output
     * holds a byte, so the save makes an MP4 of the same tracks instead.
     */
    @Test
    public void refusesWhatAnMkvCantHoldBeforeWritingAnything() throws IOException {
        FragmentedMp4ForTests pieces = Mp4JoinTest.vp9Picture();
        pieces.edits = new long[][] {{1_000, 0, 0x00010000}, {1_000, 30_720, 0x00010000}};
        assertRefused(pieces, "cuts it in pieces");

        FragmentedMp4ForTests faster = Mp4JoinTest.vp9Picture();
        faster.edits = new long[][] {{1_500, 0, 0x00020000}};
        assertRefused(faster, "changes its speed");

        assertRefused(smallPicture("mp4v"), "doesn't write mp4v");
        // An hvc1 entry that holds an avcC and no hvcC has nothing to set its decoder up with.
        assertRefused(smallPicture("hvc1"), "no decoder setup");

        FragmentedMp4ForTests turned = Mp4JoinTest.vp9Picture();
        byte[] built = turned.build();
        int tkhd = indexOf(built, "tkhd");
        // The tkhd matrix starts 40 bytes into its payload: a quarter turn is {0, 1, 0, -1, 0, 0, 0, 0, 1}.
        ByteBuffer matrix = ByteBuffer.wrap(built, tkhd + 4 + 40, 36);
        matrix.putInt(0).putInt(0x00010000).putInt(0).putInt(-0x00010000).putInt(0).putInt(0).putInt(0).putInt(0)
                .putInt(0x40000000);
        File video = temp.newFile("turned.mp4");
        Files.write(video.toPath(), built);
        File out = temp.newFile();
        IOException thrown = assertThrows(IOException.class,
                () -> MkvJoin.join(video, write(Mp4JoinTest.aacSound(), "turned-audio"), out, Downloader.SILENT));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("turned"));
        assertEquals(0, out.length());
    }

    /** A second of pictures in sample entry [entry], which holds an avcC unless it's vp09 or av01. */
    private static FragmentedMp4ForTests smallPicture(String entry) {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture(entry, 320, 240, 15_360);
        FragmentedMp4ForTests.Fragment fragment = picture.fragment(0L);
        for (int i = 0; i < 30; i++) fragment.add(100 + i, 512, i == 0 ? SYNC : NON_SYNC, 0);
        return picture;
    }

    private static int indexOf(byte[] bytes, String type) {
        byte[] wanted = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + wanted.length <= bytes.length; i++) {
            for (int k = 0; k < wanted.length; k++) {
                if (bytes[i + k] != wanted[k]) continue outer;
            }
            return i;
        }
        throw new AssertionError("no " + type);
    }

    // ---------------------------------------------------------------- small pieces

    /** Times round half up, below zero too, so a track shown 0.5 ms before zero starts at zero. */
    @Test
    public void millisecondsRoundHalfUp() {
        assertEquals(-23, MkvJoin.ms(-1_024, 44_100));
        assertEquals(33, MkvJoin.ms(512, 15_360));
        assertEquals(1, MkvJoin.ms(1, 2_000));
        assertEquals(0, MkvJoin.ms(-1, 2_000));
        assertEquals(-1, MkvJoin.ms(-3, 2_000));
        assertEquals(3_000, MkvJoin.ms(46_080, 15_360));
    }

    /** A size of all ones means unknown, so 127 takes two bytes; a nine byte Void holds nothing. */
    @Test
    public void sizesNeverWriteTheUnknownValue() {
        assertEquals(1, MkvJoin.vintLength(126));
        assertEquals(2, MkvJoin.vintLength(127));
        assertEquals(2, MkvJoin.vintLength(16_382));
        assertEquals(3, MkvJoin.vintLength(16_383));
        assertArrayEquals(new byte[] {(byte) 0xEC, 0x01, 0, 0, 0, 0, 0, 0, 0}, MkvJoin.voidOf(9));
        byte[] room = MkvJoin.voidOf(MkvJoin.TAGS_ROOM);
        List<Node> nodes = parse(room, 0, room.length);
        assertEquals(1, nodes.size());
        assertEquals(VOID, nodes.get(0).id);
    }

    /**
     * The AAC setup comes out of the esds descriptors whether their lengths take one byte or the
     * four some encoders always write, and a stream that isn't AAC has none.
     */
    @Test
    public void readsTheAacSetupOutOfTheEsds() {
        byte[] plain = {0, 0, 0, 0, 3, 25, 0, 1, 0, 4, 17, 0x40, 0x15, 0, 0, 0, 0, 1, (byte) 0xF4, 0, 0, 1,
            (byte) 0xF4, 0, 5, 2, 0x12, 0x10, 6, 1, 2};
        assertArrayEquals(new byte[] {0x12, 0x10}, MkvJoin.audioConfig(ByteBuffer.wrap(plain)));
        byte[] padded = {0, 0, 0, 0, 3, (byte) 0x80, (byte) 0x80, (byte) 0x80, 34, 0, 1, 0, 4, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, 20, 0x40, 0x15, 0, 0, 0, 0, 1, (byte) 0xF4, 0, 0, 1, (byte) 0xF4, 0, 5,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, 2, 0x11, (byte) 0x90, 6, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1,
            2};
        assertArrayEquals(new byte[] {0x11, (byte) 0x90}, MkvJoin.audioConfig(ByteBuffer.wrap(padded)));
        byte[] mp3 = plain.clone();
        mp3[11] = 0x6B;
        assertNull(MkvJoin.audioConfig(ByteBuffer.wrap(mp3)));
    }

    // ---------------------------------------------------------------- the post's details

    private static Map<Integer, String> details(String link) {
        Map<Integer, String> items = new LinkedHashMap<>();
        items.put(VideoMetadata.ARTIST, "nasa");
        items.put(VideoMetadata.DAY, "2026-03-01T12:30:00Z");
        items.put(VideoMetadata.DESCRIPTION, link);
        return items;
    }

    private static boolean tag(File file, Map<Integer, String> items) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return MkvJoin.tag(VideoMetadata.over(channel, channel), items);
        }
    }

    private File joinedFile() throws IOException {
        File out = temp.newFile();
        assertTrue(MkvJoin.join(write(Mp4JoinTest.vp9Picture(), "tag-video"), write(Mp4JoinTest.aacSound(),
                "tag-audio"), out, Downloader.SILENT));
        return out;
    }

    /**
     * The poster, the post time and the link go into the Void the join kept, as one Tags element
     * and a Void for what's left, so the file keeps its length and every byte from the first
     * cluster on. A second write finds no room and leaves the file alone.
     */
    @Test
    public void tagsGoIntoTheKeptRoomWithoutMovingAByte() throws IOException {
        File out = joinedFile();
        byte[] before = Files.readAllBytes(out.toPath());
        Mkv untagged = new Mkv(before);
        int clustersAt = (int) untagged.segment.child(CLUSTER).start;

        assertTrue(tag(out, details("https://www.instagram.com/p/DAbC123xYz_/")));
        byte[] after = Files.readAllBytes(out.toPath());
        assertEquals(before.length, after.length);
        assertArrayEquals(Arrays.copyOfRange(before, 0, (int) untagged.segment.child(VOID).start),
                Arrays.copyOfRange(after, 0, (int) untagged.segment.child(VOID).start));
        assertArrayEquals(Arrays.copyOfRange(before, clustersAt, before.length),
                Arrays.copyOfRange(after, clustersAt, after.length));

        Mkv tagged = new Mkv(after);
        assertEquals(Arrays.asList(SEEK_HEAD, INFO, TRACKS, TAGS, VOID, CLUSTER, CUES), tagged.order());
        Node tag = tagged.segment.child(TAGS).child(TAG);
        assertEquals(50, uint(after, tag.child(TARGETS).child(TARGET_TYPE_VALUE)));
        Map<String, String> simple = new LinkedHashMap<>();
        for (Node node : tag.all(SIMPLE_TAG)) {
            simple.put(string(after, node.child(TAG_NAME)), string(after, node.child(TAG_STRING)));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ARTIST", "nasa");
        expected.put("DATE_RELEASED", "2026-03-01 12:30:00");
        expected.put("DESCRIPTION", "https://www.instagram.com/p/DAbC123xYz_/");
        assertEquals(expected, simple);

        assertFalse("a second tag found room", tag(out, details("https://www.instagram.com/p/other/")));
        assertArrayEquals(after, Files.readAllBytes(out.toPath()));
    }

    /**
     * Details that fill the room exactly need no Void after them, details that would leave one to
     * eight bytes can't, since no Void is that short, and details too long for the room are left
     * out. Each refusal leaves the file as it was.
     */
    @Test
    public void tagsThatDontFitLeaveTheFileAlone() throws IOException {
        StringBuilder link = new StringBuilder("https://www.instagram.com/p/");
        while (MkvJoin.tags(details(link.toString())).length < MkvJoin.TAGS_ROOM - 4) link.append('a');
        assertEquals(MkvJoin.TAGS_ROOM - 4, MkvJoin.tags(details(link.toString())).length);
        File four = joinedFile();
        byte[] before = Files.readAllBytes(four.toPath());
        assertFalse(tag(four, details(link.toString())));
        assertArrayEquals(before, Files.readAllBytes(four.toPath()));

        link.append("aaaa");
        assertEquals(MkvJoin.TAGS_ROOM, MkvJoin.tags(details(link.toString())).length);
        File exact = joinedFile();
        assertTrue(tag(exact, details(link.toString())));
        Mkv tagged = new Mkv(Files.readAllBytes(exact.toPath()));
        assertEquals(Arrays.asList(SEEK_HEAD, INFO, TRACKS, TAGS, CLUSTER, CUES), tagged.order());

        link.append("a");
        File over = joinedFile();
        before = Files.readAllBytes(over.toPath());
        assertFalse(tag(over, details(link.toString())));
        assertArrayEquals(before, Files.readAllBytes(over.toPath()));

        assertFalse(tag(joinedFile(), new LinkedHashMap<>()));
    }

    /** An MP4, or anything else that isn't a Matroska file, is never written to. */
    @Test
    public void aFileThatIsntAnMkvIsNeverTagged() throws IOException {
        File mp4 = temp.newFile("plain.mp4");
        assertTrue(Mp4Join.join(write(Mp4JoinTest.vp9Picture(), "mp4-video"), write(Mp4JoinTest.aacSound(),
                "mp4-audio"), mp4, Downloader.SILENT));
        byte[] before = Files.readAllBytes(mp4.toPath());
        assertFalse(tag(mp4, details("https://www.instagram.com/p/x/")));
        assertArrayEquals(before, Files.readAllBytes(mp4.toPath()));

        File empty = temp.newFile("empty.mkv");
        assertFalse(tag(empty, details("https://www.instagram.com/p/x/")));
        assertEquals(0, empty.length());
    }

    // ---------------------------------------------------------------- FFmpeg

    /**
     * Real VP9, AV1, H.264 with B-frames, and H.265 (HDR in one case), each beside real AAC and
     * fragmented as a DASH packager writes them. FFmpeg reads the same codecs, sizes, colors, key
     * frames and packet sizes out of the MKV, every packet shown when it was before plus one shift
     * both tracks share, decodes the whole file without an error, seeks to a key frame through the
     * Cues, and reads the post's details once they're written.
     *
     * <p>H.264 with negative offsets and no edit list is again the one FFmpeg reads its own way: it
     * shows such a fragment later by its most negative offset, where the MKV shows each frame at its
     * composition time. So that picture's shift is its own, the same for every frame.
     */
    @Test
    public void ffmpegReadsSeeksAndDecodesTheMkvOfRealTracks() throws Exception {
        Assume.assumeTrue("FFmpeg isn't installed here", Mp4JoinTest.FFMPEG.isFile() && Mp4JoinTest.FFPROBE.isFile());
        File folder = temp.newFolder("ffmpeg");
        File sound = Mp4JoinTest.encode(folder, "sound.mp4", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "3", "-c:a", "aac", "-b:a", "64k", "-f", "mp4", "-movflags", "+dash+global_sidx");
        String[][] pictures = {
            {"vp9", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k", "-g", "30",
                "-f", "mp4", "-movflags", "+dash+global_sidx"},
            {"av1", "-c:v", "libsvtav1", "-preset", "12", "-g", "30", "-f", "mp4", "-movflags", "+dash+global_sidx"},
            {"h264", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "2", "-g", "30", "-f", "mp4", "-movflags",
                "+dash+global_sidx"},
            {"h264-negative", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "2", "-g", "30", "-f", "mp4",
                "-frag_duration", "1000000", "-movflags",
                "frag_keyframe+empty_moov+default_base_moof+negative_cts_offsets"},
            {"hevc", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error:keyint=30",
                "-f", "mp4", "-movflags", "+dash+global_sidx"},
            {"hevc-hdr", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-color_primaries",
                "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc", "-x265-params",
                "log-level=error:keyint=30", "-tag:v", "hvc1", "-f", "mp4", "-movflags", "+dash+global_sidx"},
        };
        for (String[] kind : pictures) {
            String name = kind[0];
            List<String> args = new ArrayList<>(Arrays.asList("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
                    "-t", "3"));
            args.addAll(Arrays.asList(kind).subList(1, kind.length));
            File picture = Mp4JoinTest.encode(folder, name + ".mp4", args.toArray(new String[0]));
            File out = new File(folder, name + ".mkv");
            assertTrue(name, MkvJoin.join(picture, sound, out, Downloader.SILENT));
            new Mkv(Files.readAllBytes(out.toPath()));

            String codec = name.replace("-negative", "").replace("-hdr", "");
            assertEquals(name, codec + ",320,240\naac\n", Mp4JoinTest.run(Mp4JoinTest.FFPROBE.getPath(), "-v", "error",
                    "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", out.getPath()).replace("\r", ""));
            assertEquals(name + "'s colors", colors(picture), colors(out));

            double videoShift = assertSamePackets(name + " picture", picture, out, "v:0");
            double audioShift = assertSamePackets(name + " sound", sound, out, "a:0");
            if (!name.equals("h264-negative")) {
                assertEquals(name + ": the picture and the sound moved apart", audioShift, videoShift, 0.0011);
            }

            assertEquals(name + " didn't decode cleanly", "", Mp4JoinTest.run(Mp4JoinTest.FFMPEG.getPath(), "-v",
                    "error", "-xerror", "-i", out.getPath(), "-f", "null", "-"));
            String seeked = Mp4JoinTest.run(Mp4JoinTest.FFPROBE.getPath(), "-v", "error", "-select_streams", "v:0",
                    "-read_intervals", "2%+#1", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0",
                    out.getPath()).trim();
            assertTrue(name + " seeked to " + seeked, seeked.split(",")[1].startsWith("K"));
            double landed = Double.parseDouble(seeked.split(",")[0]);
            assertTrue(name + " seeked to " + landed, landed > 0.9 && landed < 2.1);

            assertTrue(name, tag(out, details("https://www.instagram.com/p/DAbC123xYz_/")));
            String format = Mp4JoinTest.run(Mp4JoinTest.FFPROBE.getPath(), "-v", "error", "-show_entries",
                    "format_tags", "-of", "default=noprint_wrappers=1", out.getPath()).replace("\r", "");
            assertTrue(format, format.contains("ARTIST=nasa\n"));
            assertTrue(format, format.contains("DATE_RELEASED=2026-03-01 12:30:00\n"));
            assertTrue(format, format.contains("DESCRIPTION=https://www.instagram.com/p/DAbC123xYz_/\n"));
            assertEquals(name + " didn't decode cleanly once tagged", "", Mp4JoinTest.run(
                    Mp4JoinTest.FFMPEG.getPath(), "-v", "error", "-xerror", "-i", out.getPath(), "-f", "null", "-"));
        }
    }

    /** The picture stream's range, matrix, transfer and primaries as FFprobe reads them. */
    private static String colors(File file) throws Exception {
        return Mp4JoinTest.run(Mp4JoinTest.FFPROBE.getPath(), "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=pix_fmt,color_range,color_space,color_transfer,color_primaries", "-of",
                "csv=p=0", file.getPath()).replace("\r", "");
    }

    /**
     * [stream] holds the same packets in [out] as in [in]: the same count, sizes and key flags, each
     * shown at its old time plus one shift, to within the millisecond the MKV rounds to. Answers the
     * shift in seconds.
     */
    private static double assertSamePackets(String what, File in, File out, String stream) throws Exception {
        double[][] before = packets(in, stream);
        double[][] after = packets(out, stream);
        assertEquals(what + ": the packet count", before.length, after.length);
        double shift = after[0][0] - before[0][0];
        for (int i = 0; i < before.length; i++) {
            assertEquals(what + ": the size of packet " + i, before[i][1], after[i][1], 0);
            assertEquals(what + ": the key flag of packet " + i, before[i][2], after[i][2], 0);
            assertEquals(what + ": when packet " + i + " is shown", before[i][0] + shift, after[i][0], 0.0011);
        }
        return shift;
    }

    /** Each packet of [stream] as its time in seconds, its size and 1 for a key frame. */
    private static double[][] packets(File file, String stream) throws Exception {
        String csv = Mp4JoinTest.run(Mp4JoinTest.FFPROBE.getPath(), "-v", "error", "-select_streams", stream,
                "-show_entries", "packet=pts_time,size,flags", "-of", "csv=p=0", file.getPath()).trim();
        String[] lines = csv.split("\\r?\\n");
        double[][] packets = new double[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            String[] fields = lines[i].split(",");
            packets[i] = new double[] {Double.parseDouble(fields[0]), Long.parseLong(fields[1]),
                fields[2].startsWith("K") ? 1 : 0};
        }
        return packets;
    }
}
