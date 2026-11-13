/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hide chat and Unhide chat in a chat's long press menu (#661), while the double-tap on Messages
 * ({@link HiddenChatsTap}) is on, since that's how hidden chats come back into view.
 *
 * <p>The row is Instagram's own Details row of the menu, which the release app has among its menu
 * actions and never offers. The patch hands the menu builder's list of rows here, the one Preview
 * chats offers its row in: by the time the menu opens it's handed a read-only copy kept with the
 * chat's row in the inbox. As the menu opens the patch hands over the chat's key and the name at the
 * top of the menu, then relabels the row and takes its tap. A chat the inbox shows by itself while
 * hidden chats are showing reads Unhide chat, any other Hide chat.
 */
public final class HiddenChatsMenu {
    /** The menu row borrowed: Instagram builds it but nothing in the release app shows it. */
    static final String ROW = "DETAILS";

    static final String OFFER = "hide chat row";
    static final String SHOWN = "hide chat menu";
    static final String LABEL = "hide chat label";
    static final String TAP = "hide chat tap";

    /** Reads a chat's id from Instagram's key for it, which tests swap for a stand-in. */
    interface Reader {
        String id(Object key);
    }

    static volatile Reader reader = HiddenChatsMenu::threadId;

    /** The chat whose menu is open, and its name as the menu shows it. */
    private static volatile String menuId;
    private static volatile String menuName;

    private HiddenChatsMenu() {
    }

    /**
     * The chat's id inside Instagram's key for it. The patch writes the body, reading the key's id
     * field. Null here, unpatched.
     */
    public static String threadId(Object key) {
        return null;
    }

    /**
     * Called first as Instagram builds a chat's long press menu, with the builder's own list of rows
     * and the class of Instagram's menu actions. Adds the row after any already there, once.
     */
    public static void offer(List<Object> rows, Object menu) {
        offer(rows, menu, HiddenChatsMenu::switchedOn);
    }

    static void offer(List<Object> rows, Object menu, BooleanSupplier on) {
        try {
            if (rows == null || !(menu instanceof Class) || !on.getAsBoolean()) return;
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            Object row = row((Class<?>) menu);
            if (row != null && !rows.contains(row)) rows.add(row);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, OFFER, t);
        }
    }

    /**
     * Called first as a chat's long press menu opens, with the chat's key and the name over the menu,
     * so the row reads right for that chat and a tap saves it under that name.
     */
    public static void shown(Object key, String title) {
        try {
            menuId = null;
            menuName = title;
            menuId = key == null ? null : reader.id(key);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SHOWN, t);
        }
    }

    /**
     * Called as the menu's labels are handed back, with its rows and their labels in the same order.
     * Gives the borrowed row Hide chat or Unhide chat.
     */
    public static void label(List<Object> rows, String[] labels) {
        label(rows, labels, HiddenChatsMenu::switchedOn);
    }

    static void label(List<Object> rows, String[] labels, BooleanSupplier on) {
        try {
            if (rows == null || labels == null || !on.getAsBoolean()) return;
            int count = Math.min(rows.size(), labels.length);
            for (int at = 0; at < count; at++) {
                if (isRow(rows.get(at))) labels[at] = L10n.t(hidden(menuId) ? "Unhide chat" : "Hide chat");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, LABEL, t);
        }
    }

    /**
     * Called first when a row of the menu is tapped, with the row and the chat's key. True when it was
     * Hide chat or Unhide chat and it's done here, so Instagram's code for the row doesn't run.
     */
    public static boolean tap(Object chosen, Object key) {
        return tap(chosen, key, HiddenChatsMenu::switchedOn);
    }

    static boolean tap(Object chosen, Object key, BooleanSupplier on) {
        try {
            if (!isRow(chosen) || !on.getAsBoolean()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, TAP, t);
            return false;
        }
        try {
            String id = key == null ? null : reader.id(key);
            if (id == null || id.isEmpty()) {
                Utils.showToastShort(L10n.t("Couldn't save the chat."));
                return true;
            }
            String account = ChatAccounts.active();
            String name = id.equals(menuId) && menuName != null && !menuName.trim().isEmpty()
                    ? menuName.trim() : ChatLocks.placeholder(id);
            if (hidden(id)) {
                if (!HiddenChats.remove(account, id)) {
                    Utils.showToastShort(L10n.t("Couldn't save the chat."));
                    return true;
                }
                Utils.showToastShort(L10n.f("%1$s is shown again", L10n.isolate(name)));
                if (HiddenChats.saved(account).isEmpty()) HiddenChats.conceal();
            } else {
                if (!HiddenChats.add(account, id, name)) {
                    Utils.showToastShort(L10n.t("Couldn't save the chat."));
                    return true;
                }
                Utils.showToastShort(L10n.f("%1$s is hidden", L10n.isolate(name)));
            }
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, TAP);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, TAP, t);
            Utils.showToastShort(L10n.t("Couldn't save the chat."));
        }
        // Instagram's own Details row has nothing to open in a release build.
        return true;
    }

    private static boolean hidden(String id) {
        return id != null && HiddenChats.savedListed(ChatAccounts.active(), id);
    }

    /** The borrowed constant among [menu]'s, or null when this build has none. */
    static Object row(Class<?> menu) {
        Object[] constants = menu.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if (isRow(constant)) return constant;
        }
        return null;
    }

    static boolean isRow(Object row) {
        return row instanceof Enum && ROW.equals(((Enum<?>) row).name());
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.DOUBLE_TAP_HIDDEN_CHATS.get();
    }

    static void resetForTests() {
        reader = HiddenChatsMenu::threadId;
        menuId = null;
        menuName = null;
    }
}
