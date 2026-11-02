/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Start Home on Following's "Mix in Following" switch (#438): a page of posts from accounts you
 * follow after each page of For you.
 *
 * <p>Home pages its feeds through one fetcher with one cursor. A load more asks for the page after
 * that cursor, and the reply's cursor then replaces it. The fetcher marks a request for Following
 * with Following's paging source, the one Instagram's picker puts on it, and Instagram then answers
 * with Following's posts. With the switch on, every other load more on For you asks for Following's
 * next page instead: {@link #cursor} answers Following's cursor, kept here, in place of Home's, and
 * {@link #sourceSet} and {@link #source} mark the request as Following's. The reply's posts go into
 * For you's list, and {@link #nextCursor} keeps the reply's cursor for Following's next page and
 * answers Home's own in its place, so the load more after it carries on with For you where it left
 * off. {@link #moreAvailable} keeps Home paging after Following's last page, and {@link #rewound}
 * answers Home's cursor where Instagram, given a page with nothing new, goes back to the cursor the
 * request asked after.
 *
 * <p>Home asks for one page at a time, so the first reply after a Following request is Following's.
 * A load more while that request is still out asks for the same page again, which Home's check for a
 * request already out drops, and which asks again after a failure, up to {@link #TRIES} times. A
 * head load (pull to refresh, a fresh Home) starts Following over. Once Following runs out, For you
 * pages on its own until Home starts over.
 *
 * <p>Off to start, and only on a feed with no paging source of its own: For you and the plain Home
 * feed. While it's off, HushGram is paused or the settings aren't read yet, every answer is
 * Instagram's own.
 */
public final class FollowingMix {
    /** Following's paging source, its feed type's server value. */
    static final String FOLLOWING = "following";

    /** How many times one Following page is asked for before For you goes on alone. */
    static final int TRIES = 3;

    /** The report's count of load mores on For you that asked for Following's next page. */
    static final String ASKED = "Following page asked for between For you's";

    /** The report's count of Following pages whose cursor was kept and Home's left as For you's. */
    static final String MIXED = "Following page mixed into For you";

    /** The report's count of Following pages that ended Following. */
    static final String RAN_OUT = "Following ran out in For you";

    /** The report's count of Following pages given up on after {@link #TRIES} requests with no reply. */
    static final String UNANSWERED = "Following page asked for and not answered";

    private static final Object LOCK = new Object();

    /** Set from {@link #cursor} answering Following's cursor until {@link #source} marks the request. Guarded by {@link #LOCK}. */
    private static boolean asking;

    /** Set while a Following request is out and its reply hasn't come. Guarded by {@link #LOCK}. */
    private static boolean out;

    /** How many requests have asked for the Following page that's out. Guarded by {@link #LOCK}. */
    private static int tries;

    /** Set once the reply to a Following request has handed over its cursor. Guarded by {@link #LOCK}. */
    private static boolean cursorSeen;

    /** Whether the next load more on For you is Following's turn. Guarded by {@link #LOCK}. */
    private static boolean followingNext = true;

    /** The cursor of Following's next page, or null for its first. Guarded by {@link #LOCK}. */
    @Nullable private static String followingCursor;

    /** The cursor the Following request that's out asked after. Guarded by {@link #LOCK}. */
    @Nullable private static String sentCursor;

    /** Home's cursor from before the Following request, which its reply hands back. Guarded by {@link #LOCK}. */
    @Nullable private static String homeCursor;

    /** Set once a Following reply came back with no next page. Guarded by {@link #LOCK}. */
    private static boolean ranOut;

    private FollowingMix() {
    }

    /**
     * Called where Home's fetcher has the cursor it's about to ask after: null on a head load, Home's
     * own on a load more. [head] is non-zero on a head load, which starts Following over, and [feed]
     * is the fetcher's paging source, set on Following and Favorites. On a load more from For you,
     * on Following's turn, answers Following's cursor. Never throws.
     */
    @Nullable
    public static String cursor(@Nullable String cursor, int head, @Nullable String feed) {
        try {
            boolean unanswered = false;
            String answer;
            synchronized (LOCK) {
                // A Following cursor answered before a request that was never built is spent.
                asking = false;
                if (!on()) return cursor;
                if (head != 0) {
                    startOver();
                    return cursor;
                }
                if ((feed != null && !feed.isEmpty()) || ranOut) return cursor;
                if (out && tries >= TRIES) {
                    out = false;
                    ranOut = true;
                    unanswered = true;
                    answer = cursor;
                } else if (out) {
                    asking = true;
                    answer = sentCursor;
                } else if (followingNext) {
                    asking = true;
                    homeCursor = cursor;
                    sentCursor = followingCursor;
                    answer = followingCursor;
                } else {
                    followingNext = true;
                    sentCursor = null;
                    answer = cursor;
                }
            }
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (unanswered) {
                HookStatus.counted(FamilyNames.FOLLOWING_FEED, UNANSWERED);
                Logger.printDebug(() -> "Following mix: Following didn't answer, For you goes on alone");
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "following mix cursor", failure);
            return cursor;
        }
    }

    /**
     * Called with whether the request the fetcher just built is to carry the fetcher's paging
     * source, as an int. Answers 1 for a Following request, [set] otherwise. Never throws.
     */
    public static int sourceSet(int set) {
        synchronized (LOCK) {
            return asking ? 1 : set;
        }
    }

    /**
     * Called with the paging source the request the fetcher just built is about to carry. Answers
     * Following's for a Following request and marks it as out, [source] otherwise. Never throws.
     */
    @Nullable
    public static String source(@Nullable String source) {
        try {
            synchronized (LOCK) {
                if (!asking) return source;
                asking = false;
                tries = out ? tries + 1 : 1;
                out = true;
                cursorSeen = false;
                followingNext = false;
            }
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, ASKED);
            Logger.printDebug(() -> "Following mix: asks for Following's next page");
            return FOLLOWING;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "following mix source", failure);
            return source;
        }
    }

    /**
     * Called with a reply's cursor for the next page as it's about to replace Home's. For a
     * Following reply, keeps it for Following's next page and answers Home's cursor from before the
     * request. Never throws.
     */
    @Nullable
    public static String nextCursor(@Nullable String next) {
        try {
            synchronized (LOCK) {
                if (!out) return next;
                cursorSeen = true;
                followingCursor = next;
                return homeCursor;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "following mix reply cursor", failure);
            return next;
        }
    }

    /**
     * Called with whether a reply says there's more, as an int, as it's about to replace Home's.
     * Ends a Following reply: Following runs out when it says there's no more or brought no cursor,
     * and the answer is 1, since Home asked for more of For you. Answers [more] otherwise. Never
     * throws.
     */
    public static int moreAvailable(int more) {
        try {
            boolean ended;
            synchronized (LOCK) {
                if (!out) return more;
                out = false;
                tries = 0;
                ended = more == 0 || !cursorSeen || followingCursor == null || followingCursor.isEmpty();
                cursorSeen = false;
                if (ended) ranOut = true;
            }
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, ended ? RAN_OUT : MIXED);
            Logger.printDebug(() -> "Following mix: " + (ended ? "Following ran out" : "kept Following's next cursor"));
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "following mix reply more", failure);
            return more;
        }
    }

    /**
     * Called with the cursor a request asked after, as Instagram goes back to it because the reply
     * brought nothing new. For the last Following request's, answers Home's cursor from before it,
     * which keeps Home on For you. Answers [cursor] otherwise. Never throws.
     */
    @Nullable
    public static String rewound(@Nullable String cursor) {
        try {
            if (cursor == null || cursor.isEmpty()) return cursor;
            synchronized (LOCK) {
                return cursor.equals(sentCursor) ? homeCursor : cursor;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "following mix rewind", failure);
            return cursor;
        }
    }

    /** Starts Following over from its first page. Called holding {@link #LOCK}. */
    private static void startOver() {
        out = false;
        tries = 0;
        cursorSeen = false;
        followingNext = true;
        followingCursor = null;
        sentCursor = null;
        homeCursor = null;
        ranOut = false;
    }

    /** Whether the switch is on, HushGram isn't paused and the settings are read. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.FOLLOWING_MIX.get();
    }

    /** Forgets every request and cursor. Tests call it. */
    static void resetForTests() {
        synchronized (LOCK) {
            asking = false;
            startOver();
        }
    }
}
