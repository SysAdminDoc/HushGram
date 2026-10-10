/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

/** The Tab order choice: the order the tab list hook hands the bar back in. */
@RunWith(RobolectricTestRunner.class)
public class TabOrderTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, SEARCH, CREATION, CLIPS, DIRECT, NEWS, PROFILE }

    private static final List<Tab> BUILT = Collections.unmodifiableList(
            Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.DIRECT, Tab.PROFILE));

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        TabOrder.forgetForTests();
        TabStart.forgetForTests();
        HookStatus.clear();
    }

    @After public void restore() {
        TabOrder.forgetForTests();
        TabStart.forgetForTests();
        Settings.TAB_ORDER.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test public void theChoiceStartsEmptyAndLeavesTheListAsInstagramBuiltIt() {
        assertEquals("", Settings.TAB_ORDER.defaultValue);
        assertTrue(Settings.TAB_ORDER.rebootApp);
        assertSame(BUILT, ReelsTab.tabs(BUILT));
    }

    @Test public void theNamedTabsComeFirstInTheOrderChosenAndTheRestKeepInstagramsOrder() {
        Settings.TAB_ORDER.save("DIRECT,SEARCH");
        List<?> shown = ReelsTab.tabs(BUILT);
        assertEquals(Arrays.asList(Tab.DIRECT, Tab.SEARCH, Tab.FEED, Tab.CLIPS, Tab.PROFILE), shown);
        assertNotSame("a copy", BUILT, shown);
        assertEquals("the built list is never changed", Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.DIRECT, Tab.PROFILE), BUILT);
        assertTrue(String.join("\n", HookStatus.report()), String.join("\n", HookStatus.report()).contains(TabOrder.APPLIED + " 1"));
    }

    @Test public void aFullOrderIsFollowedAndAnOrderAlreadyInPlaceChangesNothing() {
        Settings.TAB_ORDER.save("PROFILE,DIRECT,CLIPS,SEARCH,FEED");
        assertEquals(Arrays.asList(Tab.PROFILE, Tab.DIRECT, Tab.CLIPS, Tab.SEARCH, Tab.FEED), ReelsTab.tabs(BUILT));

        Settings.TAB_ORDER.save("FEED,SEARCH");
        assertSame("already in that order", BUILT, ReelsTab.tabs(BUILT));
    }

    @Test public void tabsNotOnTheBarAreSkippedAndAHiddenTabStaysHidden() {
        Settings.HIDE_SEARCH_TAB.save(true);
        Settings.TAB_ORDER.save("SEARCH,CREATION,DIRECT");
        assertEquals(Arrays.asList(Tab.DIRECT, Tab.FEED, Tab.CLIPS, Tab.PROFILE), ReelsTab.tabs(BUILT));
    }

    @Test public void tabsTheChoiceDoesntNameStayInTheirPlaceAfterTheNamedOnes() {
        List<Tab> withNews = Arrays.asList(Tab.FEED, Tab.NEWS, Tab.SEARCH, Tab.PROFILE);
        assertEquals(Arrays.asList(Tab.PROFILE, Tab.FEED, Tab.NEWS, Tab.SEARCH), TabOrder.ordered(withNews, Arrays.asList("PROFILE")));
    }

    @Test public void theBarAsTheStartTabSeesItIsTheReorderedOne() {
        Settings.TAB_ORDER.save("DIRECT");
        ReelsTab.tabs(BUILT);
        assertTrue(TabStart.isOnTheBar("DIRECT"));
        assertTrue(TabStart.isOnTheBar("FEED"));
        assertSame(Tab.SEARCH, TabStart.tabNamed("SEARCH"));
    }

    @Test public void pausedTheListGoesThroughAsBuilt() {
        Settings.TAB_ORDER.save("DIRECT,SEARCH");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(BUILT, ReelsTab.tabs(BUILT));
    }

    @Test public void aListHoldingSomethingElseOrNothingGoesThroughAsItCame() {
        List<Object> mixed = new ArrayList<>(Arrays.asList(Tab.FEED, "not a tab", Tab.DIRECT));
        assertSame(mixed, TabOrder.ordered(mixed, Arrays.asList("DIRECT")));
        List<Tab> empty = Collections.emptyList();
        assertSame(empty, TabOrder.ordered(empty, Arrays.asList("DIRECT")));
        assertSame(BUILT, TabOrder.ordered(BUILT, Collections.emptyList()));
    }

    @Test public void theSavedTextIsReadForTheTabsItKnowsOnlyOnceEach() {
        assertEquals(Collections.emptyList(), TabOrder.parse(null));
        assertEquals(Collections.emptyList(), TabOrder.parse("  "));
        assertEquals(Arrays.asList("DIRECT", "FEED"), TabOrder.parse(" DIRECT , ,NEWS,FEED,DIRECT,feed"));
        assertEquals("DIRECT,FEED", TabOrder.join(Arrays.asList("DIRECT", "FEED")));
    }

    @Test public void theSettingsListShowsTheBarAsLastBuiltInTheOrderChosen() {
        assertEquals("every tab before a bar is built", TabOrder.TABS, TabOrder.choices(""));
        assertEquals(Arrays.asList("CREATION", "FEED", "SEARCH", "CLIPS", "DIRECT", "PROFILE"), TabOrder.choices("CREATION"));

        Settings.HIDE_SEARCH_TAB.save(true);
        ReelsTab.tabs(BUILT);
        assertEquals("the bar Instagram built, without the hidden tab", Arrays.asList("FEED", "CLIPS", "DIRECT", "PROFILE"), TabOrder.choices(""));
        assertEquals(Arrays.asList("PROFILE", "FEED", "CLIPS", "DIRECT"), TabOrder.choices("SEARCH,PROFILE"));
    }

    @Test public void aTapMovesATabOnePlaceUpAndTheFirstStays() {
        List<String> names = Arrays.asList("FEED", "SEARCH", "DIRECT");
        assertEquals(Arrays.asList("FEED", "DIRECT", "SEARCH"), TabOrder.movedUp(names, 2));
        assertEquals(names, TabOrder.movedUp(names, 0));
        assertEquals(names, TabOrder.movedUp(names, 7));
        assertEquals("the list handed in is left as it was", Arrays.asList("FEED", "SEARCH", "DIRECT"), names);
    }
}
