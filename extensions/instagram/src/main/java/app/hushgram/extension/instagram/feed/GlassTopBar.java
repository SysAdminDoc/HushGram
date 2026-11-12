/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.misc.GlassBlur;
import app.hushgram.extension.instagram.misc.GlassLens;
import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the See-through top bar switch (#418): Home's header shows the feed blurred under it,
 * like frosted glass, with a hairline along its bottom.
 *
 * <p>{@link HomeTopBar#slidingViews} hands the header here as Home sets it up. The header gets a
 * background of its own: a render node holding the views its parent draws before it, the feed, blurred
 * and tinted the way the glass tab bar does it, with the tab bar's blur and opacity sliders. Nothing is
 * copied. The node records those views once more, as the glass tab bar does, so the posts move under the
 * bar as they scroll. Below Android 12 there's no blur, so the header gets a firmer plain tint.
 *
 * <p>A background Instagram puts on the header or on a view in it as wide as the header would cover the
 * glass, so each frame takes it off and keeps it. Everything goes back when the switch is off, HushGram
 * is paused or the header leaves the window. It fails open: anything thrown puts Instagram's header back.
 *
 * <p>On Home the feed is also taken behind the status bar and the glass reaches up over it (#462), see
 * {@link GlassReach}. While the glass tab bar is on, every header, Home's and a chat's, takes the pill's sheen
 * too, and its rim bend along the bottom edge while the pill's bends (#513).
 *
 * <p>A message box, a chat's message bar or the reply box on a story, takes the same glass as a pill (#391),
 * see {@link #glazeBox}, with the tab bar's sheen while that's on and its rim bent all the way round while the
 * pill's bends.
 */
public final class GlassTopBar {
    static final String HOOK = "top bar glass";
    static final String COUNT_GLAZED = "top bar glazed";
    static final String COUNT_BOXES = "message boxes glazed";
    /** The backdrop is recorded at this fraction of its size and blurred there, like the glass tab bar's. */
    static final int DOWNSAMPLE = 4;
    /** The hairline along the bottom of a dark bar, and of a light one. */
    static final int HAIRLINE_DARK = 0x1fffffff;
    static final int HAIRLINE_LIGHT = 0x1f000000;
    /** How deep under the header a view as wide as it is has its background taken off. */
    private static final int CLEAR_DEPTH = 3;
    /** How many parents up from the header the one drawing the feed before it is looked for. */
    static final int HOST_DEPTH = 6;
    /** How many parents up from a message box the view drawing what's under it is looked for. */
    static final int BOX_DEPTH = 8;
    /** How deep into the header a view drawing inside it is looked for, before it's taken that one does. */
    private static final int SHOWN_DEPTH = 6;

    /** Headers already given the glass. Weak, so a header Home lets go of goes with it. Main thread. */
    private static final Set<View> GLAZED = Collections.newSetFromMap(new WeakHashMap<>());

    private GlassTopBar() {
    }

    /** Gives each view in [views] the glass while the switch is on. Never throws. */
    static void glaze(List<?> views) {
        glaze(views, GlassTopBar::switchedOn);
    }

    static void glaze(List<?> views, BooleanSupplier on) {
        try {
            if (views == null || !on.getAsBoolean()) return;
            for (Object view : views) {
                if (view instanceof View) attach((View) view, GlassTopBar::switchedOn, FamilyNames.HOME_TOP_BAR);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
        }
    }

    /**
     * Gives a chat's header the same glass while [on] says so (#449). Never throws. Done once per
     * header: the glass puts itself on and takes itself off by [on], whatever the header does.
     */
    public static void glazeChat(@Nullable View header, BooleanSupplier on) {
        try {
            if (header == null || !on.getAsBoolean()) return;
            attach(header, on, FamilyNames.GLASS_CHAT);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
        }
    }

    /**
     * Gives a message box, [box], the glass as a pill while [on] says so (#391): a chat's message bar or the
     * reply box on a story. The glass records what the view with the id [source] draws before the view holding
     * the box, the messages or the story, and [dark] keeps it dark whatever the theme, as the story viewer is.
     * A box with views in it takes the glass behind them, under the background it had, so an outline it draws
     * stays. A box with none, a picture of the pill, takes the glass over that picture, and the views laid over the
     * whole picture lose their fill while it's on. Never throws. Done once per box.
     */
    public static void glazeBox(@Nullable View box, int source, boolean dark, BooleanSupplier on, String family) {
        try {
            if (box == null || source == 0 || !on.getAsBoolean() || under(box, source) == null || !GLAZED.add(box)) return;
            listen(new Glass(box, on, family, source, dark));
            HookStatus.counted(family, COUNT_BOXES);
        } catch (Throwable failure) {
            HookStatus.threw(family, HOOK, failure);
        }
    }

    private static void attach(View header, BooleanSupplier on, String family) {
        if (!GLAZED.add(header)) return;
        listen(new Glass(header, on, family, 0, false));
        HookStatus.counted(family, COUNT_GLAZED);
    }

    private static void listen(Glass glass) {
        glass.header.addOnAttachStateChangeListener(glass);
        if (glass.header.isAttachedToWindow()) glass.onViewAttachedToWindow(glass.header);
    }

    /** Whether the message box [box] has the glass showing now. */
    public static boolean boxShowing(View box) {
        return box.getBackground() instanceof Frost || box.getForeground() instanceof Frost;
    }

    /** The view holding [box] whose parent has the id [source], at most {@link #BOX_DEPTH} up, or null. */
    @Nullable
    static View under(View box, int source) {
        View at = box;
        for (int depth = 0; depth < BOX_DEPTH; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof ViewGroup)) return null;
            if (((ViewGroup) up).getId() == source) return at;
            at = (View) up;
        }
        return null;
    }

    /**
     * Whether [view], beside [box] in one parent or inside a view that is, is laid out over the whole of it, to
     * a pixel.
     */
    static boolean covers(View view, View box) {
        ViewParent parent = box.getParent();
        int left = 0;
        int top = 0;
        View at = view;
        while (at.getParent() != parent) {
            left += at.getLeft();
            top += at.getTop();
            ViewParent up = at.getParent();
            if (!(up instanceof View)) return false;
            at = (View) up;
            left -= at.getScrollX();
            top -= at.getScrollY();
        }
        left += at.getLeft();
        top += at.getTop();
        return view.getWidth() > 0 && left <= box.getLeft() + 1 && top <= box.getTop() + 1
                && left + view.getWidth() >= box.getRight() - 1 && top + view.getHeight() >= box.getBottom() - 1;
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.SEE_THROUGH_HOME_TOP_BAR.get();
    }

    /** Whether [header] has the glass showing now. */
    public static boolean showing(View header) {
        return header.getBackground() instanceof Frost;
    }

    /** How far [header]'s glass reaches above its top edge and below its bottom one, behind the status bar. */
    static int[] reach(View header) {
        Drawable background = header.getBackground();
        if (!(background instanceof Frost)) return new int[] {0, 0};
        Glass glass = ((Frost) background).glass;
        return new int[] {glass.above, glass.below};
    }

    /** Whether [header] has nothing showing in it, so its glass is only the strip behind the status bar. */
    static boolean hollow(View header) {
        Drawable background = header.getBackground();
        return background instanceof Frost && ((Frost) background).glass.hollow;
    }

    /**
     * Whether [header] has views in it and none would draw inside it: all hidden, faded out or moved out
     * of its bounds. A header with no views in it at all isn't counted, that's nothing to go on.
     */
    static boolean showsNothing(View header) {
        return header instanceof ViewGroup && ((ViewGroup) header).getChildCount() > 0
                && !drawsInside((ViewGroup) header, 0f, 0f, header.getWidth(), header.getHeight(), 0);
    }

    private static boolean drawsInside(ViewGroup group, float x, float y, int wide, int tall, int depth) {
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            float left = x + child.getX() - group.getScrollX();
            float top = y + child.getY() - group.getScrollY();
            if (left >= wide || top >= tall || left + child.getWidth() <= 0 || top + child.getHeight() <= 0) continue;
            if (!(child instanceof ViewGroup) || ((ViewGroup) child).getChildCount() == 0 || depth >= SHOWN_DEPTH) return true;
            if (drawsInside((ViewGroup) child, left, top, wide, tall, depth + 1)) return true;
        }
        return false;
    }

    /** Each view directly in [header], its class, visibility, alpha, top and height, for the debug log. */
    private static String children(View header) {
        StringBuilder found = new StringBuilder();
        if (!(header instanceof ViewGroup)) return "";
        ViewGroup group = (ViewGroup) header;
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            View child = group.getChildAt(i);
            found.append(child.getClass().getSimpleName()).append(' ').append(child.getVisibility())
                    .append(' ').append(child.getAlpha()).append(' ').append(child.getY())
                    .append(' ').append(child.getHeight()).append(", ");
        }
        return found.toString();
    }

    /** Whether [header]'s glass has its backdrop recorded. */
    static boolean recorded(View header) {
        Drawable background = header.getBackground();
        return background instanceof Frost && ((Frost) background).glass.recorded;
    }

    /** How many times [header]'s glass asked for the tab bar's rim bend along its bottom edge (#513). */
    static int bends(View header) {
        Drawable background = header.getBackground();
        return background instanceof Frost ? ((Frost) background).glass.bends : 0;
    }

    /** Whether [header]'s glass drew the tab bar's sheen the last time it drew (#513). */
    static boolean sheened(View header) {
        Drawable background = header.getBackground();
        return background instanceof Frost && ((Frost) background).sheened;
    }

    /**
     * The view under [header]'s parent, [header] or one of the views wrapped round it, whose parent
     * draws the feed before it: the first parent more than twice as tall as [header]. Null when there's
     * none yet.
     */
    @Nullable
    static View host(View header) {
        View at = header;
        int tall = Math.max(1, header.getHeight());
        for (int depth = 0; depth < HOST_DEPTH; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof ViewGroup)) return null;
            if (((ViewGroup) up).getHeight() > 2 * tall) return at;
            at = (View) up;
        }
        return null;
    }

    /** Whether [background] fills its view with colour, so the glass under it wouldn't show. */
    static boolean fills(@Nullable Drawable background) {
        if (background == null || background instanceof Frost) return false;
        if (background instanceof ColorDrawable) return Color.alpha(((ColorDrawable) background).getColor()) != 0;
        return background.getOpacity() == PixelFormat.OPAQUE;
    }

    /** The colour the glass is tinted from: Instagram's own fill when it had a solid one, else the theme's. */
    static int base(@Nullable Drawable original, Iterable<Drawable> cleared, boolean night) {
        int found = solid(original);
        for (Drawable background : cleared) {
            if (found != 0) break;
            found = solid(background);
        }
        if (found != 0) return found;
        return night ? Color.BLACK : Color.WHITE;
    }

    private static int solid(@Nullable Drawable background) {
        if (!(background instanceof ColorDrawable)) return 0;
        int colour = ((ColorDrawable) background).getColor();
        return Color.alpha(colour) == 255 ? colour : 0;
    }

    /** One header's glass: puts it on before each frame, records what's under it, takes it off again. */
    private static final class Glass implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final View header;
        final Frost frost;
        /** Whether the glass is wanted now, and the family the report counts it under. */
        private final BooleanSupplier enabled;
        private final String family;
        @Nullable private ViewTreeObserver observer;
        /** Instagram's latest background on the header, put back when the glass comes off. */
        @Nullable private Drawable original;
        /** The views in the header whose fill was taken off, with that fill. */
        private final Map<View, Drawable> cleared = new HashMap<>();
        private final Set<View> leftOut = new HashSet<>();
        private final GlassTabBar.BlurTries tries = new GlassTabBar.BlurTries();
        private boolean on;
        private boolean broken;
        int base = Color.BLACK;
        /** Takes Home's feed behind the status bar (#462). Null for a chat's header. */
        @Nullable private final GlassReach reach;
        /** Whether the hairline runs along the bottom: Home's header has it, a chat's runs on into the messages (#537). */
        final boolean lined;
        /** For a message box, the id of the view drawing what's under it (#391). 0 for a header. */
        final int sourceId;
        /** Whether a message box's glass stays dark whatever the theme. */
        private final boolean dark;
        /** Whether a message box takes the glass over itself, as a foreground, and not as its background. */
        final boolean over;
        @Nullable private GlassLens rim;
        private final RectF rimBox = new RectF();
        /** How many times the rim bend was asked for. Read by tests. */
        int bends;
        private boolean described;
        /** How far the glass reaches past the header's top and bottom edges, to cover the status bar. */
        int above;
        int below;
        /** Whether Home's header has nothing showing, and where the status bar is measured from its top edge. */
        boolean hollow;
        int stripTop;
        int stripBottom;
        private boolean describedHollow;
        private int describedCovers = -1;

        @Nullable private RenderNode node;
        private int nodeBlurDp = -1;
        float nodePad;
        private int nodeAbove;
        private int nodeBelow;
        boolean recorded;
        private long recordedAt;
        private float lastX = Float.NaN;
        private float lastY = Float.NaN;
        private int lastWidth;
        private int lastHeight;

        Glass(View header, BooleanSupplier enabled, String family, int sourceId, boolean dark) {
            this.header = header;
            this.enabled = enabled;
            this.family = family;
            this.sourceId = sourceId;
            this.dark = dark;
            this.over = sourceId != 0 && !(header instanceof ViewGroup);
            boolean home = sourceId == 0 && FamilyNames.HOME_TOP_BAR.equals(family);
            this.lined = sourceId == 0 && !FamilyNames.GLASS_CHAT.equals(family);
            this.frost = new Frost(this, header.getResources().getDisplayMetrics().density);
            this.reach = home ? new GlassReach() : null;
        }

        /** The view whose parent draws what's under the glass, under that parent: see {@link #host} and {@link #under}. */
        @Nullable
        View host() {
            return sourceId == 0 ? GlassTopBar.host(header) : under(header, sourceId);
        }

        /** The background a message box had, drawn over its glass, or null when there's none to keep. */
        @Nullable
        Drawable kept() {
            return sourceId == 0 || over ? null : original;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (broken || observer != null) return;
            observer = header.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            // The glass goes on in a frame, and a still screen draws none.
            header.invalidate();
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stopListening();
            takeOff();
            forgetBackdrop();
        }

        private void stopListening() {
            ViewTreeObserver listening = observer;
            observer = null;
            if (listening != null && listening.isAlive()) listening.removeOnPreDrawListener(this);
            else header.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            try {
                if (!enabled.getAsBoolean()) {
                    takeOff();
                    return true;
                }
                putOn();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) record(SystemClock.uptimeMillis());
            } catch (Throwable failure) {
                // Instagram's header as it was, for as long as this header lives.
                broken = true;
                stopListening();
                try {
                    takeOff();
                } catch (Throwable ignored) {
                    // Already reporting the first failure.
                }
                HookStatus.threw(family, HOOK, failure);
            }
            return true;
        }

        private void putOn() {
            boolean night = (header.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            Drawable now = over ? header.getForeground() : header.getBackground();
            if (now != frost) {
                original = now;
                if (over) header.setForeground(frost);
                else header.setBackground(frost);
            }
            on = true;
            if (sourceId != 0) {
                // A message box keeps what's round it as it is: the glass is only the pill.
                base = dark || night ? Color.BLACK : Color.WHITE;
                if (over) uncover();
                return;
            }
            View host = host();
            int wide = header.getWidth();
            // The views wrapped round the header, then the header's own, down to CLEAR_DEPTH.
            for (View at = header; host != null && at != host; ) {
                at = (View) at.getParent();
                clear(at, wide);
            }
            if (header instanceof ViewGroup) clearIn((ViewGroup) header, wide, 1);
            base = base(original, cleared.values(), night);
            int wasAbove = above;
            int wasBelow = below;
            boolean wasHollow = hollow;
            reachUnder(host);
            // A header sliding only moves its node, and how far the glass reaches is drawn into that node.
            if (above != wasAbove || below != wasBelow || hollow != wasHollow) frost.invalidateSelf();
        }

        /** Takes the feed behind the status bar, then works out how far the glass reaches over it. */
        private void reachUnder(@Nullable View host) {
            above = 0;
            below = 0;
            hollow = false;
            if (reach == null || host == null || !(host.getParent() instanceof ViewGroup)) return;
            ViewGroup source = (ViewGroup) host.getParent();
            int gap = reach.reach(header, source, source.indexOfChild(host));
            if (gap <= 0) return;
            int top = reach.headerTop(header);
            if (!described) {
                described = true;
                String how = reach.pulledUp() ? "feed pulled up " : "Instagram's feed already up ";
                Logger.printDebug(() -> "Top bar glass: " + how + gap + ", header at " + top + ", parents " + GlassReach.parents(header));
            }
            if (top == Integer.MIN_VALUE) return;
            // From the window's top edge down, even with the header slid partly away.
            above = Math.max(0, top);
            below = Math.max(0, gap - top - header.getHeight());
            // An empty frosted band across the feed helps nobody: only the strip behind the clock stays (#513).
            hollow = showsNothing(header);
            stripTop = -top;
            stripBottom = gap - top;
            if (hollow && !describedHollow) {
                describedHollow = true;
                Logger.printDebug(() -> "Top bar glass: the header shows nothing, at " + top + ", holding " + children(header));
            }
        }

        /**
         * Takes the fill off the views drawn after a pill picture that cover the whole of it, and off the views in
         * them that do, down to {@link #CLEAR_DEPTH}: Instagram 454's text area sits in a row over its message
         * bar's pill, and the glass on the picture under them would never show (#391).
         */
        private void uncover() {
            ViewParent up = header.getParent();
            if (!(up instanceof ViewGroup) || header.getWidth() <= 0) return;
            ViewGroup parent = (ViewGroup) up;
            int at = parent.indexOfChild(header);
            if (at < 0) return;
            for (int i = at + 1, n = parent.getChildCount(); i < n; i++) uncover(parent.getChildAt(i), 1);
            if (cleared.size() == describedCovers) return;
            describedCovers = cleared.size();
            Logger.printDebug(() -> "Message box glass: on " + header.getClass().getSimpleName() + " alpha "
                    + header.getAlpha() + ", fills taken off " + takenOff());
        }

        private void uncover(View view, int depth) {
            if (!covers(view, header)) return;
            Drawable background = view.getBackground();
            if (background != null && !(background instanceof Frost)) {
                cleared.put(view, background);
                view.setBackground(null);
            }
            if (depth >= CLEAR_DEPTH || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, n = group.getChildCount(); i < n; i++) uncover(group.getChildAt(i), depth + 1);
        }

        private String takenOff() {
            StringBuilder found = new StringBuilder();
            for (Map.Entry<View, Drawable> entry : cleared.entrySet()) {
                found.append(entry.getKey().getClass().getSimpleName()).append(' ')
                        .append(entry.getValue().getClass().getSimpleName()).append(", ");
            }
            return found.length() == 0 ? "none" : found.toString();
        }

        private void clearIn(ViewGroup group, int wide, int depth) {
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                View child = group.getChildAt(i);
                clear(child, wide);
                if (depth < CLEAR_DEPTH && child instanceof ViewGroup) clearIn((ViewGroup) child, wide, depth + 1);
            }
        }

        private void clear(View view, int wide) {
            Drawable background = view.getBackground();
            if (!fills(background) || view.getWidth() < wide - 1) return;
            cleared.put(view, background);
            view.setBackground(null);
        }

        private void takeOff() {
            if (!on) return;
            on = false;
            if (over) {
                if (header.getForeground() == frost) header.setForeground(original);
            } else if (header.getBackground() == frost) {
                header.setBackground(original);
            }
            for (Map.Entry<View, Drawable> entry : cleared.entrySet()) {
                if (entry.getKey().getBackground() == null) entry.getKey().setBackground(entry.getValue());
            }
            cleared.clear();
            original = null;
            if (reach != null) reach.retreat();
            above = 0;
            below = 0;
            hollow = false;
        }

        private void forgetBackdrop() {
            recorded = false;
            lastX = Float.NaN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && node != null) node.discardDisplayList();
            GlassLens lens = rim;
            rim = null;
            if (lens != null) lens.discard();
        }

        /**
         * Records the views drawn before the header in its host's parent, round the header and the blur's
         * reach past it, into the node at a quarter of their size. It references their render nodes, so a
         * post moving under the bar moves in the glass too. Recorded again when the header moves or every
         * {@link GlassTabBar#RECORD_EVERY_MS}, for views coming and going under it.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        private void record(long now) {
            View host = host();
            if (host == null) {
                recorded = false;
                return;
            }
            ViewGroup source = (ViewGroup) host.getParent();
            int index = source.indexOfChild(host);
            int width = header.getWidth();
            int height = header.getHeight();
            if (index <= 0 || width <= 0 || height <= 0) {
                recorded = false;
                return;
            }
            float x = 0f;
            float y = 0f;
            for (View at = header; at != source; at = (View) at.getParent()) {
                x += at.getX();
                y += at.getY();
                ViewGroup up = (ViewGroup) at.getParent();
                if (up != source) {
                    x -= up.getScrollX();
                    y -= up.getScrollY();
                }
            }
            int blurDp = Settings.GLASS_TAB_BAR_BLUR_RADIUS.get();
            int up = above;
            int down = below;
            boolean same = recorded && x == lastX && y == lastY && width == lastWidth && height == lastHeight
                    && blurDp == nodeBlurDp && up == nodeAbove && down == nodeBelow;
            if (same && now - recordedAt < GlassTabBar.RECORD_EVERY_MS) return;
            if (tries.resting(now)) return;
            try {
                float density = header.getResources().getDisplayMetrics().density;
                float scale = 1f / DOWNSAMPLE;
                float pad = GlassBlur.radiusPx(blurDp, density);
                float wide = width + 2 * pad;
                float tall = height + up + down + 2 * pad;
                int nodeWidth = Math.max(1, (int) Math.ceil(wide * scale));
                int nodeHeight = Math.max(1, (int) Math.ceil(tall * scale));
                RenderNode backdrop = node;
                if (backdrop == null) {
                    backdrop = new RenderNode("hushgram-top-bar-glass");
                    node = backdrop;
                    nodeBlurDp = -1;
                }
                if (blurDp != nodeBlurDp) {
                    float blur = GlassBlur.effectRadius(blurDp, density, scale);
                    ColorMatrix saturate = new ColorMatrix();
                    saturate.setSaturation(1.5f);
                    ColorFilter colour = new ColorMatrixColorFilter(saturate);
                    backdrop.setRenderEffect(RenderEffect.createChainEffect(
                            RenderEffect.createColorFilterEffect(colour),
                            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                    nodeBlurDp = blurDp;
                }
                backdrop.setPosition(0, 0, nodeWidth, nodeHeight);
                float left = x - pad;
                float top = y - up - pad;
                Canvas recording = backdrop.beginRecording(nodeWidth, nodeHeight);
                try {
                    recording.scale(scale, scale);
                    recording.translate(-left, -top);
                    recording.clipRect(left, top, left + wide, top + tall);
                    // Where nothing draws, the glass shows the bar's own colour and not the sharp feed under it.
                    recording.drawColor(base);
                    GlassTabBar.surfacePaths(source, index, leftOut);
                    for (int i = 0; i < index; i++) {
                        View sibling = source.getChildAt(i);
                        if (sibling.getWidth() <= 0 || sibling.getBottom() <= top || sibling.getTop() >= top + tall) continue;
                        Throwable leftOutDraw = GlassTabBar.drawLeavingOutSurfaces(recording, sibling, leftOut, 0);
                        if (leftOutDraw != null) HookStatus.threw(family, HOOK + " view", leftOutDraw);
                    }
                } finally {
                    backdrop.endRecording();
                }
                nodePad = pad;
                nodeAbove = up;
                nodeBelow = down;
                recorded = true;
                recordedAt = now;
                lastX = x;
                lastY = y;
                lastWidth = width;
                lastHeight = height;
                tries.worked();
            } catch (Throwable failure) {
                // A recording cut short isn't shown: the bar draws its plain tint until one works.
                recorded = false;
                if (tries.failed(now)) HookStatus.threw(family, HOOK, failure);
            }
        }

        /** Draws the blurred backdrop over [bounds], the header's plus the reach past it, and says whether it did. */
        boolean drawBackdrop(Canvas canvas, Rect bounds) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !recorded || node == null
                    || !canvas.isHardwareAccelerated()) return false;
            int saved = canvas.save();
            try {
                canvas.clipRect(bounds);
                canvas.translate(-nodePad, -nodePad - nodeAbove);
                canvas.scale(DOWNSAMPLE, DOWNSAMPLE);
                canvas.drawRenderNode(node);
            } finally {
                canvas.restoreToCount(saved);
            }
            return true;
        }

        /**
         * Bends the blurred backdrop along [area]'s bottom edge as the glass tab bar's rim does (#513), in a band
         * the rim's share of half [across] deep, and says whether it drew. The lens's box runs [across] past the
         * other three edges, off the glass, so only the bottom one bends: the top is the screen's edge and the
         * sides run off it.
         */
        boolean bend(Canvas canvas, Rect area, float across) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !recorded || node == null) return false;
            bends++;
            int saved = canvas.save();
            try {
                canvas.clipRect(area);
                canvas.translate(area.left, area.top);
                rimBox.set(-across, -across, area.width() + across, area.height());
                return lens().paint(canvas, node, -nodePad - area.left, -nodePad - nodeAbove - area.top, DOWNSAMPLE,
                        area.width(), area.height(), rimBox, 0f, across);
            } finally {
                canvas.restoreToCount(saved);
            }
        }

        /**
         * Bends the blurred backdrop all the way round a message box's pill over [bounds], the way the glass tab
         * bar's rim bends round its own (#391), and says whether it drew. The canvas is already clipped to the pill.
         */
        boolean bendPill(Canvas canvas, Rect bounds) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !recorded || node == null) return false;
            bends++;
            int saved = canvas.save();
            try {
                canvas.translate(bounds.left, bounds.top);
                int height = bounds.height();
                rimBox.set(0, 0, bounds.width(), height);
                return lens().paint(canvas, node, -nodePad - bounds.left, -nodePad - nodeAbove - bounds.top, DOWNSAMPLE,
                        bounds.width(), height, rimBox, height / 2f, height);
            } finally {
                canvas.restoreToCount(saved);
            }
        }

        private GlassLens lens() {
            GlassLens lens = rim;
            if (lens == null) {
                lens = new GlassLens(true);
                rim = lens;
            }
            return lens;
        }
    }

    /**
     * The header's background while the glass is on: the blur, the tint over it and, on Home, the hairline, with
     * the glass tab bar's rim bend and sheen while that's on (#513).
     */
    static final class Frost extends Drawable {
        final Glass glass;
        private final Paint paint = new Paint();
        private final Paint sheen = new Paint();
        private final Rect area = new Rect();
        private final int hairline;
        /** A message box's pill, and the hairline rim round it (#391). */
        private final Path pill = new Path();
        private final RectF pillBox = new RectF();
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int sheenTop = Integer.MIN_VALUE;
        private int sheenBottom;
        private boolean sheenDark;
        /** Whether the last draw put the sheen on. Read by tests. */
        boolean sheened;

        Frost(Glass glass, float density) {
            this.glass = glass;
            this.hairline = Math.max(1, Math.round(density / 2f));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density / 2f));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            if (glass.sourceId != 0) {
                drawPill(canvas, bounds);
                return;
            }
            int base = glass.base;
            // Past the header's edges over the status bar: nothing clips a background's own node there.
            if (glass.hollow) area.set(bounds.left, bounds.top + glass.stripTop, bounds.right, bounds.top + glass.stripBottom);
            else area.set(bounds.left, bounds.top - glass.above, bounds.right, bounds.bottom + glass.below);
            boolean blurred = glass.drawBackdrop(canvas, area);
            // Every header follows the glass tab bar's pill: its rim bend, measured on the header, and its sheen.
            boolean likeTabBar = GlassTabBar.onNow();
            if (blurred && likeTabBar && GlassTabBar.bendsNow()) {
                glass.bend(canvas, area, Math.min(area.height(), bounds.height()));
            }
            paint.setColor(GlassTabBar.tint(base, blurred, GlassTabBar.opacityScale()));
            canvas.drawRect(area, paint);
            sheened = likeTabBar;
            if (likeTabBar) {
                keepSheen(GlassTabBar.isDark(base));
                canvas.drawRect(area, sheen);
            }
            if (!glass.lined) return;
            paint.setColor(hairline(base));
            canvas.drawRect(area.left, area.bottom - hairline, area.right, area.bottom, paint);
        }

        /**
         * A message box's glass (#391): the blur and tint inside a pill with fully round ends, with the glass tab
         * bar's rim bend and sheen while that's on, a hairline rim round it, then the box's own background over
         * it, so an outline Instagram draws on it stays.
         */
        private void drawPill(Canvas canvas, Rect bounds) {
            float radius = bounds.height() / 2f;
            pillBox.set(bounds);
            pill.rewind();
            pill.addRoundRect(pillBox, radius, radius, Path.Direction.CW);
            int saved = canvas.save();
            try {
                canvas.clipPath(pill);
                boolean blurred = glass.drawBackdrop(canvas, bounds);
                boolean likeTabBar = GlassTabBar.onNow();
                if (blurred && likeTabBar && GlassTabBar.bendsNow()) glass.bendPill(canvas, bounds);
                paint.setColor(GlassTabBar.tint(glass.base, blurred, GlassTabBar.opacityScale()));
                canvas.drawRect(bounds, paint);
                sheened = likeTabBar;
                if (likeTabBar) {
                    area.set(bounds);
                    keepSheen(GlassTabBar.isDark(glass.base));
                    canvas.drawRect(bounds, sheen);
                }
            } finally {
                canvas.restoreToCount(saved);
            }
            float inset = rim.getStrokeWidth() / 2f;
            pillBox.inset(inset, inset);
            rim.setColor(hairline(glass.base));
            canvas.drawRoundRect(pillBox, radius - inset, radius - inset, rim);
            Drawable kept = glass.kept();
            if (kept == null) return;
            kept.setBounds(bounds);
            kept.draw(canvas);
        }

        /** Makes the sheen's gradient again when the glass's top, bottom or shade changed. */
        private void keepSheen(boolean dark) {
            if (sheenTop == area.top && sheenBottom == area.bottom && sheenDark == dark) return;
            sheenTop = area.top;
            sheenBottom = area.bottom;
            sheenDark = dark;
            sheen.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                    GlassTabBar.sheenTop(dark), 0x00ffffff, Shader.TileMode.CLAMP));
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The hairline's colour along the bottom of a bar tinted from [base]. */
    static int hairline(int base) {
        return GlassTabBar.isDark(base) ? HAIRLINE_DARK : HAIRLINE_LIGHT;
    }
}
