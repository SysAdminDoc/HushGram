/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Skip what's already saved (#150): a save the gallery published is remembered and the same thing
 * isn't saved again while its file is where the save put it, a second ask within half a minute saves
 * a copy anyway, and nothing is remembered with the switch off, for a failed or cancelled save, or
 * past Clear download history.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = {CarouselSaveTest.LocalCandidates.class})
public class DownloadHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String ALREADY = "Already saved. Download again within 30 seconds for a copy.";
    private static final String MEDIA = "3100000000000000001";
    private static final String ACCOUNT_A = "17841400000000001";
    private static final String ACCOUNT_B = "17841400000000002";

    private Context context;
    private LocalServer server;
    private Rows gallery;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37;
        server = new LocalServer();
        CarouselSaveTest.LocalCandidates.origin = server.origin();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("10.9.8.7")}) {
            @Override Refusal refusal(URL url) {
                return url.toString().startsWith(server.origin() + "/") ? null : super.refusal(url);
            }
        };
        gallery = Robolectric.setupContentProvider(Rows.class, MediaStore.AUTHORITY);
        for (int id = 1; id <= 12; id++) {
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), new ByteArrayOutputStream());
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), new ByteArrayOutputStream());
        }
        SaveLeftovers.forgetSweepForTests();
        LogBufferManager.clearLogBuffer();
        DownloadHistory.forgetForTests();
        DownloadHistory.accounts = () -> ACCOUNT_A;
        Settings.DOWNLOAD_SKIP_SAVED.save(true);
    }

    @After public void tearDown() throws Exception {
        for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
        SavesForTests.endAll();
        waitForSaves();
        server.close();
        MediaSave.policyForTests = null;
        Settings.DOWNLOAD_SKIP_SAVED.resetToDefault();
        DownloadHistory.clear(context);
        DownloadHistory.forgetForTests();
        Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
        LogBufferManager.clearLogBuffer();
    }

    // ---------------------------------------------------------------- single saves

    @Test public void aSavedReelIsSkippedUntilItIsAskedForAgainWithinHalfAMinute() throws Exception {
        server.serve("/clip.mp4", "video/mp4", body(true));

        assertTrue(saveClip());
        waitForSaves();
        assertEquals(1, server.hits("/clip.mp4"));
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().startsWith("Saved to "));
        assertEquals(1, DownloadHistory.count(context));
        assertEquals(1, gallery.rows.size());

        int toasts = ShadowToast.shownToastCount();
        assertTrue("a skipped save must still answer that it was handled", saveClip());
        waitForSaves();
        assertEquals("the same reel was fetched again", 1, server.hits("/clip.mp4"));
        assertEquals("only the skip is said, never Saving... first", toasts + 1, ShadowToast.shownToastCount());
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());
        assertEquals(1, gallery.rows.size());

        // Asked again right away: the copy is wanted, so it's saved without a question.
        assertTrue(saveClip());
        waitForSaves();
        assertEquals(2, server.hits("/clip.mp4"));
        assertEquals(2, gallery.rows.size());
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Saved to "));

        // The second ask was spent on that copy, so the next is a skip again.
        assertTrue(saveClip());
        waitForSaves();
        assertEquals(2, server.hits("/clip.mp4"));
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());
        assertEquals(1, DownloadHistory.count(context));
    }

    @Test public void theChanceToSaveACopyEndsAfterHalfAMinute() throws Exception {
        server.serve("/clip.mp4", "video/mp4", body(true));
        saveClip();
        waitForSaves();
        saveClip();
        waitForSaves();
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());

        ShadowLooper.idleMainLooper(DownloadHistory.AGAIN_MS + 1_000, TimeUnit.MILLISECONDS);
        saveClip();
        waitForSaves();
        assertEquals("a second ask after the half minute saved a copy", 1, server.hits("/clip.mp4"));
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());
    }

    @Test public void aFileDeletedOrMovedSinceIsSavedAgain() throws Exception {
        server.serve("/clip.mp4", "video/mp4", body(true));
        saveClip();
        waitForSaves();
        assertEquals(1, gallery.rows.size());

        // Deleted in the gallery: the entry the history points at is gone.
        gallery.rows.clear();
        saveClip();
        waitForSaves();
        assertEquals("a deleted file kept a save from happening", 2, server.hits("/clip.mp4"));
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Saved to "));
        assertEquals(1, DownloadHistory.count(context));

        // MediaStore keeps the folder with a slash on its end: still the same folder.
        ContentValues saved = gallery.rows.values().iterator().next();
        saved.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Instagram/");
        saveClip();
        waitForSaves();
        assertEquals(2, server.hits("/clip.mp4"));
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());

        // Moved to another folder: no longer where the save put it.
        ShadowLooper.idleMainLooper(DownloadHistory.AGAIN_MS + 1_000, TimeUnit.MILLISECONDS);
        saved.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Elsewhere/");
        saveClip();
        waitForSaves();
        assertEquals("a moved file kept a save from happening", 3, server.hits("/clip.mp4"));
    }

    /**
     * A double tap: the second save starts while the first is still fetching, before either is
     * written down, and only the first runs. Once the first ends, its hold on the reel is gone.
     */
    @Test public void aSecondSaveWhileTheFirstRunsIsSkipped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        MediaSave.Job job = (writer, progress) -> {
            runs.incrementAndGet();
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return Downloader.Result.ok("video/mp4");
        };
        Thread first = MediaSave.start(context, true, reel(), job);
        assertNotNull(first);
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        Thread second = MediaSave.start(context, true, reel(), job);
        assertNotNull(second);
        second.join(10_000);
        assertEquals("the second tap fetched too", 1, runs.get());
        go.countDown();
        first.join(10_000);
        waitForSaves();

        // The first published nothing, so nothing was written down: a save now runs again.
        Thread third = MediaSave.start(context, true, reel(), job);
        assertNotNull(third);
        third.join(10_000);
        waitForSaves();
        assertEquals("the first save kept its hold after it ended", 2, runs.get());
    }

    @Test public void aFailedOrCancelledSaveIsNeverRemembered() throws Exception {
        assertTrue(MediaSave.saveVideo(context, SavesForTests.renditions(server.origin() + "/gone.mp4"), null, reel()));
        waitForSaves();
        assertEquals(1, server.hits("/gone.mp4"));
        assertEquals(0, DownloadHistory.count(context));

        Thread cancelled = MediaSave.start(context, true, reel(), (writer, progress) -> {
            try (OutputStream out = writer.open("video/mp4")) {
                out.write(body(true));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            writer.abandon();
            return Downloader.Result.fail(Downloader.Status.CANCELLED, "the person saving cancelled it");
        });
        assertNotNull(cancelled);
        cancelled.join(10_000);
        waitForSaves();
        assertEquals(0, DownloadHistory.count(context));

        // An answer of OK with nothing published is no save to remember either.
        Thread empty = MediaSave.start(context, true, reel(), (writer, progress) -> Downloader.Result.ok("video/mp4"));
        assertNotNull(empty);
        empty.join(10_000);
        waitForSaves();
        assertEquals(0, DownloadHistory.count(context));
        assertFalse(DownloadHistory.file(context).exists());
    }

    @Test public void pagesKindsAndAccountsEachKeepTheirOwn() throws Exception {
        String owner = DownloadHistory.owner();
        PostDetails post = PostDetails.of("100");
        assertNotEquals(DownloadHistory.key(owner, post.onPage(1), DownloadHistory.Kind.PHOTO),
                DownloadHistory.key(owner, post.onPage(2), DownloadHistory.Kind.PHOTO));
        assertNotEquals(DownloadHistory.key(owner, post, DownloadHistory.Kind.VIDEO),
                DownloadHistory.key(owner, post, DownloadHistory.Kind.PICTURE));
        assertNotEquals(DownloadHistory.key(owner, post, DownloadHistory.Kind.VIDEO),
                DownloadHistory.key(owner, post, DownloadHistory.Kind.PHOTO));
        DownloadHistory.accounts = () -> ACCOUNT_B;
        assertNotEquals(owner, DownloadHistory.owner());
        DownloadHistory.accounts = () -> ACCOUNT_A;
        assertEquals(owner, DownloadHistory.owner());
        assertTrue(owner, owner.matches("[0-9a-f]{16}"));
        assertFalse(DownloadHistory.key(owner, post, DownloadHistory.Kind.VIDEO).contains(ACCOUNT_A));
        DownloadHistory.accounts = () -> "";
        assertEquals(DownloadHistory.UNKNOWN_ACCOUNT, DownloadHistory.owner());
        DownloadHistory.accounts = () -> { throw new IllegalStateException("no session"); };
        assertEquals(DownloadHistory.UNKNOWN_ACCOUNT, DownloadHistory.owner());
        DownloadHistory.accounts = () -> ACCOUNT_A;

        // Nothing to know a save by: always saved, never remembered.
        assertNull(DownloadHistory.key(null, post, DownloadHistory.Kind.VIDEO));
        assertNull(DownloadHistory.key(owner, PostDetails.of(null, "someone", new Date()), DownloadHistory.Kind.VIDEO));
        assertNull(DownloadHistory.key(owner, PostDetails.of("not-a-number"), DownloadHistory.Kind.VIDEO));
        assertNull(DownloadHistory.key(owner, PostDetails.profilePicture("someone"), DownloadHistory.Kind.PICTURE));
        assertNull(DownloadHistory.key(owner, PostDetails.NONE, DownloadHistory.Kind.AUDIO));

        // A video and its cover are two saves.
        server.serve("/clip.mp4", "video/mp4", body(true));
        server.serve("/cover.jpg", "image/jpeg", body(false));
        saveClip();
        waitForSaves();
        assertTrue(MediaSave.savePictureBySize(context, Collections.singletonList(
                new MediaSave.Rendition(server.origin() + "/cover.jpg", 1080, 1920, 0)), reel()));
        waitForSaves();
        assertEquals("the cover was taken for the video", 1, server.hits("/cover.jpg"));
        assertEquals(2, DownloadHistory.count(context));

        // Another account in front keeps a list of its own.
        DownloadHistory.accounts = () -> ACCOUNT_B;
        saveClip();
        waitForSaves();
        assertEquals("another account's save kept this one's from happening", 2, server.hits("/clip.mp4"));
        DownloadHistory.accounts = () -> ACCOUNT_A;
        saveClip();
        waitForSaves();
        assertEquals(2, server.hits("/clip.mp4"));
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());
    }

    // ---------------------------------------------------------------- Save all

    @Test public void saveAllSavesOnlyThePagesNotSavedBeforeAndSaysHowMany() throws Exception {
        server.serve("/p1.jpg", "image/jpeg", body(false));
        server.serve("/p2.jpg", "image/jpeg", body(false));
        server.serve("/p3.jpg", "image/jpeg", body(false));
        assertTrue(MediaSave.savePhoto(context, SavesForTests.renditions(server.origin() + "/p2.jpg"),
                PostDetails.of("202").onPage(2)));
        waitForSaves();
        assertEquals(1, server.hits("/p2.jpg"));

        MediaSave.BatchResult first = saveAll();
        assertEquals(2, first.saved);
        assertEquals(0, first.failed);
        assertEquals(1, first.skipped);
        assertEquals(1, first.already);
        assertFalse(first.cancelled);
        assertEquals(1, server.hits("/p2.jpg"));
        // One toast a line: Android 12 and newer cut a toast at two lines, which lost the second one.
        assertTrue(ShadowToast.showedToast("Saved 2. Failed 0. Skipped 1."));
        assertEquals("Already saved 1. Download again within 30 seconds for those.", ShadowToast.getTextOfLatestToast());
        assertEquals(3, DownloadHistory.count(context));

        // Asked again right away: the page passed over is saved, and the two just saved are passed over.
        MediaSave.BatchResult second = saveAll();
        assertEquals(1, second.saved);
        assertEquals(2, second.skipped);
        assertEquals(2, second.already);
        assertEquals(2, server.hits("/p2.jpg"));
        assertEquals(1, server.hits("/p1.jpg"));
        assertEquals(1, server.hits("/p3.jpg"));
    }

    // ---------------------------------------------------------------- off, clear, the file

    @Test public void withTheSwitchOffNothingIsRememberedOrSkipped() throws Exception {
        Settings.DOWNLOAD_SKIP_SAVED.save(false);
        server.serve("/clip.mp4", "video/mp4", body(true));
        saveClip();
        waitForSaves();
        saveClip();
        waitForSaves();
        assertEquals(2, server.hits("/clip.mp4"));
        assertEquals(0, DownloadHistory.count(context));
        assertFalse("the switch off wrote a history", DownloadHistory.file(context).exists());

        // Turned on later, nothing saved before is taken for saved.
        Settings.DOWNLOAD_SKIP_SAVED.save(true);
        saveClip();
        waitForSaves();
        assertEquals(3, server.hits("/clip.mp4"));
        assertEquals(1, DownloadHistory.count(context));
    }

    @Test public void clearForgetsEverySaveAndDeletesTheFile() throws Exception {
        server.serve("/clip.mp4", "video/mp4", body(true));
        saveClip();
        waitForSaves();
        assertTrue(DownloadHistory.file(context).isFile());

        assertTrue(DownloadHistory.clear(context));
        assertEquals(0, DownloadHistory.count(context));
        assertFalse(DownloadHistory.file(context).exists());
        assertEquals("the saved file went with the history", 1, gallery.rows.size());

        saveClip();
        waitForSaves();
        assertEquals("a cleared history still skipped", 2, server.hits("/clip.mp4"));
    }

    @Test public void theListHoldsFifteenThousandSaves() {
        // The README promises this many (#150 asked for room past 5,000).
        assertEquals(15_000, DownloadHistory.MAX_ENTRIES);
    }

    @Test public void theListKeepsTheNewestSavesAndReadsBackAfterARestart() throws Exception {
        // A file grown to twice the list, as appends and removals leave it.
        int written = 2 * DownloadHistory.MAX_ENTRIES;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < written; i++) {
            text.append("+\tk/").append(i).append("/0/v\t1000\tcontent://media/external/video/media/").append(i)
                    .append("\tMovies/Instagram\t\t0\n");
        }
        write(text.toString());
        int oldestKept = written - DownloadHistory.MAX_ENTRIES;
        assertEquals(DownloadHistory.MAX_ENTRIES, DownloadHistory.count(context));
        assertFalse(DownloadHistory.remembers(context, "k/" + (oldestKept - 1) + "/0/v"));
        assertTrue(DownloadHistory.remembers(context, "k/" + oldestKept + "/0/v"));

        // One more save drops the oldest, and the file is written afresh at the list's size.
        DownloadHistory.remember(context, "k/new/0/v", "content://media/external/video/media/99999",
                "Movies/Instagram", null);
        assertEquals(DownloadHistory.MAX_ENTRIES, DownloadHistory.count(context));
        assertFalse(DownloadHistory.remembers(context, "k/" + oldestKept + "/0/v"));
        assertTrue(DownloadHistory.remembers(context, "k/new/0/v"));
        List<String> lines = Files.readAllLines(DownloadHistory.file(context).toPath(), StandardCharsets.UTF_8);
        assertEquals(DownloadHistory.MAX_ENTRIES, lines.size());
        assertFalse(new File(DownloadHistory.file(context).getParentFile(), DownloadHistory.FILE + ".tmp").exists());

        // Read back as Instagram's next start does.
        DownloadHistory.forgetForTests();
        assertEquals(DownloadHistory.MAX_ENTRIES, DownloadHistory.count(context));
        assertTrue(DownloadHistory.remembers(context, "k/new/0/v"));
        assertTrue(DownloadHistory.remembers(context, "k/" + (oldestKept + 1) + "/0/v"));
        assertFalse(DownloadHistory.remembers(context, "k/" + oldestKept + "/0/v"));
    }

    @Test public void aDamagedLineIsSkippedAndTheRestIsRead() throws Exception {
        write("garbage\n"
                + "+\tonly\tthree\n"
                + "+\towner/1/0/v\tnot-a-time\tcontent://media/external/video/media/1\tMovies/Instagram\t\t0\n"
                + "+\towner/2/0/v\t1000\tcontent://media/external/video/media/2\tMovies/Instagram\tsomeone\t0\n"
                + "+\towner/3/0/v\t1000\tcontent://media/external/video/media/3\tMovies/Instagram\t\t0\n"
                + "-\towner/3/0/v\n"
                + "+\towner/4/0/v\t1000\t\tMovies/Instagram\t\t0\n");
        assertEquals(1, DownloadHistory.count(context));
        assertTrue(DownloadHistory.remembers(context, "owner/2/0/v"));
    }

    @Test public void aLineTooLongNotUtf8OrCutShortIsSkippedAndTheFileIsWrittenAfresh() throws Exception {
        char[] folder = new char[DownloadHistory.MAX_LINE_CHARS];
        Arrays.fill(folder, 'f');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(utf8(saveLine("owner/1/0/v", "")));
        bytes.write(utf8(saveLine("owner/2/0/v", "").replace("Movies/Instagram", new String(folder))));
        bytes.write(utf8(saveLine("owner/3/0/v", "")));
        // A poster whose bytes aren't UTF-8: 0xC3 starts a two-byte character that '(' can't end.
        bytes.write(utf8(saveLine("owner/4/0/v", "@").split("@")[0]));
        bytes.write(new byte[]{(byte) 0xC3, '('});
        bytes.write(utf8(saveLine("owner/4/0/v", "@").split("@")[1]));
        // The last line ends inside a write that was cut short.
        String cut = saveLine("owner/5/0/v", "");
        bytes.write(utf8(cut.substring(0, cut.length() - 1)));
        write(bytes.toByteArray());

        assertEquals(2, DownloadHistory.count(context));
        assertTrue(DownloadHistory.remembers(context, "owner/1/0/v"));
        assertTrue(DownloadHistory.remembers(context, "owner/3/0/v"));
        assertFalse("a line past the budget was read", DownloadHistory.remembers(context, "owner/2/0/v"));
        assertFalse("a line that isn't UTF-8 was read", DownloadHistory.remembers(context, "owner/4/0/v"));
        assertFalse("a line cut short was read", DownloadHistory.remembers(context, "owner/5/0/v"));

        // The next save writes the file afresh, without the damage, and it reads back after a restart.
        DownloadHistory.remember(context, "owner/6/0/v", "content://media/external/video/media/6", "Movies/Instagram", null);
        List<String> lines = Files.readAllLines(DownloadHistory.file(context).toPath(), StandardCharsets.UTF_8);
        assertEquals(lines.toString(), 3, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("+\towner/1/0/v\t"));
        assertTrue(lines.get(1), lines.get(1).startsWith("+\towner/3/0/v\t"));
        assertTrue(lines.get(2), lines.get(2).startsWith("+\towner/6/0/v\t"));
        DownloadHistory.forgetForTests();
        assertEquals(3, DownloadHistory.count(context));
    }

    @Test public void aSwollenFileIsReadOnlyToItsBudgetAndWrittenAfresh() throws Exception {
        byte[] swollen = new byte[(int) DownloadHistory.MAX_CHARS_READ];
        Arrays.fill(swollen, (byte) 'x');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(utf8(saveLine("owner/1/0/v", "")));
        bytes.write(swollen);
        bytes.write('\n');
        bytes.write(utf8(saveLine("owner/2/0/v", "")));
        write(bytes.toByteArray());

        assertEquals(1, DownloadHistory.count(context));
        assertTrue(DownloadHistory.remembers(context, "owner/1/0/v"));
        assertFalse("a line past the budget was read", DownloadHistory.remembers(context, "owner/2/0/v"));

        // What's saved next isn't lost past the budget: the file is written afresh and reads back whole.
        DownloadHistory.remember(context, "owner/3/0/v", "content://media/external/video/media/3", "Movies/Instagram", null);
        assertEquals(2, Files.readAllLines(DownloadHistory.file(context).toPath(), StandardCharsets.UTF_8).size());
        DownloadHistory.forgetForTests();
        assertEquals(2, DownloadHistory.count(context));
        assertTrue(DownloadHistory.remembers(context, "owner/3/0/v"));
    }

    @Test public void onAndroidNineTheFileItselfAnswers() throws Exception {
        File saved = File.createTempFile("IG_VID_", ".mp4", context.getCacheDir());
        DownloadHistory.Entry entry = new DownloadHistory.Entry(Uri.fromFile(saved).toString(), "Movies/Instagram",
                null, 0, 0);
        assertTrue(DownloadHistory.present(context, entry));
        assertTrue(saved.delete());
        assertFalse(DownloadHistory.present(context, entry));
    }

    @Test public void nothingOfTheHistoryReachesTheReport() throws Exception {
        server.serve("/clip.mp4", "video/mp4", body(true));
        saveClip();
        waitForSaves();
        saveClip();
        waitForSaves();
        assertEquals(ALREADY, ShadowToast.getTextOfLatestToast());
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains(MEDIA));
        assertFalse(report, report.contains(ACCOUNT_A));
        assertFalse(report, report.contains(DownloadHistory.owner()));
    }

    // ---------------------------------------------------------------- helpers

    /** A reel's details, as ReelDownload reads them: Instagram's {@code <pk>_<owner>} id, poster and time. */
    private static PostDetails reel() {
        return PostDetails.of(MEDIA + "_" + ACCOUNT_A, "someone", new Date(1_760_000_000_000L));
    }

    private boolean saveClip() {
        return MediaSave.saveVideo(context, SavesForTests.renditions(server.origin() + "/clip.mp4"), null, reel());
    }

    private MediaSave.BatchResult saveAll() throws Exception {
        List<MediaSave.Item> pages = Arrays.asList(
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/p1.jpg"), null,
                        PostDetails.of("201").onPage(1)),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/p2.jpg"), null,
                        PostDetails.of("202").onPage(2)),
                new MediaSave.Item(false, SavesForTests.renditions(server.origin() + "/p3.jpg"), null,
                        PostDetails.of("203").onPage(3)));
        CompletableFuture<MediaSave.BatchResult> ended = new CompletableFuture<>();
        assertTrue(MediaSave.saveBatch(context, pages, ended::complete));
        MediaSave.BatchResult result = ended.get(20, TimeUnit.SECONDS);
        waitForSaves();
        return result;
    }

    /** [text] as the history's file, and the list read again from it. */
    private void write(String text) throws IOException {
        write(utf8(text));
    }

    /** [bytes] as the history's file, and the list read again from it. */
    private void write(byte[] bytes) throws IOException {
        File file = DownloadHistory.file(context);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        DownloadHistory.forgetForTests();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A save's line for [key] in Movies/Instagram, posted by [poster], as the history writes it. */
    private static String saveLine(String key, String poster) {
        return "+\t" + key + "\t1000\tcontent://media/external/video/media/1\tMovies/Instagram\t" + poster + "\t0\n";
    }

    private static byte[] body(boolean video) {
        byte[] bytes = new byte[4096];
        byte[] head = video ? new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}
                : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private void waitForSaves() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (MediaSave.savesInFlight() != 0) {
            assertTrue("the save never ended", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * A gallery that answers for its own entries, as MediaStore does: the folder of a published
     * entry, nothing for a pending or deleted one, and nothing for a search of a whole collection.
     */
    public static final class Rows extends ContentProvider {
        final Map<Long, ContentValues> rows = new ConcurrentHashMap<>();
        private final AtomicLong nextId = new AtomicLong(1);

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = nextId.getAndIncrement();
            rows.put(id, new ContentValues(values));
            return ContentUris.withAppendedId(uri, id);
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            String[] columns = projection == null ? new String[0] : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            String last = uri.getLastPathSegment();
            if (last == null || !last.matches("[0-9]+")) return cursor;
            ContentValues row = rows.get(Long.parseLong(last));
            if (row == null || Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                return cursor;
            }
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) values[i] = row.get(columns[i]);
            cursor.addRow(values);
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return null;
        }
    }
    @Test public void aFailedPartialAppendIsRepairedBeforeTheNextSave() throws Exception {
        DownloadHistory.remember(context, "owner/1/0/v", "content://media/external/video/media/1", "Movies/Instagram", null);
        try (PartialAppend failure = new PartialAppend(25)) {
            try {
                DownloadHistory.remember(context, "owner/2/0/v", "content://media/external/video/media/2", "Movies/Instagram", null);
                throw new AssertionError("the partial append did not fail");
            } catch (IOException expected) {
                assertEquals("controlled partial append", expected.getMessage());
            }
        }
        DownloadHistory.remember(context, "owner/3/0/v", "content://media/external/video/media/3", "Movies/Instagram", null);
        assertTrue(DownloadHistory.remembers(context, "owner/3/0/v"));
        DownloadHistory.forgetForTests();
        assertTrue("a save before the failed write was lost", DownloadHistory.remembers(context, "owner/1/0/v"));
        assertTrue("the recovered save was appended onto a damaged tail and lost on restart",
                DownloadHistory.remembers(context, "owner/3/0/v"));
    }

    @Test public void aFailedForgetAppendIsRepairedBeforeTheNextSave() throws Exception {
        DownloadHistory.remember(context, "owner/1/0/v", "content://media/external/video/media/1", "Movies/Instagram", null);
        DownloadHistory.remember(context, "owner/2/0/v", "content://media/external/video/media/2", "Movies/Instagram", null);
        try (PartialAppend failure = new PartialAppend(3)) {
            // The provider has no row, so checking this entry forgets it and appends a tombstone.
            assertFalse(DownloadHistory.saved(context, "owner/1/0/v"));
        } finally {
            DownloadHistory.release("owner/1/0/v");
        }
        DownloadHistory.remember(context, "owner/3/0/v", "content://media/external/video/media/3", "Movies/Instagram", null);
        DownloadHistory.forgetForTests();
        assertFalse(DownloadHistory.remembers(context, "owner/1/0/v"));
        assertTrue(DownloadHistory.remembers(context, "owner/2/0/v"));
        assertTrue("a failed tombstone damaged the next completed save",
                DownloadHistory.remembers(context, "owner/3/0/v"));
    }

    /** A real prefix reaches disk before the same stream throws. Closing cannot finish the lost tail. */
    private static final class PartialAppend implements AutoCloseable {
        private final java.lang.reflect.Field field;
        private final Object previous;

        PartialAppend(int prefixBytes) throws Exception {
            field = DownloadHistory.class.getDeclaredField("appendForTests");
            field.setAccessible(true);
            previous = field.get(null);
            Object factory = java.lang.reflect.Proxy.newProxyInstance(DownloadHistory.class.getClassLoader(),
                    new Class<?>[]{field.getType()}, (proxy, method, args) -> {
                        if (!method.getName().equals("open")) throw new AssertionError(method);
                        return new FileOutputStream((File) args[0], true) {
                            private int left = prefixBytes;
                            @Override public void write(int value) throws IOException {
                                write(new byte[]{(byte) value}, 0, 1);
                            }
                            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                                int written = Math.min(left, count);
                                if (written > 0) { super.write(bytes, offset, written); left -= written; }
                                throw new IOException("controlled partial append");
                            }
                        };
                    });
            field.set(null, factory);
        }

        @Override public void close() throws Exception { field.set(null, previous); }
    }


}
