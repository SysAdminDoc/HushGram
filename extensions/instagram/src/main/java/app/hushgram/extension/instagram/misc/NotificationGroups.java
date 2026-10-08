/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Group Instagram's notifications" patch.
 *
 * <p>The patch hands every notification Instagram posts to {@link #notify} in place of
 * {@code NotificationManager.notify}. While the switch is on, each one is posted in one group, or
 * in a group per notification channel (likes, comments, messages and so on) with the second switch
 * on, and a quiet summary showing the count is posted once a group has two. Instagram's own group
 * summaries are left out while that's on, since every child has moved to HushGram's groups.
 * Instagram's {@code NotificationManager.cancel} calls come to {@link #cancel} the same way, so a
 * summary is counted again each time a notification comes or goes, and comes down once its group
 * is down to one.
 * Ongoing notifications, like an upload's progress, stay as Instagram built them. Tapping a
 * notification still opens what it did, since only its group changes.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown while
 * regrouping, the notification is posted exactly as Instagram built it, and a cancel is just the
 * cancel. The first post or cancel with the switch off takes down any summary HushGram left from
 * while it was on, and turning the switch off in settings does that at once. What the manager
 * itself throws reaches Instagram as before.
 */
public final class NotificationGroups {
    /** The group every notification joins with one group. */
    static final String ONE_GROUP = "hushgram_notifications";

    /** The start of each channel's group key with a group per type. */
    static final String TYPE_GROUP = "hushgram_notifications_";

    /** The group of a notification with no channel, with a group per type. */
    static final String NO_CHANNEL = "other";

    /** The tag HushGram's summaries are posted under, so they never meet Instagram's own ids. */
    static final String SUMMARY_TAG = "hushgram_notification_group";

    /** The step a failed regroup is reported under. */
    static final String REGROUP = "regroup";

    /** The step a failed summary is reported under. */
    static final String SUMMARY = "summary";

    /** What's counted for each notification moved into a group. */
    static final String GROUPED = "grouped";

    /** A group gets its summary once it has this many notifications. */
    static final int SUMMARY_FROM = 2;

    private static volatile boolean logged;

    /** Whether HushGram's summaries were taken down since the switch was last seen on. */
    private static volatile boolean cleared;

    private NotificationGroups() {
    }

    /** In place of {@code manager.notify(tag, id, notification)}. */
    public static void notify(NotificationManager manager, String tag, int id, Notification notification) {
        post(manager, tag, id, notification, NotificationGroups::switchedOn, NotificationGroups::byType);
    }

    /** In place of {@code manager.notify(id, notification)}, which is the same with no tag. */
    public static void notify(NotificationManager manager, int id, Notification notification) {
        post(manager, null, id, notification, NotificationGroups::switchedOn, NotificationGroups::byType);
    }

    /** In place of {@code manager.cancel(tag, id)}. */
    public static void cancel(NotificationManager manager, String tag, int id) {
        withdraw(manager, tag, id, NotificationGroups::switchedOn);
    }

    /** In place of {@code manager.cancel(id)}, which is the same with no tag. */
    public static void cancel(NotificationManager manager, int id) {
        withdraw(manager, null, id, NotificationGroups::switchedOn);
    }

    static void post(NotificationManager manager, String tag, int id, Notification notification,
                     BooleanSupplier on, BooleanSupplier byType) {
        String group = null;
        Notification posted = notification;
        boolean grouping = false;
        try {
            HookStatus.invoked(FamilyNames.NOTIFICATION_GROUPS);
            grouping = on.getAsBoolean();
            if (grouping && notification != null && !isOngoing(notification)) {
                if (isSummary(notification)) {
                    // Every child is in HushGram's groups now, so Instagram's summary would stand alone.
                    return;
                }
                group = groupFor(notification, byType.getAsBoolean());
                posted = regrouped(notification, group);
                HookStatus.counted(FamilyNames.NOTIFICATION_GROUPS, GROUPED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, REGROUP, failure);
            group = null;
            posted = notification;
        }
        manager.notify(tag, id, posted);
        // Posted outside a group, the notification has left whichever group it was in.
        tidy(manager, grouping, tag, id, group == null ? null : posted, group);
    }

    /** The cancel as Instagram made it, then the summaries counted again while the switch is on. */
    static void withdraw(NotificationManager manager, String tag, int id, BooleanSupplier on) {
        manager.cancel(tag, id);
        boolean grouping = false;
        try {
            HookStatus.invoked(FamilyNames.NOTIFICATION_GROUPS);
            grouping = on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, REGROUP, failure);
        }
        tidy(manager, grouping, tag, id, null, null);
    }

    /**
     * After a post or a cancel: with the switch on, every summary counted again, and with it off,
     * HushGram's summaries taken down once. Never throws.
     */
    private static void tidy(NotificationManager manager, boolean grouping, String tag, int id,
                             @Nullable Notification posted, @Nullable String group) {
        try {
            if (grouping) {
                cleared = false;
                recount(manager, tag, id, posted, group);
            } else if (!cleared) {
                cleared = true;
                clearSummaries(manager);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, SUMMARY, failure);
        }
    }

    /** The group [notification] joins: the one group, or its channel's. */
    static String groupFor(Notification notification, boolean byType) {
        if (!byType) return ONE_GROUP;
        String channel = notification.getChannelId();
        return TYPE_GROUP + (channel == null || channel.isEmpty() ? NO_CHANNEL : channel);
    }

    static boolean isSummary(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    static boolean isOngoing(Notification notification) {
        return (notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
    }

    /** A copy of [notification] in [group], made through the platform's own builder so nothing else changes. */
    private static Notification regrouped(Notification notification, String group) {
        if (group.equals(notification.getGroup())) return notification;
        Context context = Utils.getContext();
        Notification copy = Notification.Builder.recoverBuilder(context, notification).setGroup(group).build();
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Notification groups: posted a notification in " + group);
        }
        return copy;
    }

    /**
     * Counts each of HushGram's groups from what the shade holds, then posts or updates the summary
     * of a group holding {@link #SUMMARY_FROM} or more and takes it down from one holding fewer. The
     * manager may not show the latest change yet, so the notification [tag] and [id] name is left
     * out of what it shows: just cancelled, that's where it belongs, and just posted it's counted
     * once, as [posted] in [group]. Only groups with a summary up, and [group], are looked at.
     */
    static void recount(NotificationManager manager, @Nullable String tag, int id, @Nullable Notification posted,
                        @Nullable String group) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Notification> newest = new HashMap<>();
        Map<String, Long> newestAt = new HashMap<>();
        Map<String, Notification> summaries = new HashMap<>();
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            Notification active = shown.getNotification();
            if (SUMMARY_TAG.equals(shown.getTag())) {
                if (active.getGroup() != null) summaries.put(active.getGroup(), active);
                continue;
            }
            if (shown.getId() == id && Objects.equals(shown.getTag(), tag)) continue;
            String in = active.getGroup();
            if (in == null || !in.startsWith(ONE_GROUP) || isSummary(active)) continue;
            counts.merge(in, 1, Integer::sum);
            if (shown.getPostTime() >= newestAt.getOrDefault(in, Long.MIN_VALUE)) {
                newestAt.put(in, shown.getPostTime());
                newest.put(in, active);
            }
        }
        if (posted != null && group != null) {
            counts.merge(group, 1, Integer::sum);
            newest.put(group, posted);
        }
        Set<String> groups = new HashSet<>(summaries.keySet());
        if (group != null) groups.add(group);
        for (String each : groups) {
            int count = counts.getOrDefault(each, 0);
            Notification summary = summaries.get(each);
            if (count < SUMMARY_FROM) {
                if (summary != null) manager.cancel(SUMMARY_TAG, each.hashCode());
            } else if (summary == null || summary.number != count) {
                summarize(manager, each, newest.get(each), count);
            }
        }
    }

    /**
     * Posts or updates [group]'s summary: quiet, on the newest notification's channel and icon,
     * saying how many there are.
     */
    private static void summarize(NotificationManager manager, String group, Notification newest, int count) {
        Context context = Utils.getContext();
        Notification.Builder summary = new Notification.Builder(context, newest.getChannelId());
        if (newest.getSmallIcon() != null) {
            summary.setSmallIcon(newest.getSmallIcon());
        } else {
            summary.setSmallIcon(context.getApplicationInfo().icon);
        }
        summary.setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                .setContentText(L10n.f("%d notifications", count))
                .setNumber(count)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        manager.notify(SUMMARY_TAG, group.hashCode(), summary.build());
    }

    /** Takes down every summary HushGram posted, and answers how many. Instagram's notifications stay. */
    static int clearSummaries(NotificationManager manager) {
        int gone = 0;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            if (!SUMMARY_TAG.equals(shown.getTag())) continue;
            manager.cancel(SUMMARY_TAG, shown.getId());
            gone++;
        }
        return gone;
    }

    /**
     * Called when the switch is turned off in settings: HushGram's summaries come down at once, off
     * the main thread. The notifications under them stay where they are.
     */
    public static void switchedOff(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        cleared = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                int gone = clearSummaries(manager);
                if (gone > 0) Logger.printDebug(() -> "Notification groups: took down " + gone + " summaries");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, SUMMARY, failure);
            }
        });
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.GROUP_NOTIFICATIONS.get();
    }

    private static boolean byType() {
        return Settings.GROUP_NOTIFICATIONS_BY_TYPE.get();
    }
}
