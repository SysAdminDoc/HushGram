/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.util.Set;

import app.hushgram.extension.instagram.misc.NotificationGroups;
import app.hushgram.extension.shared.L10n;

/**
 * Builds the Notifications category of HushGram settings: Instagram's notifications in the shade, and
 * what its Notifications page lists.
 */
final class NotificationsCategory {
    private NotificationsCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        boolean groups = build.contains(PatchFamily.NOTIFICATION_GROUPS);
        boolean suggestions = build.contains(PatchFamily.PROFILE_SUGGESTIONS);
        if (!groups && !suggestions) return;
        PreferenceCategory notifications = category(screen, L10n.t("Notifications"), SettingsIcons.NOTIFICATIONS,
                groups && suggestions
                        ? L10n.t("Group or mute Instagram's notifications, and take suggestions off the Notifications page.")
                        : groups ? L10n.t("Group or mute Instagram's notifications so they don't pile up.")
                        : L10n.t("Take suggestions and Featured off the Notifications page."));
        if (groups) {
            SwitchPreference grouping = toggle(context, Settings.GROUP_NOTIFICATIONS, L10n.t("Group notifications"),
                    L10n.t("Puts every notification from Instagram in one group that shows how many it holds, so they "
                            + "don't fill your notification shade. Tapping one still opens it."));
            // Turned off, HushGram's summaries come down at once. The notifications under them stay.
            grouping.setOnPreferenceChangeListener((row, value) -> {
                if (Boolean.FALSE.equals(value)) NotificationGroups.switchedOff(row.getContext());
                return true;
            });
            notifications.addPreference(grouping);
            notifications.addPreference(toggle(context, Settings.GROUP_NOTIFICATIONS_BY_TYPE, L10n.t("Group by type"),
                    L10n.t("A group for each kind of notification, such as comments or messages, in place of one group.")));
            // Muting works with grouping off, so this row doesn't hang under the switch.
            notifications.addPreference(page.mutedChannelsRow(context));
        }
        // Hide suggested people's switches for the Notifications page. They sat under Profiles,
        // with the family's other rows, and a search for Profiles still finds them.
        if (suggestions) {
            notifications.addPreference(toggle(context, Settings.HIDE_FOLLOW_BACK_SUGGESTIONS,
                    L10n.t("Hide People you don't follow back"),
                    L10n.t("Takes the People you don't follow back section off the bottom of your notifications. "
                            + "Notifications about new followers stay.")));
            notifications.addPreference(toggle(context, Settings.HIDE_NOTIFICATION_SUGGESTIONS,
                    L10n.t("Hide suggestions in notifications"),
                    L10n.t("Takes rows that only suggest something off your notifications, like a thread from an "
                            + "account you don't follow or someone you might know. Likes, comments, mentions and "
                            + "new followers stay.")));
            notifications.addPreference(toggle(context, Settings.HIDE_NOTIFICATION_FEATURED,
                    L10n.t("Hide Featured in notifications"),
                    L10n.t("Takes the Featured section off the top of your notifications, the posts and threads "
                            + "Instagram picks to show you there. Everything under it stays.")));
            notifications.addPreference(toggle(context, Settings.HIDE_FEATURED_DOT,
                    L10n.t("Keep Featured off the heart's dot"),
                    L10n.t("Stops the red dot on the heart from showing up for Featured alone. Likes, comments and "
                            + "new followers still light it.")));
        }
    }

    /** The rows that moved here from Profiles, which a search for that word still finds. */
    static boolean movedFromProfiles(String key) {
        return Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.key.equals(key)
                || Settings.HIDE_NOTIFICATION_SUGGESTIONS.key.equals(key)
                || Settings.HIDE_NOTIFICATION_FEATURED.key.equals(key)
                || Settings.HIDE_FEATURED_DOT.key.equals(key);
    }
}
