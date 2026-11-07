/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The jump: the id it makes, one request for each jump, the button, and leaving Instagram's request alone otherwise. */
@RunWith(RobolectricTestRunner.class)
public class GridJumpTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A real media id, from a photo posted late in September 2026. */
    private static final long REAL_ID = 3996319772513417509L;

    private static final String TAGGED_PATH = "usertags/%s/feed/";

    private final List<String> asked = new ArrayList<>();
    private final Object loader = new Object();
    private GridJump.Reloader stock;
    private Activity activity;

    /** Public, since the extension reaches these by reflection. */
    public static final class Tab {
        public final Bundle arguments = new Bundle();
        public boolean resumed = true;
        public Object recyclerView;

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return resumed;
        }
    }

    public static final class Manager {
        public final List<Integer> scrolled = new ArrayList<>();

        public void scrollToPosition(int position) {
            scrolled.add(position);
        }
    }

    public static final class Grid {
        public final Manager manager = new Manager();

        public Manager getLayoutManager() {
            return manager;
        }
    }

    private static Tab postsTab() {
        Tab tab = new Tab();
        tab.arguments.putString(PostsList.TAB_KEY, PostsList.POSTS_TAB);
        tab.recyclerView = new Grid();
        return tab;
    }

    @Before public void prepare() {
        stock = GridJump.reloader;
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.PROFILE_GRID_JUMP.save(true);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        GridJump.postsTab = new WeakReference<>(null);
        // What Instagram's reload does: one first page's load on this thread, free to go.
        GridJump.reloader = tab -> asked.add(GridJump.restMaxId(loader, "42", 1, null, 1));
    }

    @After public void restore() {
        GridJump.restPath(TAGGED_PATH);
        GridJump.reloader = stock;
        GridJump.postsTab = new WeakReference<>(null);
        Settings.PROFILE_GRID_JUMP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    @Test public void theIdHoldsTheTimeInItsTopBits() {
        long madeAt = (REAL_ID >>> 23) + GridJump.MEDIA_EPOCH_MS;
        Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(madeAt);
        assertEquals("the real id's year", 2026, utc.get(Calendar.YEAR));
        assertEquals("the real id's month", Calendar.SEPTEMBER, utc.get(Calendar.MONTH));
        assertEquals("only the shard and sequence bits differ", REAL_ID >>> 23, GridJump.mediaId(madeAt) >>> 23);
        assertEquals(0, GridJump.mediaId(madeAt) & ((1L << 23) - 1));
        assertTrue("a later time is a bigger id", GridJump.mediaId(madeAt + 1) > GridJump.mediaId(madeAt));
    }

    @Test public void anIdNeverGoesBelowWhereIdsStart() {
        assertEquals(0, GridJump.mediaId(GridJump.MEDIA_EPOCH_MS - 1000));
    }

    @Test public void aDayEndsAtItsLastMomentButNeverPastNow() {
        Calendar expected = Calendar.getInstance();
        expected.clear();
        expected.set(2015, Calendar.MARCH, 9, 23, 59, 59);
        expected.set(Calendar.MILLISECOND, 999);
        long now = expected.getTimeInMillis() + 86_400_000L;
        assertEquals(expected.getTimeInMillis(), GridJump.endOfDay(2015, Calendar.MARCH, 9, now));
        assertEquals("today stops at now", 1234L, GridJump.endOfDay(2015, Calendar.MARCH, 9, 1234L));
    }

    @Test public void withNothingWaitingInstagramsRouteRuns() {
        assertNull(GridJump.restMaxId(loader, "42", 1, null, 1));
        assertNull(GridJump.restMaxId(loader, "42", 0, "next_page", 1));
        assertEquals(TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
    }

    @Test public void aJumpAsksOnceWithItsId() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.reloader = tab -> {
            asked.add(GridJump.restMaxId(loader, "42", 1, null, 1));
            // A second load from the same reload stays as Instagram made it.
            asked.add(GridJump.restMaxId(new Object(), "42", 1, null, 1));
        };
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);

        Calendar end = Calendar.getInstance();
        end.clear();
        end.set(2015, Calendar.MARCH, 9, 23, 59, 59);
        end.set(Calendar.MILLISECOND, 999);
        assertEquals(GridJump.mediaId(end.getTimeInMillis()) + "_42", asked.get(0));
        assertNull(asked.get(1));
        assertEquals("Going to that date", toast());
    }

    @Test public void theJumpsRequestGoesToTheUsersFeedOnce() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals(GridJump.USER_FEED_PATH, GridJump.restPath(TAGGED_PATH));
        assertEquals("the next request is Instagram's own", TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
    }

    @Test public void theNextPagesStayOnTheRouteForTheJumpedGridOnly() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        GridJump.restPath(TAGGED_PATH);

        assertNull("another tab's next page", GridJump.restMaxId(new Object(), "42", 0, "cursor", 1));
        assertEquals("nothing sent it to the feed", TAGGED_PATH, GridJump.restPath(TAGGED_PATH));

        assertEquals("cursor", GridJump.restMaxId(loader, "42", 0, "cursor", 1));
        assertEquals(GridJump.USER_FEED_PATH, GridJump.restPath(TAGGED_PATH));

        assertNull("no more pages", GridJump.restMaxId(loader, "42", 0, null, 1));
        assertEquals(TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
    }

    /** Pull to refresh, or the tab coming back: a first page with nothing waiting. */
    @Test public void aFirstPageGoesBackToInstagramsRoute() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        GridJump.restPath(TAGGED_PATH);

        assertNull(GridJump.restMaxId(loader, "42", 1, null, 1));
        assertNull("its next pages too", GridJump.restMaxId(loader, "42", 0, "cursor", 1));
        assertEquals(TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
    }

    @Test public void aLoadOnAnotherThreadNeverSeesTheId() throws Exception {
        GridJump.postsTab = new WeakReference<>(postsTab());
        AtomicReference<String> other = new AtomicReference<>("unset");
        GridJump.reloader = tab -> {
            Thread thread = new Thread(() -> other.set(GridJump.restMaxId(new Object(), "42", 1, null, 1)));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
            asked.add(GridJump.restMaxId(loader, "42", 1, null, 1));
        };
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertNull(other.get());
        assertTrue(asked.get(0), asked.get(0).endsWith("_42"));
    }

    @Test public void theGridGoesToItsTop() {
        Tab tab = postsTab();
        GridJump.postsTab = new WeakReference<>(tab);
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals(List.of(0), ((Grid) tab.recyclerView).manager.scrolled);
    }

    @Test public void aGridWithoutTheScrollStillJumps() {
        Tab tab = postsTab();
        tab.recyclerView = "not a grid";
        GridJump.postsTab = new WeakReference<>(tab);
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals(1, asked.size());
        assertEquals("Going to that date", toast());
    }

    /** The profile's load never reaches the hook, for a kind of user the route can't take. */
    @Test public void aRefusedReloadSaysSoAndLeavesNothingWaiting() {
        Tab tab = postsTab();
        GridJump.postsTab = new WeakReference<>(tab);
        GridJump.reloader = refused -> { };
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals("Still loading, try again", toast());
        assertTrue(((Grid) tab.recyclerView).manager.scrolled.isEmpty());
        assertNull("nothing left for the next load", GridJump.restMaxId(loader, "42", 1, null, 1));
    }

    /** The grid is busy, so Instagram's route turns the load down and the day isn't used. */
    @Test public void aBusyGridLeavesTheJumpToTheStillLoadingToast() {
        Tab tab = postsTab();
        GridJump.postsTab = new WeakReference<>(tab);
        GridJump.reloader = busy -> asked.add(GridJump.restMaxId(loader, "42", 1, null, 0));
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals(java.util.Collections.singletonList(null), asked);
        assertEquals("Still loading, try again", toast());
        assertTrue(((Grid) tab.recyclerView).manager.scrolled.isEmpty());
        assertEquals("nothing sent it to the feed", TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
        assertNull("nothing left for the next load", GridJump.restMaxId(loader, "42", 1, null, 1));
    }

    @Test public void aReloadThatThrowsLeavesNothingWaiting() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.reloader = broken -> {
            throw new IllegalStateException("no view yet");
        };
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertNull(GridJump.restMaxId(loader, "42", 1, null, 1));
    }

    @Test public void anEmptyUserStaysOnInstagramsRoute() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        GridJump.reloader = tab -> {
            asked.add(GridJump.restMaxId(loader, "", 1, null, 1));
            asked.add(GridJump.restMaxId(loader, null, 1, null, 1));
        };
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertEquals(java.util.Arrays.asList(null, null), asked);
        assertEquals("Still loading, try again", toast());
        assertEquals(TAGGED_PATH, GridJump.restPath(TAGGED_PATH));
    }

    @Test public void offOrPausedNothingIsAsked() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        Settings.PROFILE_GRID_JUMP.save(false);
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertTrue(asked.isEmpty());

        Settings.PROFILE_GRID_JUMP.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertTrue(asked.isEmpty());
    }

    @Test public void aTabThatLeftIsNotReloaded() {
        Tab tab = postsTab();
        tab.resumed = false;
        GridJump.postsTab = new WeakReference<>(tab);
        GridJump.chosen(activity, 2015, Calendar.MARCH, 9);
        assertTrue(asked.isEmpty());
    }

    @Test public void onlyThePostsTabIsRemembered() {
        Tab tagged = new Tab();
        tagged.arguments.putString(PostsList.TAB_KEY, "profile_tagged_grid");
        GridJump.resumed(tagged);
        assertNull(GridJump.postsTab.get());

        Tab posts = postsTab();
        GridJump.resumed(posts);
        assertSame(posts, GridJump.postsTab.get());
    }

    @Test public void offTheTabIsNotRemembered() {
        Settings.PROFILE_GRID_JUMP.save(false);
        GridJump.resumed(postsTab());
        assertNull(GridJump.postsTab.get());
    }

    @Test public void aMissingTabNeverThrows() {
        GridJump.resumed(null);
        GridJump.resumed("not a tab");
        assertNull(GridJump.postsTab.get());
    }

    @Test public void theButtonGoesFirstInTheRowOnce() {
        LinearLayout row = new LinearLayout(activity);
        ImageView stockButton = new ImageView(activity);
        stockButton.setPaddingRelative(7, 8, 9, 10);
        row.addView(stockButton);
        activity.setContentView(row);

        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        assertEquals(2, row.getChildCount());
        GridJump.JumpButton button = GridJump.find(row);
        assertSame(button, row.getChildAt(0));
        assertTrue(button.getDrawable() instanceof GridJump.CalendarIcon);
        assertEquals("Jump to a date", button.getContentDescription().toString());
        assertEquals("padded like the row's own button", 7, button.getPaddingStart());
        assertEquals(10, button.getPaddingBottom());

        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        assertEquals("not a second time", 2, row.getChildCount());
    }

    @Test public void theButtonLeavesWithTheSwitch() {
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        assertNotNull(GridJump.find(row));

        Settings.PROFILE_GRID_JUMP.save(false);
        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        assertNull(GridJump.find(row));

        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        assertNull("off, none goes on", GridJump.find(row));
    }

    @Test public void aTapWithThePostsUpAsksForADayNoEarlierThanIdsCount() {
        GridJump.postsTab = new WeakReference<>(postsTab());
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        GridJump.find(row).performClick();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(String.valueOf(dialog), dialog instanceof DatePickerDialog);
        assertTrue(dialog.isShowing());
        assertEquals(GridJump.MEDIA_EPOCH_MS, ((DatePickerDialog) dialog).getDatePicker().getMinDate());
        assertTrue(((DatePickerDialog) dialog).getDatePicker().getMaxDate() <= System.currentTimeMillis());
        assertTrue("the tap itself asks for nothing", asked.isEmpty());
        dialog.dismiss();
    }

    @Test public void aTapOffThePostsSaysWhatToDo() {
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        GridJump.bound(row);
        ShadowLooper.idleMainLooper();
        GridJump.find(row).performClick();
        assertEquals("Open the profile's posts first", toast());
    }

    @Test public void theCalendarIsTheSizeOfTheBarsIcons() {
        GridJump.CalendarIcon icon = new GridJump.CalendarIcon(2f, 0xffffffff);
        assertEquals(48, icon.getIntrinsicWidth());
        assertEquals(48, icon.getIntrinsicHeight());
    }
}
