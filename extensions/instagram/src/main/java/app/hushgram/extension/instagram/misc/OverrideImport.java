/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Applies a validated overrides document through the typed native table Instagram's own override
 * editor writes, one parameter at a time.
 *
 * <p>Nothing reaches the native table while Allow importing overrides is off or HushGram is
 * paused, or until the document fits this session's schema (a HushGram export this exact build and
 * schema, Instagram's own file each override's config, index, given names and type), every changed
 * parameter's type agrees with Instagram's own decoder, the store's file reads the same twice a
 * settle window apart, and a last capture right before the first write still sees the same
 * manager, store and bytes. The previous overrides are saved first, outside the native store, under
 * a pending name that becomes the restore point only once the import applied or couldn't be put
 * back. After writing, the store is read back; if it doesn't hold the result, every change is put
 * back the same typed way. Nothing here writes the native file directly, imports a string, wipes
 * the table or reloads it.
 */
public final class OverrideImport {
    public enum Outcome {
        /** The document already matches the store. No native call was made. */
        UNCHANGED,
        /** The store now holds the document. Instagram applies it after a restart. */
        APPLIED,
        /** The store didn't take the change, and its previous values were put back and read back. */
        ROLLED_BACK,
        /** The store didn't take the change and the put-back couldn't be confirmed. Restore is armed. */
        UNRECOVERED,
        /**
         * Restore put back every change a typed writer can make and the store held them, but some
         * overrides hold Instagram's null value on one side, which no typed writer sets or clears.
         */
        PARTIAL
    }

    public static final class Result {
        public final Outcome outcome;
        /** Typed changes written and confirmed. */
        public final int changes;
        /** Overrides Restore couldn't put back because one side holds Instagram's null value. */
        public final int skipped;
        /** Whether imports for this store still wait for Restore or Discard. */
        public final boolean blocked;
        /** Overrides of Instagram's own file from another build that this one doesn't have, left out of the import. */
        public final int leftOut;
        Result(Outcome outcome, int changes, int skipped, boolean blocked) { this(outcome, changes, skipped, blocked, 0); }
        Result(Outcome outcome, int changes, int skipped, boolean blocked, int leftOut) {
            this.outcome = outcome; this.changes = changes; this.skipped = skipped; this.blocked = blocked;
            this.leftOut = leftOut;
        }
    }

    /** An earlier import couldn't be confirmed or put back, so only Restore or Discard may run for this store. */
    public static final class RestoreFirst extends IOException {
        RestoreFirst() { super("An earlier override import needs Restore"); }
    }

    /** There's no saved copy for this store. */
    public static final class NothingSaved extends IOException {
        NothingSaved() { super("No saved overrides for this store"); }
    }

    /** The saved copy doesn't pass Validate for this session, build and schema. */
    public static final class SavedCopyDoesntFit extends IOException {
        SavedCopyDoesntFit() { super("The saved overrides don't fit this store"); }
    }

    /** The store's file changed between two reads a settle window apart, so Instagram is still saving. */
    public static final class StoreChanging extends IOException {
        StoreChanging() { super("The native overrides are still changing"); }
    }

    /** Allow importing overrides is off, or HushGram is paused. */
    public static final class NotAllowed extends IOException {
        NotAllowed() { super("Override import is off"); }
    }

    /**
     * Bounds the typed writes one import or its rollback makes. A document holds at most 4096
     * overrides, and Instagram's own file from a phone with many set can hold a few thousand.
     */
    static final int MAX_CHANGES = 4096;
    static final String DIRECTORY = "hushgram-overrides";
    private static final String NULL = "__NULL_VALUE__";
    private static final long POLL_MILLIS = 50;
    /**
     * How long the native store gets to show a typed write in its file. Instagram's editor changes
     * the table first and the file after, so it's also how long two reads must agree before one is
     * trusted.
     */
    static long settleMillis = 3000;
    /** Syncs a directory after a file is moved into it. Replaced in tests, where Os isn't native. */
    static DirectorySync directorySync = OverrideImport::syncDirectory;
    private static final Object LOCK = new Object();

    interface DirectorySync {
        void sync(File directory) throws IOException;
        /** Test fault boundary; the production implementation has no observer. */
        default void reached(String boundary, File file) throws IOException {}
    }

    private OverrideImport() {}

    /**
     * Takes every override in this session away the way an import does, saving the current ones for
     * Restore first. Overrides holding Instagram's null value stay, since they couldn't be put back.
     */
    public static Result reset(Activity activity) throws IOException {
        allowed();
        return run(activity, OverrideExchange.exportReset(OverrideExchange.capture(activity)), false);
    }

    /** Imports a document chosen by the user. Throws before any native call when it doesn't fit. */
    public static Result apply(Activity activity, byte[] document) throws IOException {
        return run(activity, document, false);
    }

    /**
     * Puts back the overrides saved before the last import into this session's store. What a
     * typed writer can't put back is counted in {@link Result#skipped}, not refused.
     */
    public static Result restore(Activity activity) throws IOException {
        return run(activity, null, true);
    }

    /**
     * Forgets this store's restore point and lets imports run again. Instagram's overrides aren't
     * touched. Answers whether there was anything to forget.
     */
    public static boolean discard(Activity activity) throws IOException {
        synchronized (LOCK) {
            allowed();
            OverrideExchange.Snapshot snapshot = OverrideExchange.capture(activity);
            allowed();
            return new Store(activity, snapshot).discard();
        }
    }

    private static final class Change {
        final OverrideExchange.Parameter parameter;
        final String before, after;
        Change(OverrideExchange.Parameter parameter, String before, String after) {
            this.parameter = parameter; this.before = before; this.after = after;
        }
    }

    /** The typed changes from one set of values to another, and how many can't be made. */
    private static final class Plan {
        final List<Change> changes = new ArrayList<>();
        int skipped;
    }

    private static Result run(Activity activity, byte[] document, boolean restoring) throws IOException {
        synchronized (LOCK) {
            allowed();
            OverrideExchange.Snapshot first = OverrideExchange.capture(activity);
            Store store = new Store(activity, first);
            boolean wasArmed = store.isArmed();
            if (restoring) document = store.restorePoint(wasArmed);
            else if (wasArmed) throw new RestoreFirst();
            Map<Long, String> target = new TreeMap<>();
            OverrideExchange.Checked checked;
            try { checked = OverrideExchange.validated(document, first, target); }
            catch (IOException failure) { throw restoring ? new SavedCopyDoesntFit() : failure; }
            OverrideExchange.Snapshot before = settled(activity, first);
            allowed();
            Map<Long, String> current = OverrideExchange.values(bytes(before.raw), before);
            Plan plan = plan(before, current, target);
            if (!restoring) {
                // A null override has no typed writer, and the limit bounds the work and its rollback.
                if (plan.skipped > 0 || plan.changes.size() > MAX_CHANGES) throw invalid();
                // Restore must be able to undo this import with the same typed writes.
                Plan undo = plan(before, expected(current, plan.changes), current);
                if (undo.skipped > 0 || undo.changes.size() > MAX_CHANGES) throw invalid();
            }
            if (plan.changes.isEmpty()) {
                if (restoring) return restored(store, wasArmed, plan, Outcome.UNCHANGED);
                return new Result(Outcome.UNCHANGED, 0, 0, false, checked.leftOut);
            }
            try {
                for (Change change : plan.changes) {
                    if (DeveloperOptions.getOverrideTypeNative(change.parameter.nativeId) != change.parameter.type) throw invalid();
                }
            } catch (IOException mismatch) { throw mismatch; }
            catch (Throwable failure) { throw invalid(); }
            // The commit boundary: the same session manager, store, schema and bytes as planned.
            OverrideExchange.Snapshot now = OverrideExchange.capture(activity);
            unchanged(before, now);
            allowed();
            Object table;
            try { table = DeveloperOptions.getOverrideTableNative(now.manager); }
            catch (Throwable failure) { throw invalid(); }
            if (table == null) throw invalid();
            allowed();
            try {
                if (restoring) store.saveReplaced(OverrideExchange.export(before));
                else store.savePending(OverrideExchange.export(before));
                store.arm(before);
            } catch (IOException failure) {
                // Nothing was written, so the store keeps its restore point and its marker state.
                if (!restoring) store.abandon();
                else if (!wasArmed) store.settle();
                throw failure;
            }

            // Saving the journal can wait on storage. Recheck before the first typed write, and
            // undo only this operation's staging if permission changed while it waited.
            try { allowed(); }
            catch (NotAllowed refused) {
                if (!restoring) store.abandon();
                else if (wasArmed) store.dropReplaced();
                else store.settle();
                throw refused;
            }

            int reached = 0;
            boolean written = true;
            for (Change change : plan.changes) {
                reached++;
                if (!write(table, change.parameter, change.after)) { written = false; break; }
            }
            if (written && holds(now, expected(current, plan.changes))) {
                if (restoring) return restored(store, wasArmed, plan, Outcome.APPLIED);
                boolean complete = store.promote() && store.settle();
                return new Result(Outcome.APPLIED, plan.changes.size(), 0, !complete, checked.leftOut);
            }
            // Put back everything that may have reached the table, the failed write included.
            // Journal that this copy is undoing a failed import before touching the table again.
            if (!restoring && !store.beginRollback()) return new Result(Outcome.UNRECOVERED, 0, 0, true);
            boolean undone = true;
            for (int i = reached - 1; i >= 0; i--) {
                Change change = plan.changes.get(i);
                undone &= write(table, change.parameter, change.before);
            }
            if (undone && holds(now, current)) {
                // The store is as it was, and so is its restore point and whether it was armed.
                boolean complete = !restoring ? store.abandon()
                        : wasArmed ? store.dropReplaced() : store.settle();
                return new Result(Outcome.ROLLED_BACK, 0, 0, (wasArmed && restoring) || !complete);
            }
            // This import's copy is now the only way back.
            if (!restoring) store.promote();
            return new Result(Outcome.UNRECOVERED, 0, 0, true);
        }
    }

    /**
     * A restore that held. When nothing was skipped the store matches its restore point again, so
     * it stops blocking imports; otherwise it keeps whatever it had, and a blocked store waits for
     * Discard.
     */
    private static Result restored(Store store, boolean wasArmed, Plan plan, Outcome held) {
        if (plan.skipped == 0) {
            return new Result(held, plan.changes.size(), 0, !store.restoredFully());
        }
        boolean complete = wasArmed ? store.dropReplaced() : store.settle();
        return new Result(Outcome.PARTIAL, plan.changes.size(), plan.skipped, wasArmed || !complete);
    }

    /** Off or paused, nothing here reads or writes the native store. */
    private static void allowed() throws NotAllowed {
        if (!Utils.settingsReady() || !Settings.ALLOW_OVERRIDE_IMPORT.get()) throw new NotAllowed();
    }

    /**
     * Reads the store again a settle window after the first read and answers that second read
     * only when its file hasn't moved: a change Instagram's editor made just before is in the
     * table at once but in the file only later.
     */
    private static OverrideExchange.Snapshot settled(Activity activity, OverrideExchange.Snapshot first) throws IOException {
        try { Thread.sleep(settleMillis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw invalid(); }
        OverrideExchange.Snapshot again = OverrideExchange.capture(activity);
        unchanged(first, again);
        return again;
    }

    private static void unchanged(OverrideExchange.Snapshot one, OverrideExchange.Snapshot other) throws IOException {
        if (other.manager != one.manager || !other.file.equals(one.file) || !OverrideExchange.sameSchema(other, one)) throw invalid();
        if (!Arrays.equals(other.raw, one.raw)) throw new StoreChanging();
    }

    /**
     * The typed changes from current to target. A null override has no typed writer, so a change
     * to or from one is skipped and counted.
     */
    private static Plan plan(OverrideExchange.Snapshot snapshot, Map<Long, String> current,
                             Map<Long, String> target) throws IOException {
        Plan plan = new Plan();
        TreeSet<Long> keys = new TreeSet<>(current.keySet());
        keys.addAll(target.keySet());
        for (long key : keys) {
            OverrideExchange.Parameter parameter = OverrideExchange.parameter(snapshot, key);
            if (parameter == null) throw invalid();
            String before = current.get(key), after = target.get(key);
            if (before != null && after != null && same(parameter.type, before, after)) continue;
            if (NULL.equals(before) || NULL.equals(after)) { plan.skipped++; continue; }
            plan.changes.add(new Change(parameter, before, after));
        }
        return plan;
    }

    private static Map<Long, String> expected(Map<Long, String> current, List<Change> changes) {
        Map<Long, String> expected = new TreeMap<>(current);
        for (Change change : changes) {
            if (change.after == null) expected.remove(key(change.parameter));
            else expected.put(key(change.parameter), change.after);
        }
        return expected;
    }

    /**
     * One typed write through the patched bridge. The cases follow Instagram's decoder codes, and
     * the patch proves its put sends each code to the same writer: 1 bool, 2 long, 3 string, 4 double.
     */
    private static boolean write(Object table, OverrideExchange.Parameter parameter, String value) {
        try {
            long id = parameter.nativeId;
            if (value == null) return DeveloperOptions.removeOverrideNative(table, id) == 1;
            switch (parameter.type) {
                case 1: return DeveloperOptions.setOverrideBooleanNative(table, id, "true".equals(value) ? 1 : 0) == 1;
                case 2: return DeveloperOptions.setOverrideLongNative(table, id, Long.parseLong(value)) == 1;
                case 3: return DeveloperOptions.setOverrideStringNative(table, id, value) == 1;
                case 4: return DeveloperOptions.setOverrideDoubleNative(table, id, Double.parseDouble(value)) == 1;
                default: return false;
            }
        } catch (Throwable failure) {
            // A native failure can carry a value or a session path. Keep only the verdict.
            return false;
        }
    }

    /** Waits for the store's own file to hold exactly these values, compared by type. */
    private static boolean holds(OverrideExchange.Snapshot snapshot, Map<Long, String> expected) {
        long deadline = System.nanoTime() + settleMillis * 1_000_000L;
        while (true) {
            try {
                byte[] bytes = null;
                if (snapshot.file.exists()) {
                    try (InputStream input = new FileInputStream(snapshot.file)) { bytes = OverrideExchange.read(input); }
                }
                if (same(snapshot, OverrideExchange.values(bytes(bytes), snapshot), expected)) return true;
            } catch (IOException | RuntimeException partial) {
                // The native writer may be partway through the file. Read it again.
            }
            if (System.nanoTime() - deadline >= 0) return false;
            try { Thread.sleep(POLL_MILLIS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
    }

    private static boolean same(OverrideExchange.Snapshot snapshot, Map<Long, String> one, Map<Long, String> other) {
        if (!one.keySet().equals(other.keySet())) return false;
        for (Map.Entry<Long, String> entry : one.entrySet()) {
            OverrideExchange.Parameter parameter = OverrideExchange.parameter(snapshot, entry.getKey());
            if (parameter == null || !same(parameter.type, entry.getValue(), other.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean same(int type, String one, String other) {
        if (one.equals(other)) return true;
        if (NULL.equals(one) || NULL.equals(other)) return false;
        try {
            if (type == 2) return Long.parseLong(one) == Long.parseLong(other);
            if (type == 4) return Double.compare(Double.parseDouble(one), Double.parseDouble(other)) == 0;
        } catch (NumberFormatException failure) { return false; }
        return false;
    }

    private static long key(OverrideExchange.Parameter parameter) { return OverrideExchange.key(parameter.config, parameter.index); }
    private static byte[] bytes(byte[] raw) { return raw == null ? "{}".getBytes(StandardCharsets.UTF_8) : raw; }
    static IOException invalid() { return new IOException("Invalid or unavailable native overrides"); }

    /** Opens the directory and syncs it, so a file just moved into it is still there after a crash. */
    static void syncDirectory(File directory) throws IOException {
        FileDescriptor descriptor;
        try { descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0); }
        catch (ErrnoException failure) { throw invalid(); }
        try { Os.fsync(descriptor); }
        catch (ErrnoException failure) { if (!syncUnsupported(failure.errno)) throw invalid(); }
        finally {
            try { Os.close(descriptor); } catch (ErrnoException ignored) { /* Read-only; the sync already answered. */ }
        }
    }

    /** A filesystem that can't sync a directory answers EINVAL or EROFS. Anything else means the move may not last. */
    static boolean syncUnsupported(int errno) {
        return errno == OsConstants.EINVAL || errno == OsConstants.EROFS;
    }

    /**
     * One store's private recovery files. The durable marker names and hashes the selected copy;
     * file presence alone never overrides that selection. A rollback journal preserves the older
     * restore point while its pending copy repairs an interrupted native rollback.
     */
    private static final class Store {
        final File directory, backup, pending, replaced, armed, completed;
        final List<File> copies;
        final long hostCode;
        String selected = "backup", digest, previous = "-", phase = "import";

        Store(Activity activity, OverrideExchange.Snapshot snapshot) throws IOException {
            directory = new File(activity.getFilesDir(), DIRECTORY);
            String name = sha256(snapshot.file.getPath().getBytes(StandardCharsets.UTF_8));
            backup = new File(directory, name + ".json");
            pending = new File(directory, name + ".pending.json");
            replaced = new File(directory, name + ".replaced.json");
            armed = new File(directory, name + ".armed");
            completed = new File(directory, name + ".settled");
            copies = Arrays.asList(backup, pending, replaced,
                    new File(directory, backup.getName() + ".tmp"),
                    new File(directory, pending.getName() + ".tmp"),
                    new File(directory, replaced.getName() + ".tmp"),
                    new File(directory, armed.getName() + ".tmp"));
            hostCode = OverrideExchange.hostCode(snapshot);
        }

        boolean isArmed() throws IOException {
            if (armed.exists()) return true;
            for (File file : copies) if (!file.equals(backup) && file.exists()) return true;
            if (completed.exists()) {
                // The last journal is moved, never unlinked. If its directory sync fails, it
                // remains a witness across a new Store/process even when no write can succeed.
                try {
                    if (!completed.isFile() || completed.length() > 512) return true;
                    String[] record = new String(read(completed), StandardCharsets.UTF_8).split("\n", -1);
                    if (record.length != 6 || !"1".equals(record[0]) || !Long.toString(hostCode).equals(record[1])
                            || !Arrays.asList("import", "rollback", "complete").contains(record[4])
                            || !("-".equals(record[5]) || record[5].matches("[0-9a-f]{64}"))) return true;
                    if ("backup".equals(record[2])) {
                        if (!backup.isFile() || !record[3].equals(sha256(read(backup)))) return true;
                    } else if (!"none".equals(record[2]) || !"-".equals(record[3]) || backup.exists()) return true;
                    sync(directory);
                } catch (IOException | RuntimeException failure) {
                    RestoreFirst blocked = new RestoreFirst();
                    blocked.initCause(failure);
                    throw blocked;
                }
            }
            return false;
        }

        byte[] restorePoint(boolean wasArmed) throws IOException {
            File source = backup;
            File journal = armed.exists() ? armed : wasArmed && completed.exists() ? completed : null;
            if (journal != null) {
                String[] record = new String(read(journal), StandardCharsets.UTF_8).split("\n", -1);
                if (record.length == 1 && record[0].matches("[0-9]+")) {
                    // Numeric markers never recorded which of two different copies was authoritative.
                    if (pending.isFile() && backup.isFile() && !Arrays.equals(read(pending), read(backup))) {
                        throw new SavedCopyDoesntFit();
                    }
                    source = pending.isFile() && !backup.isFile() ? pending : backup;
                    selected = source.equals(pending) ? "pending" : "backup";
                } else {
                    if ((record.length != 4 && record.length != 6) || !"1".equals(record[0])
                            || !Long.toString(hostCode).equals(record[1])
                            || !Arrays.asList("pending", "backup", "none").contains(record[2])) throw new SavedCopyDoesntFit();
                    selected = record[2];
                    digest = record[3];
                    if (record.length == 6) {
                        phase = record[4];
                        previous = record[5];
                        if (!Arrays.asList("import", "rollback", "complete").contains(phase)
                                || !("-".equals(previous) || previous.matches("[0-9a-f]{64}"))) throw new SavedCopyDoesntFit();
                    }
                    if ("none".equals(selected)) throw new NothingSaved();
                    if (!digest.matches("[0-9a-f]{64}")) throw new SavedCopyDoesntFit();
                    source = "pending".equals(selected) ? pending : backup;
                    // A durable pending selection follows its own digest across an interrupted move.
                    if (source.equals(pending) && !source.exists()) source = backup;
                }
            }
            if (!source.isFile()) throw new NothingSaved();
            byte[] document = read(source);
            String found = sha256(document);
            if (digest != null && !digest.equals(found)) throw new SavedCopyDoesntFit();
            digest = found;
            return document;
        }

        void savePending(byte[] document) throws IOException {
            previous = backup.isFile() ? sha256(read(backup)) : "-";
            replace(pending, document);
            selected = "pending";
            digest = sha256(document);
            phase = "import";
        }

        void saveReplaced(byte[] document) throws IOException { replace(replaced, document); }

        void arm(OverrideExchange.Snapshot snapshot) throws IOException { mark(); }

        private byte[] marker() {
            return ("1\n" + hostCode + "\n" + selected + "\n" + digest + "\n" + phase + "\n" + previous)
                    .getBytes(StandardCharsets.UTF_8);
        }

        private void mark() throws IOException {
            byte[] record = marker();
            if (armed.isFile() && armed.length() == record.length && Arrays.equals(read(armed), record)) sync(directory);
            else replace(armed, record);
        }

        boolean beginRollback() {
            try {
                phase = "rollback";
                mark();
                return true;
            } catch (IOException | RuntimeException failure) { return false; }
        }

        /** Never deletes the older backup on failure; the selected digest still names pending. */
        boolean promote() {
            try {
                if (!"pending".equals(selected)) return false;
                if (!digest.equals(sha256(read(pending.exists() ? pending : backup)))) throw invalid();
                if (!"import".equals(phase)) {
                    phase = "import";
                    mark();
                }
                if (pending.exists()) {
                    directorySync.reached("beforeMove", backup);
                    Files.move(pending.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    directorySync.reached("moved", backup);
                }
                sync(directory);
                selected = "backup";
                mark();
                return true;
            } catch (IOException | RuntimeException failure) { return false; }
        }

        /** Select the older restore point durably before deleting a failed import's pending copy. */
        boolean abandon() {
            try {
                if (!isArmed()) return true;
                if ("-".equals(previous)) {
                    selected = "none";
                    digest = "-";
                } else {
                    if (!backup.isFile() || !previous.equals(sha256(read(backup)))) throw invalid();
                    selected = "backup";
                    digest = previous;
                }
                phase = "complete";
                mark();
                return settle();
            } catch (IOException | RuntimeException failure) { return false; }
        }

        boolean dropReplaced() {
            try { delete(replaced); return true; }
            catch (IOException | RuntimeException failure) { return false; }
        }

        /** Cleanup is checked and synced. Its marker remains until every auxiliary copy is gone. */
        boolean settle() {
            try {
                if ("pending".equals(selected)) return false;
                if (!isArmed()) return true;
                mark();
                for (File file : copies) if (!file.equals(backup)) delete(file);
                finish();
                return true;
            } catch (IOException | RuntimeException failure) { return false; }
        }

        boolean restoredFully() {
            if ("rollback".equals(phase)) return abandon();
            return (!"pending".equals(selected) || promote()) && settle();
        }

        /** Discard commits its no-restore selection first; retry can safely remove remaining files. */
        boolean discard() throws IOException {
            boolean found = armed.exists();
            for (File file : copies) found |= file.exists();
            if (!found && completed.exists()) {
                try { found = isArmed(); }
                catch (RestoreFirst damagedTerminal) {
                    // A completed journal that can't be inspected is eligible for explicit Discard.
                    found = true;
                }
            }
            if (!found) return false;
            selected = "none";
            digest = previous = "-";
            phase = "complete";
            // Replace the old journal even if the optional terminal inspection couldn't read it.
            replace(armed, marker());
            for (File file : copies) delete(file);
            try { finish(); }
            catch (IOException | RuntimeException failure) {
                throw invalid();
            }
            return true;
        }

        /** Keep a small terminal witness so failed final durability never depends on a rescue write. */
        private void finish() throws IOException {
            directorySync.reached("beforeMove", completed);
            Files.move(armed.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            directorySync.reached("moved", completed);
            sync(directory);
        }

        private static byte[] read(File file) throws IOException {
            try (InputStream input = new FileInputStream(file)) { return OverrideExchange.read(input); }
        }

        private static void sync(File directory) throws IOException {
            directorySync.reached("beforeDirectorySync", directory);
            directorySync.sync(directory);
            directorySync.reached("directorySynced", directory);
        }

        private static void delete(File file) throws IOException {
            if (!file.exists()) return;
            directorySync.reached("beforeDelete", file);
            if (!file.delete() && file.exists()) throw invalid();
            directorySync.reached("deleted", file);
            sync(Objects.requireNonNull(file.getParentFile()));
        }

        private static void replace(File target, byte[] bytes) throws IOException {
            File directory = Objects.requireNonNull(target.getParentFile());
            directorySync.reached("beforeDirectory", directory);
            if (!directory.isDirectory() && !directory.mkdir() && !directory.isDirectory()) throw invalid();
            directorySync.reached("directoryCreated", directory);
            // Also finish an earlier first-use creation whose parent sync failed.
            sync(Objects.requireNonNull(directory.getParentFile()));
            File temporary = new File(directory, target.getName() + ".tmp");
            try {
                directorySync.reached("beforeWrite", target);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(bytes);
                    directorySync.reached("written", target);
                    output.getFD().sync();
                    directorySync.reached("fileSynced", target);
                }
                directorySync.reached("beforeMove", target);
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                directorySync.reached("moved", target);
            } catch (IOException | RuntimeException failure) {
                try { delete(temporary); } catch (IOException | RuntimeException stillFailing) { /* The original failure is reported. */ }
                throw invalid();
            }
            sync(directory);
        }

        private static String sha256(byte[] bytes) throws IOException {
            try {
                StringBuilder name = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) name.append(String.format(Locale.ROOT, "%02x", value & 255));
                return name.toString();
            } catch (Exception failure) { throw invalid(); }
        }
    }
}
