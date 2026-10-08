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

/** Full resolution photos' switch: under Feed on its own, off to start, says it uses more data, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FullResolutionSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.FULL_RESOLUTION_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FULL_RESOLUTION_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.FULL_RESOLUTION) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoPhotoSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FULL_RESOLUTION_PHOTOS.key));
    }
    @Test public void photoSwitchSitsUnderFeedStartsOffAndSaysItUsesMoreData() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FULL_RESOLUTION_PHOTOS.key);
        assertNotNull(row);
        assertEquals("Full resolution photos", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("This can use more data"));
        PreferenceCategory feed = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(feed);
        assertEquals("Feed", feed.getTitle().toString());
        assertEquals(2, feed.getPreferenceCount());
        assertFalse(row.isChecked());
        assertFalse(Settings.FULL_RESOLUTION_PHOTOS.get());
        assertEquals(java.util.Arrays.asList(Settings.FULL_RESOLUTION_PHOTOS, Settings.ASK_FOR_LARGER_PHOTOS),
                PatchFamily.FULL_RESOLUTION.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FULL_RESOLUTION_PHOTOS.key));
        SwitchPreference larger = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ASK_FOR_LARGER_PHOTOS.key);
        assertNotNull(larger);
        assertEquals("Ask for larger photos", larger.getTitle().toString());
        assertTrue(larger.getSummary().toString(), larger.getSummary().toString().contains("This uses more data"));
        assertTrue(larger.getSummary().toString(), larger.getSummary().toString().contains("1440"));
        assertFalse(larger.isChecked());
        assertFalse(Settings.ASK_FOR_LARGER_PHOTOS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ASK_FOR_LARGER_PHOTOS.key));
    }
    @Test public void photoSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        Settings.FULL_RESOLUTION_PHOTOS.save(true);
        assertTrue(Settings.FULL_RESOLUTION_PHOTOS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FULL_RESOLUTION_PHOTOS.get());
        assertTrue(Settings.FULL_RESOLUTION_PHOTOS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.FULL_RESOLUTION_PHOTOS.get());
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
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
