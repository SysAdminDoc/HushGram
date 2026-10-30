/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.onFlip;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyBarRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingColorRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyRingRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.storyTimeModeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.trayBadgeRow;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.stories.StoryProgressBar;
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
                            + "of stories you haven't watched in the color below. Restart Instagram to see a "
                            + "change.")));
            stories.add(storyRingRow(context));
            stories.add(storyRingColorRow(context));
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
        }
        if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
            stories.add(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE, L10n.t("Stop Story auto-advance"),
                    L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Instagram's timing.")));
        }
        if (build.contains(PatchFamily.STORY_LOOP)) {
            stories.add(toggle(context, Settings.LOOP_STORIES, L10n.t("Loop a story"),
                    L10n.t("A story plays again from the start when it ends, until you tap or swipe to move on. "
                            + "Ads still move on. With Stop Story auto-advance on too, stories loop.")));
        }
        if (build.contains(PatchFamily.STORY_SOUND)) {
            stories.add(toggle(context, Settings.START_STORIES_WITH_SOUND, L10n.t("Start stories with sound"),
                    L10n.t("Stories open with their sound on, the same as tapping Instagram's speaker. Your phone's "
                            + "ringer and volume still count, so on silent or with the volume down, stories stay quiet.")));
        }
        if (build.contains(PatchFamily.STORY_SWIPE_REPLY)) {
            stories.add(toggle(context, Settings.STOP_STORY_SWIPE_REPLY, L10n.t("Stop swipe up to reply"),
                    L10n.t("A swipe up on someone's story no longer opens the reply box and its reactions. "
                            + "Tap Send message to reply.")));
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
            stories.add(toggle(context, Settings.GRAY_OUT_WATCHED_STORIES, L10n.t("Gray out stories you've watched"),
                    L10n.t("While you view anonymously, a story you've watched turns gray and moves to the end of the row, "
                            + "on this phone only. Instagram still isn't told you watched it.")));
        }
        if (build.contains(PatchFamily.LIVE_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_LIVE_ANONYMOUSLY, L10n.t("View live anonymously"),
                    L10n.t("Lives you watch don't put you on the host's viewer list, so they aren't told you're there. Commenting or reacting still shows you. The viewer count you see stops updating, and a live that ends can keep looking live until you leave it.")));
        }
        if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
            stories.add(toggle(context, Settings.DOWNLOAD_STORIES, L10n.t("Download on stories"),
                    L10n.t("Adds Download to the menu of anyone's story, photo or video, saved at your download quality. "
                            + "Off or paused, Instagram's own menu returns.")));
        }
        if (build.contains(PatchFamily.STORY_UPLOAD_QUALITY)) {
            stories.add(toggle(context, Settings.UPLOAD_STORIES_AT_FULL_QUALITY, L10n.t("Upload stories at full quality"),
                    L10n.t("Video stories you post are sent at 1080p and a higher bitrate, as with Instagram's own Upload at "
                            + "highest quality setting on. Instagram still shrinks story videos to 720p on its servers, so the "
                            + "difference viewers see is small. Photo stories already go up larger than Instagram keeps.")));
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
