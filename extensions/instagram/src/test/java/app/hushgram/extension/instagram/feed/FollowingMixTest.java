/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Mix in Following (#438): For you asks for a page of Following after each page of its own, keeps
 * Following's cursor apart from Home's, and goes on alone once Following runs out.
 */
@RunWith(RobolectricTestRunner.class)
public class FollowingMixTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private HookStatus.Snapshot report;

    @Before
    public void standIn() {
        report = HookStatus.snapshotAndClear();
        FollowingMix.resetForTests();
        Settings.FOLLOWING_MIX.save(true);
    }

    @After
    public void restore() {
        FollowingMix.resetForTests();
        Settings.FOLLOWING_MIX.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.restore(report);
    }

    /** What the report counted for [name]. */
    private static int counted(String name) {
        Matcher count = Pattern.compile(Pattern.quote(name) + " (\\d+)").matcher(String.join("\n", HookStatus.report()));
        return count.find() ? Integer.parseInt(count.group(1)) : 0;
    }

    /** A load more on For you after Home's [cursor]: the cursor the fetcher asks after. */
    private static String loadMore(String cursor) {
        return FollowingMix.cursor(cursor, 0, null);
    }

    /** The request the fetcher then builds: the paging source it carries, or null for none. */
    private static String built() {
        return FollowingMix.sourceSet(0) != 0 ? FollowingMix.source(null) : null;
    }

    /** The reply's writes: the cursor Home keeps for its next page. */
    private static String reply(String next, boolean more) {
        String kept = FollowingMix.nextCursor(next);
        assertEquals("Home keeps paging", 1, FollowingMix.moreAvailable(more ? 1 : 0) != 0 ? 1 : 0);
        return kept;
    }

    @Test
    public void forYouAndFollowingTakeTurnsWithTheirOwnCursors() {
        assertNull("a head load asks for the first page", FollowingMix.cursor(null, 1, null));
        assertNull("the head load stays For you's", built());
        assertEquals("h1", reply("h1", true));

        assertNull("Following's first page", loadMore("h1"));
        assertEquals(FollowingMix.FOLLOWING, built());
        assertEquals("Home keeps For you's cursor", "h1", reply("f1", true));

        assertEquals("For you's turn", "h1", loadMore("h1"));
        assertNull(built());
        assertEquals("h2", reply("h2", true));

        assertEquals("Following's next page", "f1", loadMore("h2"));
        assertEquals(FollowingMix.FOLLOWING, built());
        assertEquals("h2", reply("f2", true));

        assertEquals("h2", loadMore("h2"));
        assertNull(built());
        assertEquals(2, counted(FollowingMix.ASKED));
        assertEquals(2, counted(FollowingMix.MIXED));
    }

    @Test
    public void forYouGoesOnAloneOnceFollowingRunsOut() {
        assertNull(loadMore("h1"));
        assertEquals(FollowingMix.FOLLOWING, built());
        assertEquals("h1", reply("f1", false));
        assertEquals(1, counted(FollowingMix.RAN_OUT));

        for (String cursor : new String[] {"h1", "h2", "h3"}) {
            assertEquals(cursor, loadMore(cursor));
            assertNull("no more Following requests", built());
            assertEquals("h9", reply("h9", true));
        }
        assertEquals(1, counted(FollowingMix.ASKED));
    }

    @Test
    public void aFollowingReplyWithNoCursorEndsFollowing() {
        assertNull(loadMore("h1"));
        assertEquals(FollowingMix.FOLLOWING, built());
        // Instagram skips the cursor write for a reply without one.
        assertEquals(1, FollowingMix.moreAvailable(1));
        assertEquals(1, counted(FollowingMix.RAN_OUT));

        assertEquals("h1", loadMore("h1"));
        assertNull(built());
        assertEquals("h1", loadMore("h1"));
        assertNull(built());
    }

    @Test
    public void aHeadLoadStartsFollowingOver() {
        assertNull(loadMore("h1"));
        built();
        reply("f1", false);
        assertEquals("ran out", "h1", loadMore("h1"));
        built();
        reply("h2", true);

        assertNull(FollowingMix.cursor(null, 1, null));
        assertNull(built());
        reply("n1", true);
        assertNull("Following's first page again", loadMore("n1"));
        assertEquals(FollowingMix.FOLLOWING, built());
        assertEquals("n1", reply("f1", true));
    }

    @Test
    public void aFeedWithItsOwnPagingSourceIsLeftAlone() {
        assertEquals("p1", FollowingMix.cursor("p1", 0, "following"));
        assertEquals(1, FollowingMix.sourceSet(1));
        assertEquals("following", FollowingMix.source("following"));
        assertEquals("p2", FollowingMix.nextCursor("p2"));
        assertEquals(0, FollowingMix.moreAvailable(0));

        assertEquals("q1", FollowingMix.cursor("q1", 0, "favorites"));
        assertEquals(0, FollowingMix.sourceSet(0));
        assertEquals(0, counted(FollowingMix.ASKED));
    }

    @Test
    public void aFollowingCursorNeverBuiltIntoARequestIsSpent() {
        assertNull(loadMore("h1"));
        // The fetcher found the page already loading and built nothing; another feed asks next.
        assertEquals("q1", FollowingMix.cursor("q1", 0, "favorites"));
        assertEquals("not marked as Following", 0, FollowingMix.sourceSet(0));
        assertEquals("favorites", FollowingMix.source("favorites"));

        assertNull("still Following's turn", loadMore("h1"));
        assertEquals(FollowingMix.FOLLOWING, built());
    }

    @Test
    public void aLoadMoreWhileFollowingIsOutAsksForTheSamePageUntilItGivesUp() {
        assertEquals("h1", reply("h1", true));
        assertNull(loadMore("h1"));
        built();
        String sent = "f1";
        reply(sent, true);
        assertEquals("h1", loadMore("h1"));
        built();
        reply("h2", true);

        assertEquals(sent, loadMore("h2"));
        assertEquals(FollowingMix.FOLLOWING, built());
        for (int asked = 1; asked < FollowingMix.TRIES; asked++) {
            assertEquals("the same page again", sent, loadMore("h2"));
            assertEquals(FollowingMix.FOLLOWING, built());
        }
        assertEquals("For you goes on alone", "h2", loadMore("h2"));
        assertNull(built());
        assertEquals(1, counted(FollowingMix.UNANSWERED));
        assertEquals("and stays alone", "h3", loadMore("h3"));
        assertNull(built());
    }

    @Test
    public void aRequestAlreadyLoadingIsAskedAfterTheSameCursor() {
        assertNull(loadMore("h1"));
        built();
        assertNull("Home's check for a page already loading sees the same request", loadMore("h1"));
        assertEquals("h1", reply("f1", true));
        assertEquals("For you's turn after the one reply", "h1", loadMore("h1"));
        assertNull(built());
    }

    @Test
    public void aRewindToTheFollowingRequestsCursorGoesBackToHomes() {
        assertNull(loadMore("h1"));
        built();
        reply("f1", true);
        loadMore("h1");
        built();
        reply("h2", true);
        assertEquals("f1", loadMore("h2"));
        built();
        reply("f2", true);

        assertEquals("nothing new in Following's page", "h2", FollowingMix.rewound("f1"));
        assertEquals("h7", FollowingMix.rewound("h7"));
        assertNull(FollowingMix.rewound(null));

        assertEquals("h2", loadMore("h2"));
        assertEquals("no Following request since", "f1", FollowingMix.rewound("f1"));
    }

    @Test
    public void offOrPausedEveryAnswerIsInstagrams() {
        Settings.FOLLOWING_MIX.save(false);
        assertEquals("h1", loadMore("h1"));
        assertEquals(0, FollowingMix.sourceSet(0));
        assertNull(FollowingMix.source(null));
        assertEquals("h2", FollowingMix.nextCursor("h2"));
        assertEquals(0, FollowingMix.moreAvailable(0));

        Settings.FOLLOWING_MIX.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("h1", loadMore("h1"));
        assertEquals(0, FollowingMix.sourceSet(0));
        assertEquals(0, counted(FollowingMix.ASKED));
    }
}
