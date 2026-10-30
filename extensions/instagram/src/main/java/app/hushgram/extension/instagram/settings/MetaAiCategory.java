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

/** Builds the Meta AI category of HushGram settings. */
final class MetaAiCategory {
    private MetaAiCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.META_AI)) {
            PreferenceCategory metaAi = category(screen, L10n.t("Meta AI"), SettingsIcons.AI,
                    L10n.t("Control where Meta AI appears."));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_SEARCH, L10n.t("Hide Meta AI in search and Home's bar"),
                    L10n.t("Gives Search and your messages a plain search bar and removes Meta AI's buttons, "
                            + "follow-up bar and inbox row. Restart Instagram to see the change.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_POSTS, L10n.t("Hide Meta AI posts"),
                    L10n.t("Hides Meta AI's videos, chats and pictures of you that Instagram puts in your home feed.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_ABOUT_THIS_REEL, L10n.t("Hide About this reel"),
                    L10n.t("Removes the summary, Sources and Ask Meta AI box from a reel's more menu. In your feed "
                            + "the audio row goes too. Other options stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_ASK_META_AI, L10n.t("Hide Ask Meta AI in About this reel"),
                    L10n.t("About this reel keeps its summary and Sources, and only the Ask Meta AI box under them "
                            + "goes.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_SHARE_TARGET, L10n.t("Hide Meta AI in the share sheet"),
                    L10n.t("Removes Meta AI from the row at the bottom of the share sheet. Some accounts see it as "
                            + "Muse.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_PROFILE_BANNER, L10n.t("Hide the Muse card"),
                    L10n.t("Removes the Meet Muse card, with its Try Muse button, from Home and your profile. "
                            + "Your other cards stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_PROFILE_BUTTON, L10n.t("Hide the Muse button on your profile"),
                    L10n.t("Removes the Muse button from the top bar of your profile. Your username and the menu "
                            + "stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_BLEND, L10n.t("Hide Blend on reels"),
                    L10n.t("Removes the Blend invite button from reels. Everything else on a reel stays.")));
        }
    }
}
