/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.shared.L10n;

/**
 * The text a list of chats is kept in, shared by the locked chats ({@link ChatLocks}) and the hidden
 * ones ({@link HiddenChats}): a line for each chat, its thread id, a tab, and the name the chat had
 * when it was added. Reading never throws, and a line with no id is skipped.
 */
final class ChatList {
    private ChatList() {
    }

    /** The chats in [text], oldest first. A chat with no name is called {@link #placeholder}. */
    static List<ChatLocks.Chat> parse(String text) {
        List<ChatLocks.Chat> chats = new ArrayList<>();
        if (text == null) return chats;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            String id = (tab < 0 ? line : line.substring(0, tab)).trim();
            if (id.isEmpty()) continue;
            String name = tab < 0 ? "" : line.substring(tab + 1).trim();
            chats.add(new ChatLocks.Chat(id, name.isEmpty() ? placeholder(id) : name));
        }
        return chats;
    }

    /** [chats] with [id] added, or renamed when it is there. */
    static String added(List<ChatLocks.Chat> chats, String id, String name) {
        StringBuilder text = new StringBuilder();
        boolean found = false;
        for (ChatLocks.Chat chat : chats) {
            boolean same = chat.id.equals(id);
            found |= same;
            append(text, chat.id, same ? name : chat.name);
        }
        if (!found) append(text, id, name);
        return text.toString();
    }

    /** [chats] without [id]. */
    static String removed(List<ChatLocks.Chat> chats, String id) {
        StringBuilder text = new StringBuilder();
        for (ChatLocks.Chat chat : chats) {
            if (!chat.id.equals(id)) append(text, chat.id, chat.name);
        }
        return text.toString();
    }

    static boolean contains(List<ChatLocks.Chat> chats, String id) {
        for (ChatLocks.Chat chat : chats) {
            if (chat.id.equals(id)) return true;
        }
        return false;
    }

    private static void append(StringBuilder text, String id, String name) {
        if (text.length() > 0) text.append('\n');
        text.append(clean(id)).append('\t').append(clean(name));
    }

    static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').trim();
    }

    /** What a chat is called when its name was never found. */
    static String placeholder(String id) {
        String tail = id.length() > 4 ? id.substring(id.length() - 4) : id;
        return L10n.f("Chat %1$s", tail);
    }
}
