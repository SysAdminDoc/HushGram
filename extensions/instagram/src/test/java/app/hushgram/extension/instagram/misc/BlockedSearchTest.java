/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Search blocked accounts (#731): the filter, the pager, the line under the box and where the box goes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BlockedSearchTest {
    /** A small e with an acute accent, so "jose" has an accented name to find. */
    private static final char E_ACUTE = (char) 0x00E9;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** An account as the bridges see it. */
    static final class Account {
        final String username;
        final String fullName;

        Account(String username, String fullName) {
            this.username = username;
            this.fullName = fullName;
        }
    }

    /**
     * Stands in for the list: a redraw runs the filter as Instagram's build does, and a load adds
     * the next page and builds again.
     */
    static class FakeHost implements BlockedSearch.Host {
        final List<Account> accounts = new ArrayList<>();
        final List<List<Account>> pagesToCome = new ArrayList<>();
        int redraws;
        int loads;
        int pages = BlockedSearch.MORE;
        List<?> lastShown;

        @Override
        public void redraw(Object screen) {
            redraws++;
            lastShown = BlockedSearch.filter(new ArrayList<>(accounts));
        }

        @Override
        public void loadMore(Object screen) {
            loads++;
            if (!pagesToCome.isEmpty()) accounts.addAll(pagesToCome.remove(0));
            if (pagesToCome.isEmpty()) pages = 0;
            lastShown = BlockedSearch.filter(new ArrayList<>(accounts));
        }

        @Override
        public int pages(Object screen) {
            return pages;
        }

        @Override
        public String username(Object account) {
            return ((Account) account).username;
        }

        @Override
        public String fullName(Object account) {
            return ((Account) account).fullName;
        }
    }

    /** A pull to refresh frame, named the way such frames are. */
    public static class PullRefreshFrame extends FrameLayout {
        public PullRefreshFrame(Context context) {
            super(context);
        }
    }

    /** One that only extends such a frame, so its own name doesn't say so. */
    public static final class InstagramFrame extends PullRefreshFrame {
        public InstagramFrame(Context context) {
            super(context);
        }
    }

    private FakeHost host;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.BLOCKED_SEARCH.save(true);
        host = new FakeHost();
        BlockedSearch.host = host;
        BlockedSearch.query = "";
        BlockedSearch.loaded = 0;
        BlockedSearch.shown = 0;
        BlockedSearch.TYPED.clear();
        BlockedSearch.live = new WeakReference<>(null);
    }

    @After
    public void restore() {
        Settings.BLOCKED_SEARCH.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        BlockedSearch.host = new BlockedSearch.Bridges();
        BlockedSearch.query = "";
        BlockedSearch.loaded = 0;
        BlockedSearch.shown = 0;
        BlockedSearch.TYPED.clear();
        BlockedSearch.live = new WeakReference<>(null);
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.BLOCKED_SEARCH.resetToDefault();
        assertFalse(Settings.BLOCKED_SEARCH.get());
        assertFalse(BlockedSearch.on());
    }

    @Test
    public void wordsAreLowerCaseWithoutAccentsOrALeadingAt() {
        assertEquals("jose", BlockedSearch.normalize("  JOS" + E_ACUTE + " "));
        assertEquals("", BlockedSearch.normalize(null));
        assertEquals(Arrays.asList("mia", "lee"), BlockedSearch.words("  @Mia   LEE "));
        assertEquals(Collections.emptyList(), BlockedSearch.words(" @ "));
        assertEquals(Collections.emptyList(), BlockedSearch.words(null));
    }

    @Test
    public void anAccountMatchesWhenItsNamesHoldEveryWord() {
        Account mia = new Account("mia.lee", "Mia Lee");
        Account jose = new Account("jdiaz", "Jos" + E_ACUTE + " D" + "iaz");
        Account nameless = new Account("sam_k", null);
        assertTrue(BlockedSearch.matches(mia, BlockedSearch.words("lee mia")));
        assertTrue(BlockedSearch.matches(mia, BlockedSearch.words("@mia.l")));
        assertFalse(BlockedSearch.matches(mia, BlockedSearch.words("mia bob")));
        assertTrue("accents don't matter", BlockedSearch.matches(jose, BlockedSearch.words("jose")));
        assertTrue("the user name alone", BlockedSearch.matches(jose, BlockedSearch.words("JDIAZ")));
        assertTrue("no full name", BlockedSearch.matches(nameless, BlockedSearch.words("sam")));
        assertFalse(BlockedSearch.matches(nameless, BlockedSearch.words("null")));
    }

    @Test
    public void theFilterKeepsMatchesOnlyWithWordsAndTheSwitchOn() {
        List<Account> accounts = Arrays.asList(new Account("mia.lee", "Mia Lee"), new Account("bob", "Bob Ross"), new Account("leon", ""));

        assertSame("no search", accounts, BlockedSearch.filter(accounts));
        assertEquals(3, BlockedSearch.loaded);
        assertEquals(3, BlockedSearch.shown);

        BlockedSearch.query = "le";
        List<?> kept = BlockedSearch.filter(accounts);
        assertEquals(Arrays.asList(accounts.get(0), accounts.get(2)), kept);
        assertEquals(3, BlockedSearch.loaded);
        assertEquals(2, BlockedSearch.shown);

        Settings.BLOCKED_SEARCH.save(false);
        assertSame("switch off", accounts, BlockedSearch.filter(accounts));
        Settings.BLOCKED_SEARCH.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", accounts, BlockedSearch.filter(accounts));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        assertNull(BlockedSearch.filter(null));
    }

    @Test
    public void aBrokenBridgeLeavesTheListWhole() {
        BlockedSearch.host = new FakeHost() {
            @Override
            public String username(Object account) {
                throw new IllegalStateException("no field");
            }
        };
        BlockedSearch.query = "mia";
        List<Account> accounts = Collections.singletonList(new Account("mia", "Mia"));
        assertSame(accounts, BlockedSearch.filter(accounts));
        assertTrue(report(), report().contains("'" + BlockedSearch.HOOK + "' hook (it threw java.lang.IllegalStateException)"));
    }

    @Test
    public void thePagerWaitsForAPageAndStopsWhenThereAreNoMore() {
        BlockedSearch.Pager pager = new BlockedSearch.Pager();
        assertEquals("no list", BlockedSearch.STOP, pager.next(-1, 0));
        assertEquals(BlockedSearch.LOAD, pager.next(BlockedSearch.MORE, 20));
        assertEquals("one on its way", BlockedSearch.WAIT, pager.next(BlockedSearch.MORE | BlockedSearch.LOADING, 20));
        assertEquals(BlockedSearch.LOAD, pager.next(BlockedSearch.MORE, 40));
        assertEquals("the end", BlockedSearch.STOP, pager.next(0, 60));
    }

    @Test
    public void thePagerGivesUpOnPagesThatAddNobody() {
        BlockedSearch.Pager pager = new BlockedSearch.Pager();
        assertEquals(BlockedSearch.LOAD, pager.next(BlockedSearch.MORE, 20));
        for (int stall = 1; stall < BlockedSearch.STALLS; stall++) {
            assertEquals("stall " + stall, BlockedSearch.LOAD, pager.next(BlockedSearch.MORE, 20));
        }
        assertEquals(BlockedSearch.STOP, pager.next(BlockedSearch.MORE, 20));

        BlockedSearch.Pager growing = new BlockedSearch.Pager();
        assertEquals(BlockedSearch.LOAD, growing.next(BlockedSearch.MORE, 20));
        assertEquals(BlockedSearch.LOAD, growing.next(BlockedSearch.MORE, 20));
        assertEquals(BlockedSearch.LOAD, growing.next(BlockedSearch.MORE, 20));
        assertEquals("a page that adds someone starts the count again", BlockedSearch.LOAD, growing.next(BlockedSearch.MORE, 21));
        assertEquals(BlockedSearch.LOAD, growing.next(BlockedSearch.MORE, 21));
    }

    @Test
    public void thePagerTriesAFailedPageOnce() {
        int failed = BlockedSearch.MORE | BlockedSearch.FAILED;
        BlockedSearch.Pager pager = new BlockedSearch.Pager();
        assertEquals(BlockedSearch.LOAD, pager.next(failed, 20));
        assertEquals(BlockedSearch.STOP, pager.next(failed, 20));

        BlockedSearch.Pager recovered = new BlockedSearch.Pager();
        assertEquals(BlockedSearch.LOAD, recovered.next(failed, 20));
        assertEquals("the retry brought a page", BlockedSearch.LOAD, recovered.next(failed, 40));
        assertEquals(BlockedSearch.STOP, recovered.next(failed, 40));
    }

    @Test
    public void theLineSaysWhatWasSearched() {
        assertEquals("Matches: 3 of 120 accounts loaded so far. Loading the rest of your list.",
                BlockedSearch.statusText(BlockedSearch.MORE, true, 120, 3));
        assertEquals("Matches: 1 of 1 account loaded so far. Loading the rest of your list.",
                BlockedSearch.statusText(BlockedSearch.LOADING, true, 1, 1));
        assertEquals("Matches: 0 of the 50 accounts that loaded. Instagram didn't send the rest.",
                BlockedSearch.statusText(BlockedSearch.MORE | BlockedSearch.FAILED, false, 50, 0));
        assertEquals("Matches: 4 of 1000 blocked accounts", BlockedSearch.statusText(0, false, 1000, 4));
        assertEquals("Matches: 1 of 1 blocked account", BlockedSearch.statusText(-1, true, 1, 1));
    }

    @Test
    public void inAVerticalLayoutTheBoxGoesRightAboveTheList() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView header = new TextView(activity);
            View list = new View(activity);
            column.addView(header);
            column.addView(list);
            root.addView(column);
            Object screen = new Object();

            BlockedSearch.attach(screen, root, list);
            BlockedSearch.attach(screen, root, list);

            assertEquals(3, column.getChildCount());
            assertSame(header, column.getChildAt(0));
            assertEquals(BlockedSearch.TAG, column.getChildAt(1).getTag());
            assertSame(list, column.getChildAt(2));
            assertTrue(report().contains(BlockedSearch.COUNT_BOX));
        }
    }

    @Test
    public void elsewhereTheListMovesIntoAColumnUnderTheBox() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            View before = new View(activity);
            View list = new View(activity);
            FrameLayout.LayoutParams where = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            where.topMargin = 7;
            root.addView(before);
            root.addView(list, where);

            BlockedSearch.attach(new Object(), root, list);

            assertEquals(2, root.getChildCount());
            assertSame(before, root.getChildAt(0));
            LinearLayout column = (LinearLayout) root.getChildAt(1);
            assertEquals(LinearLayout.VERTICAL, column.getOrientation());
            assertSame("the column takes the list's place and layout", where, column.getLayoutParams());
            assertEquals(BlockedSearch.TAG, column.getChildAt(0).getTag());
            assertSame(list, column.getChildAt(1));
            assertEquals(1f, ((LinearLayout.LayoutParams) list.getLayoutParams()).weight, 0f);
        }
    }

    @Test
    public void aPullToRefreshFrameStaysAroundTheList() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            InstagramFrame refresh = new InstagramFrame(activity);
            View list = new View(activity);
            refresh.addView(list);
            root.addView(refresh);

            BlockedSearch.attach(new Object(), root, list);

            assertSame(refresh, list.getParent());
            LinearLayout column = (LinearLayout) root.getChildAt(0);
            assertEquals(BlockedSearch.TAG, column.getChildAt(0).getTag());
            assertSame(refresh, column.getChildAt(1));
        }
    }

    @Test
    public void aListThatIsTheWholeScreenGetsNoBox() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout list = new FrameLayout(activity);
            BlockedSearch.attach(new Object(), list, list);
            assertEquals(0, list.getChildCount());

            PullRefreshFrame root = new PullRefreshFrame(activity);
            View inner = new View(activity);
            root.addView(inner);
            BlockedSearch.attach(new Object(), root, inner);
            assertEquals("a refresh frame that is the root leaves no room either", 1, root.getChildCount());
            assertTrue(report().contains(BlockedSearch.COUNT_NO_PLACE));
            assertFalse(report().contains(BlockedSearch.COUNT_BOX));
        }
    }

    @Test
    public void withTheSwitchOffOrPausedNothingIsAdded() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            FrameLayout holder = new FrameLayout(activity);
            View list = new View(activity);
            holder.addView(list);
            root.addView(holder);

            Settings.BLOCKED_SEARCH.save(false);
            BlockedSearch.attach(new Object(), root, list);
            assertEquals(1, holder.getChildCount());

            Settings.BLOCKED_SEARCH.save(true);
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            BlockedSearch.attach(new Object(), root, list);
            assertEquals(1, holder.getChildCount());
            assertSame(list, holder.getChildAt(0));
            assertFalse(report().contains(BlockedSearch.COUNT_BOX));
        }
    }

    /**
     * Typing redraws the list at once and loads one page after another while there are words, the
     * line says how far it got, a screen made again gets its search back, and clearing the box
     * shows the whole list with no line.
     */
    @Test
    public void typingLoadsTheRestAndTheLineFollows() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            host.accounts.addAll(Arrays.asList(new Account("mia.lee", "Mia Lee"), new Account("bob", "Bob")));
            host.pagesToCome.add(Arrays.asList(new Account("lee_r", "Rae Lee"), new Account("kim", "Kim")));
            host.pagesToCome.add(Collections.singletonList(new Account("al", "Al Lee")));
            FrameLayout root = new FrameLayout(activity);
            View list = new View(activity);
            root.addView(list);
            activity.setContentView(root);
            ShadowLooper.idleMainLooper();
            Object screen = new Object();

            BlockedSearch.attach(screen, root, list);
            ViewGroup box = (ViewGroup) root.findViewWithTag(BlockedSearch.TAG);
            EditText field = (EditText) box.getChildAt(0);
            TextView status = (TextView) box.getChildAt(1);
            assertEquals("Search blocked accounts", field.getHint().toString());
            ShadowLooper.idleMainLooper();
            assertEquals(View.GONE, status.getVisibility());

            field.setText("lee");
            assertEquals(1, host.redraws);
            assertEquals("lee", BlockedSearch.query);
            assertEquals(1, host.lastShown.size());
            ShadowLooper.idleMainLooper();
            assertEquals("the first page is asked for", 1, host.loads);
            assertEquals(View.VISIBLE, status.getVisibility());
            assertEquals("Matches: 2 of 4 accounts loaded so far. Loading the rest of your list.", status.getText().toString());

            ShadowLooper.idleMainLooper(BlockedSearch.TICK_MS, TimeUnit.MILLISECONDS);
            assertEquals(2, host.loads);
            assertEquals(3, host.lastShown.size());
            ShadowLooper.idleMainLooper(BlockedSearch.TICK_MS, TimeUnit.MILLISECONDS);
            assertEquals("no more pages", 2, host.loads);
            assertEquals("Matches: 3 of 5 blocked accounts", status.getText().toString());
            ShadowLooper.idleMainLooper(BlockedSearch.TICK_MS * 4, TimeUnit.MILLISECONDS);
            assertEquals(2, host.loads);

            FrameLayout again = new FrameLayout(activity);
            View list2 = new View(activity);
            again.addView(list2);
            BlockedSearch.query = "";
            BlockedSearch.attach(screen, again, list2);
            assertEquals("the search comes back", "lee", BlockedSearch.query);
            EditText restored = (EditText) ((ViewGroup) again.findViewWithTag(BlockedSearch.TAG)).getChildAt(0);
            assertEquals("lee", restored.getText().toString());

            field.setText("");
            assertEquals("", BlockedSearch.query);
            assertEquals(5, host.lastShown.size());
            ShadowLooper.idleMainLooper();
            assertEquals(View.GONE, status.getVisibility());
        }
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }
}
