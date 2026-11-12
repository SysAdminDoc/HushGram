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

    /** A refresh that brings a reel more, fewer, elsewhere or as another object is sorted afresh. */
    @Test
    public void aTrayWithOtherReelsOrAnotherOrderIsSortedAfresh() {
        List<Object> tray = Arrays.<Object>asList("a", "b", "c");
        assertSame(tray, StoryTrayOrder.order(tray, ON, seenAre()));
        assertEquals("one more reel", Arrays.<Object>asList("b", "c", "d", "a"),
                StoryTrayOrder.order(Arrays.<Object>asList("a", "b", "c", "d"), ON, seenAre("a")));
        assertEquals("one fewer", Arrays.<Object>asList("b", "a"),
                StoryTrayOrder.order(Arrays.<Object>asList("a", "b"), ON, seenAre("a")));
        assertEquals("the same reels elsewhere", Arrays.<Object>asList("c", "a", "b"),
                StoryTrayOrder.order(Arrays.<Object>asList("c", "a", "b"), ON, seenAre("a", "b")));
        Object another = new StringBuilder("a").toString();
        assertEquals("a's slot held by another object that reads the same", Arrays.<Object>asList("b", "c", another),
                StoryTrayOrder.order(Arrays.<Object>asList(another, "b", "c"), ON, seenAre("a")));
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

    /** Only the last few trays are remembered, so an old one that comes round again is sorted afresh. */
    @Test
    public void onlyTheLastFewTraysAreRemembered() {
        List<Object> first = Arrays.<Object>asList("a", "b");
        assertSame(first, StoryTrayOrder.order(first, ON, seenAre()));
        for (int index = 0; index < StoryTrayOrder.REMEMBERED; index++) {
            List<Object> another = Arrays.<Object>asList("a", "b", "x" + index);
            assertSame(another, StoryTrayOrder.order(another, ON, seenAre()));
        }
        assertEquals(Arrays.<Object>asList("b", "a"), StoryTrayOrder.order(first, ON, seenAre("a")));
    }

    @Test
    public void anOrderedOrOneSidedTrayComesBackAsIs() {
        List<Object> sorted = Arrays.<Object>asList("b", "a");
        assertSame(sorted, StoryTrayOrder.order(sorted, ON, seenAre("a")));
        List<Object> allNew = Arrays.<Object>asList("a", "b");
        assertSame(allNew, StoryTrayOrder.order(allNew, ON, seenAre()));
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
