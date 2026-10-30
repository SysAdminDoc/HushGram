/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Hide profile tabs does to the list of tab keys Instagram builds for a profile: it drops the
 * keys the setting for that profile names (your own or someone else's), never the grid, never by
 * position, and gives Instagram's list back untouched when nothing is picked, HushGram is paused or
 * the list isn't one it knows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileTabsTest {
    private static final String GRID = "profile_media_grid";
    private static final String REELS = "profile_clips";
    private static final String REPOSTS = "profile_reposts";
    private static final String TAGGED = "profile_tagged_media_photos_of_you";

    /** Stands in for Instagram's builder of the tab list and the user whose profile it is. */
    private static final Object BUILDER = new Object();
    private static final Object USER = new Object();

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Answers the IDs the patch's readers would, or throws. */
    private static final class Ids implements ProfileTabs.Reader {
        final String profile;
        final String viewer;
        final boolean broken;

        Ids(String profile, String viewer, boolean broken) {
            this.profile = profile;
            this.viewer = viewer;
            this.broken = broken;
        }

        @Override
        public String profileId(Object user) {
            if (broken) throw new IllegalStateException("no id");
            return profile;
        }

        @Override
        public String viewerId(Object builder) {
            return viewer;
        }
    }

    private static final Ids MINE = new Ids("7", "7", false);
    private static final Ids FRIENDS = new Ids("8", "7", false);

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        Settings.HIDDEN_OWN_PROFILE_TABS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDDEN_PROFILE_TABS.resetToDefault();
        Settings.HIDDEN_OWN_PROFILE_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static List<String> all() {
        return Arrays.asList(GRID, REELS, REPOSTS, TAGGED);
    }

    private static List<String> mine(List<String> tabs) {
        return ProfileTabs.tabs(BUILDER, USER, tabs, MINE);
    }

    private static List<String> friends(List<String> tabs) {
        return ProfileTabs.tabs(BUILDER, USER, tabs, FRIENDS);
    }

    /** Nothing picked is the start, and Instagram's own list goes back as the same object. */
    @Test
    public void nothingPickedLeavesInstagramsListAlone() {
        assertEquals("", Settings.HIDDEN_PROFILE_TABS.get());
        assertEquals(ProfileTabs.SAME_AS_OTHERS, Settings.HIDDEN_OWN_PROFILE_TABS.get());
        List<String> tabs = all();
        assertSame(tabs, friends(tabs));
        assertSame(tabs, mine(tabs));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_TABS).toString(), HookStatus.missing(FamilyNames.PROFILE_TABS).isEmpty());
    }

    /** Each tab goes by its key, and the others keep Instagram's order. */
    @Test
    public void eachPickedTabGoesByItsKey() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), friends(all()));
        Settings.HIDDEN_PROFILE_TABS.save(REPOSTS);
        assertEquals(Arrays.asList(GRID, REELS, TAGGED), friends(all()));
        Settings.HIDDEN_PROFILE_TABS.save(TAGGED);
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), friends(all()));
    }

    /** Reels off for your profile only: yours loses the tab and a friend's keeps it, and the reverse. */
    @Test
    public void yourProfileAndOtherPeoplesEachHaveTheirOwnPick() {
        Settings.HIDDEN_OWN_PROFILE_TABS.save(REELS);
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), mine(all()));
        List<String> friend = all();
        assertSame(friend, friends(friend));

        Settings.HIDDEN_OWN_PROFILE_TABS.save("");
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        List<String> own = all();
        assertSame(own, mine(own));
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), friends(all()));

        Settings.HIDDEN_OWN_PROFILE_TABS.save(TAGGED);
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), mine(all()));
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), friends(all()));
    }

    /** A pick made before the split covers both until your own is saved, and editing the other keeps yours. */
    @Test
    public void theOldPickCoversYourProfileUntilYoursIsSaved() {
        Settings.HIDDEN_PROFILE_TABS.save(REPOSTS);
        assertEquals(Arrays.asList(GRID, REELS, TAGGED), mine(all()));
        assertEquals(Arrays.asList(GRID, REELS, TAGGED), friends(all()));
        assertEquals(REPOSTS, ProfileTabs.savedForOwn());

        ProfileTabs.saveOthers(Collections.singleton(TAGGED));
        assertEquals("your profile keeps what it was showing", REPOSTS, Settings.HIDDEN_OWN_PROFILE_TABS.savedValue());
        assertEquals(TAGGED, Settings.HIDDEN_PROFILE_TABS.savedValue());
        assertEquals(Arrays.asList(GRID, REELS, TAGGED), mine(all()));
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), friends(all()));

        ProfileTabs.saveOwn(Collections.<String>emptySet());
        assertEquals("", Settings.HIDDEN_OWN_PROFILE_TABS.savedValue());
        assertEquals("", ProfileTabs.savedForOwn());
        List<String> own = all();
        assertSame("showing everything is a pick of its own", own, mine(own));
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), friends(all()));
    }

    /** Saving your own first leaves other people's pick alone. */
    @Test
    public void savingYourOwnFirstLeavesTheOtherAlone() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        ProfileTabs.saveOwn(new LinkedHashSet<>(Arrays.asList(TAGGED, REPOSTS)));
        assertEquals(REPOSTS + "," + TAGGED, Settings.HIDDEN_OWN_PROFILE_TABS.savedValue());
        assertEquals(REELS, Settings.HIDDEN_PROFILE_TABS.savedValue());
        assertEquals(Arrays.asList(GRID, REELS), mine(all()));
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), friends(all()));
    }

    /** When it can't tell whose profile it is, the profile is someone else's, and nothing throws. */
    @Test
    public void aProfileNobodyCanPlaceIsSomeoneElses() {
        Settings.HIDDEN_OWN_PROFILE_TABS.save(REELS);
        Settings.HIDDEN_PROFILE_TABS.save(TAGGED);
        List<String> others = Arrays.asList(GRID, REELS, REPOSTS);

        assertEquals(others, ProfileTabs.tabs(null, USER, all(), MINE));
        assertEquals(others, ProfileTabs.tabs(BUILDER, null, all(), MINE));
        assertEquals(others, ProfileTabs.tabs(BUILDER, USER, all(), new Ids(null, "7", false)));
        assertEquals(others, ProfileTabs.tabs(BUILDER, USER, all(), new Ids("7", null, false)));
        assertEquals(others, ProfileTabs.tabs(BUILDER, USER, all(), new Ids("", "", false)));
        assertTrue("a missing ID isn't a fault", HookStatus.missing(FamilyNames.PROFILE_TABS).isEmpty());

        assertEquals(others, ProfileTabs.tabs(BUILDER, USER, all(), new Ids("7", "7", true)));
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_TABS).toString(),
                HookStatus.missing(FamilyNames.PROFILE_TABS).toString().contains("whose profile"));
        assertFalse(ProfileTabs.isOwn(BUILDER, USER, new Ids("7", "7", true)));
    }

    /** Before the patch writes the readers they answer nothing, so every profile is someone else's. */
    @Test
    public void unwrittenReadersMeanSomeoneElses() {
        assertNull(ProfileTabs.profileId(USER));
        assertNull(ProfileTabs.viewerId(BUILDER));
        Settings.HIDDEN_OWN_PROFILE_TABS.save(REELS);
        Settings.HIDDEN_PROFILE_TABS.save(TAGGED);
        assertEquals(Arrays.asList(GRID, REELS, REPOSTS), ProfileTabs.tabs(BUILDER, USER, all()));
    }

    /** All three picked leaves the grid alone, which Instagram draws without a strip. */
    @Test
    public void allThreePickedLeavesTheGrid() {
        Settings.HIDDEN_PROFILE_TABS.save(ProfileTabs.join(new LinkedHashSet<>(ProfileTabs.OFFERED)));
        assertEquals(Collections.singletonList(GRID), friends(all()));
    }

    /** Someone else's profile often has fewer tabs, and a tab it lacks is never added or missed. */
    @Test
    public void aProfileWithFewerTabsWorksToo() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS + "," + REPOSTS);
        assertEquals(Arrays.asList(GRID, TAGGED), friends(Arrays.asList(GRID, REELS, TAGGED)));
        List<String> gridOnly = Collections.singletonList(GRID);
        assertSame("nothing to drop", gridOnly, friends(gridOnly));
    }

    /** Tabs Instagram has that this doesn't offer stay where they are, and position never decides. */
    @Test
    public void otherTabsAndOrderDontMatter() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        List<String> shuffled = Arrays.asList("profile_longform", TAGGED, GRID, REELS, "profile_products");
        assertEquals(Arrays.asList("profile_longform", TAGGED, GRID, "profile_products"), friends(shuffled));
    }

    /** The grid can't be named, and a list without one isn't thinned. */
    @Test
    public void theGridIsNeverDropped() {
        Settings.HIDDEN_PROFILE_TABS.save(GRID + "," + REELS);
        assertEquals(Collections.singleton(REELS), ProfileTabs.parse(Settings.HIDDEN_PROFILE_TABS.get()));
        assertEquals(Arrays.asList(GRID, REPOSTS, TAGGED), friends(all()));
        List<String> noGrid = Arrays.asList(REELS, REPOSTS);
        assertSame(noGrid, friends(noGrid));
    }

    /** Paused, Instagram's list goes back untouched on every profile, whatever is saved. */
    @Test
    public void pausedReadsAsNothingPicked() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS + "," + REPOSTS + "," + TAGGED);
        Settings.HIDDEN_OWN_PROFILE_TABS.save(REELS);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        List<String> tabs = all();
        assertSame(tabs, friends(tabs));
        assertSame(tabs, mine(tabs));
        assertEquals("the choice is kept", REELS + "," + REPOSTS + "," + TAGGED, Settings.HIDDEN_PROFILE_TABS.savedValue());
        assertEquals("the choice is kept", REELS, Settings.HIDDEN_OWN_PROFILE_TABS.savedValue());
    }

    /** A null list and a list that won't copy come back as they were, and nothing throws. */
    @Test
    public void nothingThrowsIntoInstagram() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        assertNull(friends(null));
        assertNull(ProfileTabs.tabs(null, null, null));
        List<String> broken = new ArrayList<String>(all()) {
            @Override public boolean contains(Object o) {
                throw new IllegalStateException("no");
            }
        };
        assertSame(broken, friends(broken));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
    }

    /** A dropped tab is counted once per profile it was dropped on. */
    @Test
    public void aDroppedTabIsCounted() {
        Settings.HIDDEN_PROFILE_TABS.save(REELS);
        friends(all());
        mine(all());
        friends(Collections.singletonList(GRID));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + ProfileTabs.LEFT_OFF + " 2"));
    }

    /** Saved text keeps only the keys offered, once each, in the order the dialog lists them. */
    @Test
    public void savedTextIsTidied() {
        Set<String> parsed = ProfileTabs.parse(" " + TAGGED + ", ,profile_clips,profile_clips,nonsense," + GRID);
        assertEquals(new LinkedHashSet<>(Arrays.asList(TAGGED, REELS)), parsed);
        assertEquals(REELS + "," + TAGGED, ProfileTabs.join(parsed));
        assertEquals("", ProfileTabs.join(Collections.<String>emptySet()));
        assertTrue(ProfileTabs.parse(null).isEmpty());
        assertTrue("the mark that your profile follows others names no tab", ProfileTabs.parse(ProfileTabs.SAME_AS_OTHERS).isEmpty());
    }
}
