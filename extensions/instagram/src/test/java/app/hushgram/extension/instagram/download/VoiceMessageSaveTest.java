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

/** A voice message's Save reaches the downloader, a real loopback transfer and the phone's audio files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = CarouselSaveTest.LocalCandidates.class)
public class VoiceMessageSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private LocalServer server;
    private MediaSaveTest.Gallery gallery;
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();
    private final Set<File> oldFiles = new HashSet<>();
    private File legacy;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.DOWNLOAD_VOICE_MESSAGES.save(true);
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_NAME_BY_POST.resetToDefault();
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
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 1), published);
        legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Instagram");
        if (legacy.isDirectory()) oldFiles.addAll(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
        SaveLeftovers.forgetSweepForTests();
    }

    @After public void close() throws Exception {
        for (SaveControl.Running save : SaveControl.running()) SaveControl.cancel(save.id);
        waitForSave();
        server.close();
        MediaSave.policyForTests = null;
        Settings.DOWNLOAD_VOICE_MESSAGES.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        HookStatus.clear();
        Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
        File[] files = legacy.listFiles();
        if (files != null) for (File file : files) if (!oldFiles.contains(file)) assertTrue(file.delete());
    }

    /** An M4A file's first box, as Instagram's voice recordings start. */
    private static byte[] body() {
        byte[] bytes = new byte[4096];
        bytes[3] = 0x18;
        System.arraycopy("ftypM4A ".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 4, 8);
        return bytes;
    }

    private void waitForSave() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (MediaSave.savesInFlight() != 0) {
            assertTrue("recording save never ended", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Meta calls its sound files video/mp4 as often as audio/mp4; either way the save is an M4A recording. */
    @Test public void aTapSavesTheRecordingWithTheAudioFiles() throws Exception {
        server.serve("/voice.mp4", "video/mp4", body());
        String recording = server.origin() + "/voice.mp4";
        VoiceMessage.Native reads = new VoiceMessage.Native() {
            @Override public String audio(Object message) { return recording; }
            @Override public String viewMode(Object message) { return "permanent"; }
        };
        assertTrue(VoiceMessage.save(context, new Object(), reads, MediaSave::saveAudio));
        waitForSave();
        assertEquals(1, server.hits("/voice.mp4"));
        if (Build.VERSION.SDK_INT == 28) {
            List<File> saved = new ArrayList<>(Arrays.asList(Objects.requireNonNull(legacy.listFiles())));
            saved.removeAll(oldFiles);
            assertEquals(1, saved.size());
            assertEquals(body().length, saved.get(0).length());
            assertTrue(saved.get(0).getName(), saved.get(0).getName().matches("IG_AUD_\\d{8}_\\d{6}\\.m4a"));
        } else {
            assertEquals(1, gallery.rows.size());
            assertEquals(Arrays.asList(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 1)), gallery.inserts);
            ContentValues row = gallery.rows.values().iterator().next();
            assertEquals("audio/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals(Environment.DIRECTORY_RECORDINGS + "/Instagram", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertTrue(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
                    row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).matches("IG_AUD_\\d{8}_\\d{6}\\.m4a"));
            assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertArrayEquals(body(), published.toByteArray());
        }
    }

    /** A page or anything else that doesn't start like an MP4 file is never kept as a recording. */
    @Test public void aBodyThatIsntARecordingIsntSaved() throws Exception {
        server.serve("/voice.mp4", "audio/mp4", "<html>sign in</html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(MediaSave.saveAudio(context, server.origin() + "/voice.mp4", PostDetails.NONE));
        waitForSave();
        assertEquals(1, server.hits("/voice.mp4"));
        assertTrue("nothing reached the gallery", gallery.rows.isEmpty());
        File[] files = legacy.listFiles();
        assertTrue("nothing reached the audio folder", files == null || oldFiles.containsAll(Arrays.asList(files)));
    }

    @Test public void noAddressOnMetasServersSavesNothing() {
        assertFalse(MediaSave.saveAudio(context, "https://example.com/voice.mp4", PostDetails.NONE));
        assertFalse(MediaSave.saveAudio(context, "ftp://cdn.fbsbx.com/voice.mp4", PostDetails.NONE));
        assertFalse(MediaSave.saveAudio(context, null, PostDetails.NONE));
        assertFalse(MediaSave.saveAudio(null, server.origin() + "/voice.mp4", PostDetails.NONE));
        assertEquals(0, MediaSave.savesInFlight());
    }

    /** The notification and the settings list call it what it is. */
    @Test public void aRunningRecordingSaveIsCalledAVoiceMessage() {
        SaveControl.Save save = SaveControl.beginAudio(context);
        try {
            SaveControl.Running running = SaveControl.running().get(0);
            assertTrue(running.audio);
            assertFalse(running.video);
            assertEquals("Saving a voice message", SaveControl.title(running));
            assertEquals("Cancel saving this voice message", SaveControl.cancelDescription(running));
        } finally {
            save.end();
        }
    }

    @Test public void recordingsGoToRecordingsFromAndroid12AndMusicBefore() {
        assertEquals(Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC,
                MediaStoreWriter.audioDirectory());
    }
}
