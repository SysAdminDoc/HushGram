/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of the share sheet's round buttons Buttons on the share sheet leaves out (#252). */
@RunWith(RobolectricTestRunner.class)
public class ShareSheetActionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final List<String> ROW = Arrays.asList(
            "add_to_your_story", "system_share_sheet", "whatsapp", "copy_link", "download_media", "hatch");

    @After
    public void putBack() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.resetToDefault();
        PauseForTests.resume();
    }

    /** Nothing unticked, and the list Instagram built comes back, the very one. */
    @Test
    public void everyButtonShowsToStart() {
        assertEquals("", Settings.HIDDEN_SHARE_SHEET_BUTTONS.get());
        assertSame(ROW, ShareSheetActions.filter(ROW));
    }

    /** The unticked buttons drop and the rest keep their order. The list Instagram built isn't changed. */
    @Test
    public void hiddenButtonsDropAndTheRestKeepTheirOrder() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("whatsapp,copy_link");
        List<?> shown = ShareSheetActions.filter(ROW);
        assertEquals(Arrays.asList("add_to_your_story", "system_share_sheet", "download_media", "hatch"), shown);
        assertNotSame(ROW, shown);
        assertEquals(6, ROW.size());
    }

    /** Share and Copy link can be hidden like the rest. */
    @Test
    public void shareAndCopyLinkAreOfferedLikeTheRest() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("system_share_sheet,copy_link");
        assertEquals(Arrays.asList("add_to_your_story", "whatsapp", "download_media", "hatch"), ShareSheetActions.filter(ROW));
    }

    /** A downloadable reel's Download, sent in a chat or not, goes by clips_download, and unticking Download hides it too. */
    @Test
    public void downloadHidesAReelsOwnDownloadToo() {
        List<String> chatReel = Arrays.asList("user_sms", "whatsapp", "barcelona", "clips_download");
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("download_media");
        assertEquals(Arrays.asList("user_sms", "whatsapp", "barcelona"), ShareSheetActions.filter(chatReel));
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("user_sms");
        assertEquals(Arrays.asList("whatsapp", "barcelona", "clips_download"), ShareSheetActions.filter(chatReel));
    }

    /** A name the choice doesn't know is dropped on reading, so a stray one never hides a button. */
    @Test
    public void unknownNamesHideNothing() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("hatch,,nonsense");
        assertSame(ROW, ShareSheetActions.filter(ROW));
    }

    /** Paused, the row is Instagram's own. */
    @Test
    public void pausedTheRowIsUntouched() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("whatsapp");
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(ROW, ShareSheetActions.filter(ROW));
    }

    @Test
    public void aMissingOrEmptyListGoesThrough() {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("whatsapp");
        assertNull(ShareSheetActions.filter(null));
        List<String> empty = new ArrayList<>();
        assertSame(empty, ShareSheetActions.filter(empty));
    }

    @Test
    public void theChoiceIsKeptInTheListsOrder() {
        assertEquals("add_to_your_story,whatsapp",
                ShareSheetActions.join(ShareSheetActions.hidden("whatsapp, add_to_your_story,whatsapp")));
    }
}
