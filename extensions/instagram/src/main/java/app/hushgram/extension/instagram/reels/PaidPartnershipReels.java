/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide paid partnership Reels" patch (#12).
 *
 * <p>Every Reels viewer takes each page it fetched, already turned into clips items, the viewer's
 * own model, in one network callback, which knows the viewer's module name: "clips_viewer_" and
 * where it was opened. The patch hands that page and that name to {@link #page} there, so the
 * viewer gets the list this answers.
 *
 * <p>Only the Reels tab is filtered, in any of its lanes, where Instagram picks every reel (see
 * {@link #PICKED_BY_INSTAGRAM}). A viewer opened on something the user chose (search, a profile,
 * their own profile, an audio, hashtag or location page, Saved, a reel shared in a message) gets
 * its pages as they came, so a reel they tapped always opens, and a creator's own boosted reels
 * still show on their profile.
 *
 * <p>Two things about a reel's post decide it, both fields Instagram already parsed from the
 * page, read through getters the patch finds by their JSON names:
 * <ul>
 *   <li>is_paid_partnership, the flag Instagram's own Paid partnership label reads. True drops it.
 *   <li>boosted_status, the state of the boost its owner bought, which Instagram's promote screens
 *       read as one of not_boosted, pending, boosted, boosted_eligible, boosted_eligible_paused,
 *       not_approved, finished, unavailable, draft, paused or not_delivering. Only boosted (running
 *       now) and finished (ran to its end) say paid delivery happened, so only those drop it, with
 *       case ignored as Instagram ignores it. The rest, and any value it doesn't know, stay.
 * </ul>
 * Nothing else counts: a tracking token, an ad id or a field the app doesn't use to mark a
 * promotion isn't proof of one. Sponsored ads are Hide ads' to remove.
 *
 * <p>A page where every reel would go keeps every item, so the viewer always has a reel to show,
 * and the report counts it. Items that aren't reels, such as a suggestion card, don't count: a
 * page of a card and two paid partnership reels is kept whole, or it would hold no reel. Off, paused, before the settings are read, or when anything throws,
 * the page comes back as it came, the same list.
 */
public final class PaidPartnershipReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Paid partnership reels";

    /** The reason, and kind, of an item whose post is a paid partnership. */
    static final String PARTNERSHIP = "paid partnership";

    /** The reason, and kind, of an item whose post's boost is running or ran. */
    static final String BOOSTED = "boosted";

    /** The kind counted for a page left whole because every item in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** boosted_status values that mean the boost delivered: running now, and run to its end. */
    private static final String[] BOOST_RAN = {"boosted", "finished"};

    /**
     * The module names of the Reels tab's viewer, the only pages this filters: "clips_viewer_" and
     * the source of each lane the tab offers (For you, Following, Nearby, Friends, Trending, Shop,
     * the ads lane and Instagram's in-house test lanes), and the name For you takes in Instagram's
     * homecoming test. Instagram picks every reel in those. Any other name is a viewer the user
     * opened on a reel or a place they chose.
     */
    static final String[] PICKED_BY_INSTAGRAM = {
        "clips_viewer_clips_tab",
        "clips_viewer_homecoming_fyp",
        "clips_viewer_clips_connected",
        "clips_viewer_clips_location",
        "clips_viewer_friends_lane",
        "clips_viewer_trending_only",
        "clips_viewer_clips_shop_lane",
        "clips_viewer_clips_ads_only",
        "clips_viewer_social_debug",
        "clips_viewer_clips_voice_translation_dogfooding",
        "clips_viewer_clips_translated_stickers_dogfooding",
        "clips_viewer_clips_translated_closed_captions_dogfooding",
        "clips_viewer_clips_blend",
    };

    /** Reads what decides an item; the patch-filled stubs below outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's is_paid_partnership, or null when it doesn't say. */
        Boolean paidPartnership(Object media);

        /** The post's boosted_status, or null when it doesn't say. */
        String boostedStatus(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return PaidPartnershipReels.media(item);
        }

        @Override
        public Boolean paidPartnership(Object media) {
            return PaidPartnershipReels.paidPartnership(media);
        }

        @Override
        public String boostedStatus(Object media) {
            return PaidPartnershipReels.boostedStatus(media);
        }
    };

    private PaidPartnershipReels() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the paid partnership and boosted items while the switch is
     * on and the page is for the Reels tab, and [items] itself when the viewer is any other, when
     * nothing goes, when every item would, when the switch is off or paused, or when anything goes
     * wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, PaidPartnershipReels::switchedOn);
    }

    static List<?> page(List<?> items, String module, Reader reader, BooleanSupplier on) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.PAID_PARTNERSHIP_REELS);
            if (!pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0 || !on.getAsBoolean()) return items;

            String[] reasons = new String[size];
            int partnerships = 0;
            int boosted = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media != null) reels++;
                String reason = media == null ? null : reasonFor(media, reader);
                if (PARTNERSHIP.equals(reason)) partnerships++;
                if (BOOSTED.equals(reason)) boosted++;
                if (reason != null) FeedFilterCounters.sawKind(ROUTE, reason);
                reasons[at++] = reason;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            int matched = partnerships + boosted;
            if (matched == 0) return items;
            if (matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Paid partnership reels: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (reasons[at++] == null) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, partnerships, PARTNERSHIP);
            FeedFilterCounters.removed(ROUTE, boosted, BOOSTED);
            int left = kept.size();
            Logger.printDebug(() -> "Paid partnership reels: took " + matched + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PAID_PARTNERSHIP_REELS, "reels page", failure);
            return items;
        }
    }

    /** Why the reel carrying [media] goes, or null when it stays. */
    private static String reasonFor(Object media, Reader reader) {
        if (Boolean.TRUE.equals(reader.paidPartnership(media))) return PARTNERSHIP;
        return boostRan(reader.boostedStatus(media)) ? BOOSTED : null;
    }

    /** Whether a viewer's module name is one of the Reels tab's, where Instagram picks every reel. */
    static boolean pickedByInstagram(String module) {
        if (module == null) return false;
        for (String picked : PICKED_BY_INSTAGRAM) {
            if (picked.equals(module)) return true;
        }
        return false;
    }

    /** Whether a boosted_status value says the boost delivered. */
    static boolean boostRan(String status) {
        if (status == null) return false;
        for (String ran : BOOST_RAN) {
            if (ran.equalsIgnoreCase(status)) return true;
        }
        return false;
    }

    /** Whether the switch is on, read only once the settings are; false while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_PAID_PARTNERSHIP_REELS.get();
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /**
     * A post's is_paid_partnership, or null when it doesn't say. The patch writes the body, which
     * calls the post's getter for that field. Null as built.
     */
    public static Boolean paidPartnership(Object media) {
        return null;
    }

    /**
     * A post's boosted_status, or null when it doesn't say. The patch writes the body, which calls
     * the post's getter for that field. Null as built.
     */
    public static String boostedStatus(Object media) {
        return null;
    }
}
