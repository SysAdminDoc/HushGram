/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.reels.FeedReels;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The title of the header after Home's end of feed card (#104): with Hide suggested posts on and
 * nothing left in its section it goes, so the row keeps only its Older posts link, and anything that
 * shows under it, the switch off, a pause or a section that can't be read keeps Instagram's title.
 */
@RunWith(RobolectricTestRunner.class)
public class SuggestedSectionTitleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TITLE = "Suggested for you";

    /** A few of Instagram 450's feed item kinds. */
    enum Kind { MEDIA, CLIPS_NETEGO, SUGGESTED_USERS, EXPLORE_STORY, END_OF_FEED_DEMARCATOR }

    /** A feed item with its kind as an enum field, as the section's list holds them. */
    static final class Item {
        Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** A section of the group set, holding [items] as the patch's stub would read them. */
    static final class Section {
        final List<?> items;

        Section(List<?> items) {
            this.items = items;
        }
    }

    @Before
    public void readSections() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_POSTS.save(true);
        FeedSuggestions.sections = section -> ((Section) section).items;
        FeedSuggestions.tookOut = true;
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedSuggestions.sections = FeedSuggestions::sectionItems;
        FeedSuggestions.tookOut = false;
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        Settings.HIDE_FEED_REELS.resetToDefault();
        FeedReels.tookOut = false;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String title(List<?> items) {
        return FeedSuggestions.sectionTitle(TITLE, new Section(items));
    }

    /** Every suggestion taken out, nothing under the header: no title, and Diagnostics counts it. */
    @Test
    public void anEmptySectionLosesItsTitle() {
        assertEquals("", title(Collections.emptyList()));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSuggestions.SECTION_TITLE_DROPPED));
    }

    /** An empty section while the switch has taken nothing out of Home is empty for another reason, so it keeps its title. */
    @Test
    public void anEmptySectionKeepsItsTitleUntilTheSwitchTookSomethingOut() {
        FeedSuggestions.tookOut = false;
        assertEquals(TITLE, title(Collections.emptyList()));
        FeedSuggestions.tookOut = true;
        assertEquals("", title(Collections.emptyList()));
    }

    /** Instagram moves an end of feed card that opens the section above the header, so it doesn't count. */
    @Test
    public void aSectionHoldingOnlyAnEndCardLosesItsTitle() {
        assertEquals("", title(Collections.singletonList(new Item(Kind.END_OF_FEED_DEMARCATOR))));
    }

    /** A row of suggested reels another switch keeps, or a post, still shows under the header, so it keeps its title. */
    @Test
    public void aSectionWithSomethingInItKeepsItsTitle() {
        assertEquals(TITLE, title(Collections.singletonList(new Item(Kind.CLIPS_NETEGO))));
        assertEquals(TITLE, title(Arrays.asList(new Item(Kind.END_OF_FEED_DEMARCATOR), new Item(Kind.MEDIA))));
        assertFalse(String.join("\n", HookStatus.report()).contains(FeedSuggestions.SECTION_TITLE_DROPPED));
    }

    /** Off, paused or asked before the settings are read, the header is Instagram's. */
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

    /** A build where the patch couldn't fill the stub reads no section, and the title stays. */
    @Test
    public void aSectionThatCantBeReadKeepsItsTitle() {
        FeedSuggestions.sections = FeedSuggestions::sectionItems;
        assertEquals(TITLE, FeedSuggestions.sectionTitle(TITLE, new Object()));
        FeedSuggestions.sections = section -> null;
        assertEquals(TITLE, FeedSuggestions.sectionTitle(TITLE, new Object()));
    }

    /** No title or no section passes through untouched. */
    @Test
    public void nothingToDecideIsPassedThrough() {
        assertNull(FeedSuggestions.sectionTitle(null, new Section(Collections.emptyList())));
        assertEquals(TITLE, FeedSuggestions.sectionTitle(TITLE, null));
    }

    /** A read that throws, a section of another class for one, keeps the title and says the hook threw. */
    @Test
    public void aThrowingReadKeepsTheTitleAndIsReported() {
        FeedSuggestions.sections = section -> {
            throw new ClassCastException("not a section");
        };
        assertEquals(TITLE, title(Collections.emptyList()));
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("suggested section title"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
    }

    /** Hide Reels in the feed emptied the section: the strip is still in its list, or gone, and either way the title goes. */
    @Test
    public void aSectionOnlyHideReelsEmptiedLosesItsTitle() {
        FeedSuggestions.tookOut = false;
        Settings.HIDE_FEED_REELS.save(true);
        assertEquals("", title(Arrays.asList(new Item(Kind.CLIPS_NETEGO), new Item(Kind.END_OF_FEED_DEMARCATOR))));

        FeedReels.tookOut = true;
        assertEquals("", title(Collections.emptyList()));
    }

    /** Both switches on, a suggestion and a strip left in the list, nothing shows. */
    @Test
    public void unitsTheSwitchesHideDontKeepTheTitle() {
        Settings.HIDE_FEED_REELS.save(true);
        assertEquals("", title(Arrays.asList(new Item(Kind.EXPLORE_STORY), new Item(Kind.SUGGESTED_USERS),
                new Item(Kind.CLIPS_NETEGO))));
    }

    /** A post among the hidden units still shows, so the title stays. */
    @Test
    public void aPostAmongHiddenUnitsKeepsTheTitle() {
        Settings.HIDE_FEED_REELS.save(true);
        assertEquals(TITLE, title(Arrays.asList(new Item(Kind.CLIPS_NETEGO), new Item(Kind.MEDIA))));
    }

    /** The strip with Hide Reels off shows, even though Hide suggested posts is on. */
    @Test
    public void aStripNoSwitchHidesKeepsTheTitle() {
        Settings.HIDE_FEED_REELS.save(false);
        assertEquals(TITLE, title(Collections.singletonList(new Item(Kind.CLIPS_NETEGO))));
    }

    /** Hide suggested posts off: nothing of ours is hidden, so a suggestion in the list shows and Instagram's title stays. */
    @Test
    public void withTheSwitchOffNothingIsDecided() {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.HIDE_FEED_REELS.save(true);
        assertEquals(TITLE, title(Collections.singletonList(new Item(Kind.CLIPS_NETEGO))));
        assertEquals(TITLE, title(Collections.emptyList()));
    }

    /** Every call says how it went, and the kinds the section held are counted, so a report tells which case a phone hit. */
    @Test
    public void everyCallIsCountedWithWhyAndTheKindsAreListed() {
        title(Collections.singletonList(new Item(Kind.MEDIA)));
        FeedSuggestions.tookOut = false;
        title(Collections.emptyList());
        title(Collections.singletonList(new Item(Kind.END_OF_FEED_DEMARCATOR)));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FeedSuggestions.SECTION_CHECKED));
        assertTrue(report, report.contains(FeedSuggestions.SECTION_KEPT_SHOWS));
        assertTrue(report, report.contains(FeedSuggestions.SECTION_KEPT_NOTHING_TAKEN));
        assertTrue(report, report.contains(FeedSuggestions.SECTION_TITLE_DROPPED));
        String kinds = String.join("\n", app.hushgram.extension.shared.diagnostics.FeedFilterCounters.report());
        assertTrue(kinds, kinds.contains(FeedSuggestions.SECTION_ROUTE) && kinds.contains("MEDIA"));
    }
}
