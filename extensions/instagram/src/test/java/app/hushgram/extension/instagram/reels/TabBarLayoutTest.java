/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the tab bar gate answers for each choice, the classic bar's quick search wait, and that paused and not ready are Instagram's own (#356, #383). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class TabBarLayoutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void clean() {
        Settings.TAB_BAR_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TAB_BAR_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    /** The choice starts at Instagram's, so either bar Instagram picks stays and nothing is counted. */
    @Test
    public void theChoiceStartsAtInstagramsBar() {
        assertEquals(TabBarLayoutChoice.INSTAGRAM, Settings.TAB_BAR_LAYOUT.get());
        assertTrue(TabBarLayout.newTabBar(1));
        assertFalse(TabBarLayout.newTabBar(0));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.TAB_BAR_LAYOUT + ": invoked 2"));
        assertFalse(report, report.contains(TabBarLayout.SWIPE));
        assertFalse(report, report.contains(TabBarLayout.CLASSIC));
    }

    /** Swipe answers the newer bar whatever Instagram picked, and counts only the answers it turned. */
    @Test
    public void swipeKeepsTheNewerBarAndCountsTheTurnedAnswers() {
        Settings.TAB_BAR_LAYOUT.save(TabBarLayoutChoice.SWIPE);
        assertTrue(TabBarLayout.newTabBar(0));
        assertTrue(TabBarLayout.newTabBar(0));
        assertTrue(TabBarLayout.newTabBar(1));
        String report = report();
        assertTrue(report, report.contains(TabBarLayout.SWIPE + " 2"));
        assertFalse(report, report.contains(TabBarLayout.CLASSIC));
    }

    /** Classic answers the older bar whatever Instagram picked. */
    @Test
    public void classicKeepsTheOlderBarAndCountsTheTurnedAnswers() {
        Settings.TAB_BAR_LAYOUT.save(TabBarLayoutChoice.CLASSIC);
        assertFalse(TabBarLayout.newTabBar(1));
        assertFalse(TabBarLayout.newTabBar(0));
        String report = report();
        assertTrue(report, report.contains(TabBarLayout.CLASSIC + " 1"));
        assertFalse(report, report.contains(TabBarLayout.SWIPE));
    }

    /** Paused, a saved choice answers Instagram's own and is kept for when HushGram comes back. */
    @Test
    public void pausedHushGramIsInstagramsOwnBar() {
        Settings.TAB_BAR_LAYOUT.save(TabBarLayoutChoice.SWIPE);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(TabBarLayout.newTabBar(0));
        assertEquals("the saved choice is kept", TabBarLayoutChoice.SWIPE, Settings.TAB_BAR_LAYOUT.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(TabBarLayout.newTabBar(0));
    }

    /** With no swipe pager, a long press on Search waits for no pages; paused or not ready it's Instagram's own wait (#383). */
    @Test
    public void quickSearchOnTheClassicBarWaitsForNoSwipePages() {
        assertEquals(0, TabBarLayout.quickSearchPages(5));
        String report = report();
        assertTrue(report, report.contains(TabBarLayout.QUICK_SEARCH + " 1"));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(5, TabBarLayout.quickSearchPages(5));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals(5, TabBarLayout.quickSearchPages(5)));
        report = report();
        assertTrue(report, report.contains(TabBarLayout.QUICK_SEARCH + " 1"));
    }

    /** Before the settings are ready the hook can't read the choice and keeps Instagram's answer. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsOwnBar() {
        Settings.TAB_BAR_LAYOUT.save(TabBarLayoutChoice.SWIPE);
        SettingsContextRule.withoutContext(() -> assertFalse(TabBarLayout.newTabBar(0)));
        SettingsContextRule.withoutContext(() -> assertTrue(TabBarLayout.newTabBar(1)));
    }
}
