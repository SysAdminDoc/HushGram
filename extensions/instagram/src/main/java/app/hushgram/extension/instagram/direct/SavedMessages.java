/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Save deleted messages" patch.
 *
 * <p>Every text message from somebody else that Instagram reads, as a thread loads and as one
 * arrives, is kept in a database of its own in Instagram's private storage: its id, the thread, who
 * sent it, when, and the text. When Instagram later hides a message by its id, which is what an
 * unsend does to the copy on this phone, the saved one is marked deleted, and the list of those is
 * what the settings screen shows. Nothing leaves the phone, and only text is kept.
 *
 * <p>The patch hands over each message object with a layout naming Instagram's obfuscated fields,
 * found at patch time: {@code i} the message id, {@code u} the sender, {@code t} the time, {@code x}
 * the text, {@code c} the content object real-time messages carry their text in, {@code h} whether
 * it's hidden, {@code m} whether you sent it, {@code k} the thread key and {@code d} the field of a
 * thread key that holds the thread's id. Everything here fails
 * quietly: no message is lost or changed, and nothing is saved while the switch is off.
 */
public final class SavedMessages {
    /** How many messages that haven't been deleted are kept. The oldest go first. */
    static final int KEEP = 3000;

    private static final String DATABASE = "hushgram_saved_messages.db";
    private static final String TABLE = "message";

    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Map<String, Map<String, String>> LAYOUTS = new HashMap<>();
    private static volatile Store store;

    private SavedMessages() {
    }

    /** One message that was deleted after this phone saw it. */
    public static final class Entry {
        public final String id;
        public final String thread;
        public final String sender;
        public final String body;
        public final long sentAt;
        public final long deletedAt;

        Entry(String id, String thread, String sender, String body, long sentAt, long deletedAt) {
            this.id = id;
            this.thread = thread;
            this.sender = sender;
            this.body = body;
            this.sentAt = sentAt;
            this.deletedAt = deletedAt;
        }
    }

    /** Handed each message Instagram has read. Never throws, and does nothing while the switch is off. */
    public static void parsed(@Nullable Object message, @Nullable String layout) {
        try {
            if (message == null || layout == null || !on()) return;
            HookStatus.invoked(FamilyNames.DELETED_MESSAGES);
            Map<String, String> names = layout(layout);
            String id = text(read(message, names.get("i")));
            if (id == null || id.isEmpty()) return;
            if (Boolean.TRUE.equals(read(message, names.get("m")))) return;
            if (Boolean.TRUE.equals(read(message, names.get("h")))) {
                hidden(id, null);
                return;
            }
            String body = text(read(message, names.get("x")));
            if (body == null || body.isEmpty()) body = text(read(message, names.get("c")));
            if (body == null || body.isEmpty()) return;
            String sender = text(read(message, names.get("u")));
            long sentAt = micros(text(read(message, names.get("t"))));
            String thread = text(read(read(message, names.get("k")), names.get("d")));
            final String savedBody = body;
            Utils.runOnBackgroundThread(() -> save(id, thread, sender, savedBody, sentAt));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DELETED_MESSAGES, "message", failure);
        }
    }

    /** Handed the id Instagram is about to hide a message by. Marks a saved one deleted. */
    public static void hidden(@Nullable String serverId, @Nullable String clientContext) {
        try {
            if (serverId == null || serverId.isEmpty() || !on()) return;
            HookStatus.invoked(FamilyNames.DELETED_MESSAGES);
            Utils.runOnBackgroundThread(() -> markDeleted(serverId));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DELETED_MESSAGES, "hide", failure);
        }
    }

    /** The deleted messages, newest deletion first. Call off the main thread. */
    public static List<Entry> deleted() {
        List<Entry> found = new ArrayList<>();
        try (Cursor cursor = database().query(TABLE, null, "deleted_at IS NOT NULL", null, null, null,
                "deleted_at DESC", "500")) {
            while (cursor.moveToNext()) {
                found.add(new Entry(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("thread")),
                        cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                        cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("sent_at")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at"))));
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Saved messages: could not read the deleted ones", failure);
        }
        return found;
    }

    /** How many messages are saved and how many of them were deleted: {saved, deleted}. Off the main thread. */
    public static int[] counts() {
        int[] counts = new int[2];
        try {
            SQLiteDatabase db = database();
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*), COUNT(deleted_at) FROM " + TABLE, null)) {
                if (cursor.moveToFirst()) {
                    counts[0] = cursor.getInt(0);
                    counts[1] = cursor.getInt(1);
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Saved messages: could not count", failure);
        }
        return counts;
    }

    /** Forgets every saved message. Off the main thread. */
    public static void clear() {
        try {
            database().delete(TABLE, null, null);
        } catch (Throwable failure) {
            Logger.printException(() -> "Saved messages: could not clear", failure);
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SAVE_DELETED_MESSAGES.get();
    }

    private static void save(String id, @Nullable String thread, @Nullable String sender, String body, long sentAt) {
        try {
            SQLiteDatabase db = database();
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("thread", thread);
            values.put("sender", sender);
            values.put("body", body);
            values.put("sent_at", sentAt);
            values.put("saved_at", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("DELETE FROM " + TABLE + " WHERE deleted_at IS NULL AND id NOT IN (SELECT id FROM " + TABLE
                    + " WHERE deleted_at IS NULL ORDER BY saved_at DESC LIMIT " + KEEP + ")");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DELETED_MESSAGES, "save", failure);
        }
    }

    private static void markDeleted(String id) {
        try {
            ContentValues values = new ContentValues();
            values.put("deleted_at", System.currentTimeMillis());
            database().update(TABLE, values, "id = ? AND deleted_at IS NULL", new String[]{id});
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DELETED_MESSAGES, "mark", failure);
        }
    }

    private static SQLiteDatabase database() {
        Store open = store;
        if (open == null) {
            synchronized (SavedMessages.class) {
                if (store == null) store = new Store(Utils.getContext());
                open = store;
            }
        }
        return open.getWritableDatabase();
    }

    private static final class Store extends SQLiteOpenHelper {
        Store(Context context) {
            super(context, DATABASE, null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (id TEXT PRIMARY KEY, thread TEXT, sender TEXT, body TEXT, "
                    + "sent_at INTEGER, saved_at INTEGER, deleted_at INTEGER)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        }
    }

    /** {"i" -> "A0x", ...} from "i=A0x;u=A1H;...", read once per layout. */
    static Map<String, String> layout(String layout) {
        synchronized (LAYOUTS) {
            Map<String, String> found = LAYOUTS.get(layout);
            if (found == null) {
                found = new HashMap<>();
                for (String part : layout.split(";")) {
                    int equals = part.indexOf('=');
                    if (equals > 0) found.put(part.substring(0, equals), part.substring(equals + 1));
                }
                LAYOUTS.put(layout, found);
            }
            return found;
        }
    }

    /** The value of the field called [name] on [target], looking up through its superclasses, or null. */
    @Nullable
    static Object read(@Nullable Object target, @Nullable String name) {
        if (target == null || name == null || name.isEmpty()) return null;
        try {
            Class<?> type = target.getClass();
            String key = type.getName() + "#" + name;
            Field field;
            synchronized (FIELDS) {
                field = FIELDS.get(key);
                if (field == null && !FIELDS.containsKey(key)) {
                    for (Class<?> at = type; at != null && field == null; at = at.getSuperclass()) {
                        try {
                            field = at.getDeclaredField(name);
                            field.setAccessible(true);
                        } catch (NoSuchFieldException missing) {
                            field = null;
                        }
                    }
                    FIELDS.put(key, field);
                }
            }
            return field == null ? null : field.get(target);
        } catch (Throwable failure) {
            return null;
        }
    }

    @Nullable
    static String text(@Nullable Object value) {
        return value instanceof CharSequence ? value.toString() : null;
    }

    /** Instagram writes a message's time in microseconds since 1970, as text. */
    static long micros(@Nullable String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value) / 1000L;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
