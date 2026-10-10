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

/** What the inbox suggestions hook answers, and that it hands back Instagram's own list whenever it can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class InboxSuggestionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's unit model, a group of accounts with a name. Not public, as Instagram's isn't. */
    static final class Unit {
        private final Object name;

        Unit(Object name) {
            this.name = name;
        }

        public Object getName() {
            if (name instanceof RuntimeException) throw (RuntimeException) name;
            return name;
        }
    }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static List<Unit> inbox() {
        return new ArrayList<>(Arrays.asList(
                new Unit(InboxSuggestions.ACCOUNTS_TO_FOLLOW), new Unit("pending_follow_requests")));
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_INBOX_SUGGESTIONS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_INBOX_SUGGESTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, a list led by Accounts to follow is answered with an empty one, Instagram's own list is untouched, and it's counted. */
    @Test
    public void withTheSwitchOnAccountsToFollowAreLeftOut() {
        List<Unit> stock = inbox();

        List<?> kept = InboxSuggestions.units(stock);

        assertTrue(kept.isEmpty());
        assertEquals(2, stock.size());
        assertEquals(InboxSuggestions.ACCOUNTS_TO_FOLLOW, stock.get(0).getName());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(InboxSuggestions.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(InboxSuggestions.SECTION));
    }

    /** The switch starts off, and off Instagram's own list goes through, while the hook still counts that it ran. */
    @Test
    public void offToStartAndOffKeepTheSection() {
        Settings.HIDE_INBOX_SUGGESTIONS.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.HIDE_INBOX_SUGGESTIONS.defaultValue);
        List<Unit> stock = inbox();
        assertSame(stock, InboxSuggestions.units(stock));

        Settings.HIDE_INBOX_SUGGESTIONS.save(false);
        assertSame(stock, InboxSuggestions.units(stock));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().toString().contains(FamilyNames.INBOX_SUGGESTIONS));
    }

    /** Paused, or asked before the settings are read, the section stays. */
    @Test
    public void offPausedAndUnreadyKeepTheSection() {
        List<Unit> stock = inbox();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, InboxSuggestions.units(stock));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(stock, InboxSuggestions.units(stock)));

        assertTrue(InboxSuggestions.units(stock).isEmpty());
    }

    /**
     * A list led by another unit, like the people who follow you or follow requests, or by an item
     * without a name, or no list at all, goes through as it came.
     */
    @Test
    public void aListLedByAnotherUnitGoesThroughAsItCame() {
        List<Unit> followers = Arrays.asList(new Unit("people_who_follow_you"), new Unit("pending_follow_requests"));
        assertSame(followers, InboxSuggestions.units(followers));
        List<Unit> requests = Collections.singletonList(new Unit("pending_follow_requests"));
        assertSame(requests, InboxSuggestions.units(requests));
        List<Object> nameless = Arrays.asList("suggested_accounts_to_follow", new Unit(7), null);
        assertSame(nameless, InboxSuggestions.units(nameless));
        List<Unit> later = Arrays.asList(new Unit("people_who_follow_you"), new Unit(InboxSuggestions.ACCOUNTS_TO_FOLLOW));
        assertSame(later, InboxSuggestions.units(later));
        List<Unit> empty = Collections.emptyList();
        assertSame(empty, InboxSuggestions.units(empty));
        assertNull(InboxSuggestions.units(null));

        String report = FeedFilterCounters.report().toString();
        assertFalse(report, report.contains(InboxSuggestions.SECTION));
    }

    /** A switch that throws keeps the section and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheSectionAndIsReported() {
        List<Unit> stock = inbox();
        assertSame(stock, InboxSuggestions.units(stock, THROWS));

        String missing = HookStatus.missing(FamilyNames.INBOX_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + InboxSuggestions.SECTION + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** A unit whose name can't be read keeps the section and says the hook threw. */
    @Test
    public void aNameThatThrowsKeepsTheSectionAndIsReported() {
        List<Unit> stock = Collections.singletonList(new Unit(new IllegalStateException("no name")));
        assertSame(stock, InboxSuggestions.units(stock));

        String missing = HookStatus.missing(FamilyNames.INBOX_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + InboxSuggestions.SECTION + "'"));
    }
}
