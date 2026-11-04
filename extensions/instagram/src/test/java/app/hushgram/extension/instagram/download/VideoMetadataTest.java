/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import app.hushgram.extension.shared.SettingsContextRule;

/**
 * Where a saved video came from, written into the MP4's item list. The three facts have to read
 * back from the moov a player uses, every other box has to come out as it was, and the media has
 * to stay byte for byte where it was: the whole old file is still the start of the new one, but
 * for the four bytes that turn the old moov into free space.
 *
 * <p>The samples beside this test are ffmpeg's: one with the moov ahead of the media and Lavf's
 * own encoder item in its list, one with the moov after the media and an empty list, and a
 * fragmented one, which is left alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoMetadataTest {

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    static final String MOOV_FIRST = "video-metadata-first.mp4";
    static final String MOOV_LAST = "video-metadata-last.mp4";
    static final String FRAGMENTED = "video-metadata-fragmented.mp4";

    private static final String LINK = "https://www.instagram.com/p/DFDjk1fRK43/";

    /** Item types as exiftool names them, the copyright sign (0xA9) and three letters. */
    static final String ART = (char) 0xA9 + "ART";
    static final String DAY = (char) 0xA9 + "day";
    private static final String TOO = (char) 0xA9 + "too";

    /** An accented Latin name with two kanji on the end, from code points so the source stays ASCII. */
    private static final String UTF8_NAME = new String(new int[] {0xE9, 't', 0xE9, '_', 0x65E5, 0x672C}, 0, 6);

    private final List<File> temporaries = new ArrayList<>();

    @After
    public void tearDown() {
        for (File file : temporaries) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        temporaries.clear();
    }

    // ---- The write ------------------------------------------------------------------------------

    /** A moov ahead of the media: the new one goes on the end and Lavf's own item stays in the list. */
    @Test
    public void tagsAMoovFirstFileAndKeepsItsOtherItems() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        File video = file(original);

        assertTrue(write(video, full()));

        byte[] tagged = Files.readAllBytes(video.toPath());
        assertMediaStays(original, tagged);
        Map<String, String> items = items(tagged);
        assertEquals("stevious", items.get(ART));
        assertEquals("2026-09-01T12:00:00Z", items.get(DAY));
        assertEquals(LINK, items.get("desc"));
        assertEquals("the encoder item ffmpeg wrote is kept", "Lavf63.1.101", items.get(TOO));
        assertEquals(Arrays.asList("ftyp", "free", "free", "mdat", "moov"), topLevel(tagged));
        assertOtherBoxesKept(original, tagged);
    }

    /** A moov after the media, the layout MediaMuxer writes, with an empty item list. */
    @Test
    public void tagsAMoovLastFile() throws Exception {
        byte[] original = sample(MOOV_LAST);
        File video = file(original);

        assertTrue(write(video, full()));

        byte[] tagged = Files.readAllBytes(video.toPath());
        assertMediaStays(original, tagged);
        Map<String, String> items = items(tagged);
        assertEquals(3, items.size());
        assertEquals("stevious", items.get(ART));
        assertEquals("2026-09-01T12:00:00Z", items.get(DAY));
        assertEquals(LINK, items.get("desc"));
        assertEquals(Arrays.asList("ftyp", "free", "mdat", "free", "moov"), topLevel(tagged));
        assertOtherBoxesKept(original, tagged);
    }

    /** A moov with no user data gets a whole udta, meta and iTunes handler of its own. */
    @Test
    public void addsUserDataToAMoovWithout() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        int udta = indexOf(original, "udta".getBytes(StandardCharsets.US_ASCII));
        assertTrue(udta > 0);
        // The sample's udta becomes free space inside the moov, which has to stay as it is.
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, original, udta, 4);
        File video = file(original);

        assertTrue(write(video, full()));

        byte[] tagged = Files.readAllBytes(video.toPath());
        assertMediaStays(original, tagged);
        Map<String, String> items = items(tagged);
        assertEquals(3, items.size());
        assertEquals("stevious", items.get(ART));
        assertEquals("mdir", handler(tagged));
        assertOtherBoxesKept(original, tagged);
    }

    /** A profile video or a save that knows only some of it writes only what it knows. */
    @Test
    public void writesOnlyWhatTheSaveKnows() throws Exception {
        File video = file(sample(MOOV_LAST));

        assertTrue(write(video, VideoMetadata.items(PostDetails.of("3549837394260045367").withPostId("3549837394260045367"))));

        Map<String, String> items = items(Files.readAllBytes(video.toPath()));
        assertEquals(LINK, items.get("desc"));
        assertNull(items.get(ART));
        assertNull(items.get(DAY));
    }

    /** A story video, built the way StoryDownload builds it, has no post page, so it gets no link. */
    @Test
    public void aStoryVideoGetsNoLink() throws Exception {
        File video = file(sample(MOOV_FIRST));

        assertTrue(write(video, VideoMetadata.items(
                new PostDetails("3549837394260045367", "stevious", postedUtc()).withPostId(null))));

        Map<String, String> items = items(Files.readAllBytes(video.toPath()));
        assertEquals("stevious", items.get(ART));
        assertEquals("2026-09-01T12:00:00Z", items.get(DAY));
        assertNull(items.get("desc"));
    }

    @Test
    public void aSaveThatKnowsNothingLeavesTheFileAlone() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        File video = file(original);

        assertTrue(VideoMetadata.items(PostDetails.NONE).isEmpty());
        assertFalse(write(video, VideoMetadata.items(PostDetails.NONE)));
        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    /** Saving the same video again replaces the items written before, never doubles them. */
    @Test
    public void aSecondWriteReplacesItsOwnItems() throws Exception {
        File video = file(sample(MOOV_FIRST));
        assertTrue(write(video, full()));
        Map<Integer, String> again = new LinkedHashMap<>(full());
        again.put(VideoMetadata.ARTIST, "someone_else");

        assertTrue(write(video, again));

        byte[] tagged = Files.readAllBytes(video.toPath());
        Map<String, String> items = items(tagged);
        assertEquals("someone_else", items.get(ART));
        assertEquals("Lavf63.1.101", items.get(TOO));
        assertEquals(4, items.size());
        assertEquals(Arrays.asList("ftyp", "free", "free", "mdat", "free", "moov"), topLevel(tagged));
    }

    /** Names and links in any script are UTF-8 text, the data type exiftool reads as a string. */
    @Test
    public void writesTextAsUtf8() throws Exception {
        File video = file(sample(MOOV_LAST));
        Map<Integer, String> items = new LinkedHashMap<>();
        items.put(VideoMetadata.ARTIST, UTF8_NAME);

        assertTrue(write(video, items));

        assertEquals(UTF8_NAME, items(Files.readAllBytes(video.toPath())).get(ART));
    }

    // ---- What's left alone ----------------------------------------------------------------------

    @Test
    public void leavesAFragmentedFileAlone() throws Exception {
        assertLeftAlone(sample(FRAGMENTED));
    }

    @Test
    public void leavesACutShortFileAlone() throws Exception {
        byte[] whole = sample(MOOV_FIRST);
        assertLeftAlone(Arrays.copyOf(whole, whole.length - 100));
    }

    @Test
    public void leavesAFileThatIsntAnMp4Alone() throws Exception {
        try (InputStream in = VideoMetadataTest.class.getResourceAsStream("post-metadata-sample.jpg")) {
            assertNotNull(in);
            assertLeftAlone(readAll(in));
        }
    }

    /** Media that runs to the end of the file, size 0, can't have a moov written after it. */
    @Test
    public void leavesMediaThatRunsToTheEndAlone() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        int mdat = topLevelStart(original, "mdat");
        assertEquals("the sample's media is its last box", original.length, mdat + size(original, mdat));
        original[mdat] = 0;
        original[mdat + 1] = 0;
        original[mdat + 2] = 0;
        original[mdat + 3] = 0;
        assertLeftAlone(original);
    }

    /** A second moov means the tagger can't tell which one a player reads. */
    @Test
    public void leavesAFileWithTwoMoovsAlone() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        int moov = topLevelStart(original, "moov");
        ByteArrayOutputStream twice = new ByteArrayOutputStream();
        twice.write(original, 0, original.length);
        twice.write(original, moov, size(original, moov));
        assertLeftAlone(twice.toByteArray());
    }

    /** A user data meta of another handler isn't an iTunes item list, so it isn't written into. */
    @Test
    public void leavesAMetaOfAnotherHandlerAlone() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        assertEquals("mdir", handler(original));
        int handler = indexOf(original, "mdir".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy("mdta".getBytes(StandardCharsets.US_ASCII), 0, original, handler, 4);
        assertEquals("mdta", handler(original));
        assertLeftAlone(original);
    }

    /**
     * A QuickTime meta, with no version and flags ahead of its hdlr, holds an iTunes list exiftool
     * doesn't read under a moov's udta, so writing into it would tag nothing anyone sees.
     */
    @Test
    public void leavesAQuickTimeMetaAlone() throws Exception {
        byte[] full = sample(MOOV_LAST);
        int moov = topLevelStart(full, "moov");
        assertEquals("the sample's moov is its last box", full.length, moov + size(full, moov));
        int udta = child(full, moov + 8, moov + size(full, moov), "udta");
        int meta = meta(full);
        assertTrue("the sample has a udta/meta", udta > 0 && meta > udta);
        // Take out the meta's four bytes of version and flags and shrink the three boxes around them.
        byte[] quickTime = new byte[full.length - 4];
        System.arraycopy(full, 0, quickTime, 0, meta + 8);
        System.arraycopy(full, meta + 12, quickTime, meta + 8, full.length - meta - 12);
        for (int box : new int[] {moov, udta, meta}) putWord(quickTime, box, word(full, box) - 4);
        assertEquals("hdlr", type(quickTime, meta + 8));
        assertEquals("mdir", handler(quickTime));
        assertLeftAlone(quickTime);
    }

    // ---- A failed write -------------------------------------------------------------------------

    /** The write that would free the old moov fails: the old type and the old length come back. */
    @Test
    public void aFailedWritePutsTheFileBack() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        File video = file(original);
        try (RandomAccessFile file = new RandomAccessFile(video, "rw")) {
            VideoMetadata.Store real = VideoMetadata.over(file.getChannel(), file.getChannel());
            VideoMetadata.Store failing = new Failing(real, 2, false);
            try {
                VideoMetadata.write(failing, full());
                fail("the failed write was swallowed");
            } catch (IOException expected) {
                assertEquals("the second write failed", expected.getMessage());
            }
        }
        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    /** The appended moov reads back different, so the old one is never freed. */
    @Test
    public void aMoovThatReadsBackWrongIsTakenOff() throws Exception {
        byte[] original = sample(MOOV_LAST);
        File video = file(original);
        try (RandomAccessFile file = new RandomAccessFile(video, "rw")) {
            VideoMetadata.Store real = VideoMetadata.over(file.getChannel(), file.getChannel());
            try {
                VideoMetadata.write(new Failing(real, 0, true), full());
                fail("a moov that read back wrong was kept");
            } catch (IOException expected) {
                assertEquals("the new moov read back different", expected.getMessage());
            }
        }
        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    /**
     * The old moov is already free when the disk stops taking writes, so its type can't come back.
     * Cutting the end then would leave no moov at all, so the new one stays and the file plays.
     */
    @Test
    public void aFailedRestoreKeepsTheNewMoov() throws Exception {
        byte[] original = sample(MOOV_FIRST);
        File video = file(original);
        try (RandomAccessFile file = new RandomAccessFile(video, "rw")) {
            VideoMetadata.Store real = VideoMetadata.over(file.getChannel(), file.getChannel());
            Failing failing = new Failing(real, 0, false);
            failing.failSync = 2;
            failing.failWritesFrom = 3;
            try {
                VideoMetadata.write(failing, full());
                fail("the failed sync was swallowed");
            } catch (IOException expected) {
                assertEquals("the second sync failed", expected.getMessage());
            }
        }
        byte[] saved = Files.readAllBytes(video.toPath());
        assertMediaStays(original, saved);
        List<String> top = topLevel(saved);
        assertEquals("one whole moov", 1, top.stream().filter("moov"::equals).count());
        assertEquals("moov", top.get(top.size() - 1));
        assertEquals("stevious", items(saved).get(ART));
    }

    /** Running out of memory reading the new moov back puts the file back too, not only an IOException. */
    @Test
    public void runningOutOfMemoryPutsTheFileBack() throws Exception {
        byte[] original = sample(MOOV_LAST);
        File video = file(original);
        try (RandomAccessFile file = new RandomAccessFile(video, "rw")) {
            VideoMetadata.Store real = VideoMetadata.over(file.getChannel(), file.getChannel());
            Failing failing = new Failing(real, 0, false);
            failing.readBackRunsOut = true;
            try {
                VideoMetadata.write(failing, full());
                fail("the error was swallowed");
            } catch (OutOfMemoryError expected) {
                assertEquals("no room to read the new moov back", expected.getMessage());
            }
        }
        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    // ---- The way in -----------------------------------------------------------------------------

    /** Android 9 hands over a file:// address, and the MP4 MIME takes the video route. */
    @Test
    public void aSavedMp4AtAFileAddressIsTagged() throws Exception {
        byte[] original = sample(MOOV_LAST);
        File video = file(original);
        Context context = RuntimeEnvironment.getApplication();

        // file:///C:/... on Windows, file:///tmp/... elsewhere: a path with forward slashes.
        String path = video.getAbsolutePath().replace(File.separatorChar, '/');
        PostMetadata.writeTo(context, "file://" + (path.startsWith("/") ? "" : "/") + path, "video/mp4", post());

        byte[] tagged = Files.readAllBytes(video.toPath());
        assertMediaStays(original, tagged);
        assertEquals(full().size(), items(tagged).size());
        assertEquals("stevious", items(tagged).get(ART));
    }

    /** A save whose file is gone by the time the tag runs is never made again, empty. */
    @Test
    public void aFileAddressWithNoFileMakesNone() throws Exception {
        File gone = file(sample(MOOV_LAST));
        assertTrue(gone.delete());
        Context context = RuntimeEnvironment.getApplication();

        String path = gone.getAbsolutePath().replace(File.separatorChar, '/');
        PostMetadata.writeTo(context, "file://" + (path.startsWith("/") ? "" : "/") + path, "video/mp4", post());

        assertFalse(gone.exists());
    }

    /** A video of another MIME, a WebM, is never opened for writing. */
    @Test
    public void aSavedVideoOfAnotherKindIsLeftAlone() throws Exception {
        byte[] original = sample(MOOV_LAST);
        File video = file(original);
        Context context = RuntimeEnvironment.getApplication();

        PostMetadata.writeTo(context, video.getPath(), "video/webm", post());

        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** The account, noon on 2026-09-01 UTC and the post link, as a full save knows them. */
    private static Map<Integer, String> full() {
        return VideoMetadata.items(post());
    }

    /** A feed post's details, as VideoDownload builds them: the post is its own media. */
    private static PostDetails post() {
        return new PostDetails("3549837394260045367", "stevious", postedUtc()).withPostId("3549837394260045367");
    }

    private static Date postedUtc() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        return calendar.getTime();
    }

    private static boolean write(File video, Map<Integer, String> items) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(video, "rw")) {
            return VideoMetadata.write(VideoMetadata.over(file.getChannel(), file.getChannel()), items);
        }
    }

    private void assertLeftAlone(byte[] original) throws Exception {
        File video = file(original);
        assertFalse(write(video, full()));
        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
    }

    static byte[] sample(String name) throws IOException {
        try (InputStream in = VideoMetadataTest.class.getResourceAsStream(name)) {
            assertNotNull("no " + name + " beside the test", in);
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private File file(byte[] bytes) throws IOException {
        File file = File.createTempFile("hushgram-video", ".mp4");
        Files.write(file.toPath(), bytes);
        temporaries.add(file);
        return file;
    }

    /**
     * The media never moves: the whole old file is the start of the new one, but for the four
     * bytes of the old moov's type, now free.
     */
    static void assertMediaStays(byte[] original, byte[] tagged) {
        assertTrue("the new moov was added", tagged.length > original.length);
        int moov = topLevelStart(original, "moov");
        assertTrue("the original has a moov", moov >= 0);
        byte[] expected = Arrays.copyOf(original, original.length);
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, expected, moov + 4, 4);
        assertArrayEquals("the old file is the start of the new one", expected, Arrays.copyOf(tagged, original.length));
    }

    /** Every box in the new moov but its udta is the old moov's, byte for byte and in order. */
    private static void assertOtherBoxesKept(byte[] original, byte[] tagged) {
        List<byte[]> before = moovChildren(original, topLevelStart(original, "moov"));
        List<byte[]> after = moovChildren(tagged, topLevelStart(tagged, "moov"));
        List<byte[]> kept = new ArrayList<>();
        for (byte[] box : before) if (!isType(box, "udta")) kept.add(box);
        List<byte[]> now = new ArrayList<>();
        for (byte[] box : after) if (!isType(box, "udta")) now.add(box);
        assertEquals(kept.size(), now.size());
        for (int i = 0; i < kept.size(); i++) assertArrayEquals("moov child " + i, kept.get(i), now.get(i));
    }

    // ---- A reader of its own, walking the boxes the way exiftool does ---------------------------

    /** The top-level box types, in order. */
    static List<String> topLevel(byte[] file) {
        List<String> types = new ArrayList<>();
        for (int at = 0; at + 8 <= file.length; at += size(file, at)) types.add(type(file, at));
        return types;
    }

    /** Where the file's one top-level box of [type] starts, or -1. */
    private static int topLevelStart(byte[] file, String type) {
        for (int at = 0; at + 8 <= file.length; at += size(file, at)) if (type.equals(type(file, at))) return at;
        return -1;
    }

    private static List<byte[]> moovChildren(byte[] file, int moov) {
        List<byte[]> boxes = new ArrayList<>();
        for (int at = moov + 8, end = moov + size(file, moov); at < end; at += size(file, at)) {
            boxes.add(Arrays.copyOfRange(file, at, at + size(file, at)));
        }
        return boxes;
    }

    /** The moov's udta/meta, where it starts, or -1. */
    private static int meta(byte[] file) {
        int moov = topLevelStart(file, "moov");
        int udta = child(file, moov + 8, moov + size(file, moov), "udta");
        if (udta < 0) return -1;
        return child(file, udta + 8, udta + size(file, udta), "meta");
    }

    /** Where [meta]'s children start: past four bytes of version and flags in a full box. */
    private static int metaChildren(byte[] file, int meta) {
        return "hdlr".equals(type(file, meta + 8)) ? meta + 8 : meta + 12;
    }

    private static String handler(byte[] file) {
        int meta = meta(file);
        int hdlr = child(file, metaChildren(file, meta), meta + size(file, meta), "hdlr");
        // A full box's third word, after version and flags and pre_defined.
        return new String(file, hdlr + 16, 4, StandardCharsets.ISO_8859_1);
    }

    /** The items of the moov's udta/meta/ilst, type to text. */
    static Map<String, String> items(byte[] file) {
        Map<String, String> items = new LinkedHashMap<>();
        int meta = meta(file);
        if (meta < 0) return items;
        int ilst = child(file, metaChildren(file, meta), meta + size(file, meta), "ilst");
        if (ilst < 0) return items;
        for (int at = ilst + 8, end = ilst + size(file, ilst); at < end; at += size(file, at)) {
            int data = at + 8;
            assertEquals("data", type(file, data));
            assertEquals("UTF-8 text", 1, word(file, data + 8));
            String value = new String(file, data + 16, size(file, data) - 16, StandardCharsets.UTF_8);
            items.put(new String(file, at + 4, 4, StandardCharsets.ISO_8859_1), value);
        }
        return items;
    }

    private static int child(byte[] file, int from, int to, String type) {
        for (int at = from; at + 8 <= to; at += size(file, at)) if (type.equals(type(file, at))) return at;
        return -1;
    }

    private static boolean isType(byte[] box, String type) {
        return type.equals(type(box, 0));
    }

    private static int size(byte[] file, int at) {
        int size = word(file, at);
        assertTrue("a box of size " + size + " at " + at, size >= 8);
        return size;
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                | (file[at + 3] & 0xFF);
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >>> 24);
        file[at + 1] = (byte) (value >>> 16);
        file[at + 2] = (byte) (value >>> 8);
        file[at + 3] = (byte) value;
    }

    private static String type(byte[] file, int at) {
        return new String(file, at + 4, 4, StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * A store whose [failWrite]th write (counting from 1) throws, or whose read of the appended
     * moov comes back with its last byte changed when [corruptReadBack] is set. [failWritesFrom]
     * makes every write from that one on throw, [failSync] makes that sync throw, and
     * [readBackRunsOut] makes the appended moov's read run out of memory.
     */
    private static final class Failing implements VideoMetadata.Store {
        private final VideoMetadata.Store real;
        private final int failWrite;
        private final boolean corruptReadBack;
        int failWritesFrom;
        int failSync;
        boolean readBackRunsOut;
        private int writes;
        private int syncs;
        private long appendedAt = -1;

        Failing(VideoMetadata.Store real, int failWrite, boolean corruptReadBack) {
            this.real = real;
            this.failWrite = failWrite;
            this.corruptReadBack = corruptReadBack;
        }

        @Override public long size() throws IOException {
            return real.size();
        }

        @Override public byte[] read(long at, int length) throws IOException {
            if (readBackRunsOut && at == appendedAt) throw new OutOfMemoryError("no room to read the new moov back");
            byte[] bytes = real.read(at, length);
            if (corruptReadBack && at == appendedAt) bytes[length - 1] ^= 1;
            return bytes;
        }

        @Override public void write(long at, byte[] bytes) throws IOException {
            writes++;
            if (writes == 1) appendedAt = at;
            if (writes == failWrite) throw new IOException("the second write failed");
            if (failWritesFrom > 0 && writes >= failWritesFrom) throw new IOException("the disk took no more writes");
            real.write(at, bytes);
        }

        @Override public void sync() throws IOException {
            syncs++;
            if (syncs == failSync) throw new IOException("the second sync failed");
            real.sync();
        }

        @Override public void truncate(long size) throws IOException {
            real.truncate(size);
        }
    }
}
