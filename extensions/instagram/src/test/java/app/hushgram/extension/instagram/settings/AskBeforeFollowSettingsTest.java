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
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ask before a follow's switches (#631, #867): both under Profiles, off to start, and the patch's two
 * switches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class AskBeforeFollowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.ASK_BEFORE_FOLLOW.resetToDefault();
        Settings.SKIP_FOLLOW_REVIEW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ASK_BEFORE_FOLLOW.resetToDefault();
        Settings.SKIP_FOLLOW_REVIEW.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.ASK_BEFORE_FOLLOW) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_FOLLOW.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SKIP_FOLLOW_REVIEW.key));
    }
    @Test public void theSwitchSitsUnderProfilesAndStartsOff() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_FOLLOW.key);
        assertNotNull(row);
        assertEquals("Ask before a follow", row.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Profiles", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.ASK_BEFORE_FOLLOW.get());
        assertEquals(Arrays.asList(Settings.ASK_BEFORE_FOLLOW, Settings.SKIP_FOLLOW_REVIEW), PatchFamily.ASK_BEFORE_FOLLOW.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ASK_BEFORE_FOLLOW.key));
        row.setChecked(true);
        assertTrue(Settings.ASK_BEFORE_FOLLOW.get());

        SwitchPreference review = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SKIP_FOLLOW_REVIEW.key);
        assertNotNull(review);
        assertEquals("Follow without the review sheet", review.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), review));
        assertFalse(review.isChecked());
        assertFalse(Settings.SKIP_FOLLOW_REVIEW.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SKIP_FOLLOW_REVIEW.key));
        review.setChecked(true);
        assertTrue(Settings.SKIP_FOLLOW_REVIEW.get());
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
