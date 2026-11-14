/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentGifStoreTest {
    private Context context;
    private File gallery;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        gallery = new File(context.getCacheDir(), "gallery");
        assertTrue(gallery.mkdirs() || gallery.isDirectory());
    }

    @After public void tearDown() {
        delete(CommentGifStore.folder(context));
        delete(gallery);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    /** A send model the way Instagram's is: written field by field, read back by its CREATOR. */
    public static final class Model implements Parcelable {
        final String id;
        final int width;

        public Model(String id, int width) {
            this.id = id;
            this.width = width;
        }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeString(id);
            out.writeInt(width);
        }

        public static final Creator<Model> CREATOR = new Creator<Model>() {
            @Override public Model createFromParcel(Parcel in) { return new Model(in.readString(), in.readInt()); }
            @Override public Model[] newArray(int size) { return new Model[size]; }
        };
    }

    /** A model an update reads differently: what it writes back isn't what it was read from. */
    public static final class Drifting implements Parcelable {
        final String id;
        final int width;

        public Drifting(String id, int width) {
            this.id = id;
            this.width = width;
        }

        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeString(id);
            out.writeInt(width);
        }

        public static final Creator<Drifting> CREATOR = new Creator<Drifting>() {
            @Override public Drifting createFromParcel(Parcel in) { return new Drifting(in.readString(), in.readInt() + 1); }
            @Override public Drifting[] newArray(int size) { return new Drifting[size]; }
        };
    }

    private File file(String name, byte[] bytes) throws IOException {
        File file = new File(gallery, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The file:// item a pre-Q save publishes for [file]. Built rather than Uri.fromFile: on a
     * Windows test host the path has no leading slash, and SDK 28's fromFile then parses back with
     * the whole path as its authority. A phone's /storage path round-trips either way.
     */
    static String item(File file) {
        return new Uri.Builder().scheme("file").authority("").path(file.getAbsolutePath()).build().toString();
    }

    /** A file's key is its length and SHA-256, and an empty file has none. */
    @Test public void aFilesKeyIsItsLengthAndSha256() throws IOException {
        assertEquals("3-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CommentGifStore.key(new ByteArrayInputStream(bytes("abc"))));
        assertNull(CommentGifStore.key(new ByteArrayInputStream(new byte[0])));
    }

    /** A model goes to bytes and back the same; anything that isn't a Parcelable has no bytes. */
    @Test public void aModelRoundTripsThroughItsBytes() {
        byte[] kept = CommentGifStore.marshal(new Model("17900001", 200));
        assertNotNull(kept);
        Object read = CommentGifStore.unmarshal(kept, getClass().getClassLoader());
        assertTrue(read instanceof Model);
        assertEquals("17900001", ((Model) read).id);
        assertEquals(200, ((Model) read).width);
        assertNull(CommentGifStore.marshal("not a model"));
        assertNull(CommentGifStore.marshal(null));
        assertNull(CommentGifStore.unmarshal(null, getClass().getClassLoader()));
        assertNull(CommentGifStore.unmarshal(kept, null));
    }

    /**
     * Bytes an update can't read the same way read as nothing: bytes left over, a model that writes
     * itself back differently, or a class that's gone.
     */
    @Test public void bytesAnUpdateReadsDifferentlyReadAsNothing() {
        ClassLoader loader = getClass().getClassLoader();
        byte[] kept = CommentGifStore.marshal(new Model("17900001", 200));
        byte[] longer = Arrays.copyOf(kept, kept.length + 4);
        assertNull("bytes left over", CommentGifStore.unmarshal(longer, loader));
        byte[] drifted = CommentGifStore.marshal(new Drifting("17900001", 200));
        assertNull("written back differently", CommentGifStore.unmarshal(drifted, loader));
        Parcel gone = Parcel.obtain();
        byte[] missing;
        try {
            gone.writeString("com.instagram.gone.GifModel");
            gone.writeString("17900001");
            missing = gone.marshall();
        } finally {
            gone.recycle();
        }
        assertNull("class gone", CommentGifStore.unmarshal(missing, loader));
    }

    /**
     * A saved GIF's record is found again from the picked file by size and SHA-256, a copy included,
     * through the gallery entry the save published; a changed or unknown file finds nothing.
     */
    @Test public void aSavedGifIsFoundAgainByItsContentAndNothingElseIs() throws IOException {
        byte[] model = CommentGifStore.marshal(new Model("17900001", 200));
        File saved = file("saved.gif", bytes("GIF89a first"));
        CommentGifStore.remember(context, item(saved), model);
        assertArrayEquals(model, CommentGifStore.find(context, saved.getPath()));
        File copy = file("copy.gif", bytes("GIF89a first"));
        assertArrayEquals("a copy is the same GIF", model, CommentGifStore.find(context, copy.getPath()));
        File changed = file("changed.gif", bytes("GIF89a fir5t"));
        assertNull("same size, other bytes", CommentGifStore.find(context, changed.getPath()));
        File other = file("other.gif", bytes("GIF89a another"));
        assertNull(CommentGifStore.find(context, other.getPath()));
        assertNull(CommentGifStore.find(context, new File(gallery, "missing.gif").getPath()));
        assertNull(CommentGifStore.find(context, file("empty.gif", new byte[0]).getPath()));
        assertNull(CommentGifStore.find(context, null));
    }

    /** A content address, the kind a save on Android 10 and up publishes, is read through its provider. */
    @Test public void aContentAddressIsReadThroughItsProvider() throws IOException {
        byte[] model = CommentGifStore.marshal(new Model("17900002", 150));
        Uri item = Uri.parse("content://media/external/images/media/42");
        shadowOf(context.getContentResolver()).registerInputStream(item, new ByteArrayInputStream(bytes("GIF89a second")));
        CommentGifStore.remember(context, item.toString(), model);
        assertArrayEquals(model, CommentGifStore.find(context, file("picked.gif", bytes("GIF89a second")).getPath()));
    }

    /** A pick no kept GIF has the size of is never hashed: a folder with nothing that size answers null. */
    @Test public void aPickOfAnotherSizeIsNeverRead() throws IOException {
        File folder = CommentGifStore.folder(context);
        assertTrue(folder.mkdirs() || folder.isDirectory());
        File picked = file("picked.gif", bytes("GIF89a third"));
        String key = CommentGifStore.key(new ByteArrayInputStream(bytes("GIF89a third")));
        assertNull("no folder entry", CommentGifStore.find(context, picked.getPath()));
        // A record under the right key but a different size prefix is never reached.
        File wrongSize = new File(folder, "1" + key + ".parcel");
        try (FileOutputStream out = new FileOutputStream(wrongSize)) {
            out.write(1);
        }
        assertNull(CommentGifStore.find(context, picked.getPath()));
    }

    /** Past {@link CommentGifStore#KEEP} records, the oldest go first. */
    @Test public void onlyTheNewestRecordsAreKept() throws IOException {
        File folder = CommentGifStore.folder(context);
        assertTrue(folder.mkdirs() || folder.isDirectory());
        long now = System.currentTimeMillis();
        for (int i = 0; i < CommentGifStore.KEEP; i++) {
            File record = new File(folder, "9-" + i + ".parcel");
            try (FileOutputStream out = new FileOutputStream(record)) {
                out.write(i);
            }
            assertTrue(record.setLastModified(now - (CommentGifStore.KEEP - i) * 60_000L));
        }
        File oldest = new File(folder, "9-0.parcel");
        File saved = file("newest.gif", bytes("GIF89a newest"));
        byte[] model = CommentGifStore.marshal(new Model("17900003", 120));
        CommentGifStore.remember(context, item(saved), model);
        String[] records = folder.list((dir, name) -> name.endsWith(".parcel"));
        assertNotNull(records);
        assertEquals(CommentGifStore.KEEP, records.length);
        assertFalse("the oldest goes", oldest.exists());
        assertTrue(new File(folder, "9-1.parcel").exists());
        assertArrayEquals(model, CommentGifStore.find(context, saved.getPath()));
    }

    /** A published entry that can't be opened fails the keep, and nothing is written. */
    @Test public void anEntryThatCantBeOpenedKeepsNothing() {
        byte[] model = CommentGifStore.marshal(new Model("17900004", 120));
        try {
            CommentGifStore.remember(context, item(new File(gallery, "missing.gif")), model);
            fail("a missing file can't be kept");
        } catch (IOException expected) {
            // The save itself already went through; only the keep fails.
        }
        String[] records = CommentGifStore.folder(context).list();
        assertTrue(records == null || records.length == 0);
    }
}
