/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Fall back to Following (#308): an account whose For you brings only suggested posts, page after
 * page, opens Home on Following, once per account, and never over a feed you picked.
 */
@RunWith(RobolectricTestRunner.class)
public class ForYouFallbackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Home's feed types, shaped like the picker's enum. */
    enum Feed { BLENDED, BLENDED_FOR_YOU, FOLLOWING, FAVORITES }

    private final List<String> toasts = new ArrayList<>();
    private HookStatus.Snapshot report;

    @Before
    public void standIn() {
        report = HookStatus.snapshotAndClear();
        ForYouFallback.resetForTests();
        HiddenAccounts.resetForTests();
        ForYouFallback.toaster = toasts::add;
        ForYouFallback.followingInBuild = () -> true;
        Settings.FEED_ACCOUNT.save("1001");
        Settings.FOLLOWING_FALLBACK.save(true);
    }

    @After
    public void restore() {
        ForYouFallback.resetForTests();
        HiddenAccounts.resetForTests();
        Settings.FOLLOWING_FALLBACK.resetToDefault();
        Settings.FOLLOWING_FALLBACK_ACCOUNTS.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.FEED_ACCOUNT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.restore(report);
    }

    /** [pages] pages of Home's response holding suggested posts and nothing else. */
    private static void onlySuggestions(int pages) {
        for (int page = 0; page < pages; page++) ForYouFallback.pageParsed(5, 0, 0);
    }

    private static void signedIn(String account) {
        Settings.FEED_ACCOUNT.save(account);
    }

    /** What the report counted for [name]. */
    private static int counted(String name) {
        Matcher count = Pattern.compile(Pattern.quote(name) + " (\\d+)").matcher(String.join("\n", HookStatus.report()));
        return count.find() ? Integer.parseInt(count.group(1)) : 0;
    }

    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals(Boolean.FALSE, Settings.FOLLOWING_FALLBACK.defaultValue);
        assertTrue(Settings.FOLLOWING_FALLBACK.rebootApp);
        assertEquals("", Settings.FOLLOWING_FALLBACK_ACCOUNTS.defaultValue);
        assertFalse("the ids stay on this phone", Settings.FOLLOWING_FALLBACK_ACCOUNTS.includeWithImportExport);
        Settings.FOLLOWING_FALLBACK.resetToDefault();
        onlySuggestions(10);
        assertTrue(toasts.isEmpty());
        assertTrue(ForYouFallback.saved().isEmpty());
        assertFalse(ForYouFallback.movedToFollowing());
        assertFalse(ForYouFallback.holdsNextPage());
    }

    @Test
    public void afterAFewPagesOfOnlySuggestionsTheAccountMovesOnce() {
        onlySuggestions(ForYouFallback.STREAK - 1);
        assertTrue("not yet", toasts.isEmpty());
        assertFalse(ForYouFallback.movedToFollowing());
        assertFalse(ForYouFallback.holdsNextPage());

        onlySuggestions(1);
        assertEquals(Collections.singletonList("Only suggestions in For you. Home will open on Following."), toasts);
        assertEquals("1001", Settings.FOLLOWING_FALLBACK_ACCOUNTS.savedValue());
        assertTrue(ForYouFallback.movedToFollowing());
        assertTrue(ForYouFallback.holdsNextPage());
        assertEquals(1, counted(ForYouFallback.FELL_BACK));

        onlySuggestions(ForYouFallback.STREAK * 4);
        assertEquals("one toast for the move", 1, toasts.size());
        assertEquals("1001", Settings.FOLLOWING_FALLBACK_ACCOUNTS.savedValue());
        assertEquals(1, counted(ForYouFallback.FELL_BACK));

        // The next run reads the saved list and doesn't move or tell it again.
        ForYouFallback.resetForTests();
        ForYouFallback.toaster = toasts::add;
        ForYouFallback.followingInBuild = () -> true;
        assertTrue(ForYouFallback.movedToFollowing());
        onlySuggestions(ForYouFallback.STREAK);
        assertEquals(1, toasts.size());
        assertEquals("1001", Settings.FOLLOWING_FALLBACK_ACCOUNTS.savedValue());
    }

    @Test
    public void eachAccountCountsItsOwnPages() {
        onlySuggestions(ForYouFallback.STREAK - 1);
        signedIn("2002");
        onlySuggestions(ForYouFallback.STREAK - 1);
        signedIn("1001");
        onlySuggestions(1);
        assertTrue("switching accounts starts the count again", toasts.isEmpty());

        signedIn("2002");
        onlySuggestions(ForYouFallback.STREAK);
        assertEquals(1, toasts.size());
        assertTrue(ForYouFallback.movedToFollowing());
        signedIn("1001");
        assertFalse("the other account stays on For you", ForYouFallback.movedToFollowing());
        assertFalse(ForYouFallback.holdsNextPage());

        onlySuggestions(ForYouFallback.STREAK);
        assertEquals(2, toasts.size());
        assertEquals(new LinkedHashSet<>(Arrays.asList("2002", "1001")), ForYouFallback.saved());
    }

    @Test
    public void aFollowedPostOrTheEndCardKeepsForYou() {
        onlySuggestions(ForYouFallback.STREAK - 1);
        ForYouFallback.pageParsed(3, 1, 0);
        onlySuggestions(ForYouFallback.STREAK * 3);
        assertTrue("an account with a followed post isn't moved this run", toasts.isEmpty());

        signedIn("2002");
        ForYouFallback.pageParsed(0, 0, 1);
        onlySuggestions(ForYouFallback.STREAK * 3);
        assertTrue("an account that reached the end of its followed posts isn't moved", toasts.isEmpty());
        assertTrue(ForYouFallback.saved().isEmpty());
        assertFalse(ForYouFallback.holdsNextPage());
    }

    @Test
    public void aPageWithNoSuggestionsDoesntCount() {
        onlySuggestions(ForYouFallback.STREAK - 1);
        ForYouFallback.pageParsed(0, 0, 0);
        assertTrue(toasts.isEmpty());
        onlySuggestions(1);
        assertEquals(1, toasts.size());
    }

    @Test
    public void aPickedFeedWins() {
        assertEquals("BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        onlySuggestions(ForYouFallback.STREAK * 2);
        assertTrue(toasts.isEmpty());
        assertTrue(ForYouFallback.saved().isEmpty());
        assertFalse(ForYouFallback.movedToFollowing());
        assertEquals(1, counted(ForYouFallback.PICK_KEPT));
        assertTrue("For you's next pages aren't asked for over and over", ForYouFallback.holdsNextPage());

        signedIn("2002");
        Settings.START_ON_FOLLOWING.save(true);
        onlySuggestions(ForYouFallback.STREAK);
        assertTrue("with Start Home on Following on, For you shows only when picked", toasts.isEmpty());
        assertEquals(2, counted(ForYouFallback.PICK_KEPT));
        assertTrue(ForYouFallback.saved().isEmpty());
    }

    @Test
    public void aMovedAccountOpensOnFollowingUntilAFeedIsPicked() {
        assertNull("before the move Instagram's answer stands", FollowingFeed.saved(null));
        assertFalse(FollowingFeed.flag(0));
        onlySuggestions(ForYouFallback.STREAK);

        assertTrue("the picker's flags turn on", FollowingFeed.flag(0));
        assertEquals("FOLLOWING", FollowingFeed.saved(null));
        assertEquals("FOLLOWING", FollowingFeed.saved(""));
        assertEquals("one for each answer", 2, counted(ForYouFallback.OPENED));
        assertEquals("a picked feed wins", "BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        assertEquals("BLENDED", FollowingFeed.saved("BLENDED"));
        assertEquals("FAVORITES", FollowingFeed.saved("FAVORITES"));
        assertSame("Back to home switches Home in place", Feed.BLENDED_FOR_YOU, FollowingFeed.picked(Feed.BLENDED));

        signedIn("2002");
        assertFalse(FollowingFeed.flag(0));
        assertNull(FollowingFeed.saved(null));
        assertSame(Feed.BLENDED, FollowingFeed.picked(Feed.BLENDED));
    }

    @Test
    public void offPausedOrUnreadyInstagramsAnswersStand() {
        onlySuggestions(ForYouFallback.STREAK);
        assertTrue(ForYouFallback.movedToFollowing());

        Settings.FOLLOWING_FALLBACK.save(false);
        assertFalse(ForYouFallback.movedToFollowing());
        assertFalse(ForYouFallback.holdsNextPage());
        assertFalse(FollowingFeed.flag(0));
        assertNull(FollowingFeed.saved(null));
        Settings.FOLLOWING_FALLBACK.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ForYouFallback.movedToFollowing());
        assertFalse(ForYouFallback.holdsNextPage());
        assertFalse(FollowingFeed.flag(0));
        assertNull(FollowingFeed.saved(null));
        signedIn("2002");
        onlySuggestions(ForYouFallback.STREAK);
        assertEquals("nothing moves while paused", Collections.singleton("1001"), ForYouFallback.saved());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        signedIn("1001");

        SettingsContextRule.withoutContext(() -> {
            assertFalse(ForYouFallback.movedToFollowing());
            assertFalse(ForYouFallback.holdsNextPage());
            assertFalse(FollowingFeed.flag(0));
            assertNull(FollowingFeed.saved(null));
        });
        assertEquals(1, toasts.size());
    }

    @Test
    public void withoutStartHomeOnFollowingsPatchNothingMoves() {
        ForYouFallback.followingInBuild = () -> false;
        onlySuggestions(ForYouFallback.STREAK * 2);
        assertTrue(toasts.isEmpty());
        assertTrue(ForYouFallback.saved().isEmpty());
    }

    @Test
    public void aFailingToastIsCountedAndTheMoveStays() {
        ForYouFallback.toaster = text -> {
            throw new IllegalStateException("no toast");
        };
        onlySuggestions(ForYouFallback.STREAK);
        assertTrue(ForYouFallback.movedToFollowing());
        assertTrue(String.join("\n", HookStatus.report()).contains("for you fallback"));
    }

    @Test
    public void theSavedListSkipsBlankLines() {
        Settings.FOLLOWING_FALLBACK_ACCOUNTS.save("\n 1001 \n\n2002\n");
        assertEquals(new LinkedHashSet<>(Arrays.asList("1001", "2002")), ForYouFallback.saved());
        assertTrue(ForYouFallback.movedToFollowing());
        Settings.FOLLOWING_FALLBACK_ACCOUNTS.save("2002");
        assertFalse("read again once the list changes", ForYouFallback.movedToFollowing());
    }
}
