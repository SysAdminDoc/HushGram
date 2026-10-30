/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The caught up card's title on 451 (#104): the end of feed card shows "Suggested for you" itself,
 * over the section its group set has open. With Hide suggested posts on and nothing left in that
 * section the title goes and the card keeps its subtitle and Older posts link. A card with no
 * section, the switch off, a pause or a read that fails keeps Instagram's title.
 */
@RunWith(RobolectricTestRunner.class)
public class CaughtUpTitleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TITLE = "Suggested for you";

    /** A section of the group set, holding [items] as the patch's stub would read them. */
    static final class Section {
        final List<?> items;

        Section(List<?> items) {
            this.items = items;
        }
    }

    /** An end of feed card, its open section as the patch's stub would walk to it. */
    static final class Card {
        final Section section;

        Card(Section section) {
            this.section = section;
        }
    }

    @Before
    public void readCards() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.save(true);
        FeedSuggestions.caughtUpSections = card -> ((Card) card).section;
        FeedSuggestions.sections = section -> ((Section) section).items;
        FeedSuggestions.tookOut = true;
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedSuggestions.caughtUpSections = FeedSuggestions::caughtUpSection;
        FeedSuggestions.sections = FeedSuggestions::sectionItems;
        FeedSuggestions.tookOut = false;
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String title(List<?> items) {
        return FeedSuggestions.caughtUpTitle(TITLE, new Card(new Section(items)));
    }

    /** Every suggestion taken out, nothing under the card: no title, and Diagnostics counts both steps. */
    @Test
    public void anEmptySectionLosesTheCardsTitle() {
        assertEquals("", title(Collections.emptyList()));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSuggestions.CAUGHT_UP_CHECKED));
        assertTrue(report, report.contains(FeedSuggestions.SECTION_TITLE_DROPPED));
    }

    /** A post still under the card keeps its title, the same decision as the header's. */
    @Test
    public void aSectionThatShowsSomethingKeepsTheTitle() {
        assertEquals(TITLE, title(Collections.singletonList(new SuggestedSectionTitleTest.Item(SuggestedSectionTitleTest.Kind.MEDIA))));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSuggestions.SECTION_KEPT_SHOWS));
    }

    /** A card whose group set has no open section, or a build where the patch couldn't fill the stub, keeps its title. */
    @Test
    public void aCardWithoutASectionKeepsItsTitle() {
        assertEquals(TITLE, FeedSuggestions.caughtUpTitle(TITLE, new Card(null)));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSuggestions.CAUGHT_UP_KEPT_NO_SECTION));

        FeedSuggestions.caughtUpSections = FeedSuggestions::caughtUpSection;
        assertEquals(TITLE, FeedSuggestions.caughtUpTitle(TITLE, new Object()));
    }

    /** Off, paused or asked before the settings are read, the card's title is Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepTheTitle() {
        List<?> empty = Collections.emptyList();
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        assertEquals(TITLE, title(empty));

        Settings.HIDE_SUGGESTED_POSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(TITLE, title(empty));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(TITLE, title(empty)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals(TITLE, title(empty)));

        assertEquals("on again, the next bind drops it", "", title(empty));
    }

    /** No title, an empty one or no card passes through untouched, and isn't counted. */
    @Test
    public void nothingToDecideIsPassedThrough() {
        assertNull(FeedSuggestions.caughtUpTitle(null, new Card(new Section(Collections.emptyList()))));
        assertEquals("", FeedSuggestions.caughtUpTitle("", new Card(new Section(Collections.emptyList()))));
        assertEquals(TITLE, FeedSuggestions.caughtUpTitle(TITLE, null));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, !report.contains(FeedSuggestions.CAUGHT_UP_CHECKED));
    }

    /** A walk that throws, a card of another class for one, keeps the title and says the hook threw. */
    @Test
    public void aThrowingWalkKeepsTheTitleAndIsReported() {
        FeedSuggestions.caughtUpSections = card -> {
            throw new ClassCastException("not a card");
        };
        assertEquals(TITLE, title(Collections.emptyList()));
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("caught up card title"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
    }
}
