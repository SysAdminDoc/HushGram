/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The comment GIFs Save comment photo put in the gallery, each kept with the send model of the GIF it
 * came from (#402), so picking that file in the comment box's gallery can send the GIF itself. A file
 * is known by its size and SHA-256: a copy is still known, a changed one isn't. Kept in HushGram's own
 * files, the newest {@link #KEEP} only.
 */
final class CommentGifStore {
    private CommentGifStore() {}

    static final String DIRECTORY = "hushgram-comment-gifs";
    static final int KEEP = 200;
    /** Bigger than any GIF a comment carries. A bigger file is never read through. */
    static final long LARGEST = 64L * 1024 * 1024;
    private static final String SUFFIX = ".parcel";

    /**
     * Keeps [parcel] for the file at [item], the gallery entry a save published, then drops the
     * oldest past {@link #KEEP}. Runs on the save's thread. Throws on a file it can't read or write.
     */
    static void remember(Context context, String item, byte[] parcel) throws IOException {
        if (context == null || item == null || parcel == null) return;
        String key;
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(item))) {
            if (in == null) throw new IOException("no stream for the saved GIF");
            key = key(in);
        }
        if (key == null) return;
        File folder = folder(context);
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("no folder for saved GIFs");
        File work = new File(folder, key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(work)) {
            out.write(parcel);
            out.getFD().sync();
        }
        File kept = new File(folder, key + SUFFIX);
        if (!work.renameTo(kept)) {
            work.delete();
            throw new IOException("the saved GIF's record wasn't kept");
        }
        trim(folder);
    }

    /** What was kept for the file at [path], or null when it's no GIF Save kept. */
    static byte[] find(Context context, String path) throws IOException {
        if (context == null || path == null) return null;
        File file = new File(path);
        long length = file.length();
        if (length <= 0 || length > LARGEST) return null;
        File folder = folder(context);
        String[] names = folder.list();
        if (names == null) return null;
        // Hashing every picked file costs: only one the same size as a kept GIF is read.
        String sized = length + "-";
        boolean any = false;
        for (String name : names) {
            if (name.startsWith(sized) && name.endsWith(SUFFIX)) {
                any = true;
                break;
            }
        }
        if (!any) return null;
        String key;
        try (InputStream in = new FileInputStream(file)) {
            key = key(in);
        }
        if (key == null) return null;
        File kept = new File(folder, key + SUFFIX);
        if (!kept.isFile()) return null;
        byte[] parcel = new byte[(int) kept.length()];
        try (InputStream in = new FileInputStream(kept)) {
            int at = 0;
            while (at < parcel.length) {
                int read = in.read(parcel, at, parcel.length - at);
                if (read < 0) return null;
                at += read;
            }
        }
        return parcel;
    }

    /** [in]'s length and SHA-256, as a file name, or null past {@link #LARGEST}. */
    static String key(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IOException(missing);
        }
        byte[] buffer = new byte[64 * 1024];
        long length = 0;
        for (int read; (read = in.read(buffer)) > 0; ) {
            length += read;
            if (length > LARGEST) return null;
            digest.update(buffer, 0, read);
        }
        if (length == 0) return null;
        StringBuilder name = new StringBuilder().append(length).append('-');
        for (byte b : digest.digest()) {
            name.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return name.toString();
    }

    /** [info] as bytes, its class name first, or null for anything that isn't a Parcelable. */
    static byte[] marshal(Object info) {
        if (!(info instanceof Parcelable)) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable((Parcelable) info, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    /**
     * [bytes] read back through [loader], or null unless the read uses every byte and writing the
     * result out again gives the same bytes: an Instagram update that changed the model's layout
     * reads nothing, and that GIF goes up as a still the way any other picture does.
     */
    @SuppressWarnings("deprecation")
    static Object unmarshal(byte[] bytes, ClassLoader loader) {
        if (bytes == null || bytes.length == 0 || loader == null) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            Parcelable info = parcel.readParcelable(loader);
            if (info == null || parcel.dataAvail() != 0) return null;
            byte[] again = marshal(info);
            return Arrays.equals(again, bytes) ? info : null;
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    static File folder(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }

    /** Drops the oldest records past {@link #KEEP}. */
    private static void trim(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        int records = 0;
        for (File file : files) {
            if (file.getName().endsWith(SUFFIX)) records++;
        }
        if (records <= KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (records <= KEEP) break;
            if (file.getName().endsWith(SUFFIX) && file.delete()) records--;
        }
    }
}
