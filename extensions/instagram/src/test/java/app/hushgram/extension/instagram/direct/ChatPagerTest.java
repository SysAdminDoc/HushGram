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
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.ViewAnimator;

import com.instagram.ui.widget.loadmore.LoadMoreButton;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** The paging loop of Jump to the first message or a date (#246), and the long press that opens it. */
@RunWith(RobolectricTestRunner.class)
public class ChatPagerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** An activity and a view that keep a message, as any of Instagram's might. */
    public static final class HoldingActivity extends Activity {
        Object held;
    }

    public static final class HoldingView extends View {
        Object held;

        HoldingView(android.content.Context context) {
            super(context);
        }
    }

    @Test public void aChatsReadStopsAtAnActivityOrAView() {
        HoldingActivity activity = Robolectric.buildActivity(HoldingActivity.class).setup().get();
        activity.held = new ChatHistoryTest.Msg("8", "stranger", "x", "1730000000000000", "TEXT", false);
        HoldingView view = new HoldingView(activity);
        view.held = new ChatHistoryTest.Msg("9", "stranger", "y", "1730000000000000", "TEXT", false);
        List<Object> rows = new ArrayList<>();
        rows.add(new ChatHistoryTest.Plain(activity));
        rows.add(new ChatHistoryTest.Plain(view));
        rows.add(new ChatHistoryTest.Row(new ChatHistoryTest.Msg("1", "them", "z", "1730000000000000", "TEXT", false)));
        List<ChatHistory.Entry> entries = ChatHistory.read(new ChatHistoryTest.Adapter(rows), 3, ChatHistoryTest.READER);
        assertEquals(1, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals(2, entries.get(0).position);
    }

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
        final FrameLayout actionBar = new FrameLayout(activity);

        Screen() {
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
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

        /** Puts the name in the clickable area Instagram's chat header has: the tap is on the area, nothing is on the name. */
        LinearLayout nameArea(View.OnClickListener tap) {
            LinearLayout area = new LinearLayout(activity);
            area.setId(View.generateViewId());
            area.setOnClickListener(tap);
            actionBar.removeView(name);
            area.addView(name);
            actionBar.addView(area);
            Map<String, Integer> ids = new HashMap<>(MessagesLock.idsForTests);
            ids.put("header_title_subtitle_container", area.getId());
            MessagesLock.idsForTests = ids;
            return area;
        }

        /** The same area made the way 453 makes a chat's header: with a context that doesn't lead to an activity. */
        LinearLayout nameAreaOnAPlainContext(View.OnClickListener tap) {
            LinearLayout area = new LinearLayout(new ContextWrapper(RuntimeEnvironment.getApplication()));
            area.setId(View.generateViewId());
            area.setOnClickListener(tap);
            actionBar.removeView(name);
            area.addView(name);
            actionBar.addView(area);
            Map<String, Integer> ids = new HashMap<>(MessagesLock.idsForTests);
            ids.put("header_title_subtitle_container", area.getId());
            MessagesLock.idsForTests = ids;
            return area;
        }

        /** The header's row of call buttons on the right, as 453 lays it out: an image button with its own size, margins and padding. */
        LinearLayout buttonRow() {
            LinearLayout row = new LinearLayout(activity);
            row.setId(View.generateViewId());
            ImageView call = new ImageView(activity);
            call.setPadding(9, 9, 9, 9);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(66, 66);
            at.setMargins(4, 0, 4, 0);
            row.addView(call, at);
            actionBar.addView(row);
            Map<String, Integer> ids = new HashMap<>(MessagesLock.idsForTests);
            ids.put(ChatPager.BUTTON_ROW, row.getId());
            MessagesLock.idsForTests = ids;
            return row;
        }

        /** Instagram building the header again: new views with the same ids. */
        LinearLayout rebuilt(LinearLayout area) {
            actionBar.removeView(area);
            LinearLayout again = new LinearLayout(activity);
            again.setId(area.getId());
            TextView nameAgain = new TextView(activity);
            nameAgain.setId(name.getId());
            nameAgain.setText(name.getText());
            again.addView(nameAgain);
            actionBar.addView(again);
            return again;
        }
    }

    /** The menu that opened, as a popup (#453); fails when none did. */
    private static Menu menuOpened() {
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("no menu opened", popup);
        assertNull("the old list opened too", ShadowAlertDialog.getLatestAlertDialog());
        Menu menu = popup.getMenu();
        popup.dismiss();
        return menu;
    }

    private static void assertMenuOpened() {
        assertTrue(menuOpened().size() > 0);
    }

    private static void assertNoMenu() {
        assertNull(ShadowPopupMenu.getLatestPopupMenu());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /** Stand-ins for Instagram's glyphs, which the test app doesn't have. */
    private static void glyphs() {
        Map<String, Drawable> icons = new HashMap<>();
        for (String[] names : new String[][] {ChatPager.BUTTON_ICONS, ChatPager.DATE_ICONS, ChatPager.EXPORT_ICONS}) {
            icons.put(names[0], new ColorDrawable(Color.WHITE));
        }
        ChatPager.iconsForTests = icons;
    }

    private Screen screen;

    @After
    public void restore() {
        if (screen != null) ChatPager.closed(screen.fragment);
        MessagesLock.idsForTests = null;
        ChatPager.iconsForTests = null;
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
        assertEquals(3, menuOpened().size());
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
        assertMenuOpened();
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
        assertNoMenu();
    }

    /** With both switches off, the name is left alone. */
    @Test
    public void withTheSwitchesOffTheNameIsLeftAlone() {
        screen = new Screen();
        ChatPager.opened(screen.fragment);
        assertFalse(screen.name.hasOnLongClickListeners());
    }

    /**
     * The name itself takes no touches on 453: the tap that opens the chat's details is on the area
     * around the name and its subtitle. A listener on the name would swallow that tap (#246). The
     * long press goes on the area, and the tap stays Instagram's.
     */
    @Test
    public void theLongPressGoesOnTheAreaAroundTheNameAndATapStaysInstagrams() {
        screen = new Screen();
        int[] taps = new int[1];
        LinearLayout area = screen.nameArea(v -> taps[0]++);
        Settings.JUMP_IN_CHAT.save(true);

        ChatPager.opened(screen.fragment);

        assertFalse("the name would swallow the tap", screen.name.isLongClickable());
        assertTrue(area.performLongClick());
        assertMenuOpened();
        assertTrue(area.performClick());
        assertEquals(1, taps[0]);
    }

    /** Instagram can build the chat's header again after the chat comes up, and the new one gets the long press too. */
    @Test
    public void aHeaderBuiltAgainAfterTheChatOpensGetsTheLongPress() {
        screen = new Screen();
        LinearLayout area = screen.nameArea(v -> { });
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);

        LinearLayout again = screen.rebuilt(area);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertTrue(again.performLongClick());
        assertMenuOpened();
    }

    /** A long press something cleared after the chat came up comes back. */
    @Test
    public void aLongPressThatWasClearedComesBack() {
        screen = new Screen();
        LinearLayout area = screen.nameArea(v -> { });
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);

        area.setLongClickable(false);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertTrue(area.isLongClickable());
        assertTrue(area.performLongClick());
        assertMenuOpened();
    }

    /**
     * On 453 the chat's header is made with a context that leads to no activity. Asked of that context
     * alone the menu had nothing to open over, so the long press answered false and the chat's details
     * opened on release (#246).
     */
    @Test
    public void aHeaderMadeOnAPlainContextStillOpensTheMenu() {
        screen = new Screen();
        int[] taps = new int[1];
        LinearLayout area = screen.nameAreaOnAPlainContext(v -> taps[0]++);
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);

        assertTrue("the long press fell through to the tap", area.performLongClick());

        assertMenuOpened();
        assertEquals(0, taps[0]);
    }

    /** With the whole header on a plain context, the menu opens over the activity HushGram last saw. */
    @Test
    public void aScreenWithNoActivityBehindItsParentsUsesTheActivityShowing() {
        Activity original = Utils.getActivity();
        try {
            screen = new Screen();
            FrameLayout plainBar = new FrameLayout(new ContextWrapper(RuntimeEnvironment.getApplication()));
            LinearLayout area = new LinearLayout(new ContextWrapper(RuntimeEnvironment.getApplication()));
            area.setId(View.generateViewId());
            plainBar.addView(area);
            Map<String, Integer> ids = new HashMap<>(MessagesLock.idsForTests);
            ids.put("header_title_subtitle_container", area.getId());
            MessagesLock.idsForTests = ids;
            FakeChatScreen fragment = new FakeChatScreen(area);
            Utils.setActivity(screen.activity);
            Settings.JUMP_IN_CHAT.save(true);
            ChatPager.opened(fragment);

            assertTrue(area.performLongClick());
            assertMenuOpened();
            ChatPager.closed(fragment);
        } finally {
            Utils.setActivity(original);
        }
    }

    /** Instagram clears the long press again after the header watch has stopped looking, and the next touch puts it back in time. */
    @Test
    public void aLongPressClearedAfterTheWatchStoppedComesBackWithTheNextTouch() {
        screen = new Screen();
        int[] taps = new int[1];
        LinearLayout area = screen.nameArea(v -> taps[0]++);
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);

        area.setOnLongClickListener(null);
        area.setLongClickable(false);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0);
        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 1, 1, 0);
        area.dispatchTouchEvent(down);
        area.dispatchTouchEvent(cancel);
        down.recycle();
        cancel.recycle();

        assertTrue(area.isLongClickable());
        assertTrue("the touch didn't put the listener back", area.hasOnLongClickListeners());
        assertTrue(area.performLongClick());
        assertMenuOpened();
        assertEquals(0, taps[0]);
    }

    /** A touch with both switches off leaves the name's area as Instagram left it. */
    @Test
    public void aTouchWithTheSwitchesOffPutsNothingBack() {
        screen = new Screen();
        LinearLayout area = screen.nameArea(v -> { });
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        Settings.JUMP_IN_CHAT.save(false);
        area.setOnLongClickListener(null);
        area.setLongClickable(false);

        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0);
        area.dispatchTouchEvent(down);
        down.recycle();

        assertFalse(area.isLongClickable());
    }

    /** An export is named after the chat even though the long press is on the area around the name, which holds no text. */
    @Test
    public void theExportsNameComesFromTheNameNotTheAreaThatWasPressed() {
        screen = new Screen();
        screen.nameArea(v -> { });
        assertEquals("mrp1900s", ChatPager.nameIn(screen.chat));
    }

    /** A chat that comes back over the same header is a new screen, and the long press answers for that one. */
    @Test
    public void aChatOpenedAgainOverTheSameHeaderStillOpensTheMenu() {
        screen = new Screen();
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        ChatPager.closed(screen.fragment);
        FakeChatScreen again = new FakeChatScreen(screen.chat);

        ChatPager.opened(again);

        assertTrue(screen.name.performLongClick());
        assertMenuOpened();
        ChatPager.closed(again);
    }

    /** A long press Instagram answers on the area stays Instagram's, however often the header is looked at. */
    @Test
    public void anAreaInstagramListensToKeepsItsOwnLongPress() {
        screen = new Screen();
        boolean[] instagrams = new boolean[1];
        LinearLayout area = screen.nameArea(v -> { });
        area.setOnLongClickListener(v -> instagrams[0] = true);
        Settings.JUMP_IN_CHAT.save(true);

        ChatPager.opened(screen.fragment);
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertFalse("the name would swallow the tap", screen.name.isLongClickable());
        assertTrue(area.performLongClick());
        assertTrue("Instagram's long press was replaced", instagrams[0]);
        assertNoMenu();
    }

    /** The diagnostics report says when the header never showed up, instead of a silent long press. */
    @Test
    public void aHeaderThatNeverComesIsReportedMissing() {
        screen = new Screen();
        MessagesLock.idsForTests = new HashMap<>();
        Settings.JUMP_IN_CHAT.save(true);

        ChatPager.opened(screen.fragment);
        ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS);

        String missing = HookStatus.missing(FamilyNames.CHAT_HISTORY).toString();
        assertTrue(missing, missing.contains("view id"));
    }

    /** A chat that leaves the screen stops being watched, and its header isn't touched afterwards. */
    @Test
    public void aClosedChatIsNoLongerWatched() {
        screen = new Screen();
        LinearLayout area = screen.nameArea(v -> { });
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        ChatPager.closed(screen.fragment);

        LinearLayout again = screen.rebuilt(area);
        ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS);

        assertFalse(again.hasOnLongClickListeners());
        String missing = HookStatus.missing(FamilyNames.CHAT_HISTORY).toString();
        assertFalse(missing, missing.contains("view id"));
    }

    /** A chat of [total] messages, one a day, of which a page more shows each time the list is scrolled to its end. */
    private static final class FakeChat implements ChatPager.Source {
        final int total;
        final int page;
        int shown;
        int scrolledToOldest;
        int scrolledTo = -2;
        /** Looks a page takes to arrive once it's asked for. */
        int looksPerPage;
        private int looksSincePage;

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
            if (++looksSincePage <= looksPerPage) return;
            looksSincePage = 0;
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

    /** A page that takes ten seconds to come on a slow network isn't the end of the chat. */
    @Test public void aSlowPageIsWaitedFor() {
        FakeChat chat = new FakeChat(60, 20, 20);
        chat.looksPerPage = 20;
        run(new ChatPager.Loader(chat, 0, ChatPager.STALL_LOOKS, false));
        assertEquals(60, chat.shown);
    }

    /**
     * The chat's list by the rules of Instagram 453's own scroll listener, which pages only when a scroll
     * reports movement toward the old end (dy below 0) with the last row on screen. A jump to a row reports
     * none, and the list can't move past its old end. Rows and pixels are kept apart: {@link #off} is how
     * far the top of the screen sits below the old end, in pixels.
     */
    public static final class ChatList extends View {
        private static final int ROW = 100;
        private final int total;
        private final int page;
        int rows;
        int off;
        int asked;

        ChatList(int total, int rows, int page) {
            super(RuntimeEnvironment.getApplication());
            this.total = total;
            this.rows = rows;
            this.page = page;
        }

        @Override public void scrollBy(int x, int y) {
            int to = Math.max(0, off + y);
            int moved = to - off;
            off = to;
            if (moved >= 0 || off >= ROW) return;
            asked++;
            int more = Math.min(total, rows + page) - rows;
            rows += more;
            off += more * ROW;
        }

        public Rows getAdapter() {
            return new Rows();
        }

        public Manager getLayoutManager() {
            return new Manager();
        }

        public final class Rows {
            public int getItemCount() {
                return rows;
            }
        }

        public final class Manager {
            public boolean isLayoutReversed() {
                return true;
            }

            public void scrollToPosition(int position) {
                off = (rows - 1 - position) * ROW;
            }
        }
    }

    /**
     * The Loading row sat at a chat's old end for minutes with nothing coming, because the pager only ever
     * jumped there, so an export held 12 of a chat's 22 messages (#246).
     */
    @Test public void aChatIsLoadedByTheScrollInstagramPagesOn() {
        ChatList list = new ChatList(60, 20, 20);
        run(new ChatPager.Loader(new ChatPager.ListSource(list), 0, ChatPager.STALL_LOOKS, false));
        assertEquals(60, list.rows);
    }

    /** Asking moves the list by nothing a reader could see, so a look at the old end ends up back at it. */
    @Test public void askingForAPageLeavesTheListAtTheOldEnd() {
        ChatList list = new ChatList(20, 20, 20);
        ChatPager.ListSource source = new ChatPager.ListSource(list);
        source.toOldest();
        source.toOldest();
        assertEquals(2, list.asked);
        assertEquals(0, list.off);
    }

    /** Off the old end a scroll asks for nothing, so the first look only gets the list there. */
    @Test public void aListAwayFromItsOldEndIsTakenThereFirst() {
        ChatList list = new ChatList(60, 20, 20);
        list.off = 1500;
        ChatPager.ListSource source = new ChatPager.ListSource(list);
        source.toOldest();
        assertEquals(0, list.asked);
        assertEquals(0, list.off);
        source.toOldest();
        assertEquals(1, list.asked);
        assertEquals(40, list.rows);
        assertEquals(0, list.off);
    }

    /**
     * A chat list laid out like Instagram 453's, newest row at the bottom and the old end at the top, with
     * rows of their own heights. A row brought in from off screen lands at the nearer edge, the way
     * LinearLayoutManager places it. {@link #off} is how far the top of the screen sits below the old end.
     */
    public static final class RowList extends View {
        static final int SCREEN = 1000;
        final int[] heights;
        final View[] rows;
        int off;

        /** [heights] from the newest row, position 0, up to the chat's start. */
        RowList(int... heights) {
            super(RuntimeEnvironment.getApplication());
            this.heights = heights;
            rows = new View[heights.length];
            for (int i = 0; i < rows.length; i++) rows[i] = new View(getContext());
        }

        /** How far [position]'s top sits below the old end. */
        int above(int position) {
            int sum = 0;
            for (int i = heights.length - 1; i > position; i--) sum += heights[i];
            return sum;
        }

        int topOf(int position) {
            return above(position) - off;
        }

        private void moveTo(int to) {
            off = Math.max(0, Math.min(Math.max(0, above(-1) - SCREEN), to));
        }

        @Override public void scrollBy(int x, int y) {
            moveTo(off + y);
        }

        public Manager getLayoutManager() {
            return new Manager();
        }

        public final class Manager {
            public boolean isLayoutReversed() {
                return true;
            }

            public void scrollToPosition(int position) {
                int top = topOf(position);
                if (top < 0) moveTo(above(position));
                else if (top + heights[position] > SCREEN) moveTo(above(position) + heights[position] - SCREEN);
            }

            public View findViewByPosition(int position) {
                if (position < 0 || position >= heights.length) return null;
                int top = topOf(position);
                if (top + heights[position] <= 0 || top >= SCREEN) return null;
                rows[position].layout(0, top, 1080, top + heights[position]);
                return rows[position];
            }
        }
    }

    /**
     * The chat from the S22 check, newest first: five messages, an OCT 22 stamp, two OCT 14 messages, an
     * OCT 14 stamp, the OCT 10 message, its stamp and the chat's start.
     */
    private static RowList dated() {
        return new RowList(500, 400, 300, 110, 110, 90, 240, 860, 90, 120, 90, 400);
    }

    private static Set<Integer> messageRows() {
        return new HashSet<>(Arrays.asList(0, 1, 2, 3, 4, 6, 7, 9));
    }

    /** A jump to a date put the found message at the bottom edge under older ones, as if it stopped short (#246). */
    @Test public void aFoundMessageGoesToTheTopWithItsDateStampAboveIt() {
        RowList list = dated();
        new ChatPager.ListSource(list).scrollTo(4, messageRows());
        ShadowLooper.idleMainLooper();
        assertEquals("the OCT 22 stamp at the top", 0, list.topOf(5));
        assertEquals("the message right under it", 90, list.topOf(4));
    }

    @Test public void aFoundMessageRightUnderAnotherGoesToTheTopAlone() {
        RowList list = dated();
        new ChatPager.ListSource(list).scrollTo(3, messageRows());
        ShadowLooper.idleMainLooper();
        assertEquals(0, list.topOf(3));
    }

    /** The first message, already on screen under the chat's start, stays where it is. */
    @Test public void theFirstMessageLeavesTheChatsStartOnScreen() {
        RowList list = dated();
        new ChatPager.ListSource(list).scrollTo(9, messageRows());
        ShadowLooper.idleMainLooper();
        assertEquals(0, list.off);
    }

    @Test public void aFinishedLoaderStaysFinished() {
        FakeChat chat = new FakeChat(5, 5, 5);
        ChatPager.Loader loader = new ChatPager.Loader(chat, 0, 1);
        run(loader);
        int before = chat.scrolledToOldest;
        assertFalse(loader.tick());
        assertEquals(before, chat.scrolledToOldest);
    }

    /** A chat whose old-end row shows a page loading while one is on its way, the way Instagram's LoadMoreButton does. */
    private static final class LoadingChat implements ChatPager.Source {
        final FakeChat chat;

        LoadingChat(FakeChat chat) {
            this.chat = chat;
        }

        @Override public int count() {
            return chat.count();
        }

        @Override public List<ChatHistory.Entry> entries() {
            return chat.entries();
        }

        @Override public void toOldest() {
            chat.toOldest();
        }

        @Override public void scrollTo(int position) {
            chat.scrollTo(position);
        }

        @Override public void toNewest() {
            chat.toNewest();
        }

        @Override public int end() {
            return chat.shown < chat.total ? ChatPager.END_LOADING : ChatPager.END_EMPTY;
        }
    }

    /**
     * On a weak network a page can take longer than the stall limit. While Instagram shows it loading
     * the jump waits, so it doesn't stop short and claim the first message (#453).
     */
    @Test public void aPageInstagramShowsLoadingIsWaitedForPastTheStallLimit() {
        FakeChat chat = new FakeChat(60, 20, 20);
        chat.looksPerPage = 30;
        ChatPager.Loader loader = new ChatPager.Loader(new LoadingChat(chat), 0, 3);
        run(loader);
        assertEquals(60, chat.shown);
        assertEquals("the oldest message is the last row", 59, chat.scrolledTo);
        assertFalse(loader.stoppedShort());
    }

    /** A page that never comes is waited for about a minute, and the jump then says it stopped short. */
    @Test public void aPageThatNeverComesEndsTheWaitAndSaysSo() {
        FakeChat chat = new FakeChat(60, 20, 20);
        chat.looksPerPage = Integer.MAX_VALUE;
        ChatPager.Loader loader = new ChatPager.Loader(new LoadingChat(chat), 0, 3);
        int ticks = run(loader);
        assertEquals(20, chat.shown);
        assertTrue("it waited about a minute, " + ticks, ticks >= ChatPager.LOADING_LOOKS && ticks <= ChatPager.LOADING_LOOKS + 5);
        assertTrue(loader.stoppedShort());
    }

    /** A chat whose every page fails once and shows Retry, like Instagram's on a dropped connection. */
    private static final class FailingChat implements ChatPager.Source {
        final FakeChat chat;
        /** Whether a tap on Retry brings the page; false for a connection that stays down. */
        final boolean retryWorks;
        boolean failed = true;
        int retries;

        FailingChat(FakeChat chat, boolean retryWorks) {
            this.chat = chat;
            this.retryWorks = retryWorks;
        }

        @Override public int count() {
            return chat.count();
        }

        @Override public List<ChatHistory.Entry> entries() {
            return chat.entries();
        }

        @Override public void toOldest() {
            chat.scrolledToOldest++;
            if (failed) return;
            chat.shown = Math.min(chat.total, chat.shown + chat.page);
            failed = true;
        }

        @Override public void scrollTo(int position) {
            chat.scrollTo(position);
        }

        @Override public void toNewest() {
            chat.toNewest();
        }

        @Override public int end() {
            if (chat.shown >= chat.total) return ChatPager.END_EMPTY;
            return failed ? ChatPager.END_RETRY : ChatPager.END_LOADING;
        }

        @Override public boolean pressEnd() {
            if (end() != ChatPager.END_RETRY) return false;
            retries++;
            if (retryWorks) failed = false;
            return true;
        }
    }

    /** A page that failed shows Retry, and the jump taps it and goes on to the first message (#453). */
    @Test public void aFailedPageIsRetriedAndTheJumpGoesOn() {
        FakeChat chat = new FakeChat(80, 20, 20);
        FailingChat failing = new FailingChat(chat, true);
        ChatPager.Loader loader = new ChatPager.Loader(failing, 0, ChatPager.STALL_LOOKS);
        run(loader);
        assertEquals(80, chat.shown);
        assertEquals("one tap per page", 3, failing.retries);
        assertEquals(79, chat.scrolledTo);
        assertFalse(loader.stoppedShort());
    }

    /** Retry is tapped a few times at most, spaced out, and a connection that stays down ends the jump honestly. */
    @Test public void aRetryThatKeepsFailingIsTappedAFewTimesThenTheJumpSaysItStoppedShort() {
        FakeChat chat = new FakeChat(80, 20, 20);
        FailingChat failing = new FailingChat(chat, false);
        ChatPager.Loader loader = new ChatPager.Loader(failing, 0, ChatPager.STALL_LOOKS);
        run(loader);
        assertEquals(20, chat.shown);
        assertEquals(ChatPager.END_TAPS, failing.retries);
        assertTrue(loader.stoppedShort());
    }

    /** A chat that reached its start shows the empty row, and the jump ends as before, without a stopped-short note. */
    @Test public void theEmptyRowAtTheStartIsTheEndOfTheChat() {
        FakeChat chat = new FakeChat(40, 20, 20);
        ChatPager.Loader loader = new ChatPager.Loader(new LoadingChat(chat), 0, 3);
        run(loader);
        assertEquals(40, chat.shown);
        assertFalse(loader.stoppedShort());
    }

    /** A list that lets its newest rows go as older ones come keeps its length, and still counts as loading. */
    @Test public void aListThatKeepsItsLengthButGetsOlderIsStillLoading() {
        final int total = 100;
        final int window = 20;
        final int[] newest = {0};
        ChatPager.Source windowed = new ChatPager.Source() {
            @Override public int count() {
                return window;
            }

            @Override public List<ChatHistory.Entry> entries() {
                List<ChatHistory.Entry> list = new ArrayList<>();
                for (int row = window - 1; row >= 0; row--) {
                    int age = newest[0] + row;
                    list.add(new ChatHistory.Entry(row, "m" + age, "them", "t", "TEXT", 1_700_000_000_000L + (long) (total - 1 - age) * 86_400_000L, false));
                }
                return list;
            }

            @Override public void toOldest() {
                newest[0] = Math.min(total - window, newest[0] + 5);
            }

            @Override public void scrollTo(int position) {
            }

            @Override public void toNewest() {
            }
        };
        ChatPager.Loader loader = new ChatPager.Loader(windowed, 0, 3);
        run(loader);
        assertEquals("it read back to the first message", total - window, newest[0]);
    }

    /** A list with Instagram's old-end row in it, the row inside a plain frame the way the adapter hands it over. */
    private static final class EndList {
        final FrameLayout list = new FrameLayout(RuntimeEnvironment.getApplication());
        final LoadMoreButton end = new LoadMoreButton(RuntimeEnvironment.getApplication());
        final ChatPager.ListSource source = new ChatPager.ListSource(list);

        EndList() {
            FrameLayout row = new FrameLayout(list.getContext());
            row.addView(end);
            list.addView(new View(list.getContext()));
            list.addView(row);
        }
    }

    @Test public void theOldEndRowIsReadFromTheList() {
        EndList list = new EndList();
        list.end.setDisplayedChild(ChatPager.END_LOADING);
        assertEquals(ChatPager.END_LOADING, list.source.end());
        list.end.setDisplayedChild(ChatPager.END_RETRY);
        assertEquals(ChatPager.END_RETRY, list.source.end());
        list.end.setVisibility(View.GONE);
        assertEquals("a row Instagram hides isn't on screen", ChatPager.END_NONE, list.source.end());
    }

    @Test public void retryAndLoadMoreAreTappedThroughInstagramsListeners() {
        EndList list = new EndList();
        list.end.setDisplayedChild(ChatPager.END_RETRY);
        assertTrue(list.source.pressEnd());
        assertEquals(1, list.end.retryTaps);
        list.end.setDisplayedChild(ChatPager.END_MORE);
        assertTrue(list.source.pressEnd());
        assertEquals(1, list.end.loadMoreTaps);
        list.end.setDisplayedChild(ChatPager.END_LOADING);
        assertFalse("a page loading has nothing to tap", list.source.pressEnd());
        assertEquals(1, list.end.retryTaps);
    }

    /** Any other animator in a row, like a sending spinner, isn't the old-end row. */
    @Test public void anotherAnimatorIsNotTheOldEndRow() {
        FrameLayout listView = new FrameLayout(RuntimeEnvironment.getApplication());
        ViewAnimator other = new ViewAnimator(listView.getContext());
        other.addView(new View(listView.getContext()));
        other.addView(new View(listView.getContext()));
        other.setDisplayedChild(1);
        listView.addView(other);
        ChatPager.ListSource source = new ChatPager.ListSource(listView);
        assertEquals(ChatPager.END_NONE, source.end());
        assertFalse(source.pressEnd());
    }

    /** The menu has a button of its own in the chat's header, first in the row of call buttons and laid out like them (#453). */
    @Test public void theHeaderGetsAButtonBesideTheCallButtonsThatOpensTheMenu() {
        screen = new Screen();
        LinearLayout row = screen.buttonRow();
        View call = row.getChildAt(0);
        glyphs();
        Settings.JUMP_IN_CHAT.save(true);
        Settings.EXPORT_CHAT.save(true);

        ChatPager.opened(screen.fragment);

        assertEquals(2, row.getChildCount());
        View ours = row.getChildAt(0);
        assertEquals(ChatPager.BUTTON_TAG, ours.getTag());
        assertTrue(ours instanceof ImageView);
        assertEquals(call.getLayoutParams().width, ours.getLayoutParams().width);
        assertEquals(call.getLayoutParams().height, ours.getLayoutParams().height);
        assertEquals(((ViewGroup.MarginLayoutParams) call.getLayoutParams()).leftMargin, ((ViewGroup.MarginLayoutParams) ours.getLayoutParams()).leftMargin);
        assertEquals(call.getPaddingTop(), ours.getPaddingTop());
        assertNotNull(ours.getContentDescription());
        assertNotNull(((ImageView) ours).getImageTintList());

        assertTrue(ours.performClick());
        Menu menu = menuOpened();
        assertEquals(3, menu.size());
        for (int i = 0; i < menu.size(); i++) assertNotNull("line " + i + " has no glyph", menu.getItem(i).getIcon());
    }

    /** A line picked in the popup does what the old list did: here an export, in a chat with no messages to find. */
    @Test public void aLinePickedInThePopupRunsIt() {
        screen = new Screen();
        screen.buttonRow();
        glyphs();
        Settings.EXPORT_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        View ours = screen.actionBar.findViewWithTag(ChatPager.BUTTON_TAG);
        assertEquals("Export chat", String.valueOf(ours.getContentDescription()));

        assertTrue(ours.performClick());
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertEquals(1, popup.getMenu().size());
        Shadows.shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(popup.getMenu().getItem(0));
        ShadowLooper.idleMainLooper();

        assertEquals("Couldn't find this chat's messages.", ShadowToast.getTextOfLatestToast());
    }

    /** Instagram empties the row while the chat is up, and the button comes back. */
    @Test public void aButtonTakenOutOfTheRowComesBack() {
        screen = new Screen();
        LinearLayout row = screen.buttonRow();
        glyphs();
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);

        row.removeAllViews();
        ShadowLooper.idleMainLooper();

        assertNotNull("the button didn't come back", row.findViewWithTag(ChatPager.BUTTON_TAG));
        assertEquals(1, row.getChildCount());
    }

    /** Looked at again and again, the header keeps one button. */
    @Test public void theHeaderKeepsOneButton() {
        screen = new Screen();
        LinearLayout row = screen.buttonRow();
        glyphs();
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        ChatPager.opened(screen.fragment);
        assertEquals(2, row.getChildCount());
    }

    /** A chat that leaves the screen takes its button with it, and nothing puts it back. */
    @Test public void aClosedChatTakesItsButtonAway() {
        screen = new Screen();
        LinearLayout row = screen.buttonRow();
        glyphs();
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        assertNotNull(row.findViewWithTag(ChatPager.BUTTON_TAG));

        ChatPager.closed(screen.fragment);
        ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS);

        assertNull(row.findViewWithTag(ChatPager.BUTTON_TAG));
        assertEquals(1, row.getChildCount());
    }

    /** With both switches off, or without Instagram's glyph, the header's row is left as it was. */
    @Test public void noButtonWithTheSwitchesOffOrWithoutAGlyph() {
        screen = new Screen();
        LinearLayout row = screen.buttonRow();
        glyphs();
        ChatPager.opened(screen.fragment);
        assertEquals(1, row.getChildCount());
        ChatPager.closed(screen.fragment);

        ChatPager.iconsForTests = new HashMap<>();
        Settings.JUMP_IN_CHAT.save(true);
        ChatPager.opened(screen.fragment);
        assertEquals(1, row.getChildCount());
    }
}
