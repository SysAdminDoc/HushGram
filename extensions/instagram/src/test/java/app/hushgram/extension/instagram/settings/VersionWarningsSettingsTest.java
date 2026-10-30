/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Remove build expired popup sits under Version warnings, which was called Updates, and a search for Updates still finds it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class VersionWarningsSettingsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.REMOVE_BUILD_EXPIRED_POPUP.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PauseForTests.resume();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.REMOVE_BUILD_EXPIRED_POPUP.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.BUILD_EXPIRED_POPUP);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void theSwitchSitsUnderVersionWarningsAndStartsOn() throws Exception {
        open();
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REMOVE_BUILD_EXPIRED_POPUP.key);
        assertNotNull(row);
        assertEquals("Remove build expired popup", row.getTitle().toString());
        assertEquals("Version warnings", String.valueOf(row.getParent().getTitle()));
        assertEquals("Stop Instagram from saying this version is too old.",
                ((HushgramPreferenceFragment.Heading) row.getParent()).intro);
        assertEquals("hushgram_remove_build_expired_popup", Settings.REMOVE_BUILD_EXPIRED_POPUP.key);
        assertTrue(row.isChecked());
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            assertNotEquals("no section keeps the old name", "Updates",
                    String.valueOf(page.getPreferenceScreen().getPreference(i).getTitle()));
        }
    }

    @Test public void aSearchForItsOldNameStillFindsIt() throws Exception {
        open();
        page.searchSettings("Updates");
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BUILD_EXPIRED_POPUP.key));
        page.searchSettings("Version warnings");
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BUILD_EXPIRED_POPUP.key));
        page.searchSettings("Spoof location");
        assertNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BUILD_EXPIRED_POPUP.key));
    }
}
