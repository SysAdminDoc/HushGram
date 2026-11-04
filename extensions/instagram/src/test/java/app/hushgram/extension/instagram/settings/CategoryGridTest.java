/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The categories as a grid of tiles (#494): same sections, opened in place, no page of their own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class CategoryGridTest {
    private static final String TOGGLE = "hushgram_grid_toggle";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.CATEGORY_GRID.resetToDefault();
        Settings.SETTINGS_BLACK_LOOK.save(false);
        Settings.START_ON_FOLLOWING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.CATEGORY_GRID.resetToDefault();
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "category_grid");
        controller.get().getFragmentManager().executePendingTransactions();
        controller.recreate();
        ShadowLooper.idleMainLooper();
        findDialog();
    }

    private void findDialog() {
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_grid");
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        ShadowLooper.idleMainLooper();
    }

    private CategoryGrid grid() {
        Preference found = page.getPreferenceScreen().findPreference(CategoryGrid.KEY);
        return found instanceof CategoryGrid ? (CategoryGrid) found : null;
    }

    private List<String> tileTitles() {
        List<String> titles = new ArrayList<>();
        for (HushgramPreferenceFragment.Heading each : grid().tiles()) titles.add(String.valueOf(each.getTitle()));
        return titles;
    }

    /** The grid drawn by the page's own adapter at the phone's width. */
    private View drawn() {
        View view = grid().getView(null, new FrameLayout(controller.get()));
        int width = Math.round(320 * view.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        return view;
    }

    private static void collect(View view, List<View> tiles) {
        if (CategoryGrid.TILE_TAG.equals(view.getTag())) tiles.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), tiles);
        }
    }

    private List<View> tileViews() {
        List<View> tiles = new ArrayList<>();
        collect(drawn(), tiles);
        return tiles;
    }

    private static String titleOf(View tile) {
        return ((TextView) tile.findViewById(android.R.id.title)).getText().toString();
    }

    private View tile(String title) {
        for (View each : tileViews()) if (titleOf(each).equals(title)) return each;
        throw new AssertionError("no tile for " + title + " in " + tileTitles());
    }

    private void tapTile(String title) {
        assertTrue(tile(title).performClick());
        ShadowLooper.idleMainLooper();
    }

    /** Titles of the section headings on the page, folded or open. */
    private List<String> headings() {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading) titles.add(String.valueOf(item.getTitle()));
        }
        return titles;
    }

    private HushgramPreferenceFragment.Heading section(String title) {
        for (HushgramPreferenceFragment.Heading each : grid().tiles()) {
            if (title.contentEquals(each.getTitle())) return each;
        }
        throw new AssertionError("no section " + title);
    }

    private Preference toggle() {
        return page.getPreferenceScreen().findPreference(TOGGLE);
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theGridShowsOneTilePerSectionInPageOrder() throws Exception {
        Settings.CATEGORY_GRID.save(false);
        open();
        List<String> rows = new ArrayList<>(headings());
        assertFalse(rows.isEmpty());
        assertNull("no grid while it is off", grid());

        Settings.CATEGORY_GRID.save(true);
        page.filterSettings();
        ShadowLooper.idleMainLooper();
        assertNotNull(grid());
        assertEquals(rows, tileTitles());
        assertEquals("tiles stand in for the folded headings", List.of(), headings());
        List<View> views = tileViews();
        assertEquals(rows.size(), views.size());
        List<String> drawnTitles = new ArrayList<>();
        for (View each : views) drawnTitles.add(titleOf(each));
        assertEquals(rows, drawnTitles);
        // Two columns at 320 dp: the first two tiles share a line, the third starts the next.
        assertSame(views.get(0).getParent(), views.get(1).getParent());
        assertNotSame(views.get(0).getParent(), views.get(2).getParent());
        // Every tile carries its intro, whole.
        TextView intro = tile("Feed").findViewById(android.R.id.summary);
        assertNotNull(intro);
        assertEquals(section("Feed").intro, intro.getText().toString());
        assertEquals("the intro is never cut", Integer.MAX_VALUE, intro.getMaxLines());
    }

    @Test public void aWideScreenGetsThreeColumns() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        open();
        android.content.res.Configuration wide = new android.content.res.Configuration(
                controller.get().getResources().getConfiguration());
        wide.screenWidthDp = 700;
        controller.get().getResources().updateConfiguration(wide, controller.get().getResources().getDisplayMetrics());
        List<View> views = tileViews();
        assertSame(views.get(0).getParent(), views.get(1).getParent());
        assertSame(views.get(0).getParent(), views.get(2).getParent());
    }

    @Test public void aTileTapOpensItsRowsBelowTheGridAndFoldsTheOtherSection() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        open();
        assertNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));

        tapTile("Feed");
        assertTrue(section("Feed").expanded);
        assertEquals("only the open section has a heading", List.of("Feed"), headings());
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        ListAdapterOrder order = new ListAdapterOrder();
        assertTrue("the grid comes before the open section", order.of(grid()) < order.of(section("Feed")));
        assertTrue("the tiles stay", tileTitles().contains("Feed") && tileTitles().contains("Notifications"));

        tapTile("Notifications");
        assertTrue(section("Notifications").expanded);
        assertFalse("the other section folded", section("Feed").expanded);
        assertEquals(List.of("Notifications"), headings());
        assertNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));

        tapTile("Notifications");
        assertTrue("its own tile again only scrolls to it", section("Notifications").expanded);
        assertEquals(List.of("Notifications"), headings());

        // The open section's heading still folds it, and its tile goes back to unselected.
        tap(section("Notifications"));
        assertFalse(section("Notifications").expanded);
        assertEquals(List.of(), headings());
        assertFalse(page.closeCategory());
    }

    @Test public void theOpenTileIsAnnouncedAsAButtonWithItsState() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        open();
        tapTile("Feed");
        AccessibilityNodeInfo open = AccessibilityNodeInfo.obtain();
        tile("Feed").onInitializeAccessibilityNodeInfo(open);
        AccessibilityNodeInfo folded = AccessibilityNodeInfo.obtain();
        tile("Notifications").onInitializeAccessibilityNodeInfo(folded);
        assertEquals(android.widget.Button.class.getName(), open.getClassName().toString());
        assertTrue(open.isClickable());
        String openLabel = open.getContentDescription().toString();
        assertTrue(openLabel, openLabel.startsWith("Feed\n" + section("Feed").intro));
        String foldedLabel = folded.getContentDescription().toString();
        assertTrue(foldedLabel, foldedLabel.startsWith("Notifications\n" + section("Notifications").intro));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assertEquals("Expanded", String.valueOf(open.getStateDescription()));
            assertEquals("Collapsed", String.valueOf(folded.getStateDescription()));
        } else {
            assertTrue(openLabel, openLabel.endsWith("Expanded"));
            assertTrue(foldedLabel, foldedLabel.endsWith("Collapsed"));
        }
        // A screen reader's expand action opens a folded tile's section too.
        assertTrue(tile("Notifications").getAccessibilityDelegate()
                .performAccessibilityAction(tile("Notifications"), AccessibilityNodeInfo.ACTION_EXPAND, null));
        ShadowLooper.idleMainLooper();
        assertTrue(section("Notifications").expanded);
    }

    @Test public void theSwitchRowFlipsTheGridSavesItAndSwapsItsLabel() throws Exception {
        open();
        assertEquals("Show categories as a grid", toggle().getTitle().toString());
        assertNull(grid());

        tap(toggle());
        assertTrue(Settings.CATEGORY_GRID.savedValue());
        assertEquals("Show categories as rows", toggle().getTitle().toString());
        assertNotNull(grid());

        tap(toggle());
        assertFalse(Settings.CATEGORY_GRID.savedValue());
        assertEquals("Show categories as a grid", toggle().getTitle().toString());
        assertNull(grid());
    }

    @Test public void theSwitchRowIsOnlyOnTheCategoryPageWithNoSearch() throws Exception {
        open();
        assertNotNull(toggle());
        page.searchSettings("feed");
        ShadowLooper.idleMainLooper();
        assertNull("a search hides the switch", toggle());
        page.searchSettings("");
        ShadowLooper.idleMainLooper();
        assertNotNull(toggle());

        Settings.CATEGORY_PAGES.save(false);
        page.filterSettings();
        ShadowLooper.idleMainLooper();
        assertNull("the one long list has no grid to switch", toggle());
    }

    @Test public void aSearchHidesTheGridAndClearingItBringsTheGridBack() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        open();
        assertNotNull(grid());
        page.searchSettings("following");
        ShadowLooper.idleMainLooper();
        assertNull(grid());
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertTrue(page.closeCategory());
        ShadowLooper.idleMainLooper();
        assertNotNull(grid());
        assertFalse(page.closeCategory());
    }

    @Test public void theGridAndTheOpenSectionSurviveARecreate() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        openDialog();
        assertNotNull(grid());
        tapTile("Feed");
        controller.recreate();
        ShadowLooper.idleMainLooper();
        findDialog();
        assertNotNull("the grid is still on", grid());
        assertTrue(Settings.CATEGORY_GRID.savedValue());
        assertTrue(section("Feed").expanded);
        assertEquals(List.of("Feed"), headings());
        assertFalse(section("Notifications").expanded);
        assertFalse("a recreate adds no Back level", page.closeCategory());
    }

    @Test public void theBlackLookShowsTheIconAndTitleOnly() throws Exception {
        Settings.CATEGORY_GRID.save(true);
        open();
        assertNotNull("the classic look shows an intro", tile("Feed").findViewById(android.R.id.summary));
        Settings.SETTINGS_BLACK_LOOK.save(true);
        page.applyLook();
        ShadowLooper.idleMainLooper();
        for (View each : tileViews()) {
            assertNull(titleOf(each) + " keeps its title alone", each.findViewById(android.R.id.summary));
            assertNotNull(each.findViewById(android.R.id.title));
        }
    }

    @Test public void withTheGridOffTheRowsBehaveAsBefore() throws Exception {
        Settings.CATEGORY_GRID.save(false);
        open();
        assertNull(grid());
        assertTrue(headings().contains("Feed"));
        assertTrue(headings().contains("Notifications"));
        HushgramPreferenceFragment.Heading feed = null;
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            if ("Feed".contentEquals(String.valueOf(screen.getPreference(i).getTitle()))
                    && screen.getPreference(i) instanceof HushgramPreferenceFragment.Heading) {
                feed = (HushgramPreferenceFragment.Heading) screen.getPreference(i);
            }
        }
        assertNotNull(feed);
        assertTrue(feed.collapsible);
        tap(feed);
        assertTrue(feed.expanded);
        assertTrue("other headings stay, as rows", headings().contains("Notifications"));
        tap(feed);
        assertFalse(feed.expanded);
    }

    @Test public void theChoiceIsInTheBackupAndPauseLeavesItAlone() throws Exception {
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_GRID.key));
        assertFalse("off to start", Settings.CATEGORY_GRID.savedValue());
        Settings.CATEGORY_GRID.save(true);
        BaseSettings.PAUSED.save(true);
        open();
        assertNotNull(grid());
    }

    /** Where a preference stands on the list the person sees. */
    private final class ListAdapterOrder {
        int of(Preference preference) {
            android.widget.ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
            for (int i = 0; i < adapter.getCount(); i++) if (adapter.getItem(i) == preference) return i;
            throw new AssertionError("not on the page: " + preference.getKey());
        }
    }
}
