/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Start tab choice does, and the starts it leaves alone. */
@RunWith(RobolectricTestRunner.class)
public class TabStartTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, FEED_SWITCHER, SEARCH, CREATION, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final List<Tab> BAR = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CREATION, Tab.CLIPS, Tab.PROFILE);

    @Before
    public void freshProcess() {
        TabStart.forgetForTests();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        TabStart.forgetForTests();
        Settings.START_TAB.resetToDefault();
        Settings.TAB_ORDER.resetToDefault();
        Settings.HIDE_REELS_TAB.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        Settings.HIDE_PROFILE_TAB.resetToDefault();
    }

    private static Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    /** The hooks as Instagram runs them: the list is built, then the host switches to its landing tab. */
    private static Object coldStart(Intent firstActivity, List<Tab> bar, Tab landing) {
        TabStart.noteStart(firstActivity, null);
        ReelsTab.tabs(bar);
        return ReelsTab.tab(landing);
    }

    @Test
    public void everyChoiceStartsAtHomeAndHomeLeavesInstagramAlone() {
        assertEquals(StartTab.HOME, Settings.START_TAB.get());
        assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));
        assertSame("a Reels first account keeps it", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
    }

    @Test
    public void eachChoiceOpensItsTabFromTheIcon() {
        for (StartTab choice : new StartTab[] {StartTab.SEARCH, StartTab.REELS, StartTab.PROFILE}) {
            TabStart.forgetForTests();
            Settings.START_TAB.save(choice);
            Tab want = Tab.valueOf(choice.tab);
            assertSame(choice.name(), want, coldStart(launcher(), BAR, Tab.FEED));
        }
    }

    @Test
    public void onlyTheFirstLandingIsChangedAndLaterSwitchesPass() {
        Settings.START_TAB.save(StartTab.SEARCH);
        assertSame(Tab.SEARCH, coldStart(launcher(), BAR, Tab.FEED));
        assertSame("the next switch to Home", Tab.FEED, ReelsTab.tab(Tab.FEED));
        assertSame(Tab.PROFILE, ReelsTab.tab(Tab.PROFILE));
    }

    @Test
    public void aReelsFirstAccountLandsOnTheChoiceToo() {
        Settings.START_TAB.save(StartTab.PROFILE);
        assertSame(Tab.PROFILE, coldStart(launcher(), BAR, Tab.CLIPS));
    }

    /** #55: Home leaves Instagram's reopen on Reels alone, Always Home sends that one start to Home. */
    @Test
    public void alwaysHomeSendsAReelsReopenToHomeWhereHomeKeepsIt() {
        assertSame("Home is Instagram's own start", Tab.CLIPS, coldStart(launcher(), BAR, Tab.CLIPS));

        TabStart.forgetForTests();
        HookStatus.clear();
        Settings.START_TAB.save(StartTab.ALWAYS_HOME);
        assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.CLIPS));
        assertSame("a tap on Reels after that is the person's", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
        String report = String.join(" | ", HookStatus.report());
        assertTrue(report, report.contains("Start tab applied 1"));
    }

    /**
     * #55: Instagram's keep-alive service keeps the process running after it's swiped away from
     * recents, so the icon then creates an activity in the same process. With none of Instagram's
     * activities alive that's a new start, and Always Home sends its Reels reopen to Home again. A
     * second activity while one is alive, or coming back to one that's still open, isn't a start.
     */
    @Test
    public void aStartFromTheIconAfterInstagramWasSwipedAwayIsANewStart() {
        Settings.START_TAB.save(StartTab.ALWAYS_HOME);
        TabStart.activityCreated(launcher(), null);
        ReelsTab.tabs(BAR);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.CLIPS));
        TabStart.noteResumed();
        ShadowSystemClock.advanceBy(Duration.ofMillis(TabStart.STARTUP_MS + 1));
        assertSame("a tap on Reels", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));

        TabStart.activityCreated(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reel/abc/")), null);
        assertSame("a reel opened over the open app", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
        TabStart.activityDestroyed();
        TabStart.noteResumed();
        assertSame("back to the open app on Reels", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));

        TabStart.activityDestroyed();
        TabStart.activityCreated(launcher(), null);
        assertSame("asked ahead of the new start's list", Tab.FEED, ReelsTab.tab(Tab.FEED));
        ReelsTab.tabs(BAR);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.CLIPS));
        String report = String.join(" | ", HookStatus.report());
        assertTrue(report, report.contains("Start tab applied 2"));

        TabStart.activityDestroyed();
        TabStart.activityCreated(launcher(), new Bundle());
        ReelsTab.tabs(BAR);
        assertSame("a start that restores its state", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
    }

    /**
     * #55: the icon's first activity asks for Reels before the tab list is built, and with Tab order
     * reordering the bar no switch follows it, so Always Home swaps that first ask. A Home asked that
     * early still waits for the list, and a reel opened by a link stays.
     */
    @Test
    public void alwaysHomeSwapsAReelsAskedBeforeTheList() {
        Settings.START_TAB.save(StartTab.ALWAYS_HOME);
        TabStart.noteStart(launcher(), null);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.CLIPS));
        ReelsTab.tabs(BAR);
        assertSame("the decision is spent", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));

        TabStart.forgetForTests();
        TabStart.noteStart(launcher(), null);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.FEED));
        ReelsTab.tabs(BAR);
        assertSame("a Reels switch once the list is built", Tab.FEED, ReelsTab.tab(Tab.CLIPS));

        TabStart.forgetForTests();
        TabStart.noteStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reel/abc/")), null);
        assertSame("a reel opened by a link", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));

        String report = String.join(" | ", HookStatus.report());
        assertTrue(report, report.contains("Start tab applied 2"));
        assertTrue(report, report.contains("Start tab skipped, not from the icon 1"));
        assertTrue(report, report.contains("Started from the icon 2"));
        assertTrue(report, report.contains("Started another way 1"));
    }

    /**
     * With Tab order saved, the pager opens straight on the first tab asked for, which comes before
     * the list (#55), so a Search, Reels or Profile choice answers it, unless a hide switch took that
     * tab off. Messages, which not every bar has, still waits for the list, and so does every choice
     * without an order saved, since a switch follows the list then.
     */
    @Test
    public void aSavedTabOrderLetsTheChoiceAnswerBeforeTheList() {
        Settings.TAB_ORDER.save("PROFILE,SEARCH,FEED,CLIPS");
        Settings.START_TAB.save(StartTab.PROFILE);
        TabStart.noteStart(launcher(), null);
        assertSame(Tab.PROFILE, ReelsTab.tab(Tab.CLIPS));

        TabStart.forgetForTests();
        Settings.START_TAB.save(StartTab.SEARCH);
        Settings.HIDE_SEARCH_TAB.save(true);
        TabStart.noteStart(launcher(), null);
        assertSame("a hidden Search sends Reels to Home", Tab.FEED, ReelsTab.tab(Tab.CLIPS));
        Settings.HIDE_SEARCH_TAB.save(false);

        TabStart.forgetForTests();
        Settings.START_TAB.save(StartTab.MESSAGES);
        TabStart.noteStart(launcher(), null);
        assertSame("Messages waits for the list", Tab.FEED, ReelsTab.tab(Tab.FEED));

        TabStart.forgetForTests();
        Settings.TAB_ORDER.resetToDefault();
        Settings.START_TAB.save(StartTab.PROFILE);
        TabStart.noteStart(launcher(), null);
        assertSame("no order saved", Tab.FEED, ReelsTab.tab(Tab.FEED));
        ReelsTab.tabs(BAR);
        assertSame(Tab.PROFILE, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void alwaysHomeLeavesAHomeLandingAsItCame() {
        Settings.START_TAB.save(StartTab.ALWAYS_HOME);
        assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));
        TabStart.forgetForTests();
        assertSame("Home with the feed switcher is Home already",
                Tab.FEED_SWITCHER, coldStart(launcher(), BAR, Tab.FEED_SWITCHER));
        String report = String.join(" | ", HookStatus.report());
        assertFalse(report, report.contains("Start tab applied"));
    }

    @Test
    public void alwaysHomeLeavesAReelsStartThatIsNotFromTheIcon() {
        Settings.START_TAB.save(StartTab.ALWAYS_HOME);
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/reel/abc/"));
        assertSame("a reel opened by a link", Tab.CLIPS, coldStart(link, BAR, Tab.CLIPS));

        TabStart.forgetForTests();
        TabStart.noteStart(launcher(), new Bundle());
        ReelsTab.tabs(BAR);
        assertSame("a restored activity", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));

        TabStart.forgetForTests();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertSame("paused", Tab.CLIPS, coldStart(launcher(), BAR, Tab.CLIPS));
        } finally {
            PauseForTests.resume();
        }

        TabStart.forgetForTests();
        TabStart.noteStart(launcher(), null);
        ReelsTab.tabs(BAR);
        TabStart.noteResumed();
        ShadowSystemClock.advanceBy(Duration.ofMillis(TabStart.STARTUP_MS + 1));
        assertSame("a tap on Reels once Instagram is up", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
    }

    @Test
    public void aNotificationLinkOrShortcutStartIsLeftAlone() {
        Settings.START_TAB.save(StartTab.SEARCH);
        Intent notification = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra("notification_id", "1");
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/p/abc/"));
        Intent direct = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setData(Uri.parse("instagram://direct_v2?id=1"));
        Intent noCategory = new Intent(Intent.ACTION_MAIN);
        for (Intent start : new Intent[] {notification, link, direct, noCategory, null}) {
            TabStart.forgetForTests();
            assertSame(String.valueOf(start), Tab.FEED, coldStart(start, BAR, Tab.FEED));
        }
        TabStart.forgetForTests();
        TabStart.noteStart(launcher(), new Bundle());
        ReelsTab.tabs(BAR);
        assertSame("a restored activity", Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void aNotificationThatOpensATabIsNeverReplacedEvenFromTheIcon() {
        Settings.START_TAB.save(StartTab.SEARCH);
        TabStart.noteStart(launcher(), null);
        ReelsTab.tabs(BAR);
        assertSame("a thread opened by a notification keeps its tab", Tab.DIRECT, ReelsTab.tab(Tab.DIRECT));
        assertSame("and the decision is spent", Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void aHiddenOrMissingTabOpensHome() {
        Settings.START_TAB.save(StartTab.SEARCH);
        Settings.HIDE_SEARCH_TAB.save(true);
        assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));

        TabStart.forgetForTests();
        Settings.START_TAB.save(StartTab.REELS);
        Settings.HIDE_REELS_TAB.save(true);
        assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));
        assertSame("and a Reels first account is sent to Home", Tab.FEED, coldStart(launcher(), BAR, Tab.CLIPS));

        TabStart.forgetForTests();
        Settings.HIDE_REELS_TAB.save(false);
        Settings.START_TAB.save(StartTab.MESSAGES);
        assertSame("Messages isn't on this bar", Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));

        TabStart.forgetForTests();
        List<Tab> withMessages = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.DIRECT, Tab.PROFILE);
        assertSame("Messages is on this one", Tab.DIRECT, coldStart(launcher(), withMessages, Tab.FEED));
    }

    @Test
    public void aHomeAskedAheadOfTheListWaitsForTheSwitchThatFollows() {
        Settings.START_TAB.save(StartTab.PROFILE);
        TabStart.noteStart(launcher(), null);
        assertSame("no list yet", Tab.FEED, ReelsTab.tab(Tab.FEED));
        ReelsTab.tabs(BAR);
        assertSame(Tab.PROFILE, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void pausedOrNotReadyIsStock() {
        Settings.START_TAB.save(StartTab.SEARCH);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertSame(Tab.FEED, coldStart(launcher(), BAR, Tab.FEED));
        } finally {
            PauseForTests.resume();
        }
        TabStart.forgetForTests();
        SettingsContextRule.withoutContext(() -> {
            TabStart.noteStart(launcher(), null);
            assertNull(TabStart.landing(Tab.FEED));
        });
    }

    @Test
    public void aStartTabOnAHiddenSearchAndTheRedirectStillAgree() {
        Settings.START_TAB.save(StartTab.PROFILE);
        Settings.HIDE_PROFILE_TAB.save(true);
        @SuppressWarnings("unchecked")
        List<Tab> shown = (List<Tab>) ReelsTab.tabs(BAR);
        assertFalse(shown.contains(Tab.PROFILE));
        TabStart.noteStart(launcher(), null);
        assertSame(Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void whatItDidIsCounted() {
        Settings.START_TAB.save(StartTab.SEARCH);
        coldStart(launcher(), BAR, Tab.FEED);
        String report = String.join(" | ", HookStatus.report());
        assertTrue(report, report.contains("Start tab applied 1"));

        TabStart.forgetForTests();
        coldStart(new Intent(Intent.ACTION_VIEW), BAR, Tab.FEED);
        report = String.join(" | ", HookStatus.report());
        assertTrue(report, report.contains("Start tab skipped, not from the icon 1"));
    }

    @Test
    public void aTapAfterStartupIsNeverSwapped() {
        Settings.START_TAB.save(StartTab.SEARCH);
        TabStart.noteStart(launcher(), null);
        ReelsTab.tabs(BAR);
        TabStart.noteResumed();
        ShadowSystemClock.advanceBy(Duration.ofMillis(TabStart.STARTUP_MS + 1));
        assertSame("the person's first tap on Home", Tab.FEED, ReelsTab.tab(Tab.FEED));
        assertSame("and on Reels", Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
        assertSame(Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void theStartupRequestStillSwapsJustAfterTheFirstResume() {
        Settings.START_TAB.save(StartTab.SEARCH);
        TabStart.noteStart(launcher(), null);
        ReelsTab.tabs(BAR);
        TabStart.noteResumed();
        ShadowSystemClock.advanceBy(Duration.ofMillis(TabStart.STARTUP_MS - 1));
        assertSame(Tab.SEARCH, ReelsTab.tab(Tab.FEED));
    }
}
