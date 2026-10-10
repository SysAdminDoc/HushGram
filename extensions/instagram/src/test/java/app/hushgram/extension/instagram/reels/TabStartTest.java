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
        Settings.HIDE_REELS_TAB.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        Settings.HIDE_PROFILE_TAB.resetToDefault();
    }

    private static Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    /** The hooks as Instagram runs them: the list is built, then the host switches to its landing tab. */
    private static Object coldStart(Intent firstActivity, List<Tab> bar, Tab landing) {
        TabStart.noteFirstStart(firstActivity, null);
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
        TabStart.noteFirstStart(launcher(), new Bundle());
        ReelsTab.tabs(BAR);
        assertSame("a restored activity", Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void aNotificationThatOpensATabIsNeverReplacedEvenFromTheIcon() {
        Settings.START_TAB.save(StartTab.SEARCH);
        TabStart.noteFirstStart(launcher(), null);
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
        TabStart.noteFirstStart(launcher(), null);
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
            TabStart.noteFirstStart(launcher(), null);
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
        TabStart.noteFirstStart(launcher(), null);
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
}
