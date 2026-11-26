/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the third switch of the "View stories anonymously" patch, Mark a story seen when you
 * like or reply (#876).
 *
 * <p>While views are held back, a like or a reply comes from someone Instagram never saw watch the
 * story. With this switch on, the patch calls {@link #liked} in Instagram's story like store right
 * before it builds the request for a like (an unlike never gets here), and {@link #answered} first
 * thing in the send a story reply and a quick reaction from the reply bar both go through. Each
 * marks that one story as the Mark as seen button does ({@link StoryMarks}) and lets its view go
 * straight away: a story an earlier batch held back goes through {@link StorySeen#sendMarked}, and
 * one still waiting in an open viewer's batch goes when that batch is handed to Instagram's send,
 * which the patch does at once on the main thread ({@link #viewerOpened} remembers each viewer as
 * Instagram builds it). Instagram's send then asks {@link StorySeen#toSend}, which picks out the
 * marked story and holds every other one back as before.
 *
 * <p>Your own story has no view to send (#850) and is skipped, and a story already sent is left
 * alone. With the switch off, views not held back, HushGram paused or the settings not read,
 * nothing is marked or sent. Nothing here throws into Instagram: on a failure the like or reply goes
 * on as it would without the switch.
 */
public final class StoryAnswerSeen {
    static final String HOOK = "story answer seen";

    /** The flusher of each story viewer Instagram built, held weakly so a closed viewer is let go. */
    private static final Map<Object, Boolean> VIEWERS = Collections.synchronizedMap(new WeakHashMap<>());

    private StoryAnswerSeen() {
    }

    /** Where the hooks get what they need from Instagram's objects. Tests hand in their own. */
    interface Reader {
        /** The account signed in that [store], Instagram's story like store, is for, or null. */
        @Nullable
        Object likeSession(Object store);

        /** Instagram's id for [media], a story ("123_456"), or null. */
        @Nullable
        String mediaId(Object media);

        /** Instagram's user ID for [session], the account signed in, or null. */
        @Nullable
        String account(Object session);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public Object likeSession(Object store) {
            return StoryAnswerSeen.likeSession(store);
        }

        @Override
        public String mediaId(Object media) {
            return storyMediaId(media);
        }

        @Override
        public String account(Object session) {
            return StorySeen.sessionAccount(session);
        }
    };

    /** How a marked story's view is let go. */
    interface Release {
        /** Sends the marked stories earlier batches of [session]'s account held back. */
        void sendHeld(Object session);

        /** Hands each open viewer's batch of views to Instagram's send now. */
        void sendViewers();
    }

    /** Sends held stories through {@link StorySeen#sendMarked} and open viewers' batches through [flush], on the main thread. */
    static Release releasing(Consumer<Object> flush) {
        return new Release() {
            @Override
            public void sendHeld(Object session) {
                StorySeen.sendMarked(session);
            }

            @Override
            public void sendViewers() {
                Utils.runOnMainThreadNowOrLater(() -> flushViewers(flush));
            }
        };
    }

    static final Release SENT = releasing(StoryAnswerSeen::flush);

    /**
     * Injected in Instagram's story like store right before it builds the request for a like, with
     * the store and Instagram's id for the story. Never throws.
     */
    public static void liked(Object store, String id) {
        likedFrom(store, id, StoryAnswerSeen::switchedOn, PATCHED, StorySeen.MARKS, SENT);
    }

    /** As {@link #liked}. Answers whether the story's view was let go. */
    static boolean likedFrom(@Nullable Object store, @Nullable String id, BooleanSupplier on, Reader reader,
                             StoryMarks marks, Release release) {
        try {
            if (!on.getAsBoolean() || store == null) return false;
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            Logger.printDebug(() -> "Story views: a story like reached the hook");
            return letGo(reader.likeSession(store), id, reader, marks, release);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
            return false;
        }
    }

    /**
     * Injected first thing in the send a story reply and a quick reaction go through, with the
     * account signed in and the story. Never throws.
     */
    public static void answered(Object session, Object media) {
        answeredFrom(session, media, StoryAnswerSeen::switchedOn, PATCHED, StorySeen.MARKS, SENT);
    }

    /** As {@link #answered}. Answers whether the story's view was let go. */
    static boolean answeredFrom(@Nullable Object session, @Nullable Object media, BooleanSupplier on, Reader reader,
                                StoryMarks marks, Release release) {
        try {
            if (!on.getAsBoolean() || session == null || media == null) return false;
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            Logger.printDebug(() -> "Story views: a story reply reached the hook");
            return letGo(session, reader.mediaId(media), reader, marks, release);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
            return false;
        }
    }

    /**
     * Injected at the end of the constructor of a story viewer's flusher, the one that hands the
     * viewer's batch of views to Instagram's send. Remembers it, weakly. Never throws.
     */
    public static void viewerOpened(Object flusher) {
        try {
            if (flusher == null) return;
            VIEWERS.put(flusher, Boolean.TRUE);
            Logger.printDebug(() -> "Story views: a story viewer opened");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * Marks the story [id] names for [session]'s account and lets its view go. Skips your own story,
     * one already sent, and anything it can't read. Answers whether the view was let go.
     */
    static boolean letGo(@Nullable Object session, @Nullable String id, Reader reader, StoryMarks marks,
                         Release release) {
        String story = StoryMarks.storyOf(id);
        if (session == null || story == null) return false;
        String account = reader.account(session);
        // Your own story or highlight sends no view (#850).
        if (account == null || account.equals(StoryMarks.ownerOf(id))) return false;
        if (marks.mark(account, story) != StoryMarks.State.MARKED) return false;
        if (marks.held(account, story)) release.sendHeld(session);
        else release.sendViewers();
        Logger.printDebug(() -> "Story views: let the view of a story you liked or answered go");
        return true;
    }

    /** Hands each remembered viewer to [flush], one failure never stopping the rest. */
    static void flushViewers(Consumer<Object> flush) {
        List<Object> viewers;
        synchronized (VIEWERS) {
            viewers = new ArrayList<>(VIEWERS.keySet());
        }
        for (Object viewer : viewers) {
            try {
                flush.accept(viewer);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
            }
        }
    }

    /** Whether a like or reply lets the story's view go: views held back and this switch on. Both read off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_STORIES_ANONYMOUSLY.get()
                && Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.get();
    }

    /** Forgets the viewers remembered. */
    static void resetForTests() {
        VIEWERS.clear();
    }

    /** How many viewers are remembered. */
    static int viewersForTests() {
        synchronized (VIEWERS) {
            return VIEWERS.size();
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: hands [viewer]'s batch of views to Instagram's send, as leaving the viewer does. */
    public static void flush(Object viewer) {
    }

    /** Filled in by the patch: the account signed in that [store], Instagram's story like store, is for. */
    @Nullable
    public static Object likeSession(Object store) {
        return null;
    }

    /** Filled in by the patch: Instagram's id for [media], a story. */
    @Nullable
    public static String storyMediaId(Object media) {
        return null;
    }
}
