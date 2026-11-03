/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Notify me of unfollowers (#492): when a whole Followers list has been kept and it is missing people
 * the list before it had, a notification names them. It runs only from the list the person scrolled to
 * the end themselves, so it asks Instagram for nothing and checks nothing in the background.
 */
public final class FollowerAlert {
    static final String CHANNEL = "hushgram_unfollows";
    static final int ID = 0x4875;
    /** How many names the notification spells out before saying "and N more". */
    static final int NAMES = 3;

    private FollowerAlert() {}

    /** Whether the alert may be raised now. Never throws. */
    static boolean on() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.NOTIFY_UNFOLLOWS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "unfollow alert switch", failure);
            return false;
        }
    }

    /** Raises the notification for the people in [changes] who left. Does nothing when none did. Never throws. */
    static void raise(Context application, List<FollowerHistory.Change> changes) {
        try {
            if (application == null || changes == null || !on()) return;
            List<String> names = new ArrayList<>();
            int gone = 0;
            for (FollowerHistory.Change change : changes) {
                if (change.kind != FollowerHistory.UNFOLLOWED) continue;
                gone++;
                if (names.size() < NAMES && change.username != null && !change.username.isEmpty()) {
                    names.add("@" + change.username);
                }
            }
            if (gone == 0) return;
            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return;
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            String channelName = L10n.t(application, "Unfollow alerts");
            if (channel == null || !channelName.contentEquals(channel.getName())) {
                NotificationChannel named = new NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_LOW);
                named.setDescription(L10n.t(application, "Who stopped following you, after you open your Followers list"));
                named.setShowBadge(false);
                manager.createNotificationChannel(named);
            }
            String title = gone == 1
                    ? L10n.t(application, "1 person stopped following you")
                    : L10n.f(application, "%1$d people stopped following you", gone);
            String text = join(names);
            if (gone > names.size() && !names.isEmpty()) {
                text = L10n.f(application, "%1$s and %2$d more", text, gone - names.size());
            }
            Notification.Builder builder = new Notification.Builder(application, CHANNEL)
                    .setSmallIcon(application.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            if (!text.isEmpty()) builder.setContentText(text);
            manager.notify(ID, builder.build());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "unfollow alert", failure);
        }
    }

    static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.toString();
    }
}
