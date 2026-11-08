/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearViewingHistory;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.viewingHistoryRow;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Viewing history category of HushGram settings (#228). It was part of Ads and privacy before. */
final class ViewingHistoryCategory {
    private ViewingHistoryCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (!build.contains(PatchFamily.VIDEO_DOWNLOAD)) return;
        PreferenceCategory section = category(screen, L10n.t("Viewing history"), SettingsIcons.UPDATES,
                L10n.t("Find a post, reel, story or profile you saw before."));
        section.addPreference(toggle(context, Settings.VIEWING_HISTORY, L10n.t("Keep a viewing history"),
                L10n.t("Remembers the posts, reels, stories and profiles that come up on screen, and who posted "
                        + "them, newest first, on this phone only. The list shows their pictures, loaded from "
                        + "Instagram's image servers and kept on the phone. Turn it off and it stops at once.")));
        Preference history = viewingHistoryRow(context);
        section.addPreference(history);
        HushgramPreferenceFragment.Row clear = new HushgramPreferenceFragment.Row(context);
        clear.setKey(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY);
        clear.setPersistent(false);
        clear.actsAtOnce = true;
        clear.setTitle(L10n.t("Clear viewing history"));
        clear.setSummary(L10n.t("Forgets every post, reel, story and profile on the list, for all your accounts. "
                + "The switch stays as it is."));
        clear.setOnPreferenceClickListener(p -> {
            clearViewingHistory(p, history);
            return true;
        });
        section.addPreference(mark(clear, SettingsIcons.DELETE));
    }
}
