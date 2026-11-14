/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.CLEAR_PROFILE_HISTORY;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.CLEAR_UNFOLLOWERS;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearProfileHistory;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearUnfollowers;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.followerHistoryRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.followLabelPlaceRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.profileHistoryRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.savedProfilesRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.savedProfilesTabRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.unfollowersRow;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;

/** Builds the Profiles category of HushGram settings. */
final class ProfilesCategory {
    private ProfilesCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.FRIENDSHIP_STATUS) || build.contains(PatchFamily.PROFILE_SUGGESTIONS)
                || build.contains(PatchFamily.PROFILE_HIGHLIGHTS) || build.contains(PatchFamily.THREADS_BUTTON)
                || build.contains(PatchFamily.PROFILE_NOTES) || build.contains(PatchFamily.PROFILE_BANNERS)
                || build.contains(PatchFamily.PROFILE_PICTURE)
                || build.contains(PatchFamily.PROFILE_TABS)
                || build.contains(PatchFamily.SAVED_ON_PROFILE) || build.contains(PatchFamily.PROFILE_POSTS_LIST)
                || build.contains(PatchFamily.PROFILE_GRID_JUMP)
                || build.contains(PatchFamily.ASK_BEFORE_FOLLOW)) {
            PreferenceCategory profiles = category(screen, L10n.t("Profiles"), SettingsIcons.PERSON,
                    L10n.t("See who follows you back, and clear extras off profiles."));
            if (build.contains(PatchFamily.PROFILE_PICTURE)) {
                profiles.addPreference(toggle(context, Settings.ABOUT_ACCOUNT_BUTTON, L10n.t("About this account button"),
                        L10n.t("Puts an (i) next to the three dots on a profile that opens Instagram's About this account "
                                + "for that account. Instagram took its own away.")));
                profiles.addPreference(toggle(context, Settings.PROFILE_HISTORY, L10n.t("Keep profile history"),
                        L10n.t("Remembers the username, bio and profile picture of each profile you open, on this phone "
                                + "only, and tells you when one changed since your last visit. Pictures are copied from "
                                + "Instagram's image servers. Turn it off and it stops at once.")));
                Preference profileChanges = profileHistoryRow(context);
                profiles.addPreference(profileChanges);
                Row clearProfileHistory = new Row(context);
                clearProfileHistory.setKey(CLEAR_PROFILE_HISTORY);
                clearProfileHistory.setPersistent(false);
                clearProfileHistory.actsAtOnce = true;
                clearProfileHistory.setTitle(L10n.t("Clear profile history"));
                clearProfileHistory.setSummary(L10n.t("Forgets every username, bio and picture on the list, for all "
                        + "your accounts. The switch stays as it is."));
                clearProfileHistory.setOnPreferenceClickListener(p -> {
                    clearProfileHistory(p, profileChanges);
                    return true;
                });
                profiles.addPreference(mark(clearProfileHistory, SettingsIcons.DELETE));
                profiles.addPreference(toggle(context, Settings.SAVED_PROFILES, L10n.t("Saved profiles"),
                        L10n.t("Adds Save profile to the menu on someone's profile, even one you don't follow. "
                                + "The list stays on this phone, apart from Instagram's Favorites and Following.")));
                profiles.addPreference(savedProfilesRow(context));
                profiles.addPreference(savedProfilesTabRow(context));
            }
            if (build.contains(PatchFamily.FRIENDSHIP_STATUS)) {
                profiles.addPreference(toggle(context, Settings.SHOW_FRIENDSHIP_STATUS, L10n.t("Show if a profile follows you"),
                        L10n.t("Adds Follows you or Doesn't follow you beside the name on someone's profile, after "
                                + "their pronouns if they've set any. Nothing shows until Instagram has checked.")));
                profiles.addPreference(toggle(context, Settings.FRIENDSHIP_STATUS_CHIP, L10n.t("Show it as a chip"),
                        L10n.t("Puts the answer in a chip under the profile's posts, followers and following counts "
                                + "instead, and says Following each other when you follow them too.")));
                profiles.addPreference(followLabelPlaceRow(context));
                if (PatchFamily.followingListMarkInBuild()) {
                    profiles.addPreference(toggle(context, Settings.MARK_FOLLOWING_LIST, L10n.t("Mark who doesn't follow you back"),
                            L10n.t("On your own Following list, adds Doesn't follow you before the name of each account that "
                                    + "doesn't follow you back. Nothing shows until Instagram has checked.")));
                    profiles.addPreference(toggle(context, Settings.FOLLOW_MARK_ON_PICTURE, L10n.t("Show it on the profile picture"),
                            L10n.t("Puts a green check on the picture of each account that follows you back and a red cross "
                                    + "on each one that doesn't, instead of the words.")));
                    profiles.addPreference(toggle(context, Settings.FOLLOWING_NOT_BACK_FIRST, L10n.t("Put accounts that don't follow you back first"),
                            L10n.t("On your own Following list, moves the accounts that don't follow you back to the top and "
                                    + "keeps Instagram's order within each group. Accounts Instagram hasn't checked yet stay below.")));
                }
                if (PatchFamily.unfollowersInBuild()) {
                    profiles.addPreference(toggle(context, Settings.TRACK_UNFOLLOWERS, L10n.t("Recent unfollowers"),
                            L10n.t("Each time you scroll your own Followers list to the end, compares it with the last time "
                                    + "and lists who's gone. Nothing extra is asked of Instagram, and the list stays on your phone.")));
                    profiles.addPreference(toggle(context, Settings.NOTIFY_UNFOLLOWS, L10n.t("Notify me of unfollowers"),
                            L10n.t("After you scroll your own Followers list to the end, posts a notification naming who has left since last time. Nothing extra is asked of Instagram.")));
                    Preference unfollowersList = unfollowersRow(context);
                    profiles.addPreference(unfollowersList);
                    Preference followerHistory = followerHistoryRow(context);
                    profiles.addPreference(followerHistory);
                    Row clearUnfollowers = new Row(context);
                    clearUnfollowers.setKey(CLEAR_UNFOLLOWERS);
                    clearUnfollowers.setPersistent(false);
                    clearUnfollowers.actsAtOnce = true;
                    clearUnfollowers.setTitle(L10n.t("Clear unfollower list"));
                    clearUnfollowers.setSummary(L10n.t("Forgets the saved Followers lists and everyone listed above, "
                            + "for all your accounts."));
                    clearUnfollowers.setOnPreferenceClickListener(p -> {
                        clearUnfollowers(p, unfollowersList, followerHistory);
                        return true;
                    });
                    profiles.addPreference(mark(clearUnfollowers, SettingsIcons.DELETE));
                }
            }
            if (build.contains(PatchFamily.ASK_BEFORE_FOLLOW)) {
                profiles.addPreference(toggle(context, Settings.ASK_BEFORE_FOLLOW, L10n.t("Ask before a follow"),
                        L10n.t("Asks before a Follow or Follow back button follows someone or sends them a request, so a "
                                + "stray tap on a profile or in a Followers or Following list doesn't do it for you.")));
            }
            if (build.contains(PatchFamily.PROFILE_SUGGESTIONS)) {
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_SUGGESTIONS, L10n.t("Hide suggested people"),
                        L10n.t("Takes Suggested for you and the Discover people button off profiles, yours included. "
                                + "Bios, counts, posts and follower lists stay.")));
                profiles.addPreference(toggle(context, Settings.HIDE_FOLLOW_LIST_SUGGESTIONS,
                        L10n.t("Hide Suggested for you on follow lists"),
                        L10n.t("Leaves the Suggested for you section off the followers, following and mutual lists and the screen "
                                + "where you approve follow requests, and the Suggested tab off other people's follow lists. "
                                + "Every account in the lists and every real request stays.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_COMPLETION,
                        L10n.t("Hide Complete your profile"),
                        L10n.t("Takes the Complete your profile cards, like Add bio and Find people to follow, off "
                                + "your own profile. Your bio, counts and posts stay.")));
                // The family's switches for the Notifications page are under Notifications.
            }
            if (build.contains(PatchFamily.PROFILE_HIGHLIGHTS)) {
                profiles.addPreference(toggle(context, Settings.HIDE_HIGHLIGHTS, L10n.t("Hide highlights"),
                        L10n.t("Takes the row of story highlights off profiles, yours included. Bios, counts and "
                                + "posts stay, and so does Add to highlight on your stories.")));
            }
            if (build.contains(PatchFamily.PROFILE_TABS)) {
                profiles.addPreference(page.ownProfileTabsRow(context));
                profiles.addPreference(page.otherProfileTabsRow(context));
            }
            if (build.contains(PatchFamily.SAVED_ON_PROFILE)) {
                profiles.addPreference(toggle(context, Settings.SAVED_ON_PROFILE, L10n.t("Saved tab on your profile"),
                        L10n.t("Adds a bookmark to the tabs on your own profile that opens Saved. Restart Instagram "
                                + "after changing it.")));
                profiles.addPreference(toggle(context, Settings.SAVED_FAST_SCROLL, L10n.t("Fast scroll in Saved"),
                        L10n.t("Shows a handle at the right edge of Saved and your collections while they scroll. "
                                + "Drag it to jump through them.")));
            }
            if (build.contains(PatchFamily.THREADS_BUTTON)) {
                profiles.addPreference(toggle(context, Settings.HIDE_THREADS_BUTTON, L10n.t("Hide the Threads button"),
                        L10n.t("Takes the Threads button off the top of profiles, yours included. The menu and "
                                + "the other buttons stay where they were.")));
                profiles.addPreference(toggle(context, Settings.HIDE_CREATE_BUTTON, L10n.t("Hide the Create button"),
                        L10n.t("Takes the plus for new posts off the top of your own profile. The other buttons "
                                + "stay where they were.")));
                profiles.addPreference(toggle(context, Settings.HIDE_SHARE_PROFILE_BUTTON, L10n.t("Hide Share profile"),
                        L10n.t("Takes the Share profile button off your profile, and Edit profile stretches to fill "
                                + "the space.")));
                profiles.addPreference(toggle(context, Settings.HIDE_SUBSCRIBE_PROFILE_BUTTON,
                        L10n.t("Hide Subscribe on profiles"),
                        L10n.t("Takes the Subscribe button off creators' profiles, and the other buttons "
                                + "stretch to fill the space.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_OVERFLOW_BUTTON,
                        L10n.t("Hide the arrow at the end of the buttons"),
                        L10n.t("Takes the arrow off the end of a profile's row of buttons, along with the menu it "
                                + "opens, and the other buttons stretch to fill the space.")));
            }
            if (build.contains(PatchFamily.PROFILE_NOTES)) {
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_NOTES, L10n.t("Hide Notes on profile pictures"),
                        L10n.t("Takes the Notes bubble off profile pictures, yours included. Notes in Messages stay "
                                + "as they are.")));
            }
            if (build.contains(PatchFamily.PROFILE_BANNERS)) {
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_THREADS_BADGE, L10n.t("Hide the Threads badge"),
                        L10n.t("Takes the Threads badge from under the bio on other people's profiles. Yours keeps it.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_FACEBOOK_LINKS, L10n.t("Hide Facebook links"),
                        L10n.t("Takes the links to someone's Facebook profile, page or group from under their bio. "
                                + "Yours keeps them.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_WHATSAPP_LINK, L10n.t("Hide the WhatsApp link"),
                        L10n.t("Takes the link to someone's WhatsApp from under their bio. Yours keeps it.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_SONG, L10n.t("Hide the profile song"),
                        L10n.t("Takes the song someone picked for their profile from under their bio. Yours keeps it.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_INTERESTS, L10n.t("Hide interests"),
                        L10n.t("Takes the interests someone added, like reading or hiking, from under their bio. "
                                + "Yours keeps them.")));
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_CHANNEL_BANNER, L10n.t("Hide channel invites"),
                        L10n.t("Takes the invite to someone's broadcast channel from under their bio. Yours keeps it.")));
                profiles.addPreference(toggle(context, Settings.HIDE_OTHER_PROFILE_BANNERS,
                        L10n.t("Hide the other profile banners"),
                        L10n.t("Takes the rest of the banners under the bio off other people's profiles, like events, "
                                + "fundraisers, schools and business hours. Yours keeps them.")));
                if (PatchFamily.profileLinksInBuild()) {
                    profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_LINKS, L10n.t("Hide links"),
                            L10n.t("Takes the row of links, like a website or a business's links, from under the bio "
                                    + "on other people's profiles. Yours keeps it.")));
                }
            }
            if (build.contains(PatchFamily.PROFILE_POSTS_LIST)) {
                profiles.addPreference(toggle(context, Settings.PROFILE_POSTS_LIST, L10n.t("Show profile posts as a list"),
                        L10n.t("Opening someone's profile takes you on to their posts as a scrolling list of full "
                                + "posts. Go Back for the grid. Your own profile keeps its grid.")));
            }
            if (build.contains(PatchFamily.PROFILE_GRID_JUMP)) {
                profiles.addPreference(toggle(context, Settings.PROFILE_GRID_JUMP, L10n.t("Jump a profile's posts to a date"),
                        L10n.t("Puts a calendar on a profile's top bar. Pick a day and the posts grid goes back to "
                                + "that day. Pull down for the newest again.")));
            }
        }
    }
}
