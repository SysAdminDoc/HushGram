/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package com.instagram.clips.intf;

/**
 * Stands in for where Instagram 450's reel viewer was opened from, a few of its names: the
 * extension finds it by this class name and reads the constant's name, as it does Instagram's.
 */
public enum ClipsViewerSource {
    CLIPS_TAB,
    CLIPS_TAB_HOMECOMING,
    PROFILE_CLIPS,
    FEED_TIMELINE,
    DIRECT_THREAD,
    CLIPS_FRIEND_LANE
}
