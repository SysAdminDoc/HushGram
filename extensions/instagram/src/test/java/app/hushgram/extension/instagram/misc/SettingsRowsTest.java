/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Rows in Instagram's settings (#235): nothing picked, every row shows; a picked id answers yes and
 * nothing else does; paused, unreadable or thrown, every row shows; the rows made are kept with
 * their titles for the list, saved once a moment later.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SettingsRowsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Instagram's row ids are constants of one enum. */
    private enum Id { ARCHIVE, CLOSE_FRIENDS, ACCOUNT_PRIVACY }

    /** A row model, and the row made from it, in one: its id and its title. */
    private static final class Row {
        final Object id;
        final Object title;

        Row(Object id, Object title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final SettingsRows.Rows STAND_IN = new SettingsRows.Rows() {
        @Override
        public Object idOfModel(Object model) {
            return ((Row) model).id;
        }

        @Override
        public Object idOfRow(Object row) {
            return ((Row) row).id;
        }

        @Override
        public Object titleOfRow(Object row) {
            return ((Row) row).title;
        }

        @Override
        public String text(Resources resources, Object title) {
            return (String) title;
        }
    };

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
        Settings.SEEN_SETTINGS_ROWS.resetToDefault();
        SettingsRows.reset();
        SettingsRows.access = STAND_IN;
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
        Settings.SEEN_SETTINGS_ROWS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsRows.reset();
        SettingsRows.access = SettingsRows.PATCHED;
        HookStatus.clear();
    }

    @Test
    public void withNothingPickedEveryRowShows() {
        for (Id id : Id.values()) assertFalse(id.name(), SettingsRows.hidden(new Row(id, null)));
        assertTrue(HookStatus.missing(FamilyNames.SETTINGS_ROWS).toString(), HookStatus.missing(FamilyNames.SETTINGS_ROWS).isEmpty());
        assertFalse(String.join("\n", HookStatus.report()).contains(SettingsRows.LEFT_OFF));
    }

    @Test
    public void aPickedRowIsLeftOffAndNoOtherIs() {
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE");
        assertTrue(SettingsRows.hidden(new Row(Id.ARCHIVE, null)));
        assertFalse(SettingsRows.hidden(new Row(Id.CLOSE_FRIENDS, null)));
        assertFalse(SettingsRows.hidden(new Row(Id.ACCOUNT_PRIVACY, null)));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SettingsRows.LEFT_OFF + " 1"));
    }

    @Test
    public void pausedOrComingBackOrUnreadableTheRowShows() {
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE");
        // A null model is the method coming back after a wait, already past the ask.
        assertFalse(SettingsRows.hidden(null));
        // An id that isn't one of the enum's constants is never matched by its text.
        assertFalse(SettingsRows.hidden(new Row("ARCHIVE", null)));
        assertFalse(SettingsRows.hidden(new Row(null, null)));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", SettingsRows.hidden(new Row(Id.ARCHIVE, null)));
        PauseForTests.resume();
        assertTrue(SettingsRows.hidden(new Row(Id.ARCHIVE, null)));
    }

    @Test
    public void aThrowShowsTheRowAndIsReported() {
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE");
        // Not a Row, so the stand-in's cast throws.
        assertFalse(SettingsRows.hidden(new Object()));
        assertFalse(HookStatus.missing(FamilyNames.SETTINGS_ROWS).toString(), HookStatus.missing(FamilyNames.SETTINGS_ROWS).isEmpty());
        SettingsRows.built(new Object());
    }

    @Test
    public void theRowsMadeAreOfferedInOrderWithTheirTitles() {
        SettingsRows.built(new Row(Id.CLOSE_FRIENDS, "Close Friends"));
        SettingsRows.built(new Row(Id.ARCHIVE, "Archiving\tand downloading\n"));
        SettingsRows.built(new Row(Id.ACCOUNT_PRIVACY, null));
        SettingsRows.built(new Row("NOT_AN_ENUM", "Skipped"));
        List<String> offered = new ArrayList<>();
        for (String[] row : SettingsRows.offered()) offered.add(row[0] + "=" + row[1]);
        assertEquals(List.of("CLOSE_FRIENDS=Close Friends", "ARCHIVE=Archiving and downloading", "ACCOUNT_PRIVACY=Account privacy"), offered);
        assertEquals("nothing saved before the moment passes", "", Settings.SEEN_SETTINGS_ROWS.savedValue());
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertEquals("CLOSE_FRIENDS\tClose Friends\nARCHIVE\tArchiving and downloading\nACCOUNT_PRIVACY\t", Settings.SEEN_SETTINGS_ROWS.savedValue());
    }

    @Test
    public void aRowSeenAgainAsksForNoSave() {
        assertTrue(SettingsRows.remember("ARCHIVE", "Archive"));
        assertFalse("a save is already coming", SettingsRows.remember("CLOSE_FRIENDS", "Close Friends"));
        SettingsRows.saveSeen();
        assertFalse("nothing new", SettingsRows.remember("ARCHIVE", "Archive"));
        assertFalse("no title doesn't wipe one", SettingsRows.remember("ARCHIVE", null));
        assertTrue("a new title", SettingsRows.remember("ARCHIVE", "Archivio"));
        SettingsRows.saveSeen();
        SettingsRows.reset();
        assertEquals("read back from the setting", "Archivio", SettingsRows.offered().get(0)[1]);
    }

    @Test
    public void aHiddenRowFromABackupIsOfferedByItsName() {
        SettingsRows.built(new Row(Id.ARCHIVE, "Archive"));
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE,YOUR_ACTIVITY");
        List<String[]> offered = SettingsRows.offered();
        assertEquals(2, offered.size());
        assertArrayEquals(new String[]{"ARCHIVE", "Archive"}, offered.get(0));
        assertArrayEquals(new String[]{"YOUR_ACTIVITY", "Your activity"}, offered.get(1));
    }

    @Test
    public void theSavedListIsTidied() {
        assertEquals("ARCHIVE,CLOSE_FRIENDS", SettingsRows.join(SettingsRows.parse(" CLOSE_FRIENDS,,archive,ARCHIVE, ARCHIVE ,bad name,")));
        assertTrue(SettingsRows.parse(null).isEmpty());
        assertEquals("", SettingsRows.join(SettingsRows.parse("")));
    }
}
