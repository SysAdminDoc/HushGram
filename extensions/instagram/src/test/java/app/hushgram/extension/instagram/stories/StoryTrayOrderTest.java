/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Watched stories go to the back of Home's tray only with views held back and the gray ring on (#241). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryTrayOrderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    @After
    public void restore() {
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.GRAY_OUT_WATCHED_STORIES.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
        StoryTrayOrder.refreshed();
    }

    private static StoryTrayOrder.Seen seenAre(String... names) {
        Set<String> seen = new HashSet<>(Arrays.asList(names));
        return reel -> seen.contains(reel) ? 1 : 0;
    }

    @Test
    public void unseenComeFirstSeenAfterEachInServerOrder() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c", "d", "e", "f");
        List<Object> ordered = StoryTrayOrder.order(tray, ON, seenAre("a", "c", "d"));
        assertEquals(Arrays.<Object>asList("b", "e", "f", "a", "c", "d"), ordered);
        assertEquals("nothing added or dropped", new HashSet<>(tray), new HashSet<>(ordered));
        assertEquals(tray.size(), ordered.size());
    }

    @Test
    public void duplicatesAreKeptAndNullEntriesKeepTheirNativeSlots() {
        List<Object> tray = Arrays.<Object>asList("a", null, "a", "b");
        List<Object> ordered = StoryTrayOrder.order(tray, ON, seenAre("a"));
        assertEquals(Arrays.<Object>asList("b", null, "a", "a"), ordered);
    }

    @Test
    public void createOwnAndSpecialEntriesKeepTheirExactNativeSlots() {
        List<Object> tray = Arrays.<Object>asList("Create", "watched1", "special", "new1", "own", "watched2", "new2", null);
        List<Object> ordered = StoryTrayOrder.order(tray, ON, reel -> {
            String name = (String) reel;
            if (name.equals("Create") || name.equals("own") || name.equals("special")) return StoryTrayOrder.PINNED;
            return name.startsWith("watched") ? 1 : 0;
        });
        assertEquals(Arrays.<Object>asList("Create", "new1", "special", "new2", "own", "watched1", "watched2", null), ordered);
        assertEquals(Arrays.<Object>asList("Create", "watched1", "special", "new1", "own", "watched2", "new2", null), tray);
    }

    /** The signed-in account's own reel goes first, ahead of the watched ones pushed back, and the rest sort as before (#241). */
    @Test
    public void theOwnStoryStaysFirstWhateverItsStateAndWatchedOnesGoBack() {
        StoryTrayOrder.Seen seen = reel -> {
            String name = (String) reel;
            if (name.equals("own")) return StoryTrayOrder.OWN;
            return name.startsWith("watched") ? 1 : 0;
        };
        List<Object> tray = Arrays.<Object>asList("watched1", "new1", "own", "watched2", "new2");
        List<Object> ordered = StoryTrayOrder.order(tray, ON, seen);
        assertEquals(Arrays.<Object>asList("own", "new1", "new2", "watched1", "watched2"), ordered);
        assertEquals("nothing added or dropped", new HashSet<>(tray), new HashSet<>(ordered));
        assertEquals("Instagram's own list is not touched",
                Arrays.<Object>asList("watched1", "new1", "own", "watched2", "new2"), tray);
    }

    @Test
    public void anOwnStoryAlreadyFirstOrAloneChangesNothing() {
        StoryTrayOrder.Seen seen = reel -> reel.equals("own") ? StoryTrayOrder.OWN : 0;
        List<Object> first = Arrays.<Object>asList("own", "a", "b");
        assertSame(first, StoryTrayOrder.order(first, ON, seen));
        List<Object> behind = Arrays.<Object>asList("a", "own", "b");
        assertEquals(Arrays.<Object>asList("own", "a", "b"), StoryTrayOrder.order(behind, ON, seen));
    }

    /** Off, or paused, hands Instagram's order back with the own story where Instagram put it. */
    @Test
    public void ownStoryIsOnlyMovedWhileTheTrayIsOrdered() {
        StoryTrayOrder.Seen seen = reel -> reel.equals("own") ? StoryTrayOrder.OWN : 1;
        List<Object> tray = Arrays.<Object>asList("a", "own", "b");
        assertSame(tray, StoryTrayOrder.order(tray, OFF, seen));
    }

    @Test
    public void unknownEntriesStayPinnedAndAreNeverCountedAsUnseen() {
        List<Object> tray = Arrays.<Object>asList("watched", "unknown", "new");
        assertEquals(Arrays.<Object>asList("new", "unknown", "watched"), StoryTrayOrder.order(tray, ON,
                reel -> reel.equals("unknown") ? StoryTrayOrder.PINNED : reel.equals("watched") ? 1 : 0));
        StoryTrayOrder.refreshed();
        assertSame(tray, StoryTrayOrder.order(tray, ON, reel -> StoryTrayOrder.PINNED));
        StoryTrayOrder.refreshed();
        assertSame(tray, StoryTrayOrder.order(tray, ON,
                reel -> reel.equals("unknown") ? StoryTrayOrder.PINNED : 1));
    }

    @Test
    public void invalidOrFailingNativeAnswersLeaveTheEntireListAlone() {
        List<Object> tray = Arrays.<Object>asList("watched", "new", "broken");
        assertSame(tray, StoryTrayOrder.order(tray, ON, reel -> reel.equals("broken") ? 2 : reel.equals("watched") ? 1 : 0));
        assertSame(tray, StoryTrayOrder.order(tray, ON, reel -> {
            if (reel.equals("broken")) throw new IllegalStateException("missing identity");
            return reel.equals("watched") ? 1 : 0;
        }));
    }

    @Test
    public void eachRefreshUsesItsOwnNativeState() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertEquals(Arrays.<Object>asList("b", "c", "a"), StoryTrayOrder.order(tray, ON, seenAre("a")));
        StoryTrayOrder.refreshed();
        assertEquals(Arrays.<Object>asList("a", "c", "b"), StoryTrayOrder.order(tray, ON, seenAre("b")));
        StoryTrayOrder.refreshed();
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre("c")));
    }

    /** A story just watched keeps its slot, and the row's scroll with it, until the tray refreshes (#377). */
    @Test
    public void aStoryJustWatchedStaysInPlaceUntilTheTrayRefreshes() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        assertEquals(Arrays.<Object>asList("a", "b", "c"), StoryTrayOrder.order(tray, ON, seenAre("b")));
        assertEquals(Arrays.<Object>asList("a", "b", "c"), StoryTrayOrder.order(tray, ON, seenAre("b", "c")));
        String counted = String.join("\n", HookStatus.report());
        assertTrue(counted, counted.contains(StoryTrayOrder.KEPT));
        assertFalse(counted, counted.contains(StoryTrayOrder.MOVED));
        StoryTrayOrder.refreshed();
        assertEquals(Arrays.<Object>asList("a", "c", "b"), StoryTrayOrder.order(tray, ON, seenAre("b")));
        counted = String.join("\n", HookStatus.report());
        assertTrue(counted, counted.contains(StoryTrayOrder.MOVED));
    }

    /** A tray that went out sorted keeps that order as more of it is watched. */
    @Test
    public void aTrayThatWentOutSortedKeepsThatOrderAsMoreAreWatched() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertEquals(Arrays.<Object>asList("b", "c", "a"), StoryTrayOrder.order(tray, ON, seenAre("a")));
        assertEquals(Arrays.<Object>asList("b", "c", "a"), StoryTrayOrder.order(tray, ON, seenAre("a", "b")));
        assertEquals(Arrays.<Object>asList("b", "c", "a"), StoryTrayOrder.order(tray, ON, seenAre("a", "b", "c")));
        assertEquals("the list that went out is never Instagram's own", Arrays.<Object>asList("a", "b", "c"), tray);
    }

    /**
     * The store moves its own seen reels behind its unseen ones each time it's asked, so what's handed
     * over when the viewer closes is already the order that strands you. The tray keeps its order
     * through that, and gives it up when the row starts over (#377).
     */
    @Test
    public void theStoreSortingItselfDoesntMoveTheTrayUntilItRefreshes() {
        List<Object> tray = Arrays.<Object>asList("own", "a", "b", "c");
        StoryTrayOrder.Seen watchedA = reel -> reel.equals("a") ? 1 : 0;
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));

        List<Object> storeSorted = Arrays.<Object>asList("own", "b", "c", "a");
        assertEquals(tray, StoryTrayOrder.order(storeSorted, ON, watchedA));
        String counted = String.join("\n", HookStatus.report());
        assertTrue(counted, counted.contains(StoryTrayOrder.KEPT));

        StoryTrayOrder.refreshed();
        assertSame(storeSorted, StoryTrayOrder.order(storeSorted, ON, watchedA));
    }

    /**
     * Coming back from a longer watch fetches a new tray, which on the phone ranked the story just
     * watched behind the others while it was still the same reels. It stays put through that (#377).
     */
    @Test
    public void aTrayFetchedInAnotherOrderDoesntMoveAStoryJustWatched() {
        List<Object> tray = Arrays.<Object>asList("cbs", "wsj", "nba");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        assertEquals(tray, StoryTrayOrder.order(Arrays.<Object>asList("cbs", "nba", "wsj"), ON, seenAre("wsj")));
        assertEquals("the unseen ones hold their places too", tray,
                StoryTrayOrder.order(Arrays.<Object>asList("nba", "cbs", "wsj"), ON, seenAre("wsj")));
    }

    /** A reel new to the row goes where the store has it, at the front, between two reels or at the end. */
    @Test
    public void aNewReelLandsWhereTheStorePutIt() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        List<Object> between = Arrays.<Object>asList("a", "x", "b", "c");
        assertSame(between, StoryTrayOrder.order(between, ON, seenAre()));
        List<Object> ends = Arrays.<Object>asList("y", "a", "x", "b", "z", "c", "w");
        assertSame(ends, StoryTrayOrder.order(ends, ON, seenAre()));
    }

    /** A reel with a place keeps it when a shorter list leaves others out, and one that was never placed goes behind. */
    @Test
    public void aTrayWithOtherReelsKeepsThePlacesItHas() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        assertEquals("one more reel", Arrays.<Object>asList("a", "b", "c", "d"),
                StoryTrayOrder.order(Arrays.<Object>asList("a", "b", "c", "d"), ON, seenAre("a")));
        assertEquals("one fewer", Arrays.<Object>asList("a", "b"),
                StoryTrayOrder.order(Arrays.<Object>asList("a", "b"), ON, seenAre("a")));
        Object another = new StringBuilder("a").toString();
        assertEquals("another object that reads the same has no place of its own", Arrays.<Object>asList("b", "c", another),
                StoryTrayOrder.order(Arrays.<Object>asList(another, "b", "c"), ON, seenAre("a")));
    }

    /**
     * Only a tray fetched after a watch, or the row's next page, keeps the row's places, as the
     * request's own text names the reason. Every other fetch starts the row over, the reloads on
     * coming back to the app included, and so does a request with no reason to read (#241).
     */
    @Test
    public void onlyATrayAfterAWatchOrTheNextPageKeepsTheRowsPlaces() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        for (String reason : StoryTrayOrder.KEEPS_PLACES) {
            assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
            StoryTrayOrder.responded("Request{mReason=" + reason + ", mInstanceNumber=3}");
            assertSame(reason, tray, StoryTrayOrder.order(tray, ON, seenAre("a")));
            StoryTrayOrder.refreshed();
        }
        for (Object request : new Object[] {
                "Request{mReason=cold_start, mInstanceNumber=3}",
                "Request{mReason=warm_start_with_feed, mInstanceNumber=3}",
                "Request{mReason=pull_to_refresh, mInstanceNumber=3}",
                "Request{mReason=auto_refresh, mInstanceNumber=3}",
                "Request{mReason=fragment_refresh, mInstanceNumber=3}",
                "Request{mReason=background_prefetch, mInstanceNumber=3}",
                "Request{mReason=reset_to_home, mInstanceNumber=3}",
                "Request{mReason=cross_tab_refresh, mInstanceNumber=3}",
                "something else",
                null,
        }) {
            assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
            StoryTrayOrder.responded(request);
            assertEquals(String.valueOf(request), Arrays.<Object>asList("b", "c", "a"),
                    StoryTrayOrder.order(tray, ON, seenAre("a")));
            StoryTrayOrder.refreshed();
        }
    }

    /**
     * The row's next page goes after every reel already placed, the watched ones held in place
     * included. A new reel used to take the place one past the unseen reel ahead of it, level with
     * a gray ring held there, so the page came out shuffled in among the gray rings (#241).
     */
    @Test
    public void theNextPageGoesAfterTheRowNotAmongWatchedOnes() {
        List<Object> first = Arrays.<Object>asList("a", "b", "c", "d");
        assertSame(first, StoryTrayOrder.order(first, ON, seenAre()));
        StoryTrayOrder.Seen watched = seenAre("c", "d");
        assertSame("c and d stay where they were", first, StoryTrayOrder.order(first, ON, watched));
        StoryTrayOrder.responded("Request{mReason=pagination, mInstanceNumber=4}");
        List<Object> paged = Arrays.<Object>asList("a", "b", "c", "d", "e", "f");
        assertSame(paged, StoryTrayOrder.order(paged, ON, watched));
    }

    @Test
    public void aRequestsReasonIsReadFromItsText() {
        assertEquals("pull_to_refresh", StoryTrayOrder.reason("Request{mReason=pull_to_refresh, mInstanceNumber=3}"));
        assertEquals("no comma", "cold_start", StoryTrayOrder.reason("mReason=cold_start"));
        assertEquals("no reason", "", StoryTrayOrder.reason("Request{}"));
    }

    /** A request that won't even read starts the row over, as Instagram would, and shows in the diagnostics. */
    @Test
    public void aRequestThatFailsToReadIsReported() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        StoryTrayOrder.responded(new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        });
        assertEquals(Arrays.<Object>asList("b", "a"), StoryTrayOrder.order(tray, ON, seenAre("a")));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(StoryTrayOrder.RESPONSE_HOOK));
    }

    /** Either switch off forgets the order that went out, so on again sorts afresh. */
    @Test
    public void offForgetsTheOrderThatWentOut() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertEquals(Arrays.<Object>asList("b", "c", "a"), StoryTrayOrder.order(tray, ON, seenAre("a")));
        assertSame(tray, StoryTrayOrder.order(tray, OFF, seenAre("a")));
        assertEquals(Arrays.<Object>asList("a", "c", "b"), StoryTrayOrder.order(tray, ON, seenAre("b")));
    }

    /** Home's top row and its feed adapter ask for trays of their own, and each keeps its order. */
    @Test
    public void eachTrayAskedForKeepsItsOwnOrder() {
        List<Object> top = Arrays.<Object>asList("a", "b", "c");
        List<Object> feed = Arrays.<Object>asList("own", "a", "b", "c");
        assertSame(top, StoryTrayOrder.order(top, ON, seenAre()));
        assertSame(feed, StoryTrayOrder.order(feed, ON, seenAre()));
        assertEquals(Arrays.<Object>asList("a", "b", "c"), StoryTrayOrder.order(top, ON, seenAre("a")));
        assertEquals(Arrays.<Object>asList("own", "a", "b", "c"), StoryTrayOrder.order(feed, ON, seenAre("a")));
    }

    @Test
    public void anOrderedOrOneSidedTrayComesBackAsIs() {
        List<Object> sorted = Arrays.<Object>asList("b", "a");
        assertSame(sorted, StoryTrayOrder.order(sorted, ON, seenAre("a")));
        StoryTrayOrder.refreshed();
        List<Object> allNew = Arrays.<Object>asList("a", "b");
        assertSame(allNew, StoryTrayOrder.order(allNew, ON, seenAre()));
        StoryTrayOrder.refreshed();
        List<Object> allSeen = Arrays.<Object>asList("a", "b");
        assertSame(allSeen, StoryTrayOrder.order(allSeen, ON, seenAre("a", "b")));
    }

    @Test
    public void offLeavesTheListAlone() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        assertSame(tray, StoryTrayOrder.order(tray, OFF, seenAre("a")));
    }

    @Test
    public void aFailureLeavesTheListAlone() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        assertSame(tray, StoryTrayOrder.order(tray, ON, reel -> {
            throw new IllegalStateException("no");
        }));
        assertSame(tray, StoryTrayOrder.order(tray, () -> {
            throw new IllegalStateException("no");
        }, seenAre("a")));
    }

    @Test
    public void bothSwitchesAndAPauseDecide() {
        assertFalse("both off", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertFalse("gray out off", StoryTrayOrder.grayedOut());
        Settings.GRAY_OUT_WATCHED_STORIES.save(true);
        assertTrue("both on", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("anonymous off", StoryTrayOrder.grayedOut());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryTrayOrder.grayedOut());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("settings not ready", StoryTrayOrder.grayedOut()));
        assertTrue("the control: on again", StoryTrayOrder.grayedOut());
    }

    @Test
    public void theUnpatchedStubReportsNothingSeen() {
        List<Object> tray = Arrays.<Object>asList("a", "b");
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        Settings.GRAY_OUT_WATCHED_STORIES.save(true);
        assertSame(tray, StoryTrayOrder.order(tray, new Object()));
    }
}
