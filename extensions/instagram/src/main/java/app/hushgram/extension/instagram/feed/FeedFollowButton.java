/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide Follow on feed posts patch (#248): keeps the Follow and Subscribe buttons
 * beside a feed post's author out of the post header.
 *
 * <p>Instagram reuses these ids in other places (a story, a reel, a profile), so {@link ViewHider}
 * counts them only inside the feed post header, and never touches the view stubs the header
 * inflates them from. The author's name, the time and the menu are not named and stay. The
 * Subscribe shortcut sits outside the header, so it is matched by its own ids anywhere. The current
 * Litho header's Subscribe has no such id. Its eligibility answer goes through {@link #showSubscribe}
 * before Instagram creates that one child.
 */
public final class FeedFollowButton {
    /** The header of a feed post, which holds the author's name and the buttons beside it. */
    static final String HEADER = "row_feed_profile_header";
    /** The buttons: the one beside the name, the one in the header, and Subscribe. */
    static final String INLINE_FOLLOW = "inline_follow_button";
    static final String HEADER_FOLLOW = "follow_in_header";
    static final String SUBSCRIBE = "subscribe_button";
    /**
     * The Subscribe shortcut a creator's post carries (#248). It is a view stub in the post's own row,
     * not in the header, so it is hidden wherever it is: the button it inflates, and the stub's id
     * when the stub gave its id to the view it inflated. Only the feed row's holder looks these up.
     */
    static final String SUBSCRIBE_SHORTCUT = "subscribe_shortcut_button";
    static final String SUBSCRIBE_SHORTCUT_STUB = "subscribe_shortcut_stub";

    static final String HOOK = "feed follow button";
    static final String COUNT_HIDDEN = "feed follow buttons hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.FEED_FOLLOW, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_FEED_FOLLOW.get(), HEADER, INLINE_FOLLOW, HEADER_FOLLOW, SUBSCRIBE)
            .anywhere(SUBSCRIBE_SHORTCUT, SUBSCRIBE_SHORTCUT_STUB);

    private FeedFollowButton() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.feedFollow());
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
    }

    /** Keeps native ineligibility and suppresses only the feed header's Subscribe while wanted. */
    public static boolean showSubscribe(int eligible) {
        return showSubscribe(eligible, HIDER::wanted);
    }

    static boolean showSubscribe(int eligible, BooleanSupplier on) {
        if (eligible == 0) return false;
        try {
            HookStatus.invoked(FamilyNames.FEED_FOLLOW);
            if (!on.getAsBoolean()) return true;
            HookStatus.counted(FamilyNames.FEED_FOLLOW, COUNT_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_FOLLOW, HOOK, failure);
            return true;
        }
    }

    static ViewHider hider() {
        return HIDER;
    }

    public static boolean listeningForTests(Activity activity) {
        return HIDER.listeningForTests(activity);
    }

    public static void resetForTests() {
        HIDER.resetForTests();
    }
}
