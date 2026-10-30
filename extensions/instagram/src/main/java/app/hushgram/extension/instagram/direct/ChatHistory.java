/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the chat on screen holds: its messages, found in the objects the chat's list is drawn from,
 * their times, and the text lines an export writes. Nothing here touches a view or the disk.
 *
 * <p>The list's adapter holds one item per row, and a row holds the message it shows somewhere
 * below it, so the messages are found by walking the adapter's own objects ({@link #read}). When the
 * adapter's item list can be told apart, each message keeps the position of the row it sits in,
 * which is where a jump scrolls to.
 */
public final class ChatHistory {
    /** How a message's fields are read. {@link #LIVE} asks the bodies the patch wrote. */
    interface Reader {
        boolean isMessage(Object candidate);

        String id(Object message);

        String sender(Object message);

        String text(Object message);

        String time(Object message);

        String kind(Object message);

        boolean sentByViewer(Object message);
    }

    static final Reader LIVE = new Reader() {
        @Override public boolean isMessage(Object candidate) {
            return ChatMessages.isMessage(candidate);
        }

        @Override public String id(Object message) {
            return ChatMessages.id(message);
        }

        @Override public String sender(Object message) {
            return ChatMessages.sender(message);
        }

        @Override public String text(Object message) {
            return ChatMessages.text(message);
        }

        @Override public String time(Object message) {
            return ChatMessages.time(message);
        }

        @Override public String kind(Object message) {
            return ChatMessages.kind(message);
        }

        @Override public boolean sentByViewer(Object message) {
            return ChatMessages.sentByViewer(message);
        }
    };

    /** The most objects one walk looks at, so a wrong turn into a big graph ends. */
    static final int WALK_BUDGET = 400_000;
    /** How deep below a row, or below the adapter when its rows can't be told apart, a walk goes. */
    static final int ROW_DEPTH = 6;
    static final int ADAPTER_DEPTH = 8;
    private static final int MAX_ELEMENTS = 50_000;

    /** One message: where its row is (-1 when unknown), who sent it and what it says. */
    public static final class Entry {
        public final int position;
        public final String id;
        public final String sender;
        public final String text;
        public final String kind;
        public final long millis;
        public final boolean viewer;

        Entry(int position, String id, String sender, String text, String kind, long millis, boolean viewer) {
            this.position = position;
            this.id = id;
            this.sender = sender;
            this.text = text;
            this.kind = kind;
            this.millis = millis;
            this.viewer = viewer;
        }
    }

    private ChatHistory() {
    }

    /**
     * The messages an adapter holds, oldest first, each once. [count] is the adapter's item count,
     * which tells its item list from the other lists it keeps. Empty when no message is found, and
     * never throws.
     */
    static List<Entry> read(Object adapter, int count, Reader reader) {
        List<Entry> found = new ArrayList<>();
        try {
            List<?> rows = rowsOf(adapter, count);
            if (rows != null) {
                Map<Object, Boolean> seen = new IdentityHashMap<>();
                int[] budget = {WALK_BUDGET};
                for (int row = 0; row < rows.size(); row++) {
                    for (Object message : walk(rows.get(row), ROW_DEPTH, reader, seen, budget)) {
                        found.add(entry(row, message, reader));
                    }
                }
            }
            if (found.isEmpty()) {
                int[] budget = {WALK_BUDGET};
                for (Object message : walk(adapter, ADAPTER_DEPTH, reader, new IdentityHashMap<>(), budget)) {
                    found.add(entry(-1, message, reader));
                }
            }
        } catch (Throwable ignored) {
            // What was found so far stands.
        }
        return ordered(found);
    }

    /** Oldest first, one entry per message id, the one with a known row kept. */
    static List<Entry> ordered(List<Entry> entries) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        List<Entry> anonymous = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id == null) {
                anonymous.add(entry);
                continue;
            }
            Entry before = byId.get(entry.id);
            if (before == null || (before.position < 0 && entry.position >= 0)) byId.put(entry.id, entry);
        }
        List<Entry> all = new ArrayList<>(byId.values());
        all.addAll(anonymous);
        Collections.sort(all, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(a.millis, b.millis);
            }
        });
        return all;
    }

    private static Entry entry(int position, Object message, Reader reader) {
        return new Entry(position, safe(reader, 0, message), safe(reader, 1, message), safe(reader, 2, message),
                safe(reader, 4, message), millis(safe(reader, 3, message)), viewer(reader, message));
    }

    private static boolean viewer(Reader reader, Object message) {
        try {
            return reader.sentByViewer(message);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safe(Reader reader, int which, Object message) {
        try {
            switch (which) {
                case 0: return reader.id(message);
                case 1: return reader.sender(message);
                case 2: return reader.text(message);
                case 3: return reader.time(message);
                default: return reader.kind(message);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The adapter's item list: the one Instagram's list adapter keeps with as many items as it counts. */
    private static List<?> rowsOf(Object adapter, int count) {
        if (adapter == null || count <= 0) return null;
        try {
            Method current = adapter.getClass().getMethod("getCurrentList");
            Object list = current.invoke(adapter);
            if (list instanceof List && ((List<?>) list).size() == count) return (List<?>) list;
        } catch (Throwable ignored) {
            // Not a ListAdapter.
        }
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{adapter, 0});
        int looked = 0;
        while (!queue.isEmpty() && looked++ < 2000) {
            Object[] next = queue.poll();
            Object object = next[0];
            int depth = (Integer) next[1];
            if (object == null || seen.put(object, Boolean.TRUE) != null) continue;
            if (object instanceof List) {
                if (((List<?>) object).size() == count) return (List<?>) object;
                continue;
            }
            if (depth >= 3 || skipped(object.getClass())) continue;
            for (Object value : fieldValues(object)) {
                if (value != null) queue.add(new Object[]{value, depth + 1});
            }
        }
        return null;
    }

    /** The messages at or below [root], in the order the walk meets them. */
    static List<Object> walk(Object root, int depth, Reader reader, Map<Object, Boolean> seen, int[] budget) {
        List<Object> messages = new ArrayList<>();
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, 0});
        while (!stack.isEmpty() && budget[0] > 0) {
            Object[] next = stack.pop();
            Object object = next[0];
            int level = (Integer) next[1];
            if (object == null || seen.put(object, Boolean.TRUE) != null) continue;
            budget[0]--;
            boolean message;
            try {
                message = reader.isMessage(object);
            } catch (Throwable failure) {
                message = false;
            }
            if (message) {
                messages.add(object);
                continue;
            }
            if (level >= depth) continue;
            List<Object> below = new ArrayList<>();
            if (object instanceof Iterable) {
                int taken = 0;
                for (Object element : (Iterable<?>) object) {
                    if (taken++ >= MAX_ELEMENTS) break;
                    below.add(element);
                }
            } else if (object instanceof Map) {
                int taken = 0;
                for (Object element : ((Map<?, ?>) object).values()) {
                    if (taken++ >= MAX_ELEMENTS) break;
                    below.add(element);
                }
            } else if (object instanceof Object[]) {
                Object[] array = (Object[]) object;
                for (int i = 0; i < array.length && i < MAX_ELEMENTS; i++) below.add(array[i]);
            } else if (object instanceof AtomicReference) {
                below.add(((AtomicReference<?>) object).get());
            } else if (!skipped(object.getClass())) {
                below.addAll(fieldValues(object));
            }
            // Pushed backwards so the first element is looked at first.
            for (int i = below.size() - 1; i >= 0; i--) {
                Object element = below.get(i);
                if (element != null) stack.push(new Object[]{element, level + 1});
            }
        }
        return messages;
    }

    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    private static List<Object> fieldValues(Object object) {
        List<Object> values = new ArrayList<>();
        Class<?> type = object.getClass();
        Field[] fields;
        synchronized (FIELDS) {
            fields = FIELDS.get(type);
            if (fields == null) {
                List<Field> list = new ArrayList<>();
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    if (skipped(c)) break;
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        if (skipped(field.getType()) && !Iterable.class.isAssignableFrom(field.getType())
                                && !Map.class.isAssignableFrom(field.getType()) && field.getType() != Object.class
                                && !field.getType().isArray() && field.getType() != AtomicReference.class) {
                            continue;
                        }
                        try {
                            field.setAccessible(true);
                            list.add(field);
                        } catch (Throwable ignored) {
                            // Not readable.
                        }
                    }
                }
                fields = list.toArray(new Field[0]);
                FIELDS.put(type, fields);
            }
        }
        for (Field field : fields) {
            try {
                values.add(field.get(object));
            } catch (Throwable ignored) {
                // Skipped.
            }
        }
        return values;
    }

    /** Classes a walk never reads the fields of: the platform's, Kotlin's and strings and boxes. */
    static boolean skipped(Class<?> type) {
        String name = type.getName();
        return type.isEnum() || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin.") || name.startsWith("kotlinx.")
                || name.startsWith("dalvik.") || name.startsWith("libcore.") || name.startsWith("sun.");
    }

    /** A time Instagram stores as text: microseconds, milliseconds or seconds since 1970. 0 when it isn't one. */
    static long millis(String stored) {
        if (stored == null) return 0;
        String digits = stored.trim();
        if (digits.isEmpty() || digits.length() > 18) return 0;
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException notNumber) {
            return 0;
        }
        if (value <= 0) return 0;
        if (value >= 100_000_000_000_000L) return value / 1000;
        if (value >= 100_000_000_000L) return value;
        return value * 1000;
    }

    /**
     * The row to scroll to for the first message at or after [target], among entries with a known
     * row. The newest row when every message is older, the oldest when every one is newer, -1
     * when no entry has a row.
     */
    static int positionFor(List<Entry> entries, long target) {
        Entry first = null;
        Entry newest = null;
        Entry oldest = null;
        for (Entry entry : entries) {
            if (entry.position < 0 || entry.millis <= 0) continue;
            if (oldest == null || entry.millis < oldest.millis) oldest = entry;
            if (newest == null || entry.millis > newest.millis) newest = entry;
            if (entry.millis >= target && (first == null || entry.millis < first.millis)) first = entry;
        }
        if (first != null) return first.position;
        if (newest != null && target > newest.millis) return newest.position;
        return oldest == null ? -1 : oldest.position;
    }

    /** The oldest message time among entries, 0 when none has one. */
    static long oldestMillis(List<Entry> entries) {
        long oldest = 0;
        for (Entry entry : entries) {
            if (entry.millis > 0 && (oldest == 0 || entry.millis < oldest)) oldest = entry.millis;
        }
        return oldest;
    }

    static String stamp(long millis) {
        if (millis <= 0) return "????-??-?? ??:??";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    /** What stands for a message with no text: its type in square brackets. */
    static String placeholder(String kind) {
        if (kind == null) return "[message]";
        String upper = kind.toUpperCase(Locale.US);
        if (upper.equals("MEDIA") || upper.equals("EXPIRING_MEDIA") || upper.equals("RAVEN_MEDIA")) {
            return upper.equals("MEDIA") ? "[photo or video]" : "[photo or video, view once]";
        }
        if (upper.contains("VOICE")) return "[voice message]";
        if (upper.contains("VIDEO")) return "[video]";
        if (upper.equals("LIKE")) return "[like]";
        if (upper.contains("STICKER")) return "[sticker]";
        if (upper.contains("ANIMATED")) return "[GIF]";
        return "[" + upper.toLowerCase(Locale.US).replace('_', ' ') + "]";
    }

    /**
     * One message as a line: "2026-10-30 14:05 Name: text". A message with no text shows its type,
     * and a link's text is the link. Later lines of a longer message are indented two spaces.
     */
    static String line(Entry entry, String name) {
        String text = entry.text == null ? "" : entry.text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) text = placeholder(entry.kind);
        else if (entry.kind != null && entry.kind.toUpperCase(Locale.US).contains("MEDIA") && !entry.kind.equalsIgnoreCase("MEDIA_SHARE")) {
            text = placeholder(entry.kind) + " " + text;
        }
        return stamp(entry.millis) + " " + name + ": " + text.replace("\n", "\n  ");
    }

    /**
     * The export of a chat: a header and one line per message, oldest first. The signed-in account's
     * messages say [you]. The other side says [title] when only one other account wrote, and their
     * account id when several did (a group).
     */
    static String document(List<Entry> entries, String title, String you) {
        String viewerId = null;
        for (Entry entry : entries) {
            if (entry.viewer && entry.sender != null) {
                viewerId = entry.sender;
                break;
            }
        }
        Set<String> others = new java.util.LinkedHashSet<>();
        for (Entry entry : entries) {
            boolean mine = entry.viewer || (viewerId != null && viewerId.equals(entry.sender));
            if (!mine && entry.sender != null) others.add(entry.sender);
        }
        String other = title == null || title.trim().isEmpty() ? "Them" : title.trim();
        StringBuilder out = new StringBuilder();
        out.append("Chat with ").append(other).append('\n');
        out.append(entries.size()).append(entries.size() == 1 ? " message" : " messages").append("\n\n");
        for (Entry entry : entries) {
            boolean mine = entry.viewer || (viewerId != null && viewerId.equals(entry.sender));
            String name;
            if (mine) name = you;
            else if (others.size() > 1 && entry.sender != null) name = entry.sender;
            else name = other;
            out.append(line(entry, name)).append('\n');
        }
        return out.toString();
    }
}
