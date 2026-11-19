/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the New message suggestions hook answers, and that only the pickers' rows lose their suggestions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NewMessageSuggestionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private Object picker;

    private static List<String> people() {
        return new ArrayList<>(Arrays.asList("ana", "ben", "cleo"));
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
        picker = new Object();
        NewMessageSuggestions.picker(picker);
    }

    @After
    public void restore() {
        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, a picker's suggestions and its empty search come back empty, the list handed in is kept, and it's counted. */
    @Test
    public void withTheSwitchOnAPickerSuggestsNobody() {
        List<String> stock = people();

        assertTrue(NewMessageSuggestions.suggested(picker, stock).isEmpty());
        assertTrue(NewMessageSuggestions.results(picker, "", stock).isEmpty());
        assertTrue(NewMessageSuggestions.results(picker, null, stock).isEmpty());

        assertEquals(people(), stock);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(NewMessageSuggestions.ROUTE));
        assertTrue(report, report.contains("3 removed"));
        assertTrue(report, report.contains(NewMessageSuggestions.SECTION));
    }

    /** Anything typed lists its matches as Instagram found them. */
    @Test
    public void searchStillListsItsMatches() {
        List<String> found = people();
        assertSame(found, NewMessageSuggestions.results(picker, "a", found));
        assertSame(found, NewMessageSuggestions.results(picker, " ", found));
    }

    /** The same rows anywhere else, like the share sheet, were never marked and keep their suggestions. */
    @Test
    public void unmarkedRowsKeepTheirSuggestions() {
        List<String> stock = people();
        Object shareSheet = new Object();
        assertSame(stock, NewMessageSuggestions.suggested(shareSheet, stock));
        assertSame(stock, NewMessageSuggestions.results(shareSheet, "", stock));
        assertSame(stock, NewMessageSuggestions.suggested(null, stock));
        NewMessageSuggestions.picker(null);

        List<String> empty = Collections.emptyList();
        assertSame(empty, NewMessageSuggestions.suggested(picker, empty));
        assertNull(NewMessageSuggestions.suggested(picker, null));
        assertNull(NewMessageSuggestions.results(picker, "", null));

        String report = FeedFilterCounters.report().toString();
        assertFalse(report, report.contains(NewMessageSuggestions.SECTION));
    }

    /** The switch starts off; off, paused or before the settings are read, the picker keeps its suggestions. */
    @Test
    public void offPausedAndUnreadyKeepTheSuggestions() {
        List<String> stock = people();
        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.defaultValue);
        assertSame(stock, NewMessageSuggestions.suggested(picker, stock));
        assertSame(stock, NewMessageSuggestions.results(picker, "", stock));

        Settings.HIDE_NEW_MESSAGE_SUGGESTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, NewMessageSuggestions.suggested(picker, stock));
        assertSame(stock, NewMessageSuggestions.results(picker, "", stock));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertSame(stock, NewMessageSuggestions.suggested(picker, stock));
            assertSame(stock, NewMessageSuggestions.results(picker, "", stock));
        });

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().toString().contains(FamilyNames.NEW_MESSAGE));
        assertTrue(NewMessageSuggestions.suggested(picker, stock).isEmpty());
    }

    /** A switch that throws keeps the suggestions and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheSuggestionsAndIsReported() {
        List<String> stock = people();
        assertSame(stock, NewMessageSuggestions.suggested(picker, stock, THROWS));
        assertSame(stock, NewMessageSuggestions.results(picker, "", stock, THROWS));

        String missing = HookStatus.missing(FamilyNames.NEW_MESSAGE).toString();
        assertTrue(missing, missing.contains("'" + NewMessageSuggestions.SECTION + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
