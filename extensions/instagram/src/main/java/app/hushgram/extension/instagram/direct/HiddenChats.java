/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The chats "Lock your messages" hides, one at a time.
 *
 * <p>A hidden chat is left out of the list of chats the inbox draws its rows from ({@link #inbox}),
 * out of the chats each folder tab counts its unread ones from ({@link #folder}) and the ones the
 * unread badge reads ({@link #filter}), out of the inbox search's results and message matches ({@link #searchResults},
 * {@link #searchHits}), out of the recent searches it lists before you type ({@link #recents}), and a push for it isn't posted at all ({@link ChatLocks#track} asks {@link #hides}). The list is
 * {@link Settings#HIDDEN_CHATS}, kept in the same lines as the locked chats ({@link ChatList}): a
 * chat's thread id and the name it had when it was hidden. The ids never leave the phone. HushGram
 * settings add the chat you opened last and take a chat off the list.
 *
 * <p>A list with a chat on it is the switch. Paused, the setting answers an empty list, so every
 * hidden chat is back until HushGram is turned on again; the settings screen still shows what was
 * chosen ({@link #saved}).
 *
 * <p>Nothing here throws into Instagram. A list that can't be read hides nothing, and a summary
 * whose id can't be read stays in the inbox.
 */
public final class HiddenChats {
    /** What is counted in the diagnostic report. */
    static final String LEFT_OUT = "hidden chats left out of the inbox";
    static final String LEFT_OUT_FOLDERS = "hidden chats left out of folder unread counts";
    static final String LEFT_OUT_BADGE = "hidden chats left out of the unread badge";
    static final String LEFT_OUT_SEARCH = "hidden chats left out of search";
    static final String LEFT_OUT_RECENTS = "hidden chats left out of recent searches";
    static final String SILENCED = "hidden chat notifications dropped";

    /** Steps a failure is reported under. */
    static final String LIST = "hidden chats";
    static final String FILTER = "inbox filter";
    static final String FOLDER_FILTER = "folder count filter";
    static final String BADGE_FILTER = "unread badge filter";
    static final String SEARCH = "inbox search filter";
    static final String RECENTS = "recent searches filter";

    /** Instagram's own names for a chat as the inbox search lists it, and for a chat whose messages matched. */
    private static final String SHARE_TARGET = "com.instagram.model.direct.DirectShareTarget";
    private static final String HIT_THREAD = "com.instagram.model.direct.DirectMessageSearchThread";
    private static final String HIT_MESSAGE = "com.instagram.model.direct.DirectMessageSearchMessage";

    /** Reads the thread id of one of the inbox's thread summaries. Tests put a fake in. */
    interface Reader {
        String threadId(Object summary);
    }

    static volatile Reader reader = HiddenChats::threadId;

    /** Reads the thread id of a chat in the inbox search's results. Tests put a fake in. */
    static volatile Reader searchReader = HiddenChats::targetThreadId;

    private HiddenChats() {
    }

    /**
     * The thread id of a thread summary, or null when it has none. The patch writes the body at
     * patch time, from the summary's own chat key getter and the key's thread id field, and a build
     * without it answers null, so no chat is hidden from the inbox.
     */
    public static String threadId(Object summary) {
        return null;
    }

    // ---------------------------------------------------------------- the inbox

    /**
     * Asked with the list of chats the inbox screen is about to keep and turn into rows, each time
     * Instagram hands it one: the one read from the phone at start, a fresh page from the server, a
     * pull to refresh, a switch of folder tab. A list with no hidden chat in it comes back as it is,
     * and otherwise a copy without the hidden ones does. Instagram's own list is left as it was, so
     * the inbox's state, its next page and what it saves to the phone still have every chat.
     */
    public static List<Object> inbox(List<Object> summaries) {
        return leaveOut(summaries, LEFT_OUT, FILTER);
    }

    /**
     * Asked with one folder tab's chats, which Instagram counts the tab's unread chats from (the
     * "1 unread message" on a folder pill). The same as {@link #inbox}, so a hidden chat's unread
     * messages don't count on any tab.
     */
    public static List<Object> folder(List<Object> summaries) {
        return leaveOut(summaries, LEFT_OUT_FOLDERS, FOLDER_FILTER);
    }

    private static List<Object> leaveOut(List<Object> summaries, String counted, String step) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return summaries;
            ArrayList<Object> shown = shown(summaries, hidden);
            if (shown.size() == summaries.size()) return summaries;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, counted);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, step, t);
            return summaries;
        }
    }

    /**
     * Asked with the thread summaries Instagram's unread badge snapshot is about to read. A list
     * with no hidden chat in it comes back as it is. Otherwise a copy without the hidden ones does,
     * and Instagram's own list is left as it was.
     */
    public static ArrayList<Object> filter(ArrayList<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return summaries;
            ArrayList<Object> shown = shown(summaries, hidden);
            if (shown.size() != summaries.size()) HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_BADGE);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BADGE_FILTER, t);
            return summaries;
        }
    }

    /** A new list of the summaries whose chats aren't hidden, in their order. */
    private static ArrayList<Object> shown(List<Object> summaries, Set<String> hidden) {
        ArrayList<Object> shown = new ArrayList<>(summaries.size());
        for (Object summary : summaries) {
            String id = idOf(summary);
            if (id == null || !hidden.contains(id)) shown.add(summary);
        }
        return shown;
    }

    private static String idOf(Object summary) {
        try {
            String id = reader.threadId(summary);
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (Throwable t) {
            // One summary that can't be read stays in the inbox; the rest are still filtered.
            return null;
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * The thread id of a chat in the inbox search's results, or null when it has none. The patch
     * writes the body at patch time, from Instagram's own result type, and a build without it
     * answers null, so no result is hidden. A result that is only a person has no thread, and stays.
     */
    public static String targetThreadId(Object result) {
        return null;
    }

    /**
     * Asked with the results Instagram's inbox search is about to turn into rows. A list with no
     * hidden chat in it comes back as it is, and otherwise a copy without the hidden ones does.
     * People who aren't a chat yet stay, so a hidden chat doesn't stop you finding its person.
     */
    public static List<Object> searchResults(List<Object> results) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (results == null || results.isEmpty()) return results;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return results;
            List<Object> shown = new ArrayList<>(results.size());
            for (Object result : results) {
                if (!inHiddenChat(result, hidden)) shown.add(result);
            }
            if (shown.size() == results.size()) return results;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return results;
        }
    }

    /** The same for the messages the server found: the ones said in a hidden chat are left out. */
    public static ArrayList<Object> searchHits(ArrayList<Object> hits) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (hits == null || hits.isEmpty()) return hits;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return hits;
            ArrayList<Object> shown = new ArrayList<>(hits.size());
            for (Object hit : hits) {
                if (!inHiddenChat(hit, hidden)) shown.add(hit);
            }
            if (shown.size() == hits.size()) return hits;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_SEARCH);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SEARCH, t);
            return hits;
        }
    }

    /**
     * Asked with the recent searches Instagram's inbox search lists before anything is typed. An
     * entry is either a chat or a wrapper holding one. A person or another kind of search has no
     * chat and stays. A list with no hidden chat in it comes back as it is, and otherwise a copy
     * without the hidden ones does.
     */
    public static List<Object> recents(List<Object> entries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (entries == null || entries.isEmpty()) return entries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return entries;
            List<Object> shown = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (!recentInHiddenChat(entry, hidden)) shown.add(entry);
            }
            if (shown.size() == entries.size()) return entries;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT_RECENTS);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, RECENTS, t);
            return entries;
        }
    }

    /**
     * The entry is a chat on the list, or holds one in a field of Instagram's chat type. The
     * wrapper's field name changes with each build and its type doesn't. An entry that can't be
     * read stays.
     */
    private static boolean recentInHiddenChat(Object entry, Set<String> hidden) {
        try {
            if (entry == null) return false;
            Object target = isShareTarget(entry.getClass()) ? entry : heldShareTarget(entry);
            if (target == null) return false;
            String id = searchReader.threadId(target);
            return id != null && hidden.contains(id.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShareTarget(Class<?> type) {
        for (; type != null; type = type.getSuperclass()) {
            if (SHARE_TARGET.equals(type.getName())) return true;
        }
        return false;
    }

    private static Object heldShareTarget(Object entry) throws IllegalAccessException {
        for (Class<?> type = entry.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !isShareTarget(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(entry);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** The result is a chat on the list, or a message said in one. A result that can't be read isn't. */
    private static boolean inHiddenChat(Object result, Set<String> hidden) {
        try {
            if (result == null) return false;
            for (Class<?> type = result.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getName();
                if (SHARE_TARGET.equals(name)) {
                    String id = searchReader.threadId(result);
                    return id != null && hidden.contains(id.trim());
                }
                if (HIT_THREAD.equals(name) || HIT_MESSAGE.equals(name)) return carriesAny(result, type, hidden);
            }
        } catch (Throwable t) {
            // One result that can't be read stays in the search; the rest are still filtered.
        }
        return false;
    }

    /**
     * A message match keeps its chat's thread id in one of its text fields, under a name that
     * changes with each Instagram build. A thread id is a long number nothing else in the match
     * looks like, so any text field holding a hidden chat's is the chat.
     */
    private static boolean carriesAny(Object hit, Class<?> type, Set<String> hidden) throws IllegalAccessException {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(hit);
            if (value instanceof String && hidden.contains(((String) value).trim())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Some id a push carries (its deep link's chat id, its thread id or its thread IG id) is a
     * hidden chat's. Counted when it is, so the diagnostic report shows pushes that were dropped.
     */
    static boolean hides(Collection<String> pushIds) {
        try {
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return false;
            for (String id : pushIds) {
                if (hidden.contains(id)) {
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, SILENCED);
                    return true;
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return false;
    }

    // ---------------------------------------------------------------- the list

    /** The thread ids hidden now, none while HushGram is paused. */
    private static Set<String> ids() {
        Set<String> ids = new HashSet<>();
        if (!Utils.settingsReady()) return ids;
        String text = Settings.HIDDEN_CHATS.get();
        if (text.isEmpty()) return ids;
        for (ChatLocks.Chat chat : ChatList.parse(text)) ids.add(chat.id);
        return ids;
    }

    /** The hidden chats, oldest first, none while HushGram is paused. */
    public static List<ChatLocks.Chat> chats() {
        try {
            if (Utils.settingsReady()) return ChatList.parse(Settings.HIDDEN_CHATS.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The chats hidden as chosen, paused or not. The settings screen shows these. */
    public static List<ChatLocks.Chat> saved() {
        try {
            if (Utils.settingsReady()) return ChatList.parse(Settings.HIDDEN_CHATS.savedValue());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The chat with [id] is hidden now. */
    public static boolean listed(String id) {
        return ChatList.contains(chats(), id);
    }

    /** The chat with [id] is on the chosen list, paused or not. */
    public static boolean savedListed(String id) {
        return ChatList.contains(saved(), id);
    }

    /** The last chat opened, if it isn't hidden. */
    public static ChatLocks.Chat lastOpened() {
        return ChatLocks.lastOpenedUnless(HiddenChats::savedListed);
    }

    /** Hides a chat, or renames it on the list. */
    public static void add(String id, String name) {
        Settings.HIDDEN_CHATS.save(ChatList.added(saved(), id, name));
    }

    /** Brings a chat back. */
    public static void remove(String id) {
        Settings.HIDDEN_CHATS.save(ChatList.removed(saved(), id));
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        reader = HiddenChats::threadId;
        searchReader = HiddenChats::targetThreadId;
    }
}
