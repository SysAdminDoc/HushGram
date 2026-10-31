/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** The paging loop of Jump to the first message or a date (#246), and the long press that opens it. */
@RunWith(RobolectricTestRunner.class)
public class ChatPagerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The chat screen the patch hands over: only its view is read, by name. */
    public static final class FakeChatScreen {
        private final View view;

        FakeChatScreen(View view) {
            this.view = view;
        }

        public View getView() {
            return view;
        }
    }

    /** An activity laid out like Instagram's: the action bar with the chat's name beside the chat's own view. */
    private static final class Screen {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final TextView name = new TextView(activity);
        final FrameLayout chat = new FrameLayout(activity);
        final FakeChatScreen fragment = new FakeChatScreen(chat);

        Screen() {
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout actionBar = new FrameLayout(activity);
            name.setId(View.generateViewId());
            name.setText("mrp1900s");
            actionBar.addView(name);
            column.addView(actionBar);
            column.addView(chat);
            activity.setContentView(column);
            Map<String, Integer> ids = new HashMap<>();
            ids.put(ChatLocks.TITLES[0], name.getId());
            MessagesLock.idsForTests = ids;
        }
    }

    private Screen screen;

    @After
    public void restore() {
        if (screen != null) ChatPager.closed(screen.fragment);
        MessagesLock.idsForTests = null;
        Settings.JUMP_IN_CHAT.resetToDefault();
        Settings.EXPORT_CHAT.resetToDefault();
        HookStatus.clear();
    }

    /**
     * Instagram keeps the chat's name in the action bar beside the chat's view, not inside it. Looked
     * for under the chat's view alone it was never found, so a long press on the name stayed
     * Instagram's and opened the chat's details (#246, #247). Found through the chat's parents, the
     * long press opens the menu.
     */
    @Test
    public void theNameBesideTheChatGetsTheLongPressAndOpensTheMenu() {
        screen = new Screen();
        Settings.JUMP_IN_CHAT.save(true);
        Settings.EXPORT_CHAT.save(true);

        ChatPager.opened(screen.fragment);

        assertTrue("the name didn't get the long press", screen.name.hasOnLongClickListeners());
        assertTrue(screen.name.performLongClick());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no menu opened", dialog);
        assertEquals(3, dialog.getListView().getAdapter().getCount());
        dialog.dismiss();
        String missing = HookStatus.missing(FamilyNames.CHAT_HISTORY).toString();
        assertFalse(missing, missing.contains("view id"));
    }

    /** A name Instagram made long-clickable without listening still takes the menu; on it a long press fell through to a click. */
    @Test
    public void aLongClickableNameWithNothingListeningStillGetsTheMenu() {
        screen = new Screen();
        screen.name.setLongClickable(true);
        Settings.JUMP_IN_CHAT.save(true);

        ChatPager.opened(screen.fragment);

        assertTrue(screen.name.hasOnLongClickListeners());
        assertTrue(screen.name.performLongClick());
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
    }

    /** A long press Instagram already answers on the name is left to Instagram. */
    @Test
    public void aNameInstagramListensToKeepsItsOwnLongPress() {
        screen = new Screen();
        boolean[] instagrams = new boolean[1];
        screen.name.setOnLongClickListener(v -> instagrams[0] = true);
        Settings.JUMP_IN_CHAT.save(true);

        ChatPager.opened(screen.fragment);
        assertTrue(screen.name.performLongClick());

        assertTrue("Instagram's long press was replaced", instagrams[0]);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /** With both switches off, the name is left alone. */
    @Test
    public void withTheSwitchesOffTheNameIsLeftAlone() {
        screen = new Screen();
        ChatPager.opened(screen.fragment);
        assertFalse(screen.name.hasOnLongClickListeners());
    }
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
