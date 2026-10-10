/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the ring of a story you watch new while "View stories anonymously" holds its view back.
 *
 * <p>Holding the view back keeps you off the story's viewer list, but Instagram also writes down on
 * the phone how far into each account's stories you've got: a seen time per reel, in a store it
 * saves to disk. That store is what greys a ring and sends it to the end of the tray, on Home, on
 * profiles and everywhere else a ring is drawn. The story viewer writes the time of each story you
 * watch into it, and the patch hands that write to {@link #seen} instead. While views are held back
 * it's skipped, so the ring stays as it was. Instagram never got a view to put in the tray it sends,
 * so a refresh keeps the ring new too.
 *
 * <p>A story you mark as seen with the Mark as seen button does go out, and once it has, its ring
 * should look the way Instagram would draw it. So each skipped write is kept here, by account and
 * story, and when {@link StorySeen} hands a marked story to Instagram's send, {@link #sent} makes the
 * write for it, through Instagram's own method. A story whose mark already went out is written
 * straight away. The writes are kept 24 hours at most, the most recent {@link #MAX_KEPT} of them,
 * and only weakly, in memory.
 *
 * <p>Gray out stories you've watched lets the write through while views are held back, for people
 * who'd rather see what they've watched (#113).
 *
 * <p>The switch off, a pause or settings that aren't ready write the time as Instagram would. So
 * does any failure deciding: a grey ring is what Instagram does anyway, and the view itself is held
 * back or sent by {@link StorySeen} alone, whatever happens here.
 */
public final class StorySeenRings {
    /** The hook's name in the diagnostics, and what it counts. */
    static final String HOOK = "story ring";
    static final String KEPT_NEW = "watched stories kept new";
    static final String SHOWN_SEEN = "marked stories shown as seen";

    /** How long a skipped write waits for its story's mark to go out. */
    static final long LIFETIME_MS = StoryMarks.LIFETIME_MS;

    static final int MAX_KEPT = 300;

    /** Where {@link #keep} reads the account and the story. Tests hand in their own. */
    interface Reader {
        /** Instagram's user ID for [session], the account signed in, or null. */
        @Nullable
        String account(Object session);

        /** Instagram's id for [item], a story in the viewer, or null. */
        @Nullable
        String storyId(Object item);
    }

    /** Instagram's own write of a reel's seen time. */
    interface Writer {
        void write(Object reel, Object session, long at);
    }

    /** Where a write made later runs. */
    interface Poster {
        void post(Runnable task);
    }

    /** What's counted and reported. None of it may change what's written. */
    interface Counts {
        void keptNew();

        void shownSeen();

        void threw(Throwable failure);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public String account(Object session) {
            return StorySeen.sessionAccount(session);
        }

        @Override
        public String storyId(Object item) {
            return StorySeenButton.storyId(item);
        }
    };

    static final Writer INSTAGRAM = StorySeenRings::markSeen;

    /** Off the send, on the main thread, as the viewer makes its own writes. */
    static final Poster MAIN = Utils::runOnMainThread;

    static final Counts COUNTED = new Counts() {
        @Override
        public void keptNew() {
            HookStatus.counted(FamilyNames.STORY_SEEN, KEPT_NEW);
            Logger.printDebug(() -> "Story views: kept a watched story new");
        }

        @Override
        public void shownSeen() {
            HookStatus.counted(FamilyNames.STORY_SEEN, SHOWN_SEEN);
            Logger.printDebug(() -> "Story views: showed a story marked as seen as seen");
        }

        @Override
        public void threw(Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    };

    static final StorySeenRings RINGS = new StorySeenRings(SystemClock::elapsedRealtime);

    private static final BooleanSupplier ANONYMOUS = StorySeenRings::keepsWatchedNew;

    /**
     * Whether a watched story's write is skipped: views held back, and Gray out stories you've
     * watched off. With that switch on Instagram writes as it would, so the ring greys on the phone
     * while {@link StorySeen} still holds the view itself back (#113).
     */
    static boolean keepsWatchedNew() {
        return StorySeen.anonymous() && !Settings.GRAY_OUT_WATCHED_STORIES.get();
    }

    /** A write skipped for a story: the reel and account it was for, the time, and when. */
    private static final class Kept {
        final WeakReference<Object> reel;
        final WeakReference<Object> session;
        final long at;
        final long since;

        Kept(Object reel, Object session, long at, long since) {
            this.reel = new WeakReference<>(reel);
            this.session = new WeakReference<>(session);
            this.at = at;
            this.since = since;
        }
    }

    private final LongSupplier clock;
    /** Account and story ID to the write skipped for it, oldest first. */
    private final LinkedHashMap<String, Kept> kept = new LinkedHashMap<>();

    StorySeenRings(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Put by the patch in place of the story viewer's write of [reel]'s seen time, the time of
     * [item], the story it's showing, for [session]'s account. Skips the write while views are held
     * back and makes it otherwise. Throws only what Instagram's own write throws.
     */
    public static void seen(Object reel, Object session, long at, Object item) {
        if (!RINGS.keep(reel, session, at, item, ANONYMOUS, PATCHED, StorySeen.MARKS, COUNTED)) markSeen(reel, session, at);
    }

    /**
     * Whether to skip the write: views held back and [item]'s mark not gone out yet. A skipped
     * write is kept for the story's mark when the account and the story can be read. False, and
     * Instagram writes as usual, on any failure. Never throws.
     */
    boolean keep(@Nullable Object reel, @Nullable Object session, long at, @Nullable Object item, BooleanSupplier anonymous,
                 Reader reader, StoryMarks marks, Counts counts) {
        try {
            if (!anonymous.getAsBoolean()) return false;
            String account = session == null ? null : reader.account(session);
            String story = item == null ? null : StoryMarks.storyOf(reader.storyId(item));
            if (account != null && story != null && reel != null) {
                if (marks.state(account, story) == StoryMarks.State.SENT) return false;
                remember(account, story, new Kept(reel, session, at, clock.getAsLong()));
            }
        } catch (Throwable failure) {
            report(counts, failure);
            return false;
        }
        try {
            counts.keptNew();
        } catch (Throwable ignored) {
            // Counters are optional.
        }
        return true;
    }

    /**
     * Handed each batch of marked stories {@link StorySeen} sends for [account]: makes the write kept
     * for each story in it, as Instagram would have when it was watched. Never throws.
     */
    static void sent(@Nullable String account, @Nullable Object batch, StorySeen.Batches batches) {
        RINGS.shown(account, batch, batches, INSTAGRAM, MAIN, COUNTED);
    }

    void shown(@Nullable String account, @Nullable Object batch, StorySeen.Batches batches, Writer writer, Poster poster,
               Counts counts) {
        try {
            if (account == null || batch == null) return;
            Map<Object, Object> stories = batches.stories(batch);
            if (stories == null) return;
            List<Kept> due = new ArrayList<>();
            for (Object key : new ArrayList<>(stories.keySet())) {
                String story = StoryMarks.storyOfKey(key);
                Kept write = story == null ? null : take(account, story);
                if (write != null) due.add(write);
            }
            for (Kept write : due) {
                Object reel = write.reel.get();
                Object session = write.session.get();
                if (reel == null || session == null) continue;
                long at = write.at;
                poster.post(() -> writer.write(reel, session, at));
                try {
                    counts.shownSeen();
                } catch (Throwable ignored) {
                    // Counters are optional.
                }
            }
        } catch (Throwable failure) {
            report(counts, failure);
        }
    }

    /** How many skipped writes are kept, for tests. */
    synchronized int size() {
        prune(clock.getAsLong());
        return kept.size();
    }

    private synchronized void remember(String account, String story, Kept write) {
        prune(write.since);
        String id = id(account, story);
        kept.remove(id);
        kept.put(id, write);
        Iterator<String> oldest = kept.keySet().iterator();
        while (kept.size() > MAX_KEPT && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    @Nullable
    private synchronized Kept take(String account, String story) {
        prune(clock.getAsLong());
        return kept.remove(id(account, story));
    }

    private void prune(long now) {
        kept.values().removeIf(write -> now - write.since >= LIFETIME_MS);
    }

    /** An account's user ID never holds a slash, so the two can't run together into another pair. */
    private static String id(String account, String story) {
        return account + "/" + story;
    }

    private static void report(Counts counts, Throwable failure) {
        try {
            counts.threw(failure);
        } catch (Throwable ignored) {
            // There is nowhere left to report a diagnostic failure.
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: Instagram's own write of [reel]'s seen time [at] for [session]'s account. */
    public static void markSeen(Object reel, Object session, long at) {
    }
}
