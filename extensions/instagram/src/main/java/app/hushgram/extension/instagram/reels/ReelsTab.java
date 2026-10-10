/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide the Reels tab patch asks as Instagram builds its tab bar and picks a tab.
 *
 * <p>Instagram 449 keeps its tabs in one enum, which its build still calls IgTab, and Reels is the
 * one named CLIPS. The tab host gets its list of tabs from one method, and the patch hands that list
 * to {@link #tabs} as it's returned, so the tab bar, the swipe between tabs and everything else that
 * reads the list sees it without Reels. Some accounts open on Reels, and Instagram treats Reels as
 * their home tab; the patch hands that answer to {@link #tab}, and every switch to a tab goes
 * through {@link #tab} too, so a start, a notification or a link meant for the Reels tab lands on
 * Home instead.
 *
 * <p>The same list hook takes Search, Create and Profile off the bar when their switches are on. It
 * hands back a copy, so the list Instagram shares isn't changed, and the bar and the swipe between
 * tabs are built from the copy, which is why the bar closes up with no gap. Home is never hidden,
 * and a list the switches would empty comes back as built. A switch meant for a hidden Search or
 * Profile lands on Home, like one meant for Reels.
 *
 * <p>The first landing tab Instagram asks about can become the Start tab choice, see {@link TabStart}.
 * The Tab order choice puts the list in the order picked, as a copy, see {@link TabOrder}.
 *
 * <p>With Show the Reels tab on, a list Instagram built without Reels gets it back right after
 * Home, from the same enum, and Hide the Reels tab wins when both are on.
 *
 * <p>Reels themselves aren't touched. A reel in the feed, a shared reel and the Reels viewer open as
 * before. The list is built as Instagram starts, so a change to the switch shows after a restart.
 * Every tab goes through as it came while the switch is off, HushGram is paused or the settings
 * aren't ready, or when anything in here fails.
 */
public final class ReelsTab {
    /** The Reels tab's name in Instagram's tab enum. */
    static final String REELS = "CLIPS";

    /** The name of the tab a start or a switch meant for Reels goes to instead. */
    static final String HOME = "FEED";

    /** The Search, Create and Profile tabs' names in the same enum. Home stays: every redirect lands on it. */
    static final String SEARCH = "SEARCH";
    static final String CREATE = "CREATION";
    static final String PROFILE = "PROFILE";

    /** The diagnostic counter route: each tab list Instagram builds, and the tabs taken out of it. */
    static final String ROUTE = "Reels tab";

    /** What a tab taken off the bar is counted under. */
    static final String HIDDEN = "tabs";

    /**
     * The name of the one tab the next switch opens as asked, even while a switch hides it, or null.
     * Set only around the Search button's own switch ({@link SearchHeaderButton}), and cleared by it.
     */
    @Nullable
    private static volatile String letThrough;

    private ReelsTab() {
    }

    /** Lets the next switch to the tab called [name] through as asked, or with null, stops letting one through. */
    static void letThrough(@Nullable String name) {
        letThrough = name;
    }

    /**
     * Handed each tab list Instagram builds, as it's returned. A copy without the tabs the switches
     * hide, otherwise the list as it came. Never throws, and never answers with an empty list.
     */
    @Nullable
    public static List<?> tabs(@Nullable List<?> tabs) {
        try {
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (tabs == null) return null;
            FeedFilterCounters.sawList(ROUTE, tabs.size());
            List<?> shown = withoutHidden(tabs);
            if (!hiddenByName(REELS) && showing()) shown = withReels(shown);
            shown = TabOrder.apply(shown);
            TabStart.remember(shown);
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab list", failure);
            return tabs;
        }
    }

    /**
     * A copy of [tabs] without the tabs whose switch is on, in the same order, or [tabs] itself when
     * none is on, none is in the list, or taking them would leave it empty. The built list is never
     * changed: Instagram shares it.
     */
    static List<?> withoutHidden(List<?> tabs) {
        if (!hidingAny()) return tabs;
        List<Object> shown = new ArrayList<>(tabs.size());
        List<String> taken = new ArrayList<>(4);
        for (Object tab : tabs) {
            String name = hiddenName(tab);
            if (name == null) shown.add(tab);
            else taken.add(name);
        }
        if (taken.isEmpty() || shown.isEmpty()) return tabs;
        FeedFilterCounters.removed(ROUTE, taken.size(), HIDDEN);
        for (String name : taken) HookStatus.counted(FamilyNames.REELS_TAB, countLabel(name));
        Logger.printDebug(() -> "Reels tab: took " + taken + " off a list of " + tabs.size() + " tabs");
        return shown;
    }

    /** The label a hidden tab is counted under in the report: fixed text, never read from Instagram. */
    private static String countLabel(String name) {
        switch (name) {
            case SEARCH: return "Search tab hidden";
            case CREATE: return "Create tab hidden";
            case PROFILE: return "Profile tab hidden";
            default: return "Reels tab hidden";
        }
    }

    /** The switch-off tab's name when [tab] is one the switches hide, else null. */
    @Nullable
    private static String hiddenName(@Nullable Object tab) {
        if (!(tab instanceof Enum)) return null;
        String name = ((Enum<?>) tab).name();
        return hiddenByName(name) ? name : null;
    }

    /** Whether the switch for the tab called [name] is on. Off while paused or before settings are ready. */
    static boolean hiddenByName(String name) {
        if (!Utils.settingsReady()) return false;
        switch (name) {
            case REELS: return Settings.HIDE_REELS_TAB.get();
            case SEARCH: return Settings.HIDE_SEARCH_TAB.get();
            case CREATE: return Settings.HIDE_CREATE_TAB.get();
            case PROFILE: return Settings.HIDE_PROFILE_TAB.get();
            default: return false;
        }
    }

    private static boolean hidingAny() {
        return hiddenByName(REELS) || hiddenByName(SEARCH) || hiddenByName(CREATE) || hiddenByName(PROFILE);
    }

    /**
     * A copy of [tabs] with Reels after Home when the list has no Reels, or [tabs] as it came: when
     * it has Reels already, has no Home, is empty, holds something that isn't a tab, or its enum has
     * no Reels.
     */
    @Nullable
    static List<?> withReels(List<?> tabs) {
        if (tabs.isEmpty()) return tabs;
        Object first = tabs.get(0);
        if (!(first instanceof Enum)) return tabs;
        int home = -1;
        for (int index = 0; index < tabs.size(); index++) {
            Object tab = tabs.get(index);
            if (!(tab instanceof Enum)) return tabs;
            if (isReels(tab)) return tabs;
            if (HOME.equals(((Enum<?>) tab).name())) home = index;
        }
        // Without Home there's no place the tab is known to go, so the bar stays as Instagram built it.
        if (home < 0) return tabs;
        Object reels;
        try {
            reels = reelsOf((Enum<?>) first);
        } catch (IllegalArgumentException noReels) {
            return tabs;
        }
        List<Object> shown = new ArrayList<>(tabs);
        shown.add(home + 1, reels);
        Logger.printDebug(() -> "Reels tab: put Reels on a list of " + tabs.size() + " tabs");
        return shown;
    }

    /**
     * Handed a tab Instagram is about to open, or the one it treats as home. Home in place of Reels,
     * Search or Profile while its switch hides it, otherwise the tab as it came. Create is left
     * alone: Instagram opens its camera from other places too. The one switch the Search button on
     * Home's header makes goes through as asked. Never throws.
     */
    @Nullable
    public static Object tab(@Nullable Object tab) {
        try {
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (!(tab instanceof Enum)) return tab;
            String name = ((Enum<?>) tab).name();
            if (name.equals(letThrough)) {
                letThrough = null;
                return tab;
            }
            Object landed = TabStart.landing((Enum<?>) tab);
            if (landed != null) return landed;
            if (!REELS.equals(name) && !SEARCH.equals(name) && !PROFILE.equals(name)) return tab;
            if (!hiddenByName(name)) return tab;
            Object home = home((Enum<?>) tab);
            Logger.printDebug(() -> "Reels tab: sent Home in place of " + name);
            return home;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab switch", failure);
            return tab;
        }
    }

    static boolean isReels(@Nullable Object tab) {
        return tab instanceof Enum && REELS.equals(((Enum<?>) tab).name());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object home(Enum<?> reels) {
        return Enum.valueOf((Class) reels.getDeclaringClass(), HOME);
    }

    /** Home, from [any] tab's enum. */
    static Object homeOf(Enum<?> any) {
        return home(any);
    }

    /** The tab called [name], from [any] tab's enum. Throws if the enum has none. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object named(Enum<?> any, String name) {
        return Enum.valueOf((Class) any.getDeclaringClass(), name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object reelsOf(Enum<?> any) {
        return Enum.valueOf((Class) any.getDeclaringClass(), REELS);
    }

    private static boolean showing() {
        return Utils.settingsReady() && Settings.SHOW_REELS_TAB.get();
    }
}
