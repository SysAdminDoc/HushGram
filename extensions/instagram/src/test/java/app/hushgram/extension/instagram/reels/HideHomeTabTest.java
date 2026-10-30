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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide the Home tab does to the bar, the switch and the back stack's root, and when it keeps Home (#90). */
@RunWith(RobolectricTestRunner.class)
public class HideHomeTabTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, FEED_SWITCHER, SEARCH, CREATION, CLIPS, DIRECT, NEWS, PROFILE }

    /** Another enum with a Home of its own, which the stand-in must never be handed out for. */
    enum Other { FEED }

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
        Settings.HIDE_HOME_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        Settings.HIDE_REELS_TAB.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        Settings.SHOW_MESSAGES_TAB.resetToDefault();
        HookStatus.clear();
    }

    private static Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    private static Intent link() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/p/abc/"));
    }

    private static void hideHomeFor(StartTab start) {
        Settings.START_TAB.save(start);
        Settings.HIDE_HOME_TAB.save(true);
    }

    private static String report() {
        return String.join(" | ", HookStatus.report());
    }

    @Test
    public void offToStartAndThenEveryTabGoesThroughAsItCame() {
        assertFalse(Settings.HIDE_HOME_TAB.get());
        Settings.START_TAB.save(StartTab.SEARCH);

        assertSame(BAR, ReelsTab.tabs(BAR));
        assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        assertSame(Tab.FEED_SWITCHER, ReelsTab.root(Tab.FEED_SWITCHER));
        assertFalse(ReelsTab.startAtTab(0));
    }

    @Test
    public void homeLeavesTheBarAndTheStartTabStandsInWhereverHomeWouldOpen() {
        hideHomeFor(StartTab.SEARCH);
        List<Tab> built = new ArrayList<>(BAR);

        assertEquals(Arrays.asList(Tab.SEARCH, Tab.CREATION, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(built));
        assertEquals("the built list was changed", BAR, built);

        assertSame("the back stack's root", Tab.SEARCH, ReelsTab.root(Tab.FEED));
        assertSame("the feed switcher as root", Tab.SEARCH, ReelsTab.root(Tab.FEED_SWITCHER));
        assertSame("a switch to Home", Tab.SEARCH, ReelsTab.tab(Tab.FEED));
        assertSame(Tab.PROFILE, ReelsTab.root(Tab.PROFILE));
        assertSame(Tab.CLIPS, ReelsTab.tab(Tab.CLIPS));
        assertTrue("the pager opens on the asked tab", ReelsTab.startAtTab(0));
        String report = report();
        assertTrue(report, report.contains(ReelsTab.HOME_HIDDEN + " 1"));
        assertTrue(report, report.contains(ReelsTab.HOME_REPLACED));
        assertTrue(report, report.contains(ReelsTab.PAGER_STARTED_WITHOUT_HOME));
    }

    @Test
    public void aColdStartFromTheIconOrALinkLandsOnTheStartTab() {
        hideHomeFor(StartTab.PROFILE);
        TabStart.noteStart(launcher(), null);
        ReelsTab.tabs(BAR);
        assertSame("from the icon", Tab.PROFILE, ReelsTab.tab(Tab.FEED));
        assertSame("later, Back to Home", Tab.PROFILE, ReelsTab.tab(Tab.FEED));

        TabStart.forgetForTests();
        TabStart.noteStart(link(), null);
        ReelsTab.tabs(BAR);
        assertSame("from a link", Tab.PROFILE, ReelsTab.tab(Tab.FEED));
        assertSame("a saved back stack's Home", Tab.PROFILE, ReelsTab.root(Tab.FEED));
    }

    @Test
    public void aHiddenTabsRedirectGoesToTheStartTabRatherThanHome() {
        hideHomeFor(StartTab.SEARCH);
        Settings.HIDE_REELS_TAB.save(true);

        assertEquals(Arrays.asList(Tab.SEARCH, Tab.CREATION, Tab.PROFILE), ReelsTab.tabs(BAR));
        assertSame(Tab.SEARCH, ReelsTab.tab(Tab.CLIPS));
    }

    @Test
    public void theFeedSwitcherLeavesWithHome() {
        hideHomeFor(StartTab.MESSAGES);
        List<Tab> switcher = Arrays.asList(Tab.FEED_SWITCHER, Tab.SEARCH, Tab.DIRECT, Tab.PROFILE);

        assertEquals(Arrays.asList(Tab.SEARCH, Tab.DIRECT, Tab.PROFILE), ReelsTab.tabs(switcher));
        assertSame(Tab.DIRECT, ReelsTab.root(Tab.FEED_SWITCHER));
        assertSame(Tab.DIRECT, ReelsTab.root(Tab.FEED));
    }

    @Test
    public void aMessagesTabPutOnTheBarCanStandIn() {
        Settings.SHOW_MESSAGES_TAB.save(true);
        hideHomeFor(StartTab.MESSAGES);

        assertEquals(Arrays.asList(Tab.SEARCH, Tab.CREATION, Tab.CLIPS, Tab.DIRECT, Tab.PROFILE), ReelsTab.tabs(BAR));
        assertSame(Tab.DIRECT, ReelsTab.root(Tab.FEED));
    }

    @Test
    public void aStartTabOfHomeKeepsHomeAndSaysWhy() {
        for (StartTab start : new StartTab[] {StartTab.HOME, StartTab.ALWAYS_HOME}) {
            HookStatus.clear();
            hideHomeFor(start);

            assertSame(start.name(), BAR, ReelsTab.tabs(BAR));
            assertSame(start.name(), Tab.FEED, ReelsTab.root(Tab.FEED));
            assertFalse(start.name(), ReelsTab.startAtTab(0));
            String report = report();
            assertTrue(report, report.contains(ReelsTab.HOME_KEPT_FOR_START + " 1"));
        }
    }

    @Test
    public void aStartTabThatIsNotOnTheBarKeepsHomeAndSaysWhy() {
        hideHomeFor(StartTab.MESSAGES);
        assertSame("Instagram left Messages off", BAR, ReelsTab.tabs(BAR));
        assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        assertSame(Tab.FEED, ReelsTab.tab(Tab.FEED));

        Settings.START_TAB.save(StartTab.SEARCH);
        Settings.HIDE_SEARCH_TAB.save(true);
        assertEquals("Search hidden by its own switch", Arrays.asList(Tab.FEED, Tab.CREATION, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(BAR));
        assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        assertSame("a hidden Search still lands on Home", Tab.FEED, ReelsTab.tab(Tab.SEARCH));
        String report = report();
        assertTrue(report, report.contains(ReelsTab.HOME_KEPT_OFF_BAR + " 2"));
    }

    @Test
    public void pauseAndTurningItOffPutHomeBack() {
        hideHomeFor(StartTab.SEARCH);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertSame(BAR, ReelsTab.tabs(BAR));
            assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        } finally {
            PauseForTests.resume();
        }

        ReelsTab.tabs(BAR);
        assertSame(Tab.SEARCH, ReelsTab.root(Tab.FEED));
        Settings.HIDE_HOME_TAB.save(false);
        assertSame("the next bar Instagram builds", BAR, ReelsTab.tabs(BAR));
        assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        assertSame(Tab.FEED, ReelsTab.tab(Tab.FEED));
    }

    @Test
    public void beforeTheSettingsAreReadyHomeStays() {
        hideHomeFor(StartTab.SEARCH);
        SettingsContextRule.withoutContext(() -> {
            assertSame(BAR, ReelsTab.tabs(BAR));
            assertSame(Tab.FEED, ReelsTab.root(Tab.FEED));
        });
    }

    @Test
    public void theRootLeavesWhatIsNotThisBarsHomeAlone() {
        assertNull(ReelsTab.root(null));
        hideHomeFor(StartTab.SEARCH);
        ReelsTab.tabs(BAR);

        assertEquals("FEED", ReelsTab.root("FEED"));
        assertSame("another enum's Home", Other.FEED, ReelsTab.root(Other.FEED));
        List<String> names = Arrays.asList("FEED", "SEARCH");
        assertSame("names that are not tabs stay", names, ReelsTab.tabs(names));
        assertSame("and nothing stands in after them", Tab.FEED, ReelsTab.root(Tab.FEED));
    }
}
