/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The [SCREEN] section of the diagnostic report: how Instagram's screen behind the settings is laid
 * out when the report is made. A page that comes up blank (#535) is a view that's there but hidden,
 * faded, moved off, empty or left without its video, and which one it is can't be told from the
 * switches. HushGram's settings open in a dialog over Instagram's activity, so its views are still
 * the ones the person was looking at.
 *
 * <p>It names the activity, the tab buttons with the selected one marked, and then each view that
 * covers most of the window, outermost first: its class, id, size, place in the window, and whatever
 * isn't plain about it (hidden, faded, moved, scaled, margins, a solid background, no children, a
 * video view without its surface). Only shown views are walked into, so a hidden tab's page is one
 * line. No text, no images and nothing about an account is read.
 */
public final class ScreenReport {
    /** A view covering at least this share of the window gets a line. */
    static final float COVERING = 0.4f;
    /** The most lines the views take, so a strange tree can't make a long report. */
    static final int MAX_LINES = 150;
    /** How deep the walk goes. */
    static final int MAX_DEPTH = 80;
    /** How long a report made off the main thread waits for it. */
    static final long WAIT_MS = 2_000;

    static final String TAB_BAR = "tab_bar";
    static final String TAB_SUFFIX = "_tab";

    /** The section, registered with the others when the settings open. */
    public static final LogBufferManager.ReportSection SECTION = new LogBufferManager.ReportSection() {
        @Override
        public String title() {
            return "SCREEN";
        }

        @Override
        public List<String> lines() {
            return onMainThread(() -> describe(Utils.getActivity()));
        }
    };

    private ScreenReport() {
    }

    /** What [activity]'s window shows, or a line saying there's none. */
    static List<String> describe(@Nullable Activity activity) {
        List<String> lines = new ArrayList<>();
        if (activity == null) {
            lines.add("activity: none");
            return lines;
        }
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        lines.add("activity: " + name(activity.getClass()) + (activity.isFinishing() ? ", finishing" : "")
                + (decor != null && decor.hasWindowFocus() ? ", focused" : ""));
        if (decor == null) {
            lines.add("window: no views");
            return lines;
        }
        lines.add("window: " + decor.getWidth() + "x" + decor.getHeight());
        String tabs = tabs(decor);
        if (tabs != null) lines.add("tabs: " + tabs);
        lines.add("views covering most of the window, outermost first:");
        long area = (long) decor.getWidth() * decor.getHeight();
        int before = lines.size();
        walk(decor, 0, area, lines, before + MAX_LINES);
        if (lines.size() >= before + MAX_LINES) lines.add("more views left out");
        return lines;
    }

    /** The tab buttons in the tab bar under [decor], the selected one marked, or null when there's no bar. */
    @Nullable
    private static String tabs(View decor) {
        int id = GlassTabBar.id(decor.getContext(), TAB_BAR);
        View bar = id == 0 ? null : decor.findViewById(id);
        if (bar == null) return null;
        List<String> names = new ArrayList<>();
        collectTabs(bar, names, 0);
        String shown = bar.getVisibility() == View.VISIBLE ? "" : " (bar " + visibility(bar) + ")";
        return (names.isEmpty() ? "none found" : String.join(", ", names)) + shown;
    }

    private static void collectTabs(View view, List<String> names, int depth) {
        String id = idName(view);
        if (id != null && id.endsWith(TAB_SUFFIX) && depth > 0) {
            names.add(id + (view.isSelected() ? " (selected)" : "")
                    + (view.getVisibility() == View.VISIBLE ? "" : " (" + visibility(view) + ")"));
            return;
        }
        if (!(view instanceof ViewGroup) || depth > 10) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectTabs(group.getChildAt(i), names, depth + 1);
    }

    /** Adds a line for [view] when it covers most of the window, then walks its children while it shows. */
    private static void walk(View view, int depth, long area, List<String> lines, int limit) {
        if (lines.size() >= limit || depth > MAX_DEPTH) return;
        boolean covers = area > 0 && (long) view.getWidth() * view.getHeight() >= COVERING * area;
        if (!covers) return;
        lines.add(line(view, depth));
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1, area, lines, limit);
    }

    /** One view's line: its depth, class, id, size and place, then what isn't plain about it. */
    static String line(View view, int depth) {
        StringBuilder line = new StringBuilder();
        line.append(depth).append(' ').append(name(view.getClass()));
        String id = idName(view);
        if (id != null) line.append(" id ").append(id);
        line.append(' ').append(view.getWidth()).append('x').append(view.getHeight());
        int[] at = new int[2];
        view.getLocationInWindow(at);
        line.append(" at ").append(at[0]).append(',').append(at[1]);
        if (view.getVisibility() != View.VISIBLE) line.append(' ').append(visibility(view));
        if (view.getAlpha() < 1f) line.append(" alpha ").append(number(view.getAlpha()));
        if (view.getTranslationX() != 0f || view.getTranslationY() != 0f) {
            line.append(" moved ").append(number(view.getTranslationX())).append(',').append(number(view.getTranslationY()));
        }
        if (view.getScaleX() != 1f || view.getScaleY() != 1f) {
            line.append(" scaled ").append(number(view.getScaleX())).append(',').append(number(view.getScaleY()));
        }
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (margins.leftMargin != 0 || margins.topMargin != 0 || margins.rightMargin != 0 || margins.bottomMargin != 0) {
                line.append(" margins ").append(margins.leftMargin).append(',').append(margins.topMargin).append(',')
                        .append(margins.rightMargin).append(',').append(margins.bottomMargin);
            }
        }
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable) {
            line.append(" background ").append(String.format(Locale.ROOT, "%08X", ((ColorDrawable) background).getColor()));
        }
        if (view instanceof ViewGroup) line.append(" children ").append(((ViewGroup) view).getChildCount());
        if (view instanceof TextureView) line.append(((TextureView) view).isAvailable() ? " surface ready" : " no surface");
        if (!view.isAttachedToWindow()) line.append(" detached");
        if (!view.isLaidOut()) line.append(" not laid out");
        return line.toString();
    }

    private static String visibility(View view) {
        return view.getVisibility() == View.GONE ? "gone" : view.getVisibility() == View.INVISIBLE ? "invisible" : "shown";
    }

    /** A class's own name, which for Instagram's renamed classes is the part after the package. */
    private static String name(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) return simple;
        String full = type.getName();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    @Nullable
    private static String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Exception unnamed) {
            return null;
        }
    }

    private static String number(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    /** [read] run on the main thread, which owns the views, waiting up to {@link #WAIT_MS} when called from another. */
    static List<String> onMainThread(java.util.function.Supplier<List<String>> read) {
        if (Looper.myLooper() == Looper.getMainLooper()) return read.get();
        AtomicReference<List<String>> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                answer.set(read.get());
            } catch (Throwable failure) {
                answer.set(Collections.singletonList("could not be read: " + failure));
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) return Collections.singletonList("the screen was busy");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Collections.singletonList("the screen was busy");
        }
        return answer.get();
    }
}
