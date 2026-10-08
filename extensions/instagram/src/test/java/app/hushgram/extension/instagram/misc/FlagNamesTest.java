/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Import flag names: reading a name list, keeping HushGram's copy, and the labels MetaConfig's rows get. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FlagNamesTest {
    /** Instagram's own id_name_mapping.json shape, which other mods' mapping files share. */
    private static final String MAPPING = "[\"23355:ig_android_feed_tweaks:0:enabled:16:item_limit\", \"42::0:bar\"]";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** One of MetaConfig's schema entries, as the patch's filled readers see it. */
    private static final class Entry {
        final int config, index;

        Entry(int config, int index) {
            this.config = config;
            this.index = index;
        }
    }

    private FlagNames.Entries stock;

    @Before
    public void prepare() throws IOException {
        stock = FlagNames.entries;
        FlagNames.entries = new FlagNames.Entries() {
            @Override public int config(Object entry) { return entry instanceof Entry ? ((Entry) entry).config : -1; }
            @Override public int index(Object entry) { return entry instanceof Entry ? ((Entry) entry).index : -1; }
        };
        FlagNames.clear(context());
        FlagNames.forgetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() throws IOException {
        FlagNames.entries = stock;
        FlagNames.clear(context());
        FlagNames.forgetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static Context context() {
        return Utils.getContext();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Instagram's mapping format: each string is a config, its name, then index and name pairs. */
    @Test
    public void readsInstagramsMappingFormat() throws IOException {
        FlagNames.Names names = FlagNames.parse(bytes(MAPPING));

        assertEquals("ig_android_feed_tweaks", names.config(23355));
        assertEquals("enabled", names.parameter(23355, 0));
        assertEquals("item_limit", names.parameter(23355, 16));
        assertEquals("a config Instagram has no name for is fine", "bar", names.parameter(42, 0));
        assertNull(names.config(42));
        assertNull(names.parameter(23355, 1));
        assertEquals(4, names.size());
        assertEquals(0, names.leftOut);
    }

    /** A list of {code, name} objects, where a code is a config or "config::index", and a name may hold a colon. */
    @Test
    public void readsCodeAndNameObjects() throws IOException {
        FlagNames.Names names = FlagNames.parse(bytes("[{\"name\": \"Feed: tweaks\", \"desc\": \"what it does\", \"code\": \"23355\"},"
                + " {\"name\": \"Item limit\", \"code\": \"23355::16\"}, {\"name\": \"Numbered\", \"code\": 7}]"));

        assertEquals("Feed: tweaks", names.config(23355));
        assertEquals("Item limit", names.parameter(23355, 16));
        assertEquals("Numbered", names.config(7));
        assertEquals(0, names.leftOut);
    }

    /** HushGram's own text: one config=name or config::index=name a line, with comments, blanks and CRLF. */
    @Test
    public void readsHushGramsText() throws IOException {
        FlagNames.Names names = FlagNames.parse(bytes("\uFEFF# my names\n\n23355=Feed tweaks\r\n 23355::16 = Item limit \n# 1=skipped\n"));

        assertEquals("Feed tweaks", names.config(23355));
        assertEquals("Item limit", names.parameter(23355, 16));
        assertNull(names.config(1));
        assertEquals(2, names.size());
        assertEquals(0, names.leftOut);
    }

    /** A file that isn't a name list in any of the three formats is unreadable, whatever went wrong with it. */
    @Test
    public void aBadFileIsUnreadable() {
        byte[][] bad = {
                {(byte) 0xc3, (byte) 0x28},
                bytes("{\"overrides\": {}}"),
                bytes("[\"23355:feed\", 3"),
                bytes("[\"nope\", \"x:y:z:w\", 12, null]"),
                bytes("[{\"code\": \"0\", \"name\": \"zero\"}, {\"code\": \"5\"}]"),
                bytes("garbage line\nmore garbage"),
                new byte[FlagNames.MAX_BYTES + 1],
                null,
        };
        for (byte[] file : bad) {
            assertThrows(FlagNames.Unreadable.class, () -> FlagNames.parse(file));
        }
        assertThrows(FlagNames.Unreadable.class, () -> FlagNames.read(new ByteArrayInputStream(new byte[FlagNames.MAX_BYTES + 1])));
        assertThrows(FlagNames.Unreadable.class, () -> FlagNames.read(null));
    }

    /** A list that reads but names nothing is empty, not unreadable. */
    @Test
    public void aListOfNothingIsEmpty() {
        for (String file : new String[]{"", "   \n", "[]", " [ ] ", "# just a comment\n\n", "[\"42:\"]"}) {
            assertThrows(file, FlagNames.Empty.class, () -> FlagNames.parse(bytes(file)));
        }
    }

    /** A repeated id keeps its first name, and every repeat is counted as left out. */
    @Test
    public void aRepeatedIdKeepsItsFirstName() throws IOException {
        FlagNames.Names names = FlagNames.parse(bytes("[\"7:first:0:x:0:y\", \"7:second:1:z\"]"));

        assertEquals("first", names.config(7));
        assertEquals("x", names.parameter(7, 0));
        assertEquals("z", names.parameter(7, 1));
        assertEquals(2, names.leftOut);

        FlagNames.Names text = FlagNames.parse(bytes("9=one\n9=two\n9::3=a\n9::3=b\n"));
        assertEquals("one", text.config(9));
        assertEquals("a", text.parameter(9, 3));
        assertEquals(2, text.leftOut);
    }

    /** Numbers past Instagram's own bounds, padded numbers and names that can't be shown are left out, the rest kept. */
    @Test
    public void entriesThatDontFitAreLeftOut() throws IOException {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= FlagNames.MAX_NAME; i++) tooLong.append('n');
        FlagNames.Names names = FlagNames.parse(bytes("0=zero\n1048576=big\n1::16384=wide\n01=padded\n1::05=padded\n"
                + "2=" + tooLong + "\n3=be\007ll\n4=\n1::5=kept\n"));

        assertEquals("kept", names.parameter(1, 5));
        assertEquals(1, names.size());
        assertEquals(8, names.leftOut);
    }

    /** An import is kept as HushGram's own copy, read back after a restart, and forgotten by clear. */
    @Test
    public void anImportIsKeptUntilItsCleared() throws IOException {
        FlagNames.Names imported = FlagNames.importNames(context(), bytes(MAPPING));
        File copy = FlagNames.store(context());

        assertEquals(4, imported.size());
        assertTrue(copy.isFile());
        assertTrue("kept in HushGram's own folder", copy.getAbsolutePath().startsWith(context().getFilesDir().getAbsolutePath()));
        assertEquals("# HushGram flag names: config=name or config::index=name, one per line.\n"
                + "42::0=bar\n23355=ig_android_feed_tweaks\n23355::0=enabled\n23355::16=item_limit\n",
                new String(Files.readAllBytes(copy.toPath()), StandardCharsets.UTF_8));

        FlagNames.forgetForTests();
        assertEquals("read back from the copy", 4, FlagNames.count());
        assertEquals("item_limit", FlagNames.names().parameter(23355, 16));

        assertTrue(FlagNames.clear(context()));
        assertFalse(copy.exists());
        assertEquals(0, FlagNames.count());
        assertFalse("nothing left to clear", FlagNames.clear(context()));
    }

    /** A file that can't be used leaves the names already in use, and their copy, as they were. */
    @Test
    public void aFailedImportChangesNothing() throws IOException {
        FlagNames.importNames(context(), bytes(MAPPING));
        File copy = FlagNames.store(context());
        byte[] before = Files.readAllBytes(copy.toPath());

        assertThrows(FlagNames.Empty.class, () -> FlagNames.importNames(context(), bytes("[]")));
        assertThrows(FlagNames.Unreadable.class, () -> FlagNames.importNames(context(), bytes("{}")));

        assertEquals(4, FlagNames.count());
        assertEquals(new String(before, StandardCharsets.UTF_8), new String(Files.readAllBytes(copy.toPath()), StandardCharsets.UTF_8));
        FlagNames.forgetForTests();
        assertEquals(4, FlagNames.count());
    }

    /**
     * Instagram's format names a parameter in fewer bytes than HushGram's copy, which repeats the
     * config number on every line, so a list just under the limit can make a copy past it. That
     * copy wouldn't read back after a restart, so the import is refused and nothing is written.
     */
    @Test
    public void anImportWhoseCopyWouldOutgrowTheLimitIsRefused() throws IOException {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 240; i++) name.append('n');
        int config = 1_000_000, index = 0;
        StringBuilder file = new StringBuilder("[\"").append(config).append(':');
        while (file.length() + name.length() + 64 < FlagNames.MAX_BYTES) {
            if (index == FlagNames.INDEX_LIMIT) {
                file.append("\", \"").append(++config).append(':');
                index = 0;
            }
            file.append(':').append(index++).append(':').append(name);
        }
        byte[] list = bytes(file.append("\"]").toString());
        assertTrue(list.length <= FlagNames.MAX_BYTES);
        assertTrue("the copy outgrows the limit",
                FlagNames.text(FlagNames.parse(list)).getBytes(StandardCharsets.UTF_8).length > FlagNames.MAX_BYTES);

        assertThrows(FlagNames.Unreadable.class, () -> FlagNames.importNames(context(), list));

        assertFalse("nothing written", FlagNames.store(context()).exists());
        assertEquals(0, FlagNames.count());
    }

    /** A copy that no longer reads means no names, not a broken MetaConfig. */
    @Test
    public void aDamagedCopyIsNoNames() throws IOException {
        FlagNames.importNames(context(), bytes(MAPPING));
        Files.write(FlagNames.store(context()).toPath(), new byte[]{(byte) 0xff, (byte) 0xfe});
        FlagNames.forgetForTests();

        assertEquals(0, FlagNames.count());
        assertEquals("_16", FlagNames.parameter(new Entry(23355, 16), "_16"));
    }

    /** Only Instagram's own unnamed labels take an imported name; a name Instagram has stays. */
    @Test
    public void onlyUnnamedLabelsTakeAName() throws IOException {
        FlagNames.importNames(context(), bytes(MAPPING));
        Entry entry = new Entry(23355, 16);

        assertEquals("item_limit", FlagNames.parameter(entry, "_16"));
        assertEquals("item_limit", FlagNames.parameter(entry, "16"));
        assertEquals("item_limit", FlagNames.parameter(entry, ""));
        assertEquals("instagram_named_it", FlagNames.parameter(entry, "instagram_named_it"));
        assertEquals("ig_android_feed_tweaks", FlagNames.config(entry, "_23355"));
        assertEquals("ig_feed", FlagNames.config(entry, "ig_feed"));
        assertEquals("a parameter the list doesn't name", "_3", FlagNames.parameter(new Entry(23355, 3), "_3"));
        assertEquals("a config the list doesn't name", "_42", FlagNames.config(new Entry(42, 0), "_42"));
        assertEquals("bar", FlagNames.parameter(new Entry(42, 0), "_0"));
        assertNull(FlagNames.parameter(entry, null));
        assertNull(FlagNames.config(entry, null));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("MetaConfig rows named 4"));
    }

    /** Before the patch fills its readers, every entry reads as -1, and no label changes. */
    @Test
    public void unfilledReadersLeaveEveryLabel() throws IOException {
        FlagNames.entries = stock;
        FlagNames.importNames(context(), bytes(MAPPING));

        assertEquals("_16", FlagNames.parameter(new Object(), "_16"));
        assertEquals("_23355", FlagNames.config(new Object(), "_23355"));
    }

    /** Paused, before the settings are ready, or when a reader throws, Instagram's labels go through as they are. */
    @Test
    public void pausedUnreadyAndThrowingLeaveTheLabels() throws IOException {
        FlagNames.importNames(context(), bytes(MAPPING));
        Entry entry = new Entry(23355, 16);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("_16", FlagNames.parameter(entry, "_16"));
        assertEquals("_23355", FlagNames.config(entry, "_23355"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals("_16", FlagNames.parameter(entry, "_16")));

        FlagNames.entries = new FlagNames.Entries() {
            @Override public int config(Object row) { throw new IllegalStateException(); }
            @Override public int index(Object row) { throw new IllegalStateException(); }
        };
        assertEquals("_16", FlagNames.parameter(entry, "_16"));
        assertEquals("_23355", FlagNames.config(entry, "_23355"));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("'flag names' hook"));
    }

    @Test
    public void unnamedMatchesInstagramsOwnShapes() {
        for (String label : new String[]{"", "_", "_16", "16", "_1048575"}) assertTrue(label, FlagNames.unnamed(label));
        for (String label : new String[]{"enabled", "_a1", "1a", "__1", "_12345678901"}) assertFalse(label, FlagNames.unnamed(label));
    }
}
