/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Profile posts as a list" patch.
 *
 * <p>Instagram 450 has no list layout for a profile's posts: the posts tab is always a grid, and
 * tapping a post opens Instagram's own scrolling list of that profile's full posts, starting at the
 * one tapped, with Back going to the grid. So this taps the first post for you. The patch calls
 * {@link #resumed} first in the posts tab's onResume. When the tab is the posts grid of someone
 * else's profile and hasn't been opened this way before, the first cell of the grid is looked for
 * as the posts come in, and once it shows its own click listener runs, the same as a tap. Instagram's
 * code opens the list, so nothing in its grid or its list is changed.
 *
 * <p>It happens once for each profile page: a mark goes in the tab's own arguments and in the
 * arguments of the profile page around it, which Instagram saves and restores with them, so coming
 * Back to the grid, switching tabs or Instagram rebuilding the screen doesn't open the list again.
 * The page's mark is the one that counts after Tagged: switching to it and back makes Instagram
 * build the posts tab again with fresh arguments, while the page stays. Tagged posts, the other tabs and your own
 * profile are left as they are. If the tab is left, or the posts don't show within a few seconds (a
 * private profile, or none posted), nothing is opened.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet, a member
 * missing or anything thrown, the grid stays as Instagram shows it.
 */
public final class PostsList {
    /** The posts tab's arguments: which tab it is, and whether the profile is yours. */
    static final String TAB_KEY = "ProfileMediaTabFragment.profile_tab_identifier";
    static final String SELF_KEY = "ProfileMediaTabFragment.is_self_profile";
    /** What the main posts tab is called, as against tagged, reels and the rest. */
    static final String POSTS_TAB = "profile_media_grid";
    /** The tab's grid, a field that keeps its name in every 450 build. */
    static final String GRID_FIELD = "recyclerView";
    /** The view each post of the grid is drawn in. */
    static final String CELL = "com.instagram.igds.components.imagebutton.IgMultiImageButton";
    /** Put in the arguments of the tab and of its profile page once its first post has been looked for. */
    static final String OPENED_KEY = "hushgram_posts_list_opened";

    /** The steps a failure is reported under. */
    static final String RESUME = "tab resumed";
    static final String LOOK = "first post";
    /** Counted when the list was opened, when there was nothing to open, and when the tab was left first. */
    static final String OPENED = "opened the list";
    static final String NOTHING = "no post to open";
    static final String LEFT = "tab left first";

    /** How often the grid is looked at while the posts come in, and for how long. */
    static final long LOOK_EVERY_MS = 100;
    static final long GIVE_UP_MS = 5000;

    private static volatile boolean logged;

    private PostsList() {
    }

    /** Called first in the posts tab's onResume with the tab. Never throws. */
    public static void resumed(Object tab) {
        resumed(tab, PostsList::switchedOn);
    }

    static void resumed(Object tab, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_POSTS_LIST);
            if (tab == null || !on.getAsBoolean()) return;
            Method arguments = method(tab, "getArguments");
            if (arguments == null) return;
            Object found = arguments.invoke(tab);
            if (!(found instanceof Bundle)) return;
            Bundle bundle = (Bundle) found;
            if (!POSTS_TAB.equals(bundle.getString(TAB_KEY)) || bundle.getBoolean(SELF_KEY) || bundle.getBoolean(OPENED_KEY)) {
                return;
            }
            Bundle page = pageArguments(tab);
            if (page != null && page.getBoolean(OPENED_KEY)) return;
            bundle.putBoolean(OPENED_KEY, true);
            if (page != null) page.putBoolean(OPENED_KEY, true);
            main().post(new Look(tab, SystemClock.uptimeMillis() + GIVE_UP_MS));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_POSTS_LIST, RESUME, failure);
        }
    }

    /** Looks for the first post until it shows, the tab is left, or time runs out. */
    private static final class Look implements Runnable {
        private final WeakReference<Object> tab;
        private final long until;
        private final Rect area = new Rect();

        Look(Object tab, long until) {
            this.tab = new WeakReference<>(tab);
            this.until = until;
        }

        @Override public void run() {
            try {
                Object tab = this.tab.get();
                if (tab == null) return;
                Method isResumed = method(tab, "isResumed");
                if (isResumed == null) return;
                if (!Boolean.TRUE.equals(isResumed.invoke(tab))) {
                    HookStatus.counted(FamilyNames.PROFILE_POSTS_LIST, LEFT);
                    return;
                }
                Field field = field(tab);
                if (field == null) return;
                Object grid = field.get(tab);
                View tap = grid instanceof ViewGroup ? tapFor((ViewGroup) grid, area) : null;
                if (tap != null) {
                    tap.callOnClick();
                    HookStatus.counted(FamilyNames.PROFILE_POSTS_LIST, OPENED);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> "Profile posts as a list: opened the first post");
                    }
                    return;
                }
                if (SystemClock.uptimeMillis() >= until) {
                    HookStatus.counted(FamilyNames.PROFILE_POSTS_LIST, NOTHING);
                    return;
                }
                main().postDelayed(this, LOOK_EVERY_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_POSTS_LIST, LOOK, failure);
            }
        }
    }

    /**
     * The view to tap for the grid's first post: its cell, or the nearest view above it in the grid
     * that takes the tap. Null while the grid or its first cell isn't showing yet. Only the first
     * cell counts, so a later one is never opened in its place.
     */
    static View tapFor(ViewGroup grid, Rect area) {
        if (!grid.isShown()) return null;
        View cell = firstCell(grid);
        if (cell == null || !cell.isShown() || !cell.getGlobalVisibleRect(area) || area.isEmpty()) return null;
        for (View view = cell; view != null; ) {
            if (view.hasOnClickListeners()) return view;
            if (view == grid) return null;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** The first post cell under [view], in the order the grid lays its rows out. */
    static View firstCell(View view) {
        if (isCell(view.getClass())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = firstCell(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isCell(Class<?> type) {
        for (Class<?> c = type; c != null && c != View.class; c = c.getSuperclass()) {
            if (CELL.equals(c.getName())) return true;
        }
        return false;
    }

    /**
     * The arguments of the profile page the tab sits in (450's UserDetailFragment, one for each
     * profile opened), or null. Looked up without reporting anything missing: without the page, the
     * tab's own mark still keeps Back and recreation from opening the list again.
     */
    static Bundle pageArguments(Object tab) {
        try {
            Object page = tab.getClass().getMethod("getParentFragment").invoke(tab);
            if (page == null) return null;
            Object found = page.getClass().getMethod("getArguments").invoke(page);
            return found instanceof Bundle ? (Bundle) found : null;
        } catch (ReflectiveOperationException missing) {
            return null;
        }
    }

    /** A public method of the tab's, taking nothing, or null after reporting it missing. */
    private static Method method(Object tab, String name) {
        try {
            return tab.getClass().getMethod(name);
        } catch (NoSuchMethodException missing) {
            HookStatus.missingMember(FamilyNames.PROFILE_POSTS_LIST, "method", tab.getClass().getName(), name);
            return null;
        }
    }

    /** The tab's grid field, or null after reporting it missing. */
    private static Field field(Object tab) {
        try {
            return tab.getClass().getField(GRID_FIELD);
        } catch (NoSuchFieldException missing) {
            HookStatus.missingMember(FamilyNames.PROFILE_POSTS_LIST, "field", tab.getClass().getName(), GRID_FIELD);
            return null;
        }
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.PROFILE_POSTS_LIST.get();
    }
}
