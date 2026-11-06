/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop Reels scrolling" patch.
 *
 * <p>The Reels viewer shows its reels in AndroidX's pager, which a finger moves from one reel to the
 * next. Instagram turns the pager's input off and back on itself at times, from several places,
 * always through the pager's one setter. The patch hands each Reels pager to {@link #pager} as the
 * viewer sets it up, and every value any pager is given to {@link #userInput}. Around the viewer
 * sits a layout that loads fresh reels when you pull down; its two touch handlers ask
 * {@link #pull} first.
 *
 * <p>While the switch is on, a Reels pager's input is off from the start and stays off whatever
 * Instagram asks, and the pull-down layout lets every touch go by, so neither a swipe nor a pull
 * gets you past the reel you opened. Taps on the reel and its buttons go where they did. Instagram's own
 * auto scroll is the app moving the pager, not a finger, so it still moves on when it's turned on.
 *
 * <p>Three switches under it leave viewers opened from a profile, from Search and Explore or from a
 * song page out of that (#508). The hook gets each pager with the viewer's source, whose constants
 * keep their names through the build, and a pager from a source you picked scrolls and pulls as
 * Instagram has it. The reel count below still covers them, since that stop is its own.
 *
 * <p>Stop after a set number of reels: each store of a Reels pager's current page reaches
 * {@link #page}. The first page a pager stores this session is where it starts, and each page past
 * the furthest one it has reached since is one more reel, so going back to a reel and forward again,
 * or a refresh that puts the pager back at the top, counts nothing new. At the count picked
 * ({@link #cap}, {@link #CAP} to start) the pager's input goes off and stays off, in this viewer and
 * any that opens, the same as with the first switch on, until Instagram has been in the background
 * for the minutes picked ({@link #breakMillis}, {@link #BREAK_MINUTES} to start, #405). A reel you
 * open still plays.
 * Turning the switch off, or pausing HushGram, gives the swipes back at the next touch in Reels
 * ({@link #settle}).
 *
 * <p>Every other pager in Instagram is left alone. The hooks fail open: with the switch off,
 * HushGram paused, the settings not read yet or anything thrown, the pager takes what Instagram
 * gives it and the layout handles touches as before.
 */
public final class ReelScrolling {
    /** What {@link HookStatus} names a failure of each hook. */
    static final String PAGER = "Reels pager";
    static final String INPUT = "pager input";
    static final String PULL = "pull to refresh";
    static final String PAGE = "page change";

    /** What's counted each time a session reaches the cap. */
    static final String CAPPED = "stopped after the cap";

    /** The reels a session plays before swiping stops, until another count is picked. */
    public static final int CAP = 20;

    /** The minutes Instagram stays in the background before the next session starts, until others are picked. */
    public static final int BREAK_MINUTES = 15;

    /** {@link #BREAK_MINUTES} in milliseconds. */
    static final long BREAK_MILLIS = BREAK_MINUTES * 60_000L;

    /** The furthest page each Reels pager has reached this session. Only a page past it is a new reel. */
    private static final Map<Object, Integer> FURTHEST = Collections.synchronizedMap(new WeakHashMap<>());

    private static int played;
    private static volatile boolean capped;
    private static volatile long hiddenAt;
    private static volatile boolean watching;

    /**
     * The Reels pagers handed over so far, each with its viewer's source by name (null when it couldn't
     * be read), held weakly so a closed viewer's pager can go.
     */
    private static final Map<Object, String> PAGERS = Collections.synchronizedMap(new WeakHashMap<>());

    /** A viewer's source when it was opened from a profile's grids and tabs, your own included. */
    private static final Set<String> FROM_PROFILE = new HashSet<>(Arrays.asList(
            "PROFILE", "SELF_PROFILE", "FEED_CONTEXTUAL_PROFILE", "FEED_CONTEXTUAL_SELF_PROFILE",
            "GROUP_PROFILE", "GROUP_PROFILE_PENDING_POSTS", "FEED_CONTEXTUAL_GROUP_PROFILE",
            "FEED_CONTEXTUAL_FAN_CLUB", "FEED_CONTEXTUAL_SELF_FAN_CLUB", "OPAL_USER_PROFILE",
            "OPAL_USER_SELF_PROFILE", "PRIME_PROFILE", "PRIME_SELF_PROFILE", "REPOSTS_GRID",
            "SELF_REPOSTS_GRID", "TAGGED_MEDIA_VIEWER", "POCKET_GRID", "SELF_POCKET_GRID"));

    /** A viewer's source when it was opened from Search, Explore or a page they lead to. */
    private static final Set<String> FROM_SEARCH = new HashSet<>(Arrays.asList(
            "EXPLORE_CARRERA", "EXPLORE_GRID_AD_FEED_OF_ADS", "EXPLORE_GRID_TALL_AD_FEED_OF_ADS",
            "EXPLORE_NEARBY_MINOR_UNIT", "EXPLORE_POPULAR_AGGREGATE_TREND_MINOR_UNIT",
            "EXPLORE_POPULAR_DEFAULT_UNIT", "EXPLORE_POPULAR_MAJOR_UNIT", "EXPLORE_POPULAR_MINOR_UNIT",
            "EXPLORE_TILE_TWO_BY_TWO", "EXPLORE_TRENDING_MINOR_UNIT", "EXPLORE_VIDEO_CHAINING",
            "EXPLORE_VYML", "SERP_CONTEXTUAL_MINOR_UNIT", "SERP_REELS_TRENDS_HCM", "SERP_TOP_CLIPS_UNIT",
            "SERP_TOP_MINOR_UNIT", "SERP_TOP_MIXED_CONTENT_MINOR_UNIT", "KEYWORD", "FEED_CONTEXTUAL_KEYWORD",
            "HASHTAG", "TRENDS_PAGE", "TRENDING_ONLY", "TEXT_PAGE", "LOCATION"));

    /** A viewer's source when it was opened from a song or audio page. */
    private static final Set<String> FROM_AUDIO = new HashSet<>(Arrays.asList(
            "SONG", "AUDIO_PAGE_CHAIN", "AUDIO_NETEGO_PAGE", "AUDIO_PAGE_IN_CREATION", "MIFU_USE_AUDIO",
            "PRIME_AUDIO_PIVOT", "SAVED_AUDIO_NOTIFICATION"));

    private ReelScrolling() {
    }

    /**
     * Injected right after the Reels viewer keeps its pager, with the viewer's source. Remembers the
     * pager and answers 0, turn its input off now, while the switch is on and that source isn't let
     * through, and 1, leave it, otherwise or when anything goes wrong. Never throws.
     */
    public static int pager(Object pager, Object source) {
        return pager(pager, source, () -> switchedOn(pager));
    }

    static int pager(Object pager, Object source, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SCROLLING);
            if (pager == null) return 1;
            PAGERS.put(pager, source instanceof Enum ? ((Enum<?>) source).name() : null);
            settle(ReelScrolling::capOn);
            if (!on.getAsBoolean()) return 1;
            Logger.printDebug(() -> "Reels scrolling: a Reels pager's swipes are off");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, PAGER, failure);
            return 1;
        }
    }

    /**
     * Injected first thing in the pager's setter for whether a finger can move it, with the value as
     * an int (non-zero is yes). Answers 0 for a Reels pager while the switch is on, and the value
     * otherwise, or when anything goes wrong. Never throws.
     */
    public static int userInput(Object pager, int enabled) {
        return userInput(pager, enabled, () -> switchedOn(pager));
    }

    static int userInput(Object pager, int enabled, BooleanSupplier on) {
        try {
            if (enabled == 0 || pager == null || !PAGERS.containsKey(pager)) return enabled;
            HookStatus.invoked(FamilyNames.REEL_SCROLLING);
            settle(ReelScrolling::capOn);
            return on.getAsBoolean() ? 0 : enabled;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, INPUT, failure);
            return enabled;
        }
    }

    /**
     * Injected first thing in both touch handlers of the pull-down layout around the Reels viewer,
     * with the layout. Answers 0, let the touch go by, while the switch is on and the layout doesn't
     * hold a pager from a source that's let through, and 1, handle it as before, otherwise or when
     * anything goes wrong. Never throws.
     */
    public static int pull(Object layout) {
        return pull(layout, () -> pullStopped(layout));
    }

    static int pull(Object layout, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SCROLLING);
            settle(ReelScrolling::capOn);
            return on.getAsBoolean() ? 0 : 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, PULL, failure);
            return 1;
        }
    }

    /**
     * Injected after each store of a pager's current page, with the pager and the page. For a Reels
     * pager past the furthest page it has reached this session, counts a reel while Stop after a set
     * number of reels is on, and at the cap turns the pager's input off and says why. The first page a
     * pager stores in a session is where it starts, not a reel counted. Never throws.
     */
    public static void page(Object pager, int position) {
        page(pager, position, ReelScrolling::capOn);
    }

    static void page(Object pager, int position, BooleanSupplier capOn) {
        try {
            if (pager == null || !PAGERS.containsKey(pager)) return;
            HookStatus.invoked(FamilyNames.REEL_SCROLLING);
            settle(capOn);
            if (!capOn.getAsBoolean()) return;
            Integer furthest;
            synchronized (FURTHEST) {
                furthest = FURTHEST.get(pager);
                if (furthest != null && position <= furthest) return;
                FURTHEST.put(pager, position);
            }
            watch();
            if (furthest == null) return;
            int cap = cap();
            synchronized (ReelScrolling.class) {
                if (capped || ++played < cap) return;
                capped = true;
            }
            HookStatus.counted(FamilyNames.REEL_SCROLLING, CAPPED);
            Logger.printDebug(() -> "Reels scrolling: " + cap + " reels played, swipes are off");
            setInput(pager, false);
            Utils.showToastLong(L10n.f("%1$d reels. Swipe again after %2$d minutes away.", cap, breakMillis() / 60_000));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, PAGE, failure);
        }
    }

    /** Instagram's screens went to the background at [now]. */
    static void hidden(long now) {
        hiddenAt = now;
    }

    /**
     * An Instagram screen started at [now]. After a break of {@link #breakMillis} or more a new
     * session starts ({@link #release}).
     */
    static void shown(long now) {
        long since = hiddenAt;
        if (since == 0) return;
        hiddenAt = 0;
        if (now - since < breakMillis()) return;
        release();
    }

    /**
     * Asked first by every hook. While a session is capped and the reel stop is no longer on,
     * switched off or with HushGram paused, Instagram's own behavior comes back right away rather than
     * after the break: the session ends ({@link #release}). No hook runs while a capped pager sits
     * still, but the pull-down layout around the viewer sees every touch in Reels, so the first one
     * after the switch goes off gives the swipes back.
     */
    private static void settle(BooleanSupplier capOn) {
        if (capped && !capOn.getAsBoolean()) release();
    }

    /**
     * Ends the session: the count and each pager's furthest page start over, and a pager the cap
     * turned off takes a finger again, unless Stop Reels scrolling keeps it off.
     */
    private static void release() {
        boolean wasCapped;
        synchronized (ReelScrolling.class) {
            played = 0;
            wasCapped = capped;
            capped = false;
        }
        FURTHEST.clear();
        if (!wasCapped) return;
        List<Object> pagers;
        synchronized (PAGERS) {
            pagers = new ArrayList<>(PAGERS.keySet());
        }
        for (Object pager : pagers) {
            if (!stopped(pager)) setInput(pager, true);
        }
    }

    /** The pager's own setter, which AndroidX keeps by name, so it goes through the input hook too. */
    private static void setInput(Object pager, boolean enabled) {
        try {
            pager.getClass().getMethod("setUserInputEnabled", boolean.class).invoke(pager, enabled);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, PAGE, failure);
        }
    }

    /** Hears when Instagram goes to the background and comes back, from the first reel counted on. */
    private static void watch() {
        if (watching) return;
        Context context = Utils.getContext();
        Context app = context == null ? null : context.getApplicationContext();
        if (!(app instanceof Application)) return;
        Application application = (Application) app;
        application.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                if (level == TRIM_MEMORY_UI_HIDDEN) hidden(System.currentTimeMillis());
            }

            @Override
            public void onConfigurationChanged(Configuration configuration) {
            }

            @Override
            public void onLowMemory() {
            }
        });
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) {
                shown(System.currentTimeMillis());
            }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
        watching = true;
    }

    /** Forgets every pager handed over and the session's count, for tests. */
    static void forget() {
        PAGERS.clear();
        FURTHEST.clear();
        synchronized (ReelScrolling.class) {
            played = 0;
            capped = false;
        }
        hiddenAt = 0;
    }

    private static boolean switchedOn(Object pager) {
        return stopped(pager) || (Utils.settingsReady() && capped && Settings.REEL_CAP.get());
    }

    /** Whether Stop Reels scrolling is holding this pager: it's on, and no switch lets its viewer's source through. */
    private static boolean stopped(Object pager) {
        return Utils.settingsReady() && Settings.STOP_REELS_SCROLLING.get() && !letThrough(PAGERS.get(pager));
    }

    private static boolean letThrough(String source) {
        if (source == null) return false;
        return (Settings.REEL_SCROLL_FROM_PROFILE.get() && FROM_PROFILE.contains(source))
                || (Settings.REEL_SCROLL_FROM_SEARCH.get() && FROM_SEARCH.contains(source))
                || (Settings.REEL_SCROLL_FROM_AUDIO.get() && FROM_AUDIO.contains(source));
    }

    private static boolean pullStopped(Object layout) {
        if (!Utils.settingsReady()) return false;
        if (capped && Settings.REEL_CAP.get()) return true;
        return Settings.STOP_REELS_SCROLLING.get() && !holdsPagerLetThrough(layout);
    }

    /** Whether a pager whose source is let through sits somewhere inside this pull-down layout. */
    private static boolean holdsPagerLetThrough(Object layout) {
        synchronized (PAGERS) {
            for (Map.Entry<Object, String> known : PAGERS.entrySet()) {
                if (!letThrough(known.getValue()) || !(known.getKey() instanceof View)) continue;
                for (ViewParent up = ((View) known.getKey()).getParent(); up != null; up = up.getParent()) {
                    if (up == layout) return true;
                }
            }
        }
        return false;
    }

    private static boolean capOn() {
        return Utils.settingsReady() && Settings.REEL_CAP.get();
    }

    /** The reels picked for a session, or {@link #CAP} before the settings are read. */
    static int cap() {
        return Utils.settingsReady() ? Settings.REEL_CAP_COUNT.get() : CAP;
    }

    /** The break picked, in milliseconds, or {@link #BREAK_MILLIS} before the settings are read. */
    static long breakMillis() {
        return Utils.settingsReady() ? Settings.REEL_CAP_BREAK.get() * 60_000L : BREAK_MILLIS;
    }
}
