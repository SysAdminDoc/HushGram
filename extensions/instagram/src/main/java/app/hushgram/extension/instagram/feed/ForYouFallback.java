/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Start Home on Following's "Fall back to Following" switch (#308).
 *
 * <p>Some accounts get a For you made only of suggested posts: page after page of Home's response
 * holds suggested posts ("explore_story", paged from feed_recs), nothing from an account you
 * follow and no end of feed card. With Hide suggested posts on, Home stays empty, and the next
 * page brings more of the same, while Following has posts.
 *
 * <p>{@link FeedSuggestions#homePageParsed} hands each page of Home's response to
 * {@link #pageParsed}. After {@link #STREAK} pages in a row with suggested posts and nothing else
 * that counts, for one signed-in account that hasn't had a followed post or an end of feed card in
 * this run, that account falls back to Following, once: it's saved, the report counts it and one
 * toast says so. From then on {@link FollowingFeed#flag} turns the feed picker on for it and
 * {@link FollowingFeed#saved} answers Following while no feed is picked, the way Start Home on
 * Following does for every account. Nothing is asked for: this only reads the pages Home asked for
 * itself, and {@link HomeNextPage} stops asking for For you's next pages on that account.
 *
 * <p>A feed you picked wins. A remembered pick passes through {@link FollowingFeed#saved}
 * unchanged, and an account seen with one, or with Start Home on Following on (For you then only
 * shows when you pick it), is never moved. The switch is off to start, and while it's off, HushGram
 * is paused or the settings aren't read yet, Instagram's own answers stand.
 */
public final class ForYouFallback {
    /** How many pages in a row of nothing but suggested posts move an account to Following. */
    static final int STREAK = 3;

    /** The report's count of accounts moved to Following. */
    static final String FELL_BACK = "For you held only suggestions, Home moved to Following";

    /** The report's count of accounts whose For you held only suggestions and kept a feed picked there. */
    static final String PICK_KEPT = "For you held only suggestions, the picked feed kept";

    /** The report's count of Home openings answered Following for a moved account. */
    static final String OPENED = "Home opened on Following for an account moved there";

    /** The report's count of next page requests left out for a moved account. */
    static final String NOT_ASKED = "next page not asked for an account with only suggestions";

    private static final Object LOCK = new Object();

    /** The account the current streak counts pages for. Guarded by {@link #LOCK}. */
    @Nullable private static String streakAccount;

    /** Pages in a row with only suggested posts for {@link #streakAccount}. Guarded by {@link #LOCK}. */
    private static int streak;

    /** Accounts that had a followed post or an end of feed card in this run. Guarded by {@link #LOCK}. */
    private static final Set<String> settled = new HashSet<>();

    /** Accounts seen with a feed picked in this run. Guarded by {@link #LOCK}. */
    private static final Set<String> picked = new HashSet<>();

    /** Accounts whose For you was found to hold only suggestions in this run. Guarded by {@link #LOCK}. */
    private static final Set<String> onlySuggestions = new HashSet<>();

    /** The saved list, parsed, and the text it was parsed from. */
    private static final class Parsed {
        final String text;
        final Set<String> accounts;

        Parsed(String text, Set<String> accounts) {
            this.text = text;
            this.accounts = accounts;
        }
    }

    private static volatile Parsed parsed = new Parsed("", Collections.emptySet());

    /** Shows the toast. Tests stand in. */
    static volatile Consumer<String> toaster = Utils::showToastShort;

    /** Whether Start Home on Following's patch is in this build. Tests stand in. */
    static volatile BooleanSupplier followingInBuild = SettingsStatus::followingFeed;

    private ForYouFallback() {
    }

    /**
     * Called by {@link FeedSuggestions#homePageParsed} for each page of Home's response that held
     * items: how many suggested posts it read, how many posts that aren't suggestions, and how
     * many end of feed cards. Never throws.
     */
    static void pageParsed(int suggested, int followed, int endCards) {
        try {
            if (!Utils.settingsReady() || !Settings.FOLLOWING_FALLBACK.get() || !followingInBuild.getAsBoolean()) return;
            String account = HiddenAccounts.current();
            if (account == null || account.isEmpty()) return;
            boolean moved;
            boolean pickKept;
            synchronized (LOCK) {
                if (!account.equals(streakAccount)) {
                    streakAccount = account;
                    streak = 0;
                }
                if (followed > 0 || endCards > 0) {
                    streak = 0;
                    settled.add(account);
                    return;
                }
                if (suggested <= 0 || settled.contains(account) || onlySuggestions.contains(account)) return;
                if (++streak < STREAK) return;
                onlySuggestions.add(account);
                pickKept = picked.contains(account) || Settings.START_ON_FOLLOWING.get();
                moved = !pickKept && add(account);
            }
            if (pickKept) {
                HookStatus.counted(FamilyNames.FOLLOWING_FEED, PICK_KEPT);
                Logger.printDebug(() -> "For you fallback: For you holds only suggestions, the picked feed stays");
                return;
            }
            if (!moved) return;
            HookStatus.counted(FamilyNames.FOLLOWING_FEED, FELL_BACK);
            Logger.printDebug(() -> "For you fallback: " + STREAK + " pages of only suggestions, Home moves to Following");
            toaster.accept(L10n.t("Only suggestions in For you. Home will open on Following."));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "for you fallback", failure);
        }
    }

    /** Saves [account] to the list. Answers false when it was on it already. Called holding {@link #LOCK}. */
    private static boolean add(String account) {
        Set<String> accounts = new LinkedHashSet<>(saved());
        if (!accounts.add(account)) return false;
        Settings.FOLLOWING_FALLBACK_ACCOUNTS.save(String.join("\n", accounts));
        return true;
    }

    /**
     * Called by {@link FollowingFeed#saved} when Instagram hands back a feed you picked, so the
     * signed-in account keeps it. Never throws.
     */
    static void sawPick() {
        try {
            String account = HiddenAccounts.current();
            if (account == null || account.isEmpty()) return;
            synchronized (LOCK) {
                picked.add(account);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "for you fallback pick", failure);
        }
    }

    /**
     * Whether the signed-in account was moved to Following, with the switch on. False while
     * HushGram is paused or the settings aren't read. Never throws.
     */
    static boolean movedToFollowing() {
        try {
            if (!Utils.settingsReady() || !Settings.FOLLOWING_FALLBACK.get()) return false;
            String account = HiddenAccounts.current();
            return account != null && !account.isEmpty() && saved().contains(account);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "for you fallback account", failure);
            return false;
        }
    }

    /**
     * Whether {@link HomeNextPage} leaves the next page alone: the switch is on and the signed-in
     * account's For you held only suggestions in this run, or it was moved to Following. Never throws.
     */
    static boolean holdsNextPage() {
        try {
            if (!Utils.settingsReady() || !Settings.FOLLOWING_FALLBACK.get()) return false;
            String account = HiddenAccounts.current();
            if (account == null || account.isEmpty()) return false;
            synchronized (LOCK) {
                if (onlySuggestions.contains(account)) return true;
            }
            return saved().contains(account);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FOLLOWING_FEED, "for you fallback next page", failure);
            return false;
        }
    }

    /** The accounts moved to Following, read from the saved list, parsed once per change of it. */
    static Set<String> saved() {
        String text = Settings.FOLLOWING_FALLBACK_ACCOUNTS.savedValue();
        if (text == null) text = "";
        Parsed known = parsed;
        if (text.equals(known.text)) return known.accounts;
        Set<String> accounts = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            String id = line.trim();
            if (!id.isEmpty()) accounts.add(id);
        }
        Set<String> frozen = Collections.unmodifiableSet(accounts);
        parsed = new Parsed(text, frozen);
        return frozen;
    }

    /** Forgets this run's streak, picks and pages. Tests call it. */
    static void resetForTests() {
        synchronized (LOCK) {
            streakAccount = null;
            streak = 0;
            settled.clear();
            picked.clear();
            onlySuggestions.clear();
        }
        parsed = new Parsed("", Collections.emptySet());
        toaster = Utils::showToastShort;
        followingInBuild = SettingsStatus::followingFeed;
    }
}
