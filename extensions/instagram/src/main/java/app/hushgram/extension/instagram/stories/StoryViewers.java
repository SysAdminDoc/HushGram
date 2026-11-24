/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Story viewers (#710): while the switch is on, each time Instagram shows you who viewed one of
 * your stories, the list is kept on this phone, so it can be looked back on after Instagram drops
 * it, and set beside your followers: who viewed each story, who has no view recorded, and who
 * views most of them.
 *
 * <p>It keeps only what Instagram already sent for its own viewer list, as you open it, so nothing
 * is asked for and nothing is sent. A list Instagram hands over in pages is put back together. At
 * most {@link #MAX_STORIES} stories and {@link #MAX_VIEWERS} viewers each are kept, in one file in
 * the app's own storage, which the settings backup leaves out.
 */
public final class StoryViewers {
    /** One of your stories and the viewers Instagram listed for it. */
    public static final class Story {
        public final String id;
        /** When it was posted, or when its viewers were first kept if Instagram didn't say. */
        public final long posted;
        /** How many viewers Instagram counted, which can be more than it listed. */
        public final int total;
        /** The viewers' ids, in the order Instagram listed them. */
        public final List<String> viewers;

        Story(String id, long posted, int total, List<String> viewers) {
            this.id = id;
            this.posted = posted;
            this.total = total;
            this.viewers = viewers;
        }

        /** Whether every viewer Instagram counted is listed, so anyone else didn't view it. */
        public boolean complete() {
            return !viewers.isEmpty() && viewers.size() >= total;
        }
    }

    /** Someone and the stories they viewed in a period. */
    public static final class Person {
        public final String id;
        /** Their username when it was last known, empty when Instagram never gave one. */
        public final String username;
        public final int views;
        /** When the newest story they viewed was posted, 0 for none. */
        public final long last;

        Person(String id, String username, int views, long last) {
            this.id = id;
            this.username = username;
            this.views = views;
            this.last = last;
        }
    }

    /** What the kept lists say about one period. */
    public static final class Summary {
        /** When the first list was kept, 0 before any. */
        public final long since;
        /** The period's start, 0 for everything kept. */
        public final long from;
        /** The period's stories, the newest first. */
        public final List<Story> stories;
        /** Everyone who viewed one of them, the most stories first. */
        public final List<Person> viewers;
        /** Followers with no view recorded in the period, by username. Empty when followers aren't known. */
        public final List<Person> unseen;
        /** How many followers the newest followers list has, 0 when there's none. */
        public final int followers;

        Summary(long since, long from, List<Story> stories, List<Person> viewers, List<Person> unseen, int followers) {
            this.since = since;
            this.from = from;
            this.stories = stories;
            this.viewers = viewers;
            this.unseen = unseen;
            this.followers = followers;
        }

        /** Whether [person] viewed at least half the period's stories, and two or more. */
        public boolean frequent(Person person) {
            return person.views >= 2 && person.views * 2 >= stories.size();
        }

        /** The viewers who are {@link #frequent}, in the same order. */
        public List<Person> frequentViewers() {
            List<Person> kept = new ArrayList<>();
            for (Person person : viewers) if (frequent(person)) kept.add(person);
            return kept;
        }

        /** Followers who aren't on [story]'s list, by username, or empty when its list isn't whole. */
        public List<Person> missed(Story story, Map<String, String> followers) {
            if (!story.complete() || followers.isEmpty()) return Collections.emptyList();
            Set<String> viewed = new HashSet<>(story.viewers);
            List<Person> kept = new ArrayList<>();
            for (Map.Entry<String, String> follower : followers.entrySet()) {
                if (!viewed.contains(follower.getKey())) kept.add(new Person(follower.getKey(), follower.getValue(), 0, 0));
            }
            Collections.sort(kept, StoryViewers::byName);
            return kept;
        }

        /** The share of followers who viewed at least one story in the period, 0 to 100, or -1 unknown. */
        public int reach() {
            if (followers <= 0) return -1;
            return Math.min(100, Math.round(100f * (followers - unseen.size()) / followers));
        }
    }

    /** One page of viewers as Instagram handed it over. */
    static final class Page {
        final String story;
        final long posted;
        final int total;
        /** Viewer id to username, in Instagram's order. */
        final LinkedHashMap<String, String> viewers;

        Page(String story, long posted, int total, LinkedHashMap<String, String> viewers) {
            this.story = story;
            this.posted = posted;
            this.total = total;
            this.viewers = viewers;
        }
    }

    /** Reads Instagram's viewer list and the accounts on it. The patched bridges below; tests swap it. */
    interface Reads {
        @Nullable String id(Object user);

        @Nullable String username(Object user);

        /** The story the list is of, when Instagram sent it along. */
        @Nullable Object media(Object response);

        /** How many viewers Instagram counted. */
        int total(Object response);

        /** The viewers on this page of the list. */
        @Nullable List<?> viewers(Object response);

        /** A viewer's account. */
        @Nullable Object user(Object viewer);

        /** The story id the request asked about. */
        @Nullable String story(Object callback);

        @Nullable String mediaId(Object media);

        /** When the story was posted, in seconds. */
        @Nullable Long takenAt(Object media);
    }

    static final Reads PATCHED = new Reads() {
        @Override public String id(Object user) {
            return userId(user);
        }

        @Override public String username(Object user) {
            return StoryViewers.username(user);
        }

        @Override public Object media(Object response) {
            return listMedia(response);
        }

        @Override public int total(Object response) {
            return listTotal(response);
        }

        @Override public List<?> viewers(Object response) {
            return listViewers(response);
        }

        @Override public Object user(Object viewer) {
            return viewerUser(viewer);
        }

        @Override public String story(Object callback) {
            return listStory(callback);
        }

        @Override public String mediaId(Object media) {
            return StoryViewers.mediaId(media);
        }

        @Override public Long takenAt(Object media) {
            return StoryViewers.takenAt(media);
        }
    };

    static Reads reads = PATCHED;

    static final int MAX_STORIES = 300;
    static final int MAX_VIEWERS = 5000;

    public static final long DAY = 24L * 60 * 60 * 1000;

    static final String FAMILY = FamilyNames.STORY_VIEWERS;
    static final String STEP = "story viewers";
    static final String NOT_KEPT = "story viewers not kept, no worker free";

    private static final String DIRECTORY = "hushgram-story-viewers";
    private static final String FILE = "viewers.json";

    private static final Object LOCK = new Object();
    /** By id, the story kept last at the end. */
    private static final LinkedHashMap<String, Kept> STORIES = new LinkedHashMap<>();
    /** Every listed viewer's username, by id. */
    private static final Map<String, String> NAMES = new HashMap<>();
    private static long since;
    private static File loadedFrom;
    /** The story Instagram listed viewers of last in this process, null before any. */
    @Nullable private static String lastKept;

    private static final class Kept {
        final String id;
        long posted;
        int total;
        final LinkedHashSet<String> viewers = new LinkedHashSet<>();

        Kept(String id) {
            this.id = id;
        }
    }

    private StoryViewers() {
    }

    /**
     * Answers a String: [user]'s id. The patch writes the body at patch time, and a build without it
     * answers null, so nothing is kept.
     */
    public static String userId(Object user) {
        return null;
    }

    /**
     * Answers a String: [user]'s username. The patch writes the body at patch time, and a build
     * without it answers null, so the viewer is kept without one.
     */
    public static String username(Object user) {
        return null;
    }

    /** Answers an Object: the story Instagram's viewer list [response] came with. Written by the patch. */
    public static Object listMedia(Object response) {
        return null;
    }

    /** Answers an int: how many viewers [response] says the story has. Written by the patch. */
    public static int listTotal(Object response) {
        return 0;
    }

    /** Answers a List: the viewers on this page of [response]. Written by the patch. */
    public static List<?> listViewers(Object response) {
        return null;
    }

    /** Answers an Object: the account of one viewer on that list. Written by the patch. */
    public static Object viewerUser(Object viewer) {
        return null;
    }

    /** Answers a String: the story id the viewer list request [callback] asked about. Written by the patch. */
    public static String listStory(Object callback) {
        return null;
    }

    /** Answers a String: [media]'s id. Written by the patch. */
    public static String mediaId(Object media) {
        return null;
    }

    /** Answers a Long: when [media] was posted, in seconds. Written by the patch. */
    public static Long takenAt(Object media) {
        return null;
    }

    /**
     * The hook, at the top of the viewer list request's answer: Instagram listed some of the viewers
     * of one of your stories in [response], for the request [callback]. Reads the story, its viewers
     * and Instagram's count through the bridges and hands them to {@link #received}. Changes nothing
     * of Instagram's and never throws.
     */
    public static void listed(@Nullable Object callback, @Nullable Object response) {
        try {
            if (response == null || !on()) return;
            Reads read = reads;
            List<?> viewers = read.viewers(response);
            if (viewers == null || viewers.isEmpty()) return;
            Object media = read.media(response);
            String story = media == null ? null : read.mediaId(media);
            if (digits(story).isEmpty() && callback != null) story = read.story(callback);
            Long taken = media == null ? null : read.takenAt(media);
            List<Object> users = new ArrayList<>(viewers.size());
            for (Object viewer : viewers) {
                Object user = viewer == null ? null : read.user(viewer);
                if (user != null) users.add(user);
            }
            received(story, taken == null ? 0 : taken, read.total(response), users);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STEP, failure);
        }
    }

    /** Whether the switch is on. Off until the settings are ready. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.STORY_VIEWERS.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Instagram listed [users], some or all of the viewers of your story [story], posted at
     * [postedSeconds] (0 when unknown), out of the [total] it counted. Reads them here, on
     * Instagram's thread, and keeps them off it, since the first keep reads a file. Does nothing
     * while the switch is off. Never throws.
     */
    public static void received(@Nullable Object story, long postedSeconds, int total, @Nullable List<?> users) {
        try {
            if (story == null || users == null || users.isEmpty() || !on()) return;
            HookStatus.invoked(FAMILY);
            Page page = read(String.valueOf(story), postedSeconds, total, users);
            if (page == null) return;
            HookStatus.bound(FAMILY, STEP);
            Context context = Utils.getContext();
            if (context == null) return;
            long now = System.currentTimeMillis();
            if (!Utils.runOnBackgroundThread(() -> keep(context, page, now))) HookStatus.counted(FAMILY, NOT_KEPT);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STEP, failure);
        }
    }

    /** The viewers in [users] Instagram gave an id for, or null when none. */
    @Nullable
    static Page read(String story, long postedSeconds, int total, List<?> users) {
        String id = digits(story);
        if (id.isEmpty()) return null;
        LinkedHashMap<String, String> viewers = new LinkedHashMap<>();
        for (Object user : users) {
            if (user == null) continue;
            String viewer = digits(reads.id(user));
            if (viewer.isEmpty()) continue;
            String name = reads.username(user);
            viewers.put(viewer, name == null ? "" : name.trim());
        }
        if (viewers.isEmpty()) return null;
        return new Page(id, postedSeconds > 0 ? postedSeconds * 1000 : 0, Math.max(total, 0), viewers);
    }

    /**
     * A story's id as Instagram writes it, which for a media id can be followed by an underscore and
     * its owner's id: the part before that, when it's all digits, or empty.
     */
    static String digits(@Nullable String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int owner = trimmed.indexOf('_');
        if (owner >= 0) trimmed = trimmed.substring(0, owner);
        if (trimmed.isEmpty() || trimmed.length() > 30) return "";
        for (int at = 0; at < trimmed.length(); at++) if (!Character.isDigit(trimmed.charAt(at))) return "";
        return trimmed;
    }

    /**
     * Adds [page] to what's kept for its story at [now]: viewers it didn't have yet go after the
     * ones it did, so pages put back together read in Instagram's order. Blocks on the file: call
     * it on a worker thread.
     */
    static void keep(Context context, Page page, long now) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            lastKept = page.story;
            boolean changed = false;
            Kept story = STORIES.get(page.story);
            if (story == null) {
                story = new Kept(page.story);
                story.posted = page.posted > 0 ? Math.min(page.posted, now) : now;
                STORIES.put(page.story, story);
                changed = true;
            } else if (page.posted > 0 && page.posted < story.posted) {
                story.posted = page.posted;
                changed = true;
            }
            if (page.total > story.total) {
                story.total = page.total;
                changed = true;
            }
            for (Map.Entry<String, String> viewer : page.viewers.entrySet()) {
                if (story.viewers.size() >= MAX_VIEWERS) break;
                changed |= story.viewers.add(viewer.getKey());
                String name = viewer.getValue();
                if (!name.isEmpty() && !name.equals(NAMES.get(viewer.getKey()))) {
                    NAMES.put(viewer.getKey(), name);
                    changed = true;
                }
            }
            if (since == 0 || since > now) {
                since = now;
                changed = true;
            }
            if (!changed) return;
            while (STORIES.size() > MAX_STORIES) STORIES.remove(oldest());
            save(application);
        }
    }

    /** The id of the story posted first. */
    private static String oldest() {
        Kept first = null;
        for (Kept story : STORIES.values()) if (first == null || story.posted < first.posted) first = story;
        return first.id;
    }

    /**
     * What's kept for stories posted at [from] or later (0 for all of them), set beside [followers],
     * id to username, the newest followers list, or empty when there's none.
     */
    public static Summary summary(Context context, long from, Map<String, String> followers) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<Story> stories = new ArrayList<>();
        Map<String, String> names;
        long started;
        synchronized (LOCK) {
            load(application);
            for (Kept kept : STORIES.values()) {
                if (kept.posted < from) continue;
                stories.add(new Story(kept.id, kept.posted, Math.max(kept.total, kept.viewers.size()),
                        Collections.unmodifiableList(new ArrayList<>(kept.viewers))));
            }
            names = new HashMap<>(NAMES);
            started = since;
        }
        Collections.sort(stories, (a, b) -> Long.compare(b.posted, a.posted));
        Map<String, int[]> views = new LinkedHashMap<>();
        Map<String, Long> last = new HashMap<>();
        for (Story story : stories) {
            for (String viewer : story.viewers) {
                int[] count = views.get(viewer);
                if (count == null) views.put(viewer, count = new int[1]);
                count[0]++;
                Long newest = last.get(viewer);
                if (newest == null || newest < story.posted) last.put(viewer, story.posted);
            }
        }
        List<Person> viewers = new ArrayList<>();
        for (Map.Entry<String, int[]> viewer : views.entrySet()) {
            String id = viewer.getKey();
            String name = followers.containsKey(id) && !followers.get(id).isEmpty() ? followers.get(id) : names.get(id);
            viewers.add(new Person(id, name == null ? "" : name, viewer.getValue()[0], last.get(id)));
        }
        Collections.sort(viewers, (a, b) -> a.views != b.views ? Integer.compare(b.views, a.views) : byName(a, b));
        List<Person> unseen = new ArrayList<>();
        for (Map.Entry<String, String> follower : followers.entrySet()) {
            if (!views.containsKey(follower.getKey())) unseen.add(new Person(follower.getKey(), follower.getValue(), 0, 0));
        }
        Collections.sort(unseen, StoryViewers::byName);
        return new Summary(started, from, stories, viewers, unseen, followers.size());
    }

    private static int byName(Person a, Person b) {
        int byUsername = a.username.toLowerCase(Locale.ROOT).compareTo(b.username.toLowerCase(Locale.ROOT));
        return byUsername != 0 ? byUsername : a.id.compareTo(b.id);
    }

    /**
     * Which kept story a viewer list showing [usernames] is of (#833): the one whose kept viewers
     * hold most of them, on a tie the one Instagram listed viewers of last, then the newest. With
     * none of them kept anywhere, the one listed last, or null when that isn't kept either.
     */
    @Nullable
    public static String storyListing(Context context, List<String> usernames) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Set<String> shown = new HashSet<>();
        for (String name : usernames) {
            if (name != null && !name.trim().isEmpty()) shown.add(name.trim().toLowerCase(Locale.ROOT));
        }
        synchronized (LOCK) {
            load(application);
            Kept best = null;
            int most = 0;
            for (Kept story : STORIES.values()) {
                int matches = 0;
                for (String viewer : story.viewers) {
                    String name = NAMES.get(viewer);
                    if (name != null && shown.contains(name.toLowerCase(Locale.ROOT))) matches++;
                }
                if (matches == 0) continue;
                boolean better = best == null || matches > most
                        || (matches == most && !best.id.equals(lastKept)
                                && (story.id.equals(lastKept) || story.posted > best.posted));
                if (better) {
                    best = story;
                    most = matches;
                }
            }
            if (best != null) return best.id;
            return lastKept != null && STORIES.containsKey(lastKept) ? lastKept : null;
        }
    }

    /** How many stories are kept. */
    public static int count(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            return STORIES.size();
        }
    }

    /** Forgets every story and deletes the file. Answers whether it's gone. */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                File file = file(application);
                STORIES.clear();
                NAMES.clear();
                since = 0;
                lastKept = null;
                loadedFrom = file;
                File work = new File(file.getParentFile(), FILE + ".tmp");
                if (work.exists() && !work.delete()) return false;
                return !file.exists() || file.delete();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "story viewers clear", failure);
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
        STORIES.clear();
        NAMES.clear();
        since = 0;
        try {
            if (!file.isFile()) return;
            JSONObject read = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            since = Math.max(0, read.optLong("since"));
            JSONObject names = read.optJSONObject("names");
            if (names != null) {
                for (java.util.Iterator<String> ids = names.keys(); ids.hasNext(); ) {
                    String id = ids.next();
                    String name = names.optString(id);
                    if (!digits(id).isEmpty() && !name.isEmpty()) NAMES.put(id, name);
                }
            }
            JSONArray stories = read.optJSONArray("stories");
            for (int s = 0; stories != null && s < stories.length() && STORIES.size() < MAX_STORIES; s++) {
                JSONObject one = stories.optJSONObject(s);
                String id = one == null ? "" : digits(one.optString("id"));
                if (id.isEmpty() || one.optLong("posted") <= 0) continue;
                Kept story = new Kept(id);
                story.posted = one.optLong("posted");
                story.total = Math.max(0, one.optInt("total"));
                JSONArray viewers = one.optJSONArray("viewers");
                for (int v = 0; viewers != null && v < viewers.length() && story.viewers.size() < MAX_VIEWERS; v++) {
                    String viewer = digits(viewers.optString(v));
                    if (!viewer.isEmpty()) story.viewers.add(viewer);
                }
                STORIES.put(id, story);
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "story viewers read", failure);
        }
    }

    private static void save(Context application) {
        try {
            File file = file(application);
            File folder = file.getParentFile();
            if (!folder.mkdirs() && !folder.isDirectory()) return;
            Set<String> listed = new HashSet<>();
            JSONArray stories = new JSONArray();
            for (Kept story : STORIES.values()) {
                JSONArray viewers = new JSONArray();
                for (String viewer : story.viewers) {
                    viewers.put(viewer);
                    listed.add(viewer);
                }
                stories.put(new JSONObject().put("id", story.id).put("posted", story.posted)
                        .put("total", story.total).put("viewers", viewers));
            }
            // Only the names of people still listed somewhere, so a dropped story takes its names along.
            NAMES.keySet().retainAll(listed);
            JSONObject names = new JSONObject();
            for (Map.Entry<String, String> name : NAMES.entrySet()) names.put(name.getKey(), name.getValue());
            JSONObject kept = new JSONObject().put("since", since).put("names", names).put("stories", stories);
            File work = new File(folder, FILE + ".tmp");
            Files.write(work.toPath(), kept.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JSONException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "story viewers write", failure);
        }
    }

    /** Forgets what this process holds, as a new process would. */
    static void forgetForTests() {
        synchronized (LOCK) {
            STORIES.clear();
            NAMES.clear();
            since = 0;
            loadedFrom = null;
            lastKept = null;
        }
        reads = PATCHED;
    }
}
