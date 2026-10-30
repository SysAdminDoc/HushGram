/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * A folder picked with Android's folder picker (#333): every save goes there, a hidden folder too,
 * under its own name once its last byte is in, and back to Movies and Pictures when the folder's
 * grant goes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37})
public class SaveTreeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private Context context;
    private Tree tree;
    private Uri treeUri;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        LogBufferManager.clearLogBuffer();
        ShadowToast.reset();
        SaveLeftovers.forgetSweepForTests();
        // Every save sweeps once first. Done here, so a test's own leftovers meet only its own sweep.
        SaveLeftovers.sweepOnce(context);
        tree = tree();
        treeUri = DocumentsContract.buildTreeDocumentUri(Tree.AUTHORITY, Tree.ROOT);
    }

    @After
    public void tearDown() {
        SaveTree.forget(context);
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.SAVE_FOLDER_PER_ACCOUNT.resetToDefault();
        Settings.FILENAME_TEMPLATE.resetToDefault();
        MediaSave.policyForTests = null;
        ShadowMediaExtractor.reset();
        LogBufferManager.clearLogBuffer();
    }

    // ---- Choosing ---------------------------------------------------------------------------

    @Test
    public void nothingIsPickedAtFirstAndSavesGoWhereTheyAlwaysDid() throws IOException {
        assertFalse(SaveTree.picked());
        assertNull(SaveTree.saved());
        assertFalse(SaveTree.reachable(context));
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery.videoUri(1));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        video.open("video/mp4").close();
        assertEquals("Movies/Instagram", gallery.rows.get(1L).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        video.abandon();
        assertTrue("the picked folder got a file with nothing picked", tree.files().isEmpty());
    }

    @Test
    public void aPickedFolderIsKeptWithItsGrantAndNamedByItsPath() {
        assertTrue(SaveTree.keep(context, treeUri));
        assertTrue(SaveTree.picked());
        assertEquals(treeUri, SaveTree.saved());
        assertTrue(SaveTree.reachable(context));
        assertTrue("the grant isn't kept across restarts", persisted(treeUri));
        assertEquals("Download/.Insta", SaveTree.label(context, treeUri));

        SaveTree.forget(context);
        assertFalse(SaveTree.picked());
        assertFalse("forgetting the folder kept its grant", persisted(treeUri));
    }

    @Test
    public void aFolderThatTakesNoFilesOrAnAnswerThatIsntAFolderIsRefused() {
        assertFalse(SaveTree.keep(context, null));
        assertFalse(SaveTree.keep(context, Uri.parse("content://media/external/video/media/1")));
        assertFalse(SaveTree.keep(context, DocumentsContract.buildDocumentUri(Tree.AUTHORITY, Tree.ROOT)));
        tree.readOnly = true;
        assertFalse(SaveTree.keep(context, treeUri));
        assertFalse("a refused folder was kept", SaveTree.picked());
        assertFalse("a refused folder kept its grant", persisted(treeUri));
    }

    @Test
    public void pickingTheKeptFolderAgainWhenItNoLongerTakesFilesKeepsItsGrant() {
        assertTrue(SaveTree.keep(context, treeUri));
        tree.readOnly = true;
        assertFalse(SaveTree.keep(context, treeUri));
        assertEquals("the kept folder was dropped", treeUri, SaveTree.saved());
        assertTrue("the kept folder lost its grant", persisted(treeUri));
    }

    @Test
    public void pickingAnotherFolderLetsTheFirstOnesGrantGo() {
        assertTrue(SaveTree.keep(context, treeUri));
        Uri other = DocumentsContract.buildTreeDocumentUri(Tree.AUTHORITY, Tree.OTHER);
        assertTrue(SaveTree.keep(context, other));
        assertEquals(other, SaveTree.saved());
        assertFalse("the first folder's grant is still held", persisted(treeUri));
        assertTrue(persisted(other));
    }

    // ---- Saving -----------------------------------------------------------------------------

    @Test
    public void aPhotoAndAVideoLandInThePickedFolderUnderTheirNames() throws IOException {
        SaveProgressTest.Gallery gallery = gallery();
        assertTrue(SaveTree.keep(context, treeUri));
        byte[] picture = bytes(4_000, 'p');
        byte[] clip = bytes(6_000, 'v');

        MediaStoreWriter photo = new MediaStoreWriter(context, false);
        write(photo.open("image/jpeg"), picture);
        assertEquals("Download/.Insta", photo.savedLocation());
        assertEquals("the save isn't under its hidden name while it's written", 1, tree.files().size());
        assertTrue(tree.files().get(0).name.startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX));
        photo.commit();
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), clip);
        video.commit();

        Tree.Doc savedPhoto = tree.doc(photo.publishedItem());
        Tree.Doc savedVideo = tree.doc(video.publishedItem());
        assertEquals(Tree.ROOT, savedPhoto.parent);
        assertTrue(savedPhoto.name, savedPhoto.name.endsWith(".jpg"));
        assertTrue(savedVideo.name, savedVideo.name.endsWith(".mp4"));
        assertArrayEquals(picture, savedPhoto.bytes());
        assertArrayEquals(clip, savedVideo.bytes());
        assertEquals("a hidden file was left in the folder", 2, tree.files().size());
        assertTrue("the gallery got a row", gallery.rows.isEmpty());
        assertTrue("a finished save is still on the list of leftovers", ledger().isEmpty());
    }

    @Test
    public void folderPerAccountMakesThePostersFolderOnceInThePickedOne() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        Settings.SAVE_FOLDER_PER_ACCOUNT.save(true);
        PostDetails poster = PostDetails.of("1_2", "../some.one/", null);

        for (int i = 0; i < 2; i++) {
            MediaStoreWriter video = new MediaStoreWriter(context, true, poster);
            write(video.open("video/mp4"), bytes(100, 'v'));
            assertEquals("Download/.Insta/some.one", video.savedLocation());
            video.commit();
            assertEquals(Tree.ROOT + "/some.one", tree.doc(video.publishedItem()).parent);
        }
        assertEquals("the account's folder was made twice", 1, tree.folders(Tree.ROOT).size());

        MediaStoreWriter unknown = new MediaStoreWriter(context, true, PostDetails.NONE);
        write(unknown.open("video/mp4"), bytes(100, 'v'));
        unknown.commit();
        assertEquals("a save that doesn't know its poster left the picked folder", Tree.ROOT,
                tree.doc(unknown.publishedItem()).parent);
    }

    /**
     * One video saved twice at once. The template names it by its id alone, so both saves get one
     * name whatever second each opens in: "clip" would put the time on the end, and two opens
     * either side of a second's turn got different names and nothing to number.
     */
    @Test
    public void twoSavesOfOneNameFinishingTogetherBothKeepTheirFile() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        Settings.FILENAME_TEMPLATE.save("clip_" + FileNameTemplate.VIDEO_ID);
        PostDetails video = PostDetails.of("3141");
        MediaStoreWriter first = new MediaStoreWriter(context, true, video);
        MediaStoreWriter second = new MediaStoreWriter(context, true, video);
        write(first.open("video/mp4"), bytes(100, '1'));
        write(second.open("video/mp4"), bytes(100, '2'));
        first.commit();
        second.commit();

        String name = tree.doc(first.publishedItem()).name;
        int dot = name.lastIndexOf('.');
        assertEquals(name.substring(0, dot) + " (1)" + name.substring(dot), tree.doc(second.publishedItem()).name);
        assertArrayEquals(bytes(100, '1'), tree.doc(first.publishedItem()).bytes());
        assertArrayEquals(bytes(100, '2'), tree.doc(second.publishedItem()).bytes());
    }

    /** Some folder apps keep a file's id through a rename: the save is the same file, named. */
    @Test
    public void aFolderAppThatKeepsIdsThroughARenameStillGetsTheName() throws IOException {
        tree.stableIds = true;
        assertTrue(SaveTree.keep(context, treeUri));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), bytes(100, 'v'));
        video.commit();
        Tree.Doc saved = tree.doc(video.publishedItem());
        assertFalse(saved.name, saved.name.startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX));
        assertTrue(ledger().isEmpty());
    }

    @Test
    public void aCancelledSaveLeavesNothingInTheFolder() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), bytes(100, 'v'));
        assertEquals(1, ledger().size());
        video.abandon();
        assertTrue("a cancelled save left a file", tree.files().isEmpty());
        assertTrue("a cancelled save stayed on the list of leftovers", ledger().isEmpty());
        assertNull(video.publishedItem());
    }

    @Test
    public void aFinishFailingLeavesTheHiddenFileForTheWritersAbandon() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), bytes(100, 'v'));
        tree.refuseRename = true;
        try {
            video.commit();
            fail("a rename the folder refused finished the save");
        } catch (IOException | RuntimeException expected) {
            // The downloader abandons a save whose commit threw.
        }
        assertNull(video.publishedItem());
        video.abandon();
        assertTrue("the failed save left a file", tree.files().isEmpty());
        assertTrue(ledger().isEmpty());
    }

    @Test
    public void aFolderThatRefusesTheFileSendsTheSaveToMoviesAndPictures() throws IOException {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery.videoUri(1));
        writable(gallery.videoUri(2));
        assertTrue(SaveTree.keep(context, treeUri));
        tree.refuseCreate = true;
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        video.open("video/mp4").close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Movies/Instagram", video.savedLocation());
        assertEquals("Movies/Instagram", gallery.rows.get(1L).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals(L10n.f("%1$s didn't take this save, so it went to Movies and Pictures.",
                L10n.isolate("Download/.Insta")), ShadowToast.getTextOfLatestToast());
        assertEquals(1, ShadowToast.shownToastCount());

        MediaStoreWriter again = new MediaStoreWriter(context, true);
        again.open("video/mp4").close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the refusing folder was told twice", 1, ShadowToast.shownToastCount());
        video.abandon();
        again.abandon();
    }

    @Test
    public void aLostGrantSendsSavesToMoviesAndPicturesAndSaysSoOnce() throws IOException {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery.videoUri(1));
        writable(gallery.videoUri(2));
        assertTrue(SaveTree.keep(context, treeUri));
        context.getContentResolver().releasePersistableUriPermission(treeUri, GRANT);
        assertTrue("the folder stays picked, to say why saves went back", SaveTree.picked());
        assertFalse(SaveTree.reachable(context));

        MediaStoreWriter first = new MediaStoreWriter(context, true);
        first.open("video/mp4").close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Movies/Instagram", first.savedLocation());
        assertEquals("Movies/Instagram", gallery.rows.get(1L).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals(L10n.f("HushGram can't reach %1$s anymore, so saves go to Movies and Pictures. Pick the folder again.",
                L10n.isolate("Download/.Insta")), ShadowToast.getTextOfLatestToast());
        assertEquals(1, ShadowToast.shownToastCount());

        MediaStoreWriter second = new MediaStoreWriter(context, true);
        second.open("video/mp4").close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the lost folder was told twice", 1, ShadowToast.shownToastCount());
        first.abandon();
        second.abandon();
        assertTrue(tree.files().isEmpty());
    }

    /** Android 9: Movies and Pictures need the storage permission, a picked folder doesn't. */
    @Test @Config(sdk = 28)
    public void onAndroid9APickedFolderNeedsNoStoragePermission() throws IOException {
        assertEquals("the storage permission is held, so this proves nothing",
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        try {
            new MediaStoreWriter(context, true).open("video/mp4");
            fail("Android 9 saved into Movies without the storage permission");
        } catch (IOException expected) {
            // Positive control: without a folder the save can't go anywhere.
        }

        assertTrue(SaveTree.keep(context, treeUri));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), bytes(100, 'v'));
        video.commit();
        Tree.Doc saved = tree.doc(video.publishedItem());
        assertTrue(saved.name, saved.name.endsWith(".mp4"));
        assertArrayEquals(bytes(100, 'v'), saved.bytes());
    }

    // ---- Through the jobs every route runs ---------------------------------------------------

    @Test
    public void aProgressiveVideoIsSavedIntoThePickedFolder() throws Exception {
        SaveProgressTest.Gallery gallery = gallery();
        assertTrue(SaveTree.keep(context, treeUri));
        try (LocalServer server = new LocalServer()) {
            MediaSave.policyForTests = localPolicy(server, null);
            byte[] body = mp4(64_000);
            server.serve("/clip.mp4", 200, "video/mp4", body, body.length);

            finish(MediaSave.start(context, true,
                    MediaSave.fileJob(context, server.origin() + "/clip.mp4", Downloader.Kind.VIDEO)));

            List<Tree.Doc> files = tree.files();
            assertEquals(1, files.size());
            assertFalse(files.get(0).name, files.get(0).name.startsWith(MediaStoreWriter.LEGACY_PENDING_PREFIX));
            assertArrayEquals(body, files.get(0).bytes());
            assertTrue(gallery.rows.isEmpty());
            assertEquals("Saved to " + L10n.isolate("Download/.Insta"), ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void aDashVideoIsJoinedIntoThePickedFolder() throws Exception {
        assertTrue(SaveTree.keep(context, treeUri));
        byte[] picture = bytes(1_000, 'v');
        byte[] sound = bytes(1_000, 'a');
        try (LocalServer server = new LocalServer()) {
            MediaSave.policyForTests = localPolicy(server, () -> describeWorkFiles(picture, sound));
            server.serve("/v.mp4", 200, "video/mp4", mp4(1_000), 1_000);
            server.serve("/a.mp4", 200, "audio/mp4", mp4(1_000), 1_000);
            DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                    server.origin() + "/v.mp4");
            DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000,
                    server.origin() + "/a.mp4");

            finish(MediaSave.start(context, true, MediaSave.dashJob(context, video, audio, null)));

            List<Tree.Doc> files = tree.files();
            assertEquals(1, files.size());
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(picture);
            joined.write(sound);
            assertArrayEquals("the folder didn't get the joined file", joined.toByteArray(), files.get(0).bytes());
            String[] left = DashSave.workFolder(context).list();
            assertEquals("the join left work files behind", 0, left == null ? 0 : left.length);
        }
    }

    // ---- After a stop ------------------------------------------------------------------------

    @Test
    public void theSweepRemovesAStoppedSavesHiddenFileAndKeepsAFinishedOne() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        ContentResolver resolver = context.getContentResolver();
        Uri root = SaveTree.root(treeUri);
        Uri stopped = DocumentsContract.createDocument(resolver, root, "application/octet-stream", ".pending-a.tmp");
        Uri late = DocumentsContract.createDocument(resolver, root, "application/octet-stream", ".pending-b.tmp");
        assertTrue(SaveLeftovers.pending(context, stopped));
        assertTrue(SaveLeftovers.pending(context, late));
        // The process ended after the rename and before the cross-off.
        Uri finished = DocumentsContract.renameDocument(resolver, late, "IG_VID_kept.mp4");
        assertNotNull(finished);

        tree.stableIds = true;
        Uri stable = DocumentsContract.createDocument(resolver, root, "application/octet-stream", ".pending-c.tmp");
        assertTrue(SaveLeftovers.pending(context, stable));
        DocumentsContract.renameDocument(resolver, stable, "IG_VID_stable.mp4");
        // Asked for by its old path, the renamed file throws, as on the phone's storage.
        tree.stableIds = false;

        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);

        Set<String> names = new HashSet<>();
        for (Tree.Doc doc : tree.files()) names.add(doc.name);
        assertEquals(new HashSet<>(Arrays.asList("IG_VID_kept.mp4", "IG_VID_stable.mp4")), names);
        assertTrue("the sweep left entries on the list", ledger().isEmpty());
    }

    @Test
    public void aLeftoverInAFolderWhoseGrantWentIsCrossedOff() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        Uri stopped = DocumentsContract.createDocument(context.getContentResolver(), SaveTree.root(treeUri),
                "application/octet-stream", ".pending-a.tmp");
        assertTrue(SaveLeftovers.pending(context, stopped));
        tree.locked = true;

        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        assertTrue("an unreachable folder's leftover keeps the sweep from finishing every start", ledger().isEmpty());
    }

    @Test
    public void skipWhatsAlreadySavedAsksTheFolderForItsFile() throws IOException {
        assertTrue(SaveTree.keep(context, treeUri));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        write(video.open("video/mp4"), bytes(100, 'v'));
        video.commit();
        DownloadHistory.Entry entry = new DownloadHistory.Entry(video.publishedItem(), video.savedLocation(), null, 0, 0);
        assertTrue(DownloadHistory.present(context, entry));

        assertTrue(DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(video.publishedItem())));
        assertFalse("a file deleted from the folder still counts as saved", DownloadHistory.present(context, entry));
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private Tree tree() {
        return install();
    }

    /** The picked folder's tree in the fake folder app {@link #install} puts in place. */
    public static final Uri TREE = DocumentsContract.buildTreeDocumentUri(Tree.AUTHORITY, Tree.ROOT);

    /** The fake folder app, installed as the phone's own storage. For the settings page's tests too. */
    public static Tree install() {
        ProviderInfo info = new ProviderInfo();
        info.authority = Tree.AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = Manifest.permission.MANAGE_DOCUMENTS;
        info.writePermission = Manifest.permission.MANAGE_DOCUMENTS;
        return Robolectric.buildContentProvider(Tree.class).create(info).get();
    }

    private SaveProgressTest.Gallery gallery() {
        return Robolectric.setupContentProvider(SaveProgressTest.Gallery.class, MediaStore.AUTHORITY);
    }

    private void writable(Uri row) {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(row, new ByteArrayOutputStream());
    }

    private boolean persisted(Uri uri) {
        for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.getUri().equals(uri)) return true;
        }
        return false;
    }

    private Set<String> ledger() {
        return context.getSharedPreferences("hushgram_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new HashSet<>());
    }

    private static void write(OutputStream stream, byte[] body) throws IOException {
        stream.write(body);
        stream.flush();
    }

    private static byte[] bytes(int size, char fill) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        System.arraycopy(MP4_HEAD, 0, body, 0, MP4_HEAD.length);
        for (int i = MP4_HEAD.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    private void finish(Thread worker) throws InterruptedException {
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static MediaUrlPolicy localPolicy(LocalServer server, Runnable onSound) {
        int port = server.port();
        return new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (onSound != null && url.getPath().equals("/a.mp4")) onSound.run();
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
    }

    private void describeWorkFiles(byte[] pictureSample, byte[] soundSample) {
        for (File file : DashSave.workFolder(context).listFiles()) {
            DataSource path = DataSource.toDataSource(file.getPath());
            if (file.getName().startsWith("video")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createVideoFormat("video/avc", 1280, 720), pictureSample);
            } else if (file.getName().startsWith("audio")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2),
                        soundSample);
            }
        }
    }

    /**
     * The phone's own storage as its folder app shows it, two folders deep: Download/.Insta, the
     * hidden folder a person picks, and Download/Other. Ids are paths, as that app's are, so a
     * rename gives a file a new id, unless [stableIds] has it keep the one it had.
     */
    public static final class Tree extends DocumentsProvider {
        static final String AUTHORITY = "com.android.externalstorage.documents";
        static final String ROOT = "primary:Download/.Insta";
        static final String OTHER = "primary:Download/Other";

        static final class Doc {
            final String parent;
            final String mime;
            final File file;
            volatile String name;

            Doc(String parent, String name, String mime, File file) {
                this.parent = parent;
                this.name = name;
                this.mime = mime;
                this.file = file;
            }

            byte[] bytes() throws IOException {
                return Files.readAllBytes(file.toPath());
            }
        }

        final Map<String, Doc> docs = new ConcurrentHashMap<>();
        volatile boolean readOnly, refuseCreate, refuseRename, stableIds, locked;
        private File base;
        private int next;

        @Override
        public boolean onCreate() {
            base = new File(getContext().getCacheDir(), "tree-" + System.nanoTime());
            base.mkdirs();
            docs.put(ROOT, new Doc(null, ".Insta", Document.MIME_TYPE_DIR, null));
            docs.put(OTHER, new Doc(null, "Other", Document.MIME_TYPE_DIR, null));
            return true;
        }

        /** Download/.Insta deleted in Files, its grant kept. */
        public void deleteFolder() {
            docs.remove(ROOT);
        }

        /** The files, never the folders, anywhere in the tree. */
        List<Doc> files() {
            List<Doc> files = new ArrayList<>();
            for (Doc doc : docs.values()) if (!Document.MIME_TYPE_DIR.equals(doc.mime)) files.add(doc);
            return files;
        }

        List<Doc> folders(String parent) {
            List<Doc> folders = new ArrayList<>();
            for (Doc doc : docs.values()) {
                if (parent.equals(doc.parent) && Document.MIME_TYPE_DIR.equals(doc.mime)) folders.add(doc);
            }
            return folders;
        }

        Doc doc(String uri) {
            assertNotNull("nothing was published", uri);
            Doc doc = docs.get(DocumentsContract.getDocumentId(Uri.parse(uri)));
            assertNotNull("the published file isn't in the folder: " + uri, doc);
            return doc;
        }

        private Doc found(String id) throws FileNotFoundException {
            if (locked) throw new SecurityException("the grant is gone");
            Doc doc = docs.get(id);
            if (doc == null) throw new FileNotFoundException(id);
            return doc;
        }

        @Override
        public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection == null ? new String[] { DocumentsContract.Root.COLUMN_ROOT_ID } : projection);
        }

        @Override
        public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            MatrixCursor rows = new MatrixCursor(columns(projection));
            add(rows, id, found(id));
            return rows;
        }

        @Override
        public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
                throws FileNotFoundException {
            found(parent);
            MatrixCursor rows = new MatrixCursor(columns(projection));
            for (Map.Entry<String, Doc> doc : docs.entrySet()) {
                if (parent.equals(doc.getValue().parent)) add(rows, doc.getKey(), doc.getValue());
            }
            return rows;
        }

        /**
         * As the phone's storage answers: a file that isn't there throws, since its path can't be
         * looked up. An app whose ids aren't paths just doesn't find it, which reads as a refusal.
         */
        @Override
        public boolean isChildDocument(String parent, String id) {
            if (!stableIds && !docs.containsKey(id)) {
                throw new IllegalArgumentException("Failed to determine if " + id + " is child of " + parent);
            }
            Doc doc = docs.get(id);
            while (doc != null && doc.parent != null) {
                if (doc.parent.equals(parent)) return true;
                doc = docs.get(doc.parent);
            }
            return false;
        }

        @Override
        public synchronized String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            found(parent);
            if (refuseCreate) throw new FileNotFoundException("refused");
            String id = stableIds ? "id-" + (next++) : parent + "/" + name;
            if (docs.containsKey(id)) throw new IllegalStateException("already there");
            File file = Document.MIME_TYPE_DIR.equals(mime) ? null : new File(base, "f" + (next++));
            try {
                if (file != null && !file.createNewFile()) throw new FileNotFoundException("taken");
            } catch (IOException e) {
                throw new FileNotFoundException(e.toString());
            }
            docs.put(id, new Doc(parent, name, mime, file));
            return id;
        }

        @Override
        public synchronized String renameDocument(String id, String name) throws FileNotFoundException {
            Doc doc = found(id);
            if (refuseRename) throw new FileNotFoundException("refused");
            for (Doc other : docs.values()) {
                if (other != doc && name.equals(other.name) && doc.parent.equals(other.parent)) {
                    throw new IllegalStateException("already there");
                }
            }
            if (stableIds) {
                doc.name = name;
                return null;
            }
            String renamed = doc.parent + "/" + name;
            docs.remove(id);
            doc.name = name;
            docs.put(renamed, doc);
            return renamed;
        }

        @Override
        public void deleteDocument(String id) throws FileNotFoundException {
            Doc doc = found(id);
            docs.remove(id);
            if (doc.file != null) doc.file.delete();
        }

        @Override
        public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
                throws FileNotFoundException {
            Doc doc = found(id);
            if (doc.file == null) throw new FileNotFoundException("a folder");
            return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.parseMode(mode));
        }

        private static String[] columns(String[] projection) {
            return projection != null ? projection : new String[] {
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
            };
        }

        private void add(MatrixCursor rows, String id, Doc doc) {
            MatrixCursor.RowBuilder row = rows.newRow();
            row.add(Document.COLUMN_DOCUMENT_ID, id);
            row.add(Document.COLUMN_DISPLAY_NAME, doc.name);
            row.add(Document.COLUMN_MIME_TYPE, doc.mime);
            boolean folder = Document.MIME_TYPE_DIR.equals(doc.mime);
            row.add(Document.COLUMN_FLAGS, folder
                    ? (readOnly ? 0 : Document.FLAG_DIR_SUPPORTS_CREATE)
                    : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE);
            row.add(Document.COLUMN_SIZE, doc.file == null ? null : doc.file.length());
        }
    }
}
