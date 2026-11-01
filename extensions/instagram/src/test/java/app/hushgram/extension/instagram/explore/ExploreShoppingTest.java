/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of an Explore page's sections Hide shopping takes out, and which it leaves. */
@RunWith(RobolectricTestRunner.class)
public class ExploreShoppingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like Instagram 450's Explore tile kinds, a few of them. */
    enum Kind { MEDIA, CLIPS, SHOPPING, IGTV }

    /** Shaped like Instagram 453's Explore section layouts, a few of them. */
    enum Layout { MEDIA_GRID, CARRERA_ALTERNATING_AUTOPLAY }

    /** Another enum a tile keeps, ahead of its kind. */
    enum Size { ONE_BY_ONE, TWO_BY_TWO }

    /** One of Explore's tiles. */
    static final class Tile {
        Size size = Size.ONE_BY_ONE;
        Kind kind;

        Tile(Kind kind) {
            this.kind = kind;
        }
    }

    /** Not a tile, though it has a kind named the shop tile's. The patch's instance-of turns it away. */
    static final class Post {
        Kind kind = Kind.SHOPPING;
    }

    /**
     * A section's content, shaped like 453's: a tile of its own, a list of tiles, a list of sections
     * nested in it and a section to fall back on.
     */
    static final class Content {
        int span = 3;
        Tile hero;
        List<Object> tiles = new ArrayList<>();
        List<Object> nested = new ArrayList<>();
        Section fallback;
    }

    static final class Section {
        Layout layout = Layout.MEDIA_GRID;
        final Content content;

        Section(Content content) {
            this.content = content;
        }
    }

    private FeedFilterCounters.Snapshot counters;
    private HookStatus.Snapshot hooks;

    @Before
    public void standIn() {
        ExploreShopping.contentOf = section -> ((Section) section).content;
        ExploreShopping.tile = value -> value instanceof Tile;
        ExploreShopping.section = value -> value instanceof Section;
        Settings.HIDE_FEED_SHOPPING.save(true);
        counters = FeedFilterCounters.snapshotAndClear();
        hooks = HookStatus.snapshotAndClear();
    }

    @After
    public void restore() {
        ExploreShopping.contentOf = ExploreShopping::content;
        ExploreShopping.tile = ExploreShopping::isTile;
        ExploreShopping.section = ExploreShopping::isSection;
        PauseForTests.resume();
        Settings.HIDE_FEED_SHOPPING.resetToDefault();
        FeedFilterCounters.restore(counters);
        HookStatus.restore(hooks);
    }

    private static Section section(Tile hero, Object... tiles) {
        Content content = new Content();
        content.hero = hero;
        content.tiles.addAll(Arrays.asList(tiles));
        return new Section(content);
    }

    private static List<Object> page(Object... sections) {
        return new ArrayList<>(Arrays.asList(sections));
    }

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    @Test
    public void aShopTileInItsOwnSlotTakesItsSectionOut() {
        Section posts = section(new Tile(Kind.MEDIA), new Tile(Kind.CLIPS));
        Section shop = section(new Tile(Kind.SHOPPING), new Tile(Kind.MEDIA), new Tile(Kind.CLIPS));
        assertEquals(Collections.singletonList(posts), ExploreShopping.page(page(shop, posts)));
        assertEquals(ExploreShopping.ROUTE + ": 2 lists, 5 items, 1 removed. Last reason: SHOPPING. Removed: SHOPPING 1. "
                + "Kinds: CLIPS 2, MEDIA 2, MEDIA_GRID 2, SHOPPING 1", report());
    }

    @Test
    public void aShopTileInTheListTakesItsSectionOut() {
        Section posts = section(new Tile(Kind.IGTV));
        List<?> kept = ExploreShopping.page(page(
                section(new Tile(Kind.MEDIA), new Tile(Kind.MEDIA), new Tile(Kind.SHOPPING)),
                posts,
                section(null, new Tile(Kind.SHOPPING))));
        assertEquals(Collections.singletonList(posts), kept);
        String report = report();
        assertTrue(report, report.startsWith(ExploreShopping.ROUTE + ": 3 lists, 5 items, 2 removed."));
        assertTrue(report, report.endsWith("Removed: SHOPPING 2. Kinds: MEDIA_GRID 3, MEDIA 2, SHOPPING 2, IGTV 1"));
    }

    /**
     * #393: a shop tile in a section nested inside another takes the whole outer section out, so
     * the outer layout is never drawn with the nested slot gone blank.
     */
    @Test
    public void aShopTileInANestedSectionTakesTheOuterSectionOut() {
        Section outer = section(new Tile(Kind.MEDIA), new Tile(Kind.CLIPS));
        outer.content.nested.add(section(new Tile(Kind.MEDIA)));
        outer.content.nested.add(section(null, new Tile(Kind.MEDIA), new Tile(Kind.SHOPPING)));
        Section posts = section(new Tile(Kind.MEDIA));
        assertEquals(Collections.singletonList(posts), ExploreShopping.page(page(outer, posts)));
        assertEquals(ExploreShopping.ROUTE + ": 2 lists, 6 items, 1 removed. Last reason: SHOPPING. Removed: SHOPPING 1. "
                + "Kinds: MEDIA 4, MEDIA_GRID 2, CLIPS 1, SHOPPING 1", report());
    }

    /** A shop tile in the section kept to fall back on takes its outer section out too. */
    @Test
    public void aShopTileInTheFallbackTakesTheOuterSectionOut() {
        Section outer = section(new Tile(Kind.MEDIA));
        outer.content.fallback = section(new Tile(Kind.SHOPPING));
        assertEquals(Collections.emptyList(), ExploreShopping.page(page(outer)));
        assertEquals(ExploreShopping.ROUTE + ": 1 lists, 2 items, 1 removed. Last reason: SHOPPING. Removed: SHOPPING 1. "
                + "Kinds: MEDIA 1, MEDIA_GRID 1, SHOPPING 1", report());
    }

    /** With no section going, the page keeps the very list it parsed. */
    @Test
    public void aPageWithoutAShopTileKeepsItsListAndIsStillCounted() {
        Section posts = section(new Tile(Kind.MEDIA), new Tile(Kind.CLIPS), new Tile(Kind.IGTV));
        List<Object> sections = page(posts, new Section(new Content()), new Section(null), null);
        assertSame(sections, ExploreShopping.page(sections));
        assertEquals(4, sections.size());
        assertEquals(ExploreShopping.ROUTE + ": 3 lists, 3 items, 0 removed. Kinds: MEDIA_GRID 3, CLIPS 1, IGTV 1, MEDIA 1", report());
    }

    /** Only tiles are read for a kind: a post with the same kind's name stays, and so does anything not a section. */
    @Test
    public void onlyTilesAreReadForAKind() {
        Section outer = section(new Tile(Kind.MEDIA), new Post());
        Object stranger = new Post();
        List<Object> sections = page(outer, stranger);
        assertSame(sections, ExploreShopping.page(sections));
        assertEquals(ExploreShopping.ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: MEDIA 1, MEDIA_GRID 1", report());
    }

    /** Sections nested deeper than Instagram nests them are left unread, so a loop can't run away. */
    @Test
    public void nestingPastTheLimitIsLeftUnread() {
        Section deepest = section(new Tile(Kind.SHOPPING));
        Section inner = deepest;
        for (int level = 0; level < 5; level++) {
            Section holder = section(null);
            holder.content.nested.add(inner);
            inner = holder;
        }
        List<Object> sections = page(inner);
        assertSame(sections, ExploreShopping.page(sections));
        Section looped = section(new Tile(Kind.MEDIA));
        looped.content.nested.add(looped);
        List<Object> loop = page(looped);
        assertSame(loop, ExploreShopping.page(loop));
        assertEquals(ExploreShopping.ROUTE + ": 2 lists, 4 items, 0 removed. Kinds: MEDIA 4, MEDIA_GRID 2", report());
    }

    @Test
    public void offPausedOrNotReadyTheSectionStaysAndTheShopTileIsCounted() {
        List<Object> shop = page(section(new Tile(Kind.SHOPPING)));
        Settings.HIDE_FEED_SHOPPING.save(false);
        assertSame("off", shop, ExploreShopping.page(shop));
        Settings.HIDE_FEED_SHOPPING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", shop, ExploreShopping.page(shop));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("settings not ready", shop, ExploreShopping.page(shop)));
        assertEquals(ExploreShopping.ROUTE + ": 3 lists, 3 items, 0 removed. Kinds: MEDIA_GRID 3, SHOPPING 3", report());
        assertEquals("the control: on, the same section goes", Collections.emptyList(), ExploreShopping.page(shop));
    }

    /** #433: a report names each section's layout and each tile's kind, set or not. */
    @Test
    public void aReportNamesEachLayoutAndEachKind() {
        Section wide = section(new Tile(Kind.CLIPS), new Tile(null));
        wide.layout = Layout.CARRERA_ALTERNATING_AUTOPLAY;
        List<Object> sections = page(wide, section(new Tile(Kind.MEDIA)));
        assertSame(sections, ExploreShopping.page(sections));
        assertEquals(ExploreShopping.ROUTE + ": 2 lists, 3 items, 0 removed. "
                + "Kinds: CARRERA_ALTERNATING_AUTOPLAY 1, CLIPS 1, MEDIA 1, MEDIA_GRID 1, no kind 1", report());
    }

    @Test
    public void aReadThatThrowsKeepsThePageAndSaysSo() {
        ExploreShopping.contentOf = section -> {
            throw new IllegalStateException("a section of another shape");
        };
        List<Object> shop = page(section(new Tile(Kind.SHOPPING)));
        assertSame(shop, ExploreShopping.page(shop));
        String hooks = String.join("\n", HookStatus.report());
        assertTrue(hooks, hooks.contains("Explore page"));
        assertTrue(hooks, hooks.contains(IllegalStateException.class.getName()));
        assertEquals("", report());
    }

    @Test
    public void noListAnswersNoList() {
        assertNull(ExploreShopping.page(null));
        assertEquals("", report());
    }

    /** The stubs as the extension ships them, before the patch writes them, keep every section. */
    @Test
    public void unwrittenStubsKeepEverySection() {
        ExploreShopping.contentOf = ExploreShopping::content;
        ExploreShopping.tile = ExploreShopping::isTile;
        ExploreShopping.section = ExploreShopping::isSection;
        Section shop = section(new Tile(Kind.SHOPPING));
        assertNull(ExploreShopping.content(shop));
        assertFalse(ExploreShopping.isTile(new Tile(Kind.SHOPPING)));
        assertFalse(ExploreShopping.isSection(shop));
        List<Object> sections = page(shop);
        assertSame(sections, ExploreShopping.page(sections));
        assertEquals("", report());
    }
}
