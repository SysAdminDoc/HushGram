/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Find the reel you came from" patch (#30).
 *
 * <p>Tapping a reel's author opens their profile in an activity of its own. The patch calls
 * {@link #opening} first in Instagram's navigateToNormalProfile with the reel's media, and the reel's
 * id (its {@code getId()}, the same string Instagram puts on the reel's tile in a profile's grid as
 * its transition name) is held, one id at a time. The next activity to be created within a few
 * seconds is taken as that profile's, and the id is dropped when it's destroyed, so Back from the
 * profile forgets it.
 *
 * <p>With Keep the tab bar on a reel's profile on, the profile goes onto the tab the reel plays on
 * and no activity is created, so nothing was ever looked for (#693). That switch calls
 * {@link #profileOnTab} instead, and the activity the reel plays in is taken as the profile's. It
 * holds every tab, so a grid there is looked in only when it sits in the profile's screen
 * (UserDetailFragment, found up the grid's parent fragments): the first screen a grid resumes in is
 * taken as the profile's, a grid in another is left alone, and the id is dropped once that screen
 * is gone.
 *
 * <p>A reel in the feed opens its author's profile from the post's header instead, which never
 * reaches navigateToNormalProfile, so nothing was held (#795). The patch calls
 * {@link #openingFromFeed} as the header tap reads the post's media, and a video is held the same
 * way. The main feed puts that profile on its own tab, in the activity already there, so when no
 * activity comes, the first grid or Reels tab resumed in a profile's screen within the window
 * gives the profile's activity and screen, as {@link #profileOnTab} does. Other feeds open it in an
 * activity of its own, taken as above.
 *
 * <p>The patch also calls {@link #reelsTabResumed} first in the onResume of a profile's Reels tab
 * (ClipsProfileTabFragment). When that tab is in the profile's activity and shows the reel's author
 * (the id ends with the author's user id), the tab's grid is dragged up most of a screen at a time,
 * the way a finger drags it, until a tile with the reel's id is bound. It's a drag and not a scroll
 * because Instagram loads the grid's next page only for a drag: scrolled, the grid stopped at the
 * first page's end and never got more. At the loaded end it's dragged again now and then while the
 * next page comes. Found, the tile is brought fully on screen and outlined for a moment, and the id
 * is dropped. Not found after a few screens or when the grid ends, the grid goes back to where it
 * was, a toast says so, and the id is dropped. Leaving the tab first stops the search and keeps the
 * id for the next time the tab opens.
 *
 * <p>A photo reel, a photo or carousel Instagram plays in Reels with music, sits in the profile's
 * posts grid instead and never in its Reels tab (#225). So {@link #opening} also notes the reel's
 * media type, and for a photo or carousel the search runs the same way in the posts grid
 * (ProfileMediaTabFragment), from the patch's call first in its onResume, while the Reels tab leaves
 * it alone. The posts grid's arguments name which of the profile's grids it is but not whose, so the
 * first main posts grid resumed in the profile's activity is taken as the author's, and another one
 * resumed there later, a profile reached from it, is left alone. 454 names a posts tile by nothing
 * the search can match, so the patch also calls {@link #postsTileBound} first in the grid's tile
 * binder, and while a photo reel is held each tile is named by its media's id (#689). A video reel is looked for in the
 * Reels tab alone, as before, and so is any reel while the media type can't be read.
 *
 * <p>A trial reel, which Instagram shows to people who don't follow its poster, isn't on the
 * poster's profile at all, so the search could only give up (#225). When the reel's
 * {@code is_active_trial} reads true, {@link #opening} holds nothing and a toast says so. A reel
 * whose flag is false or can't be read is looked for as before. Only the poster's own Media
 * carries that flag, so for anyone else the grid tells instead: a reel's id starts with a number
 * that grows with the time it was posted, and a profile's grid runs newest first below the few
 * reels pinned to its top. Once every tile on screen is older than the reel, and more of them
 * than could be pinned, the grid has gone past where the reel would sit, so the search stops and
 * a toast says the reel isn't on the profile, whether or not Find the reel was tapped yet.
 *
 * <p>Nothing is dragged and no end is waited for until the grid holds a tile Instagram named. A
 * profile can open straight on its Reels tab (451 does for some reels), and then the tab resumes
 * before its first page: the grid shows nine grey placeholder tiles with no names, as many as fill
 * the screen, so it can't scroll. Taken for the loaded end, they made the search give up four
 * seconds in with the grid unmoved (#30). Each time more named tiles come in at the end, as when
 * Instagram fills a screen its first page left short, the wait for the end starts again.
 *
 * <p>While the grid is dragged, a Stop looking button sits near the bottom of the profile's screen,
 * and a tap on it ends the search where the grid is and drops the id. With Wait for a tap on, a
 * reel that isn't on the first screen isn't looked for until the button, reading Find the reel
 * then, is tapped; until then a reel scrolled to by hand is still outlined.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet, a member
 * missing or anything thrown, nothing is held and the grid stays as Instagram shows it.
 */
public final class ReelOrigin {
    /** The Reels tab's argument holding whose profile it is. */
    static final String USER_KEY = "ClipsProfileTabFragment.ARGS_PROFILE_USER_ID";
    /**
     * The posts grid's argument naming which of a profile's grids it is, and the name of the main
     * one, where a photo reel's tile sits; the tagged grid and the others have names of their own.
     */
    static final String GRID_KEY = "ProfileMediaTabFragment.profile_tab_identifier";
    static final String POSTS = "profile_media_grid";
    /** Instagram's media types of a photo and of a carousel, the two a photo reel comes as. */
    static final int PHOTO = 1;
    static final int CAROUSEL = 8;
    /** Instagram's media type of a video, which every reel in the feed is. */
    static final int VIDEO = 2;
    /** The type of the Reels tab's one grid field, and the posts grid's. */
    static final String GRID_TYPE = "androidx.recyclerview.widget.RecyclerView";

    /** The steps a failure is reported under. */
    static final String OPENING = "profile opened from a reel";
    static final String RESUME = "reels tab resumed";
    static final String POSTS_RESUME = "posts grid resumed";
    static final String TILE_BOUND = "posts tile bound";
    static final String LOOK = "looking for the reel";
    static final String WATCH = "profile screen watched";
    static final String ON_TAB = "profile opened on the tab";
    static final String FEED = "profile opened from a feed reel";
    /** Counted when a profile opened from a feed reel is taken as the one on the feed's tab. */
    static final String FEED_ON_TAB = "feed reel's profile on the tab";
    /** Counted when a reel's id is held, found in the grid, not found, and when the tab was left first. */
    static final String REMEMBERED = "reel remembered";
    static final String FOUND = "reel found";
    static final String NOT_FOUND = "reel not found";
    static final String LEFT = "tab left first";
    /** Counted when the Find the reel button is put up for a tap, and when Stop looking ends a search. */
    static final String OFFERED = "find button shown";
    static final String STOPPED = "search stopped";
    /** Counted once a search when it waits for the grid's first named tiles, as behind the placeholders. */
    static final String WAITED = "waited for the first page";
    /**
     * Why a search gave up, counted beside reel not found: the grid ended with nothing more coming,
     * the screens or the time ran out while it still went on, or no tile was ever named.
     */
    static final String ENDED = "grid ended";
    static final String RAN_OUT = "screens ran out";
    static final String NO_TILES = "no named tiles";
    /** Why a search gave up when the grid went past where the reel would sit, counted beside reel not found. */
    static final String PASSED = "grid past the reel's date";
    /**
     * Counted when the Reels tab in the profile's screen is another profile's than the reel's author,
     * or a posts grid there is another than the first.
     */
    static final String OTHER_PROFILE = "other profile's tab";
    /** Counted when the reel tapped is a trial reel, which isn't on the profile, so nothing is held. */
    static final String TRIAL = "trial reel, not looked for";
    /** Counted when a posts tile is named by its media's id while a photo reel is held. */
    static final String TILE_NAMED = "posts tile named";
    /** How many reels or posts Instagram pins to the top of a profile's grid at most, older ones as likely as not. */
    static final int PINNED_MAX = 3;

    /** How long after the tap the profile's activity may take to be created. */
    static final long HOST_WINDOW_MS = 5000;
    /** How many parent fragments up from a grid its profile's screen is looked for. */
    static final int SCREEN_DEPTH = 6;
    /** How often the grid moves on while the reel is looked for. */
    static final long STEP_MS = 150;
    /**
     * How long after its window's next frame a tab whose grid isn't on screen yet is looked at again,
     * or how often while the grid isn't in a window at all.
     */
    static final long WAIT_MS = 300;
    /** How often the grid is looked at while Find the reel waits for its tap, for the tab being left or the reel scrolled to by hand. */
    static final long TAP_WAIT_MS = 500;
    /**
     * How many times the grid is dragged on at most, and how long the search runs at most once the grid
     * shows. Instagram loads a dozen or so reels a page, a second or two apart, so a reel from months
     * back is dozens of pages down: 20 drags and 15 seconds gave up long before it (#225). Stop
     * looking ends a search sooner.
     */
    static final int MAX_STEPS = 150;
    static final long GIVE_UP_MS = 90000;
    /** How long the grid may show only placeholders before the search gives up. */
    static final long FIRST_PAGE_MS = 15000;
    /** How long the end of the grid may stay the end before no more is coming. */
    static final long END_WAIT_MS = 4000;
    /**
     * How long instead while Instagram's spinner shows under the last row, the next page still on its
     * way: deep in a big profile a page can take longer than END_WAIT_MS, and the search took that
     * for the end (#225, S22 2026-10-17).
     */
    static final long LOAD_WAIT_MS = 20000;
    /** How often the grid is dragged again while it sits at its loaded end. */
    static final long NUDGE_MS = 1000;
    /** How many moves a drag takes, how far apart in time, and how long it holds still before letting go. */
    static final int DRAG_MOVES = 8;
    static final long MOVE_MS = 10;
    static final long HOLD_MS = 100;
    /** How long the found tile stays marked, and how long the mark then takes to fade. */
    static final long OUTLINE_MS = 5000;
    static final long FADE_MS = 400;
    /** How far above the bottom of the profile's screen, past the navigation bar, the button sits, clear of a tab bar. */
    static final int BUTTON_ABOVE_DP = 72;

    private static final Object LOCK = new Object();
    private static final Watcher WATCHER = new Watcher();
    /** The one reel id held, and when. */
    private static String held;
    private static long heldAt;
    /** Whether the reel held is a photo or a carousel, looked for in the posts grid and not the Reels tab. */
    private static boolean heldPhoto;
    /** The activity the profile opened in, once it's created. */
    private static WeakReference<Activity> host;
    /** The first main posts grid resumed in the profile's activity, the author's, while a photo is held. */
    private static WeakReference<Object> postsGrid;
    /**
     * Whether the profile went onto the tab, in [host] with the tab's other screens, and the
     * profile's screen once a grid resumes in it.
     */
    private static boolean onTab;
    private static WeakReference<Object> tabProfile;
    /** Whether the reel held was tapped in the feed, whose profile may open on the feed's own tab. */
    private static boolean fromFeed;
    /** Where the watcher is registered while an id is held. */
    private static Application watched;
    /** The search running, at most one. */
    private static Look looking;
    /** View's computeVerticalScrollOffset, read once. */
    private static Method scrollOffset;
    /** The id of the media each posts tile was last bound to while a photo reel was held, by tile. */
    private static final Map<View, String> TILE_IDS = new WeakHashMap<>();
    /** Media's getId, read once. */
    private static Method getId;

    private ReelOrigin() {
    }

    /** Called first in navigateToNormalProfile with the reel's media. Never throws. */
    public static void opening(Object media) {
        opening(media, ReelOrigin::switchedOn);
    }

    static void opening(Object media, BooleanSupplier on) {
        opening(media, on, InstagramMedia::mediaType);
    }

    static void opening(Object media, BooleanSupplier on, Function<Object, Integer> type) {
        opening(media, on, type, InstagramMedia::activeTrial);
    }

    /** [type] reads the media's type and [trial] whether it's a trial reel, each null when it can't be told. */
    static void opening(Object media, BooleanSupplier on, Function<Object, Integer> type, Function<Object, Boolean> trial) {
        hold(media, on, type, trial, false);
    }

    /**
     * Called in a feed post's header tap as it reads the post's media, before the author's profile
     * opens (#795). Only a video, which is what a reel in the feed is, is held: a photo post was never
     * a reel to go back to. The main feed puts the profile on its own tab, in the activity already
     * there, so that profile is taken at its first grid's resume instead. Never throws.
     */
    public static void openingFromFeed(Object media) {
        openingFromFeed(media, ReelOrigin::switchedOn, InstagramMedia::mediaType, InstagramMedia::activeTrial);
    }

    static void openingFromFeed(Object media, BooleanSupplier on, Function<Object, Integer> type, Function<Object, Boolean> trial) {
        hold(media, on, type, trial, true);
    }

    private static void hold(Object media, BooleanSupplier on, Function<Object, Integer> type, Function<Object, Boolean> trial, boolean feed) {
        try {
            HookStatus.invoked(FamilyNames.REEL_ORIGIN);
            if (media == null || !on.getAsBoolean()) return;
            Integer kind = type.apply(media);
            if (feed && (kind == null || kind != VIDEO)) return;
            String id = mediaId(media);
            if (id == null || id.isEmpty()) return;
            if (isTrial(media, trial)) {
                forget();
                HookStatus.counted(FamilyNames.REEL_ORIGIN, TRIAL);
                Utils.showToastShort(L10n.t("That's a trial reel, so it isn't on their profile"));
                return;
            }
            boolean photo = kind != null && (kind == PHOTO || kind == CAROUSEL);
            Application application = application();
            if (application == null) return;
            synchronized (LOCK) {
                stopLooking();
                held = id;
                heldPhoto = photo;
                heldAt = SystemClock.uptimeMillis();
                host = null;
                postsGrid = null;
                onTab = false;
                tabProfile = null;
                fromFeed = feed;
                if (watched == null) {
                    application.registerActivityLifecycleCallbacks(WATCHER);
                    watched = application;
                }
            }
            HookStatus.counted(FamilyNames.REEL_ORIGIN, REMEMBERED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, feed ? FEED : OPENING, failure);
            forget();
        }
    }

    /**
     * A profile opened from a feed reel that no new activity came for went onto the feed's tab, so
     * the activity of [fragment], its first grid or Reels tab resumed within the window, is taken as
     * the profile's, with the profile's screen [fragment] sits in (#795). A fragment in no profile's
     * screen takes nothing.
     */
    private static void adoptFeedProfile(Object fragment) throws ReflectiveOperationException {
        synchronized (LOCK) {
            if (held == null || host != null || !fromFeed || SystemClock.uptimeMillis() - heldAt > HOST_WINDOW_MS) return;
        }
        Object screen = profileScreen(fragment);
        if (screen == null) return;
        Method activity = method(fragment, "getActivity");
        Object found = activity == null ? null : activity.invoke(fragment);
        if (!(found instanceof Activity)) return;
        synchronized (LOCK) {
            if (held == null || host != null || !fromFeed) return;
            host = new WeakReference<>((Activity) found);
            onTab = true;
            tabProfile = new WeakReference<>(screen);
        }
        HookStatus.counted(FamilyNames.REEL_ORIGIN, FEED_ON_TAB);
    }

    /**
     * Called when Keep the tab bar on a reel's profile put the profile onto [activity]'s tab instead
     * of an activity of its own (#693), so that activity is taken as the profile's. Never throws.
     */
    public static void profileOnTab(Object activity) {
        try {
            if (!(activity instanceof Activity)) return;
            synchronized (LOCK) {
                if (held == null || host != null) return;
                if (SystemClock.uptimeMillis() - heldAt > HOST_WINDOW_MS) {
                    forget();
                    return;
                }
                host = new WeakReference<>((Activity) activity);
                onTab = true;
                tabProfile = null;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, ON_TAB, failure);
        }
    }

    /**
     * Whether [fragment] may be the profile's when the profile went onto the tab: true when it didn't,
     * or when no profile screen is found up the fragment's parents. Otherwise the first screen asked
     * about is taken as the profile's when [take], a fragment in another is refused, and once the
     * profile's screen is gone the id is dropped.
     */
    private static boolean inTabProfile(Object fragment, boolean take) {
        synchronized (LOCK) {
            if (!onTab) return true;
        }
        Object screen = profileScreen(fragment);
        if (screen == null) return true;
        synchronized (LOCK) {
            if (!onTab) return true;
            if (tabProfile == null) {
                if (take) tabProfile = new WeakReference<>(screen);
                return true;
            }
            Object first = tabProfile.get();
            if (first == null) {
                forget();
                return false;
            }
            return first == screen;
        }
    }

    /** The profile's screen [fragment] sits in, up its parent fragments, or null. */
    private static Object profileScreen(Object fragment) {
        Object at = fragment;
        for (int up = 0; at != null && up <= SCREEN_DEPTH; up++) {
            if (ReelProfileTabBar.PROFILE_SCREEN.equals(at.getClass().getName())) return at;
            try {
                at = at.getClass().getMethod("getParentFragment").invoke(at);
            } catch (ReflectiveOperationException missing) {
                return null;
            }
        }
        return null;
    }

    /** True only when [trial] reads true. A flag that can't be read, even by throwing, is no trial reel. */
    private static boolean isTrial(Object media, Function<Object, Boolean> trial) {
        try {
            return Boolean.TRUE.equals(trial.apply(media));
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Called first in the Reels tab's onResume with the tab. Never throws. */
    public static void reelsTabResumed(Object tab) {
        reelsTabResumed(tab, ReelOrigin::switchedOn);
    }

    static void reelsTabResumed(Object tab, BooleanSupplier on) {
        reelsTabResumed(tab, on, ReelOrigin::waitsForTap);
    }

    static void reelsTabResumed(Object tab, BooleanSupplier on, BooleanSupplier waits) {
        try {
            HookStatus.invoked(FamilyNames.REEL_ORIGIN);
            if (tab == null || !on.getAsBoolean()) return;
            adoptFeedProfile(tab);
            String id;
            Activity profile;
            boolean photo;
            synchronized (LOCK) {
                id = held;
                photo = heldPhoto;
                profile = host == null ? null : host.get();
                if (id != null && profile == null && SystemClock.uptimeMillis() - heldAt > HOST_WINDOW_MS) {
                    id = null;
                    forget();
                }
            }
            if (id == null || profile == null || photo) return;
            Method activity = method(tab, "getActivity");
            if (activity == null || activity.invoke(tab) != profile) return;
            Method arguments = method(tab, "getArguments");
            if (arguments == null) return;
            Object found = arguments.invoke(tab);
            String user = found instanceof Bundle ? ((Bundle) found).getString(USER_KEY) : null;
            if (user == null || user.isEmpty()) return;
            if (!id.endsWith("_" + user) || !inTabProfile(tab, true)) {
                HookStatus.counted(FamilyNames.REEL_ORIGIN, OTHER_PROFILE);
                return;
            }
            look(tab, id, on, profile, waits);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, RESUME, failure);
        }
    }

    /** Called right after the posts grid's super.onResume() with the grid's fragment. Never throws. */
    public static void postsGridResumed(Object grid) {
        postsGridResumed(grid, ReelOrigin::switchedOn);
    }

    static void postsGridResumed(Object grid, BooleanSupplier on) {
        postsGridResumed(grid, on, ReelOrigin::waitsForTap);
    }

    static void postsGridResumed(Object grid, BooleanSupplier on, BooleanSupplier waits) {
        try {
            HookStatus.invoked(FamilyNames.REEL_ORIGIN);
            if (grid == null || !on.getAsBoolean()) return;
            adoptFeedProfile(grid);
            String id;
            Activity profile;
            boolean photo;
            synchronized (LOCK) {
                id = held;
                photo = heldPhoto;
                profile = host == null ? null : host.get();
                if (id != null && profile == null && SystemClock.uptimeMillis() - heldAt > HOST_WINDOW_MS) {
                    id = null;
                    forget();
                }
            }
            if (id == null || profile == null || !photo) return;
            Method activity = method(grid, "getActivity");
            if (activity == null || activity.invoke(grid) != profile) return;
            Method arguments = method(grid, "getArguments");
            if (arguments == null) return;
            Object found = arguments.invoke(grid);
            if (!(found instanceof Bundle) || !POSTS.equals(((Bundle) found).getString(GRID_KEY))) return;
            if (!inTabProfile(grid, true)) {
                HookStatus.counted(FamilyNames.REEL_ORIGIN, OTHER_PROFILE);
                return;
            }
            boolean other;
            synchronized (LOCK) {
                if (!id.equals(held)) return;
                Object first = postsGrid == null ? null : postsGrid.get();
                if (first == null) postsGrid = new WeakReference<>(grid);
                other = first != null && first != grid;
            }
            if (other) {
                HookStatus.counted(FamilyNames.REEL_ORIGIN, OTHER_PROFILE);
                return;
            }
            look(grid, id, on, profile, waits);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, POSTS_RESUME, failure);
        }
    }

    /**
     * Called first in the posts grid's tile binder with the tile and its media. While a photo reel
     * is held, the tile is named by the media's id, since 454 gives a posts tile no name the search
     * can match (#689); otherwise any name it had is dropped. Never throws.
     */
    public static void postsTileBound(Object tile, Object media) {
        postsTileBound(tile, media, ReelOrigin::switchedOn);
    }

    static void postsTileBound(Object tile, Object media, BooleanSupplier on) {
        try {
            if (!(tile instanceof View)) return;
            boolean wanted;
            synchronized (LOCK) {
                wanted = held != null && heldPhoto;
            }
            String id = wanted && media != null && on.getAsBoolean() ? mediaId(media) : null;
            synchronized (TILE_IDS) {
                if (id == null || id.isEmpty()) {
                    TILE_IDS.remove(tile);
                    return;
                }
                TILE_IDS.put((View) tile, id);
            }
            HookStatus.counted(FamilyNames.REEL_ORIGIN, TILE_NAMED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_ORIGIN, TILE_BOUND, failure);
        }
    }

    /**
     * Whether a photo reel held is to be looked for in [grid], the profile's first main posts grid,
     * so Profile posts as a list leaves the grid up instead of opening its first post. Never throws.
     */
    public static boolean searchesPosts(Object grid) {
        return searchesPosts(grid, ReelOrigin::switchedOn);
    }

    static boolean searchesPosts(Object grid, BooleanSupplier on) {
        try {
            if (grid == null || !on.getAsBoolean()) return false;
            Activity profile;
            synchronized (LOCK) {
                if (held == null || !heldPhoto) return false;
                Object first = postsGrid == null ? null : postsGrid.get();
                if (first != null && first != grid) return false;
                profile = host == null ? null : host.get();
            }
            Method activity = method(grid, "getActivity");
            Method arguments = method(grid, "getArguments");
            if (profile == null || activity == null || arguments == null || activity.invoke(grid) != profile) return false;
            Object found = arguments.invoke(grid);
            return found instanceof Bundle && POSTS.equals(((Bundle) found).getString(GRID_KEY)) && inTabProfile(grid, false);
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Starts looking for [id] in [tab]'s grid in place of any search running, unless the id was
     * dropped meanwhile or the tab lacks what the search reads.
     */
    private static void look(Object tab, String id, BooleanSupplier on, Activity profile, BooleanSupplier waits) {
        Method resumed = method(tab, "isResumed");
        Field grid = gridField(tab);
        if (resumed == null || grid == null) return;
        Look look = new Look(tab, resumed, grid, id, on, profile, !waits.getAsBoolean());
        synchronized (LOCK) {
            if (!id.equals(held)) return;
            stopLooking();
            looking = look;
        }
        look.handler.post(look);
    }

    /** The id held, for tests. */
    static String heldForTests() {
        synchronized (LOCK) {
            return held;
        }
    }

    /** The activity taken as the profile's, for tests. */
    static Activity hostForTests() {
        synchronized (LOCK) {
            return host == null ? null : host.get();
        }
    }

    /** Drops the id and anything looking for it, and stops watching activities. */
    static void forget() {
        synchronized (LOCK) {
            held = null;
            heldPhoto = false;
            host = null;
            postsGrid = null;
            onTab = false;
            tabProfile = null;
            fromFeed = false;
            stopLooking();
            synchronized (TILE_IDS) {
                TILE_IDS.clear();
            }
            if (watched != null) {
                watched.unregisterActivityLifecycleCallbacks(WATCHER);
                watched = null;
            }
        }
    }

    /** Ends the search running, if any, and takes its button down. Called holding LOCK. */
    private static void stopLooking() {
        Look was = looking;
        looking = null;
        if (was == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            was.takeButtonDown();
        } else {
            was.handler.post(was::takeButtonDown);
        }
    }

    /** The search's button, for tests: null while none is up. */
    static TextView buttonForTests() {
        synchronized (LOCK) {
            return looking == null ? null : looking.button;
        }
    }

    /**
     * Drags the tab's grid on most of a screen at a time until the reel's tile is bound, the grid
     * ends, the steps or the time run out, or Stop looking is tapped. Made not going, it waits for a
     * tap on Find the reel first. Stops without a word when the tab is left, and keeps the id.
     */
    private static final class Look implements Runnable {
        final Handler handler = main();
        private final WeakReference<Object> tab;
        private final Method resumed;
        private final Field grid;
        private final String id;
        private final BooleanSupplier on;
        private final WeakReference<Activity> screen;
        private final Rect area = new Rect();
        /** Whether the grid is being dragged, from the start or since Find the reel was tapped. */
        private boolean going;
        /** The button on the profile's screen while it's up, touched only on the main thread. */
        private TextView button;
        private long started;
        private long endSince;
        private long dragged;
        private int steps;
        /** Where the grid was when the search started. */
        private int start;
        /** Since when the search has waited for the grid's first named tiles, and how many were bound at the end last time. */
        private long unnamedSince;
        private int endNamed;
        /** Whether the last drag left the grid where it was, though the grid said more lay below. */
        private boolean stuck;
        /** The reel's own number, null when its id doesn't start with one, and the older tiles seen so far. */
        private final String number;
        private final Set<String> older = new HashSet<>();

        Look(Object tab, Method resumed, Field grid, String id, BooleanSupplier on, Activity screen, boolean going) {
            this.tab = new WeakReference<>(tab);
            this.resumed = resumed;
            this.grid = grid;
            this.id = id;
            this.number = reelNumber(id);
            this.on = on;
            this.screen = new WeakReference<>(screen);
            this.going = going;
        }

        @Override public void run() {
            try {
                synchronized (LOCK) {
                    if (looking != this) return;
                }
                Object tab = this.tab.get();
                if (tab == null || !on.getAsBoolean() || !Boolean.TRUE.equals(resumed.invoke(tab))) {
                    left();
                    return;
                }
                Object found = grid.get(tab);
                if (!(found instanceof ViewGroup) || !onScreen((ViewGroup) found)) {
                    takeButtonDown();
                    // A grid already in its window comes on screen only with a frame, so it's looked
                    // at again after the window's next one rather than every WAIT_MS while nothing
                    // changes, as when Instagram keeps the tab resumed beside the one showing.
                    if (!(found instanceof View) || !afterNextFrame((View) found)) handler.postDelayed(this, WAIT_MS);
                    return;
                }
                ViewGroup list = (ViewGroup) found;
                View tile = tile(list, id);
                if (tile != null) {
                    show(tile);
                    HookStatus.counted(FamilyNames.REEL_ORIGIN, FOUND);
                    forget();
                    return;
                }
                int named = named(list);
                long now = SystemClock.uptimeMillis();
                if (named == 0) {
                    // No reel bound yet, only the placeholders Instagram shows while the first page
                    // loads, or nothing: no button, no drag and no end until named tiles come.
                    if (going) {
                        if (unnamedSince == 0) {
                            unnamedSince = now;
                            HookStatus.counted(FamilyNames.REEL_ORIGIN, WAITED);
                        } else if (now - unnamedSince >= FIRST_PAGE_MS) {
                            giveUp(list, NO_TILES);
                            return;
                        }
                    }
                    handler.postDelayed(this, WAIT_MS);
                    return;
                }
                if (passed(list)) {
                    giveUp(list, PASSED, L10n.t(list.getContext(), "That reel isn't on their profile. It might be a trial reel."));
                    return;
                }
                if (!going) {
                    // Up once the grid has named tiles, while the tab shows; a screen with no place
                    // for it looks straight away.
                    if (putButtonUp(list)) {
                        handler.postDelayed(this, TAP_WAIT_MS);
                        return;
                    }
                    going = true;
                }
                if (started == 0) {
                    started = now;
                    start = offset(list);
                }
                putButtonUp(list);
                if (steps >= MAX_STEPS || now - started >= GIVE_UP_MS) {
                    giveUp(list, RAN_OUT);
                    return;
                }
                if (list.canScrollVertically(1) && !stuck) {
                    // On 451 the grid can say more lies below at its loaded end while the next page
                    // loads (its range is an estimate), and a drag there moves nothing. Such a drag is
                    // the end, not a step: counted, the steps ran out in three seconds (#30).
                    int before = offset(list);
                    drag(list);
                    dragged = now;
                    if (offset(list) != before) {
                        steps++;
                        endSince = 0;
                    } else {
                        stuck = true;
                    }
                } else {
                    // At the end: more named tiles coming in, as Instagram fills a screen its page
                    // left short, start the wait again.
                    if (endSince == 0) {
                        endSince = now;
                        endNamed = named;
                    } else if (named != endNamed) {
                        endSince = now;
                        endNamed = named;
                        stuck = false;
                    } else if (now - endSince >= (loading(list) ? LOAD_WAIT_MS : END_WAIT_MS)) {
                        giveUp(list, ENDED);
                        return;
                    }
                    if (now - dragged >= NUDGE_MS) {
                        int before = offset(list);
                        drag(list);
                        dragged = now;
                        if (offset(list) != before) {
                            stuck = false;
                            endSince = 0;
                        }
                    }
                }
                handler.postDelayed(this, STEP_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, LOOK, failure);
                forget();
            }
        }

        /** Find the reel starts the search; Stop looking ends it where the grid is and drops the id. */
        private void tapped() {
            try {
                synchronized (LOCK) {
                    if (looking != this) return;
                }
                if (going) {
                    HookStatus.counted(FamilyNames.REEL_ORIGIN, STOPPED);
                    forget();
                    return;
                }
                going = true;
                handler.removeCallbacks(this);
                handler.post(this);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, LOOK, failure);
                forget();
            }
        }

        private void left() {
            synchronized (LOCK) {
                if (looking == this) looking = null;
            }
            takeButtonDown();
            HookStatus.counted(FamilyNames.REEL_ORIGIN, LEFT);
        }

        /**
         * Puts the button up near the bottom of the profile's screen, reading Find the reel while the
         * search waits and Stop looking while it goes, or relabels it. False when the screen's content
         * has no place for it.
         */
        private boolean putButtonUp(View list) {
            Activity activity = screen.get();
            View content = activity == null ? null : activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return false;
            Context context = list.getContext();
            String label = going ? L10n.t(context, "Stop looking") : L10n.t(context, "Find the reel");
            if (button == null) {
                button = button(context, this::tapped);
                ((FrameLayout) content).addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
                if (!going) HookStatus.counted(FamilyNames.REEL_ORIGIN, OFFERED);
            }
            if (!label.contentEquals(button.getText())) {
                button.setText(label);
                ScreenColors.styleAction(button, !going);
            }
            // Again each pass, so a turned screen or a navigation bar that came or went moves it too.
            int margin = buttonMargin(content);
            ViewGroup.LayoutParams place = button.getLayoutParams();
            if (place instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) place).bottomMargin != margin) {
                ((ViewGroup.MarginLayoutParams) place).bottomMargin = margin;
                button.setLayoutParams(place);
            }
            return true;
        }

        /** Takes the button off the profile's screen, on the main thread. */
        void takeButtonDown() {
            TextView up = button;
            button = null;
            if (up != null && up.getParent() instanceof ViewGroup) ((ViewGroup) up.getParent()).removeView(up);
        }

        /**
         * Whether the grid has gone past where the reel would sit: every tile on screen is older than
         * it, and more older ones have been seen than could be pinned above it.
         */
        private boolean passed(ViewGroup list) {
            if (number == null) return false;
            int newer = sortTiles(list, number, older);
            return newer == 0 && older.size() > PINNED_MAX;
        }

        private void giveUp(ViewGroup list, String why) throws ReflectiveOperationException {
            giveUp(list, why, L10n.t(list.getContext(), "Couldn't find the reel you came from"));
        }

        /**
         * Not found, for [why]: the grid goes back to where it was, a toast reading [toast] says so,
         * and the id is dropped.
         */
        private void giveUp(ViewGroup list, String why, String toast) throws ReflectiveOperationException {
            int back = started == 0 ? 0 : start - offset(list);
            if (back != 0) list.scrollBy(0, back);
            HookStatus.counted(FamilyNames.REEL_ORIGIN, NOT_FOUND);
            HookStatus.counted(FamilyNames.REEL_ORIGIN, why);
            forget();
            Utils.showToastShort(toast);
        }

        private boolean onScreen(ViewGroup list) {
            return list.isShown() && list.getGlobalVisibleRect(area) && !area.isEmpty();
        }

        /**
         * Looks again WAIT_MS after the next frame of [grid]'s window, once. False when the grid isn't
         * in a window yet, which no frame of a window would tell.
         */
        private boolean afterNextFrame(View grid) {
            if (!grid.isAttachedToWindow()) return false;
            ViewTreeObserver observer = grid.getViewTreeObserver();
            if (!observer.isAlive()) return false;
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    handler.postDelayed(Look.this, WAIT_MS);
                    return true;
                }
            });
            return true;
        }
    }

    /**
     * What [view] is named by: the transition name Instagram gives a Reels tile, or else the id of the
     * media a posts tile was bound to while a photo reel was held, or null.
     */
    static String nameOf(View view) {
        String name = view.getTransitionName();
        if (name != null && !name.isEmpty()) return name;
        synchronized (TILE_IDS) {
            return TILE_IDS.get(view);
        }
    }

    /** The first view under [view] Instagram named for the reel, or null while none is bound. */
    static View tile(View view, String id) {
        if (matches(nameOf(view), id)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = tile(group.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Whether a tile Instagram named [name] is the reel [id]. Instagram names a tile by its reel's
     * id, the reel's own number then its author's ({@code 3100_7}), so the two are equal. The reel's
     * own number alone is enough either way, for a tile or an id that drops the author.
     */
    static boolean matches(String name, String id) {
        if (name == null || id == null || name.isEmpty() || id.isEmpty()) return false;
        if (name.equals(id)) return true;
        String reel = reelNumber(id);
        return reel != null && reel.equals(reelNumber(name));
    }

    /**
     * Sorts the tiles under [view] against the reel numbered [number]: the own numbers of those
     * posted before it go into [older], and the count of those posted after it comes back. Instagram
     * numbers a post by the time it went up, so a smaller number is an older post; a name that
     * doesn't start with a number is neither.
     */
    static int sortTiles(View view, String number, Set<String> older) {
        int newer = 0;
        String name = nameOf(view);
        String own = name == null || name.isEmpty() ? null : reelNumber(name);
        if (own != null) {
            if (olderThan(own, number)) {
                older.add(own);
            } else if (!own.equals(number)) {
                newer++;
            }
        }
        if (!(view instanceof ViewGroup)) return newer;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) newer += sortTiles(group.getChildAt(i), number, older);
        return newer;
    }

    /** Whether the number [own] is smaller than [number], both digits only, of any length. */
    static boolean olderThan(String own, String number) {
        String a = stripZeros(own);
        String b = stripZeros(number);
        return a.length() != b.length() ? a.length() < b.length() : a.compareTo(b) < 0;
    }

    private static String stripZeros(String digits) {
        int at = 0;
        while (at < digits.length() - 1 && digits.charAt(at) == '0') at++;
        return digits.substring(at);
    }

    /** The reel's own number at the start of [id], up to the author's, or null when it doesn't start with one. */
    private static String reelNumber(String id) {
        int end = id.indexOf('_');
        if (end < 0) end = id.length();
        if (end == 0) return null;
        for (int i = 0; i < end; i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        }
        return id.substring(0, end);
    }

    /** How many views under [view] Instagram named, its reels' tiles: none while only placeholders show. */
    static int named(View view) {
        String name = nameOf(view);
        int found = name == null || name.isEmpty() ? 0 : 1;
        if (!(view instanceof ViewGroup)) return found;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) found += named(group.getChildAt(i));
        return found;
    }

    /**
     * Whether Instagram's spinner shows in [list]'s last row, as it does below the loaded end while
     * the next page loads: 451 puts a SpinnerImageView there, a ProgressBar covers a build that
     * doesn't.
     */
    static boolean loading(ViewGroup list) {
        int count = list.getChildCount();
        return count > 0 && spinner(list.getChildAt(count - 1));
    }

    private static boolean spinner(View view) {
        if (!view.isShown()) return false;
        if (view instanceof ProgressBar || view.getClass().getName().endsWith(".SpinnerImageView")) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (spinner(group.getChildAt(i))) return true;
        }
        return false;
    }

    /**
     * Drags [list] up from near its bottom to near its top as a finger would, holding still before
     * letting go so it doesn't fling on. The grid takes it like any drag, folding the profile's header
     * away first and asking Instagram for the next page near the end.
     */
    static void drag(View list) {
        float x = list.getWidth() / 2f;
        float from = list.getHeight() * 9 / 10f;
        float to = list.getHeight() / 10f;
        long down = SystemClock.uptimeMillis();
        touch(list, down, down, MotionEvent.ACTION_DOWN, x, from);
        for (int move = 1; move <= DRAG_MOVES; move++) {
            touch(list, down, down + move * MOVE_MS, MotionEvent.ACTION_MOVE, x, from + (to - from) * move / DRAG_MOVES);
        }
        long still = down + DRAG_MOVES * MOVE_MS + HOLD_MS;
        touch(list, down, still, MotionEvent.ACTION_MOVE, x, to);
        touch(list, down, still, MotionEvent.ACTION_UP, x, to);
    }

    private static void touch(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** How far down [list] is scrolled, as its scroll bar sees it. */
    static int offset(View list) throws ReflectiveOperationException {
        Method found = scrollOffset;
        if (found == null) {
            found = View.class.getDeclaredMethod("computeVerticalScrollOffset");
            found.setAccessible(true);
            scrollOffset = found;
        }
        return (Integer) found.invoke(list);
    }

    /**
     * The same 12 dp action used in HushGram's dialogs, with a spoken update when the search starts.
     */
    static TextView button(Context context, Runnable tapped) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView button = new Button(context);
        ScreenColors.styleAction(button, true);
        button.setElevation(6 * density);
        button.setFocusable(true);
        button.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button.setOnClickListener(view -> tapped.run());
        return button;
    }

    /** How far above the bottom of [content] the button sits: BUTTON_ABOVE_DP past the navigation bar. */
    @SuppressWarnings("deprecation")
    static int buttonMargin(View content) {
        WindowInsets insets = content.getRootWindowInsets();
        int bar = insets == null ? 0 : insets.getSystemWindowInsetBottom();
        return Math.round(BUTTON_ABOVE_DP * content.getResources().getDisplayMetrics().density) + bar;
    }

    /**
     * Brings [tile] fully on screen, the header folding away if it must, and marks it for a few
     * seconds: dimmed, edged in HushGram's accent and labelled You just watched, so it stands out on a
     * pale thumbnail too (#781).
     */
    static void show(View tile) {
        tile.requestRectangleOnScreen(new Rect(0, 0, tile.getWidth(), tile.getHeight()), false);
        Mark mark = new Mark(tile, L10n.t(tile.getContext(), "You just watched"), ScreenColors.current().accent);
        mark.setBounds(0, 0, tile.getWidth(), tile.getHeight());
        tile.getOverlay().add(mark);
        tile.addOnAttachStateChangeListener(mark);
        main().postDelayed(mark::fade, OUTLINE_MS);
    }

    /**
     * The found tile's mark: a dim layer, an accent edge and a centered label that wraps to the tile.
     * It goes early when the tile leaves the window, and draws nothing once Instagram binds the tile
     * to another reel, so it never labels the wrong one.
     */
    static final class Mark extends Drawable implements View.OnAttachStateChangeListener {
        /** The dim layer's black, at 45%, so white text reads on a white thumbnail. */
        static final int DIM = 0x73000000;
        final View tile;
        final String reel;
        final String label;
        final int accent;
        private final Paint dim = new Paint();
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int padding;
        private StaticLayout lines;
        private int alpha = 255;
        private boolean gone;

        Mark(View tile, String label, int accent) {
            this.tile = tile;
            this.reel = nameOf(tile);
            this.label = label;
            this.accent = accent;
            density = tile.getResources().getDisplayMetrics().density;
            padding = Math.round(8 * density);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, Math.round(3 * density)));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                    tile.getResources().getDisplayMetrics()));
        }

        /** The label as laid out for the tile's width, or null before the mark has bounds. */
        StaticLayout lines() {
            return lines;
        }

        /** Whether the tile still shows the reel the mark was made for. */
        boolean onItsReel() {
            String now = nameOf(tile);
            return reel != null && reel.equals(now);
        }

        @Override protected void onBoundsChange(Rect bounds) {
            int width = bounds.width() - 2 * padding;
            lines = width <= 0 ? null : StaticLayout.Builder.obtain(label, 0, label.length(), text, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build();
        }

        @Override public void draw(Canvas canvas) {
            if (gone || !onItsReel()) return;
            Rect area = getBounds();
            dim.setColor(Color.argb(Color.alpha(DIM) * alpha / 255, 0, 0, 0));
            canvas.drawRect(area, dim);
            float half = edge.getStrokeWidth() / 2;
            edge.setColor(accent);
            edge.setAlpha(Color.alpha(accent) * alpha / 255);
            canvas.drawRect(area.left + half, area.top + half, area.right - half, area.bottom - half, edge);
            if (lines == null) return;
            text.setColor(Color.argb(alpha, 255, 255, 255));
            text.setShadowLayer(2 * density, 0, density, Color.argb(0x99 * alpha / 255, 0, 0, 0));
            canvas.save();
            canvas.translate(area.left + padding, area.top + Math.max(0, (area.height() - lines.getHeight()) / 2f));
            lines.draw(canvas);
            canvas.restore();
        }

        /** Fades the mark out, then takes it off the tile. */
        void fade() {
            if (gone) return;
            ValueAnimator out = ValueAnimator.ofInt(alpha, 0);
            out.setDuration(FADE_MS);
            out.addUpdateListener(step -> setAlpha((Integer) step.getAnimatedValue()));
            // With animations off the animator ends at once, and the mark still goes.
            out.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    remove();
                }
            });
            out.start();
        }

        void remove() {
            if (gone) return;
            gone = true;
            tile.getOverlay().remove(this);
            tile.removeOnAttachStateChangeListener(this);
        }

        @Override public void onViewAttachedToWindow(View view) {}

        @Override public void onViewDetachedFromWindow(View view) {
            remove();
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override public int getAlpha() {
            return alpha;
        }

        @Override public void setColorFilter(ColorFilter filter) {}

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The profile's activity is the first one created after the tap; it going drops the id. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle saved) {
            try {
                synchronized (LOCK) {
                    if (held == null || host != null) return;
                    if (SystemClock.uptimeMillis() - heldAt <= HOST_WINDOW_MS) {
                        host = new WeakReference<>(activity);
                    } else {
                        forget();
                    }
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, WATCH, failure);
                forget();
            }
        }

        @Override public void onActivityDestroyed(Activity activity) {
            try {
                synchronized (LOCK) {
                    Activity profile = host == null ? null : host.get();
                    if (host != null && (profile == null || profile == activity)) forget();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_ORIGIN, WATCH, failure);
                forget();
            }
        }

        @Override public void onActivityStarted(Activity activity) {
        }

        @Override public void onActivityResumed(Activity activity) {
        }

        @Override public void onActivityPaused(Activity activity) {
        }

        @Override public void onActivityStopped(Activity activity) {
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }
    }

    /** The reel's id, the string Instagram names its grid tile by, or null after reporting it missing. */
    private static String mediaId(Object media) throws ReflectiveOperationException {
        Method id = getId;
        if (id == null || !id.getDeclaringClass().isInstance(media)) {
            try {
                id = media.getClass().getMethod("getId");
            } catch (NoSuchMethodException missing) {
                HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "method", media.getClass().getName(), "getId");
                return null;
            }
            getId = id;
        }
        Object value = id.invoke(media);
        return value instanceof String ? (String) value : null;
    }

    /** The tab's one grid field, or null after reporting it missing. */
    private static Field gridField(Object tab) {
        Field found = null;
        int candidates = 0;
        for (Field field : tab.getClass().getDeclaredFields()) {
            if (!GRID_TYPE.equals(field.getType().getName())) continue;
            found = field;
            candidates++;
        }
        if (candidates > 1) {
            HookStatus.ambiguous(FamilyNames.REEL_ORIGIN, "field", tab.getClass().getName(), GRID_TYPE, candidates);
            return null;
        }
        if (found == null) {
            HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "field", tab.getClass().getName(), GRID_TYPE);
            return null;
        }
        found.setAccessible(true);
        return found;
    }

    /** A public method of the tab's, taking nothing, or null after reporting it missing. */
    private static Method method(Object tab, String name) {
        try {
            return tab.getClass().getMethod(name);
        } catch (NoSuchMethodException missing) {
            HookStatus.missingMember(FamilyNames.REEL_ORIGIN, "method", tab.getClass().getName(), name);
            return null;
        }
    }

    private static Application application() {
        Context context = Utils.getContext();
        if (context instanceof Application) return (Application) context;
        Context app = context == null ? null : context.getApplicationContext();
        return app instanceof Application ? (Application) app : null;
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.FIND_REEL_ORIGIN.get();
    }

    private static boolean waitsForTap() {
        return Utils.settingsReady() && Settings.FIND_REEL_ORIGIN_ON_TAP.get();
    }
}
