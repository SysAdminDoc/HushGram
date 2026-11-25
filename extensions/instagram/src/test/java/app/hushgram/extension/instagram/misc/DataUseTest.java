/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/** What the Space used row adds up (#683): Instagram's data outside its cache, its biggest folders and HushGram's share. */
public class DataUseTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static void write(File root, String path, long bytes) throws IOException {
        File file = new File(root, path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(bytes);
        }
    }

    @Test public void addsUpTheDataOutsideTheCacheAndNamesTheBiggestFolders() throws IOException {
        File data = folder.newFolder("data");
        write(data, "files/mqtt/a.bin", 200);
        write(data, "files/mqtt/deeper/b.bin", 100);
        write(data, "files/hushgram-profiles/pictures/1.jpg", 50);
        write(data, "files/loose.bin", 20);
        write(data, "databases/main.db", 150);
        write(data, "shared_prefs/hushgram_saves.xml", 10);
        write(data, "cache/image_scoped/big.bin", 10_000);
        write(data, "code_cache/c.bin", 5_000);
        File external = folder.newFolder("external");
        write(external, "files/videos/v.mp4", 400);
        write(external, "cache/w.bin", 9_999);

        DataUse use = DataUse.of(data, external);

        assertEquals("the cache folders are left out", 930, use.total);
        assertEquals(DataUse.BIGGEST, use.biggest.size());
        assertEquals("Android/data/files/videos", use.biggest.get(0).name);
        assertEquals(400, use.biggest.get(0).bytes);
        assertEquals("a folder counts everything under it", "files/mqtt", use.biggest.get(1).name);
        assertEquals(300, use.biggest.get(1).bytes);
        assertEquals("loose files count as their folder", "databases", use.biggest.get(2).name);
        assertEquals(150, use.biggest.get(2).bytes);
        assertEquals("HushGram's folders and files, wherever they are", 60, use.hushgram);
    }

    @Test public void linksAreNotFollowed() throws IOException {
        File data = folder.newFolder("data");
        write(data, "files/a.bin", 10);
        File libraries = folder.newFolder("libraries");
        write(libraries, "libbig.so", 7_000);
        try {
            Files.createSymbolicLink(new File(data, "lib").toPath(), libraries.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
            org.junit.Assume.assumeNoException("this machine can't make links", noLinks);
        }

        assertEquals(10, DataUse.of(data, null).total);
    }

    @Test public void nothingThereIsNothingUsed() {
        DataUse use = DataUse.of(new File(folder.getRoot(), "missing"), null);
        assertEquals(0, use.total);
        assertTrue(use.biggest.isEmpty());
        assertEquals(0, use.hushgram);
    }
}
