/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When the chat seen receipt is held back, when Instagram sends it, and how a chat marked read by
 * hand lets its own receipt through. Runtime decisions only: the other person seeing Seen, or not,
 * needs a check with two accounts on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ThreadSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    /** Instagram's own Mark as read, and another row of the same menu. */
    private static final Object MARK = new Object();
    private static final Object UNREAD = new Object();

    /** Two accounts signed in on one phone. */
    private static final Session SESSION = new Session("a1");
    private static final Session OTHER = new Session("a2");

    private final long[] now = {5_000L};
    private final LongSupplier clock = () -> now[0];
    private FakeChats chats;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        HookStatus.clear();
        ThreadSeen.forgetMarks();
        ShadowToast.reset();
        chats = new FakeChats();
    }

    @After
    public void restore() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ThreadSeen.forgetMarks();
    }

    @Test
    public void withTheSwitchOnTheReceiptIsHeld() {
        assertTrue(ThreadSeen.hold(null, null));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), SESSION));
        assertNothingReported();
    }

    @Test
    public void offToStartAndOffSendTheReceipt() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.defaultValue);
        assertFalse(ThreadSeen.hold(null, null));
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        assertFalse(ThreadSeen.hold(null, null));
    }

    /** The view-once switch is a separate choice in both directions. */
    @Test
    public void viewOnceMediaIsAnIndependentChoice() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(true);
        assertFalse(ThreadSeen.hold(null, null));
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.save(false);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        assertTrue(ThreadSeen.hold(null, null));
        assertFalse(VisualSeen.hold());
    }

    @Test
    public void pausedAndUnreadySendTheReceipt() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadSeen.hold(null, null));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ThreadSeen.hold(null, null)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(ThreadSeen.hold(null, null)));

        assertTrue(ThreadSeen.hold(null, null));
    }

    @Test
    public void aThrowingSwitchSendsTheReceiptAndIsReported() {
        assertFalse(ThreadSeen.hold(null, null, THROWS));

        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aLongPressOffersMarkAsReadOnceWhileTheSwitchIsOn() {
        List<Object> rows = new ArrayList<>(Collections.singletonList(UNREAD));
        ThreadSeen.offerMarkRead(rows, MARK);
        ThreadSeen.offerMarkRead(rows, MARK);
        assertEquals(List.of(UNREAD, MARK), rows);

        List<Object> empty = new ArrayList<>();
        ThreadSeen.offerMarkRead(empty, null);
        ThreadSeen.offerMarkRead(null, MARK);
        assertTrue(empty.isEmpty());
        assertNothingReported();
    }

    @Test
    public void offPausedAndUnreadyOfferNoRow() {
        List<Object> rows = new ArrayList<>();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        ThreadSeen.offerMarkRead(rows, MARK);
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ThreadSeen.offerMarkRead(rows, MARK);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> ThreadSeen.offerMarkRead(rows, MARK));
        SettingsContextRule.beforeThePauseIsDecided(() -> ThreadSeen.offerMarkRead(rows, MARK));
        assertTrue(rows.isEmpty());

        ThreadSeen.offerMarkRead(rows, MARK);
        assertEquals(List.of(MARK), rows);
    }

    @Test
    public void aThrowingSwitchOffersNoRowAndIsReported() {
        List<Object> rows = new ArrayList<>();
        ThreadSeen.offerMarkRead(rows, MARK, THROWS);
        assertTrue(rows.isEmpty());
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.ROW + "'"));
    }

    @Test
    public void markAsReadSendsThatChatsReceiptThroughInstagram() {
        Key key = new Key("t1");
        assertTrue(mark(MARK, chat("m1", "s1"), key));
        assertEquals(List.of("a1:t1/m1/s1"), chats.sent);
        assertEquals(List.of(key), chats.cleared);
        assertEquals("Marked as read", toast());

        assertFalse("the marked message's receipt goes through", hold(receipt("t1", "m1")));
        assertEquals(Set.of(ReadMarks.key("a1", "t1", "m1")), marksOnFile().keySet());
        assertNothingReported();
    }

    @Test
    public void onlyTheMarkedChatsReceiptGoesThrough() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(hold(receipt("t2", "m1")));
        assertTrue(hold(null));
        assertFalse(hold(receipt("t1", "m1")));
        assertTrue(hold(receipt("t2", "m2")));
    }

    /**
     * Instagram tries a receipt again when it fails, and after a restart reads it back from its
     * queue as a new object. The mark isn't used up, so every try goes through.
     */
    @Test
    public void aReceiptLetThroughGoesThroughAgainWhenInstagramRetriesIt() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        Receipt sent = receipt("t1", "m1");
        assertFalse(hold(sent));
        assertFalse(hold(sent));
        assertFalse(hold(receipt("t1", "m1")));
        assertEquals("marking it read sends it once", 1, chats.sent.size());
    }

    /** A message that arrives after the chat was marked read has a receipt of its own, which stays held. */
    @Test
    public void aNewerMessageInAMarkedChatStaysHeld() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(hold(receipt("t1", "m2")));
        assertFalse(hold(receipt("t1", "m1")));

        assertTrue(mark(MARK, chat("m2", "s1"), new Key("t1")));
        assertFalse(hold(receipt("t1", "m2")));
        assertFalse(hold(receipt("t1", "m1")));
    }

    /** With two accounts on the phone, a chat marked read on one doesn't let the other's receipt through. */
    @Test
    public void aMarkCountsOnlyForTheAccountItWasMadeOn() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), OTHER, ON, chats, clock));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), new Session(null), ON, chats, clock));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), null, ON, chats, clock));
        assertFalse(hold(receipt("t1", "m1")));

        assertTrue(ThreadSeen.markRead(MARK, MARK, OTHER, chat("m1", "s1"), new Key("t1"), ON, chats, clock));
        assertFalse(ThreadSeen.hold(receipt("t1", "m1"), OTHER, ON, chats, clock));
        assertEquals(List.of("a1:t1/m1/s1", "a2:t1/m1/s1"), chats.sent);
        assertEquals(2, marksOnFile().size());
    }

    /** Instagram keeps a queued receipt across a restart and sends it later, so the mark is kept on file for it. */
    @Test
    public void aMarkOutlivesARestart() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        ThreadSeen.restartForTests();
        assertFalse(hold(receipt("t1", "m1")));
        assertTrue(hold(receipt("t1", "m2")));
        assertEquals(1, marksOnFile().size());

        now[0] += ReadMarks.KEEP_MS + 1;
        ThreadSeen.restartForTests();
        assertTrue("a mark past its day is dropped when the file is read", hold(receipt("t1", "m1")));
        assertTrue(marksOnFile().isEmpty());
    }

    /** A mark lasts a day, long enough for a receipt Instagram sends once it's back online, and no longer. */
    @Test
    public void aMarkLastsADay() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        now[0] += ReadMarks.KEEP_MS;
        assertFalse(hold(receipt("t1", "m1")));
        now[0] += 1;
        assertTrue(hold(receipt("t1", "m1")));
        assertTrue("its entry on file goes with it", marksOnFile().isEmpty());

        assertTrue(mark(MARK, chat("m2", "s1"), new Key("t1")));
        now[0] -= ReadMarks.KEEP_MS + 1;
        assertTrue("a clock set back by more than a day doesn't keep a mark longer", hold(receipt("t1", "m2")));
    }

    /** At most 200 marks are kept, and the oldest goes first, on file too. */
    @Test
    public void onlyTheNewestMarksAreKept() {
        for (int message = 0; message <= ReadMarks.MAX_MARKS; message++) {
            now[0]++;
            ThreadSeen.markReadTogether(SESSION, "t1", "m" + message, ON, chats, clock);
        }
        assertTrue("the oldest is gone", hold(receipt("t1", "m0")));
        assertFalse(hold(receipt("t1", "m1")));
        assertFalse(hold(receipt("t1", "m" + ReadMarks.MAX_MARKS)));
        assertEquals(ReadMarks.MAX_MARKS, marksOnFile().size());

        ThreadSeen.restartForTests();
        assertTrue(hold(receipt("t1", "m0")));
        assertFalse(hold(receipt("t1", "m1")));
        assertEquals(ReadMarks.MAX_MARKS, marksOnFile().size());
    }

    /** A file with more marks than are kept, or a value that isn't a time, is put right when it's read. */
    @Test
    public void aFileWithTooManyOrBrokenMarksIsCleanedWhenRead() {
        SharedPreferences.Editor edit = file().edit();
        for (int message = 0; message <= ReadMarks.MAX_MARKS; message++) {
            edit.putLong(ReadMarks.key("a1", "t1", "m" + message), now[0] + message);
        }
        edit.putString(ReadMarks.key("a1", "t1", "broken"), "not a time");
        edit.commit();
        ThreadSeen.restartForTests();

        assertTrue(hold(receipt("t1", "m0")));
        assertFalse(hold(receipt("t1", "m1")));
        assertTrue(hold(receipt("t1", "broken")));
        assertEquals(ReadMarks.MAX_MARKS, marksOnFile().size());
    }

    @Test
    public void markingAChatAgainKeepsItsMarkForAnotherDay() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        now[0] += ReadMarks.KEEP_MS - 1;
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        now[0] += ReadMarks.KEEP_MS - 1;
        assertFalse(hold(receipt("t1", "m1")));
        assertEquals(List.of("a1:t1/m1/s1", "a1:t1/m1/s1"), chats.sent);
    }

    @Test
    public void otherRowsAndTheSwitchOffLeaveTheTapToInstagram() {
        Key key = new Key("t1");
        assertFalse(mark(UNREAD, chat("m1", "s1"), key));
        assertFalse(mark(null, chat("m1", "s1"), key));
        assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), key, OFF, chats, clock));
        assertTrue(chats.sent.isEmpty());
        assertTrue(chats.cleared.isEmpty());
        assertTrue(hold(receipt("t1", "m1")));
    }

    @Test
    public void offPausedAndUnreadyLeaveTheTapToInstagram() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        assertFalse(tap());
        assertFalse(ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1")));
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(tap());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(tap()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(tap()));
        assertTrue(chats.sent.isEmpty());
        assertTrue(chats.cleared.isEmpty());
        assertTrue(hold(receipt("t1", "m1")));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), SESSION));
    }

    /** A chat whose message, ids or account can't be read is told so, and no mark is kept for it. */
    @Test
    public void aChatWithNothingToMarkIsToldSo() {
        assertTrue(mark(MARK, new Chat(null), new Key("t1")));
        assertTrue(mark(MARK, chat("", "s1"), new Key("t1")));
        assertTrue(mark(MARK, chat("m1", null), new Key("t1")));
        assertTrue(mark(MARK, chat("m1", "s1"), new Key(null)));
        assertTrue(ThreadSeen.markRead(MARK, MARK, new Session(null), chat("m1", "s1"), new Key("t1"), ON, chats, clock));
        assertEquals("Couldn't mark as read", toast());
        assertTrue(chats.sent.isEmpty());
        assertTrue(chats.cleared.isEmpty());
        assertTrue(hold(receipt("t1", "m1")));
        assertTrue(marksOnFile().isEmpty());
    }

    /** Unpatched bridges answer nothing, which reads the same as a chat with nothing to mark. */
    @Test
    public void unpatchedBridgesMarkNothing() {
        assertTrue(ThreadSeen.markRead(MARK, MARK, SESSION, new Object(), new Object()));
        assertEquals("Couldn't mark as read", toast());
        ThreadSeen.markReadTogether(SESSION, new Object(), "t1", "m1", "s1");
        assertTrue(marksOnFile().isEmpty());
        assertTrue(ThreadSeen.hold(new Object(), SESSION));
        assertNothingReported();
    }

    @Test
    public void aFailedSendTakesItsMarkBackAndIsReported() {
        chats.sendThrows = true;
        assertFalse(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertEquals("Couldn't mark as read", toast());
        chats.sendThrows = false;
        assertTrue(hold(receipt("t1", "m1")));
        assertTrue(marksOnFile().isEmpty());
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.MARK + "'"));
    }

    /** A failed send for a message already marked read leaves the earlier mark, which a queued receipt may still need. */
    @Test
    public void aFailedSendKeepsAnEarlierMarkForTheSameMessage() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        chats.sendThrows = true;
        assertFalse(mark(MARK, chat("m1", "s1"), new Key("t1")));
        chats.sendThrows = false;
        assertFalse(hold(receipt("t1", "m1")));
    }

    @Test
    public void aFailedUnreadClearStillSendsAndIsReported() {
        chats.clearThrows = true;
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertEquals(List.of("a1:t1/m1/s1"), chats.sent);
        assertEquals("Marked as read", toast());
        assertFalse(hold(receipt("t1", "m1")));
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.MARK + "'"));
    }

    /** While a mark is kept, a receipt whose chat, message or account can't be read stays held rather than going out unasked. */
    @Test
    public void anUnreadableReceiptStaysHeldWhileAMarkIsKept() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertTrue(hold(new Object()));
        assertTrue(hold(receipt("t1", null)));
        assertTrue(hold(receipt(null, "m1")));
        chats.keyThrows = true;
        assertTrue(hold(receipt("t1", "m1")));
        chats.keyThrows = false;
        chats.messageThrows = true;
        assertTrue(hold(receipt("t1", "m1")));
        chats.messageThrows = false;
        chats.accountThrows = true;
        assertTrue(hold(receipt("t1", "m1")));
        chats.accountThrows = false;

        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.PASS + "'"));
        assertFalse(hold(receipt("t1", "m1")));
    }

    /** With no mark kept, receipts are held without reading them at all. */
    @Test
    public void withNoMarkKeptReceiptsAreNotRead() {
        chats.keyThrows = true;
        chats.messageThrows = true;
        chats.accountThrows = true;
        assertTrue(hold(receipt("t1", "m1")));
        assertNothingReported();
    }

    /** Off, a mark made before keeps nothing back: every receipt goes, and the mark waits. */
    @Test
    public void theSwitchOffSendsEveryReceiptAndKeepsTheMark() {
        assertTrue(mark(MARK, chat("m1", "s1"), new Key("t1")));
        assertFalse(ThreadSeen.hold(receipt("t2", "m2"), SESSION, OFF, chats, clock));
        assertFalse(ThreadSeen.hold(receipt("t1", "m1"), SESSION, OFF, chats, clock));
        assertFalse(hold(receipt("t1", "m1")));
        assertTrue(hold(receipt("t2", "m2")));
    }

    /** Chats picked together and marked read with Instagram's own Mark as read let their receipts through, as a long press does. */
    @Test
    public void chatsMarkedReadTogetherLetTheirReceiptsThrough() {
        ThreadSeen.markReadTogether(SESSION, "t1", "m1", ON, chats, clock);
        ThreadSeen.markReadTogether(SESSION, "t2", "m2", ON, chats, clock);
        assertFalse(hold(receipt("t1", "m1")));
        assertFalse(hold(receipt("t2", "m2")));
        assertTrue(hold(receipt("t3", "m3")));
        assertTrue("a newer message stays held", hold(receipt("t1", "m4")));
        assertTrue(ThreadSeen.hold(receipt("t1", "m1"), OTHER, ON, chats, clock));
        assertTrue("Instagram's own sender sends them", chats.sent.isEmpty());
        assertNothingReported();

        ThreadSeen.restartForTests();
        assertFalse(hold(receipt("t2", "m2")));
    }

    @Test
    public void offPausedAndUnreadyMarkNothingTogether() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        together();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        together();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(this::together);
        SettingsContextRule.beforeThePauseIsDecided(this::together);
        ThreadSeen.markReadTogether(SESSION, "t1", "", ON, chats, clock);
        ThreadSeen.markReadTogether(SESSION, null, "m1", ON, chats, clock);
        ThreadSeen.markReadTogether(new Session(null), "t1", "m1", ON, chats, clock);
        assertTrue(marksOnFile().isEmpty());
        assertTrue(hold(receipt("t1", "m1")));
        assertNothingReported();
    }

    @Test
    public void aThrowingSwitchOrAccountMarksNothingTogetherAndIsReported() {
        ThreadSeen.markReadTogether(SESSION, "t1", "m1", THROWS, chats, clock);
        chats.accountThrows = true;
        ThreadSeen.markReadTogether(SESSION, "t2", "m2", ON, chats, clock);
        chats.accountThrows = false;
        assertTrue(hold(receipt("t1", "m1")));
        assertTrue(hold(receipt("t2", "m2")));
        String missing = HookStatus.missing(FamilyNames.THREAD_SEEN).toString();
        assertTrue(missing, missing.contains("'" + ThreadSeen.TOGETHER + "'"));
    }

    private boolean mark(Object chosen, Object thread, Object key) {
        return ThreadSeen.markRead(chosen, MARK, SESSION, thread, key, ON, chats, clock);
    }

    /** A tap on Mark as read with the switch read as the hook reads it. */
    private boolean tap() {
        return ThreadSeen.markRead(MARK, MARK, SESSION, chat("m1", "s1"), new Key("t1"), ThreadSeen::switchedOn, chats, clock);
    }

    /** Chat t1 marked read with others, with the switch read as the hook reads it. */
    private void together() {
        ThreadSeen.markReadTogether(SESSION, "t1", "m1", ThreadSeen::switchedOn, chats, clock);
    }

    private boolean hold(Object receipt) {
        return ThreadSeen.hold(receipt, SESSION, ON, chats, clock);
    }

    private static void assertNothingReported() {
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    private static SharedPreferences file() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(ReadMarks.FILE, Context.MODE_PRIVATE);
    }

    private static Map<String, ?> marksOnFile() {
        return file().getAll();
    }

    private static Chat chat(String message, String sender) {
        return new Chat(new Message(message, sender));
    }

    private static Receipt receipt(String thread, String message) {
        return new Receipt(thread, message);
    }

    private static String toast() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static final class Session {
        final String account;

        Session(String account) {
            this.account = account;
        }
    }

    private static final class Key {
        final String thread;

        Key(String thread) {
            this.thread = thread;
        }
    }

    private static final class Receipt {
        final String thread;
        final String message;

        Receipt(String thread, String message) {
            this.thread = thread;
            this.message = message;
        }
    }

    private static final class Message {
        final String id;
        final String sender;

        Message(String id, String sender) {
            this.id = id;
            this.sender = sender;
        }
    }

    private static final class Chat {
        final Message last;

        Chat(Message last) {
            this.last = last;
        }
    }

    /**
     * Instagram's chats as these tests keep them: a session names its account, a receipt its chat
     * and message, a key its chat, and a chat its last message.
     */
    private static final class FakeChats implements ThreadSeen.Chats {
        final List<String> sent = new ArrayList<>();
        final List<Object> cleared = new ArrayList<>();
        boolean sendThrows;
        boolean clearThrows;
        boolean keyThrows;
        boolean messageThrows;
        boolean accountThrows;

        @Override
        public String accountId(Object session) {
            if (accountThrows) throw new IllegalStateException("signed out");
            return session instanceof Session ? ((Session) session).account : null;
        }

        @Override
        public String threadId(Object key) {
            return key instanceof Key ? ((Key) key).thread : null;
        }

        @Override
        public Object receiptKey(Object receipt) {
            if (keyThrows) throw new IllegalStateException("Required value was null.");
            return receipt instanceof Receipt ? new Key(((Receipt) receipt).thread) : null;
        }

        @Override
        public String receiptMessage(Object receipt) {
            if (messageThrows) throw new ClassCastException("not a receipt");
            return receipt instanceof Receipt ? ((Receipt) receipt).message : null;
        }

        @Override
        public Object lastMessage(Object thread) {
            return thread instanceof Chat ? ((Chat) thread).last : null;
        }

        @Override
        public String messageId(Object message) {
            return ((Message) message).id;
        }

        @Override
        public String senderId(Object message) {
            return ((Message) message).sender;
        }

        @Override
        public void sendSeen(Object session, String thread, String message, String sender) {
            if (sendThrows) throw new IllegalStateException("the queue went away");
            sent.add(accountId(session) + ":" + thread + "/" + message + "/" + sender);
        }

        @Override
        public void clearUnread(Object session, Object key) {
            if (clearThrows) throw new IllegalStateException("the store went away");
            assertTrue(session instanceof Session);
            cleared.add(key);
        }
    }
}
