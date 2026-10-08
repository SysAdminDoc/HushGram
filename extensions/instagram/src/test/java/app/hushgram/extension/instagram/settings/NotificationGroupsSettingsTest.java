/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.preference.Preference;
import android.preference.PreferenceCategory;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Group Instagram's notifications' two switches: under Notifications, off to start, by type under the first, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NotificationGroupsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.NOTIFICATION_GROUPS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoNotificationsSection() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key));
    }
    @Test public void bothSwitchesSitUnderNotificationsAndStartOff() throws Exception {
        open(true);
        SwitchPreference group = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key);
        SwitchPreference byType = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key);
        assertNotNull(group);
        assertNotNull(byType);
        assertEquals("Group notifications", group.getTitle().toString());
        assertTrue(group.getSummary().toString(), group.getSummary().toString().contains("one group"));
        assertEquals("Group by type", byType.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), group);
        assertNotNull(section);
        assertEquals("Notifications", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), byType));
        assertEquals(2, section.getPreferenceCount());
        assertFalse(group.isChecked());
        assertFalse(byType.isChecked());
        // Group by type picks how, not whether, so the report and Pause go by the first switch alone.
        assertEquals(Arrays.asList(Settings.GROUP_NOTIFICATIONS), PatchFamily.NOTIFICATION_GROUPS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GROUP_NOTIFICATIONS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key));
    }
    /** Turning Group notifications off takes HushGram's summary down at once. The notifications stay. */
    @Test public void turningTheSwitchOffTakesTheSummaryDown() throws Exception {
        open(true);
        NotificationManager manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
        Settings.GROUP_NOTIFICATIONS.save(true);
        for (int id = 1; id <= 2; id++) {
            app.hushgram.extension.instagram.misc.NotificationGroups.notify(manager, id,
                    new Notification.Builder(RuntimeEnvironment.getApplication(), "likes")
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("a like").build());
        }
        assertEquals("two and their summary", 3, org.robolectric.Shadows.shadowOf(manager).size());
        SwitchPreference group = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key);

        assertTrue(group.getOnPreferenceChangeListener().onPreferenceChange(group, false));
        Utils.awaitBackgroundTasksForTests();

        assertEquals(2, org.robolectric.Shadows.shadowOf(manager).size());
    }
    @Test public void byTypeWaitsForTheFirstSwitch() throws Exception {
        open(true);
        Preference byType = page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key);
        assertFalse(byType.isEnabled());
        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(byType.isEnabled());
        assertTrue(Settings.GROUP_NOTIFICATIONS.get());
    }
    @Test public void theSwitchPersistsAndHonorsPause() throws Exception {
        open(true);
        Settings.GROUP_NOTIFICATIONS.save(true);
        assertTrue(Settings.GROUP_NOTIFICATIONS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.GROUP_NOTIFICATIONS.get());
        assertTrue(Settings.GROUP_NOTIFICATIONS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.GROUP_NOTIFICATIONS.get());
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
