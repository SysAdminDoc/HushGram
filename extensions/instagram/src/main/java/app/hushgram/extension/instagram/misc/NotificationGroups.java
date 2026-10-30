/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
 * summary is counted again each time a notification comes or goes. An existing summary stays with
 * its last child, since cancelling it also cancels its children. It comes down only when no child is
 * visible and no accepted post is still waiting to appear in Android's snapshot.
 * Ongoing notifications, like an upload's progress, stay as Instagram built them. Tapping a
 * notification still opens what it did, since only its group changes.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown while
 * regrouping, the notification is posted exactly as Instagram built it, and a cancel is just the
 * cancel. With the switch off, existing groups keep their summary while they have children. Later
 * posts or cancels take down their empty summaries, as turning the switch off also does. If pending
 * ownership fills its bounded ledger, summaries stay for the rest of this process. What the manager
 * itself throws reaches Instagram as before.
 *
 * <p>Muting works with or without grouping: a notification on a channel named in
 * {@link Settings#MUTED_NOTIFICATION_CHANNELS} returns before the manager sees it, so it never shows
 * or counts toward a group, and each muted channel is logged once per process. This reaches the
 * promo and betting pushes Instagram sends on channels its own notification settings don't list.
 * The list is read at each post, so unmuting delivers the next one without a restart. Paused,
 * unreadable or throwing, nothing is muted.
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

    /** The step a failed mute check is reported under. */
    static final String MUTE = "mute";

    /** What's counted for each notification kept out of the shade. */
    static final String MUTED = "muted";

    /** What a notification channel id looks like in the muted list: no commas or spaces. */
    static final String CHANNEL_ID = "[^,\\s]{1,128}";
    private static final Pattern CHANNEL = Pattern.compile(CHANNEL_ID);

    /** HushGram's own channels, which are never offered or muted. */
    public static final String OWN_CHANNELS = "hushgram_";

    /** The muted channels already logged in this process. */
    private static final Set<String> mutedLogged = new HashSet<>();

    /** A group gets its summary once it has this many notifications. */
    static final int SUMMARY_FROM = 2;

    /** Maximum accepted identities not yet returned by Android's active/enqueued snapshot. */
    static final int MAX_PENDING_POSTS = 256;
    static final String POST_GENERATION = "hushgram_notification_generation";
    private static final String PROCESS = UUID.randomUUID().toString();
    private static long nextGeneration;
    private static final Map<Name, Pending> pending = new LinkedHashMap<>();
    /** Overflow or an unmarked owned post leaves summaries in place for the rest of this process. */
    private static boolean uncertain;

    private static final class Name {
        final String tag;
        final int id;

        Name(String tag, int id) {
            this.tag = tag;
            this.id = id;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Name)) return false;
            Name name = (Name) other;
            return id == name.id && Objects.equals(tag, name.tag);
        }

        @Override public int hashCode() { return 31 * Objects.hashCode(tag) + id; }
    }

    /** The only child fields a summary needs, without retaining its text, intents or large extras. */
    private static final class Sample {
        final String channel;
        final Icon icon;

        Sample(Notification notification) {
            channel = notification.getChannelId();
            icon = notification.getSmallIcon();
        }
    }

    private static final class Pending {
        final String generation;
        final String group;
        final Sample sample;
        final long at;

        Pending(String generation, Notification notification) {
            this.generation = generation;
            group = notification.getGroup();
            sample = new Sample(notification);
            at = System.currentTimeMillis();
        }
    }

    private static volatile boolean logged;

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
        post(manager, tag, id, notification, on, byType, NotificationGroups::muted);
    }

    static synchronized void post(NotificationManager manager, String tag, int id, Notification notification,
                     BooleanSupplier on, BooleanSupplier byType, Supplier<Set<String>> muted) {
        try {
            HookStatus.invoked(FamilyNames.NOTIFICATION_GROUPS);
            String channel = notification == null ? null : notification.getChannelId();
            if (channel != null && muted.get().contains(channel)) {
                HookStatus.counted(FamilyNames.NOTIFICATION_GROUPS, MUTED);
                if (mutedLogged.add(channel)) {
                    Logger.printInfo(() -> "Notification groups: muted " + channel + ", kept a notification out of the shade");
                }
                return;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, MUTE, failure);
        }
        String group = null;
        Notification posted = notification;
        String generation = null;
        boolean grouping = false;
        try {
            grouping = on.getAsBoolean();
            if (grouping && notification != null && !isOngoing(notification)) {
                if (isSummary(notification)) {
                    // Every child is in HushGram's groups now, so Instagram's summary would stand alone.
                    return;
                }
                group = groupFor(notification, byType.getAsBoolean());
                generation = PROCESS + ":" + ++nextGeneration;
                posted = regrouped(notification, group, generation);
                HookStatus.counted(FamilyNames.NOTIFICATION_GROUPS, GROUPED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, REGROUP, failure);
            group = null;
            posted = notification;
            generation = null;
        }
        manager.notify(tag, id, posted);
        remember(tag, id, posted, generation);
        // Posted outside a group, the notification has left whichever group it was in.
        tidy(manager, grouping, tag, id, group == null ? null : posted, group);
    }

    /** The cancel as Instagram made it, then the summaries counted again while the switch is on. */
    static synchronized void withdraw(NotificationManager manager, String tag, int id, BooleanSupplier on) {
        manager.cancel(tag, id);
        pending.remove(new Name(tag, id));
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
     * After a post or a cancel: count summaries again while grouping is on. Off, remove only empty
     * summaries and keep trying on later events while an old group still has children. Never throws.
     */
    private static void tidy(NotificationManager manager, boolean grouping, String tag, int id,
                             @Nullable Notification posted, @Nullable String group) {
        try {
            if (grouping) {
                recount(manager, tag, id, posted, group);
            } else {
                // A prior process's accepted posts may appear after an empty snapshot.
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
    private static Notification regrouped(Notification notification, String group, String generation) {
        Context context = Utils.getContext();
        Notification owned = notification.clone();
        owned.extras = notification.extras == null ? new Bundle() : new Bundle(notification.extras);
        owned.extras.putString(POST_GENERATION, generation);
        Notification copy = Notification.Builder.recoverBuilder(context, owned).setGroup(group).build();
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Notification groups: posted a notification in " + group);
        }
        return copy;
    }

    /**
     * Counts each of HushGram's groups from what the shade holds, then posts or updates the summary
     * of a group holding {@link #SUMMARY_FROM} or more. An existing summary stays while a child is
     * visible or pending in its group. Each pending identity replaces its stale snapshot and counts
     * once, even when Android has not exposed any children yet. The manager may not show the latest
     * change yet, so the notification [tag] and [id] name is left
     * out of what it shows: just cancelled, that's where it belongs, and just posted it's counted
     * once, as [posted] in [group]. Only groups with a summary up, and [group], are looked at.
     */
    static synchronized void recount(NotificationManager manager, @Nullable String tag, int id, @Nullable Notification posted,
                        @Nullable String group) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Sample> newest = new HashMap<>();
        Map<String, Long> newestAt = new HashMap<>();
        Map<String, Notification> summaries = new HashMap<>();
        Set<String> occupied = new HashSet<>();
        Name changed = new Name(tag, id);
        for (StatusBarNotification shown : observed(manager)) {
            Notification active = shown.getNotification();
            if (SUMMARY_TAG.equals(shown.getTag())) {
                if (active.getGroup() != null) summaries.put(active.getGroup(), active);
                continue;
            }
            String in = active.getGroup();
            if (in != null && !isSummary(active)) occupied.add(in);
            Name name = new Name(shown.getTag(), shown.getId());
            if (name.equals(changed) || pending.containsKey(name)) continue;
            if (in == null || !in.startsWith(ONE_GROUP) || isSummary(active)) continue;
            counts.merge(in, 1, Integer::sum);
            if (shown.getPostTime() >= newestAt.getOrDefault(in, Long.MIN_VALUE)) {
                newestAt.put(in, shown.getPostTime());
                newest.put(in, new Sample(active));
            }
        }
        for (Map.Entry<Name, Pending> entry : pending.entrySet()) {
            if (entry.getKey().equals(changed)) continue;
            Pending post = entry.getValue();
            counts.merge(post.group, 1, Integer::sum);
            if (post.at >= newestAt.getOrDefault(post.group, Long.MIN_VALUE)) {
                newestAt.put(post.group, post.at);
                newest.put(post.group, post.sample);
            }
        }
        if (posted != null && group != null) {
            counts.merge(group, 1, Integer::sum);
            newest.put(group, new Sample(posted));
        }
        Set<String> groups = new HashSet<>(summaries.keySet());
        if (group != null) groups.add(group);
        for (String each : groups) {
            int count = counts.getOrDefault(each, 0);
            Notification summary = summaries.get(each);
            if (count == 0) {
                // The manager may still show the child just cancelled. Never cancel its summary
                // until an active snapshot also shows the group empty.
                if (summary != null && !occupied.contains(each) && !protectedGroup(each)) {
                    manager.cancel(SUMMARY_TAG, each.hashCode());
                }
            } else if ((summary != null || count >= SUMMARY_FROM) && (summary == null || summary.number != count)) {
                summarize(manager, each, newest.get(each), count);
            }
        }
    }

    /**
     * Posts or updates [group]'s summary: quiet, on the newest notification's channel and icon,
     * saying how many there are.
     */
    private static void summarize(NotificationManager manager, String group, Sample newest, int count) {
        Context context = Utils.getContext();
        Notification.Builder summary = new Notification.Builder(context, newest.channel);
        if (newest.icon != null) {
            summary.setSmallIcon(newest.icon);
        } else {
            summary.setSmallIcon(context.getApplicationInfo().icon);
        }
        summary.setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                .setContentText(L10n.quantity(count, "%d notification", "%d notifications", count))
                .setNumber(count)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        manager.notify(SUMMARY_TAG, group.hashCode(), summary.build());
    }

    /** Removes empty summaries. A summary with children stays so none of those notifications is dismissed. */
    static synchronized int clearSummaries(NotificationManager manager) {
        StatusBarNotification[] active = observed(manager);
        Map<String, Integer> counts = new HashMap<>();
        Set<String> occupied = new HashSet<>();
        for (StatusBarNotification shown : active) {
            if (SUMMARY_TAG.equals(shown.getTag())) continue;
            Notification notification = shown.getNotification();
            String group = notification.getGroup();
            if (group == null || isSummary(notification)) continue;
            occupied.add(group);
            if (pending.containsKey(new Name(shown.getTag(), shown.getId()))) continue;
            counts.merge(group, 1, Integer::sum);
        }
        for (Pending post : pending.values()) counts.merge(post.group, 1, Integer::sum);
        int gone = 0;
        for (StatusBarNotification shown : active) {
            if (!SUMMARY_TAG.equals(shown.getTag())) continue;
            Notification summary = shown.getNotification();
            String group = summary.getGroup();
            int count = counts.getOrDefault(group, 0);
            if (count > 0 || occupied.contains(group) || protectedGroup(group)) {
                if (count > 0 && summary.number != count) {
                    Notification updated = Notification.Builder.recoverBuilder(Utils.getContext(), summary)
                            .setContentText(L10n.quantity(count, "%d notification", "%d notifications", count))
                            .setNumber(count).build();
                    manager.notify(SUMMARY_TAG, shown.getId(), updated);
                }
                continue;
            }
            manager.cancel(SUMMARY_TAG, shown.getId());
            gone++;
        }
        return gone;
    }

    /**
     * Called when the switch is turned off in settings: remove empty summaries off the main thread.
     * Groups that still have children keep their summary, without changing any native notification.
     */
    public static synchronized void switchedOff(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Utils.runOnBackgroundThread(() -> {
            try {
                int gone = clearSummaries(manager);
                if (gone > 0) Logger.printDebug(() -> "Notification groups: took down " + gone + " summaries");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.NOTIFICATION_GROUPS, SUMMARY, failure);
            }
        });
    }

    /** Called only after notify accepted the post, under the same monitor as cleanup. */
    private static void remember(String tag, int id, Notification notification, @Nullable String generation) {
        // Android can coalesce same-id updates. Supersede their ownership only after notify accepts.
        Name name = new Name(tag, id);
        pending.remove(name);
        if (notification == null || isSummary(notification)) return;
        String group = notification.getGroup();
        if (group == null || !group.startsWith(ONE_GROUP)) return;
        if (generation == null || pending.size() >= MAX_PENDING_POSTS) {
            uncertain = true;
            return;
        }
        pending.put(name, new Pending(generation, notification));
    }

    /**
     * Android includes enqueued records, but an accepted enqueue runnable may not have run yet.
     * A tag/id snapshot can still hold an earlier post, so only its exact generation acknowledges it.
     */
    private static StatusBarNotification[] observed(NotificationManager manager) {
        StatusBarNotification[] active = manager.getActiveNotifications();
        for (StatusBarNotification shown : active) {
            Notification notification = shown.getNotification();
            String generation = notification.extras == null ? null : notification.extras.getString(POST_GENERATION);
            Name name = new Name(shown.getTag(), shown.getId());
            Pending post = pending.get(name);
            if (post != null && post.generation.equals(generation)
                    && post.group.equals(notification.getGroup())) pending.remove(name);
        }
        return active;
    }

    private static boolean protectedGroup(@Nullable String group) {
        if (uncertain) return true;
        for (Pending post : pending.values()) if (post.group.equals(group)) return true;
        return false;
    }

    /**
     * The channel ids in a saved muted list, in order. Blank or malformed entries and HushGram's own
     * channels are dropped, so a hand-edited backup can't mute what it shouldn't.
     */
    public static Set<String> mutedChannels(@Nullable String saved) {
        Set<String> ids = new TreeSet<>();
        if (saved == null) return ids;
        for (String part : saved.split(",")) {
            String id = part.trim();
            if (CHANNEL.matcher(id).matches() && !id.startsWith(OWN_CHANNELS)) ids.add(id);
        }
        return ids;
    }

    /** [ids] as the setting saves them: in order, commas between. */
    public static String joinChannels(Set<String> ids) {
        return String.join(",", new TreeSet<>(ids));
    }

    /**
     * Instagram's notification channels on this phone as {id, label}, by label, for the mute list.
     * A label two channels share gets its id after it. Muted ids whose channel is gone come last,
     * labelled by id, so they can still be unmuted.
     */
    public static List<String[]> offeredChannels(Context context, Set<String> muted) {
        List<String[]> offered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            for (NotificationChannel channel : manager.getNotificationChannels()) {
                String id = channel.getId();
                if (id == null || id.startsWith(OWN_CHANNELS) || !CHANNEL.matcher(id).matches() || !seen.add(id)) continue;
                CharSequence name = channel.getName();
                String label = name == null ? "" : name.toString().trim();
                offered.add(new String[]{id, label.isEmpty() ? id : label});
            }
        }
        Map<String, Integer> labels = new HashMap<>();
        for (String[] each : offered) labels.merge(each[1], 1, Integer::sum);
        for (String[] each : offered) {
            if (labels.get(each[1]) > 1) each[1] = each[1] + " (" + each[0] + ")";
        }
        offered.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a[1], b[1]));
        for (String id : new TreeSet<>(muted)) {
            if (seen.add(id)) offered.add(new String[]{id, id});
        }
        return offered;
    }

    static synchronized void resetForTests() {
        pending.clear();
        uncertain = false;
        logged = false;
        mutedLogged.clear();
    }

    static synchronized int pendingForTests() {
        return pending.size();
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.GROUP_NOTIFICATIONS.get();
    }

    private static boolean byType() {
        return Settings.GROUP_NOTIFICATIONS_BY_TYPE.get();
    }

    private static Set<String> muted() {
        return Utils.settingsReady() ? mutedChannels(Settings.MUTED_NOTIFICATION_CHANNELS.get()) : Collections.emptySet();
    }
}
