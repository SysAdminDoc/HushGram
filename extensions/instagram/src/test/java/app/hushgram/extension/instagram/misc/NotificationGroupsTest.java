/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Where Group Instagram's notifications puts each notification Instagram posts, and when it leaves it alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NotificationGroupsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
    }

    @After
    public void restore() {
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private Notification built(String channel, String text) {
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent("open").setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Instagram")
                .setContentText(text)
                .setContentIntent(open)
                .build();
    }

    private Notification shown(String tag, int id) {
        return shadowOf(manager).getNotification(tag, id);
    }

    @Test
    public void offToStartAndOffPostAsBuilt() {
        assertFalse(Settings.GROUP_NOTIFICATIONS.get());
        assertFalse(Settings.GROUP_NOTIFICATIONS_BY_TYPE.get());
        Notification like = built("likes", "liked your post");
        NotificationGroups.notify(manager, "like", 1, like);
        Notification message = built("direct", "sent you a message");
        NotificationGroups.notify(manager, 2, message);

        assertSame(like, shown("like", 1));
        assertSame(message, shown(null, 2));
        assertEquals(2, shadowOf(manager).size());
    }

    @Test
    public void onePutsEveryNotificationInOneGroupWithACount() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 1).getGroup());
        assertNull("one notification gets no summary", shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));

        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        NotificationGroups.post(manager, null, 3, built("direct", "a message"), ON, OFF);

        Notification message = shown(null, 3);
        assertEquals(NotificationGroups.ONE_GROUP, message.getGroup());
        assertEquals("a message", message.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertNotNull("the tap still opens what it did", message.contentIntent);
        Notification summary = shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode());
        assertNotNull(summary);
        assertTrue((summary.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        assertEquals(NotificationGroups.ONE_GROUP, summary.getGroup());
        assertEquals(3, summary.number);
        assertEquals("3 notifications", summary.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.getGroupAlertBehavior());
        assertEquals(4, shadowOf(manager).size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.NOTIFICATION_GROUPS) && report.contains(NotificationGroups.GROUPED + " 3"));
        assertTrue(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).toString(),
                HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    @Test
    public void byTypeGivesEachChannelItsOwnGroup() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, ON);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, ON);
        NotificationGroups.post(manager, null, 3, built("direct", "a message"), ON, ON);

        String likes = NotificationGroups.TYPE_GROUP + "likes";
        String direct = NotificationGroups.TYPE_GROUP + "direct";
        assertEquals(likes, shown("like", 2).getGroup());
        assertEquals(direct, shown(null, 3).getGroup());
        assertEquals(2, shown(NotificationGroups.SUMMARY_TAG, likes.hashCode()).number);
        assertNull(shown(NotificationGroups.SUMMARY_TAG, direct.hashCode()));
    }

    @Test
    public void aGroupDownToOneLosesItsSummary() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        assertNotNull(shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));

        manager.cancel("like", 1);
        manager.cancel("like", 2);
        NotificationGroups.post(manager, "like", 3, built("likes", "third like"), ON, OFF);

        assertNull(shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));
        assertEquals(1, shadowOf(manager).size());
    }

    @Test
    public void instagramsOwnSummaryIsLeftOutOnlyWhileOn() {
        Notification summary = new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup("instagram_direct")
                .setGroupSummary(true)
                .build();

        NotificationGroups.post(manager, "summary", 9, summary, ON, OFF);
        assertNull(shown("summary", 9));

        NotificationGroups.post(manager, "summary", 9, summary, OFF, OFF);
        assertSame(summary, shown("summary", 9));
    }

    @Test
    public void anOngoingNotificationStaysAsBuilt() {
        Notification upload = new Notification.Builder(context, "uploads")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText("Posting")
                .setOngoing(true)
                .build();

        NotificationGroups.post(manager, null, 5, upload, ON, OFF);

        assertSame(upload, shown(null, 5));
        assertNull(upload.getGroup());
    }

    @Test
    public void aFailureStillPostsTheNotificationAsBuilt() {
        Notification like = built("likes", "liked your post");

        NotificationGroups.post(manager, "like", 1, like, THROWS, OFF);

        assertSame(like, shown("like", 1));
        assertFalse(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    @Test
    public void pausedAndUnreadyPostAsBuilt() {
        Settings.GROUP_NOTIFICATIONS.save(true);
        Notification like = built("likes", "liked your post");
        NotificationGroups.notify(manager, "like", 1, like);
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 1).getGroup());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        NotificationGroups.notify(manager, "like", 2, like);
        assertSame(like, shown("like", 2));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> NotificationGroups.notify(manager, "like", 3, like));
        assertSame(like, shown("like", 3));
    }

    @Test
    public void theSecondSwitchPicksAGroupPerType() {
        Settings.GROUP_NOTIFICATIONS.save(true);
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.save(true);
        NotificationGroups.notify(manager, 7, built("comments", "commented"));
        assertEquals(NotificationGroups.TYPE_GROUP + "comments", shown(null, 7).getGroup());
        assertEquals(NotificationGroups.TYPE_GROUP + NotificationGroups.NO_CHANNEL,
                NotificationGroups.groupFor(new Notification(), true));
    }

    private Notification summary(String group) {
        return shown(NotificationGroups.SUMMARY_TAG, group.hashCode());
    }

    /** Each cancel counts the group again: the summary says one fewer, and comes down with one left. */
    @Test
    public void aCancelCountsTheSummaryAgain() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        NotificationGroups.post(manager, null, 3, built("direct", "a message"), ON, OFF);
        assertEquals(3, summary(NotificationGroups.ONE_GROUP).number);

        NotificationGroups.withdraw(manager, null, 3, ON);
        assertNull(shown(null, 3));
        Notification fewer = summary(NotificationGroups.ONE_GROUP);
        assertEquals(2, fewer.number);
        assertEquals("2 notifications", fewer.extras.getCharSequence(Notification.EXTRA_TEXT).toString());

        NotificationGroups.withdraw(manager, "like", 1, ON);
        assertNull("one left gets no summary", summary(NotificationGroups.ONE_GROUP));
        assertNotNull(shown("like", 2));
        assertEquals(1, shadowOf(manager).size());
    }

    /** By type, a cancel only changes its own group's summary. */
    @Test
    public void aCancelByTypeLeavesOtherGroupsAlone() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, ON);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, ON);
        NotificationGroups.post(manager, "dm", 3, built("direct", "a message"), ON, ON);
        NotificationGroups.post(manager, "dm", 4, built("direct", "another message"), ON, ON);
        String likes = NotificationGroups.TYPE_GROUP + "likes";
        String direct = NotificationGroups.TYPE_GROUP + "direct";

        NotificationGroups.withdraw(manager, "like", 1, ON);

        assertNull(summary(likes));
        assertEquals(2, summary(direct).number);
        assertEquals(4, shadowOf(manager).size());
    }

    /** Posted again outside the group, as an ongoing notification is, it leaves its group's count. */
    @Test
    public void aNotificationPostedOngoingLeavesItsGroup() {
        NotificationGroups.post(manager, null, 5, built("uploads", "Posted"), ON, OFF);
        NotificationGroups.post(manager, "like", 1, built("likes", "a like"), ON, OFF);
        assertEquals(2, summary(NotificationGroups.ONE_GROUP).number);

        Notification upload = new Notification.Builder(context, "uploads")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText("Posting again")
                .setOngoing(true)
                .build();
        NotificationGroups.post(manager, null, 5, upload, ON, OFF);

        assertSame(upload, shown(null, 5));
        assertNull(summary(NotificationGroups.ONE_GROUP));
    }

    /** With the switch off a cancel is only the cancel, and a summary left from while it was on comes down. */
    @Test
    public void offACancelTakesLeftoverSummariesDown() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        NotificationGroups.post(manager, "like", 3, built("likes", "third like"), ON, OFF);
        assertNotNull(summary(NotificationGroups.ONE_GROUP));

        NotificationGroups.withdraw(manager, "like", 1, OFF);

        assertNull(shown("like", 1));
        assertNull(summary(NotificationGroups.ONE_GROUP));
        assertNotNull("the notifications under it stay", shown("like", 2));
        assertEquals(2, shadowOf(manager).size());
    }

    /** With the switch off a post is as built, and a summary left from while it was on comes down. */
    @Test
    public void offAPostTakesLeftoverSummariesDown() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        Notification message = built("direct", "a message");

        NotificationGroups.post(manager, null, 3, message, OFF, OFF);

        assertSame(message, shown(null, 3));
        assertNull(summary(NotificationGroups.ONE_GROUP));
        assertEquals(3, shadowOf(manager).size());
    }

    /** The stand-ins for cancel(tag, id) and cancel(id) take the notification down as the manager would. */
    @Test
    public void theCancelStandInsCancel() {
        Settings.GROUP_NOTIFICATIONS.save(true);
        NotificationGroups.notify(manager, "like", 1, built("likes", "a like"));
        NotificationGroups.notify(manager, 2, built("likes", "another like"));
        assertNotNull(summary(NotificationGroups.ONE_GROUP));

        NotificationGroups.cancel(manager, "like", 1);
        assertNull(shown("like", 1));
        assertNull(summary(NotificationGroups.ONE_GROUP));

        NotificationGroups.cancel(manager, 2);
        assertEquals(0, shadowOf(manager).size());
    }

    /** A switch that can't be read still lets the cancel through. */
    @Test
    public void aFailureStillCancels() {
        NotificationGroups.post(manager, "like", 1, built("likes", "a like"), OFF, OFF);

        NotificationGroups.withdraw(manager, "like", 1, THROWS);

        assertNull(shown("like", 1));
        assertFalse(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    /** Turning the switch off in settings takes HushGram's summaries down at once, and only those. */
    @Test
    public void switchingOffTakesTheSummariesDown() throws Exception {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        assertEquals(3, shadowOf(manager).size());

        NotificationGroups.switchedOff(context);
        Utils.awaitBackgroundTasksForTests();

        assertNull(summary(NotificationGroups.ONE_GROUP));
        assertEquals(2, shadowOf(manager).size());
    }
}
