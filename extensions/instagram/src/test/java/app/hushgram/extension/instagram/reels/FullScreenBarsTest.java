/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Full screen Reels and Home: the bars hide on the screens the switches name and come back when they're left. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FullScreenBarsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7101, BAR = 7102, REELS_TAB = 7103, HOME_TAB = 7104, OTHER_TAB = 7105,
            INBOX = 7106, CHAT = 7107;

    /** The window's own size: a view is only on screen as far as the window goes. */
    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View viewer;
    private ViewGroup bar;
    private View reelsTab, homeTab, otherTab;

    @Before public void prepare() {
        FullScreenBars.resetHeightsForTests(null);
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        Settings.FULL_SCREEN_MESSAGES.resetToDefault();
        Settings.FULL_SCREEN_HIDES.resetToDefault();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_FLOAT.resetToDefault();
        FullScreenBars.idForTests(FullScreenBars.INBOX, INBOX);
        FullScreenBars.idForTests(FullScreenBars.CHAT, CHAT);
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        reelsTab = tab(REELS_TAB);
        homeTab = tab(HOME_TAB);
        otherTab = tab(OTHER_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
        layout();
    }

    @After public void close() {
        FullScreenBars.release(activity);
        controller.close();
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        Settings.FULL_SCREEN_MESSAGES.resetToDefault();
        Settings.FULL_SCREEN_HIDES.resetToDefault();
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_FLOAT.resetToDefault();
    }

    private View tab(int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void showViewer(int width, int height) {
        if (viewer != null) root.removeView(viewer);
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        root.addView(viewer, new FrameLayout.LayoutParams(width, height));
        layout();
    }

    private void select(View tab) {
        reelsTab.setSelected(false);
        homeTab.setSelected(false);
        otherTab.setSelected(false);
        tab.setSelected(true);
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private FullScreenBars.Session watch() {
        FullScreenBars.track(activity);
        FullScreenBars.Session session = FullScreenBars.sessionFor(activity);
        assertNotNull(session);
        session.evaluate();
        return session;
    }

    private boolean barsGone() {
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        return (flags & FullScreenBars.IMMERSIVE_FLAGS) == FullScreenBars.IMMERSIVE_FLAGS;
    }

    private FullScreenBars.Screen screen() {
        return FullScreenBars.screenOf(activity.getWindow().getDecorView());
    }

    @Test public void bothSwitchesStartOffAndNothingHides() {
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        showViewer(W, H);
        assertFalse(watch().isHidden());
        assertFalse(barsGone());
    }

    @Test public void aViewerFillingTheWindowHidesTheBarsAndLeavingItBringsThemBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertTrue(barsGone());
        root.removeView(viewer);
        viewer = null;
        layout();
        session.evaluate();
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    /**
     * #147: Instagram's insets listener keeps each bar's height while the bar is hidden, so the
     * screen kept an empty strip for it. While HushGram has the bars hidden the heights come back 0,
     * and once they show again, or with nothing hidden, they go through as they came.
     */
    @Test public void hiddenBarsLeaveNoSpaceBehind() {
        assertEquals(128, FullScreenBars.barHeight(128));
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        assertEquals("the status bar's space stayed", 0, FullScreenBars.barHeight(128));
        assertEquals("the navigation bar's space stayed", 0, FullScreenBars.navBarHeight(63));
        assertEquals("an unset height changed", -1, FullScreenBars.barHeight(-1));
        assertEquals("an unset navigation bar height changed", -1, FullScreenBars.navBarHeight(-1));
        // #285: a floating glass pill keeps the hidden bar's room, the height offered or the last one.
        assertEquals(63, FullScreenBars.hiddenNavHeight(63));
        assertEquals("Instagram's 0 offered back", 63, FullScreenBars.hiddenNavHeight(0));
        assertEquals(0, FullScreenBars.hiddenNavHeight(-1));

        root.removeView(viewer);
        viewer = null;
        layout();
        session.evaluate();
        assertFalse(session.isHidden());
        assertEquals("the bars came back without their space", 128, FullScreenBars.barHeight(128));
        assertEquals(63, FullScreenBars.barHeight(63));
        assertEquals("without a glass pill the navigation bar's height goes through", 63, FullScreenBars.navBarHeight(63));
        assertEquals(63, GlassTabBar.navBarHeight(63));
        assertEquals("a bar that shows isn't kept room for", 0, FullScreenBars.hiddenNavHeight(63));
    }

    /**
     * #209: the keyboard brings the navigation bar back, and Instagram lifts a comment box above it
     * by the keyboard's height less that bar's, so the 0 kept the box behind the keys. While the
     * keyboard shows the height goes through, and once it closes the hidden bars take it again.
     */
    @Test public void theKeyboardGetsTheNavigationBarsHeight() {
        boolean[] up = {false};
        FullScreenBars.keyboardForTests(() -> up[0]);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        assertTrue(watch().isHidden());
        assertEquals(0, FullScreenBars.navBarHeight(63));

        up[0] = true;
        assertEquals("the comment box sat behind the keyboard", 63, FullScreenBars.navBarHeight(63));
        assertEquals("the status bar stays hidden", 0, FullScreenBars.barHeight(128));
        assertEquals("an unset height changed", -1, FullScreenBars.navBarHeight(-1));
        assertArrayEquals("a height that went through isn't owed", new int[] {128, 0}, FullScreenBars.heightsToGiveBack());

        up[0] = false;
        assertEquals("the keyboard closed and the strip came back", 0, FullScreenBars.navBarHeight(63));
    }

    /**
     * #288: in a chat that opened with the bars hidden, Instagram offers back the 0 it kept as the
     * keyboard comes up, or isn't asked at all, so its message box sat a navigation bar's height
     * behind the keys. The height last offered goes through for a 0 offer while the keyboard shows,
     * and a keyboard seen at a frame gives it back to the kept screens itself. When the keyboard goes,
     * the hidden bar takes it again.
     */
    @Test public void theKeyboardInAChatOpenedHiddenGetsTheHeightBack() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        boolean[] up = {false};
        FullScreenBars.keyboardForTests(() -> up[0]);
        try {
            Settings.FULL_SCREEN_REELS.save(true);
            showViewer(W, H);
            FullScreenBars.Session session = watch();
            assertTrue(session.isHidden());
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, "chat");
            heights(128, 63);
            assertEquals(Collections.singletonList("chat 0 0"), told);

            up[0] = true;
            session.watchKeyboard();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("a frame with the keyboard up gives the height back", "chat 0 63", told.get(told.size() - 1));
            up[0] = false;
            session.watchKeyboard();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the keyboard went and the hidden bar took it again", "chat 0 0", told.get(told.size() - 1));
            assertArrayEquals(new int[] {128, 63}, FullScreenBars.heightsToGiveBack());
            heights(128, 63);

            up[0] = true;
            assertEquals(0, FullScreenBars.barHeight(0));
            assertEquals("a 0 offered back with the keyboard up", 63, FullScreenBars.navBarHeight(0));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("chat 0 63", told.get(told.size() - 1));
            up[0] = false;
            assertEquals(0, FullScreenBars.navBarHeight(63));
            assertEquals("still hidden, a 0 stays 0", 0, FullScreenBars.navBarHeight(0));
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /**
     * #196: a chat opens in an activity of its own and reads the bar heights Instagram kept before
     * that window's insets reach the listener, so each height answered 0 is owed back as the activity
     * pauses, at the height Instagram last offered for it, and only once.
     */
    @Test public void aPauseGivesBackTheHeightsAnsweredZero() {
        assertEquals(128, FullScreenBars.barHeight(128));
        assertEquals(63, FullScreenBars.navBarHeight(63));
        assertArrayEquals("nothing was answered 0", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());

        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        assertEquals(0, FullScreenBars.barHeight(128));
        assertEquals(0, FullScreenBars.navBarHeight(63));
        // While the bars are hidden Instagram offers what it kept, the 0 it was given.
        assertEquals(0, FullScreenBars.barHeight(0));
        assertEquals(0, FullScreenBars.navBarHeight(0));
        assertArrayEquals("the heights Instagram last offered", new int[] {128, 63}, FullScreenBars.heightsToGiveBack());
        assertArrayEquals("owed once", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());

        assertEquals(0, FullScreenBars.navBarHeight(63));
        FullScreenBars.giveBackBarHeights();
        assertArrayEquals("the pause paid it", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());

        assertEquals(0, FullScreenBars.navBarHeight(63));
        FullScreenBars.release(activity);
        assertEquals("the bars came back", 63, FullScreenBars.navBarHeight(63));
        assertArrayEquals("a height that went through isn't owed", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());
    }

    /** #196: coming back under a floating pill, Instagram's copy goes back to 0 once, and is owed back again. */
    @Test public void aPillComingBackTakesTheNavigationBarsHeightOnce() {
        FullScreenBars.takeNavBarHeight();
        assertArrayEquals("nothing offered yet, nothing taken", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());
        assertEquals(63, FullScreenBars.navBarHeight(63));
        FullScreenBars.takeNavBarHeight();
        FullScreenBars.takeNavBarHeight();
        assertArrayEquals("taken, so owed back once", new int[] {0, 63}, FullScreenBars.heightsToGiveBack());
        assertArrayEquals(new int[] {0, 0}, FullScreenBars.heightsToGiveBack());
    }

    /**
     * #200: on a cold start into Reels the viewer's bar heights listener heard the bars' heights
     * before Full screen Reels hid them, and Instagram let it go, so the strips stayed. While a switch
     * is on, an activity's listeners are kept and told each new pair once Instagram's pass is over.
     */
    @Test public void keptListenersHearTheHeightsTheHideBrings() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Object reels = "reels";
            FullScreenBars.heightsAsked(activity, reels);
            assertTrue("both switches off, nothing kept", FullScreenBars.askersForTests(activity).isEmpty());

            Settings.FULL_SCREEN_REELS.save(true);
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, reels);
            FullScreenBars.heightsAsked(activity, reels);
            assertEquals("kept once", 1, FullScreenBars.askersForTests(activity).size());

            heights(128, 63);
            assertEquals(Collections.singletonList("reels 128 63"), told);
            heights(128, 63);
            assertEquals("the same pair isn't told again", 1, told.size());

            showViewer(W, H);
            assertTrue(watch().isHidden());
            heights(128, 63);
            assertEquals("the hide's pair", "reels 0 0", told.get(1));

            FullScreenBars.heightsDropped(activity, reels);
            assertTrue("dropped by Instagram", FullScreenBars.askersForTests(activity).isEmpty());
            FullScreenBars.release(activity);
            heights(128, 63);
            assertEquals("a dropped listener isn't told", 2, told.size());
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /**
     * #200: back on Reels from another window, Instagram's copy holds the heights the pause gave back
     * and its listener keeps them while the bars are hidden, so the hide takes both to 0 itself and the
     * kept listeners hear it.
     */
    @Test public void comingBackToReelsTakesTheHeightsThePauseGaveBack() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Settings.FULL_SCREEN_REELS.save(true);
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, "reels");
            heights(75, 135);
            showViewer(W, H);
            FullScreenBars.Session session = watch();
            assertTrue(session.isHidden());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Arrays.asList("reels 75 135", "reels 0 0"), told);

            FullScreenBars.giveBackBarHeights();
            session.show();
            assertFalse(session.isHidden());
            session.evaluate();
            assertTrue(session.isHidden());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the hide took them again", Arrays.asList("reels 75 135", "reels 0 0", "reels 0 0"), told);
            assertArrayEquals("and owes them back", new int[] {75, 135}, FullScreenBars.heightsToGiveBack());
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /**
     * #385: a reel opened from its author's profile plays in the profile's window, and Back leaves it
     * for the profile in that same window. The hide wrote 0 into Instagram's copy and no pass of its
     * listener came after the show, so the profile kept no room for the status bar and its name sat
     * under the clock. The show writes back the heights Instagram last offered, and the kept listeners
     * hear them without waiting for a pass.
     */
    @Test public void leavingAReelInItsOwnWindowGivesTheHeightsBack() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Settings.FULL_SCREEN_REELS.save(true);
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, "profile");
            heights(75, 135);
            showViewer(W, H);
            FullScreenBars.Session session = watch();
            assertTrue(session.isHidden());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Arrays.asList("profile 75 135", "profile 0 0"), told);

            root.removeView(viewer);
            layout();
            session.evaluate();
            assertFalse(session.isHidden());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("told with no pass of Instagram's", Arrays.asList("profile 75 135", "profile 0 0", "profile 75 135"), told);
            assertArrayEquals("nothing is owed back", new int[] {0, 0}, FullScreenBars.heightsToGiveBack());
            heights(75, 135);
            assertEquals("the pass after it tells nothing new", 3, told.size());
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /**
     * #203: with Full screen Home on, the first tab opened from Home (Messages, Profile) was built
     * while Home had the bars hidden, so it heard 0 and drew under the status bar until the next
     * switch. Its kept listener hears the real heights once the bars come back.
     */
    @Test public void aTabBuiltUnderHomesHiddenBarsHearsTheHeightsWhenTheyComeBack() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Settings.FULL_SCREEN_HOME.save(true);
            FullScreenBars.nowResumed(activity);
            heights(75, 135);
            select(homeTab);
            FullScreenBars.Session session = watch();
            assertTrue(session.isHidden());
            heights(75, 135);
            FullScreenBars.heightsAsked(activity, "messages");

            select(otherTab);
            session.evaluate();
            assertFalse(session.isHidden());
            heights(75, 135);
            assertEquals("the new tab hears the bars' heights", "messages 75 135", told.get(told.size() - 1));
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /**
     * #209: with only Glass tab bar on and its pill floating, a reel's comments or a chat built while
     * the pill had the navigation bar's height at 0 heard 0 and put its text box under the navigation
     * bar. Its listener is kept and hears the real height when the insets go round again. A pill that
     * doesn't float changes no height, so nothing is kept for it.
     */
    @Test public void aFloatingGlassPillKeepsListenersSoAScreenOverItHearsTheRealHeight() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Settings.GLASS_TAB_BAR.save(true);
            Settings.GLASS_TAB_BAR_FLOAT.save(false);
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, "comments");
            assertTrue("a pill that doesn't float, nothing kept", FullScreenBars.askersForTests(activity).isEmpty());

            Settings.GLASS_TAB_BAR_FLOAT.save(true);
            FullScreenBars.heightsAsked(activity, "comments");
            assertEquals("kept for the floating pill", 1, FullScreenBars.askersForTests(activity).size());
            heights(75, 135);
            assertEquals(Collections.singletonList("comments 75 135"), told);

            Settings.GLASS_TAB_BAR.save(false);
            heights(75, 0);
            assertEquals("Glass tab bar off, nobody is told", 1, told.size());
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /** An activity keeps its newest listeners only, so a screen built again and again doesn't pile them up. */
    @Test public void anActivityKeepsItsNewestListeners() {
        FullScreenBars.registeredForTests(true);
        try {
            Settings.FULL_SCREEN_REELS.save(true);
            for (int i = 0; i <= FullScreenBars.ASKERS_KEPT; i++) FullScreenBars.heightsAsked(activity, "screen " + i);
            List<Object> kept = FullScreenBars.askersForTests(activity);
            assertEquals(FullScreenBars.ASKERS_KEPT, kept.size());
            assertFalse("the oldest went", kept.contains("screen 0"));
            assertEquals("screen " + FullScreenBars.ASKERS_KEPT, kept.get(kept.size() - 1));
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /** One pass of Instagram's insets listener with these offered heights, then the main loop's next turn. */
    private static void heights(int status, int nav) {
        FullScreenBars.barHeight(status);
        FullScreenBars.navBarHeight(nav);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aViewerOnlyPartlyOnScreenIsNotReels() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H / 3);
        assertFalse(watch().isHidden());
        assertEquals(FullScreenBars.Screen.OTHER, screen());
    }

    @Test public void withoutAViewerTheSelectedReelsTabIsReels() {
        Settings.FULL_SCREEN_REELS.save(true);
        select(reelsTab);
        assertEquals(FullScreenBars.Screen.REELS, screen());
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        select(otherTab);
        session.evaluate();
        assertFalse(session.isHidden());
    }

    @Test public void homeHidesTheBarsOnlyWithItsOwnSwitch() {
        select(homeTab);
        assertEquals(FullScreenBars.Screen.HOME, screen());
        FullScreenBars.Session session = watch();
        assertFalse(session.isHidden());
        Settings.FULL_SCREEN_REELS.save(true);
        session.evaluate();
        assertFalse("the Reels switch is not Home's", session.isHidden());
        Settings.FULL_SCREEN_HOME.save(true);
        session.evaluate();
        assertTrue(session.isHidden());
    }

    private View messagesView(int id, int width, int height) {
        View view = new FrameLayout(activity);
        view.setId(id);
        root.addView(view, new FrameLayout.LayoutParams(width, height));
        layout();
        return view;
    }

    /** #288: your messages and a chat hide the bars with their own switch, Home selected under them or not. */
    @Test public void messagesHideTheBarsOnlyWithTheirOwnSwitch() {
        select(homeTab);
        View inbox = messagesView(INBOX, W, H * 3 / 4);
        assertEquals("your messages over Home aren't Home", FullScreenBars.Screen.MESSAGES, screen());
        Settings.FULL_SCREEN_HOME.save(true);
        Settings.FULL_SCREEN_REELS.save(true);
        FullScreenBars.Session session = watch();
        assertFalse("Home's and Reels' switches aren't Messages'", session.isHidden());
        Settings.FULL_SCREEN_MESSAGES.save(true);
        session.evaluate();
        assertTrue(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertTrue(barsGone());

        root.removeView(inbox);
        layout();
        Settings.FULL_SCREEN_HOME.save(false);
        session.evaluate();
        assertFalse("back on Home with its switch off", session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    /** #288: the keyboard takes about half the window and the chat still counts; a sliver of one doesn't. */
    @Test public void aChatCountsWithTheKeyboardUpButNotAsASliver() {
        View chat = messagesView(CHAT, W, H / 2);
        assertEquals(FullScreenBars.Screen.MESSAGES, screen());
        root.removeView(chat);
        messagesView(CHAT, W, H / 4);
        assertEquals(FullScreenBars.Screen.OTHER, screen());
    }

    /** #288: a list left behind out of sight doesn't hide the one showing, and one off to the side doesn't count. */
    @Test public void onlyAMessagesListOnScreenCounts() {
        FrameLayout gone = new FrameLayout(activity);
        gone.setVisibility(View.GONE);
        View left = new View(activity);
        left.setId(INBOX);
        gone.addView(left, new FrameLayout.LayoutParams(W, H));
        root.addView(gone, 0, new FrameLayout.LayoutParams(W, H));
        layout();
        assertEquals(FullScreenBars.Screen.OTHER, screen());

        FrameLayout.LayoutParams aside = new FrameLayout.LayoutParams(W, H);
        aside.leftMargin = W;
        View swipedAway = new View(activity);
        swipedAway.setId(INBOX);
        root.addView(swipedAway, aside);
        layout();
        assertEquals("swiped away to the side", FullScreenBars.Screen.OTHER, screen());

        messagesView(INBOX, W, H);
        assertEquals(FullScreenBars.Screen.MESSAGES, screen());
    }

    /** #288: a reel opened from a chat is Reels, and the Messages switch leaves it alone. */
    @Test public void aReelOverAChatIsReels() {
        Settings.FULL_SCREEN_MESSAGES.save(true);
        messagesView(CHAT, W, H);
        showViewer(W, H);
        assertEquals(FullScreenBars.Screen.REELS, screen());
        assertFalse(watch().isHidden());
    }

    @Test public void homesSwitchDoesNotHideReels() {
        Settings.FULL_SCREEN_HOME.save(true);
        showViewer(W, H);
        assertFalse("Home's switch is not Reels'", watch().isHidden());
    }

    @Test public void aViewerOverTheHomeTabIsReelsNotHome() {
        select(homeTab);
        showViewer(W, H);
        assertEquals(FullScreenBars.Screen.REELS, screen());
    }

    @Test public void turningTheSwitchOffBringsTheBarsBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        Settings.FULL_SCREEN_REELS.save(false);
        session.evaluate();
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    @Test public void pausingTheActivityBringsTheBarsBackAndForgetsIt() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        FullScreenBars.release(activity);
        assertFalse(session.isHidden());
        assertNull(FullScreenBars.sessionFor(activity));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    @Test public void theWindowsOwnFlagsAreKeptAndPutBack() {
        View decor = activity.getWindow().getDecorView();
        int before = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(before);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertEquals(before | FullScreenBars.IMMERSIVE_FLAGS, decor.getSystemUiVisibility());
            // The window asking again, as it does when it gets focus back, must not save the hidden state.
            session.onWindowFocusChanged(true);
            FullScreenBars.release(activity);
            assertEquals(before, decor.getSystemUiVisibility());
        } else {
            session.onWindowFocusChanged(true);
            FullScreenBars.release(activity);
        }
        assertFalse(session.isHidden());
    }

    /**
     * A dialog over Reels that leads to a profile: focus comes back on a screen that doesn't want the
     * bars hidden, and the window still asks for them hidden, so they have to be put back right then.
     */
    @Test public void focusComingBackAwayFromReelsBringsTheBarsBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        root.removeView(viewer);
        viewer = null;
        layout();
        session.onWindowFocusChanged(true);
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    /** A flag Instagram sets while the bars are hidden (a light status bar on the next screen) stays. */
    @Test public void flagsSetWhileTheBarsAreHiddenStayWhenTheyComeBack() {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            FullScreenBars.release(activity);
            assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, decor.getSystemUiVisibility());
        }
    }

    /** Only the immersive flags HushGram added come off; one the window had already stays. */
    @Test public void onlyTheAddedImmersiveFlagsComeOff() {
        int before = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        int now = before | FullScreenBars.IMMERSIVE_FLAGS | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        assertEquals(before | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, FullScreenBars.withoutAdded(now, before));
        assertEquals(0, FullScreenBars.withoutAdded(FullScreenBars.IMMERSIVE_FLAGS, 0));
    }

    /**
     * Home, away, Home again before the insets caught up, away again: every show asks for all the bars
     * back. The S22 kept them hidden off Home when a show went by which bars the insets said were up.
     */
    @Config(sdk = 37)
    @Test public void everyShowAsksForAllTheBarsBack() throws Exception {
        int status = android.view.WindowInsets.Type.statusBars(), navigation = android.view.WindowInsets.Type.navigationBars();
        int caption = android.view.WindowInsets.Type.captionBar();
        assertEquals(status | navigation | caption, FullScreenBars.barsToShow() & (status | navigation | caption));
        Settings.FULL_SCREEN_HOME.save(true);
        select(homeTab);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        assertEquals("hidden on Home", 0, requestedVisible() & (status | navigation));
        for (int round = 1; round <= 2; round++) {
            select(otherTab);
            session.evaluate();
            assertFalse(session.isHidden());
            assertEquals("back off Home, round " + round, status | navigation, requestedVisible() & (status | navigation));
            select(homeTab);
            session.evaluate();
            assertTrue(session.isHidden());
            assertEquals("hidden on Home, round " + round, 0, requestedVisible() & (status | navigation));
        }
        FullScreenBars.release(activity);
        assertEquals("back after a pause", status | navigation, requestedVisible() & (status | navigation));
    }

    /**
     * #231: Bars to hide set to the status bar only hides that bar and keeps the navigation bar, with
     * its height going through, and the bar comes back when Reels is left.
     */
    @Test public void theStatusBarAloneHidesWhenItsChosen() throws Exception {
        Settings.FULL_SCREEN_HIDES.save(HiddenBars.STATUS_BAR);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        assertBarsShowing(false, true);
        assertEquals("the status bar's space stayed", 0, FullScreenBars.barHeight(128));
        assertEquals("the navigation bar lost its space", 63, FullScreenBars.navBarHeight(63));
        FullScreenBars.release(activity);
        assertBarsShowing(true, true);
    }

    /** #231: the navigation bar only is the mirror, and the status bar keeps its height. */
    @Test public void theNavigationBarAloneHidesWhenItsChosen() throws Exception {
        Settings.FULL_SCREEN_HIDES.save(HiddenBars.NAVIGATION_BAR);
        Settings.FULL_SCREEN_HOME.save(true);
        select(homeTab);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        assertBarsShowing(true, false);
        assertEquals("the status bar lost its space", 128, FullScreenBars.barHeight(128));
        assertEquals("the navigation bar's space stayed", 0, FullScreenBars.navBarHeight(63));
        FullScreenBars.release(activity);
        assertBarsShowing(true, true);
    }

    /**
     * #231: a choice changed while the bars are hidden takes over at the next look, and the bar the
     * earlier choice hid comes back, flags and all, before and after the window asks again.
     */
    @Test public void aChoiceChangedWhileHiddenTakesOverAtTheNextLook() throws Exception {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        Settings.FULL_SCREEN_HIDES.save(HiddenBars.STATUS_BAR);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertBarsShowing(false, true);
        Settings.FULL_SCREEN_HIDES.save(HiddenBars.NAVIGATION_BAR);
        session.evaluate();
        assertTrue(session.isHidden());
        assertBarsShowing(true, false);
        assertEquals(128, FullScreenBars.barHeight(128));
        assertEquals(0, FullScreenBars.navBarHeight(63));
        session.onWindowFocusChanged(true);
        assertBarsShowing(true, false);
        FullScreenBars.release(activity);
        assertBarsShowing(true, true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE, decor.getSystemUiVisibility());
        }
    }

    /** #231: the hide takes only the chosen bar's height, and only that one is owed back. */
    @Test public void theHideTakesOnlyTheChosenBarsHeight() {
        List<String> told = new ArrayList<>();
        FullScreenBars.resetHeightsForTests((listener, status, nav) -> told.add(listener + " " + status + " " + nav));
        FullScreenBars.registeredForTests(true);
        try {
            Settings.FULL_SCREEN_HIDES.save(HiddenBars.STATUS_BAR);
            Settings.FULL_SCREEN_REELS.save(true);
            FullScreenBars.nowResumed(activity);
            FullScreenBars.heightsAsked(activity, "reels");
            heights(75, 135);
            showViewer(W, H);
            assertTrue(watch().isHidden());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Arrays.asList("reels 75 135", "reels 0 135"), told);
            assertArrayEquals(new int[] {75, 0}, FullScreenBars.heightsToGiveBack());
        } finally {
            FullScreenBars.registeredForTests(false);
            FullScreenBars.resetHeightsForTests(null);
        }
    }

    /** Whether the window shows the status bar ([status]) and the navigation bar ([navigation]), as far as HushGram asked. */
    private void assertBarsShowing(boolean status, boolean navigation) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            assertEquals("status bar showing", status, (flags & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0);
            assertEquals("navigation bar showing", navigation, (flags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0);
        } else {
            int visible = requestedVisible();
            assertEquals("status bar showing", status, (visible & android.view.WindowInsets.Type.statusBars()) != 0);
            assertEquals("navigation bar showing", navigation,
                    (visible & android.view.WindowInsets.Type.navigationBars()) != 0);
        }
    }

    /** The bar types the window asks to have showing (Android 13 and newer keep it on the controller). */
    private int requestedVisible() throws Exception {
        Object insetsController = activity.getWindow().getInsetsController();
        java.lang.reflect.Method method = insetsController.getClass().getMethod("getRequestedVisibleTypes");
        method.setAccessible(true);
        return (int) method.invoke(insetsController);
    }

    /**
     * Swiping Home over to the story camera moves Home's whole panel sideways, tab bar and all, with
     * Home still selected: that's the camera, and the bars come back. The glass bar sliding down on
     * scroll is still Home.
     */
    @Test public void homeSwipedSidewaysToTheCameraIsNotHome() {
        Settings.FULL_SCREEN_HOME.save(true);
        select(homeTab);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        bar.setTranslationY(H);
        session.evaluate();
        assertTrue("a tab bar slid down on scroll is still Home", session.isHidden());
        bar.setTranslationY(0);
        root.setTranslationX(W / 2f);
        assertEquals(FullScreenBars.Screen.OTHER, screen());
        session.evaluate();
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
        root.setTranslationX(-W / 3f);
        assertEquals("partway through the swipe it's still Home", FullScreenBars.Screen.HOME, screen());
        session.evaluate();
        assertTrue(session.isHidden());
    }

    @Test public void aScreenWithNoTabBarAndNoViewerIsLeftAlone() {
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FULL_SCREEN_HOME.save(true);
        root.removeView(bar);
        layout();
        assertEquals(FullScreenBars.Screen.OTHER, screen());
        assertFalse(watch().isHidden());
    }
}
