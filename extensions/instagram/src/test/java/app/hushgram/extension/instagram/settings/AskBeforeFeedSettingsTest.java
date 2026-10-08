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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Ask before a like's and Ask before a refresh's switches: under Feed, off to start, each its patch's one switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class AskBeforeFeedSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.ASK_BEFORE_LIKE.resetToDefault();
        Settings.ASK_BEFORE_REFRESH.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ASK_BEFORE_LIKE.resetToDefault();
        Settings.ASK_BEFORE_REFRESH.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> installed) throws Exception {
        PatchFamily.inBuildForTests = installed;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchesHaveNoRows() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_LIKE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_REFRESH.key));
    }

    @Test public void eachAloneBringsTheFeedSectionWithItsOwnSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_REFRESH));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_LIKE.key));
        assertSwitchUnderFeed(Settings.ASK_BEFORE_REFRESH, "Ask before a refresh", PatchFamily.ASK_BEFORE_REFRESH);
    }

    @Test public void theSwitchesSitUnderFeedAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_LIKE, PatchFamily.ASK_BEFORE_REFRESH));
        assertSwitchUnderFeed(Settings.ASK_BEFORE_LIKE, "Ask before a like", PatchFamily.ASK_BEFORE_LIKE);
        assertSwitchUnderFeed(Settings.ASK_BEFORE_REFRESH, "Ask before a refresh", PatchFamily.ASK_BEFORE_REFRESH);
    }

    private void assertSwitchUnderFeed(BooleanSetting setting, String title, PatchFamily family) {
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(setting.key);
        assertNotNull(title, row);
        assertEquals(title, row.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(title, section);
        assertEquals("Feed", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(setting.get());
        assertEquals(Collections.singletonList(setting), family.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(setting.key));
        row.setChecked(true);
        assertTrue(setting.get());
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
