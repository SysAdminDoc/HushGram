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
import java.util.Set;
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
 * hands {@link #section} every section Explore's section parser builds, and while Hide shopping is
 * on a section holding a shop tile comes back as null, which every reader of that parser skips, the
 * way it skips a section that didn't parse. The section's other tiles go with it: a layout is drawn
 * whole, and one with a slot gone isn't a layout Instagram draws.
 *
 * <p>A section inside a section (a nested one, or the one to fall back on) is built by the same
 * parser first, so it's already been asked about by the time the section holding it is.
 */
public final class ExploreShopping {
    /** The kind of a shop's tile in Explore, by the name Instagram 450 gives it. */
    static final String SHOP_TILE = "SHOPPING";

    private static final Set<String> SHOP_KINDS = Collections.singleton(SHOP_TILE);

    /**
     * The diagnostic counter route: each section seen as a list of its tiles, the ones holding a shop
     * tile as that kind, and the ones taken out.
     */
    static final String ROUTE = "Explore shopping";

    /** The fields of each content class seen, made readable. */
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    /** A section's content, as {@link #content} reads it. Tests stand in. */
    static volatile Function<Object, Object> contentOf = ExploreShopping::content;

    /** Whether an object is one of Explore's tiles, as {@link #isTile} answers it. Tests stand in. */
    static volatile Predicate<Object> tile = ExploreShopping::isTile;

    private ExploreShopping() {
    }

    /**
     * Injected at the return of Explore's section parser, with the section it built. Answers null
     * for a section holding a shop tile while Hide shopping is on, and [section] itself otherwise, or
     * when anything goes wrong. Never throws, and never waits for the settings: before they're ready
     * the section stays.
     */
    public static Object section(Object section) {
        if (section == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            Tiles tiles = new Tiles();
            tiles.read(contentOf.apply(section));
            // Every section counts as a list of its tiles, so a report with nothing removed still
            // shows the hook ran.
            FeedFilterCounters.sawList(ROUTE, tiles.count);
            if (!tiles.shop) return section;
            FeedFilterCounters.sawKind(ROUTE, SHOP_TILE);
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_SHOPPING.get()) return section;
            FeedFilterCounters.removed(ROUTE, 1, SHOP_TILE);
            Logger.printDebug(() -> "Explore shopping: took out a section holding a shop tile");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "Explore section", failure);
            return section;
        }
    }

    /**
     * The tiles in a section's content, in a slot of their own or in a list: how many, and whether
     * one is a shop's. Anything else the content holds is left unread, a section inside it among them.
     */
    private static final class Tiles {
        int count;
        boolean shop;

        void read(@Nullable Object content) throws IllegalAccessException {
            if (content == null) return;
            for (Field field : fields(content.getClass())) {
                Object value = field.get(content);
                if (value instanceof List) {
                    for (Object element : (List<?>) value) add(element);
                } else {
                    add(value);
                }
            }
        }

        private void add(@Nullable Object value) throws IllegalAccessException {
            if (value == null || !tile.test(value)) return;
            count++;
            if (!shop) shop = FeedItemKinds.kindIn(value, SHOP_KINDS, FamilyNames.FEED_SUGGESTIONS) != null;
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
     * class, so a post, a section or anything else a section's content holds is never read for a
     * kind.
     */
    public static boolean isTile(Object value) {
        return false;
    }
}
