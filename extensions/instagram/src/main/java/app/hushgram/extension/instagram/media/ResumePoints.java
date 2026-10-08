/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each long video was left, by account and video id ({@link #key}): at most
 * {@link #MAX_POINTS} of them, none older than {@link #KEEP_MS}.
 *
 * <p>The points live in a preferences file of their own, so they never reach the settings export or
 * the diagnostic report. A point is a hash of the account's user ID and the video's ID, with a
 * position and a time, nothing about what the video shows. The file is read once, the first time
 * a point is asked for, and every change after that writes only the keys it changes. The oldest
 * point goes first once there are too many, and a point past its age is dropped when it's read
 * and once each time Instagram starts playing video, switch on or off
 * ({@link ResumePlayback#started}).
 */
final class ResumePoints {
    /** The preferences file, in Instagram's own preferences folder. Only this class writes it. */
    static final String FILE = "hushgram_resume_points_by_account";

    /**
     * The file from before points had an account. Nothing says whose they were, so ResumePlayback
     * deletes it the first time it opens {@link #FILE}.
     */
    static final String UNOWNED_FILE = "hushgram_resume_points";

    /** How many videos are remembered. The least recently saved goes first. */
    static final int MAX_POINTS = 200;

    /** How long a point is kept after it was last saved: 30 days. */
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** One remembered position. */
    static final class Point {
        final int positionMs;
        final long savedAt;

        Point(int positionMs, long savedAt) {
            this.positionMs = positionMs;
            this.savedAt = savedAt;
        }
    }

    /**
     * The key of [videoId]'s point for the account with [userId]: the account's part ({@link #owner}),
     * so the file never names the account, then the video. Two accounts playing one video keep two
     * points.
     */
    static String key(String userId, String videoId) {
        return owner(userId) + '/' + videoId;
    }

    /** The account part of a key: the first 16 hex digits of a SHA-256 of the user ID. */
    static String owner(String userId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(("hushgram resume " + userId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException missing) {
            // Every Android has SHA-256.
            throw new IllegalStateException(missing);
        }
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16)).append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
    }

    private final SharedPreferences store;
    /** In the order they were saved, oldest first. */
    private final LinkedHashMap<String, Point> points = new LinkedHashMap<>();
    private boolean loaded;

    ResumePoints(SharedPreferences store) {
        this.store = store;
    }

    /** The point saved for [videoId], or null when there's none or it's past its age. */
    @Nullable
    synchronized Point get(String videoId, long now) {
        load(now);
        Point point = points.get(videoId);
        if (point == null) return null;
        if (expired(point, now)) {
            points.remove(videoId);
            store.edit().remove(videoId).apply();
            return null;
        }
        return point;
    }

    /** Remembers [positionMs] for [videoId] as its newest point, and drops the oldest past the limit. */
    synchronized void put(String videoId, int positionMs, long now) {
        load(now);
        points.remove(videoId);
        points.put(videoId, new Point(positionMs, now));
        SharedPreferences.Editor edit = store.edit().putString(videoId, encode(positionMs, now));
        Iterator<String> oldest = points.keySet().iterator();
        while (points.size() > MAX_POINTS && oldest.hasNext()) {
            String gone = oldest.next();
            oldest.remove();
            edit.remove(gone);
        }
        edit.apply();
    }

    /** Durably drops expired points on the cleanup worker, including failed earlier writes. */
    synchronized void dropExpired(long now) {
        load(now);
        SharedPreferences.Editor edit = store.edit();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Point> point : points.entrySet()) {
            if (!expired(point.getValue(), now)) continue;
            expired.add(point.getKey());
            edit.remove(point.getKey());
        }
        // apply() and failed commit() can already have changed the preferences memory map.
        // Commit even with no new removals so a retry still flushes that pending disk state.
        if (!edit.commit()) throw new IllegalStateException("Could not persist expired resume point cleanup");
        for (String key : expired) points.remove(key);
    }

    /** Forgets [videoId]'s point. True when there was one. */
    synchronized boolean remove(String videoId, long now) {
        load(now);
        if (points.remove(videoId) == null) return false;
        store.edit().remove(videoId).apply();
        return true;
    }

    /**
     * Forgets, durably, every point whose key starts with [prefix]: one account's, its
     * {@link #owner} and a slash. Answers how many went.
     */
    synchronized int removeOwner(String prefix, long now) {
        load(now);
        List<String> gone = new ArrayList<>();
        for (String key : points.keySet()) {
            if (key.startsWith(prefix)) gone.add(key);
        }
        if (gone.isEmpty()) return 0;
        SharedPreferences.Editor edit = store.edit();
        for (String key : gone) edit.remove(key);
        if (!edit.commit()) throw new IllegalStateException("Could not forget an account's resume points");
        for (String key : gone) points.remove(key);
        return gone.size();
    }

    /** How many points are kept. */
    synchronized int size(long now) {
        load(now);
        return points.size();
    }

    /** Clears this file durably and returns only the bounded, unexpired in-memory Undo snapshot. */
    synchronized Map<String, Point> clear(long now) {
        dropExpired(now);
        Map<String, Point> snapshot = new LinkedHashMap<>(points);
        if (!commitPoints(Collections.emptyMap())) {
            boolean rolledBack = commitPoints(points);
            throw new IllegalStateException("Could not clear resume points; rollback " + rolledBack);
        }
        points.clear();
        return snapshot;
    }

    /** Restores the snapshot without replacing newer positions or extending their retention. */
    synchronized void restore(Map<String, Point> snapshot, long now) {
        dropExpired(now);
        LinkedHashMap<String, Point> restored = new LinkedHashMap<>();
        for (Map.Entry<String, Point> entry : snapshot.entrySet()) {
            if (!expired(entry.getValue(), now)) restored.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Point> entry : points.entrySet()) {
            restored.remove(entry.getKey());
            restored.put(entry.getKey(), entry.getValue());
        }
        Iterator<String> oldest = restored.keySet().iterator();
        while (restored.size() > MAX_POINTS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        if (!commitPoints(restored)) {
            boolean rolledBack = commitPoints(points);
            throw new IllegalStateException("Could not restore resume points; rollback " + rolledBack);
        }
        points.clear();
        points.putAll(restored);
    }

    /** Used by clear, Undo and rollback. This private file contains no other settings. */
    private boolean commitPoints(Map<String, Point> contents) {
        SharedPreferences.Editor edit = store.edit().clear();
        for (Map.Entry<String, Point> entry : contents.entrySet()) {
            Point point = entry.getValue();
            edit.putString(entry.getKey(), encode(point.positionMs, point.savedAt));
        }
        return edit.commit();
    }

    /**
     * Reads the file the first time. A value that doesn't parse, a point past its age, and the
     * oldest points past the limit (a file written by a build that kept more) are removed from it.
     */
    private void load(long now) {
        if (loaded) return;
        // A failed read or cleanup can retry without retaining a partial in-memory snapshot.
        points.clear();
        List<String> dropped = new ArrayList<>();
        List<Map.Entry<String, Point>> read = new ArrayList<>();
        for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
            Point point = entry.getValue() instanceof String ? decode((String) entry.getValue()) : null;
            if (point == null || expired(point, now)) {
                dropped.add(entry.getKey());
                continue;
            }
            read.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), point));
        }
        Collections.sort(read, (a, b) -> Long.compare(a.getValue().savedAt, b.getValue().savedAt));
        int surplus = Math.max(0, read.size() - MAX_POINTS);
        for (int index = 0; index < read.size(); index++) {
            Map.Entry<String, Point> entry = read.get(index);
            if (index < surplus) dropped.add(entry.getKey());
            else points.put(entry.getKey(), entry.getValue());
        }
        if (!dropped.isEmpty()) {
            SharedPreferences.Editor edit = store.edit();
            for (String key : dropped) edit.remove(key);
            edit.apply();
        }
        loaded = true;
    }

    private static boolean expired(Point point, long now) {
        return now - point.savedAt > KEEP_MS;
    }

    static String encode(int positionMs, long savedAt) {
        return positionMs + ":" + savedAt;
    }

    @Nullable
    static Point decode(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) return null;
        try {
            int position = Integer.parseInt(value.substring(0, colon));
            long savedAt = Long.parseLong(value.substring(colon + 1));
            return position > 0 ? new Point(position, savedAt) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
