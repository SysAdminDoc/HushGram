/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hidden accounts, under Hide suggested posts: the usernames whose posts Home and its Following
 * feed leave out ({@link FeedSuggestions#homeItem}), kept for each signed-in account.
 *
 * <p>The list is {@link Settings#HIDDEN_ACCOUNTS}, a line for each name: the id of the account it
 * was hidden for, a tab, and the username. Nothing else in HushGram keeps anything per account, and
 * Home's reads don't say whose Home they are, so the patch tells this class instead: first thing in
 * the constructor of Home's cache source, which Instagram makes once for each signed-in session, it
 * hands over that session ({@link #homeSession}). Its account is the one whose names Home leaves
 * out and the one settings shows and edits, and it's saved ({@link Settings#FEED_ACCOUNT}) so a
 * restart that opens settings before Home still edits that account's list. A name added before any
 * account was seen is kept with an empty id and counts for every account.
 *
 * <p>A username is matched the way Instagram writes them, in lower case, trimmed and without a
 * leading @. While HushGram is paused the list reads empty, so every post shows, and settings still
 * shows what you chose.
 */
public final class HiddenAccounts {
    /** Why a post from a hidden account was taken out, on Home's post types route. */
    static final String HIDDEN = "hidden account";

    /** The report's count of posts Hidden accounts took out of Home. */
    static final String REMOVED = "hidden account posts removed";

    /** The longest username Instagram allows. */
    static final int MAX_LENGTH = 30;

    /** The account Home's cache source was last made for in this run. Null before one is. Tests clear it. */
    @Nullable
    static volatile String account;

    /** The names hidden for the account that last read them, kept until the list or the account changes. */
    private static volatile Snapshot snapshot;

    private HiddenAccounts() {
    }

    /** A parsed list and what it was parsed from. */
    private static final class Snapshot {
        final String text;
        final String account;
        final Set<String> names;

        Snapshot(String text, String account, Set<String> names) {
            this.text = text;
            this.account = account;
            this.names = names;
        }
    }

    /**
     * Injected first thing in the constructor of Home's cache source, with the session it's made
     * for. Keeps that session's account as the signed-in one, and saves it when the settings are
     * ready. Never waits for them and never throws.
     */
    public static void homeSession(Object session) {
        homeSession(session, HiddenAccounts::accountOf);
    }

    static void homeSession(Object session, Function<Object, String> idOf) {
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            if (session == null) return;
            String id = idOf.apply(session);
            if (id == null || id.isEmpty()) return;
            account = id;
            if (Utils.settingsReady() && !id.equals(Settings.FEED_ACCOUNT.savedValue())) Settings.FEED_ACCOUNT.save(id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "home account", failure);
        }
    }

    /**
     * The id of the account [session], Instagram's UserSession, is signed in to. The patch writes
     * the body, which calls the session's getUserId(). Null as built.
     */
    public static String accountOf(Object session) {
        return null;
    }

    /**
     * The username of whoever posted [media], a Media, or null when it doesn't say. The patch writes
     * the body, which reads the post's user and that user's username. Null as built.
     */
    public static String authorOfPost(Object media) {
        return null;
    }

    /** The signed-in account: Home's this run, else the one saved last, else "" when none has been seen. */
    static String current() {
        String id = account;
        if (id != null) return id;
        try {
            return Utils.settingsReady() ? Settings.FEED_ACCOUNT.savedValue() : "";
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "saved account", failure);
            return "";
        }
    }

    /**
     * The names Home leaves out now: none while HushGram is paused or its settings aren't ready.
     * Parsed once per change of the list or the account. Never throws.
     */
    static Set<String> hidden() {
        try {
            if (!Utils.settingsReady()) return Collections.emptySet();
            String text = Settings.HIDDEN_ACCOUNTS.get();
            if (text == null || text.isEmpty()) return Collections.emptySet();
            String id = current();
            Snapshot last = snapshot;
            if (last != null && last.text.equals(text) && last.account.equals(id)) return last.names;
            Set<String> names = Collections.unmodifiableSet(new LinkedHashSet<>(namesFor(text, id)));
            snapshot = new Snapshot(text, id, names);
            return names;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hidden accounts", failure);
            return Collections.emptySet();
        }
    }

    /** Whether [username], a post's author, is on the list Home leaves out now. */
    static boolean hides(@Nullable String username) {
        if (username == null) return false;
        Set<String> names = hidden();
        return !names.isEmpty() && names.contains(username.toLowerCase(Locale.ROOT));
    }

    /** The names hidden for the signed-in account as you chose them, paused or not, in the order added. */
    public static List<String> saved() {
        try {
            if (!Utils.settingsReady()) return Collections.emptyList();
            return namesFor(Settings.HIDDEN_ACCOUNTS.savedValue(), current());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hidden accounts", failure);
            return Collections.emptyList();
        }
    }

    /**
     * Hides the posts of [typed], a username as someone typed it, for the signed-in account. Answers
     * the name as kept, or null when [typed] isn't a username. A name already on the list is
     * answered as it is and isn't added twice.
     */
    @Nullable
    public static String add(String typed) {
        String name = username(typed);
        if (name == null || !Utils.settingsReady()) return null;
        String id = current();
        String text = Settings.HIDDEN_ACCOUNTS.savedValue();
        if (namesFor(text, id).contains(name)) return name;
        String line = id + "\t" + name;
        Settings.HIDDEN_ACCOUNTS.save(text == null || text.isEmpty() ? line : text + "\n" + line);
        Logger.printDebug(() -> "Hidden accounts: added a name");
        return name;
    }

    /** Shows [name]'s posts again for the signed-in account, and for every account if it was kept for all. */
    public static void remove(String name) {
        if (name == null || !Utils.settingsReady()) return;
        String id = current();
        String wanted = name.toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder();
        for (String line : lines(Settings.HIDDEN_ACCOUNTS.savedValue())) {
            String[] entry = entry(line);
            if (entry != null && entry[1].equals(wanted) && (entry[0].equals(id) || entry[0].isEmpty())) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(line);
        }
        Settings.HIDDEN_ACCOUNTS.save(kept.toString());
        Logger.printDebug(() -> "Hidden accounts: removed a name");
    }

    /**
     * [typed] as a username: trimmed, without a leading @, in lower case. Null when what's left is
     * empty, longer than Instagram allows or has anything but letters, digits, dots and underscores.
     */
    @Nullable
    static String username(@Nullable String typed) {
        if (typed == null) return null;
        String name = typed.trim();
        while (name.startsWith("@")) name = name.substring(1).trim();
        name = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > MAX_LENGTH) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_')) return null;
        }
        return name;
    }

    /** The names [text] keeps for [id] and for every account, each once, in the order added. */
    static List<String> namesFor(@Nullable String text, String id) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String line : lines(text)) {
            String[] entry = entry(line);
            if (entry != null && (entry[0].equals(id) || entry[0].isEmpty())) names.add(entry[1]);
        }
        return new ArrayList<>(names);
    }

    private static List<String> lines(@Nullable String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** A line's account id and username, or null for a line that isn't one. */
    @Nullable
    private static String[] entry(String line) {
        int tab = line.indexOf('\t');
        String id = tab < 0 ? "" : line.substring(0, tab).trim();
        String name = username(tab < 0 ? line : line.substring(tab + 1));
        return name == null ? null : new String[] {id, name};
    }

    /** Forgets the account and the parsed list. Tests only, settings' tests included. */
    public static void resetForTests() {
        account = null;
        snapshot = null;
    }
}
