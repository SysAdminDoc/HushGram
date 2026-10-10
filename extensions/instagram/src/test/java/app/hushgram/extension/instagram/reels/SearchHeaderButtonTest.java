/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.GhostHeaderButton;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/** The Search button on Home's header: where it goes, what its tap opens, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class SearchHeaderButtonTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, SEARCH, CREATION, CLIPS, DIRECT, PROFILE }

    private static final List<Tab> BUILT = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CREATION, Tab.CLIPS, Tab.PROFILE);

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> screen;
    private Activity activity;
    private MainFeedActionBar header;
    private LinearLayout end;
    private ImageView inbox;

    /** What each switch the button asked for was handed, and what the switch hook answered inside it. */
    private final List<Object[]> switched = new ArrayList<>();

    @Before public void prepare() {
        TabStart.forgetForTests();
        HookStatus.clear();
        ShadowToast.reset();
        screen = Robolectric.buildActivity(Activity.class).setup();
        activity = screen.get();
        // Instagram's views carry a themed wrapper around the activity, which the tap looks through.
        header = new MainFeedActionBar(new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault));
        end = new LinearLayout(header.getContext());
        ImageView heart = new ImageView(header.getContext());
        heart.setPaddingRelative(4, 8, 4, 8);
        inbox = new ImageView(header.getContext());
        inbox.setPaddingRelative(7, 9, 7, 9);
        end.addView(heart);
        end.addView(inbox);
        header.addView(end);
        header.setDirectInbox(inbox);
        SearchHeaderButton.selectForTests = (on, tab) -> {
            switched.add(new Object[] {on, tab, ReelsTab.tab(tab)});
            return 1;
        };
        SearchHeaderButton.pagingForTests = on -> 0;
    }

    @After public void restore() {
        SearchHeaderButton.selectForTests = null;
        SearchHeaderButton.pagingForTests = null;
        HomeHeader.standInEndRowForTests(null);
        TabStart.forgetForTests();
        Settings.SEARCH_BUTTON_ON_HOME.resetToDefault();
        Settings.HIDE_SEARCH_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private static ImageView searchIn(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof SearchHeaderButton.Magnifier) {
                return (ImageView) child;
            }
        }
        return null;
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private ImageView placed() {
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        HomeHeader.standInEndRowForTests(asked -> asked == header ? end : null);
        SearchHeaderButton.place(header);
        ImageView button = searchIn(end);
        assertNotNull(button);
        return button;
    }

    @Test public void theSwitchStartsOffAndAddsNothingToTheHeader() {
        assertEquals(Boolean.FALSE, Settings.SEARCH_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.SEARCH_BUTTON_ON_HOME.rebootApp);
        HomeHeader.standInEndRowForTests(asked -> end);
        SearchHeaderButton.place(header);
        assertNull(searchIn(end));
        assertEquals(2, end.getChildCount());
    }

    @Test public void withTheSwitchOnTheButtonGoesFirstInTheEndRowOnce() {
        ImageView button = placed();
        assertEquals("first in the end row", 0, end.indexOfChild(button));
        assertEquals(3, end.getChildCount());
        assertEquals("Messages' padding", 7, button.getPaddingStart());
        assertEquals("Messages' padding", 9, button.getPaddingTop());
        assertEquals("Search", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        SearchHeaderButton.Magnifier icon = (SearchHeaderButton.Magnifier) button.getDrawable();
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals("the ghost button's size", Math.round(24 * density), icon.getIntrinsicWidth());
        assertEquals("the ghost button's color in light mode", 0xFF262626, icon.color());

        SearchHeaderButton.place(header);
        assertSame(button, searchIn(end));
        assertEquals(3, end.getChildCount());
        assertTrue(report(), report().contains(SearchHeaderButton.PLACED + " 1"));
    }

    @Test public void withoutTheEndRowTheButtonGoesRightBeforeMessages() {
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        SearchHeaderButton.place(header);
        ImageView button = searchIn(end);
        assertNotNull(button);
        assertEquals("right before Messages", end.indexOfChild(inbox) - 1, end.indexOfChild(button));
    }

    @Test public void withNoRowAtAllItIsLeftAloneAndCounted() {
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        header.setDirectInbox(null);
        SearchHeaderButton.place(header);
        assertNull(searchIn(end));
        assertTrue(report(), report().contains(SearchHeaderButton.NO_ROW + " 1"));
        SearchHeaderButton.place(null);
        SearchHeaderButton.place(new View(activity));
    }

    @Test public void anEmptyEndRowGetsTheButtonWithItsOwnPadding() {
        LinearLayout empty = new LinearLayout(activity);
        header.addView(empty);
        header.setDirectInbox(null);
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        HomeHeader.standInEndRowForTests(asked -> empty);
        SearchHeaderButton.place(header);
        ImageView button = searchIn(empty);
        assertNotNull(button);
        int pad = Math.round(10 * activity.getResources().getDisplayMetrics().density);
        assertEquals(pad, button.getPaddingStart());
        assertEquals(pad, button.getPaddingTop());
    }

    @Test public void turningTheSwitchOffOrPausingTakesTheButtonOutAtTheNextDraw() {
        placed();
        Settings.SEARCH_BUTTON_ON_HOME.save(false);
        SearchHeaderButton.place(header);
        assertNull(searchIn(end));

        placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SearchHeaderButton.place(header);
        assertNull(searchIn(end));
        assertEquals(2, end.getChildCount());
    }

    @Test public void aTapSelectsTheSearchTabOnTheBar() {
        ReelsTab.tabs(BUILT);
        placed().performClick();

        assertEquals(1, switched.size());
        assertSame("the activity behind the header's themed context", activity, switched.get(0)[0]);
        assertSame(Tab.SEARCH, switched.get(0)[1]);
        assertSame(Tab.SEARCH, switched.get(0)[2]);
        assertTrue(report(), report().contains(SearchHeaderButton.OPENED + " 1"));
    }

    @Test public void withTheSearchTabHiddenATapStillOpensSearchAndOnlyThatSwitchGetsThrough() {
        Settings.HIDE_SEARCH_TAB.save(true);
        List<?> shown = ReelsTab.tabs(BUILT);
        assertTrue("Search is off the bar", !shown.contains(Tab.SEARCH));
        assertSame("a switch to a hidden Search lands on Home", Tab.FEED, ReelsTab.tab(Tab.SEARCH));

        placed().performClick();

        assertEquals(1, switched.size());
        assertSame(Tab.SEARCH, switched.get(0)[1]);
        assertSame("the switch hook lets the button's own switch through", Tab.SEARCH, switched.get(0)[2]);
        assertTrue(report(), report().contains(SearchHeaderButton.OPENED_OFF_BAR + " 1"));
        assertSame("the next switch to Search lands on Home again", Tab.FEED, ReelsTab.tab(Tab.SEARCH));
    }

    @Test public void withTheSearchTabHiddenAndSwipingTabsATapLeavesSearchShutAndSaysWhy() {
        Settings.HIDE_SEARCH_TAB.save(true);
        ReelsTab.tabs(BUILT);
        SearchHeaderButton.pagingForTests = on -> on == activity ? 1 : 0;

        placed().performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("no switch was asked for", switched.isEmpty());
        assertTrue(report(), report().contains(SearchHeaderButton.SWIPING + " 1"));
        assertEquals("Search isn't on your tab bar, so this button can't open it here. "
                + "If Hide the Search tab is on, turn it off and restart Instagram.", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertSame(Tab.FEED, ReelsTab.tab(Tab.SEARCH));
    }

    @Test public void swipingTabsWithSearchOnTheBarStillSelectIt() {
        ReelsTab.tabs(BUILT);
        SearchHeaderButton.pagingForTests = on -> 1;
        placed().performClick();
        assertEquals(1, switched.size());
        assertTrue(report(), report().contains(SearchHeaderButton.OPENED + " 1"));
    }

    @Test public void beforeAnyTabListOrWithoutAHostATapIsCountedAndChangesNothing() {
        ImageView button = placed();
        button.performClick();
        assertTrue("no tab enum seen yet", switched.isEmpty());
        assertTrue(report(), report().contains(SearchHeaderButton.NO_HOST + " 1"));

        ReelsTab.tabs(BUILT);
        SearchHeaderButton.selectForTests = (on, tab) -> 0;
        button.performClick();
        assertTrue(report(), report().contains(SearchHeaderButton.NO_HOST + " 2"));
    }

    @Test public void aSwitchThatThrowsStillStopsLettingSearchThrough() {
        Settings.HIDE_SEARCH_TAB.save(true);
        ReelsTab.tabs(BUILT);
        SearchHeaderButton.selectForTests = (on, tab) -> {
            throw new IllegalStateException("no host");
        };
        placed().performClick();
        assertSame(Tab.FEED, ReelsTab.tab(Tab.SEARCH));
    }

    @Test public void aPausedTapDoesNothing() {
        ReelsTab.tabs(BUILT);
        ImageView button = placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        button.performClick();
        assertTrue(switched.isEmpty());
    }

    @Test public void theStubsAnswerNothingUnpatched() {
        assertEquals(0, SearchHeaderButton.select(activity, Tab.SEARCH));
        assertEquals(0, SearchHeaderButton.paging(activity));
        assertNull(HomeHeader.endRow(header));
        assertNull("unpatched, the header has no end row", HomeHeader.endRowOf(header));
        assertNull(SearchHeaderButton.activityOf(null));
        assertSame(activity, SearchHeaderButton.activityOf(header.getContext()));
    }

    @Test public void theHeaderHookPlacesTheButtonOnceTheMainLooperRuns() {
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        HomeHeader.standInEndRowForTests(asked -> end);
        activity.setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        assertNotNull(searchIn(end));
        assertEquals(0, end.indexOfChild(searchIn(end)));
    }
}
