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

/** The Keep your place in Home switch: under Feed right after Back leaves Home, off to start, off while paused, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HomePlaceSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_HOME_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_HOME_PLACE.resetToDefault();
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
    @Test public void missingPatchHasNoHomePlaceSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BACK_LEAVES_HOME));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_PLACE.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.BACK_LEAVES_HOME.key));
    }
    /** Without the other feed patches, Feed still opens for this switch alone. */
    @Test public void homePlaceAloneStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.HOME_PLACE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_PLACE.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.BACK_LEAVES_HOME.key));
    }
    @Test public void homePlaceSwitchStartsOffUnderFeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_REFRESH, PatchFamily.BACK_LEAVES_HOME, PatchFamily.HOME_PLACE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_PLACE.key);
        assertNotNull(row);
        assertEquals("Keep your place in Home", row.getTitle().toString());
        assertEquals("Coming back to Instagram leaves Home on the post you were on, however long you were away. "
                + "Pull down to refresh still reloads it.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int back = Arrays.asList(keys).indexOf(Settings.BACK_LEAVES_HOME.key);
        assertTrue(Arrays.toString(keys), back >= 0);
        assertEquals("right after Back leaves Home", Settings.KEEP_HOME_PLACE.key, keys[back + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_HOME_PLACE.get());
        assertFalse("each return reads the switch, so no restart prompt", Settings.KEEP_HOME_PLACE.rebootApp);
        assertEquals(Collections.singletonList(Settings.KEEP_HOME_PLACE), PatchFamily.HOME_PLACE.switches);
        assertEquals("Keep your place in Home", PatchFamily.HOME_PLACE.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_HOME_PLACE.key));

        Settings.KEEP_HOME_PLACE.save(true);
        assertTrue(Settings.KEEP_HOME_PLACE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_HOME_PLACE.get());
        assertTrue(Settings.KEEP_HOME_PLACE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.KEEP_HOME_PLACE.get());
    }
}
