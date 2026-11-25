/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.text.Layout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every HushGram title in Instagram's gradient: the settings bar, its dialogs and the screens that
 * stand in for it. The colours drift along the words all the time the title is on screen, and as
 * it opens, and every {@link #PERIOD_MS} after, a white sheen sweeps over them. It moves whatever
 * Android's animation scale says (Matt, 2026-10-26: the S22 runs with animations off and the title
 * sat still there).
 */
public final class ShimmerTitle implements Runnable, View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    /** One sheen sweep and the wait after it. */
    static final long PERIOD_MS = 6000;
    /** How long a sheen sweep runs. */
    static final long SWEEP_MS = 1400;
    /** How long the colours take to drift one whole mirrored round. */
    static final long DRIFT_MS = 8000;
    /**
     * The least time between frames: the next frame after it, so about 60 a second on a 60 or
     * 120 Hz screen. A frame posted with no wait runs at once wherever time stands still, as in a
     * test's paused looper, which then never goes idle.
     */
    private static final long FRAME_MS = 10;
    /**
     * Instagram's gradient, yellow through orange, pink and purple to blue, each stop lightened
     * where needed so it reads at 7:1 or better on the black bar.
     */
    static final int[] COLORS = {0xFFFEDA75, 0xFFFA7E1E, 0xFFFF5C9A, 0xFFC77DFF, 0xFF85A1FF};
    /**
     * The same gradient on the light look's white (#762), orange through raspberry and purple to
     * blue, each stop darkened to 7:1 or better on it. Yellow can't read on white, so it starts at
     * the orange.
     */
    static final int[] LIGHT_COLORS = {0xFF9A3412, 0xFFA01E50, 0xFF8E1A9A, 0xFF7520C0, 0xFF3346D3};
    private static final int[] SHEEN = {0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF};
    /**
     * The sheen on the light look: fainter, since white over dark text on a white page fades it.
     * At its peak every light stop still reads at 3:1, the mark for a title this size.
     */
    static final int[] LIGHT_SHEEN = {0x00FFFFFF, 0x4DFFFFFF, 0x00FFFFFF};
    /**
     * The titles wearing the gradient, so painting one twice doesn't run two loops. The loop is held
     * weakly too: it holds its title, and a value that holds its key keeps the entry, and with it
     * the title's dialog and screen, for good. The title's own listener list keeps the loop alive.
     */
    private static final Map<TextView, WeakReference<ShimmerTitle>> WORN = new WeakHashMap<>();

    private final TextView title;
    private final Matrix move = new Matrix();
    private final Matrix across = new Matrix();
    private LinearGradient colors;
    /** The stops {@link #colors} was made from, so a flip of the look remakes it. */
    private int[] shadedWith;
    private LinearGradient sheen;
    private float shadedWidth = -1f;
    private float shadedHeight = -1f;
    /** When the first sweep started, so it plays as the title opens. */
    private long startedAt = -1L;
    private boolean running;
    /** The window observer waiting for the title to show again while the loop rests, or null. */
    @Nullable private ViewTreeObserver waiting;
    /** How many times the loop has run, for tests. */
    private int looks;

    private ShimmerTitle(TextView title) {
        this.title = title;
    }

    /** Puts [title] in the gradient, moving for as long as it's on screen. Again changes nothing. */
    public static void wear(TextView title) {
        if (wornBy(title) != null) return;
        ShimmerTitle shimmer = new ShimmerTitle(title);
        WORN.put(title, new WeakReference<>(shimmer));
        // The gradient replaces the colour, and a full-strength colour keeps it full strength.
        title.setTextColor(Color.WHITE);
        title.addOnAttachStateChangeListener(shimmer);
        if (title.isAttachedToWindow()) shimmer.onViewAttachedToWindow(title);
    }

    /** Whether [title] wears the gradient. */
    static boolean wears(TextView title) {
        return wornBy(title) != null;
    }

    @Nullable
    private static ShimmerTitle wornBy(TextView title) {
        WeakReference<ShimmerTitle> worn = WORN.get(title);
        return worn == null ? null : worn.get();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        startedAt = -1L;
        start();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        running = false;
        title.removeCallbacks(this);
        stopWaiting();
    }

    /**
     * A frame of the title's window while the loop rests: the window draws as it comes back to the front
     * and as a page opens over the title or closes, so the first frame that shows the title starts it again.
     */
    @Override
    public boolean onPreDraw() {
        if (!running && title.isAttachedToWindow() && inFront()) {
            stopWaiting();
            startedAt = -1L;
            start();
        }
        return true;
    }

    private void start() {
        if (running) return;
        running = true;
        title.postOnAnimationDelayed(this, FRAME_MS);
    }

    /**
     * Whether the title can be seen: it and every parent show, in a window that shows. isShown() alone
     * misses a window the system hid while its views still say they show.
     */
    private boolean inFront() {
        return title.isShown() && title.getWindowVisibility() == View.VISIBLE;
    }

    /** Rests the loop until a frame of the window shows the title again. */
    private void rest() {
        running = false;
        startedAt = -1L;
        if (waiting != null) return;
        ViewTreeObserver observer = title.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnPreDrawListener(this);
        waiting = observer;
    }

    private void stopWaiting() {
        ViewTreeObserver observer = waiting;
        waiting = null;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
    }

    /** How many times the loop of [title], which wears the gradient, has run. For tests. */
    static int looksForTests(TextView title) {
        ShimmerTitle shimmer = wornBy(title);
        return shimmer == null ? 0 : shimmer.looks;
    }

    @Override
    public void run() {
        looks++;
        if (!running || !title.isAttachedToWindow()) {
            running = false;
            return;
        }
        if (!inFront()) {
            // Nothing to draw behind a closed page or with Instagram in the background, where a loop
            // that kept looking woke the phone for nothing (#341).
            rest();
            return;
        }
        long now = AnimationUtils.currentAnimationTimeMillis();
        if (startedAt < 0L) startedAt = now;
        frame(now - startedAt);
        title.postOnAnimationDelayed(this, FRAME_MS);
    }

    /** Shades [title], which wears the gradient, as it stands [sinceStart] ms after it opened. */
    static void frameFor(TextView title, long sinceStart) {
        ShimmerTitle shimmer = wornBy(title);
        if (shimmer != null) shimmer.frame(sinceStart);
    }

    /** Shades the title as it stands [sinceStart] ms after it opened, and has it redrawn. */
    void frame(long sinceStart) {
        title.getPaint().setShader(shade(sinceStart));
        title.invalidate();
    }

    @Nullable
    private Shader shade(long sinceStart) {
        Layout layout = title.getLayout();
        if (layout == null || layout.getLineCount() == 0) return null;
        // TextView draws its layout from the padding's corner, so these are the layout's own
        // coordinates. A wrapped title takes its colours across its widest line.
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (int line = 0; line < layout.getLineCount(); line++) {
            left = Math.min(left, layout.getLineLeft(line));
            right = Math.max(right, layout.getLineRight(line));
        }
        float width = Math.max(1f, right - left);
        float height = Math.max(1f, layout.getHeight());
        float band = height * 1.6f;
        int[] stops = stops();
        if (colors == null || width != shadedWidth || height != shadedHeight || stops != shadedWith) {
            colors = new LinearGradient(0f, 0f, width, 0f, stops, null, Shader.TileMode.MIRROR);
            shadedWith = stops;
            sheen = new LinearGradient(0f, 0f, band, band * 0.35f,
                    stops == LIGHT_COLORS ? LIGHT_SHEEN : SHEEN, null, Shader.TileMode.CLAMP);
            shadedWidth = width;
            shadedHeight = height;
        }
        move.setTranslate(left + drift(sinceStart) * 2f * width, 0f);
        colors.setLocalMatrix(move);
        float sweep = sweep(sinceStart);
        if (sweep < 0f) return colors;
        across.setTranslate(left - band + sweep * (width + band), 0f);
        sheen.setLocalMatrix(across);
        // Built afresh each frame: a ComposeShader keeps the matrices its parts had when it was made.
        return new ComposeShader(colors, sheen, PorterDuff.Mode.SRC_OVER);
    }

    /** The gradient for the colours on show: {@link #LIGHT_COLORS} on the light look, else {@link #COLORS}. */
    static int[] stops() {
        return ScreenColors.current().light ? LIGHT_COLORS : COLORS;
    }

    /**
     * How far into its sweep the title is [sinceStart] ms after it opened, 0 to 1, or -1 while it
     * waits for the next.
     */
    static float sweep(long sinceStart) {
        if (sinceStart < 0L) return -1f;
        long into = sinceStart % PERIOD_MS;
        return into < SWEEP_MS ? into / (float) SWEEP_MS : -1f;
    }

    /**
     * How far round its mirrored cycle the gradient has drifted [sinceStart] ms after the title
     * opened, 0 to 1. A whole round ends on the colours it began with, so the drift never jumps.
     */
    static float drift(long sinceStart) {
        if (sinceStart <= 0L) return 0f;
        return (sinceStart % DRIFT_MS) / (float) DRIFT_MS;
    }
}
