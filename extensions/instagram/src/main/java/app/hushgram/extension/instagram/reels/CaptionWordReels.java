/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide reels by caption words" patch (#192).
 *
 * <p>It filters the same pages as {@link PaidPartnershipReels} and {@link AiLabeledReels}: each page
 * the Reels viewer's network callback takes, already turned into clips items, with the viewer's
 * module name. Only the Reels tab's pages are filtered, so a reel the user opened from search, a
 * profile, Saved or a message still plays.
 *
 * <p>The list is the words and phrases typed under the switch, split at commas and new lines, as
 * Hushfeed's Blocked caption words are, so a list from there pastes in as it is. A reel goes when
 * its caption, the post's own words with their hashtags, holds one of them, whatever the case. A
 * reel with no caption stays. With Also on Home on, Home's posts are matched against the same list
 * ({@link app.hushgram.extension.instagram.feed.FeedSuggestions#homeItem}).
 *
 * <p>A page where every reel would go keeps every item, so the viewer always has a reel to show,
 * and the report counts it. Off, paused, with an empty list, before the settings are read, or when
 * anything throws, the page comes back as it came, the same list.
 */
public final class CaptionWordReels {
    /** The diagnostic counter route: the pages seen, and the items taken out of them. */
    static final String ROUTE = "Caption word reels";

    /** The reason, and kind, of an item whose caption holds a blocked word. */
    static final String BLOCKED = "blocked word";

    /** The kind counted for a page left whole because every reel in it matched. */
    static final String PAGE_KEPT = "page kept whole";

    /** Reads what decides an item; the patch-filled stub and the caption bridges outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The post's caption text, or null when it has none. */
        String caption(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return CaptionWordReels.media(item);
        }

        @Override
        public String caption(Object media) {
            Object caption = InstagramMedia.caption(media);
            return caption == null ? null : InstagramMedia.captionText(caption);
        }
    };

    /** The last list read and the words it splits into, so a page doesn't split it again. */
    private static volatile Parsed parsed;

    private static final class Parsed {
        final String stored;
        final List<String> words;

        Parsed(String stored, List<String> words) {
            this.stored = stored;
            this.words = words;
        }
    }

    private CaptionWordReels() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module
     * name. Answers a new list without the reels whose caption holds a blocked word while the switch
     * is on and the page is for the Reels tab, and [items] itself when the viewer is any other, when
     * nothing goes, when every reel would, when the switch is off, paused or has no words, or when
     * anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED, CaptionWordReels::wordsWhenOn);
    }

    static List<?> page(List<?> items, String module, Reader reader, Supplier<List<String>> words) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.CAPTION_WORD_REELS);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0) return items;
            List<String> blocked = words.get();
            if (blocked.isEmpty()) return items;

            boolean[] goes = new boolean[size];
            int matched = 0;
            int reels = 0;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media != null) reels++;
                boolean hit = media != null && holdsAny(reader.caption(media), blocked);
                if (hit) {
                    matched++;
                    FeedFilterCounters.sawKind(ROUTE, BLOCKED);
                }
                goes[at++] = hit;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size) return items;
            if (matched == 0) return items;
            if (matched == reels) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Caption word reels: kept a page of " + size + " where every reel matched");
                return items;
            }

            List<Object> kept = new ArrayList<>(size - matched);
            at = 0;
            for (Object item : items) {
                if (!goes[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, matched, BLOCKED);
            int left = kept.size();
            int removed = matched;
            Logger.printDebug(() -> "Caption word reels: took " + removed + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAPTION_WORD_REELS, "reels page", failure);
            return items;
        }
    }

    /** Whether [caption] holds one of [words], which are already lower case. No caption holds nothing. */
    public static boolean holdsAny(String caption, List<String> words) {
        if (caption == null || caption.isEmpty()) return false;
        String text = caption.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /**
     * The words in a stored list, lower case: split at commas and new lines, each one trimmed, the
     * empty ones and repeats left out, in the order they were typed.
     */
    public static List<String> words(String stored) {
        if (stored == null || stored.trim().isEmpty()) return Collections.emptyList();
        List<String> words = new ArrayList<>();
        for (String piece : stored.split("[,\\n\\r]")) {
            String word = piece.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && !words.contains(word)) words.add(word);
        }
        return Collections.unmodifiableList(words);
    }

    /** The words of the stored list, split again only when the list changes. */
    static List<String> cached(String stored) {
        Parsed known = parsed;
        if (known != null && Objects.equals(known.stored, stored)) return known.words;
        List<String> words = words(stored);
        parsed = new Parsed(stored, words);
        return words;
    }

    /** The blocked words while the switch is on, read only once the settings are; none while paused or off. */
    static List<String> wordsWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_CAPTION_WORD_REELS.get()) return Collections.emptyList();
        return cached(Settings.REELS_BLOCKED_WORDS.get());
    }

    /**
     * The blocked words Home's posts are matched against: the list while the switch and its Also on
     * Home are both on, and none while either is off, while paused or before the settings are read.
     */
    public static List<String> homeWordsWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_CAPTION_WORD_HOME.get()) return Collections.emptyList();
        return wordsWhenOn();
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }
}
