/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.jvm.functions.Function0;
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
import app.hushgram.extension.instagram.comment.CommentPhoto;
import app.hushgram.extension.instagram.comment.CommentPhotoNative;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The comment menu's Save row reaches the unchanged downloader, a real loopback transfer and the gallery writer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = {CarouselSaveTest.LocalCandidates.class, CarouselSaveTest.MediaBridge.class,
        CommentPhotoSaveTest.NativePhoto.class})
public class CommentPhotoSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private LocalServer server;
    private MediaSaveTest.Gallery gallery;
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();
    private final CountDownLatch release = new CountDownLatch(1);
    private final Set<File> oldFiles = new HashSet<>();
    private File legacy;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        Settings.SAVE_FOLDER.resetToDefault();
        if (Build.VERSION.SDK_INT == 28) {
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        server = new LocalServer();
        CarouselSaveTest.LocalCandidates.origin = server.origin();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("10.9.8.7")}) {
            @Override Refusal refusal(URL url) {
                return url.toString().startsWith(server.origin() + "/") ? null : super.refusal(url);
            }
        };
        gallery = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1), published);
        legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Instagram");
        if (legacy.isDirectory()) oldFiles.addAll(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
        SaveLeftovers.forgetSweepForTests();
    }

    @After public void close() throws Exception {
        release.countDown();
        for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
        waitForSave();
        server.close();
        MediaSave.policyForTests = null;
        MediaSave.detailsForTests = null;
        Settings.SAVE_COMMENT_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SAVE_FOLDER.resetToDefault();
        NativePhoto.selected = null;
        NativePhoto.photo = null;
        NativePhoto.typed = true;
        NativePhoto.videos = null;
        NativePhoto.author = null;
        NativePhoto.written = null;
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        HookStatus.clear();
        Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
        File[] files = legacy.listFiles();
        if (files != null) for (File file : files) if (!oldFiles.contains(file)) assertTrue(file.delete());
    }

    private static byte[] body() {
        byte[] bytes = new byte[4096];
        bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xd8; bytes[2] = (byte) 0xff; bytes[3] = (byte) 0xe0;
        return bytes;
    }

    private Row menu() {
        return menu("photo found 1");
    }

    /** Opens the menu on the comment's photo, which reads as [counted] on the report. */
    private Row menu(String counted) {
        byte[] body = body();
        server.serve("/small.jpg", "image/jpeg", body);
        server.serve("/large.jpg", "image/jpeg", body);
        NativePhoto.selected = new Object();
        NativePhoto.photo = new MediaSave.Item(false, Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/small.jpg", 640, 480, 0),
                new MediaSave.Rendition(server.origin() + "/large.jpg", 1440, 1080, 0)), null, null);
        List<?> stock = Collections.singletonList(new Object());
        HookStatus.clear();
        List<?> rows = CommentPhoto.rows(stock, NativePhoto.selected, context);
        assertEquals(2, rows.size());
        assertSame(stock.get(0), rows.get(0));
        assertEquals("opening the menu starts nothing", 0, MediaSave.savesInFlight());
        assertEquals("every read reached the photo", Collections.singletonList(FamilyNames.COMMENT_PHOTO
                + ": invoked 1, 0 found, 0 missing. Counted: " + counted), HookStatus.report());
        return (Row) rows.get(1);
    }

    private void waitForSave() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (MediaSave.savesInFlight() != 0) {
            assertTrue("photo save never ended", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void clean() {
        assertTrue(context.getSharedPreferences("hushgram_saves", 0).getStringSet("active_jobs", new HashSet<>()).isEmpty());
        assertTrue(context.getSharedPreferences("hushgram_saves", 0).getStringSet("pending_rows", new HashSet<>()).isEmpty());
        assertEquals(0, Objects.requireNonNull(DashSave.workFolder(context).listFiles()).length);
        assertTrue(SaveControl.running().isEmpty());
    }

    @Test public void explicitCommentPhotoTapSavesTheLargestSuppliedRenditionAndCleansUp() throws Exception {
        Row row = menu();
        // The row keeps what it was shown with, whatever the comment holds later.
        NativePhoto.photo = null;
        NativePhoto.selected = null;
        assertNull(row.callback.invoke());
        waitForSave();
        assertEquals(0, server.hits("/small.jpg"));
        assertEquals(1, server.hits("/large.jpg"));
        if (Build.VERSION.SDK_INT == 28) {
            List<File> saved = new ArrayList<>(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
            saved.removeAll(oldFiles);
            assertEquals(1, saved.size());
            assertEquals(body().length, saved.get(0).length());
            assertTrue(saved.get(0).getName().endsWith(".jpg"));
        } else {
            assertEquals(1, gallery.rows.size());
            assertArrayEquals(body(), published.toByteArray());
            assertEquals(0, gallery.rows.values().iterator().next().getAsInteger(MediaStore.MediaColumns.IS_PENDING).intValue());
        }
        clean();
        assertEquals(36, context.getApplicationInfo().targetSdkVersion);
    }

    /** The server leaves media_type out of a comment's own media: with no video there, the photo saves all the same. */
    @Test public void aCommentPhotoWithoutMediaTypeSaves() throws Exception {
        NativePhoto.typed = false;
        NativePhoto.videos = Collections.emptyList();
        Row row = menu("no media_type, still image 1, photo found 1");
        assertNull(row.callback.invoke());
        waitForSave();
        assertEquals(0, server.hits("/small.jpg"));
        assertEquals(1, server.hits("/large.jpg"));
        clean();
    }

    /** Without media_type, a media with video versions gets no Save row. */
    @Test public void aCommentMediaWithoutMediaTypeButWithVideoGetsNoRow() {
        NativePhoto.typed = false;
        NativePhoto.videos = Collections.singletonList(new Object());
        NativePhoto.selected = new Object();
        NativePhoto.photo = new MediaSave.Item(false, Collections.singletonList(
                new MediaSave.Rendition(server.origin() + "/large.jpg", 1440, 1080, 0)), null, null);
        List<?> stock = Collections.singletonList(new Object());
        HookStatus.clear();
        assertSame(stock, CommentPhoto.rows(stock, NativePhoto.selected, context));
        assertEquals(Collections.singletonList(FamilyNames.COMMENT_PHOTO
                + ": invoked 1, 0 found, 0 missing. Counted: no media_type, has video 1"), HookStatus.report());
        assertEquals(0, server.hits("/large.jpg"));
    }

    /** With Name saves by account and post time on, the photo is named after the comment's author and time. */
    @Test public void aCommentPhotoIsNamedAfterItsAuthorAndTime() throws Exception {
        Settings.SAVE_NAME_BY_POST.save(true);
        Calendar noon = new GregorianCalendar();
        noon.clear();
        noon.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        NativePhoto.author = "stevi.ous";
        NativePhoto.written = noon.getTimeInMillis() / 1000L;
        Row row = menu();
        assertNull(row.callback.invoke());
        waitForSave();
        assertEquals(Collections.singletonList("stevi.ous_20260901_120000.jpg"), savedNames());
        clean();
    }

    /** Off, the same comment's photo keeps the name it always had. */
    @Test public void offTheCommentPhotoKeepsItsUsualName() throws Exception {
        NativePhoto.author = "stevi.ous";
        NativePhoto.written = 1_788_000_000L;
        Row row = menu();
        assertNull(row.callback.invoke());
        waitForSave();
        List<String> names = savedNames();
        assertEquals(1, names.size());
        assertTrue(names.get(0), names.get(0).startsWith("IG_IMG_"));
        clean();
    }

    private List<String> savedNames() {
        List<String> names = new ArrayList<>();
        if (Build.VERSION.SDK_INT == 28) {
            for (File file : Objects.requireNonNull(legacy.listFiles())) if (!oldFiles.contains(file)) names.add(file.getName());
        } else {
            for (ContentValues values : gallery.rows.values()) names.add(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        }
        Collections.sort(names);
        return names;
    }

    @Test public void commentPhotoCancelUsesTheExistingControlAndRemovesAllTemporaryState() throws Exception {
        Row row = menu();
        CountDownLatch entered = new CountDownLatch(1);
        MediaSave.detailsForTests = details -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            } catch (InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
        };
        assertNull(row.callback.invoke());
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(1, SaveControl.running().size());
        SaveControl.cancel(SaveControl.running().get(0).id);
        release.countDown();
        waitForSave();
        assertTrue(gallery.rows.isEmpty());
        assertEquals(0, published.size());
        clean();
    }

    @Test @Config(sdk = 28) public void deniedLegacyStoragePermissionLeavesNoPendingPhoto() throws Exception {
        Row row = menu();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertNull(row.callback.invoke());
        waitForSave();
        assertTrue(gallery.rows.isEmpty());
        assertEquals(0, published.size());
        File[] files = legacy.listFiles();
        assertEquals(oldFiles.size(), files == null ? 0 : files.length);
        clean();
    }

    static final class Row {
        final Function0<?> callback;
        Row(Object callback) { this.callback = (Function0<?>) callback; }
    }

    /** The patched native reads, answering for one selected comment's own photo only. */
    @Implements(value = CommentPhotoNative.class, isInAndroidSdk = false)
    public static class NativePhoto {
        static Object selected;
        static MediaSave.Item photo;
        static boolean typed = true;
        static List<?> videos;
        static String author;
        static Long written;
        private static final Object RAW = new Object(), INFO = new Object();
        @Implementation protected static int selected(Object comment) { return comment != null && comment == selected ? 1 : 0; }
        @Implementation protected static Object raw(Object comment) { return RAW; }
        @Implementation protected static Object gif(Object raw) { return null; }
        @Implementation protected static Object author(Object raw) { return raw == RAW ? author : null; }
        @Implementation protected static Object createdAt(Object raw) { return raw == RAW ? written : null; }
        @Implementation protected static Object info(Object raw) { return raw == RAW ? INFO : null; }
        @Implementation protected static Object media(Object info) { return info == INFO ? photo : null; }
        @Implementation protected static Object kind(Object media) { return typed && media instanceof MediaSave.Item ? 1 : null; }
        @Implementation protected static int photoKind() { return 1; }
        @Implementation protected static Object mediaGif(Object media) { return null; }
        @Implementation protected static Object videoVersions(Object media) { return videos; }
        @Implementation protected static Object videoDuration(Object media) { return null; }
        @Implementation protected static Object newRow(Object callback) { return new Row(callback); }
        @Implementation protected static Object callback(Object row) { return row instanceof Row ? ((Row) row).callback : null; }
    }
}
