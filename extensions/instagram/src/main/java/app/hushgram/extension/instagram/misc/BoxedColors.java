/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Compose's colors as a list holds them: each boxed in Compose's color class, which the build renames,
 * as one long. A color in sRGB, as every one Instagram makes from a color int is, keeps that int in the
 * long's top half and nothing in its bottom half. The class is read by reflection the first time it's
 * seen: its one long field, and its constructor taking a long.
 */
public final class BoxedColors {
    /** The last class read, its field and its constructor, or null before any. */
    @Nullable
    private static volatile Kind known;

    private BoxedColors() {
    }

    /**
     * The colors [colors] holds as color ints, or null when any isn't a boxed sRGB color of one class
     * this can read and make again.
     */
    @Nullable
    public static int[] argb(List<?> colors) throws ReflectiveOperationException {
        if (colors.isEmpty()) return null;
        Object first = colors.get(0);
        if (first == null) return null;
        Kind kind = kind(first.getClass());
        if (kind == null) return null;
        int[] argb = new int[colors.size()];
        for (int at = 0; at < argb.length; at++) {
            Object color = colors.get(at);
            if (color == null || color.getClass() != kind.type) return null;
            long packed = kind.value.getLong(color);
            if ((packed & 0xffffffffL) != 0) return null;
            argb[at] = (int) (packed >>> 32);
        }
        return argb;
    }

    /** [argb] boxed as [like]'s colors are, in a list of its own, or null when its class can't be read. */
    @Nullable
    public static List<Object> boxed(Object like, int[] argb) throws ReflectiveOperationException {
        Kind kind = kind(like.getClass());
        if (kind == null) return null;
        List<Object> boxed = new ArrayList<>(argb.length);
        for (int color : argb) boxed.add(kind.maker.newInstance((long) color << 32));
        return boxed;
    }

    @Nullable
    private static Kind kind(Class<?> type) {
        Kind kind = known;
        if (kind != null && kind.type == type) return kind;
        Field value = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
            if (value != null) return null;
            value = field;
        }
        if (value == null) return null;
        Constructor<?> maker;
        try {
            maker = type.getDeclaredConstructor(long.class);
        } catch (NoSuchMethodException none) {
            return null;
        }
        value.setAccessible(true);
        maker.setAccessible(true);
        kind = new Kind(type, value, maker);
        known = kind;
        return kind;
    }

    private static final class Kind {
        final Class<?> type;
        final Field value;
        final Constructor<?> maker;

        Kind(Class<?> type, Field value, Constructor<?> maker) {
            this.type = type;
            this.value = value;
            this.maker = maker;
        }
    }
}
