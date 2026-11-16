/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The list a tap on the Show it as a chip chip opens (#698): every friendship flag Instagram sent
 * for the profile, such as Close Friends, Favorites, a pending request, a mute or a restriction.
 *
 * <p>The flags come from the friendship status in the profile screen's own answer, the one the chip
 * reads whether the account follows you from, each by the Java hash of its name as Instagram's
 * trees keep them. That's data the app already has: nothing is asked of Instagram for it. Flags
 * with a plain meaning are listed in words, the few whose meaning isn't plain go under their own
 * names, and the ones the answer doesn't carry are named at the bottom. A screen with no answer yet
 * lists what Instagram keeps on the user instead, which is only whether you follow each other.
 */
final class FriendshipFlags {
    /** The flags with a plain meaning, in the order they're listed. */
    static final String[] KNOWN = {
            "followed_by", "following", "incoming_request", "outgoing_request", "is_bestie", "is_feed_favorite",
            "is_restricted", "blocking", "is_blocking_reel", "muting", "is_muting_reel", "is_muting_notes",
            "is_muting_media_notes", "is_muting_media_reposts", "is_muting_quick_snap", "is_private", "subscribed",
    };

    /** Flags Instagram sends whose meaning isn't plain, listed under their own names. */
    static final String[] OTHER = {
            "is_viewer_unconnected", "is_eligible_to_subscribe", "is_supervised_by_viewer", "is_guardian_of_viewer",
    };

    /** What the list's opening is counted under in the diagnostics. */
    static final String OPENED = "Friendship flags listed";
    /** What a flag that couldn't be read is counted under, which then counts as not sent. */
    static final String UNREADABLE = "Friendship flag that couldn't be read";

    private FriendshipFlags() {
    }

    /** The flag [key] in plain words. Every name in {@link #KNOWN} has some. */
    static String label(String key) {
        switch (key) {
            case "followed_by": return L10n.t("Follows you");
            case "following": return L10n.t("You follow them");
            case "incoming_request": return L10n.t("Asked to follow you");
            case "outgoing_request": return L10n.t("You asked to follow them");
            case "is_bestie": return L10n.t("On your Close Friends list");
            case "is_feed_favorite": return L10n.t("In your Favorites");
            case "is_restricted": return L10n.t("You restricted them");
            case "blocking": return L10n.t("You blocked them");
            case "is_blocking_reel": return L10n.t("You hide your story from them");
            case "muting": return L10n.t("You muted their posts");
            case "is_muting_reel": return L10n.t("You muted their story");
            case "is_muting_notes": return L10n.t("You muted their notes");
            case "is_muting_media_notes": return L10n.t("You muted their notes on posts and reels");
            case "is_muting_media_reposts": return L10n.t("You muted their reposts");
            case "is_muting_quick_snap": return L10n.t("You muted their Instants");
            case "is_private": return L10n.t("Their account is private");
            case "subscribed": return L10n.t("You're subscribed to them");
            default: return key;
        }
    }

    /** What the list says about one profile. */
    static final class Report {
        /** Whether the flags came from the screen's answer, rather than what Instagram keeps on the user. */
        final boolean fromScreen;
        /** The plain flags sent, as label and answer. */
        final List<String[]> known = new ArrayList<>();
        /** The other flags sent, as name and answer. */
        final List<String[]> other = new ArrayList<>();
        /** The names of the flags the answer didn't carry. */
        final List<String> missing = new ArrayList<>();

        Report(boolean fromScreen) {
            this.fromScreen = fromScreen;
        }

        /** The list as the dialog shows it. */
        String text() {
            StringBuilder text = new StringBuilder();
            if (!fromScreen) {
                text.append(known.isEmpty()
                        ? L10n.t("Instagram hasn't said anything about this account yet.")
                        : L10n.t("This profile hasn't loaded its own answer yet, so this is only what Instagram keeps on the account."));
            }
            for (String[] line : known) line(text, line);
            if (!other.isEmpty()) {
                section(text, L10n.t("Other flags"));
                for (String[] line : other) line(text, line);
            }
            if (fromScreen && !missing.isEmpty()) {
                section(text, L10n.t("Not sent for this profile"));
                text.append('\n').append(String.join(", ", missing));
            }
            return text.toString();
        }

        private static void line(StringBuilder text, String[] line) {
            if (text.length() > 0) text.append('\n');
            text.append(line[0]).append(": ").append(line[1]);
        }

        private static void section(StringBuilder text, String heading) {
            if (text.length() > 0) text.append("\n\n");
            text.append(heading);
        }
    }

    /** Reads every flag for the profile screen [header] belongs to. Never throws. */
    static Report read(@Nullable Object header) {
        Object status = null;
        Object user = null;
        if (header != null) {
            try {
                user = FriendshipStatus.profileUser(header);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "friendship flags' user", failure);
            }
            status = FriendshipStatus.screenStatus(header);
        }
        Report report = new Report(status != null);
        if (status == null) {
            kept(report, user);
            return report;
        }
        for (String key : KNOWN) add(report, status, key, label(key), report.known);
        for (String key : OTHER) add(report, status, key, key, report.other);
        return report;
    }

    private static void add(Report report, Object status, String key, String name, List<String[]> into) {
        Boolean value;
        try {
            value = FriendshipStatus.statusFlag(status, key.hashCode());
        } catch (Throwable failure) {
            HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, UNREADABLE);
            Logger.printDebug(() -> "Friendship flags: couldn't read " + key + ": " + failure);
            value = null;
        }
        if (value == null) report.missing.add(key);
        else into.add(new String[]{name, answer(value)});
    }

    /** With no answer on the screen, whether you follow each other from what Instagram keeps on [user]. */
    private static void kept(Report report, @Nullable Object user) {
        if (user == null) return;
        try {
            Boolean followedBy = FriendshipStatus.friendshipFollowedBy(user);
            if (followedBy == null) followedBy = FriendshipStatus.followedBy(user);
            if (followedBy != null) report.known.add(new String[]{label("followed_by"), answer(followedBy)});
            Boolean following = FriendshipStatus.friendshipFollowing(user);
            if (following != null) report.known.add(new String[]{label("following"), answer(following)});
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "friendship flags kept on the user", failure);
        }
    }

    private static String answer(boolean value) {
        return value ? L10n.t("Yes") : L10n.t("No");
    }

    /**
     * Opens the list for the profile screen [header] belongs to over the activity behind [context].
     * Never throws.
     */
    @Nullable
    static AlertDialog open(Context context, @Nullable Object header) {
        try {
            Activity activity = activityOf(context);
            if (activity == null || activity.isFinishing()) return null;
            Report report = read(header);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(activity, "Friendship status"))
                    .setMessage(report.text())
                    .setPositiveButton(L10n.t(activity, "OK"), null)
                    .create();
            dialog.show();
            ScreenColors.dialog(dialog);
            HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, OPENED);
            return dialog;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "friendship flags", failure);
            return null;
        }
    }

    /** The activity behind [context], or the one showing, or null. */
    @Nullable
    private static Activity activityOf(Context context) {
        Context at = context;
        for (int depth = 0; depth < 10 && at != null; depth++) {
            if (at instanceof Activity) return (Activity) at;
            if (!(at instanceof ContextWrapper)) break;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return Utils.getActivity();
    }
}
