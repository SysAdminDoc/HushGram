/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Also in search, under "Hide reels by caption words" (#807).
 *
 * <p>The Search tab's Explore grid and the grid of posts under a search's results are both read as
 * sections, each one row of the grid with its few posts, by the same section reader. The patch hands
 * {@link #sections} each page's section list as its parser hands the page back. A keyword search's
 * For you grid comes over GraphQL instead and never meets that parser, so the patch also hands over
 * each of its pages' list of blocks, each block holding its own section and posts, before Instagram
 * numbers them. While Also in
 * search is on this takes out, in place, every section or row holding a post whose caption holds a
 * word from the list, or whose author the list names with an {@code @name}. A row goes whole, the
 * posts beside the matching one with it, since a row with a hole in it isn't a layout Instagram draws.
 *
 * <p>A section's posts are found by walking its fields: a section holds its layout, the layout its
 * items, and each item Instagram's own {@code com.instagram.feed.media.Media}, a class that keeps its
 * name. The walk follows only Instagram's classes and collections, goes {@link #DEPTH} steps down at
 * most and looks at {@link #VISITS} objects at most per section, and stops at each post, at users and
 * at the signed-in session.
 *
 * <p>A page where every section would go keeps every one, so a search for a word on the list still
 * shows its results rather than an empty grid that keeps asking for more. Off, paused, with an empty
 * list, before the settings are read, or when anything throws, the page stays as it came.
 */
public final class CaptionWordSearch {
    /** The diagnostic counter route: the pages seen, and the sections taken out of them. */
    static final String ROUTE = "Caption word search";

    /** The class of Instagram's posts, a name Redex keeps. */
    static final String MEDIA = "com.instagram.feed.media.Media";

    /** The class of Instagram's users, a name Redex keeps; the walk never goes into one. */
    static final String USER = "com.instagram.user.model.User";

    /**
     * The signed-in session, a name Redex keeps. A keyword search's rows can hold it, and it leads to
     * everything the app has open, so the walk never goes into it either.
     */
    static final String SESSION = "com.instagram.common.session.UserSession";

    /** How many steps down from a section the walk goes, a collection's items counting as a step. */
    static final int DEPTH = 6;

    /** How many objects the walk looks at in one section at most. */
    static final int VISITS = 400;

    /** Instagram's own posts, by their kept class name. */
    private static final Predicate<Class<?>> POSTS = type -> MEDIA.equals(type.getName());

    /** Instagram's own classes: Redex's renamed ones in X, and the kept ones under com.instagram, users and the session left out. */
    private static final Predicate<Class<?>> INSTAGRAMS = type -> follows(type.getName());

    /** Whether the walk goes into a class of this name. */
    static boolean follows(String name) {
        return (name.startsWith("X.") || name.startsWith("com.instagram.")) && !USER.equals(name) && !SESSION.equals(name);
    }

    private CaptionWordSearch() {
    }

    /**
     * Injected where the Explore page's parser and the search results grid's parser hand their page
     * back, with the page's section list, and where a keyword search's page of blocks is numbered,
     * with that list. Takes the sections or blocks holding a blocked post out of it, in place, while
     * Also in search is on. Never throws.
     */
    public static void sections(List<?> sections) {
        sections(sections, CaptionWordReels.PATCHED, CaptionWordReels::searchWordsWhenOn, POSTS, INSTAGRAMS);
    }

    /** How many sections went from [sections], read with [reader] against [words]. */
    static int sections(List<?> sections, CaptionWordReels.Reader reader, Supplier<List<String>> words,
                        Predicate<Class<?>> posts, Predicate<Class<?>> followed) {
        if (sections == null) return 0;
        try {
            HookStatus.invoked(FamilyNames.CAPTION_WORD_REELS);
            int size = sections.size();
            if (size == 0) return 0;
            List<String> blocked = words.get();
            if (blocked.isEmpty()) return 0;
            FeedFilterCounters.sawList(ROUTE, size);
            boolean accounts = CaptionWordReels.namesAccounts(blocked);

            boolean[] goes = new boolean[size];
            List<String> authors = new ArrayList<>();
            List<String> captions = new ArrayList<>();
            int matched = 0;
            int at = 0;
            for (Object section : sections) {
                if (at >= size) return 0;
                for (Object media : medias(section, posts, followed)) {
                    String caption = reader.caption(media);
                    String author = accounts ? reader.author(media) : null;
                    if (CaptionWordReels.holdsAny(caption, blocked)
                            || accounts && CaptionWordReels.postedByAny(author, blocked)) {
                        goes[at] = true;
                        authors.add(author);
                        captions.add(caption);
                    }
                }
                if (goes[at++]) matched++;
            }
            // A list that changed size while it was read isn't one this can take from.
            if (at != size || matched == 0) return 0;
            if (matched == size) {
                FeedFilterCounters.sawKind(ROUTE, CaptionWordReels.PAGE_KEPT);
                Logger.printDebug(() -> "Caption word search: kept a page of " + size + " where every section matched");
                return 0;
            }

            Iterator<?> each = sections.iterator();
            at = 0;
            while (each.hasNext()) {
                each.next();
                if (goes[at++]) each.remove();
            }
            for (int i = 0; i < authors.size(); i++) CaptionWordStats.hidden(authors.get(i), captions.get(i));
            FeedFilterCounters.removed(ROUTE, matched, CaptionWordReels.BLOCKED);
            int removed = matched;
            Logger.printDebug(() -> "Caption word search: took " + removed + " of " + size + " sections out");
            return matched;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAPTION_WORD_REELS, "search sections", failure);
            return 0;
        }
    }

    /**
     * The posts in [root]: the objects [posts] picks, found through the fields of the classes
     * [followed] picks and the items of collections and arrays, at most [DEPTH] steps down and
     * [VISITS] objects in all. Each object is looked at once.
     */
    static List<Object> medias(Object root, Predicate<Class<?>> posts, Predicate<Class<?>> followed) {
        List<Object> found = new ArrayList<>();
        walk(root, 0, new IdentityHashMap<>(), found, posts, followed);
        return found;
    }

    private static void walk(Object value, int depth, Map<Object, Boolean> seen, List<Object> found,
                             Predicate<Class<?>> posts, Predicate<Class<?>> followed) {
        if (value == null || depth > DEPTH || seen.size() >= VISITS || seen.put(value, Boolean.TRUE) != null) return;
        Class<?> type = value.getClass();
        if (posts.test(type)) {
            found.add(value);
            return;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (seen.size() >= VISITS) return;
                walk(item, depth + 1, seen, found, posts, followed);
            }
            return;
        }
        if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                if (seen.size() >= VISITS) return;
                walk(item, depth + 1, seen, found, posts, followed);
            }
            return;
        }
        if (!followed.test(type)) return;
        for (Class<?> level = type; level != null && followed.test(level); level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !worthReading(field.getType())) continue;
                Object next;
                try {
                    field.setAccessible(true);
                    next = field.get(value);
                } catch (IllegalAccessException | RuntimeException unreadable) {
                    // A field the runtime won't open holds nothing this can read; the rest still count.
                    continue;
                }
                walk(next, depth + 1, seen, found, posts, followed);
                if (seen.size() >= VISITS) return;
            }
        }
    }

    /** Whether a field of [type] can hold a post or something on the way to one. */
    private static boolean worthReading(Class<?> type) {
        return !type.isPrimitive() && type != String.class && type != Boolean.class && type != Character.class
                && !Number.class.isAssignableFrom(type) && !type.isEnum();
    }
}
