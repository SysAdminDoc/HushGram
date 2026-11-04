/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Preview in a chat's long press menu: the chat's newest messages in a box over your messages, read
 * from what this phone already keeps for it. The chat isn't opened, so nothing is fetched and no
 * seen receipt goes out. A reply can be written and sent from the box, and Mark as read sends the
 * receipt when "Read messages without the seen receipt" holds them. View once photos and videos
 * show only as a label, never their content.
 */
public final class ChatPreview {
    /**
     * Instagram's own menu row the preview borrows: its thread debug row, which the release app has
     * in its menu enum and never offers. The patch relabels it and takes its tap.
     */
    static final String ROW = "THREAD_LEVEL_DEBUG";
    /** How many of the newest messages the box shows. */
    static final int SHOWN = 30;
    /** How long after a reply the box reads the chat again, so the reply shows in it. */
    static final long RELOAD_MS = 800;

    static final String OFFER = "preview row";
    static final String LABEL = "preview label";
    static final String OPEN = "open a preview";
    static final String OPENED = "preview opened";
    static final String LOAD = "load a preview";
    static final String SEND = "reply from a preview";
    static final String MARK = "mark a preview read";

    /** What the box reads and sends through, which tests swap for stand-ins. */
    interface Access {
        List<Object> messages(Object session, Object key);

        void send(Object session, Object key, String text);

        boolean isMessage(Object message);

        String text(Object message);

        String time(Object message);

        String kind(Object message);

        boolean mine(Object message);

        String id(Object message);

        String sender(Object message);

        String account(Object session);
    }

    static final Access PATCHED = new Access() {
        @Override
        public List<Object> messages(Object session, Object key) {
            return PreviewMessages.messages(session, key, true);
        }

        @Override
        public void send(Object session, Object key, String text) {
            PreviewMessages.send(session, key, text, null);
        }

        @Override
        public boolean isMessage(Object message) {
            return PreviewMessages.isMessage(message);
        }

        @Override
        public String text(Object message) {
            return PreviewMessages.text(message);
        }

        @Override
        public String time(Object message) {
            return PreviewMessages.time(message);
        }

        @Override
        public String kind(Object message) {
            return PreviewMessages.kind(message);
        }

        @Override
        public boolean mine(Object message) {
            return PreviewMessages.sentByViewer(message);
        }

        @Override
        public String id(Object message) {
            return PreviewMessages.id(message);
        }

        @Override
        public String sender(Object message) {
            return PreviewMessages.sender(message);
        }

        @Override
        public String account(Object session) {
            return InstagramChats.accountId(session);
        }
    };

    static volatile Access access = PATCHED;

    /** One message as the box shows it, with Instagram's message kept for Mark as read. */
    static final class Line {
        final String text;
        final long micros;
        final boolean mine;
        final Object message;

        Line(String text, long micros, boolean mine, Object message) {
            this.text = text;
            this.micros = micros;
            this.mine = mine;
            this.message = message;
        }
    }

    private ChatPreview() {
    }

    /**
     * Called first in the builder of a chat's long press menu, with its rows and the class of
     * Instagram's menu actions. Puts Preview at the top, once, while the switch is on.
     */
    public static void offer(List<Object> rows, Object menu) {
        offer(rows, menu, ChatPreview::switchedOn);
    }

    static void offer(List<Object> rows, Object menu, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.PREVIEW_CHATS);
            if (rows == null || !(menu instanceof Class) || !on.getAsBoolean()) return;
            Object row = row((Class<?>) menu);
            if (row != null && !rows.contains(row)) rows.add(0, row);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, OFFER, t);
        }
    }

    /**
     * Called as the long press menu's labels are handed back, with its rows and their labels in the
     * same order. Gives the borrowed row its Preview label while the switch is on.
     */
    public static void label(List<Object> rows, String[] labels) {
        label(rows, labels, ChatPreview::switchedOn);
    }

    static void label(List<Object> rows, String[] labels, BooleanSupplier on) {
        try {
            if (rows == null || labels == null || !on.getAsBoolean()) return;
            int count = Math.min(rows.size(), labels.length);
            for (int at = 0; at < count; at++) {
                if (isRow(rows.get(at))) labels[at] = L10n.t("Preview");
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, LABEL, t);
        }
    }

    /**
     * Called first when a row of the long press menu is tapped, with the row, the account and the
     * chat's key. True when the row was Preview and the box is handled here, so Instagram's code for
     * the row doesn't run. Never throws.
     */
    public static boolean open(Object chosen, Object session, Object key) {
        return open(chosen, session, key, ChatPreview::switchedOn);
    }

    static boolean open(Object chosen, Object session, Object key, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.PREVIEW_CHATS);
            if (!isRow(chosen) || !on.getAsBoolean()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, OPEN, t);
            return false;
        }
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Utils.showToastShort(L10n.t("Couldn't load this chat"));
                return true;
            }
            show(activity, session, key, ThreadSeen.canMarkRead(session, key));
            HookStatus.counted(FamilyNames.PREVIEW_CHATS, OPENED);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, OPEN, t);
            Utils.showToastShort(L10n.t("Couldn't load this chat"));
        }
        // Instagram's own code for its debug row has nothing to show in a release build.
        return true;
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

    /**
     * The chat's newest messages, oldest first, at most {@link #SHOWN}, or null when the store can't
     * be read. Instagram's notes of what happened in the chat, like a reaction, are left out.
     */
    static List<Line> read(Object session, Object key, Access access) {
        List<Object> all = access.messages(session, key);
        if (all == null) return null;
        List<Object> messages = new ArrayList<>();
        for (Object message : new ArrayList<>(all)) {
            if (message == null || !access.isMessage(message)) continue;
            String kind = access.kind(message);
            if (kind != null && kind.toUpperCase(Locale.ROOT).contains("ACTION_LOG")) continue;
            messages.add(message);
        }
        String you = you(session, messages, access);
        List<Line> lines = new ArrayList<>();
        for (Object message : messages) {
            boolean mine = access.mine(message) || (you != null && you.equals(sender(message, access)));
            lines.add(new Line(describe(access.text(message), access.kind(message)), micros(access.time(message)),
                    mine, message));
        }
        Collections.sort(lines, Comparator.comparingLong(line -> line.micros));
        if (lines.size() <= SHOWN) return lines;
        return new ArrayList<>(lines.subList(lines.size() - SHOWN, lines.size()));
    }

    /**
     * The signed-in account's id: the session's own, or when this build couldn't read it, the sender
     * of a message Instagram marked as the account's. Instagram sets that mark on messages the server
     * sent back, not on one just sent from this phone, so the id is what tells a fresh reply is yours.
     */
    static String you(Object session, List<Object> messages, Access access) {
        try {
            String id = access.account(session);
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable t) {
            // Logged, then a marked message still says who the account is.
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, LOAD, t);
        }
        for (Object message : messages) {
            if (!access.mine(message)) continue;
            String id = sender(message, access);
            if (id != null && !id.isEmpty()) return id;
        }
        return null;
    }

    /** The message's sender id, or null when it can't be read. */
    private static String sender(Object message, Access access) {
        try {
            return access.sender(message);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, LOAD, t);
            return null;
        }
    }

    /**
     * What a message shows as: its text, or for a message with none, a word for what it holds. A
     * view once photo or video gets its label and nothing else.
     */
    static String describe(String text, String kind) {
        if (text != null && !text.trim().isEmpty()) return text;
        String type = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        if (type.contains("RAVEN") || type.contains("VISUAL") || type.contains("EPHEMERAL")) {
            return L10n.t("View once message");
        }
        if (type.contains("VOICE") || type.contains("AUDIO")) return L10n.t("Voice message");
        if (type.contains("CLIP") || type.contains("REEL")) return L10n.t("Reel");
        if (type.contains("STORY")) return L10n.t("Story");
        if (type.contains("ANIMATED") || type.contains("STICKER") || type.contains("GIF")) {
            return L10n.t("GIF or sticker");
        }
        if (type.contains("LIKE")) return new String(Character.toChars(0x2764));
        if (type.contains("SHARE") || type.contains("XMA") || type.contains("POST")) return L10n.t("Post");
        if (type.contains("MEDIA") || type.contains("PHOTO") || type.contains("VIDEO")) {
            return L10n.t("Photo or video");
        }
        return L10n.t("Attachment");
    }

    /** Instagram's message time, microseconds written as text, or 0 when it can't be read. */
    static long micros(String time) {
        if (time == null) return 0;
        try {
            long value = Long.parseLong(time.trim());
            // An older message may carry milliseconds.
            return value < 100_000_000_000_000L ? value * 1000 : value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The newest message shown, the one Mark as read marks the chat read up to, or null. */
    static Object newest(List<Line> lines) {
        return lines == null || lines.isEmpty() ? null : lines.get(lines.size() - 1).message;
    }

    /** Marks the chat read up to the newest message shown. True when the receipt went out. */
    static boolean markRead(Object session, Object key, List<Line> lines, Access access) {
        Object newest = newest(lines);
        String message = null;
        String sender = null;
        try {
            if (newest != null) {
                message = access.id(newest);
                sender = access.sender(newest);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, MARK, t);
        }
        // With no ids, ThreadSeen says it couldn't mark the chat read.
        return ThreadSeen.markReadNow(session, key, message, sender);
    }

    /** Puts the box up over [activity] and starts reading the chat into it. */
    static AlertDialog show(Activity activity, Object session, Object key, boolean canMark) {
        ScreenColors colors = ScreenColors.current();
        int gap = dp(activity, 12);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(activity);
        status.setText(L10n.t("Loading"));
        status.setTextColor(colors.summary);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        list.addView(status);

        int most = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
        Capped scroll = new Capped(activity, most);
        scroll.addView(list);

        EditText field = new EditText(activity);
        field.setHint(L10n.t("Write a message"));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMaxLines(4);
        ScreenColors.styleField(field);
        Button send = new Button(activity);
        send.setText(L10n.t("Send"));
        ScreenColors.styleAction(send, true);

        LinearLayout composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sendAt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendAt.setMarginStart(gap / 2);
        composer.addView(send, sendAt);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), gap, dp(activity, 20), 0);
        // Weighted, so when the keyboard shortens the box the chat gives up the room, not the reply field.
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams composerAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        composerAt.topMargin = gap;
        root.addView(composer, composerAt);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(L10n.t("Preview"))
                .setView(root)
                .setNegativeButton(L10n.t("Close"), null);
        if (canMark) builder.setPositiveButton(L10n.t("Mark as read"), null);
        AlertDialog box = builder.create();
        box.show();
        if (box.getWindow() != null) {
            box.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        ScreenColors.dialog(box);

        List<List<Line>> shown = new ArrayList<>(Collections.singletonList(null));
        Runnable load = () -> load(box, list, status, scroll, session, key, shown);
        if (canMark) {
            box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (markRead(session, key, shown.get(0), access)) box.dismiss();
            });
        }
        send.setOnClickListener(view -> {
            String text = field.getText() == null ? "" : field.getText().toString().trim();
            if (text.isEmpty()) return;
            try {
                access.send(session, key, text);
                field.setText("");
                Utils.showToastShort(L10n.t("Sent"));
                Utils.runOnMainThreadDelayed(load, RELOAD_MS);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.PREVIEW_CHATS, SEND, t);
                Utils.showToastShort(L10n.t("Couldn't send"));
            }
        });
        load.run();
        return box;
    }

    /** Reads the chat off the main thread and fills the box with it, if the box is still up. */
    private static void load(AlertDialog box, LinearLayout list, TextView status, Capped scroll,
                             Object session, Object key, List<List<Line>> shown) {
        Utils.runOnBackgroundThread(() -> {
            List<Line> lines;
            try {
                lines = read(session, key, access);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.PREVIEW_CHATS, LOAD, t);
                lines = null;
            }
            List<Line> read = lines;
            Utils.runOnMainThread(() -> {
                if (!box.isShowing()) return;
                if (read != null) shown.set(0, read);
                fill(list, status, read);
                scroll.showEnd();
            });
        });
    }

    /** Puts [lines] in the box, yours on the right and theirs on the left, each with its time. */
    static void fill(LinearLayout list, TextView status, List<Line> lines) {
        Context context = list.getContext();
        ScreenColors colors = ScreenColors.current();
        list.removeAllViews();
        if (lines == null || lines.isEmpty()) {
            status.setText(lines == null ? L10n.t("Couldn't load this chat") : L10n.t("Nothing here yet"));
            list.addView(status);
            return;
        }
        int gap = dp(context, 6);
        for (Line line : lines) {
            LinearLayout entry = new LinearLayout(context);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setGravity(line.mine ? Gravity.END : Gravity.START);

            TextView bubble = new TextView(context);
            bubble.setText(line.text);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            bubble.setTextColor(line.mine ? colors.onAccent : colors.title);
            bubble.setTextIsSelectable(true);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(line.mine ? colors.accent : colors.card);
            surface.setCornerRadius(dp(context, 12));
            bubble.setBackground(surface);
            bubble.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            bubble.setMaxWidth((int) (context.getResources().getDisplayMetrics().widthPixels * 0.6f));
            entry.addView(bubble);

            if (line.micros > 0) {
                TextView time = new TextView(context);
                long millis = line.micros / 1000;
                int flags = DateUtils.FORMAT_SHOW_TIME;
                if (!DateUtils.isToday(millis)) flags |= DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
                time.setText(DateUtils.formatDateTime(context, millis, flags));
                time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                time.setTextColor(colors.summary);
                time.setPadding(dp(context, 4), dp(context, 2), dp(context, 4), 0);
                entry.addView(time);
            }
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            at.bottomMargin = gap;
            list.addView(entry, at);
        }
    }

    /** The switch, read the way every hook here reads it. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.PREVIEW_CHATS.get();
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * A scroll view no taller than [most], so the reply box stays on screen under a long chat. It shows
     * the newest message without fullScroll, which hands focus to the last bubble and takes it from the
     * reply field, so typing went nowhere.
     */
    static final class Capped extends ScrollView {
        private final int most;
        private boolean toEnd;

        Capped(Context context, int most) {
            super(context);
            this.most = most;
        }

        /** Scrolls to the newest message on the next layout and leaves focus where it is. */
        void showEnd() {
            toEnd = true;
            requestLayout();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            // The keyboard shortened the box: keep the newest message just above the reply field.
            if (h < oldh) toEnd = true;
            super.onSizeChanged(w, h, oldw, oldh);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (toEnd && getChildCount() > 0) {
                toEnd = false;
                scrollTo(0, getChildAt(0).getHeight());
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int cap = mode == MeasureSpec.UNSPECIFIED ? most : Math.min(most, size);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
