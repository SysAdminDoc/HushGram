/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Which home feed items come back from the parse helper, and which are taken out. */
@RunWith(RobolectricTestRunner.class)
public class FeedReelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_FEED_REELS.save(true);
    }

    @After
    public void switchBack() {
        Settings.HIDE_FEED_REELS.resetToDefault();
    }

    /** Shaped like Instagram 449's feed item kinds, a few of them. */
    enum Kind { MEDIA, AD, CLIPS_NETEGO, IMMERSIVE_SEGUE_ITEM, VIBES_IN_FEED_UNIT, HATCH_IMMERSIVE_IN_FEED_UNIT }

    /** The item's two other enums on 449: why the feed was fetched, and where the item came from. */
    enum Fetch { COLD_START, PULL_TO_REFRESH }

    enum Origin { NETWORK, CACHED }

    /** A feed item with its three enum fields, the kind not first among them. */
    static final class Item {
        Fetch fetch = Fetch.COLD_START;
        Origin origin = Origin.NETWORK;
        Kind kind;
        String id = "3719";

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    static final class NoKinds {
        String id = "3719";
    }

    @Test
    public void everyReelsUnitIsTakenOut() {
        for (Kind kind : new Kind[] {Kind.CLIPS_NETEGO, Kind.IMMERSIVE_SEGUE_ITEM, Kind.VIBES_IN_FEED_UNIT,
                Kind.HATCH_IMMERSIVE_IN_FEED_UNIT}) {
            assertNull(kind.name(), FeedReels.filter(new Item(kind)));
        }
    }

    /** A reel someone you follow posts is a MEDIA item. */
    @Test
    public void postsAndAdsStay() {
        Item post = new Item(Kind.MEDIA);
        assertSame(post, FeedReels.filter(post));
        Item ad = new Item(Kind.AD);
        assertSame(ad, FeedReels.filter(ad));
    }

    @Test
    public void withTheSwitchOffTheRowStays() {
        Settings.HIDE_FEED_REELS.save(false);
        try {
            Item row = new Item(Kind.CLIPS_NETEGO);
            assertSame(row, FeedReels.filter(row));
        } finally {
            Settings.HIDE_FEED_REELS.save(true);
        }
    }

    @Test
    public void anItemWithoutAKindStays() {
        Item unset = new Item(null);
        assertSame(unset, FeedReels.filter(unset));
        NoKinds other = new NoKinds();
        assertSame(other, FeedReels.filter(other));
        assertNull(FeedReels.filter(null));
    }
}
