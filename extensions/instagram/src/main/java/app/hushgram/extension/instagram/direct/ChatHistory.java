/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

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

    /** The most objects one read looks at, so a wrong turn into a big graph ends. */
    static final int WALK_BUDGET = 400_000;
    /** The most objects the look below one row takes. A row holds its message a few steps down. */
    static final int ROW_BUDGET = 2_000;
    /** How deep below a row, or below the adapter when its rows can't be told apart, a walk goes. */
    static final int ROW_DEPTH = 6;
    static final int ADAPTER_DEPTH = 8;
    private static final int MAX_ELEMENTS = 50_000;
    /** How far below the adapter its item list is looked for, and how many objects that may take. */
    private static final int LIST_DEPTH = 3;
    private static final int LIST_LOOKS = 2_000;
    private static final String READ = "chat history read";

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
            // A short chat has other lists of its size, so the rows are the first that hold messages.
            for (List<?> rows : rowLists(adapter, count)) {
                found = fromRows(rows, reader);
                if (!found.isEmpty()) break;
            }
            if (found.isEmpty()) {
                int[] budget = {WALK_BUDGET};
                for (Object message : walk(adapter, ADAPTER_DEPTH, reader, budget, null)) {
                    found.add(entry(-1, message, reader));
                }
            }
        } catch (Throwable failure) {
            // What was found so far stands.
            HookStatus.threw(FamilyNames.CHAT_HISTORY, READ, failure);
        }
        return ordered(found);
    }

    /**
     * The messages in [rows], each on the row that holds it most directly. A row can lead to its
     * neighbour's message a few steps down, and it mustn't take that message's place.
     */
    private static List<Entry> fromRows(List<?> rows, Reader reader) {
        Map<Object, int[]> held = new IdentityHashMap<>();
        List<Object> order = new ArrayList<>();
        int left = WALK_BUDGET;
        for (int row = 0; row < rows.size() && left > 0; row++) {
            int[] budget = {ROW_BUDGET};
            int[] level = new int[1];
            List<Object> messages;
            try {
                messages = walk(rows.get(row), ROW_DEPTH, reader, budget, level);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, READ, failure);
                continue;
            }
            left -= ROW_BUDGET - budget[0];
            for (Object message : messages) {
                int[] before = held.get(message);
                if (before == null) order.add(message);
                if (before == null || level[0] < before[1]) held.put(message, new int[]{row, level[0]});
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Object message : order) entries.add(entry(held.get(message)[0], message, reader));
        return entries;
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

    /**
     * The lists that could be the adapter's items, nearest first: those it keeps with as many items
     * as it counts. Instagram's chat keeps its rows in a plain array with room to spare (#246).
     */
    private static List<List<?>> rowLists(Object adapter, int count) {
        List<List<?>> lists = new ArrayList<>();
        if (adapter == null || count <= 0) return lists;
        try {
            Method current = adapter.getClass().getMethod("getCurrentList");
            Object list = current.invoke(adapter);
            if (list instanceof List && ((List<?>) list).size() == count) lists.add((List<?>) list);
        } catch (Throwable ignored) {
            // Not a ListAdapter.
        }
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        // The current list counts as met, so the search below doesn't add it twice.
        if (!lists.isEmpty()) seen.put(lists.get(0), Boolean.TRUE);
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{adapter, 0});
        int looked = 0;
        while (!queue.isEmpty() && looked++ < LIST_LOOKS) {
            Object[] next = queue.poll();
            Object object = next[0];
            int depth = (Integer) next[1];
            if (object == null || seen.put(object, Boolean.TRUE) != null) continue;
            try {
                if (object instanceof List) {
                    if (((List<?>) object).size() == count) lists.add((List<?>) object);
                    continue;
                }
                if (object instanceof Object[]) {
                    if (filledTo((Object[]) object, count)) lists.add(Arrays.asList((Object[]) object).subList(0, count));
                    continue;
                }
            } catch (Throwable ignored) {
                // A list that can't say its size isn't the one.
                continue;
            }
            if (depth >= LIST_DEPTH || skipped(object.getClass()) || fenced(object)) continue;
            for (Object value : fieldValues(object)) {
                if (value != null) queue.add(new Object[]{value, depth + 1});
            }
        }
        return lists;
    }

    /** Whether [array] holds items in exactly its first [count] slots. */
    private static boolean filledTo(Object[] array, int count) {
        if (array.length < count) return false;
        for (int i = 0; i < array.length; i++) {
            if ((array[i] != null) != (i < count)) return false;
        }
        return true;
    }

    /**
     * The messages at or below [root], nearest first. Given [nearest], the walk ends at the first
     * level holding a message and puts that level's number there: what a row shows sits above
     * whatever else its objects lead to.
     */
    static List<Object> walk(Object root, int depth, Reader reader, int[] budget, int[] nearest) {
        List<Object> messages = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        while (!queue.isEmpty() && budget[0] > 0) {
            Object[] next = queue.poll();
            Object object = next[0];
            int level = (Integer) next[1];
            if (nearest != null && !messages.isEmpty() && level > nearest[0]) break;
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
                if (nearest != null) nearest[0] = level;
                continue;
            }
            if (level >= depth || fenced(object)) continue;
            for (Object element : below(object)) {
                if (element != null) queue.add(new Object[]{element, level + 1});
            }
        }
        return messages;
    }

    /** What sits one step below [object]. A collection that fails partway gives what it gave so far. */
    private static List<Object> below(Object object) {
        List<Object> below = new ArrayList<>();
        try {
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
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, READ, failure);
        }
        return below;
    }

    /**
     * Objects a walk stops at. An activity, a view or the signed-in session lead to the whole app,
     * other chats' messages included, and a chat's rows are never behind them.
     */
    private static boolean fenced(Object object) {
        return object instanceof Context || object instanceof View
                || object.getClass().getName().endsWith("UserSession");
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
        if (upper.contains("ACTION_LOG") || upper.contains("NOTICE")) return "[chat notice]";
        if (upper.contains("VIDEO_CALL")) return "[call]";
        if (upper.contains("VIDEO")) return "[video]";
        if (upper.equals("LIKE")) return "[like]";
        if (upper.contains("REACTION")) return "[reaction]";
        if (upper.contains("STICKER")) return "[sticker]";
        if (upper.contains("ANIMATED")) return "[GIF]";
        if (upper.contains("AUDIO")) return "[shared audio]";
        if (upper.contains("CLIPS") || upper.contains("REEL") || upper.contains("FELIX")) return "[shared reel]";
        if (upper.contains("STORY")) return "[shared story]";
        if (upper.contains("MEDIA_SHARE")) return "[shared post]";
        if (upper.contains("PROFILE")) return "[shared profile]";
        if (upper.contains("LOCATION")) return "[shared location]";
        if (upper.contains("LINK")) return "[link]";
        String plain = upper.replace("XMA_", "").replace("_XMA", "");
        return "[" + plain.toLowerCase(Locale.US).replace('_', ' ') + "]";
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
