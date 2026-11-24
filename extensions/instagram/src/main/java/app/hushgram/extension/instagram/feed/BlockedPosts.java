/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide posts when you block someone" patch (#861).
 *
 * <p>Blocking someone from a post's menu in Home leaves their posts where they are until Home is
 * refreshed. Home's list already has a way to drop one account's posts at once: muting someone's
 * posts makes it call its own removal with that account's id, which takes every post of theirs out
 * of the list and redraws it. The patch tells {@link #feed} about each Home list as Instagram makes
 * it, and {@link #blocked} about the account a block that went through was for, as the block's
 * callback takes the server's answer. With the switch on, that account's id goes to each Home list's
 * removal on the main thread, the same call muting makes.
 *
 * <p>{@link #userId} and {@link #remove} are bridges the patch writes: the first reads an account's
 * id the way Home's removal reads each post's owner, the second is that removal.
 */
public final class BlockedPosts {
    /** What {@link HookStatus} counts: blocks whose account was taken out of the Home lists. */
    static final String REMOVED = "blocked accounts taken out of Home";

    private static final String HOOK = "blocked posts";

    /** The Home lists Instagram has made. Weak, so a list that goes away goes from here too. */
    private static final Set<Object> FEEDS = Collections.newSetFromMap(new WeakHashMap<>());

    /** Reads an account's id in place of {@link #userId}, for a test. */
    @Nullable
    static volatile Function<Object, String> userIdForTests;

    /** Takes an account out of a list in place of {@link #remove}, for a test. */
    @Nullable
    static volatile BiConsumer<Object, String> removalForTests;

    private BlockedPosts() {
    }

    /** Told each Home list as Instagram makes it. Never throws. */
    public static void feed(@Nullable Object list) {
        try {
            HookStatus.invoked(FamilyNames.BLOCKED_POSTS);
            if (list == null) return;
            synchronized (FEEDS) {
                FEEDS.add(list);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BLOCKED_POSTS, HOOK, failure);
        }
    }

    /**
     * Told the account a block that went through was for. With the switch on, that account's posts
     * come out of every Home list, on the main thread. Never throws.
     */
    public static void blocked(@Nullable Object account) {
        try {
            HookStatus.invoked(FamilyNames.BLOCKED_POSTS);
            if (account == null || !on()) return;
            Function<Object, String> reader = userIdForTests;
            String id = reader != null ? reader.apply(account) : userId(account);
            if (id == null || id.isEmpty()) return;
            Utils.runOnMainThreadNowOrLater(() -> take(id));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BLOCKED_POSTS, HOOK, failure);
        }
    }

    /** Takes the account with {@code id} out of each Home list, each on its own. */
    private static void take(String id) {
        List<Object> lists;
        synchronized (FEEDS) {
            lists = new ArrayList<>(FEEDS);
        }
        if (lists.isEmpty()) return;
        BiConsumer<Object, String> removal = removalForTests;
        for (Object list : lists) {
            try {
                if (removal != null) removal.accept(list, id);
                else remove(list, id);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.BLOCKED_POSTS, HOOK, failure);
            }
        }
        HookStatus.counted(FamilyNames.BLOCKED_POSTS, REMOVED);
    }

    /** The account's id, as Home's removal reads each post's owner. The patch writes the body. */
    @Nullable
    public static String userId(Object account) {
        return null;
    }

    /** Home's own removal of one account's posts from {@code list}. The patch writes the body. */
    public static void remove(Object list, String id) {
    }

    /** How many Home lists are still around, for a test. */
    static int feedsForTests() {
        synchronized (FEEDS) {
            return FEEDS.size();
        }
    }

    /** Forgets every Home list, for a test. */
    static void forgetFeedsForTests() {
        synchronized (FEEDS) {
            FEEDS.clear();
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_BLOCKED_POSTS.get();
    }
}
