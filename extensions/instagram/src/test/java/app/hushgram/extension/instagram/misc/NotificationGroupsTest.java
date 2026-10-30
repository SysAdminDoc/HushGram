/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
@Config(sdk = {28, 37}, shadows = NotificationGroupsTest.PlatformNotificationManager.class)
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
        NotificationGroups.resetForTests();
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        manager = context.getSystemService(NotificationManager.class);
    }

    @After
    public void restore() {
        Settings.GROUP_NOTIFICATIONS.resetToDefault();
        Settings.GROUP_NOTIFICATIONS_BY_TYPE.resetToDefault();
        Settings.MUTED_NOTIFICATION_CHANNELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        NotificationGroups.resetForTests();
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
    public void aGroupDownToOneKeepsItsSummary() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        assertNotNull(shown(NotificationGroups.SUMMARY_TAG, NotificationGroups.ONE_GROUP.hashCode()));

        manager.cancel("like", 1);
        manager.cancel("like", 2);
        NotificationGroups.post(manager, "like", 3, built("likes", "third like"), ON, OFF);

        assertEquals(1, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull(shown("like", 3));
        assertEquals(2, shadowOf(manager).size());
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

    private static final String PROMO = "ig_product_announcements";

    private long mutedLines(String channel) {
        return ShadowLog.getLogs().stream()
                .filter(item -> item.msg.contains("Notification groups: muted " + channel + ",")).count();
    }

    @Test
    public void aMutedChannelNeverReachesTheManagerAndIsLoggedOnce() {
        Settings.MUTED_NOTIFICATION_CHANNELS.save(PROMO + ",ig_shopping_drops");
        // The fake manager throws at the next post it gets, so any post that reached it would fail here.
        platform().failNextPost = true;

        NotificationGroups.notify(manager, "promo", 1, built(PROMO, "a promo"));
        NotificationGroups.notify(manager, 2, built(PROMO, "another promo"));

        assertTrue("the manager was never asked to post", platform().failNextPost);
        platform().failNextPost = false;
        assertEquals(0, shadowOf(manager).size());
        assertEquals(1, mutedLines(PROMO));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(NotificationGroups.MUTED + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).toString(),
                HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
    }

    @Test
    public void otherChannelsStillGroupAndCountWhileOneIsMuted() {
        Settings.MUTED_NOTIFICATION_CHANNELS.save(PROMO);
        Notification message = built("ig_direct", "a message");
        NotificationGroups.post(manager, null, 4, message, OFF, OFF);
        assertSame("with grouping off, other channels post as built", message, shown(null, 4));
        manager.cancel(null, 4);

        NotificationGroups.post(manager, "like", 1, built("ig_likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "promo", 2, built(PROMO, "a promo"), ON, OFF);
        NotificationGroups.post(manager, "like", 3, built("ig_likes", "second like"), ON, OFF);

        assertNull(shown("promo", 2));
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 1).getGroup());
        assertEquals(NotificationGroups.ONE_GROUP, shown("like", 3).getGroup());
        assertEquals("the muted one isn't counted", 2, summary(NotificationGroups.ONE_GROUP).number);
        assertEquals(3, shadowOf(manager).size());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(NotificationGroups.GROUPED + " 2") && report.contains(NotificationGroups.MUTED + " 1"));
    }

    @Test
    public void unmutingDeliversTheNextOneWithoutARestart() {
        Settings.MUTED_NOTIFICATION_CHANNELS.save(PROMO);
        NotificationGroups.notify(manager, "promo", 1, built(PROMO, "a promo"));
        assertNull(shown("promo", 1));

        Settings.MUTED_NOTIFICATION_CHANNELS.save("");
        Notification promo = built(PROMO, "the next promo");
        NotificationGroups.notify(manager, "promo", 1, promo);

        assertSame(promo, shown("promo", 1));
    }

    @Test
    public void pausedUnreadyOrThrowingMutesNothing() {
        Settings.MUTED_NOTIFICATION_CHANNELS.save(PROMO);
        Notification promo = built(PROMO, "a promo");

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        NotificationGroups.notify(manager, "promo", 1, promo);
        assertSame(promo, shown("promo", 1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> NotificationGroups.notify(manager, "promo", 2, promo));
        assertSame(promo, shown("promo", 2));

        NotificationGroups.post(manager, "promo", 3, promo, OFF, OFF, () -> {
            throw new IllegalStateException("settings went away");
        });
        assertSame(promo, shown("promo", 3));
        assertFalse(HookStatus.missing(FamilyNames.NOTIFICATION_GROUPS).isEmpty());
        assertEquals(0, mutedLines(PROMO));
    }

    @Test
    public void theMutedListKeepsInstagramsChannelIdsInOrder() {
        Set<String> parsed = NotificationGroups.mutedChannels(" " + PROMO + ",ig_likes,,ig likes,hushgram_saves ");

        assertEquals(new TreeSet<>(Arrays.asList("ig_likes", PROMO)), parsed);
        assertEquals("ig_likes," + PROMO, NotificationGroups.joinChannels(parsed));
        assertTrue(NotificationGroups.mutedChannels(null).isEmpty());
        assertEquals("", NotificationGroups.joinChannels(NotificationGroups.mutedChannels("")));
    }

    @Test
    public void theOfferedListIsInstagramsChannelsByNameWithGoneMutedOnesLast() {
        for (String[] channel : new String[][]{{"ig_likes", "Likes"}, {PROMO, "Product announcements"},
                {"ig_heads_up_comments", "Comments"}, {"ig_comments_old", "Comments"}, {"ig_blank", " "},
                {"hushgram_saves", "Saves"}}) {
            manager.createNotificationChannel(new NotificationChannel(channel[0], channel[1], NotificationManager.IMPORTANCE_DEFAULT));
        }

        List<String[]> offered = NotificationGroups.offeredChannels(context, new TreeSet<>(Arrays.asList("ig_likes", "ig_gone")));

        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String[] each : offered) {
            ids.add(each[0]);
            labels.add(each[1]);
        }
        assertEquals(Arrays.asList("Comments (ig_comments_old)", "Comments (ig_heads_up_comments)", "ig_blank", "Likes",
                "Product announcements", "ig_gone"), labels);
        assertEquals(Arrays.asList("ig_comments_old", "ig_heads_up_comments", "ig_blank", "ig_likes", PROMO, "ig_gone"), ids);
    }

    private Notification summary(String group) {
        return shown(NotificationGroups.SUMMARY_TAG, group.hashCode());
    }

    @Test
    @Config(qualifiers = "en-rUS")
    public void recountUsesEnglishSingularAndPluralWithoutChangingChildren() {
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("likes", "second"), ON, OFF);
        NotificationGroups.post(manager, "third", 3, built("likes", "third"), ON, OFF);
        Notification first = shown("first", 1);
        Notification second = shown("second", 2);

        NotificationGroups.withdraw(manager, "third", 3, ON);

        assertEquals("2 notifications", summary(NotificationGroups.ONE_GROUP).extras
                .getCharSequence(Notification.EXTRA_TEXT).toString());
        assertSame(first, shown("first", 1));
        assertSame(second, shown("second", 2));
        NotificationGroups.withdraw(manager, "first", 1, ON);

        assertEquals("1 notification", summary(NotificationGroups.ONE_GROUP).extras
                .getCharSequence(Notification.EXTRA_TEXT).toString());
        assertSame(second, shown("second", 2));
        assertEquals(2, shadowOf(manager).size());
    }

    @Test
    @Config(qualifiers = "en-rUS")
    public void offCleanupUsesEnglishSingularAndPluralWithoutChangingChildren() {
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("likes", "second"), ON, OFF);
        NotificationGroups.post(manager, "third", 3, built("likes", "third"), ON, OFF);
        Notification first = shown("first", 1);
        Notification second = shown("second", 2);

        NotificationGroups.withdraw(manager, "third", 3, OFF);

        assertEquals("2 notifications", summary(NotificationGroups.ONE_GROUP).extras
                .getCharSequence(Notification.EXTRA_TEXT).toString());
        assertSame(first, shown("first", 1));
        assertSame(second, shown("second", 2));
        NotificationGroups.withdraw(manager, "first", 1, OFF);

        assertEquals("1 notification", summary(NotificationGroups.ONE_GROUP).extras
                .getCharSequence(Notification.EXTRA_TEXT).toString());
        assertSame(second, shown("second", 2));
        assertEquals(2, shadowOf(manager).size());
    }

    /** Each cancel updates the count. The last child keeps its summary so cancelling it cannot dismiss that child. */
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
        assertEquals(1, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull(shown("like", 2));
        assertEquals(2, shadowOf(manager).size());
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

        assertEquals(1, summary(likes).number);
        assertEquals(2, summary(direct).number);
        assertEquals(5, shadowOf(manager).size());
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
        assertEquals(1, summary(NotificationGroups.ONE_GROUP).number);
    }

    /** With the switch off a cancel still preserves the other native children and their summary. */
    @Test
    public void offACancelKeepsTheRemainingChildrenAndSummary() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        NotificationGroups.post(manager, "like", 3, built("likes", "third like"), ON, OFF);
        assertNotNull(summary(NotificationGroups.ONE_GROUP));

        NotificationGroups.withdraw(manager, "like", 1, OFF);

        assertNull(shown("like", 1));
        assertEquals(2, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull("the notifications under it stay", shown("like", 2));
        assertNotNull(shown("like", 3));
        assertEquals(3, shadowOf(manager).size());
    }

    /** With the switch off a post is as built, while earlier children keep their summary. */
    @Test
    public void offAPostKeepsEarlierChildrenAndSummary() {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        Notification message = built("direct", "a message");

        NotificationGroups.post(manager, null, 3, message, OFF, OFF);

        assertSame(message, shown(null, 3));
        assertEquals(2, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull(shown("like", 1));
        assertNotNull(shown("like", 2));
        assertEquals(4, shadowOf(manager).size());
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
        assertEquals(1, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull(shown(null, 2));

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

    /** Turning the switch off keeps native children and the summary that must stay with them. */
    @Test
    public void switchingOffKeepsTheChildrenAndTheirSummary() throws Exception {
        NotificationGroups.post(manager, "like", 1, built("likes", "first like"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second like"), ON, OFF);
        assertEquals(3, shadowOf(manager).size());

        NotificationGroups.switchedOff(context);
        Utils.awaitBackgroundTasksForTests();

        assertEquals(2, summary(NotificationGroups.ONE_GROUP).number);
        assertNotNull(shown("like", 1));
        assertNotNull(shown("like", 2));
        assertEquals(3, shadowOf(manager).size());
    }

    /**
     * NotificationManagerService cancels ordinary children when their summary is cancelled.
     * The default shadow only removes the named notification. These cases keep the existing
     * preservation assertions and model that verified platform boundary.
     */
    @Implements(NotificationManager.class)
    public static class PlatformNotificationManager extends ShadowNotificationManager {
        private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();
        boolean delayed;
        boolean blockNextPost;
        boolean failNextPost;
        final CountDownLatch postAccepted = new CountDownLatch(1);
        final CountDownLatch allowPostReturn = new CountDownLatch(1);

        @Implementation
        protected void notify(int id, Notification notification) {
            notify(null, id, notification);
        }

        @Implementation
        protected void notify(String tag, int id, Notification notification) {
            if (failNextPost) {
                failNextPost = false;
                throw new IllegalStateException("notify rejected");
            }
            accept(() -> super.notify(tag, id, notification));
            if (blockNextPost) {
                blockNextPost = false;
                postAccepted.countDown();
                try {
                    if (!allowPostReturn.await(3, TimeUnit.SECONDS)) throw new AssertionError("post return not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }

        @Implementation
        protected void cancel(int id) {
            cancel(null, id);
        }

        @Implementation
        protected void cancel(String tag, int id) {
            accept(() -> cancelVisible(tag, id));
        }

        private void accept(Runnable operation) {
            if (delayed) waiting.add(operation);
            else operation.run();
        }

        void flush() {
            Runnable next;
            while ((next = waiting.poll()) != null) next.run();
        }

        void visible(String tag, int id, Notification notification) {
            super.notify(tag, id, notification);
        }

        void dismissVisible(String tag, int id) {
            super.cancel(tag, id);
        }

        private void cancelVisible(String tag, int id) {
            Notification previous = getNotification(tag, id);
            super.cancel(tag, id);
            if (summary(previous)) cancelChildren(previous.getGroup());
        }
        private void cancelChildren(String group) {
            if (group == null) return;
            for (StatusBarNotification child : getActiveNotifications()) {
                Notification notification = child.getNotification();
                if (Objects.equals(group, notification.getGroup()) && !summary(notification)
                        && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
                    super.cancel(child.getTag(), child.getId());
                }
            }
        }

        private static boolean summary(Notification notification) {
            return notification != null && (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        }
    }

    @Test
    public void platformSummaryCancellationRemovesOrdinaryChildrenInThatGroup() {
        manager.notify("child", 20, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("native-group").build());
        manager.notify("other", 21, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("other-group").build());
        manager.notify("summary", 22, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("native-group")
                .setGroupSummary(true).build());

        manager.cancel("summary", 22);

        assertNull(shown("child", 20));
        assertNotNull(shown("other", 21));
    }

    @Test
    public void platformChildCancellationLeavesItsSiblingAndSummary() {
        manager.notify("child", 20, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("native-group").build());
        manager.notify("sibling", 21, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("native-group").build());
        manager.notify("summary", 22, new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("native-group")
                .setGroupSummary(true).build());

        manager.cancel("child", 20);

        assertNull(shown("child", 20));
        assertNotNull(shown("sibling", 21));
        assertNotNull(shown("summary", 22));
    }

    private PlatformNotificationManager platform() {
        return (PlatformNotificationManager) shadowOf(manager);
    }

    private void visibleSummary(String group) {
        platform().visible(NotificationGroups.SUMMARY_TAG, group.hashCode(),
                new Notification.Builder(context, "likes").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setGroup(group).setGroupSummary(true).setNumber(2).build());
    }

    /** AOSP includes enqueued records, but acceptance can precede its EnqueueNotificationRunnable. */
    @Test
    public void switchingOffPreservesAnAcceptedChildBeforeItsEnqueueRunnable() throws Exception {
        NotificationGroups.post(manager, "like", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "second"), ON, OFF);
        platform().delayed = true;
        NotificationGroups.withdraw(manager, "like", 1, ON);
        NotificationGroups.withdraw(manager, "like", 2, ON);
        platform().flush();
        assertNotNull(summary(NotificationGroups.ONE_GROUP));
        assertNull(shown("like", 1));
        assertNull(shown("like", 2));

        NotificationGroups.post(manager, "new", 3, built("likes", "arrived"), ON, OFF);
        assertNull("accepted, before Android exposes the enqueued record", shown("new", 3));
        NotificationGroups.switchedOff(context);
        Utils.awaitBackgroundTasksForTests();
        platform().flush();

        assertNotNull("cleaning up the old summary must not cancel the new child", shown("new", 3));
        assertNotNull(summary(NotificationGroups.ONE_GROUP));
    }

    @Test
    public void anOlderSameIdSnapshotCannotAcknowledgeANewQueuedGeneration() {
        NotificationGroups.post(manager, "like", 1, built("likes", "old"), ON, OFF);
        NotificationGroups.post(manager, "like", 2, built("likes", "other"), ON, OFF);
        String oldGeneration = shown("like", 1).extras.getString(NotificationGroups.POST_GENERATION);
        platform().delayed = true;
        NotificationGroups.post(manager, "like", 1, built("likes", "replacement"), ON, OFF);
        platform().dismissVisible("like", 1);
        platform().dismissVisible("like", 2);

        NotificationGroups.clearSummaries(manager);
        platform().flush();

        Notification replacement = shown("like", 1);
        assertNotNull("the old tag/id snapshot does not prove the replacement was visible", replacement);
        assertEquals("replacement", replacement.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse(oldGeneration.equals(replacement.extras.getString(NotificationGroups.POST_GENERATION)));
    }

    @Test
    public void anEarlierProcessGenerationCannotAcknowledgeANewQueuedPost() {
        Notification old = new Notification.Builder(context, "likes").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup(NotificationGroups.ONE_GROUP).build();
        old.extras.putString(NotificationGroups.POST_GENERATION, UUID.randomUUID() + ":1");
        platform().visible("like", 1, old);
        visibleSummary(NotificationGroups.ONE_GROUP);
        platform().delayed = true;

        NotificationGroups.post(manager, "like", 1, built("likes", "new process"), ON, OFF);
        platform().dismissVisible("like", 1);
        NotificationGroups.clearSummaries(manager);
        platform().flush();

        assertNotNull(shown("like", 1));
        String generation = shown("like", 1).extras.getString(NotificationGroups.POST_GENERATION);
        assertFalse(old.extras.getString(NotificationGroups.POST_GENERATION).equals(generation));
        UUID.fromString(generation.substring(0, generation.lastIndexOf(':')));
    }

    @Test
    public void aFullPendingLedgerPreservesAnUnrecordedGroupsChild() {
        platform().delayed = true;
        for (int i = 0; i < NotificationGroups.MAX_PENDING_POSTS; i++) {
            NotificationGroups.post(manager, "pending", i, built("likes", "queued child"), ON, OFF);
        }
        assertEquals(NotificationGroups.MAX_PENDING_POSTS, NotificationGroups.pendingForTests());
        String group = NotificationGroups.TYPE_GROUP + "overflow";
        visibleSummary(group);
        NotificationGroups.post(manager, "overflow", 9, built("overflow", "outside capacity"), ON, ON);

        NotificationGroups.clearSummaries(manager);
        platform().flush();

        assertNotNull("overflow must retain the summary instead of evicting pending ownership", shown("overflow", 9));
        assertTrue(NotificationGroups.pendingForTests() <= NotificationGroups.MAX_PENDING_POSTS);
    }

    @Test
    public void ownershipIsAddedOnlyToACopyEvenWhenTheNativeGroupAlreadyMatches() {
        Notification nativeNotification = new Notification.Builder(context, "likes")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup(NotificationGroups.ONE_GROUP)
                .setContentText("native text").setContentIntent(built("likes", "tap").contentIntent).build();
        android.os.Bundle originalExtras = nativeNotification.extras;
        originalExtras.putString("native-only", "untouched");

        NotificationGroups.post(manager, "like", 1, nativeNotification, ON, OFF);

        Notification posted = shown("like", 1);
        assertNotSame(nativeNotification, posted);
        assertSame(originalExtras, nativeNotification.extras);
        assertNotSame(originalExtras, posted.extras);
        assertFalse(originalExtras.containsKey(NotificationGroups.POST_GENERATION));
        assertEquals("untouched", originalExtras.getString("native-only"));
        assertEquals("untouched", posted.extras.getString("native-only"));
        assertSame(nativeNotification.contentIntent, posted.contentIntent);
        assertNotNull(posted.extras.getString(NotificationGroups.POST_GENERATION));
    }

    @Test
    public void aCoalescedReplacementLeavesNoObsoletePendingGeneration() {
        NotificationGroups.post(manager, "like", 1, built("likes", "old"), ON, OFF);
        NotificationGroups.post(manager, "other", 2, built("likes", "other"), ON, OFF);
        platform().delayed = true;
        for (int i = 0; i < 3; i++) {
            NotificationGroups.post(manager, "like", 1, built("likes", "replacement"), ON, OFF);
        }
        platform().flush();
        NotificationGroups.clearSummaries(manager);
        assertEquals("the final observed update supersedes all earlier accepted versions", 0,
                NotificationGroups.pendingForTests());
        platform().dismissVisible("like", 1);
        platform().dismissVisible("other", 2);

        NotificationGroups.clearSummaries(manager);
        platform().flush();

        assertNull("coalesced generations must not keep an empty summary forever", summary(NotificationGroups.ONE_GROUP));
    }

    @Test
    public void invisibleFirstChildrenReceiveASummaryWithoutAnotherNativeEvent() {
        platform().delayed = true;
        Notification first = built("likes", "first");
        Notification second = built("direct", "second");
        NotificationGroups.post(manager, "first", 1, first, ON, OFF);
        NotificationGroups.post(manager, "second", 2, second, ON, OFF);
        assertNull("neither enqueue runnable has run", shown("first", 1));
        assertNull(shown("second", 2));

        platform().flush();

        Notification group = summary(NotificationGroups.ONE_GROUP);
        assertNotNull("the second accepted identity creates the summary", group);
        assertEquals(2, group.number);
        assertEquals("direct", group.getChannelId());
        assertEquals("first", shown("first", 1).extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals("second", shown("second", 2).extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertSame(first.contentIntent, shown("first", 1).contentIntent);
        assertSame(second.contentIntent, shown("second", 2).contentIntent);
    }

    @Test
    public void offCleanupKeepsRunningWhenAnInvisibleGroupArrivesLater() throws Exception {
        platform().delayed = true;
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("likes", "second"), ON, OFF);
        assertEquals("accepted children and their summary are not visible yet", 0, shadowOf(manager).size());

        NotificationGroups.switchedOff(context);
        Utils.awaitBackgroundTasksForTests();
        platform().flush();
        platform().delayed = false;
        assertNotNull(shown("first", 1));
        assertNotNull(shown("second", 2));
        assertNotNull(summary(NotificationGroups.ONE_GROUP));

        NotificationGroups.withdraw(manager, "first", 1, OFF);
        assertNotNull("the other native child stays", shown("second", 2));
        NotificationGroups.withdraw(manager, "second", 2, OFF);

        assertNull("later Off events must clean up a group missing from the first snapshot",
                summary(NotificationGroups.ONE_GROUP));
        assertEquals(0, shadowOf(manager).size());
    }

    @Test
    public void offCleanupCatchesASummaryThatAppearsAfterItsChildrenWereWithdrawn() throws Exception {
        platform().delayed = true;
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("likes", "second"), ON, OFF);
        NotificationGroups.switchedOff(context);
        Utils.awaitBackgroundTasksForTests();
        NotificationGroups.withdraw(manager, "first", 1, OFF);
        NotificationGroups.withdraw(manager, "second", 2, OFF);
        platform().flush();
        platform().delayed = false;
        assertNull(shown("first", 1));
        assertNull(shown("second", 2));
        assertNotNull("the accepted summary can arrive after both withdrawals", summary(NotificationGroups.ONE_GROUP));

        Notification nativePost = built("direct", "new native post");
        NotificationGroups.post(manager, "native", 3, nativePost, OFF, OFF);

        assertNull(summary(NotificationGroups.ONE_GROUP));
        assertSame("later Off events still post the native notification unchanged", nativePost, shown("native", 3));
        assertEquals(1, shadowOf(manager).size());
    }

    @Test
    public void offCleanupFindsAGroupAcceptedByAnEarlierProcessAfterAnEmptySnapshot() {
        platform().delayed = true;
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("likes", "second"), ON, OFF);
        assertEquals(0, shadowOf(manager).size());
        // The service and its accepted queue survive. Only the extension's process state resets.
        NotificationGroups.resetForTests();
        Notification nativePost = built("direct", "new process native post");
        NotificationGroups.post(manager, "native", 3, nativePost, OFF, OFF);
        assertEquals("the new process's first Off snapshot is empty", 0, shadowOf(manager).size());

        platform().flush();
        platform().delayed = false;
        assertNotNull(shown("first", 1));
        assertNotNull(shown("second", 2));
        assertNotNull(summary(NotificationGroups.ONE_GROUP));
        NotificationGroups.withdraw(manager, "first", 1, OFF);
        assertNotNull("the earlier process's other child survives", shown("second", 2));
        NotificationGroups.withdraw(manager, "second", 2, OFF);

        assertNull("an empty snapshot cannot retire cleanup for the new process", summary(NotificationGroups.ONE_GROUP));
        assertSame(nativePost, shown("native", 3));
        assertEquals(1, shadowOf(manager).size());
    }

    @Test
    public void neverOwnedOffEventsLeaveOtherNativeNotificationsUntouched() {
        Notification first = Notification.Builder.recoverBuilder(context, built("direct", "first"))
                .setGroup("instagram_direct").build();
        Notification second = Notification.Builder.recoverBuilder(context, built("direct", "second"))
                .setGroup("instagram_direct").build();
        Notification nativeSummary = new Notification.Builder(context, "direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("instagram_direct")
                .setGroupSummary(true).build();
        manager.notify("instagram-summary", 9, nativeSummary);

        NotificationGroups.post(manager, "first", 1, first, OFF, OFF);
        NotificationGroups.post(manager, "second", 2, second, OFF, OFF);
        assertSame(first, shown("first", 1));
        assertSame(second, shown("second", 2));
        assertSame(nativeSummary, shown("instagram-summary", 9));
        NotificationGroups.withdraw(manager, "first", 1, OFF);

        assertNull(shown("first", 1));
        assertSame(second, shown("second", 2));
        assertSame(nativeSummary, shown("instagram-summary", 9));
        assertEquals(2, shadowOf(manager).size());
    }

    @Test
    public void invisibleReplacementsCountOnceAndUseTheNewestSummarySample() {
        platform().delayed = true;
        NotificationGroups.post(manager, "first", 1, built("likes", "first"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("direct", "second"), ON, OFF);
        NotificationGroups.post(manager, "second", 2, built("comments", "latest"), ON, OFF);

        platform().flush();

        Notification group = summary(NotificationGroups.ONE_GROUP);
        assertNotNull(group);
        assertEquals("the same native tag/id is still one child", 2, group.number);
        assertEquals("comments", group.getChannelId());
        assertEquals("latest", shown("second", 2).extras.getCharSequence(Notification.EXTRA_TEXT).toString());
    }

    @Test
    public void aRejectedReplacementDoesNotDiscardPreviouslyAcceptedOwnership() {
        visibleSummary(NotificationGroups.ONE_GROUP);
        platform().delayed = true;
        NotificationGroups.post(manager, "like", 1, built("likes", "accepted"), ON, OFF);
        platform().failNextPost = true;
        try {
            NotificationGroups.post(manager, "like", 1, built("likes", "rejected"), ON, OFF);
            throw new AssertionError("the native manager rejection must reach Instagram");
        } catch (IllegalStateException expected) {
            assertEquals("notify rejected", expected.getMessage());
        }

        NotificationGroups.clearSummaries(manager);
        platform().flush();

        assertNotNull("the older accepted post still protects its summary", shown("like", 1));
        assertEquals("accepted", shown("like", 1).extras.getCharSequence(Notification.EXTRA_TEXT).toString());
    }

    @Test
    public void cleanupWaitsForAnAcceptedPostToRecordItsPendingOwnership() throws Exception {
        visibleSummary(NotificationGroups.ONE_GROUP);
        platform().delayed = true;
        platform().blockNextPost = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch cleanupDone = new CountDownLatch(1);
        Thread posting = new Thread(() -> {
            try { NotificationGroups.post(manager, "new", 1, built("likes", "arrived"), ON, OFF); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
        Thread cleanup = new Thread(() -> {
            cleanupStarted.countDown();
            try { NotificationGroups.clearSummaries(manager); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
            finally { cleanupDone.countDown(); }
        });
        posting.start();
        boolean waited;
        try {
            assertTrue(platform().postAccepted.await(2, TimeUnit.SECONDS));
            cleanup.start();
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));
            waited = !cleanupDone.await(100, TimeUnit.MILLISECONDS);
        } finally {
            platform().allowPostReturn.countDown();
            posting.join(2000);
            cleanup.join(2000);
        }
        assertFalse(posting.isAlive());
        assertFalse(cleanup.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("cleanup shares the post's acceptance and ownership monitor", waited);
        platform().flush();
        assertNotNull("a concurrently accepted child survives summary cleanup", shown("new", 1));
    }
}
