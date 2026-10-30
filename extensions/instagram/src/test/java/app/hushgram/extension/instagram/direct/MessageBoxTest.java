/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Message box buttons does: a show of an unticked button turns into a hide, every other button
 * and every hide go through as Instagram asked, and the three views go GONE when a message box is
 * made. Nothing saved, paused or anything thrown means Instagram's own answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MessageBoxTest {
    /** Stand-in for Instagram's button enum: the names are all the hook reads. */
    private enum Button { GALLERY, STICKER, VOICE_MESSAGE, CAMERA, GIF, NEW_ONE }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Nothing picked is the start: every button shows when Instagram says show. */
    @Test
    public void nothingPickedShowsEveryButton() {
        for (Button button : Button.values()) assertTrue(button.name(), MessageBox.button(button, 1));
    }

    /** A picked button's show becomes a hide, and the ones not picked still show. */
    @Test
    public void aPickedButtonIsHiddenAndTheRestShow() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("STICKER");
        assertFalse(MessageBox.button(Button.STICKER, 1));
        assertTrue(MessageBox.button(Button.GALLERY, 1));
        assertTrue(MessageBox.button(Button.VOICE_MESSAGE, 1));
    }

    /** Instagram's own hide is never turned into a show. */
    @Test
    public void aHideStaysAHide() {
        for (Button button : Button.values()) assertFalse(button.name(), MessageBox.button(button, 0));
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY,STICKER,VOICE_MESSAGE");
        for (Button button : Button.values()) assertFalse(button.name(), MessageBox.button(button, 0));
    }

    /** A button the setting can't name, one Instagram adds later and the camera, plus and GIF buttons always show. */
    @Test
    public void buttonsNotOfferedAlwaysShow() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("CAMERA,GIF,NEW_ONE,GALLERY");
        assertTrue(MessageBox.button(Button.CAMERA, 1));
        assertTrue(MessageBox.button(Button.GIF, 1));
        assertTrue(MessageBox.button(Button.NEW_ONE, 1));
        assertFalse(MessageBox.button(Button.GALLERY, 1));
        assertEquals("GALLERY", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue().replace("CAMERA,GIF,NEW_ONE,", ""));
    }

    /** Paused, Instagram's answer goes back whatever is saved, and the choice is kept. */
    @Test
    public void pausedShowsEveryButton() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY,STICKER,VOICE_MESSAGE");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        for (Button button : Button.values()) assertTrue(button.name(), MessageBox.button(button, 1));
        assertEquals("GALLERY,STICKER,VOICE_MESSAGE", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue());
    }

    /** Something that isn't an enum, or a null, shows as Instagram asked and nothing throws. */
    @Test
    public void anythingElseShows() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY");
        assertTrue(MessageBox.button(null, 1));
        assertTrue(MessageBox.button("GALLERY", 1));
        assertFalse(MessageBox.button(null, 0));
    }

    /** A new message box sends the picked buttons' views to GONE, and leaves the others and a missing one alone. */
    @Test
    public void aNewMessageBoxHidesThePickedViews() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY,VOICE_MESSAGE");
        View gallery = new View(RuntimeEnvironment.getApplication());
        View sticker = new View(RuntimeEnvironment.getApplication());
        View voice = new View(RuntimeEnvironment.getApplication());
        MessageBox.composerCreated(gallery, sticker, voice);
        assertEquals(View.GONE, gallery.getVisibility());
        assertEquals(View.VISIBLE, sticker.getVisibility());
        assertEquals(View.GONE, voice.getVisibility());
        MessageBox.composerCreated(null, null, null);
    }

    /** With nothing picked, or paused, a new message box keeps all three. */
    @Test
    public void nothingPickedOrPausedLeavesTheViewsAlone() {
        View gallery = new View(RuntimeEnvironment.getApplication());
        MessageBox.composerCreated(gallery, gallery, gallery);
        assertEquals(View.VISIBLE, gallery.getVisibility());
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY,STICKER,VOICE_MESSAGE");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        MessageBox.composerCreated(gallery, gallery, gallery);
        assertEquals(View.VISIBLE, gallery.getVisibility());
    }

    /** A button left off is counted once per hook that left it off. */
    @Test
    public void aButtonLeftOffIsCounted() {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY");
        MessageBox.button(Button.GALLERY, 1);
        MessageBox.composerCreated(new View(RuntimeEnvironment.getApplication()), null, null);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + MessageBox.LEFT_OFF + " 2"));
    }

    /** Saved text keeps only the names offered, once each, in the order the dialog lists them. */
    @Test
    public void savedTextIsTidied() {
        Set<String> parsed = MessageBox.parse(" VOICE_MESSAGE, ,GALLERY,GALLERY,CAMERA");
        assertEquals(new LinkedHashSet<>(Arrays.asList("VOICE_MESSAGE", "GALLERY")), parsed);
        assertEquals("GALLERY,VOICE_MESSAGE", MessageBox.join(parsed));
        assertEquals("", MessageBox.join(Collections.<String>emptySet()));
        assertTrue(MessageBox.parse(null).isEmpty());
    }
}
