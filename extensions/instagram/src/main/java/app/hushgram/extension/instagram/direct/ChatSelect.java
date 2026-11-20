/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Select messages (#778): a list of the messages a chat has loaded, each with a box to tick, and
 * Copy, Share and Save for the ticked ones. Instagram has no way to pick more than one message, so
 * the picker is HushGram's own, over the messages {@link ChatHistory} reads. Nothing is sent: Copy
 * puts the messages on the clipboard, Share hands them to Android's share sheet and Save writes them
 * to a text file the way Export chat does. Main thread.
 */
public final class ChatSelect {
    static final String WHERE = "chat select";
    /** The most characters of a message one row of the list shows. */
    static final int ROW_CHARS = 160;
    /** Tags the Select all switch in the dialog's title, for tests. */
    static final String ALL_TAG = "hushgram_select_all";
    static final String COUNT_COPIED = "picked messages copied";
    static final String COUNT_SHARED = "picked messages shared";
    static final String COUNT_SAVED = "picked messages saved";

    private ChatSelect() {
    }

    /** The ticked ones of [items], in order. */
    static <T> List<T> picked(List<T> items, boolean[] ticked) {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < items.size() && i < ticked.length; i++) {
            if (ticked[i]) out.add(items.get(i));
        }
        return out;
    }

    /** How many of [ticked] are ticked. */
    static int count(boolean[] ticked) {
        int count = 0;
        for (boolean each : ticked) if (each) count++;
        return count;
    }

    /** One message as a row of the list: its line on one line, cut at {@link #ROW_CHARS}. */
    static String row(String line) {
        String flat = line.replace("\n  ", " ").replace('\n', ' ');
        return flat.length() <= ROW_CHARS ? flat : flat.substring(0, ROW_CHARS).trim() + "...";
    }

    /** What Copy and Share hand over: one line per message, oldest first, as Export chat writes them. */
    static String text(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    /** Shows the picker over [entries], the chat's loaded messages oldest first, from the chat named [title]. */
    public static void show(final Activity activity, final String title, final List<ChatHistory.Entry> entries) {
        final List<String> names = ChatHistory.names(entries, title, L10n.t("You"));
        final List<String> lines = new ArrayList<>(entries.size());
        String[] rows = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            lines.add(ChatHistory.line(entries.get(i), names.get(i)));
            rows[i] = row(lines.get(i));
        }
        final boolean[] ticked = new boolean[rows.length];
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        final TextView all = new TextView(builder.getContext());
        builder.setCustomTitle(titleBar(builder.getContext(), all))
                .setMultiChoiceItems(rows, ticked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        ticked[which] = isChecked;
                        label(all, ticked);
                    }
                })
                .setPositiveButton(L10n.t("Copy"), null)
                .setNeutralButton(L10n.t("Share"), null)
                .setNegativeButton(L10n.t("Save"), null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        final ListView list = dialog.getListView();
        label(all, ticked);
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean tick = count(ticked) < ticked.length;
                for (int i = 0; i < ticked.length; i++) {
                    ticked[i] = tick;
                    if (list != null) list.setItemChecked(i, tick);
                }
                label(all, ticked);
            }
        });
        // The newest messages are at the bottom, as in the chat.
        if (list != null && rows.length > 0) list.setSelection(rows.length - 1);
        button(dialog, AlertDialog.BUTTON_POSITIVE, new Action() {
            @Override public void run(List<ChatHistory.Entry> chosen, List<String> chosenLines, List<String> chosenNames) {
                copy(activity, text(chosenLines), chosen.size());
            }
        }, entries, lines, names, ticked);
        button(dialog, AlertDialog.BUTTON_NEUTRAL, new Action() {
            @Override public void run(List<ChatHistory.Entry> chosen, List<String> chosenLines, List<String> chosenNames) {
                share(activity, text(chosenLines));
            }
        }, entries, lines, names, ticked);
        button(dialog, AlertDialog.BUTTON_NEGATIVE, new Action() {
            @Override public void run(List<ChatHistory.Entry> chosen, List<String> chosenLines, List<String> chosenNames) {
                ChatPager.save(activity.getApplicationContext(), title, ChatHistory.document(chosen, chosenNames, title));
                HookStatus.counted(FamilyNames.CHAT_HISTORY, COUNT_SAVED);
            }
        }, entries, lines, names, ticked);
    }

    private interface Action {
        void run(List<ChatHistory.Entry> chosen, List<String> chosenLines, List<String> chosenNames);
    }

    /** Gives a dialog button its action: on the ticked messages, closing the picker, or a word when none is ticked. */
    private static void button(final AlertDialog dialog, int which, final Action action, final List<ChatHistory.Entry> entries,
                               final List<String> lines, final List<String> names, final boolean[] ticked) {
        Button button = dialog.getButton(which);
        if (button == null) return;
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (count(ticked) == 0) {
                    Utils.showToastShort(L10n.t("Pick a message first."));
                    return;
                }
                try {
                    action.run(picked(entries, ticked), picked(lines, ticked), picked(names, ticked));
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.CHAT_HISTORY, WHERE, t);
                }
                dialog.dismiss();
            }
        });
    }

    private static void copy(Context context, String text, int count) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) throw new IllegalStateException("no clipboard");
        clipboard.setPrimaryClip(ClipData.newPlainText("HushGram", text));
        HookStatus.counted(FamilyNames.CHAT_HISTORY, COUNT_COPIED);
        Utils.showToastShort(L10n.quantity(context, count, "Copied %1$d message.", "Copied %1$d messages."));
    }

    private static void share(Activity activity, String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, L10n.t("Share")));
        HookStatus.counted(FamilyNames.CHAT_HISTORY, COUNT_SHARED);
    }

    /** "Select all" while some message isn't ticked, "Clear" once every one is. */
    private static void label(TextView all, boolean[] ticked) {
        all.setText(ticked.length > 0 && count(ticked) == ticked.length ? L10n.t("Clear") : L10n.t("Select all"));
    }

    /** The dialog's title, Select messages, with the Select all switch at its end. */
    private static View titleBar(Context context, TextView all) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(24 * density), Math.round(18 * density), Math.round(12 * density), Math.round(6 * density));
        TextView heading = new TextView(context);
        heading.setText(L10n.t("Select messages"));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        all.setTag(ALL_TAG);
        all.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int pad = Math.round(10 * density);
        all.setPadding(pad, pad, pad, pad);
        TypedValue accent = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)) {
            if (accent.type >= TypedValue.TYPE_FIRST_COLOR_INT && accent.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                all.setTextColor(accent.data);
            } else if (accent.resourceId != 0) {
                all.setTextColor(context.getColorStateList(accent.resourceId));
            }
        }
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) && ripple.resourceId != 0) {
            all.setBackgroundResource(ripple.resourceId);
        }
        all.setClickable(true);
        bar.addView(all, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }
}
