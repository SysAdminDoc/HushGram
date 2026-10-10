/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

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
 * Asks Home for its next page right away when Hide suggested posts leaves a page of For you short (#52).
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
 * <p>It never asks while a page is loading, once the feed has no next page, for a Home that's empty
 * (the end card logic in {@link FeedSuggestions#feedEnded} has that one), or for a feed that isn't
 * For you, so Following and Favorites page as they did. The Older Posts page has builders of its own
 * and is left alone. It fails open: with Hide suggested posts off, HushGram paused, the settings not
 * read yet, a stub the patch couldn't fill or anything thrown, Home waits for the scroll as Instagram
 * has it.
 */
public final class HomeNextPage {
    /** A page of Home's response is short when it keeps fewer items than this. */
    static final int SHORT_PAGE = 5;

    /** The most next pages asked for in a row while pages keep coming back short. */
    static final int CHAIN_CAP = 3;

    /** How long after a short page was parsed a build of Home may still ask for the next one, in nanoseconds. */
    static final long WINDOW_NANOS = 10_000_000_000L;

    /** For you's paging source, as Instagram names the feed Home's switcher calls ALL. */
    static final String FOR_YOU = "homecoming_all";

    /** The report's count of next pages asked for. */
    static final String ASKED = "asked for the next page after a short page";

    /** The report's count of chains that reached {@link #CHAIN_CAP} with pages still short. */
    static final String CAPPED = "chain stopped at its cap";

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

    /** Set once the report has counted {@link #CAPPED} for the current chain. Tests clear it. */
    static volatile boolean capCounted;

    /** Set while a request is posted to the main thread and hasn't run yet. Tests clear it. */
    static volatile boolean posted;

    /** The latest build of Home's list since a request was posted, which the request reads. Tests clear it. */
    @Nullable
    static volatile Build latest;

    /** The build of Home's list running on this thread, until it hands over its feed in {@link #feedRead}. */
    private static final ThreadLocal<Build> BUILDING = new ThreadLocal<>();

    /** One build of Home's list: its adapter, and the feed it reads its "no next page" flag from. */
    static final class Build {
        final Object adapter;
        @Nullable volatile Object feed;

        Build(Object adapter) {
            this.adapter = adapter;
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

    private HomeNextPage() {
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for each page of Home's response that held
     * items: [read] of them, [lost] to the switches. A page that keeps {@link #SHORT_PAGE} or more
     * ends the chain. A shorter one the switches took items out of becomes the page the next build
     * decides about, and starts a new chain unless it answers a request made here. A short page the
     * switches took nothing out of is Instagram's own and is left to it. Never throws.
     */
    static void pageParsed(int read, int lost) {
        try {
            boolean ours = waitingForOurs;
            waitingForOurs = false;
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
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home short page", failure);
        }
    }

    /**
     * Injected first thing in Home's list builder (MainfeedAdapter.buildModels), with the adapter.
     * While a short page waits for a decision, it notes this build and posts {@link #run} to the main
     * thread, once, so the request is made after the build. Otherwise it reads two fields and returns.
     * Never throws.
     */
    public static void buildStarts(Object adapter) {
        long page = shortPage;
        if (page == 0 || page == decided || adapter == null) return;
        try {
            Build build = new Build(adapter);
            BUILDING.set(build);
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
     * page" flag from, so the build on this thread knows its feed. Never throws.
     */
    static void feedRead(@Nullable Object feed) {
        try {
            Build build = BUILDING.get();
            if (build == null) return;
            BUILDING.remove();
            build.feed = feed;
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
            if (!Utils.settingsReady() || !Settings.HIDE_SUGGESTED_POSTS.get()) {
                decided = page;
                return;
            }
            long age = clock.getAsLong() - shortPageAt;
            if (age < 0 || age > WINDOW_NANOS) {
                decided = page;
                return;
            }
            Object feed = build.feed;
            if (feed == null) return;
            String source = sources.apply(feed);
            if (!forYou(source)) {
                Logger.printDebug(() -> "Home next page: a list paging " + source + ", which isn't For you, doesn't ask");
                return;
            }
            Object policy = policies.apply(build.adapter);
            if (policy == null) {
                decided = page;
                return;
            }
            int busy = loading.applyAsInt(policy);
            if (busy == 1) return;
            if (busy != 0 || more.applyAsInt(policy) != 1 || FeedSuggestions.feedIsEmpty(feed) != 0) {
                decided = page;
                return;
            }
            decided = page;
            if (chain >= CHAIN_CAP) {
                if (!capCounted) {
                    capCounted = true;
                    HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, CAPPED);
                    Logger.printDebug(() -> "Home next page: " + CHAIN_CAP + " short pages in a row, so Home waits for the scroll");
                }
                return;
            }
            chain++;
            waitingForOurs = true;
            asker.accept(policy, new HashMap<String, String>());
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, ASKED);
            Logger.printDebug(() -> "Home next page: asked for the next page after a short one (" + chain + " in a row)");
        } catch (Throwable failure) {
            decided = page;
            waitingForOurs = false;
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home next page", failure);
        }
    }

    /** Whether [source], a feed's paging source, is For you's. For you's first pages may name none. */
    static boolean forYou(@Nullable String source) {
        return source == null || source.isEmpty() || FOR_YOU.equals(source);
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
