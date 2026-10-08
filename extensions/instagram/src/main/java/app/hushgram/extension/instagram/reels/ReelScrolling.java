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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * <p>Stop after 20 reels: each store of a Reels pager's current page reaches {@link #page}. The first
 * page a pager stores this session is where it starts, and each page past the furthest one it has
 * reached since is one more reel, so going back to a reel and forward again, or a refresh that puts
 * the pager back at the top, counts nothing new. At {@link #CAP} the pager's input goes off and
 * stays off, in this viewer and any that opens, the same as with the first switch on, until
 * Instagram has been in the background for {@link #BREAK_MILLIS}. A reel you open still plays.
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

    /** The reels a session plays before swiping stops, with Stop after 20 reels on. */
    static final int CAP = 20;

    /** How long Instagram stays in the background before the next session starts. */
    static final long BREAK_MILLIS = 15 * 60_000L;

    /** The furthest page each Reels pager has reached this session. Only a page past it is a new reel. */
    private static final Map<Object, Integer> FURTHEST = Collections.synchronizedMap(new WeakHashMap<>());

    private static int played;
    private static volatile boolean capped;
    private static volatile long hiddenAt;
    private static volatile boolean watching;

    /** The Reels pagers handed over so far, held weakly so a closed viewer's pager can go. */
    private static final Map<Object, Boolean> PAGERS = Collections.synchronizedMap(new WeakHashMap<>());

    private ReelScrolling() {
    }

    /**
     * Injected right after the Reels viewer keeps its pager. Remembers the pager and answers 0, turn
     * its input off now, while the switch is on, and 1, leave it, otherwise or when anything goes
     * wrong. Never throws.
     */
    public static int pager(Object pager) {
        return pager(pager, ReelScrolling::switchedOn);
    }

    static int pager(Object pager, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SCROLLING);
            if (pager == null) return 1;
            PAGERS.put(pager, Boolean.TRUE);
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
        return userInput(pager, enabled, ReelScrolling::switchedOn);
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
     * Injected first thing in both touch handlers of the pull-down layout around the Reels viewer.
     * Answers 0, let the touch go by, while the switch is on, and 1, handle it as before, otherwise
     * or when anything goes wrong. Never throws.
     */
    public static int pull() {
        return pull(ReelScrolling::switchedOn);
    }

    static int pull(BooleanSupplier on) {
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
     * pager past the furthest page it has reached this session, counts a reel while Stop after 20
     * reels is on, and at the cap turns the pager's input off and says why. The first page a pager
     * stores in a session is where it starts, not a reel counted. Never throws.
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
            synchronized (ReelScrolling.class) {
                if (capped || ++played < CAP) return;
                capped = true;
            }
            HookStatus.counted(FamilyNames.REEL_SCROLLING, CAPPED);
            Logger.printDebug(() -> "Reels scrolling: " + CAP + " reels played, swipes are off");
            setInput(pager, false);
            Utils.showToastLong(L10n.f("That's %1$d reels. Swiping in Reels is off until Instagram has been in the "
                    + "background for %2$d minutes.", CAP, BREAK_MILLIS / 60_000));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, PAGE, failure);
        }
    }

    /** Instagram's screens went to the background at [now]. */
    static void hidden(long now) {
        hiddenAt = now;
    }

    /**
     * An Instagram screen started at [now]. After a break of {@link #BREAK_MILLIS} or more a new
     * session starts ({@link #release}).
     */
    static void shown(long now) {
        long since = hiddenAt;
        if (since == 0) return;
        hiddenAt = 0;
        if (now - since < BREAK_MILLIS) return;
        release();
    }

    /**
     * Asked first by every hook. While a session is capped and Stop after 20 reels is no longer on,
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
        if (!wasCapped || (Utils.settingsReady() && Settings.STOP_REELS_SCROLLING.get())) return;
        List<Object> pagers;
        synchronized (PAGERS) {
            pagers = new ArrayList<>(PAGERS.keySet());
        }
        for (Object pager : pagers) setInput(pager, true);
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

    private static boolean switchedOn() {
        return Utils.settingsReady() && (Settings.STOP_REELS_SCROLLING.get() || (capped && Settings.REEL_CAP.get()));
    }

    private static boolean capOn() {
        return Utils.settingsReady() && Settings.REEL_CAP.get();
    }
}
