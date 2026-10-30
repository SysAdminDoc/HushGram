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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
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
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * A save as the feature runs one: into the cache, then through MediaStoreWriter into a stand-in
 * for MediaStore. A refused or broken fetch must never insert a row, pending or not, and must
 * leave the work folder empty. A good one publishes one finished row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30, shadows = ReleasingMuxer.class)
public class MediaSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private LocalServer server;
    private String origin;
    private MediaUrlPolicy policy;
    private Context context;
    private Gallery gallery;
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        origin = server.origin();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37;
        // A save of one file reads its policy through MediaSave, as every save does.
        MediaSave.policyForTests = policy;
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        resolver.registerOutputStream(gallery.videoUri(1), published);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        MediaSave.policyForTests = null;
        MediaSave.capForTests = 0;
        DashSave.usableForTests = null;
        // A saved setting outlives the test method that wrote it (the preference store survives
        // this sandbox, not just this class's Application instance), and a quality other than the
        // default changes what the writable-track and DASH-pick checks judge later saves against.
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        LogBufferManager.clearLogBuffer();
        // The join cases tell Robolectric's extractor about their work files, and it keeps that
        // in a static map. Cleared here rather than left to Robolectric's own reset.
        ShadowMediaExtractor.reset();
    }

    private void serve(String path, String type, byte[] body, long announced) {
        server.serve(path, 200, type, body, announced);
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    /** The save of one video file at [path], on the path every single-file save takes. */
    private Downloader.Result save(String path) {
        return save(path, new MediaStoreWriter(context, true));
    }

    private Downloader.Result save(String path, MediaStoreWriter writer) {
        return MediaSave.fileJob(context, origin + path, Downloader.Kind.VIDEO).run(writer, Downloader.SILENT);
    }

    /** The DASH save of [video] and [audio], on the path every DASH save takes, with no single file after it. */
    private Downloader.Result saveDash(DashManifest.Track video, DashManifest.Track audio) {
        return saveDash(video, audio, Downloader.SILENT);
    }

    private Downloader.Result saveDash(DashManifest.Track video, DashManifest.Track audio, Downloader.Progress progress) {
        return MediaSave.dashJob(context, video, audio, null).run(new MediaStoreWriter(context, true), progress);
    }

    private void assertNothingWasCreated(String what, Downloader.Result result) {
        assertTrue(what + " was saved: " + result, !result.ok());
        assertEquals(what + " created a gallery row", 0, gallery.inserts.size());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(what + " left work files behind", 0, left == null ? 0 : left.length);
    }

    @Test
    public void refusedAndBrokenFetchesNeverCreateARow() {
        byte[] page = "<html><body>Log in</body></html>".getBytes(StandardCharsets.UTF_8);
        serve("/page.mp4", "video/mp4", page, page.length);
        assertNothingWasCreated("a page sent as video", save("/page.mp4"));

        serve("/login", "text/html", page, page.length);
        Downloader.Result login = save("/login");
        assertEquals(login.toString(), Downloader.Status.REFUSED, login.status);
        assertNothingWasCreated("a login page", login);

        server.serve("/part.mp4", 206, "video/mp4", mp4(4000), 4000);
        Downloader.Result part = save("/part.mp4");
        assertEquals(part.toString(), Downloader.Status.HTTP_ERROR, part.status);
        assertNothingWasCreated("part of a file nobody asked for", part);

        // The work file may grow to 1024 bytes, and a stream with no announced length runs past it.
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 1024;
        serve("/big.mp4", "video/mp4", mp4(4096), -1);
        assertNothingWasCreated("an oversized stream", save("/big.mp4"));
        DashSave.usableForTests = null;

        serve("/short.mp4", "video/mp4", mp4(1000), 5000);
        assertNothingWasCreated("a truncated body", save("/short.mp4"));

        server.redirect("/away", "https://scontent.xx.fbcdn.net/v.mp4");
        // The lookup answers 10.9.8.7 for every Meta name here.
        assertNothingWasCreated("a redirect to a Meta name on a private address", save("/away"));
    }

    /**
     * Only Meta's media servers are candidates: a save whose addresses are all somewhere else
     * never starts, and says so without naming them. So does a save with no address at all.
     */
    @Test
    public void aSaveOfAddressesOffMetasServersNeverStarts() {
        MediaSave.policyForTests = null;
        assertFalse(MediaSave.saveVideo(context, SavesForTests.renditions("https://example.com/v/clip_720p.mp4"), null, null));
        assertFalse(MediaSave.savePhoto(context, SavesForTests.renditions("https://cdninstagram.com.example.com/x.jpg"), null));
        assertFalse(MediaSave.saveItem(context, SavesForTests.renditions(), null, null));
        assertFalse(MediaSave.saveVideo(context, null, null, null));
        assertFalse(MediaSave.saveVideo(null, SavesForTests.renditions("https://example.com/v/clip_720p.mp4"), null, null));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("nothing to save: none of the 1 addresses was on Meta's media servers"));
        assertTrue(report, report.contains("nothing to save: the item carried no address"));
        assertFalse(report, report.contains("example.com"));
        assertEquals(0, gallery.inserts.size());
    }

    /**
     * Android 9 has no MediaStore folders or pending rows, so a save there writes the file into
     * Movies/Instagram itself: a hidden work file on the list of leftovers, renamed when it's whole.
     * Without the storage permission the save stops before a byte is written.
     */
    @Test
    @Config(sdk = 28)
    public void onAndroid9ASaveWritesTheFileIntoTheFolderItself() throws IOException {
        assertTrue(MediaStoreWriter.legacyStorage());
        byte[] body = mp4(64_000);
        serve("/v.mp4", "video/mp4", body, body.length);
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");

        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Downloader.Result refused = save("/v.mp4");
        assertEquals(refused.toString(), Downloader.Status.WRITE_ERROR, refused.status);
        String[] none = folder.list();
        assertEquals(0, none == null ? 0 : none.length);

        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Downloader.Result first = save("/v.mp4");
        Downloader.Result second = save("/v.mp4");
        assertEquals(first.toString(), Downloader.Status.OK, first.status);
        assertEquals(second.toString(), Downloader.Status.OK, second.status);

        File[] files = folder.listFiles();
        assertNotNull(files);
        assertEquals(Arrays.toString(files), 2, files.length);
        for (File file : files) {
            assertTrue(file.getName(), file.getName().matches("IG_VID_\\d{8}_\\d{6}( \\(1\\))?\\.mp4"));
            assertArrayEquals(body, java.nio.file.Files.readAllBytes(file.toPath()));
        }
        assertEquals("the gallery table was written on Android 9", 0, gallery.inserts.size());
        SharedPreferences ledger = context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE);
        java.util.Set<String> pending = ledger.getStringSet("pending_rows", null);
        assertTrue("a finished file stayed on the list of leftovers: " + pending, pending == null || pending.isEmpty());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    @Test
    @Config(sdk = 28)
    public void onAndroid9OverlappingSavesOwnSeparateWorkFilesAndLedgerRows() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Settings.FILENAME_TEMPLATE.save("IG_" + FileNameTemplate.VIDEO_ID);
        MediaStoreWriter first = new MediaStoreWriter(context, true, "12345");
        MediaStoreWriter second = new MediaStoreWriter(context, true, "12345");
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");
        SharedPreferences ledger = context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE);
        byte[] firstBody = mp4(4096), secondBody = mp4(8192);
        try {
            OutputStream one = first.open("video/mp4");
            one.write(firstBody);
            OutputStream two = second.open("video/mp4");
            two.write(secondBody);
            assertEquals("overlapping saves shared one cleanup identity", 2,
                    ledger.getStringSet("pending_rows", java.util.Collections.emptySet()).size());
            assertEquals("overlapping saves shared one hidden work file", 2, folder.listFiles().length);
            two.close();

            first.abandon();
            assertEquals("Cancel removed another save's cleanup identity", 1,
                    ledger.getStringSet("pending_rows", java.util.Collections.emptySet()).size());
            second.commit();
            File[] saved = folder.listFiles();
            assertNotNull(saved);
            assertEquals("Cancel removed another save's file", 1, saved.length);
            assertFalse(saved[0].getName().startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX));
            assertArrayEquals(secondBody, java.nio.file.Files.readAllBytes(saved[0].toPath()));
            assertTrue(ledger.getStringSet("pending_rows", java.util.Collections.emptySet()).isEmpty());
        } finally {
            first.abandon();
            second.abandon();
            Settings.FILENAME_TEMPLATE.resetToDefault();
        }
    }

    @Test
    @Config(sdk = 28)
    public void onAndroid9ARefusedLedgerRemovesOnlyItsReservedWorkFile() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        byte[] previous = mp4(2048);
        File completed = new File(folder, "IG_existing.mp4");
        java.nio.file.Files.write(completed.toPath(), previous);
        byte[] body = mp4(8192);
        serve("/v.mp4", "video/mp4", body, body.length);
        for (boolean throwing : new boolean[] {false, true}) {
            Downloader.Result result = save("/v.mp4", new MediaStoreWriter(new BrokenLedger(context, throwing), true));
            assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
            assertEquals("a refused ledger left an untracked hidden file", 1, folder.listFiles().length);
            assertArrayEquals(previous, java.nio.file.Files.readAllBytes(completed.toPath()));
            assertTrue(context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE)
                    .getStringSet("pending_rows", java.util.Collections.emptySet()).isEmpty());
        }
    }

    @Test
    @Config(sdk = 28)
    public void onAndroid9ConcurrentCommitsKeepEveryCompletedFile() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Settings.FILENAME_TEMPLATE.save("IG_" + FileNameTemplate.VIDEO_ID);
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 20; round++) {
                List<MediaStoreWriter> writers = new ArrayList<>();
                List<byte[]> bodies = new ArrayList<>();
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<?>> completed = new ArrayList<>();
                try {
                    for (int index = 0; index < 3; index++) {
                        MediaStoreWriter writer = new MediaStoreWriter(context, true, String.valueOf(1000 + round));
                        writers.add(writer);
                        byte[] body = mp4(4096 + index * 1024);
                        bodies.add(body);
                        OutputStream out = writer.open("video/mp4");
                        out.write(body);
                        out.close();
                    }
                    for (MediaStoreWriter writer : writers) completed.add(pool.submit(() -> {
                        go.await();
                        writer.commit();
                        return null;
                    }));
                    go.countDown();
                    for (java.util.concurrent.Future<?> result : completed) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    File[] saved = folder.listFiles();
                    assertNotNull(saved);
                    assertEquals("a concurrent commit replaced a completed file", (round + 1) * 3, saved.length);
                    List<File> current = new ArrayList<>();
                    for (File file : saved) {
                        assertFalse("a completed work file stayed hidden", file.getName().startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX));
                        if (file.getName().startsWith("IG_" + (1000 + round))) current.add(file);
                    }
                    assertEquals("a round lost one of its completed files", 3, current.size());
                    for (byte[] body : bodies) {
                        boolean found = false;
                        for (File file : current) {
                            if (Arrays.equals(body, java.nio.file.Files.readAllBytes(file.toPath()))) found = true;
                        }
                        assertTrue("a concurrent commit changed the saved bytes", found);
                    }
                } finally {
                    go.countDown();
                    for (MediaStoreWriter writer : writers) writer.abandon();
                }
            }
            assertTrue(context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE)
                    .getStringSet("pending_rows", java.util.Collections.emptySet()).isEmpty());
        } finally {
            pool.shutdownNow();
            Settings.FILENAME_TEMPLATE.resetToDefault();
        }
    }

    @Test
    public void aGoodVideoIsPublishedAsOneFinishedRow() {
        byte[] body = mp4(64_000);
        serve("/v.mp4", "video/mp4", body, body.length);

        Downloader.Result result = save("/v.mp4");

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, gallery.inserts.size());
        ContentValues row = gallery.rows.get(1L);
        assertEquals("the row was left pending", Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("video/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertTrue(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".mp4"));
        assertArrayEquals(body, published.toByteArray());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    @Test
    public void aGalleryThatDoesNotPublishThePendingRowIsReportedAsAFailedSave() {
        byte[] body = mp4(4096);
        serve("/not-published.mp4", "video/mp4", body, body.length);
        gallery.refuseUpdate = true;

        Downloader.Result result = save("/not-published.mp4");

        assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
        assertEquals(1, gallery.inserts.size());
        assertTrue("the unpublished row was left behind", gallery.rows.isEmpty());
        String[] left = DashSave.workFolder(context).list();
        assertEquals("the work file outlived a failed publish", 0, left == null ? 0 : left.length);
    }

    @Test
    public void aGalleryStreamThatFailsOnCloseDoesNotPublishItsRow() {
        byte[] body = mp4(4096);
        serve("/close-fails.mp4", "video/mp4", body, body.length);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), new OutputStream() {
            @Override public void write(int value) { published.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) {
                published.write(bytes, offset, length);
            }
            @Override public void close() throws IOException { throw new IOException("gallery write did not finish"); }
        });

        Downloader.Result result = save("/close-fails.mp4");

        assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
        assertEquals(1, gallery.inserts.size());
        assertTrue("the unfinished row was left behind", gallery.rows.isEmpty());
    }

    @Test
    public void aDashTrackOffMetasServersIsRefusedBeforeAnythingIsFetched() {
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                "https://example.com/v.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000,
                "http://scontent.xx.fbcdn.net/a.mp4");

        // Meta's own rules, as a phone runs them.
        MediaSave.policyForTests = null;
        Downloader.Result result = saveDash(video, audio);

        assertEquals(result.toString(), Downloader.Status.REFUSED, result.status);
        assertNothingWasCreated("a foreign DASH track", result);
    }

    private DashManifest.Track goodPicture() {
        byte[] picture = mp4(64_000);
        serve("/v.mp4", "video/mp4", picture, picture.length);
        return new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000, origin + "/v.mp4");
    }

    /**
     * The case above stops at the picture, so nothing held the sound track to the same checks.
     * The sound here is a good file on a second server this test's policy doesn't let through, so
     * a sound fetch that skipped the policy would get it and fail later, at the join.
     */
    @Test
    public void aSoundTrackOffMetasServersIsRefusedAfterAGoodPicture() throws IOException {
        DashManifest.Track video = goodPicture();
        try (LocalServer foreign = new LocalServer()) {
            byte[] sound = mp4(20_000);
            foreign.serve("/a.mp4", 200, "audio/mp4", sound, sound.length);
            server.redirect("/away.mp4", foreign.origin() + "/a.mp4");

            for (String url : new String[] { foreign.origin() + "/a.mp4", origin + "/away.mp4" }) {
                DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, url);
                Downloader.Result result = saveDash(video, audio);
                assertEquals(url + ": " + result, Downloader.Status.REFUSED, result.status);
                assertNothingWasCreated("a DASH save whose sound is at " + url, result);
            }
        }
    }

    /**
     * Each track used to get the whole cap, so a pair could join into a file over it. The sound
     * gets what the picture left now, and 64,000 plus 50,000 doesn't fit in 100,000.
     */
    @Test
    public void aDashPairOverTheCapIsRefusedBeforeItIsJoined() {
        DashManifest.Track video = goodPicture();
        byte[] sound = mp4(50_000);
        serve("/a.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");

        MediaSave.capForTests = 100_000;
        Downloader.Result result = saveDash(video, audio);

        assertEquals(result.toString(), Downloader.Status.TOO_LARGE, result.status);
        assertNothingWasCreated("a DASH pair over the cap", result);
    }

    /**
     * The case above holds the share from above only: a sound given nothing, or a picture given
     * half the cap, refused it just as well. So a pair that fits has to get through both fetches
     * (nothing describes them to Robolectric's extractor, so the join fails and the save ends
     * there), and a picture that takes the whole cap leaves the sound none.
     */
    @Test
    public void aDashPairThatFitsTheCapIsFetchedWhole() {
        byte[] picture = mp4(60_000);
        serve("/v60.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(40_000);
        serve("/a40.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v60.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a40.mp4");

        MediaSave.capForTests = 100_000;
        Downloader.Result result = saveDash(video, audio);

        assertEquals("BUILD_ERROR (the tracks could not be joined)", result.toString());
        assertEquals("the sound track wasn't fetched", 1, server.hits("/a40.mp4"));
        assertNothingWasCreated("a pair that fits, with nothing described to join", result);

        byte[] whole = mp4(100_000);
        serve("/v100.mp4", "video/mp4", whole, whole.length);
        DashManifest.Track wholeCap = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v100.mp4");
        result = saveDash(wholeCap, audio);
        assertEquals(result.toString(), Downloader.Status.TOO_LARGE, result.status);
        assertTrue(result.toString(), result.reason.endsWith("more than 0"));
        assertNothingWasCreated("a picture that took the whole cap", result);
    }

    /**
     * A DASH save is one save to the person watching it. Told of the sound track on its own, its
     * notification started over at the sound and gave that track's size as the whole save's; the
     * sound's bytes now count on from the picture's.
     */
    @Test
    public void aDashSavesProgressCountsOnThroughTheSoundTrack() {
        byte[] picture = mp4(60_000);
        serve("/v60.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(40_000);
        serve("/a40.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v60.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a40.mp4");
        List<long[]> told = new ArrayList<>();
        Downloader.Progress watching = new Downloader.Progress() {
            @Override
            public void transferred(long done, long total) {
                told.add(new long[]{done, total});
            }

            @Override
            public void reading(Runnable close) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };

        saveDash(video, audio, watching);

        assertFalse("nothing was reported", told.isEmpty());
        for (int i = 1; i < told.size(); i++) {
            assertTrue("the count went back from " + told.get(i - 1)[0] + " to " + told.get(i)[0],
                    told.get(i)[0] >= told.get(i - 1)[0]);
        }
        long[] last = told.get(told.size() - 1);
        assertEquals("the save's bytes", 100_000, last[0]);
        assertEquals("the save's size, not the sound track's", 100_000, last[1]);
    }

    /**
     * A 1,000-byte picture and a 1,000-byte sound, saved under a 10,000-byte cap so both fetches
     * fit, and joined. Robolectric's extractor reads no file: it answers with the samples a test
     * gives it for a path, and its muxer writes exactly those samples into the joined file, so the
     * joined size is the test's to choose. The work files get random names, so they're described
     * as the sound's address is checked. Both exist by then, and the join hasn't started.
     */
    private Downloader.Result saveAndJoin(byte[] pictureSample, byte[] soundSample) {
        byte[] picture = mp4(1_000);
        serve("/v.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(1_000);
        serve("/a.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");
        int port = server.port();
        MediaUrlPolicy describing = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/a.mp4")) describeWorkFiles(pictureSample, soundSample);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = describing;
        MediaSave.capForTests = 10_000;
        return saveDash(video, audio);
    }

    /** Each work file, found by the name DashSave starts it with, gets its one sample. */
    private void describeWorkFiles(byte[] pictureSample, byte[] soundSample) {
        for (File file : DashSave.workFolder(context).listFiles()) {
            DataSource path = DataSource.toDataSource(file.getPath());
            if (file.getName().startsWith("video")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createVideoFormat("video/avc", 1280, 720), pictureSample);
            } else if (file.getName().startsWith("audio")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2),
                        soundSample);
            }
        }
    }

    /** [size] bytes of [fill], so the joined file shows which track each byte came from. */
    private static byte[] sample(int size, char fill) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    /**
     * Every DASH case above ends before the gallery, so nothing checked what a finished join
     * publishes. This pair fits and is joined, and the gallery gets the joined file: the picture's
     * sample, then the sound's. Robolectric puts every sample at time zero, and the join takes the
     * picture on a tie.
     */
    @Test
    public void aDashPairThatFitsTheCapIsJoinedAndPublished() {
        byte[] picture = sample(1_000, 'v');
        byte[] sound = sample(1_000, 'a');

        Downloader.Result result = saveAndJoin(picture, sound);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, gallery.inserts.size());
        ContentValues row = gallery.rows.get(1L);
        assertEquals("the row was left pending", Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("video/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(picture, 0, picture.length);
        joined.write(sound, 0, sound.length);
        assertArrayEquals("the gallery didn't get the joined file", joined.toByteArray(), published.toByteArray());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    /**
     * Joining writes boxes of its own, so a pair whose fetches fit the cap can join into a file
     * over it. These are the fetches above, and their samples join into 12,000 bytes, which must
     * not reach the gallery.
     */
    @Test
    public void aDashPairWhoseJoinedFileRunsPastTheCapIsRefused() {
        Downloader.Result result = saveAndJoin(sample(6_000, 'v'), sample(6_000, 'a'));

        assertEquals("TOO_LARGE (the joined file is 12000 bytes, more than 10000)", result.toString());
        assertNothingWasCreated("a pair whose joined file ran past the cap", result);
    }

    /**
     * The cap is a limit and not a margin, on both sides of it: a joined file of exactly 10,000
     * bytes reaches the gallery, and one byte more doesn't. The case above, 2,000 bytes over,
     * couldn't tell the cap from a bound off by a byte or two.
     */
    @Test
    public void aJoinedFileExactlyAtTheCapIsPublished() {
        Downloader.Result result = saveAndJoin(sample(5_000, 'v'), sample(5_000, 'a'));

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(10_000, published.size());
    }

    @Test
    public void aJoinedFileOneByteOverTheCapIsRefused() {
        Downloader.Result result = saveAndJoin(sample(5_001, 'v'), sample(5_000, 'a'));

        assertEquals("TOO_LARGE (the joined file is 10001 bytes, more than 10000)", result.toString());
        assertNothingWasCreated("a joined file one byte over the cap", result);
    }

    /**
     * Two saves that start together both find no work folder yet, and the one whose mkdirs()
     * comes second is told false because the other just made it. That save used to end "no cache
     * folder". Each round removes the folder and lets several saves ask for it at once.
     */
    @Test
    public void savesStartingTogetherAllGetTheWorkFolder() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 300; round++) {
                File folder = DashSave.workFolder(context);
                assertNotNull(folder);
                assertTrue("round " + round + " couldn't clear the folder", folder.delete());

                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<File>> asked = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    asked.add(pool.submit(() -> {
                        go.await();
                        return DashSave.workFolder(context);
                    }));
                }
                go.countDown();
                for (java.util.concurrent.Future<File> answer : asked) {
                    assertNotNull("a save in round " + round + " got no work folder", answer.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The list of pending rows is what removes a row a stopped save leaves, so a row it can't hold
     * would be nobody's to remove. A commit that answers false, and one that throws, each stop the
     * save before a byte is copied, and the row goes again. A finished file of an earlier save,
     * already in the gallery, is left as it was.
     */
    @Test
    public void aRowTheListCannotHoldIsRemovedBeforeAByteIsCopied() {
        assertAnUnlistedRowStopsTheSave(false);
    }

    @Test
    public void aListThatThrowsStopsTheSaveTheSameWay() {
        assertAnUnlistedRowStopsTheSave(true);
    }

    private void assertAnUnlistedRowStopsTheSave(boolean throwing) {
        finishedRow();
        ContentValues earlier = new ContentValues(gallery.rows.get(1L));
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), copied);
        byte[] body = mp4(4096);
        serve("/v.mp4", "video/mp4", body, body.length);

        Downloader.Result result = save("/v.mp4", new MediaStoreWriter(new BrokenLedger(context, throwing), true));

        assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
        assertEquals("bytes were copied into a row the list doesn't hold", 0, copied.size());
        assertEquals(2, gallery.inserts.size());
        assertFalse("the unlisted row was left in the gallery", gallery.rows.containsKey(2L));
        assertEquals("the earlier finished file was changed", earlier, gallery.rows.get(1L));
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    /** When the gallery won't take the unlisted row back either, the report says so. */
    @Test
    public void anUnlistedRowTheGalleryKeepsIsReported() {
        gallery.refuseDeletion = true;
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), copied);
        byte[] body = mp4(4096);
        serve("/v.mp4", "video/mp4", body, body.length);

        Downloader.Result result = save("/v.mp4", new MediaStoreWriter(new BrokenLedger(context, false), true));

        assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
        assertEquals(0, copied.size());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("ERROR | the gallery kept an unfinished entry that isn't on the list of "
                + "pending rows"));
    }

    /** The person saving is told the save failed, never that it was saved. */
    @Test
    public void aSaveWhoseRowCannotBeListedEndsAsAFailedSave() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/v.mp4", "video/mp4", body, body.length);
        Context broken = new BrokenLedger(context, false);

        Thread worker = MediaSave.start(broken, true, MediaSave.fileJob(broken, origin + "/v.mp4",
                Downloader.Kind.VIDEO));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // The ledger that refuses the row refuses the save's own job record first, so the save
        // ends before any fetch, with the plain failure rather than a fetch or write status's hint.
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
        assertTrue("the unlisted row was left in the gallery", gallery.rows.isEmpty());
        assertEquals(0, published.size());
    }

    /**
     * A failed save says what to check when the failure tells: the connection, or that and free
     * storage. Nothing to save, or a file that couldn't be put together, asks for neither.
     */
    @Test
    public void aFailedSaveSaysWhatToCheckWhenItKnows() {
        assertEquals("Download failed. Check your connection and try again.",
                MediaSave.message(context, Downloader.Status.NETWORK_ERROR, null, false));
        assertEquals("Download didn't finish. Check your connection and free storage, then try again.",
                MediaSave.message(context, Downloader.Status.WRITE_ERROR, null, false));
        assertEquals("the server's own refusal", "Download failed",
                MediaSave.message(context, Downloader.Status.HTTP_ERROR, null, false));
        assertEquals("nothing to save, or tracks that wouldn't join", "Download failed",
                MediaSave.message(context, Downloader.Status.BUILD_ERROR, null, false));
    }

    /**
     * A DASH save whose tracks couldn't be fetched falls back to the single file, which is below the
     * picture the manifest offered. The person saving is told it's lower than on Instagram, not just
     * that it was saved; a single file that was simply the pick is told nothing more. The "lower"
     * note is weighed against what the saved file actually measures, so the policy here tells the
     * shadow extractor what the fetched file holds, the way a real save's own read of it would.
     */
    @Test
    public void aSaveBelowTheManifestsPictureSaysSoWhenItEnds() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/clip_360p.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1080, 1920, 3_000_000,
                origin + "/gone.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_360p.mp4")) describeSavedVideoWorkFile(640, 360);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_360p.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && toast.endsWith(" in lower quality than on Instagram"));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("below the manifest's video/mp4 avc1.64001f 1080x1920 3000kbps 1080p, "
                + "the best it offers within the Download quality"));

        ShadowToast.reset();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2),
                new ByteArrayOutputStream());
        Thread plain = MediaSave.start(context, true, MediaSave.fileJob(context, origin + "/clip_360p.mp4",
                Downloader.Kind.VIDEO));
        plain.join(30_000);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("lower quality"));
    }

    /**
     * The single-file path used to weigh the report's "below the manifest's" note only against a
     * guess read off the chosen address ({@link RenditionPicker#qualityOf}), before anything was
     * fetched. Here the fallback's address claims 1080p, exactly what the manifest's own track
     * states too, so that guess-based compare found nothing above it and the note never appeared,
     * even though the file that's actually fetched measures 360p. The old assertion below pinned
     * that gap as if it were correct; the note must appear, built from what the file measures, not
     * the address it happened to be named after.
     */
    @Test
    public void aMisleadingFileNameDoesNotHideAShortfall() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/clip_1080p.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "vp09.00.40.08", 1080, 1920, 2_000_000,
                origin + "/vp9.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_1080p.mp4")) describeSavedVideoWorkFile(640, 360);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_1080p.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && toast.endsWith(" in lower quality than on Instagram"));
        String report = LogBufferManager.buildExportText();
        assertTrue("the report now measures the saved file instead of trusting the address it was fetched from",
                report.contains("below the manifest's video/mp4 vp09.00.40.08 1080x1920 2000kbps 1080p"));
    }

    /**
     * A DASH save of a writable H.264 track can fail for reasons that have nothing to do with what
     * the phone can write, such as a dropped connection ({@link #aSaveBelowTheManifestsPictureSaysSoWhenItEnds}).
     * The fallback single file here measures exactly two thirds of that track's picture, the exact
     * boundary {@link MediaSave#noticeablyLower} leaves alone for a picture nothing could have
     * written. That tolerance doesn't apply here: the phone could write the 1080p track, so the
     * person saving is told regardless of where the boundary falls.
     */
    @Test
    public void aWritableTracksShortfallIsToldEvenAtTheTwoThirdsBoundary() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/clip_720p.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1080, 1920, 3_000_000,
                origin + "/gone.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_720p.mp4")) describeSavedVideoWorkFile(1280, 720);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_720p.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && toast.endsWith(" in lower quality than on Instagram"));
    }

    /**
     * A file the phone couldn't read back measures 0 on its short side ({@link
     * DashSave#savedVideoShortSide}). The writable-track check used to compare that straight
     * against the track's picture, which is always above zero, so an unreadable save was told it
     * fell short of the app's picture no matter what it actually held.
     */
    @Test
    public void anUnreadableSavedFileIsNotToldLower() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/clip_unread.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1080, 1920, 3_000_000,
                origin + "/gone.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                // Deliberately no describeSavedVideoWorkFile call: the shadow extractor knows
                // nothing of this file, the way it wouldn't for one it truly couldn't read.
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_unread.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("lower quality"));
    }

    /**
     * A saved file that measures a couple of pixels short of a writable track's picture, such as a
     * 1080x1920 track against a saved 1920x1078 file, is a rounding or a container quirk, not a real
     * shortfall: the same gap {@link MediaSave#noticeablyLower} tolerates for a picture nothing
     * could have written applies here too, held to a plain pixel margin instead of a fraction.
     */
    @Test
    public void aFewPixelsShortOfAWritableTrackIsNotTold() throws InterruptedException {
        byte[] body = mp4(4096);
        serve("/clip_close.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1080, 1920, 3_000_000,
                origin + "/gone.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_close.mp4")) describeSavedVideoWorkFile(1920, 1078);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_close.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("lower quality"));
    }

    /**
     * At a 480p ceiling, a manifest whose H.264 tracks are all above it picks the nearest one over,
     * 540p here. The single file fits the ceiling and the picked track doesn't, so the single file
     * wins and the 540p track becomes the writable track the fallback is judged against. A save that
     * is exactly what the ceiling asks for must not be told it's lower just because 540 outranks the
     * measured 360.
     */
    @Test
    public void aWritableTrackOverTheCeilingIsNotTold() throws InterruptedException {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P480);
        byte[] body = mp4(4096);
        serve("/clip_360p.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 960, 1920, 3_000_000,
                origin + "/gone.mp4", 540);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_360p.mp4")) describeSavedVideoWorkFile(640, 360);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_360p.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("lower quality"));
    }

    /**
     * Below the best quality, a track's picture is its label, not its measured picture ({@link
     * MediaSave#picture}). An ultrawide track can carry a label far above its own short side: a
     * 1280x536 track labelled 720p measures 536 on its short side, the same as a save that actually
     * holds that picture. Judging the writable check by the label rather than the pixels told a save
     * of exactly that picture it was lower.
     */
    @Test
    public void anUltrawideLabelDoesNotOutrankItsOwnMeasuredPicture() throws InterruptedException {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        byte[] body = mp4(4096);
        serve("/clip_wide.mp4", "video/mp4", body, body.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 536, 1_500_000,
                origin + "/gone.mp4", 720);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_wide.mp4")) describeSavedVideoWorkFile(1280, 536);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_wide.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && !toast.contains("lower quality"));
    }

    /**
     * Gives the next single-file save's temp video the measured size a real save reads back, the
     * way {@link #describeWorkFiles} does for a join's own work files.
     */
    private void describeSavedVideoWorkFile(int width, int height) {
        File[] files = DashSave.workFolder(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().startsWith("video")) {
                ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.getPath()),
                        MediaFormat.createVideoFormat("video/avc", width, height), new byte[0]);
            }
        }
    }

    /** A save the list can hold has its row on it before the first byte, as a stopped save needs. */
    @Test
    public void aGoodSaveListsItsRowBeforeTheFirstByte() {
        List<java.util.Set<String>> listedAtFirstByte = new ArrayList<>();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), new OutputStream() {
            @Override public void write(int value) {
                write(new byte[] { (byte) value }, 0, 1);
            }

            @Override public void write(byte[] bytes, int offset, int length) {
                if (listedAtFirstByte.isEmpty()) listedAtFirstByte.add(pendingList());
                published.write(bytes, offset, length);
            }
        });
        byte[] body = mp4(4096);
        serve("/v.mp4", "video/mp4", body, body.length);

        Downloader.Result result = save("/v.mp4");

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(java.util.Collections.singletonList(java.util.Collections.singleton(gallery.videoUri(1).toString())),
                listedAtFirstByte);
        assertTrue("the published row is still listed", pendingList().isEmpty());
    }

    private java.util.Set<String> pendingList() {
        return new java.util.HashSet<>(context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new java.util.HashSet<>()));
    }

    /** A row a finished save published. */
    private void finishedRow() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "IG_VID_20260925_010203.mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    /**
     * The application, with a list of pending rows whose commit answers false, or throws when
     * [throwing]: a full disk, or storage that went read-only.
     */
    private static final class BrokenLedger extends ContextWrapper {
        private final boolean throwing;

        BrokenLedger(Context base, boolean throwing) {
            super(base);
            this.throwing = throwing;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            SharedPreferences real = super.getSharedPreferences(name, mode);
            if (!"hushgram_saves".equals(name)) return real;
            ClassLoader loader = SharedPreferences.class.getClassLoader();
            return (SharedPreferences) Proxy.newProxyInstance(loader, new Class<?>[] { SharedPreferences.class },
                    (preferences, method, args) -> {
                        Object answer = method.invoke(real, args);
                        if (!method.getName().equals("edit")) return answer;
                        SharedPreferences.Editor editor = (SharedPreferences.Editor) answer;
                        return Proxy.newProxyInstance(loader, new Class<?>[] { SharedPreferences.Editor.class },
                                (edit, call, given) -> {
                                    if (call.getName().equals("commit")) {
                                        if (throwing) throw new IllegalStateException("the disk is full");
                                        return false;
                                    }
                                    Object result = call.invoke(editor, given);
                                    return result == editor ? edit : result;
                                });
                    });
        }
    }

    /** MediaStore's video and image tables, as much of them as a save touches. */
    public static final class Gallery extends ContentProvider {
        final Map<Long, ContentValues> rows = new HashMap<>();
        final List<Uri> inserts = new ArrayList<>();
        /** Every new entry is turned down, the way a full or locked MediaStore does. */
        boolean refuseInsert;
        boolean refuseUpdate;
        boolean refuseDeletion;
        private long nextId = 1;

        Uri videoUri(long id) {
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            if (refuseInsert) return null;
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            Uri item = ContentUris.withAppendedId(uri, id);
            inserts.add(item);
            return item;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return new MatrixCursor(projection == null ? new String[0] : projection);
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            if (refuseUpdate) return 0;
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            if (refuseDeletion) return 0;
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }
    /**
     * The DASH tracks have no room to be fetched, so the save falls back to a smaller single file.
     * The toast names low storage as the reason, not just the lower quality (#348). A fallback that
     * had room for the tracks keeps the plain lower-quality line.
     */
    @Test
    public void aLowerSaveForWantOfStorageSaysStorageIsLow() throws InterruptedException {
        serve("/track.mp4", "video/mp4", mp4(4096), 4096);
        byte[] small = mp4(64);
        serve("/clip_360p.mp4", "video/mp4", small, small.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1080, 1920, 3_000_000,
                origin + "/track.mp4", 1080);
        int port = server.port();
        MediaUrlPolicy measuring = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/clip_360p.mp4")) describeSavedVideoWorkFile(640, 360);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        MediaSave.policyForTests = measuring;
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 2000;

        Thread worker = MediaSave.start(context, true, MediaSave.dashJob(context, video, null,
                origin + "/clip_360p.mp4"));
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, gallery.inserts.size());
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.startsWith("Saved to ") && toast.endsWith(" in lower quality because storage is low"));
    }

    @Test
    public void theMessageNamesLowStorageOnlyForALowerSave() {
        assertTrue(MediaSave.message(context, Downloader.Status.OK, "Movies", true, true).endsWith(" in lower quality because storage is low"));
        assertEquals("Saved to the gallery in lower quality because storage is low",
                MediaSave.message(context, Downloader.Status.OK, null, true, true));
        assertTrue(MediaSave.message(context, Downloader.Status.OK, "Movies", true, false).endsWith(" in lower quality than on Instagram"));
        assertFalse(MediaSave.message(context, Downloader.Status.OK, "Movies", false, true).contains("lower quality"));
    }

}
