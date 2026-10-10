/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;

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
        int night = sheet.getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) return;
        Drawable background = sheet.getBackground();
        if (background == null) {
            HookStatus.counted(FamilyNames.PURE_BLACK, "comments sheet had no background");
            return;
        }
        background.mutate().setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
        HookStatus.counted(FamilyNames.PURE_BLACK, "comments sheet painted black");
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Pure black: painted the comments sheet black");
        }
    }
}
