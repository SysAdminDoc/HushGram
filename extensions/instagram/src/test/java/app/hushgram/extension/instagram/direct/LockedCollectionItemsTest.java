/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Posts saved into a locked collection stay out of Saved's other grids while the lock is shut, and
 * come back once it's open (#678). A collection's own page, and an unlocked one sharing a post, keep
 * it. Ids are learned from the collections Instagram reads and remembered for the ones on a list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LockedCollectionItemsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = MessagesLockTest.ME;
    private static final String OTHER = "5678";

    private static final String TRIPS = "1790001";
    private static final String FOOD = "1790002";

    @Before
    public void start() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(ME);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        ChatAccounts.resetForTests();
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        Settings.LOCKED_COLLECTION_IDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static int leaveOut(List<?> ids, String shown) {
        return LockedCollectionItems.leaveOut(() -> ids, () -> shown);
    }

    private static String line() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.MESSAGES_LOCK)) return line;
        }
        return "";
    }

    @Test
    public void aLockedCollectionsPostsLeaveAllAndPostsUntilItsUnlocked() {
        LockedCollectionItems.learned(TRIPS, "Trips");
        LockedCollectionItems.learned(FOOD, "Food");
        assertTrue(CollectionLocks.add(ME, "Trips"));

        assertEquals("a post only in Trips", 1, leaveOut(Collections.singletonList(TRIPS), null));
        assertEquals("one in Trips and Food", 1, leaveOut(Arrays.asList(FOOD, TRIPS), null));
        assertEquals("one only in Food", 0, leaveOut(Collections.singletonList(FOOD), null));
        assertEquals("one in no collection", 0, leaveOut(Collections.emptyList(), null));
        assertEquals("one Instagram gave no collections", 0, leaveOut(null, null));
        assertTrue(line(), line().contains(LockedCollectionItems.LEFT_OUT + " 2"));

        MessagesLock.openForTests();
        assertEquals("unlocked, it's back", 0, leaveOut(Collections.singletonList(TRIPS), null));
        MessagesLock.relock(true);
        assertEquals("locked again, it goes again", 1, leaveOut(Collections.singletonList(TRIPS), null));
    }

    @Test
    public void aCollectionsOwnGridKeepsAllOfItself() {
        LockedCollectionItems.learned(TRIPS, "Trips");
        LockedCollectionItems.learned(FOOD, "Food");
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals("Trips' own page", 0, leaveOut(Collections.singletonList(TRIPS), TRIPS));
        assertEquals("Food's page keeps a post it shares with Trips", 0, leaveOut(Arrays.asList(TRIPS, FOOD), FOOD));
        assertEquals("All, which is no collection of the post's", 1, leaveOut(Arrays.asList(TRIPS, FOOD), "ALL_MEDIA_AUTO_COLLECTION"));
    }

    @Test
    public void nothingGoesWithNothingLockedOrForAnotherAccount() {
        LockedCollectionItems.learned(TRIPS, "Trips");
        assertEquals("nothing on any list", 0, leaveOut(Collections.singletonList(TRIPS), null));
        assertTrue(CollectionLocks.add(OTHER, "Trips"));
        assertEquals("Trips is locked on another account", 0, leaveOut(Collections.singletonList(TRIPS), null));
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals(1, leaveOut(Collections.singletonList(TRIPS), null));
    }

    @Test
    public void aPostWhoseCollectionIsntKnownYetShows() {
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals("Trips' id isn't known yet", 0, leaveOut(Collections.singletonList(TRIPS), null));
        LockedCollectionItems.read(null);
        LockedCollectionItems.learned(TRIPS, "  Trips  ");
        assertEquals("known once Instagram reads it", 1, leaveOut(Collections.singletonList(TRIPS), null));
        LockedCollectionItems.learned(null, "Food");
        LockedCollectionItems.learned(FOOD, "...");
        assertNull(LockedCollectionItems.nameFor(FOOD));
    }

    @Test
    public void aLockedCollectionsIdIsRememberedForTheNextStart() {
        LockedCollectionItems.learned(FOOD, "Food");
        assertEquals("a collection on no list isn't remembered", "", Settings.LOCKED_COLLECTION_IDS.get());
        LockedCollectionItems.learned(TRIPS, "Trips");
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals("locking it remembers the id it was read with", TRIPS + "\tTrips", Settings.LOCKED_COLLECTION_IDS.get());

        // A fresh start knows nothing read, only what was remembered.
        LockedCollectionItems.resetForTests();
        assertEquals("Trips", LockedCollectionItems.nameFor(TRIPS));
        assertNull(LockedCollectionItems.nameFor(FOOD));
        assertEquals(1, leaveOut(Collections.singletonList(TRIPS), null));

        LockedCollectionItems.learned(FOOD, "Food");
        assertTrue(CollectionLocks.add(ME, "Food"));
        LockedCollectionItems.learned(FOOD, "Food");
        assertEquals(TRIPS + "\tTrips\n" + FOOD + "\tFood", Settings.LOCKED_COLLECTION_IDS.get());
    }

    @Test
    public void aCollectionReadOnceItsListedIsRememberedToo() {
        assertTrue(CollectionLocks.add(ME, "Trips"));
        LockedCollectionItems.learned(TRIPS, "Trips");
        assertEquals(TRIPS + "\tTrips", Settings.LOCKED_COLLECTION_IDS.get());
    }

    @Test
    public void onlyTheNewestIdsAreKept() {
        Map<String, String> ids = new LinkedHashMap<>();
        for (int i = 0; i < LockedCollectionItems.REMEMBERED + 5; i++) ids.put("id" + i, "name\t" + i);
        String text = LockedCollectionItems.format(ids);
        Map<String, String> back = LockedCollectionItems.parse(text);
        assertEquals(LockedCollectionItems.REMEMBERED, back.size());
        assertEquals("the oldest went first", "id5", back.keySet().iterator().next());
        assertEquals("a tab in a name doesn't split the line", "name 5", back.get("id5"));
        assertTrue(LockedCollectionItems.parse("\nno tab\n\tno id\nid\t\n").isEmpty());
    }

    @Test
    public void anythingUnexpectedShowsThePost() {
        LockedCollectionItems.learned(TRIPS, "Trips");
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals(0, LockedCollectionItems.leaveOut(() -> {
            throw new IllegalStateException("Instagram changed");
        }, () -> null));
        assertFalse(line(), line().contains(LockedCollectionItems.LEFT_OUT));
        // Instagram's own, unpatched here, give nothing, so the post shows.
        assertEquals(0, LockedCollectionItems.leaveOut(new Object(), new Object()));
        assertEquals(0, LockedCollectionItems.leaveOut((Object) null, null));
        List<Object> odd = new ArrayList<>(Arrays.asList(null, 17900010L));
        assertEquals("ids that aren't strings are read as text", 0, leaveOut(odd, null));
    }
}
