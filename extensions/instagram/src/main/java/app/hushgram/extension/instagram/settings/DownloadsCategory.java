/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.CLEAR_DOWNLOAD_HISTORY;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearDownloadHistory;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.fileNameRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.folderRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.holdFingersRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.holdTimeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.qualityRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.reelDownloadButtonRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;

/** Builds the Downloads category of HushGram settings. */
final class DownloadsCategory {
    private DownloadsCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        // Any download patch brings this section, so each one that saves joins this condition.
        if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.STORY_DOWNLOAD)
                || build.contains(PatchFamily.VIDEO_DOWNLOAD) || build.contains(PatchFamily.PROFILE_PICTURE)
                || build.contains(PatchFamily.VOICE_MESSAGE)) {
            PreferenceCategory downloads = category(screen, L10n.t("Downloads"), SettingsIcons.DOWNLOADS,
                    L10n.t("Save photos, videos and more, and choose where they go."));
            page.downloads = downloads;
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VIDEOS, L10n.t("Download feed videos"),
                        L10n.t("Adds Download to the menu of a post in your feed with a video. Uses the quality below. "
                                + "Off or paused, Instagram's own menu returns.")));
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_FEED_COVER, L10n.t("Download video covers"),
                        L10n.t("Adds Download cover to the menu of a feed post with a video, and of a carousel showing one. "
                                + "It saves the still picture shown before the video plays, at its largest size.")));
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_PHOTOS, L10n.t("Download feed photos"),
                        L10n.t("The same Download on a photo post, and on a carousel showing a photo. Saves the largest "
                                + "size Instagram has.")));
                downloads.addPreference(toggle(context, Settings.POST_DETAILS, L10n.t("Details in a post's menu"),
                        L10n.t("Adds Details to a feed post's or reel's menu. It shows when it went up, who posted it, "
                                + "its media ID and the size Download saves, with buttons to copy the file link, "
                                + "username and caption.")));
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_CHOOSE_PAGES, L10n.t("Choose carousel pages"),
                        L10n.t("Adds Choose pages to a carousel's menu, after Save all, and to the download icon. "
                                + "Tick the pages you want and only those save, in order.")));
                downloads.addPreference(toggle(context, Settings.FEED_DOWNLOAD_BUTTON, L10n.t("Download button on feed posts"),
                        L10n.t("Puts a download icon in the row under a post, left of Save, so one tap saves it without "
                                + "opening the menu. On a carousel it asks for this page or all of them.")));
                downloads.addPreference(toggle(context, Settings.FEED_DOWNLOAD_ALL_PAGES, L10n.t("Save every page without asking"),
                        L10n.t("On a carousel, the download icon saves all of its pages at once instead of asking.")));
                if (build.contains(PatchFamily.REEL_DOWNLOAD)) downloads.addPreference(reelDownloadButtonRow(context));
                downloads.addPreference(toggle(context, Settings.HOLD_TO_DOWNLOAD, L10n.t("Hold to download"),
                        L10n.t("Hold two fingers still on a feed post's picture or video, or on a reel, and it saves what's "
                                + "under them, the same as Download in the menu. Instagram's one-finger long press stays as "
                                + "it was. Needs Download feed videos or photos, and Download on reels for reels.")));
                downloads.addPreference(holdFingersRow(context));
                downloads.addPreference(holdTimeRow(context));
            }
            if (build.contains(PatchFamily.REEL_DOWNLOAD) && !build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(reelDownloadButtonRow(context));
            }
            if (build.contains(PatchFamily.PROFILE_PICTURE)) {
                downloads.addPreference(toggle(context, Settings.SAVE_PROFILE_PICTURES, L10n.t("Save profile picture"),
                        L10n.t("Adds Save profile picture to the menu on someone's profile. Saves their picture at the "
                                + "largest size Instagram has.")));
                downloads.addPreference(toggle(context, Settings.VIEW_PROFILE_PICTURES, L10n.t("View profile picture"),
                        L10n.t("Adds View profile picture to the menu on someone's profile. Opens their picture full "
                                + "screen at the largest size Instagram has, with pinch zoom and a Save button.")));
                downloads.addPreference(toggle(context, Settings.COPY_PROFILE_TEXT, L10n.t("Copy username and bio"),
                        L10n.t("Adds Copy username and Copy bio to the menu on someone's profile. Each copies the "
                                + "text exactly as the account has it.")));
                downloads.addPreference(toggle(context, Settings.PROFILE_EXTRAS_BUTTON, L10n.t("Profile extras button"),
                        L10n.t("Puts a button next to the three dots on profiles that opens HushGram's profile actions. "
                                + "The rows in the three-dot menu stay too.")));
            }
            if (build.contains(PatchFamily.VOICE_MESSAGE)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VOICE_MESSAGES, L10n.t("Download voice messages"),
                        L10n.t("Adds Save to the menu you get by holding a voice message in a chat. Saves the recording "
                                + "as an audio file. One sent to be played once never gets it.")));
            }
            if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.OPEN_IN_PLAYER, L10n.t("Open in another player"),
                        L10n.t("Adds a row next to Download on a reel and a feed video that plays it in an app you "
                                + "pick, such as VLC. The player streams it from Instagram's servers.")));
            }
            downloads.addPreference(toggle(context, Settings.SEND_DOWNLOADS_TO_APP, L10n.t("Send downloads to another app"),
                    L10n.t("Download on a reel, a feed post or a story opens the share sheet with its link, for a "
                            + "downloader app such as Seal. Save all and the other save rows still save here.")));
            // Every save reads it, whichever download patch started it, so it's here above the
            // quality it keeps within.
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_COMPATIBLE, L10n.t("Save videos other apps can open"),
                    L10n.t("For WhatsApp, video editors such as CapCut and InShot, or a gallery or player that plays saves "
                            + "without sound. May lower quality.")));
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_CONVERT, L10n.t("Convert to keep the full size"),
                    L10n.t("Saves videos other apps can open, at full size. A video Instagram only has as VP9 or AV1 "
                            + "is converted to H.264 on your phone instead of saved smaller. Slower, and videos over "
                            + "3 minutes stay smaller.")));
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_NO_AV1, L10n.t("Never save AV1 video"),
                    L10n.t("For phones that play AV1 badly. A video Instagram has at full size only as AV1 is saved "
                            + "as VP9 when it has one, or at its largest H.264 size. Convert to keep the full size "
                            + "then converts only VP9.")));
            downloads.addPreference(qualityRow(context));
            downloads.addPreference(folderRow(context));
            downloads.addPreference(fileNameRow(context));
            downloads.addPreference(toggle(context, Settings.SAVE_FOLDER_PER_ACCOUNT, L10n.t("Folder per account"),
                    L10n.t("Each save goes into a folder named for the account that posted it, inside the save folder. "
                            + "A save that doesn't know who posted stays in the save folder.")));
            downloads.addPreference(toggle(context, Settings.SAVE_NAME_BY_POST, L10n.t("Name saves by account and post time"),
                    L10n.t("Names each photo and video for the account that posted it and when, like "
                            + "username_20261005_143012, so an account's saves sort by date. A carousel page gets its "
                            + "number on the end. Takes the place of the video file name. A save that doesn't know who "
                            + "posted or when keeps its usual name.")));
            downloads.addPreference(toggle(context, Settings.SAVE_PHOTO_METADATA, L10n.t("Add the source to saved photos"),
                    L10n.t("Writes the account that posted, the post date and a link to the post into a saved photo's "
                            + "details, the EXIF a gallery or exiftool reads. JPEG photos only, and only what the save "
                            + "knows. The picture itself is unchanged.")));
            // Only the saves of a post, a reel or a story know the media's id, which the list keeps.
            if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.STORY_DOWNLOAD)
                    || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_SKIP_SAVED, L10n.t("Skip what's already saved"),
                        L10n.t("Remembers what you save while it's on, so a post, carousel page, reel or story isn't "
                                + "saved twice. Save all skips the pages you already have. Download again within "
                                + "30 seconds to save another copy. A file you deleted or moved saves again.")));
                Row clearHistory = new Row(context);
                clearHistory.setKey(CLEAR_DOWNLOAD_HISTORY);
                clearHistory.setPersistent(false);
                clearHistory.actsAtOnce = true;
                clearHistory.setTitle(L10n.t("Clear download history"));
                clearHistory.setSummary(L10n.t("Forgets everything Skip what's already saved remembers, for all your "
                        + "accounts. Your saved files stay."));
                clearHistory.setOnPreferenceClickListener(p -> {
                    clearDownloadHistory(p);
                    return true;
                });
                downloads.addPreference(mark(clearHistory, SettingsIcons.DELETE));
            }
            // Where every save goes, last, since the rows above it keep their places.
            downloads.addPreference(page.saveTreeRow(context));
            downloads.addPreference(page.galleryFoldersRow(context));
        }
    }
}
