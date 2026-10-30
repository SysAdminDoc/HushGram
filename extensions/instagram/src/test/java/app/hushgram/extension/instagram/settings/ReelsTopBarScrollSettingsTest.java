/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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

/** The Keep the Reels top bar switch: under Reels after Clean up Reels' switches, off to start, no restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelsTopBarScrollSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_REELS_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_REELS_TOP_BAR.resetToDefault();
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
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REELS_TOP_BAR.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REELS_TOP_BAR.key));
    }
    /** Without the other Reels patches, Reels still opens for this switch alone. */
    @Test public void aloneItStillGetsReels() throws Exception {
        open(EnumSet.of(PatchFamily.KEEP_REELS_TOP_BAR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_REELS_TOP_BAR.key);
        assertNotNull(row);
        assertEquals("Reels", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderReelsAfterCleanUpReels() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER, PatchFamily.KEEP_REELS_TOP_BAR, PatchFamily.REEL_WATCH_HISTORY));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_REELS_TOP_BAR.key);
        assertNotNull(row);
        assertEquals("Keep the Reels top bar", row.getTitle().toString());
        assertEquals("Keeps the Reels title, the Friends tab and the buttons beside them at the top while you "
                + "swipe through reels, instead of sliding them away.", row.getSummary().toString());
        PreferenceGroup reels = row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        assertEquals("right after Keep clear mode on", Settings.KEEP_REEL_CLEAR_MODE.key,
                reels.getPreference(indexOf(reels, row) - 1).getKey());
        assertEquals("before Don't send reel watch history", Settings.DONT_SEND_REEL_WATCH_HISTORY.key,
                reels.getPreference(indexOf(reels, row) + 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_REELS_TOP_BAR.get());
        assertFalse("read at each scroll, so no restart", Settings.KEEP_REELS_TOP_BAR.rebootApp);
        assertEquals(Collections.singletonList(Settings.KEEP_REELS_TOP_BAR), PatchFamily.KEEP_REELS_TOP_BAR.switches);
        assertEquals("Keep the Reels top bar", PatchFamily.KEEP_REELS_TOP_BAR.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_REELS_TOP_BAR.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
