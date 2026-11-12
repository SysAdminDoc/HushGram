/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Helper for the "Read messages without the seen receipt" patch.
 *
 * <p>Opening a chat queues a seen receipt, and Instagram's handler for it sends the request that
 * puts Seen under the other person's message. The patch asks {@link #hold} first in that handler.
 * While the switch is on, the handler reports the receipt done through Instagram's own callback
 * without sending it, so the queue doesn't retry it.
 *
 * <p>Instagram also records the chat seen on this phone, which takes its unread mark off in the
 * inbox and out of the unread count. The queue tells a static method of every state the receipt
 * goes through, queued, sent or given up on, and that method passes it to the chat store. The patch
 * asks {@link #keepUnread} first there. While both switches are on, a held receipt never reaches the
 * store, so the chat stays unread here until Mark as read lets its receipt through.
 *
 * <p>With Keep chats unread here off, the store records an opened chat seen, but Instagram puts its
 * unread mark back once the inbox refreshes, since the other side never got the receipt. So
 * {@link #hold} keeps each chat it holds a receipt for, with the message the receipt points at
 * ({@link ReadHere}), and the inbox asks {@link #readHere} before it decides whether a chat shows
 * unread. The chat reads as read until its newest message is another one, unless Instagram's own
 * Mark as unread is on it.
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
 * <p>Inside a chat, a check mark in its header ({@link MarkReadButton}) shows while a receipt of that
 * chat is held, and {@link #markOpenChatRead} lets the newest one through the same way.
 *
 * <p>With Choose Seen in each chat on, an eye beside it ({@link SeenChoiceButton}) gives the chat on
 * screen a choice of its own on that account ({@link SeenChoices}), which comes before the switch:
 * Hold holds its receipts as if the switch were on, with Keep chats unread here, Mark a chat seen
 * when you reply and the check mark as they're set, and Send lets them through as if it were off. A
 * chat with no choice, or whose chat or account can't be read, follows the switch. A choice applies
 * to the next receipt: nothing held is sent by it, and the day-long marks of Mark as read still
 * count. Off or paused, every chat follows the switch whatever it chose.
 *
 * <p>A chat that opens under the messages lock's cover hasn't been read, so its receipt waits for
 * the lock whatever the switch says, and the chat stays unread here ({@link MessagesLock}). The
 * unlock lets the receipt of the chat it was asked in go through {@link #hold} as if it came then
 * ({@link #unlocked}). The other chats' receipts are dropped, since those chats stay unread and
 * opening one queues its receipt again (#649).
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram sends the receipt, records the chat seen and builds its menu as usual. Deciding whether a
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
    static final String UNREAD = "chat kept unread";
    static final String READ = "chat kept read";
    static final String REPLY = "chat marked seen by a reply";
    static final String OPEN_CHAT = "chat marked read from its header";
    static final String CHOICE = "chat's own seen choice";
    static final String LOCKED = "receipt kept for the lock";

    /** Instagram's chats, through the bodies the patch writes in {@link InstagramChats}. Tests stand in their own. */
    interface Chats {
        String accountId(Object session);

        String threadId(Object key);

        Object receiptKey(Object receipt);

        String receiptMessage(Object receipt);

        Object lastMessage(Object thread);

        String messageId(Object message);

        String senderId(Object message);

        String receiptSender(Object receipt);

        String composerThread(Object source);

        void sendSeen(Object session, String thread, String message, String sender);

        void clearUnread(Object session, Object key);

        Object storeSession(Object store);

        Object chatKey(Object thread);

        String newestMessage(Object thread);

        Object cursorOf(Object reader);

        Object cursorSession(Object reader);

        List<?> cursorItems(Object reader, Object key);

        String cursorItem(Object cursor);

        void moveCursor(Object cursor, String item);
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
        public String receiptSender(Object receipt) {
            return InstagramChats.receiptSender(receipt);
        }

        @Override
        public String composerThread(Object source) {
            return InstagramChats.composerThread(source);
        }

        @Override
        public void sendSeen(Object session, String thread, String message, String sender) {
            InstagramChats.sendSeen(session, null, thread, message, sender);
        }

        @Override
        public void clearUnread(Object session, Object key) {
            InstagramChats.markUnread(session, key, false);
        }

        @Override
        public Object storeSession(Object store) {
            return InstagramChats.storeSession(store);
        }

        @Override
        public Object chatKey(Object thread) {
            return InstagramChats.chatKey(thread);
        }

        @Override
        public String newestMessage(Object thread) {
            return InstagramChats.newestMessage(thread);
        }

        @Override
        public Object cursorOf(Object reader) {
            return InstagramChats.cursorOf(reader);
        }

        @Override
        public Object cursorSession(Object reader) {
            return InstagramChats.cursorSession(reader);
        }

        @Override
        public List<?> cursorItems(Object reader, Object key) {
            Object items = InstagramChats.cursorItems(reader, key);
            return items instanceof List ? (List<?>) items : null;
        }

        @Override
        public String cursorItem(Object cursor) {
            return InstagramChats.cursorItem(cursor);
        }

        @Override
        public void moveCursor(Object cursor, String item) {
            InstagramChats.moveCursor(cursor, item);
        }
    };

    /** Wall time, since a mark outlives the process and elapsed time starts over with the phone. */
    private static final LongSupplier CLOCK = System::currentTimeMillis;

    private static final Object LOCK = new Object();

    /** The marks, read the first time they're needed. */
    private static ReadMarks marks;

    /** The chats kept read here, read the first time they're needed. */
    private static ReadHere opened;

    /** The chats given a seen choice of their own, read the first time they're needed. */
    private static SeenChoices choices;

    /** A chat's own Hold choice holds whatever the switch says. */
    private static final BooleanSupplier ALWAYS = () -> true;

    private static volatile boolean logged;

    private static volatile boolean keptLogged;

    private static volatile boolean lockLogged;

    private ThreadSeen() {
    }

    /**
     * Asked at the start of Instagram's seen receipt handler with the receipt and the account the
     * handler sends it for. True makes the handler finish without sending. False while the switch
     * is off, HushGram is paused or the settings aren't ready, and for the receipt of a message
     * marked read. A held receipt's chat is kept read here while Keep chats unread here is off.
     * True too, whatever the switch says, while the receipt's chat is locked. Never throws.
     */
    public static boolean hold(Object receipt, Object session) {
        return hold(receipt, session, ThreadSeen::switchedOn, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on) {
        return hold(receipt, session, on, INSTAGRAM, CLOCK);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on, Chats chats, LongSupplier clock) {
        return hold(receipt, session, on, ThreadSeen::readsHere, chats, clock);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on, BooleanSupplier here, Chats chats,
                        LongSupplier clock) {
        return hold(receipt, session, on, here, ThreadSeen::marksOnReply, chats, clock);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on, BooleanSupplier here,
                        BooleanSupplier replies, Chats chats, LongSupplier clock) {
        return hold(receipt, session, on, here, replies, ThreadSeen::showsButton, chats, clock);
    }

    static boolean hold(Object receipt, Object session, BooleanSupplier on, BooleanSupplier here,
                        BooleanSupplier replies, BooleanSupplier button, Chats chats, LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (MessagesLock.receiptsWait() && waitsForLock(receipt, session, chats, clock, true)) return true;
            SeenChoices own = chosen();
            String account = own == null ? null : idOf(() -> chats.accountId(session));
            String thread = own == null ? null : idOf(() -> chats.threadId(chats.receiptKey(receipt)));
            boolean hold = forChat(own, account, thread, on, ALWAYS);
            if (hold && markedRead(receipt, session, chats, clock)) {
                Logger.printDebug(() -> "Messages: sent the seen receipt for a chat marked read");
                return false;
            }
            if (hold) keepRead(receipt, session, chatSwitch(own, account, thread, here, ThreadSeen::readsHereByChoice),
                    chats, clock);
            if (hold) remember(receipt, session, chatSwitch(own, account, thread, replies, Settings.MARK_SEEN_ON_REPLY::get),
                    chatSwitch(own, account, thread, button, Settings.MARK_READ_IN_CHAT::get), chats, clock);
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
     * Called when a message has been sent from a chat's composer, with the account's session, the
     * object the composer keeps its chat in and whether Instagram took the message (non-zero). With
     * Mark a chat seen when you reply on, lets the receipt held for that chat through: marks its
     * message read, queues it through Instagram's own sender and takes the chat's unread mark off.
     * Only a chat whose receipt this process held is touched, and anything that can't be read leaves
     * the receipt held. Never throws.
     */
    public static void replied(Object session, Object source, int sent) {
        replied(session, source, sent != 0, ThreadSeen::marksOnReply, INSTAGRAM, CLOCK);
    }

    static void replied(Object session, Object source, boolean sent, BooleanSupplier on, Chats chats,
                        LongSupplier clock) {
        String id = null;
        Pending held = null;
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (!sent) return;
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return;
            String account = chats.accountId(session);
            String thread = chats.composerThread(source);
            if (isEmpty(account) || isEmpty(thread)) return;
            if (!forChat(own, account, thread, on, Settings.MARK_SEEN_ON_REPLY::get)) return;
            id = account + "\n" + thread;
            synchronized (LOCK) {
                held = PENDING.get(id);
                if (held == null || !held.reply) return;
                PENDING.remove(id);
            }
            if (!letGo(id, held, session, chats, clock, REPLY)) return;
            Logger.printDebug(() -> "Messages: a reply marked its chat seen");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, REPLY, t);
        }
    }

    /**
     * Whether a receipt of the chat on screen is held: the one of [thread] on [account], or with the
     * chat's id unknown, the newest held at or after [since], when the chat came to the front. A null
     * [account] matches any account. Never throws.
     */
    static boolean hasHeld(String account, String thread, long since) {
        try {
            synchronized (LOCK) {
                return find(account, thread, since) != null;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, OPEN_CHAT, t);
            return false;
        }
    }

    /**
     * Called when the check mark in a chat's header is tapped, with the chat on screen picked as
     * {@link #hasHeld} picks it. Lets that chat's newest held receipt go through Instagram's own sender
     * and takes the chat's unread mark off, as Mark as read does. Says what happened in a toast. True
     * when the receipt went. Never throws.
     */
    static boolean markOpenChatRead(String account, String thread, long since) {
        return markOpenChatRead(account, thread, since, INSTAGRAM, CLOCK);
    }

    static boolean markOpenChatRead(String account, String thread, long since, Chats chats, LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            String id;
            Pending held;
            synchronized (LOCK) {
                id = find(account, thread, since);
                held = id == null ? null : PENDING.remove(id);
            }
            if (held == null) {
                Logger.printDebug(() -> "Messages: the chat on screen has no held receipt to mark read");
                toast(() -> L10n.t("Nothing to mark as read"));
                return false;
            }
            Object session = held.session.get();
            if (session == null) {
                restore(id, held);
                toast(() -> L10n.t("Couldn't mark as read"));
                return false;
            }
            if (!letGo(id, held, session, chats, clock, OPEN_CHAT)) {
                toast(() -> L10n.t("Couldn't mark as read"));
                return false;
            }
            Logger.printDebug(() -> "Messages: the chat on screen was marked read from its header");
            toast(() -> L10n.t("Marked as read"));
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, OPEN_CHAT, t);
            toast(() -> L10n.t("Couldn't mark as read"));
            return false;
        }
    }

    /** The key of the held receipt {@link #hasHeld} means, or null. Called holding {@link #LOCK}. */
    private static String find(String account, String thread, long since) {
        String found = null;
        for (Map.Entry<String, Pending> entry : PENDING.entrySet()) {
            Pending held = entry.getValue();
            boolean mine = isEmpty(thread) ? held.at >= since
                    : thread.equals(held.thread) && (isEmpty(account) || account.equals(held.account));
            // Kept in the order they were held, so the last that matches is the newest.
            if (mine) found = entry.getKey();
        }
        return found;
    }

    /**
     * Marks [held]'s message read and queues its receipt through Instagram's own sender, then takes its
     * chat's unread mark off. Puts it back to be let go later when there are no marks or the send
     * fails. True when the receipt went.
     */
    private static boolean letGo(String id, Pending held, Object session, Chats chats, LongSupplier clock, String step) {
        ReadMarks marks = marks();
        if (marks == null) {
            restore(id, held);
            return false;
        }
        long now = clock.getAsLong();
        boolean fresh = !marks.allows(held.account, held.thread, held.message, now);
        marks.allow(held.account, held.thread, held.message, now);
        try {
            chats.sendSeen(session, held.thread, held.message, held.sender);
        } catch (Throwable t) {
            if (fresh) marks.revoke(held.account, held.thread, held.message, now);
            restore(id, held);
            throw t;
        }
        try {
            chats.clearUnread(session, held.key);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, step, t);
        }
        MarkReadButton.held();
        return true;
    }

    /**
     * Keeps what a reply or the header's check mark needs to let [receipt] go: its message, who sent it,
     * the chat's key and the account's session. Kept only while one of them is on, and a reply only
     * lets go what was kept while its own switch was on.
     */
    private static void remember(Object receipt, Object session, BooleanSupplier replies, BooleanSupplier button,
                                 Chats chats, LongSupplier clock) {
        boolean reply = false;
        try {
            reply = replies.getAsBoolean();
            if (!reply && !button.getAsBoolean()) return;
            String account = chats.accountId(session);
            Object key = chats.receiptKey(receipt);
            String thread = chats.threadId(key);
            String message = chats.receiptMessage(receipt);
            String sender = chats.receiptSender(receipt);
            if (isEmpty(account) || isEmpty(thread) || isEmpty(message) || isEmpty(sender)) return;
            String id = account + "\n" + thread;
            synchronized (LOCK) {
                // Taken out first, so the chat moves to the end, where the newest held receipt goes.
                PENDING.remove(id);
                PENDING.put(id, new Pending(account, thread, message, sender, key, session, reply, clock.getAsLong()));
            }
            MarkReadButton.held();
        } catch (Throwable t) {
            // Kept for the check mark alone, a chat that can't be read is the check mark's to report.
            HookStatus.threw(FamilyNames.THREAD_SEEN, reply ? REPLY : OPEN_CHAT, t);
        }
    }

    private static void restore(String id, Pending held) {
        synchronized (LOCK) {
            PENDING.putIfAbsent(id, held);
        }
    }

    /** Forgets the receipt held for [thread] on [account], once its chat was marked read another way. */
    private static void dropHeld(String account, String thread) {
        if (isEmpty(account) || isEmpty(thread)) return;
        synchronized (LOCK) {
            PENDING.remove(account + "\n" + thread);
        }
        MarkReadButton.held();
    }

    /** A held receipt's chat, message, sender and key, and the account it's on, kept until something lets it go. */
    private static final class Pending {
        final String account;
        final String thread;
        final String message;
        final String sender;
        final Object key;
        final WeakReference<Object> session;
        /** Kept while Mark a chat seen when you reply was on. */
        final boolean reply;
        /** When it was held, by {@link #CLOCK}. */
        final long at;

        Pending(String account, String thread, String message, String sender, Object key, Object session,
                boolean reply, long at) {
            this.account = account;
            this.thread = thread;
            this.message = message;
            this.sender = sender;
            this.key = key;
            this.session = new WeakReference<>(session);
            this.reply = reply;
            this.at = at;
        }
    }

    /** The newest held receipt of each chat, by account and thread, the oldest held dropped past 32. */
    private static final Map<String, Pending> PENDING = new LinkedHashMap<String, Pending>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pending> eldest) {
            return size() > 32;
        }
    };

    /**
     * Whether [receipt] waits for the messages lock: its chat is locked, by the messages lock or on
     * its own, so nobody has read it yet. A receipt of a message marked read still goes. [keep] keeps
     * it for {@link #unlocked}. An id that can't be read is reported and counts as unknown, which the
     * messages lock still covers. Anything else thrown lets the receipt go, as the lock fails open.
     */
    private static boolean waitsForLock(Object receipt, Object session, Chats chats, LongSupplier clock, boolean keep) {
        try {
            String account = lockedId(() -> chats.accountId(session));
            String thread = lockedId(() -> chats.threadId(chats.receiptKey(receipt)));
            if (!MessagesLock.holdsReceipt(account, thread) || markedRead(receipt, session, chats, clock)) return false;
            if (keep) {
                String id = account + "\n" + thread;
                synchronized (LOCK) {
                    // Taken out first, so the chat moves to the end, where the newest goes.
                    WAITING.remove(id);
                    WAITING.put(id, new Waiting(receipt, session, account, thread, chats, clock));
                }
            }
            if (!lockLogged) {
                lockLogged = true;
                Logger.printDebug(() -> "Messages: a seen receipt waits for the lock");
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, LOCKED, t);
            return false;
        }
    }

    /** An id {@link #waitsForLock} needs, or null when it can't be read, which is reported. */
    private static String lockedId(Supplier<String> id) {
        try {
            return id.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, LOCKED, t);
            return null;
        }
    }

    /**
     * Called once the lock opens, with the chat on screen as the ask found it. A receipt of that chat
     * that waited for the lock goes through {@link #hold} as if it came now, so the switch, the chat's
     * own choice and Mark as read decide. When nothing holds it, it's queued through Instagram's own
     * sender and the chat's unread mark comes off. The other chats' receipts are dropped: those chats
     * stayed unread, so opening one queues its receipt again. A null [account] matches any. Never throws.
     */
    static void unlocked(String account, String thread) {
        unlocked(account, thread, ThreadSeen::switchedOn);
    }

    static void unlocked(String account, String thread, BooleanSupplier on) {
        try {
            Waiting found = null;
            synchronized (LOCK) {
                for (Waiting waiting : WAITING.values()) {
                    if (!isEmpty(thread) && thread.equals(waiting.thread)
                            && (isEmpty(account) || isEmpty(waiting.account) || account.equals(waiting.account))) {
                        found = waiting;
                    }
                }
                WAITING.clear();
            }
            if (found == null) return;
            Object session = found.session.get();
            Chats chats = found.chats;
            if (session == null || hold(found.receipt, session, on, chats, found.clock)) return;
            chats.sendSeen(session, found.thread, chats.receiptMessage(found.receipt), chats.receiptSender(found.receipt));
            try {
                chats.clearUnread(session, chats.receiptKey(found.receipt));
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, LOCKED, t);
            }
            Logger.printDebug(() -> "Messages: the lock opened, so the open chat's seen receipt went");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, LOCKED, t);
        }
    }

    /** A receipt that waited for the lock, with what letting it go needs. */
    private static final class Waiting {
        final Object receipt;
        final WeakReference<Object> session;
        final String account;
        final String thread;
        /** The chats and clock it was held with, so it's let go the same way. */
        final Chats chats;
        final LongSupplier clock;

        Waiting(Object receipt, Object session, String account, String thread, Chats chats, LongSupplier clock) {
            this.receipt = receipt;
            this.session = new WeakReference<>(session);
            this.account = account;
            this.thread = thread;
            this.chats = chats;
            this.clock = clock;
        }
    }

    /** The newest receipt of each chat that waited for the lock, by account and thread, the oldest dropped past 32. */
    private static final Map<String, Waiting> WAITING = new LinkedHashMap<String, Waiting>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Waiting> eldest) {
            return size() > 32;
        }
    };

    /**
     * Called in the builder that offers Mark as unread on a chat's long press, once Instagram has
     * found the chat can be marked unread, with the rows so far and Instagram's own Mark as read.
     * Adds Mark as read, once, while the switch is on.
     */
    public static void offerMarkRead(List<Object> rows, Object markAsRead) {
        offerMarkRead(rows, markAsRead, () -> switchedOn() || anyHeld(chosen()));
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
        String message = null;
        String sender = null;
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (chosen == null || chosen != markAsRead) return false;
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return false;
            if (own != null && !forChat(own, chats.accountId(session), chats.threadId(key), on, ALWAYS)) return false;
            Object last = chats.lastMessage(thread);
            if (last != null) {
                message = chats.messageId(last);
                sender = chats.senderId(last);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            toast(() -> L10n.t("Couldn't mark as read"));
            return false;
        }
        // A failure lets Instagram's own code for the row run.
        return markChat(session, key, message, sender, chats, clock) != MARK_FAILED;
    }

    /**
     * Whether the preview of the chat at [key] on [session]'s account can mark it read here: the
     * patch is in and the chat's receipts are held, by the switch or by the chat's own choice. With
     * them let through, Instagram marks a chat read the moment it's opened, and the bridges the mark
     * goes through are only written alongside the hold.
     */
    public static boolean canMarkRead(Object session, Object key) {
        try {
            if (!SettingsStatus.threadSeen()) return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            return false;
        }
        return canMarkRead(session, key, ThreadSeen::switchedOn, INSTAGRAM);
    }

    /** {@link #canMarkRead(Object, Object)} once the patch is known to be in, [on] saying what every chat does. */
    static boolean canMarkRead(Object session, Object key, BooleanSupplier on, Chats chats) {
        try {
            SeenChoices own = chosen();
            if (own == null) return on.getAsBoolean();
            return forChat(own, idOf(() -> chats.accountId(session)), idOf(() -> chats.threadId(key)), on, ALWAYS);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            return false;
        }
    }

    /**
     * Marks a chat read from its preview, up to [message] from [sender], the newest message the
     * preview shows, the way the long press menu's Mark as read does. True when the receipt went
     * out. Never throws.
     */
    public static boolean markReadNow(Object session, Object key, String message, String sender) {
        if (!canMarkRead(session, key)) return false;
        return markChat(session, key, message, sender, INSTAGRAM, CLOCK) == MARK_SENT;
    }

    /** What {@link #markChat} did: sent the receipt, found nothing to mark, or failed. */
    static final int MARK_SENT = 0;
    static final int MARK_NOTHING = 1;
    static final int MARK_FAILED = 2;

    /**
     * Lets the receipt for [message] from [sender] through the hold, sends it and clears the chat's
     * unread mark.
     */
    static int markChat(Object session, Object key, String message, String sender, Chats chats, LongSupplier clock) {
        ReadMarks waiting = null;
        String account = null;
        String id = null;
        long now = 0;
        try {
            account = chats.accountId(session);
            id = chats.threadId(key);
            ReadMarks marks = marks();
            if (isEmpty(account) || isEmpty(id) || isEmpty(message) || isEmpty(sender) || marks == null) {
                Logger.printDebug(() -> "Messages: a chat marked read has no message to mark");
                toast(() -> L10n.t("Couldn't mark as read"));
                return MARK_NOTHING;
            }
            now = clock.getAsLong();
            boolean fresh = !marks.allows(account, id, message, now);
            marks.allow(account, id, message, now);
            if (fresh) waiting = marks;
            chats.sendSeen(session, id, message, sender);
            waiting = null;
            dropHeld(account, id);
            try {
                chats.clearUnread(session, key);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, MARK, t);
            }
            toast(() -> L10n.t("Marked as read"));
            return MARK_SENT;
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
            return MARK_FAILED;
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
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return;
            String account = chats.accountId(session);
            if (own != null && !forChat(own, account, thread, on, ALWAYS)) return;
            ReadMarks marks = marks();
            if (isEmpty(account) || isEmpty(thread) || isEmpty(message) || marks == null) {
                Logger.printDebug(() -> "Messages: a chat marked read together has no message to mark");
                return;
            }
            marks.allow(account, thread, message, clock.getAsLong());
            dropHeld(account, thread);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, TOGETHER, t);
        }
    }

    /**
     * Asked where Instagram's queue reports a chat receipt's state to the chat store, before the
     * store hears it, with the receipt and the store. True leaves the store alone, so the chat isn't
     * recorded seen and stays unread on this phone. False while either switch is off, HushGram is
     * paused or the settings aren't ready, for the receipt of a message marked read, which Mark as
     * read sends and so clears the chat, and when the receipt or the store can't be read. A newer
     * message in a chat marked read has another id, so its chat stays unread. True too, whatever the
     * switches say, while the receipt's chat is locked. Never throws.
     */
    public static boolean keepUnread(Object receipt, Object store) {
        return keepUnread(receipt, store, ThreadSeen::keepsUnread, INSTAGRAM, CLOCK);
    }

    static boolean keepUnread(Object receipt, Object store, BooleanSupplier on, Chats chats, LongSupplier clock) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            if (MessagesLock.receiptsWait() && waitsForLock(receipt, chats.storeSession(store), chats, clock, false)) {
                return true;
            }
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return false;
            if (own != null && !forChat(own, idOf(() -> chats.accountId(chats.storeSession(store))),
                    idOf(() -> chats.threadId(chats.receiptKey(receipt))), on, Settings.KEEP_CHATS_UNREAD::get)) {
                return false;
            }
            ReadMarks marks = marks();
            if (marks == null) return false;
            long now = clock.getAsLong();
            // With no mark kept, nothing was marked read, so the receipt isn't read at all.
            if (!marks.isEmpty(now)) {
                String account = chats.accountId(chats.storeSession(store));
                String thread = chats.threadId(chats.receiptKey(receipt));
                String message = chats.receiptMessage(receipt);
                if (isEmpty(account) || isEmpty(thread) || isEmpty(message)) {
                    Logger.printDebug(() -> "Messages: a chat's seen state has no message to match, so it clears");
                    return false;
                }
                if (marks.allows(account, thread, message, now)) return false;
            }
            if (!keptLogged) {
                keptLogged = true;
                Logger.printDebug(() -> "Messages: kept a chat unread on this phone");
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, UNREAD, t);
            return false;
        }
    }

    /**
     * Asked at the start of the inbox's question whether a chat shows unread, with the chat's
     * summary and the account. True makes the chat read, unless Instagram's own Mark as unread is on
     * it, which the patch checks next. True only while the switch is on and Keep chats unread here is
     * off, for a chat opened here while its receipt was held, as long as the message newest then is
     * still its newest. False otherwise, when the summary or the account can't be read, and while
     * HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean readHere(Object thread, Object session) {
        return readHere(thread, session, ThreadSeen::readsHere, INSTAGRAM);
    }

    static boolean readHere(Object thread, Object session, BooleanSupplier on, Chats chats) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return false;
            ReadHere kept = opened();
            // With no chat kept, nothing was opened while its receipt was held.
            if (kept == null || kept.isEmpty()) return false;
            String account = chats.accountId(session);
            String id = chats.threadId(chats.chatKey(thread));
            String newest = chats.newestMessage(thread);
            if (isEmpty(account) || isEmpty(id) || isEmpty(newest)) return false;
            if (!forChat(own, account, id, on, ThreadSeen::readsHereByChoice)) return false;
            return kept.readUpTo(account, id, newest);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, READ, t);
            return false;
        }
    }

    /**
     * Asked in Instagram's read of the account's own cursor in a chat, right after it fetched the
     * cursor, with the reader, the thread source it was handed and the chat's key. The cursor is where the server thinks the account
     * has seen up to, and with the receipt held it never moves, so a chat that reads as read in the
     * inbox still opened with the New Messages divider and jumped up to it (#186). When this phone
     * kept the chat read up to a message that is past the cursor, the cursor is moved to that message
     * here, in memory only, so the divider and the jump follow what was viewed. Nothing is sent. Does
     * nothing while the switch is off or Keep chats unread here is on, when the chat wasn't opened
     * here, when the message isn't among the chat's loaded items or isn't past the cursor. Never throws.
     */
    public static void localCursor(Object reader, Object source, Object key) {
        localCursor(reader, key, ThreadSeen::readsHere, INSTAGRAM);
    }

    static void localCursor(Object reader, Object key, BooleanSupplier on, Chats chats) {
        try {
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return;
            ReadHere kept = opened();
            if (kept == null || kept.isEmpty()) return;
            String account = chats.accountId(chats.cursorSession(reader));
            String thread = chats.threadId(key);
            if (isEmpty(account) || isEmpty(thread)) return;
            if (!forChat(own, account, thread, on, ThreadSeen::readsHereByChoice)) return;
            String message = kept.messageOf(account, thread);
            if (isEmpty(message)) return;
            Object cursor = chats.cursorOf(reader);
            List<?> items = chats.cursorItems(reader, key);
            if (cursor == null || items == null) return;
            String at = chats.cursorItem(cursor);
            int seen = -1;
            int viewed = -1;
            for (int index = 0; index < items.size(); index++) {
                String id = chats.messageId(items.get(index));
                if (id == null) continue;
                if (id.equals(message)) viewed = index;
                if (id.equals(at)) seen = index;
            }
            // Items run oldest first. Only a viewed message past the cursor moves it, never back.
            if (viewed < 0 || viewed <= seen) return;
            chats.moveCursor(cursor, message);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, READ, t);
        }
    }

    /**
     * Asked at the start of the summary's check whether an account has seen one of the chat's items,
     * with the summary, the account's id and the item's. True makes the item seen: only for the
     * message kept read here on that account, with the switch on and Keep chats unread here off.
     * Instagram's sync puts that message on the summary as one the server still has unseen, and the
     * inbox showed the chat unread again for it (#186). A newer message isn't kept, so it still shows.
     * False otherwise, for any other account's id, when the summary can't be read, and while HushGram
     * is paused or the settings aren't ready. Never throws.
     */
    public static boolean seenHere(Object thread, String account, String message) {
        return seenHere(thread, account, message, ThreadSeen::readsHere, INSTAGRAM);
    }

    static boolean seenHere(Object thread, String account, String message, BooleanSupplier on, Chats chats) {
        try {
            SeenChoices own = chosen();
            if (!on.getAsBoolean() && !anyHeld(own)) return false;
            ReadHere kept = opened();
            if (kept == null || kept.isEmpty() || isEmpty(account) || isEmpty(message)) return false;
            String id = chats.threadId(chats.chatKey(thread));
            if (isEmpty(id)) return false;
            if (!forChat(own, account, id, on, ThreadSeen::readsHereByChoice)) return false;
            return kept.readUpTo(account, id, message);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, READ, t);
            return false;
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
     * Keeps [receipt]'s chat read here up to the message it points at, while [here] says so. A
     * receipt that can't be read is left out, and a failure is reported without changing the hold.
     */
    private static void keepRead(Object receipt, Object session, BooleanSupplier here, Chats chats, LongSupplier clock) {
        try {
            if (!here.getAsBoolean()) return;
            ReadHere kept = opened();
            if (kept == null) return;
            String account = chats.accountId(session);
            String thread = chats.threadId(chats.receiptKey(receipt));
            String message = chats.receiptMessage(receipt);
            if (isEmpty(account) || isEmpty(thread) || isEmpty(message)) return;
            kept.read(account, thread, message, clock.getAsLong());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, READ, t);
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

    /** The chats kept read here, kept like {@link #marks()}. Null before Instagram's context is set. */
    private static ReadHere opened() {
        synchronized (LOCK) {
            if (opened == null) {
                if (!Utils.settingsReady()) return null;
                Context context = Utils.getContext();
                if (context == null) return null;
                opened = new ReadHere(Utils.isMainProcess()
                        ? context.getSharedPreferences(ReadHere.FILE, Context.MODE_PRIVATE)
                        : null);
            }
            return opened;
        }
    }

    /**
     * The chats given a seen choice of their own. Null before Instagram's context is set. Unlike
     * {@link #marks()}, Instagram's other processes read the file too, as it stood when they started,
     * the way they read every setting. Only the main process writes it.
     */
    private static SeenChoices choices() {
        synchronized (LOCK) {
            if (choices == null) {
                if (!Utils.settingsReady()) return null;
                Context context = Utils.getContext();
                if (context == null) return null;
                choices = new SeenChoices(context.getSharedPreferences(SeenChoices.FILE, Context.MODE_PRIVATE),
                        Utils.isMainProcess());
            }
            return choices;
        }
    }

    /**
     * The chats' own choices, while Choose Seen in each chat is on and some chat has one. Null
     * otherwise, and when they can't be read, which is reported: every chat then follows the switch.
     */
    private static SeenChoices chosen() {
        try {
            if (!choosesPerChat()) return null;
            SeenChoices own = choices();
            return own == null || own.isEmpty() ? null : own;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return null;
        }
    }

    /** Whether some chat in [own] holds its receipts by its own choice, so a hook can't stop at the switch. */
    private static boolean anyHeld(SeenChoices own) {
        try {
            return own != null && own.anyHeld();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return false;
        }
    }

    /**
     * What [thread] on [account] does where [on] says what every chat does: [on] for a chat that
     * follows the switch, [held] for one that holds its receipts by its own choice, and false for one
     * that sends them. With no choices, the chat unknown or its choice unreadable, [on]. Never
     * replays anything: a choice only changes what's held from then on.
     */
    private static boolean forChat(SeenChoices own, String account, String thread, BooleanSupplier on,
                                   BooleanSupplier held) {
        if (own == null || isEmpty(account) || isEmpty(thread)) return on.getAsBoolean();
        SeenChoices.Choice choice;
        try {
            choice = own.of(account, thread);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            choice = SeenChoices.Choice.SWITCH;
        }
        if (choice == SeenChoices.Choice.HOLD) return held.getAsBoolean();
        if (choice == SeenChoices.Choice.SEND) return false;
        return on.getAsBoolean();
    }

    /** {@link #forChat}, asked only when needed, so a switch that throws is reported where it was. */
    private static BooleanSupplier chatSwitch(SeenChoices own, String account, String thread, BooleanSupplier on,
                                              BooleanSupplier held) {
        return own == null ? on : () -> forChat(own, account, thread, on, held);
    }

    /** One id a chat's choice is looked up by, or null when it can't be read, which is reported. */
    private static String idOf(Supplier<String> id) {
        try {
            return id.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return null;
        }
    }

    /**
     * What the eye in [thread]'s header shows it chose on [account]. {@link SeenChoices.Choice#SWITCH}
     * when the chat has no choice of its own or can't be read. Never throws.
     */
    static SeenChoices.Choice choiceOf(String account, String thread) {
        try {
            SeenChoices own = choices();
            if (own == null || isEmpty(account) || isEmpty(thread)) return SeenChoices.Choice.SWITCH;
            return own.of(account, thread);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return SeenChoices.Choice.SWITCH;
        }
    }

    /**
     * Gives [thread] on [account] [choice] from the eye in its header, for its receipts from then on.
     * A receipt already held stays held, and one already sent stays sent. True when it was kept.
     * Never throws.
     */
    static boolean choose(String account, String thread, SeenChoices.Choice choice) {
        return choose(account, thread, choice, CLOCK);
    }

    static boolean choose(String account, String thread, SeenChoices.Choice choice, LongSupplier clock) {
        try {
            SeenChoices own = choices();
            if (own == null || isEmpty(account) || isEmpty(thread) || choice == null) return false;
            own.choose(account, thread, choice, clock.getAsLong());
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return false;
        }
    }

    /** Whether [thread] on [account] holds its receipts now, its own choice first, then the switch. Never throws. */
    static boolean holdsHere(String account, String thread) {
        try {
            return forChat(chosen(), account, thread, ThreadSeen::switchedOn, ALWAYS);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, CHOICE, t);
            return false;
        }
    }

    /** Whether [thread] on [account] shows the header's check mark: {@link #showsButton()}, or its own Hold. */
    static boolean showsButton(String account, String thread) {
        return forChat(chosen(), account, thread, ThreadSeen::showsButton, Settings.MARK_READ_IN_CHAT::get);
    }

    /** Whether a chat can show the header's check mark, by the switch or by a Hold it's given while open. */
    static boolean anyShowsButton() {
        return showsButton() || (choosesPerChat() && Settings.MARK_READ_IN_CHAT.get());
    }

    /** Forgets every mark, every chat kept read and every chat's own choice, on file too, for tests. */
    static void forgetMarks() {
        synchronized (LOCK) {
            PENDING.clear();
            WAITING.clear();
            ReadMarks current = marks();
            if (current != null) current.clear();
            marks = null;
            ReadHere kept = opened();
            if (kept != null) kept.clear();
            opened = null;
            SeenChoices own = choices();
            if (own != null) own.clear();
            choices = null;
        }
    }

    /** How many chats are kept read here, for tests. */
    static int openedForTests() {
        ReadHere kept = opened();
        return kept == null ? 0 : kept.size();
    }

    /** Drops the marks and chats read so far, as a restart does, so the next one is read from the file. For tests. */
    static void restartForTests() {
        synchronized (LOCK) {
            marks = null;
            opened = null;
            choices = null;
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

    /** Both switches, read the same way: chats stay unread only while their receipts are held. */
    static boolean keepsUnread() {
        return switchedOn() && Settings.KEEP_CHATS_UNREAD.get();
    }

    /** The switch on and Mark a chat seen when you reply on: a sent message lets its chat's held receipt go. */
    static boolean marksOnReply() {
        return switchedOn() && Settings.MARK_SEEN_ON_REPLY.get();
    }

    /** The switch on and Mark as read in a chat's header on: the check mark shows while a receipt is held. */
    static boolean showsButton() {
        return switchedOn() && Settings.MARK_READ_IN_CHAT.get();
    }

    /** The switch on and Keep chats unread here off: an opened chat stays read here instead. */
    static boolean readsHere() {
        return switchedOn() && !Settings.KEEP_CHATS_UNREAD.get();
    }

    /** Keep chats unread here off, for a chat that holds its receipts by its own choice. */
    static boolean readsHereByChoice() {
        return !Settings.KEEP_CHATS_UNREAD.get();
    }

    /** Choose Seen in each chat, read the same way: the eye shows in a chat's header, and its choice counts. */
    static boolean choosesPerChat() {
        return Utils.settingsReady() && Settings.SEEN_PER_CHAT.get();
    }
}
