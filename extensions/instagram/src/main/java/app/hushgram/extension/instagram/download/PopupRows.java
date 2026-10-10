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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Adds a plain text row to Instagram's newer pop-up list, the one next to a profile's three dots.
 * Its entries are all one class with a single constructor of 24 arguments, whose fifth is the
 * row's click callback, an interface of one boolean method and one void method, and whose
 * seventeenth is the label. The names are Instagram's own and change with every build, so the
 * class is never named here: a row Instagram already built in the same list is the template, and
 * the new one is made with that row's constructor and the same values, bar its icon, its label and
 * its callback.
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

    private PopupRows() {
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

    /** [template]'s class's one constructor, or null when the class isn't shaped as expected. */
    static Constructor<?> constructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length != 1 || constructors[0].getParameterTypes().length != ARGUMENTS) return null;
        Class<?>[] parameters = constructors[0].getParameterTypes();
        if (!parameters[CLICK].isInterface() || parameters[LABEL] != String.class || parameters[SUBTITLE] != String.class
                || parameters[COLOR] != Integer.class || parameters[HIGHLIGHT] != Integer.class) {
            return null;
        }
        return constructors[0];
    }

    /** The values of [item]'s instance fields in the order Instagram names them, which is its constructor's, or null. */
    static Object[] values(Object item) throws ReflectiveOperationException {
        Constructor<?> constructor = constructor(item.getClass());
        if (constructor == null) return null;
        List<Field> fields = new ArrayList<>();
        for (Field field : item.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
        }
        if (fields.size() != ARGUMENTS) return null;
        Collections.sort(fields, new Comparator<Field>() {
            @Override public int compare(Field a, Field b) {
                return a.getName().compareTo(b.getName());
            }
        });
        Class<?>[] parameters = constructor.getParameterTypes();
        Object[] values = new Object[ARGUMENTS];
        for (int at = 0; at < ARGUMENTS; at++) {
            Field field = fields.get(at);
            if (field.getType() != parameters[at]) return null;
            field.setAccessible(true);
            values[at] = field.get(item);
        }
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
