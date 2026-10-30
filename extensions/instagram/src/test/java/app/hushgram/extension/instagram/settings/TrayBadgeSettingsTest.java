/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.stories.TrayBadge;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Filter the Stories tray by verified accounts (#340): a Stories switch that starts off, last of
 * Hide suggested stories' rows, a Stories to keep list under it, both kept in a backup, and off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class TrayBadgeSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.FILTER_TRAY_BY_BADGE.resetToDefault();
        Settings.TRAY_BADGE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FILTER_TRAY_BY_BADGE.resetToDefault();
        Settings.TRAY_BADGE.resetToDefault();
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
    @Test public void theSwitchAndItsListCloseTheTraysRowsAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORIES_TRAY, PatchFamily.STORY_RING));
        Preference row = page.getPreferenceScreen().findPreference(Settings.FILTER_TRAY_BY_BADGE.key);
        assertNotNull(row);
        assertEquals("Filter the Stories tray by verified accounts", row.getTitle().toString());
        assertEquals("The row of stories at the top of Home keeps only the accounts the choice below names, "
                + "by Instagram's verified badge. Your own story stays. Turn it off to see every story.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.FILTER_TRAY_BY_BADGE.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.FILTER_TRAY_BY_BADGE.key);
        // Hide Add Yours cards between stories comes right before, the ring's switch right after the list.
        assertEquals(Arrays.toString(keys), Settings.HIDE_ADD_YOURS_CARDS.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.TRAY_BADGE.key, keys[at + 1]);
        assertEquals(Arrays.toString(keys), Settings.STORY_RING.key, keys[at + 2]);

        HushgramPreferenceFragment.TrayBadgeRow badge = (HushgramPreferenceFragment.TrayBadgeRow) stories.getPreference(at + 1);
        assertEquals("Stories to keep", badge.getTitle().toString());
        assertEquals("Stories to keep", String.valueOf(badge.getDialogTitle()));
        assertEquals(Arrays.asList("Only accounts that aren't verified", "Only verified accounts"),
                Arrays.asList(badge.getEntries()[0].toString(), badge.getEntries()[1].toString()));
        assertEquals(Arrays.asList("UNVERIFIED", "VERIFIED"),
                Arrays.asList(badge.getEntryValues()[0].toString(), badge.getEntryValues()[1].toString()));
        assertEquals("UNVERIFIED", badge.getValue());
        assertEquals("Only accounts that aren't verified", badge.getSummary().toString());
        assertFalse("the list waits for the switch", badge.isEnabled());
        ((SwitchPreference) row).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(badge.isEnabled());
        badge.setValue("VERIFIED");
        ShadowLooper.idleMainLooper();
        assertEquals(TrayBadge.VERIFIED, Settings.TRAY_BADGE.savedValue());
        assertEquals("Only verified accounts", badge.getSummary().toString());

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FILTER_TRAY_BY_BADGE.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TRAY_BADGE.key));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORIES_TRAY));
        Settings.FILTER_TRAY_BY_BADGE.save(true);
        assertTrue(Settings.FILTER_TRAY_BY_BADGE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FILTER_TRAY_BY_BADGE.get());
        assertTrue(Settings.FILTER_TRAY_BY_BADGE.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_RING));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FILTER_TRAY_BY_BADGE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.TRAY_BADGE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.FILTER_TRAY_BY_BADGE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.TRAY_BADGE.key));
    }
}
