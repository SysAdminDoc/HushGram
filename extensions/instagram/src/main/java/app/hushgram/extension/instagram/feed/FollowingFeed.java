/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.widget.ViewAnimator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Start Home on Following" patch.
 *
 * <p>Instagram has a feed picker at the top of Home that offers For you, Following and Favorites,
 * shows the picked feed's name and remembers the pick. Two server flags turn it on: one has the
 * pick remembered, read on 449 by Home, the picker and the feed request among others, and one puts
 * For you in the picker and the name at the top. The patch passes every read of both through
 * {@link #flag}, which answers yes while the switch is on, and the remembered pick through
 * {@link #saved}, which answers Following while there's none yet.
 *
 * <p>A second switch keeps Home to accounts you follow: {@link #saved} answers Following in place
 * of a remembered For you, and {@link #limitPicker} takes For you out of the picker's list before
 * Instagram freezes it.
 *
 * <p>A third switch, which works without the first, keeps Instagram's logo at the top of Home where
 * the picked feed's name would be, scrolled or not: {@link #keepLogo} is asked as the header's title
 * view is about to show that name, and a yes has it show the logo with its arrow instead. The arrow
 * still opens the picker.
 *
 * <p>The end of Following links back to the plain Home feed, which the picker doesn't offer, so
 * {@link #picked} answers a feed the picker switches Home to in place. When that's Following, the
 * feed Home is already on, the link then taps the Home tab, which takes Home back to its top.
 *
 * <p>A fourth switch works without the first: an account whose For you holds only suggested posts
 * is moved to Following ({@link ForYouFallback}), and {@link #flag}, {@link #saved} and
 * {@link #picked} answer for that account as they do with the first switch on.
 */
public final class FollowingFeed {
    /** The name Instagram 449 saves for the Following feed, its feed type constant's. */
    static final String FOLLOWING = "FOLLOWING";

    /**
     * The picker's feeds that aren't only accounts you follow: For you, and the plain Home feed
     * the picker shows in its place when For you is off.
     */
    static final Set<String> FOR_YOU = new HashSet<>(Arrays.asList("BLENDED", "BLENDED_FOR_YOU"));

    /** The plain Home feed, which the end of Following's "Back to home" link asks for. */
    static final String PLAIN_HOME = "BLENDED";

    /** The picker's For you. */
    static final String PICKER_FOR_YOU = "BLENDED_FOR_YOU";

    /** What's counted each time a link to the plain Home feed gets a feed the picker switches to in place. */
    static final String HOME_LINK_KEPT = "Back to home kept on Home";

    /** What's counted each time Back to home, with Home already on Following, taps the Home tab to go back to the top. */
    static final String HOME_LINK_TOP = "Back to home took Home back to its top";

    /** What's counted each time Back to home found no Home tab on screen to tap. */
    static final String HOME_LINK_NO_TAB = "Back to home found no Home tab to tap";

    /** The id name of the bottom bar's Home tab. A tap on it while Home shows takes the feed back to its top. */
    static final String HOME_TAB = "feed_tab";

    private static volatile boolean registered;

    /** The activity in front, from its resume to its pause, whose Home tab Back to home taps. Main thread. */
    private static WeakReference<Activity> front = new WeakReference<>(null);

    private static volatile boolean loggedFlag;
    private static volatile boolean loggedDefault;
    private static volatile boolean loggedLimit;
    private static volatile boolean loggedPicker;
    private static volatile boolean loggedLogo;
    private static volatile boolean loggedLink;

    /** The name of Home's header, whose title view shows the picked feed's name. Instagram's layouts name it, so it keeps its name. */
    static final String HOME_BAR = "instagram.features.feed.mainfeed.actionbar.MainFeedActionBar";

    /** What's counted each time the logo stands in for the feed's name. */
    static final String LOGO_KEPT = "Home header logo kept in place of the feed name";

    /** How many parents up from the title view the header is looked for. */
    private static final int BAR_REACH = 6;

    /** The feed type field of a picker item's class, looked up once. */
    private static volatile Field feedField;

    private FollowingFeed() {
    }

    /**
     * Called as the application starts. Does nothing in a build without the patch, and watches
     * which activity is in front otherwise, for Back to home to tap its Home tab.
     */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.followingFeed()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "start", failure);
        }
    }

    /**
     * Injected right after each read of the feed picker's flags, with Instagram's answer as an int
     * (non-zero is yes). Answers true while the switch is on, or while the signed-in account was
     * moved to Following ({@link ForYouFallback}), and Instagram's answer otherwise, or when
     * anything goes wrong. Never throws, and never waits for the settings: before they're ready
     * Instagram's answer stands.
     */
    public static boolean flag(int enabled) {
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!Utils.settingsReady()) return enabled != 0;
            if (!Settings.START_ON_FOLLOWING.get() && !ForYouFallback.movedToFollowing()) return enabled != 0;
            if (!loggedFlag) {
                loggedFlag = true;
                Logger.printDebug(() -> "Following feed: feed picker flags answered on");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed flag", failure);
            return enabled != 0;
        }
    }

    /**
     * Injected first thing as Home's title view is about to show the picked feed's name. Answers 1
     * to have it show Instagram's logo with its arrow instead, or 2 when the logo is already up, so
     * nothing is shown again: Home asks for the name on every scroll step past the top, and showing
     * the logo again starts its fade over each time. That happens while the logo switch is on and
     * [titleView] sits in Home's header, with or without Start Home on Following. Answers 0 for any
     * other title view, the switch off, HushGram paused, the settings not read yet or anything
     * thrown, and the name is shown as Instagram has it. Never throws, and never waits for the
     * settings.
     */
    public static int keepLogo(Object titleView) {
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!(titleView instanceof View) || !Utils.settingsReady()) return 0;
            if (!Settings.LOGO_ON_FOLLOWING.get()) return 0;
            if (!underBar((View) titleView, HOME_BAR)) return 0;
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, LOGO_KEPT);
            if (!loggedLogo) {
                loggedLogo = true;
                Logger.printDebug(() -> "Following feed: Home's title shows Instagram's logo in place of the feed name");
            }
            return titleView instanceof ViewAnimator && ((ViewAnimator) titleView).getDisplayedChild() == 0 ? 2 : 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed title logo", failure);
            return 0;
        }
    }

    /** Whether [view] has a parent, within a few levels, whose class is named [barName]. */
    static boolean underBar(View view, String barName) {
        ViewParent parent = view.getParent();
        for (int level = 0; parent != null && level < BAR_REACH; level++) {
            if (barName.equals(parent.getClass().getName())) return true;
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * Injected where Instagram hands back the feed you last picked, with that feed's name. Answers
     * Following when there's no name and the switch is on, and in place of For you while the second
     * switch is on too. With the switch off, answers Following when there's no name and the
     * signed-in account was moved to Following ({@link ForYouFallback}), whose picks this notes so
     * a picked feed keeps winning. Otherwise, or when anything goes wrong, answers the name. Never
     * throws.
     */
    public static String saved(String name) {
        boolean picked = name != null && !name.isEmpty();
        if (picked) ForYouFallback.sawPick();
        if (picked && !FOR_YOU.contains(name)) return name;
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!Utils.settingsReady()) return name;
            if (!Settings.START_ON_FOLLOWING.get()) {
                if (picked || !ForYouFallback.movedToFollowing()) return name;
                HookStatus.counted(FamilyNames.FOLLOWING_FEED, ForYouFallback.OPENED);
                return FOLLOWING;
            }
            if (picked) {
                if (!Settings.ONLY_FOLLOWING.get()) return name;
                if (!loggedLimit) {
                    loggedLimit = true;
                    Logger.printDebug(() -> "Following feed: For you was picked, Home stays on Following");
                }
                return FOLLOWING;
            }
            if (!loggedDefault) {
                loggedDefault = true;
                Logger.printDebug(() -> "Following feed: no feed picked yet, starting on Following");
            }
            return FOLLOWING;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "saved feed", failure);
            return name;
        }
    }

    /**
     * Injected first thing where Instagram switches Home to a feed, with the feed type constant.
     * With the picker on, Home switches in place only to For you or Following, and any other feed
     * opens as a page of its own. The end of Following's "Back to home" link asks for the plain
     * Home feed, which has no page, and Instagram closed on the empty one it opened. While the
     * switch is on, answers For you for it, or Following while For you is taken out. Following is
     * the feed Home is already on, so Instagram's switch does nothing and the link then taps the
     * Home tab, a moment later, to take Home back to its newest post. An account moved to Following
     * ({@link ForYouFallback}) gets For you for it. Answers [feed] otherwise, or when anything goes
     * wrong. Never throws.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object picked(Object feed) {
        if (!(feed instanceof Enum) || !PLAIN_HOME.equals(((Enum<?>) feed).name())) return feed;
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (!Utils.settingsReady()) return feed;
            boolean start = Settings.START_ON_FOLLOWING.get();
            if (!start && !ForYouFallback.movedToFollowing()) return feed;
            boolean following = start && Settings.ONLY_FOLLOWING.get();
            String name = following ? FOLLOWING : PICKER_FOR_YOU;
            Object kept = Enum.valueOf((Class) ((Enum<?>) feed).getDeclaringClass(), name);
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, HOME_LINK_KEPT);
            if (following) Utils.runOnMainThread(FollowingFeed::backToTop);
            if (!loggedLink) {
                loggedLink = true;
                Logger.printDebug(() -> "Following feed: Back to home switches Home to " + name);
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed link", failure);
            return feed;
        }
    }

    /** Taps the Home tab of the activity in front, which takes Home back to its top. Never throws. */
    static void backToTop() {
        try {
            Activity activity = front.get();
            int tab = activity == null ? 0
                    : activity.getResources().getIdentifier(HOME_TAB, "id", activity.getPackageName());
            boolean tapped = tapHome(activity, tab);
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, tapped ? HOME_LINK_TOP : HOME_LINK_NO_TAB);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed link top", failure);
        }
    }

    /** Clicks the view with id [tab] in [activity]'s window, as a finger would, when it's shown. Answers whether it did. */
    static boolean tapHome(@Nullable Activity activity, int tab) {
        if (activity == null || tab == 0 || activity.getWindow() == null) return false;
        View home = activity.getWindow().getDecorView().findViewById(tab);
        return home != null && home.isShown() && home.performClick();
    }

    /**
     * Injected just before Instagram freezes the list of feeds Home's picker offers. While both
     * switches are on, takes For you out of it, unless that would leave the picker empty. Leaves
     * the list as it was otherwise, or when anything goes wrong. Never throws.
     */
    public static void limitPicker(List<?> feeds) {
        try {
            HookStatus.invoked(FamilyNames.FOLLOWING_FEED);
            if (feeds == null || !Utils.settingsReady() || !Settings.START_ON_FOLLOWING.get()
                    || !Settings.ONLY_FOLLOWING.get()) return;
            int others = 0;
            for (Object feed : feeds) {
                if (!FOR_YOU.contains(feedName(feed))) others++;
            }
            if (others == 0 || others == feeds.size()) return;
            for (int at = feeds.size() - 1; at >= 0; at--) {
                if (FOR_YOU.contains(feedName(feeds.get(at)))) feeds.remove(at);
            }
            if (!loggedPicker) {
                loggedPicker = true;
                Logger.printDebug(() -> "Following feed: For you taken out of the feed picker");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "feed picker", failure);
        }
    }

    /**
     * The name of the feed a picker item stands for: its one field holding an enum constant.
     * Null when the item has no such field.
     */
    static String feedName(Object item) throws IllegalAccessException {
        if (item == null) return null;
        Field field = feedField;
        if (field == null || field.getDeclaringClass() != item.getClass()) {
            field = null;
            for (Field candidate : item.getClass().getDeclaredFields()) {
                if (!candidate.getType().isEnum()) continue;
                if (field != null) return null;
                field = candidate;
            }
            if (field == null) return null;
            field.setAccessible(true);
            feedField = field;
        }
        Object feed = field.get(item);
        return feed instanceof Enum ? ((Enum<?>) feed).name() : null;
    }

    /** Keeps {@link #front} to the activity between its resume and its pause. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            front = new WeakReference<>(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            if (front.get() == activity) front = new WeakReference<>(null);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
