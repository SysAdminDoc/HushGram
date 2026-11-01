/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chats opened on this phone while their seen receipt was held, each by the account and the
 * chat, with the message that was newest when it opened: at most {@link #MAX_CHATS} of them.
 *
 * <p>With Keep chats unread here off, Instagram takes a held chat's unread mark off when it opens,
 * then puts it back once the inbox refreshes, since the other side never got the receipt. A chat
 * kept here reads as read until its newest message is another one. So it lives in a preferences
 * file of its own, past a restart. The file holds only ids, never reaches the settings export or the
 * diagnostic report, and is read once, the first time a chat is asked about. The chat opened longest
 * ago goes first once there are too many.
 */
final class ReadHere {
    /** The preferences file, in Instagram's own preferences folder. Only this class writes it. */
    static final String FILE = "hushgram_read_here";

    /** How many chats are kept. The one opened longest ago goes first. */
    static final int MAX_CHATS = 500;

    @Nullable
    private final SharedPreferences store;
    /** Each chat's newest message when it opened, the chat opened longest ago first. */
    private final LinkedHashMap<String, String> chats = new LinkedHashMap<>();
    private boolean loaded;

    /** Chats kept in [store], or only in memory when it's null. */
    ReadHere(@Nullable SharedPreferences store) {
        this.store = store;
    }

    /** Keeps [thread] on [account] read up to [message], as the chat opened last. */
    synchronized void read(String account, String thread, String message, long now) {
        load();
        String key = key(account, thread);
        chats.remove(key);
        chats.put(key, message);
        SharedPreferences.Editor edit = store == null ? null : store.edit().putString(key, now + "/" + message);
        Iterator<String> oldest = chats.keySet().iterator();
        while (chats.size() > MAX_CHATS && oldest.hasNext()) {
            String gone = oldest.next();
            oldest.remove();
            if (edit != null) edit.remove(gone);
        }
        if (edit != null) edit.apply();
    }

    /** Whether [thread] on [account] was opened here while [message] was its newest. */
    synchronized boolean readUpTo(String account, String thread, String message) {
        load();
        return message.equals(chats.get(key(account, thread)));
    }

    /** The message [thread] on [account] was opened up to, or null when it wasn't opened here. */
    @Nullable
    synchronized String messageOf(String account, String thread) {
        load();
        return chats.get(key(account, thread));
    }

    /** True when no chat is kept. */
    synchronized boolean isEmpty() {
        load();
        return chats.isEmpty();
    }

    /** How many chats are kept, for tests. */
    synchronized int size() {
        load();
        return chats.size();
    }

    /** Forgets every chat, on file too, for tests. */
    synchronized void clear() {
        chats.clear();
        loaded = true;
        if (store != null) store.edit().clear().commit();
    }

    /**
     * Reads the file the first time, in the order the chats were opened. A value that isn't a time
     * and a message, and the chats opened longest ago past the limit, are removed from it.
     */
    private void load() {
        if (loaded) return;
        chats.clear();
        if (store == null) {
            loaded = true;
            return;
        }
        List<String> dropped = new ArrayList<>();
        List<Kept> read = new ArrayList<>();
        for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
            Kept kept = Kept.parse(entry.getKey(), entry.getValue());
            if (kept == null) dropped.add(entry.getKey());
            else read.add(kept);
        }
        Collections.sort(read, (a, b) -> Long.compare(a.at, b.at));
        int surplus = Math.max(0, read.size() - MAX_CHATS);
        for (int index = 0; index < read.size(); index++) {
            Kept kept = read.get(index);
            if (index < surplus) dropped.add(kept.key);
            else chats.put(kept.key, kept.message);
        }
        if (!dropped.isEmpty()) {
            SharedPreferences.Editor edit = store.edit();
            for (String key : dropped) edit.remove(key);
            edit.apply();
        }
        loaded = true;
    }

    /** The account behind its length, so no two chats can spell the same key. */
    static String key(String account, String thread) {
        return account.length() + ":" + account + "/" + thread;
    }

    /** One chat as the file keeps it: when it opened, then its newest message. */
    private static final class Kept {
        final String key;
        final long at;
        final String message;

        private Kept(String key, long at, String message) {
            this.key = key;
            this.at = at;
            this.message = message;
        }

        @Nullable
        static Kept parse(String key, Object value) {
            if (!(value instanceof String)) return null;
            String text = (String) value;
            int slash = text.indexOf('/');
            if (slash <= 0 || slash == text.length() - 1) return null;
            try {
                return new Kept(key, Long.parseLong(text.substring(0, slash)), text.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
