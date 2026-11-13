/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.reels.ReelBlurBars;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Ambient light patch (#416): a soft glow in the colors of a post or a reel around it.
 *
 * <p>A post's picture is Instagram's media frame ({@link #MEDIA_FRAME}, found by class name, since its
 * photo and video views take turns inside it), and a reel's is the video inside the reel's frame
 * ({@link #REEL_FRAME}). Each one found becomes a light on a stage: for a post, the nearest parent
 * bigger than the picture, which in the feed is the list of posts, so the glow shows behind the name
 * above and the buttons below; for a reel, the view around the video that spans its frame, so the
 * glow fills the bands a reel that isn't tall enough leaves above and below it. The stage's
 * background is wrapped in a {@link Glow} that draws the original first and then each light's glow
 * around it, and is put back the moment the stage has no lights left.
 *
 * <p>A story's stage stops at the status bar and the gesture bar, so the black under them stayed
 * black (#487). A story light also lights the nearest view round its stage that spans the window
 * ({@link #bandsHostOf}): that view's glow is the same one, reaching to the window's edges, drawn
 * everywhere but over the stage, which draws its own part. A picture that fills its stage glows
 * only there. A reel on the Reels tab lights its bands the same way, and Instagram's tab bar under
 * it, which covers that view's glow, draws the same glow over its own colour: in its background, or
 * round a docked glass pill through what the pill paints there ({@link #drawUnderPill}). A floating
 * pill has the host's glow round it already.
 *
 * <p>Two views between a story's picture and the glow paint over it themselves (#630). The frame sits
 * in Instagram's media layout, which is black, so with Rounded corners on stories the frame's cut
 * corners showed that black against the glow round the card ({@link #backOf}). And the layout that
 * keeps the story viewer below the status bar paints the strip above it, over the host's glow
 * ({@link #coversOf}). Each such view wears a {@link Glow} in its overlay that draws the same glow over
 * its own colour there and nowhere else: in the frame's corners, or in the strip above the view's
 * children, so a story's progress bar and header stay on top. The host leaves that strip to it.
 *
 * <p>Every {@link AmbientSpeed#sampleMillis} each light on screen takes a small copy of its picture
 * (a video's own frame, or the window's pixels for a photo). A story video Instagram still plays on a
 * SurfaceView is copied from that view's own layer, since the window only has a hole where it shows
 * (#492). The colors along its edges are carried
 * out into a ring, blurred and faded with distance, and the glow fades to them over
 * {@link AmbientSpeed#fadeMillis}. A copy that barely changed leaves the glow as it is. While Blur
 * the bars around Reels is on, reels are left to it.
 *
 * <p>A resumed activity joins the shared view watcher while the switch is on: each layout looks
 * through the window for pictures and each frame moves the glow with them. With the switch off,
 * HushGram paused or the settings not read, every stage gets its own background back. Anything thrown
 * leaves the screen as Instagram drew it.
 */
public final class AmbientLight {
    static final String MEDIA_FRAME = "com.instagram.ui.widget.framelayout.MediaFrameLayout";
    static final String REEL_FRAME = ReelCorners.FRAME;
    /** The frame a story's photo or video sits in, the same one Rounded corners on stories finds. */
    static final String STORY_FRAME = StoryCorners.FRAME;
    /** Instagram's tab bar, under the Reels tab's reels. */
    static final String TAB_BAR = "tab_bar";
    /** A chat's root. Its photos and stickers keep to themselves (#510). */
    static final String CHAT = "thread_view_root";
    /** The black bar with the comment box, under a reel opened from the feed. */
    static final String REEL_FOOTER = "clips_footer_mask_container";

    /** What a light shines around. */
    enum Kind { POST, REEL, STORY }

    /** How many pictures glow at once. A feed shows two or three, Reels one and its neighbours. */
    static final int MAX_LIGHTS = 6;

    /** How many parents of a picture are asked whether they're its stage. */
    static final int STAGE_DEPTH = 16;
    /** The methods taking a Canvas that a view draws itself in. */
    private static final List<String> DRAWS = Arrays.asList("draw", "onDraw", "dispatchDraw", "onDrawForeground");

    /** A parent no more than this many pixels bigger than a picture is rounding, with no room for a glow (#580). */
    static final int ROUNDING = 2;

    /** The copy of a picture is SAMPLE pixels square; the glow's face adds RING cells around it. */
    static final int SAMPLE = 16, RING = 8, SIDE = SAMPLE + 2 * RING;

    /** The glow's alpha right at the picture's edge, out of 255. It falls to nothing at the ring's outer edge. */
    static final int STRENGTH = 150;

    /** A copy whose channels moved less than this on average leaves the glow as it is. */
    static final int CHANGE = 6;

    /** How many copies in a row come out the same before a still picture is looked at less often. */
    static final int STEADY = 3;

    /** The longest a still picture goes without a copy, in milliseconds. */
    static final long LONGEST_REST = 4000;

    /** How far in Blend post edges fades a picture's edge, in dp, at most a fifth of the picture. */
    static final int EDGE_DP = 28;

    /** A reel picture at least this share of its stage both ways fills it, and leaves no room to glow. */
    static final float FILLS = 0.98f;

    /** How often a fading glow is drawn again, in milliseconds. */
    static final long FRAME = 16;

    static final String HOOK = "ambient light";
    static final String COUNT_LIT = "pictures lit";
    static final String COUNT_STORIES = "story pictures lit";
    static final String COUNT_SURFACE_STORIES = "story videos read off a SurfaceView";
    static final String NO_COPY = "copies that failed";

    /** Copies [source]'s picture into [into], then answers whether it worked. Tests stand in for the screen. */
    interface Sampler {
        void copy(Activity owner, View source, Rect inWindow, Bitmap into, Consumer<Boolean> done);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    static final Sampler SCREEN = (owner, source, inWindow, into, done) -> {
        if (source instanceof TextureView) {
            done.accept(((TextureView) source).getBitmap(into) != null);
            return;
        }
        try {
            if (source instanceof SurfaceView) {
                // The video's own layer: the window's pixels have only a hole where it shows.
                PixelCopy.request((SurfaceView) source, into, result -> done.accept(result == PixelCopy.SUCCESS), main);
                return;
            }
            PixelCopy.request(owner.getWindow(), inWindow, into, result -> done.accept(result == PixelCopy.SUCCESS), main);
        } catch (IllegalArgumentException noSurface) {
            done.accept(false);
        }
    };

    private static Sampler sampler = SCREEN;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** Each picture lit, with its light. Main thread only. */
    private static final Map<View, Light> lights = new WeakHashMap<>();
    /** Each stage wearing a glow, with it. Main thread only. */
    private static final Map<View, Glow> glows = new WeakHashMap<>();
    /** Each tab bar wearing a docked glass pill, with the glow the pill paints round itself. Main thread only. */
    private static final Map<View, Glow> pills = new WeakHashMap<>();
    /** Each view drawing a story's glow over its own colour, with the glow in its overlay (#630). Main thread only. */
    private static final Map<View, Glow> fronts = new WeakHashMap<>();
    /** Each of Instagram's rounded layouts cutting its corners, with the radius it last set, in pixels (#630). Main thread only. */
    private static final Map<View, Integer> cutCorners = new WeakHashMap<>();
    /** Whether each leaf view class asked about draws something of its own. */
    private static final Map<Class<?>, Boolean> drawing = new WeakHashMap<>();
    /** The tab bar last found, so each layout needn't search the window for it. */
    @Nullable private static WeakReference<View> barRef;
    /** Whether a tab bar wears the glass, and whether its pill floats. Swapped in tests. */
    static volatile Predicate<View> wearsGlass = GlassTabBar::wearsGlass;
    static volatile Predicate<View> floatingGlass = GlassTabBar::wearsFloatingGlass;
    /** Whether each view class asked about is Instagram's media frame. */
    private static final Map<Class<?>, Boolean> frames = new WeakHashMap<>();
    private static final Runnable TICK = AmbientLight::tick;
    private static final Rect scratch = new Rect(), aim = new Rect();
    private static final RectF mapped = new RectF();
    private static final int[] where = new int[2];
    private static boolean ticking;
    private static volatile boolean registered;
    private static volatile boolean logged;

    /** Whether Blur the bars around Reels is in the build, when a test says so instead of the build. */
    @Nullable
    static volatile Boolean blurInBuildForTests;

    private AmbientLight() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !SettingsStatus.ambientLight() || !(context instanceof Application)) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, AmbientLight::watch, AmbientLight::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK + " start", failure);
        }
    }

    /** Whether anything glows now: a switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && (Settings.AMBIENT_LIGHT.get() || Settings.AMBIENT_LIGHT_STORIES.get());
    }

    /** Whether posts and reels glow now. */
    static boolean feedWanted() {
        return Utils.settingsReady() && Settings.AMBIENT_LIGHT.get();
    }

    /**
     * Whether a story's glow lights the strips under the system bars in [decor]'s window now, so the
     * black covers there step aside for it (#666). Main thread only.
     */
    public static boolean storyBandsLit(View decor) {
        if (!storiesWanted()) return false;
        for (Light light : lights.values()) {
            if (light.kind != Kind.STORY || light.face == null || light.hostLit.isEmpty()) continue;
            ViewGroup host = light.host();
            if (host != null && host.isShown() && host.getRootView() == decor) return true;
        }
        return false;
    }

    /** Whether Blend post edges is on now. */
    static boolean blendWanted() {
        return Utils.settingsReady() && Settings.AMBIENT_LIGHT_BLEND.get();
    }

    /** Whether stories glow now. */
    static boolean storiesWanted() {
        return Utils.settingsReady() && Settings.AMBIENT_LIGHT_STORIES.get();
    }

    /** Whether Blur the bars around Reels has the reels, which are then left to it. */
    static boolean reelsBlurred() {
        Boolean forced = blurInBuildForTests;
        return (forced != null ? forced : SettingsStatus.reelBlurBars()) && Settings.BLUR_REEL_BARS.get();
    }

    /** Starts watching [activity] if the switch is on, or puts every stage's background back if it's off. */
    static void watch(Activity activity) {
        try {
            if (!wanted()) {
                putBack();
                return;
            }
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session(activity);
                    sessions.put(activity, session);
                }
            }
            session.attach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK, failure);
        }
    }

    /** The switch was flipped in HushGram settings, over [activity], which stays resumed. Posted, so it reads the saved value. */
    public static void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> watch(activity));
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) session.detach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK, failure);
        }
    }

    /** Whether [view] is Instagram's media frame, or a kind of it. */
    static boolean mediaFrame(View view) {
        Class<?> kind = view.getClass();
        Boolean known = frames.get(kind);
        if (known != null) return known;
        boolean frame = false;
        for (Class<?> each = kind; each != null && each != View.class && !frame; each = each.getSuperclass()) {
            frame = MEDIA_FRAME.equals(each.getName());
        }
        frames.put(kind, frame);
        return frame;
    }

    /**
     * A post's stage: the nearest of [media]'s first {@link #STAGE_DEPTH} parents that is bigger than
     * it either way by more than {@link #ROUNDING}. Instagram's zoom container comes out a pixel
     * taller than the picture on many posts, and a glow on that has nowhere to show.
     */
    @Nullable
    static ViewGroup stageOf(View media) {
        ViewParent parent = media.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() > media.getWidth() + ROUNDING || group.getHeight() > media.getHeight() + ROUNDING) return group;
        }
        return null;
    }

    /**
     * A reel's stage: the nearest view from [video]'s parent up to [frame] that spans at least nine
     * tenths of the frame. Rounded corners on Reels clips the frame to the video, and a glow inside
     * the frame with it, so a parent of the frame's own size takes the stage when there is one (#580).
     */
    @Nullable
    static ViewGroup reelStageOf(View video, View frame) {
        ViewParent parent = video.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() >= frame.getWidth() * 0.9f && group.getHeight() >= frame.getHeight() * 0.9f) {
                return outsideFrame(group, frame);
            }
            if (group == frame) return null;
        }
        return null;
    }

    /** [frame]'s parent when it's no bigger than the frame, else [stage]. */
    private static ViewGroup outsideFrame(ViewGroup stage, View frame) {
        ViewParent above = frame.getParent();
        if (!(above instanceof ViewGroup)) return stage;
        ViewGroup parent = (ViewGroup) above;
        boolean sameSize = parent.getWidth() <= frame.getWidth() + ROUNDING && parent.getHeight() <= frame.getHeight() + ROUNDING;
        return sameSize ? parent : stage;
    }

    /**
     * A story's stage: the nearest parent of [media] that spans at least nine tenths of the window both
     * ways, or the width and nearly all the height between the system bars, so the glow can fill the
     * bands the picture leaves above, below and beside it. A tall status bar leaves Instagram's viewer
     * under nine tenths of the window, and a stage above it had no bands host or corners to light (#630).
     */
    @Nullable
    static ViewGroup storyStageOf(View media, View window) {
        WindowInsets insets = window.getRootWindowInsets();
        int bars = insets == null ? 0 : insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
        float between = (window.getHeight() - bars) * FILLS;
        ViewParent parent = media.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() >= window.getWidth() * 0.9f
                    && (group.getHeight() >= window.getHeight() * 0.9f || bars > 0 && group.getHeight() >= between)) {
                return group;
            }
        }
        return null;
    }

    /**
     * The view whose background lights the bands under the system bars round a story's [stage]
     * (#487): the nearest of its parents, up to the activity's content view, that spans the window
     * from side to side and from top to bottom. On Instagram that is the container the story viewer's
     * own container sits in, between the status bar and the gesture bar. Null when the stage spans the
     * window itself or no parent does. The window's own views above the content (an action bar, the
     * frame that keeps content off the system bars) are left alone: they aren't Instagram's, and a
     * window that keeps its content off the bars paints them itself.
     */
    @Nullable
    static ViewGroup bandsHostOf(ViewGroup stage) {
        View root = stage.getRootView();
        int width = root.getWidth(), height = root.getHeight();
        int[] at = new int[2];
        stage.getLocationInWindow(at);
        if (at[1] <= 0 && at[1] + stage.getHeight() >= height) return null;
        ViewParent parent = stage.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup && parent != root; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            group.getLocationInWindow(at);
            if (at[0] <= 0 && at[0] + group.getWidth() >= width && at[1] <= 0 && at[1] + group.getHeight() >= height) return group;
            if (group.getId() == android.R.id.content) break;
        }
        return null;
    }

    /**
     * The view a story's [frame] sits in below its [stage], or null when the frame sits on the stage
     * itself. On Instagram that is the media layout, whose black shows in the frame's corners once
     * they're cut round (#630).
     */
    @Nullable
    static ViewGroup backOf(@Nullable View frame, ViewGroup stage) {
        ViewParent parent = frame == null ? null : frame.getParent();
        if (!(parent instanceof ViewGroup) || parent == stage) return null;
        ViewGroup group = (ViewGroup) parent;
        return under(group, stage) ? group : null;
    }

    /**
     * The views from a story's [stage] up to its [host] that leave a strip above their children and
     * can paint it themselves, over the host's glow (#630). On Instagram the layout round the story
     * viewer holds it below the status bar and paints the status bar's strip.
     */
    static List<ViewGroup> coversOf(ViewGroup stage, ViewGroup host) {
        List<ViewGroup> found = new ArrayList<>(1);
        ViewParent parent = stage.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup && parent != host; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (stripUnder(group) > 0) found.add(group);
        }
        return found;
    }

    /**
     * How deep the strip at the top of [group] is that none of its children shows in, in its own
     * pixels: where the highest child is drawn, moved and scaled. 0 with no child showing. A child that
     * draws nothing doesn't count: Instagram puts a bare one pixel view above the story viewer, and the
     * strip that left there showed the black behind it as a line above the story (#630).
     */
    static int stripUnder(ViewGroup group) {
        float highest = Float.MAX_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0 || drawsNothing(child)) continue;
            float scale = child.getScaleY();
            float top = child.getTop() + child.getTranslationY() + child.getPivotY() * (1 - scale);
            highest = Math.min(highest, Math.min(top, top + child.getHeight() * scale));
        }
        if (highest == Float.MAX_VALUE) return 0;
        return Math.max(0, Math.min(group.getHeight(), (int) Math.floor(highest) - group.getScrollY()));
    }

    /** Whether [view] shows nothing: no children, background or foreground, and a class that draws nothing itself. */
    static boolean drawsNothing(View view) {
        if (view instanceof ViewGroup || view.getBackground() != null || view.getForeground() != null) return false;
        Class<?> type = view.getClass();
        Boolean draws = drawing.get(type);
        if (draws == null) {
            draws = drawsItself(type);
            drawing.put(type, draws);
        }
        return !draws;
    }

    /** Whether [type], or a class between it and View, draws on the canvas itself. */
    private static boolean drawsItself(Class<?> type) {
        try {
            for (Class<?> at = type; at != null && at != View.class; at = at.getSuperclass()) {
                for (java.lang.reflect.Method method : at.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 1 && parameters[0] == Canvas.class && DRAWS.contains(method.getName())) return true;
                }
            }
            return false;
        } catch (Throwable unreadable) {
            // A class whose methods the runtime can't list may draw.
            return true;
        }
    }

    /**
     * The first video, or with [video] false the first photo, that shows under [view]. A video is a
     * TextureView, or a SurfaceView when Instagram chose one before a switch could keep it off (the
     * player was made before the settings were read, or before the switch went on).
     */
    @Nullable
    static View storyMediaIn(View view, boolean video, int[] budget) {
        if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE) return null;
        if (video ? view instanceof TextureView || view instanceof SurfaceView
                : view instanceof ImageView && ((ImageView) view).getDrawable() != null) {
            return view.getWidth() > 0 && view.getHeight() > 0 ? view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = storyMediaIn(group.getChildAt(i), video, budget);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Lights the picture in a story's [frame]: its video if it has one showing, else its photo. A
     * picture that took its place (the next story, a photo giving way to a video) puts the old
     * one's light out.
     */
    static void lightStory(Activity owner, View frame, int[] budget) {
        View window = owner.getWindow().getDecorView();
        View media = storyMediaIn(frame, true, budget);
        if (media == null) media = storyMediaIn(frame, false, budget);
        for (Map.Entry<View, Light> entry : new ArrayList<>(lights.entrySet())) {
            Light old = entry.getValue();
            if (old.kind == Kind.STORY && old.anchor.get() == frame && entry.getKey() != media) drop(entry.getKey(), old);
        }
        if (media != null && light(owner, media, Kind.STORY, frame, storyStageOf(media, window))
                && media instanceof SurfaceView) {
            HookStatus.counted(FamilyNames.AMBIENT_LIGHT, COUNT_SURFACE_STORIES);
        }
    }

    /** Lights [media] on [stage], if it isn't already and there's room. True when it was. */
    static boolean light(Activity owner, View media, Kind kind, @Nullable View anchor, @Nullable ViewGroup stage) {
        if (stage == null || media.getWidth() <= 0 || media.getHeight() <= 0) return false;
        Light old = lights.get(media);
        if (old != null) {
            if (old.stage.get() == stage) {
                // Each layout asks again, as the tab bar can come and go under a reel that stays.
                if (old.kind == Kind.REEL) {
                    old.keepBar(barUnder(owner, old.host()));
                    old.keepFooter(footerOn(owner, old.host()));
                } else if (old.kind == Kind.STORY) {
                    keepFronts(old, anchor, stage, old.host());
                }
                return false;
            }
            drop(media, old);
        }
        if (lights.size() >= MAX_LIGHTS) return false;
        Glow glow = glowOn(stage);
        ViewGroup host = kind == Kind.POST ? null : bandsHostOf(stage);
        Light light = new Light(owner, media, anchor, stage, host, kind);
        if (kind == Kind.REEL) {
            light.keepBar(barUnder(owner, host));
            light.keepFooter(footerOn(owner, host));
        } else if (kind == Kind.STORY) {
            keepFronts(light, anchor, stage, host);
        }
        lights.put(media, light);
        glow.lights.add(light);
        if (host != null) glowOn(host).lights.add(light);
        light.place(media, stage);
        dressBar(light, host);
        dressFronts(light);
        HookStatus.counted(FamilyNames.AMBIENT_LIGHT, kind == Kind.STORY ? COUNT_STORIES : COUNT_LIT);
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Ambient light: lit a " + kind.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!ticking) {
            ticking = true;
            main.post(TICK);
        }
        return true;
    }

    /** The glow [stage] wears, wrapping its background now. A background Instagram set since is wrapped afresh. */
    static Glow glowOn(View stage) {
        Glow glow = glows.get(stage);
        if (glow != null && glow.wornBy(stage)) return glow;
        Glow fresh = new Glow(stage, stage.getBackground());
        if (glow != null) {
            fresh.lights.addAll(glow.lights);
            glow.release();
        }
        glows.put(stage, fresh);
        fresh.wear(stage);
        return fresh;
    }

    /**
     * Instagram's tab bar in [owner]'s window while it shows, for a reel whose bands [host] lights, or
     * null without a host, a bar or its id. The bar covers the host's glow under it (#487).
     */
    @Nullable
    static View barUnder(Activity owner, @Nullable ViewGroup host) {
        if (host == null) return null;
        View root = owner.getWindow().getDecorView();
        View bar = barRef == null ? null : barRef.get();
        if (bar == null || !bar.isAttachedToWindow() || bar.getRootView() != root) {
            int id = ids.resolve(owner.getResources(), Utils.resourcePackage(owner), TAB_BAR, false);
            bar = id == 0 ? null : root.findViewById(id);
            barRef = bar == null ? null : new WeakReference<>(bar);
        }
        return bar != null && bar.isShown() ? bar : null;
    }

    /**
     * The black bar with the comment box in [host], while it shows, or null. It sits between a reel
     * opened from the feed and the strip under it, so a glow in that strip looked cut off (#510).
     */
    @Nullable
    static View footerOn(Activity owner, @Nullable ViewGroup host) {
        if (host == null) return null;
        int id = ids.resolve(owner.getResources(), Utils.resourcePackage(owner), REEL_FOOTER, false);
        View footer = id == 0 ? null : host.findViewById(id);
        return footer != null && footer.isShown() && footer.getHeight() > 0 ? footer : null;
    }

    /**
     * Has the tab bar under [light]'s reel carry the host's glow the way the bar draws now: Instagram's
     * own bar in its background, a docked glass pill round itself ({@link #drawUnderPill}), and a
     * floating pill, which has the host's glow showing round it, or no bar not at all.
     */
    static void dressBar(Light light, @Nullable ViewGroup host) {
        View bar = light.bar();
        if (bar == null) return;
        boolean shown = host != null && bar.isAttachedToWindow() && bar.isShown() && !floatingGlass.test(bar);
        boolean glass = shown && wearsGlass.test(bar);
        if (shown && !glass) {
            Glow glow = glowOn(bar);
            if (!glow.lights.contains(light)) glow.lights.add(light);
        } else {
            Glow worn = glows.get(bar);
            if (worn != null && worn.lights.contains(light)) putOut(bar, light);
        }
        if (glass) {
            Glow glow = pillOf(bar);
            if (!glow.lights.contains(light)) {
                glow.lights.add(light);
                bar.invalidate();
            }
        } else {
            dropPill(bar, light);
        }
    }

    /** The glow a docked pill on [bar] paints round itself, made the first time it's asked for. */
    private static Glow pillOf(View bar) {
        Glow glow = pills.get(bar);
        if (glow == null) {
            glow = new Glow(bar, null);
            glow.redraws(bar);
            pills.put(bar, glow);
        }
        return glow;
    }

    /** Takes [light] off the glow round [bar]'s docked pill, and forgets that glow when it was the last. */
    private static void dropPill(@Nullable View bar, Light light) {
        Glow glow = bar == null ? null : pills.get(bar);
        if (glow == null || !glow.lights.remove(light)) return;
        if (glow.lights.isEmpty()) {
            pills.remove(bar);
            glow.unscheduleSelf(glow.again);
        }
        bar.invalidate();
    }

    /** Takes the views that paint over a story [light]'s glow as of this layout: the frame's [anchor]'s back, and covers up to [host]. */
    private static void keepFronts(Light light, @Nullable View anchor, ViewGroup stage, @Nullable ViewGroup host) {
        light.keepBack(backOf(anchor, stage));
        light.keepCovers(host == null ? new ArrayList<>(0) : coversOf(stage, host));
    }

    /**
     * Has each view that paints over [light]'s glow draw it over its own colour where [light] shows
     * through it now, and takes the glow off the views it no longer does (#630).
     */
    static void dressFronts(Light light) {
        if (!fronts.isEmpty()) {
            for (Map.Entry<View, Glow> entry : new ArrayList<>(fronts.entrySet())) {
                View on = entry.getKey();
                if (on != null && entry.getValue().lights.contains(light) && !light.drawsOver(on)) takeFront(on, light);
            }
        }
        ViewGroup back = light.back();
        if (back != null && light.drawsOver(back)) wearFront(back, light);
        for (int i = 0; i < light.covers.size(); i++) {
            ViewGroup cover = light.covers.get(i).get();
            if (cover != null && light.drawsOver(cover)) wearFront(cover, light);
        }
    }

    /** Puts [light] on the glow in [view]'s overlay, laying one there first if it has none, sized to [view]. */
    private static void wearFront(View view, Light light) {
        Glow glow = fronts.get(view);
        if (glow == null) {
            glow = new Glow(view, null);
            glow.front = true;
            fronts.put(view, glow);
        }
        glow.setBounds(0, 0, view.getWidth(), view.getHeight());
        // A view that cleared its overlay let the glow go with the rest.
        if (glow.getCallback() == null) view.getOverlay().add(glow);
        if (!glow.lights.contains(light)) {
            glow.lights.add(light);
            glow.invalidateSelf();
        }
    }

    /** Takes [light] off the glow in [view]'s overlay, and the glow off [view] when it was the last. */
    private static void takeFront(View view, Light light) {
        Glow glow = fronts.get(view);
        if (glow == null || !glow.lights.remove(light)) return;
        if (!glow.lights.isEmpty()) {
            glow.invalidateSelf();
            return;
        }
        fronts.remove(view);
        glow.unscheduleSelf(glow.again);
        view.getOverlay().remove(glow);
    }

    /** Takes [light] off every overlay drawing it. */
    private static void takeFronts(Light light) {
        if (fronts.isEmpty()) return;
        for (Map.Entry<View, Glow> entry : new ArrayList<>(fronts.entrySet())) {
            if (entry.getKey() != null) takeFront(entry.getKey(), light);
        }
    }

    /** Has each overlay drawing [light]'s glow draw it again. */
    private static void redrawFronts(Light light) {
        if (fronts.isEmpty()) return;
        for (Glow glow : fronts.values()) {
            if (glow.lights.contains(light)) glow.invalidateSelf();
        }
    }

    /**
     * Paints the ambient light of the reel above [bar] over the bar's own colour round its docked glass
     * pill, in [canvas]'s coordinates, which are the bar's (#487). Paints nothing with no reel lit
     * there. Never throws.
     */
    public static void drawUnderPill(Canvas canvas, View bar) {
        try {
            Glow glow = pills.get(bar);
            if (glow != null) glow.draw(canvas);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK + " under the pill", failure);
        }
    }

    /**
     * Called first in Instagram's RoundedCornerFrameLayout.setCornerRadius with the layout and the radius
     * it now cuts its corners at. The layout paints those corners solid over its children, black on the
     * story viewer, so a story's glow covers them as it does a frame's cut ones (#630). Never throws.
     */
    public static void cornerRadius(View layout, int radius) {
        try {
            if (radius > 0) cutCorners.put(layout, radius);
            else cutCorners.remove(layout);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK + " corner radius", failure);
        }
    }

    /** The radius [view] paints its own corners at as one of Instagram's rounded layouts, or 0. */
    static int paintedCorner(View view) {
        Integer radius = cutCorners.get(view);
        return radius == null ? 0 : radius;
    }

    /** Puts out [media]'s light, and gives its stage, host and tab bar their own backgrounds back when it was the last. */
    private static void drop(View media, Light light) {
        lights.remove(media);
        light.takeEdges(media);
        putOut(light.stage.get(), light);
        putOut(light.host(), light);
        View bar = light.bar();
        putOut(bar, light);
        dropPill(bar, light);
        takeFronts(light);
    }

    /** Takes [light] off the glow [view] wears, and gives [view] its own background back when it was the last. */
    private static void putOut(@Nullable View view, Light light) {
        Glow glow = view == null ? null : glows.get(view);
        if (glow == null) return;
        glow.lights.remove(light);
        if (!glow.lights.isEmpty()) {
            glow.invalidateSelf();
            return;
        }
        glows.remove(view);
        glow.takeOff(view);
    }

    /** Whether [view] sits somewhere under [ancestor]. */
    private static boolean under(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    /**
     * Moves [rect] from [view]'s coordinates onto [ancestor]'s, through the scale and translation
     * of each view on the way. offsetDescendantRectToMyCoords leaves those out, so a carousel page
     * that Peek at the next carousel page shrinks lit as if it still filled its row, and the glow
     * left the whole row dark around it (#585).
     */
    static void onto(View view, ViewGroup ancestor, Rect rect) {
        mapped.set(rect);
        View at = view;
        while (at != ancestor) {
            Matrix matrix = at.getMatrix();
            if (!matrix.isIdentity()) matrix.mapRect(mapped);
            mapped.offset(at.getLeft(), at.getTop());
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) break;
            at = (View) parent;
            mapped.offset(-at.getScrollX(), -at.getScrollY());
        }
        mapped.round(rect);
    }

    /**
     * Puts out lights whose picture left the window or its stage, or a reel's while Blur the bars
     * around Reels has it, and moves each glow with its picture.
     */
    static void follow() {
        boolean blurred = reelsBlurred();
        boolean feed = feedWanted(), stories = storiesWanted(), blend = blendWanted();
        for (Map.Entry<View, Light> entry : new ArrayList<>(lights.entrySet())) {
            View media = entry.getKey();
            Light light = entry.getValue();
            if (media == null) continue;
            ViewGroup stage = light.stage.get();
            ViewGroup host = light.host();
            boolean story = light.kind == Kind.STORY;
            if (stage == null || !media.isAttachedToWindow() || !stage.isAttachedToWindow() || !under(media, stage)
                    || (light.host != null && (host == null || !host.isAttachedToWindow() || !under(stage, host)))
                    || (story ? !stories || !media.isShown() : !feed || (light.kind == Kind.REEL && blurred))) {
                drop(media, light);
                continue;
            }
            Glow glow = glowOn(stage);
            Glow bands = host == null ? null : glowOn(host);
            dressBar(light, host);
            boolean moved = light.place(media, stage);
            if (story) dressFronts(light);
            if (moved) {
                glow.invalidateSelf();
                if (bands != null) bands.invalidateSelf();
                redrawBar(light);
                redrawFronts(light);
            }
            light.dressEdges(media, stage, blend);
        }
    }

    /** Has the tab bar carrying [light]'s glow draw it again. */
    private static void redrawBar(Light light) {
        View bar = light.bar();
        if (bar == null) return;
        Glow worn = glows.get(bar);
        if (worn != null && worn.lights.contains(light)) worn.invalidateSelf();
        Glow pill = pills.get(bar);
        if (pill != null && pill.lights.contains(light)) pill.invalidateSelf();
    }

    /** Has each light on screen take a copy of its picture, and comes back after the speed chosen. */
    static void tick() {
        ticking = false;
        try {
            if (!wanted() || lights.isEmpty()) return;
            for (Light light : new ArrayList<>(lights.values())) sample(light);
            ticking = true;
            main.postDelayed(TICK, Settings.AMBIENT_LIGHT_SPEED.get().sampleMillis);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK + " copy", failure);
        }
    }

    private static void sample(Light light) {
        View media = light.media.get();
        Activity owner = light.owner.get();
        if (media == null || owner == null || light.asking || !media.isShown() || !media.getGlobalVisibleRect(scratch)) return;
        media.getLocationInWindow(where);
        boolean moved = where[0] != light.atX || where[1] != light.atY;
        light.atX = where[0];
        light.atY = where[1];
        if (moved) light.stir();
        // Reading a picture back is what costs. While the list moves the copy waits for it to stop,
        // and a still photo rests between copies. A picture with no glow yet still gets its first.
        if (light.face != null && (moved || SystemClock.uptimeMillis() < light.restUntil)) return;
        light.asking = true;
        try {
            if (!light.inView.isEmpty()) {
                // A letterboxed story photo: copy the picture, not the black bands of its view.
                media.getLocationInWindow(where);
                aim.set(light.inView);
                aim.offset(where[0], where[1]);
                if (aim.intersect(scratch)) scratch.set(aim);
            }
            sampler.copy(owner, media, new Rect(scratch), light.copy, copied -> {
                light.asking = false;
                if (lights.get(media) != light) return;
                if (copied) {
                    took(light);
                } else {
                    HookStatus.counted(FamilyNames.AMBIENT_LIGHT, NO_COPY);
                }
            });
        } catch (Throwable failure) {
            light.asking = false;
            HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK + " copy", failure);
        }
    }

    /** Takes [light]'s fresh copy in: when it changed, the glow starts fading to its colors. */
    static void took(Light light) {
        int[] pixels = new int[SAMPLE * SAMPLE];
        light.copy.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        if (light.face != null && light.seen != null && close(light.seen, pixels)) {
            light.rest(SystemClock.uptimeMillis());
            return;
        }
        light.stir();
        light.seen = pixels;
        light.taken++;
        int[] target = face(pixels);
        if (light.face == null) {
            light.face = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
            // The first glow fades in from nothing in its own colors, never from black.
            for (int i = 0; i < target.length; i++) light.current[i] = target[i] & 0x00FFFFFF;
        }
        System.arraycopy(light.current, 0, light.before, 0, light.current.length);
        System.arraycopy(target, 0, light.shown, 0, target.length);
        light.fadeStart = SystemClock.uptimeMillis();
        light.fadeMillis = Settings.AMBIENT_LIGHT_SPEED.get().fadeMillis;
        light.fading = true;
        light.blend(light.fadeStart);
        View stage = light.stage.get();
        Glow glow = stage == null ? null : glows.get(stage);
        if (glow != null) glow.invalidateSelf();
        View host = light.host();
        Glow bands = host == null ? null : glows.get(host);
        if (bands != null) bands.invalidateSelf();
        redrawBar(light);
        redrawFronts(light);
    }

    /** Whether two copies are close enough that the glow stays as it is. */
    static boolean close(int[] seen, int[] fresh) {
        long moved = 0;
        for (int i = 0; i < seen.length; i++) {
            int a = seen[i], b = fresh[i];
            moved += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                    + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                    + Math.abs((a & 0xFF) - (b & 0xFF));
        }
        return moved < (long) CHANGE * 3 * seen.length;
    }

    /**
     * The glow's face from a copy of the picture: SIDE cells square, the copy (less its black edges)
     * in the middle and its edge colors carried out through the ring, blurred, with the alpha
     * falling from {@link #STRENGTH} at the picture to nothing at the ring's outer edge. All
     * transparent when the copy is black.
     */
    static int[] face(int[] pixels) {
        int[] out = new int[SIDE * SIDE];
        Rect kept = ReelBlurBars.trim(pixels, SAMPLE, SAMPLE);
        if (kept == null) return out;
        for (int y = 0; y < SIDE; y++) {
            int from = kept.top + Math.min(SAMPLE - 1, Math.max(0, y - RING)) * kept.height() / SAMPLE;
            for (int x = 0; x < SIDE; x++) {
                int across = kept.left + Math.min(SAMPLE - 1, Math.max(0, x - RING)) * kept.width() / SAMPLE;
                out[y * SIDE + x] = pixels[from * SAMPLE + across];
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            out = ReelBlurBars.boxBlur(out, SIDE, SIDE, 2, true);
            out = ReelBlurBars.boxBlur(out, SIDE, SIDE, 2, false);
        }
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) out[y * SIDE + x] = (alpha(x, y) << 24) | (out[y * SIDE + x] & 0xFFFFFF);
        }
        return out;
    }

    /** The glow's alpha at cell [x], [y] of its face, by how far the cell is from the picture. */
    static int alpha(int x, int y) {
        float across = outside(x), down = outside(y);
        float distance = Math.min(1f, (float) Math.sqrt(across * across + down * down) / RING);
        return Math.round(STRENGTH * (1 - distance) * (1 - distance));
    }

    private static float outside(int cell) {
        float middle = cell + 0.5f;
        return Math.max(0f, Math.max(RING - middle, middle - (RING + SAMPLE)));
    }

    /** Gives every stage its own background back, and forgets the pictures. */
    static void putBack() {
        main.removeCallbacks(TICK);
        ticking = false;
        for (Map.Entry<View, Light> entry : new ArrayList<>(lights.entrySet())) {
            if (entry.getKey() != null) entry.getValue().takeEdges(entry.getKey());
        }
        for (Map.Entry<View, Glow> entry : new ArrayList<>(glows.entrySet())) {
            if (entry.getKey() != null) entry.getValue().takeOff(entry.getKey());
        }
        glows.clear();
        for (Map.Entry<View, Glow> entry : new ArrayList<>(pills.entrySet())) {
            entry.getValue().unscheduleSelf(entry.getValue().again);
            if (entry.getKey() != null) entry.getKey().invalidate();
        }
        pills.clear();
        for (Map.Entry<View, Glow> entry : new ArrayList<>(fronts.entrySet())) {
            entry.getValue().unscheduleSelf(entry.getValue().again);
            if (entry.getKey() != null) entry.getKey().getOverlay().remove(entry.getValue());
        }
        fronts.clear();
        lights.clear();
    }

    /** Tests give the reel frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), REEL_FRAME, id);
    }

    static void storyIdForTests(Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), STORY_FRAME, id);
    }

    static void barIdForTests(Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), TAB_BAR, id);
    }

    static void footerIdForTests(Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), REEL_FOOTER, id);
    }

    static void chatIdForTests(Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), CHAT, id);
    }

    /** What Blend post edges lays over [media], or null with nothing there. */
    @Nullable
    static Drawable edgesForTests(View media) {
        Light light = lights.get(media);
        return light == null ? null : light.edges;
    }

    static void samplerForTests(@Nullable Sampler stand) {
        sampler = stand == null ? SCREEN : stand;
    }

    static int litForTests() {
        return lights.size();
    }

    @Nullable
    static Rect litRectForTests(View media) {
        Light light = lights.get(media);
        return light == null ? null : new Rect(light.lit);
    }

    /** Where [media]'s picture is on the host lighting the bands round its stage, empty without one. */
    @Nullable
    static Rect hostLitForTests(View media) {
        Light light = lights.get(media);
        return light == null ? null : new Rect(light.hostLit);
    }

    /** The face's color at cell [x], [y] as it shows now, or 0 with no face. */
    static int faceForTests(View media, int x, int y) {
        Light light = lights.get(media);
        return light == null || light.face == null ? 0 : light.current[y * SIDE + x];
    }

    /** How many copies of [media]'s picture changed its glow, or -1 when it has no light. */
    static int copiesTakenForTests(View media) {
        Light light = lights.get(media);
        return light == null ? -1 : light.taken;
    }

    public static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.listening();
        }
    }

    public static void resetForTests() {
        ids.clear();
        synchronized (sessions) {
            for (Session session : new ArrayList<>(sessions.values())) session.detach();
            sessions.clear();
        }
        putBack();
        frames.clear();
        cutCorners.clear();
        drawing.clear();
        sampler = SCREEN;
        logged = false;
        blurInBuildForTests = null;
        barRef = null;
        wearsGlass = GlassTabBar::wearsGlass;
        floatingGlass = GlassTabBar::wearsFloatingGlass;
    }

    /** One picture's glow: where it is on its stage, its last copy and the face it fades between. */
    static final class Light {
        final WeakReference<Activity> owner;
        final WeakReference<View> media;
        final WeakReference<ViewGroup> stage;
        final Kind kind;
        /** For a story, the frame its picture sits in, which tells a picture that took another's place. */
        final WeakReference<View> anchor;
        /** For a story or a reel, the view round its stage that lights the bands under the system bars, or null without one. */
        @Nullable final WeakReference<ViewGroup> host;
        /** For a reel with a host, Instagram's tab bar showing over that host, as of the last layout. */
        @Nullable private WeakReference<View> bar;
        /** Where the picture is on the stage, empty while it fills the stage or doesn't show. */
        final Rect lit = new Rect();
        /** Where the picture is on the host, empty without one or while it doesn't show. */
        final Rect hostLit = new Rect();
        /** Where the stage is on the host: its own glow draws there, so the host's leaves it alone. */
        final Rect hostStage = new Rect();
        /** For a story's photo, where the picture is inside its view, which is all the copy takes. */
        final Rect inView = new Rect();
        /** Where the bar over the reel's foot starts on the host, or -1: the host's glow stops there. */
        int hostCut = -1;
        /** How far in a rounded picture's corners are cut, in pixels: the glow goes on under them. */
        int corner;
        /** How far the glow reaches past the picture, in pixels. */
        int spread;
        /** Where the picture was at the last look, how many copies in a row came out the same, and when the next is due. */
        int atX = Integer.MIN_VALUE, atY = Integer.MIN_VALUE, same;
        long restUntil;
        /** What Blend post edges lays over the picture, or null with nothing there. */
        @Nullable Edges edges;
        final Bitmap copy = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
        @Nullable int[] seen;
        final int[] before = new int[SIDE * SIDE], shown = new int[SIDE * SIDE], current = new int[SIDE * SIDE];
        @Nullable Bitmap face;
        long fadeStart;
        int fadeMillis;
        boolean fading, asking;
        int taken;
        private final Rect at = new Rect(), onHost = new Rect(), stageOnHost = new Rect();
        private final int[] footerAt = new int[2], hostAt = new int[2];
        /** For a reel with a host, the black bar with the comment box, as of the last layout. */
        @Nullable private WeakReference<View> footer;
        /** For a story, the view its frame sits in below the stage ({@link #backOf}), as of the last layout. */
        @Nullable private WeakReference<ViewGroup> back;
        /** Where the frame is on that view while its corners are cut, else empty: the view's colour shows in them. */
        final Rect frameOnBack = new Rect();
        /** For a story with a host, the views that paint a strip above it ({@link #coversOf}), as of the last layout. */
        final List<WeakReference<ViewGroup>> covers = new ArrayList<>(1);
        /** Where each cover's strip is on the host, empty while it doesn't show. */
        final List<Rect> strips = new ArrayList<>(1);
        private final Rect fresh = new Rect();
        private final Matrix transform = new Matrix();
        private final RectF content = new RectF();

        Light(Activity owner, View media, @Nullable View anchor, ViewGroup stage, @Nullable ViewGroup host, Kind kind) {
            this.owner = new WeakReference<>(owner);
            this.media = new WeakReference<>(media);
            this.anchor = new WeakReference<>(anchor);
            this.stage = new WeakReference<>(stage);
            this.host = host == null ? null : new WeakReference<>(host);
            this.kind = kind;
        }

        /** The host lighting the bands round the stage, or null without one or once it's gone. */
        @Nullable
        ViewGroup host() {
            return host == null ? null : host.get();
        }

        /** The tab bar carrying this light's glow over the host, or null. */
        @Nullable
        View bar() {
            return bar == null ? null : bar.get();
        }

        /** Takes [found] as the tab bar now, and takes the glow off the one before when it changed. */
        void keepBar(@Nullable View found) {
            View before = bar();
            if (before == found) return;
            if (before != null) {
                putOut(before, this);
                dropPill(before, this);
            }
            bar = found == null ? null : new WeakReference<>(found);
        }

        /** Takes [found] as the bar over the reel's foot now. */
        void keepFooter(@Nullable View found) {
            footer = found == null ? null : new WeakReference<>(found);
        }

        /** The view a story's frame sits in below the stage, or null. */
        @Nullable
        ViewGroup back() {
            return back == null ? null : back.get();
        }

        /** Takes [found] as the view the frame sits in now. */
        void keepBack(@Nullable ViewGroup found) {
            if (back() != found) back = found == null ? null : new WeakReference<>(found);
        }

        /** Takes [found] as the covers now. A strip shows from the next place. */
        void keepCovers(List<ViewGroup> found) {
            boolean same = found.size() == covers.size();
            for (int i = 0; same && i < found.size(); i++) same = covers.get(i).get() == found.get(i);
            if (same) return;
            covers.clear();
            strips.clear();
            for (ViewGroup each : found) {
                covers.add(new WeakReference<>(each));
                strips.add(new Rect());
            }
        }

        /** Which of the covers [view] is, or -1. */
        int coverIndex(View view) {
            for (int i = 0; i < covers.size(); i++) {
                if (covers.get(i).get() == view) return i;
            }
            return -1;
        }

        /** Whether the view the frame sits in draws the glow in the frame's cut corners, which the stage then leaves to it. */
        boolean backDraws() {
            return corner > 0 && !frameOnBack.isEmpty() && back() != null;
        }

        /** Whether this light's glow goes over [view]'s own colour: in the frame's corners, or in a strip above the stage. */
        boolean drawsOver(View view) {
            if (view == back() && backDraws()) return true;
            int index = coverIndex(view);
            return index >= 0 && !strips.get(index).isEmpty() && !hostLit.isEmpty();
        }

        /** Something moved or changed, so the next copy comes at the usual time. */
        void stir() {
            same = 0;
            restUntil = 0;
        }

        /**
         * A copy came out the same as the one before. A photo doesn't change on its own, so after
         * {@link #STEADY} of those each wait doubles, up to {@link #LONGEST_REST}. A video keeps its speed.
         */
        void rest(long now) {
            same++;
            View on = media.get();
            if (same < STEADY || on == null || hasVideo(on)) return;
            long wait = (long) Settings.AMBIENT_LIGHT_SPEED.get().sampleMillis << Math.min(same - STEADY + 1, 8);
            restUntil = now + Math.min(wait, LONGEST_REST);
        }

        private static boolean hasVideo(View view) {
            if (view.getVisibility() != View.VISIBLE) return false;
            if (view instanceof TextureView || view instanceof SurfaceView) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (hasVideo(group.getChildAt(i))) return true;
                }
            }
            return false;
        }

        /** Lays Blend post edges over a post's picture that has a glow beside it, or takes it off. */
        void dressEdges(View media, ViewGroup stage, boolean wanted) {
            if (!wanted || kind != Kind.POST || face == null || lit.isEmpty()) {
                takeEdges(media);
                return;
            }
            if (edges == null) {
                edges = new Edges();
                media.getOverlay().add(edges);
            }
            int depth = Math.round(EDGE_DP * stage.getResources().getDisplayMetrics().density);
            boolean upAndDown = stage.getHeight() > media.getHeight();
            edges.fit(media.getWidth(), media.getHeight(), depth, upAndDown, lit.left > 0, lit.right < stage.getWidth(), stage);
        }

        /** Takes Blend post edges off [media]. */
        void takeEdges(View media) {
            if (edges == null) return;
            media.getOverlay().remove(edges);
            edges = null;
        }

        /**
         * Finds where [media]'s picture is on [stage] now, and on the host round it. A picture less
         * than half across the screen, like a carousel's next page, gets no glow. A picture filling
         * its stage glows on the host only. True when it moved, or the spread changed.
         */
        boolean place(View media, ViewGroup stage) {
            at.setEmpty();
            inView.setEmpty();
            boolean story = kind == Kind.STORY;
            boolean fills = false;
            if (media.isShown() && media.getGlobalVisibleRect(at) && at.width() * 2 >= media.getWidth()) {
                at.setEmpty();
                if (kind != Kind.POST && media instanceof TextureView) {
                    ((TextureView) media).getTransform(transform);
                    content.set(0, 0, media.getWidth(), media.getHeight());
                    transform.mapRect(content);
                    content.round(at);
                } else if (story && media instanceof ImageView && ((ImageView) media).getDrawable() != null
                        && ((ImageView) media).getDrawable().getIntrinsicWidth() > 0
                        && ((ImageView) media).getDrawable().getIntrinsicHeight() > 0) {
                    // A photo letterboxed inside its own view: where the drawable lands, padding included.
                    ImageView photo = (ImageView) media;
                    content.set(0, 0, photo.getDrawable().getIntrinsicWidth(), photo.getDrawable().getIntrinsicHeight());
                    photo.getImageMatrix().mapRect(content);
                    content.offset(photo.getPaddingLeft(), photo.getPaddingTop());
                    content.round(at);
                    at.intersect(0, 0, media.getWidth(), media.getHeight());
                    inView.set(at);
                } else {
                    at.set(0, 0, media.getWidth(), media.getHeight());
                }
                onto(media, stage, at);
                fills =at.width() >= stage.getWidth() * FILLS && at.height() >= stage.getHeight() * FILLS;
            } else {
                // The search above may have left the part that shows.
                at.setEmpty();
            }
            onHost.setEmpty();
            stageOnHost.setEmpty();
            ViewGroup band = host();
            if (band != null && !at.isEmpty() && band.isAttachedToWindow() && under(stage, band)) {
                stageOnHost.set(0, 0, stage.getWidth(), stage.getHeight());
                onto(stage, band, stageOnHost);
                onHost.set(at);
                onto(stage, band, onHost);
            }
            if (fills) at.setEmpty();
            int reach = Math.round(Settings.AMBIENT_LIGHT_SPREAD.get().dp * stage.getResources().getDisplayMetrics().density);
            // A story's bands can be deeper than the glow reaches, so the glow reaches the edge of the
            // host, or of the stage without one. Both draw it the same way, so it runs on unbroken. A
            // reel's reaches the host's edges too, past the tab bar, which draws the host's glow.
            if ((story || kind == Kind.REEL) && !onHost.isEmpty()) {
                reach = Math.max(reach, farthest(onHost, band));
            } else if (story && !at.isEmpty()) {
                reach = Math.max(reach, farthest(at, stage));
            }
            int cut = -1;
            View foot = footer == null ? null : footer.get();
            if (band != null && !onHost.isEmpty() && foot != null && foot.isShown()) {
                foot.getLocationInWindow(footerAt);
                band.getLocationInWindow(hostAt);
                cut = footerAt[1] - hostAt[1];
            }
            int rounded = roundedIn(stage);
            boolean moved = false;
            // The frame's corners show the view it sits in while they're cut, as Rounded corners on stories does,
            // and where that view paints its own.
            fresh.setEmpty();
            ViewGroup behind = back();
            View frame = anchor.get();
            if (behind != null && frame != null && media.isShown() && under(frame, behind)) {
                if (frame.getClipToOutline()) {
                    fresh.set(0, 0, frame.getWidth(), frame.getHeight());
                    onto(frame, behind, fresh);
                }
                // Instagram's media layout paints its own corners solid over the frame, cut round or not (#630).
                int painted = paintedCorner(behind);
                if (painted > 0) {
                    if (fresh.isEmpty()) fresh.set(0, 0, behind.getWidth(), behind.getHeight());
                    rounded = Math.max(rounded, painted);
                }
            }
            if (!fresh.equals(frameOnBack)) {
                frameOnBack.set(fresh);
                moved = true;
            }
            for (int i = 0; i < covers.size(); i++) {
                ViewGroup cover = covers.get(i).get();
                fresh.setEmpty();
                int strip = cover == null || onHost.isEmpty() || !under(cover, band) ? 0 : stripUnder(cover);
                if (strip > 0) {
                    fresh.set(0, 0, cover.getWidth(), strip);
                    onto(cover, band, fresh);
                }
                Rect kept = strips.get(i);
                if (!fresh.equals(kept)) {
                    kept.set(fresh);
                    moved = true;
                }
            }
            if (!moved && at.equals(lit) && onHost.equals(hostLit) && stageOnHost.equals(hostStage) && reach == spread
                    && cut == hostCut && rounded == corner) return false;
            lit.set(at);
            hostLit.set(onHost);
            hostStage.set(stageOnHost);
            hostCut = cut;
            corner = rounded;
            spread = reach;
            return true;
        }

        /** How far in the Rounded corners switch for this kind of picture cuts it, in pixels, or 0 with it off. */
        private int roundedIn(View stage) {
            boolean on = (kind == Kind.POST ? Settings.ROUNDED_POST_CORNERS
                    : kind == Kind.REEL ? Settings.ROUNDED_REEL_CORNERS : Settings.ROUNDED_STORY_CORNERS).get();
            return on ? Math.round(Settings.ROUNDED_CORNER_SIZE.get().dp * stage.getResources().getDisplayMetrics().density) : 0;
        }

        /** The widest gap between [rect] and [view]'s edges, in [view]'s pixels. */
        private static int farthest(Rect rect, View view) {
            return Math.max(Math.max(rect.left, rect.top), Math.max(view.getWidth() - rect.right, view.getHeight() - rect.bottom));
        }

        /** Writes the face as it shows at [now], partway from before to shown. True while it is still fading. */
        boolean blend(long now) {
            if (!fading || face == null) return false;
            float progress = fadeMillis <= 0 ? 1f : Math.min(1f, Math.max(0f, (now - fadeStart) / (float) fadeMillis));
            for (int i = 0; i < current.length; i++) current[i] = mix(before[i], shown[i], progress);
            face.setPixels(current, 0, SIDE, 0, 0, SIDE, SIDE);
            if (progress >= 1f) fading = false;
            return fading;
        }

        private static int mix(int from, int to, float progress) {
            int out = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
                out |= Math.round(a + (b - a) * progress) << shift;
            }
            return out;
        }
    }

    /**
     * A stage's background with the glow of each of its lights drawn over it. Everything a view
     * asks of its background goes to the original, which draws first, so the stage looks and lays
     * out as before under the glow.
     */
    static final class Glow extends Drawable implements Drawable.Callback {
        @Nullable final Drawable original;
        /** What the stage held right after the glow went on: the glow, or the stage's wrapper around it. */
        @Nullable private Drawable worn;
        final List<Light> lights = new ArrayList<>();
        private final WeakReference<View> stage;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect from = new Rect();
        private final RectF to = new RectF();
        private final float[] xs = new float[4], ys = new float[4];
        private final int[] hostAt = new int[2], onAt = new int[2];
        private final Runnable again = this::invalidateSelf;
        /** For a glow no view wears, what has its view draw it again. Held here, as a drawable holds its callback weakly. */
        @Nullable private Drawable.Callback redraw;
        /** Whether this glow sits in its view's overlay, over the view's own colour where a story's glow shows through it (#630). */
        boolean front;
        private final Path cut = new Path();
        private final RectF card = new RectF();
        private static final int[] CELLS = {0, RING, RING + SAMPLE, SIDE};

        Glow(View stage, @Nullable Drawable original) {
            this.stage = new WeakReference<>(stage);
            this.original = original;
            if (original != null) setBounds(original.getBounds());
        }

        /** Makes this glow, which [view] paints without wearing it, ask [view] to draw again. */
        void redraws(View view) {
            WeakReference<View> held = new WeakReference<>(view);
            redraw = new Drawable.Callback() {
                @Override
                public void invalidateDrawable(@NonNull Drawable who) {
                    View on = held.get();
                    if (on != null) on.invalidate();
                }

                @Override
                public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                    main.postAtTime(what, who, when);
                }

                @Override
                public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
                    main.removeCallbacks(what, who);
                }
            };
            setCallback(redraw);
        }

        /**
         * Sets the glow as [stage]'s background, keeping its padding, and notes what the stage
         * holds then. Instagram's rounded frame wraps whatever its background setter is given in a
         * drawable of its own (#485), so that can be the wrapper rather than the glow.
         */
        void wear(View stage) {
            keepPadding(stage, this);
            worn = stage.getBackground();
            if (original != null) original.setCallback(this);
        }

        /**
         * Whether [stage] still holds what it held once the glow went on. Asking for the glow
         * itself fails on a stage that wrapped it, and wrapping it again each frame nested deeper
         * every frame until drawing overflowed its stack.
         */
        boolean wornBy(View stage) {
            return stage.getBackground() == worn;
        }

        /** Gives [stage] its original background back when it still wears this glow. */
        void takeOff(View stage) {
            unscheduleSelf(again);
            if (!wornBy(stage)) return;
            release();
            keepPadding(stage, givenBack());
        }

        /**
         * What [takeOff] hands the stage. A stage that wrapped the glow had wrapped the original
         * the same way, so it gets what it wrapped and wraps that once, as it did before the glow,
         * rather than a wrapper inside a wrapper each time a post lights and goes out.
         */
        @Nullable
        private Drawable givenBack() {
            if (worn != this && worn instanceof DrawableWrapper && ((DrawableWrapper) worn).getDrawable() == this
                    && original != null && original.getClass() == worn.getClass()) {
                return ((DrawableWrapper) original).getDrawable();
            }
            return original;
        }

        /** Lets go of the original, which a new glow or the stage takes over. */
        void release() {
            if (original != null && original.getCallback() == this) original.setCallback(null);
        }

        private static void keepPadding(View stage, @Nullable Drawable background) {
            int left = stage.getPaddingLeft(), top = stage.getPaddingTop();
            int right = stage.getPaddingRight(), bottom = stage.getPaddingBottom();
            stage.setBackground(background);
            if (stage.getPaddingLeft() != left || stage.getPaddingTop() != top
                    || stage.getPaddingRight() != right || stage.getPaddingBottom() != bottom) {
                stage.setPadding(left, top, right, bottom);
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (front) {
                drawFront(canvas);
                return;
            }
            if (original != null) original.draw(canvas);
            View on = stage.get();
            long now = SystemClock.uptimeMillis();
            boolean fading = false;
            for (Light light : lights) {
                // On a host the glow fills the bands round the stage, which draws its own part.
                boolean bands = on != null && light.stage.get() != on;
                Rect lit = bands ? light.hostLit : light.lit;
                if (light.face == null || lit.isEmpty()) continue;
                View host = bands ? light.host() : null;
                if (bands && host == null) continue;
                fading |= light.blend(now);
                int saved = bands ? canvas.save() : 0;
                if (bands && host != on) {
                    // The tab bar over the host draws the host's glow where it covers it.
                    host.getLocationInWindow(hostAt);
                    on.getLocationInWindow(onAt);
                    canvas.translate(hostAt[0] - onAt[0], hostAt[1] - onAt[1]);
                }
                if (bands) canvas.clipOutRect(light.hostStage);
                if (bands && light.hostCut >= 0) canvas.clipOutRect(0, light.hostCut, host.getWidth(), host.getHeight());
                // The views painting a strip above the stage draw it there themselves (#630).
                if (bands) {
                    for (int i = 0; i < light.strips.size(); i++) canvas.clipOutRect(light.strips.get(i));
                }
                // A story's bands glow on black, whatever the theme gave the host (#666).
                if (bands && host == on && light.kind == Kind.STORY) canvas.drawColor(Color.BLACK);
                // A rounded story frame's corners are left to the view it sits in, when that one draws them (#630).
                pieces(canvas, light, lit, light.corner > 0 && (bands || !light.backDraws()));
                if (bands) canvas.restoreToCount(saved);
            }
            if (fading) {
                unscheduleSelf(again);
                scheduleSelf(again, now + FRAME);
            }
        }

        /**
         * Draws the glow in the view's overlay, over its own colour, where each story's glow shows
         * through it (#630): in the frame's cut corners when it's the view the frame sits in, and in the
         * strip above its children when it's a cover. Nowhere else, so what's in it stays on top.
         */
        private void drawFront(Canvas canvas) {
            View on = stage.get();
            if (on == null) return;
            long now = SystemClock.uptimeMillis();
            boolean fading = false;
            for (Light light : lights) {
                if (light.face == null) continue;
                if (on == light.back() && light.backDraws()) {
                    // A picture that fills its stage glows on the host only.
                    boolean bands = light.lit.isEmpty();
                    View source = bands ? light.host() : light.stage.get();
                    Rect lit = bands ? light.hostLit : light.lit;
                    if (source != null && !lit.isEmpty()) {
                        fading |= light.blend(now);
                        int saved = canvas.save();
                        canvas.clipRect(light.frameOnBack);
                        card.set(light.frameOnBack);
                        cut.reset();
                        cut.addRoundRect(card, light.corner, light.corner, Path.Direction.CW);
                        canvas.clipOutPath(cut);
                        moveTo(canvas, source, on);
                        pieces(canvas, light, lit, true);
                        canvas.restoreToCount(saved);
                    }
                }
                int index = light.coverIndex(on);
                ViewGroup host = light.host();
                if (index >= 0 && host != null && !light.hostLit.isEmpty() && !light.strips.get(index).isEmpty()) {
                    fading |= light.blend(now);
                    int saved = canvas.save();
                    moveTo(canvas, host, on);
                    canvas.clipRect(light.strips.get(index));
                    canvas.clipOutRect(light.hostStage);
                    // Over the cover's own colour, which is white in light mode, the glow sits on black (#666).
                    canvas.drawColor(Color.BLACK);
                    pieces(canvas, light, light.hostLit, light.corner > 0);
                    canvas.restoreToCount(saved);
                }
            }
            if (fading) {
                unscheduleSelf(again);
                scheduleSelf(again, now + FRAME);
            }
        }

        /** Moves [canvas] from [on]'s coordinates to [source]'s, by where each is in the window. */
        private void moveTo(Canvas canvas, View source, View on) {
            if (source == on) return;
            source.getLocationInWindow(hostAt);
            on.getLocationInWindow(onAt);
            canvas.translate(hostAt[0] - onAt[0], hostAt[1] - onAt[1]);
        }

        /**
         * The glow round [lit]: eight pieces around it, the face's corners staying square however long
         * its sides, and with [corners] a strip at its top and bottom edge as deep as the picture's
         * rounded corners, which show what's behind it (#510).
         */
        private void pieces(Canvas canvas, Light light, Rect lit, boolean corners) {
            Bitmap face = light.face;
            if (face == null) return;
            int spread = light.spread;
            xs[0] = lit.left - spread;
            xs[1] = lit.left;
            xs[2] = lit.right;
            xs[3] = lit.right + spread;
            ys[0] = lit.top - spread;
            ys[1] = lit.top;
            ys[2] = lit.bottom;
            ys[3] = lit.bottom + spread;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    if (row == 1 && column == 1) continue;
                    from.set(CELLS[column], CELLS[row], CELLS[column + 1], CELLS[row + 1]);
                    to.set(xs[column], ys[row], xs[column + 1], ys[row + 1]);
                    canvas.drawBitmap(face, from, to, paint);
                }
            }
            if (corners) {
                from.set(RING, RING, RING + SAMPLE, RING + 1);
                to.set(lit.left, lit.top, lit.right, lit.top + light.corner);
                canvas.drawBitmap(face, from, to, paint);
                from.set(RING, RING + SAMPLE - 1, RING + SAMPLE, RING + SAMPLE);
                to.set(lit.left, lit.bottom - light.corner, lit.right, lit.bottom);
                canvas.drawBitmap(face, from, to, paint);
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            if (original != null) original.setBounds(bounds);
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return original != null && original.setState(state);
        }

        @Override
        public boolean isStateful() {
            return original != null && original.isStateful();
        }

        @Override
        public void jumpToCurrentState() {
            if (original != null) original.jumpToCurrentState();
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (original != null) original.setVisible(visible, restart);
            return changed;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            if (original != null) original.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            if (original != null) original.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return original != null && original.getOpacity() == PixelFormat.OPAQUE ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }

        @Override
        public boolean getPadding(@NonNull Rect padding) {
            padding.setEmpty();
            return false;
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            if (original != null) {
                original.getOutline(outline);
            } else {
                super.getOutline(outline);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return original == null ? -1 : original.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return original == null ? -1 : original.getIntrinsicHeight();
        }

        @Override
        public int getMinimumWidth() {
            return original == null ? 0 : original.getMinimumWidth();
        }

        @Override
        public int getMinimumHeight() {
            return original == null ? 0 : original.getMinimumHeight();
        }

        @Override
        public void setHotspot(float x, float y) {
            if (original != null) original.setHotspot(x, y);
        }

        @Override
        public void setHotspotBounds(int left, int top, int right, int bottom) {
            if (original != null) original.setHotspotBounds(left, top, right, bottom);
        }

        @Override
        public boolean isProjected() {
            // Drawable.isProjected is API 29; Android 9 has no method to forward to.
            return original != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && original.isProjected();
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }

    /**
     * What Blend post edges lays over a post's picture (#510): at each edge with room past it for the
     * glow, a strip in the page's color, as strong at the edge as what the glow lets through there and
     * gone a little way in. Right at the edge the picture then reads like the glow beside it, so the
     * edge stops showing as a line.
     */
    static final class Edges extends Drawable {
        private final Paint paint = new Paint();
        private final Shader[] shaders = new Shader[4];
        private final Rect[] areas = {new Rect(), new Rect(), new Rect(), new Rect()};
        private int width, height, depth;
        private boolean upAndDown, left, right;

        /** Fits the strips to a picture this big, doing nothing when they already fit. */
        void fit(int width, int height, int depthAtMost, boolean upAndDown, boolean left, boolean right, View stage) {
            int depth = Math.max(1, Math.min(depthAtMost, Math.min(width, height) / 5));
            if (width == this.width && height == this.height && depth == this.depth
                    && upAndDown == this.upAndDown && left == this.left && right == this.right) return;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.upAndDown = upAndDown;
            this.left = left;
            this.right = right;
            setBounds(0, 0, width, height);
            int page = pageColor(stage);
            int strong = ((255 - STRENGTH) << 24) | (page & 0xFFFFFF), clear = page & 0xFFFFFF;
            side(0, upAndDown, 0, 0, width, depth, 0, 0, 0, depth, strong, clear);
            side(1, upAndDown, 0, height - depth, width, height, 0, height, 0, height - depth, strong, clear);
            side(2, left, 0, 0, depth, height, 0, 0, depth, 0, strong, clear);
            side(3, right, width - depth, 0, width, height, width, 0, width - depth, 0, strong, clear);
            invalidateSelf();
        }

        private void side(int which, boolean shown, int l, int t, int r, int b, int x0, int y0, int x1, int y1, int strong, int clear) {
            shaders[which] = shown ? new LinearGradient(x0, y0, x1, y1, strong, clear, Shader.TileMode.CLAMP) : null;
            areas[which].set(l, t, r, b);
        }

        /** The page's color behind [stage]: its own solid background, or the theme's. */
        private static int pageColor(View stage) {
            Glow worn = glows.get(stage);
            Drawable own = worn != null ? worn.original : stage.getBackground();
            if (own instanceof ColorDrawable && Color.alpha(((ColorDrawable) own).getColor()) == 255) {
                return ((ColorDrawable) own).getColor();
            }
            TypedValue value = new TypedValue();
            if (stage.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)
                    && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            boolean night = (stage.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            return night ? Color.BLACK : Color.WHITE;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            for (int i = 0; i < shaders.length; i++) {
                if (shaders[i] == null) continue;
                paint.setShader(shaders[i]);
                canvas.drawRect(areas[i], paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** One activity's place in the shared view watcher. */
    private static final class Session implements ViewWatcher.Member {
        private final WeakReference<Activity> activity;
        /** Whether posts glow, as of the last search. */
        private boolean posts;
        /** Whether stories glow, as of the last search. */
        private boolean stories;
        /** A chat's root, as of the last search: what's under it stays dark. */
        private int chat;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        boolean listening() {
            Activity owner = activity.get();
            return owner != null && ViewWatcher.listening(owner, this);
        }

        void attach() {
            Activity owner = activity.get();
            if (owner == null || ViewWatcher.joined(owner, this)) return;
            HookStatus.invoked(FamilyNames.AMBIENT_LIGHT);
            ViewWatcher.join(owner, this);
        }

        void detach() {
            Activity owner = activity.get();
            if (owner != null) ViewWatcher.leave(owner, this);
        }

        @Override
        public boolean wanted() {
            return AmbientLight.wanted();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            try {
                if (!wanted()) {
                    putBack();
                    detach();
                    return false;
                }
                follow();
                if (laidOut) {
                    boolean feed = feedWanted(), stories = storiesWanted();
                    int frame = 0, storyFrame = 0;
                    if (feed) {
                        frame = ids.resolve(owner.getResources(), Utils.resourcePackage(owner), REEL_FRAME, false);
                        // Without the reel frame posts still glow.
                        if (frame == 0) {
                            HookStatus.missingViewId(FamilyNames.AMBIENT_LIGHT, REEL_FRAME);
                        } else {
                            HookStatus.recoveredViewId(FamilyNames.AMBIENT_LIGHT, REEL_FRAME);
                        }
                    }
                    // Read with either switch: Instagram's story frame is a kind of its post frame, and
                    // posts leave it to the story switch.
                    storyFrame = ids.resolve(owner.getResources(), Utils.resourcePackage(owner), STORY_FRAME, false);
                    if (stories) {
                        if (storyFrame == 0) {
                            HookStatus.missingViewId(FamilyNames.AMBIENT_LIGHT, STORY_FRAME);
                        } else {
                            HookStatus.recoveredViewId(FamilyNames.AMBIENT_LIGHT, STORY_FRAME);
                        }
                    }
                    posts = feed;
                    this.stories = stories;
                    chat = feed ? ids.resolve(owner.getResources(), Utils.resourcePackage(owner), CHAT, false) : 0;
                    walk(owner, owner.getWindow().getDecorView(), frame, storyFrame, feed && !reelsBlurred(),
                            new int[] {ReelCorners.MAX_VIEWS});
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.AMBIENT_LIGHT, HOOK, failure);
            }
            // A glow that moved was invalidated in place, so this frame already draws it.
            return false;
        }

        /** Lights each post's media frame and, with [reels], each reel's video in a frame with [frame], under [view]. */
        private void walk(Activity owner, View view, int frame, int storyFrame, boolean reels, int[] budget) {
            if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE) return;
            if (chat != 0 && view.getId() == chat) return;
            // Before the post frame: a story's frame is one too, and lit as a post it lost its bands and corners (#630).
            if (storyFrame != 0 && view.getId() == storyFrame) {
                if (stories) lightStory(owner, view, budget);
                return;
            }
            if (posts && mediaFrame(view)) {
                light(owner, view, Kind.POST, null, stageOf(view));
                return;
            }
            if (frame != 0 && view.getId() == frame) {
                if (reels) {
                    TextureView video = videoIn(view, budget);
                    if (video != null) light(owner, video, Kind.REEL, null, reelStageOf(video, view));
                }
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(owner, group.getChildAt(i), frame, storyFrame, reels, budget);
            }
        }

        /** The first video under [view] that shows. */
        @Nullable
        private TextureView videoIn(View view, int[] budget) {
            if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE) return null;
            if (view instanceof TextureView) return (TextureView) view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    TextureView video = videoIn(group.getChildAt(i), budget);
                    if (video != null) return video;
                }
            }
            return null;
        }
    }
}
