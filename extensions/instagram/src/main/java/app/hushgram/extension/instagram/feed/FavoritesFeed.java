/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Favorites from the tab bar (#643), part of the "Start Home on Following" patch.
 *
 * <p>Instagram opens Favorites from Home's feed picker as a page on the current tab, and the tab
 * host hides the tab bar for anything pushed on a tab, so Search and Messages were out of reach
 * until Back. With its switch on, {@link #keepTabRoot} answers the host's check for a tab at its
 * root with yes while Favorites is on top, and the bar stays.
 *
 * <p>A tab chosen in the settings opens Favorites on a long press through {@link #open}: it hands
 * the Favorites feed type to the feed switch Home's picker uses, which {@link #switchReady} keeps
 * as Instagram builds it.
 */
public final class FavoritesFeed {
    /** The Favorites feed type's constant name. */
    static final String FAVORITES = "FAVORITES";

    /** The argument Instagram names the feed of a feed page with, and the Favorites page's value. */
    static final String MODE_KEY = "ContextualFeedFragment.ARGUMENT_CONTEXTUAL_FEED_MODE";
    static final String FAVORITES_MODE = "Favorites_Feed";

    /** What's counted each time a tab long press opened Favorites. */
    static final String OPENED = "Favorites opened from a tab long press";

    /** What's counted each time a tab long press found no feed switch of the activity's own. */
    static final String NO_SWITCH = "Favorites tab long press found no feed switch";

    /** What's counted each time the tab bar stayed on Favorites. */
    static final String BAR_KEPT = "Tab bar kept on Favorites";

    static final String WHAT_OPEN = "open Favorites";
    static final String WHAT_ROOT = "keep the tab bar on Favorites";

    /** Home's feed switch, last built. It holds the activity it was built for. */
    private static volatile WeakReference<Object> feedSwitch = new WeakReference<>(null);

    /** Stand in for the feed type and the feed switch's call, which only the patch writes. Tests only. */
    @Nullable static volatile Class<?> typesForTests;
    @Nullable static volatile Show showForTests;

    private static volatile boolean loggedOpen;
    private static volatile boolean loggedBar;

    private FavoritesFeed() {
    }

    interface Show {
        void feed(Object feedSwitch, Object feed);
    }

    interface Screens {
        List<?> of(Object host);
    }

    /** Injected as Instagram finishes building Home's feed switch. Never throws. */
    public static void switchReady(Object built) {
        feedSwitch = new WeakReference<>(built);
    }

    /**
     * Opens Favorites as a page on [activity]'s current tab, the way Home's picker does. Answers
     * false, having done nothing, when there's no feed switch built for [activity] or anything goes
     * wrong. Main thread. Never throws.
     */
    public static boolean open(Activity activity) {
        Show stand = showForTests;
        if (stand != null) return open(activity, feedSwitch.get(), typesForTests, stand);
        return open(activity, feedSwitch.get(), FavoritesFeedTargets.feedTypes(), FavoritesFeedTargets::show);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean open(Activity activity, @Nullable Object owner, @Nullable Class<?> feedTypes, Show show) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (owner == null || feedTypes == null || !feedTypes.isEnum() || !builtFor(owner, activity)) {
                HookStatus.counted(FamilyNames.FOLLOWING_FEED, NO_SWITCH);
                return false;
            }
            show.feed(owner, Enum.valueOf((Class) feedTypes, FAVORITES));
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, OPENED);
            if (!loggedOpen) {
                loggedOpen = true;
                Logger.printDebug(() -> "Favorites feed: opened from a tab long press");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, WHAT_OPEN, failure);
            return false;
        }
    }

    /** Whether [owner]'s one activity field holds [activity]: a switch built for an older activity can't open pages on this one. */
    static boolean builtFor(Object owner, Activity activity) throws IllegalAccessException {
        Field held = null;
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (!Activity.class.isAssignableFrom(field.getType())) continue;
            if (held != null) return false;
            held = field;
        }
        if (held == null) return false;
        held.setAccessible(true);
        return held.get(owner) == activity;
    }

    /** Called with a tab host's answer to whether it's at its root, which decides on the tab bar. Non-zero is yes. */
    public static int keepTabRoot(int atRoot, Object host) {
        return keepTabRoot(atRoot, host, FavoritesFeedTargets::fragments);
    }

    static int keepTabRoot(int atRoot, Object host, Screens screens) {
        if (atRoot != 0) return atRoot;
        try {
            if (!Utils.settingsReady() || !Settings.FAVORITES_TAB_BAR.get()) return atRoot;
            List<?> added = screens.of(host);
            if (added == null || added.isEmpty() || !isFavorites(added.get(added.size() - 1))) return atRoot;
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, BAR_KEPT);
            if (!loggedBar) {
                loggedBar = true;
                Logger.printDebug(() -> "Favorites feed: tab bar kept on Favorites");
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, WHAT_ROOT, failure);
            return atRoot;
        }
    }

    /** Whether [screen] is a feed page showing Favorites, by the arguments it was opened with. */
    @SuppressWarnings("deprecation")
    static boolean isFavorites(@Nullable Object screen) throws ReflectiveOperationException {
        if (screen == null) return false;
        Method arguments;
        try {
            arguments = screen.getClass().getMethod("getArguments");
        } catch (NoSuchMethodException notAScreen) {
            return false;
        }
        Object bundle = arguments.invoke(screen);
        return bundle instanceof Bundle && FAVORITES_MODE.equals(String.valueOf(((Bundle) bundle).get(MODE_KEY)));
    }
}
