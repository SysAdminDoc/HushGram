/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.onFlip;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.feed.CommentEmojiRow;
import app.hushgram.extension.shared.L10n;

/** Builds the Comments category of HushGram settings. */
final class CommentsCategory {
    private CommentsCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.COMMENT_COPY) || build.contains(PatchFamily.COMMENT_PHOTO)
                || build.contains(PatchFamily.HIDE_COMMENTS) || build.contains(PatchFamily.COMMENT_EMOJI_ROW)
                || build.contains(PatchFamily.COMMENT_KEYBOARD)) {
            PreferenceCategory comments = category(screen, L10n.t("Comments"), SettingsIcons.COMMENTS,
                    L10n.t("Copy, save or hide comments."));
            if (build.contains(PatchFamily.COMMENT_COPY)) {
                comments.addPreference(toggle(context, Settings.COPY_COMMENTS, L10n.t("Copy comment"),
                        L10n.t("Adds Copy to a selected comment's menu. Copies the original text, including line breaks.")));
                comments.addPreference(toggle(context, Settings.COPY_COMMENTS_WITHOUT_MENTIONS, L10n.t("Copy without the @names at the start"),
                        L10n.t("When a comment starts with @names, like most replies do, Copy leaves them out. The comment in Instagram stays the same.")));
                if (PatchFamily.commentAuthorInBuild()) {
                    comments.addPreference(toggle(context, Settings.COPY_COMMENT_AUTHORS, L10n.t("Copy the commenter's username"),
                            L10n.t("Adds Copy username to a selected comment's menu, for the account that wrote it.")));
                }
            }
            if (build.contains(PatchFamily.COMMENT_PHOTO)) {
                comments.addPreference(toggle(context, Settings.SAVE_COMMENT_PHOTOS, L10n.t("Save comment photo"),
                        L10n.t("Adds Save to a selected comment's menu when the comment has its own photo or GIF. Saves the largest size Instagram sent.")));
            }
            if (build.contains(PatchFamily.HIDE_COMMENTS)) {
                comments.addPreference(toggle(context, Settings.HIDE_COMMENTS, L10n.t("Hide comments"),
                        L10n.t("Takes the Comment button and the comment count off the posts in your feed.")));
            }
            if (build.contains(PatchFamily.COMMENT_EMOJI_ROW)) {
                comments.addPreference(onFlip(toggle(context, Settings.HIDE_COMMENT_EMOJI_ROW, L10n.t("Hide the emoji row"),
                        L10n.t("Takes the row of quick emoji off above the comment box. The box itself stays.")),
                        CommentEmojiRow::switchChanged));
            }
            if (build.contains(PatchFamily.COMMENT_KEYBOARD)) {
                comments.addPreference(toggle(context, Settings.COMMENT_KEYBOARD_DOWN, L10n.t("Open comments without the keyboard"),
                        L10n.t("Comments open with the keyboard down. A tap in the comment box, or on Reply, still brings it up.")));
            }
        }
    }
}
