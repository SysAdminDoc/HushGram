/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Asks Home for its next page right away when Hide suggested posts, or any other switch that takes
 * things out of Home, leaves a page of For you short (#52, #28).
 *
 * <p>On an account whose For you pages each carry about one followed post and four suggested ones,
 * Hide suggested posts leaves a page holding one post, and Instagram's loading row sits right under
 * it. Instagram asks for the next page when the scroll nears that row, so Home grew one post at a
 * time. Now, when a page of Home's response keeps fewer than {@link #SHORT_PAGE} items after the
 * switches took some out ({@link #pageParsed}), the next build of Home's list
 * ({@link #buildStarts}) posts a request for the next page to the main thread, to run once the
 * build is done. It's the call Instagram makes when that loading row comes into view, made on Home's
 * own load more policy, which still checks for itself that the feed is loaded and has a next page.
 * It keeps asking while pages come back short, {@link #CHAIN_CAP} times in a row at most, and a
 * page that keeps enough starts the count over.
 *
 * <p>Past that cap it goes on asking, up to {@link #END_CAP} in a row, while Home's list sits at its
 * end ({@link #atEnd}): nothing else would ask there, since Instagram asks on a scroll and as its
 * loading row comes into view, and a list that can't scroll further shows a row that's been in view
 * all along (#28). A short page no build of Home's list follows, one the switches emptied that
 * changes nothing Home draws, or one whose build ran while it was still loading, is decided by a
 * check {@link #SETTLE_MS} later ({@link #settle}) with the latest build that read its feed.
 *
 * <p>Instagram doesn't always answer a request with a page of Home's response. On the S22 the first
 * one after a cold start read some 60 posts outside any page, Hide videos took every one, and no page
 * followed, so Home sat under one post with no loading row and stopped (#28). Each request is watched
 * ({@link #watch}): while no page with items has answered it, it's asked again once Home's list sits
 * at its end, within the same {@link #END_CAP}.
 *
 * <p>A page the switches emptied leaves Home empty, and {@link FeedSuggestions#feedEnded} ends it so
 * Instagram draws its empty feed card rather than a loading placeholder nothing would ever fill. It
 * still asks for For you's next page then, within the same cap, and the posts it brings take the
 * card's place. Before, nothing asked, and the card stayed until something else loaded Home (#28).
 * While that page is on its way, Home keeps Instagram's loading row instead of the card for up to
 * {@link #HOLD_NANOS} ({@link #holdsEnd}): after a while away or an account switch, For you's first
 * page can be all suggestions with the followed posts a page behind, and the Welcome to Instagram
 * card showed for a few seconds before them (#126). An account that follows nobody reaches suggested
 * posts only, which it doesn't ask for while Hide suggested posts is on, so its Home ends on the card
 * at once (#105).
 *
 * <p>It never asks while a page is loading, once the feed has no next page, or for a feed that isn't
 * For you, so Following and Favorites page as they did. For you's suggested posts past the ones from
 * accounts you follow page from a source of their own, which it asks for only while Hide suggested
 * posts is off ({@link #asksFor}). Hide videos took a page of them down to a post or two and the
 * loading row under them never moved, since Instagram asks only on a scroll (#28). The Older Posts
 * page has builders of its own and is left alone. It fails open: with every switch that took
 * something out of Home off ({@link FeedSuggestions#switchesTookOut}), HushGram paused, the settings
 * not read yet, a stub the patch couldn't fill or anything thrown, Home waits for the scroll as
 * Instagram has it. With Fall back to Following on, it doesn't ask for an account whose For you
 * held only suggestions ({@link ForYouFallback#holdsNextPage}), since the next page brings more.
 */
public final class HomeNextPage {
    /** A page of Home's response is short when it keeps fewer items than this. */
    static final int SHORT_PAGE = 5;

    /** The most next pages asked for in a row while pages keep coming back short. */
    static final int CHAIN_CAP = 3;

    /** The most next pages asked for in a row while Home's list sits at its end, where no scroll would ask (#28). */
    static final int END_CAP = 10;

    /** How long after a short page was parsed a build of Home may still ask for the next one, in nanoseconds. */
    static final long WINDOW_NANOS = 10_000_000_000L;

    /** How long after a short page {@link #settle} checks whether a build decided it, in milliseconds. */
    static final long SETTLE_MS = 1_500L;

    /** How long after a request {@link #watch} checks whether a page answered it, in milliseconds. */
    static final long ANSWER_MS = 4_000L;

    /** How long after a request {@link #watch} keeps checking, in nanoseconds. */
    static final long WATCH_NANOS = 30_000_000_000L;

    /** For you's paging source, as Instagram names the feed Home's switcher calls ALL. */
    static final String FOR_YOU = "homecoming_all";

    /** The paging source of the suggested posts For you pages in once the posts from accounts you follow run out. */
    static final String SUGGESTED = "feed_recs";

    /** The report's count of next pages asked for. */
    static final String ASKED = "asked for the next page after a short page";

    /** The report's count of chains that reached their cap with pages still short. */
    static final String CAPPED = "chain stopped at its cap";

    /** How long an emptied Home keeps Instagram's loading row while its next page is on its way, in nanoseconds. */
    static final long HOLD_NANOS = 6_000_000_000L;

    /** The report's count of empty Homes that kept the loading row for their next page. */
    static final String HELD = "empty Home kept the loading row for its next page";

    /**
     * When the current hold on an empty Home's end began, by {@link #clock}: 0 when there's none, and
     * -1 once a hold ran out, until a page keeps something or another feed is read. Tests clear it.
     */
    static volatile long holdSince;

    /** The feed {@link #holdSince} is about, held weakly. Tests clear it. */
    @Nullable
    static volatile WeakReference<Object> holdFeed;

    /** Why {@link #holdsEnd} last let an empty Home end, so the debug log names a reason once in a row. */
    @Nullable
    private static volatile String endedBecause;

    /** How many pages of Home's response that held items have been parsed. Tests clear it. */
    static volatile long pages;

    /** The number ({@link #pages}) of the latest page if it came back short, 0 otherwise. Tests clear it. */
    static volatile long shortPage;

    /** When that page was parsed, by {@link #clock}. Tests clear it. */
    static volatile long shortPageAt;

    /** The number of the short page a build has already decided about. Tests clear it. */
    static volatile long decided;

    /** Next pages asked for in a row while pages came back short. Tests clear it. */
    static volatile int chain;

    /** Set when the next page parsed is the answer to a request this class made. Tests clear it. */
    static volatile boolean waitingForOurs;

    /** How many requests this class has made. Tests clear it. */
    static volatile long requests;

    /** The latest request ({@link #requests}) a page with items answered. Tests clear it. */
    static volatile long answered;

    /** When the latest request was made, by {@link #clock}. Tests clear it. */
    static volatile long requestAt;

    /** Set once the report has counted {@link #CAPPED} for the current chain. Tests clear it. */
    static volatile boolean capCounted;

    /** Set while a request is posted to the main thread and hasn't run yet. Tests clear it. */
    static volatile boolean posted;

    /** The latest build of Home's list since a request was posted, which the request reads. Tests clear it. */
    @Nullable
    static volatile Build latest;

    /** The latest build of Home's list that read its feed, which {@link #settle} asks with. Tests clear it. */
    @Nullable
    static volatile Build home;

    /** Set once the debug log has named the class of Home's list, so a phone run shows what {@link #atEnd} reads. */
    private static volatile boolean listNamed;

    /** The build of Home's list running on this thread, until it hands over its feed in {@link #feedRead}. */
    private static final ThreadLocal<Build> BUILDING = new ThreadLocal<>();

    /**
     * One build of Home's list: its adapter, and the feed it reads its "no next page" flag from. Both
     * are held weakly: a build that never reaches its feed stays on its thread until the next one,
     * {@link #home} keeps the latest that did, and neither may keep Home's fragment or feed alive.
     */
    static final class Build {
        final WeakReference<Object> adapter;
        @Nullable private volatile WeakReference<Object> feed;

        Build(Object adapter) {
            this.adapter = new WeakReference<>(adapter);
        }

        /** Notes the feed this build read its flag from. */
        void read(@Nullable Object feed) {
            this.feed = feed == null ? null : new WeakReference<>(feed);
        }

        /** The feed this build read its flag from, or null when it read none or the feed is gone. */
        @Nullable
        Object feed() {
            WeakReference<Object> read = feed;
            return read == null ? null : read.get();
        }
    }

    /** The time a short page's age is measured by. Tests stand in. */
    static volatile LongSupplier clock = System::nanoTime;

    /** Posts the request to the main thread. Tests stand in. */
    static volatile Consumer<Runnable> poster = Utils::runOnMainThread;

    /** Reads an adapter's load more policy, as {@link #policyOf} does. Tests stand in. */
    static volatile Function<Object, Object> policies = HomeNextPage::policyOf;

    /** Reads a feed's paging source, as {@link #sourceOf} does. Tests stand in. */
    static volatile Function<Object, String> sources = HomeNextPage::sourceOf;

    /** Asks a policy whether a page is loading, as {@link #loadingNow} does. Tests stand in. */
    static volatile ToIntFunction<Object> loading = HomeNextPage::loadingNow;

    /** Asks a policy whether the feed has a next page, as {@link #moreLeft} does. Tests stand in. */
    static volatile ToIntFunction<Object> more = HomeNextPage::moreLeft;

    /** Asks a policy for the next page, as {@link #askNextPage} does. Tests stand in. */
    static volatile BiConsumer<Object, Object> asker = HomeNextPage::askNextPage;

    /** Tells whether an adapter's list sits at its end, as {@link #atEnd} does. Tests stand in. */
    static volatile ToIntFunction<Object> ends = HomeNextPage::atEnd;

    /** Posts {@link #settle} to the main thread {@link #SETTLE_MS} later. Tests stand in. */
    static volatile Consumer<Runnable> later = HomeNextPage::settleLater;

    /** Posts {@link #watch} to the main thread {@link #ANSWER_MS} later. Tests stand in. */
    static volatile Consumer<Runnable> watcher = HomeNextPage::watchLater;

    private HomeNextPage() {
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for each page of Home's response that held
     * items: [read] of them, [lost] to the switches. A page that keeps {@link #SHORT_PAGE} or more
     * ends the chain. A shorter one the switches took items out of becomes the page the next build
     * decides about, and starts a new chain unless it answers a request made here, and
     * {@link #settle} checks on it later. A short page the switches took nothing out of is
     * Instagram's own and is left to it. A page that keeps anything lets the next empty Home hold
     * its end again ({@link #holdsEnd}). Never throws.
     */
    static void pageParsed(int read, int lost) {
        try {
            boolean ours = waitingForOurs;
            waitingForOurs = false;
            if (ours) answered = requests;
            if (read > lost) holdSince = 0;
            long number = ++pages;
            if (read - lost >= SHORT_PAGE) {
                chain = 0;
                capCounted = false;
                shortPage = 0;
                return;
            }
            if (lost <= 0) {
                shortPage = 0;
                return;
            }
            if (!ours) {
                chain = 0;
                capCounted = false;
            }
            shortPageAt = clock.getAsLong();
            shortPage = number;
            Logger.printDebug(() -> "Home next page: a page kept " + (read - lost) + " of its " + read + " items");
            later.accept(() -> settle(number));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home short page", failure);
        }
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for a page of Home's response with no items.
     * It still answers a request made here, so the next short page starts a chain of its own instead
     * of continuing this one. Never throws.
     */
    static void emptyPageParsed() {
        waitingForOurs = false;
    }

    /**
     * Injected first thing in Home's list builder (MainfeedAdapter.buildModels), with the adapter.
     * Notes this build on its thread for {@link #feedRead}. While a short page waits for a decision,
     * it also posts {@link #run} to the main thread, once, so the request is made after the build.
     * Never throws.
     */
    public static void buildStarts(Object adapter) {
        if (adapter == null) return;
        try {
            Build build = new Build(adapter);
            BUILDING.set(build);
            long page = shortPage;
            if (page == 0 || page == decided) return;
            latest = build;
            if (posted) return;
            posted = true;
            poster.accept(HomeNextPage::run);
        } catch (Throwable failure) {
            posted = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page", failure);
        }
    }

    /**
     * Called by {@link FeedSuggestions#homeFeedRead} with the feed the adapter reads its "no next
     * page" flag from, so the build on this thread knows its feed and, when that's For you's,
     * becomes {@link #home}. Following's and Favorites' lists keep adapters of the same class, so a
     * build of theirs never does. Never throws.
     */
    static void feedRead(@Nullable Object feed) {
        try {
            Build build = BUILDING.get();
            if (build == null) return;
            BUILDING.remove();
            build.read(feed);
            if (feed != null && homeFeed(sources.apply(feed))) home = build;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page feed", failure);
        }
    }

    /** The posted request: decides about the short page for the latest build. */
    static void run() {
        posted = false;
        Build build = latest;
        latest = null;
        if (build != null) askIfShort(build);
    }

    /**
     * Posted {@link #SETTLE_MS} after short page [page] was parsed, for a page no build of Home's
     * list decided: one the switches emptied changes nothing Home draws, so Instagram may not build
     * again, and a build that ran while the page was still loading left it waiting. Asks with
     * {@link #home}, the latest build of For you's list that read its feed, and checks again while
     * the page waits, within {@link #WINDOW_NANOS}. A home whose list is gone (Home was rebuilt) is
     * dropped, and the new list's build decides. Never throws.
     */
    static void settle(long page) {
        if (shortPage != page || decided == page) return;
        try {
            Build build = home;
            if (build != null && build.adapter.get() == null) {
                if (home == build) home = null;
                build = null;
                Logger.printDebug(() -> "Home next page: the last build's list is gone, so the short page waits");
            }
            if (build != null && !posted) askIfShort(build);
            if (shortPage != page || decided == page) return;
            long age = clock.getAsLong() - shortPageAt;
            if (age >= 0 && age <= WINDOW_NANOS) later.accept(() -> settle(page));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page settle", failure);
        }
    }

    /** Posts [check] to the main thread {@link #SETTLE_MS} from now. */
    static void settleLater(Runnable check) {
        Utils.runOnMainThreadDelayed(check, SETTLE_MS);
    }

    /**
     * Asks [build]'s load more policy for the next page when the latest short page is still waiting
     * and every check passes. A page loading now, a build that never read its feed, or a build of
     * another feed's list (Following's, say, which keeps its own adapter) leaves the short page for a
     * later build within {@link #WINDOW_NANOS}. Every other way out decides it, so it's asked about
     * once.
     */
    static void askIfShort(Build build) {
        long page = shortPage;
        if (page == 0 || page == decided) return;
        try {
            if (!Utils.settingsReady() || !FeedSuggestions.switchesTookOut()) {
                decided = page;
                return;
            }
            long age = clock.getAsLong() - shortPageAt;
            if (age < 0 || age > WINDOW_NANOS) {
                decided = page;
                return;
            }
            if (ForYouFallback.holdsNextPage()) {
                decided = page;
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, ForYouFallback.NOT_ASKED);
                Logger.printDebug(() -> "Home next page: this account's For you holds only suggestions, so it doesn't ask");
                return;
            }
            Object feed = build.feed();
            if (feed == null) {
                Logger.printDebug(() -> "Home next page: the build never read its feed, so the short page waits");
                return;
            }
            String source = sources.apply(feed);
            if (!asksFor(source)) {
                Logger.printDebug(() -> "Home next page: a list paging " + source + ", which isn't For you, doesn't ask");
                return;
            }
            Object adapter = build.adapter.get();
            Object policy = adapter == null ? null : policies.apply(adapter);
            if (policy == null) {
                decided = page;
                Logger.printDebug(() -> "Home next page: no load more policy to ask");
                return;
            }
            int busy = loading.applyAsInt(policy);
            if (busy == 1) {
                Logger.printDebug(() -> "Home next page: a page is loading, so the short page waits");
                return;
            }
            int left = busy == 0 ? more.applyAsInt(policy) : -1;
            if (left != 1) {
                decided = page;
                Logger.printDebug(() -> "Home next page: " + (left == 0 ? "Home has no next page" : "can't tell whether Home can load a page"));
                return;
            }
            decided = page;
            boolean atEnd = chain >= CHAIN_CAP && chain < END_CAP && ends.applyAsInt(adapter) == 1;
            if (chain >= CHAIN_CAP && !atEnd) {
                if (!capCounted) {
                    capCounted = true;
                    HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAPPED);
                    int asked = chain;
                    Logger.printDebug(() -> "Home next page: " + asked + " short pages in a row, so Home waits for the scroll");
                }
                return;
            }
            chain++;
            waitingForOurs = true;
            asker.accept(policy, new HashMap<String, String>());
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, ASKED);
            Logger.printDebug(() -> "Home next page: asked for the next page after a short one (" + chain + " in a row"
                    + (atEnd ? ", Home's list at its end)" : ")"));
            requested();
        } catch (Throwable failure) {
            decided = page;
            waitingForOurs = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page", failure);
        }
    }

    /** Numbers the request just made and has {@link #watch} check on it. */
    private static void requested() {
        long request = ++requests;
        requestAt = clock.getAsLong();
        watcher.accept(() -> watch(request));
    }

    /**
     * Posted {@link #ANSWER_MS} after request [request], while it's the latest. Until a page with
     * items answers it, within {@link #WATCH_NANOS}: once {@link #home}'s list sits at its end with
     * no page loading and a next page left, it asks again, within {@link #END_CAP}, where no scroll
     * would (#28). While a page loads or the list still scrolls it checks again later. A next page
     * Home doesn't have, a list or policy it can't read, a switch turned off or the cap stops it.
     * Never throws.
     */
    static void watch(long request) {
        if (requests != request || answered >= request) return;
        try {
            if (!Utils.settingsReady() || !FeedSuggestions.switchesTookOut()) return;
            long age = clock.getAsLong() - requestAt;
            if (age < 0 || age > WATCH_NANOS) return;
            if (ForYouFallback.holdsNextPage()) return;
            Build build = home;
            Object adapter = build == null ? null : build.adapter.get();
            Object feed = build == null ? null : build.feed();
            if (adapter == null || feed == null || !asksFor(sources.apply(feed))) return;
            Object policy = policies.apply(adapter);
            if (policy == null) return;
            int busy = loading.applyAsInt(policy);
            int left = busy == 0 ? more.applyAsInt(policy) : -1;
            int end = left == 1 ? ends.applyAsInt(adapter) : -1;
            Logger.printDebug(() -> "Home next page: no page answered request " + request + " yet (loading " + busy
                    + ", next page " + left + ", at its end " + end + ")");
            if (busy == 1 || end == 0) {
                watcher.accept(() -> watch(request));
                return;
            }
            if (end != 1 || chain >= END_CAP) return;
            chain++;
            waitingForOurs = true;
            asker.accept(policy, new HashMap<String, String>());
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, ASKED);
            int asked = chain;
            Logger.printDebug(() -> "Home next page: asked again, no page answered the last request (" + asked
                    + " in a row, Home's list at its end)");
            requested();
        } catch (Throwable failure) {
            waitingForOurs = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page watch", failure);
        }
    }

    /** Posts [check] to the main thread {@link #ANSWER_MS} from now. */
    static void watchLater(Runnable check) {
        Utils.runOnMainThreadDelayed(check, ANSWER_MS);
    }

    /**
     * Asked by {@link FeedSuggestions#feedEnded} once the switches have left Home empty, with the feed
     * the flag is read from: whether Home keeps Instagram's loading row a little longer rather than
     * end on the empty feed card (#126). That's while For you's next page is on its way: a page loads
     * now, a request made here waits for its answer, or a short page still waits for the request
     * {@link #askIfShort} makes for it, within the same caps. Each brings a page and a new build of
     * Home that asks again.
     *
     * <p>A hold lasts {@link #HOLD_NANOS} from its first yes. Past that, or once no page is coming,
     * Home ends, and the hold stays spent until a page keeps something or another feed is read, so a
     * Home whose pages bring nothing shows the card after one wait. A list that isn't For you's (or
     * its suggested posts while they show), a feed other than the latest build's, one with no next
     * page, or anything it can't read ends Home as before, and the debug log names which (#28).
     * Never throws.
     */
    static boolean holdsEnd(@Nullable Object feed) {
        if (feed == null) return false;
        try {
            WeakReference<Object> heldFor = holdFeed;
            if (heldFor == null || heldFor.get() != feed) {
                holdFeed = new WeakReference<>(feed);
                holdSince = 0;
            }
            long since = holdSince;
            if (since < 0) return endsBecause("its wait already ran out");
            Build build = home;
            if (build == null) return endsBecause("no build of For you's list read its feed");
            if (build.feed() != feed) return endsBecause("the latest build of For you's list read another feed");
            Object adapter = build.adapter.get();
            if (adapter == null) return endsBecause("the latest build's list is gone");
            String source = sources.apply(feed);
            if (!asksFor(source)) return endsBecause("its feed pages " + source + ", which isn't asked for");
            Object policy = policies.apply(adapter);
            if (policy == null) return endsBecause("no load more policy to ask");
            int busy = loading.applyAsInt(policy);
            long page = shortPage;
            boolean asking = page != 0 && page != decided
                    && (chain < CHAIN_CAP || (chain < END_CAP && ends.applyAsInt(adapter) == 1));
            int left = busy == 0 ? more.applyAsInt(policy) : -1;
            boolean waiting = waitingForOurs;
            boolean coming = busy == 1 || (left == 1 && (waiting || asking));
            long now = clock.getAsLong();
            long age = now - since;
            if (!coming || (since > 0 && (age < 0 || age > HOLD_NANOS))) {
                if (since > 0) {
                    holdSince = -1;
                    Logger.printDebug(() -> "Home next page: no page filled the empty Home in time, so it ends");
                } else {
                    endsBecause("no page is on its way (loading " + busy + ", next page " + left + ", request waiting "
                            + waiting + ", short page " + page + " waiting " + asking + ", " + chain + " asked in a row)");
                }
                return false;
            }
            if (since == 0) {
                holdSince = now;
                endedBecause = null;
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, HELD);
                Logger.printDebug(() -> "Home next page: Home is empty with its next page on the way, so the loading row stays");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "empty Home hold", failure);
            return false;
        }
    }

    /** Logs [why] an empty Home ends rather than holds, unless it's the reason given last. Returns false. */
    private static boolean endsBecause(String why) {
        if (!why.equals(endedBecause)) {
            endedBecause = why;
            Logger.printDebug(() -> "Home next page: the empty Home ends, " + why);
        }
        return false;
    }

    /** Whether [source], a feed's paging source, is For you's. For you's first pages may name none. */
    static boolean forYou(@Nullable String source) {
        return source == null || source.isEmpty() || FOR_YOU.equals(source);
    }

    /** Whether a list paging [source] is For you's: its own source, or the suggested posts it pages in. */
    static boolean homeFeed(@Nullable String source) {
        return forYou(source) || SUGGESTED.equals(source);
    }

    /**
     * Whether a short page of a list paging [source] gets its next page asked for: For you's always,
     * and the suggested posts For you pages in past the posts from accounts you follow
     * ({@link #SUGGESTED}) while Hide suggested posts is off, so they show and a post switch left
     * the page short (#28). With it on, every post on those pages goes, so asking brings nothing.
     * Callers check {@link Utils#settingsReady} first.
     */
    static boolean asksFor(@Nullable String source) {
        if (forYou(source)) return true;
        return SUGGESTED.equals(source) && !Settings.HIDE_SUGGESTED_POSTS.get();
    }

    /**
     * Whether [adapter]'s list sits at its end: 1 when it's on screen and can't scroll further down,
     * 0 when it can or isn't on screen, and -1 when it can't be told. Runs on the main thread, from
     * {@link #run} and {@link #settle}.
     */
    static int atEnd(Object adapter) {
        try {
            Object list = listOf(adapter);
            int answer = endOf(list);
            if (!listNamed && list != null) {
                listNamed = true;
                String kind = list.getClass().getName();
                Logger.printDebug(() -> "Home next page: Home's list is a " + kind + ", at its end: " + answer);
            }
            return answer;
        } catch (Throwable failure) {
            // Instagram's own read of the list throws once Home's fragment is gone.
            Logger.printDebug(() -> "Home next page: Home's list can't be read: " + failure);
            return -1;
        }
    }

    /** {@link #atEnd}'s answer for [list], the view {@link #listOf} read. */
    static int endOf(@Nullable Object list) {
        if (!(list instanceof View)) return -1;
        View view = (View) list;
        if (!view.isShown()) return 0;
        return view.canScrollVertically(1) ? 0 : 1;
    }

    /**
     * Home's list, the view the adapter draws into, read through the adapter's host the way
     * Instagram reads it for its most visible post. The patch writes the body. Null as built.
     */
    public static Object listOf(Object adapter) {
        return null;
    }

    /**
     * Home's load more policy, from the adapter's field holding it. The patch writes the body. Null
     * as built.
     */
    public static Object policyOf(Object adapter) {
        return null;
    }

    /**
     * The paging source of the feed the adapter reads its flag from, the one Instagram compares
     * with Following's to apply Following's end card rule. The patch writes the body. Null as
     * built, which a build without the body never gets this far to read.
     */
    public static String sourceOf(Object feed) {
        return null;
    }

    /**
     * Whether the policy has a page loading: 1 when it does, 0 when it doesn't and -1 when it can't
     * be told. The patch writes the body, which asks the question both isLoading() and the call for
     * the next page ask.
     */
    public static int loadingNow(Object policy) {
        return -1;
    }

    /**
     * Whether the policy's feed has a next page: 1 when it does, 0 when it doesn't and -1 when it
     * can't be told. The patch writes the body, which asks the question isLoading() and the load
     * more row's show check share.
     */
    public static int moreLeft(Object policy) {
        return -1;
    }

    /**
     * Asks the policy for the next page with [params], the request's extra parameters, as Instagram
     * does when the loading row comes into view. The patch writes the body. Does nothing as built.
     */
    public static void askNextPage(Object policy, Object params) {
    }
}
