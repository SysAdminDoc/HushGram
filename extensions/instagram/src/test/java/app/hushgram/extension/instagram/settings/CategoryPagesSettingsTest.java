/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.LinearLayout;

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

/** Oxbow-style overview and section navigation must preserve HushGram's existing Preference model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class CategoryPagesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;
    private final List<HushgramPreferenceFragment.Heading> knownCategories = new ArrayList<>();

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.CATEGORY_GRID.resetToDefault();
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.HIDE_ADS.save(false);
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
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.HIDE_ADS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        rememberCategories();
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "oxbow_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        rememberCategories();
        layoutPage();
    }

    private void rememberCategories() {
        knownCategories.clear();
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)) {
                knownCategories.add((HushgramPreferenceFragment.Heading) item);
            }
        }
    }

    private List<String> visibleCategoryTitles() {
        List<String> titles = new ArrayList<>();
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)) {
                titles.add(String.valueOf(item.getTitle()));
            }
        }
        return titles;
    }

    private HushgramPreferenceFragment.Heading categoryRow(String title) {
        for (HushgramPreferenceFragment.Heading item : knownCategories) {
            if (title.contentEquals(item.getTitle())) return item;
        }
        throw new AssertionError("no category for " + title);
    }

    private void tap(Preference row) {
        assertNotNull("the category or row must keep its action", row.getOnPreferenceClickListener());
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    private void layoutPage() {
        ShadowLooper.idleMainLooper();
        View root = dialog.getView();
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(480 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    @Test public void overviewListsEveryCategoryAndRetiresOnlyTheOldLayoutControls() throws Exception {
        open();
        PreferenceScreen screen = page.getPreferenceScreen();
        assertEquals(SettingsHeader.KEY, screen.getPreference(0).getKey());
        assertEquals("hushgram_settings_search", screen.getPreference(1).getKey());
        assertNull("the list/grid switch is no longer a competing presentation", screen.findPreference("hushgram_view_toggle"));
        assertNull(screen.findPreference("hushgram_grid_toggle"));
        assertNull("expandable categories is a legacy display option, not a feature setting",
                screen.findPreference(Settings.CATEGORY_PAGES.key));
        assertNull("the old black-only palette option is retired", screen.findPreference(Settings.SETTINGS_BLACK_LOOK.key));
        assertTrue("existing backup still includes the old keys", ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_PAGES.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_GRID.key));
        assertTrue(visibleCategoryTitles().toString(), visibleCategoryTitles().containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics", "About")));
        assertEquals("all 23 HushGram sections stay reachable", 23, visibleCategoryTitles().size());
        assertTrue(categoryRow("Feed").collapsible);
        assertFalse(categoryRow("Feed").pageHeader);
        assertEquals(0, categoryRow("Feed").getPreferenceCount());
        assertTrue("feature settings remain available through the fragment lookup", page.findPreference(Settings.HIDE_ADS.key) instanceof SwitchPreference);
    }

    @Test public void selectingACategoryShowsItsRealRowsOnADedicatedPage() throws Exception {
        open();
        HushgramPreferenceFragment.Heading feed = categoryRow("Feed");
        tap(feed);
        PreferenceScreen screen = page.getPreferenceScreen();
        assertEquals(List.of("Feed"), visibleCategoryTitles());
        assertFalse(feed.collapsible);
        assertTrue(feed.pageHeader);
        assertNull("category descriptions are not displayed", feed.getSummary());
        assertTrue(feed.getPreferenceCount() > 0);
        Preference following = screen.findPreference(Settings.START_ON_FOLLOWING.key);
        assertNotNull(following);
        assertSame(feed, following.getParent());
        assertNull("the overview search is hidden on a section page", screen.findPreference("hushgram_settings_search"));
        assertNull("another section is not left inline below this one", screen.findPreference(Settings.HIDE_ADS.key));
    }

    @Test public void backReturnsToTheOverviewBeforeItClosesSettings() throws Exception {
        openDialog();
        tap(categoryRow("Notifications"));
        assertTrue(dialog.getDialog().isShowing());
        assertEquals(List.of("Notifications"), visibleCategoryTitles());
        assertNull("the overview search is detached while a section is open",
                page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        assertTrue("section back is consumed", page.closeCategory());
        assertTrue(dialog.getDialog().isShowing());
        assertEquals("the complete overview is restored", 23, visibleCategoryTitles().size());
        assertNotNull("Back reattaches the overview search",
                page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        assertFalse("back at the overview may close the dialog", page.closeCategory());

        View back = ((LinearLayout) ((LinearLayout) dialog.getView()).getChildAt(0)).getChildAt(0);
        assertTrue(back.performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.getDialog().isShowing());
        dialog = null;
    }

    @Test public void settingRefreshKeepsTheSelectedSectionWhenOverviewSearchIsDetached() throws Exception {
        open();
        tap(categoryRow("Feed"));
        assertNull(page.getPreferenceScreen().findPreference("hushgram_settings_search"));

        page.updateUIToSettingValues();

        assertEquals("a value refresh must not silently return to an unfiltered screen",
                List.of("Feed"), visibleCategoryTitles());
        assertNull("the refresh must keep the overview search hidden on a section page",
                page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        assertTrue("Back must still rebuild the overview", page.closeCategory());
        assertEquals(23, visibleCategoryTitles().size());
    }

    @Test public void searchStillFindsSettingsAcrossCategoriesAndBackClearsIt() throws Exception {
        open();
        page.searchSettings("Hide ads");
        Preference ads = page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key);
        assertNotNull("search attaches the original setting row", ads);
        assertNotNull(ads.getParent());
        assertTrue(visibleCategoryTitles().contains("Ads and privacy"));
        page.searchSettings("zzzz-no-result");
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_settings_search_empty"));
        assertTrue(page.closeCategory());
        assertNull(page.getPreferenceScreen().findPreference("hushgram_settings_search_empty"));
        assertEquals(23, visibleCategoryTitles().size());
        assertFalse(page.closeCategory());
    }

    @Test public void customSwitchUpdatesTheOriginalPreferenceAndPersistedSetting() throws Exception {
        open();
        tap(categoryRow("Ads and privacy"));
        SwitchPreference preference = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key);
        assertNotNull(preference);
        assertFalse(preference.isChecked());
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        View bound = null;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) == preference) {
                bound = adapter.getView(i, null, new FrameLayout(controller.get()));
                break;
            }
        }
        assertNotNull("the real preference row is bound", bound);
        ExpressiveSwitch control = bound.findViewById(android.R.id.switch_widget);
        assertNotNull("the reference switch replaces the framework widget", control);
        assertFalse(control.isChecked());
        assertTrue(control.performClick());
        assertTrue(preference.isChecked());
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test @Config(qualifiers = "zh-rTW-w320dp-h480dp-mdpi")
    public void translatedSettingsKeepTheirOriginalNamesAndPersistence() throws Exception {
        Settings.HIDE_ADS.save(false);
        open();
        tap(categoryRow("廣告與隱私"));
        SwitchPreference ads = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key);
        assertNotNull(ads);
        assertEquals("隱藏廣告", ads.getTitle().toString());
        assertFalse(ads.isChecked());
        ads.setChecked(true);
        assertTrue(Settings.HIDE_ADS.get());
    }

    @Test public void everySectionRemainsReachableAndOnlyOneSectionIsVisibleAtOnce() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        open();
        assertEquals(23, knownCategories.size());
        for (HushgramPreferenceFragment.Heading section : new ArrayList<>(knownCategories)) {
            if (!page.currentPage().isEmpty()) assertTrue(page.closeCategory());
            tap(section);
            assertEquals("only the selected section is shown", List.of(section.getTitle().toString()), visibleCategoryTitles());
            assertFalse(section.collapsible);
            assertTrue(section.pageHeader);
            if (section != categoryRow("About")) assertTrue(section.getPreferenceCount() > 0);
        }
        assertTrue(page.closeCategory());
        assertEquals(23, visibleCategoryTitles().size());
    }

    @Test public void savedStateRetainsTheSelectedSectionWithoutSavingSearchText() throws Exception {
        open();
        tap(categoryRow("Feed"));
        Bundle state = new Bundle();
        page.onSaveInstanceState(state);
        assertEquals(categoryRow("Feed").getKey(), state.getString("hushgram_open_category"));
        assertEquals(List.of(categoryRow("Feed").getKey()), state.getStringArrayList("hushgram_expanded_categories"));
        assertFalse(state.containsKey("hushgram_settings_query"));
    }

    @Test public void restartActionStaysPinnedOutsideTheScrollingListWithoutDoneButton() throws Exception {
        openDialog();
        View root = dialog.getView();
        View restart = root.findViewById(SettingsDialog.RESTART_ID);
        assertEquals("the window contains only the toolbar, page and optional restart action", 3,
                ((android.view.ViewGroup) root).getChildCount());
        assertNotNull(restart);
        assertTrue("Restart now uses a ripple surface",
                restart.getBackground() instanceof android.graphics.drawable.RippleDrawable);
        android.graphics.drawable.RippleDrawable restartRipple =
                (android.graphics.drawable.RippleDrawable) restart.getBackground();
        assertNotSame("the ripple mask must not share mutable drawable state with the surface",
                restartRipple.getDrawable(0), restartRipple.getDrawable(2));
        assertTrue("the restart ripple follows its rounded card",
                restartRipple.getDrawable(2) instanceof android.graphics.drawable.GradientDrawable);
        assertEquals(ScreenColors.CORNER_L * restart.getResources().getDisplayMetrics().density,
                ((android.graphics.drawable.GradientDrawable) restartRipple.getDrawable(2)).getCornerRadius(), 0.5f);
        tap(categoryRow("Feed"));
        SwitchPreference following = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key);
        assertNotNull(following);
        boolean old = following.isChecked();
        following.setChecked(!old);
        layoutPage();
        assertEquals(View.VISIBLE, restart.getVisibility());
        following.setChecked(old);
        layoutPage();
        assertTrue("the settings dialog stays open until Back is used", dialog.getDialog().isShowing());
        dialog = null;
    }

}
