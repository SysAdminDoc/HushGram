/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download on a song's page (#741), the page a reel's song opens: an icon beside the page's Save
 * that saves the whole song as an audio file, the way Download full song does from a reel's menu.
 * It follows the same switch, under Download any reel.
 *
 * The patch hands {@link #shown} the page and its view as the page finishes making the view.
 * Save's row and the song both come later, so the icon goes in on the first layout that has both,
 * and the song is read again at the tap, since the page can change it.
 */
public final class AudioPageSong {
    private static final String SOURCE = "AudioPageSong";

    /** Save on a song's page, which the icon goes just before. */
    static final String SAVE_ID = "secondary_bar_save_button";

    static final String SHOWN = "song page shown";
    static final String PLACED = "song page button placed";
    static final String NOT_PLACED = "song page button not placed";
    static final String SAVED = "saved a song from its page";

    /** Layouts the page gets to show Save and its song before the icon stops waiting. */
    static final int MAX_LAYOUTS = 120;

    private static final int ICON_DP = 24;

    private static volatile int saveId;

    private AudioPageSong() {
    }

    /** What a song's page icon carries: the page, so a tap reads the song it shows then. */
    static final class Marker {
        final WeakReference<Object> page;

        Marker(Object page) {
            this.page = new WeakReference<>(page);
        }
    }

    /** Whether a song's page gets the icon: Download any reel and its Download full song. Never throws. */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_REELS.get() && Settings.DOWNLOAD_REEL_SONG.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "song page switch", t);
            return false;
        }
    }

    /** Called with a song's [page] and its [view] at the end of the page's onViewCreated. Never throws. */
    public static void shown(Object page, Object view) {
        try {
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SHOWN);
            if (page == null || !(view instanceof View) || !on()) return;
            watch((View) view, page);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "song page", t);
        }
    }

    /** Tries to add the icon now and on each layout of [root] until it's in, the page goes or the cap runs out. */
    private static void watch(View root, Object page) {
        WeakReference<Object> held = new WeakReference<>(page);
        ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        int[] left = {MAX_LAYOUTS};
        listener[0] = () -> {
            try {
                if (settle(root, held) || --left[0] <= 0) stop(root, listener[0]);
            } catch (Throwable t) {
                stop(root, listener[0]);
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "song page layout", t);
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                stop(root, listener[0]);
                view.removeOnAttachStateChangeListener(this);
            }
        });
        root.post(() -> {
            try {
                if (settle(root, held)) stop(root, listener[0]);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "song page layout", t);
            }
        });
    }

    private static void stop(View root, ViewTreeObserver.OnGlobalLayoutListener listener) {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
    }

    /**
     * Adds the icon beside Save when Save shows and the page's song has a file to save. Answers
     * whether there's nothing left to wait for: the icon is in, can't go in, or the page is gone.
     */
    static boolean settle(View root, WeakReference<Object> held) {
        Object page = held.get();
        if (page == null || !on()) return true;
        Context context = root.getContext();
        int id = saveId(context);
        if (id == 0) {
            HookStatus.missingViewId(FamilyNames.REEL_DOWNLOAD, SAVE_ID);
            return true;
        }
        View save = root.findViewById(id);
        if (save == null || !save.isShown() || !(save.getParent() instanceof ViewGroup)) return false;
        ViewGroup row = (ViewGroup) save.getParent();
        if (existing(row) != null) return true;
        if (url(page) == null) return false;
        ImageView button = make(save, page);
        if (!FeedDownloadButton.place(row, save, button)) {
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, NOT_PLACED);
            return true;
        }
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, PLACED);
        return true;
    }

    private static int saveId(Context context) {
        int id = saveId;
        if (id == 0) {
            id = context.getResources().getIdentifier(SAVE_ID, "id", Utils.resourcePackage(context));
            saveId = id;
        }
        return id;
    }

    static ImageView existing(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && child.getTag() instanceof Marker) return (ImageView) child;
        }
        return null;
    }

    /** The icon, made the way the feed's Download icon is, with Save's scale and padding. */
    private static ImageView make(View save, Object page) {
        Context context = save.getContext();
        ImageView button = new ImageView(context);
        FeedDownloadButton.Glyph glyph = new FeedDownloadButton.Glyph(dp(context, ICON_DP));
        glyph.color = FeedDownloadButton.iconColor(context);
        button.setImageDrawable(glyph);
        button.setScaleType(save instanceof ImageView ? ((ImageView) save).getScaleType() : ImageView.ScaleType.CENTER);
        button.setPaddingRelative(save.getPaddingStart(), save.getPaddingTop(), save.getPaddingEnd(), save.getPaddingBottom());
        button.setContentDescription(L10n.t(context, "Download"));
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setTag(new Marker(page));
        button.setOnClickListener(AudioPageSong::tap);
        return button;
    }

    private static void tap(View button) {
        try {
            if (!(button.getTag() instanceof Marker) || !on()) return;
            Object page = ((Marker) button.getTag()).page.get();
            Context context = button.getContext();
            if (!save(context, page)) {
                Context application = context.getApplicationContext();
                Feedback.show(application, L10n.t(application, "Download failed"), true);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "song page tap", t);
        }
    }

    /** Saves the song [page] shows as an audio file. Answers whether a save started. */
    static boolean save(Context context, Object page) {
        Object song = page == null ? null : song(page);
        String url = song == null ? null : songUrl(song);
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "song page download tapped: "
                + (song == null ? "no song yet" : url == null ? "no song file" : "a song file"));
        if (url == null) return false;
        boolean started = MediaSave.saveAudio(context, url, details(song));
        if (started) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAVED);
        return started;
    }

    /** The song's file on [page], or null before the song loads or when it has none. */
    private static String url(Object page) {
        Object song = song(page);
        return song == null ? null : songUrl(song);
    }

    /** The save's name: the artist and the song's title, whichever are known. */
    static PostDetails details(Object song) {
        String title = blankless(songTitle(song));
        String artist = blankless(songArtist(song));
        String name = title != null && artist != null ? artist + " - " + title : title != null ? title : artist;
        return PostDetails.of(null, name, null);
    }

    private static String blankless(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** The song [page] holds, or null before it loads. The patch writes the body. */
    public static Object song(Object page) {
        return null;
    }

    /** The address of [song]'s whole file. The patch writes the body. */
    public static String songUrl(Object song) {
        return null;
    }

    /** [song]'s title. The patch writes the body. */
    public static String songTitle(Object song) {
        return null;
    }

    /** [song]'s artist as Instagram shows it. The patch writes the body. */
    public static String songArtist(Object song) {
        return null;
    }
}
