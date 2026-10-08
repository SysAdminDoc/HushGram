/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download cover in the menu of a feed post or a profile post with a video.
 *
 * <p>Reels and a profile's Reels tab already get this row from {@link ReelDownload}, under the same
 * Download cover switch. This adds it to the two places that were left out: a video in the home feed
 * and a video in a profile's posts grid, the latter opened from the grid or from the post itself. A
 * carousel's page on screen counts as its own post here, the way Download does, so a carousel's
 * video gets its own cover row for whichever page is showing.
 *
 * <p>The feed menu and the Reels menu are separate in Instagram, so this can't reuse the Reels row:
 * the Reels sheet is filled through its own helper's adder of one row, while the feed menu builds a
 * list of options and both filters and orders its rows by a second list this patch answers. So the
 * row is added twice over, exactly the way {@link PostInfo}'s Details row is: once to the list the
 * builder fills, and once to the list of options the short menu keeps, or the row would be built and
 * then dropped.
 *
 * <p>Stories are not in either menu here, and stay with {@link StoryDownload}'s own rows. A post
 * with no video gets no row: its picture is what Download already saves, under the photo switch.
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with the switch
 * off, or when something throws, the menu is Instagram's own.
 */
public final class CoverDownload {
    /** The name of the row's option, made once, as Save all's and Details' are. */
    static final String OPTION = "HUSHGRAM_DOWNLOAD_FEED_COVER";

    /** The source a feed cover save's lines carry in the diagnostic report. */
    private static final String SOURCE = "CoverDownload";

    /** What the menu found for a post's cover, and what a tap on Download cover started. */
    static final String HAS_COVER = "has cover";
    static final String SAVED_COVER = "saved cover";

    private static Object option;

    private CoverDownload() {
    }

    /** The row's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object option() {
        try {
            if (option == null) option = InstagramMedia.feedOption(OPTION);
            return option;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover option", failure);
            return null;
        }
    }

    /**
     * Whether Download cover is on, which a pause answers off. The same switch Reels uses, so one
     * answer covers every video's menu.
     */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_REEL_COVER.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover switch", failure);
            return false;
        }
    }

    /**
     * Adds Download cover to [rows], the list the feed menu's builder [menu] fills, when the switch
     * is on and the post, or the carousel page on screen, is a video that lists a picture to save as
     * its cover. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null || !on()) return;
            Object shown = VideoDownload.shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu));
            if (!VideoDownload.hasVideo(shown) || StoryDownload.pictures(shown).isEmpty()) return;
            Object row = option();
            if (row == null) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, HAS_COVER);
            InstagramMedia.addSaveAllRow(menu, rows, row, L10n.t("Download cover"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover row", failure);
        }
    }

    /**
     * [options], the options the short feed menu keeps, with Download cover after Download, and after
     * Save all when that is there too, so it reads under the Download row it belongs to. A list that
     * has it already comes back as it came, and one without Download keeps its own order.
     */
    static List<?> withCover(List<?> options, Object download) {
        Object cover = option();
        if (cover == null || options.contains(cover)) return options;
        Object all = VideoDownload.allOption();
        List<Object> allowed = new ArrayList<>(options);
        int after = all != null && allowed.contains(all) ? allowed.indexOf(all)
                : download != null ? allowed.indexOf(download) : -1;
        allowed.add(after + 1, cover);
        return allowed;
    }

    /**
     * Saves the cover of the post [media], or of the carousel page on screen that [itemState], the
     * post's feed state, names, when its Download cover row is tapped. [activity] is the one the menu
     * belongs to. A save that can't start says so. Never throws.
     */
    public static void save(Object media, Object itemState, Activity activity) {
        try {
            if (!on() || media == null) return;
            Object shown = VideoDownload.shown(media, itemState);
            if (!VideoDownload.hasVideo(shown)) return;
            Context context = activity != null ? activity : Utils.getContext();
            if (ExternalDownload.handOff(context, ExternalDownload.postLink(media, false), FamilyNames.VIDEO_DOWNLOAD)) {
                return;
            }
            if (!saveCover(context, shown, media)) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
        } catch (Throwable t) {
            // It runs inside Instagram's click dispatch, where a throw ends the app.
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover menu", t);
        }
    }

    /**
     * Starts the save of [shown]'s cover, the still picture Instagram shows before the video plays,
     * and answers whether it started. The sizes are all of that one picture, so the largest the post
     * states goes, by {@link MediaSave#savePictureBySize}: a cover's address often names its size,
     * like {@code p540x540}, which the plain photo save takes for a thumbnail's and turns down (#79).
     */
    private static boolean saveCover(Context context, Object shown, Object post) {
        List<MediaSave.Rendition> pictures = StoryDownload.pictures(shown);
        final int sizes = pictures.size();
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "feed cover tapped: " + sizes + " picture size(s)");
        if (sizes == 0) return false;
        boolean started = MediaSave.savePictureBySize(context, pictures, VideoDownload.details(shown, post));
        if (started) HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, SAVED_COVER);
        return started;
    }
}
