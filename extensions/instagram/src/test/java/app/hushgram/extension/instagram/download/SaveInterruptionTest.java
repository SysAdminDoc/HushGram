/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** Process-lifetime save ownership, independent of its number of temporary resources. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveInterruptionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private SaveProgressTest.Gallery gallery;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        ledger().edit().clear().commit();
        SaveLeftovers.forgetSweepForTests();
        gallery = Robolectric.setupContentProvider(SaveProgressTest.Gallery.class, MediaStore.AUTHORITY);
        ShadowToast.reset();
        SaveLeftovers.sweepOnce(context);
    }

    @After public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
        ledger().edit().clear().commit();
    }

    private SharedPreferences ledger() {
        return context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE);
    }

    private Set<String> active() {
        return ledger().getStringSet("active_jobs", new HashSet<>());
    }

    private void notice() throws Exception {
        SaveLeftovers.showInterrupted(context);
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test @Config(sdk = 28)
    public void sdk28InterruptedSaveRemovesItsHiddenStorageFileBeforeNotice() throws Exception {
        assertEquals(28, Build.VERSION.SDK_INT);
        context.getApplicationInfo().targetSdkVersion = 37;
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Instagram");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        File hidden = File.createTempFile(MediaStoreWriter.LEGACY_PENDING_PREFIX, ".mp4", folder);
        File finished = File.createTempFile("finished-", ".mp4", folder);
        // Android paths are rooted with '/', unlike the Windows backing files Robolectric uses.
        String root = hidden.toPath().getRoot().toString();
        String hiddenPath = hidden.getAbsolutePath().replace(File.separatorChar, '/');
        String finishedPath = finished.getAbsolutePath().replace(File.separatorChar, '/');
        if (root.matches("[A-Za-z]:[\\\\/]")) {
            hiddenPath = hiddenPath.substring(2);
            finishedPath = finishedPath.substring(2);
        }
        Uri hiddenUri = new Uri.Builder().scheme("file").path(hiddenPath).build();
        Uri finishedUri = new Uri.Builder().scheme("file").path(finishedPath).build();
        assertEquals(hidden.getCanonicalFile(), new File(Uri.parse(hiddenUri.toString()).getPath()).getCanonicalFile());
        try {
            SaveLeftovers.beginJob(context);
            assertTrue(SaveLeftovers.pending(context, hiddenUri));
            // A rename can beat the ledger cross-off. Finished media must survive that stale entry.
            assertTrue(SaveLeftovers.pending(context, finishedUri));
            SaveLeftovers.forgetSweepForTests();
            notice();

            assertFalse(hidden.exists());
            assertTrue(finished.exists());
            assertTrue(active().isEmpty());
            assertFalse(ledger().contains("pending_rows"));
            assertEquals(1, SaveLeftovers.interruptedCount());
            assertEquals("A save stopped. Reopen the media and save again.", ShadowToast.getTextOfLatestToast());
        } finally {
            hidden.delete();
            finished.delete();
        }
    }

    @Test public void oneKilledJobWithSeveralResourcesProducesOneConsumedNotice() throws Exception {
        SaveLeftovers.beginJob(context);
        File folder = DashSave.workFolder(context);
        File video = new File(folder, "video.part");
        File audio = new File(folder, "audio.part");
        assertTrue(video.createNewFile());
        assertTrue(audio.createNewFile());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri pending = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        SaveLeftovers.pending(context, pending);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        SaveControl.Save stopped = SaveControl.begin(context, true);
        int id = stopped.id;
        stopped.end();
        manager.notify(SaveControl.TAG, id, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build());

        SaveLeftovers.forgetSweepForTests();
        notice();

        assertEquals(1, SaveLeftovers.interruptedCount());
        assertEquals("A save stopped. Reopen the media and save again.", ShadowToast.getTextOfLatestToast());
        assertFalse(video.exists());
        assertFalse(audio.exists());
        assertTrue(gallery.rows.isEmpty());
        assertTrue(active().isEmpty());
        assertFalse(ledger().contains("interrupted_jobs"));
        assertNull(Shadows.shadowOf(manager).getNotification(SaveControl.TAG, id));
        ShadowToast.reset();
        notice();
        assertNull("a second opening repeated the consumed toast", ShadowToast.getTextOfLatestToast());
        assertEquals("settings retain the complete explanation in this process", 1, SaveLeftovers.interruptedCount());
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void eachLogicalJobCountsOnceAndCompletedJobsDoNotCount() throws Exception {
        String completed = SaveLeftovers.beginJob(context);
        SaveLeftovers.beginJob(context);
        SaveLeftovers.beginJob(context);
        SaveLeftovers.finishJob(context, completed);
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(2, SaveLeftovers.interruptedCount());
        assertEquals("2 saves stopped. Reopen the media and save again.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void completionAndHandledFailureRetireTheActualWorkersMarker() throws Exception {
        for (Downloader.Status status : new Downloader.Status[]{Downloader.Status.OK, Downloader.Status.NETWORK_ERROR}) {
            AtomicReference<Set<String>> during = new AtomicReference<>();
            Thread worker = MediaSave.start(context, true, "private-media-id", (writer, progress) -> {
                during.set(new HashSet<>(active()));
                return status == Downloader.Status.OK ? Downloader.Result.ok("video/mp4")
                        : Downloader.Result.fail(status, "controlled failure");
            });
            worker.join(10_000);
            assertFalse(worker.isAlive());
            assertNotNull(during.get());
            assertEquals(1, during.get().size());
            assertTrue(during.get().iterator().next().matches("[a-f0-9-]{36}"));
            assertTrue(active().isEmpty());
        }
        assertEquals(0, MediaSave.savesInFlight());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ShadowToast.reset();
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void anExplicitCancelAndAThrownJobLeaveNoInterruptionNotice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = MediaSave.start(context, true, (writer, progress) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            return Downloader.Result.fail(progress.cancelled() ? Downloader.Status.CANCELLED
                    : Downloader.Status.NETWORK_ERROR, "controlled cancel");
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(1, active().size());
            assertTrue(SaveControl.cancel(SaveControl.running().get(0).id));
        } finally { release.countDown(); worker.join(10_000); }
        assertFalse(worker.isAlive());
        assertTrue(active().isEmpty());
        Thread failed = MediaSave.start(context, true, (writer, progress) -> {
            throw new IllegalStateException("controlled job exception");
        });
        failed.join(10_000);
        assertFalse(failed.isAlive());
        assertTrue(active().isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ShadowToast.reset();
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void aTransientRetirementFailureCannotTurnCompletionOrCancelIntoAnInterruption() throws Exception {
        for (boolean throwing : new boolean[]{false, true}) {
            for (Downloader.Status terminal : new Downloader.Status[]{Downloader.Status.OK, Downloader.Status.CANCELLED}) {
                AtomicInteger retirementWrites = new AtomicInteger();
                SharedPreferences original = ledger();
                SharedPreferences faulty = (SharedPreferences) Proxy.newProxyInstance(
                        SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                            Object result = method.invoke(original, args);
                            if (!method.getName().equals("edit")) return result;
                            SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                            boolean[] retires = {false};
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class[]{SharedPreferences.Editor.class}, (wrapped, operation, parameters) -> {
                                        if (operation.getName().equals("remove") && "active_jobs".equals(parameters[0])) retires[0] = true;
                                        if (operation.getName().equals("commit") && retires[0]
                                                && retirementWrites.getAndIncrement() == 0) {
                                            if (throwing) throw new IllegalStateException("controlled retirement failure");
                                            return false; // Nothing reached the durable preferences.
                                        }
                                        Object value = operation.invoke(editor, parameters);
                                        return value == editor ? wrapped : value;
                                    });
                        });
                Context wrapped = new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                        return "hushgram_saves".equals(name) ? faulty : super.getSharedPreferences(name, mode);
                    }
                };
                Thread worker = MediaSave.start(wrapped, true, (writer, progress) -> terminal == Downloader.Status.OK
                        ? Downloader.Result.ok("video/mp4") : Downloader.Result.fail(terminal, "controlled cancel"));
                worker.join(10_000);
                assertFalse(worker.isAlive());
                assertEquals(2, retirementWrites.get());
                assertTrue(active().isEmpty());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                ShadowToast.reset();
                SaveLeftovers.forgetSweepForTests();
                notice();
                assertEquals(0, SaveLeftovers.interruptedCount());
                assertNull(ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void exhaustedRetirementWritesStillCannotTurnCompletionOrCancelIntoAnInterruption() throws Exception {
        for (Downloader.Status terminal : new Downloader.Status[]{Downloader.Status.OK, Downloader.Status.CANCELLED}) {
            AtomicInteger retirementWrites = new AtomicInteger();
            SharedPreferences original = ledger();
            SharedPreferences faulty = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                        Object result = method.invoke(original, args);
                        if (!method.getName().equals("edit")) return result;
                        SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                        boolean[] retires = {false};
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class[]{SharedPreferences.Editor.class}, (wrapped, operation, parameters) -> {
                                    if (operation.getName().equals("remove") && "active_jobs".equals(parameters[0])) retires[0] = true;
                                    if (operation.getName().equals("commit") && retires[0]) {
                                        retirementWrites.incrementAndGet();
                                        return false;
                                    }
                                    Object value = operation.invoke(editor, parameters);
                                    return value == editor ? wrapped : value;
                                });
                    });
            Context wrapped = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return "hushgram_saves".equals(name) ? faulty : super.getSharedPreferences(name, mode);
                }
            };
            Thread worker = MediaSave.start(wrapped, true, (writer, progress) -> terminal == Downloader.Status.OK
                    ? Downloader.Result.ok("video/mp4") : Downloader.Result.fail(terminal, "controlled cancel"));
            worker.join(10_000);
            assertFalse(worker.isAlive());
            assertEquals(2, retirementWrites.get());
            assertEquals("the original durable marker must still exist for this reproduction", 1, active().size());
            String token = active().iterator().next();
            File outcome = new File(new File(context.getFilesDir(), "hushgram-save-outcomes"), token);
            assertArrayEquals(new byte[]{1}, java.nio.file.Files.readAllBytes(outcome.toPath()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowToast.reset();
            SaveLeftovers.forgetSweepForTests();
            notice();
            assertEquals(0, SaveLeftovers.interruptedCount());
            assertNull(ShadowToast.getTextOfLatestToast());
            assertTrue(active().isEmpty());
            assertFalse(outcome.exists());
        }
    }

    @Test public void anUnreadableOutcomeNeverGuessesThatAJobWasInterrupted() throws Exception {
        String token = SaveLeftovers.beginJob(context);
        File outcome = new File(new File(context.getFilesDir(), "hushgram-save-outcomes"), token);
        java.nio.file.Files.write(outcome.toPath(), new byte[]{2});
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertEquals(1, active().size());
        assertNull(ShadowToast.getTextOfLatestToast());
        java.nio.file.Files.write(outcome.toPath(), new byte[]{0});
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(1, SaveLeftovers.interruptedCount());
        assertFalse(outcome.exists());
    }

    @Test public void aFailedCleanupDefersTheNoticeAndKeepsItsOpaqueOwnership() throws Exception {
        SaveLeftovers.beginJob(context);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri pending = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        SaveLeftovers.pending(context, pending);
        gallery.throwOnDelete = true;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
        assertEquals(1, active().size());
        assertFalse(gallery.rows.isEmpty());
        gallery.throwOnDelete = false;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(1, SaveLeftovers.interruptedCount());
        assertTrue(gallery.rows.isEmpty());
        assertTrue(active().isEmpty());
    }

    @Test @Config(sdk = {30, 37})
    public void anInaccessibleAbsentRowRetiresAfterAnExactQuery() throws Exception {
        String token = SaveLeftovers.beginJob(context);
        File marker = new File(new File(context.getFilesDir(), "hushgram-save-outcomes"), token);
        java.nio.file.Files.write(marker.toPath(), new byte[]{1});
        ContentValues pending = new ContentValues();
        pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri own = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending);
        Uri unrelated = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending);
        SaveLeftovers.pending(context, own);
        gallery.rows.remove(android.content.ContentUris.parseId(own));
        gallery.securityOnDelete = true;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(own, gallery.lastQuery);
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
        assertTrue(active().isEmpty());
        assertFalse(marker.exists());
        assertFalse(ledger().contains("pending_rows"));
        assertEquals(1, gallery.rows.size());
        assertTrue(gallery.rows.containsKey(android.content.ContentUris.parseId(unrelated)));
    }

    @Test @Config(sdk = {30, 37})
    public void anInaccessiblePublishedRowIsKeptButItsStaleLedgerRetires() throws Exception {
        String token = SaveLeftovers.beginJob(context);
        File marker = new File(new File(context.getFilesDir(), "hushgram-save-outcomes"), token);
        java.nio.file.Files.write(marker.toPath(), new byte[]{1});
        ContentValues published = new ContentValues();
        published.put(MediaStore.MediaColumns.IS_PENDING, 0);
        Uri own = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, published);
        SaveLeftovers.pending(context, own);
        gallery.securityOnDelete = true;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(own, gallery.lastQuery);
        assertTrue(active().isEmpty());
        assertFalse(marker.exists());
        assertFalse(ledger().contains("pending_rows"));
        assertEquals(1, gallery.rows.size());
        assertEquals(0, SaveLeftovers.interruptedCount());
    }

    @Test @Config(sdk = {30, 37})
    public void aDeleteExceptionNeverAcknowledgesAStillPendingRow() throws Exception {
        SaveLeftovers.beginJob(context);
        ContentValues pending = new ContentValues();
        pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri own = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending);
        SaveLeftovers.pending(context, own);
        gallery.securityOnDelete = true;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(own, gallery.lastQuery);
        assertEquals(1, active().size());
        assertEquals(java.util.Collections.singleton(own.toString()), ledger().getStringSet("pending_rows", null));
        assertEquals(1, gallery.rows.size());
        assertEquals(0, SaveLeftovers.interruptedCount());
    }

    @Test @Config(sdk = {30, 37})
    public void aDeleteExceptionWithUnknownQueryStateKeepsTheRetryRecord() throws Exception {
        SaveLeftovers.beginJob(context);
        ContentValues pending = new ContentValues();
        pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri own = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending);
        SaveLeftovers.pending(context, own);
        gallery.securityOnDelete = true;
        for (int failure : new int[]{1, 2, 3, 4}) {
            gallery.queryFailure = failure;
            SaveLeftovers.forgetSweepForTests();
            notice();
            assertEquals("query mode " + failure, own, gallery.lastQuery);
            assertEquals(1, active().size());
            assertEquals(java.util.Collections.singleton(own.toString()), ledger().getStringSet("pending_rows", null));
            assertEquals(1, gallery.rows.size());
            assertEquals(0, SaveLeftovers.interruptedCount());
        }
    }

    @Test public void aLegacyResourceLedgerDoesNotInventALogicalJobCount() throws Exception {
        File leftover = new File(DashSave.workFolder(context), "legacy.part");
        assertTrue(leftover.createNewFile());
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertFalse(leftover.exists());
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void aZeroDeletionKeepsTheUnpublishedRowAndDefersTheNotice() throws Exception {
        SaveLeftovers.beginJob(context);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri pending = gallery.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        SaveLeftovers.pending(context, pending);
        gallery.refuseDeletion = true;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertEquals(1, active().size());
        assertEquals(java.util.Collections.singleton(pending.toString()),
                ledger().getStringSet("pending_rows", new HashSet<>()));
        gallery.refuseDeletion = false;
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(1, SaveLeftovers.interruptedCount());
        assertTrue(gallery.rows.isEmpty());
    }

    @Test public void aWorkFileThatCannotBeDeletedDefersItsJobNotice() throws Exception {
        SaveLeftovers.beginJob(context);
        File directory = new File(DashSave.workFolder(context), "held.part");
        assertTrue(directory.mkdir());
        File nested = new File(directory, "held");
        assertTrue(nested.createNewFile());
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertEquals(1, active().size());
        assertNull(ShadowToast.getTextOfLatestToast());
        assertTrue(nested.delete());
        SaveLeftovers.forgetSweepForTests();
        notice();
        assertFalse(directory.exists());
        assertEquals(1, SaveLeftovers.interruptedCount());
    }

    @Test public void anUnreadableWorkFolderDoesNotAcknowledgeCleanup() throws Exception {
        SaveLeftovers.beginJob(context);
        File blockedCache = File.createTempFile("blocked-cache", ".part", context.getCacheDir());
        Context wrapped = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return blockedCache; }
        };
        try {
            SaveLeftovers.forgetSweepForTests();
            SaveLeftovers.showInterrupted(wrapped);
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, SaveLeftovers.interruptedCount());
            assertEquals(1, active().size());
            assertNull(ShadowToast.getTextOfLatestToast());
            SaveLeftovers.forgetSweepForTests();
            notice();
            assertEquals(1, SaveLeftovers.interruptedCount());
        } finally { assertTrue(blockedCache.delete()); }
    }

    @Test public void theJobLedgerContainsOnlyOpaqueTokensAndAConsumedCount() throws Exception {
        String marker = SaveLeftovers.beginJob(context);
        assertTrue(marker.matches("[a-f0-9-]{36}"));
        assertEquals(java.util.Collections.singleton("active_jobs"), ledger().getAll().keySet());
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        assertEquals(java.util.Collections.singleton("interrupted_jobs"), ledger().getAll().keySet());
        assertEquals(1, ledger().getInt("interrupted_jobs", 0));
        notice();
        assertTrue(ledger().getAll().isEmpty());
    }

    @Test public void aFailedAcknowledgementRetainsTheNoticeForTheNextOpening() throws Exception {
        SaveLeftovers.beginJob(context);
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        SharedPreferences original = ledger();
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences faulty = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                    Object result = method.invoke(original, args);
                    if (!method.getName().equals("edit")) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class[]{SharedPreferences.Editor.class}, (wrapped, operation, parameters) -> {
                                Object value = operation.invoke(editor, parameters);
                                if (operation.getName().equals("commit") && commits.getAndIncrement() == 0) return false;
                                return value == editor ? wrapped : value;
                            });
                });
        Context wrapped = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return faulty; }
        };
        SaveLeftovers.showInterrupted(wrapped);
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, SaveLeftovers.interruptedCount());
        assertEquals(1, original.getInt("interrupted_jobs", 0));
        assertNull(ShadowToast.getTextOfLatestToast());
        SaveLeftovers.showInterrupted(wrapped);
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, SaveLeftovers.interruptedCount());
        assertFalse(original.contains("interrupted_jobs"));
        assertEquals("A save stopped. Reopen the media and save again.", ShadowToast.getTextOfLatestToast());
    }
}
