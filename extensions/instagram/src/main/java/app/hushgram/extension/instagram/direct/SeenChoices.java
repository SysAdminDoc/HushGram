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
 * The chats given a seen receipt choice of their own from the eye in their header (#13), each by the
 * account and the chat: hold its receipts, or send them, whatever Read messages without the seen
 * receipt says. A chat that follows the switch isn't kept. At most {@link #MAX_CHATS} of them.
 *
 * <p>The choices last past a restart, so they live in a preferences file of their own. Like
 * {@link ReadHere}, the file holds only ids and the choice, never reaches the settings export or the
 * diagnostic report, and is read once, the first time a chat is asked about. The chat chosen longest
 * ago goes first once there are too many.
 */
final class SeenChoices {
    /** The preferences file, in Instagram's own preferences folder. Only this class writes it. */
    static final String FILE = "hushgram_seen_choices";

    /** How many chats are kept. The one chosen longest ago goes first. */
    static final int MAX_CHATS = 500;

    /** What a chat does with its seen receipts. */
    enum Choice {
        /** Whatever Read messages without the seen receipt says. Never kept. */
        SWITCH,
        /** Held, as if the switch were on. */
        HOLD,
        /** Sent, as if the switch were off. */
        SEND
    }

    @Nullable
    private final SharedPreferences store;
    /** False in Instagram's other processes, which read the file but never change it. */
    private final boolean writes;
    /** Each chat's choice, the chat chosen longest ago first. */
    private final LinkedHashMap<String, Choice> chats = new LinkedHashMap<>();
    private boolean loaded;

    /** Choices kept in [store], or only in memory when it's null. */
    SeenChoices(@Nullable SharedPreferences store) {
        this(store, true);
    }

    /**
     * Choices read from [store]. With [writes] false they're read once, as the file stood then, and a
     * choice made here stays in memory: a chat held in the main process must stay held wherever else a
     * receipt goes out, but only one process may write the file.
     */
    SeenChoices(@Nullable SharedPreferences store, boolean writes) {
        this.store = store;
        this.writes = writes;
    }

    /** What [thread] on [account] does with its receipts. */
    synchronized Choice of(String account, String thread) {
        load();
        Choice choice = chats.get(ReadHere.key(account, thread));
        return choice == null ? Choice.SWITCH : choice;
    }

    /** Gives [thread] on [account] [choice], as the chat chosen last. {@link Choice#SWITCH} forgets the chat. */
    synchronized void choose(String account, String thread, Choice choice, long now) {
        load();
        String key = ReadHere.key(account, thread);
        chats.remove(key);
        if (store == null || !writes) {
            if (choice != Choice.SWITCH) chats.put(key, choice);
            trim(null);
            return;
        }
        SharedPreferences.Editor edit = store.edit();
        if (choice == Choice.SWITCH) {
            edit.remove(key);
        } else {
            chats.put(key, choice);
            edit.putString(key, now + "/" + choice.name());
        }
        trim(edit);
        edit.apply();
    }

    /** True when no chat has a choice of its own. */
    synchronized boolean isEmpty() {
        load();
        return chats.isEmpty();
    }

    /** True when some chat holds its receipts by its own choice. */
    synchronized boolean anyHeld() {
        load();
        return chats.containsValue(Choice.HOLD);
    }

    /** How many chats have a choice of their own, for tests. */
    synchronized int size() {
        load();
        return chats.size();
    }

    /** Forgets every choice, on file too, for tests. */
    synchronized void clear() {
        chats.clear();
        loaded = true;
        if (store != null && writes) store.edit().clear().commit();
    }

    private void trim(@Nullable SharedPreferences.Editor edit) {
        Iterator<String> oldest = chats.keySet().iterator();
        while (chats.size() > MAX_CHATS && oldest.hasNext()) {
            String gone = oldest.next();
            oldest.remove();
            if (edit != null) edit.remove(gone);
        }
    }

    /**
     * Reads the file the first time, in the order the chats were chosen. A value that isn't a time
     * and a choice, and the chats chosen longest ago past the limit, are removed from it.
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
            else chats.put(kept.key, kept.choice);
        }
        if (!dropped.isEmpty() && writes) {
            SharedPreferences.Editor edit = store.edit();
            for (String key : dropped) edit.remove(key);
            edit.apply();
        }
        loaded = true;
    }

    /** One chat as the file keeps it: when it was chosen, then the choice. */
    private static final class Kept {
        final String key;
        final long at;
        final Choice choice;

        private Kept(String key, long at, Choice choice) {
            this.key = key;
            this.at = at;
            this.choice = choice;
        }

        @Nullable
        static Kept parse(String key, Object value) {
            if (!(value instanceof String)) return null;
            String text = (String) value;
            int slash = text.indexOf('/');
            if (slash <= 0) return null;
            String name = text.substring(slash + 1);
            if (!Choice.HOLD.name().equals(name) && !Choice.SEND.name().equals(name)) return null;
            try {
                return new Kept(key, Long.parseLong(text.substring(0, slash)), Choice.valueOf(name));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
