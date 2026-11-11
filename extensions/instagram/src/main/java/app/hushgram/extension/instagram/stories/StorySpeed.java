/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Speed button on stories patch (#469): a speed button in a video story's header that
 * plays the story at 1x, 1.5x or 2x, and later video stories keep the speed.
 *
 * <p>The story viewer, ReelViewerFragment, hears from its video player each time a story's video
 * starts, with the story and the viewer's page it plays in. The patch hands those to {@link #playing}
 * as that callback ends, after Instagram's own handling. While the switch is on the page's header gets
 * the button, in Instagram's row of buttons before the three-dot menu, and the video gets the kept
 * speed through the player's own speed setter. A tap moves to the next speed and sets it on the video
 * playing. A photo has no video, so the callback never comes for it, and {@link #bound}, called each
 * time the viewer's binder puts a story into a page, hides a button left over from the page's last
 * story.
 *
 * <p>The speed lives in memory only, so it's gone when Instagram restarts. Ads play at normal speed
 * and get no button. With the switch off, HushGram paused or the settings not read yet, the kept speed
 * is forgotten, a video this class sped up goes back to normal speed when the next one starts, and
 * every button hides. A switch that was never on costs one check per story. Nothing here throws into
 * Instagram.
 *
 * <p>With Speed in the story menu on (#621) the button stays hidden and a video story's three-dot
 * menu gets Playback speed instead, through Download any story's menu hooks
 * ({@link app.hushgram.extension.instagram.download.StoryDownload}). A tap there opens the same
 * speeds as a list, {@link #fromMenu}, and a pick is kept and set on the video playing as a tap on
 * the button is. The story stays paused until the list closes ({@link #afterList}).
 */
public final class StorySpeed {
    static final float NORMAL = 1f;

    /** Two speeds this close are the same one. */
    static final float SAME = 0.01f;

    /** Instagram's row of buttons at the top end of the story header, the one the Download button uses. */
    static final String CONTAINER = StoryDownloadButton.CONTAINER;

    /** How far up from a page's media view the header is looked for. */
    static final int MAX_DEPTH = 8;

    static final String HOOK = "story speed";

    /** Counted under the patch's name for each video story started at a kept speed. */
    static final String APPLIED = "story started at the kept speed";

    /** Counted under the patch's name for each tap on the button. */
    static final String TAPPED = "speed changed";

    /** Counted under the patch's name for each speed picked from a story's menu. */
    static final String PICKED = "speed picked in the story menu";

    /** The step a failure in the menu's row or list is reported under. */
    static final String MENU = "story menu speed";

    private static final String FAMILY = FamilyNames.STORY_SPEED;
    private static final ResourceIdCache IDS = new ResourceIdCache();

    /** What this class does to the viewer, a page and a story. {@link #PATCHED} is the patch's; tests stand in. */
    interface Access {
        /** Sets the speed of the video the viewer plays. True once it reached the player. */
        boolean setSpeed(Object viewer, float speed);

        /** Whether [holder] is one of the viewer's pages. */
        boolean isPage(Object holder);

        /** The view a page shows its story's media in, or null. */
        @Nullable
        View pageView(Object page);

        /** Whether the story is an ad. */
        boolean ad(Object item);
    }

    static final Access PATCHED = new Access() {
        @Override
        public boolean setSpeed(Object viewer, float speed) {
            return StorySpeedReader.setSpeed(viewer, speed);
        }

        @Override
        public boolean isPage(Object holder) {
            return StorySpeedReader.isPage(holder);
        }

        @Override
        public View pageView(Object page) {
            return StorySpeedReader.pageView(page);
        }

        @Override
        public boolean ad(Object item) {
            return StorySpeedReader.ad(item);
        }
    };

    static volatile Access access = PATCHED;

    private static final Object LOCK = new Object();

    /** The speed kept for the next video stories, {@link #NORMAL} when none is. */
    private static float kept = NORMAL;

    /** The speed this class set on a player last, so normal speed is put back only when it changed one. */
    private static float applied = NORMAL;

    /** Whether a button was ever added, so a switch that was never on costs nothing per story. */
    private static volatile boolean added;

    /** The viewer the last video story started in, for a speed picked from the menu. */
    private static volatile WeakReference<Object> lastViewer = new WeakReference<>(null);

    /** Whether the last video story that started was an ad, which a pick from the menu leaves alone. */
    private static volatile boolean playingAd;

    /** Whether the speed list a story's menu opened is showing. Main thread only. */
    private static boolean listOpen;

    /** What runs once that list closes, the menu's own close. Main thread only. */
    @Nullable
    private static Runnable afterList;

    /** The activity that list shows over. One destroyed under it never reports the list closed. */
    private static WeakReference<Activity> listOver = new WeakReference<>(null);

    /** Whether a test says the menu's hooks are in the build, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean menuInBuildForTests;

    /** Shows the speeds as a list over [activity], [kept] checked, hands a pick to [chosen] and runs [closed] once it goes. */
    interface Lists {
        void show(Activity activity, float kept, Chosen chosen, Runnable closed);
    }

    interface Chosen {
        void chose(float speed);
    }

    static final Lists LISTS = (activity, kept, chosen, closed) -> {
        float[] all = speeds();
        CharSequence[] labels = new CharSequence[all.length];
        int checked = -1;
        for (int i = 0; i < all.length; i++) {
            labels[i] = text(all[i]);
            if (checked < 0 && same(all[i], kept)) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Playback speed"))
                .setSingleChoiceItems(labels, checked, (shown, which) -> {
                    shown.dismiss();
                    if (which >= 0 && which < all.length) chosen.chose(all[which]);
                })
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .create();
        dialog.setOnDismissListener(gone -> closed.run());
        dialog.show();
        ScreenColors.dialog(dialog);
    };

    static volatile Lists lists = LISTS;

    private StorySpeed() {
    }

    /** The speeds a tap moves through, in order, back to the first after the last. */
    static float[] speeds() {
        return new float[]{1f, 1.5f, 2f};
    }

    /** The button this class adds, told apart from the row's own buttons by its class. */
    static final class SpeedButton extends TextView {
        WeakReference<Object> story = new WeakReference<>(null);
        WeakReference<Object> viewer = new WeakReference<>(null);

        SpeedButton(Context context) {
            super(context);
        }
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, as the viewer's callback for a story's video starting ends: [viewer] is the
     * ReelViewerFragment, [item] the story and [holder] what the video plays in, normally one of the
     * viewer's pages. Sets the kept speed and shows the button. Never throws.
     */
    public static void playing(@Nullable Object viewer, @Nullable Object item, @Nullable Object holder) {
        try {
            HookStatus.invoked(FAMILY);
            if (viewer == null) return;
            Access reader = access;
            boolean on = wanted();
            boolean ad = on && item != null && reader.ad(item);
            if (on) {
                if (lastViewer.get() != viewer) lastViewer = new WeakReference<>(viewer);
                playingAd = ad;
            }
            float speed;
            float before;
            synchronized (LOCK) {
                if (!on) kept = NORMAL;
                speed = on && !ad ? kept : NORMAL;
                before = applied;
            }
            if (!same(speed, NORMAL) || !same(before, NORMAL)) {
                if (reader.setSpeed(viewer, speed)) {
                    synchronized (LOCK) {
                        applied = speed;
                    }
                    if (!same(speed, NORMAL)) {
                        HookStatus.counted(FAMILY, APPLIED);
                        Logger.printDebug(() -> "Story speed: a video story started, set to the kept " + speed + "x");
                    } else {
                        Logger.printDebug(() -> ad
                                ? "Story speed: an ad started, set back to normal speed"
                                : "Story speed: set back to normal speed");
                    }
                }
            }
            // With Speed in the story menu on, the speed is kept but the button stays hidden.
            boolean show = on && !ad && item != null && buttonWanted();
            if (!show && !added) return;
            Utils.runOnMainThreadNowOrLater(() -> header(reader, viewer, item, holder, show));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * The hook, right after the viewer's binder puts [item] into [page]. A button left from the page's
     * last story hides until that story's video starts. Never throws.
     */
    public static void bound(@Nullable Object page, @Nullable Object item) {
        try {
            if (!added || page == null) return;
            HookStatus.invoked(FAMILY);
            Access reader = access;
            if (!reader.isPage(page)) return;
            View media = reader.pageView(page);
            if (media == null) return;
            LinearLayout row = row(media, false);
            if (row == null) return;
            SpeedButton button = find(row);
            if (button != null && (button.story.get() != item || !buttonWanted())) hide(button);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    // ------------------------------------------------------------------ the button

    /** Shows the button for [item] in [holder]'s header, or hides it there when [show] is false. */
    private static void header(Access reader, Object viewer, @Nullable Object item, @Nullable Object holder, boolean show) {
        try {
            if (holder == null || !reader.isPage(holder)) return;
            View media = reader.pageView(holder);
            if (media == null) return;
            LinearLayout row = row(media, show);
            if (row == null) return;
            SpeedButton button = find(row);
            if (!show) {
                if (button != null) hide(button);
                return;
            }
            if (button == null) {
                button = create(row.getContext());
                StoryDownloadButton.insert(row, button);
                added = true;
            }
            button.story = new WeakReference<>(item);
            button.viewer = new WeakReference<>(viewer);
            label(button, current());
            button.setVisibility(View.VISIBLE);
            HookStatus.bound(FAMILY, "story header");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * A tap on [button]: the next speed, kept and set on the video playing. A button that was hidden,
     * or whose viewer is gone, changes nothing. Never throws.
     */
    static void tapped(SpeedButton button) {
        try {
            if (!buttonWanted()) {
                // Moved to the menu, the speed stays kept. Off, it's forgotten.
                if (!wanted()) drop();
                hide(button);
                return;
            }
            Object viewer = button.viewer.get();
            if (viewer == null) {
                hide(button);
                return;
            }
            float next;
            synchronized (LOCK) {
                next = next(kept);
                kept = next;
            }
            label(button, next);
            if (access.setSpeed(viewer, next)) {
                synchronized (LOCK) {
                    applied = next;
                }
            }
            HookStatus.counted(FAMILY, TAPPED);
            Logger.printDebug(() -> "Story speed: tapped, the story and the next ones play at " + next + "x");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /** The speed after [speed] in {@link #speeds}, the first after the last or after one not among them. */
    static float next(float speed) {
        float[] all = speeds();
        for (int i = 0; i < all.length - 1; i++) {
            if (same(all[i], speed)) return all[i + 1];
        }
        return all[0];
    }

    /** "1x", "1.5x" or "2x", with the phone's decimal mark. */
    static String text(float speed) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(1);
        return format.format(speed) + "x";
    }

    private static void label(SpeedButton button, float speed) {
        String text = text(speed);
        button.setText(text);
        button.setContentDescription(L10n.f(button.getContext(), "Playback speed, %1$s", text));
    }

    /**
     * The header's row of buttons for the page showing [media]: going up from it, the first view
     * holding the row. Reports a build without it while the button is wanted.
     */
    @Nullable
    static LinearLayout row(View media, boolean wanted) {
        int id = IDS.resolve(media.getResources(), Utils.resourcePackage(media.getContext()), CONTAINER, false);
        View at = id == 0 ? null : media;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            View found = at.findViewById(id);
            if (found != null) {
                if (!(found instanceof LinearLayout)) break;
                HookStatus.recoveredViewId(FAMILY, CONTAINER);
                return (LinearLayout) found;
            }
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        if (wanted) HookStatus.missingViewId(FAMILY, CONTAINER);
        return null;
    }

    @Nullable
    static SpeedButton find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof SpeedButton) return (SpeedButton) row.getChildAt(i);
        }
        return null;
    }

    private static SpeedButton create(Context context) {
        SpeedButton button = new SpeedButton(context);
        float density = context.getResources().getDisplayMetrics().density;
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setShadowLayer(2 * density, 0, 0, 0x66000000);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> tapped((SpeedButton) view));
        return button;
    }

    /** Hides [button] and forgets its story and viewer, so a tap can't reach them until it shows again. */
    private static void hide(SpeedButton button) {
        button.setVisibility(View.GONE);
        button.story = new WeakReference<>(null);
        button.viewer = new WeakReference<>(null);
    }

    // ------------------------------------------------------------------ the story menu

    /**
     * Whether a video story's menu gets Playback speed: Speed in the story menu on, with Download any
     * story's menu hooks in the build. Paused answers no. Never throws.
     */
    public static boolean offersRow() {
        try {
            return inMenu();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, MENU, failure);
            return false;
        }
    }

    /** The menu's row, Playback speed, in the app's language. */
    public static String menuLabel() {
        return L10n.t(Utils.getContext(), "Playback speed");
    }

    /** Whether [label], a tapped row, is Playback speed, however the menu styled it. */
    public static boolean isMenuLabel(@Nullable CharSequence label) {
        return label != null && menuLabel().contentEquals(label);
    }

    /**
     * A tap on Playback speed in a story's menu: the speeds as a list over the screen, the kept one
     * checked. A pick is kept for the next video stories and set on the video playing. Answers
     * whether the list showed. Never throws.
     */
    public static boolean fromMenu() {
        try {
            if (!inMenu()) return false;
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) return false;
            HookStatus.invoked(FAMILY);
            listOpen = true;
            afterList = null;
            listOver = new WeakReference<>(activity);
            try {
                lists.show(activity, current(), StorySpeed::picked, StorySpeed::listClosed);
            } catch (Throwable failure) {
                listOpen = false;
                throw failure;
            }
            Logger.printDebug(() -> "Story speed: the story menu's speed list opened at " + current() + "x");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, MENU, failure);
            return false;
        }
    }

    /**
     * Holds [then], the story menu's own close, which plays the story again, until the speed list
     * closes, and answers true. False when no list is open, so the caller runs it now.
     */
    public static boolean afterList(Runnable then) {
        if (!listOpen) return false;
        Activity over = listOver.get();
        if (over == null || over.isFinishing() || over.isDestroyed()) {
            // Gone with its activity, the list never says it closed.
            listOpen = false;
            afterList = null;
            return false;
        }
        afterList = then;
        return true;
    }

    /** The speed list closed, picked or not: what waited for it runs. */
    private static void listClosed() {
        listOpen = false;
        Runnable then = afterList;
        afterList = null;
        if (then != null) then.run();
    }

    /** [speed], picked from the menu's list: kept, and set on the video playing unless it's an ad. Never throws. */
    static void picked(float speed) {
        try {
            if (!inMenu()) return;
            synchronized (LOCK) {
                kept = speed;
            }
            Object viewer = lastViewer.get();
            boolean set = viewer != null && !playingAd && access.setSpeed(viewer, speed);
            if (set) {
                synchronized (LOCK) {
                    applied = speed;
                }
            }
            HookStatus.counted(FAMILY, PICKED);
            Logger.printDebug(() -> "Story speed: picked " + speed + "x in the story menu, "
                    + (set ? "set on the story playing" : "kept for the next video story"));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, MENU, failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    /**
     * Whether the speed is kept: the button's switch or Speed in the story menu on, HushGram not
     * paused, the settings read.
     */
    static boolean wanted() {
        return Utils.settingsReady() && (Settings.STORY_SPEED_BUTTON.get() || inMenu());
    }

    /** Whether the button shows: its switch on and the speed not moved to the story menu. */
    static boolean buttonWanted() {
        return Utils.settingsReady() && Settings.STORY_SPEED_BUTTON.get() && !inMenu();
    }

    /** Whether the speed is in the story menu: its switch on, with the menu's hooks in the build. */
    static boolean inMenu() {
        return Utils.settingsReady() && Settings.STORY_SPEED_IN_MENU.get() && menuInBuild();
    }

    /** Whether Download any story's menu hooks, which carry the row, are in the build along with this patch. */
    static boolean menuInBuild() {
        Boolean forced = menuInBuildForTests;
        return forced != null ? forced : SettingsStatus.storySpeed() && SettingsStatus.storyDownload();
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < SAME;
    }

    /** Off or paused: nothing is kept, so turning the switch back on starts at normal speed. */
    private static void drop() {
        synchronized (LOCK) {
            kept = NORMAL;
        }
    }

    /** The speed kept for the next video stories. */
    static float current() {
        synchronized (LOCK) {
            return kept;
        }
    }

    // ------------------------------------------------------------------ tests

    @VisibleForTesting
    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(Utils.resourcePackage(context), name, id);
    }

    /**
     * Forgets the kept speed, the speed last set, the ids looked up, that a button was added, the
     * last viewer and an open list.
     */
    @VisibleForTesting
    static void resetForTests() {
        synchronized (LOCK) {
            kept = NORMAL;
            applied = NORMAL;
        }
        added = false;
        IDS.clear();
        access = PATCHED;
        lastViewer = new WeakReference<>(null);
        playingAd = false;
        listOpen = false;
        afterList = null;
        listOver = new WeakReference<>(null);
        lists = LISTS;
        menuInBuildForTests = null;
    }
}
