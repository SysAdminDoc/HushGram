/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Helper for the "Read messages without the seen receipt" patch.
 *
 * <p>Opening a chat queues a seen receipt, and Instagram's handler for it sends the request that
 * puts Seen under the other person's message. The patch asks {@link #hold} first in that handler.
 * While the switch is on, the handler reports the receipt done through Instagram's own callback
 * without sending it, so the chat still reads as seen on this phone and the queue doesn't retry it.
 *
 * <p>Instagram's own Mark as read still lets a chat know. While the switch is on, a long press on a
 * chat in the inbox offers it, which Instagram otherwise offers only when several chats are picked
 * ({@link #offerMarkRead}), wherever Instagram would let that chat be marked unread. Tapping it
 * ({@link #markRead}), where Instagram allows an action on that chat, queues the chat's receipt
 * through Instagram's own sender, picked the way Instagram's own handler for marking a chat read
 * picks it, and takes the chat's unread mark off. Marking several picked chats read together goes
 * through the same sender, and {@link #markReadTogether} is told about each chat first. Either way
 * the receipt for that message, on that account, goes through the hold from then on, for a day
 * ({@link ReadMarks}), so it still goes out when Instagram sends it after a restart or once it's
 * back online. Every other receipt stays held, a newer message in the same chat included.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram sends the receipt and builds its menu as usual. Deciding whether a
 * held receipt was marked read fails closed: a receipt whose chat, message or account can't be
 * read, or whose marks can't be read, stays held, since letting it through would send a receipt
 * nobody asked for.
 */
public final class ThreadSeen {
    /** The steps a failure is reported under. */
    static final String SWITCH = "switch read";
    static final String ROW = "mark as read row";
    static final String MARK = "mark as read";
    static final String TOGETHER = "chats marked read together";
    static final String PASS = "chat marked read";
    static final String FEEDBACK = "mark as read feedback";

    /** Instagram's chats, through the bodies the patch writes in {@link InstagramChats}. Tests stand in their own. */
    interface Chats {
        String accountId(Object session);

        String threadId(Object key);

        Object receiptKey(Object receipt);

        String receiptMessage(Object receipt);

        Object lastMessage(Object thread);

        String messageId(Object message);

        String senderId(Object message);

        void sendSeen(Object session, String thread, String message, String sender);

        void clearUnread(Object session, Object key);
    }

    private static final Chats INSTAGRAM = new Chats() {
        @Override
        public String accountId(Object session) {
            return InstagramChats.accountId(session);
        }

        @Override
        public String threadId(Object key) {
            return InstagramChats.threadId(key);
        }

        @Override
        public Object receiptKey(Object receipt) {
            return InstagramChats.receiptKey(receipt);
        }

        @Override
        public String receiptMessage(Object receipt) {
            return InstagramChats.receiptMessage(receipt);
        }

        @Override
        public Object lastMessage(Object thread) {
            return InstagramChats.lastMessage(thread);
        }

        @Override
        public String messageId(Object message) {
            return InstagramChats.messageId(message);
        }

        @Override
        public String senderId(Object message) {
            return InstagramChats.senderId(message);
        }

        @Override
        public void sendSeen(Object session, String thread, String message, String sender) {
            InstagramChats.sendSeen(session, null, thread, message, sender);
        }

        @Override
        public void clearUnread(Object session, Object key) {
            InstagramChats.markUnread(session, key, false);
        }
    };

    /** Wall time, since a mark outlives the process and elapsed time starts over with the phone. */
    private static final LongSupplier CLOCK = System::currentTimeMillis;

    private static final Object LOCK = new Object();

    /** The marks, read the first time they're needed. */
    private static ReadMarks marks;

    private static volatile boolean logged;

    private ThreadSeen() {
    }

    /**
     * Asked at the start of Instagram's seen receipt handler with the receipt and the account the
     * handler sends it for. True makes the handler finish without sending. False while the switch
     * is off, HushGram is paused or the settings aren't ready, and for the receipt of a message
     * marked read. Never throws.
     */
    public static boolean hold(Object receipt, Object session) {
        return hold(receipt, session, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on) {
        return hold(receipt, session, on, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on, Chats chats, LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            boolean hold = on.getAsBoolean();
            if (hold && markedRead(receipt, session, chats, clock)) {
                Logger.printDebug(() -> "Messages: sent the seen receipt for a chat marked read");
                return false;
            }
            if (hold && !logged) {
                logged = true;
                Logger.printDebug(() -> "Messages: held back a seen receipt");
            }
            return hold;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, SWITCH, t);
            return false;
        }
    }

    /**
     * Called in the builder that offers Mark as unread on a chat's long press, once Instagram has
     * found the chat can be marked unread, with the rows so far and Instagram's own Mark as read.
     * Adds Mark as read, once, while the switch is on.
     */
    public static void offerMarkRead(List<Object> rows, Object markAsRead) {
        offerMarkRead(rows, markAsRead, ThreadSeen::switchedOn);
    }

    static void offerMarkRead(List<Object> rows, Object markAsRead, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (rows != null && markAsRead != null && on.getAsBoolean() && !rows.contains(markAsRead)) {
                rows.add(markAsRead);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, ROW, t);
        }
    }

    /**
     * Called when a row of a chat's long press menu is tapped and Instagram has allowed an action
     * on that chat, with the row, Instagram's own Mark as read, the account, the chat and its key.
     * True when the tap was Mark as read and has been handled here, so Instagram's code for the row
     * doesn't run. Never throws.
     */
    public static boolean markRead(Object chosen, Object markAsRead, Object session, Object thread, Object key) {
        return markRead(chosen, markAsRead, session, thread, key, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static boolean markRead(Object chosen, Object markAsRead, Object session, Object thread, Object key,
                            BooleanSupplier on, Chats chats, LongSupplier clock) {
        ReadMarks waiting = null;
        String account = null;
        String id = null;
        String message = null;
        long now = 0;
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (chosen == null || chosen != markAsRead || !on.getAsBoolean()) return false;
            account = chats.accountId(session);
            id = chats.threadId(key);
            Object last = chats.lastMessage(thread);
            message = last == null ? null : chats.messageId(last);
            String sender = last == null ? null : chats.senderId(last);
            ReadMarks marks = marks();
            if (isEmpty(account) || isEmpty(id) || isEmpty(message) || isEmpty(sender) || marks == null) {
                Logger.printDebug(() -> "Messages: a chat marked read has no message to mark");
                toast(() -> L10n.t("Couldn't mark as read"));
                return true;
            }
            now = clock.getAsLong();
            boolean fresh = !marks.allows(account, id, message, now);
            marks.allow(account, id, message, now);
            if (fresh) waiting = marks;
            chats.sendSeen(session, id, message, sender);
            waiting = null;
            try {
                chats.clearUnread(session, key);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            }
            toast(() -> L10n.t("Marked as read"));
            return true;
        } catch (Throwable t) {
            if (waiting != null) {
                try {
                    waiting.revoke(account, id, message, now);
                } catch (Throwable ignored) {
                    // The mark stays, and so does the failure reported below.
                }
            }
            HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            toast(() -> L10n.t("Couldn't mark as read"));
            return false;
        }
    }

    /**
     * Called for each chat picked with others and marked read together, just before Instagram's own
     * sender queues its receipt, with what the sender is handed: the account, a detail of the
     * receipt, the chat's thread id, the message it points at and that message's sender. Lets that
     * receipt through the hold while the switch is on, as a long press's Mark as read does. Never
     * throws.
     */
    @SuppressWarnings("unused")
    public static void markReadTogether(Object session, Object detail, String thread, String message, String sender) {
        markReadTogether(session, thread, message, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static void markReadTogether(Object session, String thread, String message, BooleanSupplier on, Chats chats,
                                 LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (!on.getAsBoolean()) return;
            String account = chats.accountId(session);
            ReadMarks marks = marks();
            if (isEmpty(account) || isEmpty(thread) || isEmpty(message) || marks == null) {
                Logger.printDebug(() -> "Messages: a chat marked read together has no message to mark");
                return;
            }
            marks.allow(account, thread, message, clock.getAsLong());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, TOGETHER, t);
        }
    }

    /**
     * Whether [receipt] goes through because its message was marked read on [session]'s account.
     * With no mark kept, the receipt isn't read at all. Anything that can't be read leaves it held,
     * and the failure is reported.
     */
    private static boolean markedRead(Object receipt, Object session, Chats chats, LongSupplier clock) {
        try {
            ReadMarks marks = marks();
            long now = clock.getAsLong();
            if (marks == null || marks.isEmpty(now)) return false;
            String account = chats.accountId(session);
            String thread = chats.threadId(chats.receiptKey(receipt));
            String message = chats.receiptMessage(receipt);
            if (isEmpty(account) || isEmpty(thread) || isEmpty(message)) return false;
            return marks.allows(account, thread, message, now);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, PASS, t);
            return false;
        }
    }

    /**
     * The marks, kept in their own file in Instagram's main process and only in memory in any other.
     * Null before Instagram's context is set.
     */
    private static ReadMarks marks() {
        synchronized (LOCK) {
            if (marks == null) {
                if (!Utils.settingsReady()) return null;
                Context context = Utils.getContext();
                if (context == null) return null;
                marks = new ReadMarks(Utils.isMainProcess()
                        ? context.getSharedPreferences(ReadMarks.FILE, Context.MODE_PRIVATE)
                        : null);
            }
            return marks;
        }
    }

    /** Forgets every mark, on file too, for tests. */
    static void forgetMarks() {
        synchronized (LOCK) {
            ReadMarks current = marks();
            if (current != null) current.clear();
            marks = null;
        }
    }

    /** Drops the marks read so far, as a restart does, so the next one is read from the file. For tests. */
    static void restartForTests() {
        synchronized (LOCK) {
            marks = null;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static void toast(Supplier<String> text) {
        try {
            Utils.showToastShort(text.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, FEEDBACK, t);
        }
    }

    /** The switch, read the way every hook here reads it. Tests hand it to the overloads that take fake chats. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.READ_WITHOUT_SEEN_RECEIPT.get();
    }
}
