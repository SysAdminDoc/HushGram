/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide reels with few views" patch (#444, #780).
 *
 * <p>It filters the same pages as {@link CaptionWordReels}: each page the Reels viewer's network
 * callback takes, already turned into clips items, with the viewer's module name. Only the Reels
 * tab's pages are filtered, so a reel opened from search, a profile, Saved or a message still plays.
 *
 * <p>A reel goes when its post is outside a number the user typed: fewer views, likes, comments,
 * shares or saves than the minimum or more than the maximum, fewer likes or comments for each 1,000
 * views than asked, a video shorter than the shortest or longer than the longest, or posted longer ago
 * than the maximum age. A number of 0 leaves that one alone, and a post that doesn't carry what a number
 * looks at stays for that number. Instagram 453 sends most Reels tab posts with no view count at all
 * but with their likes, so likes are what judges most reels there. Every reel from an account the
 * user follows stays. A page where every reel would go keeps every item, since Instagram keeps an
 * empty page as the tab's end, and the report counts it. Once the viewer holds reels, {@link #added}
 * takes those reels out where its data source takes the page in, even when none stay, so short
 * pages of one to three reels don't slip through whole (#780). Newest first puts the reels a page keeps in the order they
 * were posted, newest on top, in the places reels held. Off, paused, before the settings are read, or
 * when anything throws, the page comes back as it came, the same list.
 *
 * <p>With Also on Home on, the same numbers judge the suggested posts on Home through
 * {@link #homeReason}.
 */
public final class LowViewReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Low view reels";

    /** The diagnostic counter route of what Also on Home couldn't read in Home's posts. */
    static final String HOME_ROUTE = "Low view Home posts";

    /** The diagnostic counter route of the lists the Reels viewer's data source adds once it holds reels. */
    static final String ADDED_ROUTE = "Low view reels added";

    /** The reason, and kind, of an item whose view count is under the minimum. */
    static final String BELOW = "below the minimum";

    /** The reason, and kind, of an item whose view count is over the maximum. */
    static final String ABOVE = "above the maximum views";

    /** The reason, and kind, of an item whose like count is under the minimum likes. */
    static final String FEW_LIKES = "below the minimum likes";

    /** The reason, and kind, of an item whose like count is over the maximum likes. */
    static final String MANY_LIKES = "above the maximum likes";

    /** The reasons, and kinds, of an item with fewer or more comments than asked. */
    static final String FEW_COMMENTS = "below the minimum comments";
    static final String MANY_COMMENTS = "above the maximum comments";

    /** The reasons, and kinds, of an item shared fewer or more times than asked. */
    static final String FEW_SHARES = "below the minimum shares";
    static final String MANY_SHARES = "above the maximum shares";

    /** The reasons, and kinds, of an item saved fewer or more times than asked. */
    static final String FEW_SAVES = "below the minimum saves";
    static final String MANY_SAVES = "above the maximum saves";

    /** The reason, and kind, of an item with fewer likes for each 1,000 views than asked. */
    static final String FEW_LIKES_FOR_VIEWS = "few likes for its views";

    /** The reason, and kind, of an item with fewer comments for each 1,000 views than asked. */
    static final String FEW_COMMENTS_FOR_VIEWS = "few comments for its views";

    /** The reasons, and kinds, of a video shorter than the shortest or longer than the longest. */
    static final String TOO_SHORT = "shorter than the shortest";
    static final String TOO_LONG = "longer than the longest";

    /** The reason, and kind, of a post older than the maximum age. */
    static final String TOO_OLD = "older than the maximum age";

    /**
     * The kinds counted along the way: not a reel with a post, a post with no view count, a post with
     * no like or comment count, length or time while one is looked at, and a followed author kept.
     */
    static final String NOT_A_REEL = "no post read";
    static final String NO_VIEWS = "no view count";
    static final String NO_LIKES = "no like count";
    static final String NO_COMMENTS = "no comment count";
    static final String NO_SHARES = "no share count";
    static final String NO_SAVES = "no save count";
    static final String NO_LENGTH = "no length";
    static final String NO_TIME = "no post time";
    static final String FOLLOWED = "followed author kept";

    /** The kind counted for a page left whole because every reel in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** The kind counted for a page whose reels Newest first put in a new order. */
    static final String NEWEST_FIRST = "page put newest first";

    /** The count of reels taken out, in the diagnostics report. */
    static final String REMOVED = "reels hidden for few views";

    /** The view, like or comment count, or post time, of a post that has none. */
    static final long NO_COUNT = -1;

    /** The length of a post that has none, a photo for one. */
    static final double NO_SECONDS = -1;

    private static final long DAY_SECONDS = 86_400L;

    /** Reads what decides an item; the patch-filled stubs outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's view count, or {@link #NO_COUNT} when it carries none. */
        long views(Object media);

        /** The post's like count, or {@link #NO_COUNT} when it carries none. */
        long likes(Object media);

        /** Whether the post's author is someone the user follows. */
        boolean followed(Object media);

        /** The post's comment count, or {@link #NO_COUNT} when it carries none. */
        default long comments(Object media) {
            return NO_COUNT;
        }

        /** How many times the post was shared, or {@link #NO_COUNT} when it doesn't say. */
        default long shares(Object media) {
            return NO_COUNT;
        }

        /** How many times the post was saved, or {@link #NO_COUNT} when it doesn't say. */
        default long saves(Object media) {
            return NO_COUNT;
        }

        /** The post's video length in seconds, or {@link #NO_SECONDS} when it has none. */
        default double seconds(Object media) {
            return NO_SECONDS;
        }

        /** When the post went up, in seconds since 1970, or {@link #NO_COUNT} when it doesn't say. */
        default long postedAt(Object media) {
            return NO_COUNT;
        }
    }

    /**
     * The numbers a post is judged by, 0 for each one left alone, and whether a page's reels are put
     * newest first.
     */
    static final class Rules {
        int minViews;
        int maxViews;
        int minLikes;
        int maxLikes;
        int minComments;
        int maxComments;
        int minShares;
        int maxShares;
        int minSaves;
        int maxSaves;
        int likesPerThousand;
        int commentsPerThousand;
        int shortest;
        int longest;
        int maxAgeDays;
        boolean newestFirst;

        Rules minViews(int value) {
            minViews = value;
            return this;
        }

        Rules maxViews(int value) {
            maxViews = value;
            return this;
        }

        Rules minLikes(int value) {
            minLikes = value;
            return this;
        }

        Rules maxLikes(int value) {
            maxLikes = value;
            return this;
        }

        Rules comments(int min, int max) {
            minComments = min;
            maxComments = max;
            return this;
        }

        Rules shares(int min, int max) {
            minShares = min;
            maxShares = max;
            return this;
        }

        Rules saves(int min, int max) {
            minSaves = min;
            maxSaves = max;
            return this;
        }

        Rules likesPerThousand(int value) {
            likesPerThousand = value;
            return this;
        }

        Rules commentsPerThousand(int value) {
            commentsPerThousand = value;
            return this;
        }

        Rules shortest(int seconds) {
            shortest = seconds;
            return this;
        }

        Rules longest(int seconds) {
            longest = seconds;
            return this;
        }

        Rules maxAgeDays(int days) {
            maxAgeDays = days;
            return this;
        }

        Rules newestFirst(boolean value) {
            newestFirst = value;
            return this;
        }

        /** Whether any number takes posts out. */
        boolean judges() {
            return minViews > 0 || maxViews > 0 || minLikes > 0 || maxLikes > 0 || likesPerThousand > 0
                    || minComments > 0 || maxComments > 0 || commentsPerThousand > 0 || minShares > 0 || maxShares > 0
                    || minSaves > 0 || maxSaves > 0 || shortest > 0 || longest > 0 || maxAgeDays > 0;
        }

        /** Whether a page has anything done to it: a number, or Newest first. */
        boolean anything() {
            return judges() || newestFirst;
        }

        /**
         * The first number [media] is outside of, or null when it's inside every one or doesn't carry
         * what they look at. Counts what it couldn't read under [route]. A followed author isn't
         * looked at here.
         */
        String reason(Reader reader, Object media, long nowSeconds, String route) {
            boolean viewsNeeded = minViews > 0 || maxViews > 0 || likesPerThousand > 0 || commentsPerThousand > 0;
            long views = viewsNeeded ? reader.views(media) : NO_COUNT;
            if (viewsNeeded && views == NO_COUNT) FeedFilterCounters.sawKind(route, NO_VIEWS);
            if (views != NO_COUNT) {
                if (minViews > 0 && views < minViews) return BELOW;
                if (maxViews > 0 && views > maxViews) return ABOVE;
            }
            if (minLikes > 0 || maxLikes > 0 || likesPerThousand > 0) {
                long likes = reader.likes(media);
                if (likes == NO_COUNT) {
                    FeedFilterCounters.sawKind(route, NO_LIKES);
                } else {
                    if (minLikes > 0 && likes < minLikes) return FEW_LIKES;
                    if (maxLikes > 0 && likes > maxLikes) return MANY_LIKES;
                    if (likesPerThousand > 0 && views > 0 && likes * 1000 < (long) likesPerThousand * views) {
                        return FEW_LIKES_FOR_VIEWS;
                    }
                }
            }
            boolean perThousand = commentsPerThousand > 0 && views > 0;
            if (minComments > 0 || maxComments > 0 || perThousand) {
                long comments = reader.comments(media);
                if (comments == NO_COUNT) {
                    FeedFilterCounters.sawKind(route, NO_COMMENTS);
                } else {
                    if (minComments > 0 && comments < minComments) return FEW_COMMENTS;
                    if (maxComments > 0 && comments > maxComments) return MANY_COMMENTS;
                    if (perThousand && comments * 1000 < (long) commentsPerThousand * views) return FEW_COMMENTS_FOR_VIEWS;
                }
            }
            if (minShares > 0 || maxShares > 0) {
                String why = outside(reader.shares(media), minShares, maxShares, FEW_SHARES, MANY_SHARES, NO_SHARES, route);
                if (why != null) return why;
            }
            if (minSaves > 0 || maxSaves > 0) {
                String why = outside(reader.saves(media), minSaves, maxSaves, FEW_SAVES, MANY_SAVES, NO_SAVES, route);
                if (why != null) return why;
            }
            if (shortest > 0 || longest > 0) {
                double seconds = reader.seconds(media);
                if (!(seconds > 0)) FeedFilterCounters.sawKind(route, NO_LENGTH);
                else if (shortest > 0 && seconds < shortest) return TOO_SHORT;
                else if (longest > 0 && seconds > longest) return TOO_LONG;
            }
            if (maxAgeDays > 0) {
                long posted = reader.postedAt(media);
                if (posted <= 0) FeedFilterCounters.sawKind(route, NO_TIME);
                else if (nowSeconds - posted > maxAgeDays * DAY_SECONDS) return TOO_OLD;
            }
            return null;
        }

        /**
         * [below] when [count] is under [min], [above] when it's over [max], each only when set, and
         * null otherwise. A post that doesn't say is counted under [none] and stays.
         */
        private static String outside(long count, int min, int max, String below, String above, String none, String route) {
            if (count == NO_COUNT) {
                FeedFilterCounters.sawKind(route, none);
                return null;
            }
            if (min > 0 && count < min) return below;
            if (max > 0 && count > max) return above;
            return null;
        }
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return LowViewReels.media(item);
        }

        @Override
        public long views(Object media) {
            Integer plays = playCount(media);
            if (plays == null) plays = igPlayCount(media);
            if (plays == null) plays = viewCount(media);
            return plays == null ? NO_COUNT : plays.longValue();
        }

        @Override
        public long likes(Object media) {
            Integer likes = likeCount(media);
            return likes == null ? NO_COUNT : likes.longValue();
        }

        @Override
        public boolean followed(Object media) {
            return Boolean.TRUE.equals(LowViewReels.followed(media));
        }

        @Override
        public long comments(Object media) {
            Integer comments = commentCount(media);
            return comments == null ? NO_COUNT : comments.longValue();
        }

        @Override
        public long shares(Object media) {
            Integer shares = shareCount(media);
            return shares == null ? NO_COUNT : shares.longValue();
        }

        @Override
        public long saves(Object media) {
            Integer saves = saveCount(media);
            return saves == null ? NO_COUNT : saves.longValue();
        }

        @Override
        public double seconds(Object media) {
            Double seconds = videoLength(media);
            return seconds == null ? NO_SECONDS : seconds;
        }

        @Override
        public long postedAt(Object media) {
            Long posted = LowViewReels.postedAt(media);
            return posted == null ? NO_COUNT : posted;
        }
    };

    private LowViewReels() {
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the reels outside the numbers, or with its reels newest first,
     * while the switch is on and the page is for the Reels tab, and [items] itself when the viewer is
     * any other, when nothing changes, when every reel would go and Newest first is off, when the
     * switch is off or paused, or when anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, LowViewReels::rulesWhenOn, LowViewReels::nowSeconds);
    }

    static List<?> page(List<?> items, String module, Reader reader, Supplier<Rules> rulesOf, LongSupplier now) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.LOW_VIEW_REELS);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0) return items;
            Rules rules = rulesOf.get();
            if (rules == null || !rules.anything()) return items;
            long nowSeconds = now.getAsLong();

            boolean[] goes = new boolean[size];
            Object[] posts = new Object[size];
            Map<String, Integer> reasons = new LinkedHashMap<>();
            int matched = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                String why = null;
                if (media == null) {
                    FeedFilterCounters.sawKind(ROUTE, NOT_A_REEL);
                } else {
                    reels++;
                    posts[at] = media;
                    why = rules.reason(reader, media, nowSeconds, ROUTE);
                    if (why != null && reader.followed(media)) {
                        FeedFilterCounters.sawKind(ROUTE, FOLLOWED);
                        why = null;
                    }
                }
                if (why != null) {
                    matched++;
                    Integer before = reasons.get(why);
                    reasons.put(why, before == null ? 1 : before + 1);
                    FeedFilterCounters.sawKind(ROUTE, why);
                }
                goes[at++] = why != null;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            boolean remove = matched > 0;
            if (remove && matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Low view reels: kept a page of " + size + " where every reel matched");
                remove = false;
            }

            List<Object> kept = new ArrayList<>(remove ? size - matched : size);
            List<Object> keptPosts = new ArrayList<>(kept.size());
            for (at = 0; at < size; at++) {
                if (remove && goes[at]) continue;
                kept.add(items.get(at));
                keptPosts.add(posts[at]);
            }
            boolean reordered = rules.newestFirst && newestFirst(kept, keptPosts, reader);
            if (!remove && !reordered) return items;

            if (reordered) {
                FeedFilterCounters.sawKind(ROUTE, NEWEST_FIRST);
                Logger.printDebug(() -> "Low view reels: put a page of " + size + " newest first");
            }
            if (remove) {
                for (Map.Entry<String, Integer> reason : reasons.entrySet()) {
                    FeedFilterCounters.removed(ROUTE, reason.getValue(), reason.getKey());
                }
                for (int count = 0; count < matched; count++) HookStatus.counted(FamilyNames.LOW_VIEW_REELS, REMOVED);
                int left = kept.size();
                int removed = matched;
                Logger.printDebug(() -> "Low view reels: took " + removed + " of " + size + " reels out, " + left + " left");
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOW_VIEW_REELS, "reels page", failure);
            return items;
        }
    }

    /**
     * Injected first thing in the Reels viewer's data source where it adds a list to its items or
     * inserts one among them, with the viewer's module name and [held], how many items it holds
     * already. {@link #page} keeps a page whole when every reel on it would go, and the Reels tab's
     * pages hold one to three reels, so with a length or age set most short or old reels came through
     * that way (#780). Once the viewer holds more than one item, every reel outside the numbers goes
     * here, even when nothing else in the list stays: one item may be no more than the placeholder
     * Instagram shows while a page loads, so the viewer always keeps a reel to stand on. Reels from
     * accounts the user follows stay. Answers [items] itself for any other viewer, before that, when
     * nothing goes, when the switch is off or paused, or when anything goes wrong. Never throws.
     */
    public static List<?> added(List<?> items, String module, int held) {
        return added(items, module, held, PATCHED, LowViewReels::rulesWhenOn, LowViewReels::nowSeconds);
    }

    static List<?> added(List<?> items, String module, int held, Reader reader, Supplier<Rules> rulesOf, LongSupplier now) {
        if (items == null) return null;
        try {
            if (held <= 1 || !PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            if (size == 0) return items;
            Rules rules = rulesOf.get();
            if (rules == null || !rules.anything()) return items;
            FeedFilterCounters.sawList(ADDED_ROUTE, size);
            long nowSeconds = now.getAsLong();

            boolean[] goes = new boolean[size];
            Map<String, Integer> reasons = new LinkedHashMap<>();
            int matched = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                String why = media == null ? null : rules.reason(reader, media, nowSeconds, ADDED_ROUTE);
                if (why != null && reader.followed(media)) {
                    FeedFilterCounters.sawKind(ADDED_ROUTE, FOLLOWED);
                    why = null;
                }
                if (why != null) {
                    matched++;
                    Integer before = reasons.get(why);
                    reasons.put(why, before == null ? 1 : before + 1);
                }
                goes[at++] = why != null;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size || matched == 0) return items;

            List<Object> kept = new ArrayList<>(size - matched);
            for (at = 0; at < size; at++) {
                if (!goes[at]) kept.add(items.get(at));
            }
            for (Map.Entry<String, Integer> reason : reasons.entrySet()) {
                FeedFilterCounters.removed(ADDED_ROUTE, reason.getValue(), reason.getKey());
            }
            for (int count = 0; count < matched; count++) HookStatus.counted(FamilyNames.LOW_VIEW_REELS, REMOVED);
            int left = kept.size();
            int removed = matched;
            Logger.printDebug(() -> "Low view reels: took " + removed + " of " + size + " added reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOW_VIEW_REELS, "reels items added", failure);
            return items;
        }
    }

    /**
     * Puts the reels in [items] that say when they were posted in that order, newest first, in the
     * places those reels held, and answers whether anything moved. [posts] holds each item's post,
     * null for an item that isn't a reel. Reels with the same time keep their order, and a reel that
     * doesn't say, or an item that isn't a reel, stays where it is.
     */
    private static boolean newestFirst(List<Object> items, List<Object> posts, Reader reader) {
        int size = items.size();
        List<Integer> places = new ArrayList<>(size);
        List<long[]> timed = new ArrayList<>(size);
        for (int at = 0; at < size; at++) {
            Object post = posts.get(at);
            if (post == null) continue;
            long posted = reader.postedAt(post);
            if (posted <= 0) continue;
            places.add(at);
            timed.add(new long[]{posted, at});
        }
        if (timed.size() < 2) return false;
        // A stable sort, so reels posted at the same second keep the order Instagram gave them.
        List<long[]> sorted = new ArrayList<>(timed);
        sorted.sort((a, b) -> Long.compare(b[0], a[0]));
        boolean moved = false;
        List<Object> original = new ArrayList<>(items);
        for (int slot = 0; slot < places.size(); slot++) {
            int from = (int) sorted.get(slot)[1];
            int to = places.get(slot);
            if (from != to) moved = true;
            items.set(to, original.get(from));
        }
        return moved;
    }

    /**
     * The numbers while the switch is on, with Newest first, or null (nothing changes) while off,
     * paused or before the settings are read.
     */
    static Rules rulesWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_LOW_VIEW_REELS.get()) return null;
        return new Rules()
                .minViews(Settings.MIN_REEL_VIEWS.get())
                .maxViews(Settings.MAX_REEL_VIEWS.get())
                .minLikes(Settings.MIN_REEL_LIKES.get())
                .maxLikes(Settings.MAX_REEL_LIKES.get())
                .comments(Settings.MIN_REEL_COMMENTS.get(), Settings.MAX_REEL_COMMENTS.get())
                .shares(Settings.MIN_REEL_SHARES.get(), Settings.MAX_REEL_SHARES.get())
                .saves(Settings.MIN_REEL_SAVES.get(), Settings.MAX_REEL_SAVES.get())
                .likesPerThousand(Settings.MIN_REEL_LIKES_PER_1000.get())
                .commentsPerThousand(Settings.MIN_REEL_COMMENTS_PER_1000.get())
                .shortest(Settings.MIN_REEL_SECONDS.get())
                .longest(Settings.MAX_REEL_SECONDS.get())
                .maxAgeDays(Settings.MAX_REEL_AGE_DAYS.get())
                .newestFirst(Settings.REELS_NEWEST_FIRST.get());
    }

    /** The numbers for Home's posts: also needs Also on Home on, and a number that takes posts out. */
    static Rules homeRulesWhenOn() {
        Rules rules = rulesWhenOn();
        if (rules == null || !Settings.HIDE_LOW_VIEW_HOME.get() || !rules.judges()) return null;
        return rules;
    }

    /** Whether Also on Home takes posts out of Home now: the switch, Also on Home and a number all on. */
    public static boolean homeOn() {
        return homeRulesWhenOn() != null;
    }

    /**
     * Called by Home's per-item filter with the post of a suggested post on Home. Answers the number
     * the post is outside of while the switch and Also on Home are on, and null when it's inside them
     * all, when its author is followed, when it's null, while off or paused, or when anything goes
     * wrong. Never throws.
     */
    public static String homeReason(Object post) {
        return homeReason(post, PATCHED, LowViewReels::homeRulesWhenOn, LowViewReels::nowSeconds, HOME_ROUTE);
    }

    static String homeReason(Object post, Reader reader, Supplier<Rules> rulesOf, LongSupplier now, String route) {
        if (post == null) return null;
        try {
            Rules rules = rulesOf.get();
            if (rules == null) return null;
            String why = rules.reason(reader, post, now.getAsLong(), route);
            if (why == null) return null;
            if (reader.followed(post)) {
                FeedFilterCounters.sawKind(route, FOLLOWED);
                return null;
            }
            return why;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LOW_VIEW_REELS, "home post", failure);
            return null;
        }
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /** A post's play count, which the patch fills in. Null as built, and for a post with none. */
    public static Integer playCount(Object media) {
        return null;
    }

    /** A post's Instagram-side play count, the one the patch falls back on. Null as built. */
    public static Integer igPlayCount(Object media) {
        return null;
    }

    /** A post's view count, the last one the patch falls back on. Null as built. */
    public static Integer viewCount(Object media) {
        return null;
    }

    /** A post's like count, which the patch fills in. Null as built, and for a post with none. */
    public static Integer likeCount(Object media) {
        return null;
    }

    /** A post's comment count, which the patch fills in. Null as built, and for a post with none. */
    public static Integer commentCount(Object media) {
        return null;
    }

    /** How many times a post was shared, which the patch fills in. Null as built, and for a post that doesn't say. */
    public static Integer shareCount(Object media) {
        return null;
    }

    /** How many times a post was saved, which the patch fills in. Null as built, and for a post that doesn't say. */
    public static Integer saveCount(Object media) {
        return null;
    }

    /** A post's video length in seconds, which the patch fills in. Null as built, and for a photo. */
    public static Double videoLength(Object media) {
        return null;
    }

    /** When a post went up, in seconds since 1970, which the patch fills in. Null as built. */
    public static Long postedAt(Object media) {
        return null;
    }

    /**
     * Whether the user follows the post's author, read through the author's friendship status.
     * Null as built, and when the post has no author or status.
     */
    public static Boolean followed(Object media) {
        return null;
    }
}
