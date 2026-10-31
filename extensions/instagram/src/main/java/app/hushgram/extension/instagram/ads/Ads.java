/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.feed.FeedSuggestions;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide ads" patch.
 *
 * <p>Instagram puts ads into a feed two ways. The app inserts some itself, through the one method
 * {@link #hide} guards. The server also sends others inside a page of posts: Home's Older Posts page
 * (S25, Instagram 450) came with two, each an ordinary post item whose post carries Instagram's ad
 * details, which no insert ever saw. The patch passes every feed item Instagram's parse helper reads
 * through {@link #feedItem}, beside the other feed filters, so those go too: Home and its Following
 * and Older Posts pages, Home's store of the last run, Explore's chain of posts and the ad responses
 * that read their items the same way. The same filter takes out the two kinds of item Instagram
 * gives only its rows of ads ({@link #AD_UNITS}), which keep no post where the sponsored check reads.
 */
public final class Ads {

    /** The diagnostic counter route of {@link #feedItem}: the items it read, and the sponsored ones taken out. */
    static final String ROUTE = "Feed ads";

    /** The counted kind of an item whose post carries Instagram's ad details. */
    static final String SPONSORED = "sponsored";

    /** The counted kind of every other item. */
    static final String NOT_SPONSORED = "not sponsored";

    /** The report's count of sponsored posts taken out of a feed's own pages. */
    static final String REMOVED = "sponsored posts removed";

    /**
     * The feed item kinds Instagram gives only its rows of ads, counted on {@link #ROUTE} by these
     * names. INTENT_AWARE_AD_PIVOT is the row a contextual ad response fills, which Instagram's own
     * sponsored check always counts as sponsored. STAND_ALONE_MULTI_AD_PIVOT is a post carrying
     * "multi_ads_info" with a row of other posts, shown as that row. The patch checks that the
     * item's kind enum names both.
     */
    static final Set<String> AD_UNITS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("INTENT_AWARE_AD_PIVOT", "STAND_ALONE_MULTI_AD_PIVOT")));

    /** The report's count of {@link #AD_UNITS} taken out of a feed's own pages. */
    static final String UNITS_REMOVED = "ad pivots removed";

    /** The report's count of comments sheets built with their ad turned off. */
    static final String COMMENT_ADS_OFF = "comment ads turned off";

    private Ads() {}

    /**
     * Asked at the start of the method that puts a sponsored item into a feed. True makes it
     * answer that nothing went in, so the item isn't shown and no gap is left. False while the
     * switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean hide() {
        HookStatus.invoked(FamilyNames.HIDE_ADS);
        try {
            return Utils.settingsReady() && Settings.HIDE_ADS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDE_ADS, "switch read", t);
            return false;
        }
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for one of the
     * {@link #AD_UNITS} or an item whose post is sponsored ({@link #sponsored}) while Hide ads is
     * on, and [item] itself otherwise: with the switch off, HushGram paused, the settings not ready
     * or anything thrown. Inside a page of Home's response the item still counts toward that page as
     * one taken out, so a page left with no posts ends the way one the feed switches emptied does.
     * Never throws.
     */
    public static Object feedItem(Object item) {
        return feedItem(item, Ads::sponsored);
    }

    static Object feedItem(Object item, ToIntFunction<Object> sponsoredOf) {
        if (item == null) return null;
        HookStatus.invoked(FamilyNames.HIDE_ADS);
        try {
            if (!Utils.settingsReady() || !Settings.HIDE_ADS.get()) return item;
            String unit = FeedItemKinds.kindIn(item, AD_UNITS, FamilyNames.HIDE_ADS);
            if (unit != null) {
                FeedFilterCounters.sawKind(ROUTE, unit);
                FeedFilterCounters.removed(ROUTE, 1, unit);
                HookStatus.counted(FamilyNames.HIDE_ADS, UNITS_REMOVED);
                FeedSuggestions.adTakenOut();
                Logger.printDebug(() -> "Hide ads: took a " + unit + " item out of a feed");
                return null;
            }
            boolean sponsored = sponsoredOf.applyAsInt(item) == 1;
            FeedFilterCounters.sawKind(ROUTE, sponsored ? SPONSORED : NOT_SPONSORED);
            if (!sponsored) return item;
            FeedFilterCounters.removed(ROUTE, 1, SPONSORED);
            HookStatus.counted(FamilyNames.HIDE_ADS, REMOVED);
            FeedSuggestions.adTakenOut();
            Logger.printDebug(() -> "Hide ads: took a sponsored post out of a feed");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADS, "feed item", failure);
            return item;
        }
    }

    /**
     * Handed each value the comments sheet's settings keep for "enable_comment_ads" as they're
     * built (#415). Answers 0 while Hide ads is on, so the sheet neither fetches an ad nor shows one
     * among the comments, and [wanted] otherwise: with the switch off, HushGram paused, the settings
     * not ready or anything thrown. Never throws.
     */
    public static int commentAds(int wanted) {
        if (wanted == 0) return 0;
        HookStatus.invoked(FamilyNames.HIDE_ADS);
        try {
            if (!Utils.settingsReady() || !Settings.HIDE_ADS.get()) return wanted;
            HookStatus.counted(FamilyNames.HIDE_ADS, COMMENT_ADS_OFF);
            Logger.printDebug(() -> "Hide ads: turned the comments sheet's ad off");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADS, "comment ads", failure);
            return wanted;
        }
    }

    /**
     * Whether the post a feed item carries is sponsored: 1 when the post has Instagram's ad details
     * ("injected" in its JSON, the key Instagram itself reads to tell an ad from a post), and 0 when
     * it hasn't or the item carries no post. The patch writes the body, which reads the item's post
     * field and the post's ad details. 0 as built.
     */
    public static int sponsored(Object item) {
        return 0;
    }
}
