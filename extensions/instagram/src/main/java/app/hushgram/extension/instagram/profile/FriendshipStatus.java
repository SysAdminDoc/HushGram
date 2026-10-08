/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show if a profile follows you" patch.
 *
 * <p>The header of someone's profile shows their pronouns in a small line beside their name, from a
 * slot that's only filled in when they've set some. Each time Instagram binds the name, it fills that
 * slot and shows it, or hides it. The patch calls {@link #besidePronouns} right after the slot is
 * shown and {@link #inPlaceOfPronouns} right after it's hidden, with the slot and the profile
 * screen's header, and the label goes after the pronouns or takes the slot on its own.
 *
 * <p>Whether the account follows you comes first from the profile screen's own answer, read the way
 * Instagram's options sheet on that profile reads it to offer Remove follower. The friendship status
 * Instagram keeps on the profile's user can be older than that (#40), so it's only asked when the
 * screen has no answer, and failing that the user's own followed_by. Until it knows, nothing is
 * added.
 *
 * <p>With Show it as a chip on, the answer goes in a chip under the profile's counts instead
 * ({@link FriendshipChip}), which also says Following each other when you follow the account too.
 * The pronouns slot is then left as Instagram set it. A header whose counts can't be found keeps
 * the text label.
 */
public final class FriendshipStatus {
    static final String SEPARATOR = " · ";

    /** The keys of whether the account follows you and whether you follow it, as Instagram's trees hash them. */
    static final int FOLLOWED_BY_KEY = "followed_by".hashCode();
    static final int FOLLOWING_KEY = "following".hashCode();

    /** What the profile's account and you are to each other, as far as Instagram has said. */
    enum Relation { FOLLOWS_YOU, FOLLOWING_EACH_OTHER, DOESNT_FOLLOW_YOU }

    private FriendshipStatus() {
    }

    /**
     * Injected right after the profile header shows the pronouns slot with the account's pronouns.
     * Adds the label after them. Never throws.
     */
    public static void besidePronouns(Object slot, Object header) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            Relation relation = relation(header);
            if (relation == null && !FriendshipChip.anyShown()) return;
            View view = slotView(slot);
            if (!(view instanceof TextView)) return;
            TextView text = (TextView) view;
            if (chipped(text, relation)) return;
            String label = textFor(relation);
            if (label == null) return;
            text.setText(joined(text.getText(), label));
            log(label);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile header", failure);
        }
    }

    /**
     * Injected right after the profile header hides the pronouns slot, for an account with none.
     * Puts the label in the slot and shows it. With the chip showing instead, the slot stays hidden.
     * Never throws.
     */
    public static void inPlaceOfPronouns(Object slot, Object header) {
        try {
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            Relation relation = relation(header);
            if (relation == null && !FriendshipChip.anyShown()) return;
            View view = slotView(slot);
            if (!(view instanceof TextView)) return;
            String label = chipped(view, relation) ? null : textFor(relation);
            if (label == null) {
                // Asking for the slot's view can put it in place, so it's hidden again as Instagram left it.
                setSlotVisibility(slot, View.GONE);
                return;
            }
            ((TextView) view).setText(label);
            setSlotVisibility(slot, View.VISIBLE);
            log(label);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile header", failure);
        }
    }

    /** The label for the profile [header] belongs to, or null when the switch is off or it isn't known. */
    @Nullable
    static String label(Object header) {
        return textFor(relation(header));
    }

    /**
     * What the profile [header] belongs to and you are to each other, or null when the switch is
     * off, it's your own profile or Instagram hasn't said. Following each other needs Instagram to
     * have said you follow the account too.
     *
     * <p>The profile screen's own answer goes first, as it does for Instagram's options sheet: the
     * status kept on the user can still say an account doesn't follow you after the screen has
     * heard it does (#40). Only a screen with no answer falls back on the kept status.
     */
    @Nullable
    static Relation relation(Object header) {
        if (!Utils.settingsReady() || !Settings.SHOW_FRIENDSHIP_STATUS.get()) return null;
        Object user = profileUser(header);
        if (user == null) {
            Logger.printDebug(() -> "Friendship status: the profile's user isn't loaded yet");
            return null;
        }
        if (isViewer(header, user)) return null;
        Object screen = screenStatus(header);
        Boolean followedBy = screenFlag(screen, FOLLOWED_BY_KEY);
        Boolean following = followedBy == null ? null : screenFlag(screen, FOLLOWING_KEY);
        if (followedBy == null) followedBy = friendshipFollowedBy(user);
        if (followedBy == null) followedBy = followedBy(user);
        if (followedBy == null) {
            Logger.printDebug(() -> "Friendship status: Instagram hasn't said whether this account follows you");
            return null;
        }
        if (following == null) following = friendshipFollowing(user);
        return relation(followedBy, following);
    }

    /**
     * The friendship status in the answer of the profile screen [header] belongs to, or null when
     * the screen has none yet or this build's couldn't be found. A failure reading it is noted once
     * in the diagnostics and counts as no answer, so the label falls back on the kept status.
     */
    @Nullable
    static Object screenStatus(Object header) {
        try {
            return screenFriendship(header);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile screen's answer", failure);
            return null;
        }
    }

    /** What the screen's friendship status [status] says under [key], or null when it says nothing there. */
    @Nullable
    static Boolean screenFlag(@Nullable Object status, int key) {
        if (status == null) return null;
        try {
            return statusFlag(status, key);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile screen's friendship status", failure);
            return null;
        }
    }

    /** The relation for whether the account follows you and whether you follow it, which may be unknown. */
    static Relation relation(boolean followedBy, @Nullable Boolean following) {
        if (!followedBy) return Relation.DOESNT_FOLLOW_YOU;
        return Boolean.TRUE.equals(following) ? Relation.FOLLOWING_EACH_OTHER : Relation.FOLLOWS_YOU;
    }

    /**
     * With Show it as a chip on and [relation] known, puts the chip under the counts of the header
     * [inside] is in; otherwise takes a chip it put there before away. True when the chip is
     * showing, so the text label stays out.
     */
    private static boolean chipped(View inside, @Nullable Relation relation) {
        if (relation == null || !Settings.FRIENDSHIP_STATUS_CHIP.get()) {
            FriendshipChip.clear(inside);
            return false;
        }
        boolean shown = FriendshipChip.show(inside, relation, inside instanceof TextView ? (TextView) inside : null);
        if (!shown) Logger.printDebug(() -> "Friendship status: no counts to put the chip under, so the label goes by the name");
        return shown;
    }

    /** The text label: the relation's answer, where following each other still reads Follows you. */
    @Nullable
    static String textFor(@Nullable Relation relation) {
        return relation == null ? null : text(relation != Relation.DOESNT_FOLLOW_YOU);
    }

    /** Whether [user] is the account signed in, whose own profile gets no label. */
    static boolean isViewer(Object header, Object user) {
        String id = userId(user);
        return id != null && id.equals(viewerId(header));
    }

    /** "Follows you" for true, "Doesn't follow you" for false, and null when Instagram doesn't know. */
    @Nullable
    static String text(@Nullable Boolean followedBy) {
        if (followedBy == null) return null;
        return followedBy ? L10n.t("Follows you") : L10n.t("Doesn't follow you");
    }

    /** The pronouns with the label after them, or the label alone when there are none. */
    static CharSequence joined(@Nullable CharSequence pronouns, String label) {
        if (pronouns == null || pronouns.length() == 0) return label;
        return pronouns + SEPARATOR + label;
    }

    private static void log(String label) {
        Logger.printDebug(() -> "Friendship status: labelled a profile " + label);
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the user of the profile screen [header] belongs to, or null. */
    @Nullable
    public static Object profileUser(Object header) {
        return null;
    }

    /**
     * Filled in by the patch: whether [user] follows you, from the friendship status Instagram keeps
     * on the user, or null when it has none.
     */
    @Nullable
    public static Boolean friendshipFollowedBy(Object user) {
        return null;
    }

    /**
     * Filled in by the patch: whether you follow [user], from the friendship status Instagram keeps on
     * the user, or null when it has none or the build's getter couldn't be told.
     */
    @Nullable
    public static Boolean friendshipFollowing(Object user) {
        return null;
    }

    /**
     * Filled in by the patch: the friendship status in the answer of the profile screen [header]
     * belongs to, read as fresh as Instagram's options sheet reads it to offer Remove follower, or
     * null when the screen has none or this build's couldn't be found.
     */
    @Nullable
    public static Object screenFriendship(Object header) {
        return null;
    }

    /** Filled in by the patch: the Boolean the screen's friendship status [status] keeps under [key], or null. */
    @Nullable
    public static Boolean statusFlag(Object status, int key) {
        return null;
    }

    /** Filled in by the patch: whether [user] follows you, from the user's own field, or null when Instagram doesn't know. */
    @Nullable
    public static Boolean followedBy(Object user) {
        return null;
    }

    /** Filled in by the patch: [user]'s ID, or null. */
    @Nullable
    public static String userId(Object user) {
        return null;
    }

    /** Filled in by the patch: the ID of the account signed in on the profile screen [header] belongs to, or null. */
    @Nullable
    public static String viewerId(Object header) {
        return null;
    }

    /** Filled in by the patch: the slot's TextView, put in place the first time it's asked for. */
    @Nullable
    public static View slotView(Object slot) {
        return null;
    }

    /** Filled in by the patch: shows or hides the slot. True once it's done that. */
    public static boolean setSlotVisibility(Object slot, int visibility) {
        return false;
    }
}
