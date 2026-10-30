/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Choose pages on a carousel: its pages as numbered rows with a box each, and only the ticked ones
 * save, in their order, through Save all's batch ({@link MediaSave#saveBatch}) with its queue, Skip
 * what's already saved, Cancel and cleanup.
 *
 * <p>Everything is read on the tap into plain values ({@link Page}), so the list holds no Instagram
 * object. A row reads "Page 3, video" and works without its picture. Each row's small picture is
 * fetched off the main thread, {@link #PREVIEW_THREADS} at a time with at most a carousel's worth
 * waiting, from Meta's media servers alone ({@link PictureFetch}), and a row whose picture never
 * comes keeps its blank square. A page the photo or video switch leaves out is listed, off. Save
 * stays off until a box is ticked. The account in front when the list opened is the one the save is
 * for: after a switch of accounts it says so and saves nothing, and so does a list whose screen has
 * closed.
 */
final class CarouselPages {
    private CarouselPages() {
    }

    /** How many rows' pictures are fetched at once. */
    static final int PREVIEW_THREADS = 2;

    /** A row's picture, in dp. */
    static final int PREVIEW_DP = 48;

    /** Fetches a row's picture, blocking. Replaced by tests. */
    interface Previews {
        @Nullable
        Bitmap fetch(Context context, String url, int size);
    }

    static Previews previews = PictureFetch::fetch;

    private static ThreadPoolExecutor fetches;

    /** One page as plain values. */
    static final class Page {
        /** Counted from 1. */
        final int number;
        final boolean video;
        /** What saves of it, or null when the photo or video switch leaves it out. */
        @Nullable final MediaSave.Item item;
        /** A small picture of it, or null when it lists none. */
        @Nullable final String preview;

        Page(int number, boolean video, @Nullable MediaSave.Item item, @Nullable String preview) {
            this.number = number;
            this.video = video;
            this.item = item;
            this.preview = preview;
        }
    }

    /**
     * Plain values for each of [pages], [post]'s carousel pages in order: what saves of it with the
     * [videos] and [photos] switches, read as Save all reads them, and the address of a picture of
     * it near [size] pixels across.
     */
    static List<Page> read(List<?> pages, Object post, boolean videos, boolean photos, int size) {
        List<MediaSave.Item> items = VideoDownload.snapshot(pages, post, videos, photos, FamilyNames.VIDEO_DOWNLOAD);
        List<Page> read = new ArrayList<>(pages.size());
        for (int index = 0; index < pages.size(); index++) {
            Object page = pages.get(index);
            MediaSave.Item item = items.get(index);
            read.add(new Page(index + 1, item != null ? item.video : video(page), item, previewOf(page, size)));
        }
        return Collections.unmodifiableList(read);
    }

    private static boolean video(Object page) {
        try {
            return VideoDownload.hasVideo(page);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The address of a picture of [page] near [size] pixels across, or null when it lists none. Never throws. */
    @Nullable
    static String previewOf(Object page, int size) {
        try {
            return page == null ? null : pick(StoryDownload.pictures(page), size);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Of [sizes], the smallest whose short side is still [size] pixels, or the largest when none is
     * that big, or the first when none states its size. Null when there's none.
     */
    @Nullable
    static String pick(@Nullable List<MediaSave.Rendition> sizes, int size) {
        if (sizes == null) return null;
        MediaSave.Rendition fit = null, largest = null;
        for (MediaSave.Rendition each : sizes) {
            if (each == null || each.url == null) continue;
            int side = Math.min(each.width, each.height);
            if (largest == null || side > Math.min(largest.width, largest.height)) largest = each;
            if (side >= size && (fit == null || side < Math.min(fit.width, fit.height))) fit = each;
        }
        return fit != null ? fit.url : largest != null ? largest.url : null;
    }

    /** The items of the [pages] ticked in [ticked], in order, leaving out a page that saves nothing. */
    static List<MediaSave.Item> chosen(List<Page> pages, boolean[] ticked) {
        List<MediaSave.Item> chosen = new ArrayList<>();
        for (int index = 0; index < pages.size() && index < ticked.length; index++) {
            MediaSave.Item item = pages.get(index).item;
            if (ticked[index] && item != null) chosen.add(item);
        }
        return chosen;
    }

    /** Whether [activity] can't show or own a list any more. */
    static boolean gone(@Nullable Activity activity) {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    /** A row's picture size in pixels. */
    static int previewSize() {
        return Dim.dp(PREVIEW_DP);
    }

    /**
     * Saves [chosen] for [owner], the account in front when the list opened. Says why and saves
     * nothing when no page is ticked, the screen has closed or the account changed. Answers whether
     * the batch started or is waiting its turn.
     */
    static boolean save(Activity activity, List<MediaSave.Item> chosen, String owner) {
        Context application = Utils.getContext();
        if (application == null && activity != null) application = activity.getApplicationContext();
        if (application == null) return false;
        if (chosen.isEmpty()) {
            Feedback.show(application, L10n.t(application, "No pages chosen"), false);
            return false;
        }
        if (gone(activity)) {
            Feedback.show(application, L10n.t(application, "Not saved: the screen closed"), true);
            return false;
        }
        if (!owner.equals(DownloadHistory.owner())) {
            Feedback.show(application, L10n.t(application, "Not saved: the account changed"), true);
            return false;
        }
        if (!MediaSave.saveBatch(activity, chosen, null)) {
            Feedback.show(application, L10n.t(application, "Download failed"), true);
            return false;
        }
        return true;
    }

    /**
     * Shows [pages] over [activity] as a list with a box each, for the account [owner], or nothing
     * when [activity] is gone. Answers the list.
     */
    @Nullable
    static AlertDialog show(Activity activity, List<Page> pages, String owner) {
        if (gone(activity) || pages.isEmpty()) return null;
        boolean[] ticked = new boolean[pages.size()];
        AtomicBoolean open = new AtomicBoolean(true);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Choose pages"))
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .setPositiveButton(L10n.t(activity, "Save"), null);
        Context themed = builder.getContext();
        AlertDialog dialog = builder.create();
        LinearLayout rows = new LinearLayout(themed);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, Dim.dp(8), 0, Dim.dp(8));
        int size = previewSize();
        for (int index = 0; index < pages.size(); index++) {
            final int at = index;
            rows.addView(row(themed, pages.get(index), size, open, checked -> {
                ticked[at] = checked;
                Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (save != null) save.setEnabled(!chosen(pages, ticked).isEmpty());
            }));
        }
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(rows);
        dialog.setView(scroll);
        dialog.setOnDismissListener(closed -> open.set(false));
        dialog.show();
        ScreenColors.dialog(dialog);
        Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        save.setEnabled(false);
        save.setOnClickListener(view -> {
            try {
                List<MediaSave.Item> chosen = chosen(pages, ticked);
                if (chosen.isEmpty()) return;
                dialog.dismiss();
                save(activity, chosen, owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "carousel choose save", failure);
            }
        });
        return dialog;
    }

    /** What a row of [page] reads, its picture aside. */
    static String label(Context context, Page page) {
        String label = L10n.f(context, page.video ? "Page %1$d, video" : "Page %1$d, photo", page.number);
        return page.item != null ? label : label + "\n" + L10n.t(context, "Left out by your download switches");
    }

    /** Called with a row's box once it's ticked or unticked. */
    private interface Ticked {
        void changed(boolean checked);
    }

    /** A row of [page]: its picture, blank until it comes, then its box. A page that saves nothing is off. */
    private static View row(Context themed, Page page, int size, AtomicBoolean open, Ticked ticked) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp(56));
        row.setPaddingRelative(Dim.dp(20), Dim.dp(4), Dim.dp(20), Dim.dp(4));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView picture = new ImageView(themed);
        LinearLayout.LayoutParams pictureSize = new LinearLayout.LayoutParams(size, size);
        pictureSize.setMarginEnd(Dim.dp(12));
        picture.setLayoutParams(pictureSize);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable blank = new GradientDrawable();
        blank.setCornerRadius(Dim.dp(6));
        blank.setColor((ScreenColors.DEFAULT.summary & 0x00FFFFFF) | 0x33000000);
        picture.setImageDrawable(blank);
        picture.setClipToOutline(true);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(picture);

        CheckBox box = new CheckBox(themed);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.setText(label(themed, page));
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.setEnabled(page.item != null);
        box.setOnCheckedChangeListener((view, checked) -> ticked.changed(checked));
        row.addView(box);
        picture.setOnClickListener(view -> {
            if (box.isEnabled()) box.toggle();
        });
        if (page.preview != null) load(picture, page.preview, size, open);
        return row;
    }

    /**
     * Shows the picture at [url] in [view] while the list is [open], fetched on {@link #fetches}.
     * One the line can't take, or that doesn't come, leaves the blank square.
     */
    private static void load(ImageView view, String url, int size, AtomicBoolean open) {
        Context application = view.getContext().getApplicationContext();
        WeakReference<ImageView> target = new WeakReference<>(view);
        fetches().execute(() -> {
            if (!open.get()) return;
            Bitmap bitmap;
            try {
                bitmap = previews.fetch(application, url, size);
            } catch (Throwable failure) {
                Logger.printDebug(() -> "A carousel page's picture wasn't fetched: " + failure);
                return;
            }
            if (bitmap == null || !open.get()) return;
            Bitmap shown = fit(bitmap, size);
            Utils.runOnMainThread(() -> {
                ImageView image = target.get();
                if (open.get() && image != null) image.setImageBitmap(shown);
            });
        });
    }

    /** [bitmap] scaled down so its short side is [size] pixels, when it's larger than that. */
    static Bitmap fit(Bitmap bitmap, int size) {
        int shorter = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (size <= 0 || shorter <= size) return bitmap;
        float scale = size / (float) shorter;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    /**
     * The line the rows' pictures are fetched on: {@link #PREVIEW_THREADS} at a time, a carousel's
     * worth waiting, and the oldest one waiting dropped when another list's pictures come in.
     */
    static synchronized ThreadPoolExecutor fetches() {
        if (fetches == null) {
            fetches = new ThreadPoolExecutor(PREVIEW_THREADS, PREVIEW_THREADS, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(MediaSave.MAX_BATCH_PAGES), runnable -> {
                        Thread thread = new Thread(runnable, "HushGram carousel pictures");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }, new ThreadPoolExecutor.DiscardOldestPolicy());
            fetches.allowCoreThreadTimeOut(true);
        }
        return fetches;
    }
}
