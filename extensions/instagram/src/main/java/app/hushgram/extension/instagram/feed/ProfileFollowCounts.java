/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;

/**
 * Helper for the Hide Followers and Following on profiles patch (#771): the two buttons beside the
 * post count at the top of a profile, or just their numbers.
 *
 * <p>It lives beside {@link FeedFollowButton} because {@link ViewHider} does. A profile header lays
 * each button out as a stack, the number over its label, and these ids are used nowhere else, so
 * both hiders look through the whole window. Hiding a number lets its label move up into its place,
 * and the stack still opens the list. Hiding a stack closes the row up, so no gap is left where it
 * was. The post count, the name and the bio stay.
 */
public final class ProfileFollowCounts {
    /** The followers and following buttons, each a number over its label. */
    static final String FOLLOWERS = "profile_header_followers_stacked_familiar";
    static final String FOLLOWING = "profile_header_following_stacked_familiar";
    /** The numbers alone. */
    static final String FOLLOWERS_COUNT = "profile_header_familiar_followers_value";
    static final String FOLLOWING_COUNT = "profile_header_familiar_following_value";

    static final String HOOK = "profile follow counts";
    static final String COUNT_HIDDEN = "profile follow counts hidden";

    private static final ViewHider BUTTONS = new ViewHider(FamilyNames.PROFILE_FOLLOW_COUNTS, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_PROFILE_FOLLOW_BUTTONS.get(), null, FOLLOWERS, FOLLOWING);

    /** With the buttons hidden their numbers go with them, so this one stands down rather than search again. */
    private static final ViewHider COUNTS = new ViewHider(FamilyNames.PROFILE_FOLLOW_COUNTS, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_PROFILE_FOLLOW_COUNTS.get() && !Settings.HIDE_PROFILE_FOLLOW_BUTTONS.get(), null,
            FOLLOWERS_COUNT, FOLLOWING_COUNT);

    private ProfileFollowCounts() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        BUTTONS.install(context, SettingsStatus.profileFollowCounts());
        COUNTS.install(context, SettingsStatus.profileFollowCounts());
    }

    /** A switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        BUTTONS.switchChanged(activity);
        COUNTS.switchChanged(activity);
    }

    static ViewHider buttons() {
        return BUTTONS;
    }

    static ViewHider counts() {
        return COUNTS;
    }

    public static boolean listeningForTests(Activity activity) {
        return BUTTONS.listeningForTests(activity) || COUNTS.listeningForTests(activity);
    }

    public static void resetForTests() {
        BUTTONS.resetForTests();
        COUNTS.resetForTests();
    }
}
