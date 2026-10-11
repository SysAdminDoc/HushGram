/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Back leaves Home switch: under Feed beside the other Home switches, off to start, off while paused, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class BackLeavesHomeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.BACK_LEAVES_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.BACK_LEAVES_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoBackLeavesHomeSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_REFRESH));
        assertNull(page.getPreferenceScreen().findPreference(Settings.BACK_LEAVES_HOME.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_REFRESH.key));
    }
    /** Without the other feed patches, Feed still opens for this switch alone. */
    @Test public void backLeavesHomeAloneStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.BACK_LEAVES_HOME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.BACK_LEAVES_HOME.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.ASK_BEFORE_REFRESH.key));
    }
    @Test public void backLeavesHomeSwitchStartsOffUnderFeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_REFRESH, PatchFamily.POST_TIME, PatchFamily.BACK_LEAVES_HOME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.BACK_LEAVES_HOME.key);
        assertNotNull(row);
        assertEquals("Back leaves Home as it is", row.getTitle().toString());
        assertEquals("Back on Home leaves Instagram without first scrolling the feed to the top and reloading it.",
                row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int refresh = Arrays.asList(keys).indexOf(Settings.ASK_BEFORE_REFRESH.key);
        assertTrue(Arrays.toString(keys), refresh >= 0);
        assertEquals("right after Ask before a refresh", Settings.BACK_LEAVES_HOME.key, keys[refresh + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.BACK_LEAVES_HOME.get());
        assertFalse("each Back reads the switch, so no restart prompt", Settings.BACK_LEAVES_HOME.rebootApp);
        assertEquals(Collections.singletonList(Settings.BACK_LEAVES_HOME), PatchFamily.BACK_LEAVES_HOME.switches);
        assertEquals("Back leaves Home", PatchFamily.BACK_LEAVES_HOME.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.BACK_LEAVES_HOME.key));

        Settings.BACK_LEAVES_HOME.save(true);
        assertTrue(Settings.BACK_LEAVES_HOME.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.BACK_LEAVES_HOME.get());
        assertTrue(Settings.BACK_LEAVES_HOME.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.BACK_LEAVES_HOME.get());
    }
}
