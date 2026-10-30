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

/** What the Messages search bar hook answers, alone and beside the notes row hook, and that it fails open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class InboxSearchBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for the inbox's section enum, 452's order: the header, the search bar, the notes row, the chats. */
    enum Section { INBOX_HEADER, SEARCH_BAR, TRAY, THREADS }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static Section[] inbox() {
        return new Section[] {Section.INBOX_HEADER, Section.SEARCH_BAR, Section.TRAY, Section.THREADS};
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_INBOX_SEARCH.save(true);
        Settings.HIDE_NOTES_ROW.resetToDefault();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_INBOX_SEARCH.resetToDefault();
        Settings.HIDE_NOTES_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, the search bar is left out of a copy of the same array type, Instagram's own array is untouched, and it's counted. */
    @Test
    public void withTheSwitchOnTheSearchBarIsLeftOut() {
        Section[] stock = inbox();

        Object[] kept = InboxSearchBar.sections(stock);

        assertEquals(Section[].class, kept.getClass());
        assertArrayEquals(new Section[] {Section.INBOX_HEADER, Section.TRAY, Section.THREADS}, kept);
        assertArrayEquals(inbox(), stock);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(InboxSearchBar.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(InboxSearchBar.ROW));
    }

    /** The switch starts off, and off Instagram's own array goes through, while the hook still counts that it ran. */
    @Test
    public void offToStartAndOffKeepTheSearchBar() {
        Settings.HIDE_INBOX_SEARCH.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.HIDE_INBOX_SEARCH.defaultValue);
        Section[] stock = inbox();
        assertSame(stock, InboxSearchBar.sections(stock));

        Settings.HIDE_INBOX_SEARCH.save(false);
        assertSame(stock, InboxSearchBar.sections(stock));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.INBOX_SEARCH));
    }

    /** Paused, or asked before the settings are read, the search bar stays. */
    @Test
    public void offPausedAndUnreadyKeepTheSearchBar() {
        Section[] stock = inbox();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, InboxSearchBar.sections(stock));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(stock, InboxSearchBar.sections(stock)));

        assertEquals(3, InboxSearchBar.sections(stock).length);
    }

    /** Each switch takes only its own section, and with both on, both go whichever hook asks first. */
    @Test
    public void eachSwitchTakesOnlyItsOwnSectionAndBothTogetherTakeBoth() {
        Section[] stock = inbox();
        assertArrayEquals(new Section[] {Section.INBOX_HEADER, Section.TRAY, Section.THREADS}, NotesRow.sections(InboxSearchBar.sections(stock)));

        Settings.HIDE_INBOX_SEARCH.save(false);
        Settings.HIDE_NOTES_ROW.save(true);
        assertArrayEquals(new Section[] {Section.INBOX_HEADER, Section.SEARCH_BAR, Section.THREADS}, InboxSearchBar.sections(NotesRow.sections(stock)));

        Settings.HIDE_INBOX_SEARCH.save(true);
        Section[] both = {Section.INBOX_HEADER, Section.THREADS};
        assertArrayEquals(both, NotesRow.sections(InboxSearchBar.sections(stock)));
        assertArrayEquals(both, InboxSearchBar.sections(NotesRow.sections(stock)));
        assertEquals(Section[].class, InboxSearchBar.sections(NotesRow.sections(stock)).getClass());
        assertArrayEquals(inbox(), stock);
    }

    /** A list without the search bar, a name that isn't an enum's, or no list at all, goes through as it came. */
    @Test
    public void aListWithoutTheSearchBarGoesThroughAsItCame() {
        Section[] noSearch = {Section.TRAY, Section.THREADS};
        assertSame(noSearch, InboxSearchBar.sections(noSearch));
        Object[] names = {"INBOX_HEADER", InboxSearchBar.SEARCH, "THREADS"};
        assertSame(names, InboxSearchBar.sections(names));
        Section[] empty = {};
        assertSame(empty, InboxSearchBar.sections(empty));
        assertNull(InboxSearchBar.sections(null));

        String report = FeedFilterCounters.report().toString();
        assertFalse(report, report.contains(InboxSearchBar.ROW));
    }

    /** A switch that throws keeps the search bar and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheSearchBarAndIsReported() {
        Section[] stock = inbox();
        assertSame(stock, InboxSearchBar.sections(stock, THROWS));

        String missing = HookStatus.missing(FamilyNames.INBOX_SEARCH).toString();
        assertTrue(missing, missing.contains("'" + InboxSearchBar.ROW + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
