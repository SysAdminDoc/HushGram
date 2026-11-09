/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

/** What a swipe right on Home opens, and the id of the tab bar button that opens it (#589). */
public enum HomeSwipe {
    CAMERA(null),
    SEARCH("search_tab"),
    REELS("clips_tab"),
    MESSAGES("direct_tab"),
    PROFILE("profile_tab");

    /** The tab's button in the tab bar, or null for the camera, which is Instagram's own. */
    @Nullable
    final String button;

    HomeSwipe(@Nullable String button) {
        this.button = button;
    }
}
