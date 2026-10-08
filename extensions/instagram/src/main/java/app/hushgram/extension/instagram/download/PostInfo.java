/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Details in the menu of a feed post, with the Details switch on.
 *
 * <p>The row is offered beside Save all and Open in another player, before the builder splits into
 * your own and others' rows, so every post gets it, and the short menu keeps it after them. A tap
 * shows what Instagram already holds for the post, or for a carousel the page on screen: when it
 * went up, who posted it, its media ID, the page's place in the carousel and the size a Download
 * would save, with a button that copies the direct address of the single file Instagram lists for
 * it. A video Download would join from its manifest's picture and sound tracks has no one address,
 * so the button copies that single file, and its own size is shown beside the saved one. Under the
 * facts, Copy username copies who posted it and Copy caption the post's own words, exactly as
 * written, each only when the post has one. Both read the post whose menu was opened, and a
 * carousel's caption is the post's, never a page's. Nothing is fetched to show it.
 *
 * <p>The address is a signed link to the file on Meta's servers, so it goes on the clipboard marked
 * sensitive and never into a log.
 */
public final class PostInfo {
    /** The name of the row's option, made once, as Save all's is. */
    static final String OPTION = "HUSHGRAM_POST_DETAILS";

    /** How the post's time reads: the date with the year, and the time, in the phone's style. */
    static final int FORMAT = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_ABBREV_MONTH;

    /** Past this many seconds, the time in milliseconds no longer fits a long. */
    private static final long LAST_SECOND = Long.MAX_VALUE / 1000L;

    private static Object option;

    private PostInfo() {
    }

    /** The row's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object option() {
        try {
            if (option == null) option = InstagramMedia.feedOption(OPTION);
            return option;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details option", failure);
            return null;
        }
    }

    /** Whether the switch is on, which a pause answers off. */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.POST_DETAILS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details switch", failure);
            return false;
        }
    }

    /**
     * Adds Details to [rows], the list the feed menu's builder [menu] fills, when the switch is on
     * and the menu is for a post. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        try {
            if (menu == null || rows == null || !on()) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (InstagramMedia.feedMenuMedia(menu) == null) return;
            Object row = option();
            if (row != null) InstagramMedia.addSaveAllRow(menu, rows, row, L10n.t("Details"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details row", failure);
        }
    }

    /**
     * [options], the options the short feed menu keeps, with Details after HushGram's own rows that
     * are on it, [ours], or in front when none is. A list that has it already comes back as it came.
     */
    static List<?> withDetails(List<?> options, Object... ours) {
        Object details = option();
        if (details == null || options.contains(details)) return options;
        List<Object> allowed = new ArrayList<>(options);
        int after = -1;
        for (Object row : ours) {
            if (row != null) after = Math.max(after, allowed.indexOf(row));
        }
        allowed.add(after + 1, details);
        return allowed;
    }

    /**
     * Shows the details of the post [media], or of the carousel page on screen that [itemState],
     * the post's feed state, names, over [activity], when its Details row is tapped. Never throws.
     */
    public static void show(Object media, Object itemState, Activity activity) {
        try {
            if (!on() || media == null || gone(activity)) return;
            Source source = Source.read(VideoDownload.shown(media, itemState), media);
            if (source.manifest == null) {
                present(activity, source.facts());
                return;
            }
            // A manifest is parsed on a worker, as a save parses it, never on the thread that draws
            // the app. Only a full queue parses it here, so the tap still answers.
            boolean queued = Utils.runOnBackgroundThread(() -> {
                Facts facts = facts(source);
                if (facts != null) Utils.runOnMainThread(() -> present(activity, facts));
            });
            if (!queued) present(activity, source.facts());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details", failure);
        }
    }

    private static boolean gone(Activity activity) {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    /** [source]'s facts, on the worker. Null when they can't be read. */
    @Nullable
    private static Facts facts(Source source) {
        try {
            return source.facts();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details", failure);
            return null;
        }
    }

    /** Shows [facts] over [activity], unless the switch went off or the screen went away meanwhile. */
    private static void present(Activity activity, Facts facts) {
        try {
            if (!on() || gone(activity)) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(activity, "Details"))
                    .setMessage(facts.text(activity))
                    .setNegativeButton(L10n.t(activity, "Close"), null);
            String link = facts.link();
            if (link != null) builder.setPositiveButton(L10n.t(activity, "Copy media link"), (dialog, which) -> copy(activity, link));
            AlertDialog dialog = builder.create();
            View copies = copies(builder.getContext(), activity, facts, dialog);
            if (copies != null) dialog.setView(copies);
            dialog.show();
            TextView message = dialog.findViewById(android.R.id.message);
            // So the ID or the name can be copied on its own.
            if (message != null) message.setTextIsSelectable(true);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details", failure);
        }
    }

    /**
     * Copy username and Copy caption, as rows under the facts, for what [facts] knows. Each closes
     * [dialog] and copies. Null when the post has neither. [themed] is the dialog's own context, so
     * the rows take its colors.
     */
    @Nullable
    static View copies(Context themed, Activity activity, Facts facts, AlertDialog dialog) {
        LinearLayout rows = new LinearLayout(themed);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Dim.dp(8), 0, 0);
        String owner = facts.owner;
        if (owner != null) {
            rows.addView(row(themed, L10n.t(activity, "Copy username"), () -> {
                dialog.dismiss();
                copy(activity, "copy username", L10n.t(activity, "Username"), owner,
                        L10n.t(activity, "Username copied"), L10n.t(activity, "Couldn't copy the username"));
            }));
        }
        String caption = facts.caption;
        if (caption != null) {
            rows.addView(row(themed, L10n.t(activity, "Copy caption"), () -> {
                dialog.dismiss();
                copy(activity, "copy caption", L10n.t(activity, "Caption"), caption,
                        L10n.t(activity, "Caption copied"), L10n.t(activity, "Couldn't copy the caption"));
            }));
        }
        return rows.getChildCount() == 0 ? null : rows;
    }

    /** A row reading [label], in the dialog's accent like its buttons, that runs [action] on a tap. */
    private static TextView row(Context themed, String label, Runnable action) {
        TextView row = new TextView(themed);
        row.setText(label);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setMinHeight(Dim.dp(48));
        row.setPadding(Dim.dp(24), 0, Dim.dp(24), 0);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        // In ascending order, as obtainStyledAttributes reads them.
        TypedArray theme = themed.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground, android.R.attr.colorAccent});
        try {
            Drawable touch = theme.getDrawable(0);
            if (touch != null) row.setBackground(touch);
            ColorStateList accent = theme.getColorStateList(1);
            if (accent != null) row.setTextColor(accent);
        } finally {
            theme.recycle();
        }
        row.setOnClickListener(view -> {
            try {
                action.run();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed details copy", failure);
            }
        });
        return row;
    }

    /** Puts [link] on the clipboard, marked sensitive, and says so. Never throws. */
    static void copy(Context context, String link) {
        try {
            copy(context, "copy media link", L10n.t(context, "Media link"), link,
                    L10n.t(context, "Media link copied"), L10n.t(context, "Couldn't copy the media link"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "copy media link", failure);
        }
    }

    /**
     * Puts [text] on the clipboard under [label], marked sensitive as every copy is, and says
     * [copied], or [failed] when it can't. [what] names it in the hook report.
     */
    private static void copy(Context context, String what, String label, String text, String copied, String failed) {
        try {
            Utils.setClipboard(context, label, text);
            Utils.showToastShort(copied);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, what, failure);
            try {
                Utils.showToastShort(failed);
            } catch (Throwable feedbackFailure) {
                HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, what + " feedback", feedbackFailure);
            }
        }
    }

    /**
     * What Details reads of one post or page on the tap, on the thread that draws the app, before
     * anything is picked: the facts, and the single files and manifest a Download would choose from.
     */
    static final class Source {
        @Nullable final Long posted;
        @Nullable final String owner;
        @Nullable final String id;
        final int page;
        final int pages;
        /** Whether it's a video: single video files or a manifest. */
        final boolean video;
        @Nullable final List<MediaSave.Rendition> videos;
        @Nullable final String manifest;
        @Nullable final List<MediaSave.Rendition> pictures;
        /** The post's caption, as written, or null when it has none. */
        @Nullable final String caption;

        Source(@Nullable Long posted, @Nullable String owner, @Nullable String id, int page, int pages, boolean video,
               @Nullable List<MediaSave.Rendition> videos, @Nullable String manifest,
               @Nullable List<MediaSave.Rendition> pictures, @Nullable String caption) {
            this.posted = posted;
            this.owner = owner;
            this.id = id;
            this.page = page;
            this.pages = pages;
            this.video = video;
            this.videos = videos;
            this.manifest = manifest;
            this.pictures = pictures;
            this.caption = caption;
        }

        /**
         * [shown], the post [post] itself or the page of its carousel on screen. A page keeps its
         * own ID, and takes the poster and the time from the post when it doesn't list them. The
         * caption is always the post's. A carousel whose page on screen isn't known, [shown] null,
         * has the post's facts and no file, since its first page might not be the one on screen.
         */
        static Source read(@Nullable Object shown, Object post) {
            if (shown == null) {
                Source known = read(post, post);
                return new Source(known.posted, known.owner, known.id, 0, 0, false, null, null, null, known.caption);
            }
            Long posted = InstagramMedia.takenAt(shown);
            if ((posted == null || posted <= 0) && shown != post) posted = InstagramMedia.takenAt(post);
            Object user = InstagramMedia.owner(shown);
            if (user == null && shown != post) user = InstagramMedia.owner(post);
            String owner = user == null ? null : InstagramMedia.username(user);
            String id = InstagramMedia.mediaId(shown);
            List<?> carousel = InstagramMedia.carouselMedia(post);
            int pages = carousel == null || shown == post ? 0 : carousel.size();
            int page = pages == 0 ? 0 : VideoDownload.pageOf(shown, post);
            List<MediaSave.Rendition> videos = ReelDownload.renditions(shown);
            String manifest = InstagramMedia.dashManifest(shown);
            boolean video = !videos.isEmpty() || manifest != null;
            return new Source(posted == null || posted <= 0 ? null : posted, Facts.empty(owner) ? null : owner,
                    Facts.empty(id) ? null : id, page, page == 0 ? 0 : pages, video, videos, manifest,
                    video ? null : StoryDownload.pictures(shown), caption(post));
        }

        /** [post]'s caption text, untouched, or null when it has none or only blanks. */
        @Nullable
        private static String caption(Object post) {
            Object caption = InstagramMedia.caption(post);
            String text = caption == null ? null : InstagramMedia.captionText(caption);
            return text == null || text.trim().isEmpty() ? null : text;
        }

        /**
         * The facts, with the single file a link copies and the size a Download saves: for a video,
         * as {@link MediaSave#plannedVideo} picks it, which reads the manifest.
         */
        Facts facts() {
            if (!video) {
                MediaSave.Rendition picture = pictures == null ? null : MediaSave.picked(pictures, false);
                return new Facts(posted, owner, id, page, pages, picture,
                        picture == null ? 0 : picture.width, picture == null ? 0 : picture.height, caption);
            }
            MediaSave.Planned planned = MediaSave.plannedVideo(videos, manifest);
            return new Facts(posted, owner, id, page, pages, planned.file, planned.width(), planned.height(), caption);
        }
    }

    /** What the details show of one post or page. */
    static final class Facts {
        /** When it went up, in seconds since 1970, or null. */
        @Nullable final Long posted;
        /** Who posted it, or null. */
        @Nullable final String owner;
        /** Instagram's media ID, {@code <media pk>_<owner's pk>}, or null. */
        @Nullable final String id;
        /** Its place in the carousel, counted from 1, and the carousel's pages, or 0 and 0. */
        final int page;
        final int pages;
        /** The single file with an address of its own, the one Copy media link copies, or null. */
        @Nullable final MediaSave.Rendition file;
        /** The size a Download saves, which a video joined from its manifest's tracks has apart from [file], or 0. */
        final int width;
        final int height;
        /** The post's caption, for Copy caption, or null. Not shown among the facts. */
        @Nullable final String caption;

        Facts(@Nullable Long posted, @Nullable String owner, @Nullable String id, int page, int pages,
              @Nullable MediaSave.Rendition file, int width, int height, @Nullable String caption) {
            this.posted = posted;
            this.owner = owner;
            this.id = id;
            this.page = page;
            this.pages = pages;
            this.file = file;
            this.width = width;
            this.height = height;
            this.caption = caption;
        }

        /** The facts of [shown] and [post], as {@link Source#read} reads them, picked at once. */
        static Facts of(@Nullable Object shown, Object post) {
            return Source.read(shown, post).facts();
        }

        /** The address to copy, or null when there's no file. */
        @Nullable
        String link() {
            return file == null ? null : file.url;
        }

        /** One line for each fact known, in the phone's language. */
        String text(Context context) {
            List<String> lines = new ArrayList<>();
            if (posted != null && posted <= LAST_SECOND) {
                lines.add(L10n.f(context, "Posted %1$s", DateUtils.formatDateTime(context, posted * 1000L, FORMAT)));
            }
            if (owner != null) lines.add(L10n.f(context, "By @%1$s", L10n.isolate(owner)));
            if (page > 0) lines.add(L10n.f(context, "Page %1$d of %2$d", page, pages));
            if (width > 0 && height > 0) lines.add(L10n.f(context, "Size %1$d × %2$d", width, height));
            if (id != null) lines.add(L10n.f(context, "Media ID %1$s", L10n.isolate(id)));
            if (file == null) {
                lines.add(L10n.t(context, "No direct link for this one"));
            } else if (file.width > 0 && file.height > 0 && (file.width != width || file.height != height)) {
                // Download joins its picture from the manifest, so the link is a file of its own size.
                lines.add(L10n.f(context, "Media link is the %1$d × %2$d file", file.width, file.height));
            }
            return String.join("\n", lines);
        }

        static boolean empty(String text) {
            return text == null || text.trim().isEmpty();
        }
    }
}
