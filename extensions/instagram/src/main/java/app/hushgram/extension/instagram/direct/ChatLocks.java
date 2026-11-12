/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Notification;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The chats "Lock your messages" keeps locked one at a time, each for the account it was locked on.
 *
 * <p>A locked chat gets the same cover and the same ask of the phone's lock as the messages do
 * ({@link MessagesLock}), whether or not that switch is on, and its message notifications say only
 * that a message came. The list is {@link Settings#LOCKED_CHATS}, kept as {@link ChatList} says: each
 * chat with the account it belongs to, its thread id and the name the chat had there. HushGram
 * settings add the chat you opened last and take a chat off the list, for the account in front
 * ({@link ChatAccounts}) and no other.
 *
 * <p>The patch tells this class which chat is on screen ({@link #opened}, {@link #closed}), by the
 * thread id Instagram keeps in the chat's key and the account the chat's own screen is signed in to,
 * and which chat a push notification is for ({@link #track}), by the ids the push carries and the
 * account it was sent to. A notification is marked with them as it goes to Android, so a copy in
 * the shade can be matched to a chat later, when the lock comes back, for that account whichever one
 * is in front then.
 *
 * <p>Nothing here throws into Instagram. A chat whose id can't be read isn't locked, and a
 * notification that can't be matched to a chat is hidden only when the whole messages lock is. A
 * chat or a push whose account can't be read is locked when any account's list has it.
 */
public final class ChatLocks {
    /** Marks a notification with the ids of the chat it is for, separated by commas. */
    static final String CHAT_EXTRA = "hushgram_lock_chat";
    /** Marks a notification with the id of the account its push was sent to. */
    static final String ACCOUNT_EXTRA = "hushgram_lock_account";
    /** What comes before the chat's id in the tag Instagram posts a message notification under. */
    static final String TAG_THREAD = ";thread_id:";

    /** Steps a failure is reported under. */
    static final String OPENED = "chat opened";
    static final String TRACK = "notification chat";
    static final String LIST = "locked chats";

    /** How many frames a chat's name is looked for in before the name it was given is kept. */
    static final int NAME_ATTEMPTS = 40;
    private static final int NAME_LENGTH = 40;
    /**
     * The views Instagram's chat header gives the chat's name, as it names them, newest first. 450
     * calls it header_title and puts the header beside the chat's root rather than in it.
     */
    static final String[] TITLES = {"header_title", "thread_title"};

    /** A chat on the list, as one account's list shows it. */
    public static final class Chat {
        public final String id;
        public final String name;

        Chat(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Reads a chat fragment's thread id. Tests put a fake in. */
    interface Reader {
        String threadId(Object fragment);
    }

    static volatile Reader reader = ChatLocks::threadId;

    /** A chat that came on screen: its thread id and the account its screen is signed in to, null when unknown. */
    private static final class Open {
        final String id;
        @Nullable final String account;

        Open(String id, @Nullable String account) {
            this.id = id;
            this.account = account;
        }
    }

    private static volatile Open current;
    private static volatile String currentName;
    private static volatile WeakReference<Object> screen = new WeakReference<>(null);
    private static volatile Open last;
    private static volatile String lastName;
    private static volatile int nameAttempts;

    private ChatLocks() {
    }

    /**
     * The id of the chat a fragment shows, or null when it has none yet (a chat that is only being
     * started). The patch writes the body at patch time, from the fields and calls Instagram's own
     * code reads the id with, and a build without it answers null, so no chat counts as locked.
     */
    public static String threadId(Object fragment) {
        return null;
    }

    /** Asked first when a chat comes to the front. */
    public static void opened(Object fragment) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            String id = reader.threadId(fragment);
            screen = new WeakReference<>(fragment);
            Open open = empty(id) ? null : new Open(id, ChatAccounts.accountOf(fragment));
            current = open;
            currentName = null;
            nameAttempts = 0;
            if (open != null) {
                last = open;
                lastName = null;
            }
            MessagesLock.chatOpened(empty(id) ? null : id);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, OPENED, t);
            current = null;
        }
    }

    /** Asked first when a chat leaves the front. A chat that already gave way to another is left alone. */
    public static void closed(Object fragment) {
        try {
            Object shown = screen.get();
            if (shown != null && shown != fragment) return;
            screen = new WeakReference<>(null);
            current = null;
            currentName = null;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, OPENED, t);
        }
    }

    /** The thread id of the chat on screen, if there is one. */
    static String current() {
        Open open = current;
        return open == null ? null : open.id;
    }

    /** The account the chat on screen is signed in to, or null when there's none or it isn't known. */
    static String currentAccount() {
        Open open = current;
        return open == null ? null : open.account;
    }

    /** The chat on screen is on its account's list, or on any account's when its account isn't known. */
    static boolean currentListed() {
        Open open = current;
        return open != null && listed(open.account, open.id);
    }

    /** The last chat that was on screen, signed in to [account], with the name it was found under, if it isn't on that account's list. */
    public static Chat lastOpened(@Nullable String account) {
        return lastOpenedUnless(account, ChatLocks::listed);
    }

    /** The same for the account in front. */
    public static Chat lastOpened() {
        return lastOpened(ChatAccounts.active());
    }

    /**
     * The last chat that was on screen, when it was signed in to [account] and [onList] doesn't say
     * it's already on that account's list being offered it. Never a chat of another account, or of
     * one that isn't known.
     */
    static Chat lastOpenedUnless(@Nullable String account, BiPredicate<String, String> onList) {
        Open open = last;
        if (open == null || empty(account) || !account.equals(open.account) || onList.test(account, open.id)) return null;
        String name = lastName;
        return new Chat(open.id, name == null ? placeholder(open.id) : name);
    }

    /**
     * [account] came to the front after another one: what was remembered of the other (the chat it
     * opened last and the list as parsed) goes, and every account's saved choices stay as they are.
     * A chat [account] itself just opened stays, since a chat's screen is read before the screen
     * under it says which account is in front.
     */
    static void accountChanged(String account) {
        Open opened = last;
        if (opened != null && !account.equals(opened.account)) {
            last = null;
            lastName = null;
        }
        parsed = null;
        HiddenChats.accountChanged();
    }

    // ---------------------------------------------------------------- the list

    /** The list as last parsed, with the text it came from, so a frame's worth of asks parse it once. */
    private static final class Parsed {
        final String raw;
        final List<ChatList.Row> rows;

        Parsed(String raw, List<ChatList.Row> rows) {
            this.raw = raw;
            this.rows = rows;
        }
    }

    private static volatile Parsed parsed;

    /** Every account's chats on the list, oldest first, parsed again only when its text changed; callers never change it. */
    private static List<ChatList.Row> kept() {
        try {
            if (Utils.settingsReady()) {
                String raw = Settings.LOCKED_CHATS.get();
                Parsed kept = parsed;
                if (kept == null || !kept.raw.equals(raw)) {
                    kept = new Parsed(raw, Collections.unmodifiableList(ChatList.parse(raw)));
                    parsed = kept;
                }
                return kept.rows;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return Collections.emptyList();
    }

    /** The chats on [account]'s list, oldest first: none for an account that isn't known. */
    public static List<Chat> chats(@Nullable String account) {
        return new ArrayList<>(ChatList.shown(kept(), account));
    }

    /** The chats on the list of the account in front. */
    public static List<Chat> chats() {
        return chats(ChatAccounts.active());
    }

    /** Any chat is on any account's list. */
    public static boolean any() {
        return !kept().isEmpty();
    }

    /** The chat with [id] is on [account]'s list, or on any account's when [account] is null. */
    public static boolean listed(@Nullable String account, String id) {
        return ChatList.protects(kept(), account, id);
    }

    /** The chat with [id] is on the list of the account in front, or on any account's when none is known. */
    public static boolean listed(String id) {
        return listed(ChatAccounts.active(), id);
    }

    /**
     * Puts a chat on [account]'s list, or renames it there. False, with nothing changed, for an account
     * that isn't known, before the settings are read, or when the list couldn't be saved.
     */
    public static boolean add(@Nullable String account, String id, String name) {
        if (empty(account) || empty(id)) return false;
        return change(rows -> ChatList.added(rows, account, id, name));
    }

    /** The same for the account in front. */
    public static boolean add(String id, String name) {
        return add(ChatAccounts.active(), id, name);
    }

    /**
     * Takes a chat off [account]'s list, and only off its. False, with the chat still locked, for an
     * account that isn't known, before the settings are read, or when the list couldn't be saved.
     */
    public static boolean remove(@Nullable String account, String id) {
        if (empty(account) || empty(id)) return false;
        return change(rows -> ChatList.removed(rows, account, id));
    }

    /** The same for the account in front. */
    public static boolean remove(String id) {
        return remove(ChatAccounts.active(), id);
    }

    /**
     * Saves the list [edit] makes of the one saved, read fresh. True when it's saved or there was
     * nothing to change. A list that can't be read or saved is left as it was.
     */
    private static boolean change(UnaryOperator<List<ChatList.Row>> edit) {
        try {
            if (!Utils.settingsReady()) return false;
            List<ChatList.Row> rows = ChatList.parse(Settings.LOCKED_CHATS.savedValue());
            List<ChatList.Row> changed = edit.apply(rows);
            return changed == rows || Settings.LOCKED_CHATS.save(ChatList.format(changed));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return false;
        }
    }

    private static String clean(String text) {
        return ChatList.clean(text);
    }

    /** What a chat is called when its name was never found. */
    static String placeholder(String id) {
        return ChatList.placeholder(id);
    }

    // ---------------------------------------------------------------- the chat's name

    /** Whether a chat is on screen whose name hasn't been found yet and may still be looked for. */
    static boolean wantsName() {
        return current != null && currentName == null && nameAttempts < NAME_ATTEMPTS;
    }

    /**
     * Looks for the name of the chat on screen in its [root] while it has none, a bounded number of
     * frames, and keeps it for the list. A chat already on its account's list takes the name it is
     * found under there, and so does one kept from before accounts were, which gets a line of that
     * account's own. A chat whose account isn't known changes no list. A header that only stands in
     * for the name ({@link ChatList#standsIn}) isn't kept, and the name is looked for again.
     */
    static void learnName(View root) {
        Open open = current;
        if (open == null || !wantsName()) return;
        nameAttempts++;
        String name = findName(root);
        // A header that only stands in for the name, before the chat has loaded, is looked at again.
        if (name == null || ChatList.standsIn(open.id, name)) return;
        currentName = name;
        Open opened = last;
        if (opened != null && opened.id.equals(open.id) && Objects.equals(opened.account, open.account)) lastName = name;
        if (empty(open.account)) return;
        try {
            change(rows -> ChatList.named(rows, open.account, open.id, name));
            HiddenChats.named(open.account, open.id, name);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
    }

    /**
     * The chat header's title when Instagram names it, else the first text high in the chat. The
     * header is looked for from the chat's root up, so the nearest one is the chat's own. A header
     * that is still empty, hidden or not there yet is waited for: the first text in the chat may be
     * a message, so it's only read in a build whose header has neither name.
     */
    private static String findName(View root) {
        boolean headerKnown = false;
        for (String name : TITLES) {
            int id = MessagesLock.id(root.getContext(), name);
            if (id == 0) continue;
            headerKnown = true;
            for (View at = root; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
                View title = at.findViewById(id);
                if (title == null) continue;
                String text = title instanceof TextView && title.isShown() ? text((TextView) title) : null;
                if (text != null) return text;
                break;
            }
        }
        if (headerKnown) return null;
        if (!(root instanceof ViewGroup)) return null;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        return firstText((ViewGroup) root, origin[1], Math.max(root.getHeight() / 5, 1));
    }

    private static String firstText(ViewGroup group, int top, int within) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof TextView && !(child instanceof android.widget.EditText)) {
                int[] at = new int[2];
                child.getLocationOnScreen(at);
                String text = text((TextView) child);
                if (text != null && at[1] - top < within) return text;
            } else if (child instanceof ViewGroup && !(child instanceof MessagesLock.Cover)) {
                String found = firstText((ViewGroup) child, top, within);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String text(TextView view) {
        CharSequence text = view.getText();
        if (text == null) return null;
        String name = clean(text.toString());
        if (ChatList.standsIn(name)) return null;
        return name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Asked first when Instagram hands a push's notifications to Android: the notification, the
     * group summary, the others it posts with them, the ids the push carries (its deep link, its
     * thread id and its thread IG id) and the id of the account it was sent to. Answers true for the
     * push of a chat hidden on that account ({@link HiddenChats}), which the display then drops before
     * it builds or posts anything. Otherwise, only while some chat is on some account's locked list,
     * each notification is marked with the chat's ids and the account as it goes to Android, so a copy
     * in the shade can be matched to that account's chat later, when the lock comes back. With no
     * locked chat (the list stays in force while HushGram is paused) nothing is written and every
     * notification goes through untouched, since the mark is only ever read to match a locked chat. A
     * message left unmarked that way is still matched in the shade by Instagram's own tag
     * ({@link #listedIn(Notification, String)}), which names the account too. A push whose account
     * can't be read is matched against every account's list.
     */
    public static boolean track(Notification notification, Notification summary, Map<?, ?> others,
                                String action, String threadId, String igThreadId, String recipient) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            Set<String> ids = new LinkedHashSet<>();
            addId(ids, linkId(action));
            addId(ids, threadId);
            addId(ids, igThreadId);
            if (ids.isEmpty()) return false;
            String account = ChatAccounts.clean(recipient);
            if (HiddenChats.hides(ids, account)) return true;
            if (!any()) return false;
            String marked = String.join(",", ids);
            mark(notification, marked, account);
            mark(summary, marked, account);
            if (others != null) {
                for (Object each : others.values()) {
                    if (each instanceof Notification) mark((Notification) each, marked, account);
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, TRACK, t);
        }
        return false;
    }

    /** The chat id in a direct message push's deep link, like {@code direct_v2?id=340282&x=1}. */
    static String linkId(String action) {
        if (empty(action) || !action.contains("direct_v2")) return null;
        try {
            String id = Uri.parse(action).getQueryParameter("id");
            return empty(id) ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void addId(Set<String> ids, String id) {
        if (!empty(id) && id.indexOf(',') < 0) ids.add(id.trim());
    }

    private static void mark(Notification notification, String ids, @Nullable String account) {
        if (notification == null) return;
        if (notification.extras == null) notification.extras = new Bundle();
        notification.extras.putString(CHAT_EXTRA, ids);
        if (account != null) notification.extras.putString(ACCOUNT_EXTRA, account);
        else notification.extras.remove(ACCOUNT_EXTRA);
    }

    /** The ids [notification] was marked with, or null. */
    static String idsOf(Notification notification) {
        Bundle extras = notification == null ? null : notification.extras;
        return extras == null ? null : extras.getString(CHAT_EXTRA);
    }

    /** The account [notification] was marked with, or null. */
    static String accountOf(Notification notification) {
        Bundle extras = notification == null ? null : notification.extras;
        return extras == null ? null : ChatAccounts.clean(extras.getString(ACCOUNT_EXTRA));
    }

    /** [notification] is for a chat on the list of the account it was marked with, or of any account when it has none. */
    static boolean listedIn(Notification notification) {
        return listedIn(idsOf(notification), accountOf(notification));
    }

    private static boolean listedIn(String ids, @Nullable String account) {
        if (empty(ids)) return false;
        for (String id : ids.split(",")) {
            if (listed(account, id)) return true;
        }
        return false;
    }

    /**
     * [notification], in the shade under [tag], is for a chat on the list of the account it was sent
     * to: by the ids it was marked with, or by the chat Instagram's own tag names. The tag is how a
     * message that came while no chat was locked, so went to Android unmarked, is still found once its
     * chat is put on the list. The account is the one it was marked with, else the one its tag names,
     * else every account's list counts.
     */
    static boolean listedIn(Notification notification, String tag) {
        String account = accountOf(notification);
        if (account == null) account = tagAccount(tag);
        if (listedIn(idsOf(notification), account)) return true;
        String id = tagThread(tag);
        return id != null && listed(account, id);
    }

    /**
     * The chat id in Instagram's tag for a message notification, or null. Instagram posts a chat's
     * messages under its kind, a bar, then the id of the account the push was sent to,
     * {@link #TAG_THREAD} and the chat's id (and {@code ;type:rr} for a reply reminder), the same key
     * it clears them by when the chat opens: {@code direct|1234;thread_id:340282366841710301244276}.
     */
    static String tagThread(String tag) {
        if (empty(tag)) return null;
        int at = tag.indexOf(TAG_THREAD);
        if (at < 0) return null;
        int start = at + TAG_THREAD.length();
        int end = tag.indexOf(';', start);
        String id = (end < 0 ? tag.substring(start) : tag.substring(start, end)).trim();
        return id.isEmpty() || id.indexOf(',') >= 0 ? null : id;
    }

    /**
     * The id of the account in Instagram's tag for a message notification, between the bar and
     * {@link #TAG_THREAD}, or null. A tag with no bar names no account, so every account's list
     * counts; reading its kind as one would match no list and leave a locked chat's text showing.
     */
    static String tagAccount(String tag) {
        if (empty(tag)) return null;
        int at = tag.indexOf(TAG_THREAD);
        if (at < 0) return null;
        String key = tag.substring(0, at);
        int bar = key.lastIndexOf('|');
        return bar < 0 ? null : ChatAccounts.clean(key.substring(bar + 1));
    }

    private static boolean empty(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        current = null;
        currentName = null;
        screen = new WeakReference<>(null);
        last = null;
        lastName = null;
        nameAttempts = 0;
        parsed = null;
        reader = ChatLocks::threadId;
        ChatAccounts.resetForTests();
        HiddenChats.resetForTests();
    }
}
