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
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

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
import java.util.Collections;
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

    /** Each inbox holder the extension had draw its rows again, with the list it was handed. */
    private final List<Object[]> redrawn = new ArrayList<>();

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(MessagesLockTest.ME);
        HiddenChats.reader = summary -> ((Summary) summary).id;
        HiddenChats.redrawer = (holder, summaries) -> redrawn.add(new Object[]{holder, summaries});
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
        // filter is the unread badge's read; the inbox's rows have their own count (below).
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of the unread badge 1"));
    }

    @Test
    public void aHiddenChatLeavesTheListTheInboxDrawsItsRowsFrom() {
        HiddenChats.add(ALICE, "Alice");
        List<Object> inbox = Collections.unmodifiableList(list(ALICE, BOB, "7"));

        List<Object> shown = HiddenChats.inbox(null,inbox);

        assertEquals(Arrays.asList(BOB, "7"), ids(shown));
        assertEquals("Instagram's own list is untouched", Arrays.asList(ALICE, BOB, "7"), ids(inbox));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("hidden chats left out of the inbox 1"));
        assertFalse("the badge wasn't asked", report.contains("hidden chats left out of the unread badge"));
        assertEquals("the next list Instagram hands over is filtered too", Arrays.asList("7"), ids(HiddenChats.inbox(null,list("7", ALICE))));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of the inbox 2"));
    }

    @Test
    public void aHiddenChatsUnreadMessagesDontCountOnAFolderTab() {
        HiddenChats.add(ALICE, "Alice");
        List<Object> primary = list(BOB, ALICE);

        List<Object> counted = HiddenChats.folder(primary);

        assertEquals(Arrays.asList(BOB), ids(counted));
        assertEquals("Instagram's own list is untouched", Arrays.asList(BOB, ALICE), ids(primary));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chats left out of folder unread counts 1"));
    }

    @Test
    public void inboxAndFolderListsWithNothingHiddenComeBackAsTheyAre() {
        List<Object> inbox = list(ALICE, BOB);
        assertSame("no hidden chat", inbox, HiddenChats.inbox(null,inbox));
        assertSame("no hidden chat", inbox, HiddenChats.folder(inbox));

        HiddenChats.add(ALICE, "Alice");
        List<Object> other = list(BOB, "7");
        assertSame("a list with no hidden chat in it", other, HiddenChats.inbox(null,other));
        assertSame("a list with no hidden chat in it", other, HiddenChats.folder(other));
        assertNull(HiddenChats.inbox(null,null));
        assertNull(HiddenChats.folder(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, HiddenChats.inbox(null,empty));
        assertSame(empty, HiddenChats.folder(empty));

        HiddenChats.remove(ALICE);
        assertSame("shown again", inbox, HiddenChats.inbox(null,inbox));
        assertSame("counted again", inbox, HiddenChats.folder(inbox));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("left out of the inbox"));
    }

    @Test
    public void aSummaryThatCantBeReadStaysInTheInboxAndPausedShowsEverything() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.reader = summary -> {
            String id = ((Summary) summary).id;
            if (id.equals("bad")) throw new IllegalStateException("no key");
            return id.equals("none") ? null : id;
        };

        assertEquals(Arrays.asList("bad", "none", BOB), ids(HiddenChats.inbox(null,list("bad", ALICE, "none", BOB))));
        assertEquals(Arrays.asList("bad", BOB), ids(HiddenChats.folder(list("bad", ALICE, BOB))));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        // The unpatched bridge answers null for everything, so nothing is hidden.
        HiddenChats.resetForTests();
        List<Object> inbox = list(ALICE, BOB);
        assertSame(inbox, HiddenChats.inbox(null,inbox));
        assertSame(inbox, HiddenChats.folder(inbox));

        HiddenChats.reader = summary -> ((Summary) summary).id;
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame(reason.name(), inbox, HiddenChats.inbox(null,inbox));
            assertSame(reason.name(), inbox, HiddenChats.folder(inbox));

            PauseForTests.resume();
            assertEquals(reason.name(), Arrays.asList(BOB), ids(HiddenChats.inbox(null,inbox)));
            assertEquals(reason.name(), Arrays.asList(BOB), ids(HiddenChats.folder(inbox)));
        }
    }

    @Test
    public void aListThatThrowsIsHandedBackAndReported() {
        HiddenChats.add(ALICE, "Alice");
        List<Object> broken = new ArrayList<Object>(list(ALICE, BOB)) {
            @Override
            public java.util.Iterator<Object> iterator() {
                throw new IllegalStateException("broken");
            }
        };

        assertSame(broken, HiddenChats.inbox(null,broken));
        assertSame(broken, HiddenChats.folder(broken));

        String missing = HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString();
        assertTrue(missing, missing.contains("'inbox filter'"));
        assertTrue(missing, missing.contains("'folder count filter'"));
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

        assertTrue(ChatLocks.track(null, null, null, "instagram://direct_v2?id=" + ALICE + "&x=1", null, null, MessagesLockTest.ME));
        assertTrue("by the push's thread id", ChatLocks.track(null, null, null, null, ALICE, null, MessagesLockTest.ME));
        assertTrue("by the push's thread IG id", ChatLocks.track(null, null, null, "media?id=5", null, ALICE, MessagesLockTest.ME));
        assertFalse(ChatLocks.track(null, null, null, "direct_v2?id=" + BOB, BOB, null, MessagesLockTest.ME));
        assertFalse("a push with no chat in it", ChatLocks.track(null, null, null, null, null, null, MessagesLockTest.ME));
        assertFalse("a link that isn't a chat's", ChatLocks.track(null, null, null, "media?id=" + ALICE, null, null, MessagesLockTest.ME));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hidden chat notifications dropped 3"));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void aDroppedPushIsNotMarkedForTheLock() {
        HiddenChats.add(ALICE, "Alice");
        android.app.Notification notification = new android.app.Notification();

        assertTrue(ChatLocks.track(notification, null, null, null, ALICE, null, MessagesLockTest.ME));

        assertNull(ChatLocks.idsOf(notification));
    }

    @Test
    public void pausedBringsEveryChatBackWhileTheListKeepsWhatWasChosen() {
        HiddenChats.add(ALICE, "Alice");
        ArrayList<Object> inbox = list(ALICE, BOB);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);

            assertSame(reason.name(), inbox, HiddenChats.filter(inbox));
            assertFalse(reason.name(), ChatLocks.track(null, null, null, null, ALICE, null, MessagesLockTest.ME));
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
            assertFalse(ChatLocks.track(null, null, null, null, ALICE, null, MessagesLockTest.ME));
        });
        Settings.LOCK_MESSAGES.save(false);
        assertEquals("the list alone is the switch", Arrays.asList(BOB), ids(HiddenChats.filter(list(ALICE, BOB))));
    }

    @Test
    public void theListReadsAndWritesTheSameLinesAsLockedChats() {
        String me = MessagesLockTest.ME;
        Settings.HIDDEN_CHATS.save(ChatList.HEADER + "\n" + me + "\t1\tAnn\n\n" + me + "\t2\n " + me + " \t 3 \t Cy \n\t");

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

    @Test
    public void tickingAChatDrawsTheInboxAgainAtOnce() {
        Object inbox = new Object();
        List<Object> instagrams = Collections.unmodifiableList(list(ALICE, BOB, "7"));
        assertSame("nothing hidden yet", instagrams, HiddenChats.inbox(inbox, instagrams));
        assertTrue(redrawn.isEmpty());

        HiddenChats.add(ALICE, "Alice");

        assertEquals(1, redrawn.size());
        assertSame("the inbox's own holder", inbox, redrawn.get(0)[0]);
        assertEquals(Arrays.asList(BOB, "7"), ids(rows(0)));
        assertEquals("Instagram's own list is untouched", Arrays.asList(ALICE, BOB, "7"), ids(instagrams));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("inbox redrawn after hidden chats changed 1"));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
        assertTrue("nothing left waiting on the main thread", shadowOf(Looper.getMainLooper()).isIdle());

        HiddenChats.add(BOB, "Bob");

        assertEquals("a second tick draws it again", 2, redrawn.size());
        assertEquals(Arrays.asList("7"), ids(rows(1)));
    }

    @Test
    public void untickingAChatDrawsTheInboxAgainAtOnce() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.add(BOB, "Bob");
        Object inbox = new Object();
        List<Object> instagrams = list(ALICE, BOB, "7");
        assertEquals(Arrays.asList("7"), ids(HiddenChats.inbox(inbox, instagrams)));

        HiddenChats.remove(ALICE);

        assertEquals(1, redrawn.size());
        assertSame(inbox, redrawn.get(0)[0]);
        assertEquals("Alice is back where Instagram put her", Arrays.asList(ALICE, "7"), ids(rows(0)));

        HiddenChats.remove(BOB);

        assertEquals(2, redrawn.size());
        assertSame("nothing hidden, so Instagram's own list", instagrams, rows(1));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("inbox redrawn after hidden chats changed 2"));
        assertTrue("nothing left waiting on the main thread", shadowOf(Looper.getMainLooper()).isIdle());
    }

    @Test
    public void nothingHiddenOrNoRowChangedDrawsNothing() {
        Object inbox = new Object();
        List<Object> instagrams = list(ALICE, BOB);
        HiddenChats.inbox(inbox, instagrams);

        HiddenChats.remove(ALICE);
        assertTrue("nothing hidden before or after", redrawn.isEmpty());
        HiddenChats.add("5", "Eve");
        assertTrue("a chat that isn't in this inbox", redrawn.isEmpty());
        HiddenChats.add("5", "Eva");
        assertTrue("renamed, the same chats hidden", redrawn.isEmpty());
        HiddenChats.remove("5");
        assertTrue("shown again, still not in this inbox", redrawn.isEmpty());

        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("inbox redrawn"));
        assertTrue("nothing left waiting on the main thread", shadowOf(Looper.getMainLooper()).isIdle());
        assertEquals("the inbox is still known, for a chat ticked later", 1, HiddenChats.keptForTests());
        HiddenChats.add(BOB, "Bob");
        assertEquals(Arrays.asList(ALICE), ids(rows(0)));
    }

    @Test
    public void pausedOrNotReadyDrawsNothing() {
        Object inbox = new Object();
        List<Object> instagrams = list(ALICE, BOB);
        HiddenChats.inbox(inbox, instagrams);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);

            assertSame(reason.name(), instagrams, HiddenChats.inbox(new Object(), instagrams));
            HiddenChats.add(ALICE, "Alice");
            HiddenChats.remove(ALICE);
            HiddenChats.add(ALICE, "Alice");

            assertTrue(reason.name(), redrawn.isEmpty());
            assertEquals(reason.name() + ": an inbox handed a list while paused isn't kept", 1, HiddenChats.keptForTests());
            assertTrue("nothing left waiting on the main thread", shadowOf(Looper.getMainLooper()).isIdle());
            HiddenChats.remove(ALICE);
            PauseForTests.resume();
        }
        Settings.HIDDEN_CHATS.save(ALICE + "\tAlice");
        SettingsContextRule.withoutContext(() -> {
            assertSame(instagrams, HiddenChats.inbox(new Object(), instagrams));
            HiddenChats.changed();
        });
        assertTrue("settings not ready", redrawn.isEmpty());
        assertEquals(1, HiddenChats.keptForTests());

        // On again, the same change draws it: the pause and the settings were why nothing did.
        HiddenChats.changed();
        assertEquals(1, redrawn.size());
        assertSame(inbox, redrawn.get(0)[0]);
        assertEquals(Arrays.asList(BOB), ids(rows(0)));
    }

    @Test
    public void anInboxThatsGoneDrawsNothing() {
        HiddenChats.inbox(new Object(), list(ALICE, BOB));
        // Instagram dropped the holder once its screen went away, and the collector took it.
        HiddenChats.holdersDroppedForTests();

        HiddenChats.add(ALICE, "Alice");

        assertTrue(redrawn.isEmpty());
        assertEquals("a dropped inbox is let go", 0, HiddenChats.keptForTests());

        Object cleared = new Object();
        HiddenChats.inbox(cleared, list(ALICE, BOB));
        HiddenChats.inbox(cleared, null);
        HiddenChats.remove(ALICE);
        assertTrue("an inbox Instagram took its list from", redrawn.isEmpty());
        assertEquals(0, HiddenChats.keptForTests());

        HiddenChats.add(BOB, "Bob");
        assertTrue("no inbox at all", redrawn.isEmpty());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("inbox redrawn"));
        assertTrue("nothing left waiting on the main thread", shadowOf(Looper.getMainLooper()).isIdle());
    }

    @Test
    public void eachInboxDrawsFromItsNewestListAndOneThatFailsLeavesTheOthers() {
        Object first = new Object();
        Object second = new Object();
        HiddenChats.inbox(first, list(ALICE, "7"));
        HiddenChats.inbox(first, list(BOB, ALICE));
        HiddenChats.inbox(second, list(ALICE));
        HiddenChats.redrawer = (holder, summaries) -> {
            if (holder == second) throw new IllegalStateException("gone");
            redrawn.add(new Object[]{holder, summaries});
        };

        HiddenChats.add(ALICE, "Alice");

        assertEquals(1, redrawn.size());
        assertSame(first, redrawn.get(0)[0]);
        assertEquals("the newest list it was handed", Arrays.asList(BOB), ids(rows(0)));
        String missing = HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString();
        assertTrue(missing, missing.contains("'inbox redraw'"));
        assertEquals(2, HiddenChats.keptForTests());
    }

    @Test
    public void revealingShowsTheHiddenChatsAloneAndConcealingPutsTheInboxBack() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.add(BOB, "Bob");
        Object inbox = new Object();
        List<Object> instagrams = Collections.unmodifiableList(list("7", BOB, "8", ALICE));
        assertEquals(Arrays.asList("7", "8"), ids(HiddenChats.inbox(inbox, instagrams)));
        assertFalse(HiddenChats.revealed());

        HiddenChats.reveal();

        assertTrue(HiddenChats.revealed());
        assertEquals("drawn again at once with the hidden chats alone", 1, redrawn.size());
        assertSame(inbox, redrawn.get(0)[0]);
        assertEquals("in Instagram's order", Arrays.asList(BOB, ALICE), ids(rows(0)));
        assertEquals("the next list is the hidden chats too", Arrays.asList(ALICE), ids(HiddenChats.inbox(inbox, list("9", ALICE))));
        assertEquals("Instagram's own list is untouched", Arrays.asList("7", BOB, "8", ALICE), ids(instagrams));

        assertTrue(HiddenChats.conceal());

        assertFalse(HiddenChats.revealed());
        assertEquals(2, redrawn.size());
        assertEquals("every other chat again", Arrays.asList("9"), ids(rows(1)));
        assertFalse("nothing to conceal twice", HiddenChats.conceal());
        assertEquals(2, redrawn.size());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void revealedWithNothingHiddenLeavesInstagramsListAndTheLockConcealsThem() {
        List<Object> instagrams = list(ALICE, BOB);
        HiddenChats.reveal();
        assertSame("nothing hidden, so Instagram's own list", instagrams, HiddenChats.inbox(null, instagrams));

        HiddenChats.add(ALICE, "Alice");
        assertEquals(Arrays.asList(ALICE), ids(HiddenChats.inbox(null, list(ALICE, BOB))));

        MessagesLockForTests.leave();

        assertFalse("leaving Instagram hides them again", HiddenChats.revealed());
        assertEquals(Arrays.asList(BOB), ids(HiddenChats.inbox(null, list(ALICE, BOB))));
    }

    /** The list the inbox was asked to draw its rows from the [n]th time. */
    @SuppressWarnings("unchecked")
    private List<Object> rows(int n) {
        return (List<Object>) redrawn.get(n)[1];
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
