/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Picked files go after free native formats and before paid ones only with the switch on. Native
 * paid entries keep their gates, a name rebuild keeps the last local pick, and unknowns stay native.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryFontsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    /** A stand-in for Instagram's format: what the patch's stub would make for a path. */
    private static final class Format {
        final String path;

        Format(String path) {
            this.path = path;
        }

        // Instagram's formats compare by content, so two of them can be equal and still not be the same one.
        @Override
        public boolean equals(Object other) {
            return other instanceof Format && ((Format) other).path.equals(path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }

    private Context context;

    @Before
    public void clean() {
        context = RuntimeEnvironment.getApplication();
        reset();
        StoryFonts.formatFor = Format::new;
        StoryFonts.premiumOf = format -> format instanceof String && ((String) format).startsWith("paid:") ? 1 : 0;
        FontsForTests.loadEveryFile(Typeface.DEFAULT);
    }

    @After
    public void restore() {
        reset();
        FontsForTests.reset();
    }

    private void reset() {
        Settings.STORY_FONTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        StoryFonts.resetForTests();
        File[] kept = StoryFonts.directory(RuntimeEnvironment.getApplication()).listFiles();
        if (kept != null) for (File file : kept) assertTrue(file.delete());
    }

    private static String report() {
        StringBuilder all = new StringBuilder();
        for (String line : HookStatus.report()) all.append(line).append('\n');
        return all.toString();
    }

    private String add(String shown) {
        return StoryFonts.add(context, new ByteArrayInputStream(TRUE_TYPE), shown);
    }

    private static List<Object> instagrams() {
        return new ArrayList<>(Arrays.asList("classic", "modern"));
    }

    /** Off to start, so a picked file waits until the switch is on, and the call is counted. */
    @Test
    public void offToStartLeavesTheList() {
        assertFalse(Settings.STORY_FONTS.get());
        add("Lobster.ttf");
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(instagrams(), list);
        assertTrue(report(), report().contains(FamilyNames.STORY_FONTS + ": invoked 1"));
    }

    /** On, each picked file goes after Instagram's own, sorted by name, and once only. */
    @Test
    public void onAddsEachPickedFontAfterInstagrams() {
        Settings.STORY_FONTS.save(true);
        assertEquals("Lobster.ttf", add("Lobster.ttf"));
        assertEquals("comic.otf", add("comic.otf"));
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(4, list.size());
        assertEquals(instagrams(), list.subList(0, 2));
        File directory = StoryFonts.directory(context);
        assertEquals(new File(directory, "comic.otf").getAbsolutePath(), ((Format) list.get(2)).path);
        assertEquals(new File(directory, "Lobster.ttf").getAbsolutePath(), ((Format) list.get(3)).path);

        StoryFonts.formats(list);
        assertEquals("one already in the list isn't added again", 4, list.size());
        List<Object> again = instagrams();
        StoryFonts.formats(again);
        assertSame("the editor gets the same format each time", list.get(2), again.get(2));
        assertTrue(report(), report().contains(StoryFonts.ADDED + " 2"));
    }

    /** Instagram's picker gates the entire tail from its first paid row, not just each paid name. */
    private static boolean nativePaidClick(List<?> list, int index) {
        for (int at = 0; at < list.size(); at++) {
            if (StoryFonts.premiumOf.applyAsInt(list.get(at)) == 1) return index >= at;
        }
        return false;
    }

    /** #325: imported files must not enter that paid tail; all native entries retain their identity and order. */
    @Test
    public void localFontsStaySelectableBeforeTheNativePaidTail() {
        Settings.STORY_FONTS.save(true);
        add("Zebra.ttf");
        add("Alpha.otf");
        Format nativeImport = new Format("/native/imported.ttf");
        Object arcade = "paid:arcade";
        Object gothic = "paid:gothic";
        List<Object> list = new ArrayList<>(Arrays.asList("classic", nativeImport, arcade, gothic));
        List<Object> before = new ArrayList<>(list);
        before.add(new Format("/local/font.ttf"));
        assertTrue("the old append puts a free local file in the paid tail", nativePaidClick(before, 4));

        StoryFonts.formats(list);

        assertEquals(6, list.size());
        assertEquals("classic", list.get(0));
        assertSame("Instagram's own imported format stays untouched", nativeImport, list.get(1));
        assertEquals("Alpha", StoryFonts.label(((Format) list.get(2)).path));
        assertEquals("Zebra", StoryFonts.label(((Format) list.get(3)).path));
        assertSame(arcade, list.get(4));
        assertSame(gothic, list.get(5));
        assertFalse("a native imported file stays free", nativePaidClick(list, 1));
        assertFalse("the first local font applies without a Plus offer", nativePaidClick(list, 2));
        assertFalse("the second local font applies without a Plus offer", nativePaidClick(list, 3));
        assertTrue("Arcade still requires its native entitlement", nativePaidClick(list, 4));
        assertTrue("Gothic still requires its native entitlement", nativePaidClick(list, 5));
        StoryFonts.applied(nativeImport);
        assertNull("native imports do not acquire local ownership", StoryFonts.named(StoryFonts.IMPORT_FONT));

        StoryFonts.formats(list);
        assertEquals("the same list is not duplicated", 6, list.size());
        assertSame(arcade, list.get(4));
        assertSame(gothic, list.get(5));
    }

    /** The insertion also works with no free native entry, or no native entries at all. */
    @Test
    public void aPaidOnlyOrEmptyListStillPreservesNativeEligibility() {
        Settings.STORY_FONTS.save(true);
        add("Local.ttf");
        List<Object> paid = new ArrayList<>(List.of("paid:headline"));
        StoryFonts.formats(paid);
        assertFalse(nativePaidClick(paid, 0));
        assertEquals("paid:headline", paid.get(1));
        assertTrue(nativePaidClick(paid, 1));

        List<Object> empty = new ArrayList<>();
        StoryFonts.formats(empty);
        assertEquals(1, empty.size());
        assertFalse(nativePaidClick(empty, 0));
    }

    /** An unknown boundary never falls back to appending a local file beyond a possibly paid row. */
    @Test
    public void unreadableNativeClassificationLeavesTheWholeListUntouched() {
        Settings.STORY_FONTS.save(true);
        add("Local.ttf");
        Object unknown = new Object();
        List<Object> list = new ArrayList<>(Arrays.asList("classic", unknown, "paid:arcade"));
        List<Object> original = new ArrayList<>(list);
        StoryFonts.premiumOf = format -> format == unknown ? -1 : 0;
        StoryFonts.formats(list);
        assertEquals(original, list);
        assertSame(unknown, list.get(1));

        StoryFonts.premiumOf = StoryFonts::nativePremium;
        assertEquals(-1, StoryFonts.nativePremium(unknown));
        StoryFonts.formats(list);
        assertEquals("an unfilled classifier stub also leaves the list", original, list);
    }

    @Test
    public void aThrowingNativeClassifierKeepsThePaidEntriesAndReportsIt() {
        Settings.STORY_FONTS.save(true);
        add("Local.ttf");
        List<Object> list = new ArrayList<>(Arrays.asList("classic", "paid:gothic"));
        List<Object> original = new ArrayList<>(list);
        StoryFonts.premiumOf = format -> { throw new IllegalStateException("unreadable native font"); };
        StoryFonts.formats(list);
        assertEquals(original, list);
        assertTrue(HookStatus.missing(FamilyNames.STORY_FONTS).toString().contains(IllegalStateException.class.getName()));
    }

    /** No files, no list, or a stub that can't make a format: Instagram's list as it was. */
    @Test
    public void nothingToAddLeavesTheList() {
        Settings.STORY_FONTS.save(true);
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        StoryFonts.formats(null);
        assertEquals(instagrams(), list);

        add("Lobster.ttf");
        StoryFonts.formatFor = path -> null;
        StoryFonts.formats(list);
        assertEquals("the stub as built makes nothing", instagrams(), list);
    }

    /** Paused, a saved switch adds nothing until HushGram is back on. */
    @Test
    public void pausedLeavesTheList() {
        Settings.STORY_FONTS.save(true);
        add("Lobster.ttf");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(instagrams(), list);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        StoryFonts.formats(list);
        assertEquals(3, list.size());
    }

    /** Before the settings are ready the switch can't be read, so the list stays Instagram's. */
    @Test
    public void beforeTheSettingsAreReadyItLeavesTheList() {
        Settings.STORY_FONTS.save(true);
        add("Lobster.ttf");
        List<Object> list = instagrams();
        SettingsContextRule.withoutContext(() -> StoryFonts.formats(list));
        assertEquals(instagrams(), list);
        SettingsContextRule.withoutContext(() -> assertNull(StoryFonts.named(StoryFonts.IMPORT_FONT)));
    }

    /** A stub that throws leaves the list as it was and says the hook threw. */
    @Test
    public void aThrowingStubLeavesTheListAndIsReported() {
        Settings.STORY_FONTS.save(true);
        add("Lobster.ttf");
        StoryFonts.formatFor = path -> {
            throw new IllegalStateException("no format");
        };
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(instagrams(), list);
        String missing = HookStatus.missing(FamilyNames.STORY_FONTS).toString();
        assertTrue(missing, missing.contains("'" + StoryFonts.LIST + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /**
     * A rebuild by IMPORT_FONT gets back the last picked font the editor applied, and nothing for
     * any other name, for one of Instagram's, for an equal format that isn't ours, or once its file
     * is taken out.
     */
    @Test
    public void aRebuildByNameGetsTheLastAppliedFontBack() {
        Settings.STORY_FONTS.save(true);
        add("Lobster.ttf");
        add("comic.otf");
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertNull("nothing applied yet", StoryFonts.named(StoryFonts.IMPORT_FONT));

        StoryFonts.applied(list.get(3));
        StoryFonts.applied("classic");
        StoryFonts.applied(new Format(((Format) list.get(2)).path));
        StoryFonts.applied(null);
        assertSame(list.get(3), StoryFonts.named(StoryFonts.IMPORT_FONT));
        assertNull(StoryFonts.named("classic"));
        assertNull(StoryFonts.named(null));
        assertTrue(report(), report().contains(StoryFonts.KEPT + " 1"));

        Settings.STORY_FONTS.save(false);
        assertNull("off, Instagram makes it", StoryFonts.named(StoryFonts.IMPORT_FONT));
        Settings.STORY_FONTS.save(true);
        assertTrue(StoryFonts.remove(context, "Lobster.ttf"));
        assertNull("its file is gone", StoryFonts.named(StoryFonts.IMPORT_FONT));
    }

    /** A file that isn't a font keeps nothing; a taken name gets a number; a removed one leaves the list. */
    @Test
    public void addingAndRemovingFiles() {
        assertNull(StoryFonts.add(context, new ByteArrayInputStream(new byte[] {'P', 'K', 3, 4, 0, 0}), "fonts.zip"));
        assertEquals(List.of(), StoryFonts.names(context));

        assertEquals("Lobster.ttf", add("Lobster.ttf"));
        assertEquals("Lobster (2).ttf", add("Lobster.ttf"));
        assertEquals("Lobster (3).ttf", add("Lobster.ttf"));
        assertEquals(List.of("Lobster (2).ttf", "Lobster (3).ttf", "Lobster.ttf"), StoryFonts.names(context));

        Settings.STORY_FONTS.save(true);
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(5, list.size());
        assertTrue(StoryFonts.remove(context, "Lobster (2).ttf"));
        assertTrue("already gone counts as gone", StoryFonts.remove(context, "Lobster (2).ttf"));
        assertFalse("never outside the folder", StoryFonts.remove(context, "../shared_prefs/x.xml"));
        List<Object> after = instagrams();
        StoryFonts.formats(after);
        assertEquals(4, after.size());
        assertEquals(List.of("Lobster (3).ttf", "Lobster.ttf"), StoryFonts.names(context));
    }

    /** The first editor list after restart recovers before caching any local paths. */
    @Test public void theFirstStoryListOmitsAnUnacceptedFontWhenAlreadyEnabled() {
        abandonStoryFont();
        List<Object> list = instagrams();
        StoryFonts.formats(list);
        assertEquals(3, list.size());
        assertEquals("kept.ttf", new File(((Format) list.get(2)).path).getName());
        assertFalse(new File(StoryFonts.directory(context), "pending.ttf").exists());
        assertTrue("the existing saved switch stays on", Settings.STORY_FONTS.savedValue());
    }

    @Test public void theFirstSavedFontListRecoversWithoutAnotherImport() {
        abandonStoryFont();
        assertEquals(List.of("kept.ttf"), StoryFonts.names(context));
        assertFalse(new File(StoryFonts.directory(context), "pending.ttf").exists());
    }

    private void abandonStoryFont() {
        Settings.STORY_FONTS.save(true);
        assertEquals("kept.ttf", add("kept.ttf"));
        Fonts.Prepared pending = StoryFonts.prepare(context, new ByteArrayInputStream(TRUE_TYPE));
        assertNotNull(pending);
        assertEquals("pending.ttf", StoryFonts.prepareInstall(context, pending, "pending.ttf"));
        assertTrue(pending.recordSetting(Settings.STORY_FONTS, true));
        assertTrue(pending.install());
        FontsForTests.reset();
        FontsForTests.loadEveryFile(Typeface.DEFAULT);
        StoryFonts.resetForTests();
        StoryFonts.formatFor = Format::new;
        StoryFonts.premiumOf = format -> format instanceof String && ((String) format).startsWith("paid:") ? 1 : 0;
    }

    /** The picker's name, made safe for a file: no folders, no control characters, never empty, not too long. */
    @Test
    public void cleanNames() {
        assertEquals("font", StoryFonts.cleanName(null));
        assertEquals("font", StoryFonts.cleanName("  "));
        assertEquals("font", StoryFonts.cleanName("..."));
        assertEquals("font", StoryFonts.cleanName("x.ttf.tmp"));
        assertEquals("hidden.ttf", StoryFonts.cleanName(".hidden.ttf"));
        assertEquals("a_b_c_d.otf", StoryFonts.cleanName("a/b\\c:d.otf"));
        assertEquals("tab_name.ttf", StoryFonts.cleanName("tab\tname.ttf"));
        String longName = "x".repeat(200) + ".ttf";
        String cleaned = StoryFonts.cleanName(longName);
        assertEquals(StoryFonts.LONGEST_NAME, cleaned.length());
        assertTrue(cleaned, cleaned.endsWith(".ttf"));
    }

    /** The editor names a kept font after its file, without the extension. */
    @Test
    public void labels() {
        assertEquals("Inkfree", StoryFonts.label("/data/fonts/Inkfree.ttf"));
        assertEquals("My Font (2)", StoryFonts.label("/data/fonts/My Font (2).otf"));
        assertEquals("font.v2", StoryFonts.label("/data/fonts/font.v2.ttf"));
        assertEquals("plain", StoryFonts.label("/data/fonts/plain"));
    }

    /** A free name keeps the extension last. */
    @Test
    public void freeNames() {
        File directory = StoryFonts.directory(context);
        assertEquals("plain", StoryFonts.freeName(directory, "plain"));
        add("plain");
        assertEquals("plain (2)", StoryFonts.freeName(directory, "plain"));
    }
}
