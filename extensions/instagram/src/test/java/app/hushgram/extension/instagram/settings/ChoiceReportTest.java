/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;

import java.util.EnumSet;

import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.misc.FontChoice;
import app.hushgram.extension.instagram.reels.ReelTapChoice;
import app.hushgram.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A report line prints the choice beside its family's switches, so a report shows what a switch
 * that's on picked. #85's report said Default playback quality was on and left out the quality.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ChoiceReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        Settings.FONT.resetToDefault();
        Settings.TEXT_SIZE.resetToDefault();
        Settings.KEEP_REELS_MUTED.resetToDefault();
        Settings.REEL_TAP_CHOICE.resetToDefault();
    }

    @Test
    public void thePlaybackQualityLineSaysWhichQuality() {
        Settings.DEFAULT_PLAYBACK_QUALITY.save(true);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        assertEquals("Default playback quality: on (hushgram_default_playback_quality=on, hushgram_playback_quality=HIGHEST)",
                line(PatchFamily.PLAYBACK_QUALITY));

        Settings.DEFAULT_PLAYBACK_QUALITY.save(false);
        assertEquals("Default playback quality: disabled by its switch (hushgram_default_playback_quality=off,"
                + " hushgram_playback_quality=HIGHEST)", line(PatchFamily.PLAYBACK_QUALITY));
    }

    @Test
    public void aChoiceWithNoSwitchSaysWhatWasPicked() {
        Settings.FONT.save(FontChoice.SYSTEM);
        assertEquals("Font: a choice, not a switch (hushgram_font=SYSTEM, hushgram_text_size_percent=100)", line(PatchFamily.FONT));
        // The text size (#682) shows beside the font, so a report says why the text is big or small.
        Settings.TEXT_SIZE.save(130);
        assertEquals("Font: a choice, not a switch (hushgram_font=SYSTEM, hushgram_text_size_percent=130)", line(PatchFamily.FONT));
    }

    @Test
    public void theReelTapLineSaysWhatATapDoes() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.PAUSE);
        assertEquals("Control taps and volume on Reels: on (hushgram_keep_reels_muted=off, hushgram_reel_tap_choice=PAUSE)",
                line(PatchFamily.REEL_TAP_AND_VOLUME));
    }

    /** Buttons picked to hide work with the group switch off, so the line says on (#252). */
    @Test
    public void theShareSheetLineCountsThePickedButtons() {
        try {
            Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("copy_link");
            assertEquals("Hide group buttons on the share sheet: on (hushgram_hide_share_sheet_group=off,"
                    + " hushgram_hide_share_sheet_search=off, hushgram_quick_send=off, hushgram_more_quick_send_people=off, hushgram_hidden_share_sheet_buttons=copy_link)", line(PatchFamily.SHARE_SHEET));

            Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("");
            assertEquals("Hide group buttons on the share sheet: disabled by its switch (hushgram_hide_share_sheet_group=off,"
                    + " hushgram_hide_share_sheet_search=off, hushgram_quick_send=off, hushgram_more_quick_send_people=off, hushgram_hidden_share_sheet_buttons=)", line(PatchFamily.SHARE_SHEET));

            // Hide the search bar is a switch of the family too, so it alone makes the line say on (#545).
            Settings.HIDE_SHARE_SHEET_SEARCH.save(true);
            assertEquals("Hide group buttons on the share sheet: on (hushgram_hide_share_sheet_group=off,"
                    + " hushgram_hide_share_sheet_search=on, hushgram_quick_send=off, hushgram_more_quick_send_people=off, hushgram_hidden_share_sheet_buttons=)", line(PatchFamily.SHARE_SHEET));

            // So is More people when you hold Share (discussion #811).
            Settings.HIDE_SHARE_SHEET_SEARCH.save(false);
            Settings.MORE_QUICK_SEND_PEOPLE.save(true);
            assertEquals("Hide group buttons on the share sheet: on (hushgram_hide_share_sheet_group=off,"
                    + " hushgram_hide_share_sheet_search=off, hushgram_quick_send=off, hushgram_more_quick_send_people=on, hushgram_hidden_share_sheet_buttons=)", line(PatchFamily.SHARE_SHEET));

            // And Turn on Hold Share to send (#889).
            Settings.MORE_QUICK_SEND_PEOPLE.save(false);
            Settings.QUICK_SEND.save(true);
            assertEquals("Hide group buttons on the share sheet: on (hushgram_hide_share_sheet_group=off,"
                    + " hushgram_hide_share_sheet_search=off, hushgram_quick_send=on, hushgram_more_quick_send_people=off, hushgram_hidden_share_sheet_buttons=)", line(PatchFamily.SHARE_SHEET));
        } finally {
            Settings.HIDDEN_SHARE_SHEET_BUTTONS.resetToDefault();
            Settings.HIDE_SHARE_SHEET_SEARCH.resetToDefault();
            Settings.MORE_QUICK_SEND_PEOPLE.resetToDefault();
            Settings.QUICK_SEND.resetToDefault();
        }
    }

    @Test
    public void familiesWithoutAChoiceKeepTheirLine() {
        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        try {
            assertEquals("Turn off HDR brightness boosts: on (hushgram_turn_off_hdr_boosts=on)", line(PatchFamily.HDR_BOOST));
        } finally {
            Settings.TURN_OFF_HDR_BOOSTS.resetToDefault();
        }
    }

    private static String line(PatchFamily family) {
        return PatchFamily.reportLines(EnumSet.of(family), false).get(0);
    }
}
