/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.instagram.igds.components.imagebutton.IgMultiImageButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When a profile's posts tab opens its first post, Instagram's list of full posts. Runtime decisions
 * only: that the tap lands in the list, and Back in the grid, needs a check on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PostsListTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** A posts tab as the extension sees 450's: its arguments, whether it's resumed, and its grid. */
    public static final class Tab {
        public ViewGroup recyclerView;
        public boolean resumed = true;
        public Page page;
        private final Bundle arguments;

        Tab(Bundle arguments, ViewGroup grid) {
            this.arguments = arguments;
            this.recyclerView = grid;
        }

        public Bundle getArguments() {
            return arguments;
        }

        public boolean isResumed() {
            return resumed;
        }

        public Object getParentFragment() {
            return page;
        }
    }

    /** The profile page a posts tab sits in, which outlives the tab. */
    public static final class Page {
        private final Bundle arguments = new Bundle();

        public Bundle getArguments() {
            return arguments;
        }
    }

    /** A tab without the grid field. */
    public static final class NoGrid {
        public Bundle getArguments() {
            return arguments(PostsList.POSTS_TAB, false);
        }

        public boolean isResumed() {
            return true;
        }
    }

    /** One of Instagram's own cells, a subclass of the grid's cell. */
    static final class PinnedCell extends IgMultiImageButton {
        PinnedCell(Activity activity) {
            super(activity);
        }
    }

    private Activity activity;
    private LinearLayout grid;
    private final List<String> taps = new ArrayList<>();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PROFILE_POSTS_LIST.save(true);
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, new FrameLayout.LayoutParams(1080, 1500));
        activity.setContentView(root);
        layout();
    }

    @After
    public void restore() {
        Settings.PROFILE_POSTS_LIST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, the first post of someone else's posts tab is tapped, once, and the rest are left. */
    @Test
    public void withTheSwitchOnTheFirstPostIsOpenedOnce() {
        View header = new View(activity);
        header.setOnClickListener(v -> taps.add("header"));
        grid.addView(header, new LinearLayout.LayoutParams(1080, 200));
        addRow("first", "second", "third");
        layout();
        Tab tab = tab(PostsList.POSTS_TAB, false);

        PostsList.resumed(tab, ON);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("first"), taps);
        assertTrue("the tab is marked so it isn't opened again", tab.getArguments().getBoolean(PostsList.OPENED_KEY));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(PostsList.OPENED + " 1"));

        // Back from the list resumes the tab again, and Instagram restores it with the same arguments.
        PostsList.resumed(tab, ON);
        PostsList.resumed(new Tab(tab.getArguments(), grid), ON);
        idleSeconds(6);
        assertEquals(List.of("first"), taps);
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_POSTS_LIST).toString(), HookStatus.missing(FamilyNames.PROFILE_POSTS_LIST).isEmpty());
    }

    /**
     * Tagged and back makes Instagram build the posts tab again with fresh arguments, seen on the
     * emulator: the profile page's mark keeps that tab on the grid, and another profile still opens.
     */
    @Test
    public void aPostsTabBuiltAgainOnTheSamePageStaysOnTheGrid() {
        addRow("first", "second");
        layout();
        Page page = new Page();
        Tab tab = tab(PostsList.POSTS_TAB, false);
        tab.page = page;
        PostsList.resumed(tab, ON);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("first"), taps);
        assertTrue("the page is marked too", page.getArguments().getBoolean(PostsList.OPENED_KEY));

        Tab rebuilt = tab(PostsList.POSTS_TAB, false);
        rebuilt.page = page;
        PostsList.resumed(rebuilt, ON);
        idleSeconds(6);
        assertEquals(List.of("first"), taps);

        Tab other = tab(PostsList.POSTS_TAB, false);
        other.page = new Page();
        PostsList.resumed(other, ON);
        ShadowLooper.idleMainLooper();
        assertEquals("another profile opens its own list", List.of("first", "first"), taps);
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_POSTS_LIST).toString(), HookStatus.missing(FamilyNames.PROFILE_POSTS_LIST).isEmpty());
    }

    /** A cell that leaves the tap to the view around it gets it through that view, and a subclass of the cell counts. */
    @Test
    public void theTapGoesToWhicheverViewTakesItAndSubclassesCount() {
        LinearLayout row = new LinearLayout(activity);
        row.setOnClickListener(v -> taps.add("row"));
        row.addView(new PinnedCell(activity), new LinearLayout.LayoutParams(360, 360));
        grid.addView(row, new LinearLayout.LayoutParams(1080, 360));
        layout();

        PostsList.resumed(tab(PostsList.POSTS_TAB, false), ON);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("row"), taps);
    }

    /** The posts come in after the tab is up: it waits for the first, then taps it. */
    @Test
    public void waitsForThePostsToShow() {
        Tab tab = tab(PostsList.POSTS_TAB, false);
        PostsList.resumed(tab, ON);
        idleSeconds(1);
        assertTrue(taps.isEmpty());

        addRow("first", "second");
        layout();
        idleSeconds(1);
        assertEquals(List.of("first"), taps);
    }

    /** No posts within a few seconds, a private profile or one with none, and nothing is opened later either. */
    @Test
    public void givesUpWhenNoPostShows() {
        PostsList.resumed(tab(PostsList.POSTS_TAB, false), ON);
        idleSeconds(6);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(PostsList.NOTHING + " 1"));

        addRow("first");
        layout();
        idleSeconds(6);
        assertTrue(taps.isEmpty());
    }

    /** A first cell not showing yet isn't passed over for a later one. */
    @Test
    public void aHiddenFirstCellIsWaitedForNotSkipped() {
        addRow("first", "second");
        View first = ((ViewGroup) grid.getChildAt(0)).getChildAt(0);
        first.setVisibility(View.INVISIBLE);
        layout();
        PostsList.resumed(tab(PostsList.POSTS_TAB, false), ON);
        idleSeconds(1);
        assertTrue(taps.isEmpty());

        first.setVisibility(View.VISIBLE);
        layout();
        idleSeconds(1);
        assertEquals(List.of("first"), taps);
    }

    /** Leaving the tab before the posts show opens nothing, then or later. */
    @Test
    public void leavingTheTabFirstOpensNothing() {
        Tab tab = tab(PostsList.POSTS_TAB, false);
        PostsList.resumed(tab, ON);
        tab.resumed = false;
        addRow("first");
        layout();
        idleSeconds(6);
        assertTrue(taps.isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(PostsList.LEFT + " 1"));
    }

    /** Tagged posts, the other tabs and your own profile keep their grid, and aren't marked. */
    @Test
    public void onlyThePostsTabOfSomeoneElsesProfile() {
        addRow("first");
        layout();
        Tab tagged = tab("profile_tagged_media_photos_of_you", false);
        Tab reels = tab("profile_clips", false);
        Tab yours = tab(PostsList.POSTS_TAB, true);
        PostsList.resumed(tagged, ON);
        PostsList.resumed(reels, ON);
        PostsList.resumed(yours, ON);
        PostsList.resumed(new Tab(new Bundle(), grid), ON);
        idleSeconds(6);
        assertTrue(taps.isEmpty());
        for (Tab tab : List.of(tagged, reels, yours)) assertFalse(tab.getArguments().getBoolean(PostsList.OPENED_KEY));
    }

    /** Off to start, off, paused and before the settings are read, the grid stays and the tab isn't marked. */
    @Test
    public void offToStartOffPausedAndUnreadyLeaveTheGrid() {
        addRow("first");
        layout();
        Settings.PROFILE_POSTS_LIST.resetToDefault();
        assertFalse(Settings.PROFILE_POSTS_LIST.defaultValue);
        Tab tab = tab(PostsList.POSTS_TAB, false);
        PostsList.resumed(tab);
        Settings.PROFILE_POSTS_LIST.save(false);
        PostsList.resumed(tab);

        Settings.PROFILE_POSTS_LIST.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        PostsList.resumed(tab);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> PostsList.resumed(tab));
        SettingsContextRule.beforeThePauseIsDecided(() -> PostsList.resumed(tab));

        idleSeconds(6);
        assertTrue(taps.isEmpty());
        assertFalse(tab.getArguments().getBoolean(PostsList.OPENED_KEY));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.PROFILE_POSTS_LIST));

        PostsList.resumed(tab);
        ShadowLooper.idleMainLooper();
        assertEquals("on again, the real switch opens it", List.of("first"), taps);
    }

    /** A throw, a tab missing what the extension reads, or no tab at all opens nothing and is reported. */
    @Test
    public void failuresOpenNothingAndAreReported() {
        addRow("first");
        layout();
        Tab tab = tab(PostsList.POSTS_TAB, false);
        PostsList.resumed(tab, THROWS);
        PostsList.resumed(null, ON);
        PostsList.resumed(new Object(), ON);
        PostsList.resumed(new NoGrid(), ON);
        idleSeconds(6);
        assertTrue(taps.isEmpty());
        assertFalse(tab.getArguments().getBoolean(PostsList.OPENED_KEY));

        String missing = HookStatus.missing(FamilyNames.PROFILE_POSTS_LIST).toString();
        assertTrue(missing, missing.contains("'" + PostsList.RESUME + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
        assertTrue(missing, missing.contains(Object.class.getName() + "#getArguments"));
        assertTrue(missing, missing.contains(NoGrid.class.getName() + "#" + PostsList.GRID_FIELD));
    }

    private Tab tab(String identifier, boolean self) {
        return new Tab(arguments(identifier, self), grid);
    }

    static Bundle arguments(String identifier, boolean self) {
        Bundle arguments = new Bundle();
        arguments.putString(PostsList.TAB_KEY, identifier);
        arguments.putBoolean(PostsList.SELF_KEY, self);
        return arguments;
    }

    /** A row of the grid, one cell for each name, each tap noted under its name. */
    private void addRow(String... names) {
        LinearLayout row = new LinearLayout(activity);
        for (String name : names) {
            IgMultiImageButton cell = new IgMultiImageButton(activity);
            cell.setOnClickListener(v -> taps.add(name));
            row.addView(cell, new LinearLayout.LayoutParams(360, 360));
        }
        grid.addView(row, new LinearLayout.LayoutParams(1080, 360));
    }

    /** Lets [seconds] pass on the main thread, running what comes due. */
    private static void idleSeconds(long seconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }
}
