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
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of Explore's sections Hide shopping takes out, and which it leaves. */
@RunWith(RobolectricTestRunner.class)
public class ExploreShoppingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like Instagram 450's Explore tile kinds, a few of them. */
    enum Kind { MEDIA, CLIPS, SHOPPING, IGTV }

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

    /** A section's content: a tile of its own, a list of tiles and a section to fall back on. */
    static final class Content {
        int span = 3;
        Tile hero;
        List<Object> tiles = new ArrayList<>();
        Section fallback;
    }

    static final class Section {
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
        Settings.HIDE_FEED_SHOPPING.save(true);
        counters = FeedFilterCounters.snapshotAndClear();
        hooks = HookStatus.snapshotAndClear();
    }

    @After
    public void restore() {
        ExploreShopping.contentOf = ExploreShopping::content;
        ExploreShopping.tile = ExploreShopping::isTile;
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

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    @Test
    public void aShopTileInItsOwnSlotTakesItsSectionOut() {
        assertNull(ExploreShopping.section(section(new Tile(Kind.SHOPPING), new Tile(Kind.MEDIA), new Tile(Kind.CLIPS))));
        assertEquals(ExploreShopping.ROUTE + ": 1 lists, 3 items, 1 removed. Last reason: SHOPPING. Removed: SHOPPING 1. "
                + "Kinds: SHOPPING 1", report());
    }

    @Test
    public void aShopTileInTheListTakesItsSectionOut() {
        assertNull(ExploreShopping.section(section(new Tile(Kind.MEDIA), new Tile(Kind.MEDIA), new Tile(Kind.SHOPPING))));
        assertNull(ExploreShopping.section(section(null, new Tile(Kind.SHOPPING))));
        String report = report();
        assertTrue(report, report.startsWith(ExploreShopping.ROUTE + ": 2 lists, 4 items, 2 removed."));
        assertTrue(report, report.endsWith("Removed: SHOPPING 2. Kinds: SHOPPING 2"));
    }

    @Test
    public void aSectionWithoutAShopTileStaysAndIsStillCounted() {
        Section posts = section(new Tile(Kind.MEDIA), new Tile(Kind.CLIPS), new Tile(Kind.IGTV));
        assertSame(posts, ExploreShopping.section(posts));
        Section empty = new Section(new Content());
        assertSame(empty, ExploreShopping.section(empty));
        Section noContent = new Section(null);
        assertSame(noContent, ExploreShopping.section(noContent));
        assertEquals(ExploreShopping.ROUTE + ": 3 lists, 3 items, 0 removed", report());
    }

    /** Only tiles are read for a kind: a post with the same kind's name, and a section inside, stay. */
    @Test
    public void onlyTheSectionsOwnTilesAreRead() {
        Section inner = section(new Tile(Kind.SHOPPING));
        Section outer = section(new Tile(Kind.MEDIA), new Post());
        outer.content.fallback = inner;
        assertSame(outer, ExploreShopping.section(outer));
        assertEquals(ExploreShopping.ROUTE + ": 1 lists, 1 items, 0 removed", report());
        assertNull("the control: the inner section on its own goes", ExploreShopping.section(inner));
    }

    @Test
    public void offPausedOrNotReadyTheSectionStaysAndTheShopTileIsCounted() {
        Section shop = section(new Tile(Kind.SHOPPING));
        Settings.HIDE_FEED_SHOPPING.save(false);
        assertSame("off", shop, ExploreShopping.section(shop));
        Settings.HIDE_FEED_SHOPPING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", shop, ExploreShopping.section(shop));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("settings not ready", shop, ExploreShopping.section(shop)));
        assertEquals(ExploreShopping.ROUTE + ": 3 lists, 3 items, 0 removed. Kinds: SHOPPING 3", report());
        assertNull("the control: on, the same section goes", ExploreShopping.section(shop));
    }

    @Test
    public void aReadThatThrowsKeepsTheSectionAndSaysSo() {
        ExploreShopping.contentOf = section -> {
            throw new IllegalStateException("a section of another shape");
        };
        Section shop = section(new Tile(Kind.SHOPPING));
        assertSame(shop, ExploreShopping.section(shop));
        String hooks = String.join("\n", HookStatus.report());
        assertTrue(hooks, hooks.contains("Explore section"));
        assertTrue(hooks, hooks.contains(IllegalStateException.class.getName()));
        assertEquals("", report());
    }

    @Test
    public void noSectionAnswersNoSection() {
        assertNull(ExploreShopping.section(null));
        assertEquals("", report());
    }

    /** The stubs as the extension ships them, before the patch writes them, keep every section. */
    @Test
    public void unwrittenStubsKeepEverySection() {
        ExploreShopping.contentOf = ExploreShopping::content;
        ExploreShopping.tile = ExploreShopping::isTile;
        Section shop = section(new Tile(Kind.SHOPPING));
        assertNull(ExploreShopping.content(shop));
        assertFalse(ExploreShopping.isTile(new Tile(Kind.SHOPPING)));
        assertSame(shop, ExploreShopping.section(shop));
        assertEquals(ExploreShopping.ROUTE + ": 1 lists, 0 items, 0 removed", report());
    }
}
