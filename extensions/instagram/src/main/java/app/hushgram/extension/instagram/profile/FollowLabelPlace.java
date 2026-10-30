/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

/**
 * Where Show it as a chip puts the chip on a profile, and how big it is (#157): under the posts,
 * followers and following counts at its usual size, which is all there was before the choice, or
 * smaller and centered under the profile picture. A header with no room under the picture gets the
 * small chip under the counts ({@link FriendshipChip}).
 */
public enum FollowLabelPlace {
    UNDER_COUNTS,
    UNDER_PICTURE
}
