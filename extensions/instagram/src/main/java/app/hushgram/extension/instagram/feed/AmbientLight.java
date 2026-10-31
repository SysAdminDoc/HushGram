/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

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
 * <p>Every {@link AmbientSpeed#sampleMillis} each light on screen takes a small copy of its picture
 * (a video's own frame, or the window's pixels for a photo). The colors along its edges are carried
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

    /** What a light shines around. */
    enum Kind { POST, REEL, STORY }

    /** How many pictures glow at once. A feed shows two or three, Reels one and its neighbours. */
    static final int MAX_LIGHTS = 6;

    /** How many parents of a picture are asked whether they're its stage. */
    static final int STAGE_DEPTH = 16;

    /** The copy of a picture is SAMPLE pixels square; the glow's face adds RING cells around it. */
    static final int SAMPLE = 16, RING = 8, SIDE = SAMPLE + 2 * RING;

    /** The glow's alpha right at the picture's edge, out of 255. It falls to nothing at the ring's outer edge. */
    static final int STRENGTH = 150;

    /** A copy whose channels moved less than this on average leaves the glow as it is. */
    static final int CHANGE = 6;

    /** A reel picture at least this share of its stage both ways fills it, and leaves no room to glow. */
    static final float FILLS = 0.98f;

    /** How often a fading glow is drawn again, in milliseconds. */
    static final long FRAME = 16;

    static final String HOOK = "ambient light";
    static final String COUNT_LIT = "pictures lit";
    static final String COUNT_STORIES = "story pictures lit";
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
    /** Whether each view class asked about is Instagram's media frame. */
    private static final Map<Class<?>, Boolean> frames = new WeakHashMap<>();
    private static final Runnable TICK = AmbientLight::tick;
    private static final Rect scratch = new Rect(), aim = new Rect();
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

    /** A post's stage: the nearest of [media]'s first {@link #STAGE_DEPTH} parents that is bigger than it either way. */
    @Nullable
    static ViewGroup stageOf(View media) {
        ViewParent parent = media.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() > media.getWidth() || group.getHeight() > media.getHeight()) return group;
        }
        return null;
    }

    /** A reel's stage: the nearest view from [video]'s parent up to [frame] that spans at least nine tenths of the frame. */
    @Nullable
    static ViewGroup reelStageOf(View video, View frame) {
        ViewParent parent = video.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() >= frame.getWidth() * 0.9f && group.getHeight() >= frame.getHeight() * 0.9f) return group;
            if (group == frame) return null;
        }
        return null;
    }

    /**
     * A story's stage: the nearest parent of [media] that spans at least nine tenths of the window both
     * ways, so the glow can fill the bands the picture leaves above, below and beside it.
     */
    @Nullable
    static ViewGroup storyStageOf(View media, View window) {
        ViewParent parent = media.getParent();
        for (int i = 0; i < STAGE_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getWidth() >= window.getWidth() * 0.9f && group.getHeight() >= window.getHeight() * 0.9f) return group;
        }
        return null;
    }

    /** The first video, or with [video] false the first photo, that shows under [view]. */
    @Nullable
    static View storyMediaIn(View view, boolean video, int[] budget) {
        if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE) return null;
        if (video ? view instanceof TextureView : view instanceof ImageView && ((ImageView) view).getDrawable() != null) {
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
        if (media != null) light(owner, media, Kind.STORY, frame, storyStageOf(media, window));
    }

    /** Lights [media] on [stage], if it isn't already and there's room. True when it was. */
    static boolean light(Activity owner, View media, Kind kind, @Nullable View anchor, @Nullable ViewGroup stage) {
        if (stage == null || media.getWidth() <= 0 || media.getHeight() <= 0) return false;
        Light old = lights.get(media);
        if (old != null) {
            if (old.stage.get() == stage) return false;
            drop(media, old);
        }
        if (lights.size() >= MAX_LIGHTS) return false;
        Glow glow = glowOn(stage);
        Light light = new Light(owner, media, anchor, stage, kind);
        lights.put(media, light);
        glow.lights.add(light);
        light.place(media, stage);
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
    static Glow glowOn(ViewGroup stage) {
        Glow glow = glows.get(stage);
        if (glow != null && stage.getBackground() == glow) return glow;
        Glow fresh = new Glow(stage, stage.getBackground());
        if (glow != null) {
            fresh.lights.addAll(glow.lights);
            glow.release();
        }
        glows.put(stage, fresh);
        fresh.wear(stage);
        return fresh;
    }

    /** Puts out [media]'s light, and gives its stage its own background back when it was the last. */
    private static void drop(View media, Light light) {
        lights.remove(media);
        ViewGroup stage = light.stage.get();
        Glow glow = stage == null ? null : glows.get(stage);
        if (glow == null) return;
        glow.lights.remove(light);
        if (!glow.lights.isEmpty()) {
            glow.invalidateSelf();
            return;
        }
        glows.remove(stage);
        glow.takeOff(stage);
    }

    /** Whether [view] sits somewhere under [ancestor]. */
    private static boolean under(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    /**
     * Puts out lights whose picture left the window or its stage, or a reel's while Blur the bars
     * around Reels has it, and moves each glow with its picture.
     */
    static void follow() {
        boolean blurred = reelsBlurred();
        boolean feed = feedWanted(), stories = storiesWanted();
        for (Map.Entry<View, Light> entry : new ArrayList<>(lights.entrySet())) {
            View media = entry.getKey();
            Light light = entry.getValue();
            if (media == null) continue;
            ViewGroup stage = light.stage.get();
            boolean story = light.kind == Kind.STORY;
            if (stage == null || !media.isAttachedToWindow() || !stage.isAttachedToWindow() || !under(media, stage)
                    || (story ? !stories || !media.isShown() : !feed || (light.kind == Kind.REEL && blurred))) {
                drop(media, light);
                continue;
            }
            Glow glow = glowOn(stage);
            if (light.place(media, stage)) glow.invalidateSelf();
        }
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
        if (light.face != null && light.seen != null && close(light.seen, pixels)) return;
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
        for (Map.Entry<View, Glow> entry : new ArrayList<>(glows.entrySet())) {
            if (entry.getKey() != null) entry.getValue().takeOff(entry.getKey());
        }
        glows.clear();
        lights.clear();
    }

    /** Tests give the reel frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), REEL_FRAME, id);
    }

    static void storyIdForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), STORY_FRAME, id);
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
        sampler = SCREEN;
        logged = false;
        blurInBuildForTests = null;
    }

    /** One picture's glow: where it is on its stage, its last copy and the face it fades between. */
    static final class Light {
        final WeakReference<Activity> owner;
        final WeakReference<View> media;
        final WeakReference<ViewGroup> stage;
        final Kind kind;
        /** For a story, the frame its picture sits in, which tells a picture that took another's place. */
        final WeakReference<View> anchor;
        /** Where the picture is on the stage, empty while it fills the stage or doesn't show. */
        final Rect lit = new Rect();
        /** For a story's photo, where the picture is inside its view, which is all the copy takes. */
        final Rect inView = new Rect();
        /** How far the glow reaches past the picture, in pixels. */
        int spread;
        final Bitmap copy = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
        @Nullable int[] seen;
        final int[] before = new int[SIDE * SIDE], shown = new int[SIDE * SIDE], current = new int[SIDE * SIDE];
        @Nullable Bitmap face;
        long fadeStart;
        int fadeMillis;
        boolean fading, asking;
        int taken;
        private final Rect at = new Rect();
        private final Matrix transform = new Matrix();
        private final RectF content = new RectF();

        Light(Activity owner, View media, @Nullable View anchor, ViewGroup stage, Kind kind) {
            this.owner = new WeakReference<>(owner);
            this.media = new WeakReference<>(media);
            this.anchor = new WeakReference<>(anchor);
            this.stage = new WeakReference<>(stage);
            this.kind = kind;
        }

        /**
         * Finds where [media]'s picture is on [stage] now. A picture less than half across the screen,
         * like a carousel's next page, gets no glow. True when it moved, or the spread changed.
         */
        boolean place(View media, ViewGroup stage) {
            at.setEmpty();
            inView.setEmpty();
            boolean story = kind == Kind.STORY;
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
                stage.offsetDescendantRectToMyCoords(media, at);
                if (at.width() >= stage.getWidth() * FILLS && at.height() >= stage.getHeight() * FILLS) at.setEmpty();
            } else {
                // The search above may have left the part that shows.
                at.setEmpty();
            }
            int reach = Math.round(Settings.AMBIENT_LIGHT_SPREAD.get().dp * stage.getResources().getDisplayMetrics().density);
            // A story's bands can be deeper than the glow reaches, so the glow reaches the stage's edge.
            if (story && !at.isEmpty()) {
                reach = Math.max(reach, Math.max(Math.max(at.left, at.top), Math.max(stage.getWidth() - at.right, stage.getHeight() - at.bottom)));
            }
            if (at.equals(lit) && reach == spread) return false;
            lit.set(at);
            spread = reach;
            return true;
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
        final List<Light> lights = new ArrayList<>();
        private final WeakReference<View> stage;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect from = new Rect();
        private final RectF to = new RectF();
        private final float[] xs = new float[4], ys = new float[4];
        private final Runnable again = this::invalidateSelf;
        private static final int[] CELLS = {0, RING, RING + SAMPLE, SIDE};

        Glow(View stage, @Nullable Drawable original) {
            this.stage = new WeakReference<>(stage);
            this.original = original;
            if (original != null) setBounds(original.getBounds());
        }

        /** Sets the glow as [stage]'s background, keeping its padding. */
        void wear(View stage) {
            keepPadding(stage, this);
            if (original != null) original.setCallback(this);
        }

        /** Gives [stage] its original background back when it still wears this glow. */
        void takeOff(View stage) {
            unscheduleSelf(again);
            if (stage.getBackground() != this) return;
            release();
            keepPadding(stage, original);
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
            if (original != null) original.draw(canvas);
            long now = SystemClock.uptimeMillis();
            boolean fading = false;
            for (Light light : lights) {
                if (light.face == null || light.lit.isEmpty()) continue;
                fading |= light.blend(now);
                Rect lit = light.lit;
                int spread = light.spread;
                xs[0] = lit.left - spread;
                xs[1] = lit.left;
                xs[2] = lit.right;
                xs[3] = lit.right + spread;
                ys[0] = lit.top - spread;
                ys[1] = lit.top;
                ys[2] = lit.bottom;
                ys[3] = lit.bottom + spread;
                // Eight pieces around the picture, the face's corners staying square however long its sides.
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 3; column++) {
                        if (row == 1 && column == 1) continue;
                        from.set(CELLS[column], CELLS[row], CELLS[column + 1], CELLS[row + 1]);
                        to.set(xs[column], ys[row], xs[column + 1], ys[row + 1]);
                        canvas.drawBitmap(light.face, from, to, paint);
                    }
                }
            }
            if (fading) {
                unscheduleSelf(again);
                scheduleSelf(again, now + FRAME);
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
            return original != null && original.isProjected();
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

    /** One activity's place in the shared view watcher. */
    private static final class Session implements ViewWatcher.Member {
        private final WeakReference<Activity> activity;
        /** Whether posts glow, as of the last search. */
        private boolean posts;

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
                        frame = ids.resolve(owner.getResources(), owner.getPackageName(), REEL_FRAME, false);
                        // Without the reel frame posts still glow.
                        if (frame == 0) {
                            HookStatus.missingViewId(FamilyNames.AMBIENT_LIGHT, REEL_FRAME);
                        } else {
                            HookStatus.recoveredViewId(FamilyNames.AMBIENT_LIGHT, REEL_FRAME);
                        }
                    }
                    if (stories) {
                        storyFrame = ids.resolve(owner.getResources(), owner.getPackageName(), STORY_FRAME, false);
                        if (storyFrame == 0) {
                            HookStatus.missingViewId(FamilyNames.AMBIENT_LIGHT, STORY_FRAME);
                        } else {
                            HookStatus.recoveredViewId(FamilyNames.AMBIENT_LIGHT, STORY_FRAME);
                        }
                    }
                    posts = feed;
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
            if (storyFrame != 0 && view.getId() == storyFrame) {
                lightStory(owner, view, budget);
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
