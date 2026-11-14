/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.PictureFetch;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Sent follow requests" patch (#690): the accounts you've asked to follow that
 * haven't let you in yet, so they can be found again and the request cancelled.
 *
 * <p>Nothing is asked of Instagram for it. Every follow button asks its helper for the account's
 * status each time it shows it, and the patch hands each answer here with the session and the
 * account ({@link #seen}). Requested puts the account on the list, or brings its username, name and
 * picture up to date. Following takes it off, since the request was taken. Follow takes it off too,
 * since the request was cancelled or turned down, but only when the button holds the account's full
 * model, which Instagram keeps up to date: a row built from an older answer could still say Follow
 * for a request sent since. So the list holds the requests a follow button showed while the switch
 * was on, not ones sent before it or on another device until their button shows up.
 *
 * <p>Cancelling stays Instagram's: the page opens the profile, where the Requested button asks
 * before it takes the request back. The list is one file in Instagram's own storage on the phone,
 * written off the main thread, and each account you're signed in to keeps its own requests in it.
 */
public final class SentRequests {
    static final String SEEN = "sent requests seen";
    static final String SAVE = "sent requests save";
    static final String READ = "sent requests read";
    /** What's counted when a change had no worker to write it. */
    static final String NO_WORKER = "sent requests had no worker";

    /** Instagram's follow statuses as they write themselves, and their constants' names. */
    static final String REQUESTED = "FollowStatusRequested";
    static final String REQUESTED_NAME = "FOLLOWSTATUSREQUESTED";
    static final String FOLLOWING = "FollowStatusFollowing";
    static final String FOLLOWING_NAME = "FOLLOWSTATUSFOLLOWING";

    /** The fields of an account's model, as the model looks fields up. */
    static final int ID = "id".hashCode();
    static final int USERNAME = "username".hashCode();
    static final int FULL_NAME = "full_name".hashCode();

    /** The full account model, the one whose status Instagram keeps up to date and whose picture can be read. */
    static final String USER_CLASS = "com.instagram.user.model.User";

    static final String DIRECTORY = "hushgram-requests";
    static final String FILE = "sent.tsv";

    /** How many requests are kept, newest first, across every account. */
    static final int MAX = 1000;

    /** The smallest picture kept for a row, in pixels. */
    static final int PICTURE_PX = 160;

    private static final int OTHER = 0;
    private static final int WAITING = 1;
    private static final int TAKEN = 2;
    private static final int GONE = 3;

    /** Held while the list in memory changes or is copied. */
    private static final Object LOCK = new Object();
    /** Held while the file is read or written, so two writes never cross. */
    private static final Object FILE_LOCK = new Object();

    /** The list by owner and account, newest first, once the file has been read. Changed under {@link #LOCK} only. */
    @Nullable private static LinkedHashMap<String, Request> kept;
    /** Changes seen before the file was read, applied once it is. */
    private static final List<Change> pending = new ArrayList<>();
    private static final AtomicBoolean loading = new AtomicBoolean();
    private static final AtomicBoolean writing = new AtomicBoolean();

    /** The account the last status was seen for, which the page shows. */
    @Nullable static volatile String lastOwner;

    private SentRequests() {
    }

    /** One request: who sent it, the account asked as last seen, and when it was first seen. */
    public static final class Request {
        public final String owner;
        public final String id;
        public final String username;
        public final String name;
        public final String url;
        public final long time;

        Request(String owner, String id, String username, String name, String url, long time) {
            this.owner = owner;
            this.id = id;
            this.username = username;
            this.name = name;
            this.url = url;
            this.time = time;
        }

        String key() {
            return SentRequests.key(owner, id);
        }
    }

    /** What changes the list: a request to keep, or an account to take off. */
    private static final class Change {
        @Nullable final Request put;
        final String key;

        Change(@Nullable Request put, String key) {
            this.put = put;
            this.key = key;
        }
    }

    /** Filled in by the patch: the account model's field tree, or null unpatched. */
    @Nullable
    static Object treeOf(Object model) {
        return null;
    }

    /** Filled in by the patch: the string field [field] of the account's model, or null unpatched. */
    @Nullable
    static String fieldOf(Object model, int field) {
        return null;
    }

    /** Filled in by the patch: the ID of the account the session is signed in to, or null unpatched. */
    @Nullable
    static String ownerOf(Object session) {
        return null;
    }

    /** How the hook reads what it's handed. Tests hand in their own. */
    interface Reads {
        @Nullable String owner(Object session);

        @Nullable Object tree(Object model);

        @Nullable String field(Object model, int field);

        /** Whether [tree], an account model's field tree, is the full account model. */
        boolean user(Object tree);

        /** The address of the picture [user] shows, a full account model. */
        @Nullable String picture(Object user);
    }

    static final Reads PATCHED = new Reads() {
        @Override public String owner(Object session) {
            return ownerOf(session);
        }

        @Override public Object tree(Object model) {
            return treeOf(model);
        }

        @Override public String field(Object model, int field) {
            return fieldOf(model, field);
        }

        @Override public boolean user(Object tree) {
            return USER_CLASS.equals(tree.getClass().getName());
        }

        @Override public String picture(Object user) {
            Object shown = InstagramMedia.profilePicture(user);
            return shown == null ? null : InstagramMedia.candidateUrl(shown);
        }
    };

    /** Runs a task off the main thread, answering whether one took it. Tests run it at once. */
    interface Worker {
        boolean run(Runnable task);
    }

    static volatile Worker worker = Utils::runOnBackgroundThread;

    /** Where the list is kept. Tests hand in a folder of their own. */
    interface Storage {
        @Nullable File file();
    }

    static final Storage STORED = () -> {
        Context context = Utils.getContext();
        if (context == null) return null;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return file(application);
    };

    static volatile Storage storage = STORED;

    static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    /** Reads a picture's address into a bitmap at least [size] pixels across. Tests hand in their own. */
    interface Fetch {
        @Nullable Bitmap fetch(Context application, String url, int size);
    }

    static volatile Fetch fetch = PictureFetch::fetch;

    /**
     * Injected at each return of a follow button's status read, with the session, the account's model
     * and the status it answers. Never throws, and never changes the status.
     */
    public static void seen(Object session, Object model, Object status) {
        seen(session, model, status, PATCHED, SentRequests::switchedOn, System.currentTimeMillis());
    }

    /** Whether the list was changed, or a change was put in hand. */
    static boolean seen(@Nullable Object session, @Nullable Object model, @Nullable Object status, Reads reads,
                        BooleanSupplier on, long now) {
        try {
            HookStatus.invoked(FamilyNames.SENT_FOLLOW_REQUESTS);
            if (session == null || model == null || status == null || !on.getAsBoolean()) return false;
            int kind = kind(status);
            if (kind == OTHER) return false;
            String owner = reads.owner(session);
            if (owner == null || owner.isEmpty()) return false;
            lastOwner = owner;
            String id = reads.field(model, ID);
            if (id == null || id.isEmpty()) return false;
            String key = key(owner, id);
            Object tree = reads.tree(model);
            boolean user = tree != null && reads.user(tree);
            if (kind == WAITING) {
                String username = clean(reads.field(model, USERNAME));
                if (username.isEmpty()) return false;
                String name = clean(reads.field(model, FULL_NAME));
                String url = user ? clean(reads.picture(tree)) : "";
                Request was = known(key);
                if (was != null && was.username.equals(username) && was.name.equals(name)
                        && (url.isEmpty() || url.equals(was.url))) return false;
                long first = was != null ? was.time : now;
                String picture = url.isEmpty() && was != null ? was.url : url;
                return change(new Change(new Request(owner, id, username, name, picture, first), key));
            }
            // Follow from a row built from an older answer could miss a request sent since.
            if (kind == GONE && !user) return false;
            synchronized (LOCK) {
                if (kept != null && !kept.containsKey(key)) return false;
            }
            return change(new Change(null, key));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, SEEN, failure);
            return false;
        }
    }

    /** Which way a status goes: still waiting, taken, gone, or nothing to do with a request. */
    private static int kind(Object status) {
        String written = status.toString();
        String name = status instanceof Enum ? ((Enum<?>) status).name() : "";
        if (REQUESTED.equals(written) || REQUESTED_NAME.equals(name)) return WAITING;
        if (FOLLOWING.equals(written) || FOLLOWING_NAME.equals(name)) return TAKEN;
        if (FollowConfirm.NOT_FOLLOWING.equals(written) || FollowConfirm.NOT_FOLLOWING_NAME.equals(name)) return GONE;
        return OTHER;
    }

    @Nullable
    private static Request known(String key) {
        synchronized (LOCK) {
            return kept == null ? null : kept.get(key);
        }
    }

    /**
     * Makes [change] to the list in memory at once, so changes keep the order they were seen in, and
     * has the file written off the main thread. Before the file is read, the change waits for it.
     */
    private static boolean change(Change change) {
        boolean read;
        synchronized (LOCK) {
            read = kept != null;
            if (read) {
                apply(kept, change);
            } else {
                // A file that can't be read yet doesn't make the wait grow without end.
                if (pending.size() >= MAX) pending.remove(0);
                pending.add(change);
            }
        }
        if (!read) {
            load();
            return true;
        }
        write();
        return true;
    }

    private static void apply(LinkedHashMap<String, Request> list, Change change) {
        Request put = change.put;
        if (put == null) {
            list.remove(change.key);
            return;
        }
        // An account seen again keeps its place, since a map keeps a key where it was put first.
        if (list.containsKey(change.key)) {
            list.put(change.key, put);
            return;
        }
        // The newest first: a new request goes ahead of the rest, and the oldest past the limit go.
        LinkedHashMap<String, Request> before = new LinkedHashMap<>(list);
        list.clear();
        list.put(change.key, put);
        for (Map.Entry<String, Request> each : before.entrySet()) {
            if (list.size() >= MAX) break;
            list.put(each.getKey(), each.getValue());
        }
    }

    /** Reads the file once, off the main thread, then applies what was seen meanwhile. */
    private static void load() {
        if (!loading.compareAndSet(false, true)) return;
        if (worker.run(SentRequests::loadNow)) return;
        loading.set(false);
        HookStatus.counted(FamilyNames.SENT_FOLLOW_REQUESTS, NO_WORKER);
    }

    private static void loadNow() {
        try {
            if (!readIn()) loading.set(false);
        } catch (Throwable failure) {
            loading.set(false);
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, READ, failure);
        }
    }

    /**
     * Reads the file into memory unless it already is, applying the changes seen while it waited,
     * and has them written. The file is read outside {@link #LOCK}, so the hook never waits on it.
     * False when there's nowhere to read it from yet.
     */
    private static boolean readIn() throws IOException {
        synchronized (FILE_LOCK) {
            synchronized (LOCK) {
                if (kept != null) return true;
            }
            File file = storage.file();
            if (file == null) return false;
            LinkedHashMap<String, Request> read = read(file);
            boolean changed;
            synchronized (LOCK) {
                if (kept != null) return true;
                changed = !pending.isEmpty();
                for (Change each : pending) apply(read, each);
                pending.clear();
                kept = read;
                loading.set(true);
            }
            if (changed) write();
            return true;
        }
    }

    /** Has the list written off the main thread, once for any number of changes made meanwhile. */
    private static void write() {
        if (!writing.compareAndSet(false, true)) return;
        if (worker.run(SentRequests::writeNow)) return;
        writing.set(false);
        HookStatus.counted(FamilyNames.SENT_FOLLOW_REQUESTS, NO_WORKER);
    }

    private static void writeNow() {
        try {
            synchronized (FILE_LOCK) {
                // Changes from here on take another write.
                writing.set(false);
                List<Request> all;
                synchronized (LOCK) {
                    if (kept == null) return;
                    all = new ArrayList<>(kept.values());
                }
                File file = storage.file();
                if (file != null) write(file, all);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, SAVE, failure);
        }
    }

    /**
     * The requests [owner] sent, newest first, or every account's when [owner] is null or empty.
     * Reads the file the first time, so not on the main thread. Never throws.
     */
    public static List<Request> all(@Nullable String owner) {
        try {
            if (!readIn()) return Collections.emptyList();
            List<Request> shown = new ArrayList<>();
            synchronized (LOCK) {
                if (kept == null) return shown;
                for (Request each : kept.values()) {
                    if (owner == null || owner.isEmpty() || owner.equals(each.owner)) shown.add(each);
                }
            }
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, READ, failure);
            return Collections.emptyList();
        }
    }

    /**
     * The account whose requests the page shows: the one a follow button was last seen for, else the
     * one Home was last read for, or null for all of them when neither has been seen.
     */
    @Nullable
    public static String shownOwner() {
        String owner = lastOwner;
        if (owner != null && !owner.isEmpty()) return owner;
        String signedIn = HiddenAccounts.signedInAccount();
        return signedIn == null || signedIn.isEmpty() ? null : signedIn;
    }

    /** The requests whose username or name holds [typed], every one when it's blank. */
    public static List<Request> matching(List<Request> requests, @Nullable CharSequence typed) {
        String wanted = typed == null ? "" : typed.toString().trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        if (wanted.isEmpty()) return requests;
        List<Request> found = new ArrayList<>();
        for (Request each : requests) {
            if (each.username.toLowerCase(Locale.ROOT).contains(wanted) || each.name.toLowerCase(Locale.ROOT).contains(wanted)) {
                found.add(each);
            }
        }
        return found;
    }

    /** Takes [request] off the list, as if its button had said Follow. The file catches up off the main thread. */
    public static void remove(Request request) {
        try {
            change(new Change(null, request.key()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, SAVE, failure);
        }
    }

    /** Forgets every account's requests and deletes the file. Answers whether it's gone. Not on the main thread. Never throws. */
    public static boolean clear() {
        try {
            synchronized (FILE_LOCK) {
                synchronized (LOCK) {
                    kept = new LinkedHashMap<>();
                    pending.clear();
                    loading.set(true);
                }
                File file = storage.file();
                if (file == null) return false;
                File folder = file.getParentFile();
                boolean cleared = !file.exists() || file.delete() || !file.exists();
                return cleared && (folder == null || !folder.exists() || folder.delete() || !folder.exists());
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SENT_FOLLOW_REQUESTS, "sent requests clear", failure);
            return false;
        }
    }

    /** The picture [request] showed last, at least [size] pixels across, or null. Blocks, so never on the main thread. */
    @Nullable
    public static Bitmap picture(Context application, Request request, int size) {
        if (request.url.isEmpty()) return null;
        try {
            return fetch.fetch(application, request.url, Math.max(size, PICTURE_PX));
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Forgets the list in memory, as a new process would. Tests only. */
    static void forgetForTests() {
        synchronized (FILE_LOCK) {
            synchronized (LOCK) {
                kept = null;
                pending.clear();
                loading.set(false);
                writing.set(false);
                lastOwner = null;
            }
        }
    }

    static String key(String owner, String id) {
        return owner + "\t" + id;
    }

    /**
     * Reads a kept list: an {@code r} line per request with its owner, ID, username, name, picture and
     * when it was first seen, newest first. A file that isn't there is empty, and a line cut short is
     * left out.
     */
    static LinkedHashMap<String, Request> read(File file) throws IOException {
        LinkedHashMap<String, Request> read = new LinkedHashMap<>();
        if (!file.isFile()) return read;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && read.size() < MAX) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 7 || !fields[0].equals("r") || fields[1].isEmpty() || fields[2].isEmpty()
                        || fields[3].isEmpty()) continue;
                try {
                    Request request = new Request(fields[1], fields[2], fields[3], fields[4], fields[5], Long.parseLong(fields[6]));
                    read.putIfAbsent(request.key(), request);
                } catch (NumberFormatException skipped) {
                    // A line cut short is left out; the rest still counts.
                }
            }
        }
        return read;
    }

    /** Writes [requests] to [file] whole, through a file beside it, so a cut write leaves the old list. */
    static void write(File file, List<Request> requests) throws IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the requests folder could not be made");
        }
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            for (Request each : requests) {
                out.write("r\t" + clean(each.owner) + "\t" + clean(each.id) + "\t" + clean(each.username) + "\t"
                        + clean(each.name) + "\t" + clean(each.url) + "\t" + each.time + "\n");
            }
            out.flush();
            stream.getFD().sync();
        }
        try {
            Files.move(work.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            work.delete();
            throw failure;
        }
    }

    private static String clean(@Nullable String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SENT_FOLLOW_REQUESTS.get();
    }
}
