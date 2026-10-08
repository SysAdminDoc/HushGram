/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.ContentUris;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Save profile picture's row reaches the downloader, a real loopback transfer and the gallery writer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = CarouselSaveTest.LocalCandidates.class)
public class ProfilePictureSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private LocalServer server;
    private MediaSaveTest.Gallery gallery;
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();
    private final Set<File> oldFiles = new HashSet<>();
    private final List<PostDetails> named = new ArrayList<>();
    private File legacy;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SAVE_PROFILE_PICTURES.save(true);
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
        MediaSave.detailsForTests = named::add;
        gallery = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1), published);
        legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Instagram");
        if (legacy.isDirectory()) oldFiles.addAll(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
        SaveLeftovers.forgetSweepForTests();
    }

    @After public void close() throws Exception {
        for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
        waitForSave();
        server.close();
        MediaSave.policyForTests = null;
        MediaSave.detailsForTests = null;
        Settings.SAVE_PROFILE_PICTURES.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
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

    private void waitForSave() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (MediaSave.savesInFlight() != 0) {
            assertTrue("picture save never ended", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Both sizes name their size in the address, as Instagram's profile pictures do. */
    private List<MediaSave.Rendition> sizes() {
        server.serve("/full.jpg", "image/jpeg", body());
        server.serve("/shown.jpg", "image/jpeg", body());
        return Arrays.asList(
                new MediaSave.Rendition(server.origin() + "/shown.jpg?stp=dst-jpg_s150x150", 150, 150, 0),
                new MediaSave.Rendition(server.origin() + "/full.jpg?stp=dst-jpg_s1080x1080", 1080, 1080, 0));
    }

    @Test public void aTapSavesTheLargestStatedSize() throws Exception {
        List<MediaSave.Rendition> sizes = sizes();
        View.OnClickListener[] row = new View.OnClickListener[1];
        ProfilePicture.Native reads = new Account(sizes, (listener) -> row[0] = listener);
        ProfilePicture.offer(new Object(), new Object(), context, reads, MediaSave::savePictureBySize);
        assertEquals("opening the menu starts nothing", 0, MediaSave.savesInFlight());

        row[0].onClick(null);
        waitForSave();
        assertEquals(0, server.hits("/shown.jpg"));
        assertEquals(1, server.hits("/full.jpg"));
        if (Build.VERSION.SDK_INT == 28) {
            List<File> saved = new ArrayList<>(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
            saved.removeAll(oldFiles);
            assertEquals(1, saved.size());
            assertEquals(body().length, saved.get(0).length());
            assertTrue(saved.get(0).getName().endsWith(".jpg"));
        } else {
            assertEquals(1, gallery.rows.size());
            assertArrayEquals(body(), published.toByteArray());
        }
        assertEquals("someone", named.get(0).owner);
        assertTrue("named as a profile picture", named.get(0).profile);
    }

    /** Why the row doesn't use the photo save: its ranking takes these addresses for thumbnails. */
    @Test public void thePhotoSaveTurnsTheseSizesDown() {
        assertFalse(MediaSave.savePhoto(context, sizes(), PostDetails.NONE));
        assertEquals(0, server.hits("/full.jpg"));
    }

    @Test public void noAddressOnMetasServersSavesNothing() {
        assertFalse(MediaSave.savePictureBySize(context, Arrays.asList(
                new MediaSave.Rendition("https://example.com/full.jpg", 1080, 1080, 0),
                new MediaSave.Rendition("ftp://scontent.cdninstagram.com/full.jpg", 1080, 1080, 0)), PostDetails.NONE));
        assertFalse(MediaSave.savePictureBySize(context, null, PostDetails.NONE));
        assertFalse(MediaSave.savePictureBySize(null, sizes(), PostDetails.NONE));
        assertEquals(0, MediaSave.savesInFlight());
    }

    /** One account with a full size and a shown picture, whose row goes to [added]. */
    private static final class Account implements ProfilePicture.Native {
        final List<MediaSave.Rendition> sizes;
        final java.util.function.Consumer<View.OnClickListener> added;

        Account(List<MediaSave.Rendition> sizes, java.util.function.Consumer<View.OnClickListener> added) {
            this.sizes = sizes;
            this.added = added;
        }

        @Override public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
            added.accept(listener);
            return true;
        }
        @Override public Object fullSize(Object user) { return sizes.get(1); }
        @Override public String fullSizeUrl(Object info) { return ((MediaSave.Rendition) info).url; }
        @Override public int fullSizeWidth(Object info) { return ((MediaSave.Rendition) info).width; }
        @Override public int fullSizeHeight(Object info) { return ((MediaSave.Rendition) info).height; }
        @Override public Object shown(Object user) { return sizes.get(0); }
        @Override public String shownUrl(Object image) { return ((MediaSave.Rendition) image).url; }
        @Override public int shownWidth(Object image) { return ((MediaSave.Rendition) image).width; }
        @Override public int shownHeight(Object image) { return ((MediaSave.Rendition) image).height; }
        @Override public String username(Object user) { return "someone"; }
        @Override public String biography(Object user) { return null; }
    }
}
