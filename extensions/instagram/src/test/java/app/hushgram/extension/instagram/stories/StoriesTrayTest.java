/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide suggested stories takes out of Home's stories tray, and when it hides the tray. */
@RunWith(RobolectricTestRunner.class)
public class StoriesTrayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Named like 450's reel types. */
    enum ReelType {
        USER_REEL, HIGHLIGHT_REEL, SUGGESTED_USER, SUGGESTED_USER_REEL, SUGGESTED_CREATOR_REEL, HIGHLIGHT_REWIND_REEL,
        MEMORY_REEL, MY_WEEK_REEL, END_OF_YEAR, FOLLOW_VERSARIES, BIRTHDAY_HIGHLIGHTS, PROMPT_STICKER_REEL
    }

    /** A tray item keeping its reel type in an enum field, as 449's ReelResponseItem does. */
    static final class TrayItem {
        final ReelType reelType;

        TrayItem(ReelType reelType) {
            this.reelType = reelType;
        }
    }

    @Test
    public void suggestedStoriesAreTakenOut() {
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_USER_REEL)));
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_USER)));
        assertNull(StoriesTray.filter(new TrayItem(ReelType.SUGGESTED_CREATOR_REEL)));
    }

    @Test
    public void storiesFromAccountsYouFollowStay() {
        TrayItem followed = new TrayItem(ReelType.USER_REEL);
        TrayItem highlight = new TrayItem(ReelType.HIGHLIGHT_REEL);
        assertSame(followed, StoriesTray.filter(followed));
        assertSame(highlight, StoriesTray.filter(highlight));
        assertNull(StoriesTray.filter(null));
    }

    @Test
    public void withTheSwitchOffSuggestedStoriesStay() {
        Settings.HIDE_SUGGESTED_STORIES.save(false);
        try {
            TrayItem suggested = new TrayItem(ReelType.SUGGESTED_USER_REEL);
            assertSame(suggested, StoriesTray.filter(suggested));
        } finally {
            Settings.HIDE_SUGGESTED_STORIES.save(true);
        }
    }

    /** Rewinds and recaps stay until their own switches, off to start, are turned on, each for its own kind. */
    @Test
    public void rewindsAndRecapsGoOnlyWithTheirSwitches() {
        ReelType[] recaps = {ReelType.MEMORY_REEL, ReelType.MY_WEEK_REEL, ReelType.END_OF_YEAR, ReelType.FOLLOW_VERSARIES,
                ReelType.BIRTHDAY_HIGHLIGHTS};
        TrayItem rewind = new TrayItem(ReelType.HIGHLIGHT_REWIND_REEL);
        assertFalse(Settings.HIDE_STORY_REWINDS.get());
        assertFalse(Settings.HIDE_STORY_RECAPS.get());
        assertSame(rewind, StoriesTray.filter(rewind));
        for (ReelType kind : recaps) {
            TrayItem recap = new TrayItem(kind);
            assertSame(kind.name(), recap, StoriesTray.filter(recap));
        }

        Settings.HIDE_STORY_REWINDS.save(true);
        try {
            assertNull(StoriesTray.filter(rewind));
            TrayItem memory = new TrayItem(ReelType.MEMORY_REEL);
            assertSame("a recap went with the rewinds", memory, StoriesTray.filter(memory));
        } finally {
            Settings.HIDE_STORY_REWINDS.save(false);
        }

        Settings.HIDE_STORY_RECAPS.save(true);
        try {
            for (ReelType kind : recaps) assertNull(kind.name(), StoriesTray.filter(new TrayItem(kind)));
            assertSame("a rewind went with the recaps", rewind, StoriesTray.filter(rewind));
            TrayItem story = new TrayItem(ReelType.USER_REEL);
            TrayItem prompt = new TrayItem(ReelType.PROMPT_STICKER_REEL);
            assertSame(story, StoriesTray.filter(story));
            assertSame("a story someone posted", prompt, StoriesTray.filter(prompt));
        } finally {
            Settings.HIDE_STORY_RECAPS.save(false);
        }
    }

    /** Stop loading stories and highlights drops every item, yours included, and the reels left to fetch, while it's on. */
    @Test
    public void stopLoadingStoriesDropsEveryItemAndTheReelsLeftToFetch() {
        ArrayList<Object> ids = new ArrayList<>(Arrays.asList("1", "2"));
        TrayItem story = new TrayItem(ReelType.USER_REEL);
        assertFalse(Settings.STOP_LOADING_STORIES.get());
        assertSame(story, StoriesTray.filter(story));
        assertSame(ids, StoriesTray.remaining(ids));

        Settings.STOP_LOADING_STORIES.save(true);
        try {
            for (ReelType kind : ReelType.values()) assertNull(kind.name(), StoriesTray.filter(new TrayItem(kind)));
            assertTrue(StoriesTray.remaining(ids).isEmpty());
            assertEquals("the list Instagram read is left as it was", 2, ids.size());
            assertNull(StoriesTray.remaining(null));
        } finally {
            Settings.STOP_LOADING_STORIES.save(false);
        }
        assertSame(story, StoriesTray.filter(story));
    }

    /** Some other enum a tray item also carries. */
    enum Shape { CIRCLE, CARD }

    static final class TwoEnums {
        final Shape shape = Shape.CARD;
        final ReelType reelType;

        TwoEnums(ReelType reelType) {
            this.reelType = reelType;
        }
    }

    /** Every item read is counted by its reel type, one no switch covers too, and never by the other enum. */
    @Test
    public void everyReelTypeReadIsCounted() {
        FeedFilterCounters.snapshotAndClear();
        StoriesTray.filter(new TwoEnums(ReelType.PROMPT_STICKER_REEL));
        StoriesTray.filter(new TwoEnums(ReelType.PROMPT_STICKER_REEL));
        StoriesTray.filter(new TrayItem(ReelType.USER_REEL));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(StoriesTray.KINDS_ROUTE));
        assertTrue(report, report.contains("PROMPT_STICKER_REEL 2"));
        assertTrue(report, report.contains("USER_REEL 1"));
        assertFalse(report, report.contains("CARD"));
    }

    /** The whole tray stays until its own switch, off to start, is turned on. */
    @Test
    public void theTrayStaysUntilItsSwitchIsOn() {
        assertFalse(StoriesTray.hideTray());
        Settings.HIDE_STORIES_TRAY.save(true);
        try {
            assertTrue(StoriesTray.hideTray());
        } finally {
            Settings.HIDE_STORIES_TRAY.save(false);
        }
    }

    /** #357: the floating tray stays in the feed while Keep the Stories tray in the feed or Hide the Stories tray is on. */
    @Test
    public void theTrayFloatsUntilOneOfItsSwitchesIsOn() {
        assertFalse(StoriesTray.keepTrayOutOfOverlay());
        Settings.KEEP_TRAY_IN_FEED.save(true);
        try {
            assertTrue(StoriesTray.keepTrayOutOfOverlay());
            assertFalse("keeping the tray in the feed doesn't hide its row", StoriesTray.hideTray());
        } finally {
            Settings.KEEP_TRAY_IN_FEED.save(false);
        }
        Settings.HIDE_STORIES_TRAY.save(true);
        try {
            assertTrue(StoriesTray.keepTrayOutOfOverlay());
        } finally {
            Settings.HIDE_STORIES_TRAY.save(false);
        }
        assertFalse(StoriesTray.keepTrayOutOfOverlay());
    }

    /** A tray item's owner, standing in for Instagram's User. */
    static final class Owner {
        final String id;
        final Boolean verified;

        Owner(String id, Boolean verified) {
            this.id = id;
            this.verified = verified;
        }
    }

    /** A story of [owner]'s, which {@link #badged} reads the way the patch's stubs read a ReelResponseItem. */
    static final class OwnedItem {
        final ReelType reelType;
        final Owner owner;

        OwnedItem(ReelType reelType, Owner owner) {
            this.reelType = reelType;
            this.owner = owner;
        }
    }

    private static Object badged(Object item) {
        return StoriesTray.filter(item, it -> it instanceof OwnedItem ? ((OwnedItem) it).owner : null,
                owner -> ((Owner) owner).verified, owner -> ((Owner) owner).id);
    }

    /**
     * #340: with Filter the Stories tray by verified accounts on, Only accounts that aren't
     * verified takes out the stories of verified owners and keeps the rest; Only verified accounts
     * the reverse. Off to start, every story stays.
     */
    @Test
    public void theBadgeChoiceKeepsOnlyItsOwnersStories() {
        OwnedItem brand = new OwnedItem(ReelType.USER_REEL, new Owner("1", true));
        OwnedItem friend = new OwnedItem(ReelType.USER_REEL, new Owner("2", false));
        assertFalse(Settings.FILTER_TRAY_BY_BADGE.get());
        assertEquals(TrayBadge.UNVERIFIED, Settings.TRAY_BADGE.get());
        assertSame(brand, badged(brand));
        assertSame(friend, badged(friend));

        Settings.FILTER_TRAY_BY_BADGE.save(true);
        try {
            assertNull(badged(brand));
            assertSame(friend, badged(friend));
            Settings.TRAY_BADGE.save(TrayBadge.VERIFIED);
            assertSame(brand, badged(brand));
            assertNull(badged(friend));
        } finally {
            Settings.FILTER_TRAY_BY_BADGE.save(false);
            Settings.TRAY_BADGE.resetToDefault();
        }
    }

    /**
     * Your own story stays whichever badge it has, and so does a story whose owner, badge or id
     * Instagram didn't send. Only an account's story is read: a suggested one still goes by its own
     * switch, and a highlight or a card stays.
     */
    @Test
    public void yourOwnStoryAndUnknownOwnersStay() {
        Settings.FEED_ACCOUNT.save("7");
        Settings.FILTER_TRAY_BY_BADGE.save(true);
        Settings.TRAY_BADGE.save(TrayBadge.VERIFIED);
        try {
            OwnedItem own = new OwnedItem(ReelType.USER_REEL, new Owner("7", false));
            OwnedItem noBadge = new OwnedItem(ReelType.USER_REEL, new Owner("3", null));
            OwnedItem noId = new OwnedItem(ReelType.USER_REEL, new Owner("", false));
            OwnedItem noOwner = new OwnedItem(ReelType.USER_REEL, null);
            OwnedItem highlight = new OwnedItem(ReelType.HIGHLIGHT_REEL, new Owner("4", false));
            OwnedItem prompt = new OwnedItem(ReelType.PROMPT_STICKER_REEL, new Owner("5", false));
            for (OwnedItem kept : new OwnedItem[]{own, noBadge, noId, noOwner, highlight, prompt}) {
                assertSame(kept.reelType.name(), kept, badged(kept));
            }
            assertNull("a suggested story still goes", badged(new OwnedItem(ReelType.SUGGESTED_USER_REEL, new Owner("6", true))));
        } finally {
            Settings.FILTER_TRAY_BY_BADGE.save(false);
            Settings.TRAY_BADGE.resetToDefault();
            Settings.FEED_ACCOUNT.resetToDefault();
        }
    }

    /** Paused, the switch reads off and every story stays; the choice outlives the pause. */
    @Test
    public void pausedEveryStoryStays() {
        OwnedItem brand = new OwnedItem(ReelType.USER_REEL, new Owner("1", true));
        Settings.FILTER_TRAY_BY_BADGE.save(true);
        try {
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertSame(brand, badged(brand));
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            assertNull(badged(brand));
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
            Settings.FILTER_TRAY_BY_BADGE.save(false);
        }
    }

    /** As built, before the patch writes the stubs, nothing is known about an owner and every story stays. */
    @Test
    public void unpatchedStubsKeepEveryStory() {
        assertNull(StoriesTray.trayOwner(new Object()));
        assertNull(StoriesTray.userVerified(new Object()));
        assertNull(StoriesTray.userId(new Object()));
        Settings.FILTER_TRAY_BY_BADGE.save(true);
        try {
            TrayItem story = new TrayItem(ReelType.USER_REEL);
            assertSame(story, StoriesTray.filter(story));
        } finally {
            Settings.FILTER_TRAY_BY_BADGE.save(false);
        }
    }
}
