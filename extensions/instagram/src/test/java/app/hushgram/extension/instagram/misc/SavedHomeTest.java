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
 * arrow, and a switch leaves the + for a new collection off the top of both Saved screens.
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
        Intent intent = ProfileSaved.savedIntent("com.instagram.android", "token-1");
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
}
