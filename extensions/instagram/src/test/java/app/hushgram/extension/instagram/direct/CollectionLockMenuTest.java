/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A long press on a collection's tile in Saved offers Lock collection, which locks it at once, or
 * Remove lock for one that's locked, which asks every time first. The patch hands each tile to
 * {@link CollectionLockMenu#tile} as Instagram makes it. Paused, or on a tile whose name can't be
 * read, it does nothing, and a tile with a long press of its own keeps it (#678).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CollectionLockMenuTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = MessagesLockTest.ME;
    private static final String OTHER = "5678";

    private static final int PICTURE = 0x7f0b0030;
    private static final int NAME = 0x7f0b0031;
    /** Where a test tile keeps its count of taps. */
    private static final int TAPS = 0x7f0b0032;

    /** Each ask's two answers, oldest first, and the title it asked with. */
    private final List<Runnable[]> asks = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();

    @Before
    public void enable() {
        fresh();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOCK_MESSAGES.save(false);
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        HookStatus.clear();
        ShadowPopupMenu.setLatestPopupMenu(null);
    }

    private void fresh() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(ME);
        Map<String, Integer> ids = new HashMap<>();
        ids.put(CollectionLockMenu.NAME, NAME);
        MessagesLock.idsForTests = ids;
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> {
            titles.add(MessagesLock.askTitle());
            asks.add(new Runnable[]{confirmed, notConfirmed});
        };
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        ChatAccounts.resetForTests();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCKED_COLLECTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ShadowPopupMenu.setLatestPopupMenu(null);
    }

    @Test
    public void lockCollectionLocksAtOnceAndRemoveLockAsksEveryTime() {
        Activity activity = saved("Trips", "Recipes");
        View trips = tile(activity, "Trips");
        assertTrue("the tile got no long press", trips.isLongClickable());

        PopupMenu menu = longPress(trips);
        assertEquals(Collections.singletonList("Lock collection"), items(menu));
        pick(menu);
        assertTrue(CollectionLocks.listed(ME, "Trips"));
        assertFalse("locked the collection next to it", CollectionLocks.listed(ME, "Recipes"));
        assertEquals("Trips is locked", toast());
        assertEquals("locking asked", 0, asks.size());

        // Taking it off asks, names the collection, and changes nothing until it's answered.
        menu = longPress(trips);
        assertEquals(Collections.singletonList("Remove lock"), items(menu));
        pick(menu);
        assertEquals(1, asks.size());
        assertEquals("Unlock this collection", titles.get(0));
        assertTrue("the lock came off before the ask was answered", CollectionLocks.listed(ME, "Trips"));
        asks.get(0)[0].run();
        idle();
        assertFalse(CollectionLocks.listed(ME, "Trips"));
        assertEquals("Trips is unlocked", toast());
        assertNotEquals("the ask's title stuck", "Unlock this collection", MessagesLock.askTitle());

        // With everything just unlocked, it still asks, and a no leaves it locked.
        pick(longPress(trips));
        assertTrue(CollectionLocks.listed(ME, "Trips"));
        pick(longPress(trips));
        assertEquals("an open session took the lock off without asking", 2, asks.size());
        asks.get(1)[1].run();
        idle();
        assertTrue("a no took the lock off", CollectionLocks.listed(ME, "Trips"));

        // A tap still opens the collection as Instagram bound it.
        assertTrue(trips.performClick());
        assertEquals(1, opened(trips)[0]);
    }

    @Test
    public void theNameIsReadAsItsPressedAndEachAccountKeepsItsOwn() {
        Activity activity = saved("Trips");
        View tile = tile(activity, "Trips");
        pick(longPress(tile));
        assertTrue(CollectionLocks.listed(ME, "Trips"));

        // Instagram reuses a tile for another collection as the list scrolls.
        ((TextView) tile.findViewById(NAME)).setText("Recipes");
        PopupMenu menu = longPress(tile);
        assertEquals(Collections.singletonList("Lock collection"), items(menu));
        pick(menu);
        assertTrue(CollectionLocks.listed(ME, "Recipes"));

        ChatAccounts.signInForTests(OTHER);
        ((TextView) tile.findViewById(NAME)).setText("Trips");
        assertEquals("another account's lock showed", Collections.singletonList("Lock collection"), items(longPress(tile)));

        // With no account known, nothing is saved and the toast says so.
        ChatAccounts.signInForTests(null);
        ((TextView) tile.findViewById(NAME)).setText("Pets");
        pick(longPress(tile));
        assertEquals("Couldn't change the lock. Try again.", toast());
        assertFalse(CollectionLocks.listed(null, "Pets"));
        assertTrue(CollectionLocks.listed(ME, "Trips"));
        assertFalse(CollectionLocks.listed(OTHER, "Trips"));
    }

    @Test
    public void pausedUnnamedAndInstagramsOwnLongPressAreLeftAlone() {
        Activity activity = saved("", "Recipes");
        boolean[] own = {false};
        View mine = tileView(activity, "Trips");
        mine.setOnLongClickListener(view -> own[0] = true);
        CollectionLockMenu.tile(mine);
        assertTrue(mine.performLongClick());
        assertTrue("Instagram's own long press was replaced", own[0]);
        assertNull(ShadowPopupMenu.getLatestPopupMenu());

        View unnamed = tile(activity, "");
        assertTrue(unnamed.isLongClickable());
        assertFalse("a tile with no name offered a menu", unnamed.performLongClick());
        assertNull(ShadowPopupMenu.getLatestPopupMenu());

        // Paused, a long press does nothing, and the tile is back once HushGram is.
        View recipes = tile(activity, "Recipes");
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("a paused long press offered a menu", recipes.performLongClick());
        assertNull(ShadowPopupMenu.getLatestPopupMenu());
        PauseForTests.resume();
        assertEquals(Collections.singletonList("Lock collection"), items(longPress(recipes)));
        assertEquals(0, asks.size());
        assertFalse(CollectionLocks.any());

        // A tile the patch couldn't hand over is never a reason to throw.
        CollectionLockMenu.tile(null);
    }

    /**
     * Only the tiles the patch hands over get the long press: nothing looks for them while screens
     * draw, so a frame of Saved with a tile Instagram didn't make leaves it alone.
     */
    @Test
    public void onlyTilesThePatchHandsOverGetIt() {
        Activity activity = saved("Trips");
        LinearLayout grid = (LinearLayout) tile(activity, "Trips").getParent();
        View stray = tileView(activity, "Recipes");
        grid.addView(stray, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        MessagesLockTest.layout(activity);
        MessagesLock.check(activity);
        idle();
        assertTrue(tile(activity, "Trips").isLongClickable());
        assertFalse("a frame gave a tile its long press", stray.isLongClickable());
    }

    /** Saved's row of collections, each tile made, handed to the patch's hook, then bound with its tap. */
    private static Activity saved(String... names) {
        Activity activity = MessagesLockTest.focused().get();
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (String name : names) {
            grid.addView(made(activity, name), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        }
        activity.setContentView(grid);
        MessagesLockTest.layout(activity);
        return activity;
    }

    /** A tile as Instagram makes it, handed to the hook, then bound with the tap that opens it. */
    private static View made(Activity activity, String name) {
        View tile = tileView(activity, name);
        CollectionLockMenu.tile(tile);
        int[] taps = new int[1];
        tile.setTag(TAPS, taps);
        tile.setOnClickListener(view -> taps[0]++);
        return tile;
    }

    private static int[] opened(View tile) {
        return (int[]) tile.getTag(TAPS);
    }

    /** A picture over its name, as Instagram lays a tile out. Nothing about it is clickable until it's bound. */
    private static View tileView(Activity activity, String name) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setTag(name);
        FrameLayout frame = new FrameLayout(activity);
        View picture = new View(activity);
        picture.setId(PICTURE);
        frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        TextView label = new TextView(activity);
        label.setId(NAME);
        label.setText(name);
        tile.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        return tile;
    }

    private static View tile(Activity activity, String name) {
        View tile = activity.getWindow().getDecorView().findViewWithTag(name);
        assertNotNull("no tile " + name, tile);
        return tile;
    }

    private static PopupMenu longPress(View tile) {
        PopupMenu before = ShadowPopupMenu.getLatestPopupMenu();
        assertTrue("the long press wasn't taken", tile.performLongClick());
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("no menu", menu);
        assertNotSame("no new menu", before, menu);
        assertTrue(Shadows.shadowOf(menu).isShowing());
        return menu;
    }

    private static List<String> items(PopupMenu popup) {
        Menu menu = popup.getMenu();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) titles.add(String.valueOf(menu.getItem(i).getTitle()));
        return titles;
    }

    /** Taps the menu's one item. */
    private static void pick(PopupMenu popup) {
        assertEquals(1, popup.getMenu().size());
        assertTrue(Shadows.shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(popup.getMenu().getItem(0)));
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The latest toast without the marks that keep a name's direction its own. */
    private static String toast() {
        String text = String.valueOf(ShadowToast.getTextOfLatestToast());
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x2066 || c > 0x2069) plain.append(c);
        }
        return plain.toString();
    }
}
