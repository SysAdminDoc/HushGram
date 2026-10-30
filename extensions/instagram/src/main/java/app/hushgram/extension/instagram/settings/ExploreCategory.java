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

/** Builds the Explore category of HushGram settings. */
final class ExploreCategory {
    private ExploreCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.EXPLORE_GRID) || build.contains(PatchFamily.RECENT_SEARCHES)
                || build.contains(PatchFamily.ALL_RECENT_SEARCHES)) {
            PreferenceCategory explore = category(screen, L10n.t("Explore"), SettingsIcons.EXPLORE,
                    L10n.t("Search, suggestions and the Explore grid."));
            if (build.contains(PatchFamily.EXPLORE_GRID)) {
                explore.addPreference(toggle(context, Settings.HIDE_EXPLORE_GRID, L10n.t("Hide the Explore grid"),
                        L10n.t("Hides the grid of posts and reels under the Search tab's bar. Search, recent "
                                + "searches and results stay.")));
                explore.addPreference(toggle(context, Settings.HIDE_SEARCH_SUGGESTIONS,
                        L10n.t("Hide suggested accounts in search"),
                        L10n.t("Leaves the Suggested for you accounts out of what shows when you tap the search "
                                + "bar. Recent searches stay.")));
            }
            if (build.contains(PatchFamily.RECENT_SEARCHES)) {
                explore.addPreference(toggle(context, Settings.DONT_SAVE_RECENT_SEARCHES,
                        L10n.t("Don't save recent searches"),
                        L10n.t("What you open from search stays out of Recent, in the app and on Instagram's side. "
                                + "Searches already there stay until you clear them.")));
            }
            if (build.contains(PatchFamily.ALL_RECENT_SEARCHES)) {
                explore.addPreference(toggle(context, Settings.SHOW_ALL_RECENT_SEARCHES,
                        L10n.t("Show all recent searches"),
                        L10n.t("Lists every recent search under the search bar, not just the first few. See all and "
                                + "the x on each row work as before.")));
            }
        }
    }
}
