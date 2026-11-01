/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

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
import java.util.List;
import java.util.Map;

/**
 * The log behind Recent unfollowers: each time a whole Followers list is kept, who left, who came
 * back and who is new since the one before, with when it was seen. Nothing is asked of Instagram.
 * Idea and first version by argus2968 in #309, rewritten here against the current code.
 */
public final class FollowerHistory {
    public static final int UNFOLLOWED = 0;
    public static final int FOLLOWED_AGAIN = 1;
    public static final int NEW_FOLLOWER = 2;

    /** How many changes are kept, newest first. */
    static final int MAX = 2000;

    private FollowerHistory() {
    }

    /** One change: what happened, to whom (ID and username then), and when it was seen. */
    public static final class Change {
        public final int kind;
        public final String id;
        public final String username;
        public final long when;

        Change(int kind, String id, String username, long when) {
            this.kind = kind;
            this.id = id;
            this.username = username;
            this.when = when;
        }
    }

    /**
     * What changed between the list kept in [before] and the whole list [followers] taken at [when].
     * The first list taken has nothing before it and logs nothing.
     */
    static List<Change> changes(Unfollowers.Record before, Map<String, String> followers, long when) {
        List<Change> changes = new ArrayList<>();
        if (before.checked <= 0) return changes;
        for (Map.Entry<String, String> was : before.followers.entrySet()) {
            if (!followers.containsKey(was.getKey())) changes.add(new Change(UNFOLLOWED, was.getKey(), was.getValue(), when));
        }
        for (Map.Entry<String, String> now : followers.entrySet()) {
            if (before.followers.containsKey(now.getKey())) continue;
            boolean back = false;
            for (Unfollowers.Left left : before.left) back |= left.id.equals(now.getKey());
            changes.add(new Change(back ? FOLLOWED_AGAIN : NEW_FOLLOWER, now.getKey(), now.getValue(), when));
        }
        return changes;
    }

    /** The log in [file], newest first. A file that isn't there is empty. */
    static List<Change> read(File file) throws IOException {
        if (!file.isFile()) return Collections.emptyList();
        List<Change> changes = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && changes.size() < MAX) {
                String[] fields = line.split("\t", -1);
                try {
                    int kind = fields.length == 5 && fields[0].equals("h") ? Integer.parseInt(fields[1]) : -1;
                    if (kind >= UNFOLLOWED && kind <= NEW_FOLLOWER && !fields[2].isEmpty()) {
                        changes.add(new Change(kind, fields[2], fields[3], Long.parseLong(fields[4])));
                    }
                } catch (NumberFormatException skipped) {
                    // A line cut short is left out; the rest still counts.
                }
            }
        }
        return changes;
    }

    /** Puts [added] ahead of what [file] holds, keeps the newest {@link #MAX}, and writes it whole. */
    static void add(File file, List<Change> added) throws IOException {
        if (added.isEmpty()) return;
        List<Change> all = new ArrayList<>(added);
        all.addAll(read(file));
        if (all.size() > MAX) all = new ArrayList<>(all.subList(0, MAX));
        File folder = file.getParentFile();
        if (folder != null && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the followers folder could not be made");
        }
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            for (Change each : all) {
                out.write("h\t" + each.kind + "\t" + clean(each.id) + "\t" + clean(each.username) + "\t" + each.when + "\n");
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

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
