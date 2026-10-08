/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chats marked read by hand whose seen receipt may go out, each by the account it was marked
 * on, the chat and the message the receipt points at: at most {@link #MAX_MARKS} of them, none older
 * than {@link #KEEP_MS}.
 *
 * <p>Instagram keeps a queued receipt when it's closed or offline and sends it again later, as a
 * new object read back from its own queue. So a mark lives in a preferences file of its own, not
 * only in memory, and isn't used up when its receipt goes through: the same receipt tried again
 * goes through again. A newer message in the same chat has another id, so its receipt stays held.
 * The file holds only ids, never reaches the settings export or the diagnostic report, and is read
 * once, the first time a mark is asked for. The oldest mark goes first once there are too many.
 */
final class ReadMarks {
    /** The preferences file, in Instagram's own preferences folder. Only this class writes it. */
    static final String FILE = "hushgram_read_marks";

    /** How many marks are kept. The oldest goes first. */
    static final int MAX_MARKS = 200;

    /** How long a mark lasts: a day, long enough for a receipt queued offline or across a restart. */
    static final long KEEP_MS = 24L * 60 * 60 * 1000;

    @Nullable
    private final SharedPreferences store;
    /** When each mark was made, oldest first. */
    private final LinkedHashMap<String, Long> marks = new LinkedHashMap<>();
    private boolean loaded;

    /** Marks kept in [store], or only in memory when it's null. */
    ReadMarks(@Nullable SharedPreferences store) {
        this.store = store;
    }

    /** Lets the receipt for [message] in [thread] on [account] through, as the newest mark. */
    synchronized void allow(String account, String thread, String message, long now) {
        load(now);
        String key = key(account, thread, message);
        marks.remove(key);
        marks.put(key, now);
        SharedPreferences.Editor edit = store == null ? null : store.edit().putLong(key, now);
        Iterator<String> oldest = marks.keySet().iterator();
        while (marks.size() > MAX_MARKS && oldest.hasNext()) {
            String gone = oldest.next();
            oldest.remove();
            if (edit != null) edit.remove(gone);
        }
        if (edit != null) edit.apply();
    }

    /** Whether the receipt for [message] in [thread] on [account] may go through. */
    synchronized boolean allows(String account, String thread, String message, long now) {
        load(now);
        String key = key(account, thread, message);
        Long markedAt = marks.get(key);
        if (markedAt == null) return false;
        if (!expired(markedAt, now)) return true;
        forget(key);
        return false;
    }

    /** Takes the mark for [message] in [thread] on [account] back. */
    synchronized void revoke(String account, String thread, String message, long now) {
        load(now);
        forget(key(account, thread, message));
    }

    /** True when no mark is left, after dropping the ones past their age. */
    synchronized boolean isEmpty(long now) {
        load(now);
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> mark : marks.entrySet()) {
            if (expired(mark.getValue(), now)) expired.add(mark.getKey());
        }
        if (!expired.isEmpty()) {
            SharedPreferences.Editor edit = store == null ? null : store.edit();
            for (String key : expired) {
                marks.remove(key);
                if (edit != null) edit.remove(key);
            }
            if (edit != null) edit.apply();
        }
        return marks.isEmpty();
    }

    /** How many marks are kept, for tests. */
    synchronized int size(long now) {
        load(now);
        return marks.size();
    }

    /** Forgets every mark, on file too, for tests. */
    synchronized void clear() {
        marks.clear();
        loaded = true;
        if (store != null) store.edit().clear().commit();
    }

    private void forget(String key) {
        if (marks.remove(key) != null && store != null) store.edit().remove(key).apply();
    }

    /**
     * Reads the file the first time. A value that isn't a time, a mark past its age, and the oldest
     * marks past the limit are removed from it.
     */
    private void load(long now) {
        if (loaded) return;
        marks.clear();
        if (store == null) {
            loaded = true;
            return;
        }
        List<String> dropped = new ArrayList<>();
        List<Map.Entry<String, Long>> read = new ArrayList<>();
        for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Long) || expired((Long) value, now)) {
                dropped.add(entry.getKey());
                continue;
            }
            read.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), (Long) value));
        }
        Collections.sort(read, (a, b) -> Long.compare(a.getValue(), b.getValue()));
        int surplus = Math.max(0, read.size() - MAX_MARKS);
        for (int index = 0; index < read.size(); index++) {
            Map.Entry<String, Long> entry = read.get(index);
            if (index < surplus) dropped.add(entry.getKey());
            else marks.put(entry.getKey(), entry.getValue());
        }
        if (!dropped.isEmpty()) {
            SharedPreferences.Editor edit = store.edit();
            for (String key : dropped) edit.remove(key);
            edit.apply();
        }
        loaded = true;
    }

    /** Past its age, or made more than a day ahead of a clock that was set back since. */
    private static boolean expired(long markedAt, long now) {
        return now - markedAt > KEEP_MS || markedAt - now > KEEP_MS;
    }

    /** Each id behind its length, so no two marks can spell the same key. */
    static String key(String account, String thread, String message) {
        return account.length() + ":" + account + "/" + thread.length() + ":" + thread + "/" + message;
    }
}
