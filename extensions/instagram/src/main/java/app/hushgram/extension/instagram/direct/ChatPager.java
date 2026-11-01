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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import app.hushgram.extension.instagram.download.TextGrab;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
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
    private static final int ACTION_FIRST = 0;
    private static final int ACTION_DATE = 1;
    private static final int ACTION_EXPORT = 2;
    /** How often the list is scrolled to its old end and looked at again. */
    static final long TICK_MS = 450;
    /** Looks with nothing new in a row before the chat counts as fully loaded. A page can take seconds to come. */
    static final int STALL_LOOKS = 24;
    /** Pixels the list steps off its old end and back to ask for an older page. Nothing draws between the two. */
    static final int ASK_STEP = 1;
    /** The area around a chat's name and subtitle in Instagram's chat header. */
    static final String NAME_AREA = "header_title_subtitle_container";
    private static final int ATTACH_TRIES = 24;
    private static final long ATTACH_MS = 600;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final View.OnLongClickListener PRESS = new View.OnLongClickListener() {
        @Override public boolean onLongClick(View v) {
            return menu(v);
        }
    };
    /**
     * Puts the long press back as a touch starts, before the view times the press. Instagram clears it
     * on the name's area seconds after the chat comes up, and can again after {@link HeaderWatch} stops
     * looking. Never takes the touch, so a tap stays Instagram's.
     */
    private static final View.OnTouchListener REARM = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && wanted() && (!v.isLongClickable() || !hasLongPress(v))) {
                v.setOnLongClickListener(PRESS);
            }
            return false;
        }
    };
    private static volatile WeakReference<Object> screen = new WeakReference<>(null);
    private static volatile WeakReference<View> attached = new WeakReference<>(null);
    private static volatile HeaderWatch watch;
    private static volatile Run running;

    private ChatPager() {
    }

    /** What a loader works on: the chat's list. */
    interface Source {
        /** How many rows the list holds. */
        int count();

        /** The messages the list holds now, oldest first. */
        List<ChatHistory.Entry> entries();

        /** Asks Instagram for older messages if the list is at its old end, then scrolls it there. */
        void toOldest();

        /** Scrolls the list to a row. */
        void scrollTo(int position);

        /** Scrolls the list back to its new end, where a chat is read from. */
        void toNewest();
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
        private final boolean scroll;

        /** [target] is a time in milliseconds, or 0 to go to the very first message. */
        Loader(Source source, long target, int stallLimit) {
            this(source, target, stallLimit, true);
        }

        /** With [scroll] false the loader ends where the list is, and an export puts it back at the new end. */
        Loader(Source source, long target, int stallLimit, boolean scroll) {
            this.source = source;
            this.target = target;
            this.stallLimit = stallLimit;
            this.scroll = scroll;
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
            if (!scroll) {
                source.toNewest();
                return false;
            }
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
            stopWatching();
            if (!wanted()) {
                log("chat opened with both switches off or settings not ready");
                return;
            }
            log("chat opened");
            watch = new HeaderWatch(fragment);
            watch.run();
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
            stopWatching();
            Run run = running;
            if (run != null) run.stop();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
        }
    }

    static boolean wanted() {
        return jumpWanted() || exportWanted();
    }

    static boolean jumpWanted() {
        return Utils.settingsReady() && Settings.JUMP_IN_CHAT.get();
    }

    static boolean exportWanted() {
        return Utils.settingsReady() && Settings.EXPORT_CHAT.get();
    }

    private static void stopWatching() {
        HeaderWatch old = watch;
        if (old != null) MAIN.removeCallbacks(old);
        watch = null;
    }

    private static void log(final String line) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, PAGER, () -> line);
    }

    /**
     * Looks at the chat's header through its first seconds. Instagram builds the header after the
     * chat comes up and can build it again, so finding it once isn't the end of the looking.
     */
    private static final class HeaderWatch implements Runnable {
        private final Object fragment;
        private int tries;
        private boolean found;

        HeaderWatch(Object fragment) {
            this.fragment = fragment;
        }

        @Override public void run() {
            if (watch != this || screen.get() != fragment) return;
            try {
                View root = viewOf(fragment);
                View target = root == null ? null : pressTarget(root);
                if (target != null) {
                    found = true;
                    HookStatus.recoveredViewId(FamilyNames.CHAT_HISTORY, ChatLocks.TITLES[0]);
                    arm(target);
                }
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
                return;
            }
            if (++tries < ATTACH_TRIES) {
                MAIN.postDelayed(this, ATTACH_MS);
                return;
            }
            if (!found) {
                // The diagnostics report then says the name was never found, instead of a silent long press.
                HookStatus.missingViewId(FamilyNames.CHAT_HISTORY, ChatLocks.TITLES[0]);
                log("the chat's name was never found");
            }
        }

        private void arm(View target) {
            View ours = attached.get();
            if (ours == target && target.isLongClickable()) return;
            if (ours != target && hasLongPress(target)) {
                if (tries == 0) log("Instagram answers the long press on the chat's name");
                return;
            }
            target.setOnLongClickListener(PRESS);
            target.setOnTouchListener(REARM);
            attached = new WeakReference<>(target);
            final String how = ours == target ? "again, it was cleared" : ours == null ? "first time" : "header was rebuilt";
            log("long press set on the chat's name, try " + tries + ", " + how);
        }
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

    /**
     * The chat header's title, when it is there. Instagram keeps the header in the screen's action
     * bar beside the chat's view, not inside it, so the lookup walks from [root] up through its
     * parents the way the chat lock does, and the nearest title is the chat's own. Under the root
     * alone it was never found, and the long press stayed Instagram's (#246, #247).
     */
    static View titleIn(View root) {
        for (String name : ChatLocks.TITLES) {
            View title = nearest(root, name);
            if (title != null) return title;
        }
        return null;
    }

    /**
     * Where the long press goes: the area around the chat's name and subtitle. A tap on the name
     * lands there and opens the chat's details, while the name itself takes no touches, so a
     * listener on the name would swallow that tap (#246). The name is the fallback.
     */
    static View pressTarget(View root) {
        View area = nearest(root, NAME_AREA);
        return area != null ? area : titleIn(root);
    }

    /** The chat's name as the header shows it, empty when it isn't there. */
    static String nameIn(View root) {
        View title = titleIn(root);
        return title instanceof TextView ? String.valueOf(((TextView) title).getText()) : "";
    }

    private static View nearest(View root, String name) {
        int id = MessagesLock.id(root.getContext(), name);
        if (id == 0) return null;
        for (View at = root; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            View found = at.findViewById(id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Whether Instagram already answers a long press on [view], in which case its listener stays.
     * A view that is merely long-clickable, with nothing listening, still gets the menu: on it a
     * long press fell through to Instagram's click.
     */
    static boolean hasLongPress(View view) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? view.hasOnLongClickListeners() : view.isLongClickable();
    }

    private static boolean menu(View pressed) {
        Object fragment = screen.get();
        if (fragment == null || !wanted()) {
            log("no menu: chat " + (fragment != null) + ", switches " + wanted());
            return false;
        }
        try {
            // The header's views carry a context that doesn't lead to an activity, so the parents are asked too.
            final Activity activity = TextGrab.ownerOf(pressed, null);
            if (activity == null) {
                log("no menu: no activity from " + pressed.getContext().getClass().getName());
                return false;
            }
            final View root = viewOf(fragment);
            if (root == null) {
                log("no menu: the chat has no view");
                return false;
            }
            final List<String> labels = new ArrayList<>();
            final List<Integer> actions = new ArrayList<>();
            if (jumpWanted()) {
                labels.add(L10n.t("Jump to the first message"));
                actions.add(ACTION_FIRST);
                labels.add(L10n.t("Jump to a date"));
                actions.add(ACTION_DATE);
            }
            if (exportWanted()) {
                labels.add(L10n.t("Export chat"));
                actions.add(ACTION_EXPORT);
            }
            if (labels.isEmpty()) return false;
            final String name = nameIn(root);
            new AlertDialog.Builder(activity)
                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            int action = actions.get(which);
                            if (action == ACTION_FIRST) start(activity, root, 0, null);
                            else if (action == ACTION_DATE) pickDate(activity, root);
                            else start(activity, root, 0, name);
                        }
                    }).show();
            return true;
        } catch (Throwable t) {
            log("no menu: " + t);
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
                start(activity, root, Math.max(1, chosen.getTimeInMillis()), null);
            }
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(now.getTimeInMillis());
        dialog.show();
    }

    /** [exportName] is the chat's name when the whole chat is to be saved as text, null for a jump. */
    private static void start(Activity activity, View root, long target, String exportName) {
        Run old = running;
        if (old != null) old.stop();
        View list = ListSource.find(root);
        if (list == null) {
            Utils.showToastShort(L10n.t("Couldn't find this chat's messages."));
            return;
        }
        Run run = new Run(activity, new ListSource(list), target, exportName);
        running = run;
        run.begin();
    }

    /** One jump on screen: the dialog, the clock and the loader. */
    private static final class Run implements Runnable {
        private final Activity activity;
        private final Loader loader;
        private final Source source;
        private final long target;
        private final String exportName;
        private AlertDialog dialog;
        private boolean stopped;

        Run(Activity activity, Source source, long target, String exportName) {
            this.activity = activity;
            this.source = source;
            this.target = target;
            this.exportName = exportName;
            this.loader = new Loader(source, target, STALL_LOOKS, exportName == null);
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
                if (exportName != null) {
                    export(activity.getApplicationContext(), exportName, source.entries());
                    return;
                }
                Utils.showToastShort(target > 0 && !loader.reachedDate()
                        ? L10n.t("That's as far back as this chat goes.")
                        : L10n.t("Here it is."));
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
                stop();
            }
        }
    }

    /** Writes the loaded chat out as text, off the main thread, and says where it went. */
    static void export(final Context context, String title, List<ChatHistory.Entry> entries) {
        if (entries.isEmpty()) {
            Utils.showToastShort(L10n.t("No messages found to save."));
            return;
        }
        final String text = ChatHistory.document(entries, title, L10n.t("You"));
        final String name = ChatExport.fileName(title, System.currentTimeMillis());
        Utils.runOnBackgroundThread(new Runnable() {
            @Override public void run() {
                final boolean saved = ChatExport.save(context, name, text);
                Utils.runOnMainThread(new Runnable() {
                    @Override public void run() {
                        if (!saved) {
                            Utils.showToastShort(L10n.t("Couldn't save the chat."));
                            return;
                        }
                        Utils.showToastShort(ChatExport.appFolderOnly()
                                ? L10n.t("Chat saved in the app's Download folder.")
                                : L10n.t("Chat saved in Downloads/HushGram."));
                        Utils.showToastShort(name);
                    }
                });
            }
        });
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
            Object adapter = adapter();
            int count = count();
            List<ChatHistory.Entry> entries = ChatHistory.read(adapter, count, ChatHistory.LIVE);
            int placed = 0;
            for (ChatHistory.Entry entry : entries) {
                if (entry.position >= 0) placed++;
            }
            log("read " + entries.size() + " messages, " + placed + " on known rows, from " + count + " rows of "
                    + list.getClass().getName() + " with adapter " + (adapter == null ? null : adapter.getClass().getName()));
            return entries;
        }

        private Object manager() throws Exception {
            return list.getClass().getMethod("getLayoutManager").invoke(list);
        }

        /**
         * Instagram asks for an older page only from a scroll that moves toward the old end with that
         * end's row on screen. A jump to a row reports no movement, and sitting at the end leaves no room
         * to move that way, so the list steps off the end and straight back. The old end is at the top
         * whichever way the layout runs.
         */
        @Override public void toOldest() {
            try {
                list.scrollBy(0, ASK_STEP);
                list.scrollBy(0, -ASK_STEP);
                int count = count();
                if (count > 0) scrollTo(oldestEnd(count));
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

        @Override public void toNewest() {
            try {
                int count = count();
                if (count <= 0) return;
                scrollTo(oldestEnd(count) == 0 ? count - 1 : 0);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.CHAT_HISTORY, PAGER, t);
            }
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
