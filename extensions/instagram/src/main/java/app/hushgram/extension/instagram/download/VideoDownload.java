/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Download in the menu of a feed post with a video, and with its second switch, of a photo post.
 *
 * <p>Instagram's feed menu has a Download row of its own, but only on your own posts, and a tap
 * fetches a copy with a watermark. The patch adds the same row to anyone else's post and changes
 * what a tap does:
 *
 * <ul>
 *   <li>The menu's builder calls {@link #offer} where it starts on the rows for someone else's
 *       post. With the switch on, a post with a video gets Instagram's Download row there. On a
 *       carousel, that's the page on screen.
 *   <li>The short menu most of the feed opens shows only the rows whose option is on a fixed list,
 *       in the list's order. The method that makes the list hands it to {@link #allow}, which puts
 *       Download first with the switch on.
 *   <li>Your own post gets Instagram's row through {@link #ownPost} and {@link #ownPostRow}
 *       whenever a tap would save something, not only when Instagram allows its own download and
 *       keeps it in this menu rather than the share sheet.
 *   <li>The menu's handler asks {@link #save} first when Download is tapped, which saves the video
 *       from the addresses its Media already holds, through {@link MediaSave}. A post without a
 *       video goes to Instagram's own download.
 *   <li>With the photo switch on, a post or carousel page without a video gets the same row, and a
 *       tap saves its picture at the largest size, the way {@link StoryDownload} saves a photo story.
 *   <li>A carousel also has Save all. It snapshots every ordered page for one cancellable batch,
 *       respecting the photo and video switches while Download still saves the page on screen.
 *   <li>With Download video covers on, a post, or carousel page on screen, with a video gets
 *       Download cover, which saves the still picture Instagram shows before the video plays, as
 *       Download cover on a reel does (#94). It's offered after Save all, and the short menu keeps
 *       it after Download and Save all.
 *   <li>With Open in another player on, a post, or carousel page on screen, with a video file gets
 *       a row that hands the file to a player picked from Android's chooser ({@link ExternalPlayer}).
 *       It's offered next to Save all, before the builder splits into your own and others' rows,
 *       and the short menu keeps it after Download, Save all and Download cover.
 *   <li>With Details on, every post gets a Details row there too ({@link PostInfo}), kept last of
 *       these in the short menu.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with the switch
 * off, or when something throws, the menu is Instagram's own.
 */
public final class VideoDownload {
    private VideoDownload() {
    }

    /** The source a feed video save's lines carry in the diagnostic report. */
    private static final String SOURCE = "VideoDownload";

    /** Process-only identity, never entered in Instagram's native enum arrays. */
    private static Object batchOption;

    /** Open in another player's option, made once, as Save all's is. */
    private static Object playerOption;

    /** Download cover's option, made once, as Save all's is. */
    private static Object coverOption;

    public static synchronized Object coverOption() {
        try {
            if (coverOption == null) coverOption = InstagramMedia.feedOption(ReelDownload.COVER_OPTION);
            return coverOption;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover option", failure);
            return null;
        }
    }

    /**
     * Adds Download cover to [rows], the list the feed menu's builder [menu] fills, when Download
     * feed videos and Download video covers are on and the post, or the carousel page on screen,
     * has a video and a picture to save as its cover. Called beside {@link #offerAll}, so your own
     * posts get it too. Never throws.
     */
    public static void offerCover(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null || !covers()) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            Object shown = shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu));
            if (!hasVideo(shown) || StoryDownload.pictures(shown).isEmpty()) return;
            Object option = coverOption();
            if (option != null) InstagramMedia.addSaveAllRow(menu, rows, option, L10n.t("Download cover"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover row", failure);
        }
    }

    /**
     * Saves the cover of the post [media], or of the carousel page on screen that [itemState] names,
     * when its Download cover row is tapped: the still picture shown before the video plays, at the
     * largest size it lists. The sizes are all of that one picture, so it goes by
     * {@link MediaSave#savePictureBySize}, as a reel's cover does (#79). [activity] is the one the
     * menu belongs to. A save that can't start says so. Never throws.
     */
    public static void saveCover(Object media, Object itemState, Activity activity) {
        Context context = activity != null ? activity : Utils.getContext();
        try {
            if (!covers()) return;
            Object shown = shown(media, itemState);
            List<MediaSave.Rendition> pictures = shown == null ? new ArrayList<>() : StoryDownload.pictures(shown);
            final int sizes = pictures.size();
            final String on = shown != media ? " on a carousel page" : "";
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "feed cover download tapped" + on + ": " + sizes + " picture size(s)");
            if (sizes == 0 || !MediaSave.savePictureBySize(context, pictures, details(shown, media))) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed cover save", failure);
            if (context != null) Feedback.show(context.getApplicationContext(), L10n.t(context, "Download failed"), true);
        }
    }

    public static synchronized Object playerOption() {
        try {
            if (playerOption == null) playerOption = InstagramMedia.feedOption(ExternalPlayer.OPTION);
            return playerOption;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed player option", failure);
            return null;
        }
    }

    /**
     * Adds Open in another player to [rows], the list the feed menu's builder [menu] fills, when
     * the switch is on and the post, or the carousel page on screen, has a video file a player can
     * open. Called beside {@link #offerAll}, so your own posts get it too. Never throws.
     */
    public static void offerPlayer(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null || !ExternalPlayer.on()) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            Object shown = shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu));
            if (!ExternalPlayer.offers(shown)) return;
            Object option = playerOption();
            if (option != null) InstagramMedia.addSaveAllRow(menu, rows, option, L10n.t("Open in another player"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed player row", failure);
        }
    }

    /**
     * Hands the video of [media], or of the carousel page on screen that [itemState] names, to a
     * player, when its Open in another player row is tapped. [activity] is the one the menu belongs
     * to. Never throws.
     */
    public static void play(Object media, Object itemState, Activity activity) {
        Context context = activity != null ? activity : Utils.getContext();
        try {
            ExternalPlayer.open(context, shown(media, itemState), FamilyNames.VIDEO_DOWNLOAD);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed player tap", failure);
        }
    }

    public static synchronized Object allOption() {
        try {
            if (batchOption == null) batchOption = InstagramMedia.saveAllOption();
            return batchOption;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "carousel menu option", failure);
            return null;
        }
    }

    /**
     * A carousel gets its own action before the builder splits into your own and others' rows,
     * when at least one page would save with the switches as they are.
     */
    public static void offerAll(Object menu, ArrayList<?> rows) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (menu == null || rows == null || !videos() && !photos()) return;
            Object post = InstagramMedia.feedMenuMedia(menu);
            List<?> pages = post == null ? null : InstagramMedia.carouselMedia(post);
            if (pages == null || pages.size() < 2 || !anySaves(pages)) return;
            Object option = allOption();
            if (option != null) InstagramMedia.addSaveAllRow(menu, rows, option, L10n.t("Save all"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "carousel menu", failure);
        }
    }

    /** Takes plain values from every ordered page on the tap; the worker holds no host object. */
    public static void saveAll(Object post, Activity activity) {
        Context context = activity != null ? activity : Utils.getContext();
        try {
            boolean videos = videos(), photos = photos();
            if (!videos && !photos) return;
            List<?> pages = post == null ? null : InstagramMedia.carouselMedia(post);
            if (pages == null || pages.size() < 2) return;
            if (pages.size() > MediaSave.MAX_BATCH_PAGES) {
                Feedback.show(context, L10n.f(context, "Not saved: a carousel can have at most %1$d pages",
                        MediaSave.MAX_BATCH_PAGES), true);
                return;
            }
            List<?> ordered = new ArrayList<>(pages);
            if (ordered.size() > MediaSave.MAX_BATCH_PAGES) {
                Feedback.show(context, L10n.f(context, "Not saved: a carousel can have at most %1$d pages",
                        MediaSave.MAX_BATCH_PAGES), true);
                return;
            }
            List<MediaSave.Item> snapshot = snapshot(ordered, post, videos, photos, FamilyNames.VIDEO_DOWNLOAD);
            if (!MediaSave.saveBatch(context, snapshot, null)) Feedback.show(context, L10n.t(context, "Download failed"), true);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "carousel save", failure);
            Feedback.show(context, L10n.t(context, "Download failed"), true);
        }
    }

    /**
     * Plain values from each of [pages], [post]'s carousel pages in order: a page with a video when
     * [videos], a page with only a picture when [photos], and null for a page left out. A page that
     * can't be read is counted under [family] and fails once on the worker.
     */
    static List<MediaSave.Item> snapshot(List<?> pages, Object post, boolean videos, boolean photos, String family) {
        List<MediaSave.Item> snapshot = new ArrayList<>(pages.size());
        for (Object page : pages) {
            try {
                if (page == null) { snapshot.add(null); continue; }
                List<MediaSave.Rendition> renditions = ReelDownload.renditions(page);
                String manifest = InstagramMedia.dashManifest(page);
                boolean video = !renditions.isEmpty() || manifest != null;
                if (video) snapshot.add(videos ? new MediaSave.Item(true, renditions, manifest, details(page, post)) : null);
                else snapshot.add(photos ? new MediaSave.Item(false, StoryDownload.pictures(page), null,
                        details(page, post)) : null);
            } catch (Throwable failure) {
                HookStatus.threw(family, "carousel page snapshot", failure);
                // An unreadable page fails once on the worker rather than silently disappearing.
                snapshot.add(new MediaSave.Item(true, null, null, null));
            }
        }
        return snapshot;
    }

    /**
     * Adds the Download row to [rows], the list the feed menu's builder [menu] fills for someone
     * else's post, when the post, or the carousel page on screen, has something a tap would save:
     * a video with the video switch on, or a picture and no video with the photo switch on. Never
     * throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (menu == null || rows == null || !videos() && !photos()) return;
            if (what(shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu))) == Save.NONE) return;
            InstagramMedia.addDownloadRow(menu, rows);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu", t);
        }
    }

    /**
     * Injected where the feed menu's builder asks Instagram whether your own post may be
     * downloaded, which [eligible] answers. Instagram adds its Download row to your own post only
     * on a yes, and a photo, or a video it doesn't allow downloads of, never gets one (#57).
     * Answers 1 when the post, or the carousel page on screen, has something a tap would save
     * with the switches as they are, and [eligible] otherwise. A tap then goes to {@link #save}.
     * Never throws.
     */
    public static int ownPost(int eligible, Object menu) {
        if (eligible != 0) return eligible;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (menu == null || !videos() && !photos()) return eligible;
            return own(eligible, what(shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu))));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "own post menu", t);
            return eligible;
        }
    }

    /** Instagram's answer [eligible] for your own post, or a yes when a tap would save [what]. */
    static int own(int eligible, Save what) {
        return eligible != 0 || what == Save.NONE ? eligible : 1;
    }

    /**
     * Called on your own post's rows past the download check with [instagrams], the menu state's
     * flag that, together with a server flag, moves Instagram's Download from this menu to the
     * share sheet (#57). Instagram adds the row here only on a 0, so this answers 0 when the post,
     * or the carousel page on screen, has something a tap would save, and [instagrams] otherwise.
     * Never throws.
     */
    public static int ownPostRow(int instagrams, Object menu) {
        if (instagrams == 0) return instagrams;
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (menu == null || !videos() && !photos()) return instagrams;
            return row(instagrams, what(shown(InstagramMedia.feedMenuMedia(menu), InstagramMedia.feedMenuItemState(menu))));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "own post row", t);
            return instagrams;
        }
    }

    /** Instagram's share sheet flag [instagrams] for your own post, or 0, which keeps the row in the menu, when a tap would save [what]. */
    static int row(int instagrams, Save what) {
        return what == Save.NONE ? instagrams : 0;
    }

    /**
     * Answers [options], the options the short feed menu keeps, with [download], Instagram's
     * Download option, in front when the switch is on. The menu keeps a row only when its option is
     * on this list and orders the rows by it, so without this the row {@link #offer} added never
     * shows there. Save all follows Download, keeping native options in their existing order, then
     * Download cover with its switch on. With their own switches on, Open in another player follows
     * them, then Details ({@link PostInfo}). A list with every action or the switches off comes back
     * as it came. Never throws.
     */
    public static List<?> allow(List<?> options, Object download) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (options == null) return options;
            List<?> allowed = download == null || !videos() && !photos() ? options : withSaves(options, download);
            if (covers()) allowed = withCover(allowed, download);
            if (ExternalPlayer.on()) allowed = withPlayer(allowed, download);
            return PostInfo.on() ? PostInfo.withDetails(allowed, download, batchOption, coverOption, playerOption) : allowed;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "short feed menu", t);
            return options;
        }
    }

    /** [options] with Download in front and Save all after it, each when it isn't there yet. */
    private static List<?> withSaves(List<?> options, Object download) {
        Object all = allOption();
        if (options.contains(download) && (all == null || options.contains(all))) return options;
        List<Object> allowed = new ArrayList<>(options.size() + 2);
        if (!options.contains(download)) allowed.add(download);
        allowed.addAll(options);
        if (all != null && !options.contains(all)) allowed.add(allowed.indexOf(download) + 1, all);
        return allowed;
    }

    /**
     * [options] with Download cover after Save all, or after Download when Save all isn't there, or
     * in front when neither is. A list that has it already comes back as it came.
     */
    static synchronized List<?> withCover(List<?> options, Object download) {
        Object cover = coverOption();
        if (cover == null || options.contains(cover)) return options;
        List<Object> allowed = new ArrayList<>(options);
        allowed.add(after(allowed, batchOption, download) + 1, cover);
        return allowed;
    }

    /**
     * [options] with Open in another player after Download cover, or when that isn't there after
     * Save all, or after Download, or in front when none is. A list that has it already comes back
     * as it came.
     */
    static synchronized List<?> withPlayer(List<?> options, Object download) {
        Object player = playerOption();
        if (player == null || options.contains(player)) return options;
        List<Object> allowed = new ArrayList<>(options);
        allowed.add(after(allowed, coverOption, batchOption, download) + 1, player);
        return allowed;
    }

    /** Where the first of [rows] that [options] has sits in it, or -1 when it has none. */
    private static int after(List<?> options, Object... rows) {
        for (Object row : rows) {
            if (row != null && options.contains(row)) return options.indexOf(row);
        }
        return -1;
    }

    /**
     * Saves the post [media], or the carousel page on screen that [itemState], the post's feed
     * state, names, when its Download row is tapped: its video with the video switch on, or with
     * the photo switch on its picture when it has no video. Answers whether it did, in which case
     * Instagram's own download is skipped. Anything else, your own photo with the photo switch off
     * for one, goes to Instagram's own download. [activity] is the one the menu belongs to. A save
     * that can't start says so. Never throws.
     */
    public static boolean save(Object media, Object itemState, Activity activity) {
        try {
            if (!videos() && !photos()) return false;
            Object shown = shown(media, itemState);
            Save what = what(shown);
            if (what == Save.NONE) return false;
            Context context = activity != null ? activity : Utils.getContext();
            if (ExternalDownload.handOff(context, ExternalDownload.postLink(media, false), FamilyNames.VIDEO_DOWNLOAD)) {
                return true;
            }
            PostDetails details = details(shown, media);
            final String on = shown != media ? " on a carousel page" : "";
            boolean started;
            if (what == Save.VIDEO) {
                List<MediaSave.Rendition> renditions = ReelDownload.renditions(shown);
                String manifest = InstagramMedia.dashManifest(shown);
                final int files = renditions.size();
                final boolean dash = manifest != null;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "feed video download tapped" + on
                        + ": " + files + " file(s)" + (dash ? " and a manifest" : ", no manifest"));
                started = MediaSave.saveVideo(context, renditions, manifest, details);
            } else {
                List<MediaSave.Rendition> pictures = StoryDownload.pictures(shown);
                final int sizes = pictures.size();
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "feed photo download tapped" + on + ": " + sizes + " size(s)");
                started = MediaSave.savePhoto(context, pictures, details);
            }
            if (!started) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
            return true;
        } catch (Throwable t) {
            // It runs inside Instagram's click dispatch, where a throw ends the app. Instagram's own
            // download goes ahead instead.
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu", t);
            return false;
        }
    }

    /**
     * What's on screen of [post]: the post itself, or for a carousel the page its feed state
     * [itemState] says is showing. Null for a carousel whose page isn't known.
     */
    static Object shown(Object post, Object itemState) {
        List<?> pages = post == null ? null : InstagramMedia.carouselMedia(post);
        if (pages == null || pages.isEmpty()) return post;
        return page(pages, itemState == null ? -1 : InstagramMedia.carouselIndex(itemState));
    }

    /** Page [index] of a carousel's [pages], or null when there's no such page. */
    static Object page(List<?> pages, int index) {
        return index >= 0 && index < pages.size() ? pages.get(index) : null;
    }

    /**
     * The file name's details for [shown], a carousel page of [post] or the post itself. A page
     * keeps its own id, takes the poster and the day from the post when it doesn't list them, and
     * knows its number among the post's pages.
     */
    static PostDetails details(Object shown, Object post) {
        if (shown == post) return ReelDownload.details(post);
        Object user = InstagramMedia.owner(shown);
        if (user == null) user = InstagramMedia.owner(post);
        Long takenAt = InstagramMedia.takenAt(shown);
        if (takenAt == null || takenAt <= 0) takenAt = InstagramMedia.takenAt(post);
        String id = InstagramMedia.mediaId(shown);
        return PostDetails.of(id != null ? id : InstagramMedia.mediaId(post), user == null ? null : InstagramMedia.username(user),
                takenAt == null || takenAt <= 0 ? null : new Date(takenAt * 1000L)).onPage(pageOf(shown, post));
    }

    /** Which page of [post]'s carousel [shown] is, counted from 1, or 0 when it isn't one of them. */
    static int pageOf(Object shown, Object post) {
        List<?> pages = InstagramMedia.carouselMedia(post);
        if (pages == null) return 0;
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) == shown) return index + 1;
        }
        return 0;
    }

    /** Whether [media] lists a video: single files or a DASH manifest. */
    static boolean hasVideo(Object media) {
        return media != null && (!ReelDownload.renditions(media).isEmpty() || InstagramMedia.dashManifest(media) != null);
    }

    /** What a tap on Download saves of [media]. */
    enum Save { VIDEO, PHOTO, NONE }

    /**
     * Whether the feed's Download button has something to save on [post] with the switches as they
     * are: on a carousel any page, as {@link #offerAll} offers Save all, and otherwise the post itself,
     * as {@link #offer} offers Download. A photo with Download feed photos off has nothing (#97).
     * Never throws: a post that can't be read has nothing.
     */
    static boolean saves(Object post) {
        try {
            if (post == null || !videos() && !photos()) return false;
            List<?> pages = InstagramMedia.carouselMedia(post);
            if (pages != null && !pages.isEmpty()) return anySaves(pages);
            return what(post) != Save.NONE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed button post check", failure);
            return false;
        }
    }

    /** Whether Save all would save any of [pages]: an all-photo carousel with the photo switch off saves nothing. */
    static boolean anySaves(List<?> pages) {
        for (Object page : pages) {
            if (what(page) != Save.NONE) return true;
        }
        return false;
    }

    /** What a tap on Download saves of [media], a post or a carousel page, with the switches as they are. */
    static Save what(Object media) {
        if (media == null) return Save.NONE;
        boolean video = hasVideo(media);
        return what(video, !video && !StoryDownload.pictures(media).isEmpty(), videos(), photos());
    }

    /**
     * What a tap saves of a post with a video [video] or a picture [picture], with the video
     * switch [videos] and the photo switch [photos]. Every video has a picture too, its cover, so a
     * post with a video never saves as a photo.
     */
    static Save what(boolean video, boolean picture, boolean videos, boolean photos) {
        if (video) return videos ? Save.VIDEO : Save.NONE;
        return picture && photos ? Save.PHOTO : Save.NONE;
    }

    private static boolean videos() {
        return on(Settings.DOWNLOAD_VIDEOS);
    }

    private static boolean photos() {
        return on(Settings.DOWNLOAD_PHOTOS);
    }

    /** Whether a post with a video gets Download cover: Download feed videos and Download video covers both on. */
    static boolean covers() {
        return videos() && on(Settings.DOWNLOAD_FEED_COVER);
    }

    private static boolean on(BooleanSetting setting) {
        try {
            return Utils.settingsReady() && setting.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed menu switch", t);
            return false;
        }
    }
}
