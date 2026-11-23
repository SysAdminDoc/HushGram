/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Chat groups (discussion #838): groups of chats you name yourself, kept on the phone.
 *
 * <p>A chat's long press menu files it in a group ({@link #pickFor}), and a chat in a group leaves
 * the main inbox. A long press on Messages' button ({@link HiddenChatsTap}) picks a group to show in
 * the inbox by itself, or all chats again ({@link #pick}). Leaving Instagram goes back to all chats.
 * The inbox does the filtering through the hidden chats' list hook ({@link HiddenChats#inbox}), so
 * nothing is asked of Instagram's server: search, the folder counts, the unread badge and
 * notifications see a grouped chat as before.
 *
 * <p>The list is {@link Settings#GROUPED_CHATS}: {@link #HEADER}, then a line for each chat with the
 * account it was grouped on, a tab, its thread id, a tab, the group's name, a tab and the chat's
 * name. Each account sees its own groups. A paused HushGram reads an empty list, and a backup leaves
 * the list out like the hidden chats. Nothing here throws into Instagram.
 */
public final class ChatGroups {
    /** The first line of the list. */
    static final String HEADER = "#groups 1";

    /** The longest group name kept, in characters. */
    static final int MAX_NAME = 40;

    /** What is counted in the diagnostic report. */
    static final String LEFT_OUT = "grouped chats left out of the inbox";
    static final String SHOWN = "inbox showing one chat group";
    static final String MOVED = "chat moved to a group";

    /** Steps a failure is reported under. */
    static final String LIST = "chat groups";
    static final String PICK = "chat group picker";
    static final String MOVE = "chat group move";

    /** One chat in a group. */
    static final class Row {
        final String account;
        final String id;
        final String group;
        final String name;

        Row(String account, String id, String group, String name) {
            this.account = account;
            this.id = id;
            this.group = group;
            this.name = name;
        }
    }

    /** The group the inbox shows by itself, or null for all chats. */
    private static volatile String showing;

    /** The list as last parsed, with the text it came from. */
    private static volatile Parsed parsed;

    private static final class Parsed {
        final String raw;
        final List<Row> rows;

        Parsed(String raw, List<Row> rows) {
            this.raw = raw;
            this.rows = rows;
        }
    }

    private ChatGroups() {
    }

    /** Chat groups is on and HushGram isn't paused. */
    static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.CHAT_GROUPS.get();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the list

    /** The chats in [text], in the order they were grouped. A line without an account, id or group is skipped. */
    static List<Row> parse(@Nullable String text) {
        List<Row> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) continue;
            String account = parts[0].trim();
            String id = parts[1].trim();
            String group = clean(parts[2]);
            if (account.isEmpty() || id.isEmpty() || group == null) continue;
            rows.add(new Row(account, id, group, parts.length > 3 ? flat(parts[3]) : ""));
        }
        return rows;
    }

    /** [rows] as the text they're kept in. */
    static String format(List<Row> rows) {
        StringBuilder text = new StringBuilder(HEADER);
        for (Row row : rows) {
            text.append('\n').append(row.account).append('\t').append(row.id).append('\t')
                    .append(row.group).append('\t').append(flat(row.name));
        }
        return text.toString();
    }

    /**
     * A typed group name as it's kept: spaces, tabs and line breaks run together into one space,
     * trimmed, and cut at {@link #MAX_NAME} characters. Null when nothing is left.
     */
    @Nullable
    static String clean(@Nullable String typed) {
        if (typed == null) return null;
        String name = flat(typed);
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME).trim();
        return name.isEmpty() ? null : name;
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Every account's grouped chats now: none while Chat groups is off or HushGram is paused. */
    private static List<Row> rows() {
        if (!switchedOn()) return Collections.emptyList();
        String text = Settings.GROUPED_CHATS.get();
        if (text.isEmpty()) return Collections.emptyList();
        Parsed kept = parsed;
        if (kept == null || !kept.raw.equals(text)) {
            kept = new Parsed(text, Collections.unmodifiableList(parse(text)));
            parsed = kept;
        }
        return kept.rows;
    }

    /** [account]'s groups, in the order they were started, each once. None for an account that isn't known. */
    static List<String> groups(@Nullable String account) {
        List<String> groups = new ArrayList<>();
        if (empty(account)) return groups;
        for (Row row : rows()) {
            if (row.account.equals(account) && find(groups, row.group) == null) groups.add(row.group);
        }
        return groups;
    }

    /** The group [id] is in on [account], or null. */
    @Nullable
    static String groupOf(@Nullable String account, @Nullable String id) {
        if (empty(account) || empty(id)) return null;
        for (Row row : rows()) {
            if (row.account.equals(account) && row.id.equals(id)) return row.group;
        }
        return null;
    }

    /** The ids of [account]'s chats in any group, which the inbox leaves out while it shows all chats. */
    static Set<String> grouped(@Nullable String account) {
        if (empty(account)) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        for (Row row : rows()) {
            if (row.account.equals(account)) ids.add(row.id);
        }
        return ids;
    }

    /** The ids of [account]'s chats in [group]. */
    static Set<String> in(@Nullable String account, @Nullable String group) {
        if (empty(account) || group == null) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        for (Row row : rows()) {
            if (row.account.equals(account) && row.group.equalsIgnoreCase(group)) ids.add(row.id);
        }
        return ids;
    }

    /**
     * Files [account]'s chat [id], named [name], in [group], or takes it out of its group when
     * [group] is null. A group whose name differs only in case from one [account] has is that one.
     * False, with nothing changed, for an account that isn't known, while Chat groups is off, or
     * when the list couldn't be saved.
     */
    static boolean move(@Nullable String account, @Nullable String id, @Nullable String name, @Nullable String group) {
        try {
            if (empty(account) || empty(id) || !switchedOn()) return false;
            String into = group == null ? null : clean(group);
            if (group != null && into == null) return false;
            String same = into == null ? null : find(groups(account), into);
            if (same != null) into = same;
            List<Row> rows = new ArrayList<>();
            for (Row row : parse(Settings.GROUPED_CHATS.savedValue())) {
                if (!(row.account.equals(account) && row.id.equals(id))) rows.add(row);
            }
            if (into != null) rows.add(new Row(account, id, into, name == null ? "" : name));
            if (!Settings.GROUPED_CHATS.save(rows.isEmpty() ? "" : format(rows))) return false;
            String shown = showing;
            if (shown != null && in(account, shown).isEmpty()) showing = null;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, MOVED);
            HiddenChats.changedSoon();
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, MOVE, t);
            return false;
        }
    }

    /** The name among [groups] that [name] matches, ignoring case, or null. */
    @Nullable
    private static String find(List<String> groups, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (String group : groups) {
            if (group.toLowerCase(Locale.ROOT).equals(wanted)) return group;
        }
        return null;
    }

    // ---------------------------------------------------------------- the group shown

    /** The group the inbox shows by itself on [account], or null for all chats. */
    @Nullable
    static String showing(@Nullable String account) {
        String shown = showing;
        if (shown == null || !switchedOn()) return null;
        return find(groups(account), shown);
    }

    /** The inbox shows [group] by itself, or all chats when it's null. Hidden chats a double tap showed leave first. */
    static void show(@Nullable String group) {
        showing = group;
        if (group != null) HiddenChats.conceal();
        HiddenChats.changedSoon();
    }

    /** Instagram left the screen, or the hidden chats came into view: back to all chats. */
    static void left() {
        if (showing == null) return;
        showing = null;
        HiddenChats.changedSoon();
    }

    // ---------------------------------------------------------------- the dialogs

    /**
     * The long press on Messages' button: a list of the account's groups with All chats on top, and
     * the inbox shows the one picked by itself.
     */
    static void pick(Activity activity) {
        try {
            String account = ChatAccounts.active();
            final List<String> groups = groups(account);
            if (groups.isEmpty()) {
                Utils.showToastShort(L10n.t("Long press a chat to group it"));
                return;
            }
            String[] rows = new String[groups.size() + 1];
            rows[0] = L10n.t("All chats");
            for (int i = 0; i < groups.size(); i++) rows[i + 1] = groups.get(i);
            String shown = showing(account);
            int checked = shown == null ? 0 : groups.indexOf(shown) + 1;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Chat groups"))
                    .setSingleChoiceItems(rows, checked, (box, which) -> {
                        box.dismiss();
                        if (which <= 0) {
                            show(null);
                            Utils.showToastShort(L10n.t("Showing all chats"));
                        } else {
                            String group = groups.get(which - 1);
                            show(group);
                            Utils.showToastShort(L10n.f("Showing %1$s", L10n.isolate(group)));
                        }
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.show();
            ScreenColors.dialog(dialog);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PICK, t);
        }
    }

    /**
     * A chat's long press menu row: No group and each of the account's groups, the chat's own
     * ticked, with New group to start one. [hide] is Hide chat or Unhide chat, offered beside them
     * under [hideLabel] when the double tap for hidden chats is on, and null otherwise.
     */
    static void pickFor(Activity activity, final String account, final String id, final String name,
                        @Nullable final Runnable hide, @Nullable String hideLabel) {
        final List<String> groups = groups(account);
        String[] rows = new String[groups.size() + 1];
        rows[0] = L10n.t("No group");
        for (int i = 0; i < groups.size(); i++) rows[i + 1] = groups.get(i);
        String current = groupOf(account, id);
        int checked = current == null ? 0 : groups.indexOf(current) + 1;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(name)
                .setSingleChoiceItems(rows, checked, (box, which) -> {
                    box.dismiss();
                    moveAndSay(account, id, name, which <= 0 ? null : groups.get(which - 1));
                })
                .setPositiveButton(L10n.t("New group"), (box, which) -> askName(activity, account, id, name))
                .setNegativeButton(L10n.t("Cancel"), null);
        if (hide != null && hideLabel != null) builder.setNeutralButton(hideLabel, (box, which) -> hide.run());
        AlertDialog dialog = builder.create();
        dialog.show();
        ScreenColors.dialog(dialog);
    }

    /** Asks for a new group's name, and files the chat in it. */
    private static void askName(Activity activity, String account, String id, String name) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            final EditText field = new EditText(activity);
            field.setHint(L10n.t("Group name"));
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            ScreenColors.styleField(field);
            FrameLayout frame = new FrameLayout(activity);
            int side = Math.round(20 * activity.getResources().getDisplayMetrics().density);
            frame.setPadding(side, side / 2, side, 0);
            frame.addView(field);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("New group"))
                    .setView(frame)
                    .setPositiveButton(L10n.t("Save"), (box, which) -> {
                        String group = clean(field.getText().toString());
                        if (group != null) moveAndSay(account, id, name, group);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
            ScreenColors.dialog(dialog);
            field.requestFocus();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PICK, t);
        }
    }

    private static void moveAndSay(String account, String id, String name, @Nullable String group) {
        if (!move(account, id, name, group)) {
            Utils.showToastShort(L10n.t("Couldn't save the chat."));
            return;
        }
        String kept = group == null ? null : groupOf(account, id);
        Utils.showToastShort(kept == null
                ? L10n.f("%1$s is back in your inbox", L10n.isolate(name))
                : L10n.f("%1$s is in %2$s", L10n.isolate(name), L10n.isolate(kept)));
    }

    private static boolean empty(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        showing = null;
        parsed = null;
    }
}
