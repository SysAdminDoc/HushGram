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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

import java.util.Arrays;
import java.util.Collection;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which Friends tab reels a page keeps by who's on them, and what holding the tab offers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FriendLanePicksTest {
    /** A Reels item carrying a post that names friends, or none at all. */
    static final class Clip {
        final List<String> friends;

        Clip(String... friends) {
            this.friends = friends == null ? null : Arrays.asList(friends);
        }
    }

    private static final FriendLanePicks.Reader READER = new FriendLanePicks.Reader() {
        @Override
        public Object media(Object item) {
            return item instanceof Clip && ((Clip) item).friends != null ? item : null;
        }

        @Override
        public Collection<String> friends(Object media) {
            return ((Clip) media).friends;
        }
    };

    private static final String LANE = "clips_viewer_friends_lane";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PICK_FRIENDS_TAB.save(true);
        Settings.FRIENDS_TAB_PICKS.save("ana\nbo");
        HookStatus.clear();
        FeedFilterCounters.clear();
        FriendLanePicks.forgetSeen();
        ShadowAlertDialog.reset();
        controller = Robolectric.buildActivity(Activity.class).setup();
    }

    @After
    public void restore() {
        controller.close();
        Settings.PICK_FRIENDS_TAB.resetToDefault();
        Settings.FRIENDS_TAB_PICKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
        FriendLanePicks.forgetSeen();
    }

    private static List<?> page(List<?> items, String module) {
        return FriendLanePicks.page(items, module, READER);
    }

    @Test
    public void onlyTheReelsAPickIsOnStay() {
        Clip ana = new Clip("ana");
        Clip both = new Clip("zed", "bo");
        Clip zed = new Clip("zed");
        List<Clip> items = Arrays.asList(zed, ana, both, new Clip("yan"));
        assertEquals(Arrays.asList(ana, both), page(items, LANE));
        assertTrue(FeedFilterCounters.report().toString().contains(FriendLanePicks.NOT_PICKED));
    }

    @Test
    public void namesMatchWhateverTheirCase() {
        Clip ana = new Clip("ANA");
        assertEquals(Collections.singletonList(ana), page(Arrays.asList(new Clip("zed"), ana), LANE));
    }

    @Test
    public void somethingThatIsntAReelStays() {
        Clip card = new Clip((String[]) null);
        Clip ana = new Clip("ana");
        assertEquals(Arrays.asList(card, ana), page(Arrays.asList(card, new Clip("zed"), ana), LANE));
    }

    @Test
    public void aReelNamingNobodyGoesWhenOthersOnThePageNameSomeone() {
        Clip ana = new Clip("ana");
        assertEquals(Collections.singletonList(ana), page(Arrays.asList(new Clip(), ana), LANE));
    }

    @Test
    public void aPageWithoutAPickKeepsItsFirstReelSoTheViewerHasOne() {
        Clip card = new Clip((String[]) null);
        Clip first = new Clip("zed");
        assertEquals(Arrays.asList(card, first), page(Arrays.asList(card, first, new Clip("yan")), LANE));
    }

    @Test
    public void aSingleReelWithoutAPickComesBackAsItCame() {
        List<Clip> items = Collections.singletonList(new Clip("zed"));
        assertSame(items, page(items, LANE));
    }

    @Test
    public void aPageNamingNobodyComesBackAsItCame() {
        List<Clip> items = Arrays.asList(new Clip(), new Clip());
        assertSame(items, page(items, LANE));
    }

    @Test
    public void anyOtherViewerIsLeftAlone() {
        List<Clip> items = Arrays.asList(new Clip("zed"), new Clip("ana"));
        assertSame(items, page(items, "clips_viewer_clips_tab"));
        assertSame(items, page(items, null));
        assertTrue("nothing was remembered from it", FriendLanePicks.roster().equals(Arrays.asList("ana", "bo")));
    }

    @Test
    public void offPausedOrNothingPickedLeavesThePageAlone() {
        List<Clip> items = Arrays.asList(new Clip("zed"), new Clip("ana"));
        Settings.PICK_FRIENDS_TAB.save(false);
        assertSame(items, page(items, LANE));
        Settings.PICK_FRIENDS_TAB.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items, LANE));
        PauseForTests.resume();
        Settings.FRIENDS_TAB_PICKS.save("");
        assertSame(items, page(items, LANE));
    }

    @Test
    public void nullEmptyAndAReaderThatThrowsAreSafe() {
        assertNull(page(null, LANE));
        List<Clip> none = Collections.emptyList();
        assertSame(none, page(none, LANE));
        List<Clip> items = Arrays.asList(new Clip("zed"), new Clip("ana"));
        List<?> answer = FriendLanePicks.page(items, LANE, new FriendLanePicks.Reader() {
            @Override
            public Object media(Object item) {
                throw new IllegalStateException("no");
            }

            @Override
            public Collection<String> friends(Object media) {
                return null;
            }
        });
        assertSame(items, answer);
    }

    @Test
    public void theListHoldsPicksFirstThenWhoThePagesNamedEvenWithNothingPicked() {
        Settings.FRIENDS_TAB_PICKS.save("zed");
        List<Clip> items = Arrays.asList(new Clip("cy", "Bo"), new Clip("zed"), new Clip((String[]) null));
        page(items, LANE);
        assertEquals(Arrays.asList("zed", "bo", "cy"), FriendLanePicks.roster());

        Settings.FRIENDS_TAB_PICKS.save("");
        page(Collections.singletonList(new Clip("dee")), LANE);
        assertEquals("picking nothing still fills the list", Arrays.asList("bo", "cy", "dee", "zed"), FriendLanePicks.roster());
    }

    @Test
    public void picksReadOnePerLineLowerCaseWithoutBlanksOrRepeats() {
        assertEquals(Arrays.asList("ana", "bo"), new java.util.ArrayList<>(FriendLanePicks.parse(" ANA\n\nbo\nana\n")));
        assertTrue(FriendLanePicks.parse(null).isEmpty());
        assertEquals("ana\nbo", FriendLanePicks.join(Arrays.asList("ana", "bo")));
    }

    @Test
    public void theListStopsGrowingAtItsLimit() {
        Settings.FRIENDS_TAB_PICKS.save("");
        for (int i = 0; i < FriendLanePicks.MOST_SEEN + 5; i++) {
            page(Collections.singletonList(new Clip("friend" + i)), LANE);
        }
        assertEquals(FriendLanePicks.MOST_SEEN, FriendLanePicks.roster().size());
    }

    private View tab() {
        View tab = new View(new ContextWrapper(controller.get()));
        FriendLanePicks.watch(tab);
        return tab;
    }

    @Test
    public void offOrPausedTheHoldStaysInstagrams() {
        Settings.PICK_FRIENDS_TAB.save(false);
        assertFalse(FriendLanePicks.held(tab()));
        Settings.PICK_FRIENDS_TAB.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(FriendLanePicks.held(tab()));
        assertFalse("no tab at all", FriendLanePicks.held(null));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /** Touches the tab down and lets go without a tap, as a finger starting a hold does. */
    private static void press(View tab) {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        MotionEvent cancel = MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, 1f, 1f, 0);
        tab.dispatchTouchEvent(down);
        tab.dispatchTouchEvent(cancel);
        down.recycle();
        cancel.recycle();
    }

    /** Some builds give the tab a hold of Instagram's own after it's built (#571). */
    @Test
    public void aPressTakesTheHoldBackFromInstagramsOwn() {
        Settings.FRIENDS_TAB_PICKS.save("");
        View tab = tab();
        boolean[] instagrams = {false};
        tab.setOnLongClickListener(held -> {
            instagrams[0] = true;
            return false;
        });
        press(tab);
        assertTrue(tab.performLongClick());
        assertFalse("Instagram's hold didn't run", instagrams[0]);
        ShadowLooper.idleMainLooper();
        assertEquals("Open the Friends tab first.", ShadowToast.getTextOfLatestToast());

        Settings.PICK_FRIENDS_TAB.save(false);
        View off = tab();
        off.setOnLongClickListener(held -> {
            instagrams[0] = true;
            // A tab without a parent has no context menu to fall back on.
            return true;
        });
        press(off);
        off.performLongClick();
        assertTrue("off, Instagram keeps its hold", instagrams[0]);
    }

    @Test
    public void holdingWithNobodyToListSaysToOpenTheTab() {
        Settings.FRIENDS_TAB_PICKS.save("");
        assertTrue(tab().performLongClick());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowLooper.idleMainLooper();
        assertEquals("Open the Friends tab first.", ShadowToast.getTextOfLatestToast());
    }

    /** #503: the tab open and still nobody named, as an all-caught-up Friends tab is, says so. */
    @Test
    public void holdingTheOpenTabWithNobodyToListSaysSo() {
        Settings.FRIENDS_TAB_PICKS.save("");
        View tab = tab();
        tab.setSelected(true);
        assertTrue(tab.performLongClick());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowLooper.idleMainLooper();
        assertEquals("No friends to pick yet.", ShadowToast.getTextOfLatestToast());

        page(Arrays.asList(new Clip("ana")), LANE);
        assertTrue(tab.performLongClick());
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("once a friend is named the list opens", dialog != null && dialog.isShowing());
    }

    @Test
    public void holdingListsTheFriendsAndEachChoiceIsSavedAsItsMade() {
        Settings.FRIENDS_TAB_PICKS.save("bo");
        page(Arrays.asList(new Clip("ana"), new Clip("bo")), LANE);
        assertTrue(tab().performLongClick());
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the list is showing", dialog != null && dialog.isShowing());
        ListView list = dialog.getListView();
        assertEquals("bo", list.getAdapter().getItem(0).toString());
        assertEquals("ana", list.getAdapter().getItem(1).toString());
        assertTrue("a pick starts ticked", list.isItemChecked(0));
        assertFalse(list.isItemChecked(1));

        org.robolectric.Shadows.shadowOf(dialog).clickOnItem(1);
        ShadowLooper.idleMainLooper();
        assertEquals("bo\nana", Settings.FRIENDS_TAB_PICKS.get());

        ShadowToast.reset();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Applies to the next reels loaded.", ShadowToast.getTextOfLatestToast());
    }

    /** #616: 454 builds the tab on the app's context, so the list opens on the screen in front. */
    @Test
    public void aTabBuiltOnTheAppsContextOpensTheListOnTheScreenInFront() {
        Activity showing = Utils.getActivity();
        try {
            View tab = new View(new ContextWrapper(controller.get().getApplication()));
            FriendLanePicks.watch(tab);
            Utils.setActivity(controller.get());
            assertTrue(tab.performLongClick());
            ShadowLooper.idleMainLooper();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("the list is showing", dialog != null && dialog.isShowing());
            dialog.dismiss();

            ShadowAlertDialog.reset();
            Utils.setActivity(null);
            assertFalse("no screen to show it on, the hold stays Instagram's", FriendLanePicks.held(tab));
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            Utils.setActivity(showing);
        }
    }

    @Test
    public void closingTheListWithoutAChangeSaysNothing() {
        assertTrue(tab().performLongClick());
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ShadowToast.reset();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNull(ShadowToast.getTextOfLatestToast());
        assertEquals("ana\nbo", Settings.FRIENDS_TAB_PICKS.get());
    }
}
