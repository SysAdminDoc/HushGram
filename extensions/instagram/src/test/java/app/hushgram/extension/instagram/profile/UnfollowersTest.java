/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoC/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class UnfollowersTest {
    private enum Kind { FOLLOWERS, FOLLOWERS_SIMPLIFIED, FOLLOWING, MUTUAL_FOLLOWERS }

    private static Map<String, String> accounts(int from, int to) {
        Map<String, String> found = new LinkedHashMap<>();
        for (int i = from; i < to; i++) found.put("id" + i, "user" + i);
        return found;
    }

    @Test
    public void theFirstScanIsSavedAndNothingIsComparedWithIt() {
        Unfollowers.Result result = Unfollowers.compare(Collections.emptyMap(), accounts(0, 5));
        assertTrue(result.first);
        assertFalse(result.incomplete);
        assertTrue(result.gone.isEmpty());
    }

    @Test
    public void whoIsSavedButNotOnTheNewScanLeft() {
        Unfollowers.Result result = Unfollowers.compare(accounts(0, 10), accounts(2, 12));
        assertEquals(Arrays.asList("id0", "id1"), result.gone);
        assertFalse(result.first);
        assertFalse(result.incomplete);
    }

    @Test
    public void aNewFollowerIsNotALeaver() {
        assertTrue(Unfollowers.compare(accounts(0, 3), accounts(0, 6)).gone.isEmpty());
    }

    @Test
    public void aLongListThatCameBackFarShorterWasNotScrolledThrough() {
        Unfollowers.Result result = Unfollowers.compare(accounts(0, 100), accounts(0, 30));
        assertTrue(result.incomplete);
        assertTrue(result.gone.isEmpty());
    }

    @Test
    public void aShortListMayReallyLoseMost() {
        Unfollowers.Result result = Unfollowers.compare(accounts(0, 10), accounts(0, 3));
        assertFalse(result.incomplete);
        assertEquals(7, result.gone.size());
    }

    @Test
    public void onlyYourOwnFollowersListsCount() {
        assertTrue(Unfollowers.ownFollowersList(Kind.FOLLOWERS, "1", "1"));
        assertTrue(Unfollowers.ownFollowersList(Kind.FOLLOWERS_SIMPLIFIED, "1", "1"));
        assertFalse(Unfollowers.ownFollowersList(Kind.FOLLOWERS, "2", "1"));
        assertFalse(Unfollowers.ownFollowersList(Kind.FOLLOWING, "1", "1"));
        assertFalse(Unfollowers.ownFollowersList(Kind.MUTUAL_FOLLOWERS, "1", "1"));
        assertFalse(Unfollowers.ownFollowersList("FOLLOWERS", "1", "1"));
        assertFalse(Unfollowers.ownFollowersList(Kind.FOLLOWERS, null, null));
        assertFalse(Unfollowers.ownFollowersList(Kind.FOLLOWERS, "", ""));
    }

    @Test
    public void theUsernameIsTheFirstTextShapedLikeOne() {
        assertEquals("jane.doe_1", Unfollowers.username(Arrays.asList("jane.doe_1", "Jane Doe", "Remove")));
        assertEquals("jane", Unfollowers.username(Arrays.asList("", "  jane ", "Jane Doe")));
        assertNull(Unfollowers.username(Arrays.asList("Jane Doe", "", "a very long line, with punctuation")));
        assertNull(Unfollowers.username(Collections.emptyList()));
    }

    @Test
    public void theHeaderCountIsReadFromAPlainNumber() {
        assertEquals(1072, Unfollowers.headerCount("1,072 followers"));
        assertEquals(1072, Unfollowers.headerCount(" 1.072 Followers "));
        assertEquals(1, Unfollowers.headerCount("1 follower"));
        assertEquals(-1, Unfollowers.headerCount("12.5K followers"));
        assertEquals(-1, Unfollowers.headerCount("929 following"));
        assertEquals(-1, Unfollowers.headerCount("Followers"));
        assertEquals(-1, Unfollowers.headerCount((CharSequence) null));
    }

    @Test
    public void aListIsCompleteOnlyOnceItHoldsTheCountItsHeaderGives() {
        assertFalse(Unfollowers.complete(925, 1072));
        assertTrue(Unfollowers.complete(1072, 1072));
        assertTrue(Unfollowers.complete(1050, 1072));
        assertTrue("without a count there is nothing to compare", Unfollowers.complete(5, -1));
    }

    @Test
    public void theSettleWaitIsLongerThanAPageTakesToArriveButNotAnnoying() {
        assertTrue(Unfollowers.SETTLE_MS >= 1500 && Unfollowers.SETTLE_MS <= 5000);
    }
}
