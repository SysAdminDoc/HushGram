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

import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Sharing category of HushGram settings. */
final class SharingCategory {
    private SharingCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.SHARE_SHEET) || build.contains(PatchFamily.REPOST_BUTTON)
                || build.contains(PatchFamily.HIDE_SHARE_BUTTON)) {
            PreferenceCategory sharing = category(screen, L10n.t("Sharing"), SettingsIcons.NETWORK,
                    L10n.t("Take buttons you don't use off posts and the share sheet."));
            if (build.contains(PatchFamily.SHARE_SHEET)) {
                sharing.addPreference(toggle(context, Settings.HIDE_SHARE_SHEET_GROUP, L10n.t("Hide group buttons"),
                        L10n.t("Leaves New group out of the share sheet, and the button that sends to the people you "
                                + "picked as a group. Send separately stays, and you can still start a group from your messages.")));
                sharing.addPreference(page.shareSheetButtonsRow(context));
            }
            if (build.contains(PatchFamily.REPOST_BUTTON)) {
                sharing.addPreference(toggle(context, Settings.HIDE_REPOST_BUTTON, L10n.t("Hide the Repost button"),
                        L10n.t("Takes Repost and its count off posts and reels, so nothing gets reposted to your "
                                + "followers by mistake. Share still sends a post or reel to someone.")));
            }
            if (build.contains(PatchFamily.HIDE_SHARE_BUTTON)) {
                sharing.addPreference(toggle(context, Settings.HIDE_SHARE_BUTTON, L10n.t("Hide the Share button"),
                        L10n.t("Takes the Share button and its count off the posts in your feed and off reels.")));
            }
        }
    }
}
