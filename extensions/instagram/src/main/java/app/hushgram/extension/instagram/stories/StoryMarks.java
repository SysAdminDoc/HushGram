/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The stories you tapped Mark as seen on while viewing anonymously, and what became of each.
 *
 * <p>A story is known by its media ID, the digits Instagram's id for it starts with. Instagram keys
 * each story in a batch of views by that ID, its owner's and its reel's, joined by underscores, so a
 * batch's stories are matched to marks by the first part of their key. A key of any other shape is
 * never matched, and that story stays held back.
 *
 * <p>Everything here belongs to the account it happened on, by Instagram's user ID for it: a mark
 * made while signed in to one account is never sent in another account's batch, and a story one
 * account held back is never sent for another. With no account to go by, nothing is marked, kept
 * or sent.
 *
 * <p>When a batch goes to be sent, {@link #choose} picks the stories in it that are marked, and the
 * ones an earlier batch held back that have been marked since, and puts them, and nothing else, in a
 * new batch of Instagram's that started empty. Each story picked counts as sent: its mark is spent,
 * and if it turns up in a batch again it's held back like any other. Sent means handed to
 * Instagram's send, which may still fail on the network. A mark whose story isn't in the batch stays
 * until a send carries it, for 24 hours after the tap at most. Marks, and what was sent, are kept in
 * memory only, so they also lapse when Instagram's process ends.
 *
 * <p>Batches are held back whole while views are anonymous. So a story you mark after its batch went
 * can still go out, the stories of each batch held back while the button is on are kept here, up to
 * {@link #MAX_HELD} of them for 24 hours, and a tap on one of those sends it straight away.
 */
final class StoryMarks {
    /** How long a mark waits for a send, and how long a held-back story or a sent one is remembered. */
    static final long LIFETIME_MS = 24L * 60 * 60 * 1000;

    static final int MAX_MARKS = 200;
    static final int MAX_SENT = 500;
    static final int MAX_HELD = 300;

    /** Where a story stands. */
    enum State {
        /** Held back like any other while you view anonymously. */
        UNMARKED,
        /** Marked, and waiting for the next send to carry it. */
        MARKED,
        /** Marked and handed to Instagram's send. */
        SENT,
    }

    /** A story a batch held back: the account, the story, its key and entry in the batch's map, and when. */
    private static final class Held {
        final String account;
        final String story;
        final Object key;
        final Object entry;
        final long at;

        Held(String account, String story, Object key, Object entry, long at) {
            this.account = account;
            this.story = story;
            this.key = key;
            this.entry = entry;
            this.at = at;
        }
    }

    private final LongSupplier clock;
    /** Account and story ID to when it was marked, oldest first. */
    private final LinkedHashMap<String, Long> marked = new LinkedHashMap<>();
    /** Account and story ID to when it was sent, oldest first. */
    private final LinkedHashMap<String, Long> sent = new LinkedHashMap<>();
    /** Account and a held-back story's key in a batch's map, to what was held, oldest first. */
    private final LinkedHashMap<String, Held> held = new LinkedHashMap<>();

    StoryMarks(LongSupplier clock) {
        this.clock = clock;
    }

    /** Where [story] stands for [account], UNMARKED for either null. */
    synchronized State state(@Nullable String account, @Nullable String story) {
        if (account == null || story == null) return State.UNMARKED;
        prune(clock.getAsLong());
        String id = id(account, story);
        if (sent.containsKey(id)) return State.SENT;
        return marked.containsKey(id) ? State.MARKED : State.UNMARKED;
    }

    /**
     * A tap: marks [story] for [account], or takes its mark back. A story already sent stays sent,
     * and with no account nothing is marked. Answers where it stands now.
     */
    synchronized State toggle(@Nullable String account, String story) {
        if (account == null) return State.UNMARKED;
        long now = clock.getAsLong();
        prune(now);
        String id = id(account, story);
        if (sent.containsKey(id)) return State.SENT;
        if (marked.remove(id) != null) return State.UNMARKED;
        marked.put(id, now);
        trim(marked, MAX_MARKS);
        return State.MARKED;
    }

    /** Whether a batch of [account]'s held [story] back and it's still kept here to send. */
    synchronized boolean held(@Nullable String account, String story) {
        if (account == null) return false;
        prune(clock.getAsLong());
        for (Held entry : held.values()) {
            if (entry.account.equals(account) && entry.story.equals(story)) return true;
        }
        return false;
    }

    /**
     * Picks what goes out of [batch], a batch of [account]'s: a new batch from [batches] holding
     * only the marked stories of [batch] and the marked ones [account] held back before, or null
     * when there are none, and then nothing goes. Keeps the other stories of [batch] as held back.
     * Null too when the new batch doesn't start empty, since anything else in it would go out as
     * well; the marked stories are then kept as held back, for a later send to carry. Null, and
     * nothing kept, with no account.
     */
    @Nullable
    synchronized Object choose(@Nullable String account, @Nullable Object batch, StorySeen.Batches batches) {
        if (account == null) return null;
        long now = clock.getAsLong();
        prune(now);
        Map<Object, Object> stories = batch == null ? null : batches.stories(batch);
        Map<Object, Object> chosen = new LinkedHashMap<>();
        if (stories != null) {
            List<Map.Entry<Object, Object>> entries = new ArrayList<>(stories.entrySet());
            for (Map.Entry<Object, Object> entry : entries) {
                String story = storyOfKey(entry.getKey());
                if (story == null) continue;
                String id = id(account, story);
                if (marked.containsKey(id)) {
                    chosen.put(entry.getKey(), entry.getValue());
                } else if (!sent.containsKey(id)) {
                    keep(account, story, entry.getKey(), entry.getValue(), now);
                }
            }
            trim(held, MAX_HELD);
        }
        for (Held entry : held.values()) {
            if (entry.account.equals(account) && marked.containsKey(id(account, entry.story)) && !chosen.containsKey(entry.key)) {
                chosen.put(entry.key, entry.entry);
            }
        }
        if (chosen.isEmpty()) return null;

        Object fresh = batches.empty();
        Map<Object, Object> into = fresh == null ? null : batches.stories(fresh);
        if (into == null || !into.isEmpty()) {
            for (Map.Entry<Object, Object> entry : chosen.entrySet()) {
                keep(account, storyOfKey(entry.getKey()), entry.getKey(), entry.getValue(), now);
            }
            trim(held, MAX_HELD);
            return null;
        }
        into.putAll(chosen);
        for (Object key : chosen.keySet()) {
            held.remove(heldId(account, key));
            String id = id(account, storyOfKey(key));
            marked.remove(id);
            sent.remove(id);
            sent.put(id, now);
        }
        trim(sent, MAX_SENT);
        return fresh;
    }

    /** Forgets everything. */
    synchronized void clear() {
        marked.clear();
        sent.clear();
        held.clear();
    }

    /**
     * The story ID in Instagram's id for a story, the media ID before its owner's ("123_456"), or
     * null for an id of another shape, such as a live video's or an ad's that isn't a post.
     */
    @Nullable
    static String storyOf(@Nullable String id) {
        if (id == null) return null;
        int split = id.indexOf('_');
        String media = split < 0 ? id : id.substring(0, split);
        if (!digits(media)) return null;
        if (split >= 0 && !digits(id.substring(split + 1))) return null;
        return media;
    }

    /**
     * The owner's user ID in Instagram's id for a story, the part after the media ID ("123_456"), or
     * null for an id without one or of another shape.
     */
    @Nullable
    static String ownerOf(@Nullable String id) {
        if (storyOf(id) == null) return null;
        int split = id.indexOf('_');
        return split < 0 ? null : id.substring(split + 1);
    }

    /** The story ID a batch's key is for, the first of its media, owner and reel parts, or null. */
    @Nullable
    static String storyOfKey(@Nullable Object key) {
        if (!(key instanceof String)) return null;
        String text = (String) key;
        int first = text.indexOf('_');
        int second = first < 0 ? -1 : text.indexOf('_', first + 1);
        if (second < 0 || second == text.length() - 1) return null;
        String media = text.substring(0, first);
        return digits(media) && digits(text.substring(first + 1, second)) ? media : null;
    }

    private void keep(String account, String story, Object key, Object entry, long now) {
        String id = heldId(account, key);
        held.remove(id);
        held.put(id, new Held(account, story, key, entry, now));
    }

    /** An account's user ID never holds a slash, so the two can't run together into another pair. */
    private static String id(String account, String story) {
        return account + "/" + story;
    }

    private static String heldId(String account, Object key) {
        return account + "/" + key;
    }

    private static boolean digits(String text) {
        if (text.isEmpty() || text.length() > 30) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void prune(long now) {
        marked.values().removeIf(at -> expired(at, now));
        sent.values().removeIf(at -> expired(at, now));
        held.values().removeIf(entry -> expired(entry.at, now));
    }

    private static boolean expired(long at, long now) {
        return now - at >= LIFETIME_MS;
    }

    private static <K, V> void trim(LinkedHashMap<K, V> map, int max) {
        Iterator<K> oldest = map.keySet().iterator();
        while (map.size() > max && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
