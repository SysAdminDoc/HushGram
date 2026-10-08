/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Change the like animation" patch.
 *
 * <p>The heart that pops up over a post when you double tap it is set up with one of Instagram's own
 * like animations, the ones it made for Instagram Rings creators and plays on their posts, or with
 * the plain heart. The patch hands each set-up's animation through {@link #pick} first, so while the
 * switch is on it gets the one picked in settings, and passes the check Instagram makes before it
 * plays any of its own animations through {@link #allow}, so that check doesn't put the plain heart
 * back. Nothing Instagram stores is written, so with the switch off or HushGram paused the plain
 * heart (or a Rings creator's own animation) is back on the next post set up.
 *
 * <p>The animations are Instagram's own enum. The patch fills {@link #noAnimation} in with its
 * plain heart, and {@link #animationType} reads the enum off that, which is how the settings
 * screen lists them by name.
 *
 * <p>Both hooks fail open: with the switch off, nothing picked, HushGram paused, the settings not
 * read yet, a picked name this Instagram doesn't have or anything thrown, Instagram decides as it
 * would.
 */
public final class LikeAnimation {
    /** The steps a failure is reported under. */
    static final String SET_UP = "heart set-up";
    static final String CHECK = "animation check";

    private static final String RINGS_PREFIX = "RINGS_LIKE_";
    private static final String ACTIVATION_SUFFIX = "_LIKE_ACTIVATION";

    private static volatile boolean logged;

    private LikeAnimation() {
    }

    /** Instagram's like animation type, the enum of the plain heart, or null unpatched. */
    @Nullable
    static Class<?> animationType() {
        return typeOf(noAnimation());
    }

    /** The enum [value] belongs to, or null when it isn't an enum's value. */
    @Nullable
    static Class<?> typeOf(@Nullable Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).getDeclaringClass() : null;
    }

    /** Filled in by the patch: the type's value for the plain heart, or null unpatched. */
    @Nullable
    static Object noAnimation() {
        return null;
    }

    /**
     * Injected first thing in the heart's set-up for a post, with Instagram's animation for it (null
     * for the plain heart). Answers the picked animation while the switch is on, and Instagram's
     * otherwise. Never throws.
     */
    public static Object pick(@Nullable Object instagram) {
        return pick(instagram, LikeAnimation::chosen);
    }

    static Object pick(@Nullable Object instagram, Supplier<Object> chosen) {
        Object picked;
        try {
            HookStatus.invoked(FamilyNames.LIKE_ANIMATION);
            picked = chosen.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIKE_ANIMATION, SET_UP, failure);
            return instagram;
        }
        HookStatus.counted(FamilyNames.LIKE_ANIMATION, picked == null ? "Instagram's animation" : "picked animation");
        if (picked == null) return instagram;
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Change the like animation: set up the picked animation");
        }
        return picked;
    }

    /**
     * Injected right after the check Instagram makes before it plays one of its own animations.
     * Answers yes while an animation is picked, and Instagram's answer otherwise. Never throws.
     */
    public static boolean allow(int instagram) {
        return allow(instagram != 0, LikeAnimation::chosen);
    }

    static boolean allow(boolean instagram, Supplier<Object> chosen) {
        if (instagram) return true;
        try {
            return chosen.get() != null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIKE_ANIMATION, CHECK, failure);
            return false;
        }
    }

    /**
     * The names of the animations to pick from, in Instagram's order, leaving out the plain heart.
     * Empty when the patch isn't in.
     */
    public static List<String> names() {
        return names(animationType(), noAnimation());
    }

    static List<String> names(@Nullable Class<?> type, @Nullable Object none) {
        Object[] values = type == null ? null : type.getEnumConstants();
        if (values == null) return Collections.emptyList();
        List<String> names = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != none) names.add(((Enum<?>) value).name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * What the list calls the animation [name]: the creator's or the animation's own name from
     * Instagram's, in words, so RINGS_LIKE_AKI_KOICHI is "Aki Koichi".
     */
    public static String label(String name) {
        String bare = name;
        if (bare.startsWith(RINGS_PREFIX)) bare = bare.substring(RINGS_PREFIX.length());
        if (bare.endsWith(ACTIVATION_SUFFIX)) bare = bare.substring(0, bare.length() - ACTIVATION_SUFFIX.length());
        StringBuilder words = new StringBuilder();
        for (String word : bare.split("_")) {
            if (word.isEmpty()) continue;
            if (words.length() > 0) words.append(' ');
            words.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return words.length() == 0 ? name : words.toString();
    }

    /** The picked animation while the switch is on, or null for Instagram's. */
    @Nullable
    private static Object chosen() {
        return chosen(animationType(), noAnimation());
    }

    @Nullable
    static Object chosen(@Nullable Class<?> type, @Nullable Object none) {
        if (!Utils.settingsReady() || !Settings.CHANGE_LIKE_ANIMATION.get()) return null;
        return find(type, none, Settings.LIKE_ANIMATION.get());
    }

    /** The value of [type] named [name], leaving out the plain heart, or null. */
    @Nullable
    static Object find(@Nullable Class<?> type, @Nullable Object none, @Nullable String name) {
        if (type == null || name == null || name.isEmpty()) return null;
        Object[] values = type.getEnumConstants();
        if (values == null) return null;
        for (Object value : values) {
            if (value != none && ((Enum<?>) value).name().equals(name)) return value;
        }
        return null;
    }
}
