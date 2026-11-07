/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Count on the heart (#541): the notifications heart on Home's header shows how many notifications
 * are waiting, the way Messages shows a number, in place of its red dot.
 *
 * <p>The heart is Instagram's ToastingBadge, a badge view that draws itself in one of several display
 * styles: a dot, a number, a toast. Its style setters keep their names in Instagram's code, and so do
 * the style's values, so this sets the heart's style, and the styles it falls back to after a toast,
 * to the number one. Instagram's own badge code then draws the count and caps it the way the Messages
 * count is capped. It runs as Home's header draws, and once per heart view. Turned off, the heart is
 * left alone, so a heart already showing a number keeps it until Instagram restarts.
 */
public final class HeartCount {
    private HeartCount() { }

    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String SHOWN = "Home header heart shows a count";
    static final String NO_NUMBER_STYLE = "Home header heart count not shown, the badge has no number style";

    /** The heart's class, com.instagram.notifications.badging.ui.component.ToastingBadge, by its simple name. */
    static final String HEART_CLASS = "ToastingBadge";

    /** The display style that draws a number, as Instagram's badge style enum names it. */
    static final String NUMBERED = "NUMBERED";

    /** The badge's setters for the style it shows, and the two it goes back to after a toast. */
    static final String[] STYLE_SETTERS = {"setBadgeDisplayStyle", "setToastFallbackDisplayStyle",
            "setToastCappedFallbackDisplayStyle"};

    /** How deep the header's views are searched for the heart. */
    private static final int MAX_DEPTH = 8;

    /** Hearts already given the number style. */
    private static final Map<View, Boolean> done = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Called as Home's header draws, after Instagram put its buttons in. Gives every heart in
     * [header] the number style while the switch is on and HushGram isn't paused. Never throws.
     */
    public static void show(@Nullable View header) {
        try {
            if (header == null || !Utils.settingsReady() || HushgramPause.isPaused() || !Settings.HEART_COUNT.get()) return;
            for (View heart : hearts(header)) {
                if (done.containsKey(heart)) continue;
                if (numbered(heart)) {
                    done.put(heart, Boolean.TRUE);
                    HookStatus.counted(ROUTE, SHOWN);
                    Logger.printDebug(() -> "Count on the heart: the heart shows a number");
                } else {
                    // Asked once per view, so a heart without the style isn't searched again.
                    done.put(heart, Boolean.FALSE);
                    HookStatus.counted(ROUTE, NO_NUMBER_STYLE);
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "heart count", failure);
        }
    }

    /** The hearts under [root], found by their class's simple name. */
    static List<View> hearts(View root) {
        List<View> found = new ArrayList<>();
        collect(root, 0, found);
        return found;
    }

    private static void collect(View view, int depth, List<View> found) {
        if (HEART_CLASS.equals(view.getClass().getSimpleName())) {
            found.add(view);
            return;
        }
        if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null) collect(child, depth + 1, found);
        }
    }

    /**
     * Sets [badge]'s display style to the number one, then the styles it falls back to after a toast.
     * Answers false, having changed nothing, when it has no display style setter or its styles have no
     * number one. A fallback setter that's missing is skipped.
     */
    static boolean numbered(View badge) throws ReflectiveOperationException {
        Method shown = setter(badge.getClass(), STYLE_SETTERS[0]);
        if (shown == null) return false;
        Object number = style(shown.getParameterTypes()[0]);
        if (number == null) return false;
        shown.invoke(badge, number);
        for (int i = 1; i < STYLE_SETTERS.length; i++) {
            Method fallback = setter(badge.getClass(), STYLE_SETTERS[i]);
            if (fallback != null && fallback.getParameterTypes()[0].isInstance(number)) fallback.invoke(badge, number);
        }
        return true;
    }

    /** The public one-argument [name] setter of [type] that takes an enum, or null. */
    @Nullable
    private static Method setter(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isEnum()) {
                return method;
            }
        }
        return null;
    }

    /** [styles]' value named NUMBERED, or null when it has none. */
    @Nullable
    private static Object style(Class<?> styles) {
        Object[] values = styles.getEnumConstants();
        if (values == null) return null;
        for (Object value : values) {
            if (NUMBERED.equals(((Enum<?>) value).name())) return value;
        }
        return null;
    }

    /** For tests: forgets which hearts were given the number style. */
    static void forgetForTests() {
        done.clear();
    }
}
