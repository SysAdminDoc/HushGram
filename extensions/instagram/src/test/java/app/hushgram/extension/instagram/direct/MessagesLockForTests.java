/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import java.util.List;

/** Lets tests in other packages start the lock from how a fresh start finds it, and drive it. */
public final class MessagesLockForTests {
    private MessagesLockForTests() {
    }

    public static void reset() {
        MessagesLock.resetForTests();
    }

    /** Each ask's two answers (confirmed, not confirmed) go into [asks] instead of a prompt. */
    public static void askWith(List<Runnable[]> asks) {
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> asks.add(new Runnable[]{confirmed, notConfirmed});
    }

    /** As a confirmed ask leaves it. */
    public static void open() {
        MessagesLock.openForTests();
    }

    /** As a leave with Lock again at Right away leaves it. */
    public static void relock() {
        MessagesLock.relock(true);
    }

    /** As Instagram leaving the screen leaves it, an ask that's up included. */
    public static void leave() {
        MessagesLock.left();
    }

    /** Instagram signed in to [account], as the chat lists read it. */
    public static void signIn(String account) {
        ChatAccounts.signInForTests(account);
    }

    /** No account known, as at a fresh start. */
    public static void signOut() {
        ChatAccounts.resetForTests();
    }
}
