/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The Saved collections "Lock your messages" keeps locked one at a time, each for the account it was
 * locked on (#678).
 *
 * <p>A locked collection's page gets the cover Lock Saved puts over Saved, over the whole screen,
 * since its top bar and its header both name it, and the same ask as every other lock, whether or
 * not a lock switch is on. One unlock opens it with the rest until Instagram leaves the screen.
 * Saving a post into it never asks: the sheet that picks a collection isn't its page.
 *
 * <p>A collection is known by the name its page shows, since nothing else of it is on screen. The
 * list is {@link Settings#LOCKED_COLLECTIONS}, in {@link ChatList}'s lines with the name as the id
 * too, so a collection renamed in Instagram leaves the list under its old name. HushGram settings add
 * the collection you opened last and take one off, for the account in front ({@link ChatAccounts})
 * and no other. With none on any list and no lock on, a collection's name is looked for only once
 * that list has been opened, so a screen with nothing locked isn't walked for it.
 *
 * <p>Nothing here throws into Instagram. A page whose name can't be read isn't covered.
 */
public final class CollectionLocks {
    /** A collection's page, as Instagram names it: the tabs of its posts and reels under its header. */
    static final String PAGE = "save_collection_viewpager";
    /** The views that name the collection on its page, as Instagram names them: its header's title, then the top bar's. */
    static final String[] TITLES = {"save_collection_header_title", "action_bar_title"};

    /** The step a failure is reported under. */
    static final String LIST = "locked collections";

    private static final int NAME_LENGTH = 60;

    /** The last collection whose page showed, by the account in front then, and its name. */
    private static volatile String lastAccount;
    private static volatile String lastName;
    /** The list in HushGram settings was opened, so the collection that shows next is wanted by name. */
    private static volatile boolean wanted;

    private CollectionLocks() {
    }

    /** The list in HushGram settings was opened: from now on, the collection that shows is learned by name. */
    public static void lookForNames() {
        wanted = true;
    }

    /** A collection's name is wanted even with nothing on any list. */
    static boolean wantsName() {
        return wanted;
    }

    // ---------------------------------------------------------------- the page

    /**
     * The name of the collection whose [page] is on screen, from the nearest view that names it,
     * looked for from the page up. Kept as the collection opened last, for the account in front.
     * Null while neither names it yet.
     */
    @Nullable
    static String nameOf(@Nullable View page) {
        if (page == null) return null;
        for (String title : TITLES) {
            int id = MessagesLock.id(page.getContext(), title);
            if (id == 0) continue;
            for (View at = page; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
                View found = at.findViewById(id);
                if (found == null) continue;
                String name = found instanceof TextView && found.isShown() ? text((TextView) found) : null;
                if (name == null) break;
                seen(name);
                return name;
            }
        }
        return null;
    }

    private static String text(TextView view) {
        CharSequence text = view.getText();
        if (text == null) return null;
        String name = ChatList.clean(text.toString());
        if (ChatList.standsIn(name)) return null;
        return name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name;
    }

    /** The collection called [name] showed, for the account in front. */
    static void seen(String name) {
        String account = ChatAccounts.active();
        if (empty(account)) return;
        lastAccount = account;
        lastName = name;
    }

    /**
     * The last collection whose page showed for [account], when it isn't on that account's list
     * already. Never one of another account, or of one that isn't known.
     */
    @Nullable
    public static String lastOpened(@Nullable String account) {
        String name = lastName;
        if (name == null || empty(account) || !account.equals(lastAccount) || listed(account, name)) return null;
        return name;
    }

    // ---------------------------------------------------------------- the list

    /** The names on [account]'s list, oldest first: none for an account that isn't known. */
    public static List<String> names(@Nullable String account) {
        List<String> names = new ArrayList<>();
        for (ChatLocks.Chat each : ChatList.shown(kept(), account)) names.add(each.name);
        return names;
    }

    /** Any collection is on any account's list. */
    public static boolean any() {
        return !kept().isEmpty();
    }

    /** The collection called [name] is on [account]'s list, or on any account's when [account] is null. */
    public static boolean listed(@Nullable String account, @Nullable String name) {
        return !empty(name) && ChatList.protects(kept(), account, name);
    }

    /** The same for the account in front, or any account when none is known. */
    static boolean listed(@Nullable String name) {
        return listed(ChatAccounts.active(), name);
    }

    /**
     * Puts the collection called [name] on [account]'s list. False, with nothing changed, for an
     * account that isn't known, before the settings are read, or when the list couldn't be saved.
     */
    public static boolean add(@Nullable String account, @Nullable String name) {
        if (empty(account) || empty(name)) return false;
        String kept = ChatList.clean(name);
        return change(rows -> ChatList.added(rows, account, kept, kept));
    }

    /**
     * Takes the collection called [name] off [account]'s list, and only off its. False, with it still
     * locked, for an account that isn't known, before the settings are read, or when the list
     * couldn't be saved.
     */
    public static boolean remove(@Nullable String account, @Nullable String name) {
        if (empty(account) || empty(name)) return false;
        return change(rows -> ChatList.removed(rows, account, ChatList.clean(name)));
    }

    /** The list as last parsed, with the text it came from, so a frame's worth of asks parse it once. */
    private static volatile String parsedRaw;
    private static volatile List<ChatList.Row> parsedRows = Collections.emptyList();

    private static List<ChatList.Row> kept() {
        try {
            if (Utils.settingsReady()) {
                String raw = Settings.LOCKED_COLLECTIONS.get();
                if (!raw.equals(parsedRaw)) {
                    parsedRows = Collections.unmodifiableList(ChatList.parse(raw));
                    parsedRaw = raw;
                }
                return parsedRows;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return Collections.emptyList();
    }

    private static boolean change(UnaryOperator<List<ChatList.Row>> edit) {
        try {
            if (!Utils.settingsReady()) return false;
            List<ChatList.Row> rows = ChatList.parse(Settings.LOCKED_COLLECTIONS.savedValue());
            List<ChatList.Row> changed = edit.apply(rows);
            return changed == rows || Settings.LOCKED_COLLECTIONS.save(ChatList.format(changed));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return false;
        }
    }

    private static boolean empty(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        lastAccount = null;
        lastName = null;
        wanted = false;
        parsedRaw = null;
        parsedRows = Collections.emptyList();
    }
}
