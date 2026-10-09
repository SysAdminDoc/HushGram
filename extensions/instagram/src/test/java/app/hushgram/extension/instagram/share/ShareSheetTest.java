/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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
