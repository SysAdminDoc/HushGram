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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.ContentValues;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/** Batch ownership, ordered real transfers, snapshots and cleanup were absent from single-save coverage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = {CarouselSaveTest.LocalCandidates.class, CarouselSaveTest.MediaBridge.class})
public class CarouselSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Context context;
    private LocalServer server;
    private MediaSaveTest.Gallery gallery;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<ByteArrayOutputStream> files = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        server = new LocalServer();
        LocalCandidates.origin = server.origin();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("10.9.8.7")}) {
            @Override Refusal refusal(URL url) {
                return url.toString().startsWith(server.origin() + "/") ? null : super.refusal(url);
            }
        };
        gallery = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        for (int id = 1; id <= 4; id++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            files.add(bytes);
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(id), bytes);
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), bytes);
        }
        SaveLeftovers.forgetSweepForTests();
        Settings.DOWNLOAD_PHOTOS.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() throws Exception {
        release.countDown();
        for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
        SavesForTests.endAll();
        waitForSaves();
        server.close();
        MediaSave.policyForTests = null;
        MediaSave.detailsForTests = null;
        Downloader.readTimeoutMs = 20_000;
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.DOWNLOAD_COMPATIBLE.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
        MediaBridge.post = null;
        MediaBridge.poster = null;
        MediaBridge.postedAt = null;
        MediaBridge.cover = null;
        Settings.DOWNLOAD_FEED_COVER.resetToDefault();
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static byte[] body(boolean video) {
        byte[] bytes = new byte[4096];
        byte[] head = video ? new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}
                : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private MediaSave.Item page(boolean video, String path, String id) {
        server.serve(path, video ? "video/mp4" : "image/jpeg", body(video));
        return new MediaSave.Item(video, SavesForTests.renditions(server.origin() + path), null, PostDetails.of(id));
    }

    private void waitForSaves() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (MediaSave.savesInFlight() != 0) {
            assertTrue("the save never ended", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void assertClean() {
        assertTrue("a pending row survived", context.getSharedPreferences("hushgram_saves", 0)
                .getStringSet("pending_rows", new HashSet<>()).isEmpty());
        assertTrue("a logical job survived", active().isEmpty());
        File[] work = DashSave.workFolder(context).listFiles();
        assertNotNull(work);
        assertEquals("temporary files survived", 0, work.length);
        assertTrue("Cancel outlived the batch", SaveControl.running().isEmpty());
    }

    private Set<String> active() {
        return new HashSet<>(context.getSharedPreferences("hushgram_saves", 0)
                .getStringSet("active_jobs", new HashSet<>()));
    }

    @Test public void aMixedBatchPreservesOrderSnapshotsAndQuality() throws Exception {
        MediaSave.Item first = page(false, "/first.jpg", "101");
        MediaSave.Item last = page(false, "/last.jpg", "103");
        server.serve("/720.mp4", "video/mp4", body(true));
        server.serve("/1080.mp4", "video/mp4", body(true));
        List<MediaSave.Rendition> video = new ArrayList<>(Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/1080.mp4", 1080, 1920, 0),
                new MediaSave.Rendition(server.origin() + "/720.mp4", 720, 1280, 0)));
        MediaSave.Item middle = new MediaSave.Item(true, video, null, PostDetails.of("102"));
        List<MediaSave.Item> pages = new ArrayList<>(Arrays.asList(first, middle, last));
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<Integer> controls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1);
        MediaSave.detailsForTests = details -> {
            assertEquals("a nested logical job was created", 1, active().size());
            assertEquals("a nested in-flight slot was created", 1, MediaSave.savesInFlight());
            order.add(details.videoId);
            controls.add(SaveControl.running().get(0).id);
            if (order.size() == 1) { entered.countDown(); await(release); }
        };
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, pages, ended::complete));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        pages.clear(); video.clear();
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.BEST);
        release.countDown();
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        waitForSaves();
        assertEquals(Arrays.asList("101", "102", "103"), order);
        assertEquals(1, new HashSet<>(controls).size());
        assertEquals(3, result.saved); assertEquals(0, result.failed); assertEquals(0, result.skipped);
        assertFalse(result.cancelled);
        assertEquals(1, server.hits("/720.mp4")); assertEquals(0, server.hits("/1080.mp4"));
        assertEquals(Arrays.asList("image/jpeg", "video/mp4", "image/jpeg"), Arrays.asList(
                gallery.rows.get(1L).getAsString(MediaStore.MediaColumns.MIME_TYPE),
                gallery.rows.get(2L).getAsString(MediaStore.MediaColumns.MIME_TYPE),
                gallery.rows.get(3L).getAsString(MediaStore.MediaColumns.MIME_TYPE)));
        for (android.content.ContentValues row : gallery.rows.values()) assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("Saved 3. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        assertEquals("Saved 3. Failed 0. Skipped 0.", SaveControl.batchOutcome());
        assertClean();
    }

    @Test public void failedAndDisabledPagesHaveExactCountsAndNoRetry() throws Exception {
        MediaSave.Item first = page(false, "/good.jpg", "1");
        MediaSave.Item broken = new MediaSave.Item(true, SavesForTests.renditions(server.origin() + "/gone.mp4"), null, null);
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(first, broken, null), ended::complete));
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        waitForSaves();
        assertEquals(1, result.saved); assertEquals(1, result.failed); assertEquals(1, result.skipped);
        assertEquals(1, server.hits("/gone.mp4"));
        assertEquals("Saved 1. Failed 1. Skipped 1.", ShadowToast.getTextOfLatestToast());
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains(server.origin()));
        assertFalse(report, report.contains("/gone.mp4"));
        assertClean();
    }

    @Test public void tooManyPagesAreRejectedAndTheExactLimitIsAccepted() throws Exception {
        assertFalse(MediaSave.saveBatch(context, Collections.nCopies(33, page(true, "/unused.mp4", "1")), null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Not saved: a carousel can have at most 32 pages", ShadowToast.getTextOfLatestToast());
        assertEquals(0, server.hits("/unused.mp4"));
        assertEquals(0, MediaSave.savesInFlight());
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Collections.nCopies(32, null), ended::complete));
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        assertEquals(32, result.skipped); assertEquals(0, result.saved); assertEquals(0, result.failed);
        assertClean();
    }

    @Test public void oneCancelStopsTheTransferAndAllRemainingPages() throws Exception {
        Downloader.readTimeoutMs = 500;
        MediaSave.Item first = page(false, "/first.jpg", "1");
        MediaSave.Item second = new MediaSave.Item(true, SavesForTests.renditions(server.origin() + "/held.mp4"), null, null);
        MediaSave.Item last = page(false, "/last.jpg", "3");
        server.serveHeld("/held.mp4", "video/mp4", body(true), 256 * 1024, 64 * 1024, release);
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(first, second, last), ended::complete));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        SaveControl.Running shown;
        do {
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(10);
            shown = SaveControl.running().get(0);
        } while (shown.batchPage != 2 || shown.done < 64 * 1024);
        assertEquals("Saving a carousel", SaveControl.title(shown));
        assertTrue(SaveControl.status(shown).startsWith("Page 2 of 3\n"));
        assertEquals(1, active().size());
        assertTrue(SaveControl.cancel(shown.id));
        MediaSave.BatchResult result = ended.get(10, TimeUnit.SECONDS);
        release.countDown();
        waitForSaves();
        assertTrue(result.cancelled);
        assertEquals(1, result.saved); assertEquals(0, result.failed); assertEquals(2, result.skipped);
        assertEquals(0, server.hits("/last.jpg"));
        assertEquals(1, gallery.rows.size());
        assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("Carousel cancelled. Saved 1. Failed 0. Skipped 2.", ShadowToast.getTextOfLatestToast());
        assertEquals("Carousel cancelled. Saved 1. Failed 0. Skipped 2.", SaveControl.batchOutcome());
        assertClean();
    }

    @Test public void cancelBeforeCommitRemovesThePendingRowAndKeepsEarlierFiles() throws Exception {
        MediaSave.Item first = page(false, "/first.jpg", "1");
        MediaSave.Item middle = page(true, "/middle.mp4", "2");
        MediaSave.Item last = page(false, "/last.jpg", "3");
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), new OutputStream() {
            @Override public void write(int value) {}
            @Override public void flush() {
                assertEquals(2, gallery.rows.size());
                assertEquals(Integer.valueOf(1), gallery.rows.get(2L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
                assertTrue(SaveControl.cancel(SaveControl.running().get(0).id));
            }
        });
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(first, middle, last), ended::complete));
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        assertEquals(1, result.saved); assertEquals(0, result.failed); assertEquals(2, result.skipped);
        assertTrue(result.cancelled); assertEquals(0, server.hits("/last.jpg"));
        assertEquals(1, gallery.rows.size());
        assertTrue(gallery.rows.containsKey(1L));
        assertClean();
    }

    @Test public void aBatchUsesOneSlotAndNeverQueuesPastTheExistingLimit() throws Exception {
        CountDownLatch singles = new CountDownLatch(2);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) workers.add(MediaSave.start(context, true, (writer, progress) -> {
            singles.countDown(); await(release);
            return Downloader.Result.fail(Downloader.Status.CANCELLED, "test ended");
        }));
        assertTrue(singles.await(10, TimeUnit.SECONDS));
        MediaSave.Item held = new MediaSave.Item(true, SavesForTests.renditions(server.origin() + "/held.mp4"), null, null);
        server.serveHeld("/held.mp4", "video/mp4", body(true), 256 * 1024, 64 * 1024, release);
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(held, held), ended::complete));
        assertEquals(3, MediaSave.savesInFlight());
        assertFalse(MediaSave.saveBatch(context, Arrays.asList(held, held), null));
        assertFalse(MediaSave.saveVideo(context, SavesForTests.renditions(server.origin() + "/held.mp4"), null, null));
        release.countDown();
        ended.get(20, TimeUnit.SECONDS);
        for (Thread worker : workers) worker.join(10_000);
        waitForSaves();
        assertEquals("a refused batch was queued", 2, server.hits("/held.mp4"));
        assertClean();
    }

    @Test public void currentPageDownloadAndSaveAllReadDifferentSnapshots() throws Exception {
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"), page(false, "/last.jpg", "3"));
        assertTrue(VideoDownload.save(MediaBridge.post, 1, null));
        waitForSaves();
        assertEquals(1, server.hits("/middle.mp4")); assertEquals(0, server.hits("/first.jpg"));
        VideoDownload.saveAll(MediaBridge.post, null);
        waitForSaves();
        assertEquals(2, server.hits("/middle.mp4")); assertEquals(1, server.hits("/first.jpg")); assertEquals(1, server.hits("/last.jpg"));
        assertEquals("Saved 3. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        assertClean();
    }

    /**
     * A carousel's Download in the Reels viewer saved its first page whatever page was on screen
     * (#78), since its own picture is the first page's. Every page saves now, in order, and the
     * feed's photo and video switches, which belong to Download any video, don't leave any out.
     */
    @Test public void theReelsViewerSavesEveryPageOfACarousel() throws Exception {
        Settings.DOWNLOAD_PHOTOS.save(false);
        Settings.DOWNLOAD_VIDEOS.save(false);
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"), page(false, "/last.jpg", "3"));
        assertTrue(ReelDownload.saveReel(context, MediaBridge.post));
        waitForSaves();
        assertEquals(1, server.hits("/first.jpg")); assertEquals(1, server.hits("/middle.mp4")); assertEquals(1, server.hits("/last.jpg"));
        assertEquals("Saved 3. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        assertClean();
    }

    /**
     * Download cover failed on a reel whose only cover address names its size (#79), which the
     * photo save takes for a thumbnail's and turns down. The cover's sizes are all of the one
     * picture, so the largest the reel states saves.
     */
    @Test public void aReelsCoverSavesWhenItsAddressNamesItsSize() throws Exception {
        server.serve("/small.jpg", "image/jpeg", body(false));
        server.serve("/cover.jpg", "image/jpeg", body(false));
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/small.jpg?stp=dst-jpg_e15_s150x150_tt6", 150, 266, 0),
                new MediaSave.Rendition(server.origin() + "/cover.jpg?stp=dst-jpg_e15_p540x540_tt6", 540, 960, 0)),
                null, PostDetails.of("7"));
        assertNull("the photo save's ranking let a sized address in", RenditionPicker.pickImage(reel.renditions));
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/cover.jpg")); assertEquals(0, server.hits("/small.jpg"));
        assertEquals(1, gallery.rows.size());
        assertClean();
    }

    /**
     * Download cover on a feed post (#94) saves the picture shown before the video on screen plays,
     * a carousel page's here, at the largest size it states, even with addresses that name their
     * sizes, as a reel's cover does. Neither the video nor another page is fetched.
     */
    @Test public void aFeedVideosCoverSavesAtItsLargestSize() throws Exception {
        Settings.DOWNLOAD_FEED_COVER.save(true);
        server.serve("/small.jpg", "image/jpeg", body(false));
        server.serve("/cover.jpg", "image/jpeg", body(false));
        MediaBridge.cover = Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/small.jpg?stp=dst-jpg_e15_s150x150_tt6", 150, 266, 0),
                new MediaSave.Rendition(server.origin() + "/cover.jpg?stp=dst-jpg_e15_p540x540_tt6", 540, 960, 0));
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"));
        VideoDownload.saveCover(MediaBridge.post, 1, null);
        waitForSaves();
        assertEquals(1, server.hits("/cover.jpg")); assertEquals(0, server.hits("/small.jpg"));
        assertEquals("the video isn't its cover", 0, server.hits("/middle.mp4"));
        assertEquals(0, server.hits("/first.jpg"));
        assertEquals(1, gallery.rows.size());
        assertClean();
    }

    /** The names the saves took, photos and videos together, sorted. Android 9 has them as files, later ones as rows. */
    private List<String> savedNames() {
        List<String> names = new ArrayList<>();
        if (MediaStoreWriter.legacyStorage()) {
            for (String directory : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES}) {
                String[] saved = new File(Environment.getExternalStoragePublicDirectory(directory), "Instagram").list();
                if (saved != null) names.addAll(Arrays.asList(saved));
            }
        } else {
            for (ContentValues row : gallery.rows.values()) names.add(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Name saves by account and post time (#20), on Android 9's own folders and on today's
     * MediaStore: Save all names every page for the account and the post's time with its page
     * number, so no two pages of one post share a name, and the page on screen saved on its own
     * carries its number too.
     */
    @Test @Config(sdk = {28, 37})
    public void namesByPostNumberEveryCarouselPage() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        for (String directory : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES}) {
            File[] old = new File(Environment.getExternalStoragePublicDirectory(directory), "Instagram").listFiles();
            if (old != null) for (File file : old) assertTrue(file.delete());
        }
        Settings.SAVE_NAME_BY_POST.save(true);
        Calendar noon = new GregorianCalendar();
        noon.clear();
        noon.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        MediaBridge.poster = "stevi.ous";
        MediaBridge.postedAt = noon.getTimeInMillis() / 1000L;
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"), page(false, "/last.jpg", "3"));

        VideoDownload.saveAll(MediaBridge.post, null);
        waitForSaves();
        assertEquals("Saved 3. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        String base = "stevi.ous_20260901_120000";
        assertEquals(Arrays.asList(base + "_1.jpg", base + "_2.mp4", base + "_3.jpg"), savedNames());

        assertEquals(3, VideoDownload.details(MediaBridge.post.get(2), MediaBridge.post).page);
        assertEquals(0, VideoDownload.details(MediaBridge.post, MediaBridge.post).page);
        assertEquals(0, VideoDownload.pageOf(page(false, "/elsewhere.jpg", "9"), MediaBridge.post));
        assertClean();
    }

    @Test public void theSeparateMenuActionUsesItsLabelAndKeepsNativeOptionsIntact() {
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"));
        ArrayList<Object> rows = new ArrayList<>();
        VideoDownload.offerAll(new Object(), rows);
        VideoDownload.offer(new Object(), rows);
        assertEquals(Arrays.asList(MediaBridge.ALL, MediaBridge.DOWNLOAD), rows);
        assertEquals("Save all", MediaBridge.label.toString());
        Object report = new Object();
        List<Object> nativeOptions = Collections.unmodifiableList(Arrays.asList(MediaBridge.DOWNLOAD, report));
        List<?> allowed = VideoDownload.allow(nativeOptions, MediaBridge.DOWNLOAD);
        assertEquals(Arrays.asList(MediaBridge.DOWNLOAD, MediaBridge.ALL, report), allowed);
        assertEquals(Arrays.asList(MediaBridge.DOWNLOAD, report), nativeOptions);
        assertSame(allowed, VideoDownload.allow(allowed, MediaBridge.DOWNLOAD));
        Settings.DOWNLOAD_VIDEOS.save(false); Settings.DOWNLOAD_PHOTOS.save(false);
        assertSame(nativeOptions, VideoDownload.allow(nativeOptions, MediaBridge.DOWNLOAD));
    }

    /** On a phone an all-photo carousel with the photo switch off offered Save all, which then saved nothing. */
    @Test public void saveAllIsOfferedOnlyWhenAPageWouldSave() {
        ArrayList<Object> rows = new ArrayList<>();
        MediaBridge.post = Arrays.asList(page(false, "/a.jpg", "1"), page(false, "/b.jpg", "2"));
        Settings.DOWNLOAD_PHOTOS.save(false);
        VideoDownload.offerAll(new Object(), rows);
        assertTrue("photos off, yet an all-photo carousel offered Save all", rows.isEmpty());
        Settings.DOWNLOAD_PHOTOS.save(true);
        VideoDownload.offerAll(new Object(), rows);
        assertEquals(Collections.singletonList(MediaBridge.ALL), rows);

        rows.clear();
        MediaBridge.post = Arrays.asList(page(false, "/c.jpg", "3"), page(true, "/d.mp4", "4"));
        Settings.DOWNLOAD_PHOTOS.save(false);
        VideoDownload.offerAll(new Object(), rows);
        assertEquals("its video page still saves", Collections.singletonList(MediaBridge.ALL), rows);

        rows.clear();
        MediaBridge.post = Arrays.asList(page(true, "/e.mp4", "5"), page(true, "/f.mp4", "6"));
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.DOWNLOAD_PHOTOS.save(true);
        VideoDownload.offerAll(new Object(), rows);
        assertTrue("a video's cover isn't a photo to save", rows.isEmpty());
        int fetched = 0;
        for (String path : Arrays.asList("/a.jpg", "/b.jpg", "/c.jpg", "/d.mp4", "/e.mp4", "/f.mp4")) fetched += server.hits(path);
        assertEquals("nothing was fetched to decide", 0, fetched);
    }

    @Test public void exhaustedPreferenceRetirementDoesNotTurnTheBatchIntoAnInterruption() throws Exception {
        Context wrapped = withRetirementFailure();
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(wrapped, Collections.nCopies(3, null), ended::complete));
        assertEquals(3, ended.get(20, TimeUnit.SECONDS).skipped);
        assertTerminalOutcomeSurvivesRetirement();
    }

    @Test public void cancellationSurvivesExhaustedPreferenceRetirementToo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        MediaSave.detailsForTests = details -> { entered.countDown(); await(release); };
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        MediaSave.Item first = page(false, "/unused.jpg", "1");
        assertTrue(MediaSave.saveBatch(withRetirementFailure(), Arrays.asList(first, first, first), ended::complete));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertTrue(SaveControl.cancel(SaveControl.running().get(0).id));
        release.countDown();
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        assertTrue(result.cancelled); assertEquals(3, result.skipped);
        assertEquals(0, result.saved); assertEquals(0, result.failed);
        assertEquals(0, server.hits("/unused.jpg"));
        assertTerminalOutcomeSurvivesRetirement();
    }

    private Context withRetirementFailure() {
        SharedPreferences original = context.getSharedPreferences("hushgram_saves", 0);
        SharedPreferences faulty = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                    Object value = method.invoke(original, args);
                    if (!method.getName().equals("edit")) return value;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) value;
                    boolean[] retires = {false};
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class},
                            (wrapped, call, parameters) -> {
                                if (call.getName().equals("remove") && "active_jobs".equals(parameters[0])) retires[0] = true;
                                if (call.getName().equals("commit") && retires[0]) return false;
                                Object answer = call.invoke(editor, parameters);
                                return answer == editor ? wrapped : answer;
                            });
                });
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("hushgram_saves") ? faulty : super.getSharedPreferences(name, mode);
            }
        };
    }

    private void assertTerminalOutcomeSurvivesRetirement() throws Exception {
        assertEquals("nested page markers were persisted", 1, active().size());
        File[] markers = new File(context.getFilesDir(), "hushgram-save-outcomes").listFiles();
        assertNotNull(markers); assertEquals(1, markers.length);
        assertArrayEquals(new byte[]{1}, java.nio.file.Files.readAllBytes(markers[0].toPath()));
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        SaveLeftovers.showInterrupted(context);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertClean();
    }

    @Test public void completeCountsArePublishedBeforeTheRowEndsAndRefusedStartsKeepThem() throws Exception {
        CompletableFuture<String> published = new CompletableFuture<>();
        SaveControl.Watcher watcher = () -> {
            if (SaveControl.batchOutcome() != null && !SaveControl.running().isEmpty()) {
                published.complete(SaveControl.batchOutcome());
            }
        };
        SaveControl.watch(watcher);
        try {
            CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
            assertTrue(MediaSave.saveBatch(context, Arrays.asList(null, null), ended::complete));
            ended.get(20, TimeUnit.SECONDS);
            assertEquals("Saved 0. Failed 0. Skipped 2.", published.get(10, TimeUnit.SECONDS));
            assertFalse(MediaSave.saveBatch(context, Collections.emptyList(), null));
            assertFalse(MediaSave.saveBatch(context, Collections.nCopies(33, null), null));
            assertEquals("Saved 0. Failed 0. Skipped 2.", SaveControl.batchOutcome());
            MediaSave.detailsForTests = details -> await(release);
            assertTrue(MediaSave.saveBatch(context, Arrays.asList(page(false, "/held.jpg", "1"), null), null));
            assertNull("a newly admitted carousel retained stale counts", SaveControl.batchOutcome());
            assertCleanAfterRelease();
        } finally {
            SaveControl.unwatch(watcher);
        }
    }

    private void assertCleanAfterRelease() throws Exception {
        release.countDown();
        waitForSaves();
        assertClean();
    }

    @Test @Config(sdk = {28, 37})
    public void theSameNotificationCancelSurvivesEveryPageAndPhase() throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int id = SavesForTests.beginCarousel(context, 3);
        try {
            PendingIntent cancel = batchNotification(manager, id).actions[0].actionIntent;
            for (int page = 1; page <= 3; page++) {
                SavesForTests.page(id, page, page == 2);
                SavesForTests.transferred(id, 4096, 8192);
                assertBatchNotification(manager, id, page, cancel);
                SavesForTests.joining(id);
                assertBatchNotification(manager, id, page, cancel);
                SavesForTests.saving(id);
                assertBatchNotification(manager, id, page, cancel);
            }
            cancel.send();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(SavesForTests.cancelled(id));
            assertTrue(SaveControl.running().isEmpty());
        } finally {
            SavesForTests.end(id);
        }
    }

    private static Notification batchNotification(NotificationManager manager, int id) {
        for (android.service.notification.StatusBarNotification shown : manager.getActiveNotifications()) {
            if (SaveControl.TAG.equals(shown.getTag()) && shown.getId() == id) return shown.getNotification();
        }
        throw new AssertionError("the batch notification disappeared");
    }

    private static void assertBatchNotification(NotificationManager manager, int id, int page, PendingIntent cancel) {
        assertEquals(1, manager.getActiveNotifications().length);
        Notification shown = batchNotification(manager, id);
        assertEquals("Saving a carousel", String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertTrue(String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TEXT)).startsWith("Page " + page + " of 3\n"));
        assertEquals(1, shown.actions.length);
        assertEquals(cancel, shown.actions[0].actionIntent);
        assertEquals(id, SaveControl.running().get(0).id);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out"); }
        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
    }

    /** Only this suite admits its loopback candidates; production URL checks and other suites stay intact. */
    @Implements(value = MediaUrlPolicy.class, isInAndroidSdk = false)
    public static class LocalCandidates {
        static String origin;
        @Implementation protected static String shapeRefusal(String url) {
            return url != null && url.startsWith(origin + "/") ? null
                    : Shadow.directlyOn(MediaUrlPolicy.class, "shapeRefusal", ClassParameter.from(String.class, url));
        }
    }

    /** The plain values the uniquely certified native getter bridges hand over. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class MediaBridge {
        static List<MediaSave.Item> post;
        static String poster;
        static Long postedAt;
        static List<MediaSave.Rendition> cover;
        static final Object ALL = new Object(), DOWNLOAD = new Object();
        static CharSequence label;
        @Implementation protected static Object saveAllOption() { return ALL; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return post; }
        @Implementation protected static Object feedMenuItemState(Object menu) { return 1; }
        @Implementation protected static void addSaveAllRow(Object menu, ArrayList<Object> rows, Object option, CharSequence title) {
            rows.add(option); label = title;
        }
        @Implementation protected static void addDownloadRow(Object menu, ArrayList<Object> rows) { rows.add(DOWNLOAD); }
        @Implementation protected static List<?> carouselMedia(Object media) { return media == post ? post : null; }
        @Implementation protected static int carouselIndex(Object itemState) { return (int) itemState; }
        @Implementation protected static List<?> videoVersions(Object media) {
            return media instanceof MediaSave.Item && ((MediaSave.Item) media).video ? ((MediaSave.Item) media).renditions : null;
        }
        @Implementation protected static String dashManifest(Object media) { return media instanceof MediaSave.Item ? ((MediaSave.Item) media).manifest : null; }
        @Implementation protected static Object imageVersions(Object media) { return media; }
        /** A carousel's own picture is its first page's, as Instagram's is, and a video's is its cover. */
        @Implementation protected static List<?> imageCandidates(Object media) {
            MediaSave.Item item = (MediaSave.Item) (media instanceof List ? ((List<?>) media).get(0) : media);
            return item.video ? cover : item.renditions;
        }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static String candidateUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static int candidateWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static int candidateHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object owner(Object media) { return poster; }
        @Implementation protected static String username(Object user) { return (String) user; }
        @Implementation protected static Long takenAt(Object media) { return postedAt; }
        @Implementation protected static String mediaId(Object media) { return media instanceof MediaSave.Item ? ((MediaSave.Item) media).details.videoId : null; }
    }
}
