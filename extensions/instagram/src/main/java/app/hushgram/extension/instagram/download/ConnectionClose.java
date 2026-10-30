/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Socket cancellation must not wait in the same pool as the reads it ends. */
final class ConnectionClose {
    private static final ThreadPoolExecutor CLOSING = new ThreadPoolExecutor(
            3, 3, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), work -> {
                Thread thread = new Thread(work, "hushgram-save-close");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { CLOSING.allowCoreThreadTimeOut(true); }

    private ConnectionClose() {}

    static boolean closeAsync(Runnable close) {
        try {
            CLOSING.execute(() -> {
                try { close.run(); }
                catch (Throwable ignored) { /* The owner still performs final cleanup. */ }
            });
            return true;
        } catch (RejectedExecutionException occupied) {
            return false;
        }
    }
}
