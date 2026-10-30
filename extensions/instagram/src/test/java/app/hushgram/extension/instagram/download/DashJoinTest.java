/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Looper;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The join of a DASH save, sample by sample. Robolectric's own extractor hands a whole track over
 * as one sample, so these tests describe each track as a run of real-sized samples, and a muxer
 * that can fail where a phone's can: writing a sample, stopping, or being released.
 *
 * <p>The normal case here is a minute of 720p at 30 frames a second, 1,800 picture samples of
 * 5 KB, beside 2,584 AAC frames of 370 bytes: the size of a saved reel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = {DashJoinTest.Samples.class, DashJoinTest.FaultyMuxer.class})
public class DashJoinTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int PICTURES = 1_800;
    private static final int SOUNDS = 2_584;

    private LocalServer server;
    private MediaUrlPolicy policy;
    private Context context;
    private DashManifest.Track video;
    private DashManifest.Track audio;

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        byte[] body = mp4(1_000);
        server.serve("/v.mp4", 200, "video/mp4", body, body.length);
        server.serve("/a.mp4", 200, "audio/mp4", body, body.length);
        video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 1_200_000, server.origin() + "/v.mp4");
        audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, server.origin() + "/a.mp4");
        Samples.reset();
        FaultyMuxer.reset();
        LogBufferManager.clearLogBuffer();
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        Samples.reset();
        FaultyMuxer.reset();
        MediaSave.policyForTests = null;
        LogBufferManager.clearLogBuffer();
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        return body;
    }

    private int workFiles() {
        String[] left = DashSave.workFolder(context).list();
        return left == null ? 0 : left.length;
    }

    /** Cancelled once [when] says so, and remembers when it first did. */
    private static final class CancelWhen implements Downloader.Progress {
        final BooleanSupplier when;
        volatile long cancelledAt;

        CancelWhen(BooleanSupplier when) {
            this.when = when;
        }

        @Override public void transferred(long done, long total) {
        }

        @Override public void reading(Runnable close) {
        }

        @Override public boolean cancelled() {
            if (cancelledAt != 0) return true;
            if (!when.getAsBoolean()) return false;
            cancelledAt = System.nanoTime();
            return true;
        }
    }

    /** Records whether the gallery was ever asked for a row, and the work files there were then. */
    private final class Gallery implements Downloader.Sink {
        boolean opened;
        int workFilesAtOpen = -1;

        @Override public OutputStream open(String mime) {
            opened = true;
            workFilesAtOpen = workFiles();
            return new NullStream();
        }

        @Override public void commit() {
        }

        @Override public void abandon() {
        }
    }

    private static final class NullStream extends OutputStream {
        @Override public void write(int b) {
        }

        @Override public void write(byte[] bytes, int offset, int length) {
        }
    }

    @Test
    public void aNormalJoinCopiesEverySampleAndReleasesEverything() {
        long started = System.nanoTime();
        Gallery gallery = new Gallery();
        Downloader.Result result = DashSave.save(context, video, audio, gallery, policy, Downloader.MAX_BYTES,
                Downloader.SILENT);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(PICTURES + SOUNDS, FaultyMuxer.writes.get());
        assertEquals("both extractors", 2, Samples.released.get());
        assertEquals("the extractor that read the joined file back", 1, Samples.readBack.get());
        assertEquals(1, FaultyMuxer.released.get());
        assertEquals(0, workFiles());
        // Read back from the joined file: these tracks declare no profile and no duration, and
        // unknown is what the report says of them.
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the saved file holds video/avc profile unknown 1280x720 duration unknown, "
                + "audio/mp4a-latm AAC object type unknown 44100 Hz 2 ch duration unknown"));
        System.out.println("DashJoinTest: a " + (PICTURES + SOUNDS) + "-sample join took " + tookMs + " ms");
    }

    /**
     * Cancel is read before every sample. It used to be read once, before the join, so a Cancel
     * pressed while a long video was joined wrote the whole file before anything stopped.
     */
    @Test
    public void aCancelDuringTheJoinStopsBeforeTheNextSampleAndRemovesTheOutput() {
        CancelWhen progress = new CancelWhen(() -> FaultyMuxer.writes.get() >= 1_000);
        Gallery gallery = new Gallery();

        Downloader.Result result = DashSave.save(context, video, audio, gallery, policy, Downloader.MAX_BYTES, progress);
        long afterCancelMs = (System.nanoTime() - progress.cancelledAt) / 1_000_000;

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertEquals("samples were written after the cancel", 1_000, FaultyMuxer.writes.get());
        assertFalse("a cancelled join reached the gallery", gallery.opened);
        assertEquals("a cancelled join left its output", 0, workFiles());
        assertEquals("both extractors", 2, Samples.released.get());
        assertEquals(1, FaultyMuxer.released.get());
        System.out.println("DashJoinTest: the join ended " + afterCancelMs + " ms after Cancel");
    }

    /**
     * The tracks go once they're joined. They used to stay until the gallery had its copy, so a
     * save held the picture, the sound, the joined file and the gallery's copy all at once.
     */
    @Test
    public void onlyTheJoinedFileIsLeftWhileTheGalleryCopiesIt() {
        Gallery gallery = new Gallery();

        Downloader.Result result = DashSave.save(context, video, audio, gallery, policy, Downloader.MAX_BYTES,
                Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("work files while the gallery copied", 1, gallery.workFilesAtOpen);
    }

    /**
     * A track can declare any sample size, and the join allocated a buffer of whatever it said. One
     * that declares more than a join holds fails at once, and the save takes the single file.
     */
    @Test
    public void aTrackDeclaringHugeSamplesFallsBackToTheSingleFile() {
        Samples.maxInputSize = 1_500_000_000;
        byte[] single = mp4(4_096);
        server.serve("/single.mp4", 200, "video/mp4", single, single.length);
        MediaSave.policyForTests = policy;
        MediaSaveTest.Gallery rows = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream published = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(rows.videoUri(1), published);

        Downloader.Result result = MediaSave.dashJob(context, video, audio, server.origin() + "/single.mp4")
                .run(new MediaStoreWriter(context, true), Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals("the single file wasn't what reached the gallery", single, published.toByteArray());
        assertEquals(0, FaultyMuxer.writes.get());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("declares samples of 1500000000 bytes"));
        assertFalse(report, report.contains("heap space"));
        assertTrue(report, report.contains("Saved in place of the manifest's tracks because the DASH save ended with "
                + "BUILD_ERROR (the tracks could not be joined)"));
        assertEquals(0, workFiles());
    }

    /**
     * A VP9 picture never reaches the muxer, which refuses VP9 in an MP4 on every Android version: the
     * join writes the MP4 itself, and the gallery gets the VP9 picture and its sound in one plain MP4.
     */
    @Test
    public void aVp9PictureIsJoinedWithoutTheMuxer() {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 1080, 1920, 15_360);
        FragmentedMp4ForTests.Fragment frames = picture.fragment(0L);
        for (int i = 0; i < 60; i++) {
            frames.add(i == 0 ? 4_000 : 600, 512, i == 0 ? FragmentedMp4ForTests.SYNC : FragmentedMp4ForTests.NON_SYNC, 0);
        }
        FragmentedMp4ForTests sound = FragmentedMp4ForTests.sound(44_100);
        sound.edits = new long[][] {{0, 1_024, 0x00010000}};
        FragmentedMp4ForTests.Fragment frames2 = sound.fragment(0L);
        for (int i = 0; i < 88; i++) frames2.add(200, 1_024, FragmentedMp4ForTests.SYNC, 0);
        byte[] pictureFile = picture.build();
        byte[] soundFile = sound.build();
        server.serve("/vp9.mp4", 200, "video/mp4", pictureFile, pictureFile.length);
        server.serve("/sound.mp4", 200, "audio/mp4", soundFile, soundFile.length);
        DashManifest.Track vp9 = new DashManifest.Track("video/mp4", "vp09.00.40.08", 1080, 1920, 711_000,
                server.origin() + "/vp9.mp4");
        DashManifest.Track aac = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 64_000,
                server.origin() + "/sound.mp4");
        Kept gallery = new Kept();

        Downloader.Result result = DashSave.save(context, vp9, aac, gallery, policy, Downloader.MAX_BYTES,
                Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("the muxer was asked to write VP9", 0, FaultyMuxer.writes.get());
        PlainMp4ForTests.Movie saved = PlainMp4ForTests.read(gallery.bytes.toByteArray());
        assertEquals(java.util.Arrays.asList("ftyp", "moov", "mdat"), saved.topLevel);
        assertEquals("vp09", saved.tracks.get(0).sampleEntry);
        assertEquals(60, saved.tracks.get(0).count());
        assertEquals("mp4a", saved.tracks.get(1).sampleEntry);
        assertEquals(88, saved.tracks.get(1).count());
        assertEquals(0, workFiles());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("joining the tracks without MediaMuxer, which can't write VP9 into an MP4"));
    }

    /** Only VP9, and AV1 before Android 14, skip the muxer. Every other picture keeps the join it always had. */
    @Test
    public void onlyVp9AndAv1BeforeAndroid14SkipTheMuxer() {
        assertTrue(DashSave.ownWriter("vp09.00.40.08", 36));
        assertTrue(DashSave.ownWriter("vp09.00.21.08", 30));
        assertTrue(DashSave.ownWriter("av01.0.08m.08", 33));
        assertFalse(DashSave.ownWriter("av01.0.08m.08", 34));
        assertFalse(DashSave.ownWriter("avc1.64001f", 30));
        assertFalse(DashSave.ownWriter("hvc1.1.6.l93.90", 30));
    }

    /**
     * A conversion to H.264 runs after both tracks are down and before the join (#236). Robolectric
     * has no codecs, so it fails the way a phone without a fitting decoder does: a build error that
     * says so, nothing joined or sent to the gallery, and no work file left.
     */
    @Test
    public void aConversionTheCodecsCantDoEndsTheSaveCleanly() {
        Gallery gallery = new Gallery();
        Downloader.Result result = DashSave.save(context, video, audio, true, false, gallery, policy,
                Downloader.MAX_BYTES, Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.BUILD_ERROR, result.status);
        assertTrue(result.toString(), result.toString().contains("could not be converted to H.264: the phone has no "
                + "decoder of video/avc"));
        assertFalse("a failed conversion reached the gallery", gallery.opened);
        assertEquals("the muxer joined a picture that was never converted", 0, FaultyMuxer.writes.get());
        assertEquals("a failed conversion left its work files", 0, workFiles());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the picture was not converted to H.264: the phone has no decoder of video/avc"));
    }

    private Downloader.Result runConverting(DashManifest.Track picture, DashManifest.Pick instead) {
        MediaSave.policyForTests = policy;
        MediaSaveTest.Gallery rows = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(rows.videoUri(1), new ByteArrayOutputStream());
        return MediaSave.dashJob(context, picture, audio, null, DownloadQuality.BEST, true, instead)
                .run(new MediaStoreWriter(context, true), Downloader.SILENT);
    }

    /**
     * When the conversion fails and the save would fall back to the same picture, it joins the one
     * it already fetched: nothing is fetched twice.
     */
    @Test
    public void aFailedConversionJoinsThePictureItFetched() {
        Downloader.Result result = runConverting(video, new DashManifest.Pick(video, audio));

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(PICTURES + SOUNDS, FaultyMuxer.writes.get());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the conversion ended with BUILD_ERROR (the picture could not be converted "
                + "to H.264: the phone has no decoder of video/avc"));
        assertTrue(report, report.contains(", joining the picture as it is"));
        assertFalse("the same tracks were saved again", report.contains("saving the tracks as they are"));
        assertEquals(0, workFiles());
    }

    /**
     * When the conversion of the full size fails, the save takes the H.264 pair it would have
     * without it, so the person still gets a file other apps can open, only smaller.
     */
    @Test
    public void aFailedConversionSavesTheH264PairInstead() {
        byte[] body = mp4(1_000);
        server.serve("/full.mp4", 200, "video/mp4", body, body.length);
        DashManifest.Track full = new DashManifest.Track("video/mp4", "avc1.640028", 1920, 1080, 3_000_000,
                server.origin() + "/full.mp4");

        Downloader.Result result = runConverting(full, new DashManifest.Pick(video, audio));

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(PICTURES + SOUNDS, FaultyMuxer.writes.get());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the DASH save ended with BUILD_ERROR (the picture could not be converted "
                + "to H.264: the phone has no decoder of video/avc"));
        assertTrue(report, report.contains("saving the tracks as they are: video/mp4 avc1.64001f 1280x720 1200kbps"));
        assertFalse(report, report.contains("joining the picture as it is"));
        assertEquals(0, workFiles());
    }

    /**
     * With only xHE-AAC in the manifest, the converted picture's sound is the single file's (#236):
     * that file is fetched as the sound, never the manifest's sound. A whole file can't be joined as
     * it is, so a conversion that fails saves the single file in its place, even when the picture
     * itself is what a failed conversion would otherwise join as it is.
     */
    @Test
    public void theSingleFilesSoundIsFetchedForAConvertedPicture() {
        byte[] single = mp4(4_096);
        server.serve("/single.mp4", 200, "video/mp4", single, single.length);
        MediaSave.policyForTests = policy;
        MediaSaveTest.Gallery rows = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream published = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(rows.videoUri(1), published);

        Downloader.Result result = MediaSave.dashJob(context, video, null,
                MediaSave.Rendition.of(server.origin() + "/single.mp4"), DownloadQuality.BEST, true,
                new DashManifest.Pick(video, null), true)
                .run(new MediaStoreWriter(context, true), Downloader.SILENT);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals("the single file wasn't what reached the gallery", single, published.toByteArray());
        assertEquals("fetched as the sound, then as the file saved in the picture's place", 2,
                server.hits("/single.mp4"));
        assertEquals(0, server.hits("/a.mp4"));
        assertEquals("the muxer joined a picture that was never converted", 0, FaultyMuxer.writes.get());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the DASH save ended with BUILD_ERROR (the picture could not be converted "
                + "to H.264: the phone has no decoder of video/avc"));
        assertFalse(report, report.contains("joining the picture as it is"));
        assertEquals(0, workFiles());
    }

    /** Keeps what the gallery was sent. */
    private static final class Kept implements Downloader.Sink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public OutputStream open(String mime) {
            return bytes;
        }

        @Override public void commit() {
        }

        @Override public void abandon() {
        }
    }

    /** A cancel that lands once the last sample is written still wins over publication. */
    @Test
    public void aCancelAfterTheLastSampleLeavesNoGalleryRow() {
        CancelWhen progress = new CancelWhen(() -> FaultyMuxer.stops.get() > 0);
        Gallery gallery = new Gallery();

        Downloader.Result result = DashSave.save(context, video, audio, gallery, policy, Downloader.MAX_BYTES, progress);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertFalse("a save cancelled before publication opened a gallery row", gallery.opened);
        assertEquals(0, workFiles());
    }

    /**
     * HushGram's settings cancel a save the way its notification does: SaveControl.cancel on a
     * save they list, here while it's listed as joining. The join stops at its next sample.
     */
    @Test
    public void aCancelFromTheSettingsListStopsTheJoin() throws Exception {
        java.util.List<SaveControl.Phase> seen = new java.util.ArrayList<>();
        FaultyMuxer.afterWrite = () -> {
            if (FaultyMuxer.writes.get() != 1_000) return;
            for (SaveControl.Running save : SaveControl.running()) {
                seen.add(save.phase);
                SaveControl.cancel(save.id);
            }
        };

        String report = runOnTheWorker();

        assertEquals("the save wasn't listed as joining", java.util.Collections.singletonList(SaveControl.Phase.JOINING),
                seen);
        assertEquals("samples were written after the cancel", 1_000, FaultyMuxer.writes.get());
        assertTrue("a cancelled save is still listed", SaveControl.running().isEmpty());
        assertTrue(report, report.contains("save finished: CANCELLED (cancelled during the join)"));
        assertEquals("both extractors", 2, Samples.released.get());
    }

    /** A muxer that can't stop used to skip both extractors, and hid the failure that came first. */
    @Test
    public void aMuxerThatFailsToStopStillReleasesBothExtractors() throws Exception {
        FaultyMuxer.failStop = true;
        String report = runOnTheWorker();

        assertEquals("both extractors", 2, Samples.released.get());
        assertEquals(1, FaultyMuxer.released.get());
        assertTrue(report, report.contains("DashSave | ERROR | the DASH save failed"));
        assertTrue(report, report.contains("save finished: BUILD_ERROR (the tracks could not be joined)"));
    }

    @Test
    public void aMuxerThatFailsToReleaseStillReleasesBothExtractors() throws Exception {
        FaultyMuxer.failRelease = true;
        runOnTheWorker();

        assertEquals("both extractors", 2, Samples.released.get());
    }

    @Test
    public void theFirstFailureIsTheOneReported() throws Exception {
        FaultyMuxer.failWriteAt = 5;
        FaultyMuxer.failStop = true;
        String report = runOnTheWorker();

        assertTrue(report, report.contains("Exception: " + FaultyMuxer.WRITE_FAILED));
        assertFalse(report, report.contains("Exception: " + FaultyMuxer.STOP_FAILED));
        assertEquals("both extractors", 2, Samples.released.get());
        assertEquals(1, FaultyMuxer.released.get());
    }

    /**
     * The save as a tap runs it, on its worker with its notification. Its job and notification are
     * gone after it, however it ended.
     */
    private String runOnTheWorker() throws InterruptedException {
        MediaSave.policyForTests = policy;
        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, audio, null));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (Notification shown : Shadows.shadowOf(manager).getAllNotifications()) {
            assertFalse("the failed save's notification stayed up", SaveControl.CHANNEL.equals(shown.getChannelId()));
        }
        assertEquals(0, MediaSave.savesInFlight());
        assertEquals("a failed join left its work files", 0, workFiles());
        return LogBufferManager.buildExportText();
    }

    /**
     * An extractor over a run of samples: the picture file or the sound file, by the name the
     * save gives its work file. Counts every release.
     */
    @Implements(MediaExtractor.class)
    public static class Samples {
        static final AtomicInteger released = new AtomicInteger();
        /** Releases of extractors that read a joined file back for the report. */
        static final AtomicInteger readBack = new AtomicInteger();
        /** The largest sample each track declares, or 0 to declare none. */
        static volatile int maxInputSize;

        private boolean picture;
        /** A joined file: the picture's track, then the sound's. */
        private boolean joined;
        private boolean selected;
        private int at;

        static void reset() {
            released.set(0);
            readBack.set(0);
            maxInputSize = 0;
        }

        @Implementation
        protected void setDataSource(String path) throws IOException {
            String name = new File(path).getName();
            if (name.startsWith("video")) picture = true;
            else if (name.startsWith("joined")) joined = true;
            else if (!name.startsWith("audio")) throw new IOException("not a track: " + name);
        }

        @Implementation
        protected int getTrackCount() {
            return joined ? 2 : 1;
        }

        @Implementation
        protected MediaFormat getTrackFormat(int index) {
            MediaFormat format = picture || (joined && index == 0)
                    ? MediaFormat.createVideoFormat("video/avc", 1280, 720)
                    : MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2);
            if (maxInputSize > 0) format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
            return format;
        }

        @Implementation
        protected void selectTrack(int index) {
            selected = true;
        }

        private int count() {
            return picture ? PICTURES : SOUNDS;
        }

        @Implementation
        protected int readSampleData(ByteBuffer buffer, int offset) {
            if (!selected || at >= count()) return -1;
            int size = picture ? 5_000 : 370;
            buffer.position(offset);
            buffer.put(new byte[size]);
            return size;
        }

        @Implementation
        protected boolean advance() {
            at++;
            return at < count();
        }

        @Implementation
        protected long getSampleTime() {
            if (at >= count()) return -1;
            return picture ? at * 33_333L : at * 23_220L;
        }

        @Implementation
        protected int getSampleFlags() {
            return picture && at % 30 == 0 ? MediaExtractor.SAMPLE_FLAG_SYNC : 0;
        }

        @Implementation
        protected void release() {
            (joined ? readBack : released).incrementAndGet();
        }
    }

    /** Robolectric's muxer, which can be told to fail a write, the stop or the release. */
    @Implements(MediaMuxer.class)
    public static class FaultyMuxer extends ShadowMediaMuxer {
        static final String WRITE_FAILED = "a sample could not be written";
        static final String STOP_FAILED = "the muxer could not stop";
        static final AtomicInteger writes = new AtomicInteger();
        static final AtomicInteger stops = new AtomicInteger();
        static final AtomicInteger released = new AtomicInteger();
        /**
         * The muxers this test made. A muxer whose release failed keeps its native handle, and its
         * finalizer releases it again during some later test.
         */
        static final java.util.Set<Long> made = java.util.concurrent.ConcurrentHashMap.newKeySet();
        static volatile int failWriteAt = -1;
        static volatile boolean failStop;
        static volatile boolean failRelease;
        /** Run after each sample is written, on the save's thread. */
        static volatile Runnable afterWrite;

        static void reset() {
            writes.set(0);
            stops.set(0);
            released.set(0);
            made.clear();
            failWriteAt = -1;
            failStop = false;
            failRelease = false;
            afterWrite = null;
        }

        @Implementation
        protected static long nativeSetup(java.io.FileDescriptor fd, int format) throws IOException {
            long handle = ShadowMediaMuxer.nativeSetup(fd, format);
            made.add(handle);
            return handle;
        }

        @Implementation
        protected static void nativeWriteSampleData(long nativeObject, int trackIndex, ByteBuffer byteBuf,
                int offset, int size, long presentationTimeUs, int flags) {
            if (writes.get() == failWriteAt) throw new IllegalStateException(WRITE_FAILED);
            ShadowMediaMuxer.nativeWriteSampleData(nativeObject, trackIndex, byteBuf, offset, size,
                    presentationTimeUs, flags);
            writes.incrementAndGet();
            Runnable after = afterWrite;
            if (after != null) after.run();
        }

        @Implementation
        protected static void nativeStop(long nativeObject) {
            stops.incrementAndGet();
            // Robolectric's stop closes the output file, which Windows can't delete while it's open.
            ShadowMediaMuxer.nativeStop(nativeObject);
            if (failStop) throw new IllegalStateException(STOP_FAILED);
        }

        @Implementation
        protected static void nativeRelease(long nativeObject) {
            ReleasingMuxer.closeUnstopped(nativeObject);
            if (made.contains(nativeObject)) released.incrementAndGet();
            if (failRelease) throw new IllegalStateException("the muxer could not be released");
        }
    }
}
