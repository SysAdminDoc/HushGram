/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Group Instagram's notifications' two switches: under Notifications, off to start, by type under the first, off while paused. Then the mute list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NotificationGroupsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        Settings.MUTED_NOTIFICATION_CHANNELS.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = phone;
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
        Settings.MUTED_NOTIFICATION_CHANNELS.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = SettingsLock.PHONE;
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
        assertNull(page.getPreferenceScreen().findPreference(Settings.MUTED_NOTIFICATION_CHANNELS.key));
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
        // The two switches and the mute row after them.
        assertEquals(3, section.getPreferenceCount());
        assertFalse(group.isChecked());
        assertFalse(byType.isChecked());
        // Group by type picks how, not whether, so the report and Pause go by the first switch alone.
        assertEquals(Arrays.asList(Settings.GROUP_NOTIFICATIONS), PatchFamily.NOTIFICATION_GROUPS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GROUP_NOTIFICATIONS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key));
    }
    private Preference muteRow() {
        return page.getPreferenceScreen().findPreference(Settings.MUTED_NOTIFICATION_CHANNELS.key);
    }
    private AlertDialog openMuteList() {
        Preference row = muteRow();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        return ShadowAlertDialog.getLatestAlertDialog();
    }
    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }
    /** Made through the page's own activity, whose manager the list reads. */
    private void channel(String id, String name) {
        controller.get().getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT));
    }
    /** The mute row comes last under Notifications, mutes nothing to start, works with grouping off and is kept in a backup. */
    @Test public void theMuteRowFollowsGroupByTypeAndStartsEmpty() throws Exception {
        open(true);
        Preference row = muteRow();
        assertNotNull(row);
        assertEquals("Mute notification types", row.getTitle().toString());
        assertEquals("Stops the notification types you tick, even ones Instagram's own notification settings can't turn off.",
                row.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertSame(row, section.getPreference(2));
        assertFalse(Settings.GROUP_NOTIFICATIONS.get());
        assertTrue("muting doesn't wait for grouping", row.isEnabled());
        assertEquals("", Settings.MUTED_NOTIFICATION_CHANNELS.get());
        assertEquals(Arrays.asList(Settings.MUTED_NOTIFICATION_CHANNELS), PatchFamily.NOTIFICATION_GROUPS.choices());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MUTED_NOTIFICATION_CHANNELS.key));
        assertEquals("ig_likes,ig_product_announcements", ConfigurationBackup.accepted(Settings.MUTED_NOTIFICATION_CHANNELS,
                "string", "ig_likes,ig_product_announcements"));
        assertNull("out of order", ConfigurationBackup.accepted(Settings.MUTED_NOTIFICATION_CHANNELS, "string",
                "ig_product_announcements,ig_likes"));
        assertNull("HushGram's own channel", ConfigurationBackup.accepted(Settings.MUTED_NOTIFICATION_CHANNELS, "string",
                "hushgram_saves"));
    }
    /** The list offers Instagram's channels by name, saves a tick at once, and Unmute all clears it without closing. */
    @Test public void tickingAChannelMutesItAndUnmuteAllClears() throws Exception {
        open(true);
        channel("ig_product_announcements", "Product announcements");
        channel("ig_likes", "Likes");
        channel("hushgram_saves", "Saved by HushGram");
        AlertDialog dialog = openMuteList();
        ListView list = dialog.getListView();
        assertEquals(2, list.getCount());
        assertEquals("Likes", list.getItemAtPosition(0).toString());
        assertEquals("Product announcements", list.getItemAtPosition(1).toString());
        assertFalse(list.isItemChecked(0));
        assertFalse(list.isItemChecked(1));

        tap(list, 1);
        assertEquals("ig_product_announcements", Settings.MUTED_NOTIFICATION_CHANNELS.get());
        tap(list, 0);
        assertEquals("ig_likes,ig_product_announcements", Settings.MUTED_NOTIFICATION_CHANNELS.get());
        assertEquals("2 notification types are muted.", muteRow().getSummary().toString());
        tap(list, 0);
        assertEquals("1 notification type is muted.", muteRow().getSummary().toString());

        assertEquals("Unmute all", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("", Settings.MUTED_NOTIFICATION_CHANNELS.get());
        assertFalse(list.isItemChecked(1));
        assertTrue(dialog.isShowing());
        assertTrue(muteRow().getSummary().toString(), muteRow().getSummary().toString().startsWith("Stops the notification types"));
    }
    /** While settings are locked a tick or Unmute all changes nothing, on screen too once the list lays out again. */
    @Test public void aLockedListKeepsItsTicksAndTheSavedList() throws Exception {
        open(true);
        channel("ig_product_announcements", "Product announcements");
        channel("ig_likes", "Likes");
        AlertDialog dialog = openMuteList();
        ListView list = dialog.getListView();
        tap(list, 1);
        assertEquals("ig_product_announcements", Settings.MUTED_NOTIFICATION_CHANNELS.get());

        assertTrue(SettingsLock.lock(1));
        tap(list, 0);
        tap(list, 1);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        list.requestLayout();
        ShadowLooper.idleMainLooper();
        assertEquals("ig_product_announcements", Settings.MUTED_NOTIFICATION_CHANNELS.get());
        assertFalse("a refused tick came back", list.isItemChecked(0));
        assertTrue("a refused untick or Unmute all cleared the check", list.isItemChecked(1));
        assertEquals("1 notification type is muted.", muteRow().getSummary().toString());
    }
    /** Before Instagram has made its channels, the list says to open Instagram once. */
    @Test public void noChannelsYetSaysToOpenInstagram() throws Exception {
        open(true);
        AlertDialog dialog = openMuteList();
        assertTrue(dialog.isShowing());
        assertEquals("Instagram hasn't set up its notification types on this phone yet. Open Instagram once, then come back here.",
                org.robolectric.Shadows.shadowOf(dialog).getMessage().toString());
    }
    /** Hide suggested people alone opens Notifications for its Notifications page switches, without the grouping rows. */
    @Test public void suggestionsAloneOpenNotificationsWithoutGrouping() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_SUGGESTIONS);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        assertNull(page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key));
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(),
                page.getPreferenceScreen().findPreference(Settings.HIDE_NOTIFICATION_FEATURED.key));
        assertNotNull(section);
        assertEquals("Notifications", section.getTitle().toString());
        assertEquals(4, section.getPreferenceCount());
        assertEquals("Take suggestions and Featured off the Notifications page.",
                ((HushgramPreferenceFragment.Heading) section).intro);
    }
    /** Turning Group notifications off preserves the children and the summary that must stay with them. */
    @Test public void turningTheSwitchOffKeepsTheChildrenAndTheirSummary() throws Exception {
        open(true);
        NotificationManager manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
        Settings.GROUP_NOTIFICATIONS.save(true);
        for (int id = 1; id <= 2; id++) {
            app.hushgram.extension.instagram.misc.NotificationGroups.notify(manager, id,
                    new Notification.Builder(RuntimeEnvironment.getApplication(), "likes")
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("a like").build());
        }
        assertEquals("two and their summary", 3, org.robolectric.Shadows.shadowOf(manager).size());
        Notification first = org.robolectric.Shadows.shadowOf(manager).getNotification(1);
        Notification second = org.robolectric.Shadows.shadowOf(manager).getNotification(2);
        SwitchPreference group = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GROUP_NOTIFICATIONS.key);

        assertTrue(group.getOnPreferenceChangeListener().onPreferenceChange(group, false));
        Utils.awaitBackgroundTasksForTests();

        assertEquals(3, org.robolectric.Shadows.shadowOf(manager).size());
        assertNotNull(first);
        assertNotNull(second);
        assertSame(first, org.robolectric.Shadows.shadowOf(manager).getNotification(1));
        assertSame(second, org.robolectric.Shadows.shadowOf(manager).getNotification(2));
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
