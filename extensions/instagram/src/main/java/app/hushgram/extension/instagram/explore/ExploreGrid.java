/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the Explore grid" patch.
 *
 * <p>The Search tab's grid comes from Instagram's topical Explore pages, read from the server (or
 * its copy of the last one) by one parser. The patch asks {@link #hide} as that parser finishes a
 * page, and while the switch is on it empties the page's sections and marks it the last, so the
 * grid stays empty and nothing more is fetched. The load more row state the Explore fragment hands
 * its grid adapter is noted when the adapter's built, and {@link #loadMoreRow} hides that row's
 * button on the empty grid. Search, recent searches and search results come from other requests and
 * stay. With the grid kept, Hide tall reels in Explore takes the sections built around a tall reel
 * out of each page instead ({@link #dropTall}).
 */
public final class ExploreGrid {
    /** The Explore fragments' load more row states. Their classes keep Object's identity equals. */
    private static final Map<Object, Boolean> EXPLORE_STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ExploreGrid() {
    }

    /**
     * Injected where Instagram finishes reading an Explore page, with the page's sections. Answers
     * true while Hide the Explore grid is on, and false otherwise, or when anything goes wrong.
     * With Hide tall reels in Explore on instead, it takes the page's tall reel sections out of
     * [sections] itself ({@link #dropTall}) and answers false. Never throws, and never waits for
     * the settings: before they're ready the page stays.
     */
    public static boolean hide(List<?> sections) {
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_GRID);
            if (!Utils.settingsReady()) return false;
            if (!Settings.HIDE_EXPLORE_GRID.get()) {
                if (Settings.HIDE_EXPLORE_TALL_REELS.get()) dropTall(sections);
                return false;
            }
            int count = sections == null ? 0 : sections.size();
            Logger.printDebug(() -> "Explore: emptied a page of " + count + " sections");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, "explore page", failure);
            return false;
        }
    }

    /** What the diagnostic report counts when a page loses its tall reels. */
    static final String DROPPED_TALL = "Explore tall reel sections left out";

    /**
     * The start of the names of Instagram's Explore layouts built around a tall reel, one tile wide
     * and two rows tall: ONE_BY_TWO_LEFT, ONE_BY_TWO_RIGHT, ONE_BY_TWO_CENTER, ONE_BY_TWO_ROW and
     * their forms with an ad or a fallback. The layouts are constants of an enum that keeps the
     * server's names on every build (457: "ONE_BY_TWO_LEFT" for "one_by_two_left").
     */
    static final String TALL_LAYOUT = "ONE_BY_TWO";

    /** The enum fields of each section class, found once per class. */
    private static final Map<Class<?>, List<Field>> LAYOUT_FIELDS = new ConcurrentHashMap<>();

    /**
     * Takes the sections Instagram lays out around a tall reel out of [sections], for Hide tall
     * reels in Explore (#880). Each section of an Explore page fills its own block of the grid and
     * names its layout, so taking a whole section out leaves the blocks around it as they were.
     * The few posts sharing the tall reel's block go with it. A page whose sections are all tall
     * keeps them, since Explore shows its loading placeholder for a page with nothing in it.
     * Answers how many went. Throws only what the list throws, which {@link #hide} catches.
     */
    static int dropTall(List<?> sections) throws IllegalAccessException {
        if (sections == null || sections.isEmpty()) return 0;
        int tall = 0;
        for (Object section : sections) {
            if (isTall(section)) tall++;
        }
        if (tall == 0 || tall == sections.size()) return 0;
        for (Iterator<?> each = sections.iterator(); each.hasNext(); ) {
            if (isTall(each.next())) each.remove();
        }
        int dropped = tall;
        int left = sections.size();
        HookStatus.counted(FamilyNames.EXPLORE_GRID, DROPPED_TALL);
        Logger.printDebug(() -> "Explore: left out " + dropped + " tall reel sections, " + left + " sections stay");
        return dropped;
    }

    /** Whether [section] is laid out around a tall reel: one of its enum fields holds a {@link #TALL_LAYOUT} layout. */
    static boolean isTall(Object section) throws IllegalAccessException {
        if (section == null) return false;
        for (Field field : layoutFields(section.getClass())) {
            Object layout = field.get(section);
            if (layout instanceof Enum && ((Enum<?>) layout).name().startsWith(TALL_LAYOUT)) return true;
        }
        return false;
    }

    private static List<Field> layoutFields(Class<?> type) {
        List<Field> known = LAYOUT_FIELDS.get(type);
        if (known != null) return known;
        List<Field> found = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Enum.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                found.add(field);
            }
        }
        LAYOUT_FIELDS.put(type, found);
        return found;
    }

    /**
     * Injected right after the Explore fragment builds its grid adapter, with the load more row
     * state it hands that adapter. The adapter's type also draws other grids, a location's page
     * among them, so the state is what marks Explore's row. Held weakly. Never throws.
     */
    public static void track(Object state) {
        try {
            if (state != null) EXPLORE_STATES.put(state, Boolean.TRUE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, "explore row", failure);
        }
    }

    /**
     * Injected where a load more button binds and asks whether to show itself, with the row's state
     * and Instagram's answer, non-zero for yes. Instagram shows the row, a "+" that fetches one more
     * page, on any empty grid, so for Explore's state this answers 0 while Hide the Explore grid is
     * on, and the button hides itself. Every other list gets Instagram's answer. Never throws.
     */
    public static int loadMoreRow(Object state, int show) {
        try {
            if (show == 0 || state == null || !EXPLORE_STATES.containsKey(state)) return show;
            HookStatus.invoked(FamilyNames.EXPLORE_GRID);
            if (!Utils.settingsReady() || !Settings.HIDE_EXPLORE_GRID.get()) return show;
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_GRID, "load more row", failure);
            return show;
        }
    }
}
