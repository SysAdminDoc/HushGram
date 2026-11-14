/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Shorter Active now switch (#683): under Messages after Show when someone was last active, off to start, off while paused, and a restart to apply. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ShortActiveNowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SHORT_ACTIVE_NOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SHORT_ACTIVE_NOW.resetToDefault();
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
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.LAST_ACTIVE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHORT_ACTIVE_NOW.key));
    }
    /** Without the other message patches, Messages still opens for this switch alone. */
    @Test public void aloneItStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.SHORT_ACTIVE_NOW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHORT_ACTIVE_NOW.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertEquals("Shorter Active now", row.getTitle().toString());
    }
    @Test public void sitsRightAfterTheLastActiveSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.LAST_ACTIVE, PatchFamily.SHORT_ACTIVE_NOW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHORT_ACTIVE_NOW.key);
        SwitchPreference before = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_LAST_ACTIVE.key);
        assertSame(before.getParent(), row.getParent());
        assertEquals(before.getOrder() + 1, row.getOrder());
    }
    @Test public void startsOffNeedsARestartAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.LAST_ACTIVE, PatchFamily.SHORT_ACTIVE_NOW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHORT_ACTIVE_NOW.key);
        assertFalse(row.isChecked());
        assertFalse(Settings.SHORT_ACTIVE_NOW.get());
        assertTrue("Instagram reads the limit once per process", Settings.SHORT_ACTIVE_NOW.rebootApp);
        assertTrue(row.getSummary().toString(), row.getSummary().toString().endsWith("Restart Instagram to see the change."));
        assertEquals(Collections.singletonList(Settings.SHORT_ACTIVE_NOW), PatchFamily.SHORT_ACTIVE_NOW.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SHORT_ACTIVE_NOW.key));

        Settings.SHORT_ACTIVE_NOW.save(true);
        assertTrue(Settings.SHORT_ACTIVE_NOW.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SHORT_ACTIVE_NOW.get());
        assertTrue(Settings.SHORT_ACTIVE_NOW.savedValue());
    }
}
