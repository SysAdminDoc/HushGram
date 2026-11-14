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
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A Saved collection on the list gets Saved's cover over its page by itself, with no lock switch on,
 * and the same ask as every lock. Other collections, the rest of Saved and the sheet a post is saved
 * into stay as they are, and each account keeps a list of its own (#678).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CollectionLocksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = MessagesLockTest.ME;
    private static final String OTHER = "5678";

    private static final int FRAME = 0x7f0b0001;
    private static final int LIST = 0x7f0b0002;
    private static final int CHAT = 0x7f0b0003;
    /** Saved's pages, in {@link MessagesLock#SAVED_PAGES}' order: its home first, a collection's page second. */
    private static final int[] SAVED_PAGES = {0x7f0b0010, 0x7f0b0011, 0x7f0b0012, 0x7f0b0013, 0x7f0b0014, 0x7f0b0015};
    private static final int HEADER = 0x7f0b0020;
    private static final int BAR = 0x7f0b0021;

    /** Each ask's two answers, oldest first. */
    private final List<Runnable[]> asks = new ArrayList<>();

    @Before
    public void enable() {
        fresh();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOCK_MESSAGES.save(false);
        HookStatus.clear();
    }

    /** The lock as a fresh start finds it, signed in to [ME], with this test's ids and asks. */
    private void fresh() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(ME);
        Map<String, Integer> ids = new HashMap<>();
        ids.put(MessagesLock.INBOX_FRAME, FRAME);
        ids.put(MessagesLock.INBOX_LIST, LIST);
        ids.put(MessagesLock.CHAT_ROOT, CHAT);
        for (int i = 0; i < SAVED_PAGES.length; i++) ids.put(MessagesLock.SAVED_PAGES[i], SAVED_PAGES[i]);
        ids.put(CollectionLocks.TITLES[0], HEADER);
        ids.put(CollectionLocks.TITLES[1], BAR);
        MessagesLock.idsForTests = ids;
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> asks.add(new Runnable[]{confirmed, notConfirmed});
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        ChatAccounts.resetForTests();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.LOCK_SAVED.resetToDefault();
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void aCollectionOnTheListIsCoveredAndAsksForWithNoLockOn() {
        assertEquals("a collection's page isn't one of Saved's", MessagesLock.SAVED_PAGES[1], CollectionLocks.PAGE);
        assertTrue(CollectionLocks.add(ME, "Trips"));
        Activity activity = collection("Trips").get();
        View content = activity.findViewById(android.R.id.content);

        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over the collection", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(content).width(), cover.getWidth());
        assertEquals(visible(content).height(), cover.getHeight());
        assertTrue(texts(cover).toString(), texts(cover).contains("This collection is locked"));
        assertEquals(1, asks.size());
        assertEquals("Unlock this collection", MessagesLock.askTitle());
        assertFalse("the settings got locked with it", MessagesLock.locked());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals("the unlock left it covered", View.GONE, cover.getVisibility());

        // Back after a leave, with Lock again at Right away, it's covered and asked for again.
        MessagesLock.relock(true);
        MessagesLock.check(activity);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(2, asks.size());
    }

    @Test
    public void otherCollectionsSavedAndTheSaveSheetStayOpen() {
        assertTrue(CollectionLocks.add(ME, "Trips"));

        for (String name : new String[]{"Recipes", "Trips 2"}) {
            Activity activity = collection(name).get();
            MessagesLock.check(activity);
            assertNull(name + " got a cover", cover(activity));
        }

        Activity home = page(SAVED_PAGES[0]).get();
        MessagesLock.check(home);
        assertNull("Saved's home got a cover with Lock Saved off", cover(home));

        // The sheet a post is saved into names the collection too, but isn't its page.
        Activity sheet = MessagesLockTest.focused().get();
        LinearLayout rows = new LinearLayout(sheet);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String name : new String[]{"Save to collection", "Trips", "Recipes"}) {
            TextView row = new TextView(sheet);
            row.setText(name);
            rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        }
        sheet.setContentView(rows);
        MessagesLockTest.layout(sheet);
        MessagesLock.check(sheet);
        assertNull("saving into it got a cover", cover(sheet));

        // A page whose name can't be read yet isn't covered.
        ActivityController<Activity> unnamed = collection("Trips");
        unnamed.get().findViewById(HEADER).setVisibility(View.GONE);
        unnamed.get().findViewById(BAR).setVisibility(View.GONE);
        MessagesLockTest.layout(unnamed.get());
        MessagesLock.check(unnamed.get());
        assertNull("a page with no name showing got a cover", cover(unnamed.get()));

        assertEquals("something here asked", 0, asks.size());
    }

    /** With Lock Saved on, a collection on the list is Saved's to lock, and one unlock opens both. */
    @Test
    public void lockSavedKeepsItsOwnCoverOverACollectionOnTheList() {
        Settings.LOCK_SAVED.save(true);
        assertTrue(CollectionLocks.add(ME, "Trips"));
        Activity activity = collection("Trips").get();

        MessagesLock.check(activity);

        View cover = cover(activity);
        assertTrue(texts(cover).toString(), texts(cover).contains("Saved is locked"));
        assertEquals("Unlock Saved", MessagesLock.askTitle());
        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover.getVisibility());
        assertEquals(1, asks.size());
    }

    @Test
    public void eachAccountKeepsItsOwnList() {
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertEquals(Collections.singletonList("Trips"), CollectionLocks.names(ME));
        assertEquals(Collections.emptyList(), CollectionLocks.names(OTHER));
        assertFalse("locked for an account nobody knows", CollectionLocks.add(null, "Recipes"));

        ChatAccounts.signInForTests(OTHER);
        Activity other = collection("Trips").get();
        MessagesLock.check(other);
        assertNull("another account's collection of the same name got a cover", cover(other));
        assertEquals(0, asks.size());
        assertTrue(CollectionLocks.remove(OTHER, "Trips"));
        assertTrue("taking it off another account's list took it off mine", CollectionLocks.listed(ME, "Trips"));

        ChatAccounts.signInForTests(ME);
        Activity mine = collection("Trips").get();
        MessagesLock.check(mine);
        assertNotNull(cover(mine));

        assertTrue(CollectionLocks.remove(ME, "Trips"));
        assertFalse(CollectionLocks.any());
    }

    /**
     * With nothing on any list and no lock on, a collection isn't looked for by name, so a screen
     * isn't walked for it. Once the list in settings was opened, the one that shows is learned for
     * the account in front, and offered until it's on the list.
     */
    @Test
    public void theCollectionOpenedLastIsLearnedOnceTheListWasOpened() {
        Activity activity = collection("Trips").get();
        MessagesLock.check(activity);
        assertNull("learned with nothing asking for it", CollectionLocks.lastOpened(ME));

        CollectionLocks.lookForNames();
        SystemClock.sleep(MessagesLock.IDLE_LOOK_MS);
        MessagesLock.check(activity);

        assertEquals("Trips", CollectionLocks.lastOpened(ME));
        assertNull("offered to another account", CollectionLocks.lastOpened(OTHER));
        assertNull(cover(activity));
        assertEquals(0, asks.size());
        assertTrue(CollectionLocks.add(ME, "Trips"));
        assertNull("still offered once it's on the list", CollectionLocks.lastOpened(ME));

        // With Lock your messages on, the screen is looked at on every frame, and still learns it.
        fresh();
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        Settings.LOCK_MESSAGES.save(true);
        CollectionLocks.lookForNames();
        Activity messagesOn = collection("Recipes").get();
        MessagesLock.check(messagesOn);
        assertEquals("Recipes", CollectionLocks.lastOpened(ME));
        assertNull("Lock your messages covered a collection", cover(messagesOn));
    }

    /** A collection's page under the top bar, its header naming it over the tabs of its posts, as Instagram lays it out. */
    private static ActivityController<Activity> collection(String name) {
        ActivityController<Activity> controller = MessagesLockTest.focused();
        Activity activity = controller.get();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView bar = new TextView(activity);
        bar.setId(BAR);
        bar.setText(name);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
        FrameLayout body = new FrameLayout(activity);
        TextView header = new TextView(activity);
        header.setId(HEADER);
        header.setText(name);
        body.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        View pager = new View(activity);
        pager.setId(SAVED_PAGES[1]);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200);
        below.topMargin = 120;
        body.addView(pager, below);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(root);
        MessagesLockTest.layout(activity);
        return controller;
    }

    /** One of Saved's other pages under a top bar. */
    private static ActivityController<Activity> page(int id) {
        ActivityController<Activity> controller = MessagesLockTest.focused();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        View view = new View(activity);
        view.setId(id);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200);
        params.topMargin = 150;
        root.addView(view, params);
        activity.setContentView(root);
        MessagesLockTest.layout(activity);
        return controller;
    }

    /** The cover over Saved in the activity's window, if any. */
    private static View cover(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (child instanceof MessagesLock.Cover && ((MessagesLock.Cover) child).screen.equals(MessagesLock.SAVED)) return child;
        }
        return null;
    }

    private static Rect visible(View view) {
        Rect area = new Rect();
        assertTrue(view.getGlobalVisibleRect(area));
        return area;
    }

    private static List<String> texts(View view) {
        List<String> found = new ArrayList<>();
        if (view instanceof TextView) found.add(String.valueOf(((TextView) view).getText()));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(texts(group.getChildAt(i)));
        }
        return found;
    }
}
