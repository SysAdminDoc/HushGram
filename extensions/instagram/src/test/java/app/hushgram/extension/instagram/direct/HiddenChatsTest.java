/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.instagram.model.direct.DirectMessageSearchMessage;
import com.instagram.model.direct.DirectMessageSearchThread;
import com.instagram.model.direct.DirectShareTarget;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What hiding single chats leaves out of the inbox and the shade, and when it leaves everything to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenChatsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ALICE = "340282366841710300949128";
    private static final String BOB = "340282366841710300949999";

    /** A thread summary standing in for Instagram's: the id is all the extension reads from it. */
    private static final class Summary {
        final String id;

        Summary(String id) {
            this.id = id;
        }
    }

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        HiddenChats.reader = summary -> ((Summary) summary).id;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.LOCK_MESSAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void aHiddenChatLeavesTheInboxAndTheOthersStay() {
        HiddenChats.add(ALICE, "Alice");
        ArrayList<Object> inbox = list(ALICE, BOB, "7");

        ArrayList<Object> shown = HiddenChats.filter(inbox);

        assertEquals(Arrays.asList(BOB, "7"), ids(shown));
        assertEquals("Instagram's own list is untouched", Arrays.asList(ALICE, BOB, "7"), ids(inbox));
        assertNotSame(inbox, shown);
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of the inbox 1"));
    }

    @Test
    public void anEmptyListChangesNothing() {
        ArrayList<Object> inbox = list(ALICE, BOB);

        assertSame(inbox, HiddenChats.filter(inbox));
        assertNull(HiddenChats.filter(null));
        ArrayList<Object> empty = new ArrayList<>();
        HiddenChats.add(ALICE, "Alice");
        assertSame(empty, HiddenChats.filter(empty));
        assertEquals("a list with no hidden chat in it", Arrays.asList(BOB, "7"), ids(HiddenChats.filter(list(BOB, "7"))));
    }

    @Test
    public void showingAChatAgainBringsItBack() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.add(BOB, "Bob");
        assertEquals(Arrays.asList("7"), ids(HiddenChats.filter(list(ALICE, BOB, "7"))));

        HiddenChats.remove(ALICE);

        assertEquals(Arrays.asList(ALICE, "7"), ids(HiddenChats.filter(list(ALICE, BOB, "7"))));
        HiddenChats.remove(BOB);
        ArrayList<Object> inbox = list(ALICE, BOB);
        assertSame(inbox, HiddenChats.filter(inbox));
    }

    @Test
    public void aSummaryWhoseIdCantBeReadStaysInTheInbox() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.reader = summary -> {
            String id = ((Summary) summary).id;
            if (id.equals("bad")) throw new IllegalStateException("no key");
            return id.equals("none") ? null : id;
        };

        ArrayList<Object> shown = HiddenChats.filter(list("bad", ALICE, "none", BOB));

        assertEquals(Arrays.asList("bad", "none", BOB), ids(shown));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        // The unpatched bridge answers null for everything, so nothing is hidden.
        HiddenChats.resetForTests();
        ArrayList<Object> inbox = list(ALICE);
        assertEquals(1, HiddenChats.filter(inbox).size());
    }

    @Test
    public void aHiddenChatLeavesTheInboxSearchAndPeopleAndOtherChatsStay() {
        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        HiddenChats.add(ALICE, "Alice");
        DirectShareTarget alice = new DirectShareTarget(ALICE);
        DirectShareTarget bob = new DirectShareTarget(BOB);
        DirectShareTarget person = new DirectShareTarget(null);
        List<Object> results = new ArrayList<>(Arrays.asList(bob, alice, person, "something else"));

        List<Object> shown = HiddenChats.searchResults(results);

        assertEquals(Arrays.asList(bob, person, "something else"), shown);
        assertEquals("Instagram's own list is untouched", 4, results.size());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of search 1"));
    }

    @Test
    public void searchResultsWithNothingHiddenComeBackAsTheyAre() {
        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        List<Object> results = new ArrayList<>(Arrays.asList(new DirectShareTarget(ALICE), new DirectShareTarget(BOB)));

        assertSame("no hidden chat", results, HiddenChats.searchResults(results));
        HiddenChats.add(ALICE, "Alice");
        List<Object> other = new ArrayList<>(Arrays.asList(new DirectShareTarget(BOB)));
        assertSame("a list with no hidden chat in it", other, HiddenChats.searchResults(other));
        assertNull(HiddenChats.searchResults(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, HiddenChats.searchResults(empty));
        HiddenChats.remove(ALICE);
        assertSame("shown again", results, HiddenChats.searchResults(results));
    }

    /** Stands in for Instagram's wrapper of a recent search: a chat in a field, or something else. */
    private static final class RecentEntry {
        final DirectShareTarget chat;
        final String other;

        RecentEntry(DirectShareTarget chat, String other) {
            this.chat = chat;
            this.other = other;
        }
    }

    @Test
    public void aHiddenChatLeavesTheRecentSearchesAndPeopleAndOtherChatsStay() {
        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        HiddenChats.add(ALICE, "Alice");
        RecentEntry alice = new RecentEntry(new DirectShareTarget(ALICE), null);
        RecentEntry bob = new RecentEntry(new DirectShareTarget(BOB), null);
        RecentEntry person = new RecentEntry(null, "a person");
        DirectShareTarget bareAlice = new DirectShareTarget(ALICE);
        DirectShareTarget bareBob = new DirectShareTarget(BOB);
        List<Object> recents = new ArrayList<>(Arrays.asList(bob, alice, person, bareAlice, bareBob, "something else"));

        List<Object> shown = HiddenChats.recents(recents);

        assertEquals(Arrays.asList(bob, person, bareBob, "something else"), shown);
        assertEquals("Instagram's own list is untouched", 6, recents.size());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of recent searches 1"));
    }

    @Test
    public void recentSearchesWithNothingHiddenComeBackAsTheyAre() {
        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        List<Object> recents = new ArrayList<>(Arrays.asList(new RecentEntry(new DirectShareTarget(ALICE), null), new DirectShareTarget(BOB)));

        assertSame("no hidden chat", recents, HiddenChats.recents(recents));
        HiddenChats.add(ALICE, "Alice");
        List<Object> other = new ArrayList<>(Arrays.asList(new DirectShareTarget(BOB), new RecentEntry(null, "x")));
        assertSame("a list with no hidden chat in it", other, HiddenChats.recents(other));
        assertNull(HiddenChats.recents(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, HiddenChats.recents(empty));
        assertEquals(1, HiddenChats.recents(recents).size());
        HiddenChats.remove(ALICE);
        assertSame("shown again", recents, HiddenChats.recents(recents));
    }

    @Test
    public void aRecentSearchThatCantBeReadStaysAndPausedShowsEverything() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.searchReader = result -> {
            throw new IllegalStateException("no key");
        };
        List<Object> recents = new ArrayList<>(Arrays.asList(new RecentEntry(new DirectShareTarget(ALICE), null), new DirectShareTarget(ALICE)));
        assertSame("nothing could be read", recents, HiddenChats.recents(recents));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        // The unpatched bridge answers null for everything, so nothing is hidden.
        HiddenChats.resetForTests();
        assertSame(recents, HiddenChats.recents(recents));

        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame(reason.name(), recents, HiddenChats.recents(recents));

            PauseForTests.resume();
            assertTrue(reason.name(), HiddenChats.recents(recents).isEmpty());
        }
    }

    @Test
    public void messagesSaidInAHiddenChatAreLeftOutOfSearchHits() {
        HiddenChats.add(ALICE, "Alice");
        DirectMessageSearchThread inAlice = new DirectMessageSearchThread(ALICE);
        DirectMessageSearchMessage saidToAlice = new DirectMessageSearchMessage(ALICE);
        DirectMessageSearchThread inBob = new DirectMessageSearchThread(BOB);
        DirectMessageSearchMessage saidToBob = new DirectMessageSearchMessage(BOB);
        ArrayList<Object> hits = new ArrayList<>(Arrays.asList(inAlice, saidToBob, saidToAlice, inBob));

        ArrayList<Object> shown = HiddenChats.searchHits(hits);

        assertEquals(Arrays.asList(saidToBob, inBob), shown);
        assertEquals("Instagram's own list is untouched", 4, hits.size());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of search 1"));
        ArrayList<Object> clean = new ArrayList<>(Arrays.asList(saidToBob));
        assertSame(clean, HiddenChats.searchHits(clean));
        assertNull(HiddenChats.searchHits(null));
    }

    @Test
    public void aSearchResultThatCantBeReadStaysAndPausedShowsEverything() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.searchReader = result -> {
            throw new IllegalStateException("no key");
        };
        List<Object> results = new ArrayList<>(Arrays.asList(new DirectShareTarget(ALICE), new DirectShareTarget(BOB)));
        assertSame("nothing could be read", results, HiddenChats.searchResults(results));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        // The unpatched bridge answers null for everything, so nothing is hidden.
        HiddenChats.resetForTests();
        assertSame(results, HiddenChats.searchResults(results));

        HiddenChats.searchReader = result -> ((DirectShareTarget) result).threadId;
        ArrayList<Object> hits = new ArrayList<>(Arrays.asList(new DirectMessageSearchMessage(ALICE)));
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);

            assertSame(reason.name(), results, HiddenChats.searchResults(results));
            assertSame(reason.name(), hits, HiddenChats.searchHits(hits));

            PauseForTests.resume();
            assertEquals(reason.name(), 1, HiddenChats.searchResults(results).size());
            assertTrue(reason.name(), HiddenChats.searchHits(hits).isEmpty());
        }
    }

    @Test
    public void aHiddenChatsPushIsDroppedAndOthersAreNot() {
        HiddenChats.add(ALICE, "Alice");

        assertTrue(ChatLocks.track(null, null, null, "instagram://direct_v2?id=" + ALICE + "&x=1", null, null));
        assertTrue("by the push's thread id", ChatLocks.track(null, null, null, null, ALICE, null));
        assertTrue("by the push's thread IG id", ChatLocks.track(null, null, null, "media?id=5", null, ALICE));
        assertFalse(ChatLocks.track(null, null, null, "direct_v2?id=" + BOB, BOB, null));
        assertFalse("a push with no chat in it", ChatLocks.track(null, null, null, null, null, null));
        assertFalse("a link that isn't a chat's", ChatLocks.track(null, null, null, "media?id=" + ALICE, null, null));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chat notifications dropped 3"));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void aDroppedPushIsNotMarkedForTheLock() {
        HiddenChats.add(ALICE, "Alice");
        android.app.Notification notification = new android.app.Notification();

        assertTrue(ChatLocks.track(notification, null, null, null, ALICE, null));

        assertNull(ChatLocks.idsOf(notification));
    }

    @Test
    public void pausedBringsEveryChatBackWhileTheListKeepsWhatWasChosen() {
        HiddenChats.add(ALICE, "Alice");
        ArrayList<Object> inbox = list(ALICE, BOB);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);

            assertSame(reason.name(), inbox, HiddenChats.filter(inbox));
            assertFalse(reason.name(), ChatLocks.track(null, null, null, null, ALICE, null));
            assertTrue("nothing hidden now", HiddenChats.chats().isEmpty());
            assertEquals("the list still shows the choice", 1, HiddenChats.saved().size());

            PauseForTests.resume();
            assertEquals(reason.name(), Arrays.asList(BOB), ids(HiddenChats.filter(list(ALICE, BOB))));
        }
    }

    @Test
    public void nothingIsHiddenWithoutContextAndOtherSettingsDontMatter() {
        HiddenChats.add(ALICE, "Alice");
        SettingsContextRule.withoutContext(() -> {
            ArrayList<Object> inbox = list(ALICE);
            assertSame(inbox, HiddenChats.filter(inbox));
            assertFalse(ChatLocks.track(null, null, null, null, ALICE, null));
        });
        Settings.LOCK_MESSAGES.save(false);
        assertEquals("the list alone is the switch", Arrays.asList(BOB), ids(HiddenChats.filter(list(ALICE, BOB))));
    }

    @Test
    public void theListReadsAndWritesTheSameLinesAsLockedChats() {
        Settings.HIDDEN_CHATS.save("1\tAnn\n\n2\n 3 \t Cy \n\t");

        List<ChatLocks.Chat> chats = HiddenChats.chats();

        assertEquals(3, chats.size());
        assertEquals("Ann", chats.get(0).name);
        assertEquals("a chat saved with no name", "Chat 2", chats.get(1).name);
        assertEquals("3", chats.get(2).id);
        assertTrue(HiddenChats.listed("1"));
        assertFalse(HiddenChats.listed("4"));
        HiddenChats.add("1", "Anna");
        HiddenChats.add("5", "Eve");
        HiddenChats.remove("2");
        List<String> ids = new ArrayList<>();
        for (ChatLocks.Chat chat : HiddenChats.chats()) ids.add(chat.id);
        assertEquals(Arrays.asList("1", "3", "5"), ids);
        assertEquals("Anna", HiddenChats.chats().get(0).name);
        assertFalse("the locked chats are a separate list", ChatLocks.listed("1"));
    }

    @Test
    public void theLastChatOpenedIsOfferedUntilItIsHidden() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        ChatLocks.reader = fragment -> ALICE;
        ChatLocks.opened(screen);
        ChatLocks.closed(screen);

        assertEquals(ALICE, HiddenChats.lastOpened().id);
        assertEquals("Chat " + ALICE.substring(ALICE.length() - 4), HiddenChats.lastOpened().name);

        HiddenChats.add(ALICE, "Alice");

        assertNull(HiddenChats.lastOpened());
        assertEquals("hidden is not locked", ALICE, ChatLocks.lastOpened().id);
    }

    @Test
    public void theListOpensBehindThePhoneLockOnlyWhileAMessagesLockIsOn() {
        List<Runnable[]> asks = new ArrayList<>();
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> asks.add(new Runnable[]{confirmed, notConfirmed});
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        boolean[] opened = {false};

        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        assertTrue("no lock is on", opened[0]);
        assertTrue(asks.isEmpty());

        opened[0] = false;
        Settings.LOCK_MESSAGES.save(true);
        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        assertFalse("waits for the phone's lock", opened[0]);
        assertEquals(1, asks.size());
        asks.get(0)[1].run();
        assertFalse("cancelling opens nothing", opened[0]);

        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        asks.get(1)[0].run();
        assertTrue(opened[0]);
    }

    private static ArrayList<Object> list(String... ids) {
        ArrayList<Object> list = new ArrayList<>();
        for (String id : ids) list.add(new Summary(id));
        return list;
    }

    private static List<String> ids(List<Object> summaries) {
        List<String> ids = new ArrayList<>();
        for (Object summary : summaries) ids.add(((Summary) summary).id);
        return ids;
    }
}
