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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Finding the reel a profile was opened from in that profile's Reels grid. Runtime decisions only:
 * that 450 opens the profile in its own activity and names its grid tiles by the reel's id needs a
 * check on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelOriginTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };
    /** The media types a reel's media answers: a photo, a carousel, a video, and none, as with the bridge unwritten. */
    private static final Function<Object, Integer> PHOTO = media -> ReelOrigin.PHOTO;
    private static final Function<Object, Integer> CAROUSEL = media -> ReelOrigin.CAROUSEL;
    private static final Function<Object, Integer> VIDEO = media -> 2;
    private static final Function<Object, Integer> UNKNOWN = media -> null;
    /** The reel's author, and the reel as Instagram names it: its own id, then its author's. */
    private static final String AUTHOR = "7";
    private static final String REEL = "3100_" + AUTHOR;
    /**
     * Every tab made in a test, until it ends. The search holds its tab only weakly, while Instagram's
     * fragment manager holds a real one, so a tab a test made and dropped could be collected mid-search.
     */
    private static final List<Object> TABS = new ArrayList<>();

    /** A reel as the extension sees 450's Media. */
    public static final class Reel {
        private final String id;
        int reads;

        Reel(String id) {
            this.id = id;
        }

        public String getId() {
            reads++;
            return id;
        }
    }

    /** A profile's Reels tab as the extension sees 450's: its activity, its arguments, whether it's resumed, and its grid. */
    public static final class Tab {
        public RecyclerView grid;
        public String gridKey = "profile_clips";
        public boolean resumed = true;
        private final Activity activity;
        private final Bundle arguments = new Bundle();

        Tab(Activity activity, RecyclerView grid, String user) {
            this.activity = activity;
            this.grid = grid;
            arguments.putString(ReelOrigin.USER_KEY, user);
            TABS.add(this);
        }

        public Activity getActivity() {
            return activity;
        }

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return resumed;
        }
    }

    /**
     * A profile's posts grid as the extension sees 451's ProfileMediaTabFragment: which of the
     * profile's grids it is, and no user id, then the rest as a tab's.
     */
    public static final class PostsGrid {
        public RecyclerView recyclerView;
        public boolean resumed = true;
        private final Activity activity;
        private final Bundle arguments = new Bundle();

        PostsGrid(Activity activity, RecyclerView grid, String which) {
            this.activity = activity;
            this.recyclerView = grid;
            arguments.putString(ReelOrigin.GRID_KEY, which);
            TABS.add(this);
        }

        public Activity getActivity() {
            return activity;
        }

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return resumed;
        }
    }

    /** A tab with two grids, which the extension can't tell apart. */
    public static final class TwoGrids {
        public RecyclerView grid;
        public RecyclerView other;
        private final Activity activity;
        private final Bundle arguments = new Bundle();

        TwoGrids(Activity activity) {
            this.activity = activity;
            arguments.putString(ReelOrigin.USER_KEY, AUTHOR);
            TABS.add(this);
        }

        public Activity getActivity() {
            return activity;
        }

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return true;
        }
    }

    /** A tile of the grid, noting each time it's asked onto the screen. */
    static final class Tile extends View {
        final List<String> shown;

        Tile(Context context, List<String> shown) {
            super(context);
            this.shown = shown;
        }

        @Override public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) {
            shown.add(getTransitionName());
            return super.requestRectangleOnScreen(rectangle, immediate);
        }
    }

    private final List<ActivityController<Activity>> controllers = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private final List<Tile> made = new ArrayList<>();
    private Activity profile;
    private RecyclerView grid;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.FIND_REEL_ORIGIN.save(true);
        HookStatus.clear();
        ReelOrigin.forget();
    }

    @After
    public void restore() {
        ReelOrigin.forget();
        TABS.clear();
        for (ActivityController<Activity> controller : controllers) controller.pause().stop().destroy();
        Settings.FIND_REEL_ORIGIN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A reel on the grid's first screen: brought fully on screen and outlined for a moment, with no scrolling. */
    @Test
    public void aReelOnTheFirstScreenIsShownAndOutlined() {
        ReelOrigin.opening(new Reel(REEL), ON);
        assertEquals(REEL, ReelOrigin.heldForTests());
        openProfile(List.of("3000_7", REEL, "2900_7", "2800_7"), null);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(REEL), shown);
        assertTrue("nothing was dragged", grid.drags.isEmpty());
        assertTrue("nothing was scrolled", grid.scrolls.isEmpty());
        Tile tile = tile(REEL);
        assertEquals("outlined", 1, outlines(tile));
        idleSeconds(2);
        assertEquals("the outline goes after a moment", 0, outlines(tile));
        assertNull("the reel is dropped once found", ReelOrigin.heldForTests());

        // Back to the tab later: found once, it isn't looked for again.
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(2);
        assertEquals(List.of(REEL), shown);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.REMEMBERED + " 1"));
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.REEL_ORIGIN).toString(), HookStatus.missing(FamilyNames.REEL_ORIGIN).isEmpty());
    }

    /**
     * A reel further down: the grid is dragged on most of a screen at a time, waiting for each page
     * Instagram loads, until it shows. Instagram loads a page only at the end of a drag, so a grid
     * moved by scrollBy would stop at the first page's end.
     */
    @Test
    public void aReelFurtherDownIsScrolledToAsPagesLoad() {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            List<String> reels = new ArrayList<>();
            for (int at = 0; at < 12; at++) reels.add((5000 - page * 12 - at) + "_" + AUTHOR);
            pages.add(reels);
        }
        pages.get(2).set(7, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(), null);
        Handler main = new Handler(Looper.getMainLooper());
        int[] loaded = {0};
        Runnable nextPage = () -> {
            if (loaded[0] < pages.size()) grid.reels.addAll(pages.get(loaded[0]++));
        };
        // Instagram loads its next page a moment after a drag ends at the end, and the first a moment after the tab opens.
        grid.atEnd = () -> main.postDelayed(nextPage, 300);
        main.postDelayed(() -> {
            nextPage.run();
            grid.bind();
        }, 1000);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(10);
        assertEquals(HookStatus.report() + " drags " + grid.drags + " pages " + loaded[0], List.of(REEL), shown);
        assertFalse("the grid was dragged on", grid.drags.isEmpty());
        assertTrue("every drag went down the grid", grid.drags.stream().allMatch(by -> by > 0));
        assertTrue("and it was never scrolled", grid.scrolls.isEmpty());
        assertTrue("it waited for Instagram's pages", loaded[0] >= 3);
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
    }

    /** Never there: after its steps it gives up, the grid goes back to where it was, and a toast says so. */
    @Test
    public void givesUpAfterItsStepsAndPutsTheGridBack() {
        ReelOrigin.opening(new Reel(REEL), ON);
        // Every page sits far above the reel, so even MAX_STEPS pages never reach it.
        openProfile(reels(12, 90000), null);
        int[] page = {0};
        grid.atEnd = () -> grid.reels.addAll(reels(12, 80000 - 100 * page[0]++));

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds((int) (ReelOrigin.GIVE_UP_MS / 1000) + 5);
        assertTrue(shown.isEmpty());
        assertEquals("the steps", ReelOrigin.MAX_STEPS, grid.drags.size());
        assertEquals("then one scroll back", 1, grid.scrolls.size());
        assertTrue(grid.scrolls.get(0) < 0);
        assertEquals("back at the top", 0, grid.firstRow());
        assertEquals("Couldn't find the reel you came from", ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        assertNull(ReelOrigin.buttonForTests());
        assertEquals("Stop looking went with the search", 1, content().getChildCount());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.NOT_FOUND + " 1"));
        assertTrue("and why", report.contains(ReelOrigin.RAN_OUT + " 1"));
        assertFalse(report, report.contains(ReelOrigin.ENDED));
    }

    /**
     * A grid that ends without it gives up after waiting a few seconds for more, without moving.
     * While it waits, the grid is dragged at its end now and then, in case more is coming.
     */
    @Test
    public void givesUpWhenTheGridEnds() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(5, 9000), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(2);
        assertNull("still waiting for more", ShadowToast.getTextOfLatestToast());
        idleSeconds(4);
        assertEquals("Couldn't find the reel you came from", ShadowToast.getTextOfLatestToast());
        assertFalse("dragged at the end in case more was coming", grid.drags.isEmpty());
        assertTrue("never scrolled", grid.scrolls.isEmpty());
        assertEquals("never moved", 0, grid.firstRow());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.NOT_FOUND + " 1"));
        assertTrue("and why", report.contains(ReelOrigin.ENDED + " 1"));
        assertFalse("its tiles were there from the start", report.contains(ReelOrigin.WAITED));
    }

    /**
     * 451's grid can say more lies below at its loaded end while the next page loads, and a drag
     * there moves nothing (#30, S22 2026-10-14). Those drags aren't steps: the search waits at the
     * end for each page as at a real end, then goes on and finds the reel four pages down.
     */
    @Test
    public void dragsThatMoveNothingAtTheLoadedEndAreNotSteps() {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 0; page < 4; page++) pages.add(reels(12, 5000 - page * 100));
        pages.get(3).set(4, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(pages.get(0)), null);
        grid.slack = RecyclerView.ROW;
        Handler main = new Handler(Looper.getMainLooper());
        int[] loaded = {1};
        boolean[] loading = {false};
        // Instagram loads the next page a second and a half after a drag ends at the loaded end, one at a time.
        grid.atEnd = () -> {
            if (loading[0] || loaded[0] >= pages.size()) return;
            loading[0] = true;
            main.postDelayed(() -> {
                grid.reels.addAll(pages.get(loaded[0]++));
                loading[0] = false;
            }, 1500);
        };

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(14);
        assertEquals(HookStatus.report() + " drags " + grid.drags + " pages " + loaded[0], List.of(REEL), shown);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertFalse(report, report.contains(ReelOrigin.RAN_OUT));
    }

    /**
     * A reel from months back sits dozens of pages down, each page a second and a half behind the
     * last. The search kept going only 15 seconds and said it couldn't find it (#225); it goes on
     * until the reel shows.
     */
    @Test
    public void aReelDozensOfPagesDownIsStillFound() {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 0; page < 30; page++) pages.add(reels(12, 9000 - page * 100));
        pages.get(29).set(6, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(pages.get(0)), null);
        Handler main = new Handler(Looper.getMainLooper());
        int[] loaded = {1};
        boolean[] loading = {false};
        grid.atEnd = () -> {
            if (loading[0] || loaded[0] >= pages.size()) return;
            loading[0] = true;
            main.postDelayed(() -> {
                grid.reels.addAll(pages.get(loaded[0]++));
                loading[0] = false;
            }, 1500);
        };

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(80);
        assertEquals(HookStatus.report() + " drags " + grid.drags.size() + " pages " + loaded[0], List.of(REEL), shown);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertFalse(report, report.contains(ReelOrigin.RAN_OUT));
    }

    /**
     * Deep in a big profile a page can take longer than the end's wait, Instagram's spinner showing
     * below the last row all the while. The search took that for the end and gave up (#225, S22
     * 2026-10-17): it waits while the spinner shows, and finds the reel three slow pages down.
     */
    @Test
    public void aSlowPageWithItsSpinnerShowingIsWaitedFor() {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 0; page < 4; page++) pages.add(reels(12, 9000 - page * 100));
        pages.get(3).set(5, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(pages.get(0)), null);
        Handler main = new Handler(Looper.getMainLooper());
        int[] loaded = {1};
        long slow = ReelOrigin.END_WAIT_MS + 3000;
        grid.atEnd = () -> {
            if (grid.loading || loaded[0] >= pages.size()) return;
            grid.loading = true;
            grid.bind();
            main.postDelayed(() -> {
                grid.reels.addAll(pages.get(loaded[0]++));
                grid.loading = false;
                grid.bind();
            }, slow);
        };

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(40);
        assertEquals(HookStatus.report() + " drags " + grid.drags.size() + " pages " + loaded[0], List.of(REEL), shown);
        assertNull(ShadowToast.getTextOfLatestToast());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertFalse(report, report.contains(ReelOrigin.NOT_FOUND));
    }

    /** A spinner that never stops doesn't keep the search going: it gives up after the longer wait. */
    @Test
    public void aSpinnerThatNeverStopsGivesUpAfterTheLongerWait() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(5, 9000), null);
        grid.loading = true;
        grid.bind();
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(ReelOrigin.END_WAIT_MS / 1000 + 3);
        assertNull("still waiting while it spins", ShadowToast.getTextOfLatestToast());
        assertEquals(REEL, ReelOrigin.heldForTests());
        idleSeconds(ReelOrigin.LOAD_WAIT_MS / 1000);
        assertEquals("Couldn't find the reel you came from", ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue("and why", report.contains(ReelOrigin.ENDED + " 1"));
    }

    /**
     * A profile opened straight on its Reels tab, as 451 does for some reels (#30): the tab resumes
     * before its first page, over the grey placeholders Instagram puts up, which fill the screen and
     * carry no name. They aren't taken for the grid's end: nothing is dragged and no button goes up
     * until the page comes, however long it takes, and then the reel is found.
     */
    @Test
    public void aTabOpenedBeforeItsFirstPageWaitsPastThePlaceholders() {
        List<String> first = reels(12, 9000);
        first.set(10, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(), null);
        grid.placeholders = RecyclerView.PER_ROW * RecyclerView.SHOWN_ROWS;
        grid.bind();
        assertFalse("the placeholders fill the screen", grid.canScrollVertically(1));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            grid.reels.addAll(first);
            grid.bind();
        }, 6000);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(5);
        assertNull("no giving up over the placeholders", ShadowToast.getTextOfLatestToast());
        assertTrue("nothing dragged over them", grid.drags.isEmpty());
        assertNull("no button over them", ReelOrigin.buttonForTests());
        assertEquals(REEL, ReelOrigin.heldForTests());

        idleSeconds(5);
        assertEquals(List.of(REEL), shown);
        assertFalse("dragged once the page came", grid.drags.isEmpty());
        assertNull(ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.WAITED + " 1"));
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertFalse(report, report.contains(ReelOrigin.NOT_FOUND));
    }

    /** Placeholders that no page ever replaces give up after the search's time, with the grid unmoved. */
    @Test
    public void placeholdersThatNeverGiveWayGiveUpWithoutMoving() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(), null);
        grid.placeholders = RecyclerView.PER_ROW * RecyclerView.SHOWN_ROWS;
        grid.bind();

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(10);
        assertNull("still waiting for the first page", ShadowToast.getTextOfLatestToast());
        idleSeconds(7);
        assertEquals("Couldn't find the reel you came from", ShadowToast.getTextOfLatestToast());
        assertTrue(grid.drags.isEmpty());
        assertTrue(grid.scrolls.isEmpty());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.WAITED + " 1"));
        assertTrue(report, report.contains(ReelOrigin.NOT_FOUND + " 1"));
        assertTrue("and why", report.contains(ReelOrigin.NO_TILES + " 1"));
    }

    /**
     * A first page too short to fill the screen, which Instagram tops up a page at a time: each page
     * coming in at the end starts the wait for more again, so a reel a few pages on is still found.
     */
    @Test
    public void tilesComingInAtTheEndStartTheWaitAgain() {
        List<String> third = reels(12, 8000);
        third.set(10, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(3, 9000), null);
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> {
            grid.reels.addAll(reels(3, 8500));
            grid.bind();
        }, 3000);
        main.postDelayed(() -> {
            grid.reels.addAll(third);
            grid.bind();
        }, 6000);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(12);
        assertEquals(List.of(REEL), shown);
        assertNull(ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
    }

    /**
     * A tile is the reel when Instagram named it by the reel's id, the reel's own number then its
     * author's, or by the reel's own number alone, either way round.
     */
    @Test
    public void aTileIsTheReelByItsIdOrItsOwnNumber() {
        assertTrue(ReelOrigin.matches(REEL, REEL));
        assertTrue("a tile named by the reel's own number", ReelOrigin.matches("3100", REEL));
        assertTrue("an id without its author", ReelOrigin.matches(REEL, "3100"));
        assertFalse("another reel by the same author", ReelOrigin.matches("3101_" + AUTHOR, REEL));
        assertFalse("a number that only starts the same", ReelOrigin.matches("31_" + AUTHOR, REEL));
        assertFalse("a name that's no reel's", ReelOrigin.matches("row_header", REEL));
        assertFalse(ReelOrigin.matches("_" + AUTHOR, REEL));
        assertFalse(ReelOrigin.matches(null, REEL));
        assertFalse(ReelOrigin.matches("", REEL));
        assertFalse(ReelOrigin.matches(REEL, null));
        assertFalse(ReelOrigin.matches(REEL, ""));

        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(List.of("3000_7", "3100", "2900_7"), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        ShadowLooper.idleMainLooper();
        assertEquals("found by its own number", List.of("3100"), shown);
        assertNull(ReelOrigin.heldForTests());
    }

    /** Instagram numbers a post by when it went up, so a smaller number is an older post, at any length. */
    @Test
    public void aSmallerNumberIsAnOlderPost() {
        assertTrue(ReelOrigin.olderThan("999", "1000"));
        assertFalse(ReelOrigin.olderThan("1000", "999"));
        assertFalse("the reel itself", ReelOrigin.olderThan("3100", "3100"));
        assertTrue(ReelOrigin.olderThan("3456789012345678901", "3456789012345678902"));
        assertTrue("past a long's range", ReelOrigin.olderThan("9456789012345678901", "13456789012345678901"));
        assertTrue("leading zeros", ReelOrigin.olderThan("0999", "1000"));
        assertFalse(ReelOrigin.olderThan("01000", "999"));
    }

    /**
     * A screen of reels all older than the one tapped, more than could be pinned, is past where it
     * would sit: a trial reel, which Instagram doesn't put on the profile, or one taken off it
     * (#225). The search stops there with no drag and says the reel isn't on the profile.
     */
    @Test
    public void aGridOfOlderReelsSaysTheReelIsNotOnTheProfile() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(30, 3000), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(1);
        assertTrue(shown.isEmpty());
        assertTrue("nothing was dragged", grid.drags.isEmpty());
        assertTrue("nothing was scrolled", grid.scrolls.isEmpty());
        assertEquals("That reel isn't on their profile. It might be a trial reel.", ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        assertNull(ReelOrigin.buttonForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.NOT_FOUND + " 1"));
        assertTrue("and why", report.contains(ReelOrigin.PASSED + " 1"));
    }

    /** With Wait for a tap on, a grid already past the reel says so instead of offering Find the reel for nothing. */
    @Test
    public void waitForATapSaysSoBeforeOfferingTheButton() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(30, 3000), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
        idleSeconds(1);
        assertNull(ReelOrigin.buttonForTests());
        assertEquals("only the grid on the screen", 1, content().getChildCount());
        assertEquals("That reel isn't on their profile. It might be a trial reel.", ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(ReelOrigin.OFFERED));
        assertTrue(report, report.contains(ReelOrigin.PASSED + " 1"));
    }

    /**
     * Up to three reels pinned above the rest can be older than the reel, and an owner who moved
     * reels around leaves older ones among newer ones, so neither ends the search while newer reels
     * still show: it goes on and finds the reel further down.
     */
    @Test
    public void pinnedAndMovedOlderReelsDoNotEndTheSearch() {
        List<String> reels = new ArrayList<>(List.of("1000_7", "900_7", "800_7"));
        reels.addAll(reels(9, 9000));
        reels.set(7, "700_7");
        reels.addAll(reels(12, 8000));
        reels.set(20, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(5);
        assertEquals(HookStatus.report().toString(), List.of(REEL), shown);
        assertNull(ShadowToast.getTextOfLatestToast());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertFalse(report, report.contains(ReelOrigin.PASSED));
    }

    /**
     * Dragged on through reels newer than the one tapped, the search stops once the grid reaches
     * older ones, long before the grid's end, and the grid goes back to where it was.
     */
    @Test
    public void theSearchStopsOnceTheGridPassesTheReelsDate() {
        List<String> reels = reels(12, 9000);
        reels.addAll(reels(30, 3000));
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(10);
        assertTrue(shown.isEmpty());
        assertFalse("dragged through the newer reels", grid.drags.isEmpty());
        assertTrue("stopped well before the grid's end", grid.drags.size() < 6);
        assertEquals("then one scroll back", 1, grid.scrolls.size());
        assertEquals("back at the top", 0, grid.firstRow());
        assertEquals("That reel isn't on their profile. It might be a trial reel.", ShadowToast.getTextOfLatestToast());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.PASSED + " 1"));
        assertFalse(report, report.contains(ReelOrigin.ENDED));
    }

    /**
     * A drag that ends at the end while Instagram is still busy loads nothing, and a drag there a
     * moment later does, so the search drags again at the end and finds the reel on the next page.
     */
    @Test
    public void aPageThatComesOnlyForALaterDragIsStillSearched() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(12, 9000), null);
        List<String> next = reels(12, 8000);
        next.set(4, REEL);
        int[] asks = {0};
        grid.atEnd = () -> {
            if (asks[0]++ == 1) grid.reels.addAll(next);
        };

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON);
        idleSeconds(5);
        assertEquals(List.of(REEL), shown);
        assertTrue("it asked again after the first ask loaded nothing", asks[0] >= 2);
        assertNull(ReelOrigin.heldForTests());
    }

    /** Leaving the tab stops the search and keeps the reel, and coming back to it finds it. */
    @Test
    public void leavingTheTabFirstKeepsTheReelForNextTime() {
        List<String> reels = reels(30, 9000);
        reels.set(25, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);
        Tab tab = new Tab(profile, grid, AUTHOR);
        ReelOrigin.reelsTabResumed(tab, ON);
        ShadowLooper.idleMainLooper();
        tab.resumed = false;
        idleSeconds(3);
        int moved = grid.drags.size();
        assertTrue(shown.isEmpty());
        assertEquals("it kept the reel", REEL, ReelOrigin.heldForTests());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelOrigin.LEFT + " 1"));
        idleSeconds(3);
        assertEquals("and stopped", moved, grid.drags.size());

        tab.resumed = true;
        ReelOrigin.reelsTabResumed(tab, ON);
        idleSeconds(5);
        assertEquals(List.of(REEL), shown);
        assertNull(ReelOrigin.heldForTests());
    }

    /**
     * While the grid is dragged, Stop looking is up on the profile's screen. A tap on it ends the
     * search where the grid is, with no toast and no scroll back, and drops the reel.
     */
    @Test
    public void stopLookingEndsTheSearchWhereItIs() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels(12, 9000), null);
        int[] page = {0};
        grid.atEnd = () -> grid.reels.addAll(reels(12, 8000 - 100 * page[0]++));

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, OFF);
        idleSeconds(1);
        TextView button = ReelOrigin.buttonForTests();
        assertNotNull("up while the grid is dragged", button);
        assertEquals("Stop looking", button.getText().toString());
        assertSame(content(), button.getParent());
        assertEquals("a button to a screen reader", Button.class.getName(), String.valueOf(role(button)));
        assertEquals("Stop stays readable on the dark surface", ScreenColors.DEFAULT.heading, button.getCurrentTextColor());
        assertEquals("the changed action is announced", View.ACCESSIBILITY_LIVE_REGION_POLITE, button.getAccessibilityLiveRegion());
        int row = grid.firstRow();
        assertTrue("it had moved on", row > 0);
        int moved = grid.drags.size();

        assertTrue(button.performClick());
        idleSeconds(5);
        assertEquals("no drag after the tap", moved, grid.drags.size());
        assertTrue("never scrolled back", grid.scrolls.isEmpty());
        assertEquals("the grid stays where it was stopped", row, grid.firstRow());
        assertNull(button.getParent());
        assertNull(ReelOrigin.buttonForTests());
        assertNull(ReelOrigin.heldForTests());
        assertNull("no toast", ShadowToast.getTextOfLatestToast());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.STOPPED + " 1"));
        assertFalse(report, report.contains(ReelOrigin.NOT_FOUND));
        assertFalse("nothing waited for a tap", report.contains(ReelOrigin.OFFERED));
    }

    /**
     * With Wait for a tap on, a reel past the first screen isn't looked for until Find the reel is
     * tapped. Then the button reads Stop looking until the reel is found, and goes with the search.
     */
    @Test
    public void waitForATapLooksOnlyOnceFindTheReelIsTapped() {
        List<String> reels = reels(30, 9000);
        reels.set(25, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
        idleSeconds(5);
        TextView button = ReelOrigin.buttonForTests();
        assertNotNull(button);
        assertEquals("Find the reel", button.getText().toString());
        assertEquals("the waiting action uses the shared primary style", ScreenColors.DEFAULT.onAccent, button.getCurrentTextColor());
        assertSame(content(), button.getParent());
        assertTrue("nothing moved before the tap", grid.drags.isEmpty());
        assertTrue(shown.isEmpty());
        assertEquals(REEL, ReelOrigin.heldForTests());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelOrigin.OFFERED + " 1"));

        assertTrue(button.performClick());
        ShadowLooper.idleMainLooper();
        assertEquals("Stop looking", button.getText().toString());
        assertEquals("the cancellable search uses the shared secondary style", ScreenColors.DEFAULT.heading, button.getCurrentTextColor());
        assertSame("the same button, relabeled", button, ReelOrigin.buttonForTests());
        idleSeconds(5);
        assertEquals(List.of(REEL), shown);
        assertFalse(grid.drags.isEmpty());
        assertNull("gone once found", button.getParent());
        assertNull(ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 1"));
        assertTrue(report, report.contains(ReelOrigin.OFFERED + " 1"));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theReelActionHasNativeFocusTouchFeedbackAndRoomForWrappedText() throws Exception {
        openProfile(List.of(REEL), null);
        int[] tapped = {0};
        TextView button = ReelOrigin.button(profile, () -> tapped[0]++);
        content().addView(button);
        layout();
        button.setText("Find the reel you came from on this profile");
        button.setTextSize(32);
        int width = Math.round(220 * profile.getResources().getDisplayMetrics().density);
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        button.layout(0, 0, width, button.getMeasuredHeight());
        assertEquals(Button.class.getName(), String.valueOf(role(button)));
        assertTrue(button.isFocusable());
        assertTrue(button.isClickable());
        assertTrue("press and focus use the same feedback as settings actions", button.getBackground() instanceof RippleDrawable);
        assertTrue("a long translation wraps without truncation", button.getLineCount() > 1);
        assertNull(button.getEllipsize());
        assertTrue(button.getHeight() >= Math.round(48 * profile.getResources().getDisplayMetrics().density));
        assertTrue(button.performClick());
        assertEquals(1, tapped[0]);
        captureReelActions();
    }

    /** Export the production Find/Stop transition, without running a second screen implementation. */
    private void captureReelActions() throws Exception {
        String output = System.getenv("HUSHGRAM_UI_CAPTURES");
        if (output == null || output.isEmpty()) output = System.getProperty("HUSHGRAM_UI_CAPTURES");
        if (output == null || output.isEmpty()) return;
        float fontScale = profile.getResources().getConfiguration().fontScale;
        int applicationFlags = RuntimeEnvironment.getApplication().getApplicationInfo().flags;
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        try {
            for (boolean rtl : new boolean[]{false, true}) {
                RuntimeEnvironment.setFontScale(rtl ? 2f : 1f);
                for (boolean light : new boolean[]{true, false}) {
                    ReelOrigin.opening(new Reel(REEL), ON);
                    openProfile(reels(30, 9000), null);
                    profile.setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar
                            : android.R.style.Theme_Material_NoActionBar);
                    content().setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                    content().setBackgroundColor(light ? Color.WHITE : Color.BLACK);
                    ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
                    layout();
                    TextView action = ReelOrigin.buttonForTests();
                    assertNotNull(action);
                    assertEquals("Find the reel", action.getText().toString());
                    assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR, action.getLayoutDirection());
                    String context = "runtime-reel-" + (light ? "light" : "dark")
                            + (rtl ? "-rtl-200" : "-100") + "-api" + android.os.Build.VERSION.SDK_INT;
                    captureReelAction(action, new File(output, context), "01-find", light);
                    assertTrue(action.performClick());
                    layout();
                    assertSame(action, ReelOrigin.buttonForTests());
                    assertEquals("Stop looking", action.getText().toString());
                    captureReelAction(action, new File(output, context), "02-stop", light);
                    assertTrue(action.performClick());
                    assertNull("Capturing Stop must still cancel the real search", ReelOrigin.heldForTests());
                    assertNull(action.getParent());
                }
            }
        } finally {
            ReelOrigin.forget();
            RuntimeEnvironment.setFontScale(fontScale);
            RuntimeEnvironment.getApplication().getApplicationInfo().flags = applicationFlags;
        }
    }

    private static void captureReelAction(TextView action, File directory, String name, boolean light) throws Exception {
        assertTrue(action.isShown());
        assertTrue(action.getWidth() > 0 && action.getHeight() > 0);
        assertTrue("The real label must fit at every captured text scale", action.getLayout().getEllipsisCount(0) == 0);
        int padding = Math.round(16 * action.getResources().getDisplayMetrics().density);
        Bitmap pixels = Bitmap.createBitmap(action.getWidth() + 2 * padding, action.getHeight() + 2 * padding,
                Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(pixels);
            canvas.drawColor(light ? Color.WHITE : Color.BLACK);
            canvas.translate(padding, padding);
            action.draw(canvas);
            assertTrue(directory.mkdirs() || directory.isDirectory());
            try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } finally { pixels.recycle(); }
    }

    /** With Wait for a tap on, a reel on the first screen is outlined straight away, with no button. */
    @Test
    public void waitForATapStillOutlinesAReelOnTheFirstScreen() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(List.of("3000_7", REEL, "2900_7"), null);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(REEL), shown);
        assertNull(ReelOrigin.buttonForTests());
        assertEquals("nothing but the grid on the screen", 1, content().getChildCount());
    }

    /** Waiting for the tap, a grid with no tiles yet gets no button until its first page binds. */
    @Test
    public void theFindButtonWaitsForTheGridsFirstTiles() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(new ArrayList<>(), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
        idleSeconds(2);
        assertNull("no button over an empty grid", ReelOrigin.buttonForTests());
        assertEquals(1, content().getChildCount());

        grid.reels.addAll(reels(12, 9000));
        grid.bind();
        idleSeconds(1);
        assertNotNull(ReelOrigin.buttonForTests());
        assertTrue("still waiting for the tap", grid.drags.isEmpty());
    }

    /** Waiting for the tap, a reel scrolled to by hand is outlined, and the button goes. */
    @Test
    public void whileWaitingAReelScrolledToByHandIsOutlined() {
        List<String> reels = reels(30, 9000);
        reels.set(25, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, ON);
        idleSeconds(1);
        TextView button = ReelOrigin.buttonForTests();
        assertNotNull(button);

        grid.scrollBy(0, 7 * RecyclerView.ROW);
        idleSeconds(1);
        assertEquals(List.of(REEL), shown);
        assertNull(button.getParent());
        assertTrue("never dragged", grid.drags.isEmpty());
        assertNull(ReelOrigin.heldForTests());
    }

    /**
     * Leaving the tab takes the button down and keeps the reel, coming back puts one button up again,
     * and a reel tapped elsewhere takes the old search's button down.
     */
    @Test
    public void leavingTheTabTakesTheButtonDown() {
        List<String> reels = reels(30, 9000);
        reels.set(25, REEL);
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(reels, null);
        Tab tab = new Tab(profile, grid, AUTHOR);
        ReelOrigin.reelsTabResumed(tab, ON, ON);
        idleSeconds(1);
        TextView button = ReelOrigin.buttonForTests();
        assertNotNull(button);

        tab.resumed = false;
        idleSeconds(1);
        assertNull(button.getParent());
        assertNull(ReelOrigin.buttonForTests());
        assertEquals(REEL, ReelOrigin.heldForTests());

        tab.resumed = true;
        ReelOrigin.reelsTabResumed(tab, ON, ON);
        ReelOrigin.reelsTabResumed(tab, ON, ON);
        idleSeconds(1);
        assertNotNull(ReelOrigin.buttonForTests());
        assertEquals("one button, not two", 2, content().getChildCount());

        ReelOrigin.opening(new Reel("3200_" + AUTHOR), ON);
        assertEquals(1, content().getChildCount());
        assertNull(ReelOrigin.buttonForTests());
    }

    /**
     * Only the Reels tab of the reel's own author, in the screen opened from the reel, looks. A
     * profile reached from there, another screen, and a screen that came too late to be the
     * profile's are left alone.
     */
    @Test
    public void onlyTheAuthorsProfileInItsOwnScreenLooks() {
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(List.of(REEL), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, "8"), ON);
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        ReelOrigin.reelsTabResumed(new Tab(other, grid, AUTHOR), ON);
        idleSeconds(6);
        assertTrue(shown.isEmpty());
        assertEquals("still held for the author's own tab", REEL, ReelOrigin.heldForTests());
        String report = HookStatus.report().toString();
        assertTrue("another profile's tab is counted", report.contains(ReelOrigin.OTHER_PROFILE + " 1"));

        ReelOrigin.forget();
        ReelOrigin.opening(new Reel(REEL), ON);
        idleSeconds(6);
        Activity late = Robolectric.buildActivity(Activity.class).setup().get();
        assertNull("no screen came in time, so the reel is dropped", ReelOrigin.heldForTests());
        ReelOrigin.reelsTabResumed(new Tab(late, grid, AUTHOR), ON);
        idleSeconds(6);
        assertTrue(shown.isEmpty());
    }

    /**
     * A photo reel or a carousel lives in the profile's posts grid, never its Reels tab (#225), so
     * the posts grid is searched for it and the Reels tab is left alone.
     */
    @Test
    public void aPhotoReelIsFoundInThePostsGrid() {
        for (Function<Object, Integer> type : List.of(PHOTO, CAROUSEL)) {
            ReelOrigin.forget();
            shown.clear();
            List<String> posts = reels(30, 9000);
            posts.set(20, REEL);
            ReelOrigin.opening(new Reel(REEL), ON, type);
            openProfile(posts, null);

            ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, OFF);
            idleSeconds(3);
            assertTrue("the Reels tab doesn't look for a photo reel", shown.isEmpty());
            assertTrue(grid.drags.isEmpty());

            ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON, OFF);
            idleSeconds(5);
            assertEquals(HookStatus.report() + " drags " + grid.drags, List.of(REEL), shown);
            assertFalse("dragged down to it", grid.drags.isEmpty());
            assertNull("dropped once found", ReelOrigin.heldForTests());
        }
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.FOUND + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.REEL_ORIGIN).toString(), HookStatus.missing(FamilyNames.REEL_ORIGIN).isEmpty());
    }

    /**
     * A video reel, or one whose type can't be read, is looked for in the Reels tab as before: the
     * posts grid never moves for it.
     */
    @Test
    public void aVideoReelLeavesThePostsGridAlone() {
        for (Function<Object, Integer> type : List.of(VIDEO, UNKNOWN)) {
            ReelOrigin.forget();
            shown.clear();
            ReelOrigin.opening(new Reel(REEL), ON, type);
            List<String> posts = reels(30, 9000);
            posts.set(20, REEL);
            openProfile(posts, null);
            RecyclerView postsGrid = grid;

            ReelOrigin.postsGridResumed(new PostsGrid(profile, postsGrid, ReelOrigin.POSTS), ON, OFF);
            idleSeconds(5);
            assertTrue("the posts grid isn't searched", shown.isEmpty());
            assertTrue(postsGrid.drags.isEmpty());
            assertEquals(REEL, ReelOrigin.heldForTests());

            ReelOrigin.reelsTabResumed(new Tab(profile, postsGrid, AUTHOR), ON, OFF);
            idleSeconds(5);
            assertEquals("the Reels tab finds it", List.of(REEL), shown);
        }
    }

    /**
     * A trial reel isn't on its poster's profile at all (#225), so nothing is held, a toast says why
     * at once, and the profile's grids are left alone.
     */
    @Test
    public void aTrialReelIsNotLookedFor() {
        ReelOrigin.opening(new Reel(REEL), ON, VIDEO, media -> true);
        ShadowLooper.idleMainLooper();
        assertNull(ReelOrigin.heldForTests());
        assertEquals("That's a trial reel, so it isn't on their profile", ShadowToast.getTextOfLatestToast());
        List<String> posts = reels(30, 9000);
        posts.set(20, REEL);
        openProfile(posts, null);

        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, OFF);
        ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON, OFF);
        idleSeconds(3);
        assertTrue(shown.isEmpty());
        assertTrue(grid.drags.isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelOrigin.TRIAL + " 1"));
    }

    /** A reel whose trial flag reads false, or can't be read (null or a throw), is held and found as before. */
    @Test
    public void aReelThatIsNoTrialIsFoundAsBefore() {
        Function<Object, Boolean> throwing = media -> { throw new ClassCastException("not a Media"); };
        for (Function<Object, Boolean> trial : List.<Function<Object, Boolean>>of(media -> false, media -> null, throwing)) {
            ReelOrigin.forget();
            shown.clear();
            ReelOrigin.opening(new Reel(REEL), ON, VIDEO, trial);
            assertEquals(REEL, ReelOrigin.heldForTests());
            openProfile(List.of("3000_7", REEL, "2900_7"), null);

            ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), ON, OFF);
            idleSeconds(2);
            assertEquals(List.of(REEL), shown);
        }
        assertNull("no toast", ShadowToast.getTextOfLatestToast());
    }

    /**
     * Only the profile's main posts grid looks. Its tagged, fan club and long video grids are left
     * alone, and so is a second posts grid in the same screen, which is another profile's.
     */
    @Test
    public void onlyTheFirstMainPostsGridLooks() {
        ReelOrigin.opening(new Reel(REEL), ON, PHOTO);
        openProfile(List.of("3000_7", REEL, "2900_7"), null);
        for (String other : List.of("profile_tagged_media_photos_of_you", "profile_fan_club_grid", "profile_longform")) {
            ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, other), ON, OFF);
        }
        Activity elsewhere = Robolectric.buildActivity(Activity.class).setup().get();
        ReelOrigin.postsGridResumed(new PostsGrid(elsewhere, grid, ReelOrigin.POSTS), ON, OFF);
        idleSeconds(3);
        assertTrue("no other grid looked", shown.isEmpty());
        assertEquals(REEL, ReelOrigin.heldForTests());

        PostsGrid author = new PostsGrid(profile, grid, ReelOrigin.POSTS);
        PostsGrid another = new PostsGrid(profile, grid, ReelOrigin.POSTS);
        author.resumed = false;
        ReelOrigin.postsGridResumed(author, ON, OFF);
        ReelOrigin.postsGridResumed(another, ON, OFF);
        idleSeconds(3);
        assertTrue("the author's grid was left first, and the second is another profile's", shown.isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ReelOrigin.OTHER_PROFILE + " 1"));

        author.resumed = true;
        ReelOrigin.postsGridResumed(author, ON, OFF);
        ShadowLooper.idleMainLooper();
        assertEquals("back on the author's grid, it's found", List.of(REEL), shown);
    }

    /**
     * Profile posts as a list asks before opening a grid's first post: only the author's main posts
     * grid, while a photo reel is held and the switch is on, is kept up for the search (#225).
     */
    @Test
    public void profilePostsAsAListLeavesTheAuthorsGridUpForAPhoto() {
        ReelOrigin.opening(new Reel(REEL), ON, PHOTO);
        openProfile(List.of("3000_7", REEL), null);
        PostsGrid author = new PostsGrid(profile, grid, ReelOrigin.POSTS);
        assertTrue(ReelOrigin.searchesPosts(author, ON));
        assertFalse("switched off", ReelOrigin.searchesPosts(author, OFF));
        assertFalse("the tagged grid", ReelOrigin.searchesPosts(new PostsGrid(profile, grid, "profile_tagged_media_photos_of_you"), ON));
        Activity elsewhere = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse("another screen", ReelOrigin.searchesPosts(new PostsGrid(elsewhere, grid, ReelOrigin.POSTS), ON));
        assertFalse(ReelOrigin.searchesPosts(null, ON));
        assertFalse(ReelOrigin.searchesPosts(new Object(), ON));
        assertFalse(ReelOrigin.searchesPosts(author, THROWS));

        ReelOrigin.postsGridResumed(author, ON, ON);
        assertTrue("still the author's", ReelOrigin.searchesPosts(author, ON));
        assertFalse("a second posts grid is another profile's", ReelOrigin.searchesPosts(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON));

        ReelOrigin.forget();
        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(List.of("3000_7", REEL), null);
        assertFalse("a video reel is looked for on the Reels tab", ReelOrigin.searchesPosts(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON));
    }

    /** With Wait for a tap on, a photo reel past the posts grid's first screen waits for Find the reel too. */
    @Test
    public void waitForATapHoldsThePostsGridSearch() {
        List<String> posts = reels(30, 9000);
        posts.set(25, REEL);
        ReelOrigin.opening(new Reel(REEL), ON, PHOTO);
        openProfile(posts, null);

        ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON, ON);
        idleSeconds(5);
        TextView button = ReelOrigin.buttonForTests();
        assertNotNull(button);
        assertEquals("Find the reel", button.getText().toString());
        assertTrue("nothing moved before the tap", grid.drags.isEmpty());

        assertTrue(button.performClick());
        idleSeconds(5);
        assertEquals(List.of(REEL), shown);
        assertNull(ReelOrigin.heldForTests());
    }

    /** A posts grid that throws or lacks what the extension reads moves nothing and is reported under its own hook. */
    @Test
    public void postsGridFailuresAreReported() {
        ReelOrigin.opening(new Reel(REEL), ON, PHOTO);
        openProfile(List.of(REEL), null);
        ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, ReelOrigin.POSTS), THROWS, OFF);
        ReelOrigin.postsGridResumed(null, ON, OFF);
        ReelOrigin.postsGridResumed(new Object(), ON, OFF);
        idleSeconds(3);
        assertTrue(shown.isEmpty());
        assertEquals("a failure on the grid keeps the reel", REEL, ReelOrigin.heldForTests());

        String missing = HookStatus.missing(FamilyNames.REEL_ORIGIN).toString();
        assertTrue(missing, missing.contains("'" + ReelOrigin.POSTS_RESUME + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
        assertTrue(missing, missing.contains(Object.class.getName() + "#getActivity"));

        ReelOrigin.postsGridResumed(new PostsGrid(profile, grid, ReelOrigin.POSTS), ON, OFF);
        ShadowLooper.idleMainLooper();
        assertEquals("a sound grid still finds it", List.of(REEL), shown);
    }

    /** Leaving the profile drops the reel. */
    @Test
    public void leavingTheProfileDropsTheReel() {
        ReelOrigin.opening(new Reel(REEL), ON);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Robolectric.buildActivity(Activity.class).setup();
        assertEquals(REEL, ReelOrigin.heldForTests());
        controller.pause().stop().destroy();
        assertNull(ReelOrigin.heldForTests());

        // A screen made later isn't taken for a profile, and nothing is held.
        Robolectric.buildActivity(Activity.class).setup();
        assertNull(ReelOrigin.heldForTests());
    }

    /** Off to start, off, paused and before the settings are read, no reel is read or held and no grid moves. */
    @Test
    public void offToStartOffPausedAndUnreadyHoldNothing() {
        Reel reel = new Reel(REEL);
        Settings.FIND_REEL_ORIGIN.resetToDefault();
        assertFalse(Settings.FIND_REEL_ORIGIN.defaultValue);
        ReelOrigin.opening(reel);
        Settings.FIND_REEL_ORIGIN.save(false);
        ReelOrigin.opening(reel);

        Settings.FIND_REEL_ORIGIN.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelOrigin.opening(reel);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> ReelOrigin.opening(reel));
        SettingsContextRule.beforeThePauseIsDecided(() -> ReelOrigin.opening(reel));
        assertEquals("the reel wasn't even read", 0, reel.reads);
        assertNull(ReelOrigin.heldForTests());

        ReelOrigin.opening(reel);
        assertEquals("on again, the real switch holds it", REEL, ReelOrigin.heldForTests());
        openProfile(List.of(REEL), null);
        Tab tab = new Tab(profile, grid, AUTHOR);
        Settings.FIND_REEL_ORIGIN.save(false);
        ReelOrigin.reelsTabResumed(tab);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.FIND_REEL_ORIGIN.save(true);
        ReelOrigin.reelsTabResumed(tab);
        idleSeconds(6);
        assertTrue(shown.isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(FamilyNames.REEL_ORIGIN));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ReelOrigin.reelsTabResumed(tab);
        ShadowLooper.idleMainLooper();
        assertEquals("on again, the real switch finds it", List.of(REEL), shown);
    }

    /** A throw, a reel or tab missing what the extension reads, or two grids hold or move nothing and are reported. */
    @Test
    public void failuresHoldNothingAndAreReported() {
        ReelOrigin.opening(new Reel(REEL), THROWS);
        ReelOrigin.opening(null, ON);
        ReelOrigin.opening(new Object(), ON);
        assertNull(ReelOrigin.heldForTests());

        ReelOrigin.opening(new Reel(REEL), ON);
        openProfile(List.of(REEL), null);
        ReelOrigin.reelsTabResumed(new Tab(profile, grid, AUTHOR), THROWS);
        ReelOrigin.reelsTabResumed(null, ON);
        ReelOrigin.reelsTabResumed(new Object(), ON);
        TwoGrids two = new TwoGrids(profile);
        two.grid = grid;
        ReelOrigin.reelsTabResumed(two, ON);
        idleSeconds(6);
        assertTrue(shown.isEmpty());
        assertEquals("a failure on the tab keeps the reel", REEL, ReelOrigin.heldForTests());

        String missing = HookStatus.missing(FamilyNames.REEL_ORIGIN).toString();
        assertTrue(missing, missing.contains("'" + ReelOrigin.OPENING + "'"));
        assertTrue(missing, missing.contains("'" + ReelOrigin.RESUME + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
        assertTrue(missing, missing.contains(Object.class.getName() + "#getId"));
        assertTrue(missing, missing.contains(Object.class.getName() + "#getActivity"));
        assertTrue(missing, missing.contains(TwoGrids.class.getName() + "#" + ReelOrigin.GRID_TYPE + " (found 2)"));
    }

    /** Opens the profile's screen, which comes up right after the tap, with its Reels grid holding [reels]. */
    private void openProfile(List<String> reels, Runnable atEnd) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controllers.add(controller);
        profile = controller.get();
        FrameLayout root = new FrameLayout(profile);
        grid = new RecyclerView(profile);
        grid.tiles = id -> {
            Tile tile = new Tile(profile, shown);
            made.add(tile);
            return tile;
        };
        grid.atEnd = atEnd;
        grid.reels.addAll(reels);
        root.addView(grid, new FrameLayout.LayoutParams(1080, RecyclerView.ROW * RecyclerView.SHOWN_ROWS));
        profile.setContentView(root);
        grid.bind();
        layout();
    }

    /** The profile screen's content, where the button goes. */
    private FrameLayout content() {
        return profile.findViewById(android.R.id.content);
    }

    /** What a screen reader is told [view] is. */
    private static CharSequence role(View view) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        view.onInitializeAccessibilityNodeInfo(info);
        return info.getClassName();
    }

    /** [count] reels by the author, none of them the one looked for, numbered down from [from]. */
    private static List<String> reels(int count, int from) {
        List<String> reels = new ArrayList<>();
        for (int at = 0; at < count; at++) reels.add((from - at) + "_" + AUTHOR);
        return reels;
    }

    /** The last tile made for [id]. */
    private Tile tile(String id) {
        for (int at = made.size() - 1; at >= 0; at--) {
            if (id.equals(made.get(at).getTransitionName())) return made.get(at);
        }
        throw new AssertionError("no tile for " + id);
    }

    /** How many drawables sit on [view]'s overlay. */
    private static int outlines(View view) {
        Object group = ReflectionHelpers.getField(view.getOverlay(), "mOverlayViewGroup");
        List<?> drawables = ReflectionHelpers.getField(group, "mDrawables");
        return drawables == null ? 0 : drawables.size();
    }

    /** Lets [seconds] pass on the main thread, running what comes due. */
    private static void idleSeconds(long seconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        View decor = profile.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }
}
