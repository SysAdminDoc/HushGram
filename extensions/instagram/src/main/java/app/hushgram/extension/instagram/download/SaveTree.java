/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/**
 * A folder picked with Android's folder picker, which every save goes to in place of Movies and
 * Pictures (#333).
 *
 * <p>The picker hands back a document tree, and the grant to write in it is kept across restarts
 * with {@code takePersistableUriPermission}. Any folder the picker shows can be the one, a hidden
 * {@code .folder} too, which Save folder's name can't be since a leading dot is taken off there.
 * It's the same on Android 9, so the storage permission Movies and Pictures need there isn't.
 *
 * <p>A grant can go: Instagram's data cleared, the folder's card taken out, the grant revoked. Then
 * saves go where they went before a folder was picked, the settings row says why, and the first
 * save that finds it gone says so in a toast. Nothing is written half way into the folder: a save
 * writes under a hidden {@code .pending-} name SaveLeftovers keeps on its list, and the name it
 * keeps comes with the last byte, the way Android 9's gallery writes go ({@link MediaStoreWriter}).
 */
public final class SaveTree {

    private SaveTree() {}

    /** What the sweep did with a stopped save's file: removed it, found nothing to do, or failed. */
    static final int REMOVED = 1;
    static final int GONE = 0;
    static final int RETRY = -1;

    /** Where the phone's own storage keeps its trees. Its document ids are the folder's path. */
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";

    /** One account folder per name, however many saves of that account start together. */
    private static final Object FOLDERS = new Object();

    /** The lost folder's toast is once per process. */
    private static volatile boolean toldLost;

    /** So is the toast for a folder that wouldn't take a save. */
    private static volatile boolean toldRefused;

    /** Whether a folder was picked, reachable or not. Never throws. */
    public static boolean picked() {
        try {
            return Utils.settingsReady() && !Settings.SAVE_TREE.get().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The picked tree as the setting holds it, or null for none or a value that isn't a tree. */
    public static Uri saved() {
        try {
            if (!Utils.settingsReady()) return null;
            String text = Settings.SAVE_TREE.get();
            if (text.isEmpty()) return null;
            Uri tree = Uri.parse(text);
            return "content".equals(tree.getScheme()) && DocumentsContract.isTreeUri(tree) ? tree : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The picked tree when Instagram still holds the grant to write in it, or null. Never throws. */
    static Uri usable(Context context) {
        Uri tree = saved();
        return tree != null && granted(context, tree) ? tree : null;
    }

    /**
     * Whether a folder is picked, Instagram can still write in it, and it's still there to take
     * files: one deleted in Files keeps its grant, but saves go to Movies and Pictures. Never throws.
     */
    public static boolean reachable(Context context) {
        Uri tree = usable(context);
        return tree != null && takesFiles(context, tree, false);
    }

    private static boolean granted(Context context, Uri tree) {
        try {
            for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                if (grant.isWritePermission() && tree.equals(grant.getUri())) return true;
            }
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not read the grant for the folder you picked", t);
        }
        return false;
    }

    /**
     * Keeps [tree], the picker's answer, as the folder saves go to: the grant held across restarts,
     * and the grant of the folder it replaces let go. Answers false, keeping what was there, for an
     * answer that isn't a tree, a grant that can't be kept, or a folder that takes no new files.
     */
    public static boolean keep(Context context, Uri tree) {
        if (context == null || tree == null || !"content".equals(tree.getScheme())
                || !DocumentsContract.isTreeUri(tree)) {
            return false;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not keep the grant for the folder that was picked", t);
            return false;
        }
        if (!takesFiles(context, tree, true)) {
            MediaSave.info(() -> "the folder that was picked doesn't take new files");
            // Picked again as the folder already kept: its grant is still the one saves use.
            if (!tree.equals(saved())) release(context, tree);
            return false;
        }
        Uri old = saved();
        Settings.SAVE_TREE.save(tree.toString());
        if (old != null && !old.equals(tree)) release(context, old);
        toldLost = false;
        toldRefused = false;
        return true;
    }

    /** Saves go to Movies and Pictures again, and the picked folder's grant is let go. */
    public static void forget(Context context) {
        Uri old = saved();
        Settings.SAVE_TREE.save("");
        if (old != null && context != null) release(context, old);
        toldLost = false;
        toldRefused = false;
    }

    private static void release(Context context, Uri tree) {
        try {
            context.getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            // Already gone, or never held: nothing is left to let go.
        }
    }

    /**
     * Whether the folder [tree] names says it takes new files. A folder that's gone throws, which is
     * only [logged] at pick time: the settings row asks on every refresh.
     */
    private static boolean takesFiles(Context context, Uri tree, boolean logged) {
        try (Cursor row = query(context, root(tree), new String[] { Document.COLUMN_FLAGS })) {
            return row != null && row.moveToFirst() && (row.getInt(0) & Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
        } catch (Throwable t) {
            if (logged) MediaSave.failure(() -> "could not ask the folder that was picked whether it takes files", t);
            return false;
        }
    }

    /** The picked folder itself, as a document. */
    public static Uri root(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /**
     * What the folder is called in a message: its path on the phone's storage, like Download/.Insta,
     * and otherwise the name its app gives it. Never throws.
     */
    public static String label(Context context, Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable t) {
            return String.valueOf(tree);
        }
        int colon = id.indexOf(':');
        if (EXTERNAL_STORAGE.equals(tree.getAuthority()) && colon >= 0 && colon < id.length() - 1) {
            return id.substring(colon + 1);
        }
        try (Cursor row = query(context, root(tree), new String[] { Document.COLUMN_DISPLAY_NAME })) {
            if (row != null && row.moveToFirst() && !row.isNull(0) && !row.getString(0).isEmpty()) return row.getString(0);
        } catch (Throwable t) {
            // Named by its id below.
        }
        return id;
    }

    /** What the settings row and the first save say when the folder's grant went. */
    public static String lostMessage(Context context, Uri tree) {
        String folder = tree == null ? Settings.SAVE_TREE.get() : label(context, tree);
        return L10n.f("HushGram can't reach %1$s anymore, so saves go to Movies and Pictures. Pick the folder again.",
                L10n.isolate(folder));
    }

    /** A save found the picked folder out of reach: said once per process, and in the report. */
    static void tellIfLost(Context context) {
        if (toldLost || !picked()) return;
        toldLost = true;
        MediaSave.info(() -> "the folder that was picked can't be reached, so the save goes to Movies or Pictures");
        Feedback.show(context, lostMessage(context, saved()), true);
    }

    /** The picked folder, reachable, wouldn't take a save, which went to Movies or Pictures: said once per process. */
    static void tellRefused(Context context, Uri tree) {
        if (toldRefused) return;
        toldRefused = true;
        Feedback.show(context, L10n.f("%1$s didn't take this save, so it went to Movies and Pictures.",
                L10n.isolate(label(context, tree))), true);
    }

    /**
     * The folder in [tree] a save goes to: the tree itself, or the [account] folder in it, found or
     * made, for Folder per account. Throws when the folder can't be read or the account's made.
     */
    static Uri folder(Context context, Uri tree, String account) throws IOException {
        Uri root = root(tree);
        if (account == null || account.isEmpty()) return root;
        synchronized (FOLDERS) {
            Uri found = child(context, tree, root, account);
            if (found != null) return found;
            Uri made = DocumentsContract.createDocument(context.getContentResolver(), root, Document.MIME_TYPE_DIR, account);
            if (made == null) throw new IOException("the account's folder could not be made in the folder that was picked");
            return made;
        }
    }

    /** The folder named [name] in [parent], or null. */
    private static Uri child(Context context, Uri tree, Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor rows = query(context, children,
                new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE })) {
            if (rows == null) throw new IOException("the folder that was picked can't be read");
            while (rows.moveToNext()) {
                if (name.equals(rows.getString(1)) && Document.MIME_TYPE_DIR.equals(rows.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0));
                }
            }
        }
        return null;
    }

    /** Every name in the folder [parent] of [tree], for a save that must not take one. */
    static Set<String> names(Context context, Uri tree, Uri parent) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        Set<String> names = new HashSet<>();
        try (Cursor rows = query(context, children, new String[] { Document.COLUMN_DISPLAY_NAME })) {
            if (rows == null) throw new IOException("the folder that was picked can't be read");
            while (rows.moveToNext()) {
                if (!rows.isNull(0)) names.add(rows.getString(0));
            }
        }
        return names;
    }

    /** A new hidden file in [parent] for a save's bytes, a name nobody else's save can have. */
    static Uri createWork(Context context, Uri parent) throws IOException {
        Uri work = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/octet-stream",
                MediaStoreWriter.LEGACY_PENDING_PREFIX + UUID.randomUUID() + ".tmp");
        if (work == null) throw new IOException("the folder that was picked refused a new file");
        return work;
    }

    /** [work] takes the name [name]. Answers the finished file, or throws. */
    static Uri rename(Context context, Uri work, String name) throws IOException {
        Uri done = DocumentsContract.renameDocument(context.getContentResolver(), work, name);
        if (done == null) throw new IOException("the finished file could not take its name");
        return done;
    }

    /** Removes [document]. Answers whether it's gone, removed now or never there. Never throws. */
    static boolean delete(Context context, Uri document) {
        try {
            if (DocumentsContract.deleteDocument(context.getContentResolver(), document)) return true;
        } catch (Throwable t) {
            // It may already be gone: the look below answers.
        }
        try (Cursor row = query(context, document, new String[] { Document.COLUMN_DOCUMENT_ID })) {
            return row == null || !row.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether [document], a save in a picked folder, is still there. A file that's gone isn't in
     * the tree any more, and the folder's app refuses to say so: the phone's storage throws an
     * IllegalArgumentException, others a SecurityException, as they do for a grant that went. Both
     * answer no, so the save goes ahead. Throws when the folder can't be asked otherwise.
     */
    static boolean exists(Context context, Uri document) {
        try (Cursor row = query(context, document, new String[] { Document.COLUMN_DOCUMENT_ID })) {
            return row != null && row.moveToFirst();
        } catch (SecurityException | IllegalArgumentException gone) {
            return false;
        }
    }

    /**
     * What SaveLeftovers does with [work], a hidden file a stopped save left in a picked folder:
     * {@link #REMOVED}, {@link #GONE} for nothing to remove, or {@link #RETRY}. Only a file still
     * under its hidden name goes, since a rename that beat the cross-off made it a finished save.
     */
    static int removeStopped(Context context, Uri work) {
        String name;
        try (Cursor row = query(context, work, new String[] { Document.COLUMN_DISPLAY_NAME })) {
            if (row == null || !row.moveToFirst() || row.isNull(0)) return GONE;
            name = row.getString(0);
        } catch (SecurityException | IllegalArgumentException gone) {
            // The file was renamed or removed, which the folder's app answers by refusing (see
            // exists), or the grant went, and with it every way to reach the file.
            return GONE;
        } catch (Throwable t) {
            return RETRY;
        }
        if (!name.startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX)) return GONE;
        return delete(context, work) ? REMOVED : RETRY;
    }

    /** A provider query in the form every DocumentsProvider answers. */
    private static Cursor query(Context context, Uri uri, String[] projection) {
        return context.getContentResolver().query(uri, projection, (Bundle) null, null);
    }
}
