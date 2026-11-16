/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Online history (#590): while the switch is on, each time Instagram's active status for the
 * people in your messages comes in, a change is noted on this phone: when someone came online, and
 * when they went offline, by the time Instagram gives for their last activity.
 *
 * <p>It reads only the active status Instagram already received for its own Active now dots, from
 * the store every update goes through, so nothing is asked for and nothing is sent. Names come
 * from Instagram's own cache of accounts. At most {@link #MAX_PEOPLE} people and
 * {@link #MAX_EVENTS} changes each are kept, in one small file in the app's own storage, which the
 * settings backup leaves out.
 */
public final class OnlineHistory {
    /** One change: someone came online, or went offline, at a time. */
    public static final class Event {
        public final String id;
        /** Their username when it was last known, empty when Instagram never had it. */
        public final String username;
        public final boolean online;
        public final long time;

        Event(String id, String username, boolean online, long time) {
            this.id = id;
            this.username = username;
            this.online = online;
            this.time = time;
        }
    }

    /** What one update said about one person. */
    static final class Seen {
        final String id;
        final String username;
        final boolean active;
        /** When Instagram says they were last active, 0 when it doesn't say. */
        final long last;

        Seen(String id, String username, boolean active, long last) {
            this.id = id;
            this.username = username;
            this.active = active;
            this.last = last;
        }
    }

    /** One person and the changes noted for them, oldest first. */
    private static final class Person {
        final String id;
        String username = "";
        /** Whether an update was seen for them yet, so the first one isn't taken for a change. */
        boolean known;
        boolean active;
        long last;
        final List<long[]> events = new ArrayList<>();

        Person(String id) {
            this.id = id;
        }
    }

    /** Reads an update. The patched bridges below; tests swap it. */
    interface Reads {
        /** The update's own description, as Instagram writes it, or null. */
        @Nullable String presence(Object state);

        /** The username Instagram's cache has for [id] in [session], or null. */
        @Nullable String username(@Nullable Object session, String id);
    }

    static final Reads PATCHED = new Reads() {
        @Override public String presence(Object state) {
            return OnlineHistory.presence(state);
        }

        @Override public String username(Object session, String id) {
            long number;
            try {
                number = Long.parseLong(id);
            } catch (NumberFormatException notAnId) {
                return null;
            }
            Object user = session == null ? null : OnlineHistory.user(session, number);
            return user == null ? null : InstagramMedia.username(user);
        }
    };

    static Reads reads = PATCHED;

    static final int MAX_PEOPLE = 300;
    static final int MAX_EVENTS = 50;

    static final String FAMILY = FamilyNames.ONLINE_HISTORY;
    static final String STEP = "online history";
    static final String NOT_NOTED = "online history not noted, no worker free";

    private static final String DIRECTORY = "hushgram-online";
    private static final String FILE = "history.json";

    private static final Object LOCK = new Object();
    /** By id, the person changed last at the end. */
    private static final LinkedHashMap<String, Person> PEOPLE = new LinkedHashMap<>(16, 0.75f, true);
    private static File loadedFrom;

    private OnlineHistory() {
    }

    /**
     * Answers a String: [state]'s description, the JSON Instagram's presence model writes of itself.
     * The patch writes the body at patch time, and a build without it answers null, so nothing is noted.
     */
    public static String presence(Object state) {
        return null;
    }

    /**
     * Answers an Object: the account with the id [id] in Instagram's cache of accounts for the
     * signed-in [session], or null. The patch writes the body at patch time.
     */
    public static Object user(Object session, long id) {
        return null;
    }

    /** Whether the switch is on. Off until the settings are ready. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.ONLINE_HISTORY.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Instagram's active status for some people came in, for the signed-in [session], by user id.
     * Reads it here, on Instagram's thread, and notes it off that thread, since the first note reads
     * a file. Does nothing while the switch is off. Never throws.
     */
    public static void received(Object session, Map<?, ?> updates) {
        try {
            if (updates == null || updates.isEmpty() || !on()) return;
            HookStatus.invoked(FAMILY);
            List<Seen> seen = new ArrayList<>();
            for (Map.Entry<?, ?> update : updates.entrySet()) {
                Seen one = read(session, update.getKey(), update.getValue());
                if (one != null) seen.add(one);
            }
            if (seen.isEmpty()) return;
            HookStatus.bound(FAMILY, STEP);
            Context context = Utils.getContext();
            if (context == null) return;
            long now = System.currentTimeMillis();
            if (!Utils.runOnBackgroundThread(() -> record(context, seen, now))) HookStatus.counted(FAMILY, NOT_NOTED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STEP, failure);
        }
    }

    /** What [state], the update for [key], says, or null when it can't be read. */
    @Nullable
    static Seen read(@Nullable Object session, @Nullable Object key, @Nullable Object state) {
        if (state == null) return null;
        String json = reads.presence(state);
        if (json == null || json.isEmpty()) return null;
        JSONObject read;
        try {
            read = new JSONObject(json);
        } catch (JSONException notJson) {
            return null;
        }
        String id = read.optString("user_id").trim();
        if (id.isEmpty() && key != null) id = String.valueOf(key).trim();
        if (id.isEmpty() || !read.has("is_active")) return null;
        String username = reads.username(session, id);
        return new Seen(id, username == null ? "" : username.trim(), read.optBoolean("is_active"),
                Math.max(0, read.optLong("lastActivityAtMs")));
    }

    /**
     * Notes what changed in [seen] at [now], and writes the file when something did. Blocks on the
     * file: call it on a worker thread.
     */
    static void record(Context context, List<Seen> seen, long now) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            boolean changed = false;
            for (Seen one : seen) changed |= note(one, now);
            if (!changed) return;
            while (PEOPLE.size() > MAX_PEOPLE) PEOPLE.remove(PEOPLE.keySet().iterator().next());
            save(application);
        }
    }

    /**
     * Notes [one] at [now]: online when they weren't before, offline at their last activity when
     * they were online before, or when their last activity moved on while they stayed offline, since
     * they came and went between two updates. Their first update is noted as it is: online now, or
     * offline since their last activity when Instagram gives it. Answers whether anything changed.
     */
    private static boolean note(Seen one, long now) {
        Person person = PEOPLE.get(one.id);
        if (person == null) {
            person = new Person(one.id);
            PEOPLE.put(one.id, person);
        }
        boolean changed = false;
        if (!one.username.isEmpty() && !one.username.equals(person.username)) {
            person.username = one.username;
            changed = true;
        }
        long until = one.last > 0 ? Math.min(one.last, now) : now;
        if (one.active) {
            if (!person.known || !person.active) changed |= add(person, now, true);
        } else if (!person.known) {
            if (one.last > 0) changed |= add(person, until, false);
        } else if (person.active || one.last > person.last) {
            changed |= add(person, until, false);
        }
        if (!person.known || person.active != one.active || one.last > person.last) changed = true;
        person.known = true;
        person.active = one.active;
        person.last = Math.max(person.last, one.last);
        return changed;
    }

    /** Adds a change at [time], in time order, dropping the oldest past {@link #MAX_EVENTS}. */
    private static boolean add(Person person, long time, boolean online) {
        int at = person.events.size();
        while (at > 0 && person.events.get(at - 1)[0] > time) at--;
        person.events.add(at, new long[]{time, online ? 1 : 0});
        while (person.events.size() > MAX_EVENTS) person.events.remove(0);
        return true;
    }

    /** Every change kept, newest first. */
    public static List<Event> events(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<Event> found = new ArrayList<>();
        synchronized (LOCK) {
            load(application);
            for (Person person : PEOPLE.values()) {
                for (long[] event : person.events) found.add(new Event(person.id, person.username, event[1] != 0, event[0]));
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(b.time, a.time));
        return found;
    }

    /** Forgets everyone and deletes the file. Answers whether it's gone. */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                File file = file(application);
                PEOPLE.clear();
                loadedFrom = file;
                File work = new File(file.getParentFile(), FILE + ".tmp");
                if (work.exists() && !work.delete()) return false;
                return !file.exists() || file.delete();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "online history clear", failure);
                return false;
            }
        }
    }

    private static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    private static void load(Context application) {
        File file = file(application);
        if (file.equals(loadedFrom)) return;
        loadedFrom = file;
        PEOPLE.clear();
        try {
            if (!file.isFile()) return;
            JSONArray people = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int p = 0; p < people.length() && PEOPLE.size() < MAX_PEOPLE; p++) {
                JSONObject read = people.optJSONObject(p);
                String id = read == null ? "" : read.optString("id");
                if (id.isEmpty()) continue;
                Person person = new Person(id);
                person.username = read.optString("username");
                person.known = true;
                person.active = read.optBoolean("active");
                person.last = read.optLong("last");
                JSONArray events = read.optJSONArray("events");
                for (int e = 0; events != null && e < events.length(); e++) {
                    JSONObject event = events.optJSONObject(e);
                    if (event == null || event.optLong("time") <= 0) continue;
                    add(person, event.optLong("time"), event.optBoolean("online"));
                }
                PEOPLE.put(id, person);
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "online history read", failure);
        }
    }

    private static void save(Context application) {
        try {
            File file = file(application);
            File folder = file.getParentFile();
            if (!folder.mkdirs() && !folder.isDirectory()) return;
            JSONArray people = new JSONArray();
            for (Person person : PEOPLE.values()) {
                JSONArray events = new JSONArray();
                for (long[] event : person.events) {
                    events.put(new JSONObject().put("time", event[0]).put("online", event[1] != 0));
                }
                people.put(new JSONObject().put("id", person.id).put("username", person.username)
                        .put("active", person.active).put("last", person.last).put("events", events));
            }
            File work = new File(folder, FILE + ".tmp");
            Files.write(work.toPath(), people.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JSONException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "online history write", failure);
        }
    }

    /** Forgets what this process holds, as a new process would. */
    static void forgetForTests() {
        synchronized (LOCK) {
            PEOPLE.clear();
            loadedFrom = null;
        }
        reads = PATCHED;
    }
}
