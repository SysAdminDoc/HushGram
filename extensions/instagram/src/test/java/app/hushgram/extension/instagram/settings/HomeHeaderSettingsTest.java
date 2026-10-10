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
import app.hushgram.extension.shared.settings.BooleanSetting;

/** The Home header switches sit under Tab bar with the Reels tab's patch in the build, and start off. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class HomeHeaderSettingsTest {
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
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    private static BooleanSetting[] switches() {
        return new BooleanSetting[] {Settings.HIDE_HOME_CREATE_BUTTON, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON};
    }

    @Test public void withoutThePatchNoHeaderRowShows() throws Exception {
        open();
        for (BooleanSetting setting : switches()) {
            assertNull(setting.key, row(setting.key));
            assertFalse(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }

    @Test public void eachHeaderButtonHasASwitchUnderTabBarThatStartsOffAndAsksForARestart() throws Exception {
        open(PatchFamily.REELS_TAB);
        for (BooleanSetting setting : switches()) {
            SwitchPreference toggle = (SwitchPreference) row(setting.key);
            assertNotNull(setting.key, toggle);
            assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
            assertFalse(setting.key, toggle.isChecked());
            assertTrue(setting.key, setting.rebootApp);
            assertTrue(setting.key, PatchFamily.REELS_TAB.switches.stream().anyMatch(each -> each.key.equals(setting.key)));
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
        assertEquals("Hide Create on Home's header", String.valueOf(row(Settings.HIDE_HOME_CREATE_BUTTON.key).getTitle()));
        assertEquals("Hide notifications on Home's header", String.valueOf(row(Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.key).getTitle()));
    }
}
