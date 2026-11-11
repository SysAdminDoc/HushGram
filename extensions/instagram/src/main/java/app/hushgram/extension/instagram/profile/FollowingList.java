/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.method.SingleLineTransformationMethod;
import android.text.method.TransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * goes before the name on the name line, so a long name is what the line's end cuts short (#351),
 * or takes that line on its own for an account with no name.
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
 *
 * <p>The third switch puts the accounts that don't follow you back first ({@link #ordered}). Instagram
 * rebuilds every row of a follow list from the whole list of accounts loaded so far, at each page and
 * each change, and the patch hands that list here as it does. The sort is stable and works on a copy,
 * with the same answers the mark goes by, so it covers every page loaded and never just the newest.
 * The answers come after the rows are drawn, so once the server has said no to an account, the
 * list is rebuilt once more, a moment later ({@link #rebuildSoon}).
 *
 * <p>A switch under the mark's own shows it on each row's profile picture instead (#572): a green
 * check when the account follows you back and a red cross when it doesn't ({@link FollowBadge}), by
 * the same answers, and nothing before Instagram has checked. The badge is the picture's foreground,
 * and what the picture had there before goes back when the row is bound again. A row whose picture
 * can't be found gets the words.
 */
public final class FollowingList {
    static final String REFETCH = "following list refetch";
    static final String ANSWER = "following list answer";
    static final String ORDER = "following list order";
    static final String REBUILD = "following list rebuild";

    /** How many lines a marked name line may take. */
    static final int MARKED_LINES = 2;

    /** How long after the last no from the server the list is rebuilt, so one page's answers rebuild it once. */
    static final long REBUILD_DELAY_MS = 400;

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

    /** The state holder of the list last put in order, weakly, so it can be asked to rebuild its rows. */
    private static WeakReference<Object> orderedList = new WeakReference<>(null);
    private static boolean rebuildQueued;

    /**
     * The name lines this marked, with what each held before and what it was given. Weak, so a row
     * gone from the screen isn't kept.
     */
    private static final Map<TextView, Mark> marked = new WeakHashMap<>();

    /** The profile pictures this put a badge on, with what each had as its foreground before. Weak, like [marked]. */
    private static final Map<View, Badged> badged = new WeakHashMap<>();

    /** Instagram's names for a follow list row's profile picture: the follow list's own, then the shared user row's. */
    static final String[] PICTURES = {"follow_list_user_imageview", "row_user_imageview"};

    /** Their ids, looked up once: -1 until then, 0 when the build has no such id. */
    static final int[] pictureIds = {-1, -1};

    private FollowingList() {
    }

    /** What a badge replaced on a profile picture, so it can be put back. */
    private static final class Badged {
        final FollowBadge badge;
        @Nullable final Drawable before;
        final int gravity;

        Badged(FollowBadge badge, @Nullable Drawable before, int gravity) {
            this.badge = badge;
            this.before = before;
            this.gravity = gravity;
        }
    }

    /** What a row's mark replaced, so it can be taken back. */
    private static final class Mark {
        final CharSequence before;
        final CharSequence wrote;
        final int visibility;
        @Nullable final TransformationMethod method;
        final int maxLines;

        Mark(CharSequence before, CharSequence wrote, int visibility, @Nullable TransformationMethod method, int maxLines) {
            this.before = before;
            this.wrote = wrote;
            this.visibility = visibility;
            this.method = method;
            this.maxLines = maxLines;
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

        /** The profile picture on [row], or null. */
        @Nullable
        View picture(View row);
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

        @Override
        public View picture(View row) {
            return FollowingList.picture(row);
        }
    };

    /**
     * Injected right after a follow list's binder has filled in a row. Adds Doesn't follow you to the
     * name line when the row is on your own Following list and its account doesn't follow you back,
     * or with the picture switch on, a check or a cross on its profile picture. Never throws.
     */
    public static void row(Object binder, int position, View row, Object user) {
        row(binder, row, user, PATCHED, FollowingList::switchedOn, FollowingList::pictureSwitchedOn);
    }

    static void row(Object binder, View row, Object user, Reader reader, BooleanSupplier on) {
        row(binder, row, user, reader, on, () -> false);
    }

    static void row(Object binder, View row, Object user, Reader reader, BooleanSupplier on, BooleanSupplier onPicture) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            boolean marking = on.getAsBoolean();
            if (!marking && nothingMarked()) return;
            Object holder = row == null ? null : row.getTag();
            TextView subtitle = holder == null ? null : reader.subtitle(holder);
            View picture = row == null ? null : reader.picture(row);
            if (subtitle != null) unmark(subtitle);
            if (picture != null) unbadge(picture);
            if (!marking || (subtitle == null && picture == null)) return;
            String viewer = reader.viewerId(binder);
            if (!ownFollowingList(reader.listKind(binder), reader.listOwnerId(binder), viewer)) return;
            // Only the server's no to a request that asked counts, and a later yes on the account's
            // own status (its profile, say) overrules it. Before the server has answered, nothing.
            Boolean said = reader.answer(viewer, user);
            boolean followsYou = Boolean.TRUE.equals(reader.followedBy(user));
            boolean notBack = Boolean.FALSE.equals(said) && !followsYou;
            if (picture != null && onPicture.getAsBoolean()) {
                if (notBack) badge(picture, false);
                else if (followsYou || Boolean.TRUE.equals(said)) badge(picture, true);
                return;
            }
            if (notBack && subtitle != null) mark(subtitle, L10n.t("Doesn't follow you"));
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
                FollowingList::fetchViewerId, FollowingList::asking);
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
            if (!asking() || user == null || status == null || session == null) return;
            Boolean followedBy = statusFollowedBy(status);
            remember(sessionUserId(session), FriendshipStatus.userId(user), followedBy);
            if (Boolean.FALSE.equals(followedBy) && orderSwitchedOn()) rebuildSoon();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ANSWER, failure);
        }
    }

    /**
     * Injected where a follow list builds its rows, with the list of accounts loaded so far and the
     * list's state holder. Answers the list with the accounts that don't follow you back first, each
     * group in Instagram's order, when it's your own Following list and the switch is on, and the list
     * itself otherwise. The answer is a copy and Instagram's list is never changed. Recent unfollowers
     * looks at the same list first ({@link Unfollowers#seen}). Never throws.
     */
    public static Object ordered(Object users, Object list) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            Unfollowers.seen(users, list);
            if (!orderSwitchedOn()) return users;
            return ordered(users, list, orderBinder(list), PATCHED, FollowingList::orderSwitchedOn);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ORDER, failure);
            return users;
        }
    }

    static Object ordered(Object users, Object list, @Nullable Object binder, Reader reader, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            if (!(users instanceof List) || list == null || binder == null || !on.getAsBoolean()) return users;
            String viewer = reader.viewerId(binder);
            if (!ownFollowingList(reader.listKind(binder), reader.listOwnerId(binder), viewer)) return users;
            synchronized (FollowingList.class) {
                orderedList = new WeakReference<>(list);
            }
            List<Object> notBack = new ArrayList<>();
            List<Object> rest = new ArrayList<>();
            boolean moved = false;
            for (Object user : (List<?>) users) {
                // Only the server's no to a request that asked counts, as it does for the mark.
                if (Boolean.FALSE.equals(reader.answer(viewer, user)) && !Boolean.TRUE.equals(reader.followedBy(user))) {
                    moved |= !rest.isEmpty();
                    notBack.add(user);
                } else {
                    rest.add(user);
                }
            }
            if (!moved) return users;
            notBack.addAll(rest);
            return notBack;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ORDER, failure);
            return users;
        }
    }

    /**
     * Has the list put in order last rebuild its rows once, a moment from now, when none is queued
     * already: the server's answers come after the rows are drawn, and a no moves its account up.
     */
    private static void rebuildSoon() {
        synchronized (FollowingList.class) {
            if (rebuildQueued || orderedList.get() == null) return;
            rebuildQueued = true;
        }
        Utils.runOnMainThreadDelayed(FollowingList::rebuild, REBUILD_DELAY_MS);
    }

    private static void rebuild() {
        Object list;
        synchronized (FollowingList.class) {
            rebuildQueued = false;
            list = orderedList.get();
        }
        try {
            if (list != null && orderSwitchedOn()) rebuildRows(list, false, false);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, REBUILD, failure);
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
     * Puts [label] before the name, or on the hidden name line of an account with no name, which it
     * then shows. The row's Message button leaves that line little room, enough to cut the mark itself
     * short, so a marked line may take a second one (#351). Whatever goes wrong partway puts the line
     * back as it was.
     */
    private static void mark(TextView subtitle, String label) {
        CharSequence before = subtitle.getText();
        int visibility = subtitle.getVisibility();
        TransformationMethod method = subtitle.getTransformationMethod();
        int maxLines = subtitle.getMaxLines();
        CharSequence wrote = visibility == View.VISIBLE ? ahead(label, before) : label;
        try {
            subtitle.setText(wrote);
            if (visibility != View.VISIBLE) subtitle.setVisibility(View.VISIBLE);
            // Instagram's line is single-line with no transformation to show for it, and setting the
            // most lines alone leaves it on one. Any other transformation it had still applies.
            subtitle.setSingleLine(false);
            if (method != null && !(method instanceof SingleLineTransformationMethod)) subtitle.setTransformationMethod(method);
            subtitle.setMaxLines(MARKED_LINES);
        } catch (RuntimeException failure) {
            subtitle.setText(before);
            subtitle.setVisibility(visibility);
            linesBack(subtitle, method, maxLines);
            throw failure;
        }
        synchronized (marked) {
            marked.put(subtitle, new Mark(before, wrote, visibility, method, maxLines));
        }
        Logger.printDebug(() -> "Following list: marked an account that doesn't follow you");
    }

    /** Gives [subtitle] back the lines it had. Instagram doesn't set them again when it rebinds a row. */
    private static void linesBack(TextView subtitle, @Nullable TransformationMethod method, int maxLines) {
        if (maxLines == 1) subtitle.setSingleLine(true);
        else if (maxLines > 0) subtitle.setMaxLines(maxLines);
        subtitle.setTransformationMethod(method);
    }

    /**
     * [label] ahead of [name]. Instagram cuts a long name line short at its end, which hid a mark
     * written after the name (#351).
     */
    static CharSequence ahead(String label, @Nullable CharSequence name) {
        if (name == null || name.length() == 0) return label;
        return label + FriendshipStatus.SEPARATOR + name;
    }

    /**
     * Takes back the mark on [subtitle], when it still holds it. Once Instagram has filled the line
     * in again for the row's next account, the mark is gone already and only the lines it took are
     * given back.
     */
    private static void unmark(TextView subtitle) {
        Mark mark;
        synchronized (marked) {
            mark = marked.remove(subtitle);
        }
        if (mark == null) return;
        linesBack(subtitle, mark.method, mark.maxLines);
        if (!TextUtils.equals(subtitle.getText(), mark.wrote)) return;
        subtitle.setText(mark.before);
        if (mark.visibility != View.VISIBLE && subtitle.getVisibility() == View.VISIBLE) {
            subtitle.setVisibility(mark.visibility);
        }
    }

    private static boolean nothingMarked() {
        synchronized (marked) {
            if (!marked.isEmpty()) return false;
        }
        synchronized (badged) {
            return badged.isEmpty();
        }
    }

    /**
     * Puts a check, or a cross when the account doesn't follow you back, on [picture]'s bottom end
     * corner as its foreground. Whatever goes wrong partway puts the foreground back as it was.
     */
    private static void badge(View picture, boolean followsYou) {
        Drawable before = picture.getForeground();
        int gravity = picture.getForegroundGravity();
        FollowBadge badge = new FollowBadge(picture.getContext(), followsYou);
        try {
            picture.setForeground(badge);
            picture.setForegroundGravity(Gravity.BOTTOM | Gravity.END);
        } catch (RuntimeException failure) {
            picture.setForeground(before);
            picture.setForegroundGravity(gravity);
            throw failure;
        }
        synchronized (badged) {
            badged.put(picture, new Badged(badge, before, gravity));
        }
        Logger.printDebug(() -> "Following list: badged a profile picture, follows you " + followsYou);
    }

    /**
     * Takes the badge off [picture] and gives it back the foreground it had, when the badge is still
     * there. Where Instagram has set one of its own since, only the gravity is put back.
     */
    private static void unbadge(View picture) {
        Badged was;
        synchronized (badged) {
            was = badged.remove(picture);
        }
        if (was == null) return;
        if (picture.getForeground() == was.badge) picture.setForeground(was.before);
        if (picture.getForegroundGravity() == (Gravity.BOTTOM | Gravity.END)) picture.setForegroundGravity(was.gravity);
    }

    /**
     * The profile picture on [row]: the follow list's own, else the shared user row's. Each id is
     * looked up once, and a build without it gives the words instead.
     */
    @Nullable
    static View picture(View row) {
        for (int at = 0; at < PICTURES.length; at++) {
            int id = pictureIds[at];
            if (id == -1) {
                pictureIds[at] = id = row.getResources().getIdentifier(PICTURES[at], "id", Utils.resourcePackage(row.getContext()));
            }
            View found = id == 0 ? null : row.findViewById(id);
            if (found != null) return found;
        }
        return null;
    }

    /** Sets the profile picture ids as a build of Instagram would have them, or -1 to look them up again. */
    static void pictureIdsForTests(int followList, int userRow) {
        pictureIds[0] = followList;
        pictureIds[1] = userRow;
    }

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.MARK_FOLLOWING_LIST.get();
    }

    /** Whether the mark goes on the profile picture: off while paused and before the settings are read. */
    static boolean pictureSwitchedOn() {
        return Utils.settingsReady() && Settings.FOLLOW_MARK_ON_PICTURE.get();
    }

    /** Whether the list is asked about every row: for the mark, or for the order, which goes by the same answers. */
    static boolean asking() {
        return Utils.settingsReady() && (Settings.MARK_FOLLOWING_LIST.get() || Settings.FOLLOWING_NOT_BACK_FIRST.get());
    }

    /** Whether the order switch is on: off while paused and before the settings are read. */
    static boolean orderSwitchedOn() {
        return Utils.settingsReady() && Settings.FOLLOWING_NOT_BACK_FIRST.get();
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the row binder a follow list's state holder [list] draws its rows with, or null. */
    @Nullable
    public static Object orderBinder(Object list) {
        return null;
    }

    /** Filled in by the patch: has the follow list's state holder [list] build its rows again from its accounts. */
    public static void rebuildRows(Object list, boolean first, boolean second) {
    }

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
