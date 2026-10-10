/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * What the Clean up Reels patch asks before a part of the Reels viewer is drawn.
 *
 * <p>The viewer's overlay is built from components, each drawn by its own render method, and a
 * render that answers nothing leaves its part out. Instagram does that itself whenever a part has
 * nothing to show. The patch asks one of these first thing in each render it hides:
 *
 * <ul>
 *   <li>{@link #hideFollowButton} in the Follow button beside a reel's author. The Follow button
 *       on the cards of suggested accounts between reels is another component and stays.
 *   <li>{@link #hideChips} in each pill that prompts you to make something or promotes something:
 *       the attribution pills for Edits, templates and creative tools, the Stories template pill,
 *       the Meta AI pill, the Ray-Ban Meta glasses pills and the affiliate link. The other things
 *       Instagram puts above a reel's author, a live badge or a state-controlled media label, have
 *       components of their own and stay.
 *   <li>{@link #hideSocialFooter} in the inline comment preview and the row of friends who saw
 *       the reel, and in Instagram's check for putting the bubbles of friends' likes, comments and
 *       follows above the author, which then answers no. It's also asked first thing in the use
 *       case that works out a reel's floating bubbles, which then answers that there are none.
 *   <li>{@link #hideSocialContext} in Instagram's check for leaving out a reel's social context
 *       line, the faces with Liked by or Followed by beside them, with the line's type. The check
 *       answers yes for {@link #FRIENDS_ACTIVITY}, so a follower count or a seller's rating stays.
 *   <li>{@link #hideCommentBar} first thing whenever the controller of the Add comment bar under a
 *       reel opened outside the Reels tab would show it, once it inflates the bar, and when it
 *       would put the bar back after hiding it for a while, with where the viewer was opened from. It answers yes for a profile's reposts, {@link #REPOSTS}, and
 *       Instagram's own hide then takes the bar off. The reel's comment button stays. With
 *       the switch for all reels on, it answers yes for every source.
 *   <li>{@link #hideEveryCommentBar} first thing in the render of the in-viewer comment bar
 *       component, the bar Instagram 450 draws under reels in the Reels tab.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while HushGram is paused, with a switch
 * off, or when something throws, Instagram draws the part.
 */
public final class ReelDeclutter {
    /**
     * The social context types that tell you what people you know did with a reel or its author,
     * as Instagram 449 names them. Counts, ratings, partnerships and what strangers did aren't here.
     */
    static final Set<String> FRIENDS_ACTIVITY = new HashSet<>(Arrays.asList(
            "FOLLOWED_BY",
            "LIKED_BY",
            "COMMENTED_BY",
            "COMMENT_REACTION",
            "COMMENT_PROMPT_ANSWERED_BY",
            "REPOSTED_BY",
            "TAGGED_BY",
            "VOTED_ON_BY",
            "VIEWED_BY_FRIENDS",
            "MOST_VIEWED_BY_FRIENDS",
            "REMIXED_BY_FRIENDS",
            "EFFECT_USED_BY_FRIENDS",
            "TEMPLATE_USED_BY_FRIENDS",
            "REFRAME_USED_BY_FRIENDS",
            "BLEND_MEDIA_FROM_RESHARE",
            "BLEND_MEDIA_SUGGESTED_BY",
            "CARRERA_INTEREST_SHARING_BY"));

    /** Where a viewer of reposted reels is opened from, as Instagram 450's ClipsViewerSource names it. */
    static final Set<String> REPOSTS = new HashSet<>(Arrays.asList("REPOSTS_GRID", "SELF_REPOSTS_GRID"));

    /** The line types the debug log has named, each once a run. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    private ReelDeclutter() {
    }

    /** True leaves the Follow button beside a reel's author out. Never throws. */
    public static boolean hideFollowButton() {
        return hide(Settings.HIDE_REEL_FOLLOW_BUTTON, "Follow button");
    }

    /** True leaves a creation or promotion pill out. Never throws. */
    public static boolean hideChips() {
        return hide(Settings.HIDE_REEL_CHIPS, "pill");
    }

    /** True leaves friends' activity and the comment preview out. Never throws. */
    public static boolean hideSocialFooter() {
        return hide(Settings.HIDE_REEL_SOCIAL_FOOTER, "friends' activity");
    }

    /**
     * True takes the comment bar off a reel opened from a profile's reposts, yours or someone
     * else's: [source] is the viewer's ClipsViewerSource, and only {@link #REPOSTS} answer yes.
     * Never throws.
     */
    public static boolean hideCommentBar(Object source) {
        if (hide(Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, "comment bar")) return true;
        if (!hide(Settings.HIDE_REEL_COMMENT_BAR, "comment bar")) return false;
        try {
            return source instanceof Enum && REPOSTS.contains(((Enum<?>) source).name());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "comment bar source", failure);
            return false;
        }
    }

    /**
     * True leaves the in-viewer Add a comment bar out of every reel, the Reels tab's included.
     * Never throws.
     */
    public static boolean hideEveryCommentBar() {
        return hide(Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, "comment bar on all reels");
    }

    /**
     * True leaves a reel's social context line out when [type], the line's enum, is one of
     * {@link #FRIENDS_ACTIVITY}. Never throws.
     */
    public static boolean hideSocialContext(Object type) {
        if (!hide(Settings.HIDE_REEL_SOCIAL_FOOTER, "friends' activity line")) return false;
        try {
            boolean friends = isFriendsActivity(type);
            String name = type instanceof Enum ? ((Enum<?>) type).name() : String.valueOf(type);
            if (LOGGED.add(name)) {
                Logger.printDebug(() -> "Clean up Reels: " + (friends ? "leaves out" : "keeps") + " the " + name + " line");
            }
            return friends;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "friends' activity line", failure);
            return false;
        }
    }

    static boolean isFriendsActivity(Object type) {
        return type instanceof Enum && FRIENDS_ACTIVITY.contains(((Enum<?>) type).name());
    }

    private static boolean hide(BooleanSetting setting, String what) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            return Utils.settingsReady() && setting.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, what, failure);
            return false;
        }
    }
}
