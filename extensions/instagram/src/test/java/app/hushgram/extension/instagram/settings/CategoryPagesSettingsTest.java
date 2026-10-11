/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
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

/** Open categories as pages: a list of categories, one page each, Back to the list, and search across all of them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CategoryPagesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.CATEGORY_PAGES.resetToDefault();
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
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** The titles of the categories and the category rows on the screen itself, in order. */
    private List<String> shown(Class<? extends Preference> type) {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            boolean row = item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY);
            if (type == PreferenceCategory.class ? item instanceof PreferenceCategory : row) {
                titles.add(String.valueOf(item.getTitle()));
            }
        }
        return titles;
    }

    private Preference categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && title.contentEquals(item.getTitle())) return item;
        }
        throw new AssertionError("no row for " + title + " in " + shown(Preference.class));
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    @Test public void onToStartTheCategoriesAreListed() throws Exception {
        open();
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertNotNull(pages);
        assertEquals("Open categories as pages", pages.getTitle().toString());
        assertTrue(pages.isChecked());
        assertTrue(shown(Preference.class).contains("Feed"));
    }

    @Test public void theTopButtonSwitchesBetweenListAndCategories() throws Exception {
        open();
        Preference button = page.findPreference("hushgram_view_toggle");
        assertEquals("Show all settings as a list", button.getTitle().toString());
        tap(button);
        assertFalse(Settings.CATEGORY_PAGES.savedValue());
        assertEquals("Show settings by category", button.getTitle().toString());
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        // The switch the long list shows says the same as the button did, so a tap on it changes the view.
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertSame(pages, page.getPreferenceScreen().findPreference(Settings.CATEGORY_PAGES.key));
        assertFalse(pages.isChecked());
        tap(button);
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));
        assertTrue(pages.isChecked());
    }

    @Test public void offTheCategoriesStayOneList() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_PAGES.key));
    }

    @Test public void onTheCategoriesAreListedWithPauseStillOpen() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        List<String> rows = shown(Preference.class);
        assertTrue(rows.toString(), rows.containsAll(List.of("Settings entry", "Ads and privacy", "Feed", "Notifications")));
        assertFalse("Pause and diagnostics stays a section", rows.contains("Pause and diagnostics"));
        assertEquals(List.of("Pause and diagnostics"), shown(PreferenceCategory.class));
        assertTrue(categoryRow("Ads and privacy").getSummary().toString().contains("Hide ads"));
        // Rows off the screen are still the page's own, for syncing and saved choices.
        assertNotNull(page.findPreference(Settings.HIDE_ADS.key));
    }

    @Test public void aTapOpensOnlyThatCategoryAndBackGoesToTheList() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        tap(categoryRow("Feed"));
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
        assertTrue(shown(Preference.class).isEmpty());

        assertTrue(page.closeCategory());
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("Pause and diagnostics"), shown(PreferenceCategory.class));
        assertTrue(shown(Preference.class).contains("Feed"));
        assertFalse("Back from the list closes settings", page.closeCategory());
    }

    @Test public void searchLooksThroughEveryCategoryFromAPage() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        tap(categoryRow("Feed"));
        page.searchSettings("Hide ads");
        assertTrue(shown(PreferenceCategory.class).contains("Ads and privacy"));
        assertFalse(shown(PreferenceCategory.class).contains("Feed"));
        assertTrue(shown(Preference.class).isEmpty());

        page.searchSettings("");
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
    }

    @Test public void theSwitchChangesTheOpenPage() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));

        tap(categoryRow("Settings entry"));
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(false);
        ShadowLooper.idleMainLooper();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(List.of("Settings entry", "Feed", "Ads and privacy")));
        assertFalse(page.closeCategory());
    }

    @Test public void pauseLeavesTheListAsChosen() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        BaseSettings.PAUSED.save(true);
        try {
            open();
            assertTrue(shown(Preference.class).contains("Feed"));
        } finally {
            BaseSettings.PAUSED.save(false);
        }
    }

    @Test public void theDialogsBackClosesThePageFirst() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "category_pages");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull(page);
        tap(categoryRow("Notifications"));
        Dialog window = dialog.getDialog();

        window.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertTrue("the first Back goes to the list", window.isShowing());
        assertTrue(shown(Preference.class).contains("Notifications"));

        window.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertFalse(window.isShowing());
        dialog = null;
    }
}
