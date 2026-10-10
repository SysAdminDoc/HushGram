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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The chats "Lock your messages" keeps locked one at a time.
 *
 * <p>A locked chat gets the same cover and the same ask of the phone's lock as the messages do
 * ({@link MessagesLock}), whether or not that switch is on, and its message notifications say only
 * that a message came. The list is {@link Settings#LOCKED_CHATS}: a line for each chat, its thread
 * id and the name the chat had when it was locked. HushGram settings add the chat you opened last
 * and take a chat off the list.
 *
 * <p>The patch tells this class which chat is on screen ({@link #opened}, {@link #closed}), by the
 * thread id Instagram keeps in the chat's key, and which chat a push notification is for
 * ({@link #track}), by the ids the push carries. A notification is marked with them as it goes to
 * Android, so a copy in the shade can be matched to a chat later, when the lock comes back.
 *
 * <p>Nothing here throws into Instagram. A chat whose id can't be read isn't locked, and a
 * notification that can't be matched to a chat is hidden only when the whole messages lock is.
 */
public final class ChatLocks {
    /** Marks a notification with the ids of the chat it is for, separated by commas. */
    static final String CHAT_EXTRA = "hushgram_lock_chat";
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

    /** A chat on the list. */
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

    private static volatile String current;
    private static volatile String currentName;
    private static volatile WeakReference<Object> screen = new WeakReference<>(null);
    private static volatile String last;
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
            current = empty(id) ? null : id;
            currentName = null;
            nameAttempts = 0;
            if (current != null) {
                last = current;
                lastName = null;
            }
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
        return current;
    }

    /** The chat on screen is on the list. */
    static boolean currentListed() {
        String id = current;
        return id != null && listed(id);
    }

    /** The last chat that was on screen, with the name it was found under, if it isn't on the list. */
    public static Chat lastOpened() {
        return lastOpenedUnless(ChatLocks::listed);
    }

    /** The last chat that was on screen, unless [onList] says it is already on the list being offered it. */
    static Chat lastOpenedUnless(java.util.function.Predicate<String> onList) {
        String id = last;
        if (empty(id) || onList.test(id)) return null;
        return new Chat(id, lastName == null ? placeholder(id) : lastName);
    }

    // ---------------------------------------------------------------- the list

    /** The chats on the list, oldest first. */
    public static List<Chat> chats() {
        try {
            if (Utils.settingsReady()) return ChatList.parse(Settings.LOCKED_CHATS.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** Any chat is on the list. */
    public static boolean any() {
        return !chats().isEmpty();
    }

    /** The chat with [id] is on the list. */
    public static boolean listed(String id) {
        return ChatList.contains(chats(), id);
    }

    /** Puts a chat on the list, or renames it there. */
    public static void add(String id, String name) {
        Settings.LOCKED_CHATS.save(ChatList.added(chats(), id, name));
    }

    /** Takes a chat off the list. */
    public static void remove(String id) {
        Settings.LOCKED_CHATS.save(ChatList.removed(chats(), id));
    }

    private static String clean(String text) {
        return ChatList.clean(text);
    }

    /** What a chat is called when its name was never found. */
    static String placeholder(String id) {
        return ChatList.placeholder(id);
    }

    // ---------------------------------------------------------------- the chat's name

    /**
     * Looks for the name of the chat on screen in its [root] while it has none, a bounded number of
     * frames, and keeps it for the list. A chat already on the list takes the name it is found under.
     */
    static void learnName(View root) {
        String id = current;
        if (id == null || currentName != null || nameAttempts >= NAME_ATTEMPTS) return;
        nameAttempts++;
        String name = findName(root);
        if (name == null) return;
        currentName = name;
        if (id.equals(last)) lastName = name;
        try {
            if (listed(id)) add(id, name);
            if (HiddenChats.savedListed(id)) HiddenChats.add(id, name);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
    }

    /**
     * The chat header's title when Instagram names it, else the first text high in the chat. The
     * header is looked for from the chat's root up, so the nearest one is the chat's own. A header
     * that is still empty is waited for, since the first text in the chat may be a message.
     */
    private static String findName(View root) {
        for (String name : TITLES) {
            int id = MessagesLock.id(root.getContext(), name);
            if (id == 0) continue;
            for (View at = root; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
                View title = at.findViewById(id);
                if (title == null) continue;
                return title instanceof TextView ? text((TextView) title) : null;
            }
        }
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
        if (name.isEmpty()) return null;
        return name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Asked first when Instagram hands a push's notifications to Android: the notification, the
     * group summary, the others it posts with them, and the ids the push carries (its deep link, its
     * thread id and its thread IG id). Answers true for the push of a hidden chat ({@link HiddenChats}),
     * which the display then drops before it builds or posts anything. Otherwise, only while some chat
     * is on the locked list, each notification is marked with them as it goes to Android, so a copy in
     * the shade can be matched to a chat later, when the lock comes back. With no locked chat (the list
     * stays in force while HushGram is paused) nothing is written and every notification goes through
     * untouched, since the mark is only ever read to match a locked chat. A message left unmarked that
     * way is still matched in the shade by Instagram's own tag ({@link #listedIn(Notification, String)}).
     */
    public static boolean track(Notification notification, Notification summary, Map<?, ?> others,
                                String action, String threadId, String igThreadId) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            Set<String> ids = new LinkedHashSet<>();
            addId(ids, linkId(action));
            addId(ids, threadId);
            addId(ids, igThreadId);
            if (ids.isEmpty()) return false;
            if (HiddenChats.hides(ids)) return true;
            if (!any()) return false;
            String marked = String.join(",", ids);
            mark(notification, marked);
            mark(summary, marked);
            if (others != null) {
                for (Object each : others.values()) {
                    if (each instanceof Notification) mark((Notification) each, marked);
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

    private static void mark(Notification notification, String ids) {
        if (notification == null) return;
        if (notification.extras == null) notification.extras = new Bundle();
        notification.extras.putString(CHAT_EXTRA, ids);
    }

    /** The ids [notification] was marked with, or null. */
    static String idsOf(Notification notification) {
        Bundle extras = notification == null ? null : notification.extras;
        return extras == null ? null : extras.getString(CHAT_EXTRA);
    }

    /** [notification] is for a chat on the list. */
    static boolean listedIn(Notification notification) {
        String ids = idsOf(notification);
        if (empty(ids)) return false;
        for (String id : ids.split(",")) {
            if (listed(id)) return true;
        }
        return false;
    }

    /**
     * [notification], in the shade under [tag], is for a chat on the list: by the ids it was marked
     * with, or by the chat Instagram's own tag names. The tag is how a message that came while no
     * chat was locked, so went to Android unmarked, is still found once its chat is put on the list.
     */
    static boolean listedIn(Notification notification, String tag) {
        if (listedIn(notification)) return true;
        String id = tagThread(tag);
        return id != null && listed(id);
    }

    /**
     * The chat id in Instagram's tag for a message notification, or null. Instagram posts a chat's
     * messages under its kind, a bar, then a key of the account, {@link #TAG_THREAD} and the chat's
     * id (and {@code ;type:rr} for a reply reminder), the same key it clears them by when the chat
     * opens: {@code direct|1234;thread_id:340282366841710301244276}.
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
        reader = ChatLocks::threadId;
        HiddenChats.resetForTests();
    }
}
