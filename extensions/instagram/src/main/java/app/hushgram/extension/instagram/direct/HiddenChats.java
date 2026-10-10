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
 * <p>A hidden chat is left out of the thread summaries Instagram's inbox reads ({@link #filter}),
 * out of the inbox search's results and message matches ({@link #searchResults},
 * {@link #searchHits}), and a push for it isn't posted at all ({@link ChatLocks#track} asks {@link #hides}). The list is
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
    static final String LEFT_OUT_SEARCH = "hidden chats left out of search";
    static final String SILENCED = "hidden chat notifications dropped";

    /** Steps a failure is reported under. */
    static final String LIST = "hidden chats";
    static final String FILTER = "inbox filter";
    static final String SEARCH = "inbox search filter";

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
     * Asked with the list of thread summaries Instagram's inbox is about to read. A list with no
     * hidden chat in it comes back as it is. Otherwise a copy without the hidden ones does, and
     * Instagram's own list is left as it was.
     */
    public static ArrayList<Object> filter(ArrayList<Object> summaries) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (summaries == null || summaries.isEmpty()) return summaries;
            Set<String> hidden = ids();
            if (hidden.isEmpty()) return summaries;
            ArrayList<Object> shown = new ArrayList<>(summaries.size());
            for (Object summary : summaries) {
                String id = idOf(summary);
                if (id == null || !hidden.contains(id)) shown.add(summary);
            }
            if (shown.size() != summaries.size()) HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT);
            return shown;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, FILTER, t);
            return summaries;
        }
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
