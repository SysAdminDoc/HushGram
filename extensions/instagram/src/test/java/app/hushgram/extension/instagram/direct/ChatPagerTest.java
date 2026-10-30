/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The paging loop of Jump to the first message or a date (#246), without a view. */
public class ChatPagerTest {
    /** A chat of [total] messages, one a day, of which a page more shows each time the list is scrolled to its end. */
    private static final class FakeChat implements ChatPager.Source {
        final int total;
        final int page;
        int shown;
        int scrolledToOldest;
        int scrolledTo = -2;

        FakeChat(int total, int shown, int page) {
            this.total = total;
            this.shown = shown;
            this.page = page;
        }

        /** Message i (0 = oldest) is at row total - 1 - i of a newest-first list; only the newest [shown] exist. */
        @Override public int count() {
            return shown;
        }

        @Override public List<ChatHistory.Entry> entries() {
            List<ChatHistory.Entry> list = new ArrayList<>();
            for (int row = 0; row < shown; row++) {
                int age = row; // row 0 is the newest
                long day = 86_400_000L;
                long millis = 1_700_000_000_000L + (long) (total - 1 - age) * day;
                list.add(new ChatHistory.Entry(row, "m" + row, "them", "t", "TEXT", millis, false));
            }
            java.util.Collections.reverse(list);
            return list;
        }

        @Override public void toOldest() {
            scrolledToOldest++;
            shown = Math.min(total, shown + page);
        }

        @Override public void scrollTo(int position) {
            scrolledTo = position;
        }

        int scrolledToNewest;

        @Override public void toNewest() {
            scrolledToNewest++;
        }

        long timeOfMessage(int index) {
            return 1_700_000_000_000L + (long) index * 86_400_000L;
        }
    }

    private static int run(ChatPager.Loader loader) {
        int ticks = 0;
        while (loader.tick()) {
            if (++ticks > 10_000) throw new AssertionError("the loader never finished");
        }
        return ticks;
    }

    @Test public void theFirstMessageIsReachedWhenTheListStopsGrowing() {
        FakeChat chat = new FakeChat(95, 20, 20);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 3);
        run(loader);
        assertEquals(95, chat.shown);
        assertEquals("the oldest message is the last row", 94, chat.scrolledTo);
        assertEquals(95, loader.loaded());
        assertFalse(loader.reachedDate());
    }

    @Test public void aDateStopsTheLoadingAsSoonAsTheChatReachesBackToIt() {
        FakeChat chat = new FakeChat(200, 20, 20);
        // Message 100 is about halfway; the chat needs to load back to it.
        long target = chat.timeOfMessage(100);
        ChatPager.Loader loader = new ChatPager.Loader(chat, target, 3);
        run(loader);
        assertTrue(loader.reachedDate());
        assertTrue("it stops once the date is loaded, not at the very start", chat.shown < 200);
        assertEquals("it lands on the message of that day", 199 - 100, chat.scrolledTo);
    }

    @Test public void aDateOlderThanTheChatGoesToItsFirstMessage() {
        FakeChat chat = new FakeChat(30, 10, 10);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 1, 3);
        run(loader);
        assertEquals(30, chat.shown);
        assertEquals(29, chat.scrolledTo);
    }

    @Test public void aDateAlreadyOnScreenDoesNotScrollTheListToItsEnd() {
        FakeChat chat = new FakeChat(50, 50, 10);
        long target = chat.timeOfMessage(30);
        ChatPager.Loader loader = new ChatPager.Loader(chat, target, 3);
        assertFalse(loader.tick());
        assertEquals(0, chat.scrolledToOldest);
        assertEquals(49 - 30, chat.scrolledTo);
        assertTrue(loader.reachedDate());
    }

    @Test public void aStalledListEndsAfterTheStallLimit() {
        FakeChat chat = new FakeChat(10, 10, 10);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 4);
        int ticks = run(loader);
        assertEquals("four looks that asked for more, the fifth ended it", 4, ticks);
        assertEquals(9, chat.scrolledTo);
    }

    @Test public void aListThatGrowsSlowlyIsNotMistakenForAStall() {
        FakeChat chat = new FakeChat(40, 1, 1);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 2);
        run(loader);
        assertEquals(40, chat.shown);
    }

    @Test public void anEmptyListEndsWithoutScrollingToARow() {
        FakeChat chat = new FakeChat(0, 0, 10);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 2);
        run(loader);
        assertEquals(-2, chat.scrolledTo);
        assertEquals(0, loader.loaded());
    }

    /** An export reads the whole chat, then leaves the list at the new end instead of on the first message (#247). */
    @Test public void anExportLoadsEverythingAndPutsTheListBackAtTheNewEnd() {
        FakeChat chat = new FakeChat(60, 20, 20);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 3, false);
        run(loader);
        assertEquals(60, chat.shown);
        assertEquals(60, chat.entries().size());
        assertEquals("no jump to the first message", -2, chat.scrolledTo);
        assertEquals(1, chat.scrolledToNewest);
    }

    @Test public void aFinishedLoaderStaysFinished() {
        FakeChat chat = new FakeChat(5, 5, 5);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 1);
        run(loader);
        int before = chat.scrolledToOldest;
        assertFalse(loader.tick());
        assertEquals(before, chat.scrolledToOldest);
    }
}
