/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;

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
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
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
import java.util.function.Supplier;

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
    private final CarouselPages.Previews previews = CarouselPages.previews;
    private final Supplier<String> accounts = DownloadHistory.accounts;
    private final List<OutputStream> metadataStreams = new ArrayList<>();
    private final List<File> metadataFiles = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37;
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
        Settings.DOWNLOAD_VIDEOS.save(true);
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
        MediaBridge.postId = null;
        MediaBridge.story = null;
        MediaBridge.cover = null;
        MediaBridge.frames = null;
        CoverFrame.firstFrameForTests = null;
        Settings.DOWNLOAD_FEED_COVER.resetToDefault();
        Settings.DOWNLOAD_CHOOSE_PAGES.resetToDefault();
        CarouselPages.previews = previews;
        DownloadHistory.accounts = accounts;
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        Settings.SAVE_PHOTO_METADATA.resetToDefault();
        Settings.FILENAME_TEMPLATE.resetToDefault();
        MetadataExif.files.clear();
        MetadataExif.failures.clear();
        for (OutputStream stream : metadataStreams) stream.close();
        for (File file : metadataFiles) assertTrue(!file.exists() || file.delete());
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

    @Test public void singlePostsUseTheirCodeWhileStorySavesKeepTheirUsualPrefixes() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{shortcode}");
        List<PostDetails> handed = Collections.synchronizedList(new ArrayList<>());
        MediaSave.detailsForTests = handed::add;
        MediaSave.Item photo = page(false, "/post.jpg", "3549837394260045367");
        MediaSave.Item video = page(true, "/post.mp4", "908540701891980503");
        assertTrue(VideoDownload.save(photo, null, null));
        waitForSaves();
        assertTrue(VideoDownload.save(video, null, null));
        waitForSaves();

        MediaBridge.story = page(false, "/story.jpg", "3549837394260045367");
        assertTrue(StoryDownload.save("Download", new Object()));
        waitForSaves();
        MediaBridge.story = page(true, "/story.mp4", "908540701891980503");
        assertTrue(StoryDownload.save("Download", new Object()));
        waitForSaves();

        assertEquals("DFDjk1fRK43.jpg", gallery.rows.get(1L).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("ybyPRoQWzX.mp4", gallery.rows.get(2L).getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertTrue(gallery.rows.toString(), gallery.rows.get(3L).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                .matches("IG_IMG_\\d{8}_\\d{6}\\.jpg"));
        assertTrue(gallery.rows.toString(), gallery.rows.get(4L).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                .matches("IG_VID_\\d{8}_\\d{6}\\.mp4"));
        assertEquals(4, handed.size());
        assertEquals("DFDjk1fRK43", handed.get(0).shortcode);
        assertEquals("ybyPRoQWzX", handed.get(1).shortcode);
        assertNull(handed.get(2).shortcode);
        assertNull(handed.get(3).shortcode);
        for (ContentValues row : gallery.rows.values()) {
            assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        }
        assertClean();
    }

    @Test @Config(sdk = {28, 37})
    public void shortcodeOnlyCarouselNamesUseTheParentAndKeepRepeatedPages() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        for (String directory : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES}) {
            File[] old = new File(Environment.getExternalStoragePublicDirectory(directory), "Instagram").listFiles();
            if (old != null) for (File file : old) assertTrue(file.delete());
        }
        FileNameTemplateTest.FolderGallery output = Robolectric.setupContentProvider(
                FileNameTemplateTest.FolderGallery.class, MediaStore.AUTHORITY);
        Settings.FILENAME_TEMPLATE.save("{shortcode}");
        MediaBridge.postId = "3549837394260045367_123";
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "101"), page(true, "/second.mp4", "102"),
                page(false, "/third.jpg", "103"), page(true, "/fourth.mp4", "104"));
        List<PostDetails> handed = Collections.synchronizedList(new ArrayList<>());
        MediaSave.detailsForTests = handed::add;

        VideoDownload.saveAll(MediaBridge.post, null);
        waitForSaves();

        assertEquals("Saved 4. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        List<String> names = new ArrayList<>();
        if (MediaStoreWriter.legacyStorage()) names.addAll(savedNames());
        else for (ContentValues row : output.rows.values()) {
            names.add(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
            assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        }
        assertEquals(names.toString(), 4, names.size());
        assertEquals(names.toString(), 4, new HashSet<>(names).size());
        assertTrue(names.toString(), names.contains("DFDjk1fRK43.jpg"));
        assertTrue(names.toString(), names.contains("DFDjk1fRK43.mp4"));
        for (String name : names) assertTrue(name, name.matches("DFDjk1fRK43(?:_\\d{6})?\\.(?:jpg|mp4)"));
        assertEquals(4, handed.size());
        for (int page = 0; page < handed.size(); page++) {
            assertEquals(Integer.toString(101 + page), handed.get(page).videoId);
            assertEquals("DFDjk1fRK43", handed.get(page).shortcode);
            assertEquals(page + 1, handed.get(page).page);
        }
        MediaBridge.postId = null;
        PostDetails unknownParent = VideoDownload.details(MediaBridge.post.get(0), MediaBridge.post);
        assertEquals("101", unknownParent.videoId);
        assertFalse("a child id isn't a substitute for the missing parent", unknownParent.hasShortcode());
        assertClean();
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
        assertEquals("Carousel canceled. Saved 1. Failed 0. Skipped 2.", ShadowToast.getTextOfLatestToast());
        assertEquals("Carousel canceled. Saved 1. Failed 0. Skipped 2.", SaveControl.batchOutcome());
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

    /**
     * A batch uses one slot. A second Download all while it saves waits its turn instead of being
     * refused, and starts once the first ends (#115). A single save past the limit is still refused.
     */
    @Test public void aBatchUsesOneSlotAndASecondWaitsItsTurn() throws Exception {
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
        CompletableFuture<MediaSave.BatchResult> second = new CompletableFuture<>();
        assertTrue("the second waits its turn", MediaSave.saveBatch(context, Arrays.asList(held, held), second::complete));
        assertEquals(1, MediaSave.batchesWaiting());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Queued. It starts once the post before it is saved.", ShadowToast.getTextOfLatestToast());
        assertFalse(MediaSave.saveVideo(context, SavesForTests.renditions(server.origin() + "/held.mp4"), null, null));
        release.countDown();
        ended.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
        for (Thread worker : workers) worker.join(10_000);
        waitForSaves();
        assertEquals("each batch fetched both its pages once", 4, server.hits("/held.mp4"));
        assertEquals(0, MediaSave.batchesWaiting());
        assertClean();
    }

    /** Two Download all requests save one post's pages, then the other's, in the order they were asked (#115). */
    @Test public void aSecondDownloadAllWaitsForTheFirstAndKeepsItsOrder() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1);
        MediaSave.detailsForTests = details -> {
            order.add(details.videoId);
            if (order.size() == 1) { entered.countDown(); await(release); }
        };
        CompletableFuture<MediaSave.BatchResult> first = new CompletableFuture<>();
        CompletableFuture<MediaSave.BatchResult> second = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(page(false, "/a1.jpg", "a1"), page(false, "/a2.jpg", "a2")), first::complete));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(page(false, "/b1.jpg", "b1"), page(false, "/b2.jpg", "b2")), second::complete));
        assertEquals(1, MediaSave.batchesWaiting());
        assertEquals("one saving, one waiting", 2, MediaSave.savesInFlight());
        assertEquals("only the first has a Cancel", 1, SaveControl.running().size());
        release.countDown();
        assertEquals(2, first.get(20, TimeUnit.SECONDS).saved);
        assertEquals(2, second.get(20, TimeUnit.SECONDS).saved);
        waitForSaves();
        assertEquals(Arrays.asList("a1", "a2", "b1", "b2"), order);
        assertEquals(0, MediaSave.batchesWaiting());
        assertClean();
    }

    /** With the line full, one more Download all says so and saves nothing, and the ones waiting still run. */
    @Test public void aFullLineSaysSoAndTheWaitingOnesStillRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        MediaSave.detailsForTests = details -> { entered.countDown(); await(release); };
        assertTrue(MediaSave.saveBatch(context, Collections.singletonList(page(false, "/first.jpg", "1")), null));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        List<CompletableFuture<MediaSave.BatchResult>> waiting = new ArrayList<>();
        for (int i = 0; i < MediaSave.MAX_WAITING; i++) {
            CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
            waiting.add(ended);
            assertTrue(MediaSave.saveBatch(context, Collections.singletonList((MediaSave.Item) null), ended::complete));
        }
        assertEquals(MediaSave.MAX_WAITING, MediaSave.batchesWaiting());
        assertTrue("refused with its own message", MediaSave.saveBatch(context, Collections.singletonList(page(false, "/late.jpg", "2")), null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Not saved: 10 posts are already waiting", ShadowToast.getTextOfLatestToast());
        assertEquals(MediaSave.MAX_WAITING, MediaSave.batchesWaiting());
        release.countDown();
        for (CompletableFuture<MediaSave.BatchResult> ended : waiting) assertEquals(1, ended.get(20, TimeUnit.SECONDS).skipped);
        waitForSaves();
        assertEquals(1, server.hits("/first.jpg"));
        assertEquals(0, server.hits("/late.jpg"));
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

    /** #155: a cover whose sizes are listed smallest first saves the largest one, not the first. */
    @Test public void aCoverListedSmallestFirstSavesItsLargest() throws Exception {
        for (String size : new String[] {"/144.jpg", "/540.jpg", "/1080.jpg"}) server.serve(size, "image/jpeg", body(false));
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/144.jpg?stp=dst-jpg_e15_s144x256", 144, 256, 0),
                new MediaSave.Rendition(server.origin() + "/540.jpg?stp=dst-jpg_e15_s540x960", 540, 960, 0),
                new MediaSave.Rendition(server.origin() + "/1080.jpg?stp=dst-jpg_e15_s1080x1920", 1080, 1920, 0)),
                null, PostDetails.of("7"));
        assertEquals("3 picture size(s) (candidates 3), largest from candidates 1080x1920", StoryDownload.cover(reel).summary());
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/1080.jpg")); assertEquals(0, server.hits("/144.jpg")); assertEquals(0, server.hits("/540.jpg"));
        assertClean();
    }

    /**
     * #261: on a phone whose screen is 720 pixels wide every size Instagram sends of a photo names
     * its size, and the photo save took them all for thumbnails, so feed photos, a profile's photos
     * and a carousel's photo pages failed. The largest saves now, alone and in a batch.
     */
    @Test public void aPhotoWhoseEverySizeNamesItSavesItsLargest() throws Exception {
        server.serve("/360.jpg", "image/jpeg", body(false));
        server.serve("/720.jpg", "image/jpeg", body(false));
        List<MediaSave.Rendition> sizes = Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/360.jpg?stp=dst-jpg_e35_p360x360_tt6", 360, 450, 0),
                new MediaSave.Rendition(server.origin() + "/720.jpg?stp=dst-jpg_e35_p720x720_tt6", 720, 900, 0));
        assertNull("every address names its size", RenditionPicker.pickImage(sizes));
        assertTrue(MediaSave.savePhoto(context, sizes, PostDetails.of("7")));
        waitForSaves();
        assertEquals(1, server.hits("/720.jpg")); assertEquals(0, server.hits("/360.jpg"));

        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, Arrays.asList(new MediaSave.Item(false, sizes, null, PostDetails.of("8")),
                page(false, "/next.jpg", "9")), ended::complete));
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        waitForSaves();
        assertEquals(2, result.saved); assertEquals(0, result.failed);
        assertEquals(2, server.hits("/720.jpg")); assertEquals(0, server.hits("/360.jpg"));
        assertEquals(3, gallery.rows.size());

        // An address that doesn't name its size still wins over every one that does.
        MediaSave.Rendition plain = new MediaSave.Rendition(server.origin() + "/full.jpg?stp=dst-jpg_e35_tt6", 0, 0, 0);
        assertSame(plain, MediaSave.pictureOf(Arrays.asList(sizes.get(1), plain)));
        assertClean();
    }

    /**
     * #155: when the app states no size for any of a cover's addresses, the size each address
     * names decides, so a thumbnail listed first doesn't save in place of the full picture.
     */
    @Test public void aCoverWithNoStatedSizesGoesByWhatItsAddressesName() throws Exception {
        server.serve("/thumb.jpg", "image/jpeg", body(false));
        server.serve("/full.jpg", "image/jpeg", body(false));
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                MediaSave.Rendition.of(server.origin() + "/thumb.jpg?stp=dst-jpg_e15_s150x150_tt6"),
                MediaSave.Rendition.of(server.origin() + "/full.jpg?stp=dst-jpg_e15_p1080x1080_tt6")),
                null, PostDetails.of("7"));
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/full.jpg")); assertEquals(0, server.hits("/thumb.jpg"));
        assertClean();
    }

    /**
     * A reel whose model keeps a larger still in additional_candidates than its candidates state
     * saves that still, and the log names where the largest came from.
     */
    @Test public void aReelsCoverSavesTheLargestStillItsModelKeeps() throws Exception {
        server.serve("/v/up_1_n.jpg", "image/jpeg", body(false));
        server.serve("/w/up_1_n.jpg", "image/jpeg", body(false));
        MediaBridge.frames = new MediaSave.Rendition[] {new MediaSave.Rendition(server.origin() + "/w/up_1_n.jpg?stp=dst-jpg_e15", 1080, 1920, 0), null, null};
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/v/up_1_n.jpg?stp=dst-jpg_e15_s360x640", 360, 640, 0)), null, PostDetails.of("7"));
        StoryDownload.Cover cover = StoryDownload.cover(reel);
        assertEquals(2, cover.sizes.size());
        assertEquals("first_frame 1080x1920", cover.chosenSource());
        assertEquals("2 picture size(s) (candidates 1, first_frame 1), largest from first_frame 1080x1920", cover.summary());
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/w/up_1_n.jpg")); assertEquals(0, server.hits("/v/up_1_n.jpg"));
        assertClean();
    }

    /**
     * A reel with a custom cover keeps a first frame that is another picture, and nothing 450 states
     * says which a reel has, so a still from another file isn't taken for its cover. The candidates
     * save, and an address naming no file leaves the stills out too.
     */
    @Test public void aStillFromAnotherPictureIsNotTheCover() throws Exception {
        server.serve("/v/custom_n.jpg", "image/jpeg", body(false));
        server.serve("/w/frame0_n.jpg", "image/jpeg", body(false));
        MediaBridge.frames = new MediaSave.Rendition[] {
                new MediaSave.Rendition(server.origin() + "/w/frame0_n.jpg", 1080, 1920, 0),
                new MediaSave.Rendition(server.origin() + "/", 1080, 1920, 0), null};
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/v/custom_n.jpg", 360, 640, 0)), null, PostDetails.of("7"));
        StoryDownload.Cover cover = StoryDownload.cover(reel);
        assertEquals(1, cover.sizes.size());
        assertEquals("1 picture size(s) (candidates 1), largest from candidates 360x640", cover.summary());
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/v/custom_n.jpg")); assertEquals(0, server.hits("/w/frame0_n.jpg"));
        assertClean();
    }

    /** A reel whose cover is its first frame, listed at 360x640, beside a 720x1280 video file (#214). */
    private MediaSave.Item aReelWithASmallFirstFrameCover() {
        server.serve("/v/up_1_n.jpg", "image/jpeg", body(false));
        server.serve("/w/up_1_n.jpg", "image/jpeg", body(false));
        server.serve("/720.mp4", "video/mp4", body(true));
        MediaBridge.cover = Collections.singletonList(new MediaSave.Rendition(server.origin() + "/v/up_1_n.jpg", 360, 640, 0));
        MediaBridge.frames = new MediaSave.Rendition[] {new MediaSave.Rendition(server.origin() + "/w/up_1_n.jpg", 360, 640, 0), null, null};
        return new MediaSave.Item(true, Collections.singletonList(
                new MediaSave.Rendition(server.origin() + "/720.mp4", 720, 1280, 0)), null, PostDetails.of("7"));
    }

    private int coverWorkFiles() {
        String[] left = DashSave.workFolder(context).list((folder, name) -> name.startsWith("frame") || name.startsWith("cover"));
        return left == null ? 0 : left.length;
    }

    /**
     * #214: Instagram lists a reel's cover at 360 wide now. When the cover is the reel's first frame,
     * that frame is taken from the larger video and saved as a JPEG, and the small still isn't fetched.
     */
    @Test public void aFirstFrameCoverIsTakenFromTheLargerVideo() throws Exception {
        MediaSave.Item reel = aReelWithASmallFirstFrameCover();
        CoverFrame.firstFrameForTests = file -> android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888);
        assertTrue(StoryDownload.cover(reel).firstFrame());

        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();

        assertEquals(1, server.hits("/720.mp4"));
        assertEquals(0, server.hits("/v/up_1_n.jpg")); assertEquals(0, server.hits("/w/up_1_n.jpg"));
        assertEquals(1, gallery.rows.size());
        assertEquals("image/jpeg", gallery.rows.values().iterator().next().getAsString(MediaStore.MediaColumns.MIME_TYPE));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the cover is the first frame, taking it from the video at 720x1280 over the still at 360x640"));
        assertTrue(report, report.contains("the cover was taken from the video's first frame at 720x1280"));
        assertEquals(0, coverWorkFiles());
        assertClean();
    }

    /** A video that gives no frame still saves the cover: the largest still, as before. */
    @Test public void aCoverFrameThatFailsSavesTheStill() throws Exception {
        MediaSave.Item reel = aReelWithASmallFirstFrameCover();
        CoverFrame.firstFrameForTests = file -> null;

        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();

        assertEquals(1, server.hits("/720.mp4"));
        assertEquals(1, server.hits("/v/up_1_n.jpg") + server.hits("/w/up_1_n.jpg"));
        assertEquals(1, gallery.rows.size());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the cover frame ended with BUILD_ERROR (the video gave no first frame), saving the still as it is"));
        assertEquals(0, coverWorkFiles());
        assertClean();
    }

    /** A cancel while the frame is read ends the save there, with no still saved in its place. */
    @Test public void aCancelWhileTakingTheFrameSavesNoStill() throws Exception {
        MediaSave.Item reel = aReelWithASmallFirstFrameCover();
        CoverFrame.firstFrameForTests = file -> {
            for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
            return null;
        };

        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();

        assertEquals(1, server.hits("/720.mp4"));
        assertEquals(0, server.hits("/v/up_1_n.jpg") + server.hits("/w/up_1_n.jpg"));
        assertTrue(gallery.rows.isEmpty());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("CANCELLED (cancelled while taking the cover from the video)"));
        assertFalse(report, report.contains("saving the still as it is"));
        assertEquals(0, coverWorkFiles());
        assertClean();
    }

    /**
     * A custom cover is another picture than the first frame, and a still at least the video's size
     * needs nothing from it, so neither fetches the video.
     */
    @Test public void onlyAFirstFrameCoverSmallerThanTheVideoComesFromIt() throws Exception {
        MediaSave.Item reel = aReelWithASmallFirstFrameCover();
        CoverFrame.firstFrameForTests = file -> { throw new AssertionError("the video was read"); };

        server.serve("/w/frame0_n.jpg", "image/jpeg", body(false));
        MediaBridge.frames = new MediaSave.Rendition[] {new MediaSave.Rendition(server.origin() + "/w/frame0_n.jpg", 360, 640, 0), null, null};
        assertFalse("a custom cover", StoryDownload.cover(reel).firstFrame());
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/v/up_1_n.jpg"));

        MediaBridge.frames = new MediaSave.Rendition[] {new MediaSave.Rendition(server.origin() + "/w/up_1_n.jpg", 720, 1280, 0), null, null};
        MediaSave.Item another = new MediaSave.Item(true, reel.renditions, null, PostDetails.of("8"));
        assertTrue(StoryDownload.cover(another).firstFrame());
        assertTrue(ReelDownload.saveCover(context, another));
        waitForSaves();
        assertEquals("a still the video's size", 1, server.hits("/w/up_1_n.jpg"));

        assertEquals(0, server.hits("/720.mp4"));
        assertEquals(2, gallery.rows.size());
        assertClean();
    }

    /**
     * The video a cover is taken from is the largest picture the phone decodes, of the files and
     * the manifest's tracks: AV1 only on a phone with an AV1 decoder and Never save AV1 video off
     * (#379), an HDR picture only with Never save HDR video off (#352), and only Meta's addresses.
     */
    @Test public void theCoverComesFromTheLargestVideoThePhoneDecodes() {
        Object av1Before = org.robolectric.util.ReflectionHelpers.getStaticField(DashSave.class, "canWriteAv1");
        try {
            String cdn = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/";
            String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                    + "<Representation codecs=\"vp09.02.10.10.01.09.16.09.01\" width=\"1440\" height=\"2560\" bandwidth=\"2500000\">"
                    + "<BaseURL>" + cdn + "v1440hdr.mp4?oh=1&amp;oe=2</BaseURL></Representation>"
                    + "<Representation codecs=\"av01.0.08m.08.0.111.01.01.01.0\" width=\"1080\" height=\"1920\" bandwidth=\"1525000\">"
                    + "<BaseURL>" + cdn + "v1080.mp4?oh=1&amp;oe=2</BaseURL></Representation>"
                    + "<Representation codecs=\"avc1.64001f\" width=\"720\" height=\"1280\" bandwidth=\"900000\">"
                    + "<BaseURL>" + cdn + "v720.mp4?oh=1&amp;oe=2</BaseURL></Representation>"
                    + "</AdaptationSet></Period></MPD>";
            List<MediaSave.Rendition> files = Arrays.asList(
                    new MediaSave.Rendition(cdn + "f720.mp4", 720, 1280, 0),
                    new MediaSave.Rendition("https://example.com/big.mp4", 1440, 2560, 0));
            MediaSave.Rendition still = new MediaSave.Rendition(cdn + "c.jpg", 360, 640, 0);

            org.robolectric.util.ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", Boolean.TRUE);
            assertEquals("every phone decodes VP9, HDR included", cdn + "v1440hdr.mp4?oh=1&oe=2",
                    CoverFrame.source(files, manifest, still).url);

            Settings.DOWNLOAD_NO_HDR.save(true);
            assertEquals("the HDR switch passes the HDR picture over", cdn + "v1080.mp4?oh=1&oe=2",
                    CoverFrame.source(files, manifest, still).url);

            Settings.DOWNLOAD_NO_AV1.save(true);
            assertEquals("the switch passes the AV1 over on a phone that decodes it", cdn + "v720.mp4?oh=1&oe=2",
                    CoverFrame.source(files, manifest, still).url);
            Settings.DOWNLOAD_NO_AV1.save(false);

            org.robolectric.util.ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", Boolean.FALSE);
            assertEquals("of two at 720, the track that states the smaller file", cdn + "v720.mp4?oh=1&oe=2",
                    CoverFrame.source(files, manifest, still).url);
            assertEquals(cdn + "f720.mp4", CoverFrame.source(files, null, still).url);
            assertNull("no video beats a still its size", CoverFrame.source(files, manifest,
                    new MediaSave.Rendition(cdn + "c.jpg", 720, 1280, 0)));
            assertNull("a still of unknown size may be the larger", CoverFrame.source(files, manifest,
                    new MediaSave.Rendition(cdn + "c.jpg", 0, 0, 0)));
            assertNull(CoverFrame.source(null, null, still));
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", av1Before);
            Settings.DOWNLOAD_NO_AV1.resetToDefault();
            Settings.DOWNLOAD_NO_HDR.resetToDefault();
        }
    }

    @Test public void theFileAnAddressNamesIsWhatTwoSizesShare() {
        assertEquals("up_1_n", StoryDownload.assetOf("https://cdn.example/v/t51.1-15/up_1_n.jpg?stp=a/b&x=1"));
        assertEquals("up_1_n", StoryDownload.assetOf("https://cdn.example/other/up_1_n.webp#top"));
        assertNull(StoryDownload.assetOf("https://cdn.example/"));
        assertNull(StoryDownload.assetOf(null));
    }

    /** A reel with a single size, and no still beside it, saves as it did, and a still of other proportions is left out. */
    @Test public void aReelsCoverWithOneSizeSavesAsBefore() throws Exception {
        server.serve("/small.jpg", "image/jpeg", body(false));
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/small.jpg", 360, 640, 0)), null, PostDetails.of("7"));
        StoryDownload.Cover alone = StoryDownload.cover(reel);
        assertEquals("1 picture size(s) (candidates 1), largest from candidates 360x640", alone.summary());
        MediaBridge.frames = new MediaSave.Rendition[] {null, null, new MediaSave.Rendition(server.origin() + "/sq/small.jpg", 1080, 1080, 0)};
        assertEquals("a square smart frame isn't the cover", 1, StoryDownload.cover(reel).sizes.size());
        assertTrue(ReelDownload.saveCover(context, reel));
        waitForSaves();
        assertEquals(1, server.hits("/small.jpg"));
        assertClean();
    }

    /** Candidates that state no size give nothing to compare a still with, so the stills aren't read. */
    @Test public void stillsAreLeftOutWhenTheCandidatesStateNoSize() {
        MediaBridge.frames = new MediaSave.Rendition[] {new MediaSave.Rendition("https://x.invalid/f.jpg", 1080, 1920, 0), null, null};
        MediaSave.Item reel = new MediaSave.Item(false, Arrays.asList(MediaSave.Rendition.of("https://x.invalid/c.jpg")), null, PostDetails.of("7"));
        assertEquals(1, StoryDownload.cover(reel).sizes.size());
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

    /**
     * #97: the feed's Download button asks what the menu asks. With Download feed videos on and
     * Download feed photos off, a photo post and an all-photo carousel have nothing to save, while a
     * video, or a carousel with a video page, does. Nothing is fetched to decide.
     */
    @Test public void theFeedButtonGoesOnlyWhereTheMenuWouldSave() {
        MediaSave.Item photo = page(false, "/photo.jpg", "1");
        MediaSave.Item video = page(true, "/video.mp4", "2");
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertFalse("a photo with the photo switch off", VideoDownload.saves(photo));
        assertTrue(VideoDownload.saves(video));
        assertFalse(VideoDownload.saves(null));

        MediaBridge.post = Arrays.asList(page(false, "/a.jpg", "3"), page(false, "/b.jpg", "4"));
        assertFalse("an all-photo carousel", VideoDownload.saves(MediaBridge.post));
        MediaBridge.post = Arrays.asList(page(false, "/c.jpg", "5"), page(true, "/d.mp4", "6"));
        assertTrue("its video page saves", VideoDownload.saves(MediaBridge.post));

        Settings.DOWNLOAD_PHOTOS.save(true);
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertTrue(VideoDownload.saves(photo));
        assertFalse("a video's cover isn't a photo to save", VideoDownload.saves(video));
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertFalse("every switch off", VideoDownload.saves(photo));
        int fetched = 0;
        for (String path : Arrays.asList("/photo.jpg", "/video.mp4", "/a.jpg", "/b.jpg", "/c.jpg", "/d.mp4")) fetched += server.hits(path);
        assertEquals("nothing was fetched to decide", 0, fetched);
    }

    /** With Choose carousel pages on, Choose pages follows Save all in the long menu and in the short one. Off, it's in neither. */
    @Test public void choosePagesFollowsSaveAllInBothMenus() {
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"));
        Object report = new Object();
        List<Object> nativeOptions = Collections.unmodifiableList(Arrays.asList(MediaBridge.DOWNLOAD, report));
        ArrayList<Object> rows = new ArrayList<>();
        VideoDownload.offerAll(new Object(), rows);
        assertEquals(Collections.singletonList(MediaBridge.ALL), rows);
        assertEquals(Arrays.asList(MediaBridge.DOWNLOAD, MediaBridge.ALL, report), VideoDownload.allow(nativeOptions, MediaBridge.DOWNLOAD));

        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        rows.clear();
        VideoDownload.offerAll(new Object(), rows);
        Object choose = VideoDownload.chooseOption();
        assertNotNull(choose);
        assertEquals(Arrays.asList(MediaBridge.ALL, choose), rows);
        assertEquals("Choose pages", MediaBridge.label.toString());
        List<?> allowed = VideoDownload.allow(nativeOptions, MediaBridge.DOWNLOAD);
        assertEquals(Arrays.asList(MediaBridge.DOWNLOAD, MediaBridge.ALL, choose, report), allowed);
        assertSame(allowed, VideoDownload.allow(allowed, MediaBridge.DOWNLOAD));
        Settings.DOWNLOAD_VIDEOS.save(false); Settings.DOWNLOAD_PHOTOS.save(false);
        assertSame(nativeOptions, VideoDownload.allow(nativeOptions, MediaBridge.DOWNLOAD));
    }

    /**
     * Choose pages lists the carousel's pages as numbered rows, keeps Save off until a box is
     * ticked, and saves only the ticked pages, in order, through Save all's batch. The pages are
     * read on the tap, so the post changing after doesn't change what saves, and each row's
     * picture is fetched off the main thread and fitted to the row.
     */
    @Test public void choosePagesSavesOnlyTheTickedPagesInOrder() throws Exception {
        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        List<MediaSave.Item> pages = new ArrayList<>(Arrays.asList(page(false, "/first.jpg", "1"),
                page(true, "/middle.mp4", "2"), page(false, "/last.jpg", "3")));
        MediaBridge.post = pages;
        List<String> fetched = Collections.synchronizedList(new ArrayList<>());
        CarouselPages.previews = (application, url, size) -> {
            boolean main = Thread.currentThread() == Looper.getMainLooper().getThread();
            fetched.add(url.substring(server.origin().length()) + (main ? " on the main thread" : ""));
            return Bitmap.createBitmap(4 * size, 2 * size, Bitmap.Config.ARGB_8888);
        };
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        MediaSave.detailsForTests = details -> order.add(details.videoId);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(list);
        List<CheckBox> boxes = boxes(list);
        assertEquals(Arrays.asList("Page 1, photo", "Page 2, video", "Page 3, photo"), texts(boxes));
        Button save = list.getButton(DialogInterface.BUTTON_POSITIVE);
        assertFalse("Save is off with no box ticked", save.isEnabled());
        save.performClick();
        assertTrue("nothing ticked, so nothing saved", list.isShowing());

        int size = CarouselPages.previewSize();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(picture(boxes.get(0)).getDrawable() instanceof BitmapDrawable
                && picture(boxes.get(2)).getDrawable() instanceof BitmapDrawable)) {
            assertTrue("the pictures never showed", System.nanoTime() < deadline);
            Thread.sleep(10);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        List<String> sorted = new ArrayList<>(fetched);
        Collections.sort(sorted);
        assertEquals(Arrays.asList("/first.jpg", "/last.jpg"), sorted);
        assertEquals(size, ((BitmapDrawable) picture(boxes.get(0)).getDrawable()).getBitmap().getHeight());
        assertEquals(2 * size, ((BitmapDrawable) picture(boxes.get(2)).getDrawable()).getBitmap().getWidth());
        assertTrue("a video with no cover keeps its blank square", picture(boxes.get(1)).getDrawable() instanceof GradientDrawable);

        picture(boxes.get(0)).performClick();
        assertTrue("a tap on the picture ticks its box", boxes.get(0).isChecked());
        assertTrue(save.isEnabled());
        boxes.get(1).setChecked(true);
        boxes.get(2).setChecked(true);
        boxes.get(1).setChecked(false);
        pages.clear();
        MediaBridge.post = null;
        save.performClick();
        assertFalse(list.isShowing());
        waitForSaves();
        assertEquals(Arrays.asList("1", "3"), order);
        assertEquals(1, server.hits("/first.jpg")); assertEquals(0, server.hits("/middle.mp4")); assertEquals(1, server.hits("/last.jpg"));
        assertEquals("Saved 2. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        assertClean();
    }

    /**
     * Choose pages keeps Name saves by account and post time's page numbers: ticking pages 2 and 4
     * of five saves just those two, as _2 and _4, the names Save all gives them.
     */
    @Test @Config(sdk = {28, 37})
    public void choosePagesKeepsEachPagesNumberInItsName() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        for (String directory : new String[]{Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES}) {
            File[] old = new File(Environment.getExternalStoragePublicDirectory(directory), "Instagram").listFiles();
            if (old != null) for (File file : old) assertTrue(file.delete());
        }
        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        Settings.SAVE_NAME_BY_POST.save(true);
        Calendar noon = new GregorianCalendar();
        noon.clear();
        noon.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        MediaBridge.poster = "stevi.ous";
        MediaBridge.postedAt = noon.getTimeInMillis() / 1000L;
        CarouselPages.previews = (application, url, size) -> null;
        MediaBridge.post = Arrays.asList(page(false, "/p1.jpg", "1"), page(false, "/p2.jpg", "2"),
                page(false, "/p3.jpg", "3"), page(true, "/p4.mp4", "4"), page(false, "/p5.jpg", "5"));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        List<CheckBox> boxes = boxes(list);
        assertEquals(5, boxes.size());
        boxes.get(1).setChecked(true);
        boxes.get(3).setChecked(true);
        list.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        waitForSaves();
        assertEquals("Saved 2. Failed 0. Skipped 0.", ShadowToast.getTextOfLatestToast());
        String base = "stevi.ous_20260901_120000";
        assertEquals(Arrays.asList(base + "_2.jpg", base + "_4.mp4"), savedNames());
        for (String path : new String[]{"/p1.jpg", "/p3.jpg", "/p5.jpg"}) assertEquals(path, 0, server.hits(path));
        assertClean();
    }

    /**
     * A page the photo switch leaves out is listed but can't be ticked, rows work with no picture,
     * and Save says why and saves nothing when the account changed, the screen closed or nothing is
     * chosen.
     */
    @Test public void choosePagesSaysWhyItSavesNothing() throws Exception {
        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        Settings.DOWNLOAD_PHOTOS.save(false);
        CarouselPages.previews = (application, url, size) -> null;
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"), page(true, "/last.mp4", "3"));
        DownloadHistory.accounts = () -> "first account";
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        List<CheckBox> boxes = boxes(list);
        assertEquals("Page 1, photo\nLeft out by your download switches", boxes.get(0).getText().toString());
        assertFalse(boxes.get(0).isEnabled());
        picture(boxes.get(0)).performClick();
        assertFalse("a page that saves nothing can't be ticked", boxes.get(0).isChecked());
        assertTrue(boxes.get(1).isEnabled());
        boxes.get(1).setChecked(true);
        DownloadHistory.accounts = () -> "second account";
        list.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(list.isShowing());
        assertEquals("Not saved: the account changed", ShadowToast.getTextOfLatestToast());

        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(list, again);
        boxes(again).get(2).setChecked(true);
        activity.finish();
        again.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Not saved: the screen closed", ShadowToast.getTextOfLatestToast());

        int toasts = ShadowToast.shownToastCount();
        VideoDownload.choosePages(MediaBridge.post, activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame("no list opens over a closing screen", again, ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(toasts + 1, ShadowToast.shownToastCount());
        assertEquals("Not saved: the screen closed", ShadowToast.getTextOfLatestToast());

        Activity open = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(CarouselPages.save(open, Collections.emptyList(), DownloadHistory.owner()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No pages chosen", ShadowToast.getTextOfLatestToast());
        waitForSaves();
        for (String path : Arrays.asList("/first.jpg", "/middle.mp4", "/last.mp4")) assertEquals(path, 0, server.hits(path));
        assertClean();
    }

    /** Back closes Choose pages with a box ticked and saves nothing, and the next tap opens a new list. */
    @Test public void choosePagesClosesOnBackWithoutSaving() throws Exception {
        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        CarouselPages.previews = (application, url, size) -> null;
        MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(false, "/second.jpg", "2"));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        boxes(list).get(1).setChecked(true);
        int toasts = ShadowToast.shownToastCount();
        list.onBackPressed();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(list.isShowing());
        waitForSaves();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, server.hits("/first.jpg")); assertEquals(0, server.hits("/second.jpg"));
        assertEquals("nothing saved, so nothing was reported", toasts, ShadowToast.shownToastCount());

        VideoDownload.choosePages(MediaBridge.post, activity);
        assertNotSame(list, ShadowAlertDialog.getLatestAlertDialog());
        assertClean();
    }

    /** Choose pages keeps Save all's page limit, and with its switch off it opens nothing. */
    @Test public void choosePagesKeepsTheBatchLimit() {
        Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
        CarouselPages.previews = (application, url, size) -> null;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MediaSave.Item one = page(false, "/one.jpg", "1");
        MediaBridge.post = new ArrayList<>(Collections.nCopies(MediaSave.MAX_BATCH_PAGES + 1, one));
        VideoDownload.choosePages(MediaBridge.post, activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("Not saved: a carousel can have at most 32 pages", ShadowToast.getTextOfLatestToast());

        MediaBridge.post = new ArrayList<>(Collections.nCopies(MediaSave.MAX_BATCH_PAGES, one));
        VideoDownload.choosePages(MediaBridge.post, activity);
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        List<CheckBox> boxes = boxes(list);
        assertEquals(MediaSave.MAX_BATCH_PAGES, boxes.size());
        assertEquals("Page 32, photo", boxes.get(31).getText().toString());
        list.dismiss();

        Settings.DOWNLOAD_CHOOSE_PAGES.save(false);
        VideoDownload.choosePages(MediaBridge.post, activity);
        assertSame("off, no list opens", list, ShadowAlertDialog.getLatestAlertDialog());
    }

    /** Right to left and at twice the text size, a row starts at the right with its picture and its label grows. */
    @Test public void choosePagesRowsFollowRightToLeftAndLargeText() {
        int flags = context.getApplicationInfo().flags;
        context.getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        RuntimeEnvironment.setFontScale(2f);
        try {
            Settings.DOWNLOAD_CHOOSE_PAGES.save(true);
            CarouselPages.previews = (application, url, size) -> null;
            MediaBridge.post = Arrays.asList(page(false, "/first.jpg", "1"), page(true, "/middle.mp4", "2"));
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            VideoDownload.choosePages(MediaBridge.post, activity);
            AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
            View screen = list.getWindow().getDecorView();
            screen.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            screen.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST));
            screen.layout(0, 0, screen.getMeasuredWidth(), screen.getMeasuredHeight());
            CheckBox first = boxes(list).get(0);
            assertEquals(View.LAYOUT_DIRECTION_RTL, first.getLayoutDirection());
            assertTrue("the picture isn't at the row's start", picture(first).getLeft() > first.getLeft());
            float scaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, first.getResources().getDisplayMetrics());
            assertTrue("the text didn't grow", scaled > 16 * first.getResources().getDisplayMetrics().density);
            assertEquals(scaled, first.getTextSize(), 0.01f);
            assertEquals("Page 1, photo", first.getText().toString());
            list.dismiss();
        } finally {
            RuntimeEnvironment.setFontScale(1f);
            context.getApplicationInfo().flags = flags;
        }
    }

    private static List<CheckBox> boxes(AlertDialog list) {
        List<CheckBox> boxes = new ArrayList<>();
        collect(list.getWindow().getDecorView(), boxes);
        return boxes;
    }

    private static void collect(View view, List<CheckBox> boxes) {
        if (view instanceof CheckBox) boxes.add((CheckBox) view);
        if (!(view instanceof ViewGroup)) return;
        for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) collect(((ViewGroup) view).getChildAt(index), boxes);
    }

    private static List<String> texts(List<CheckBox> boxes) {
        List<String> texts = new ArrayList<>();
        for (CheckBox box : boxes) texts.add(box.getText().toString());
        return texts;
    }

    /** The picture in [box]'s row. */
    private static ImageView picture(CheckBox box) {
        return (ImageView) ((ViewGroup) box.getParent()).getChildAt(0);
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
            // The first carousel gives up its turn just after its completion runs, so this one can
            // queue for a moment before it starts. Its counts clear when it starts.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (SaveControl.running().isEmpty()) {
                assertTrue("the held carousel never started", System.nanoTime() < deadline);
                Thread.sleep(10);
            }
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
        static String postId;
        static Object story;
        static String poster;
        static Long postedAt;
        static List<MediaSave.Rendition> cover;
        /** first_frame, igtv_first_frame and smart_frame of the additional_candidates, or null when it has none. */
        static MediaSave.Rendition[] frames;
        static final Object ALL = new Object(), DOWNLOAD = new Object();
        static CharSequence label;
        @Implementation protected static Object saveAllOption() { return ALL; }
        @Implementation protected static Object feedOption(String name) { return name; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return post; }
        @Implementation protected static Object storyMedia(Object menu) { return story; }
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
        @Implementation protected static Object additionalCandidates(Object versions) { return frames; }
        @Implementation protected static Object firstFrame(Object more) { return frames[0]; }
        @Implementation protected static Object igtvFirstFrame(Object more) { return frames[1]; }
        @Implementation protected static Object smartFrame(Object more) { return frames[2]; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static String candidateUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static int candidateWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static int candidateHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object owner(Object media) { return poster; }
        @Implementation protected static String username(Object user) { return (String) user; }
        @Implementation protected static Long takenAt(Object media) { return postedAt; }
        @Implementation protected static String mediaId(Object media) {
            return media == post ? postId : media instanceof MediaSave.Item ? ((MediaSave.Item) media).details.videoId : null;
        }
    }
    @Test
    @Config(shadows = {LocalCandidates.class, MediaBridge.class, MetadataExif.class})
    public void aMixedBatchTagsOnlyItsCompletedJpegPhotos() throws Exception {
        Settings.SAVE_PHOTO_METADATA.save(true);
        assertTrue("the metadata switch did not become active", PostMetadata.on());
        MetadataGallery output = metadataGallery();
        byte[] jpeg = metadataJpeg();
        server.serve("/source-one.jpg", "image/jpeg", jpeg);
        server.serve("/source-two.jpg", "image/jpeg", jpeg);
        byte[] png;
        Bitmap picture = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            png = bytes.toByteArray();
        } finally { picture.recycle(); }
        server.serve("/source.png", "image/png", png);
        PostDetails first = metadataDetails("409960495", "source_one");
        PostDetails last = metadataDetails("3549837394260045367", "source_two");
        List<MediaSave.Item> pages = Arrays.asList(
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/source-one.jpg"), null, first),
                page(true, "/source-video.mp4", "102"),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/source.png"), null, first),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/source-two.jpg"), null, last),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/missing.jpg"), null, first));
        MediaSave.BatchResult result = metadataBatch(pages);
        assertEquals(4, result.saved); assertEquals(1, result.failed); assertEquals(0, result.skipped);
        assertEquals(metadataDiagnostics(), Arrays.asList(1L, 4L), output.metadataOpens);
        assertMetadata(output.files.get(1L), first, "https://www.instagram.com/p/Yb4Av/");
        assertMetadata(output.files.get(4L), last, "https://www.instagram.com/p/DFDjk1fRK43/");
        assertArrayEquals("a video was rewritten for photo metadata", body(true), Files.readAllBytes(output.files.get(2L).toPath()));
        assertArrayEquals("a PNG was rewritten for JPEG metadata", png, Files.readAllBytes(output.files.get(3L).toPath()));
        assertClean();
    }

    @Test
    @Config(shadows = {LocalCandidates.class, MediaBridge.class, MetadataExif.class})
    public void photoMetadataOffLeavesBatchJpegsUnchanged() throws Exception {
        Settings.SAVE_PHOTO_METADATA.save(false);
        MetadataGallery output = metadataGallery();
        byte[] jpeg = metadataJpeg();
        server.serve("/no-source.jpg", "image/jpeg", jpeg);
        MediaSave.BatchResult result = metadataBatch(Collections.singletonList(new MediaSave.Item(false,
                SavesForTests.renditions(server.origin() + "/no-source.jpg"), null,
                metadataDetails("409960495", "source_one"))));
        assertEquals(1, result.saved);
        assertArrayEquals(jpeg, Files.readAllBytes(output.files.get(1L).toPath()));
        assertTrue(output.metadataOpens.isEmpty());
        assertClean();
    }

    @Test
    @Config(shadows = {LocalCandidates.class, MediaBridge.class, MetadataExif.class})
    public void cancelingABatchTagsOnlyThePhotoAlreadyPublished() throws Exception {
        Settings.SAVE_PHOTO_METADATA.save(true);
        assertTrue("the metadata switch did not become active", PostMetadata.on());
        MetadataGallery output = metadataGallery();
        byte[] jpeg = metadataJpeg();
        server.serve("/completed-source.jpg", "image/jpeg", jpeg);
        server.serve("/canceled-source.jpg", "image/jpeg", jpeg);
        PostDetails first = metadataDetails("409960495", "source_one");
        MediaSave.detailsForTests = details -> {
            if ("3549837394260045367".equals(details.videoId)) SaveControl.cancel(SaveControl.running().get(0).id);
        };
        MediaSave.BatchResult result = metadataBatch(Arrays.asList(
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/completed-source.jpg"), null, first),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/canceled-source.jpg"), null,
                        metadataDetails("3549837394260045367", "source_two")), null));
        assertEquals(1, result.saved); assertEquals(0, result.failed); assertEquals(2, result.skipped);
        assertTrue(result.cancelled);
        assertEquals(metadataDiagnostics(), Collections.singletonList(1L), output.metadataOpens);
        assertMetadata(output.files.get(1L), first, "https://www.instagram.com/p/Yb4Av/");
        assertEquals("cancel fetched the next photo", 0, server.hits("/canceled-source.jpg"));
        assertClean();
    }

    @Test
    @Config(shadows = {LocalCandidates.class, MediaBridge.class, MetadataExif.class})
    public void aSinglePhotoTagsThroughTheSameMetadataProvider() throws Exception {
        Settings.SAVE_PHOTO_METADATA.save(true);
        assertTrue("the metadata switch did not become active", PostMetadata.on());
        MetadataGallery output = metadataGallery();
        server.serve("/single-source.jpg", "image/jpeg", metadataJpeg());
        PostDetails details = metadataDetails("409960495", "source_one");
        assertTrue(MediaSave.savePhoto(context, SavesForTests.renditions(server.origin() + "/single-source.jpg"), details));
        waitForSaves();
        assertEquals(metadataDiagnostics(), Collections.singletonList(1L), output.metadataOpens);
        assertMetadata(output.files.get(1L), details, "https://www.instagram.com/p/Yb4Av/");
        assertClean();
    }

    private MediaSave.BatchResult metadataBatch(List<MediaSave.Item> pages) throws Exception {
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, pages, ended::complete));
        MediaSave.BatchResult result = ended.get(10, TimeUnit.SECONDS);
        waitForSaves();
        return result;
    }

    private static PostDetails metadataDetails(String id, String owner) {
        return PostDetails.of(id, owner, new java.util.Date(1_783_670_400_000L));
    }

    private static byte[] metadataJpeg() throws Exception {
        try (InputStream input = PostMetadataTest.class.getResourceAsStream("post-metadata-sample.jpg")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int read; (read = input.read(buffer)) != -1;) bytes.write(buffer, 0, read);
            return bytes.toByteArray();
        }
    }

    private static void assertMetadata(File file, PostDetails details, String link) throws Exception {
        ExifInterface exif = new ExifInterface(file.getPath());
        assertEquals(metadataDiagnostics(), details.owner, exif.getAttribute(ExifInterface.TAG_ARTIST));
        java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
        stamp.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        assertEquals(stamp.format(details.posted), exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertEquals(link, exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
    }

    private static String metadataDiagnostics() {
        String logged = LogBufferManager.buildExportText().lines()
                .filter(line -> line.contains("tagged") || line.contains("could not tag") || line.contains("Exception:"))
                .collect(java.util.stream.Collectors.joining("\n"));
        java.io.StringWriter trace = new java.io.StringWriter();
        synchronized (MetadataExif.failures) {
            for (Throwable failure : MetadataExif.failures) failure.printStackTrace(new java.io.PrintWriter(trace));
        }
        return logged + "\n" + trace;
    }

    /**
     * The host JDK lacks Android's FileInputStream(FileDescriptor, boolean), and ShadowOs.lseek
     * doesn't move real descriptors. Route only the FD constructor to that descriptor's real file.
     * Release its host handle first: SDK 30's filename writer renames the JPEG, which Windows
     * refuses while the provider's RandomAccessFile is open.
     * The platform JPEG parser, attribute writer and readback remain unchanged. Native coverage
     * separately exercises the Android descriptor constructor and seeks.
     */
    @Implements(ExifInterface.class)
    public static final class MetadataExif {
        static final java.util.Map<FileDescriptor, FixtureFile> files = new java.util.concurrent.ConcurrentHashMap<>();
        static final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        @RealObject private ExifInterface exif;
        @Implementation protected void __constructor__(FileDescriptor descriptor) throws java.io.IOException {
            FixtureFile file = files.remove(descriptor);
            assertNotNull("metadata must use the descriptor of a published fixture file", file);
            file.owner.close();
            Shadow.invokeConstructor(ExifInterface.class, exif, ClassParameter.from(String.class, file.path.getPath()));
        }
        @Implementation protected void saveAttributes() throws Throwable {
            try {
                Shadow.directlyOn(exif, ExifInterface.class, "saveAttributes");
            } catch (Throwable failure) {
                failures.add(failure);
                throw failure;
            }
        }
        private static final class FixtureFile {
            final File path;
            final android.os.ParcelFileDescriptor owner;
            FixtureFile(File path, android.os.ParcelFileDescriptor owner) {
                this.path = path;
                this.owner = owner;
            }
        }
    }

    /** Real files let ExifInterface read back the completed MediaStore writer, including its URI. */
    private MetadataGallery metadataGallery() throws Exception {
        MetadataGallery output = Robolectric.setupContentProvider(MetadataGallery.class, MediaStore.AUTHORITY);
        for (long id = 1; id <= 4; id++) {
            File file = File.createTempFile("carousel-source", ".media", context.getCacheDir());
            metadataFiles.add(file);
            output.files.put(id, file);
            OutputStream stream = new java.io.FileOutputStream(file);
            metadataStreams.add(stream);
            for (android.net.Uri collection : Arrays.asList(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                        ContentUris.withAppendedId(collection, id), stream);
            }
        }
        return output;
    }

    public static final class MetadataGallery extends android.content.ContentProvider {
        final java.util.Map<Long, File> files = new java.util.HashMap<>();
        final java.util.Map<Long, ContentValues> rows = new java.util.HashMap<>();
        final List<Long> metadataOpens = new ArrayList<>();
        private long next = 1;
        @Override public boolean onCreate() { return true; }
        @Override public android.net.Uri insert(android.net.Uri uri, ContentValues values) {
            long id = next++;
            rows.put(id, new ContentValues(values));
            return ContentUris.withAppendedId(uri, id);
        }
        @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
            long id = ContentUris.parseId(uri);
            metadataOpens.add(id);
            android.os.ParcelFileDescriptor descriptor = android.os.ParcelFileDescriptor.open(
                    files.get(id), android.os.ParcelFileDescriptor.parseMode(mode));
            MetadataExif.files.put(descriptor.getFileDescriptor(), new MetadataExif.FixtureFile(files.get(id), descriptor));
            return descriptor;
        }
        @Override public int update(android.net.Uri uri, ContentValues values, String selection, String[] args) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }
        @Override public int delete(android.net.Uri uri, String selection, String[] args) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection,
                String[] args, String order) {
            return new android.database.MatrixCursor(projection == null ? new String[0] : projection);
        }
        @Override public String getType(android.net.Uri uri) { return "image/jpeg"; }
    }


}
