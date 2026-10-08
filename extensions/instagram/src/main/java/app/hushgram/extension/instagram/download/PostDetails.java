/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.util.Date;

/**
 * What a save knows about the post its media comes from, for the file name: the media's id, who
 * posted it, when it went up and, for a carousel, which page it is. Each is missing when the route
 * to the save doesn't reach it, and {@link FileNameTemplate} then leaves its token out of the name.
 *
 * <p>Only the value is here. Hushfacebook read these off Facebook's GraphQL trees; the Instagram
 * hook that saves a post reads them off Instagram's own media model and hands them in through
 * {@link #of(String, String, Date)}.
 */
public final class PostDetails {

    /** A save that knows nothing of its post. */
    public static final PostDetails NONE = new PostDetails(null, null, null);

    /** The most pages a carousel can have and still count: more than any carousel Instagram makes. */
    static final int MAX_PAGE = 99;

    /** The media's id as the route handed it over, or null. Used only when it's a number. */
    public final String videoId;

    /** The poster's name, cleaned the way a folder name is and bounded, or null when unknown. */
    public final String owner;

    /** When the post went up, or null when unknown. */
    public final Date posted;

    /** Which page of a carousel the media is, counted from 1, or 0 when it isn't one or that's unknown. */
    public final int page;

    /**
     * Whether it's an account's profile picture rather than a post's media. It has no post time,
     * so Name saves by account and post time names it for the account and the time of the save.
     */
    public final boolean profile;

    PostDetails(String videoId, String owner, Date posted) {
        this(videoId, owner, posted, 0);
    }

    PostDetails(String videoId, String owner, Date posted, int page) {
        this(videoId, owner, posted, page, false);
    }

    private PostDetails(String videoId, String owner, Date posted, int page, boolean profile) {
        this.videoId = videoId;
        String clean = owner == null ? "" : SaveFolder.clean(owner, FileNameTemplate.MAX_OWNER_CODE_POINTS);
        this.owner = clean.isEmpty() ? null : clean;
        this.posted = posted;
        this.page = page > 0 && page <= MAX_PAGE ? page : 0;
        this.profile = profile;
    }

    /**
     * These details for page [page] of a carousel, counted from 1. A page below 1 or past
     * {@link #MAX_PAGE} is no page.
     */
    public PostDetails onPage(int page) {
        int known = page > 0 && page <= MAX_PAGE ? page : 0;
        if (known == this.page) return this;
        return new PostDetails(videoId, owner, posted == null ? null : new Date(posted.getTime()), known, profile);
    }

    /**
     * A save of [owner]'s profile picture, which knows the account and nothing else. Null [owner]
     * knows nothing.
     */
    public static PostDetails profilePicture(String owner) {
        return owner == null ? NONE : new PostDetails(null, owner, null, 0, true);
    }

    /** A save that knows the media's id and nothing else. */
    public static PostDetails of(String videoId) {
        return videoId == null ? NONE : new PostDetails(videoId, null, null);
    }

    /**
     * A save that knows the media's id [mediaId], its poster's name [owner] and when it was posted
     * [posted], each null when unknown. Instagram writes a media id as {@code <pk>_<owner's id>};
     * the pk before the underscore is the media's own number, so that's the one kept.
     */
    public static PostDetails of(String mediaId, String owner, Date posted) {
        String id = mediaId;
        if (id != null) {
            int underscore = id.indexOf('_');
            if (underscore > 0 && FileNameTemplate.isVideoId(id.substring(0, underscore))
                    && FileNameTemplate.isVideoId(id.substring(underscore + 1))) {
                id = id.substring(0, underscore);
            }
        }
        if (id == null && owner == null && posted == null) return NONE;
        return new PostDetails(id, owner, posted);
    }

    public boolean hasVideoId() {
        return FileNameTemplate.isVideoId(videoId);
    }

    public boolean hasOwner() {
        return owner != null;
    }

    public boolean hasPosted() {
        return posted != null;
    }

    public boolean hasPage() {
        return page > 0;
    }

    @Override
    public String toString() {
        // Never the poster's name or the id: a details object can end up in a diagnostic line.
        return "PostDetails(id " + (hasVideoId() ? "known" : "unknown") + ", poster " + (hasOwner() ? "known" : "unknown")
            + ", posted " + (hasPosted() ? "known" : "unknown") + (hasPage() ? ", page " + page : "")
            + (profile ? ", profile picture" : "") + ")";
    }
}
