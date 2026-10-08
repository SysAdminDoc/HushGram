/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Spoof location's rows: under Ads and privacy, the switch off to start, the place waiting for it and refusing what isn't a place. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SpoofLocationSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.SPOOF_LOCATION.resetToDefault();
        Settings.SPOOF_LOCATION_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SPOOF_LOCATION.resetToDefault();
        Settings.SPOOF_LOCATION_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.SPOOF_LOCATION) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SPOOF_LOCATION.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SPOOF_LOCATION_PLACE.key));
    }
    @Test public void bothRowsSitUnderPrivacyAndThePlaceWaits() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SPOOF_LOCATION.key);
        EditTextPreference place = (EditTextPreference) page.getPreferenceScreen().findPreference(Settings.SPOOF_LOCATION_PLACE.key);
        assertNotNull(row);
        assertNotNull(place);
        assertEquals("Spoof location", row.getTitle().toString());
        assertEquals("Place", place.getTitle().toString());
        assertTrue(place.getSummary().toString(), place.getSummary().toString().contains("No place set"));
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Ads and privacy", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), place));
        assertFalse(row.isChecked());
        assertFalse(Settings.SPOOF_LOCATION.get());
        assertFalse("waits for the switch", place.isEnabled());
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(place.isEnabled());
        assertEquals(Collections.singletonList(Settings.SPOOF_LOCATION), PatchFamily.SPOOF_LOCATION.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SPOOF_LOCATION.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SPOOF_LOCATION_PLACE.key));
    }
    @Test public void thePlaceTakesOnlyAPlace() throws Exception {
        open(true);
        EditTextPreference place = (EditTextPreference) page.getPreferenceScreen().findPreference(Settings.SPOOF_LOCATION_PLACE.key);
        assertFalse(place.getOnPreferenceChangeListener().onPreferenceChange(place, "Times Square"));
        assertFalse(place.getOnPreferenceChangeListener().onPreferenceChange(place, "91, 0"));
        assertTrue(place.getOnPreferenceChangeListener().onPreferenceChange(place, ""));
        assertTrue(place.getOnPreferenceChangeListener().onPreferenceChange(place, "40.758, -73.9855"));
        place.setText("40.758, -73.9855");
        ShadowLooper.idleMainLooper();
        assertTrue(place.getSummary().toString(), place.getSummary().toString().contains("40.758, -73.9855"));
    }
    @Test public void theSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        Settings.SPOOF_LOCATION.save(true);
        assertTrue(Settings.SPOOF_LOCATION.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SPOOF_LOCATION.get());
        assertTrue(Settings.SPOOF_LOCATION.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SPOOF_LOCATION.get());
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
