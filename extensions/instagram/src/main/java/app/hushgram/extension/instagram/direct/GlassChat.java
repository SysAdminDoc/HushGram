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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
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
 * be sure it's a chat, the chat's root. The buttons are looked for from that root outward, since 453 puts
 * the header beside the root and not in it, and the header is the view that holds both. {@link GlassTopBar}
 * gives it the blurred, tinted background, recording the views its parent draws before it. A chat that
 * lists its messages beside the header and not under it has nothing under the header to blur, so the
 * header shows its tint over the chat's own colour. Each button gets a disc behind it, over whatever
 * background it had, which the disc keeps and gives back.
 *
 * <p>The chat's message bar takes the glass too, as a pill over Instagram's own (#391), blurring the messages
 * that scroll under it. With Glass reply box on stories, the Send message box on each story page the viewer
 * holds takes it as well, blurring the story behind it. Both are found by name the same way, and the boxes
 * are looked for again every {@link #SEARCH_EVERY_MS}, since the viewer adds a page for each person.
 *
 * <p>Off, paused or anything thrown: the glass comes off the header by its own check, the discs come
 * off here and Instagram's header is as it was.
 */
public final class GlassChat {
    static final String HOOK = "chat header glass";
    static final String STORY_HOOK = "story reply box glass";
    static final String COUNT_HEADERS = "chat headers found";
    static final String COUNT_DISCS = "chat buttons given a disc";

    /** The names of the views Instagram's header looks up. */
    static final String BACK = "header_left_button";
    static final String BUTTONS = "header_right_buttons";
    static final String CHAT = "thread_view_root";
    /** The message bar's pill, and the view drawing the messages before the bar. */
    static final String MESSAGE_BOX = "blurred_text_area_background";
    static final String MESSAGES = "direct_thread_content_below_action_bar";
    /** The story viewer, one person's page in it, which draws the story first, and the page's reply box. */
    static final String STORY_VIEWER = "reel_viewer_root";
    static final String STORY_PAGE = "reel_view_group";
    static final String STORY_BOX = "message_composer_container";
    /** How deep into the story viewer its reply boxes are looked for. */
    static final int STORY_DEPTH = 24;

    /**
     * How many parents up from the chat's root the header's buttons are looked for. Instagram 453 puts the
     * header beside the root, three up, under the screen they share.
     */
    static final int REACH = 4;
    /** How long the search for the header waits between tries while there's none, so a feed isn't searched every frame. */
    static final long SEARCH_EVERY_MS = 400L;
    /** The disc's diameter, in dp, at most: a button's touch area can be larger than the circle drawn on it. */
    static final int DISC_DP = 44;
    /**
     * The disc's glass on a dark screen and on a light one (#537, #542), the liquid tab bar's look: a light fill
     * over the header's blur, frosted white on a light screen, and a sheen down from its top.
     */
    static final int FILL_DARK = 0x1fffffff;
    static final int FILL_LIGHT = 0x8cffffff;
    static final int SHEEN_DARK = 0x2effffff;
    static final int SHEEN_LIGHT = 0x73ffffff;
    /**
     * The rim, lit where the light falls at the top left, faint across the sides and lit again, less, at the
     * bottom right, the way the light runs round the edge of a glass bead.
     */
    static final int RIM_LIT_DARK = 0xb3ffffff;
    static final int RIM_FAINT_DARK = 0x14ffffff;
    static final int RIM_BACK_DARK = 0x59ffffff;
    static final int RIM_LIT_LIGHT = 0xffffffff;
    static final int RIM_FAINT_LIGHT = 0x1a000000;
    static final int RIM_BACK_LIGHT = 0x29000000;
    /** The soft shadow under light glass, which has little to stand out against, as the tab bar's has. */
    static final int SHADE_LIGHT = 0x1f000000;

    private static boolean registered;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static WeakReference<View> header = new WeakReference<>(null);
    private static WeakReference<View> messageBox = new WeakReference<>(null);
    private static final List<WeakReference<View>> discs = new ArrayList<>();
    private static long searchedAt = -SEARCH_EVERY_MS;
    private static long boxSearchedAt = -SEARCH_EVERY_MS;
    private static long storiesSearchedAt = -SEARCH_EVERY_MS;
    /** Whether a frame skipped a search that wasn't due yet. */
    private static boolean putOff;
    /** The look again a skipped search waits for, while it's waiting. */
    @Nullable private static Runnable lookAgain;
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

    /** Whether the top bar's glass is wanted now: the round buttons and the message bar keep theirs without it (#670). */
    static boolean topBarOn() {
        return on() && Settings.GLASS_CHAT_TOP_BAR.get();
    }

    /** Whether the glass on the stories' reply box is wanted now. */
    static boolean storiesOn() {
        return Utils.settingsReady() && Settings.GLASS_STORY_REPLY_BOX.get();
    }

    private static int id(Context context, String name) {
        Map<String, Integer> forTests = idsForTests;
        if (forTests != null) {
            Integer id = forTests.get(name);
            return id == null ? 0 : id;
        }
        Integer known = IDS.get(name);
        if (known == null) {
            known = context.getResources().getIdentifier(name, "id", Utils.resourcePackage(context));
            IDS.put(name, known);
        }
        return known;
    }

    /**
     * Looks at the activity's chat header before a frame: gives it the glass, or takes it away. Then the reply
     * boxes on stories. Never throws.
     */
    static void check(Activity activity) {
        checkChat(activity);
        try {
            if (storiesOn()) glazeStories(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, STORY_HOOK, failure);
        }
    }

    private static void checkChat(Activity activity) {
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
            if (now - searchedAt < SEARCH_EVERY_MS) {
                putOff = true;
                return;
            }
            searchedAt = now;
            View found = find(activity);
            if (found == null) {
                restore();
                // Not again before the next search is due, so a screen with no chat isn't searched every frame.
                searchedAt = now;
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
        View left = nearest(root, back);
        View right = nearest(root, buttons);
        if (left == null || right == null) return null;
        View at = left;
        for (int up = 0; up < 5 && at != null; up++) {
            if (at != right && contains(at, right)) return at;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** The view with [id] in [root] or in the nearest of its parents, at most {@link #REACH} up. */
    @Nullable
    private static View nearest(View root, int id) {
        View at = root;
        for (int up = 0; up <= REACH && at != null; up++) {
            View found = at.findViewById(id);
            if (found != null) return found;
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

    /**
     * Gives the reply box on each story page the viewer holds the glass (#391), looking again every
     * {@link #SEARCH_EVERY_MS} for the pages it adds.
     */
    static void glazeStories(Activity activity) {
        long now = SystemClock.uptimeMillis();
        if (now - storiesSearchedAt < SEARCH_EVERY_MS) {
            putOff = true;
            return;
        }
        storiesSearchedAt = now;
        int viewer = id(activity, STORY_VIEWER);
        int page = id(activity, STORY_PAGE);
        int box = id(activity, STORY_BOX);
        if (viewer == 0 || page == 0 || box == 0) return;
        View root = activity.findViewById(viewer);
        if (root == null || !root.isShown()) return;
        List<View> boxes = new ArrayList<>();
        collect(root, box, boxes, 0);
        if (boxes.isEmpty()) return;
        HookStatus.invoked(FamilyNames.GLASS_CHAT);
        for (View found : boxes) GlassTopBar.glazeBox(found, page, true, GlassChat::storiesOn, FamilyNames.GLASS_CHAT);
    }

    private static void collect(View at, int id, List<View> into, int depth) {
        if (at.getId() == id) into.add(at);
        if (!(at instanceof ViewGroup) || depth >= STORY_DEPTH) return;
        ViewGroup group = (ViewGroup) at;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), id, into, depth + 1);
    }

    /** The chat's message bar pill, looked for every {@link #SEARCH_EVERY_MS} while there's none. */
    @Nullable
    private static View messageBox(Activity activity) {
        View known = messageBox.get();
        if (known != null && known.isAttachedToWindow()) return known;
        long now = SystemClock.uptimeMillis();
        if (now - boxSearchedAt < SEARCH_EVERY_MS) {
            putOff = true;
            return null;
        }
        boxSearchedAt = now;
        int id = id(activity, MESSAGE_BOX);
        View found = id == 0 ? null : activity.findViewById(id);
        messageBox = new WeakReference<>(found);
        return found;
    }

    private static void dress(View container, Activity activity) {
        GlassTopBar.glazeChat(container, GlassChat::topBarOn);
        GlassTopBar.glazeBox(messageBox(activity), id(activity, MESSAGES), false, GlassChat::on, FamilyNames.GLASS_CHAT);
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
        messageBox = new WeakReference<>(null);
        searchedAt = -SEARCH_EVERY_MS;
        boxSearchedAt = -SEARCH_EVERY_MS;
    }

    /**
     * The disc: a round piece of glass as wide as the button up to {@link #DISC_DP}, over the header's blur. A
     * light fill, a sheen down from the top and a rim lit at the top left and the bottom right, with a soft
     * shadow under it on a light screen.
     */
    static final class Disc extends Drawable {
        boolean night;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Where the gradients were last made for, so a still disc doesn't make them every frame. */
        private float shapedX = Float.NaN;
        private float shapedY = Float.NaN;
        private float shapedRadius = Float.NaN;
        private boolean shapedNight;

        Disc(boolean night, float density) {
            this.night = night;
            this.density = density;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density));
            shade.setColor(SHADE_LIGHT);
        }

        /** The diameter drawn in [bounds], its shadow included. */
        float diameter(Rect bounds) {
            return Math.min(Math.min(bounds.width(), bounds.height()), DISC_DP * density);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            float radius = diameter(bounds) / 2f;
            if (!night) {
                // The shadow sits a little low and the glass a little in from it, so both stay in the button.
                canvas.drawCircle(x, y + density / 2f, radius - density / 2f, shade);
                radius -= density;
            }
            if (radius <= 0f) return;
            shape(x, y, radius);
            fill.setColor(night ? FILL_DARK : FILL_LIGHT);
            canvas.drawCircle(x, y, radius, fill);
            canvas.drawCircle(x, y, radius, sheen);
            canvas.drawCircle(x, y, radius - rim.getStrokeWidth() / 2f, rim);
        }

        /** Makes the sheen's and the rim's gradients again when the disc moved, changed size or changed shade. */
        private void shape(float x, float y, float radius) {
            if (x == shapedX && y == shapedY && radius == shapedRadius && night == shapedNight) return;
            shapedX = x;
            shapedY = y;
            shapedRadius = radius;
            shapedNight = night;
            sheen.setShader(new LinearGradient(0f, y - radius, 0f, y + radius * 0.2f,
                    night ? SHEEN_DARK : SHEEN_LIGHT, 0x00ffffff, Shader.TileMode.CLAMP));
            float corner = radius * 0.7071f;
            int[] colors = night
                    ? new int[] {RIM_LIT_DARK, RIM_FAINT_DARK, RIM_FAINT_DARK, RIM_BACK_DARK}
                    : new int[] {RIM_LIT_LIGHT, RIM_FAINT_LIGHT, RIM_FAINT_LIGHT, RIM_BACK_LIGHT};
            rim.setShader(new LinearGradient(x - corner, y - corner, x + corner, y + corner,
                    colors, new float[] {0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
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
                lookAgainLater(activity);
                return true;
            };
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(listener);
            drawListener = listener;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }

    /**
     * Looks again once a search a frame skipped is due. A chat that opens on a frame too soon after the last
     * search and then sits still draws nothing more, so its header and message bar stayed Instagram's own until
     * something moved (#513). Never throws.
     */
    private static void lookAgainLater(Activity activity) {
        boolean skipped = putOff;
        putOff = false;
        if (!skipped || lookAgain != null) return;
        try {
            Runnable later = () -> {
                lookAgain = null;
                putOff = false;
                if (watched.get() != activity) return;
                check(activity);
                lookAgainLater(activity);
            };
            lookAgain = later;
            activity.getWindow().getDecorView().postDelayed(later, SEARCH_EVERY_MS);
        } catch (Throwable failure) {
            lookAgain = null;
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }

    static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        Runnable later = lookAgain;
        drawListener = null;
        lookAgain = null;
        putOff = false;
        watched = new WeakReference<>(null);
        storiesSearchedAt = -SEARCH_EVERY_MS;
        try {
            restore();
            if (activity == null) return;
            View decor = activity.getWindow().getDecorView();
            if (later != null) decor.removeCallbacks(later);
            if (listener == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }
}
