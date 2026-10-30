/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.ListPreference;
import android.preference.PreferenceGroup;
import java.util.EnumSet;
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
import app.hushgram.extension.instagram.reels.TabBarLayoutChoice;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The tab bar layout choice: first under Tab bar, Instagram's to start, a restart to apply, kept in a backup (#356). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class TabBarLayoutSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.TAB_BAR_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.TAB_BAR_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoLayoutChoice() throws Exception {
        open(EnumSet.of(PatchFamily.REELS_TAB));
        assertNull(page.getPreferenceScreen().findPreference(Settings.TAB_BAR_LAYOUT.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_TAB.key));
    }
    /** Without the other tab bar patches, Tab bar still opens for this choice alone. */
    @Test public void theChoiceAloneStillGetsTabBar() throws Exception {
        open(EnumSet.of(PatchFamily.TAB_BAR_LAYOUT));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.TAB_BAR_LAYOUT.key);
        assertNotNull(row);
        assertEquals("Tab bar", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.START_TAB.key));
    }
    @Test public void choiceStartsAtInstagramsFirstUnderTabBar() throws Exception {
        open(EnumSet.of(PatchFamily.REELS_TAB, PatchFamily.GLASS_TAB_BAR, PatchFamily.TAB_BAR_LAYOUT));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.TAB_BAR_LAYOUT.key);
        assertNotNull(row);
        assertEquals("Tab bar layout", row.getTitle().toString());
        assertEquals("Instagram's choice", row.getEntries()[0].toString());
        assertEquals("Swipe between tabs", row.getEntries()[1].toString());
        assertEquals("Classic", row.getEntries()[2].toString());
        assertEquals(TabBarLayoutChoice.INSTAGRAM.name(), row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Instagram picks the tab bar"));
        PreferenceGroup tabBar = row.getParent();
        assertEquals("first in Tab bar", Settings.TAB_BAR_LAYOUT.key, tabBar.getPreference(0).getKey());
        assertTrue("the tab host lays itself out once, so a change takes a restart", Settings.TAB_BAR_LAYOUT.rebootApp);
        assertEquals("Tab bar layout", PatchFamily.TAB_BAR_LAYOUT.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TAB_BAR_LAYOUT.key));
    }
    @Test public void choosingSwipeChangesTheSummary() throws Exception {
        open(EnumSet.of(PatchFamily.TAB_BAR_LAYOUT));
        ListPreference row = (ListPreference) page.getPreferenceScreen().findPreference(Settings.TAB_BAR_LAYOUT.key);
        row.setValue(TabBarLayoutChoice.SWIPE.name());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Keeps the newer bar"));
        row.setValue(TabBarLayoutChoice.CLASSIC.name());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Keeps the older bar"));
    }
}
