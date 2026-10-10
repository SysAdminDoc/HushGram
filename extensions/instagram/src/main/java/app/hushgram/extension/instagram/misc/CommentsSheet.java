/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Pure black dark mode" patch.
 *
 * <p>Instagram's bottom sheets are one host fragment that paints a rounded gray background on its
 * container and puts the real screen inside it. The comments list is one of those screens, so the
 * theme colors the patch already changes never reach it (#108). When the host has put the comments
 * list in, and the app is in dark mode, the container's own background is tinted black. The shape
 * stays, so the rounded top edge and the drag handle look as they did.
 *
 * <p>The host's set-up method turned out not to be on the 450 comments sheet's path, so the screens'
 * own onViewCreated also calls {@link #contentShown}, which finds the sheet by walking up to the
 * view named bottom_sheet_container.
 */
public final class CommentsSheet {
    /** The host's container field, kept by Instagram's build. */
    private static final String CONTAINER_FIELD = "bottomSheetContainer";
    /** The static field Instagram's build leaves with each class's source name. */
    private static final String ORIGINAL_NAME = "__redex_internal_original_name";
    /** Every comments sheet screen's source name starts with this. */
    private static final String COMMENTS_PREFIX = "CommentListBottomsheet";

    /** The hosts showing comments, weakly, so a drag can repaint them. */
    private static final Map<Object, Boolean> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The sheet container's id name; resolved at run time, since ids change with every build. */
    private static final String SHEET_ID_NAME = "bottom_sheet_container";
    private static final PorterDuffColorFilter BLACK = new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
    /** The sheets already watched for a swapped background, weakly. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Field container;
    private static volatile boolean logged;

    private CommentsSheet() {
    }

    /**
     * Injected where the host finishes setting a sheet's content up. Never throws.
     *
     * @param host the bottom sheet fragment
     * @param content the screen it shows
     */
    public static void paint(Object host, Object content) {
        try {
            HookStatus.invoked(FamilyNames.PURE_BLACK);
            if (host == null || content == null || !isComments(content)) {
                if (host != null) HOSTS.remove(host);
                return;
            }
            HOSTS.put(host, Boolean.TRUE);
            blacken(host);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet", failure);
        }
    }

    /**
     * Injected where the host moves while it is dragged, which is where it can put its own
     * background back. Never throws.
     */
    public static void repaint(Object host) {
        try {
            if (host != null && HOSTS.containsKey(host)) blacken(host);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet drag", failure);
        }
    }

    /**
     * Injected at the start of each comments sheet screen's onViewCreated. Paints the sheet the
     * screen sits in (the nearest ancestor named bottom_sheet_container) and the screen's own root
     * black, and keeps the sheet black if the host swaps its background later. Never throws.
     *
     * @param screen the comments fragment
     * @param view the screen's root view
     */
    public static void contentShown(Object screen, Object view) {
        try {
            HookStatus.invoked(FamilyNames.PURE_BLACK);
            if (!(view instanceof View)) return;
            View root = (View) view;
            if (!isNight(root)) return;
            registerHost(screen);
            if (!paintScreen(root)) {
                // Not attached yet: finish when it is.
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View attached) {
                        attached.removeOnAttachStateChangeListener(this);
                        try {
                            paintScreen(attached);
                        } catch (Throwable failure) {
                            HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet attach", failure);
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View detached) {
                    }
                });
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet screen", failure);
        }
    }

    private static void registerHost(Object screen) {
        try {
            Object host = screen.getClass().getMethod("getParentFragment").invoke(screen);
            if (host != null) HOSTS.put(host, Boolean.TRUE);
        } catch (Throwable ignored) {
            // The drag repaint is a bonus; the layout watcher below covers a swapped background.
        }
    }

    /** Paints the screen's root and its sheet; false when the sheet isn't above the root yet. */
    private static boolean paintScreen(View root) {
        tint(root, "comments screen");
        View sheet = findSheet(root);
        if (sheet == null) return false;
        tint(sheet, "comments sheet");
        watch(sheet, root);
        return true;
    }

    /** The nearest ancestor whose id is named bottom_sheet_container. */
    private static View findSheet(View root) {
        int id = root.getResources().getIdentifier(SHEET_ID_NAME, "id", root.getContext().getPackageName());
        return id == 0 ? null : findSheet(root, id);
    }


    static View findSheet(View root, int id) {
        ViewParent parent = root.getParent();
        while (parent instanceof View) {
            View candidate = (View) parent;
            if (candidate.getId() == id) return candidate;
            parent = candidate.getParent();
        }
        return null;
    }

    /**
     * The host puts its own background on the sheet when it sets the content up and as it is
     * dragged, so after each layout the sheet is checked and painted again if the drawable changed.
     */
    private static void watch(final View sheet, final View root) {
        if (WATCHED.put(sheet, Boolean.TRUE) != null) return;
        final ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    Drawable now = sheet.getBackground();
                    if (now != null && now.getColorFilter() != BLACK) { Drawable fix = now.mutate(); fix.setColorFilter(BLACK); }
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet layout", failure);
                }
            }
        };
        sheet.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {
            }

            @Override
            public void onViewDetachedFromWindow(View detached) {
                detached.removeOnAttachStateChangeListener(this);
                WATCHED.remove(sheet);
                if (sheet.getViewTreeObserver().isAlive()) sheet.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
        });
    }

    private static boolean isNight(View view) {
        int night = view.getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void tint(View view, String what) {
        Drawable background = view.getBackground();
        if (background == null) {
            HookStatus.counted(FamilyNames.PURE_BLACK, what + " had no background");
            return;
        }
        Drawable mutated = background.mutate();
        mutated.setColorFilter(BLACK);
        HookStatus.counted(FamilyNames.PURE_BLACK, what + " painted black");
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Pure black: painted the comments sheet black");
        }
    }

    private static boolean isComments(Object content) {
        try {
            Field name = content.getClass().getDeclaredField(ORIGINAL_NAME);
            name.setAccessible(true);
            Object value = name.get(null);
            return value instanceof String && ((String) value).startsWith(COMMENTS_PREFIX);
        } catch (NoSuchFieldException missing) {
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PURE_BLACK, "comments sheet name", failure);
            return false;
        }
    }

    private static void blacken(Object host) throws Exception {
        Field field = container;
        if (field == null) {
            field = host.getClass().getDeclaredField(CONTAINER_FIELD);
            field.setAccessible(true);
            container = field;
        }
        Object value = field.get(host);
        if (!(value instanceof ViewGroup)) return;
        ViewGroup sheet = (ViewGroup) value;
        if (!isNight(sheet)) return;
        tint(sheet, "comments sheet");
    }
}
