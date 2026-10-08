/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The saved points stay within their bounds: at most {@link ResumePoints#MAX_POINTS}, the least
 * recently saved going first, none older than {@link ResumePoints#KEEP_MS}, and a value that
 * doesn't read as a point is dropped from the file rather than kept or trusted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ResumePointsTest {
    private static final long NOW = 1_800_000_000_000L;
    private SharedPreferences file;

    @Before
    public void start() {
        file = RuntimeEnvironment.getApplication().getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        file.edit().clear().commit();
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void failedCleanupWritesRetryForNewAndLoadedStores() {
        for (boolean loaded : new boolean[]{false, true}) {
            file.edit().clear().putString("old", ResumePoints.encode(90_000, NOW)).commit();
            AtomicBoolean fail = new AtomicBoolean(true);
            SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return method.invoke(file, args);
                        SharedPreferences.Editor editor = file.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                    if ((editMethod.getName().equals("apply") || editMethod.getName().equals("commit"))
                                            && fail.getAndSet(false)) {
                                        throw new IllegalStateException("storage unavailable");
                                    }
                                    Object result = editMethod.invoke(editor, editArgs);
                                    return result == editor ? editProxy : result;
                                });
                    });
            ResumePoints points = new ResumePoints(failing);
            if (loaded) assertNotNull(points.get("old", NOW));
            boolean failed = false;
            try { points.dropExpired(NOW + ResumePoints.KEEP_MS + 1); }
            catch (IllegalStateException expected) { failed = true; }
            assertTrue("cleanup did not reach the failing editor", failed);
            assertTrue(file.contains("old"));
            points.dropExpired(NOW + ResumePoints.KEEP_MS + 1);
            assertFalse("retry forgot a failed deletion", file.contains("old"));
            assertEquals(0, points.size(NOW + ResumePoints.KEEP_MS + 1));
        }
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void diskFailureRetriesEvenAfterPreferencesMemoryChanged() {
        long later = NOW + ResumePoints.KEEP_MS + 1;
        for (boolean loaded : new boolean[]{false, true}) {
            file.edit().clear().putString("old", ResumePoints.encode(90_000, NOW))
                    .putString("live", ResumePoints.encode(120_000, later)).commit();
            Map<String, Object> durable = new HashMap<>(file.getAll());
            AtomicInteger commits = new AtomicInteger();
            SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return method.invoke(file, args);
                        SharedPreferences.Editor editor = file.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                    // Android changes its memory map even when the disk write fails.
                                    Object result = editMethod.invoke(editor, editArgs);
                                    if (editMethod.getName().equals("commit")) {
                                        if (commits.incrementAndGet() <= 2) return false;
                                        durable.clear();
                                        durable.putAll(file.getAll());
                                        return true;
                                    }
                                    return result == editor ? editProxy : result;
                                });
                    });
            ResumePoints points = new ResumePoints(failing);
            if (loaded) assertNotNull(points.get("old", NOW));
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean failed = false;
                try { points.dropExpired(later); }
                catch (IllegalStateException expected) { failed = true; }
                assertTrue("disk failure was reported as successful cleanup", failed);
                assertFalse("the fixture must model memory already changing", file.contains("old"));
                assertTrue("the failed disk write must retain the old record", durable.containsKey("old"));
            }
            points.dropExpired(later);
            assertEquals(3, commits.get());
            assertFalse(durable.containsKey("old"));
            assertEquals(ResumePoints.encode(120_000, later), durable.get("live"));
            assertEquals(120_000, points.get("live", later).positionMs);
        }
    }

    @Test
    public void aPointIsKeptReplacedAndForgotten() {
        ResumePoints points = new ResumePoints(file);
        assertNull(points.get("1", NOW));
        points.put("1", 90_000, NOW);
        points.put("1", 95_000, NOW + 1);
        ResumePoints.Point point = points.get("1", NOW + 2);
        assertNotNull(point);
        assertEquals(95_000, point.positionMs);
        assertEquals(1, points.size(NOW));
        assertTrue(points.remove("1", NOW));
        assertFalse("a second remove found it", points.remove("1", NOW));
        assertNull(points.get("1", NOW));
        assertTrue(file.getAll().isEmpty());
    }

    @Test
    public void theOldestGoesFirstPastTheLimit() {
        ResumePoints points = new ResumePoints(file);
        for (int index = 0; index <= ResumePoints.MAX_POINTS; index++) {
            points.put("id" + index, 60_000 + index, NOW + index);
        }
        assertEquals(ResumePoints.MAX_POINTS, points.size(NOW));
        assertNull("the oldest stayed", points.get("id0", NOW + 1_000));
        assertNotNull(points.get("id1", NOW + 1_000));
        assertEquals(ResumePoints.MAX_POINTS, file.getAll().size());

        // Saving an old one again makes it the newest, so the next to go is the one after it.
        points.put("id1", 70_000, NOW + 2_000);
        points.put("new", 70_000, NOW + 2_001);
        assertNotNull(points.get("id1", NOW + 3_000));
        assertNull(points.get("id2", NOW + 3_000));
    }

    @Test
    public void aPointPastItsAgeIsDropped() {
        ResumePoints points = new ResumePoints(file);
        points.put("1", 90_000, NOW);
        assertNotNull(points.get("1", NOW + ResumePoints.KEEP_MS));
        assertNull(points.get("1", NOW + ResumePoints.KEEP_MS + 1));
        assertFalse("the file kept it", file.contains("1"));
    }

    @Test
    public void theFileIsReadOnceAndCleanedAsItIsRead() {
        file.edit()
                .putString("fresh", ResumePoints.encode(120_000, NOW - 1_000))
                .putString("stale", ResumePoints.encode(120_000, NOW - ResumePoints.KEEP_MS - 1))
                .putString("garbled", "twelve minutes")
                .putString("zero", ResumePoints.encode(0, NOW))
                .putString("noTime", "5000:")
                .putInt("notAString", 4)
                .commit();
        ResumePoints points = new ResumePoints(file);
        assertEquals(1, points.size(NOW));
        assertEquals(120_000, points.get("fresh", NOW).positionMs);
        assertEquals("the file kept what isn't a point", 1, file.getAll().size());
    }

    @Test
    public void aFileWithMoreThanTheLimitKeepsTheNewest() {
        SharedPreferences.Editor edit = file.edit();
        for (int index = 0; index < ResumePoints.MAX_POINTS + 5; index++) {
            edit.putString("id" + index, ResumePoints.encode(60_000, NOW - 10_000 + index));
        }
        edit.commit();
        ResumePoints points = new ResumePoints(file);
        assertEquals(ResumePoints.MAX_POINTS, points.size(NOW));
        for (int index = 0; index < 5; index++) assertNull(points.get("id" + index, NOW));
        assertNotNull(points.get("id5", NOW));
        assertEquals(ResumePoints.MAX_POINTS, file.getAll().size());
    }

    @Test
    public void aPointReadsBackAsItWasWritten() {
        ResumePoints.Point point = ResumePoints.decode(ResumePoints.encode(754_000, NOW));
        assertNotNull(point);
        assertEquals(754_000, point.positionMs);
        assertEquals(NOW, point.savedAt);
        assertNull(ResumePoints.decode(":5"));
        assertNull(ResumePoints.decode("-5:" + NOW));
    }

    @Test
    public void aKeyHidesTheAccountAndKeepsAccountsApart() {
        String first = ResumePoints.key("17841400000000001", "3712345678901234567");
        assertEquals(first, ResumePoints.key("17841400000000001", "3712345678901234567"));
        assertTrue(first, first.matches("[0-9a-f]{16}/3712345678901234567"));
        assertFalse(first, first.contains("17841400000000001"));
        assertFalse(first.equals(ResumePoints.key("17841400000000002", "3712345678901234567")));
        assertNull(ResumePlayback.ownedKey(null, "1"));
        assertNull(ResumePlayback.ownedKey("", "1"));
        assertNull(ResumePlayback.ownedKey("17841400000000001", null));
        assertEquals(first, ResumePlayback.ownedKey("17841400000000001", "3712345678901234567"));
    }
}
