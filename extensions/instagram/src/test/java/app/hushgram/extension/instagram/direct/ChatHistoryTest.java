/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Reading a chat's messages out of its list, and the lines an export writes (#246, #247). */
public class ChatHistoryTest {
    @After public void forgetTheFailures() {
        HookStatus.clear();
    }

    /** A stand-in for Instagram's message. */
    static final class Msg {
        final String id;
        final String sender;
        final String text;
        final String time;
        final String kind;
        final boolean viewer;

        Msg(String id, String sender, String text, String time, String kind, boolean viewer) {
            this.id = id;
            this.sender = sender;
            this.text = text;
            this.time = time;
            this.kind = kind;
            this.viewer = viewer;
        }
    }

    /** A row of the list: it holds a message somewhere below it, like Instagram's row items do. */
    static final class Row {
        final Object content;

        Row(Object content) {
            this.content = content;
        }
    }

    static final class Wrapper {
        final Msg message;

        Wrapper(Msg message) {
            this.message = message;
        }
    }

    /** An adapter keeping its rows where a ListAdapter does. */
    public static final class Adapter {
        private final List<Object> rows;

        Adapter(List<Object> rows) {
            this.rows = rows;
        }

        public List<Object> getCurrentList() {
            return rows;
        }
    }

    /** An adapter that keeps its rows in a field, with another list of the wrong size beside it. */
    static final class FieldAdapter {
        final List<Object> other = new ArrayList<>(Arrays.<Object>asList("a", "b"));
        final List<Object> rows;

        FieldAdapter(List<Object> rows) {
            this.rows = rows;
        }
    }

    /** How Instagram's chat keeps its rows on 453: a store, a holder, and an array with room to spare. */
    static final class Holder {
        final int used;
        final Object[] slots;

        Holder(Object[] slots, int used) {
            this.slots = slots;
            this.used = used;
        }
    }

    static final class Store {
        final Holder holder;

        Store(Holder holder) {
            this.holder = holder;
        }
    }

    static final class StoreAdapter {
        final Store store;

        StoreAdapter(Object[] slots, int used) {
            store = new Store(new Holder(slots, used));
        }
    }

    /** A row with no message of its own, a date or a seen line, holding something else. */
    static final class Plain {
        final Object held;

        Plain(Object held) {
            this.held = held;
        }
    }

    /** The signed-in session, whose caches hold other chats' messages. */
    static final class UserSession {
        final List<Object> cached;

        UserSession(List<Object> cached) {
            this.cached = cached;
        }
    }

    /** An adapter with the session in its first field, the way Instagram's has an activity and a session. */
    static final class SessionAdapter {
        final UserSession session;
        final List<Object> rows;

        SessionAdapter(UserSession session, List<Object> rows) {
            this.session = session;
            this.rows = rows;
        }
    }

    /** An adapter whose first field is a collection that fails partway through. */
    static final class BrokenAdapter {
        final Iterable<Object> broken = new Iterable<Object>() {
            @Override public Iterator<Object> iterator() {
                throw new ConcurrentModificationException();
            }
        };
        final List<Object> rows;

        BrokenAdapter(List<Object> rows) {
            this.rows = rows;
        }
    }

    /** Row items whose second row can't be read. */
    static final class FailingRows extends AbstractList<Object> {
        final List<Object> rows;

        FailingRows(List<Object> rows) {
            this.rows = rows;
        }

        @Override public Object get(int index) {
            if (index == 1) throw new IndexOutOfBoundsException();
            return rows.get(index);
        }

        @Override public int size() {
            return rows.size();
        }
    }

    static final ChatHistory.Reader READER = new ChatHistory.Reader() {
        @Override public boolean isMessage(Object candidate) {
            return candidate instanceof Msg;
        }

        @Override public String id(Object message) {
            return ((Msg) message).id;
        }

        @Override public String sender(Object message) {
            return ((Msg) message).sender;
        }

        @Override public String text(Object message) {
            return ((Msg) message).text;
        }

        @Override public String time(Object message) {
            return ((Msg) message).time;
        }

        @Override public String kind(Object message) {
            return ((Msg) message).kind;
        }

        @Override public boolean sentByViewer(Object message) {
            return ((Msg) message).viewer;
        }
    };

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static String micros(long millis) {
        return Long.toString(millis * 1000);
    }

    private static Msg msg(String id, String sender, String text, long millis, String kind, boolean viewer) {
        return new Msg(id, sender, text, micros(millis), kind, viewer);
    }

    private static ChatHistory.Entry entry(int position, long millis) {
        return new ChatHistory.Entry(position, "id" + position, "them", "t", "TEXT", millis, false);
    }

    private static String lineOf(String text, String kind, String name) {
        return ChatHistory.line(new ChatHistory.Entry(0, "1", "u", text, kind, at(2026, 1, 2, 3, 4), false), name);
    }

    @Test public void timesAreMicrosecondsMillisecondsOrSeconds() {
        assertEquals(1_730_000_000_000L, ChatHistory.millis("1730000000000000"));
        assertEquals(1_730_000_000_000L, ChatHistory.millis("1730000000000"));
        assertEquals(1_730_000_000_000L, ChatHistory.millis("1730000000"));
        assertEquals(0, ChatHistory.millis(null));
        assertEquals(0, ChatHistory.millis("soon"));
        assertEquals(0, ChatHistory.millis("-5"));
        assertEquals(0, ChatHistory.millis("99999999999999999999"));
    }

    @Test public void messagesComeOutOldestFirstWithTheirRows() {
        List<Object> rows = new ArrayList<>();
        // Newest first, as a reversed chat list is.
        rows.add(new Row(new Wrapper(msg("3", "me", "c", at(2026, 1, 3, 9, 0), "TEXT", true))));
        rows.add("a date header");
        rows.add(new Row(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false)));
        rows.add(new Row(new Wrapper(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false))));
        List<ChatHistory.Entry> entries = ChatHistory.read(new Adapter(rows), rows.size(), READER);
        assertEquals(3, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals(3, entries.get(0).position);
        assertEquals(2, entries.get(1).position);
        assertEquals(0, entries.get(2).position);
        assertTrue(entries.get(2).viewer);
    }

    @Test public void theItemListIsToldApartByItsSize() {
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("3", "them", "c", at(2026, 1, 3, 9, 0), "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new FieldAdapter(rows), 3, READER);
        assertEquals(3, entries.size());
        assertEquals(0, entries.get(0).position);
        assertEquals(2, entries.get(2).position);
    }

    @Test public void rowsInAnArrayWithRoomToSpareKeepTheirPlaces() {
        Object[] slots = new Object[40];
        slots[0] = "a seen line";
        slots[1] = new Row(msg("3", "me", "c", at(2026, 1, 3, 9, 0), "TEXT", true));
        slots[2] = "a date header";
        slots[3] = new Row(new Wrapper(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false)));
        slots[4] = new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false));
        List<ChatHistory.Entry> entries = ChatHistory.read(new StoreAdapter(slots, 5), 5, READER);
        assertEquals(3, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals(4, entries.get(0).position);
        assertEquals(3, entries.get(1).position);
        assertEquals(1, entries.get(2).position);
    }

    @Test public void anArrayWithGapsIsntTheRows() {
        Object[] table = new Object[8];
        table[1] = new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false));
        table[4] = new Row(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false));
        List<ChatHistory.Entry> entries = ChatHistory.read(new StoreAdapter(table, 2), 2, READER);
        assertEquals(2, entries.size());
        assertEquals(-1, entries.get(0).position);
        assertEquals(-1, entries.get(1).position);
    }

    @Test public void aListOfTheRightSizeWithNoMessagesIsPassedOver() {
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new FieldAdapter(rows), 2, READER);
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).position);
        assertEquals(1, entries.get(1).position);
    }

    @Test public void aRowDoesntTakeItsNeighboursMessage() {
        Msg message = msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false);
        List<Object> rows = new ArrayList<>();
        rows.add(new Plain(new Row(new Wrapper(message))));
        rows.add(new Row(message));
        List<ChatHistory.Entry> entries = ChatHistory.read(new Adapter(rows), 2, READER);
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).position);
    }

    @Test public void aRowsOwnMessageHidesTheOneItQuotes() {
        Msg own = msg("2", "me", "b", at(2026, 1, 2, 9, 0), "TEXT", true);
        Msg quoted = msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false);
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(new Object[]{own, new Plain(new Plain(quoted))}));
        List<ChatHistory.Entry> entries = ChatHistory.read(new Adapter(rows), 1, READER);
        assertEquals(1, entries.size());
        assertEquals("2", entries.get(0).id);
    }

    @Test public void anotherChatsMessageInTheSessionStaysOut() {
        Msg other = msg("9", "stranger", "not this chat", at(2026, 1, 9, 9, 0), "TEXT", false);
        UserSession session = new UserSession(new ArrayList<>(Arrays.<Object>asList(other)));
        List<Object> rows = new ArrayList<>();
        rows.add(new Plain(session));
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        List<ChatHistory.Entry> byRow = ChatHistory.read(new Adapter(rows), 2, READER);
        assertEquals(1, byRow.size());
        assertEquals("1", byRow.get(0).id);
        // The same with the rows out of reach, so the walk starts from the adapter.
        List<ChatHistory.Entry> walked = ChatHistory.read(new SessionAdapter(session, rows), 50, READER);
        assertEquals(1, walked.size());
        assertEquals("1", walked.get(0).id);
    }

    @Test public void aHugeSessionBeforeTheRowsLeavesTheBudgetForThem() {
        List<Object> cached = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<Object> page = new ArrayList<>();
            for (int j = 0; j < 50_000; j++) page.add(new Object());
            cached.add(page);
        }
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new SessionAdapter(new UserSession(cached), rows), 50, READER);
        assertEquals(1, entries.size());
    }

    @Test public void aCollectionThatFailsLosesOnlyItself() {
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        assertEquals(1, ChatHistory.read(new BrokenAdapter(rows), 50, READER).size());
    }

    @Test public void aRowThatFailsLosesOnlyItself() {
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("2", "them", "b", at(2026, 1, 2, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("3", "them", "c", at(2026, 1, 3, 9, 0), "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new Adapter(new FailingRows(rows)), 3, READER);
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).position);
        assertEquals(2, entries.get(1).position);
    }

    @Test public void aMessageSeenTwiceIsOneEntryAndNoMessagesIsEmpty() {
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        rows.add(new Row(msg("1", "them", "a", at(2026, 1, 1, 9, 0), "TEXT", false)));
        assertEquals(1, ChatHistory.read(new Adapter(rows), 2, READER).size());
        List<Object> none = new ArrayList<>(Arrays.<Object>asList("x", "y"));
        assertTrue(ChatHistory.read(new Adapter(none), 2, READER).isEmpty());
        assertTrue(ChatHistory.read(null, 0, READER).isEmpty());
    }

    @Test public void aReaderThatThrowsLosesOnlyThatField() {
        ChatHistory.Reader failing = new ChatHistory.Reader() {
            @Override public boolean isMessage(Object c) { return c instanceof Msg; }
            @Override public String id(Object m) { throw new IllegalStateException(); }
            @Override public String sender(Object m) { return "them"; }
            @Override public String text(Object m) { return "hello"; }
            @Override public String time(Object m) { return micros(at(2026, 1, 1, 9, 0)); }
            @Override public String kind(Object m) { throw new ClassCastException(); }
            @Override public boolean sentByViewer(Object m) { throw new IllegalStateException(); }
        };
        List<Object> rows = new ArrayList<>();
        rows.add(new Row(msg("1", "x", "y", 5000, "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new Adapter(rows), 1, failing);
        assertEquals(1, entries.size());
        assertEquals("hello", entries.get(0).text);
        assertNull(entries.get(0).id);
    }

    @Test public void aDateJumpLandsOnTheFirstMessageOnOrAfterIt() {
        List<ChatHistory.Entry> entries = Arrays.asList(
                entry(0, at(2026, 1, 5, 9, 0)), entry(1, at(2026, 1, 9, 9, 0)), entry(2, at(2026, 3, 1, 9, 0)));
        assertEquals(1, ChatHistory.positionFor(entries, at(2026, 1, 6, 0, 0)));
        assertEquals(0, ChatHistory.positionFor(entries, at(2025, 12, 1, 0, 0)));
        assertEquals(0, ChatHistory.positionFor(entries, 0));
        assertEquals(2, ChatHistory.positionFor(entries, at(2027, 1, 1, 0, 0)));
        assertEquals(-1, ChatHistory.positionFor(Arrays.asList(entry(-1, 5000)), 0));
        assertEquals(at(2026, 1, 5, 9, 0), ChatHistory.oldestMillis(entries));
    }

    @Test public void aLineHasTheTimeTheNameAndTheText() {
        ChatHistory.Entry entry = new ChatHistory.Entry(0, "1", "u1", "see you at 5", "TEXT",
                at(2026, 10, 30, 14, 5), false);
        assertEquals("2026-10-30 14:05 Sam: see you at 5", ChatHistory.line(entry, "Sam"));
    }

    @Test public void aLongerMessageIndentsItsLaterLinesAndTrims() {
        assertEquals("2026-01-02 03:04 You: one\n  two\n  three",
                lineOf("  one\r\ntwo\nthree \n", "TEXT", "You"));
    }

    @Test public void aMessageWithNoTextShowsItsType() {
        assertEquals("2026-01-02 03:04 Sam: [photo or video]", lineOf(null, "MEDIA", "Sam"));
        assertEquals("2026-01-02 03:04 Sam: [photo or video, view once]", lineOf("", "EXPIRING_MEDIA", "Sam"));
        assertEquals("2026-01-02 03:04 Sam: [voice message]", lineOf(null, "VOICE_MEDIA", "Sam"));
        assertEquals("2026-01-02 03:04 Sam: [like]", lineOf(null, "LIKE", "Sam"));
        assertEquals("2026-01-02 03:04 Sam: [shared reel]", lineOf(null, "REEL_SHARE", "Sam"));
        assertEquals("2026-01-02 03:04 Sam: [message]", lineOf(null, null, "Sam"));
    }

    /** Instagram's names for what a chat holds aren't for reading: a saved chat said [xma clips share] (#246). */
    @Test public void aKindInstagramNamesInItsOwnWordsReadsPlainly() {
        assertEquals("[shared reel]", ChatHistory.placeholder("XMA_CLIPS_SHARE"));
        assertEquals("[shared reel]", ChatHistory.placeholder("XMA_REEL_SHARE"));
        assertEquals("[shared reel]", ChatHistory.placeholder("XMA_FELIX_SHARE"));
        assertEquals("[shared audio]", ChatHistory.placeholder("REELS_AUDIO_SHARE"));
        assertEquals("[shared story]", ChatHistory.placeholder("XMA_STORY_SHARE"));
        assertEquals("[shared post]", ChatHistory.placeholder("XMA_MEDIA_SHARE"));
        assertEquals("[shared profile]", ChatHistory.placeholder("XMA_PROFILE"));
        assertEquals("[shared location]", ChatHistory.placeholder("XMA_LOCATION_SHARE"));
        assertEquals("[link]", ChatHistory.placeholder("XMA_LINK"));
        assertEquals("[chat notice]", ChatHistory.placeholder("ACTION_LOG"));
        assertEquals("[chat notice]", ChatHistory.placeholder("INSTAMADILLO_ACTION_LOG"));
        assertEquals("[chat notice]", ChatHistory.placeholder("E2EE_NOTICE"));
        assertEquals("[reaction]", ChatHistory.placeholder("PROFILE_PICTURE_REACTION"));
        assertEquals("[call]", ChatHistory.placeholder("VIDEO_CALL_EVENT"));
        assertEquals("[sticker]", ChatHistory.placeholder("ANIMATED_AVATAR_STICKER"));
        assertEquals("a kind with no plain name still loses the prefix", "[collage]", ChatHistory.placeholder("XMA_COLLAGE"));
    }

    @Test public void aLinkKeepsItsAddress() {
        assertEquals("2026-01-02 03:04 Sam: https://example.com/x", lineOf("https://example.com/x", "LINK", "Sam"));
    }

    @Test public void aMessageWithNoTimeShowsQuestionMarks() {
        assertEquals("????-??-?? ??:?? Sam: hi",
                ChatHistory.line(new ChatHistory.Entry(0, "1", "u", "hi", "TEXT", 0, false), "Sam"));
    }

    @Test public void theDocumentNamesYouAndTheOtherSide() {
        List<ChatHistory.Entry> entries = Arrays.asList(
                new ChatHistory.Entry(0, "1", "u1", "hi", "TEXT", at(2026, 1, 2, 3, 4), false),
                new ChatHistory.Entry(1, "2", "me", "hello", "TEXT", at(2026, 1, 2, 3, 5), true));
        assertEquals("Chat with Sam\n2 messages\n\n2026-01-02 03:04 Sam: hi\n2026-01-02 03:05 You: hello\n",
                ChatHistory.document(entries, "Sam", "You"));
        assertEquals("Chat with Them\n1 message\n\n2026-01-02 03:04 Them: hi\n",
                ChatHistory.document(entries.subList(0, 1), " ", "You"));
    }

    @Test public void aGroupNamesEachSenderById() {
        List<ChatHistory.Entry> entries = Arrays.asList(
                new ChatHistory.Entry(0, "1", "u1", "a", "TEXT", at(2026, 1, 2, 3, 4), false),
                new ChatHistory.Entry(1, "2", "u2", "b", "TEXT", at(2026, 1, 2, 3, 5), false),
                new ChatHistory.Entry(2, "3", "me", "c", "TEXT", at(2026, 1, 2, 3, 6), true));
        String text = ChatHistory.document(entries, "Crew", "You");
        assertTrue(text, text.contains("03:04 u1: a"));
        assertTrue(text, text.contains("03:05 u2: b"));
        assertTrue(text, text.contains("03:06 You: c"));
    }
}
