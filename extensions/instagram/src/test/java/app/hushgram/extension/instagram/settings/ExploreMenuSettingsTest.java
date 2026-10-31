/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Explore without personalization's two switches: under Explore on their own, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ExploreMenuSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.resetToDefault();
        Settings.HIDE_EXPLORE_MENU.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.resetToDefault();
        Settings.HIDE_EXPLORE_MENU.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.EXPLORE_MENU) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSwitches() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.EXPLORE_WITHOUT_PERSONALIZATION.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_EXPLORE_MENU.key));
    }
    @Test public void bothSwitchesSitUnderExploreAndStartOff() throws Exception {
        open(true);
        SwitchPreference grid = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.EXPLORE_WITHOUT_PERSONALIZATION.key);
        SwitchPreference menu = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_EXPLORE_MENU.key);
        assertNotNull(grid);
        assertNotNull(menu);
        assertEquals("Explore without personalization", grid.getTitle().toString());
        assertEquals("Hide Explore's personalized menu", menu.getTitle().toString());
        assertTrue(grid.getSummary().toString(), grid.getSummary().toString().contains("in the EU"));
        assertTrue(menu.getSummary().toString(), menu.getSummary().toString().contains("top bar button"));
        PreferenceCategory explore = categoryHolding(page.getPreferenceScreen(), grid);
        assertNotNull(explore);
        assertEquals("Explore", explore.getTitle().toString());
        assertSame(explore, categoryHolding(page.getPreferenceScreen(), menu));
        assertEquals(2, explore.getPreferenceCount());
        assertFalse(grid.isChecked());
        assertFalse(menu.isChecked());
        assertFalse(Settings.EXPLORE_WITHOUT_PERSONALIZATION.get());
        assertFalse(Settings.HIDE_EXPLORE_MENU.get());
        assertEquals(Arrays.asList(Settings.EXPLORE_WITHOUT_PERSONALIZATION, Settings.HIDE_EXPLORE_MENU), PatchFamily.EXPLORE_MENU.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.EXPLORE_WITHOUT_PERSONALIZATION.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_EXPLORE_MENU.key));
    }
    @Test public void theSwitchesPersistAndHonorPause() throws Exception {
        open(true);
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.save(true);
        Settings.HIDE_EXPLORE_MENU.save(true);
        assertTrue(Settings.EXPLORE_WITHOUT_PERSONALIZATION.get());
        assertTrue(Settings.HIDE_EXPLORE_MENU.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.EXPLORE_WITHOUT_PERSONALIZATION.get());
        assertFalse(Settings.HIDE_EXPLORE_MENU.get());
        assertTrue(Settings.EXPLORE_WITHOUT_PERSONALIZATION.savedValue());
        assertTrue(Settings.HIDE_EXPLORE_MENU.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.EXPLORE_WITHOUT_PERSONALIZATION.get());
        assertTrue(Settings.HIDE_EXPLORE_MENU.get());
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
