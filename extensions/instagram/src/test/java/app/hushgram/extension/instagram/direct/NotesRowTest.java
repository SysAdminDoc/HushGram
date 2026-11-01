/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the notes row hook answers, and that it hands back Instagram's own list whenever it can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NotesRowTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for the inbox's section enum, with the notes row between two others. */
    enum Section { SEARCH_BAR, TRAY, THREADS }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static Section[] inbox() {
        return new Section[] {Section.SEARCH_BAR, Section.TRAY, Section.THREADS};
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_NOTES_ROW.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_NOTES_ROW.resetToDefault();
        Settings.HIDE_NOTES_MAP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, the row is left out of a copy of the same array type, Instagram's own array is untouched, and it's counted. */
    @Test
    public void withTheSwitchOnTheRowIsLeftOut() {
        Section[] stock = inbox();

        Object[] kept = NotesRow.sections(stock);

        assertEquals(Section[].class, kept.getClass());
        assertArrayEquals(new Section[] {Section.SEARCH_BAR, Section.THREADS}, kept);
        assertArrayEquals(inbox(), stock);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(NotesRow.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(NotesRow.ROW));
    }

    /** The switch starts off, and off Instagram's own array goes through, while the hook still counts that it ran. */
    @Test
    public void offToStartAndOffKeepTheRow() {
        Settings.HIDE_NOTES_ROW.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.HIDE_NOTES_ROW.defaultValue);
        Section[] stock = inbox();
        assertSame(stock, NotesRow.sections(stock));

        Settings.HIDE_NOTES_ROW.save(false);
        assertSame(stock, NotesRow.sections(stock));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.NOTES_ROW));
    }

    /** Paused, or asked before the settings are read, the row stays. */
    @Test
    public void offPausedAndUnreadyKeepTheRow() {
        Section[] stock = inbox();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, NotesRow.sections(stock));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(stock, NotesRow.sections(stock)));

        assertEquals(2, NotesRow.sections(stock).length);
    }

    /** The Map bubble has its own switch: on, the row's answer becomes no, and it's counted. The notes row's switch doesn't touch it. */
    @Test
    public void theMapSwitchAnswersNoAndIsCounted() {
        Settings.HIDE_NOTES_ROW.save(false);
        Settings.HIDE_NOTES_MAP.save(true);

        assertEquals(0, NotesRow.mapShown(1));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(NotesRow.MAP));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.NOTES_ROW));
        assertSame("the sections aren't asked about the Map", inbox()[1], NotesRow.sections(new Section[] {Section.SEARCH_BAR, Section.TRAY, Section.THREADS})[1]);
    }

    /** Off, paused or before the settings are read, Instagram's answer goes through. */
    @Test
    public void offPausedAndUnreadyKeepTheMap() {
        assertEquals(Boolean.FALSE, Settings.HIDE_NOTES_MAP.defaultValue);
        assertEquals(1, NotesRow.mapShown(1));

        Settings.HIDE_NOTES_MAP.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(1, NotesRow.mapShown(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(1, NotesRow.mapShown(1)));
    }

    /** No Map to add stays no, and a failure reading the switch leaves Instagram's answer, recorded. */
    @Test
    public void noMapStaysNoAndAFailureLeavesTheAnswer() {
        Settings.HIDE_NOTES_MAP.save(true);
        assertEquals(0, NotesRow.mapShown(0));
        assertEquals(1, NotesRow.mapShown(1, () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().toString().contains("a working 'Map bubble' hook (it threw"));
    }

    /** A list without the notes row, a name that isn't an enum's, or no list at all, goes through as it came. */
    @Test
    public void aListWithoutTheRowGoesThroughAsItCame() {
        Section[] noNotes = {Section.SEARCH_BAR, Section.THREADS};
        assertSame(noNotes, NotesRow.sections(noNotes));
        Object[] names = {"SEARCH_BAR", NotesRow.NOTES, "THREADS"};
        assertSame(names, NotesRow.sections(names));
        Section[] empty = {};
        assertSame(empty, NotesRow.sections(empty));
        assertNull(NotesRow.sections(null));

        assertFalse(FeedFilterCounters.report().toString(), FeedFilterCounters.report().toString().contains(NotesRow.ROW));
    }

    /** A switch that throws keeps the row and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheRowAndIsReported() {
        Section[] stock = inbox();
        assertSame(stock, NotesRow.sections(stock, THROWS));

        String missing = HookStatus.missing(FamilyNames.NOTES_ROW).toString();
        assertTrue(missing, missing.contains("'" + NotesRow.ROW + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
