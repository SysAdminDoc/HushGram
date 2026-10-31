/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.blockedWordsRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.burnInGuardRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.burnInWaitRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.hiddenBarsRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.likeAnimationRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.reelCapBreakRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.reelCapCountRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.scrollBreakRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Reels category of HushGram settings. */
final class ReelsCategory {
    private ReelsCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        List<Preference> reels = new ArrayList<>();
        if (build.contains(PatchFamily.FEED_REELS)) {
            reels.add(toggle(context, Settings.HIDE_FEED_REELS, L10n.t("Hide Reels in the feed"),
                    L10n.t("The rows of suggested reels between posts in your home feed. A reel someone you "
                            + "follow posts stays.")));
        }
        if (build.contains(PatchFamily.REELS_SUGGESTIONS)) {
            reels.add(toggle(context, Settings.HIDE_REELS_SUGGESTIONS, L10n.t("Hide suggested accounts"),
                    L10n.t("The cards of people and creators to follow that Instagram puts between reels. "
                            + "Every reel still plays.")));
        }
        if (build.contains(PatchFamily.PAID_PARTNERSHIP_REELS)) {
            reels.add(toggle(context, Settings.HIDE_PAID_PARTNERSHIP_REELS, L10n.t("Hide paid partnership Reels"),
                    L10n.t("Leaves out reels with a Paid partnership label, and reels Instagram marks as boosted. "
                            + "Ads have their own switch.")));
        }
        if (build.contains(PatchFamily.AI_LABELED_REELS)) {
            reels.add(toggle(context, Settings.HIDE_AI_LABELED_REELS, L10n.t("Hide AI-labeled reels"),
                    L10n.t("Leaves out reels that carry Instagram's own AI label. A reel without that label stays, "
                            + "even if AI made it.")));
        }
        if (build.contains(PatchFamily.CAPTION_WORD_REELS)) {
            reels.add(toggle(context, Settings.HIDE_CAPTION_WORD_REELS, L10n.t("Hide reels by caption words"),
                    L10n.t("Leaves out reels in the Reels tab whose caption holds a word or phrase from the list below. "
                            + "A reel you open yourself still plays.")));
            reels.add(blockedWordsRow(context));
            if (PatchFamily.feedTypesInBuild()) {
                reels.add(toggle(context, Settings.HIDE_CAPTION_WORD_HOME, L10n.t("Also on Home"),
                        L10n.t("Leaves out posts on Home whose caption holds a word or phrase from the list too. "
                                + "Pull down on Home to refresh after changing it.")));
            }
        }
        if (build.contains(PatchFamily.REEL_DECLUTTER)) {
            reels.add(toggle(context, Settings.HIDE_REEL_FOLLOW_BUTTON, L10n.t("Hide the Follow button"),
                    L10n.t("The Follow and Subscribe buttons beside a reel's author. Their profile still has them.")));
            reels.add(toggle(context, Settings.HIDE_REEL_CHIPS, L10n.t("Hide promotion buttons on reels"),
                    L10n.t("Hides the buttons that push Edits, templates, Meta AI and Ray-Ban Meta glasses. Live "
                            + "badges and state-controlled media labels stay.")));
            reels.add(toggle(context, Settings.HIDE_REEL_SEARCH_PILL, L10n.t("Hide suggested searches on reels"),
                    L10n.t("The search terms Instagram suggests near a reel's author.")));
            reels.add(toggle(context, Settings.HIDE_REEL_PRODUCT_PILLS, L10n.t("Hide shopping buttons on reels"),
                    L10n.t("The product tag and shopping buttons near a reel's author.")));
            reels.add(toggle(context, Settings.HIDE_REEL_POLL_PILLS, L10n.t("Hide poll prompts on reels"),
                    L10n.t("The poll and vote prompts near a reel's author.")));
            reels.add(toggle(context, Settings.HIDE_REEL_SOCIAL_FOOTER, L10n.t("Hide friends' activity and comment previews"),
                    L10n.t("The bubbles of friends who liked or commented, the Followed by and Liked by lines with "
                            + "their faces, the comment shown under a reel and the row of friends who saw it. Comments "
                            + "are still a tap away.")));
            reels.add(toggle(context, Settings.HIDE_REEL_COMMENT_BAR, L10n.t("Hide the comment bar on reposted reels"),
                    L10n.t("The Add comment bar under a reel you open from a profile's reposts. The comment button "
                            + "still opens the comments.")));
            reels.add(toggle(context, Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, L10n.t("Hide the comment bar on all reels"),
                    L10n.t("The Add a comment bar under every reel, including the ones in the Reels tab. The comment "
                            + "button still opens the comments.")));
            reels.add(toggle(context, Settings.HIDE_REEL_REPLY_BAR, L10n.t("Hide the reply bar on reels from chats"),
                    L10n.t("The Reply to box under a reel you open from a chat, with its reactions and plus button. "
                            + "The chat's own message box stays.")));
            reels.add(toggle(context, Settings.HIDE_REELS_TOP_BAR, L10n.t("Hide the top bar on the Reels tab"),
                    L10n.t("Takes away the row at the top of the Reels tab, with the Reels title, the camera button and "
                            + "anything else Instagram puts there. Reels you open from a profile, a post or a chat keep "
                            + "theirs. The camera still opens from Home.")));
            reels.add(toggle(context, Settings.HIDE_REELS_CREATE_BUTTON, L10n.t("Hide the Create button on reels"),
                    L10n.t("Takes the plus button that opens the camera off the top of reels, on the Reels tab and "
                            + "everywhere else, and closes up the gap. The rest of the bar stays.")));
            reels.add(toggle(context, Settings.HIDE_REELS_YOUR_ALGORITHM, L10n.t("Hide Your algorithm on reels"),
                    L10n.t("Takes the Your algorithm button off the top of the Reels tab and closes up the gap. The "
                            + "rest of the bar stays.")));
            reels.add(toggle(context, Settings.HIDE_REELS_FRIENDS_TAB, L10n.t("Hide the Friends tab on Reels"),
                    L10n.t("Takes the Friends tab, with reels the people you follow shared, off the top of the Reels "
                            + "tab. Reels stays where it is.")));
            reels.add(toggle(context, Settings.HIDE_REEL_SAVE_BUTTON, L10n.t("Hide the Save button on reels"),
                    L10n.t("Takes the Save button out of the column of buttons beside a reel, and the count under it. "
                            + "Posts in your feed keep theirs.")));
            reels.add(burnInGuardRow(context));
            reels.add(burnInWaitRow(context));
            reels.add(toggle(context, Settings.BURN_IN_STAY_DIM, L10n.t("Stay dim when I tap"),
                    L10n.t("Once Burn-in guard fades the buttons, they stay faded on Reels whatever you tap or swipe, "
                            + "the next reel too. They still work. Leaving Reels brings them back.")));
            reels.add(toggle(context, Settings.KEEP_REEL_CLEAR_MODE, L10n.t("Keep clear mode on"),
                    L10n.t("Turn on Instagram's clear mode on a reel and the next reels open in it too. Leave clear "
                            + "mode yourself and the next reels show their buttons again.")));
            reels.add(toggle(context, Settings.START_REEL_CLEAR_MODE, L10n.t("Start every reel in clear mode"),
                    L10n.t("Reels open in clear mode from the first one, with no tap. Leave it yourself and the next "
                            + "reels show their buttons again. Instagram blocks screenshots in clear mode unless Allow "
                            + "screenshots is on.")));
        }
        if (build.contains(PatchFamily.KEEP_REELS_TOP_BAR)) {
            reels.add(toggle(context, Settings.KEEP_REELS_TOP_BAR, L10n.t("Keep the Reels top bar"),
                    L10n.t("Keeps the Reels title, the Friends tab and the buttons beside them at the top while you "
                            + "swipe through reels, instead of sliding them away.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.add(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY, L10n.t("Don't send reel watch history"),
                    L10n.t("Stops telling Instagram which reels you watched or how far you got. Instagram uses that "
                            + "to pick your Reels. Watched reels may come back.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            reels.add(toggle(context, Settings.DOWNLOAD_REELS, L10n.t("Download on reels"),
                    L10n.t("Adds Download to every reel's more menu, saved at your download quality. Off or paused, "
                            + "Instagram's own menu returns.")));
            reels.add(toggle(context, Settings.DOWNLOAD_REEL_COVER, L10n.t("Download cover"),
                    L10n.t("Adds Download cover under Download. It saves the still picture a reel shows before it "
                            + "plays, at its largest size.")));
            reels.add(toggle(context, Settings.DOWNLOAD_REEL_AUDIO, L10n.t("Download audio"),
                    L10n.t("Adds Download audio under Download. It saves the sound of the reel as an audio file.")));
        }
        if (build.contains(PatchFamily.DOUBLE_TAP_LIKE)) {
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE, L10n.t("Turn off double tap to like"),
                    L10n.t("A double tap on a post or reel no longer likes it or shows a heart. A single tap and "
                            + "the Like button work as before.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS, L10n.t("On posts"),
                    L10n.t("A double tap on a post doesn't like it. Turn this off to keep double tap to like on posts.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS, L10n.t("On reels"),
                    L10n.t("A double tap on a reel doesn't like it. Turn this off to keep double tap to like on reels.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS, L10n.t("On comments"),
                    L10n.t("A double tap on a comment doesn't like it. Starts off, so comments keep double tap to like until you turn this on.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES, L10n.t("On messages"),
                    L10n.t("A double tap on a message in a chat doesn't react to it. Starts off. A long press still shows the reactions.")));
        }
        if (build.contains(PatchFamily.LIKE_ANIMATION)) {
            reels.add(toggle(context, Settings.CHANGE_LIKE_ANIMATION, L10n.t("Change the like animation"),
                    L10n.t("The heart that pops up when you double tap a post plays the animation you pick below. "
                            + "They're animations Instagram made for its Rings creators.")));
            reels.add(likeAnimationRow(context));
        }
        if (build.contains(PatchFamily.FULL_SCREEN_BARS)) {
            reels.add(toggle(context, Settings.FULL_SCREEN_REELS, L10n.t("Full screen Reels"),
                    L10n.t("Hides the status bar and the navigation bar while Reels shows. Swipe in from the edge to "
                            + "bring them back for a moment. Back and the keyboard work as usual.")));
            reels.add(hiddenBarsRow(context));
            reels.add(toggle(context, Settings.FILL_REEL_SCREEN, L10n.t("Fill the screen"),
                    L10n.t("Reels that are nearly the screen's shape fill it, cut a little at the sides, instead of "
                            + "showing black bars above and below. Square and wide videos still fit.")));
            reels.add(toggle(context, Settings.FIT_REEL_SCREEN, L10n.t("Show the whole reel"),
                    L10n.t("Every reel fits inside the screen, with black bars where its shape differs, so nothing is "
                            + "cut off at the sides. Works without Full screen Reels and wins over Fill the screen.")));
        }
        if (build.contains(PatchFamily.KEEP_REEL_SPEED)) {
            reels.add(toggle(context, Settings.KEEP_REEL_SPEED, L10n.t("Keep the reel speed"),
                    L10n.t("Lock a reel at 2x (hold its edge, then slide down) and the next reels play at 2x too. "
                            + "Slide the lock off, or hold the edge and let go, to go back to normal speed.")));
        }
        if (build.contains(PatchFamily.REEL_POST_TIME)) {
            reels.add(toggle(context, Settings.SHOW_REEL_POST_TIME, L10n.t("Show a reel's exact time"),
                    L10n.t("Shows the date and time a reel was posted under the author's name, like Oct 2, 3:45 PM. "
                            + "A change shows from the next reel.")));
            reels.add(toggle(context, Settings.REEL_POST_TIME_SECONDS, L10n.t("Show seconds in a reel's time"),
                    L10n.t("Adds the seconds to a reel's time, like Oct 2, 3:45:30 PM.")));
            reels.add(toggle(context, Settings.REEL_POST_TIME_AGO, L10n.t("Show how long ago a reel was posted"),
                    L10n.t("Shows the days, hours and minutes since it went up on a line under the time, like 1d 2h 5m ago.")));
        }
        if (build.contains(PatchFamily.REEL_SEEK_BAR)) {
            reels.add(toggle(context, Settings.REEL_SEEK_BAR, L10n.t("Keep a seek bar"),
                    L10n.t("Instagram's seek bar stays under every reel, short ones too, with the time played and "
                            + "the reel's length above it. Ads keep Instagram's own rules.")));
            reels.add(toggle(context, Settings.REEL_SEEK_THUMB, L10n.t("Round handle on the seek bar"),
                    L10n.t("Adds a white round handle to the seek bar that you can drag. Ads keep their own bar.")));
            reels.add(toggle(context, Settings.BIG_REEL_SEEK_BAR, L10n.t("Bigger seek bar on reels"),
                    L10n.t("Makes the seek bar thicker, with a round white handle and the time above it, over a soft "
                            + "dark fade so it shows on any video. Drag it to seek. It also keeps the bar on short reels.")));
            reels.add(toggle(context, Settings.DM_REEL_SEEK_BAR, L10n.t("Seek bar like DM videos"),
                    L10n.t("Gives reels the seek bar of a video in a chat, a thin gray line that turns white as it plays, "
                            + "with a big round white handle. The time shows above it. Drag it to seek. When Bigger seek "
                            + "bar is on too, this look wins. It also keeps the bar on short reels.")));
        }
        if (build.contains(PatchFamily.REEL_ZOOM)) {
            reels.add(toggle(context, Settings.PINCH_ZOOM_REELS, L10n.t("Pinch to zoom"),
                    L10n.t("Two fingers zoom into a playing reel, the way a photo zooms. Lift them and it goes back. "
                            + "Restart Instagram to see the change.")));
        }
        if (build.contains(PatchFamily.REEL_BLUR_BARS)) {
            reels.add(toggle(context, Settings.BLUR_REEL_BARS, L10n.t("Blur the bars around a reel"),
                    L10n.t("A reel that doesn't fill the screen shows a blurred copy of itself above and below it "
                            + "instead of black bars, and the strip under the tab bar and the band behind the clock show the reel "
                            + "blurred too. "
                            + "Open Reels again after a change.")));
        }
        if (build.contains(PatchFamily.REEL_AUTO_SCROLL)) {
            reels.add(toggle(context, Settings.KEEP_REEL_AUTO_SCROLL, L10n.t("Keep auto scroll on"),
                    L10n.t("Once you turn on Instagram's auto scroll in Reels, it stays on after a restart or after "
                            + "you leave Reels, until you turn it off.")));
            reels.add(toggle(context, Settings.REEL_AUTO_ADVANCE, L10n.t("Move on when a reel ends"),
                    L10n.t("When a reel ends, Reels moves on to the next one instead of playing it again. It uses "
                            + "Instagram's own auto scroll, even on accounts where Instagram doesn't offer it. "
                            + "While it's on, Instagram's own Auto scroll switch can't turn it off. "
                            + "Restart Instagram to see the change.")));
        }
        if (build.contains(PatchFamily.REEL_SCROLLING)) {
            reels.add(toggle(context, Settings.STOP_REELS_SCROLLING, L10n.t("Stop Reels scrolling"),
                    L10n.t("A swipe in Reels no longer moves on to the next reel, and pulling down doesn't load new "
                            + "ones. The reel you opened still plays. Restart Instagram to see the change.")));
            reels.add(toggle(context, Settings.REEL_CAP, L10n.t("Stop after a set number of reels"),
                    L10n.t("After the reels you pick below, swiping in Reels stops until Instagram has been in the "
                            + "background for the time you pick. A reel you open from a message or a post still "
                            + "plays.")));
            reels.add(reelCapCountRow(context));
            reels.add(reelCapBreakRow(context));
            reels.add(scrollBreakRow(context));
        }
        if (build.contains(PatchFamily.SWIPE_TO_PROFILE)) {
            reels.add(toggle(context, Settings.REEL_SWIPE_TO_PROFILE, L10n.t("Swipe left to open the profile"),
                    L10n.t("A left swipe on a reel opens the creator's profile, like Instagram used to. Tap "
                            + "Friends at the top to reach your friends' reels.")));
        }
        if (build.contains(PatchFamily.REEL_ORIGIN)) {
            reels.add(toggle(context, Settings.FIND_REEL_ORIGIN, L10n.t("Find the reel you came from"),
                    L10n.t("Open a creator's profile from a reel, then their Reels tab, and it scrolls to that reel "
                            + "and outlines it for a moment. A photo reel is found in their posts. Stop looking ends "
                            + "the search where it is.")));
            reels.add(toggle(context, Settings.FIND_REEL_ORIGIN_ON_TAP, L10n.t("Wait for a tap to find it"),
                    L10n.t("When the reel isn't on the first screen, the profile shows a Find the reel button "
                            + "instead of scrolling on its own.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            reels.add(toggle(context, Settings.REEL_NOT_INTERESTED_BUTTON, L10n.t("Not interested button on reels"),
                    L10n.t("Puts a Not interested icon beside a reel, so one tap does what Not interested in its "
                            + "menu does.")));
            reels.add(toggle(context, Settings.REEL_BLOCK_BUTTON, L10n.t("Block button on reels"),
                    L10n.t("Puts a Block icon beside someone else's reel. A tap opens Instagram's own block "
                            + "screen for that account, so you still confirm it there.")));
            reels.add(toggle(context, Settings.HIDE_SOUND_REELS, L10n.t("Hide reels by sound"),
                    L10n.t("Puts a music icon beside a reel. Tap it and the Reels tab skips other reels using that "
                            + "sound. Tap it again to bring them back.")));
            reels.add(page.hiddenSoundsRow(context));
            reels.add(toggle(context, Settings.TEXT_BUTTON_ON_REELS, L10n.t("Copy text button on reels"),
                    L10n.t("Puts a Lens icon beside a reel. Tap it and Google Lens opens on what's showing, so you "
                            + "can select and copy its text.")));
        }
        if (!reels.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Reels"), SettingsIcons.REELS,
                    L10n.t("Playback and controls in the Reels tab."));
            for (Preference row : reels) section.addPreference(row);
        }
    }
}
