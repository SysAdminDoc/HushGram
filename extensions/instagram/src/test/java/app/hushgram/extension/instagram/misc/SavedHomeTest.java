/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The bookmark's Saved (#762): it opens on All, the tabbed Saved the menu opens, with the menu's back
 * arrow, and a switch leaves the + for a new collection off the top of both Saved screens. Two more
 * (#810) leave the Reels tab out of Saved and the Saved bookmark off the Search tab.
 */
@RunWith(RobolectricTestRunner.class)
public class SavedHomeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The two Saved screens as the Saved link's screen sees them: the tabbed one and the grid. */
    static final class Tabbed {
    }

    static final class Grid {
    }

    @Before public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.HIDE_SAVED_NEW_COLLECTION.resetToDefault();
        Settings.HIDE_SAVED_REELS_TAB.resetToDefault();
        Settings.HIDE_EXPLORE_SAVED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
    }

    private static Activity linkScreen(Intent intent) {
        return Robolectric.buildActivity(Activity.class, intent).get();
    }

    private static String savedLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SAVED_ON_PROFILE)) return line;
        }
        return "";
    }

    @Test public void theBookmarksIntentIsMarkedAndKeepsTheSession() {
        Intent intent = ProfileSaved.savedIntent("com.instagram.android", ProfileSaved.SAVED_HANDLER, "token-1");
        Bundle bundle = intent.getBundleExtra(ProfileSaved.LINK_BUNDLE);
        assertEquals("token-1", bundle.getString(ProfileSaved.SESSION_TOKEN));
        assertTrue(bundle.getBoolean(SavedHome.FROM_BOOKMARK));
        assertEquals(1, SavedHome.fromBookmark(linkScreen(intent)));
    }

    @Test public void instagramsOwnSavedLinkIsLeftAsItIs() {
        Bundle bundle = new Bundle();
        bundle.putString(ProfileSaved.SESSION_TOKEN, "token-1");
        Intent own = new Intent().putExtra(ProfileSaved.LINK_BUNDLE, bundle);
        assertEquals(0, SavedHome.fromBookmark(linkScreen(own)));
        assertEquals(0, SavedHome.fromBookmark(linkScreen(new Intent())));
        assertEquals(0, SavedHome.fromBookmark(null));
    }

    @Test public void theBookmarkGetsTheTabbedSavedAndCountsIt() {
        Tabbed tabbed = new Tabbed();
        Grid grid = new Grid();
        assertSame(tabbed, SavedHome.pick(tabbed, grid));
        assertTrue(savedLine(), savedLine().contains(SavedHome.OPENED_ON_ALL + " 1"));
    }

    @Test public void anAccountWithoutTheTabbedSavedKeepsTheLinksOwnGrid() {
        Grid links = new Grid();
        assertSame(links, SavedHome.pick(new Grid(), links));
        assertSame(links, SavedHome.pick(null, links));
        assertTrue(savedLine(), !savedLine().contains(SavedHome.OPENED_ON_ALL));
    }

    @Test public void theBookmarksSavedGetsTheBackArrow() {
        Tabbed bookmarks = new Tabbed();
        assertSame(bookmarks, SavedHome.pick(bookmarks, new Grid()));
        assertTrue(SavedHome.showBack(bookmarks));
        // Another tabbed Saved, the menu's or one Instagram rebuilt, keeps Instagram's own answer.
        assertFalse(SavedHome.showBack(new Tabbed()));
        assertFalse(SavedHome.showBack(null));
    }

    @Test public void theLinksOwnGridGetsNoArrow() {
        Grid links = new Grid();
        assertSame(links, SavedHome.pick(new Grid(), links));
        assertFalse(SavedHome.showBack(links));
    }

    @Test public void thePlusStaysUntilTheSwitchIsOn() {
        assertEquals(0, SavedHome.hideNewCollection());
        Settings.HIDE_SAVED_NEW_COLLECTION.save(true);
        assertEquals(1, SavedHome.hideNewCollection());
        assertTrue(savedLine(), savedLine().contains(SavedHome.NEW_COLLECTION_HIDDEN + " 1"));
        Settings.HIDE_SAVED_NEW_COLLECTION.save(false);
        assertEquals(0, SavedHome.hideNewCollection());
    }

    @Test public void aPauseBringsThePlusBack() {
        Settings.HIDE_SAVED_NEW_COLLECTION.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, SavedHome.hideNewCollection());
        PauseForTests.resume();
        assertEquals(1, SavedHome.hideNewCollection());
    }

    @Test public void aFailingSwitchReadKeepsThePlus() {
        assertEquals(0, SavedHome.hideNewCollection(() -> {
            throw new IllegalStateException("not read yet");
        }));
        assertTrue(savedLine(), !savedLine().contains(SavedHome.NEW_COLLECTION_HIDDEN));
    }

    /** Saved's tabs as Instagram names them: an enum whose names it keeps. */
    enum Tab { ALL_CONTENT, REELS, POSTS, COLLECTIONS }

    private static List<Tab> savedTabs() {
        return new ArrayList<>(Arrays.asList(Tab.ALL_CONTENT, Tab.REELS, Tab.POSTS, Tab.COLLECTIONS));
    }

    @Test public void theReelsTabStaysUntilTheSwitchIsOn() {
        List<Tab> tabs = savedTabs();
        SavedHome.tabs(tabs);
        assertEquals(savedTabs(), tabs);
        Settings.HIDE_SAVED_REELS_TAB.save(true);
        SavedHome.tabs(tabs);
        assertEquals(Arrays.asList(Tab.ALL_CONTENT, Tab.POSTS, Tab.COLLECTIONS), tabs);
        assertTrue(savedLine(), savedLine().contains(SavedHome.REELS_TAB_HIDDEN + " 1"));
        List<Tab> again = savedTabs();
        Settings.HIDE_SAVED_REELS_TAB.save(false);
        SavedHome.tabs(again);
        assertEquals(savedTabs(), again);
    }

    @Test public void savedKeepsATabToOpenOn() {
        Settings.HIDE_SAVED_REELS_TAB.save(true);
        List<Tab> onlyReels = new ArrayList<>(Collections.singletonList(Tab.REELS));
        SavedHome.tabs(onlyReels);
        assertEquals(Collections.singletonList(Tab.REELS), onlyReels);
        List<Object> none = new ArrayList<>(Arrays.asList("REELS", Tab.POSTS));
        SavedHome.tabs(none);
        assertEquals("only Instagram's tab, not a string that reads like it", 2, none.size());
        SavedHome.tabs(null);
    }

    @Test public void aPauseOrAFailingReadKeepsTheReelsTab() {
        Settings.HIDE_SAVED_REELS_TAB.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        List<Tab> paused = savedTabs();
        SavedHome.tabs(paused);
        assertEquals(savedTabs(), paused);
        PauseForTests.resume();

        List<Tab> failing = savedTabs();
        assertEquals(0, SavedHome.tabs(failing, () -> {
            throw new IllegalStateException("not read yet");
        }));
        assertEquals(savedTabs(), failing);
        // A list Instagram made unchangeable stays as it is, and Saved still opens.
        List<Tab> fixed = Collections.unmodifiableList(savedTabs());
        assertEquals(0, SavedHome.tabs(fixed, () -> true));
        assertEquals(savedTabs(), fixed);
    }

    @Test public void theSearchTabsSavedStaysUntilTheSwitchIsOn() {
        assertEquals(0, SavedHome.hideExploreSaved());
        Settings.HIDE_EXPLORE_SAVED.save(true);
        assertEquals(1, SavedHome.hideExploreSaved());
        assertTrue(savedLine(), savedLine().contains(SavedHome.EXPLORE_SAVED_HIDDEN + " 1"));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, SavedHome.hideExploreSaved());
        PauseForTests.resume();
        assertEquals(0, SavedHome.hideExploreSaved(() -> {
            throw new IllegalStateException("not read yet");
        }));
        Settings.HIDE_EXPLORE_SAVED.save(false);
        assertEquals(0, SavedHome.hideExploreSaved());
    }
}
