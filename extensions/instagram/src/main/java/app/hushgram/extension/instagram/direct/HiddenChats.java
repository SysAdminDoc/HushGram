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
 * and a push for it isn't posted at all ({@link ChatLocks#track} asks {@link #hides}). The list is
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
    static final String SILENCED = "hidden chat notifications dropped";

    /** Steps a failure is reported under. */
    static final String LIST = "hidden chats";
    static final String FILTER = "inbox filter";

    /** Reads the thread id of one of the inbox's thread summaries. Tests put a fake in. */
    interface Reader {
        String threadId(Object summary);
    }

    static volatile Reader reader = HiddenChats::threadId;

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
    }
}
