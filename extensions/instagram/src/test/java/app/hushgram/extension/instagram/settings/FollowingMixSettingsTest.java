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

/**
 * Mix in Following (#438) sits under Feed when the build has Start Home on Following with Home's
 * paging, starts off, asks for a restart and is in a backup.
 */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class FollowingMixSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.followingMixForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.FOLLOWING_MIX.resetToDefault();
    }

    private void open(PatchFamily... families) throws Exception {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void itNeedsHomesPaging() throws Exception {
        open(PatchFamily.FEED_SUGGESTIONS);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FOLLOWING_MIX.key));
        PatchFamily.followingMixForTests = false;
        open(PatchFamily.FOLLOWING_FEED);
        assertNull("not without Home's paging", page.getPreferenceScreen().findPreference(Settings.FOLLOWING_MIX.key));
    }

    @Test public void theRowStartsOffUnderFeedAndAsksForARestart() throws Exception {
        open(PatchFamily.FOLLOWING_FEED);
        SwitchPreference toggle = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FOLLOWING_MIX.key);
        assertNotNull(toggle);
        assertEquals("Feed", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Mix in Following", String.valueOf(toggle.getTitle()));
        assertFalse(toggle.isChecked());
        assertTrue("it doesn't need Start Home on Following's switch", toggle.isEnabled());
        assertTrue(Settings.FOLLOWING_MIX.rebootApp);
        assertTrue(PatchFamily.FOLLOWING_FEED.switches.contains(Settings.FOLLOWING_MIX));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FOLLOWING_MIX.key));
    }

    @Test public void theReportSaysWhenHomesPagingIsMissing() {
        PatchFamily.followingMixForTests = false;
        assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.FOLLOWING_FEED), false)
                .contains("  Mix in Following: not in this build (Home's paging didn't match)"));
        PatchFamily.followingMixForTests = true;
        assertFalse(String.join("\n", PatchFamily.reportLines(EnumSet.of(PatchFamily.FOLLOWING_FEED), false))
                .contains("Mix in Following"));
    }
}
