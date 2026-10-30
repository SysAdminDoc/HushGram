/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.settings.GhostHeaderButton;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/** The Messages button on Home's header (#156): when it goes in, where, what its tap opens, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class MessagesHeaderButtonTest {
    /** Named like Instagram's tab enum, which is all the hooks go by. */
    enum Tab { FEED, SEARCH, CREATION, CLIPS, DIRECT, PROFILE }

    /** A bar Instagram built with Messages on it, and one without. */
    private static final List<Tab> WITH_MESSAGES = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.DIRECT, Tab.PROFILE);
    private static final List<Tab> WITHOUT_MESSAGES = Arrays.asList(Tab.FEED, Tab.SEARCH, Tab.CLIPS, Tab.PROFILE);

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> screen;
    private Activity activity;
    private MainFeedActionBar header;
    private LinearLayout end;
    private ImageView heart;

    /** What each switch the button asked for was handed. */
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
        heart = new ImageView(header.getContext());
        heart.setPaddingRelative(6, 8, 6, 8);
        end.addView(heart);
        header.addView(end);
        // Messages is on the tab bar here, so the header has no Messages button of its own.
        header.setDirectInbox(null);
        HomeHeader.standInEndRowForTests(asked -> asked == header ? end : null);
        MessagesHeaderButton.iconForTests = context -> new ColorDrawable(0xFF000000);
        SearchHeaderButton.selectForTests = (on, tab) -> {
            switched.add(new Object[] {on, tab});
            return 1;
        };
        SearchHeaderButton.pagingForTests = on -> 0;
    }

    @After public void restore() {
        MessagesHeaderButton.iconForTests = null;
        SearchHeaderButton.selectForTests = null;
        SearchHeaderButton.pagingForTests = null;
        HomeHeader.standInEndRowForTests(null);
        TabStart.forgetForTests();
        Settings.MESSAGES_BUTTON_ON_HOME.resetToDefault();
        Settings.HIDE_HOME_MESSAGES_BUTTON.resetToDefault();
        Settings.SHOW_MESSAGES_TAB.resetToDefault();
        Settings.HIDE_MESSAGES_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private ImageView placed() {
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        MessagesHeaderButton.place(header);
        ImageView button = MessagesHeaderButton.in(end);
        assertNotNull(button);
        return button;
    }

    @Test public void theSwitchStartsOffAndAddsNothingToTheHeader() {
        assertEquals(Boolean.FALSE, Settings.MESSAGES_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.MESSAGES_BUTTON_ON_HOME.rebootApp);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    /** #298: Hide the Messages tab puts the button in without its own switch, and Hide Messages on Home's header still wins. */
    @Test public void hidingTheMessagesTabKeepsAWayInOnTheHeader() {
        Settings.HIDE_MESSAGES_TAB.save(true);
        MessagesHeaderButton.place(header);
        assertNotNull(MessagesHeaderButton.in(end));

        Settings.HIDE_HOME_MESSAGES_BUTTON.save(true);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));

        Settings.HIDE_HOME_MESSAGES_BUTTON.save(false);
        Settings.HIDE_MESSAGES_TAB.save(false);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
    }

    @Test public void withTheSwitchOnAndNoMessagesOnTheHeaderTheButtonGoesLastInTheEndRowOnce() {
        ImageView button = placed();
        assertEquals("last in the end row, where Instagram's own sits", 1, end.indexOfChild(button));
        assertEquals("the heart's padding", 6, button.getPaddingStart());
        assertEquals("the heart's padding", 8, button.getPaddingTop());
        assertEquals("Messages", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        assertTrue(button.getDrawable() instanceof ColorDrawable);
        assertEquals("Instagram's icon color for the header's theme, as the Search and ghost buttons use",
                new PorterDuffColorFilter(FeedDownloadButton.iconColor(header.getContext()), PorterDuff.Mode.SRC_IN),
                button.getColorFilter());

        MessagesHeaderButton.place(header);
        assertSame(button, MessagesHeaderButton.in(end));
        assertEquals(2, end.getChildCount());
        assertTrue(report(), report().contains(MessagesHeaderButton.PLACED + " 1"));
    }

    @Test public void instagramsOwnMessagesButtonOnTheHeaderMeansNoSecondOne() {
        ImageView own = new ImageView(header.getContext());
        end.addView(own);
        header.setDirectInbox(own);
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertEquals(2, end.getChildCount());

        // Instagram's button came back after HushGram's went in: the next draw takes HushGram's out.
        end.removeView(own);
        MessagesHeaderButton.place(header);
        assertNotNull(MessagesHeaderButton.in(end));
        end.addView(own);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertEquals(2, end.getChildCount());
    }

    /** #210: an account that still has Instagram's button on the header and Messages on the tab bar too. */
    @Test public void hideMessagesHidesInstagramsOwnButtonAndAddsNoneUntilItIsOffOrPaused() {
        ImageView own = new ImageView(header.getContext());
        end.addView(own);
        header.setDirectInbox(own);
        assertEquals(Boolean.FALSE, Settings.HIDE_HOME_MESSAGES_BUTTON.defaultValue);
        assertTrue(Settings.HIDE_HOME_MESSAGES_BUTTON.rebootApp);
        MessagesHeaderButton.place(header);
        assertEquals("off to start, Instagram's button stays", View.VISIBLE, own.getVisibility());

        Settings.HIDE_HOME_MESSAGES_BUTTON.save(true);
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        MessagesHeaderButton.place(header);
        assertEquals("hidden", View.GONE, own.getVisibility());
        assertSame("kept in its row, so the buttons beside it still find it", end, own.getParent());
        assertNull("and none of HushGram's goes in", MessagesHeaderButton.in(end));
        MessagesHeaderButton.place(header);
        assertEquals(View.GONE, own.getVisibility());
        assertTrue(report(), report().contains(MessagesHeaderButton.HIDDEN_OWN + " 1"));

        // Instagram shows it again as it rebinds the header: the next draw hides it again.
        own.setVisibility(View.VISIBLE);
        MessagesHeaderButton.place(header);
        assertEquals(View.GONE, own.getVisibility());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        MessagesHeaderButton.place(header);
        assertEquals("paused, it's back", View.VISIBLE, own.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        MessagesHeaderButton.place(header);
        assertEquals(View.GONE, own.getVisibility());
        Settings.HIDE_HOME_MESSAGES_BUTTON.save(false);
        MessagesHeaderButton.place(header);
        assertEquals("switched off, it's back", View.VISIBLE, own.getVisibility());
        assertNull("Instagram's shows, so still no second one", MessagesHeaderButton.in(end));
    }

    @Test public void hideMessagesLeavesAButtonInstagramHidItselfAloneAndKeepsHushGramsOut() {
        ImageView own = new ImageView(header.getContext());
        own.setVisibility(View.GONE);
        end.addView(own);
        header.setDirectInbox(own);
        Settings.HIDE_HOME_MESSAGES_BUTTON.save(true);
        MessagesHeaderButton.place(header);
        Settings.HIDE_HOME_MESSAGES_BUTTON.save(false);
        MessagesHeaderButton.place(header);
        assertEquals("Instagram hid it, so it stays hidden", View.GONE, own.getVisibility());

        // No button of Instagram's at all: Hide Messages keeps HushGram's out too.
        end.removeView(own);
        header.setDirectInbox(null);
        Settings.HIDE_HOME_MESSAGES_BUTTON.save(true);
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        Settings.HIDE_HOME_MESSAGES_BUTTON.save(false);
        MessagesHeaderButton.place(header);
        assertNotNull(MessagesHeaderButton.in(end));
    }

    @Test public void aMessagesButtonInstagramKeptButTookOffTheHeaderDoesNotCount() {
        header.setDirectInbox(new ImageView(header.getContext()));
        placed();
    }

    @Test public void turningTheSwitchOffOrPausingTakesTheButtonOutAtTheNextDraw() {
        placed();
        Settings.MESSAGES_BUTTON_ON_HOME.save(false);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));

        placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void withNoEndRowOrNoIconNothingGoesInAndItIsCounted() {
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        HomeHeader.standInEndRowForTests(asked -> null);
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertTrue(report(), report().contains(MessagesHeaderButton.NO_ROW + " 1"));
        MessagesHeaderButton.place(null);
        MessagesHeaderButton.place(new View(activity));

        HomeHeader.standInEndRowForTests(asked -> end);
        MessagesHeaderButton.iconForTests = context -> null;
        MessagesHeaderButton.place(header);
        assertNull(MessagesHeaderButton.in(end));
        assertTrue(report(), report().contains(MessagesHeaderButton.NO_ICON + " 1"));
    }

    @Test public void theIconIsLookedUpByNameAndAnAppWithoutItAnswersNull() {
        MessagesHeaderButton.iconForTests = null;
        assertEquals("instagram_direct_outline_24", MessagesHeaderButton.ICON);
        end.setId(View.generateViewId());
        Drawable icon = MessagesHeaderButton.icon(header.getContext(), end);
        assertNull("the test app has no Instagram icon", icon);
    }

    @Test public void aTapOpensTheMessagesTabOnTheBar() {
        ReelsTab.tabs(WITH_MESSAGES);
        placed().performClick();

        assertEquals(1, switched.size());
        assertSame("the activity behind the header's themed context", activity, switched.get(0)[0]);
        assertSame(Tab.DIRECT, switched.get(0)[1]);
        assertTrue(report(), report().contains(MessagesHeaderButton.OPENED + " 1"));
    }

    @Test public void swipingTabsWithMessagesOnTheBarStillOpenIt() {
        ReelsTab.tabs(WITH_MESSAGES);
        SearchHeaderButton.pagingForTests = on -> 1;
        placed().performClick();
        assertEquals(1, switched.size());
        assertSame(Tab.DIRECT, switched.get(0)[1]);
    }

    @Test public void offTheBarWhereTabsDontSwipeATapOpensMessagesByItsFragment() {
        ReelsTab.tabs(WITHOUT_MESSAGES);
        placed().performClick();
        assertEquals(1, switched.size());
        assertSame(Tab.DIRECT, switched.get(0)[1]);
        assertTrue(report(), report().contains(MessagesHeaderButton.OPENED_OFF_BAR + " 1"));
    }

    @Test public void offTheBarWhereTabsSwipeATapLeavesMessagesShutAndSaysWhatToDo() {
        ReelsTab.tabs(WITHOUT_MESSAGES);
        SearchHeaderButton.pagingForTests = on -> on == activity ? 1 : 0;
        ImageView button = placed();

        button.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("no switch was asked for", switched.isEmpty());
        assertTrue(report(), report().contains(MessagesHeaderButton.SWIPING + " 1"));
        // Android 12 and newer show two lines of a toast, so the toast is what to do and nothing else.
        assertEquals("To open Messages here, turn on Show the Messages tab and restart.",
                String.valueOf(ShadowToast.getTextOfLatestToast()));

        Settings.SHOW_MESSAGES_TAB.save(true);
        button.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("no switch was asked for", switched.isEmpty());
        assertEquals("Restart Instagram to put Messages on your tab bar.", String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    @Test public void beforeAnyTabListOrWithoutAHostATapIsCountedAndChangesNothing() {
        ImageView button = placed();
        button.performClick();
        assertTrue("no tab enum seen yet", switched.isEmpty());
        assertTrue(report(), report().contains(MessagesHeaderButton.NO_HOST + " 1"));

        ReelsTab.tabs(WITH_MESSAGES);
        SearchHeaderButton.selectForTests = (on, tab) -> 0;
        button.performClick();
        assertTrue(report(), report().contains(MessagesHeaderButton.NO_HOST + " 2"));

        SearchHeaderButton.selectForTests = (on, tab) -> {
            throw new IllegalStateException("no host");
        };
        button.performClick();
        assertTrue(report(), report().contains("messages button tap"));
    }

    @Test public void aPausedTapDoesNothing() {
        ReelsTab.tabs(WITH_MESSAGES);
        ImageView button = placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        button.performClick();
        assertTrue(switched.isEmpty());
    }

    @Test public void theHeaderHookPlacesItOnceTheMainLooperRuns() {
        Settings.MESSAGES_BUTTON_ON_HOME.save(true);
        activity.setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();

        ImageView button = MessagesHeaderButton.in(end);
        assertNotNull(button);
        assertEquals("heart, Messages", 2, end.getChildCount());
        assertSame(heart, end.getChildAt(0));
        assertSame(button, end.getChildAt(1));
        assertTrue(report(), report().contains(MessagesHeaderButton.PLACED + " 1"));
    }
}
