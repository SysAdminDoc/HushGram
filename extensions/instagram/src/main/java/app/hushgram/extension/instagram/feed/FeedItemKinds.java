/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Reads the kind of an item in Instagram's home feed or its stories tray.
 *
 * <p>Each feed item carries its kind as an enum: a post (MEDIA), an ad, a row of suggested reels
 * (CLIPS_NETEGO), a row of accounts to follow (SUGGESTED_USERS) and so on, and each tray item its
 * reel type (USER_REEL, SUGGESTED_USER_REEL and so on). The kind is read from whichever of the
 * item's enum fields holds one of the names asked about. The feed item has three enum fields on
 * Instagram 449, and each patch reading this checks at patch time that only one of an item's enum
 * types names any of its kinds.
 */
public final class FeedItemKinds {
    /** The enum fields of each item class seen. Feed items all come from one class, tray items from another. */
    private static final Map<Class<?>, EnumFields> known = new ConcurrentHashMap<>();

    /** The constant names of each kind enum seen, so a field's type is listed once. */
    private static final Map<Class<?>, Set<String>> constantNames = new ConcurrentHashMap<>();

    private FeedItemKinds() {
    }

    /**
     * The name of [item]'s kind when it's one of [names], or null. A class with no enum field is
     * reported as a missing member of [family], once per class.
     */
    public static String kindIn(Object item, Set<String> names, String family) throws IllegalAccessException {
        for (Field field : enumFields(item.getClass(), family)) {
            Object value = field.get(item);
            if (value instanceof Enum && names.contains(((Enum<?>) value).name())) {
                return ((Enum<?>) value).name();
            }
        }
        return null;
    }

    /**
     * The name of [item]'s kind, whatever it is, read from the enum field whose type has a constant
     * named [marker], or null. For a report that counts every kind a list carries, where a switch
     * for a kind nobody has named yet needs that name first.
     */
    public static String kindOf(Object item, String marker, String family) throws IllegalAccessException {
        for (Field field : enumFields(item.getClass(), family)) {
            Object value = field.get(item);
            if (value instanceof Enum && constants(((Enum<?>) value).getDeclaringClass()).contains(marker)) {
                return ((Enum<?>) value).name();
            }
        }
        return null;
    }

    private static Set<String> constants(Class<?> type) {
        Set<String> names = constantNames.get(type);
        if (names == null) {
            Set<String> found = new HashSet<>();
            for (Object constant : type.getEnumConstants()) found.add(((Enum<?>) constant).name());
            names = Collections.unmodifiableSet(found);
            constantNames.put(type, names);
        }
        return names;
    }

    private static List<Field> enumFields(Class<?> owner, String family) {
        EnumFields found = known.get(owner);
        if (found == null) {
            found = new EnumFields(owner, family);
            known.put(owner, found);
        }
        return found.fields;
    }

    /** A class's own instance fields typed by an enum, made readable. */
    private static final class EnumFields {
        final List<Field> fields;

        EnumFields(Class<?> owner, String family) {
            List<Field> fields = new ArrayList<>();
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Enum.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
            if (fields.isEmpty()) {
                HookStatus.missingMember(family, "field", owner.getName(), "feed item kind");
            }
            this.fields = Collections.unmodifiableList(fields);
        }
    }
}
