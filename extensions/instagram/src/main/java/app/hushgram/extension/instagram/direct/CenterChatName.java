/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Center names in chats (#779): the name and username at the top of a chat sit in the middle of its bar.
 *
 * <p>No part of Instagram's code changes. Once an activity is in front, this looks before each frame for the
 * chat's header the way {@link GlassChat} finds it, then for the area in it holding the name and the username
 * under it ({@link #NAME_AREA}). Instagram lays that area out between the picture and the call buttons with
 * each line at its start. Each line is moved sideways onto one middle line, which is the bar's middle when the
 * longest line fits there and as near it as the picture and the buttons leave room for when it doesn't. The
 * area moves with them only when it's narrower than that room. Only translations change, so nothing is laid
 * out again and a tap on the name still opens the chat's details.
 *
 * <p>The username is drawn by a plain view's background and not by a TextView, and that background says
 * nothing of how wide it draws, so a line's width is its TextView's text, or else its background's or
 * foreground's own width, or else the columns its background inks when drawn off screen, or else the whole
 * line. Instagram swaps what the username line says (the username, then when they were last active), so the
 * ink is measured again every {@link #INK_EVERY_MS}.
 *
 * <p>Off, paused or anything thrown: every line and the area go back where Instagram put them.
 */
public final class CenterChatName {
    static final String HOOK = "chat name centering";
    static final String COUNT_CENTERED = "chat names centered";
    /** The area holding the chat's name and username, by the name Instagram's header looks it up by. */
    static final String NAME_AREA = ChatPager.NAME_AREA;
    /** How long the search for a chat waits between tries while there's none, so a feed isn't searched every frame. */
    static final long SEARCH_EVERY_MS = GlassChat.SEARCH_EVERY_MS;

    private static boolean registered;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    @Nullable private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static WeakReference<View> area = new WeakReference<>(null);
    /** Every view this has moved, so each can be put back. */
    private static final List<WeakReference<View>> moved = new ArrayList<>();
    private static long searchedAt = -SEARCH_EVERY_MS;
    /** The look again a skipped search waits for, while it's waiting. */
    @Nullable private static Runnable lookAgain;
    private static boolean counted;
    /** Where one line draws in its area, its start and end, reused frame to frame. */
    private static final float[] SPAN = new float[2];
    /** How long a background's measured ink is trusted before it's drawn and measured again. */
    static final long INK_EVERY_MS = 250;
    /** The most pixels a background is drawn into to measure it, past which the whole line counts. */
    private static final int INK_MOST_PIXELS = 1 << 20;
    /** Each background measured lately: width, height, when, first inked column, last inked column + 1. */
    private static final WeakHashMap<Drawable, long[]> inks = new WeakHashMap<>();
    @Nullable private static Bitmap inkBitmap;
    @Nullable private static Canvas inkCanvas;
    @Nullable private static int[] inkPixels;

    private CenterChatName() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.centerChatName()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CENTER_CHAT_NAME, "start", failure);
        }
    }

    /** Whether the names are wanted in the middle now. */
    static boolean on() {
        return Utils.settingsReady() && Settings.CENTER_CHAT_NAME.get();
    }

    /** Looks at the activity's chat header before a frame and centers its name, or puts it back. Never throws. */
    static void check(Activity activity) {
        try {
            if (!on()) {
                restore();
                return;
            }
            View known = area.get();
            if (known != null && known.isAttachedToWindow() && known.isShown()) {
                center(known);
                return;
            }
            if (known != null) restore();
            long now = SystemClock.uptimeMillis();
            long waited = now - searchedAt;
            if (waited < SEARCH_EVERY_MS) {
                lookAgainLater(activity, SEARCH_EVERY_MS - waited);
                return;
            }
            searchedAt = now;
            View found = find(activity);
            if (found == null) return;
            area = new WeakReference<>(found);
            HookStatus.invoked(FamilyNames.CENTER_CHAT_NAME);
            if (!counted) {
                counted = true;
                HookStatus.counted(FamilyNames.CENTER_CHAT_NAME, COUNT_CENTERED);
            }
            String how = center(found);
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, HOOK, () -> "chat name found, " + how);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CENTER_CHAT_NAME, HOOK, failure);
            try {
                restore();
            } catch (Throwable ignored) {
                // Already reporting the first failure.
            }
        }
    }

    /** The area holding the name of the chat the activity shows, or null when it shows none. */
    @Nullable
    static View find(Activity activity) {
        View header = GlassChat.find(activity);
        if (header == null) return null;
        int id = GlassChat.id(activity, NAME_AREA);
        if (id == 0) return null;
        View found = header.findViewById(id);
        return found instanceof ViewGroup && found.isShown() ? found : null;
    }

    /**
     * Moves the lines of [view] onto one middle line, as near the middle of the bar holding it as the room
     * around it allows. Says what it did, for the diagnostics log.
     */
    static String center(View view) {
        if (!(view instanceof ViewGroup)) return "not a group";
        ViewGroup group = (ViewGroup) view;
        ViewParent parent = group.getParent();
        int width = group.getWidth();
        if (!(parent instanceof ViewGroup) || width <= 0) return "not laid out";
        ViewGroup bar = (ViewGroup) parent;
        float widest = 0f;
        int lines = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View line = group.getChildAt(i);
            if (line.getVisibility() != View.VISIBLE) continue;
            span(line, SPAN);
            widest = Math.max(widest, SPAN[1] - SPAN[0]);
            lines++;
        }
        if (lines == 0 || widest <= 0f) return "no lines";
        // The room on the bar from the view before the area to the one after it, in the area's own terms.
        float roomStart = -group.getLeft();
        float roomEnd = bar.getWidth() - group.getLeft();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View other = bar.getChildAt(i);
            if (other == group || other.getVisibility() != View.VISIBLE || other.getWidth() <= 0) continue;
            if (other.getRight() <= group.getLeft()) roomStart = Math.max(roomStart, other.getRight() - group.getLeft());
            else if (other.getLeft() >= group.getRight()) roomEnd = Math.min(roomEnd, other.getLeft() - group.getLeft());
        }
        ViewGroup.LayoutParams params = group.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            roomStart = Math.min(0f, roomStart + margins.leftMargin);
            roomEnd = Math.max(width, roomEnd - margins.rightMargin);
        } else {
            roomStart = Math.min(0f, roomStart);
            roomEnd = Math.max(width, roomEnd);
        }
        float half = widest / 2f;
        float middle = bar.getWidth() / 2f - group.getLeft();
        float axis = Math.max(roomStart + half, Math.min(roomEnd - half, middle));
        // The area moves only as far as it must to keep every line inside it, so nothing is cut off.
        float shift = Math.max(axis + half - width, Math.min(axis - half, 0f));
        int areaShift = Math.round(shift);
        move(group, areaShift);
        for (int i = 0; i < group.getChildCount(); i++) {
            View line = group.getChildAt(i);
            if (line.getVisibility() != View.VISIBLE) continue;
            span(line, SPAN);
            move(line, Math.round(axis - areaShift - (SPAN[0] + SPAN[1]) / 2f));
        }
        return String.format(Locale.US, "%d lines, widest %.0f of %d, bar %d, middle at %.0f, area moved %d",
                lines, widest, width, bar.getWidth(), axis + group.getLeft(), areaShift);
    }

    /** Where [line] draws in its area, before it's moved: its text, or what a drawable on it draws, or all of it. */
    static void span(View line, float[] into) {
        float start = line.getLeft();
        float end = line.getRight();
        if (line instanceof TextView) {
            TextView text = (TextView) line;
            Layout layout = text.getLayout();
            ViewGroup.LayoutParams params = line.getLayoutParams();
            boolean wraps = params != null && params.width == ViewGroup.LayoutParams.WRAP_CONTENT;
            if (layout != null && !wraps && layout.getLineCount() > 0) {
                float from = Float.MAX_VALUE;
                float to = -Float.MAX_VALUE;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    from = Math.min(from, layout.getLineLeft(i));
                    to = Math.max(to, layout.getLineRight(i));
                }
                float offset = line.getLeft() + text.getCompoundPaddingLeft();
                start = Math.max(start, offset + from);
                end = Math.min(end, offset + to);
            }
        } else {
            int drawn = drawnWidth(line.getBackground(), line.getWidth());
            if (drawn <= 0) drawn = drawnWidth(line.getForeground(), line.getWidth());
            if (drawn > 0) {
                if (line.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) start = end - drawn;
                else end = start + drawn;
            } else {
                long[] ink = ink(line.getBackground(), line.getWidth(), line.getHeight());
                if (ink != null) {
                    end = start + ink[4];
                    start += ink[3];
                }
            }
        }
        into[0] = start;
        into[1] = Math.max(start, end);
    }

    /**
     * The columns [drawable] inks across a line [width] by [height], as {width, height, when, first, last + 1},
     * or null when it inks nothing, can't be drawn off screen, or is too big to draw. It's drawn with no callback,
     * so measuring never asks the view for another frame, and at the line's size when it hasn't been drawn yet.
     */
    @Nullable
    static long[] ink(@Nullable Drawable drawable, int width, int height) {
        if (drawable == null || width <= 0 || height <= 0 || (long) width * height > INK_MOST_PIXELS) return null;
        long now = SystemClock.uptimeMillis();
        long[] known = inks.get(drawable);
        if (known != null && known[0] == width && known[1] == height && now - known[2] < INK_EVERY_MS) {
            return known[4] > known[3] ? known : null;
        }
        long[] measured = {width, height, now, 0, 0};
        inks.put(drawable, measured);
        Drawable.Callback callback = drawable.getCallback();
        Rect bounds = drawable.copyBounds();
        boolean resized = bounds.left != 0 || bounds.top != 0 || bounds.right != width || bounds.bottom != height;
        try {
            Bitmap bitmap = inkBitmap;
            if (bitmap == null || bitmap.getWidth() < width || bitmap.getHeight() < height) {
                int wide = bitmap == null ? width : Math.max(width, bitmap.getWidth());
                int tall = bitmap == null ? height : Math.max(height, bitmap.getHeight());
                if (bitmap != null) bitmap.recycle();
                inkBitmap = bitmap = Bitmap.createBitmap(wide, tall, Bitmap.Config.ARGB_8888);
                inkCanvas = new Canvas(bitmap);
            }
            Canvas canvas = inkCanvas;
            bitmap.eraseColor(Color.TRANSPARENT);
            drawable.setCallback(null);
            if (resized) drawable.setBounds(0, 0, width, height);
            int saved = canvas.save();
            try {
                canvas.clipRect(0, 0, width, height);
                drawable.draw(canvas);
            } finally {
                canvas.restoreToCount(saved);
            }
            int[] pixels = inkPixels;
            if (pixels == null || pixels.length < width * height) inkPixels = pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            int first = -1;
            for (int x = 0; x < width && first < 0; x++) {
                if (inked(pixels, x, width, height)) first = x;
            }
            if (first < 0) return null;
            int last = first;
            for (int x = width - 1; x > first; x--) {
                if (inked(pixels, x, width, height)) {
                    last = x;
                    break;
                }
            }
            measured[3] = first;
            measured[4] = last + 1;
            return measured;
        } catch (Throwable unmeasurable) {
            // A hardware bitmap or a drawable that only draws on screen: the whole line counts.
            return null;
        } finally {
            if (resized) drawable.setBounds(bounds);
            drawable.setCallback(callback);
        }
    }

    private static boolean inked(int[] pixels, int x, int width, int height) {
        for (int y = 0; y < height; y++) {
            if ((pixels[y * width + x] >>> 24) != 0) return true;
        }
        return false;
    }

    /** How wide [drawable] draws when it's narrower than the line holding it, 0 when that isn't known. */
    private static int drawnWidth(@Nullable Drawable drawable, int line) {
        int natural = drawable == null ? -1 : drawable.getIntrinsicWidth();
        return natural > 0 && natural < line ? natural : 0;
    }

    private static void move(View view, int by) {
        if (Math.abs(view.getTranslationX() - by) < 0.5f) return;
        view.setTranslationX(by);
        for (WeakReference<View> had : moved) {
            if (had.get() == view) return;
        }
        moved.add(new WeakReference<>(view));
    }

    /** Puts every moved view back where Instagram laid it out and forgets the chat and the measured ink. */
    static void restore() {
        for (WeakReference<View> had : moved) {
            View view = had.get();
            if (view != null) view.setTranslationX(0f);
        }
        moved.clear();
        area = new WeakReference<>(null);
        inks.clear();
        inkBitmap = null;
        inkCanvas = null;
        inkPixels = null;
    }

    /**
     * Looks again once a search a frame skipped is due. A chat that opens on a frame too soon after the last
     * search and then sits still draws nothing more, so its name would stay where Instagram put it.
     */
    private static void lookAgainLater(Activity activity, long delay) {
        if (lookAgain != null) return;
        try {
            Runnable later = () -> {
                lookAgain = null;
                if (watched.get() == activity) check(activity);
            };
            lookAgain = later;
            activity.getWindow().getDecorView().postDelayed(later, delay);
        } catch (Throwable failure) {
            lookAgain = null;
            HookStatus.threw(FamilyNames.CENTER_CHAT_NAME, HOOK, failure);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) {
            follow(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (watched.get() == activity) unfollow();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    static void follow(Activity activity) {
        unfollow();
        try {
            watched = new WeakReference<>(activity);
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                check(activity);
                return true;
            };
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(listener);
            drawListener = listener;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CENTER_CHAT_NAME, HOOK, failure);
        }
    }

    static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        Runnable later = lookAgain;
        drawListener = null;
        lookAgain = null;
        watched = new WeakReference<>(null);
        searchedAt = -SEARCH_EVERY_MS;
        try {
            restore();
            if (activity == null) return;
            View decor = activity.getWindow().getDecorView();
            if (later != null) decor.removeCallbacks(later);
            if (listener == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CENTER_CHAT_NAME, HOOK, failure);
        }
    }
}
