/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** How many people the row a long press on Share opens shows (discussion #811). */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp")
public class QuickSendTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity before;

    @Before
    public void remember() {
        before = Utils.getActivity();
        Utils.setActivity(Robolectric.buildActivity(Activity.class).setup().get());
    }

    @After
    public void putBack() {
        Settings.MORE_QUICK_SEND_PEOPLE.resetToDefault();
        Utils.setActivity(before);
    }

    @Test
    public void theSwitchStartsOffSoTheRowKeepsInstagramsFour() {
        assertFalse(Settings.MORE_QUICK_SEND_PEOPLE.defaultValue);
        assertEquals(4, QuickSend.people(4));
    }

    /** A 411 dp window, the S22's and the S25's, takes six faces of 44 dp with their gaps and padding. */
    @Test
    public void withTheSwitchOnTheRowFillsTheWindow() {
        Settings.MORE_QUICK_SEND_PEOPLE.save(true);
        assertEquals(6, QuickSend.people(4));
        Settings.MORE_QUICK_SEND_PEOPLE.save(false);
        assertEquals(4, QuickSend.people(4));
    }

    /** However narrow the window, the row never shows fewer than Instagram does, and however wide, no more than ten. */
    @Test
    public void theRowStaysBetweenInstagramsFourAndTen() {
        assertEquals(4, QuickSend.fit(4, 0));
        assertEquals(4, QuickSend.fit(4, -20));
        assertEquals(4, QuickSend.fit(4, 280));
        assertEquals(5, QuickSend.fit(4, 360));
        assertEquals(6, QuickSend.fit(4, 411));
        assertEquals(QuickSend.MOST, QuickSend.fit(4, 1280));
    }

    /** Every count past four leaves the row narrower than the window less its edges, and one more face wouldn't fit. */
    @Test
    public void whatTheRowTakesFitsAcrossTheWindow() {
        for (int width = 200; width <= 800; width++) {
            int people = QuickSend.fit(4, width);
            if (people == 4 || people == QuickSend.MOST) continue;
            assertTrue(width + " dp: " + people, rowWidth(people) <= width - 2 * QuickSend.EDGE_DP);
            assertTrue(width + " dp: " + (people + 1), rowWidth(people + 1) > width - 2 * QuickSend.EDGE_DP);
        }
    }

    /** The popup's width for [people] faces, as Instagram's clamp works it out. */
    private static float rowWidth(int people) {
        return people * QuickSend.FACE_DP + (people - 1) * QuickSend.GAP_DP + 2 * QuickSend.PADDING_DP;
    }
}
