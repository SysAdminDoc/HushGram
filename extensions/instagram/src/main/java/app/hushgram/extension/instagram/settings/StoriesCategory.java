/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.CLEAR_STORY_VIEWERS;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearStoryViewers;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyViewersRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.onFlip;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.priorityAccountsRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyBarRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyCommentLineRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyMentionsLookRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingColorRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingCustomEndRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingCustomRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingThicknessRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storySeekBarLookRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storySeekBarPositionRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyTimeModeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadBitrateRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadFrameRateRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadPhotoQualityRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadPhotoSizeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadResolutionRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.uploadScopeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.slowsOlderPhones;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.trayBadgeRow;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.feed.AmbientLight;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.instagram.stories.StoryReplyBox;
import app.hushgram.extension.shared.L10n;

/** Builds the Stories category of HushGram settings. */
final class StoriesCategory {
    private StoriesCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        List<Preference> stories = new ArrayList<>();
        if (build.contains(PatchFamily.STORIES_TRAY)) {
            stories.add(toggle(context, Settings.HIDE_SUGGESTED_STORIES, L10n.t("Hide suggested stories"),
                    L10n.t("Hides stories from accounts you don't follow, and accounts Instagram suggests, in the "
                            + "row at the top of Home. Stories from accounts you follow stay.")));
            stories.add(toggle(context, Settings.HIDE_STORY_REWINDS, L10n.t("Hide story rewinds"),
                    L10n.t("Hides the rewind cards, which bring back old highlights, from the row of stories at the "
                            + "top of Home.")));
            stories.add(toggle(context, Settings.HIDE_STORY_RECAPS, L10n.t("Hide memories and recaps"),
                    L10n.t("Takes the memories, recaps, follow anniversaries and birthday cards Instagram makes out "
                            + "of the row of stories at the top of Home. Stories people post stay.")));
            stories.add(toggle(context, Settings.STOP_LOADING_STORIES, L10n.t("Stop loading stories and highlights"),
                    L10n.t("Nothing in the row of stories at the top of Home loads, your own story included, and "
                            + "highlights on profiles stay empty too. Saves data. A story ring on a profile or in a chat "
                            + "still opens its stories.")));
            stories.add(toggle(context, Settings.HIDE_STORIES_TRAY, L10n.t("Hide the Stories tray"),
                    L10n.t("Takes the whole row of stories off the top of Home, Your story included. Stories still "
                            + "open from a profile or a message.")));
            stories.add(toggle(context, Settings.KEEP_TRAY_IN_FEED, L10n.t("Keep the Stories tray in the feed"),
                    L10n.t("Stops Instagram from floating the row of stories over Home when you come back to it "
                            + "mid-feed or scroll up fast. The row stays at the top of the feed and scrolls away with it.")));
            stories.add(toggle(context, Settings.HIDE_MUSIC_CARD, L10n.t("Hide the Music for you card"),
                    L10n.t("Keeps Instagram's Music for you card from turning up between the stories you watch. "
                            + "The stories themselves stay.")));
            stories.add(toggle(context, Settings.HIDE_STORY_SUGGESTIONS, L10n.t("Hide Suggested for you between stories"),
                    L10n.t("Leaves out the Suggested for you cards of accounts to follow that Instagram puts between "
                            + "the stories you tap through. The stories themselves stay.")));
            stories.add(toggle(context, Settings.HIDE_ADD_YOURS_CARDS, L10n.t("Hide Add Yours cards between stories"),
                    L10n.t("Leaves out the Add Yours cards, like Popular Add Yours and Join a trending Add Yours, "
                            + "that Instagram puts between the stories you tap through. The stories themselves stay.")));
            stories.add(toggle(context, Settings.FILTER_TRAY_BY_BADGE, L10n.t("Filter the Stories tray by verified accounts"),
                    L10n.t("The row of stories at the top of Home keeps only the accounts the choice below names, "
                            + "by Instagram's verified badge. Your own story stays. Turn it off to see every story.")));
            stories.add(trayBadgeRow(context));
        }
        if (build.contains(PatchFamily.STORY_RING)) {
            stories.add(toggle(context, Settings.STORY_RING, L10n.t("Story ring size"),
                    L10n.t("Draws the rings in the stories row at the top of Home at the size below, and the rings "
                            + "of stories you haven't watched in the color below. Story rings take the thickness "
                            + "below. Restart Instagram to see a change.")));
            stories.add(storyRingRow(context));
            stories.add(storyRingColorRow(context));
            stories.add(storyRingCustomRow(context));
            stories.add(storyRingCustomEndRow(context));
            stories.add(storyRingThicknessRow(context));
            stories.add(toggle(context, Settings.HIDE_OTHER_STORY_RINGS, L10n.t("Hide rings outside the stories row"),
                    L10n.t("Takes the story and live rings off profile pictures on posts, comments, reels and "
                            + "profiles. The pictures stay, and so do the rings in the stories row.")));
        }
        if (build.contains(PatchFamily.SAVED_STORY_RINGS)) {
            stories.add(toggle(context, Settings.SAVED_STORY_RINGS, L10n.t("Story rings on saved posts"),
                    L10n.t("Posts you saved show the story ring around the author's picture, like Home does, and a tap "
                            + "on it opens their story. It only uses stories Instagram has already loaded.")));
        }
        if (build.contains(PatchFamily.SMOOTH_STORY_OPEN)) {
            stories.add(toggle(context, Settings.SMOOTH_STORY_OPEN, L10n.t("Smooth story opening"),
                    L10n.t("A story you tap in the stories row opens with a slower zoom that doesn't bounce, "
                            + "and closes the same way.")));
        }
        if (build.contains(PatchFamily.STORY_ZOOM_PLAY)) {
            stories.add(toggle(context, Settings.KEEP_STORIES_PLAYING_ON_ZOOM, L10n.t("Keep stories playing while zooming"),
                    L10n.t("A story keeps playing while you pinch to zoom it. Hold it still first and it stays "
                            + "paused while you zoom.")));
        }
        if (build.contains(PatchFamily.STORY_PRIORITY)) {
            stories.add(toggle(context, Settings.STORY_PRIORITY, L10n.t("Stories priority"),
                    L10n.t("Puts the accounts on your list first in the stories row at the top of Home while they "
                            + "have a story you haven't watched, in the order of the list. Once you've watched it, "
                            + "Instagram's own placement applies again. Your own story stays first.")
                            + " " + L10n.t("To add someone, tap Add to Stories priority in their story's menu.")));
            stories.add(priorityAccountsRow(context));
        }
        if (build.contains(PatchFamily.STORY_PROGRESS_BAR)) {
            stories.add(onFlip(toggle(context, Settings.THICKER_STORY_BAR, L10n.t("Thicker story progress bar"),
                    L10n.t("Draws the bar along the top of a story as thick as the choice below. Its segments "
                            + "and timing stay as they were.")), StoryProgressBar::switchChanged));
            stories.add(storyBarRow(context));
        }
        if (build.contains(PatchFamily.STORY_SEEK_BAR)) {
            stories.add(toggle(context, Settings.STORY_SEEK_BAR, L10n.t("Seek bar on stories"),
                    L10n.t("Shows a bar near the bottom of a video story. Drag it to jump to any point in the "
                            + "video. Photos don't get one.")));
            stories.add(storySeekBarLookRow(context));
            stories.add(storySeekBarPositionRow(context));
            stories.add(toggle(context, Settings.STORY_SEEK_TIME, L10n.t("Time on the seek bar"),
                    L10n.t("Shows the time played and the story's length at the start of its seek bar, like "
                            + "0:03 / 0:15. Tap the time to see the time left instead.")));
            stories.add(toggle(context, Settings.STORY_SEEK_TIME_LEFT, L10n.t("Show the time left"),
                    L10n.t("The time on the seek bar counts down what's left of the video, like -0:33 / 0:45. "
                            + "A tap on the time switches back.")));
        }
        if (build.contains(PatchFamily.STORY_SPEED)) {
            stories.add(toggle(context, Settings.STORY_SPEED_BUTTON, L10n.t("Speed button on stories"),
                    L10n.t("Puts a speed button at the top of a video story, beside the three dots. Tap it for "
                            + "1.5x, again for 2x and once more for 1x. Later video stories keep the speed until you "
                            + "change it or restart Instagram. Ads play at normal speed.")));
            // The menu row rides on Download any story's menu hooks (#621).
            if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
                stories.add(toggle(context, Settings.STORY_SPEED_IN_MENU, L10n.t("Speed in the story menu"),
                        L10n.t("Adds Playback speed to the menu a video story's three dots open, with the same 1x, "
                                + "1.5x and 2x, and keeps the speed button off the story. Later video stories keep "
                                + "the speed you pick until you change it or restart Instagram.")));
            }
        }
        if (build.contains(PatchFamily.FULL_SCREEN_BARS)) {
            stories.add(toggle(context, Settings.FULL_SCREEN_STORIES, L10n.t("Full screen stories"),
                    L10n.t("Hides the status bar and the navigation bar while a story shows. Swipe in from the edge "
                            + "to bring them back for a moment.")));
            stories.add(toggle(context, Settings.FILL_STORY_SCREEN, L10n.t("Stories fill the screen"),
                    L10n.t("On a tall screen a story covers the whole screen, with the reply bar on top of it like "
                            + "on shorter phones, instead of a rounded card above the bar.")));
            // The menu row rides on Download any story's menu hooks (#845).
            if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
                stories.add(toggle(context, Settings.WHOLE_STORY_IN_STORY_MENU, L10n.t("Whole story in the story menu"),
                        L10n.t("Adds Show the whole story to the menu a story's three dots open while Stories fill the "
                                + "screen is on. A tap fits that story inside the screen until the next one. Fill the "
                                + "screen there fills it again.")));
            }
        }
        if (build.contains(PatchFamily.AMBIENT_LIGHT)) {
            stories.add(onFlip(toggle(context, Settings.AMBIENT_LIGHT_STORIES, L10n.t("Ambient light in stories"),
                    slowsOlderPhones(L10n.t("Fills the bands above and below a story that doesn't fill the screen with a soft glow in "
                            + "its own colors. Glow spread and Glow speed, under Feed, set it too."))),
                    AmbientLight::switchChanged));
        }
        if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
            stories.add(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE, L10n.t("Stop Story auto-advance"),
                    L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Instagram's timing.")));
        }
        if (build.contains(PatchFamily.STORY_LOOP)) {
            stories.add(toggle(context, Settings.LOOP_STORIES, L10n.t("Loop a story"),
                    L10n.t("A story plays again from the start when it ends, until you tap or swipe to move on. "
                            + "Ads still move on. With Stop Story auto-advance on too, stories loop.") + " "
                            + L10n.t("To loop one story, tap Loop this story in its menu.")));
        }
        if (build.contains(PatchFamily.STORY_SOUND)) {
            stories.add(toggle(context, Settings.START_STORIES_WITH_SOUND, L10n.t("Start stories with sound"),
                    L10n.t("Stories open with their sound on, the same as tapping Instagram's speaker. Your phone's "
                            + "ringer and volume still count, so on silent or with the volume down, stories stay quiet.")));
            stories.add(toggle(context, Settings.START_STORIES_ON_SILENT, L10n.t("Even on silent"),
                    L10n.t("Stories open with sound with the phone on silent or vibrate too. Only the media "
                            + "volume counts.")));
        }
        if (build.contains(PatchFamily.STORY_SWIPE_REPLY)) {
            stories.add(toggle(context, Settings.STOP_STORY_SWIPE_REPLY, L10n.t("Stop swipe up to reply"),
                    L10n.t("A swipe up on someone's story no longer opens the reply box and its reactions. "
                            + "Tap Send message to reply.")));
            // Rides on Stop swipe up to reply's patch, the story gesture patch (#887).
            stories.add(toggle(context, Settings.STOP_STORY_SIDE_TAPS, L10n.t("Stop story side taps"),
                    L10n.t("A tap on the left or right of a story no longer moves to the one before or after, "
                            + "and the story keeps playing. Swipe to go to the next person's stories.")));
        }
        if (build.contains(PatchFamily.STORY_REPLY_BOX)) {
            stories.add(onFlip(toggle(context, Settings.HIDE_STORY_REPLY_BOX, L10n.t("Hide the reply box on stories"),
                    L10n.t("Takes the Send message box off the bottom of other people's stories. The like and "
                            + "share buttons next to it stay where they are.")), StoryReplyBox::switchChanged));
            stories.add(onFlip(toggle(context, Settings.HIDE_STORY_LIKE_BUTTON, L10n.t("Hide the Like button on stories"),
                    L10n.t("Takes the heart off the bottom of other people's stories, so nothing gets liked by "
                            + "mistake. No empty space is left behind.")), StoryReplyBox::switchChanged));
            stories.add(onFlip(toggle(context, Settings.HIDE_STORY_COMMENT_BUTTON, L10n.t("Hide the Comment button on stories"),
                    L10n.t("Takes the Comment button off the bottom of other people's stories that have one. "
                            + "No empty space is left behind.")), StoryReplyBox::switchChanged));
            stories.add(onFlip(toggle(context, Settings.HIDE_STORY_SHARE_BUTTON, L10n.t("Hide the Share button on stories"),
                    L10n.t("Takes the paper plane off the bottom of other people's stories. No empty space is "
                            + "left behind.")), StoryReplyBox::switchChanged));
            stories.add(onFlip(toggle(context, Settings.HIDE_STORY_COMMENTS, L10n.t("Hide comments on stories"),
                    L10n.t("Takes the comments floating over stories off them, along with the faces of the people "
                            + "who wrote them. The Comment button still opens them.")), StoryReplyBox::switchChanged));
            stories.add(onFlip(storyCommentLineRow(context), StoryReplyBox::switchChanged));
        }
        if (build.contains(PatchFamily.GLASS_CHAT)) {
            stories.add(toggle(context, Settings.GLASS_STORY_REPLY_BOX, L10n.t("Glass reply box on stories"),
                    L10n.t("Gives the Send message box on other people's stories frosted glass, so it's easy to read "
                            + "over any story. Needs Android 12 or newer for the blur.")));
        }
        if (build.contains(PatchFamily.STORY_BUTTONS_ROOM)) {
            stories.add(toggle(context, Settings.KEEP_STORY_BUTTONS_BELOW, L10n.t("Keep story buttons below the story"),
                    L10n.t("With a large font or display size, the reply box and buttons at the bottom of a story "
                            + "sit in the space under it instead of covering the story.")));
        }
        if (build.contains(PatchFamily.STORY_TIME)) {
            stories.add(toggle(context, Settings.SHOW_STORY_TIME, L10n.t("Show a story's exact time"),
                    L10n.t("A story's header shows its time the way the choice below says, instead of how long ago. "
                            + "It follows your phone's language and 12 or 24-hour setting.")));
            stories.add(storyTimeModeRow(context));
        }
        if (build.contains(PatchFamily.STORY_MENTIONS)) {
            stories.add(toggle(context, Settings.SHOW_STORY_MENTIONS, L10n.t("See who a story mentions"),
                    L10n.t("A story's header says how many accounts it mentions, even when the mention is hidden. "
                            + "Tap that for the list, and tap someone to open their profile.")));
            stories.add(toggle(context, Settings.RED_STORY_MENTIONS, L10n.t("Red story mentions"),
                    L10n.t("Draws the mention pill's text in red instead of white, so it stands out on bright stories.")));
            stories.add(storyMentionsLookRow(context));
        }
        if (build.contains(PatchFamily.STORY_FOLLOW)) {
            stories.add(toggle(context, Settings.HIDE_STORY_FOLLOW, L10n.t("Hide Follow on stories"),
                    L10n.t("Takes the Follow button off the top of stories from accounts you don't follow. "
                            + "The name, time and menu stay.")));
        }
        if (build.contains(PatchFamily.STORY_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_STORIES_ANONYMOUSLY, L10n.t("View stories anonymously"),
                    L10n.t("Instagram isn't told which stories you watch, so you stay off their viewer lists. "
                            + "Replying or reacting still shows you, and stories you've watched keep showing as new.")));
            stories.add(toggle(context, Settings.MARK_STORIES_SEEN, L10n.t("Mark as seen button"),
                    L10n.t("Adds an eye button to the top of each story while you view anonymously. Tap it to show up "
                            + "on that story's viewer list. The other stories stay hidden.")));
            stories.add(toggle(context, Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY, L10n.t("Mark a story seen when you like or reply"),
                    L10n.t("While you view anonymously, liking a story, replying to it or sending it a quick reaction "
                            + "puts you on that story's viewer list first. The other stories stay hidden.")));
            stories.add(toggle(context, Settings.GRAY_OUT_WATCHED_STORIES, L10n.t("Gray out stories you've watched"),
                    L10n.t("While you view anonymously, a story you've watched turns gray and moves to the end of the row, "
                            + "on this phone only. Instagram still isn't told you watched it.")));
        }
        if (build.contains(PatchFamily.LIVE_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_LIVE_ANONYMOUSLY, L10n.t("View live anonymously"),
                    L10n.t("Lives you watch don't put you on the host's viewer list, so they aren't told you're there. Commenting or reacting still shows you. The viewer count you see stops updating, and a live that ends can keep looking live until you leave it.")));
            stories.add(toggle(context, Settings.MARK_LIVE_SEEN, L10n.t("Mark as seen button on lives"),
                    L10n.t("Adds an eye button to the top of a live while you watch anonymously. Tap it to show up on "
                            + "that live's viewer list. Other lives stay hidden.")));
        }
        if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
            stories.add(toggle(context, Settings.DOWNLOAD_STORIES, L10n.t("Download on stories"),
                    L10n.t("Adds Download to the menu of anyone's story, photo or video, saved at your download quality. "
                            + "Off or paused, Instagram's own menu returns.")));
            stories.add(toggle(context, Settings.DOWNLOAD_BUTTON_ON_STORIES, L10n.t("Download button on stories"),
                    L10n.t("Puts a Download button at the top of a story, beside the three dots. One tap saves the "
                            + "photo or video in front, the same as Download in the menu. Press and hold for "
                            + "Save all, to save every story in the set you're watching.")));
            stories.add(toggle(context, Settings.TEXT_BUTTON_ON_STORIES, L10n.t("Copy text button on stories"),
                    L10n.t("Puts a Lens icon at the top of a story, beside the three dots. Tap it and Google Lens "
                            + "opens on what's showing, so you can select and copy its text.")));
            stories.add(toggle(context, Settings.STORY_COPY_TEXT_IN_MENU, L10n.t("Copy text in the story menu"),
                    L10n.t("Adds Copy text to the menu a story's three dots open. It copies the words Instagram "
                            + "found in the picture, and when it found none, Google Lens opens on the story so you "
                            + "can copy them there.")));
        }
        if (build.contains(PatchFamily.STORY_SEEN) || build.contains(PatchFamily.STORY_DOWNLOAD)) {
            stories.add(toggle(context, Settings.STORY_BUTTONS_BY_THUMB, L10n.t("Story buttons at the bottom"),
                    L10n.t("Moves the Mark as seen and Download buttons to the bottom right of a story, next to Like "
                            + "and Share, where your thumb is. Stories without Like and Share keep them at the top.")));
        }
        if (build.contains(PatchFamily.STORY_UPLOAD_QUALITY)) {
            stories.add(toggle(context, Settings.UPLOAD_STORIES_AT_FULL_QUALITY, L10n.t("Upload stories at full quality"),
                    L10n.t("Video stories you post are sent at 1080p and a higher bitrate, as with Instagram's own Upload at "
                            + "highest quality setting on. Instagram still shrinks story videos to 720p on its servers, so the "
                            + "difference viewers see is small. Photo stories already go up larger than Instagram keeps.")));
            stories.add(uploadScopeRow(context));
            stories.add(uploadBitrateRow(context));
            stories.add(uploadResolutionRow(context));
            stories.add(uploadFrameRateRow(context));
            stories.add(uploadPhotoQualityRow(context));
            stories.add(uploadPhotoSizeRow(context));
        }
        if (build.contains(PatchFamily.STORY_VIEWERS)) {
            stories.add(toggle(context, Settings.STORY_VIEWERS, L10n.t("Keep story viewers"),
                    L10n.t("Each time you open who viewed one of your stories, the list is kept on this phone and set "
                            + "beside your followers. Nothing extra is asked of Instagram. Turn it off and it stops at once.")));
            stories.add(toggle(context, Settings.STORY_VIEWERS_SEARCH, L10n.t("Search bar in the viewer list"),
                    L10n.t("Puts a Search viewers bar at the top of the list of who viewed your story. It searches "
                            + "the viewers kept on this phone, the ones Instagram has listed so far.")));
            Preference viewers = storyViewersRow(context);
            stories.add(viewers);
            HushgramPreferenceFragment.Row clear = new HushgramPreferenceFragment.Row(context);
            clear.setKey(CLEAR_STORY_VIEWERS);
            clear.setPersistent(false);
            clear.actsAtOnce = true;
            clear.setTitle(L10n.t("Clear story viewers"));
            clear.setSummary(L10n.t("Forgets every story's viewers kept here. The switch stays as it is."));
            clear.setOnPreferenceClickListener(p -> {
                clearStoryViewers(p, viewers);
                return true;
            });
            stories.add(mark(clear, SettingsIcons.DELETE));
        }
        if (build.contains(PatchFamily.STORY_FONTS)) {
            stories.add(toggle(context, Settings.STORY_FONTS, L10n.t("Your fonts in story text"),
                    L10n.t("Adds the fonts below to the story text editor alongside Instagram's own fonts, which all "
                            + "stay. Pick one there like any other font.")));
            stories.add(page.storyFontsRow(context));
            stories.add(page.storyEmojiRow(context));
        }
        if (!stories.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Stories"), SettingsIcons.STORIES,
                    L10n.t("Viewing, sound and the stories tray."));
            for (Preference row : stories) section.addPreference(row);
        }
    }
}
