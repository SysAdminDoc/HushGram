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
 * also gives the results page its header (Back and the query), so it stays on, and the view the
 * page looks the bar up in goes through {@link #followUpBar} as the page sets the bar up instead.
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
 */
public final class MetaAi {
    /**
     * The feed item kinds of Meta AI's units, by the constant names Instagram 449 gives them:
     * Vibes (its feed of AI videos), Meta AI chats ("hatch") and Imagine pictures of you ("memu").
     * Hide Reels in the feed takes out the first two as well.
     */
    static final Set<String> FEED_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT", "MEMU_IN_FEED_UNIT")));

    /** The name Home's top bar list gives Meta AI's button on 449. */
    static final String HOME_BUTTON = "meta_ai";

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
     * Injected where the search results page checks the view it looks its "Ask a follow-up…" bar
     * up in, with that view. Answers null, which Instagram takes as a page without the bar, while
     * the search switch is on, and the view otherwise, or when anything goes wrong. Never throws.
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
     * Injected where Home's top bar takes each button name from the server's list. Answers null for
     * Meta AI's button ("meta_ai"), which the bar skips like an empty name, while the search switch
     * is on, and the name otherwise, or when anything goes wrong. Never throws.
     */
    public static String homeButton(String name) {
        if (!HOME_BUTTON.equals(name)) return name;
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
