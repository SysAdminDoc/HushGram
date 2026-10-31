/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearViewingHistory;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.viewingHistoryRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.ghostTabRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.placeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.proxyServerRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.sharingDomainRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.withCoverage;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Ads and privacy category of HushGram settings. */
final class PrivacyCategory {
    private PrivacyCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        List<Preference> privacy = new ArrayList<>();
        page.ghostSwitches = GhostMode.switches(build);
        if (GhostMode.offered(page.ghostSwitches)) {
            page.ghostMode = page.ghostModeRow(context);
            privacy.add(page.ghostMode);
            page.ghostPicks = page.ghostPicksRow(context);
            privacy.add(page.ghostPicks);
            page.ghostTab = ghostTabRow(context);
            privacy.add(page.ghostTab);
        }
        if (build.contains(PatchFamily.HIDE_ADS)) {
            privacy.add(toggle(context, Settings.HIDE_ADS, L10n.t("Hide ads"),
                    L10n.t("Hides sponsored posts, reels, stories and the ad in comments. No empty gap is left where an "
                            + "ad would have been.")));
        }
        if (build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
            privacy.add(toggle(context, Settings.SANITIZE_SHARING_LINKS, L10n.t("Sanitize sharing links"),
                    withCoverage(L10n.t("Removes tracking tags from links you copy or share, and opens bio links without "
                            + "Instagram's click tracker. The link still opens the same post, reel or profile."),
                            SettingsStatus.sanitizeSharingLinksCoverage())));
            privacy.add(sharingDomainRow(context));
        }
        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            privacy.add(toggle(context, Settings.OPEN_LINKS_EXTERNALLY, L10n.t("Open links in external browser"),
                    L10n.t("Web links you tap open in your usual browser, without Instagram's click tracker. "
                            + "Instagram pages and ads still open in the app.")));
        }
        if (build.contains(PatchFamily.SPOOF_LOCATION)) {
            privacy.add(toggle(context, Settings.SPOOF_LOCATION, L10n.t("Spoof location"),
                    L10n.t("Tells Instagram your phone is at the place set below, for the location sticker, nearby "
                            + "places and maps. Photos keep their own places.")));
            privacy.add(placeRow(context));
        }
        if (build.contains(PatchFamily.APP_PROXY)) {
            privacy.add(toggle(context, Settings.APP_PROXY, L10n.t("App-only proxy"),
                    L10n.t("Sends Instagram through the HTTP proxy set below. Other apps and your phone's own "
                            + "settings aren't changed.")));
            privacy.add(proxyServerRow(context));
        }
        if (build.contains(PatchFamily.SETUP_POPUPS)) {
            privacy.add(toggle(context, Settings.SKIP_SETUP_POPUPS, L10n.t("Skip the contacts and location popups"),
                    L10n.t("The screens asking you to sync your contacts and turn on location services stop "
                            + "opening each time Instagram starts. Nothing else is skipped.")));
        }
        if (build.contains(PatchFamily.AD_DATA_REMINDER)) {
            privacy.add(toggle(context, Settings.SKIP_AD_DATA_REMINDER, L10n.t("Skip the ad data reminder"),
                    L10n.t("Instagram's reminder to review whether it can process your data for ads stops opening "
                            + "by itself. Your ad choice doesn't change, and nothing is answered for you.")));
        }
        if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
            privacy.add(toggle(context, Settings.VIEWING_HISTORY, L10n.t("Keep a viewing history"),
                    L10n.t("Remembers the posts, reels, stories and profiles that come up on screen, newest first, "
                            + "on this phone only. Nothing is sent anywhere, and nothing is read about other accounts. "
                            + "Turn it off and it stops at once. Clear it below.")));
            Preference history = viewingHistoryRow(context);
            privacy.add(history);
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
            privacy.add(mark(clear, SettingsIcons.DELETE));
        }
        if (!privacy.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Ads and privacy"), SettingsIcons.PRIVACY,
                    L10n.t("Hide ads and share less with Instagram."));
            for (Preference row : privacy) section.addPreference(row);
        }
    }
}
