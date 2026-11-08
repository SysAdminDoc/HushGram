/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Hide group buttons on the share sheet leaves the group buttons out. */
@RunWith(RobolectricTestRunner.class)
public class ShareSheetTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_SHARE_SHEET_GROUP.save(true);
    }

    @After
    public void switchBack() {
        Settings.HIDE_SHARE_SHEET_GROUP.resetToDefault();
        Settings.HIDE_SHARE_SHEET_SEARCH.resetToDefault();
    }

    /** Hide the search bar takes the sheet's search bar away, and starts off so the bar stays (#545). */
    @Test
    public void theSearchBarGoesOnlyWhileItsSwitchIsOn() {
        assertFalse(Settings.HIDE_SHARE_SHEET_SEARCH.defaultValue);
        View box = new View(RuntimeEnvironment.getApplication());
        ShareSheet.searchBox(box);
        assertEquals(View.VISIBLE, box.getVisibility());

        Settings.HIDE_SHARE_SHEET_SEARCH.save(true);
        ShareSheet.searchBox(box);
        assertEquals(View.GONE, box.getVisibility());
    }

    /**
     * The sheet shows the bar itself right after handing it over (454's onViewCreated), so the bar
     * goes again the next time it's laid out, and stays once the switch is off (#545).
     */
    @Test
    public void theSearchBarStaysGoneWhenTheSheetShowsItAgain() {
        Settings.HIDE_SHARE_SHEET_SEARCH.save(true);
        View box = new View(RuntimeEnvironment.getApplication());
        ShareSheet.searchBox(box);
        ShareSheet.searchBox(box);
        box.setVisibility(View.VISIBLE);
        box.layout(0, 0, 688, 88);
        assertEquals(View.GONE, box.getVisibility());

        Settings.HIDE_SHARE_SHEET_SEARCH.save(false);
        box.setVisibility(View.VISIBLE);
        box.layout(0, 0, 688, 90);
        assertEquals(View.VISIBLE, box.getVisibility());
    }

    /** The group switch alone leaves the search bar, and a missing bar is no trouble. */
    @Test
    public void theGroupSwitchLeavesTheSearchBarAlone() {
        View box = new View(RuntimeEnvironment.getApplication());
        ShareSheet.searchBox(box);
        assertEquals(View.VISIBLE, box.getVisibility());

        Settings.HIDE_SHARE_SHEET_SEARCH.save(true);
        ShareSheet.searchBox(null);
    }

    /** With the switch on, every group button stays away. */
    @Test
    public void theGroupButtonsGoWhileTheSwitchIsOn() {
        assertTrue(ShareSheet.hideGroupButton());
        assertTrue(ShareSheet.hideGroupAction());
        assertTrue(ShareSheet.hideGroupSend());
    }

    @Test
    public void withTheSwitchOffTheGroupButtonsStay() {
        Settings.HIDE_SHARE_SHEET_GROUP.save(false);
        try {
            assertFalse(ShareSheet.hideGroupButton());
            assertFalse(ShareSheet.hideGroupAction());
            assertFalse(ShareSheet.hideGroupSend());
        } finally {
            Settings.HIDE_SHARE_SHEET_GROUP.save(true);
        }
    }
}
