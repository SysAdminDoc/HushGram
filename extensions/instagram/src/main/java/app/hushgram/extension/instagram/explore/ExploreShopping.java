/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Explore's shop tiles, for the "Hide suggested posts" patch's Hide shopping switch.
 *
 * <p>Explore's grid comes in sections, each one block of the grid in one layout: a big tile beside
 * small ones, a row of tiles and so on. A section keeps its tiles in its content, one per slot and in
 * lists, and each tile has a kind. A tile Instagram reads from "shopping" is a shop's tile, with a
 * title and a few of the shop's posts as its cover, and its kind is {@link #SHOP_TILE}. The patch
 * hands {@link #page} the list of sections each Explore page parsed, and while Hide shopping is on a
 * section holding a shop tile anywhere inside it leaves the list whole. The section's other tiles go
 * with it: a layout is drawn whole, and one with a slot gone isn't a layout Instagram draws.
 *
 * <p>A section's content can hold sections of its own (nested ones, and one to fall back on), and
 * those are read too. Taking out only the inner section left its slot in the outer layout drawn
 * blank (#393), so the choice is made once, on the page's own list.
 */
public final class ExploreShopping {
    /** The kind of a shop's tile in Explore, by the name Instagram 450 gives it. */
    static final String SHOP_TILE = "SHOPPING";

    /** A constant of the enum a section keeps its layout in, by the name Instagram 453 gives it. */
    static final String LAYOUT_MARKER = "MEDIA_GRID";

    /** How the report counts a tile whose kind isn't set yet. */
    static final String NO_KIND = "no kind";

    /**
     * The diagnostic counter route: each top-level section seen as a list of its tiles (those of the
     * sections inside it included), each section's layout and each tile's kind by name, and the
     * sections taken out.
     */
    static final String ROUTE = "Explore shopping";

    /** How deep sections inside sections are read. Instagram 453 goes one level down. */
    private static final int MAX_DEPTH = 3;

    /** The fields of each content class seen, made readable. */
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    /** A section's content, as {@link #content} reads it. Tests stand in. */
    static volatile Function<Object, Object> contentOf = ExploreShopping::content;

    /** Whether an object is one of Explore's tiles, as {@link #isTile} answers it. Tests stand in. */
    static volatile Predicate<Object> tile = ExploreShopping::isTile;

    /** Whether an object is one of Explore's sections, as {@link #isSection} answers it. Tests stand in. */
    static volatile Predicate<Object> section = ExploreShopping::isSection;

    private ExploreShopping() {
    }

    /**
     * Injected at the return of Explore's page parser, with the page's sections. Answers a new list
     * without the sections holding a shop tile while Hide shopping is on, and [sections] itself when
     * none goes, or when anything goes wrong. Never throws, and never waits for the settings: before
     * they're ready every section stays.
     */
    public static List<?> page(List<?> sections) {
        if (sections == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            List<Object> kept = new ArrayList<>(sections.size());
            for (Object item : sections) {
                // Only a section is read: the content stub casts to one.
                if (item == null || !section.test(item) || !goes(item)) kept.add(item);
            }
            int removed = sections.size() - kept.size();
            if (removed == 0) return sections;
            Logger.printDebug(() -> "Explore shopping: took out " + removed + " section(s) holding a shop tile");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "Explore page", failure);
            return sections;
        }
    }

    /** Whether a top-level section goes, counting it on the way. */
    private static boolean goes(Object section) throws IllegalAccessException {
        Tiles tiles = new Tiles();
        tiles.read(section, 0);
        // Every section counts as a list of its tiles, so a report with nothing removed still shows
        // the hook ran. Its layout and its tiles' kinds are what a blank slot in someone else's grid
        // can be told from: no test account is served every layout (#433).
        FeedFilterCounters.sawList(ROUTE, tiles.count);
        String layout = FeedItemKinds.kindOf(section, LAYOUT_MARKER, FamilyNames.FEED_SUGGESTIONS);
        if (layout != null) FeedFilterCounters.sawKind(ROUTE, layout);
        if (!tiles.shop) return false;
        if (!Utils.settingsReady() || !Settings.HIDE_FEED_SHOPPING.get()) return false;
        FeedFilterCounters.removed(ROUTE, 1, SHOP_TILE);
        return true;
    }

    /**
     * The tiles in a section's content, in a slot of their own or in a list, and in the sections it
     * holds the same way: how many, and whether one is a shop's. Anything else the content holds is
     * left unread.
     */
    private static final class Tiles {
        int count;
        boolean shop;

        void read(Object section, int depth) throws IllegalAccessException {
            if (depth > MAX_DEPTH) return;
            Object content = contentOf.apply(section);
            if (content == null) return;
            for (Field field : fields(content.getClass())) {
                Object value = field.get(content);
                if (value instanceof List) {
                    for (Object element : (List<?>) value) add(element, depth);
                } else {
                    add(value, depth);
                }
            }
        }

        private void add(@Nullable Object value, int depth) throws IllegalAccessException {
            if (value == null) return;
            if (tile.test(value)) {
                count++;
                String kind = FeedItemKinds.kindOf(value, SHOP_TILE, FamilyNames.FEED_SUGGESTIONS);
                FeedFilterCounters.sawKind(ROUTE, kind == null ? NO_KIND : kind);
                shop |= SHOP_TILE.equals(kind);
            } else if (section.test(value)) {
                read(value, depth + 1);
            }
        }
    }

    private static List<Field> fields(Class<?> owner) {
        List<Field> found = FIELDS.get(owner);
        if (found == null) {
            List<Field> fields = new ArrayList<>();
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                fields.add(field);
            }
            found = Collections.unmodifiableList(fields);
            FIELDS.put(owner, found);
        }
        return found;
    }

    /**
     * The content of an Explore section: the object holding its tiles. The patch writes the body,
     * which reads the section's content field. Answers an Object.
     */
    public static Object content(Object section) {
        return null;
    }

    /**
     * Whether [value] is one of Explore's tiles. The patch writes the body, an instance-of the tile
     * class, so a post or anything else a section's content holds is never read for a kind.
     */
    public static boolean isTile(Object value) {
        return false;
    }

    /**
     * Whether [value] is one of Explore's sections, nested in another's content or kept there to fall
     * back on. The patch writes the body, an instance-of the section class.
     */
    public static boolean isSection(Object value) {
        return false;
    }
}
