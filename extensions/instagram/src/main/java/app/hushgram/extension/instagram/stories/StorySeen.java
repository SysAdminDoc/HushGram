/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "View stories anonymously" patch.
 *
 * <p>Instagram gathers the stories you've seen into a batch and posts it to {@code media/seen/},
 * which is what puts you on each story's viewer list. The patch asks {@link #toSend} first thing in
 * the method that sends a batch, and sends what it answers in the batch's place, or nothing at all
 * on null. Each caller has already cleared its own copy, so a batch held back is gone, and turning
 * the switch off lets the next batch through. A batch the store retries from what an earlier
 * session saved goes through {@link #toRetry} right before its request is built, and a batch held
 * back there takes the native loop's next-item branch before any ownership change or request.
 *
 * <p>With the second switch on, the stories you tapped Mark as seen on ({@link StorySeenButton})
 * still go out: the answer is then a batch of the extension's own, started empty by Instagram's own
 * constructor, holding those stories and nothing else. Marks belong to the account they were made
 * on, read from the store that sends. See {@link StoryMarks}. Each such batch is handed on to
 * {@link StorySeenRings}, which shows its stories as seen on the phone too, as they go out.
 *
 * <p>The switch off, a pause or settings that aren't ready send views as Instagram would. A fresh
 * send also fails open if reading the switch fails. A retry that can't read the switch stays held.
 * A failure picking out the marked stories holds the whole batch back, as the switch on does
 * without marks. The diagnostics it keeps are only counted: a failure there never changes what goes out.
 */
public final class StorySeen {
    /** The diagnostic counter route: each batch of views Instagram went to send, and the ones held back. */
    static final String ROUTE = "Story views";

    /** What a batch held back is counted under. */
    static final String HELD_BACK = "viewed stories";

    static final String SEND_HOOK = "story seen send";
    static final String MARKED_HOOK = "marked story send";
    static final String SHOWN_HOOK = "marked story shown";

    /** The stories marked as seen, shared with the button. */
    static final StoryMarks MARKS = new StoryMarks(SystemClock::elapsedRealtime);

    /** Where {@link #toSend} reads and starts batches, and reads the account a store sends for. Tests hand in their own. */
    interface Batches {
        /** A new batch of Instagram's, holding nothing, or null. */
        @Nullable
        Object empty();

        /** The map of stories [batch] holds, Instagram's own, which changes the batch when changed. */
        @Nullable
        Map<Object, Object> stories(Object batch);

        /** Instagram's user ID for the account [store] sends for, or null. */
        @Nullable
        String account(@Nullable Object store);
    }

    /** What {@link #toSend} counts and reports. None of it may change what goes out. */
    interface Diagnostics {
        /** A batch reached the send. */
        void saw();

        /** A batch was held back whole. */
        void heldBack();

        /** Only the marked stories went out. */
        void sentMarked();

        void threw(String hook, Throwable failure);
    }

    /** Told of each batch of marked stories that goes out, with the account it's for. */
    interface Shown {
        void sent(@Nullable String account, Object batch, Batches batches);
    }

    /** Shows the stories of a batch that went out as seen on the phone. See {@link StorySeenRings}. */
    static final Shown RINGS = StorySeenRings::sent;

    /** For a caller that shows nothing. */
    static final Shown NOT_SHOWN = (account, batch, batches) -> {
    };

    static final Batches PATCHED = new Batches() {
        @Override
        public Object empty() {
            return emptyBatch();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<Object, Object> stories(Object batch) {
            return (Map<Object, Object>) seenStories(batch);
        }

        @Override
        public String account(Object store) {
            return store == null ? null : storeAccount(store);
        }
    };

    static final Diagnostics COUNTED = new Diagnostics() {
        @Override
        public void saw() {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            FeedFilterCounters.sawList(ROUTE, 1);
        }

        @Override
        public void heldBack() {
            FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
            Logger.printDebug(() -> "Story views: held back a batch of viewed stories");
        }

        @Override
        public void sentMarked() {
            Logger.printDebug(() -> "Story views: sent only the stories marked as seen");
        }

        @Override
        public void threw(String hook, Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, hook, failure);
        }
    };

    private StorySeen() {
    }

    // Reuse the adapters: a held retry needs no new lambda or batch.
    private static final BooleanSupplier ANONYMOUS = StorySeen::anonymous;
    private static final BooleanSupplier MARKING = StorySeenButton::switchedOn;

    /**
     * Asked first thing in the send, with the store sending and the batch it was handed. Answers the
     * batch to send: the same one while the switch is off, HushGram is paused or the settings aren't
     * ready; one holding only the stories you marked on the store's account; or null, and the send
     * returns before the request is built. Never throws.
     */
    @Nullable
    public static Object toSend(Object store, Object batch) {
        return toSend(store, batch, PATCHED, ANONYMOUS, MARKING, MARKS, COUNTED, RINGS);
    }

    @Nullable
    static Object toSend(@Nullable Object store, @Nullable Object batch, Batches batches, BooleanSupplier anonymous,
                         BooleanSupplier marking, StoryMarks marks, Diagnostics diagnostics) {
        return toSend(store, batch, batches, anonymous, marking, marks, diagnostics, NOT_SHOWN);
    }

    @Nullable
    static Object toSend(@Nullable Object store, @Nullable Object batch, Batches batches, BooleanSupplier anonymous,
                         BooleanSupplier marking, StoryMarks marks, Diagnostics diagnostics, Shown shown) {
        return choose(store, batch, batches, anonymous, marking, marks, diagnostics, shown, false);
    }

    @Nullable
    private static Object choose(@Nullable Object store, @Nullable Object batch, Batches batches, BooleanSupplier anonymous,
                                 BooleanSupplier marking, StoryMarks marks, Diagnostics diagnostics, Shown shown,
                                 boolean retry) {
        saw(diagnostics);
        boolean holdBack;
        try {
            holdBack = anonymous.getAsBoolean();
        } catch (Throwable failure) {
            report(diagnostics, SEND_HOOK, failure);
            if (retry) counted(diagnostics, false);
            return retry ? null : batch;
        }
        if (!holdBack) return batch;
        Object marked = null;
        String account = null;
        try {
            if (marking.getAsBoolean()) {
                account = batches.account(store);
                marked = marks.choose(account, batch, batches);
            }
        } catch (Throwable failure) {
            report(diagnostics, MARKED_HOOK, failure);
            marked = null;
        }
        counted(diagnostics, marked != null);
        if (marked != null) show(shown, account, marked, batches, diagnostics);
        return marked;
    }

    /** Hands the marked batch going out to [shown]. Nothing it does or throws changes what goes out. */
    private static void show(Shown shown, @Nullable String account, Object marked, Batches batches, Diagnostics diagnostics) {
        try {
            shown.sent(account, marked, batches);
        } catch (Throwable failure) {
            report(diagnostics, SHOWN_HOOK, failure);
        }
    }

    /**
     * Asked after the retry loop looks up a saved batch, before it claims the pending entry.
     * Answers what {@link #toSend} does on a successful switch read, and null if that read fails.
     * A null answer takes the native snapshot-loop backedge,
     * leaving the pending batch for another check on every retry. There is no empty factory fallback:
     * an active anonymity selection or allocation failure cannot forward the original. Never throws.
     */
    @Nullable
    public static Object toRetry(Object store, Object batch) {
        return toRetry(store, batch, PATCHED, ANONYMOUS, MARKING, MARKS, COUNTED, RINGS);
    }

    @Nullable
    static Object toRetry(@Nullable Object store, Object batch, Batches batches, BooleanSupplier anonymous,
                          BooleanSupplier marking, StoryMarks marks, Diagnostics diagnostics) {
        return toRetry(store, batch, batches, anonymous, marking, marks, diagnostics, NOT_SHOWN);
    }

    @Nullable
    static Object toRetry(@Nullable Object store, Object batch, Batches batches, BooleanSupplier anonymous,
                          BooleanSupplier marking, StoryMarks marks, Diagnostics diagnostics, Shown shown) {
        return choose(store, batch, batches, anonymous, marking, marks, diagnostics, shown, true);
    }

    /** Whether views are held back: the switch on, HushGram not paused and the settings read. */
    static boolean anonymous() {
        return Utils.settingsReady() && Settings.VIEW_STORIES_ANONYMOUSLY.get();
    }

    /**
     * Sends the stories marked as seen that an earlier batch held back, through Instagram's own send
     * for [session]'s account, handing it an empty batch for {@link #toSend} to fill. Never throws.
     */
    static void sendMarked(Object session) {
        try {
            Object batch = emptyBatch();
            if (batch != null) send(session, batch);
        } catch (Throwable failure) {
            report(COUNTED, MARKED_HOOK, failure);
        }
    }

    /** No per-call diagnostic adapter is allocated outside the protection. */
    private static void saw(Diagnostics diagnostics) {
        try {
            diagnostics.saw();
        } catch (Throwable ignored) {
            // Counters are optional, including under allocation failure.
        }
    }

    private static void counted(Diagnostics diagnostics, boolean marked) {
        try {
            if (marked) diagnostics.sentMarked();
            else diagnostics.heldBack();
        } catch (Throwable ignored) {
            // Counters are optional, including under allocation failure.
        }
    }

    private static void report(Diagnostics diagnostics, String hook, Throwable failure) {
        try {
            diagnostics.threw(hook, failure);
        } catch (Throwable ignored) {
            // There is nowhere left to report a diagnostic failure.
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: a new batch of Instagram's, from its constructor taking nothing. */
    @Nullable
    public static Object emptyBatch() {
        return null;
    }

    /** Filled in by the patch: the map of stories [batch] holds, the one sent under "reels". */
    @Nullable
    public static Map<?, ?> seenStories(Object batch) {
        return null;
    }

    /** Filled in by the patch: hands [batch] to Instagram's send for [session]'s account. */
    public static void send(Object session, Object batch) {
    }

    /** Filled in by the patch: Instagram's user ID for the account [store], the store that sends, is for. */
    @Nullable
    public static String storeAccount(Object store) {
        return null;
    }

    /** Filled in by the patch: Instagram's user ID for [session], the account signed in. */
    @Nullable
    public static String sessionAccount(Object session) {
        return null;
    }
}
