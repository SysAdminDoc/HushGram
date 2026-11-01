/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.ambientSpeedRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.ambientSpreadRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.cornerSizeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.onFlip;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.feed.AmbientLight;
import app.hushgram.extension.instagram.feed.CarouselArrows;
import app.hushgram.extension.instagram.feed.FeedFollowButton;
import app.hushgram.extension.instagram.feed.HideCaptions;
import app.hushgram.extension.instagram.feed.CarouselPeek;
import app.hushgram.extension.instagram.feed.ReelCorners;
import app.hushgram.extension.instagram.feed.StoryCorners;
import app.hushgram.extension.shared.L10n;

/** Builds the Feed category of HushGram settings. */
final class FeedCategory {
    private FeedCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        boolean suggestions = build.contains(PatchFamily.FEED_SUGGESTIONS);
        boolean following = build.contains(PatchFamily.FOLLOWING_FEED);
        boolean swipe = build.contains(PatchFamily.SWIPE_TO_CREATE);
        boolean fullResolution = build.contains(PatchFamily.FULL_RESOLUTION);
        boolean homeFeed = build.contains(PatchFamily.HOME_FEED);
        boolean tabSwipe = build.contains(PatchFamily.TAB_SWIPE);
        boolean askLike = build.contains(PatchFamily.ASK_BEFORE_LIKE);
        boolean askRefresh = build.contains(PatchFamily.ASK_BEFORE_REFRESH);
        boolean saveTray = build.contains(PatchFamily.SAVE_TRAY);
        boolean postTime = build.contains(PatchFamily.POST_TIME);
        boolean hiddenLikes = build.contains(PatchFamily.HIDDEN_LIKE_COUNTS);
        boolean backHome = build.contains(PatchFamily.BACK_LEAVES_HOME);
        boolean homePlace = build.contains(PatchFamily.HOME_PLACE);
        boolean feedSound = build.contains(PatchFamily.FEED_SOUND);
        boolean feedTapPause = build.contains(PatchFamily.FEED_TAP_PAUSE);
        boolean feedSeekBar = build.contains(PatchFamily.FEED_SEEK_BAR);
        boolean longPressZoom = build.contains(PatchFamily.LONG_PRESS_ZOOM);
        boolean roundedCorners = build.contains(PatchFamily.ROUNDED_POST_CORNERS);
        boolean carouselArrows = build.contains(PatchFamily.CAROUSEL_ARROWS);
        boolean ambientLight = build.contains(PatchFamily.AMBIENT_LIGHT);
        boolean fullScreen = build.contains(PatchFamily.FULL_SCREEN_BARS);
        boolean feedFollow = build.contains(PatchFamily.FEED_FOLLOW);
        boolean hideCaptions = build.contains(PatchFamily.HIDE_CAPTIONS);
        boolean homeTopBar = build.contains(PatchFamily.HOME_TOP_BAR);
        PreferenceCategory feed = suggestions || following || swipe || fullResolution || homeFeed || tabSwipe
                || askLike || askRefresh || saveTray || postTime || hiddenLikes || backHome || homePlace || feedSound || feedTapPause || feedSeekBar || longPressZoom
                || roundedCorners || carouselArrows || ambientLight
                || fullScreen || feedFollow || hideCaptions || homeTopBar
                ? category(screen, L10n.t("Feed"), SettingsIcons.FEED,
                        L10n.t("Choose what Home shows and how it behaves.")) : null;
        if (following) {
            feed.addPreference(toggle(context, Settings.START_ON_FOLLOWING, L10n.t("Start Home on Following"),
                    L10n.t("Opens Home on posts from accounts you follow instead of For you. Tap the top of Home to "
                            + "switch. Restart Instagram to see the change.")));
            feed.addPreference(toggle(context, Settings.ONLY_FOLLOWING, L10n.t("Only accounts you follow"),
                    L10n.t("Removes For you from the choices at the top of Home, so it stays on Following or "
                            + "Favorites. Needs Start Home on Following. Restart Instagram to see the change.")));
            feed.addPreference(toggle(context, Settings.LOGO_ON_FOLLOWING, L10n.t("Instagram logo on Home"),
                    L10n.t("Keeps Instagram's logo at the top of Home in place of the feed's name. Tap it to switch "
                            + "feeds. Restart Instagram to see the change.")));
            if (suggestions && PatchFamily.feedTypesInBuild()) {
                feed.addPreference(toggle(context, Settings.FOLLOWING_FALLBACK, L10n.t("Fall back to Following"),
                        L10n.t("When For you brings an account only suggested posts, page after page, Home opens on "
                                + "Following for that account. A feed you pick at the top of Home stays picked. Restart "
                                + "Instagram to see the change.")));
            }
        }
        if (suggestions) {
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_ACCOUNTS, L10n.t("Hide suggested accounts"),
                    L10n.t("Hides the rows of accounts, shops and hashtags Instagram suggests you follow.")));
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS, L10n.t("Hide suggested posts"),
                    L10n.t("Hides posts and reels from accounts you don't follow, marked Suggested for you. Posts "
                            + "from accounts you follow stay.")));
            feed.addPreference(toggle(context, Settings.HIDE_THREADS_POSTS, L10n.t("Hide Threads posts"),
                    L10n.t("Hides the posts, accounts and communities from Threads that Instagram mixes into your "
                            + "feed.")));
            feed.addPreference(toggle(context, Settings.HIDE_FEED_SURVEYS, L10n.t("Hide surveys"),
                    L10n.t("Hides the cards between posts that ask you to rate what you saw.")));
            feed.addPreference(toggle(context, Settings.HIDE_FEED_SHOPPING, L10n.t("Hide shopping"),
                    L10n.t("Hides the rows of products to shop and live shopping that Instagram puts between posts, "
                            + "and the shop tiles in Explore. Posts with tagged products don't show the shopping "
                            + "bag.")));
            feed.addPreference(toggle(context, Settings.HIDE_FEED_INTEREST, L10n.t("Hide 'Are you interested?' bubbles"),
                    L10n.t("Hides the bubble under a post that asks 'Are you interested in this post?' with Not "
                            + "interested and Interested buttons. Nothing is answered for you.")));
            feed.addPreference(toggle(context, Settings.HIDE_CAUGHT_UP_CARD, L10n.t("Hide the caught up card"),
                    L10n.t("Hides the card after the posts from accounts you follow that says you've seen the latest "
                            + "ones, and its Older posts link. When Home has no new posts at all, only that line goes "
                            + "and the link stays. Pull down on Home to refresh after changing it.")));
            if (PatchFamily.feedTypesInBuild()) {
                feed.addPreference(toggle(context, Settings.HIDE_FEED_VIDEOS, L10n.t("Hide videos"),
                        L10n.t("Removes every single-video post and reel from Home, even from accounts you follow. "
                                + "Pull down on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_PHOTOS, L10n.t("Hide photos"),
                        L10n.t("Removes every single-photo post from Home, even from accounts you follow. Pull down "
                                + "on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_CAROUSELS, L10n.t("Hide carousels"),
                        L10n.t("Removes every post with more than one photo or video from Home, even from accounts "
                                + "you follow. Pull down on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_LIKED, L10n.t("Hide posts you've liked"),
                        L10n.t("Removes posts you've already liked from Home and Following. A post you like now "
                                + "stays until you pull down on Home to refresh.")));
                feed.addPreference(page.hiddenAccountsRow(context));
            }
        }
        if (homeFeed) {
            feed.addPreference(toggle(context, Settings.HIDE_HOME_FEED, L10n.t("Hide the home feed"),
                    L10n.t("Empties Home on purpose, so you see the stories row and nothing under it. Profiles, "
                            + "Explore and Reels still show posts. Pull down on Home to refresh after changing it.")));
        }
        if (swipe) {
            feed.addPreference(toggle(context, Settings.STOP_SWIPE_TO_CREATE, L10n.t("Stop swipe to create"),
                    L10n.t("A sideways swipe on Home no longer opens the camera. The + button and every other way "
                            + "into the camera still work.")));
        }
        if (tabSwipe) {
            feed.addPreference(toggle(context, Settings.STOP_TAB_SWIPING, L10n.t("Stop swiping between tabs"),
                    L10n.t("A sideways swipe no longer moves you between Home, Reels and the other main tabs. "
                            + "Tap the tab bar instead. Stop swipe to create covers the camera.")));
        }
        if (fullResolution) {
            feed.addPreference(toggle(context, Settings.FULL_RESOLUTION_PHOTOS, L10n.t("Full resolution photos"),
                    L10n.t("Loads photos in your feed, carousels and opened posts at the largest size Instagram "
                            + "offers. This can use more data.")));
            feed.addPreference(toggle(context, Settings.ASK_FOR_LARGER_PHOTOS, L10n.t("Ask for larger photos"),
                    L10n.t("On a phone under 1440 pixels wide, asks Instagram for photos sized for a wider screen. "
                            + "This uses more data. Restart Instagram to see the change.")));
            feed.addPreference(toggle(context, Settings.FULL_SIZE_COVERS, L10n.t("Full size covers"),
                    L10n.t("Reel covers and grid pictures come at full size, so Download cover saves a picked "
                            + "cover at full size too. Grids use a lot more data. Restart Instagram to see the change.")));
        }
        if (askLike) {
            feed.addPreference(toggle(context, Settings.ASK_BEFORE_LIKE, L10n.t("Ask before a like"),
                    L10n.t("Asks before the Like button under a post likes or unlikes it, so a stray tap doesn't do "
                            + "it for you. Double taps aren't asked about.")));
        }
        if (askRefresh) {
            feed.addPreference(toggle(context, Settings.ASK_BEFORE_REFRESH, L10n.t("Ask before a refresh"),
                    L10n.t("Asks before pulling down refreshes Home, Reels or another list. Cancel keeps what's on "
                            + "screen.")));
        }
        if (saveTray) {
            feed.addPreference(toggle(context, Settings.SKIP_COLLECTIONS_TRAY, L10n.t("Skip the collections tray"),
                    L10n.t("Saving a post or reel shows Instagram's Saved message instead of the tray that asks you "
                            + "to start a collection.")));
        }
        if (backHome) {
            feed.addPreference(toggle(context, Settings.BACK_LEAVES_HOME, L10n.t("Back leaves Home as it is"),
                    L10n.t("Back on Home leaves Instagram without first scrolling the feed to the top and reloading "
                            + "it.")));
        }
        if (homePlace) {
            feed.addPreference(toggle(context, Settings.KEEP_HOME_PLACE, L10n.t("Keep your place in Home"),
                    L10n.t("Coming back to Instagram leaves Home on the post you were on, however long you were "
                            + "away. Pull down to refresh still reloads it.")));
        }
        if (postTime) {
            feed.addPreference(toggle(context, Settings.SHOW_POST_TIME, L10n.t("Show a post's exact time"),
                    L10n.t("Shows when a post and its comments went up, like Oct 2, 3:45 PM, instead of how long "
                            + "ago. Posts you load after a change show it.")));
        }
        if (hiddenLikes) {
            feed.addPreference(toggle(context, Settings.SHOW_HIDDEN_LIKE_COUNTS, L10n.t("Show like counts the poster hid"),
                    L10n.t("Shows how many likes a post or reel has when its owner hid the count, but only when "
                            + "Instagram still sends the number. If it doesn't, nothing changes. Posts you load "
                            + "after a change show it.")));
        }
        if (feedSound) {
            feed.addPreference(toggle(context, Settings.START_FEED_VIDEOS_WITH_SOUND, L10n.t("Start feed videos with sound"),
                    L10n.t("The first video on Home plays with its sound on, the same as tapping its speaker. Your "
                            + "phone's ringer and volume still count, so on silent or with the volume down, it stays quiet.")));
        }
        if (feedTapPause) {
            feed.addPreference(toggle(context, Settings.TAP_FEED_VIDEO_TO_PAUSE, L10n.t("Tap a feed video to pause it"),
                    L10n.t("Tap the video playing in a feed to pause it in place instead of opening it in Reels, and tap it "
                            + "again to play it. Ads keep Instagram's own tap.")));
        }
        if (feedSeekBar) {
            feed.addPreference(toggle(context, Settings.FEED_SEEK_BAR, L10n.t("Seek bar on feed videos"),
                    L10n.t("Shows a bar along the bottom of the video playing in a feed. Drag it and let go to jump "
                            + "to that point in the video.")));
            feed.addPreference(toggle(context, Settings.FEED_SEEK_BUTTON, L10n.t("Seek bar button on feed videos"),
                    L10n.t("Adds a small button at the bottom left of a feed video that shows or hides its seek bar. "
                            + "It works with Seek bar on feed videos on or off.")));
        }
        if (longPressZoom) {
            feed.addPreference(toggle(context, Settings.LONG_PRESS_TO_ZOOM, L10n.t("Long press a photo to zoom"),
                    L10n.t("Press and hold a photo in a feed to see it twice as big around your finger, and slide to "
                            + "look around. Let go to close it. While this is on, it takes the place of Instagram's own "
                            + "long press on a photo.")));
            feed.addPreference(toggle(context, Settings.LONG_PRESS_ZOOM_STAYS_OPEN, L10n.t("Keep the zoom open"),
                    L10n.t("The zoom stays up after you let go. Drag to look around, pinch to zoom from 1x to 4x, "
                            + "and tap or press Back to close it.")));
        }
        if (roundedCorners) {
            feed.addPreference(toggle(context, Settings.ROUNDED_POST_CORNERS, L10n.t("Rounded corners on posts"),
                    L10n.t("Rounds the corners of a post's photo, video and carousel pages in Home and Following. "
                            + "Posts you scroll to after a change show it. Stories, profiles and Explore stay as "
                            + "they were.")));
            feed.addPreference(onFlip(toggle(context, Settings.ROUNDED_REEL_CORNERS, L10n.t("Rounded corners on Reels"),
                    L10n.t("Rounds the corners of a reel's video in the Reels tab and wherever a reel opens full "
                            + "screen. The seek bar, captions and buttons stay whole.")), ReelCorners::switchChanged));
            feed.addPreference(onFlip(toggle(context, Settings.ROUNDED_STORY_CORNERS, L10n.t("Rounded corners on stories"),
                    L10n.t("Rounds the corners of a story's photo or video, from the tray, a profile or a highlight. "
                            + "The progress bar, names and stickers stay whole.")), StoryCorners::switchChanged));
            feed.addPreference(cornerSizeRow(context));
            feed.addPreference(onFlip(toggle(context, Settings.PEEK_CAROUSEL_PAGES, L10n.t("Peek at the next carousel page"),
                    L10n.t("A carousel post's page sits a little in from the edges, with the start of the next page "
                            + "showing at the right.")), CarouselPeek::switchChanged));
        }
        if (carouselArrows) {
            feed.addPreference(onFlip(toggle(context, Settings.CAROUSEL_ARROWS, L10n.t("Carousel arrows"),
                    L10n.t("Puts a back and a next arrow on a carousel post's photos and videos. Tap one to move a "
                            + "page without swiping.")), CarouselArrows::switchChanged));
            feed.addPreference(onFlip(toggle(context, Settings.CAROUSEL_DOTS_PILL, L10n.t("Page dots on a pill"),
                    L10n.t("Moves a carousel post's page dots onto a round pill at the bottom of its photo or video, "
                            + "with the page you're on in blue.")), CarouselArrows::switchChanged));
        }
        if (ambientLight) {
            feed.addPreference(onFlip(toggle(context, Settings.AMBIENT_LIGHT, L10n.t("Ambient light"),
                    L10n.t("Puts a soft glow in a post's own colors around its photo or video, and in the bands "
                            + "around a reel that doesn't fill the screen. The glow follows a video as it plays.")),
                    AmbientLight::switchChanged));
            feed.addPreference(ambientSpreadRow(context));
            feed.addPreference(ambientSpeedRow(context));
        }
        if (fullScreen) {
            feed.addPreference(toggle(context, Settings.FULL_SCREEN_HOME, L10n.t("Full screen Home"),
                    L10n.t("Hides the status bar and the navigation bar while Home shows. Swipe in from the edge to "
                            + "bring them back for a moment.")));
        }
        if (feedFollow) {
            feed.addPreference(onFlip(toggle(context, Settings.HIDE_FEED_FOLLOW, L10n.t("Hide Follow on posts"),
                    L10n.t("Takes the Follow and Subscribe buttons off the top of posts in your feed. The name, time and menu stay.")),
                    FeedFollowButton::switchChanged));
        }
        if (hideCaptions) {
            feed.addPreference(onFlip(toggle(context, Settings.HIDE_CAPTIONS, L10n.t("Hide captions"),
                    L10n.t("Takes the caption off posts in your feed and off reels. The post stays, and the comments sheet "
                            + "still shows it.")),
                    HideCaptions::switchChanged));
        }
        if (homeTopBar) {
            feed.addPreference(toggle(context, Settings.KEEP_HOME_TOP_BAR, L10n.t("Keep Home's top bar"),
                    L10n.t("Keeps the Instagram logo and the buttons beside it at the top of Home while you scroll, "
                            + "instead of sliding them away.")));
            feed.addPreference(toggle(context, Settings.SEE_THROUGH_HOME_TOP_BAR, L10n.t("See-through top bar"),
                    L10n.t("Shows your feed blurred under Home's top bar, like frosted glass.")));
        }
    }
}
