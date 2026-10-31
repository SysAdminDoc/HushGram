/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide suggested people's switches: the profile rows under Profiles, the Notifications page rows under Notifications, each starting off, kept in a backup and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FollowListSuggestionsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.resetToDefault();
        Settings.HIDE_PROFILE_COMPLETION.resetToDefault();
        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.resetToDefault();
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.resetToDefault();
        Settings.HIDE_PROFILE_COMPLETION.resetToDefault();
        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.resetToDefault();
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.resetToDefault();
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
    @Test public void theSwitchIsUnderProfilesAfterSuggestedPeopleAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.key);
        assertNotNull(row);
        assertEquals("Hide Suggested for you on follow lists", row.getTitle().toString());
        String summary = String.valueOf(row.getSummary());
        assertTrue("names the Suggested tab and the mutual list (#302): " + summary,
                summary.contains("the Suggested tab off other people's follow lists") && summary.contains("mutual lists"));
        assertEquals("Profiles", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.get());
        PreferenceGroup profiles = row.getParent();
        String[] keys = new String[profiles.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = profiles.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.HIDE_PROFILE_SUGGESTIONS.key, keys[Arrays.asList(keys).indexOf(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.key) - 1]);
        assertTrue(PatchFamily.PROFILE_SUGGESTIONS.switches.contains(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.key));
    }
    /** #159: Hide Complete your profile comes right after the follow list switch, starts off and is kept in a backup. */
    @Test public void hideCompleteYourProfileComesNextAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_COMPLETION.key);
        assertNotNull(row);
        assertEquals("Hide Complete your profile", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("off your own profile"));
        PreferenceGroup profiles = row.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        String[] keys = new String[profiles.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = profiles.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.key,
                keys[Arrays.asList(keys).indexOf(Settings.HIDE_PROFILE_COMPLETION.key) - 1]);
        assertFalse(Settings.HIDE_PROFILE_COMPLETION.get());
        assertTrue(PatchFamily.PROFILE_SUGGESTIONS.switches.contains(Settings.HIDE_PROFILE_COMPLETION));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_PROFILE_COMPLETION.key));
    }
    /** #161: Hide People you don't follow back is the first row under Notifications when grouping isn't in the build, starts off, is kept in a backup and answers off while paused. */
    @Test public void hidePeopleYouDontFollowBackComesNextAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key);
        assertNotNull(row);
        assertEquals("Hide People you don't follow back", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("new followers stay"));
        PreferenceGroup notifications = row.getParent();
        assertEquals("Notifications", notifications.getTitle().toString());
        assertEquals(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key, notifications.getPreference(0).getKey());
        assertNull("the Profiles rows don't follow it here", notifications.findPreference(Settings.HIDE_PROFILE_COMPLETION.key));
        assertFalse(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.get());
        assertTrue(PatchFamily.PROFILE_SUGGESTIONS.switches.contains(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key));
        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.get());
        assertTrue(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.savedValue());
    }
    /** With grouping in the build too, the Notifications page's switches come after Group by type and the mute list. */
    @Test public void withGroupingTheNotificationsPageSwitchesFollowGroupByType() throws Exception {
        open(EnumSet.of(PatchFamily.NOTIFICATION_GROUPS, PatchFamily.PROFILE_SUGGESTIONS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key);
        PreferenceGroup notifications = row.getParent();
        assertEquals("Notifications", notifications.getTitle().toString());
        String[] keys = new String[notifications.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = notifications.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.GROUP_NOTIFICATIONS.key, Settings.GROUP_NOTIFICATIONS_BY_TYPE.key,
                Settings.MUTED_NOTIFICATION_CHANNELS.key, Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key,
                Settings.HIDE_NOTIFICATION_SUGGESTIONS.key, Settings.HIDE_NOTIFICATION_FEATURED.key,
                Settings.HIDE_FEATURED_DOT.key), Arrays.asList(keys));
        assertEquals("Group or mute Instagram's notifications, and take suggestions off the Notifications page.",
                ((HushgramPreferenceFragment.Heading) notifications).intro);
    }
    /** A search for Profiles, where they used to be, still finds the moved rows and nothing else under Notifications. */
    @Test public void aSearchForProfilesStillFindsTheMovedRows() throws Exception {
        open(EnumSet.of(PatchFamily.NOTIFICATION_GROUPS, PatchFamily.PROFILE_SUGGESTIONS));
        page.searchSettings("Profiles");
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTIFICATION_SUGGESTIONS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTIFICATION_FEATURED.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEATURED_DOT.key));
        assertNull("grouping never sat under Profiles", page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key));
    }
    /** #181: Hide suggestions in notifications comes right after Hide People you don't follow back under Notifications, starts off, is kept in a backup and answers off while paused. */
    @Test public void hideSuggestionsInNotificationsComesNextAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_NOTIFICATION_SUGGESTIONS.key);
        assertNotNull(row);
        assertEquals("Hide suggestions in notifications", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("new followers stay"));
        PreferenceGroup notifications = row.getParent();
        assertEquals("Notifications", notifications.getTitle().toString());
        String[] keys = new String[notifications.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = notifications.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key,
                keys[Arrays.asList(keys).indexOf(Settings.HIDE_NOTIFICATION_SUGGESTIONS.key) - 1]);
        assertFalse(Settings.HIDE_NOTIFICATION_SUGGESTIONS.get());
        assertTrue(PatchFamily.PROFILE_SUGGESTIONS.switches.contains(Settings.HIDE_NOTIFICATION_SUGGESTIONS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_NOTIFICATION_SUGGESTIONS.key));
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_NOTIFICATION_SUGGESTIONS.get());
        assertTrue(Settings.HIDE_NOTIFICATION_SUGGESTIONS.savedValue());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS));
        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.save(true);
        assertTrue(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.get());
        assertTrue(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_COMPLETION.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTIFICATION_SUGGESTIONS.key));
    }
}
