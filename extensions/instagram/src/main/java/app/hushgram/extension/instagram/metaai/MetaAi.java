/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide Meta AI" patch.
 *
 * <p>Instagram decides whether its search bars offer Meta AI from server flags. On 449 one flag
 * covers the Search tab's bar ("Search with Meta AI"), its results and Meta AI's answers there, and
 * two more the bar at the top of your messages: its "Search or ask Meta AI" hint, and the Meta AI
 * ring at its end. The patch passes every read of them through {@link #searchFlag}, which answers no
 * while its switch is on, the answer an account without Meta AI gets.
 *
 * <p>A keyword search's results end in an "Ask a follow-up…" bar with topic pills above it. Its flag
 * also gives the results page its header (Back and the query), so it stays on. Instead, the bar's
 * stub goes through {@link #followUpStub} as the page finds it, before inflating it, and the view
 * the page then looks the pills up in goes through {@link #followUpBar}.
 *
 * <p>The results page keeps Instagram's answer for that flag, since it opens a tapped reel by its
 * place in the server's list and leaving Meta AI's rows out shifted every reel after them (#152).
 * The rows Meta AI's summary card sits in go through {@link #summaryCard} as they're made instead,
 * which folds them away without taking them out of the list.
 *
 * <p>Home's top bar is built from a list of button names the server sends. Meta AI's ("meta_ai")
 * goes through {@link #homeButton}, and a fourth flag, which adds a Meta AI chats button when the
 * list has no messages button, goes through {@link #searchFlag} with the others.
 *
 * <p>The home feed's Meta AI units go through {@link #filter} at the feed's parse helper, the way
 * Hide suggested posts' do.
 *
 * <p>A reel's More menu, in Reels and in the feed, can open with About this reel: a generated
 * summary, its Sources and an Ask Meta AI box. Every menu asks one factory for it and leaves the
 * block out on a null, so the factory's answer goes through {@link #aboutThisReel}. The summary
 * row adds the box alone with one addView, which goes through {@link #askMetaAiBox} instead.
 *
 * <p>The share sheet builds the targets in its bottom row from a list of names. Meta AI's ("hatch",
 * shown as Muse on some accounts) has its own case, and the answer of the case's name check goes
 * through {@link #shareTarget}.
 *
 * <p>A reel's Blend invite button comes from one static check that the Reels viewer's reel binder
 * and the clips viewer both read. {@link #blendButton} makes it answer "no Blend" first.
 *
 * <p>Your profile's top bar is filled from a list of items, one view each. Where the loop reaches
 * the Muse button's item, {@link #museButton} decides whether it skips to the next one.
 */
public final class MetaAi {
    /**
     * The feed item kinds of Meta AI's units, by the constant names Instagram 449 gives them:
     * Vibes (its feed of AI videos), Meta AI chats ("hatch") and Imagine pictures of you ("memu").
     * Hide Reels in the feed takes out the first two as well.
     */
    static final Set<String> FEED_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")));

    /** The names Home's top bar list gives Meta AI's button (449 on) and its Meta AI chats button. */
    static final Set<String> HOME_BUTTONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("meta_ai", "hatch")));

    /** The three Meta AI buttons in Instagram's native message composer button enum. */
    static final Set<String> COMPOSER_BUTTONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "META_AI_DISCOVERY", "META_AI_INVOCATION", "META_AI_VOICE")));

    /** The diagnostic counter route for the feed units. */
    static final String ROUTE = "Meta AI in the feed";

    private static volatile boolean loggedSearch;

    private MetaAi() {
    }

    /**
     * Injected right after each read of one of Meta AI's search flags, with Instagram's answer as an
     * int (non-zero is yes). Answers false while its switch is on, and Instagram's answer
     * otherwise, or when anything goes wrong. Never throws, and never waits for the settings: before
     * they're ready Instagram's answer stands.
     */
    public static boolean searchFlag(int enabled) {
        if (enabled == 0) return false;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return true;
            if (!loggedSearch) {
                loggedSearch = true;
                Logger.printDebug(() -> "Meta AI: search flag answered off");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "search flag", failure);
            return true;
        }
    }

    /**
     * Injected where the search results page checks the view it looks up a stub of its "Ask a
     * follow-up…" bar in, with that view: on 450 the bar itself, before its topic pills are looked
     * up in it. Answers null while the search switch is on, which Instagram takes as a view without
     * that stub, and the view otherwise, or when anything goes wrong. Never throws.
     */
    public static View followUpBar(View view) {
        if (view == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return view;
            Logger.printDebug(() -> "Meta AI: left out the Ask a follow-up bar");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "follow-up bar", failure);
            return view;
        }
    }

    /**
     * Injected where the search results page finds its "Ask a follow-up…" bar's stub, with the
     * stub, before it tests it for null and inflates it. Answers null while the search switch is
     * on, which skips the inflate, so a new results page has no bar and no topic pills, and the
     * stub otherwise, or when anything goes wrong. Never throws.
     */
    public static View followUpStub(View stub) {
        if (stub == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return stub;
            Logger.printDebug(() -> "Meta AI: left the Ask a follow-up bar out of search results");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "follow-up bar stub", failure);
            return stub;
        }
    }

    /**
     * Injected where the search results page makes a row of Meta AI's summary card, with the row's
     * view, before the row's holder takes it. The row stays in the page's list, which opens a tapped
     * reel by its row's place in the list the server sent (#152). Instead it's folded away: each time
     * it's attached it takes no height and draws nothing while the search switch is on, and gets its
     * own height back once the switch is off or HushGram is paused. Answers the row. Never throws.
     */
    public static View summaryCard(View row) {
        if (row == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            SummaryRow folding = new SummaryRow(row);
            row.addOnAttachStateChangeListener(folding);
            folding.apply();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "summary card", failure);
        }
        return row;
    }

    /** Folds one summary card row away while the search switch is on, and back out when it's off. */
    static final class SummaryRow implements View.OnAttachStateChangeListener {
        private final View row;
        private boolean folded;
        private int height;
        private int visibility;

        SummaryRow(View row) {
            this.row = row;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            apply();
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
        }

        void apply() {
            try {
                boolean fold = Utils.settingsReady() && Settings.HIDE_META_AI_SEARCH.get();
                ViewGroup.LayoutParams params = row.getLayoutParams();
                if (fold == folded || params == null) return;
                if (fold) {
                    height = params.height;
                    visibility = row.getVisibility();
                    params.height = 0;
                    row.setVisibility(View.GONE);
                    Logger.printDebug(() -> "Meta AI: folded the summary card out of search results");
                } else {
                    params.height = height;
                    // Instagram's own visibility, unless it changed it while the row was folded.
                    if (row.getVisibility() == View.GONE) row.setVisibility(visibility);
                }
                folded = fold;
                row.setLayoutParams(params);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.META_AI, "summary card", failure);
            }
        }
    }

    /**
     * Injected where Home's top bar takes each button name from the server's list. Answers null for
     * Meta AI's button ("meta_ai"), which the bar skips like an empty name, while the search switch
     * is on, and the name otherwise, or when anything goes wrong. Never throws.
     */
    public static String homeButton(String name) {
        if (!HOME_BUTTONS.contains(name)) return name;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return name;
            Logger.printDebug(() -> "Meta AI: left Home's Meta AI button out");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "home button", failure);
            return name;
        }
    }

    /**
     * Passed the native composer's button and show flag before its view lookup. Only Meta AI's
     * buttons answer false with the search switch on. Instagram then uses its own hide path,
     * including its layout callback, and never inflates a missing button just to hide it.
     */
    public static boolean composerButton(Object button, int visible) {
        if (visible == 0) return false;
        try {
            if (!(button instanceof Enum<?>) || !COMPOSER_BUTTONS.contains(((Enum<?>) button).name())) return true;
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return true;
            Logger.printDebug(() -> "Meta AI: left out a message composer button");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "composer button", failure);
            return true;
        }
    }

    /** Passed only the optional Hatch inbox row, before Instagram's own null and eligibility checks. */
    public static Object inboxRow(Object row) {
        if (row == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SEARCH.get()) return row;
            Logger.printDebug(() -> "Meta AI: left out the optional inbox row");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "inbox row", failure);
            return row;
        }
    }

    /**
     * Injected after each ask for a reel's About this reel summary, the block at the top of its More
     * menu with the summary, its Sources and an Ask Meta AI box, with Instagram's answer. Answers
     * null, which every menu takes as a reel with no summary and leaves the block out, while Hide
     * About this reel is on, and the answer otherwise, or when anything goes wrong. Never throws.
     */
    public static Object aboutThisReel(Object summary) {
        if (summary == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_ABOUT_THIS_REEL.get()) return summary;
            Logger.printDebug(() -> "Meta AI: left out About this reel");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "About this reel", failure);
            return summary;
        }
    }

    /**
     * Injected in place of the call that adds the Ask Meta AI box to About this reel's summary row.
     * Leaves the box out while Hide Ask Meta AI is on, and adds it the way Instagram does otherwise,
     * or when anything goes wrong. Only Instagram's own addView can throw, as it would unpatched.
     */
    public static void askMetaAiBox(ViewGroup row, View box) {
        boolean hide;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            hide = Utils.settingsReady() && Settings.HIDE_ASK_META_AI.get();
            if (hide) Logger.printDebug(() -> "Meta AI: left the Ask Meta AI box out of About this reel");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Ask Meta AI box", failure);
            hide = false;
        }
        if (!hide) row.addView(box);
    }

    /**
     * Injected right after the share sheet's target builder checks whether a name is Meta AI's
     * ("hatch"), with the answer as an int (non-zero is yes). Answers false, which the builder takes
     * as a name it doesn't know and builds no target for, while Hide Meta AI in the share sheet is
     * on, and the answer otherwise, or when anything goes wrong. Never throws.
     */
    public static boolean shareTarget(int matches) {
        if (matches == 0) return false;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_SHARE_TARGET.get()) return true;
            Logger.printDebug(() -> "Meta AI: left Meta AI's target out of the share sheet");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "share target", failure);
            return true;
        }
    }

    /** The quick promotion slots of Home's feed header and your own profile's header. */
    static final Set<String> MUSE_SLOTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MAIN_FEED", "PERSONAL_PROFILE")));

    private static final Pattern MUSE_WORD = Pattern.compile("(?<![\\p{L}\\p{N}])muse(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Reads a promotion as {slot, text, text, text}. Tests stand in. */
    static volatile Function<Object, String[]> promotionReader = MetaAi::readPromotion;

    /**
     * Whether any of the texts names Muse as a whole word, in any case. "Muse" is the product's
     * name, so it reads the same in every language; "museum" and "amused" don't count.
     */
    static boolean mentionsMuse(String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && !text.isEmpty() && MUSE_WORD.matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * Injected at the head of the method that turns a quick promotion into the card Instagram builds
     * for it, passed the promotion. Answers true, which the patch turns into "no card" (a promotion
     * with nothing to show is a case that method already handles), for a promotion bound for Home's
     * feed header or your own profile whose title or text names Muse, while Hide the Muse card on
     * profiles is on. Every other promotion, and every failure, answers false. Logs each promotion
     * it sees with its slot and texts when debug logging is on. Never throws.
     */
    public static boolean dropsPromotion(Object promotion) {
        if (promotion == null) return false;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            boolean on = Utils.settingsReady() && Settings.HIDE_META_AI_PROFILE_BANNER.get();
            String[] fields = promotionReader.apply(promotion);
            if (fields == null || fields.length == 0) return false;
            String slot = fields[0];
            boolean inSlot = slot == null || MUSE_SLOTS.contains(slot);
            boolean drop = on && inSlot && mentionsMuse(Arrays.copyOfRange(fields, 1, fields.length));
            Logger.printDebug(() -> "Meta AI: promotion slot=" + slot + " texts=" + quoted(fields)
                    + " drop=" + drop);
            return drop;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "promotion", failure);
            return false;
        }
    }

    private static String quoted(String[] fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < fields.length; i++) {
            String text = fields[i];
            if (text != null && text.length() > 80) text = text.substring(0, 80) + "...";
            out.append(i > 1 ? " | " : "").append('"').append(text).append('"');
        }
        return out.toString();
    }

    static String[] readPromotion(Object promotion) {
        String[] fields = new String[4];
        fields[0] = guarded(() -> promotionSlot(promotion));
        fields[1] = guarded(() -> promotionText1(promotion));
        fields[2] = guarded(() -> promotionText2(promotion));
        fields[3] = guarded(() -> promotionText3(promotion));
        return fields;
    }

    private static String guarded(java.util.concurrent.Callable<String> read) {
        try {
            return read.call();
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * Stubs the patch fills to read a quick promotion: the name of its slot, and the three texts of
     * its creative (title, content and the other, as Instagram orders them). Null as built, so a
     * build where the patch couldn't fill them drops nothing.
     */
    public static String promotionSlot(Object promotion) {
        return null;
    }

    public static String promotionText1(Object promotion) {
        return null;
    }

    public static String promotionText2(Object promotion) {
        return null;
    }

    public static String promotionText3(Object promotion) {
        return null;
    }

    /**
     * Injected at the start of the method that builds a profile's Muse banner (the Meet Muse card
     * with its Try Muse button). Answers true to have it answer no banner, which every place that
     * adds the banner to a list takes as a profile without one, while Hide the Muse card on
     * profiles is on. Answers false otherwise, or when anything goes wrong. Never throws.
     */
    public static boolean museBanner() {
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_PROFILE_BANNER.get()) return false;
            Logger.printDebug(() -> "Meta AI: left the Muse card out of a profile");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Muse banner", failure);
            return false;
        }
    }

    /**
     * Injected where the method that fills your profile's top bar reaches the Muse button's item.
     * Answers true to have it go on to the bar's next item without adding the button, while Hide
     * the Muse button on your profile is on. Answers false otherwise, or when anything goes wrong.
     * Never throws.
     */
    public static boolean museButton() {
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_PROFILE_BUTTON.get()) return false;
            Logger.printDebug(() -> "Meta AI: left the Muse button out of the profile's top bar");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Muse button", failure);
            return false;
        }
    }

    /**
     * Injected at the start of the method that decides a reel's Blend invite button. Answers true to
     * have it answer "no Blend" without working anything out, while Hide Blend on reels is on, and
     * false otherwise, or when anything goes wrong. Never throws.
     */
    public static boolean blendButton() {
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_BLEND.get()) return false;
            Logger.printDebug(() -> "Meta AI: left the Blend button off a reel");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "Blend button", failure);
            return false;
        }
    }

    /**
     * Injected at the return of Instagram's feed item parse helper. Answers null for a Meta AI unit
     * while Hide Meta AI posts is on, and [item] itself otherwise, or when anything goes wrong.
     * Never throws.
     */
    public static Object filter(Object item) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.META_AI);
            String kind = FeedItemKinds.kindIn(item, FEED_UNITS, FamilyNames.META_AI);
            if (kind == null) return item;
            FeedFilterCounters.sawKind(ROUTE, kind);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_POSTS.get()) return item;
            FeedFilterCounters.removed(ROUTE, 1, kind);
            Logger.printDebug(() -> "Meta AI: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.META_AI, "feed item", failure);
            return item;
        }
    }
}
