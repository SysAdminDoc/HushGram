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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** piko #2210: Skip the collections tray's switch sits under Feed, starts off and is its patch's one switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SaveTraySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SKIP_COLLECTIONS_TRAY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SKIP_COLLECTIONS_TRAY.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> installed) throws Exception {
        PatchFamily.inBuildForTests = installed;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void withoutThePatchThereIsNoRow() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SKIP_COLLECTIONS_TRAY.key));
    }

    @Test public void thePatchAloneBringsTheFeedSectionWithItsSwitchOff() throws Exception {
        open(EnumSet.of(PatchFamily.SAVE_TRAY));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SKIP_COLLECTIONS_TRAY.key);
        assertNotNull(row);
        assertEquals("Skip the collections tray", row.getTitle().toString());
        assertEquals("Saving a post or reel shows Instagram's Saved message instead of the tray that asks you to start "
                + "a collection.", row.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Feed", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.SKIP_COLLECTIONS_TRAY.get());
        assertEquals(Collections.singletonList(Settings.SKIP_COLLECTIONS_TRAY), PatchFamily.SAVE_TRAY.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SKIP_COLLECTIONS_TRAY.key));
        row.setChecked(true);
        assertTrue(Settings.SKIP_COLLECTIONS_TRAY.get());
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
