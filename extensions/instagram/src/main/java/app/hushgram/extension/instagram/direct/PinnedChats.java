/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The chats you pinned to the top of your Messages inbox (#345), kept on this phone only.
 *
 * <p>Instagram's own Pin only keeps a chat on top for the people who see it, so this is HushGram's:
 * the inbox's list of chats is handed through {@link HiddenChats#inbox} before it becomes rows, and
 * the pinned chats are moved to the front of it, in the order they were pinned, every other chat
 * after them in the order Instagram gave. Instagram's own list is left as it was. The list is
 * {@link Settings#PINNED_CHATS}, in the same lines as the hidden chats ({@link ChatList}), for the
 * account a chat was pinned on and no other.
 *
 * <p>A list with a chat on it is the switch. Paused, the setting answers an empty list, so the inbox
 * is in Instagram's order until HushGram is turned on again. Nothing here throws into Instagram.
 */
public final class PinnedChats {
    /** What is counted in the diagnostic report. */
    static final String MOVED = "pinned chats moved to the top of the inbox";

    /** The step a failure is reported under. */
    static final String LIST = "pinned chats";

    private PinnedChats() {
    }

    // ---------------------------------------------------------------- the inbox

    /**
     * [summaries] with the chats pinned on [account] first, in pin order. The same list when none is
     * pinned, none of the pinned chats is in it or they already lead it. Never throws.
     */
    static List<Object> first(List<Object> summaries, List<String> pins) {
        try {
            if (pins.isEmpty() || summaries == null || summaries.size() < 2) return summaries;
            Map<String, Object> found = new LinkedHashMap<>();
            List<Object> rest = new ArrayList<>(summaries.size());
            for (Object summary : summaries) {
                String id = HiddenChats.idOf(summary);
                if (id != null && pins.contains(id) && !found.containsKey(id)) {
                    found.put(id, summary);
                } else {
                    rest.add(summary);
                }
            }
            if (found.isEmpty()) return summaries;
            List<Object> arranged = new ArrayList<>(summaries.size());
            for (String id : pins) {
                Object summary = found.get(id);
                if (summary != null) arranged.add(summary);
            }
            arranged.addAll(rest);
            for (int i = 0; i < arranged.size(); i++) {
                if (arranged.get(i) != summaries.get(i)) {
                    HookStatus.counted(FamilyNames.MESSAGES_LOCK, MOVED);
                    return arranged;
                }
            }
            return summaries;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return summaries;
        }
    }

    /** The thread ids pinned now on the account in front, in the order they were pinned: none while paused. */
    static List<String> ids() {
        return idsFor(ChatAccounts.active());
    }

    private static List<String> idsFor(@Nullable String account) {
        try {
            if (!Utils.settingsReady()) return Collections.emptyList();
            String text = Settings.PINNED_CHATS.get();
            if (text.isEmpty()) return Collections.emptyList();
            Set<String> ids = new LinkedHashSet<>();
            for (ChatList.Row row : ChatList.parse(text)) {
                if (row.protects(account)) ids.add(row.id);
            }
            return new ArrayList<>(ids);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------------------- the list

    /** The chats pinned on [account] as chosen, paused or not, in pin order. */
    public static List<ChatLocks.Chat> saved(@Nullable String account) {
        try {
            if (Utils.settingsReady()) {
                return ChatList.shown(ChatList.parse(Settings.PINNED_CHATS.savedValue()), account);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return new ArrayList<>();
    }

    /** The same for the account in front. */
    public static List<ChatLocks.Chat> saved() {
        return saved(ChatAccounts.active());
    }

    /** The chat with [id] is pinned on [account], paused or not. */
    public static boolean savedListed(@Nullable String account, String id) {
        try {
            if (Utils.settingsReady()) {
                return ChatList.protects(ChatList.parse(Settings.PINNED_CHATS.savedValue()), account, id);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
        }
        return false;
    }

    /** The last chat opened on [account], if it isn't pinned there. */
    public static ChatLocks.Chat lastOpened(@Nullable String account) {
        return ChatLocks.lastOpenedUnless(account, PinnedChats::savedListed);
    }

    /** The same for the account in front. */
    public static ChatLocks.Chat lastOpened() {
        return lastOpened(ChatAccounts.active());
    }

    /**
     * Pins a chat on [account], under [name], below the ones pinned before it, and the inbox draws it
     * at the top. False, with nothing changed, for an account that isn't known, before the settings
     * are read, or when the list couldn't be saved.
     */
    public static boolean add(@Nullable String account, String id, String name) {
        if (empty(account) || empty(id)) return false;
        boolean saved = change(rows -> ChatList.added(rows, account, id, name));
        if (saved) HiddenChats.changedSoon();
        return saved;
    }

    /** Unpins a chat on [account], and the inbox puts it back where Instagram has it. */
    public static boolean remove(@Nullable String account, String id) {
        if (empty(account) || empty(id)) return false;
        boolean saved = change(rows -> ChatList.removed(rows, account, id));
        if (saved) HiddenChats.changedSoon();
        return saved;
    }

    private static boolean change(UnaryOperator<List<ChatList.Row>> edit) {
        try {
            if (!Utils.settingsReady()) return false;
            List<ChatList.Row> rows = ChatList.parse(Settings.PINNED_CHATS.savedValue());
            List<ChatList.Row> changed = edit.apply(rows);
            return changed == rows || Settings.PINNED_CHATS.save(ChatList.format(changed));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LIST, t);
            return false;
        }
    }

    private static boolean empty(String text) {
        return text == null || text.trim().isEmpty();
    }
}
