/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.shared.SettingsContextRule;

/** What counts as the menu's Saved row. */
@RunWith(RobolectricTestRunner.class)
public class ProfileSavedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void aRowLabelledSavedIsTheSavedRowInAnyCase() {
        assertTrue(ProfileSaved.isSavedLabel("Saved"));
        assertTrue(ProfileSaved.isSavedLabel("  saved "));
    }

    @Test
    public void otherRowsAreNot() {
        assertFalse(ProfileSaved.isSavedLabel("Archive"));
        assertFalse(ProfileSaved.isSavedLabel("Saved posts and more"));
        assertFalse(ProfileSaved.isSavedLabel(null));
    }
}
