/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds a plain text row to Instagram's newer pop-up list, the one next to a profile's three dots.
 * Its entries are all one class with a single constructor of 24 arguments, whose fifth is the
 * row's click callback, an interface of one boolean method and one void method, and whose
 * seventeenth is the label. The names are Instagram's own and change with every build, so the
 * class is never named here: a row Instagram already built in the same list is the template, and
 * the new one is made with that row's constructor and the same values, bar its icon, its label and
 * its callback. Which field holds which argument doesn't follow the field names, so the patch reads
 * it from the constructor and hands it over through {@link ProfilePicture#popupItemFields()}.
 *
 * <p>Instagram taps a row by calling the callback's void method, then its boolean one, and closes
 * the list when that answers true. The callback is a proxy: the void method runs the listener, the
 * boolean one answers true.
 */
final class PopupRows {
    /** The constructor's arguments, and the places of the ones that change. */
    static final int ARGUMENTS = 24;
    static final int ICON = 0;
    static final int CLICK = 4;
    static final int LABEL = 16;
    static final int SUBTITLE = 17;
    /**
     * A row with no extra color: Instagram gives one an Integer color of -1 in slot 9 and nothing
     * in slot 10, while a highlighted one carries its color in both.
     */
    static final int COLOR = 9;
    static final int HIGHLIGHT = 10;

    /** Each item class's constructor and its fields in constructor order, read once; NONE when it isn't shaped as expected. */
    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();
    private static final Shape NONE = new Shape(null, null);

    /** Where the item's class and field order come from: the patched stub, or a stand-in in tests. */
    interface Layout {
        String read();
    }

    static volatile Layout layout = ProfilePicture::popupItemFields;

    private PopupRows() {
    }

    /** Forgets every item class read, for tests that swap [layout]. */
    static void reset() {
        SHAPES.clear();
    }

    private static final class Shape {
        final Constructor<?> constructor;
        final Field[] fields;

        Shape(Constructor<?> constructor, Field[] fields) {
            this.constructor = constructor;
            this.fields = fields;
        }
    }

    /**
     * Adds a row labeled [label] that runs [listener] to the end of [items], the pop-up list's
     * entries. Answers whether it went in, which is false when the list has no plain row to copy or
     * the item class isn't shaped as expected. Never throws.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean add(List items, View.OnClickListener listener, String label) {
        try {
            if (items == null || listener == null || label == null) return false;
            // Instagram can hand the same list over again when it re-shows the pop-up, and the row is already there.
            if (owns(items, label)) return true;
            Object template = template(items);
            if (template == null) return false;
            Object row = make(template, listener, label);
            if (row == null) return false;
            items.add(row);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The first plain row of [items] in the expected shape, or null. */
    private static Object template(List<?> items) throws ReflectiveOperationException {
        for (Object item : items) {
            if (item == null) continue;
            Object[] values = values(item);
            if (values == null) continue;
            if (Integer.valueOf(-1).equals(values[COLOR]) && values[HIGHLIGHT] == null) return item;
        }
        return null;
    }

    /** Whether [items] already holds a row of ours labeled [label]: one whose callback is a proxy. */
    private static boolean owns(List<?> items, String label) throws ReflectiveOperationException {
        for (Object item : items) {
            if (item == null) continue;
            Object[] values = values(item);
            if (values != null && label.equals(values[LABEL]) && values[CLICK] != null
                    && Proxy.isProxyClass(values[CLICK].getClass())) {
                return true;
            }
        }
        return false;
    }

    /** [type]'s constructor and ordered fields, read on first use and kept. */
    private static Shape shape(Class<?> type) {
        Shape shape = SHAPES.get(type);
        if (shape != null) return shape;
        Constructor<?> constructor = readConstructor(type);
        Field[] ordered;
        try {
            ordered = constructor == null ? null : orderedFields(type, constructor);
        } catch (ReflectiveOperationException | RuntimeException missing) {
            ordered = null;
        }
        shape = ordered == null ? NONE : new Shape(constructor, ordered);
        SHAPES.put(type, shape);
        return shape;
    }

    /** [template]'s class's one constructor, or null when the class isn't shaped as expected. */
    static Constructor<?> constructor(Class<?> type) {
        return shape(type).constructor;
    }

    private static Constructor<?> readConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length != 1 || constructors[0].getParameterTypes().length != ARGUMENTS) return null;
        Class<?>[] parameters = constructors[0].getParameterTypes();
        if (!parameters[CLICK].isInterface() || parameters[LABEL] != String.class || parameters[SUBTITLE] != String.class
                || parameters[COLOR] != Integer.class || parameters[HIGHLIGHT] != Integer.class) {
            return null;
        }
        return constructors[0];
    }

    /**
     * [type]'s instance fields in its constructor's argument order, as the patch read them off the
     * constructor, or null when there's no such order for this class or the fields don't line up.
     */
    private static Field[] orderedFields(Class<?> type, Constructor<?> constructor) throws NoSuchFieldException {
        String read = layout.read();
        int bar = read == null ? -1 : read.indexOf('|');
        if (bar < 0 || !read.substring(0, bar).equals(type.getName())) return null;
        String[] names = read.substring(bar + 1).split(",", -1);
        if (names.length != ARGUMENTS) return null;
        Class<?>[] parameters = constructor.getParameterTypes();
        Set<String> seen = new HashSet<>();
        Field[] fields = new Field[ARGUMENTS];
        for (int at = 0; at < ARGUMENTS; at++) {
            Field field = type.getDeclaredField(names[at]);
            if (!seen.add(names[at]) || Modifier.isStatic(field.getModifiers()) || field.getType() != parameters[at]) return null;
            field.setAccessible(true);
            fields[at] = field;
        }
        return fields;
    }

    /** The values of [item]'s instance fields in constructor order, or null when its class isn't shaped as expected. */
    static Object[] values(Object item) throws ReflectiveOperationException {
        Field[] fields = shape(item.getClass()).fields;
        if (fields == null) return null;
        Object[] values = new Object[ARGUMENTS];
        for (int at = 0; at < ARGUMENTS; at++) values[at] = fields[at].get(item);
        return values;
    }

    private static Object make(Object template, View.OnClickListener listener, String label) throws ReflectiveOperationException {
        Constructor<?> constructor = constructor(template.getClass());
        Object[] values = values(template);
        if (constructor == null || values == null) return null;
        Class<?> callback = constructor.getParameterTypes()[CLICK];
        Object click = callback(callback, listener);
        if (click == null) return null;
        values[ICON] = null;
        values[CLICK] = click;
        values[LABEL] = label;
        values[SUBTITLE] = null;
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    /** A proxy of [callback] that runs [listener] on its void method and answers true on its boolean one, or null. */
    static Object callback(Class<?> callback, final View.OnClickListener listener) {
        Method[] methods = callback.getMethods();
        int booleans = 0, voids = 0;
        for (Method method : methods) {
            if (method.getParameterTypes().length != 0) return null;
            if (method.getReturnType() == boolean.class) booleans++;
            else if (method.getReturnType() == void.class) voids++;
        }
        if (methods.length != 2 || booleans != 1 || voids != 1) return null;
        InvocationHandler handler = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) {
                    switch (method.getName()) {
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        default: return "HushGram row";
                    }
                }
                if (method.getReturnType() == boolean.class) return Boolean.TRUE;
                listener.onClick(null);
                return null;
            }
        };
        return Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback}, handler);
    }
}
