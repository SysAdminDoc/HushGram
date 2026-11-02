/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.feed.GlassTopBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Glass in chats (#449): a chat's header gets the glass of the See-through top bar, and its buttons, back,
 * call, video and the rest, each sit on a round glass disc.
 *
 * <p>No part of Instagram's code changes. Once an activity is in front, this looks before each frame for
 * the chat's header by the ids Instagram's own header code looks its views up by, found by name so no
 * obfuscated name is written here: the header's back button, the row of buttons on its right and, to
 * be sure it's a chat, the chat's root. The header is the view that holds both. {@link GlassTopBar}
 * gives it the blurred, tinted background, recording the views its parent draws before it. A chat that
 * lists its messages beside the header and not under it has nothing under the header to blur, so the
 * header shows its tint over the chat's own colour. Each button gets a disc behind it, over whatever
 * background it had, which the disc keeps and gives back.
 *
 * <p>Off, paused or anything thrown: the glass comes off the header by its own check, the discs come
 * off here and Instagram's header is as it was.
 */
public final class GlassChat {
    static final String HOOK = "chat header glass";
    static final String COUNT_HEADERS = "chat headers found";
    static final String COUNT_DISCS = "chat buttons given a disc";

    /** The names of the views Instagram's header looks up. */
    static final String BACK = "header_left_button";
    static final String BUTTONS = "header_right_buttons";
    static final String CHAT = "thread_view_root";

    /** How long the search for the header waits between tries while there's none, so a feed isn't searched every frame. */
    static final long SEARCH_EVERY_MS = 400L;
    /** The disc's diameter, in dp, at most: a button's touch area can be larger than the circle drawn on it. */
    static final int DISC_DP = 44;
    /** The disc's fill and its ring on a dark screen and on a light one. */
    static final int FILL_DARK = 0x26ffffff;
    static final int RING_DARK = 0x33ffffff;
    static final int FILL_LIGHT = 0x14000000;
    static final int RING_LIGHT = 0x24000000;

    private static boolean registered;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static WeakReference<View> header = new WeakReference<>(null);
    private static final List<WeakReference<View>> discs = new ArrayList<>();
    private static long searchedAt = -SEARCH_EVERY_MS;
    private static boolean counted;

    /** Ids by name, misses included: they don't change while the process runs. */
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    /** Ids a test hands in, in place of the resources it hasn't got. */
    static volatile Map<String, Integer> idsForTests;

    private GlassChat() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.glassChat()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, "start", failure);
        }
    }

    /** Whether the glass is wanted now. */
    static boolean on() {
        return Utils.settingsReady() && Settings.GLASS_CHAT.get();
    }

    private static int id(Context context, String name) {
        Map<String, Integer> forTests = idsForTests;
        if (forTests != null) {
            Integer id = forTests.get(name);
            return id == null ? 0 : id;
        }
        Integer known = IDS.get(name);
        if (known == null) {
            known = context.getResources().getIdentifier(name, "id", context.getPackageName());
            IDS.put(name, known);
        }
        return known;
    }

    /** Looks at the activity's chat header before a frame: gives it the glass, or takes it away. Never throws. */
    static void check(Activity activity) {
        try {
            if (!on()) {
                restore();
                return;
            }
            View known = header.get();
            if (known != null && known.isAttachedToWindow()) {
                dress(known, activity);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - searchedAt < SEARCH_EVERY_MS) return;
            searchedAt = now;
            View found = find(activity);
            if (found == null) {
                restore();
                return;
            }
            header = new WeakReference<>(found);
            HookStatus.invoked(FamilyNames.GLASS_CHAT);
            if (!counted) {
                counted = true;
                HookStatus.counted(FamilyNames.GLASS_CHAT, COUNT_HEADERS);
            }
            dress(found, activity);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
            try {
                restore();
            } catch (Throwable ignored) {
                // Already reporting the first failure.
            }
        }
    }

    /** The header of the chat the activity shows, or null when it shows none. */
    @Nullable
    static View find(Activity activity) {
        int chat = id(activity, CHAT);
        int back = id(activity, BACK);
        int buttons = id(activity, BUTTONS);
        if (chat == 0 || back == 0 || buttons == 0) return null;
        View root = activity.findViewById(chat);
        if (root == null || !root.isShown()) return null;
        View left = root.findViewById(back);
        View right = root.findViewById(buttons);
        if (left == null || right == null) return null;
        View at = left;
        for (int up = 0; up < 5 && at != null; up++) {
            if (at != right && contains(at, right)) return at;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean contains(View ancestor, View view) {
        for (ViewParent at = view.getParent(); at != null; at = at.getParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    private static void dress(View container, Activity activity) {
        GlassTopBar.glazeChat(container, GlassChat::on);
        boolean night = (container.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        float density = container.getResources().getDisplayMetrics().density;
        View left = container.findViewById(id(activity, BACK));
        if (left != null) disc(left, night, density);
        View right = container.findViewById(id(activity, BUTTONS));
        if (right instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) right;
            for (int i = 0; i < row.getChildCount(); i++) disc(row.getChildAt(i), night, density);
        }
    }

    private static void disc(View button, boolean night, float density) {
        Drawable now = button.getBackground();
        if (now instanceof Rim) {
            Rim rim = (Rim) now;
            if (rim.disc.night == night) return;
            rim.disc.night = night;
            rim.invalidateSelf();
            return;
        }
        Disc disc = new Disc(night, density);
        Rim rim = new Rim(disc, now);
        button.setBackground(rim);
        discs.add(new WeakReference<>(button));
        HookStatus.counted(FamilyNames.GLASS_CHAT, COUNT_DISCS);
    }

    /** Takes every disc off and gives back the backgrounds under them. */
    static void restore() {
        for (WeakReference<View> reference : discs) {
            View button = reference.get();
            if (button == null) continue;
            Drawable now = button.getBackground();
            if (now instanceof Rim) button.setBackground(((Rim) now).original);
        }
        discs.clear();
        header = new WeakReference<>(null);
        searchedAt = -SEARCH_EVERY_MS;
    }

    /** The disc: a round, faintly lit fill with a ring, as wide as the button up to {@link #DISC_DP}. */
    static final class Disc extends Drawable {
        boolean night;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Disc(boolean night, float density) {
            this.night = night;
            this.density = density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1f, density / 2f));
        }

        /** The diameter drawn in [bounds]. */
        float diameter(Rect bounds) {
            return Math.min(Math.min(bounds.width(), bounds.height()), DISC_DP * density);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float radius = diameter(bounds) / 2f;
            fill.setColor(night ? FILL_DARK : FILL_LIGHT);
            ring.setColor(night ? RING_DARK : RING_LIGHT);
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, fill);
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius - ring.getStrokeWidth() / 2f, ring);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A button's background while it has a disc: the disc under the background it had, kept as it was. */
    static final class Rim extends LayerDrawable {
        final Disc disc;
        @Nullable final Drawable original;

        Rim(Disc disc, @Nullable Drawable original) {
            super(original == null ? new Drawable[] {disc} : new Drawable[] {disc, original});
            this.disc = disc;
            this.original = original;
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
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }

    static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        drawListener = null;
        watched = new WeakReference<>(null);
        try {
            restore();
            if (activity == null || listener == null) return;
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }
}
