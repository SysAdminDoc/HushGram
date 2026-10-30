/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded ownership while native discovery or a connection callback cannot be interrupted. */
final class DownloadPreparation implements Downloader.Progress {
    interface Work {
        Object open(DownloadPreparation preparation) throws IOException;
    }

    private static final int MAX_PREPARING = 3;
    private static final ThreadPoolExecutor PREPARING = new ThreadPoolExecutor(
            MAX_PREPARING, MAX_PREPARING, 10, TimeUnit.SECONDS, new SynchronousQueue<>(),
            work -> daemon(work, "hushgram-save-prepare"), new ThreadPoolExecutor.AbortPolicy());
    static {
        PREPARING.allowCoreThreadTimeOut(true);
    }

    /** A shorter preparation deadline for tests. Zero keeps the caller's normal deadline. */
    static volatile long timeoutForTests;

    private final Downloader.Progress progress;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile boolean abandoned;
    private boolean claimed;
    private Object answer;
    private Throwable failure;
    private Downloader.Prepared live;
    private boolean closeRequested;

    private DownloadPreparation(Downloader.Progress progress) {
        this.progress = progress;
        // There may not be a socket yet. Cancel still abandons discovery and the later handoff.
        progress.reading(this::cancel);
    }

    static Object await(Downloader.Progress progress, long timeoutMs, Work work) throws IOException {
        DownloadPreparation owner = new DownloadPreparation(progress);
        if (owner.cancelled()) return cancelledResult();
        long testTimeout = timeoutForTests;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(testTimeout > 0 ? testTimeout : timeoutMs);
        try {
            // No queue: callbacks that ignore cancellation retain at most these three workers.
            PREPARING.execute(() -> owner.prepare(work));
        } catch (RejectedExecutionException occupied) {
            owner.cancel();
            return Downloader.Result.fail(Downloader.Status.NETWORK_ERROR, "connection preparation is busy");
        }
        try {
            while (true) {
                if (owner.cancelled()) return cancelledResult();
                long left = deadline - System.nanoTime();
                if (left <= 0) return Downloader.Result.fail(Downloader.Status.NETWORK_ERROR, "connection preparation timed out");
                if (owner.ready.await(Math.min(left, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) {
                    Object prepared;
                    synchronized (owner) {
                        if (owner.cancelled()) return cancelledResult();
                        if (deadline - System.nanoTime() <= 0) return Downloader.Result.fail(
                                Downloader.Status.NETWORK_ERROR, "connection preparation timed out");
                        prepared = owner.answer;
                        owner.answer = null;
                        owner.claimed = true;
                        owner.released.countDown();
                    }
                    Throwable failed = owner.failure;
                    if (failed instanceof IOException) throw (IOException) failed;
                    if (failed instanceof RuntimeException) throw (RuntimeException) failed;
                    if (failed instanceof Error) throw (Error) failed;
                    if (failed != null) throw new IOException("connection preparation failed", failed);
                    return prepared;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("connection preparation interrupted", interrupted);
        } finally {
            synchronized (owner) {
                if (!owner.claimed) owner.cancel();
            }
        }
    }

    private void prepare(Work work) {
        try {
            if (!cancelled()) answer = work.open(this);
            else answer = cancelledResult();
        } catch (Throwable failed) {
            failure = failed;
        } finally {
            ready.countDown();
            boolean interrupted = false;
            while (true) {
                try { released.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            boolean taken;
            synchronized (this) { taken = claimed; }
            // This worker keeps its slot until a late response is closed. No cleanup queue owns it.
            if (!taken && answer instanceof Downloader.Prepared) ((Downloader.Prepared) answer).closeQuietly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    void reading(Downloader.Prepared response) {
        synchronized (this) {
            live = response;
            closeRequested = false;
        }
        if (cancelled()) cancel();
    }

    void closed(Downloader.Prepared response) {
        synchronized (this) { if (live == response) live = null; }
    }

    private void cancel() {
        Downloader.Prepared response;
        synchronized (this) {
            abandoned = true;
            released.countDown();
            response = live;
            if (response == null || closeRequested) return;
            closeRequested = true;
        }
        // Only disconnect crosses threads. The preparation/fetch worker owns stream.close().
        // If this bounded queue is full, that worker still closes after the socket's read timeout.
        ConnectionClose.closeAsync(response::disconnect);
    }

    @Override public void transferred(long done, long total) { progress.transferred(done, total); }
    @Override public void reading(Runnable close) { throw new IllegalStateException("A preparation must own its response"); }
    @Override public boolean cancelled() { return abandoned || progress.cancelled(); }

    private static Downloader.Result cancelledResult() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "the person saving cancelled it");
    }

    private static Thread daemon(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }
}
