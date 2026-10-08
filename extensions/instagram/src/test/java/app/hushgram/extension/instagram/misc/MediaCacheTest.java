/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;

/** What Clear the media cache deletes, what it keeps, and when it leaves the cache alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MediaCacheTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final long NOW = 1_800_000_000_000L;
    private static final long OLD = NOW - 10 * 60_000;

    private Context context;
    private File cache;

    @Before
    public void setUp() {
        HookStatus.clear();
        MediaCache.restartForTests();
        context = RuntimeEnvironment.getApplication();
        cache = context.getCacheDir();
    }

    @After
    public void tearDown() {
        HookStatus.clear();
        MediaCache.restartForTests();
    }

    private File file(String path, long bytes, long modified) throws IOException {
        File file = new File(cache, path);
        file.getParentFile().mkdirs();
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(bytes);
        }
        assertTrue(file.setLastModified(modified));
        return file;
    }

    @Test
    public void overTheLimitItDeletesOldImagesAndLeavesTheVideosForTheNextStart() throws IOException {
        File image = file("image_scoped/0/a.jpg", 400, OLD);
        File legacy = file("images/b.jpg", 100, OLD);
        File writing = file("images/c.jpg", 300, NOW - 5_000);
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 500, OLD);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File save = file("hushgram-save/reel.mp4", 700, OLD);

        assertEquals(500, MediaCache.clearIfOver(context, 1_000, NOW));

        assertFalse(image.exists());
        assertFalse(legacy.exists());
        assertTrue("an image still being written stays", writing.exists());
        assertTrue("no video goes while Instagram runs", span.exists());
        assertTrue(index.exists());
        assertTrue("the videos go at the next start", MediaCache.videosWaiting(context));
        assertTrue("HushGram's own folder stays", save.exists());
        assertTrue("the folders stay", new File(cache, "image_scoped/0").isDirectory());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.CLEARED + " 1"));
    }

    /**
     * The first time Instagram names the video cache's folders in a process, before its player has
     * built the cache, the video cache a clear asked for goes: all of it, index and metadata with it.
     */
    @Test
    public void theNextStartClearsTheVideos() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File index = file("ExoPlayerCacheDir/videocache/cached_content_index.exi", 50, OLD);
        File prefetch = file("ExoPlayerCacheDir/videoprefetchcache/3.0.1.v3.exo", 40, OLD);
        File metadata = file("ExoPlayerCacheDir/videocachemetadata/meta", 20, OLD);
        File image = file("image_scoped/a.jpg", 100, NOW - 5_000);
        File responses = file("http_responses/feed", 70, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 1_000, NOW));

        MediaCache.beforeVideoCache(cache.getPath(), () -> true);
        Utils.awaitBackgroundTasksForTests();

        for (File gone : new File[] {span, index, prefetch, metadata}) assertFalse(gone.getPath(), gone.exists());
        assertFalse(new File(cache, MediaCache.VIDEO_FOLDER).exists());
        assertFalse("the request is used up", MediaCache.videosWaiting(context));
        assertEquals("nothing moved aside is left", 0, leftovers().length);
        assertTrue(image.exists());
        assertTrue(responses.exists());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(MediaCache.VIDEOS_CLEARED + " 1"));
    }

    /** Once Instagram has named the folders in this process, its player may hold them, so nothing more goes. */
    @Test
    public void onlyTheFirstCallInAProcessClears() throws Exception {
        MediaCache.beforeVideoCache(cache.getPath());
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));
        assertTrue(MediaCache.videosWaiting(context));

        MediaCache.beforeVideoCache(cache.getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertTrue("still waiting for the next start", MediaCache.videosWaiting(context));
    }

    /** Without a clear asking, a start leaves the videos, and only deletes what a start before it moved aside. */
    @Test
    public void aStartNobodyAskedForKeepsTheVideos() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File moved = file(MediaCache.OLD_VIDEOS + "1/videocache/2.0.1.v3.exo", 900, OLD);

        MediaCache.beforeVideoCache(cache.getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertFalse(moved.exists());
        assertEquals(0, leftovers().length);
    }

    /**
     * A clear over the limit asked for the videos while the switch was on. A start that can't read
     * the settings yet leaves the note for a later one, and a start after the switch was turned off
     * drops it and keeps the videos, while what an earlier start moved aside still goes.
     */
    @Test
    public void aClearOverTheLimitWaitsForTheSwitch() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 100, NOW));
        assertTrue(MediaCache.videosWaiting(context));

        MediaCache.beforeVideoCache(cache.getPath(), () -> null);
        Utils.awaitBackgroundTasksForTests();
        assertTrue(span.exists());
        assertTrue("it waits for a start that can read the switch", MediaCache.videosWaiting(context));

        File moved = file(MediaCache.OLD_VIDEOS + "1/videocache/2.0.1.v3.exo", 900, OLD);
        MediaCache.restartForTests();
        MediaCache.beforeVideoCache(cache.getPath(), () -> false);
        Utils.awaitBackgroundTasksForTests();
        assertTrue(span.exists());
        assertFalse("the switch is off, so the note goes", MediaCache.videosWaiting(context));
        assertFalse(moved.exists());
        assertFalse(HookStatus.report().toString().contains(MediaCache.VIDEOS_CLEARED));
    }

    /**
     * Before HushGram's settings are ready the switch is read from the settings file itself, so a
     * start that names the video cache that early still drops or carries out a clear over the limit.
     * Paused, by the switch, safe mode or the marker file, it reads as off, the way the setting would.
     * After a start that died young it reads as nothing: this start may turn safe mode on.
     */
    @Test
    public void beforeTheSettingsAreReadyTheSavedSwitchAnswers() throws Exception {
        assertEquals(Settings.CLEAR_MEDIA_CACHE.key, MediaCache.SWITCH_KEY);
        assertEquals(BaseSettings.PAUSED.key, MediaCache.PAUSED_KEY);
        assertEquals(BaseSettings.SAFE_MODE.key, MediaCache.SAFE_MODE_KEY);
        assertEquals(null, MediaCache.savedAnswer(null));
        assertEquals("Android's own application is there before any onCreate", context, MediaCache.currentApplication());

        // The record this sandbox's first start left, if this class came first.
        File record = new File(context.getFilesDir(), HushgramPause.START_RECORD_NAME);
        record.delete();
        SharedPreferences saved = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        saved.edit().clear().commit();
        assertEquals(Boolean.FALSE, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.SWITCH_KEY, true).commit();
        assertEquals(Boolean.TRUE, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.PAUSED_KEY, true).commit();
        assertEquals(Boolean.FALSE, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.PAUSED_KEY, false).putBoolean(MediaCache.SAFE_MODE_KEY, true).commit();
        assertEquals(Boolean.FALSE, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.SAFE_MODE_KEY, false).commit();
        File marker = new File(context.getExternalFilesDir(null), HushgramPause.MARKER_FILE_NAME);
        assertTrue(marker.createNewFile());
        assertEquals(Boolean.FALSE, MediaCache.savedAnswer(context));
        assertTrue(marker.delete());

        // The last start died young: whether safe mode comes on is this start's to decide, later.
        assertTrue(record.createNewFile());
        assertEquals(null, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.SAFE_MODE_KEY, true).commit();
        assertEquals("safe mode already on still reads as off", Boolean.FALSE, MediaCache.savedAnswer(context));
        saved.edit().putBoolean(MediaCache.SAFE_MODE_KEY, false).commit();
        File over = file("ExoPlayerCacheDir/videocache/2.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 100, NOW));
        MediaCache.beforeVideoCache(cache.getPath(), () -> MediaCache.savedAnswer(context));
        Utils.awaitBackgroundTasksForTests();
        assertTrue("the note waits for a start that can tell", over.exists());
        assertTrue(MediaCache.videosWaiting(context));
        assertTrue(record.delete());
        MediaCache.restartForTests();

        // Saved on: the clear over the limit happens at a start that names the cache that early.
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 100, NOW));
        MediaCache.beforeVideoCache(cache.getPath(), () -> MediaCache.savedAnswer(context));
        Utils.awaitBackgroundTasksForTests();
        assertFalse(span.exists());
        assertFalse(MediaCache.videosWaiting(context));

        // Saved off: the next one's note goes, and the videos stay.
        File again = file("ExoPlayerCacheDir/videocache/3.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearIfOver(context, 100, NOW));
        saved.edit().putBoolean(MediaCache.SWITCH_KEY, false).commit();
        MediaCache.restartForTests();
        MediaCache.beforeVideoCache(cache.getPath(), () -> MediaCache.savedAnswer(context));
        Utils.awaitBackgroundTasksForTests();
        assertTrue(again.exists());
        assertFalse(MediaCache.videosWaiting(context));
        saved.edit().clear().commit();
    }

    /** Clear now asked for the videos itself, so they go at the next start whatever the switch says. */
    @Test
    public void clearNowsVideosGoWithTheSwitchOff() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));

        MediaCache.beforeVideoCache(cache.getPath(), () -> false);
        Utils.awaitBackgroundTasksForTests();

        assertFalse(span.exists());
        assertFalse(MediaCache.videosWaiting(context));
        assertEquals(0, leftovers().length);
    }

    /**
     * A video cache that's a link isn't moved. Both notes go, so later starts don't keep trying, and
     * what an earlier start moved aside still goes.
     */
    @Test
    public void aLinkedVideoCacheDropsTheNotes() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        File moved = file(MediaCache.OLD_VIDEOS + "1/videocache/2.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));
        assertEquals(0, MediaCache.clearIfOver(context, 100, NOW));
        assertTrue(new File(cache, MediaCache.VIDEOS_AT_START).isFile());
        assertTrue(new File(cache, MediaCache.VIDEOS_OVER_LIMIT).isFile());
        MediaCache.linked = folder -> folder.getName().equals(MediaCache.VIDEO_FOLDER);

        MediaCache.beforeVideoCache(cache.getPath(), () -> true);
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertFalse(MediaCache.videosWaiting(context));
        assertFalse(moved.exists());
    }

    /** A folder Instagram hands over that isn't there, or none at all, changes nothing and doesn't throw. */
    @Test
    public void anOddFolderChangesNothing() throws Exception {
        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, OLD);
        assertEquals(0, MediaCache.clearNow(context));

        MediaCache.beforeVideoCache(null);
        MediaCache.restartForTests();
        MediaCache.beforeVideoCache(new File(cache, "elsewhere").getPath());
        Utils.awaitBackgroundTasksForTests();

        assertTrue(span.exists());
        assertTrue(MediaCache.videosWaiting(context));
    }

    private File[] leftovers() {
        File[] found = cache.listFiles((parent, name) -> name.startsWith(MediaCache.OLD_VIDEOS));
        return found == null ? new File[0] : found;
    }

    /** Everything outside the two caches stays, however big and old, and doesn't count toward the limit. */
    @Test
    public void filesOutsideTheMediaCachesSurvive() throws IOException {
        File image = file("image_scoped/a.jpg", 400, OLD);
        File responses = file("http_responses/feed", 5_000, OLD);
        File upload = file("pending_media/upload.tmp", 5_000, OLD);
        File loose = file("cached_content_index.exi", 5_000, OLD);
        File looseSpan = file("other/1.0.1.v3.exo", 5_000, OLD);
        File external = new File(context.getExternalCacheDir(), "ExoPlayerCacheDir/videocache/1.exo");
        external.getParentFile().mkdirs();
        assertTrue(external.createNewFile() || external.exists());
        assertTrue(external.setLastModified(OLD));

        assertEquals("only the image counts, and it's under the limit", 0, MediaCache.clearIfOver(context, 1_000, NOW));
        assertTrue(image.exists());

        assertEquals(400, MediaCache.clearIfOver(context, 100, NOW));
        assertFalse(image.exists());
        for (File kept : new File[] {responses, upload, loose, looseSpan, external}) assertTrue(kept.getPath(), kept.exists());
    }

    @Test
    public void underTheLimitNothingGoes() throws IOException {
        File image = file("image_scoped/a.jpg", 400, OLD);
        file("hushgram-save/reel.mp4", 5_000, OLD);

        assertEquals("HushGram's own files don't count toward the limit", 0, MediaCache.clearIfOver(context, 1_000, NOW));

        assertTrue(image.exists());
    }

    /** Clear now ignores the limit and follows the same rules: images now, videos at the next start. */
    @Test
    public void clearNowIgnoresTheLimit() throws IOException {
        long old = System.currentTimeMillis() - 2 * MediaCache.SETTLE_MILLIS;
        File image = file("image_scoped/a.jpg", 40, old);
        File responses = file("http_responses/feed", 70, old);
        long freed = MediaCache.clearNow(context);
        assertEquals(40, freed);
        assertFalse(image.exists());
        assertTrue("a file outside the media caches stays", responses.exists());
        assertFalse("no videos, so nothing waits for a start", MediaCache.videosWaiting(context));

        File span = file("ExoPlayerCacheDir/videocache/1.0.1.v3.exo", 900, old);
        assertEquals(0, MediaCache.clearNow(context));
        assertTrue(span.exists());
        assertTrue(MediaCache.videosWaiting(context));
    }

    @Test
    public void offOrThrowingQueuesNothing() throws IOException {
        File image = file("images/a.jpg", 400, OLD);

        assertFalse(MediaCache.onBackground(context, () -> false));
        assertFalse(MediaCache.onBackground(context, () -> {
            throw new IllegalStateException("settings went away");
        }));

        assertTrue(image.exists());
        assertFalse(HookStatus.missing(FamilyNames.MEDIA_CACHE).isEmpty());
    }

    @Test
    public void withoutThePatchItDoesntWatch() {
        MediaCache.watch(context);
        assertTrue(HookStatus.missing(FamilyNames.MEDIA_CACHE).toString(), HookStatus.missing(FamilyNames.MEDIA_CACHE).isEmpty());
    }
}
