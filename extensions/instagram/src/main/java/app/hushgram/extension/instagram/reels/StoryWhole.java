/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Whole story in the story menu (#845): with Stories fill the screen on, Show the whole story in the
 * menu a story's three dots open fits that one story inside the screen, without turning Stories fill
 * the screen off for every story.
 *
 * <p>Stories fill the screen lays a story out over the whole screen ({@link ReelFill#story}), so on a
 * screen taller than 9:16 a story loses its sides. The row gives that story's frame, the rounded view
 * its picture or video fills, a 9:16 shape across the screen's width, centered with a band above and
 * below, the way a story sharing a reel keeps its frame ({@link ReelFill#storyFrame}).
 *
 * <p>It lasts for that story. The next story Instagram binds to the same page, or the page leaving the
 * window, puts Instagram's frame back, and Fill the screen, the row's label while the story shows
 * whole, does it right away. A story that isn't cut, as a shared reel's frame isn't, gets no row.
 *
 * <p>The row rides on Download any story's menu hooks, like Copy text: {@link
 * app.hushgram.extension.instagram.download.StoryDownload#labels} adds it and its save hands the tap here.
 */
public final class StoryWhole {
    /** What the menu offers for the story on screen: show it whole, crop it again, or nothing. */
    public enum Offer {
        WHOLE,
        CROP,
        NONE
    }

    static final String FAMILY = FamilyNames.FULL_SCREEN_BARS;
    static final String COUNT_WHOLE = "stories shown whole from the menu";

    /** How far a frame's shape may be from 9:16, as a share of it, and still count as whole. */
    static final float NO_CUT = 0.01f;

    /** Each story frame Stories fill the screen has laid out, with the page it was last laid out for. Weak. */
    static final Map<View, Object> FRAMES = Collections.synchronizedMap(new WeakHashMap<>());

    /** The frames shown whole, with the layout Instagram had given each. Weak. */
    static final Map<View, ViewGroup.LayoutParams> WHOLE = Collections.synchronizedMap(new WeakHashMap<>());

    private StoryWhole() {
    }

    /** Whether the switch and Stories fill the screen are on. Paused answers no. Never throws. */
    static boolean wanted() {
        try {
            return Utils.settingsReady() && Settings.WHOLE_STORY_IN_STORY_MENU.get() && Settings.FILL_STORY_SCREEN.get();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "whole story switch", failure);
            return false;
        }
    }

    /**
     * Whether a story's menu can get the row: {@link #wanted}, and Download any story, whose menu hooks
     * carry the row, is in the build. Never throws.
     */
    public static boolean offersRow() {
        try {
            return wanted() && SettingsStatus.storyDownload();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "whole story switch", failure);
            return false;
        }
    }

    /** The row for the story on screen in [activity], in the app's language, or null when it gets none. Never throws. */
    @Nullable
    public static String menuLabel(@Nullable Activity activity) {
        if (!offersRow()) return null;
        Offer offer = offer(activity);
        if (offer == Offer.NONE) return null;
        Context context = Utils.getContext();
        return offer == Offer.CROP ? L10n.t(context, "Fill the screen") : L10n.t(context, "Show the whole story");
    }

    /** Whether [label], a tapped row, is Show the whole story or Fill the screen, however the menu styled it. */
    public static boolean isMenuLabel(@Nullable CharSequence label) {
        if (label == null) return false;
        Context context = Utils.getContext();
        return L10n.t(context, "Show the whole story").contentEquals(label) || L10n.t(context, "Fill the screen").contentEquals(label);
    }

    /** What the menu offers for the story on screen in [activity]. Never throws. */
    public static Offer offer(@Nullable Activity activity) {
        try {
            if (!wanted()) return Offer.NONE;
            View frame = onScreen(activity);
            if (frame == null) return Offer.NONE;
            if (WHOLE.containsKey(frame)) return Offer.CROP;
            return cut(frame) ? Offer.WHOLE : Offer.NONE;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "whole story offer", failure);
            return Offer.NONE;
        }
    }

    /**
     * The row's tap: the story on screen in [activity] shows whole, or, shown whole already, fills the
     * screen again. Answers what it did, {@link Offer#NONE} when there was nothing to do. Never throws.
     */
    public static Offer toggle(@Nullable Activity activity) {
        try {
            if (!wanted()) return Offer.NONE;
            View frame = onScreen(activity);
            if (frame == null) return Offer.NONE;
            if (WHOLE.containsKey(frame)) {
                restore(frame);
                return Offer.CROP;
            }
            if (!cut(frame)) return Offer.NONE;
            ViewGroup.LayoutParams instagrams = frame.getLayoutParams();
            if (instagrams == null || !ReelFill.frameWhole(frame)) return Offer.NONE;
            WHOLE.put(frame, instagrams);
            frame.addOnAttachStateChangeListener(LEAVING);
            HookStatus.counted(FAMILY, COUNT_WHOLE);
            return Offer.WHOLE;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "whole story tap", failure);
            return Offer.NONE;
        }
    }

    /**
     * Told by {@link ReelFill#storyFrame} each time it lays out [frame] for [holder], the page the story
     * is bound to, with Stories fill the screen on. True when that frame shows its story whole.
     */
    static boolean laidOut(Object holder, View frame) {
        FRAMES.put(frame, holder);
        return WHOLE.containsKey(frame);
    }

    /**
     * Told by {@link ReelFill#storyBound} as Instagram binds a story to [holder]: a frame of that page
     * shown whole gets Instagram's frame back, so the next story fills the screen again.
     */
    static void bound(Object holder) {
        for (View frame : new ArrayList<>(WHOLE.keySet())) {
            if (FRAMES.get(frame) == holder) restore(frame);
        }
    }

    /** Puts back the layout Instagram gave [frame] before it showed whole. */
    static void restore(View frame) {
        ViewGroup.LayoutParams instagrams = WHOLE.remove(frame);
        frame.removeOnAttachStateChangeListener(LEAVING);
        if (instagrams != null) frame.setLayoutParams(instagrams);
    }

    /** Whether [frame]'s shape is off 9:16 by more than {@link #NO_CUT}, so the story in it is cut. */
    static boolean cut(View frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= 0 || height <= 0) return false;
        float shape = (float) width / height;
        return Math.abs(shape / ReelFill.REEL_SHAPE - 1f) > NO_CUT;
    }

    /** A frame shown whole that leaves the window gets Instagram's frame back. */
    private static final View.OnAttachStateChangeListener LEAVING = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                restore(view);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "whole story leaving", failure);
            }
        }
    };

    /**
     * The frame of the story on screen in [activity]: one Stories fill the screen laid out, shown, in
     * that window, covering its middle. Null when there's none.
     */
    @Nullable
    static View onScreen(@Nullable Activity activity) {
        if (activity == null || activity.getWindow() == null) return null;
        View root = activity.getWindow().getDecorView();
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        int middleX = origin[0] + root.getWidth() / 2;
        int middleY = origin[1] + root.getHeight() / 2;
        Rect shown = new Rect();
        for (View frame : new ArrayList<>(FRAMES.keySet())) {
            if (frame == null || frame.getRootView() != root || !frame.isShown() || !frame.getGlobalVisibleRect(shown)) continue;
            if (shown.contains(middleX, middleY)) return frame;
        }
        return null;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        for (View frame : new ArrayList<>(WHOLE.keySet())) restore(frame);
        WHOLE.clear();
        FRAMES.clear();
    }
}
