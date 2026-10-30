/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Jump to a chat's first message or to a date (#246).
 *
 * <p>A long press on the chat's name opens a short menu. Instagram loads a chat a page at a time as
 * the list is scrolled to its old end, so a jump scrolls there again and again until the chat has
 * nothing older (or, for a date, until the oldest message it holds is that old), with a count and a
 * Cancel button on screen, and then scrolls to the message. Messages and their rows are read by
 * {@link ChatHistory}. The patch tells this class which chat is on screen with {@link #opened} and
 * {@link #closed}, the same two moments the chat lock uses.
 */
public final class ChatPager {
    static final String PAGER = "chat pager";
    /** How often the list is scrolled to its old end and looked at again. */
    static final long TICK_MS = 450;
    /** Looks with nothing new in a row before the chat counts as fully loaded. */
    static final int STALL_LOOKS = 6;
    private static final int ATTACH_TRIES = 24;
    private static final long ATTACH_MS = 600;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Object> screen = new WeakReference<>(null);
    private static volatile WeakReference<View> attached = new WeakReference<>(null);
    private static volatile Run running;

    private ChatPager() {
    }

    /** What a loader works on: the chat's list. */
    interface Source {
        /** How many rows the list holds. */
        int count();

        /** The messages the list holds now, oldest first. */
        List<ChatHistory.Entry> entries();

        /** Scrolls the list to its old end, which makes Instagram load older messages. */
        void toOldest();

        /** Scrolls the list to a row. */
        void scrollTo(int position);
    }

    /** The paging itself: one {@link #tick} per look, no views and no clock. */
    static final class Loader {
        private final Source source;
        private final long target;
        private final int stallLimit;
        private int lastCount = -1;
        private int stalls;
        private boolean done;
        private boolean reachedDate;
        private int loaded;

        /** [target] is a time in milliseconds, or 0 to go to the very first message. */
        Loader(Source source, long target, int stallLimit) {
            this.source = source;
            this.target = target;
            this.stallLimit = stallLimit;
        }

        /** Looks once. True while more should be loaded, false when the jump is over. */
        boolean tick() {
            if (done) return false;
            List<ChatHistory.Entry> entries = source.entries();
            int count = source.count();
            loaded = entries.size();
            long oldest = ChatHistory.oldestMillis(entries);
            if (target > 0 && oldest > 0 && oldest <= target) {
                reachedDate = true;
                return finish(entries);
            }
            if (count == lastCount) stalls++;
            else stalls = 0;
            lastCount = count;
            if (stalls >= stallLimit) return finish(entries);
            source.toOldest();
            return true;
        }

        private boolean finish(List<ChatHistory.Entry> entries) {
            done = true;
            int position = ChatHistory.positionFor(entries, target);
            if (position >= 0) source.scrollTo(position);
            else source.toOldest();
            return false;
        }

        /** How many messages were found at the last look. */
        int loaded() {
            return loaded;
        }

        /** Whether a date jump stopped because the chat reached back to that date. */
        boolean reachedDate() {
            return reachedDate;
        }
    }

    /** Asked first when a chat comes to the front. */
    public static void opened(Object fragment) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_HISTORY);
            screen = new WeakReference<>(fragment);
            attached = new WeakReference<>(null);
            if (!wanted()) return;
            tryAttach(fragment, 0);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
        }
    }

    /** Asked first when a chat leaves the front. */
    public static void closed(Object fragment) {
        try {
            Object shown = screen.get();
            if (shown != null && shown != fragment) return;
            screen = new WeakReference<>(null);
            Run run = running;
            if (run != null) run.stop();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && Settings.JUMP_IN_CHAT.get();
    }

    private static void tryAttach(final Object fragment, final int tries) {
        if (screen.get() != fragment || tries >= ATTACH_TRIES) return;
        try {
            View root = viewOf(fragment);
            View title = root == null ? null : titleIn(root);
            if (title != null) {
                if (attached.get() != title && !title.isLongClickable()) {
                    title.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            return menu(fragment, v);
                        }
                    });
                }
                attached = new WeakReference<>(title);
                return;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                tryAttach(fragment, tries + 1);
            }
        }, ATTACH_MS);
    }

    /** The view a fragment shows, or null. */
    static View viewOf(Object fragment) {
        try {
            Object view = fragment.getClass().getMethod("getView").invoke(fragment);
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The chat header's title under [root], when it is there and shown. */
    static View titleIn(View root) {
        for (String name : ChatLocks.TITLES) {
            int id = app.hushgram.extension.instagram.direct.MessagesLock.id(root.getContext(), name);
            if (id == 0) continue;
            View title = root.findViewById(id);
            if (title != null) return title;
        }
        return null;
    }

    private static boolean menu(Object fragment, View title) {
        if (!wanted() || screen.get() != fragment) return false;
        try {
            final Activity activity = activityOf(title.getContext());
            if (activity == null) return false;
            final View root = viewOf(fragment);
            if (root == null) return false;
            final String[] labels = {L10n.t("Jump to the first message"), L10n.t("Jump to a date")};
            new AlertDialog.Builder(activity)
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) start(activity, root, 0);
                            else pickDate(activity, root);
                        }
                    }).show();
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
            return false;
        }
    }

    private static void pickDate(final Activity activity, final View root) {
        Calendar now = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(activity, new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(android.widget.DatePicker view, int year, int month, int day) {
                Calendar chosen = Calendar.getInstance();
                chosen.clear();
                chosen.set(year, month, day, 0, 0, 0);
                start(activity, root, Math.max(1, chosen.getTimeInMillis()));
            }
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(now.getTimeInMillis());
        dialog.show();
    }

    private static Activity activityOf(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static void start(Activity activity, View root, long target) {
        Run old = running;
        if (old != null) old.stop();
        View list = ListSource.find(root);
        if (list == null) {
            Utils.showToastShort(L10n.t("Couldn't find this chat's messages."));
            return;
        }
        Run run = new Run(activity, new ListSource(list), target);
        running = run;
        run.begin();
    }

    /** One jump on screen: the dialog, the clock and the loader. */
    private static final class Run implements Runnable {
        private final Activity activity;
        private final Loader loader;
        private final Source source;
        private final long target;
        private AlertDialog dialog;
        private boolean stopped;

        Run(Activity activity, Source source, long target) {
            this.activity = activity;
            this.source = source;
            this.target = target;
            this.loader = new Loader(source, target, STALL_LOOKS);
        }

        void begin() {
            TextView text = new TextView(activity);
            int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
            text.setPadding(pad, pad, pad, pad);
            text.setText(L10n.t("Loading older messages."));
            text.setTag("hushgram_pager_text");
            dialog = new AlertDialog.Builder(activity)
                    .setView(text)
                    .setCancelable(true)
                    .setNegativeButton(L10n.t("Cancel"), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            stop();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface d) {
                            stop();
                        }
                    }).create();
            dialog.show();
            MAIN.post(this);
        }

        void stop() {
            stopped = true;
            MAIN.removeCallbacks(this);
            try {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {
                // The window is already gone.
            }
            if (running == this) running = null;
        }

        @Override public void run() {
            if (stopped) return;
            try {
                if (activity.isFinishing()) {
                    stop();
                    return;
                }
                boolean more = loader.tick();
                View shown = dialog == null ? null : dialog.getWindow().getDecorView().findViewWithTag("hushgram_pager_text");
                if (shown instanceof TextView) {
                    ((TextView) shown).setText(L10n.quantity(activity, loader.loaded(),
                            "Loading older messages. %1$d message so far.",
                            "Loading older messages. %1$d messages so far."));
                }
                if (more) {
                    MAIN.postDelayed(this, TICK_MS);
                    return;
                }
                stop();
                Utils.showToastShort(target > 0 && !loader.reachedDate()
                        ? L10n.t("That's as far back as this chat goes.")
                        : L10n.t("Here it is."));
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
                stop();
            }
        }
    }

    /** The chat's RecyclerView, driven by reflection: the extension has no androidx dependency. */
    static final class ListSource implements Source {
        private final View list;

        ListSource(View list) {
            this.list = list;
        }

        /** The biggest RecyclerView under [root] that holds rows. */
        static View find(View root) {
            List<View> found = new ArrayList<>();
            collect(root, found);
            View best = null;
            for (View candidate : found) {
                if (best == null || candidate.getHeight() * candidate.getWidth() > best.getHeight() * best.getWidth()) {
                    best = candidate;
                }
            }
            return best;
        }

        private static void collect(View view, List<View> found) {
            if (isRecycler(view.getClass())) found.add(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
            }
        }

        private static boolean isRecycler(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
            }
            return false;
        }

        private Object adapter() {
            try {
                return list.getClass().getMethod("getAdapter").invoke(list);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override public int count() {
            try {
                Object adapter = adapter();
                if (adapter == null) return 0;
                return (Integer) adapter.getClass().getMethod("getItemCount").invoke(adapter);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        @Override public List<ChatHistory.Entry> entries() {
            return ChatHistory.read(adapter(), count(), ChatHistory.LIVE);
        }

        private Object manager() throws Exception {
            return list.getClass().getMethod("getLayoutManager").invoke(list);
        }

        @Override public void toOldest() {
            try {
                int count = count();
                if (count <= 0) return;
                scrollTo(oldestEnd(count));
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
            }
        }

        /** The row at the old end: the one the oldest message sits in, else the end the layout puts it at. */
        private int oldestEnd(int count) throws Exception {
            ChatHistory.Entry oldest = null;
            ChatHistory.Entry newest = null;
            for (ChatHistory.Entry entry : entries()) {
                if (entry.position < 0 || entry.millis <= 0) continue;
                if (oldest == null || entry.millis < oldest.millis) oldest = entry;
                if (newest == null || entry.millis > newest.millis) newest = entry;
            }
            if (oldest != null && newest != null && oldest.position != newest.position) {
                return oldest.position < newest.position ? 0 : count - 1;
            }
            Object manager = manager();
            if (manager != null) {
                try {
                    Method reversed = manager.getClass().getMethod("isLayoutReversed");
                    if (Boolean.TRUE.equals(reversed.invoke(manager))) return count - 1;
                } catch (Throwable ignored) {
                    // Not a linear layout manager.
                }
            }
            return 0;
        }

        @Override public void scrollTo(int position) {
            try {
                Object manager = manager();
                if (manager == null) return;
                manager.getClass().getMethod("scrollToPosition", int.class).invoke(manager, position);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
            }
        }
    }
}
