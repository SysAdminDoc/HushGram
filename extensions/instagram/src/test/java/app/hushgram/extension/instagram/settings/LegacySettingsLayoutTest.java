/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceScreen;

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

/** The fixed Oxbow-style overview replaces HushGram's older grid and expandable-list modes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class LegacySettingsLayoutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.CATEGORY_GRID.resetToDefault();
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
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

    private List<Preference> categories() {
        List<Preference> result = new ArrayList<>();
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && item.getKey() != null
                    && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)) result.add(item);
        }
        return result;
    }

    private Preference category(String title) {
        for (Preference item : categories()) if (title.contentEquals(item.getTitle())) return item;
        throw new AssertionError("no overview row for " + title);
    }

    private void tap(Preference row) {
        assertNotNull(row.getOnPreferenceClickListener());
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theOverviewUsesTheFixedSectionListForEveryLegacyLayoutValue() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        Settings.CATEGORY_GRID.save(true);
        Settings.SETTINGS_BLACK_LOOK.save(true);
        open();

        PreferenceScreen screen = page.getPreferenceScreen();
        assertNull(screen.findPreference("hushgram_view_toggle"));
        assertNull(screen.findPreference("hushgram_grid_toggle"));
        assertNull(screen.findPreference(Settings.CATEGORY_PAGES.key));
        assertNull(screen.findPreference(Settings.CATEGORY_GRID.key));
        assertNull(screen.findPreference(Settings.SETTINGS_BLACK_LOOK.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_PAGES.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_GRID.key));
        assertEquals("all HushGram sections stay in the overview", 23, categories().size());
        assertTrue("legacy values remain saved", !Settings.CATEGORY_PAGES.savedValue() && Settings.CATEGORY_GRID.savedValue()
                && Settings.SETTINGS_BLACK_LOOK.savedValue());
    }

    @Test public void selectingASectionOpensItsOriginalControlsAndBackReturnsToTheOverview() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        Settings.CATEGORY_GRID.save(true);
        open();
        Preference feed = category("Feed");
        assertTrue(feed instanceof HushgramPreferenceFragment.Heading);
        assertEquals(0, ((HushgramPreferenceFragment.Heading) feed).getPreferenceCount());
        tap(feed);

        HushgramPreferenceFragment.Heading section = (HushgramPreferenceFragment.Heading) feed;
        assertEquals(section.getKey(), page.currentPage());
        assertTrue(section.pageHeader);
        assertFalse(section.collapsible);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertTrue(page.closeCategory());
        assertEquals("", page.currentPage());
        assertEquals(23, categories().size());
    }
}
