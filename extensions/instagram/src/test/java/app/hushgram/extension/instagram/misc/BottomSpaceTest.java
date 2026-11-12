/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Remove the empty space at the bottom drops Instagram's guessed navigation bar. */
@RunWith(RobolectricTestRunner.class)
public class BottomSpaceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.REMOVE_BOTTOM_SPACE.save(true);
    }

    @After
    public void restore() {
        Settings.REMOVE_BOTTOM_SPACE.resetToDefault();
    }

    /** With the switch on, the guess leaves no room. */
    @Test
    public void withTheSwitchOnTheGuessIsDropped() {
        assertEquals(0, BottomSpace.navigationBarHeight(135));
    }

    @Test
    public void withTheSwitchOffTheGuessStays() {
        Settings.REMOVE_BOTTOM_SPACE.save(false);
        assertEquals(135, BottomSpace.navigationBarHeight(135));
    }

    /** No height to drop: whatever Instagram read is handed back, so nothing else changes. */
    @Test
    public void aMissingHeightIsLeftAlone() {
        assertEquals(0, BottomSpace.navigationBarHeight(0));
        assertEquals(-1, BottomSpace.navigationBarHeight(-1));
    }
}
