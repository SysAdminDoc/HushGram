/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Reels suggestion hooks answer for each item, and that they keep the item when they can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsSuggestionsTest {
    /** Named like Instagram 450's clips item kinds, which is all the hook goes by. */
    enum Kind {
        ORGANIC, AD, AD_PREVIEW, MULTI_ADS, NETEGO, TYA_IN_REELS, QPMIDCARD, SCROLL_PIVOT, SUGGESTED_USERS,
        CREATORS_YOU_MAY_FOLLOW, NETEGO_SUGGESTED_USERS, NETEGO_SUGGESTED_CREATORS
    }

    /** A clips item: one enum field holding its kind. */
    static final class Item {
        final Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** An item of a class with no kind to read. */
    static final class Kindless {
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_REELS_SUGGESTIONS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_REELS_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, every card of accounts or creators to follow is answered null and counted by its kind. */
    @Test
    public void withTheSwitchOnSuggestedAccountsAreTakenOut() {
        for (Kind kind : new Kind[]{Kind.SUGGESTED_USERS, Kind.CREATORS_YOU_MAY_FOLLOW,
                Kind.NETEGO_SUGGESTED_USERS, Kind.NETEGO_SUGGESTED_CREATORS}) {
            assertNull(kind.name(), ReelsSuggestions.filter(new Item(kind)));
        }
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ReelsSuggestions.ROUTE));
        assertTrue(report, report.contains("4 removed"));
        assertTrue(report, report.contains("CREATORS_YOU_MAY_FOLLOW"));
    }

    /**
     * On, reels, ads, Your algorithm and other netego units come back as they came. None counts as a
     * suggestion; the units that aren't reels or ads are named on the kept line, except a netego
     * item, which the netego hook names by its type at the same return.
     */
    @Test
    public void withTheSwitchOnEverythingElseStays() {
        for (Kind kind : new Kind[]{Kind.ORGANIC, Kind.AD, Kind.NETEGO, Kind.TYA_IN_REELS}) {
            Item item = new Item(kind);
            assertSame(kind.name(), item, ReelsSuggestions.filter(item));
        }
        assertNull(ReelsSuggestions.filter(null));
        List<String> report = FeedFilterCounters.report();
        assertEquals(report.toString(), 1, report.size());
        assertEquals(ReelsSuggestions.KEPT_ROUTE + ": 0 lists, 0 items, 0 removed. Kinds: TYA_IN_REELS 1",
                report.get(0));
    }

    /**
     * A unit the switch keeps is named in Diagnostics, by its kind or its netego type, so a screen of
     * suggestions that still shows (#167) says what it is. Reels and ads are left out of that line.
     * Named whether the switch is on or off.
     */
    @Test
    public void aKeptUnitIsNamedInTheReport() {
        for (Kind kind : new Kind[]{Kind.QPMIDCARD, Kind.SCROLL_PIVOT, Kind.AD_PREVIEW, Kind.MULTI_ADS, Kind.ORGANIC}) {
            Item item = new Item(kind);
            assertSame(kind.name(), item, ReelsSuggestions.filter(item));
        }
        Item netego = new Item(Kind.NETEGO);
        assertSame(netego, ReelsSuggestions.netego(netego, "suggested_users"));
        assertSame(netego, ReelsSuggestions.netego(netego, null));
        Settings.HIDE_REELS_SUGGESTIONS.save(false);
        assertSame(netego, ReelsSuggestions.netego(netego, "suggested_users"));
        assertSame(netego, ReelsSuggestions.filter(netego));
        Item scrollPivot = new Item(Kind.SCROLL_PIVOT);
        assertSame(scrollPivot, ReelsSuggestions.filter(scrollPivot));

        List<String> report = FeedFilterCounters.report();
        assertEquals(report.toString(), 1, report.size());
        assertEquals(ReelsSuggestions.KEPT_ROUTE + ": 0 lists, 0 items, 0 removed. Kinds: SCROLL_PIVOT 2, "
                + "netego suggested_users 2, QPMIDCARD 1, netego untyped 1", report.get(0));
    }

    /** On, a netego unit of friends or creators to follow is answered null, and any other type stays. */
    @Test
    public void netegoUnitsGoByTheirType() {
        assertNull(ReelsSuggestions.netego(new Item(Kind.NETEGO), "friend_su_in_reels"));
        assertNull(ReelsSuggestions.netego(new Item(Kind.NETEGO), "creators_in_reels"));

        Item yourAlgorithm = new Item(Kind.NETEGO);
        assertSame(yourAlgorithm, ReelsSuggestions.netego(yourAlgorithm, "tya_in_reels"));
        Item unknown = new Item(Kind.NETEGO);
        assertSame(unknown, ReelsSuggestions.netego(unknown, "friend_su_in_reels_v2"));
        Item untyped = new Item(Kind.NETEGO);
        assertSame(untyped, ReelsSuggestions.netego(untyped, null));
        assertNull(ReelsSuggestions.netego(null, "friend_su_in_reels"));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("2 removed"));
        assertTrue(report, report.contains("friend_su_in_reels"));
    }

    /** Off, paused or before the settings are read, every item comes back, though the hooks still count it. */
    @Test
    public void offPausedAndUnreadyKeepEveryItem() {
        Item suggested = new Item(Kind.SUGGESTED_USERS);
        Item friends = new Item(Kind.NETEGO);

        Settings.HIDE_REELS_SUGGESTIONS.save(false);
        assertSame(suggested, ReelsSuggestions.filter(suggested));
        assertSame(friends, ReelsSuggestions.netego(friends, "friend_su_in_reels"));
        Settings.HIDE_REELS_SUGGESTIONS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(suggested, ReelsSuggestions.filter(suggested));
        assertSame(friends, ReelsSuggestions.netego(friends, "friend_su_in_reels"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertSame(suggested, ReelsSuggestions.filter(suggested));
            assertSame(friends, ReelsSuggestions.netego(friends, "friend_su_in_reels"));
        });

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(report, report.contains("SUGGESTED_USERS 3"));
    }

    /**
     * On, the friends tab's page of people to follow (#167) is answered null, which Instagram reads
     * as no page, and counted under its own name. Any other kind stays and is named on the kept line.
     */
    @Test
    public void withTheSwitchOnTheFriendsTabPageIsTakenOut() {
        assertNull(ReelsSuggestions.friendsTab(new Item(Kind.SUGGESTED_USERS)));
        Item reel = new Item(Kind.ORGANIC);
        assertSame(reel, ReelsSuggestions.friendsTab(reel));
        assertNull(ReelsSuggestions.friendsTab(null));

        List<String> report = FeedFilterCounters.report();
        assertEquals(report.toString(), 2, report.size());
        String all = report.toString();
        assertTrue(all, all.contains(ReelsSuggestions.ROUTE + ": 0 lists, 0 items, 1 removed"));
        assertTrue(all, all.contains("suggested_users_friends_lane 1"));
        assertTrue(all, all.contains(ReelsSuggestions.KEPT_ROUTE));
        assertTrue(all, all.contains("suggested_users_friends_lane ORGANIC 1"));
    }

    /** Off, paused or before the settings are read, the friends tab keeps its page, though it's still counted. */
    @Test
    public void offPausedAndUnreadyKeepTheFriendsTabPage() {
        Item page = new Item(Kind.SUGGESTED_USERS);

        Settings.HIDE_REELS_SUGGESTIONS.save(false);
        assertSame(page, ReelsSuggestions.friendsTab(page));
        Settings.HIDE_REELS_SUGGESTIONS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(page, ReelsSuggestions.friendsTab(page));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(page, ReelsSuggestions.friendsTab(page)));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(report, report.contains("suggested_users_friends_lane 3"));
    }

    /** A reader or a switch that throws keeps the friends tab's page, and the throw is reported. */
    @Test
    public void aFriendsTabPageThatCantBeReadStays() {
        Item page = new Item(Kind.SUGGESTED_USERS);
        assertSame(page, ReelsSuggestions.friendsTab(page, (item, names, family) -> {
            throw new IllegalAccessException("field went private");
        }, () -> true));
        assertSame(page, ReelsSuggestions.friendsTab(page, (item, names, family) -> "SUGGESTED_USERS", () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.REELS_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'friends tab page'"));
        assertTrue(missing, missing.contains(IllegalAccessException.class.getName()));
    }

    /** An item with no kind to read stays, and the class with no kind is reported once. */
    @Test
    public void anItemWithNoKindStays() {
        Kindless item = new Kindless();

        assertSame(item, ReelsSuggestions.filter(item));
        assertSame(item, ReelsSuggestions.filter(item));
        assertTrue(FeedFilterCounters.report().toString(), FeedFilterCounters.report().isEmpty());
        List<String> missing = HookStatus.missing(FamilyNames.REELS_SUGGESTIONS);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains(Kindless.class.getName()));
    }

    /** A reader or a switch that throws keeps the item and says which hook threw. */
    @Test
    public void aThrowingReaderOrSwitchKeepsTheItemAndIsReported() {
        Item suggested = new Item(Kind.SUGGESTED_USERS);
        ReelsSuggestions.KindReader broken = (item, names, family) -> {
            throw new IllegalAccessException("field went private");
        };

        assertSame(suggested, ReelsSuggestions.filter(suggested, broken, () -> true));
        assertSame(suggested, ReelsSuggestions.filter(suggested, (item, names, family) -> "SUGGESTED_USERS", () -> {
            throw new IllegalStateException("settings went away");
        }));
        Item friends = new Item(Kind.NETEGO);
        assertSame(friends, ReelsSuggestions.netego(friends, "friend_su_in_reels", () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.REELS_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'reels item'"));
        assertTrue(missing, missing.contains("'netego unit'"));
        assertTrue(missing, missing.contains(IllegalAccessException.class.getName()));
    }
}
