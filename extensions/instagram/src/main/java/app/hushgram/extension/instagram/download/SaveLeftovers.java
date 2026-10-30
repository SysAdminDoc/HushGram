/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/**
 * What a save that never finished leaves behind, and its removal.
 *
 * <p>A save removes its own files when it fails or is cancelled. When Android ends Instagram's
 * process in the middle of one, nothing runs: the work files stay in the cache, a save that was
 * already copying into the gallery leaves a row marked pending, which the platform only clears
 * after about a week, and its notification stays up. So each start of Instagram removes all three,
 * on a worker, and the first save of a process waits for that or, if it comes first, does it
 * itself before it makes anything of its own. Only the main process saves, so nothing there can
 * belong to a save still running, and a notification of one this process is running is kept.
 *
 * <p>The pending rows are known by a list kept here, not found by a query. The app's own save
 * can write into the same folders with the same kind of names, and a query can't tell its rows from
 * these. On Android 9 the list holds the hidden work file itself ({@link MediaStoreWriter}), and
 * in a folder picked in settings the hidden work file's document ({@link SaveTree}).
 */
public final class SaveLeftovers {

    private SaveLeftovers() {}

    /**
     * Instagram started. In its main process, the sweep goes to a worker, so the start doesn't wait
     * on the gallery, and it's once per process however often this is called. The start used to
     * leave it to the next save, and without one a stopped save's pending row and notification
     * stayed. Never throws.
     */
    public static void sweepAfterStart(Context context) {
        try {
            if (context == null || !Utils.isMainProcess()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            // A full queue leaves the sweep to the first save, as before.
            Utils.runOnBackgroundThread(() -> sweepOnce(application));
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not start removing what a stopped save left", t);
        }
    }

    /** Private cleanup state. Job markers are random, never media, account or network identities. */
    private static final String LEDGER = "hushgram_saves";
    private static final String PENDING = "pending_rows";
    private static final String JOBS = "active_jobs";
    private static final String INTERRUPTED = "interrupted_jobs";
    private static final String OUTCOMES = "hushgram-save-outcomes";

    private static final Object LOCK = new Object();
    private static boolean swept;
    private static boolean cleanupFinished;
    private static volatile boolean noticeChecked;
    private static volatile int interrupted;
    private static int awaitingNotice;

    /** Complete feedback remains available in settings for this process, without a disk history. */
    public static int interruptedCount() {
        return interrupted;
    }

    /** A resumed app or settings page consumes the notice only after cleanup and a durable write. */
    public static void showInterrupted(Context context) {
        try {
            if (context == null || noticeChecked || !Utils.isMainProcess()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Utils.runOnBackgroundThread(() -> {
                try {
                    int count;
                    synchronized (LOCK) {
                        sweepOnce(application);
                        if (noticeChecked || !cleanupFinished) return;
                        SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                        count = Math.max(awaitingNotice, ledger.getInt(INTERRUPTED, 0));
                        if (count > 0 && !ledger.edit().remove(INTERRUPTED).commit()) {
                            // commit(false) can still change the in-memory preference map.
                            awaitingNotice = count;
                            ledger.edit().putInt(INTERRUPTED, count).commit();
                            MediaSave.failure(() -> "could not consume the interrupted-save notice", null);
                            return;
                        }
                        noticeChecked = true;
                        awaitingNotice = 0;
                        interrupted = count;
                    }
                    if (count <= 0) return;
                    Feedback.show(application, L10n.quantity(application, count,
                            "A save stopped. Reopen the media and save again.",
                            "%1$d saves stopped. Reopen the media and save again."), true);
                    SaveControl.tell();
                } catch (Throwable failure) {
                    MediaSave.failure(() -> "could not show the interrupted-save notice", failure);
                }
            });
        } catch (Throwable failure) {
            MediaSave.failure(() -> "could not start the interrupted-save notice", failure);
        }
    }

    /** One logical save, before its first resource or transfer. Failed recording stops the save. */
    static String beginJob(Context application) {
        synchronized (LOCK) {
            SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
            Set<String> jobs = new HashSet<>(ledger.getStringSet(JOBS, new HashSet<>()));
            if (jobs.size() >= 64) throw new IllegalStateException("Save cleanup ledger is full");
            String token = UUID.randomUUID().toString();
            File marker = outcome(application, token);
            try {
                if (!marker.getParentFile().mkdirs() && !marker.getParentFile().isDirectory()) throw new IOException();
                if (!marker.createNewFile()) throw new IOException();
                try (FileOutputStream file = new FileOutputStream(marker)) {
                    file.write(0);
                    file.getFD().sync();
                }
            } catch (IOException failure) {
                marker.delete();
                throw new IllegalStateException("Could not allocate the save outcome marker", failure);
            }
            jobs.add(token);
            if (!ledger.edit().putStringSet(JOBS, jobs).commit()) {
                jobs.remove(token);
                terminal(application, token);
                if (ledger.edit().putStringSet(JOBS, jobs).commit()) marker.delete();
                throw new IllegalStateException("Could not record the active save");
            }
            return token;
        }
    }

    /** All normal results, including cancellation and failure, retire the same single marker. */
    static void finishJob(Context application, String token) {
        if (token == null) return;
        synchronized (LOCK) {
            try {
                // Preallocated before the save: finishing needs no new file or preference write.
                // Keep this terminal bit if preference retirement fails, so a later process can
                // distinguish an ended job from one Android killed.
                terminal(application, token);
                SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                Set<String> jobs = new HashSet<>(ledger.getStringSet(JOBS, new HashSet<>()));
                if (!jobs.remove(token)) return;
                // A failed commit can still remove the token from the in-memory map. Retry the
                // captured remaining set, not a fresh read that would mistake that for success.
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        SharedPreferences.Editor update = ledger.edit();
                        if (jobs.isEmpty()) update.remove(JOBS);
                        else update.putStringSet(JOBS, jobs);
                        if (update.commit()) { outcome(application, token).delete(); return; }
                    } catch (Throwable failure) {
                        MediaSave.failure(() -> "could not retire an active-save marker", failure);
                    }
                }
                MediaSave.failure(() -> "could not retire an active-save marker after retry", null);
            } catch (Throwable failure) {
                MediaSave.failure(() -> "could not retire an active-save marker", failure);
            }
        }
    }

    private static File outcome(Context context, String token) {
        if (!UUID.fromString(token).toString().equals(token)) throw new IllegalArgumentException("Invalid save marker");
        return new File(new File(context.getFilesDir(), OUTCOMES), token);
    }

    private static void terminal(Context context, String token) {
        File marker = outcome(context, token);
        try (RandomAccessFile file = new RandomAccessFile(marker, "rw")) {
            file.seek(0);
            file.write(1);
            file.getFD().sync();
        } catch (IOException failure) {
            // Preference retirement can still settle it. Failure of both paths is logged.
            MediaSave.failure(() -> "could not record a save's terminal outcome", failure);
        }
    }

    private static boolean completed(Context context, String token) throws IOException {
        File marker = outcome(context, token);
        if (!marker.exists()) return false; // The previous source format had no outcome marker.
        try (FileInputStream file = new FileInputStream(marker)) {
            int state = file.read();
            if ((state != 0 && state != 1) || file.read() != -1) throw new IOException("Unreadable save outcome");
            return state == 1;
        }
    }

    /**
     * Once per process, before the first save makes a file or a row. Every save calls it first, so
     * a save started while the sweep after Instagram's start runs waits for it on the lock.
     */
    static void sweepOnce(Context application) {
        synchronized (LOCK) {
            if (swept) return;
            swept = true;
            // A save waits on this and goes on after it, so nothing here may throw into that save.
            // What a failure leaves stays on the list for the next process to try again.
            try {
                int files = removeWorkFiles(application);
                int rows = removePendingRows(application);
                int notices = SaveControl.removeStale(application);
                SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                cleanupFinished = files >= 0 && rows >= 0 && notices >= 0
                        && ledger.getStringSet(PENDING, new HashSet<>()).isEmpty();
                Set<String> jobs = ledger.getStringSet(JOBS, new HashSet<>());
                // A process can end between allocating its marker and committing the job. Those
                // files own no save resources and must not accumulate across failed starts.
                File[] markers = new File(application.getFilesDir(), OUTCOMES).listFiles();
                if (markers != null) for (File marker : markers) {
                    if (marker.isFile() && marker.getName().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                            && !jobs.contains(marker.getName())) marker.delete();
                }
                if (cleanupFinished && !jobs.isEmpty()) {
                    int unfinished = 0;
                    for (String token : jobs) if (!completed(application, token)) unfinished++;
                    int count = Math.addExact(ledger.getInt(INTERRUPTED, 0), unfinished);
                    SharedPreferences.Editor update = ledger.edit().remove(JOBS);
                    if (count > 0) update.putInt(INTERRUPTED, count);
                    cleanupFinished = update.commit();
                    if (cleanupFinished) for (String token : jobs) outcome(application, token).delete();
                    if (!cleanupFinished) MediaSave.failure(() -> "could not record the interrupted-save notice", null);
                }
                if (files > 0 || rows > 0 || notices > 0) {
                    MediaSave.info(() -> "removed what a stopped save left: " + files + " work file(s), "
                        + rows + " pending gallery row(s), " + notices + " notification(s)");
                }
            } catch (Throwable t) {
                cleanupFinished = false;
                MediaSave.failure(() -> "could not remove what a stopped save left", t);
            }
        }
    }

    /** The next save sweeps again, as the first save of a new process would. For tests. */
    static void forgetSweepForTests() {
        synchronized (LOCK) {
            swept = false;
            cleanupFinished = false;
            noticeChecked = false;
            interrupted = 0;
            awaitingNotice = 0;
        }
    }

    /**
     * A row was just inserted pending. Answers whether the list holds it on disk now; a row it
     * doesn't hold would be nobody's to remove, so the caller removes it before a byte goes in.
     */
    static boolean pending(Context application, Uri row) {
        return record(application, row, true);
    }

    /** The row was published or removed. */
    static void settled(Context application, Uri row) {
        record(application, row, false);
    }

    private static boolean record(Context application, Uri row, boolean add) {
        if (row == null) return false;
        synchronized (LOCK) {
            try {
                SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                Set<String> rows = new HashSet<>(ledger.getStringSet(PENDING, new HashSet<>()));
                boolean changed = add ? rows.add(row.toString()) : rows.remove(row.toString());
                // commit(), not apply(): a process ended right after an apply() can lose the
                // entry, and the entry exists for exactly that case. This runs on the save's thread.
                if (!changed || ledger.edit().putStringSet(PENDING, rows).commit()) return true;
                MediaSave.failure(() -> "could not write the list of pending gallery rows", null);
            } catch (Throwable t) {
                MediaSave.failure(() -> "could not update the list of pending gallery rows", t);
            }
            return false;
        }
    }

    private static int removeWorkFiles(Context application) {
        File folder = DashSave.workFolder(application);
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) {
            MediaSave.failure(() -> "could not inspect stopped-save work files", null);
            return -1;
        }

        int removed = 0;
        boolean complete = true;
        for (File file : files) {
            if (DashSave.inUse(file)) continue;
            if (file.delete()) removed++;
            else if (file.exists()) complete = false;
        }
        if (!complete) MediaSave.failure(() -> "could not remove every stopped-save work file", null);
        return complete ? removed : -1;
    }

    private static int removePendingRows(Context application) {
        SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
        Set<String> rows = ledger.getStringSet(PENDING, null);
        if (rows == null || rows.isEmpty()) return 0;

        ContentResolver resolver = application.getContentResolver();
        int removed = 0;
        Set<String> retry = new HashSet<>();
        for (String row : new HashSet<>(rows)) {
            try {
                Uri address = Uri.parse(row);
                if ("file".equals(address.getScheme())) {
                    // Android 9's hidden work file (MediaStoreWriter). Only ever the hidden name: a
                    // finished file has another, so a rename that beat the cross-off keeps it.
                    File file = address.getPath() == null ? null : new File(address.getPath());
                    if (file == null || !file.getName().startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX)) continue;
                    boolean existed = file.exists();
                    if (existed && !file.delete()) retry.add(row);
                    else if (existed) removed++;
                    continue;
                }
                if (DocumentsContract.isTreeUri(address)) {
                    // A picked folder's hidden work file (SaveTree), on the same terms as Android 9's.
                    int outcome = SaveTree.removeStopped(application, address);
                    if (outcome == SaveTree.RETRY) retry.add(row);
                    else if (outcome == SaveTree.REMOVED) removed++;
                    continue;
                }
                // Only while it's still pending: a row the stopped save had already published is a
                // finished file that was crossed off too late. MediaStore matches a pending row
                // when it's named by its own address.
                int deleted = 0;
                RuntimeException deletionFailure = null;
                try {
                    deleted = resolver.delete(address, MediaStore.MediaColumns.IS_PENDING + "=1", null);
                } catch (RuntimeException failure) {
                    // Samsung can reject an already-absent URI. The query below must prove its
                    // state before retirement; a delete exception alone proves nothing.
                    deletionFailure = failure;
                }
                removed += deleted;
                if (deleted == 0) {
                    // Zero also means a provider refused deletion. Cross off only a confirmed
                    // absent or already-published row; otherwise the next process retries it.
                    try (Cursor remaining = resolver.query(address,
                            new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
                        if (remaining == null) retry.add(row);
                        else if (remaining.moveToFirst()) {
                            int column = remaining.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
                            if (column < 0 || remaining.isNull(column) || remaining.getInt(column) != 0) retry.add(row);
                        }
                    }
                    if (retry.contains(row) && deletionFailure != null) {
                        MediaSave.failure(() -> "could not remove a pending gallery row a stopped save left", deletionFailure);
                    }
                }
            } catch (Throwable t) {
                retry.add(row);
                MediaSave.failure(() -> "could not remove a pending gallery row a stopped save left", t);
            }
        }
        SharedPreferences.Editor update = ledger.edit();
        if (retry.isEmpty()) update.remove(PENDING);
        else update.putStringSet(PENDING, retry);
        if (!update.commit()) {
            MediaSave.failure(() -> "could not update the list of pending gallery rows after a sweep", null);
            return -1;
        }
        return removed;
    }
}
