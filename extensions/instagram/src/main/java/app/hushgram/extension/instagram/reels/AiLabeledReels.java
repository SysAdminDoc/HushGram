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
 * Helper for the "Hide AI-labeled reels" patch (#174).
 *
 * <p>It filters the same pages as {@link PaidPartnershipReels}: each page the Reels viewer's network
 * callback takes, already turned into clips items, with the viewer's module name. Only the Reels
 * tab's pages are filtered (see {@link PaidPartnershipReels#PICKED_BY_INSTAGRAM}), so a reel the user
 * opened from search, a profile, Saved or a message still plays.
 *
 * <p>A reel goes when its post's own AI label, the ai_label_info Instagram parsed from the page,
 * says to show the label and carries its text: should_read_attribution_label is TRUE and
 * attribution_label_text isn't empty. That's the test Instagram 451 makes before it shows the AI
 * label under a reel and logs it as "ai_content". This follows what the server attributes to the
 * post, not whether this account's rollout shows the label, so a reel whose label is held back
 * for this account, or whose creator is an AI account with its own label, goes too.
 *
 * <p>Everything else stays: a post with no label info, a label that doesn't say to show itself or
 * has no text, an item that isn't a reel. A label object alone, or the flag alone, isn't proof. No
 * caption, label text or picture is read for meaning, and AI content Instagram didn't label still
 * shows.
 *
 * <p>A page where every reel would go keeps every item, so the viewer always has a reel to show,
 * and the report counts it. Off, paused, before the settings are read, or when anything throws,
 * the page comes back as it came, the same list.
 */
public final class AiLabeledReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "AI-labeled reels";

    /** The reason, and kind, of an item whose post carries Instagram's AI label. */
    static final String LABELED = "AI label";

    /** The kind counted for a page left whole because every reel in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** Reads what decides an item; the patch-filled stubs below outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's ai_label_info, or null when it has none. */
        Object labelInfo(Object media);

        /** The label's should_read_attribution_label, or null when it doesn't say. */
        Boolean readsLabel(Object info);

        /** The label's attribution_label_text, or null when it has none. */
        String labelText(Object info);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return AiLabeledReels.media(item);
        }

        @Override
        public Object labelInfo(Object media) {
            return AiLabeledReels.labelInfo(media);
        }

        @Override
        public Boolean readsLabel(Object info) {
            return AiLabeledReels.readsLabel(info);
        }

        @Override
        public String labelText(Object info) {
            return AiLabeledReels.labelText(info);
        }
    };

    private AiLabeledReels() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the AI-labeled reels while the switch is on and the page is
     * for the Reels tab, and [items] itself when the viewer is any other, when nothing goes, when
     * every reel would, when the switch is off or paused, or when anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, AiLabeledReels::switchedOn);
    }

    static List<?> page(List<?> items, String module, Reader reader, BooleanSupplier on) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.AI_LABELED_REELS);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0 || !on.getAsBoolean()) return items;

            boolean[] labeled = new boolean[size];
            int matched = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media != null) reels++;
                boolean goes = media != null && labeled(media, reader);
                if (goes) {
                    matched++;
                    FeedFilterCounters.sawKind(ROUTE, LABELED);
                }
                labeled[at++] = goes;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            if (matched == 0) return items;
            if (matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "AI-labeled reels: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (!labeled[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, matched, LABELED);
            int left = kept.size();
            int removed = matched;
            Logger.printDebug(() -> "AI-labeled reels: took " + removed + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AI_LABELED_REELS, "reels page", failure);
            return items;
        }
    }

    /**
     * Whether the post carrying [media] has Instagram's AI label: label info that says to show the
     * label, with text to show. Anything missing or unknown answers false.
     */
    static boolean labeled(Object media, Reader reader) {
        Object info = reader.labelInfo(media);
        if (info == null || !Boolean.TRUE.equals(reader.readsLabel(info))) return false;
        String text = reader.labelText(info);
        return text != null && text.length() != 0;
    }

    /** Whether the switch is on, read only once the settings are; false while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_AI_LABELED_REELS.get();
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /**
     * A post's ai_label_info, or null when it has none. The patch writes the body, which calls the
     * post's getter for that field. Null as built.
     */
    public static Object labelInfo(Object media) {
        return null;
    }

    /**
     * A label's should_read_attribution_label, or null when it doesn't say. The patch writes the
     * body, which calls the label's getter for that field. Null as built.
     */
    public static Boolean readsLabel(Object info) {
        return null;
    }

    /**
     * A label's attribution_label_text, or null when it has none. The patch writes the body, which
     * calls the label's getter for that field. Null as built.
     */
    public static String labelText(Object info) {
        return null;
    }
}
