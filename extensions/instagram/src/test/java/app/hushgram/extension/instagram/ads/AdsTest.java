/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

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
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide ads' answer to the ad-insert method (audit A04). Only an on switch, read with settings ready
 * and HushGram not paused, hides an ad. Every other state leaves Instagram's own insert running.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AdsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private HookStatus.Snapshot saved;

    @Before
    public void setUp() {
        saved = HookStatus.snapshotAndClear();
        PauseForTests.resume();
        Settings.HIDE_ADS.save(true);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        HookStatus.restore(saved);
    }

    @Test
    public void anOnSwitchHidesTheAd() {
        assertTrue(Ads.hide());
        assertFalse("the call wasn't counted", HookStatus.snapshot().isEmpty());
    }

    @Test
    public void anOffSwitchLetsInstagramInsertIt() {
        Settings.HIDE_ADS.save(false);
        assertFalse(Ads.hide());
    }

    @Test
    public void pauseLetsInstagramInsertIt() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Ads.hide());
    }

    @Test
    public void unreadySettingsLetInstagramInsertIt() {
        SettingsContextRule.withoutContext(() -> assertFalse("no context", Ads.hide()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", Ads.hide()));
        assertTrue("ready again", Ads.hide());
    }

    /** Feed item kinds, named the way Instagram's kind enum names them. */
    private enum Kind { MEDIA, CLIPS_NETEGO, INTENT_AWARE_AD_PIVOT, STAND_ALONE_MULTI_AD_PIVOT }

    /** A feed item as the parse helper answers it, keeping its kind in an enum field the way Instagram's does. */
    private static final class Item {
        final Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** A post's feed item. */
    private static final Object ITEM = new Item(Kind.MEDIA);

    /** Says every item it's asked about is sponsored (or isn't), and counts the asks. */
    private static final class Sponsored implements ToIntFunction<Object> {
        final int answer;
        int asked;

        Sponsored() {
            this(1);
        }

        Sponsored(int answer) {
            this.answer = answer;
        }

        @Override
        public int applyAsInt(Object item) {
            asked++;
            return answer;
        }
    }

    /**
     * A row of ads a contextual ad response filled and a stand-alone multi-ad pivot keep no post
     * where the sponsored check reads. With the switch on each goes by its kind, without reading a
     * post, and Diagnostics counts it under its kind on its own line and on Hide ads' line.
     */
    @Test
    public void anAdPivotInAFeedsPageIsTakenOut() {
        FeedFilterCounters.snapshotAndClear();
        Sponsored notSponsored = new Sponsored(0);
        assertNull("intent aware", Ads.feedItem(new Item(Kind.INTENT_AWARE_AD_PIVOT), notSponsored));
        assertNull("stand-alone", Ads.feedItem(new Item(Kind.STAND_ALONE_MULTI_AD_PIVOT), notSponsored));
        assertEquals("a post was read", 0, notSponsored.asked);
        String route = String.join("\n", FeedFilterCounters.report());
        assertTrue(route, route.contains(Ads.ROUTE + ": "));
        assertTrue(route, route.contains("Removed: INTENT_AWARE_AD_PIVOT 1, STAND_ALONE_MULTI_AD_PIVOT 1"));
        String family = String.join("\n", HookStatus.report());
        assertTrue(family, family.contains(Ads.UNITS_REMOVED + " 2"));
        assertFalse(family, family.contains(Ads.REMOVED));
    }

    /** Another kind of unit without ad details stays: a row of suggested reels isn't an ad. */
    @Test
    public void anotherUnitWithoutAdDetailsStays() {
        Object reels = new Item(Kind.CLIPS_NETEGO);
        assertSame(reels, Ads.feedItem(reels, new Sponsored(0)));
    }

    /** Off, paused or not ready, an ad pivot stays where Instagram put it. */
    @Test
    public void offPausedOrUnreadyKeepsAnAdPivot() {
        Object pivot = new Item(Kind.INTENT_AWARE_AD_PIVOT);
        Settings.HIDE_ADS.save(false);
        assertSame("off", pivot, Ads.feedItem(pivot, new Sponsored(0)));
        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", pivot, Ads.feedItem(pivot, new Sponsored(0)));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("no context", pivot, Ads.feedItem(pivot, new Sponsored(0))));
        assertNull("on and ready again", Ads.feedItem(pivot, new Sponsored(0)));
    }

    /** The kinds the filter drops are the two the patch checks the item's kind enum names. */
    @Test
    public void theAdUnitsAreTheTwoPivots() {
        assertEquals(new HashSet<>(Arrays.asList("INTENT_AWARE_AD_PIVOT", "STAND_ALONE_MULTI_AD_PIVOT")), Ads.AD_UNITS);
    }

    /**
     * Older Posts' page (S25, 450) came with sponsored posts the ad insert never saw. With the
     * switch on, an item whose post carries Instagram's ad details goes, and Diagnostics counts it on
     * its own line and on Hide ads' line.
     */
    @Test
    public void aSponsoredPostInAFeedsPageIsTakenOut() {
        FeedFilterCounters.snapshotAndClear();
        assertNull(Ads.feedItem(ITEM, item -> 1));
        String route = String.join("\n", FeedFilterCounters.report());
        assertTrue(route, route.contains(Ads.ROUTE + ": "));
        assertTrue(route, route.contains("Removed: " + Ads.SPONSORED + " 1"));
        String family = String.join("\n", HookStatus.report());
        assertTrue(family, family.contains(Ads.REMOVED + " 1"));
    }

    @Test
    public void aPostWithoutAdDetailsStays() {
        FeedFilterCounters.snapshotAndClear();
        assertSame(ITEM, Ads.feedItem(ITEM, item -> 0));
        String route = String.join("\n", FeedFilterCounters.report());
        assertTrue(route, route.contains(Ads.NOT_SPONSORED + " 1"));
        assertFalse(route, route.contains("Removed"));
    }

    /** Off, paused or not ready, the page is Instagram's own, and the post isn't even read. */
    @Test
    public void offPausedOrUnreadyKeepsEveryItem() {
        Sponsored sponsored = new Sponsored();
        Settings.HIDE_ADS.save(false);
        assertSame("off", ITEM, Ads.feedItem(ITEM, sponsored));
        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", ITEM, Ads.feedItem(ITEM, sponsored));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("no context", ITEM, Ads.feedItem(ITEM, sponsored)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame("pause undecided", ITEM, Ads.feedItem(ITEM, sponsored)));
        assertEquals("the post was read", 0, sponsored.asked);
        assertNull("on and ready again", Ads.feedItem(ITEM, sponsored));
    }

    /** A read that throws keeps the item and says where on Hide ads' line. */
    @Test
    public void aReadThatThrowsKeepsTheItem() {
        assertSame(ITEM, Ads.feedItem(ITEM, item -> {
            throw new IllegalStateException("moved");
        }));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("feed item"));
    }

    /** An item another filter already took out stays out, uncounted. */
    @Test
    public void nullStaysNull() {
        Sponsored sponsored = new Sponsored();
        assertNull(Ads.feedItem(null, sponsored));
        assertEquals(0, sponsored.asked);
    }

    /** #415: with the switch on the comments sheet is built with its ad off, and Diagnostics counts it. */
    @Test
    public void anOnSwitchTurnsTheCommentsSheetsAdOff() {
        assertEquals(0, Ads.commentAds(1));
        String family = String.join("\n", HookStatus.report());
        assertTrue(family, family.contains(Ads.COMMENT_ADS_OFF + " 1"));
    }

    /** Off, paused or unready, the sheet keeps what Instagram asked for, and a sheet without an ad stays without one. */
    @Test
    public void otherwiseTheCommentsSheetKeepsInstagramsAnswer() {
        assertEquals("no ad asked for", 0, Ads.commentAds(0));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, Ads.commentAds(1));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("no context", 1, Ads.commentAds(1)));
        Settings.HIDE_ADS.save(false);
        assertEquals("off", 1, Ads.commentAds(1));
        assertFalse(String.join("\n", HookStatus.report()).contains(Ads.COMMENT_ADS_OFF));
    }

    /** As built, before the patch writes the stub, nothing is sponsored and every item stays. */
    @Test
    public void theUnwrittenStubKeepsEveryItem() {
        assertEquals(0, Ads.sponsored(ITEM));
        assertSame(ITEM, Ads.feedItem(ITEM));
    }
}
