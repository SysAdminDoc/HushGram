/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the second switch of the "Show if a profile follows you" patch: Doesn't follow you on
 * the rows of your own Following list.
 *
 * <p>Every row of a follow list goes through one binder, which hands the row's account and view to
 * a static call that fills in the username, the name line under it and the follow button. The patch
 * calls {@link #row} right after that, with the binder, the row's view and its account. The mark
 * goes after the name on the name line, or takes that line on its own for an account with no name.
 *
 * <p>Only your own Following list is marked: the binder's list has to be one of Instagram's two
 * kinds of Following list, and its owner the account signed in. Followers, someone else's Following
 * list and every other list Instagram draws with this binder stay as they are.
 *
 * <p>Whether an account follows you is taken only from an answer Instagram's server gave to its batch
 * friendship request (friendships/show_many) in this run, which the Following list asks with
 * followed_by, and only while the account's own status doesn't say it does. Instagram caches a
 * status for every account it has drawn anywhere, and one from a feed or a reel can carry a
 * followed_by no one asked the server for; the Following list then skipped asking again for those
 * rows, and they read as not following you (#40). With the switch on, the patch has your own
 * Following list ask about every row rather than only the ones it knows nothing about
 * ({@link #known}), and hands each answer here ({@link #answered}). A row with no answer yet says
 * nothing.
 *
 * <p>Rows are recycled, so a row this marked may come back for another account. Instagram fills the
 * name line in again on each bind, and a mark it didn't overwrite is taken back here before the row
 * is looked at afresh. With the switch off, HushGram paused, the settings not read yet or anything
 * thrown, the row stays as Instagram drew it.
 */
public final class FollowingList {
    static final String REFETCH = "following list refetch";
    static final String ANSWER = "following list answer";

    /** How many answers are kept: about a dozen pages of a long list, oldest dropped first. */
    static final int MAX_ANSWERS = 4096;

    /**
     * Whether each account follows you, as the server last answered a batch request asked with
     * followed_by, by the signed-in account and the other account's ID.
     */
    private static final Map<String, Boolean> answers = new Answers();

    /** The answers, least recently read first, past [MAX_ANSWERS] the oldest dropped. */
    private static final class Answers extends LinkedHashMap<String, Boolean> {
        Answers() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_ANSWERS;
        }
    }

    /** Instagram's names for its Following lists, which it keeps: the full list and the simplified one. */
    static final Set<String> FOLLOWING_KINDS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("FOLLOWING", "FOLLOWING_SIMPLIFIED")));

    static final String ROW = "following list row";

    /**
     * The name lines this marked, with what each held before and what it was given. Weak, so a row
     * gone from the screen isn't kept.
     */
    private static final Map<TextView, Mark> marked = new WeakHashMap<>();

    private FollowingList() {
    }

    /** What a row's mark replaced, so it can be taken back. */
    private static final class Mark {
        final CharSequence before;
        final CharSequence wrote;
        final int visibility;

        Mark(CharSequence before, CharSequence wrote, int visibility) {
            this.before = before;
            this.wrote = wrote;
            this.visibility = visibility;
        }
    }

    /** Where {@link #row} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Reader {
        /** The kind of list the binder draws, Instagram's enum constant, or null. */
        @Nullable
        Object listKind(Object binder);

        /** The ID of the account whose list the binder draws, or null. */
        @Nullable
        String listOwnerId(Object binder);

        /** The ID of the account signed in, or null. */
        @Nullable
        String viewerId(Object binder);

        /** Whether [user] follows you, as Instagram's cached status says, or null when it doesn't say. */
        @Nullable
        Boolean followedBy(Object user);

        /** Whether [user] follows [viewer], as the server last answered when asked, or null. */
        @Nullable
        Boolean answer(String viewer, Object user);

        /** The name line of the row whose view holder is [holder]. */
        @Nullable
        TextView subtitle(Object holder);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public Object listKind(Object binder) {
            return FollowingList.listKind(binder);
        }

        @Override
        public String listOwnerId(Object binder) {
            return FollowingList.listOwnerId(binder);
        }

        @Override
        public String viewerId(Object binder) {
            return FollowingList.viewerId(binder);
        }

        @Override
        public Boolean followedBy(Object user) {
            return FriendshipStatus.friendshipFollowedBy(user);
        }

        @Override
        public Boolean answer(String viewer, Object user) {
            return FollowingList.answer(viewer, FriendshipStatus.userId(user));
        }

        @Override
        public TextView subtitle(Object holder) {
            return FollowingList.subtitle(holder);
        }
    };

    /**
     * Injected right after a follow list's binder has filled in a row. Adds Doesn't follow you to the
     * name line when the row is on your own Following list and its account doesn't follow you back.
     * Never throws.
     */
    public static void row(Object binder, int position, View row, Object user) {
        row(binder, row, user, PATCHED, FollowingList::switchedOn);
    }

    static void row(Object binder, View row, Object user, Reader reader, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            boolean marking = on.getAsBoolean();
            if (!marking && nothingMarked()) return;
            Object holder = row == null ? null : row.getTag();
            TextView subtitle = holder == null ? null : reader.subtitle(holder);
            if (subtitle == null) return;
            unmark(subtitle);
            if (!marking) return;
            String viewer = reader.viewerId(binder);
            if (!ownFollowingList(reader.listKind(binder), reader.listOwnerId(binder), viewer)) return;
            // Only the server's no to a request that asked counts, and a later yes on the account's
            // own status (its profile, say) overrules it. Before the server has answered, nothing.
            if (!Boolean.FALSE.equals(reader.answer(viewer, user)) || Boolean.TRUE.equals(reader.followedBy(user))) return;
            mark(subtitle, L10n.t("Doesn't follow you"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ROW, failure);
        }
    }

    /**
     * Injected where a follow list decides which of a page's rows to ask the server about, with a
     * row's cached friendship and the list's state holder: Instagram skips a row whose cached status
     * already says whether it follows you. Answers null, so the row is asked about, on your own
     * Following list with the switch on, and the friendship otherwise. Never throws.
     */
    @Nullable
    public static Object known(@Nullable Object friendship, Object list) {
        return known(friendship, list, FollowingList::fetchKind, FollowingList::fetchOwnerId,
                FollowingList::fetchViewerId, FollowingList::switchedOn);
    }

    interface ListPart {
        @Nullable
        Object of(Object list);
    }

    static Object known(@Nullable Object friendship, Object list, ListPart kind, ListPart owner, ListPart viewer,
                        BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            if (friendship == null || !on.getAsBoolean()) return friendship;
            Object ownerId = owner.of(list), viewerId = viewer.of(list);
            if (!(ownerId instanceof String) || !(viewerId instanceof String)) return friendship;
            return ownFollowingList(kind.of(list), (String) ownerId, (String) viewerId) ? null : friendship;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, REFETCH, failure);
            return friendship;
        }
    }

    /**
     * Injected in Instagram's parser of a batch friendship answer, once per account it answered
     * about, with the account, the status the server sent and the signed-in session. Keeps
     * whether it follows you when the server said so either way. Never throws.
     */
    public static void answered(@Nullable Object user, @Nullable Object status, @Nullable Object session) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            if (!switchedOn() || user == null || status == null || session == null) return;
            remember(sessionUserId(session), FriendshipStatus.userId(user), statusFollowedBy(status));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ANSWER, failure);
        }
    }

    /** Keeps an answer the server gave, when it gave one, for the account it was about. */
    static void remember(@Nullable String viewer, @Nullable String user, @Nullable Boolean followedBy) {
        if (viewer == null || viewer.isEmpty() || user == null || user.isEmpty() || followedBy == null) return;
        synchronized (answers) {
            answers.put(viewer + "/" + user, followedBy);
        }
    }

    /** What the server last answered about whether [user] follows [viewer], or null. */
    @Nullable
    static Boolean answer(@Nullable String viewer, @Nullable String user) {
        if (viewer == null || user == null) return null;
        synchronized (answers) {
            return answers.get(viewer + "/" + user);
        }
    }

    static void forgetAnswers() {
        synchronized (answers) {
            answers.clear();
        }
    }

    /** Whether the list is one of your own Following lists: its kind is a Following list and you own it. */
    static boolean ownFollowingList(@Nullable Object kind, @Nullable String owner, @Nullable String viewer) {
        if (!(kind instanceof Enum)) return false;
        if (!FOLLOWING_KINDS.contains(((Enum<?>) kind).name())) return false;
        return owner != null && !owner.isEmpty() && owner.equals(viewer);
    }

    /**
     * Puts [label] after the name, or on the hidden name line of an account with no name, which it
     * then shows. Whatever goes wrong partway puts the line back as it was.
     */
    private static void mark(TextView subtitle, String label) {
        CharSequence before = subtitle.getText();
        int visibility = subtitle.getVisibility();
        CharSequence wrote = visibility == View.VISIBLE ? FriendshipStatus.joined(before, label) : label;
        try {
            subtitle.setText(wrote);
            if (visibility != View.VISIBLE) subtitle.setVisibility(View.VISIBLE);
        } catch (RuntimeException failure) {
            subtitle.setText(before);
            subtitle.setVisibility(visibility);
            throw failure;
        }
        synchronized (marked) {
            marked.put(subtitle, new Mark(before, wrote, visibility));
        }
        Logger.printDebug(() -> "Following list: marked an account that doesn't follow you");
    }

    /**
     * Takes back the mark on [subtitle], when it still holds it. Once Instagram has filled the line
     * in again for the row's next account, the mark is gone already and the line is left alone.
     */
    private static void unmark(TextView subtitle) {
        Mark mark;
        synchronized (marked) {
            mark = marked.remove(subtitle);
        }
        if (mark == null || !TextUtils.equals(subtitle.getText(), mark.wrote)) return;
        subtitle.setText(mark.before);
        if (mark.visibility != View.VISIBLE && subtitle.getVisibility() == View.VISIBLE) {
            subtitle.setVisibility(mark.visibility);
        }
    }

    private static boolean nothingMarked() {
        synchronized (marked) {
            return marked.isEmpty();
        }
    }

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.MARK_FOLLOWING_LIST.get();
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the kind of list [binder] draws, Instagram's enum constant, or null. */
    @Nullable
    public static Object listKind(Object binder) {
        return null;
    }

    /** Filled in by the patch: the ID of the account whose list [binder] draws, or null. */
    @Nullable
    public static String listOwnerId(Object binder) {
        return null;
    }

    /** Filled in by the patch: the ID of the account signed in on [binder]'s list, or null. */
    @Nullable
    public static String viewerId(Object binder) {
        return null;
    }

    /** Filled in by the patch: the name line of the row whose view holder is [holder]. */
    @Nullable
    public static TextView subtitle(Object holder) {
        return null;
    }

    /** Filled in by the patch: the kind of list a follow list's state holder [list] fetches, or null. */
    @Nullable
    public static Object fetchKind(Object list) {
        return null;
    }

    /** Filled in by the patch: the ID of the account whose list [list] fetches, or null. */
    @Nullable
    public static Object fetchOwnerId(Object list) {
        return null;
    }

    /** Filled in by the patch: the ID of the account signed in on [list], or null. */
    @Nullable
    public static Object fetchViewerId(Object list) {
        return null;
    }

    /** Filled in by the patch: whether the batch answer's [status] says the account follows you, or null. */
    @Nullable
    public static Boolean statusFollowedBy(Object status) {
        return null;
    }

    /** Filled in by the patch: the ID of the account signed in on [session], or null. */
    @Nullable
    public static String sessionUserId(Object session) {
        return null;
    }
}
