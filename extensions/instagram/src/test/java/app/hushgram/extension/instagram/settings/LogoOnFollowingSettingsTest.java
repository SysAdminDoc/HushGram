/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
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

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;

/** The logo switch sits under Feed with Start Home on Following's patch in the build, starts off and needs that patch's first switch. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class LogoOnFollowingSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.LOGO_ON_FOLLOWING.resetToDefault();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void withoutThePatchNoRowShows() throws Exception {
        open();
        assertNull(page.getPreferenceScreen().findPreference(Settings.LOGO_ON_FOLLOWING.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.LOGO_ON_FOLLOWING.key));
    }

    @Test public void theRowStartsOffUnderFeedAndAsksForARestart() throws Exception {
        Settings.START_ON_FOLLOWING.save(true);
        open(PatchFamily.FOLLOWING_FEED);
        SwitchPreference toggle = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.LOGO_ON_FOLLOWING.key);
        assertNotNull(toggle);
        assertEquals("Feed", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Instagram logo on Home", String.valueOf(toggle.getTitle()));
        assertFalse(toggle.isChecked());
        assertTrue(toggle.isEnabled());
        assertTrue(Settings.LOGO_ON_FOLLOWING.rebootApp);
        assertTrue(PatchFamily.FOLLOWING_FEED.switches.contains(Settings.LOGO_ON_FOLLOWING));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.LOGO_ON_FOLLOWING.key));
    }

    @Test public void withStartHomeOnFollowingOffTheRowSaysWhatItNeeds() throws Exception {
        open(PatchFamily.FOLLOWING_FEED);
        Preference row = page.getPreferenceScreen().findPreference(Settings.LOGO_ON_FOLLOWING.key);
        assertNotNull(row);
        assertFalse(row.isEnabled());
        assertEquals("Turn on Start Home on Following to use this choice.", String.valueOf(row.getSummary()));
    }
}
