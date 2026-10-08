/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import java.util.ArrayList;
import java.util.List;

/**
 * Instagram's media model, read for a save. Instagram's getters have shortened names that change
 * with every release, so none is named here: each method below answers null as built, and the
 * download patch writes its body at patch time, as a call to the getter it finds by the field it
 * reads. A method whose body wasn't written answers null, and a save then has nothing to go on and
 * says so.
 *
 * <p>Every argument is Instagram's own object, handed over as an Object, of the type the method
 * names. The written bodies cast it, so a wrong type throws, and the callers catch that.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class InstagramMedia {
    private InstagramMedia() {
    }

    /** A Media's {@code video_versions}: the single MP4 files Instagram lists for its video. */
    public static List<?> videoVersions(Object media) {
        return null;
    }

    /** A Media's {@code video_dash_manifest}: the DASH manifest its player streams, as text. */
    public static String dashManifest(Object media) {
        return null;
    }

    /** A Media's id, {@code <media pk>_<owner's pk>}. */
    public static String mediaId(Object media) {
        return null;
    }

    /** A Media's {@code user}: who posted it. */
    public static Object owner(Object media) {
        return null;
    }

    /** A Media's {@code taken_at}: when it was posted, in seconds since 1970. */
    public static Long takenAt(Object media) {
        return null;
    }

    /** A Media's {@code caption}: a comment, the post's own words, or null when it has none. */
    public static Object caption(Object media) {
        return null;
    }

    /** A caption's {@code text}, as its poster wrote it. */
    public static String captionText(Object caption) {
        return null;
    }

    /** A User's {@code username}. */
    public static String username(Object user) {
        return null;
    }

    /** A User's {@code biography}, as its owner wrote it. */
    public static String biography(Object user) {
        return null;
    }

    /** A video version's {@code url}. */
    public static String versionUrl(Object version) {
        return null;
    }

    /** A video version's {@code width}, in pixels. */
    public static Integer versionWidth(Object version) {
        return null;
    }

    /** A video version's {@code height}, in pixels. */
    public static Integer versionHeight(Object version) {
        return null;
    }

    /** A Media's {@code carousel_media}: the pages of a carousel post, in order, or null for any other post. */
    public static List<?> carouselMedia(Object media) {
        return null;
    }

    /**
     * A Media's {@code is_story_image_with_music}: true for a photo story with music, which
     * Instagram serves as a video next to the photo's own sizes.
     */
    public static Boolean storyImageWithMusic(Object media) {
        return null;
    }

    /** A Media's {@code image_versions2}: the sizes Instagram lists for its picture. */
    public static Object imageVersions(Object media) {
        return null;
    }

    /**
     * The sizes Instagram's feed photo picker reads for a Media, through the helper it asks. Under
     * two of Instagram's server flags they're a carousel page's rather than the post's own
     * {@code image_versions2}. Written by the Full resolution photos patch alone.
     */
    public static Object pickerImageVersions(Object media) {
        return null;
    }

    /** The {@code candidates} of a picture's sizes, one per size. */
    public static List<?> imageCandidates(Object imageVersions) {
        return null;
    }

    /** A candidate's address. */
    public static String candidateUrl(Object candidate) {
        return null;
    }

    /** A candidate's width in pixels, or 0 as built. */
    public static int candidateWidth(Object candidate) {
        return 0;
    }

    /** A candidate's height in pixels, or 0 as built. */
    public static int candidateHeight(Object candidate) {
        return 0;
    }

    /** A User's {@code profile_pic_url}: the picture the profile shows, one size, read as a candidate. */
    public static Object profilePicture(Object user) {
        return null;
    }

    /** A User's {@code hd_profile_pic_url_info}: the picture's full size, when Instagram has it. */
    public static Object fullSizeProfilePicture(Object user) {
        return null;
    }

    /** The full size picture's address. */
    public static String profilePictureUrl(Object info) {
        return null;
    }

    /** The full size picture's width in pixels, or 0 as built. */
    public static int profilePictureWidth(Object info) {
        return 0;
    }

    /** The full size picture's height in pixels, or 0 as built. */
    public static int profilePictureHeight(Object info) {
        return 0;
    }

    /**
     * A Media's {@code music_metadata}, where a photo post keeps its music. Its type is one of
     * Instagram's with a single getter of the music, which {@link #metadataMusic} calls.
     */
    public static Object musicMetadata(Object media) {
        return null;
    }

    /** The {@code music_info} of a Media's {@code music_metadata}. */
    public static Object metadataMusic(Object metadata) {
        return null;
    }

    /** A Media's {@code clips_metadata}, where a reel keeps its music. */
    public static Object clipsMetadata(Object media) {
        return null;
    }

    /** The {@code music_info} of a Media's {@code clips_metadata}. */
    public static Object clipsMusic(Object metadata) {
        return null;
    }

    /** A music info's {@code music_asset_info}: the track itself. */
    public static Object musicTrack(Object music) {
        return null;
    }

    /** A music info's {@code music_consumption_info}: which part of the track the post plays. */
    public static Object musicConsumption(Object music) {
        return null;
    }

    /** A track's {@code progressive_download_url}: the whole track as one file. */
    public static String trackUrl(Object track) {
        return null;
    }

    /** A track's {@code fast_start_progressive_download_url}: the same file, laid out to play sooner. */
    public static String trackFastStartUrl(Object track) {
        return null;
    }

    /** A consumption info's {@code audio_asset_start_time_in_ms}: where in the track the post's music starts. */
    public static Integer musicStartMs(Object consumption) {
        return null;
    }

    /** A consumption info's {@code overlap_duration_in_ms}: how long the post's music plays. */
    public static Integer musicLengthMs(Object consumption) {
        return null;
    }

    /**
     * A new option of the reel menu named [name], drawn and handled like Download: Download's icon
     * and ordinal, made by the option's own constructor. Null as built.
     */
    public static Object reelOption(String name) {
        return null;
    }

    /**
     * Adds a row for [option] labeled [label] to [sheet], the reel menu's sheet, through the menu
     * helper [menu]'s adder of one row, the way Instagram adds a row with a label of its own.
     * [context] and [rowState] are what the menu hands that adder. Answers whether the row went in,
     * which as built it never does.
     */
    public static boolean addReelRow(Object menu, Object context, Object option, Object sheet, Object rowState,
            String label) {
        return false;
    }

    /** The Media a story's menu is open on, read off the menu's helper, or null for a story with none. */
    public static Object storyMedia(Object menu) {
        return null;
    }

    /** The post a feed menu's builder is building rows for, read off its state. */
    public static Object feedMenuMedia(Object menu) {
        return null;
    }

    /** What Instagram keeps about that post in the feed, read off the builder's state. */
    public static Object feedMenuItemState(Object menu) {
        return null;
    }

    /** The page of a carousel a post's feed state says is on screen, counting from 0, or -1 as built. */
    public static int carouselIndex(Object itemState) {
        return -1;
    }

    /**
     * Adds Instagram's own Download row to [rows], the feed menu's list, the way its builder adds it
     * for your own posts. The patch replaces this body.
     */
    public static void addDownloadRow(Object menu, ArrayList<?> rows) {
    }

    /** A distinct menu option with Download's icon and ordinal, made by its native constructor. */
    public static Object saveAllOption() {
        return null;
    }

    /**
     * A new option of the feed menu named [name], drawn and handled like Download: Download's icon
     * and ordinal, made by the option's own constructor. Null as built.
     */
    public static Object feedOption(String name) {
        return null;
    }

    /**
     * Adds a separate labeled row through the same native adder that creates Download: Save all,
     * and Open in another player.
     */
    public static void addSaveAllRow(Object menu, ArrayList<?> rows, Object option, CharSequence label) {
    }
}
