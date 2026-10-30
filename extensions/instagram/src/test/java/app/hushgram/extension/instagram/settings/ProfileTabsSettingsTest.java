/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.widget.ListView;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.profile.ProfileTabs;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The Tabs on your profile and Tabs on other profiles rows (#296): both under Profiles, every tab
 * ticked to start, the grid never offered, each saving its own pick.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileTabsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        Settings.HIDDEN_OWN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        Settings.HIDDEN_OWN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.PROFILE_TABS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    private Preference own() {
        return page.getPreferenceScreen().findPreference(Settings.HIDDEN_OWN_PROFILE_TABS.key);
    }

    private Preference others() {
        return page.getPreferenceScreen().findPreference(Settings.HIDDEN_PROFILE_TABS.key);
    }

    private static ListView pick(Preference row) {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        return ShadowAlertDialog.getLatestAlertDialog().getListView();
    }

    @Test public void missingPatchHasNoTabsRows() throws Exception {
        open(false);
        assertNull(own());
        assertNull(others());
    }

    /** Both rows sit under Profiles, yours first, each offering the three tabs all ticked and never the grid. */
    @Test public void everyTabStartsTickedAndTheGridIsNotOffered() throws Exception {
        open(true);
        Preference own = own();
        Preference others = others();
        assertNotNull(own);
        assertNotNull(others);
        PreferenceGroup profiles = own.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        assertSame(profiles, others.getParent());
        int at = -1;
        for (int i = 0; i < profiles.getPreferenceCount(); i++) {
            if (profiles.getPreference(i) == own) at = i;
        }
        assertSame("other profiles come right after yours", others, profiles.getPreference(at + 1));
        assertEquals("Tabs on your profile", own.getTitle().toString());
        assertEquals("Tabs on other profiles", others.getTitle().toString());
        assertTrue(own.getSummary().toString(), own.getSummary().toString().startsWith("Every tab shows on your profile"));
        assertTrue(others.getSummary().toString(), others.getSummary().toString().startsWith("Every tab shows on other people's"));
        assertEquals(Arrays.asList(Settings.HIDDEN_OWN_PROFILE_TABS, Settings.HIDDEN_PROFILE_TABS),
                PatchFamily.PROFILE_TABS.choices());
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_PROFILE_TABS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_OWN_PROFILE_TABS.key));

        for (Preference row : new Preference[]{own, others}) {
            ListView list = pick(row);
            assertEquals(row.getTitle().toString(), 3, list.getCount());
            assertEquals("Reels", list.getItemAtPosition(0).toString());
            assertEquals("Reposts", list.getItemAtPosition(1).toString());
            assertEquals("Tagged", list.getItemAtPosition(2).toString());
            for (int i = 0; i < 3; i++) assertTrue("tab " + i + " ticked", list.isItemChecked(i));
            ShadowAlertDialog.getLatestAlertDialog().dismiss();
        }
    }

    /** Unticking on the other-profiles row saves at once under the tab's key, and ticking it again puts it back. */
    @Test public void theOtherProfilesRowSavesItsOwnKey() throws Exception {
        Settings.HIDDEN_OWN_PROFILE_TABS.save("");
        open(true);
        Preference row = others();
        ListView list = pick(row);

        tap(list, 1);
        assertEquals("profile_reposts", Settings.HIDDEN_PROFILE_TABS.get());
        tap(list, 0);
        assertEquals("profile_clips,profile_reposts", Settings.HIDDEN_PROFILE_TABS.get());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Left off other profiles: "));
        assertEquals("your profile's pick is untouched", "", Settings.HIDDEN_OWN_PROFILE_TABS.get());
        tap(list, 1);
        assertEquals("profile_clips", Settings.HIDDEN_PROFILE_TABS.get());
        tap(list, 0);
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
    }

    /** Your profile's row saves only your profile's key. */
    @Test public void yourProfileRowSavesItsOwnKey() throws Exception {
        open(true);
        Preference row = own();
        ListView list = pick(row);

        tap(list, 0);
        assertEquals("profile_clips", Settings.HIDDEN_OWN_PROFILE_TABS.get());
        assertEquals("other profiles keep every tab", "", Settings.HIDDEN_PROFILE_TABS.get());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Left off your profile: "));
        assertTrue(others().getSummary().toString().startsWith("Every tab shows on other people's"));
        tap(list, 0);
        assertEquals("ticking everything again is a pick of its own", "", Settings.HIDDEN_OWN_PROFILE_TABS.get());
    }

    /** A pick from before the split shows on both rows, and changing the other row keeps yours as it was. */
    @Test public void theOldPickShowsOnBothRows() throws Exception {
        Settings.HIDDEN_PROFILE_TABS.save("profile_tagged_media_photos_of_you");
        open(true);
        assertTrue(own().getSummary().toString(), own().getSummary().toString().startsWith("Left off your profile: "));
        assertTrue(others().getSummary().toString(), others().getSummary().toString().startsWith("Left off other profiles: "));
        ListView mine = pick(own());
        assertTrue(mine.isItemChecked(0));
        assertTrue(mine.isItemChecked(1));
        assertFalse(mine.isItemChecked(2));
        ShadowAlertDialog.getLatestAlertDialog().dismiss();

        ListView theirs = pick(others());
        tap(theirs, 2);
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertEquals("profile_tagged_media_photos_of_you", Settings.HIDDEN_OWN_PROFILE_TABS.get());
    }

    /** A saved choice shows unticked, and Pause answers Instagram's own without touching either choice. */
    @Test public void aSavedChoiceShowsAndPauseKeepsIt() throws Exception {
        Settings.HIDDEN_PROFILE_TABS.save("profile_tagged_media_photos_of_you");
        Settings.HIDDEN_OWN_PROFILE_TABS.save("profile_clips");
        open(true);
        ListView list = pick(others());
        assertTrue(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        ListView mine = pick(own());
        assertFalse(mine.isItemChecked(0));
        assertTrue(mine.isItemChecked(1));
        assertTrue(mine.isItemChecked(2));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertEquals(ProfileTabs.SAME_AS_OTHERS, Settings.HIDDEN_OWN_PROFILE_TABS.get());
        assertEquals("profile_tagged_media_photos_of_you", Settings.HIDDEN_PROFILE_TABS.savedValue());
        assertEquals("profile_clips", Settings.HIDDEN_OWN_PROFILE_TABS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals("profile_tagged_media_photos_of_you", Settings.HIDDEN_PROFILE_TABS.get());
        assertEquals("profile_clips", Settings.HIDDEN_OWN_PROFILE_TABS.get());
    }

    /** A backup takes your own pick or the mark that it follows other profiles, and nothing else. */
    @Test public void backupsTakeOnlyWhatTheDialogWrites() {
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_OWN_PROFILE_TABS, ProfileTabs.SAME_AS_OTHERS));
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_OWN_PROFILE_TABS, ""));
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_OWN_PROFILE_TABS, "profile_clips,profile_reposts"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_OWN_PROFILE_TABS, "profile_reposts,profile_clips"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_OWN_PROFILE_TABS, "profile_media_grid"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_PROFILE_TABS, ProfileTabs.SAME_AS_OTHERS));
    }
}
