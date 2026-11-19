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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.GhostModeEntry;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which buttons the New message hook takes off the inbox bar, and that it gives back every other one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NewMessageButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private View button;
    private Object newMessage;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_NEW_MESSAGE_BUTTON.save(true);
        HookStatus.clear();
        button = new View(RuntimeEnvironment.getApplication());
        newMessage = new Object();
        GhostModeEntry.newMessageAction(newMessage);
    }

    @After
    public void restore() {
        Settings.HIDE_NEW_MESSAGE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, the button bound to New message goes, and it's counted. */
    @Test
    public void withTheSwitchOnTheNewMessageButtonGoes() {
        NewMessageButton.bind(button, newMessage);

        assertEquals(View.GONE, button.getVisibility());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.NEW_MESSAGE));
        assertTrue(report, report.contains(NewMessageButton.HIDDEN_FROM_BAR));
    }

    /** A button the bar binds to anything else stays, hidden or not by Instagram itself. */
    @Test
    public void anotherActionKeepsItsButton() {
        NewMessageButton.bind(button, new Object());
        assertEquals(View.VISIBLE, button.getVisibility());

        button.setVisibility(View.INVISIBLE);
        NewMessageButton.bind(button, "camera");
        assertEquals(View.INVISIBLE, button.getVisibility());

        NewMessageButton.bind(button, null);
        assertEquals(View.INVISIBLE, button.getVisibility());
        NewMessageButton.bind(null, newMessage);
    }

    /** A button this hid comes back when the bar reuses it for another action, and only then. */
    @Test
    public void aReusedButtonComesBack() {
        NewMessageButton.bind(button, newMessage);
        assertEquals(View.GONE, button.getVisibility());

        NewMessageButton.bind(button, new Object());
        assertEquals(View.VISIBLE, button.getVisibility());

        View other = new View(RuntimeEnvironment.getApplication());
        other.setVisibility(View.GONE);
        NewMessageButton.bind(other, new Object());
        assertEquals(View.GONE, other.getVisibility());
    }

    /** The switch starts off; off, paused or before the settings are read the button stays, and comes back if hidden. */
    @Test
    public void offPausedAndUnreadyKeepTheButton() {
        Settings.HIDE_NEW_MESSAGE_BUTTON.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.HIDE_NEW_MESSAGE_BUTTON.defaultValue);
        NewMessageButton.bind(button, newMessage);
        assertEquals(View.VISIBLE, button.getVisibility());

        Settings.HIDE_NEW_MESSAGE_BUTTON.save(true);
        NewMessageButton.bind(button, newMessage);
        assertEquals(View.GONE, button.getVisibility());
        Settings.HIDE_NEW_MESSAGE_BUTTON.save(false);
        NewMessageButton.bind(button, newMessage);
        assertEquals(View.VISIBLE, button.getVisibility());

        Settings.HIDE_NEW_MESSAGE_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        NewMessageButton.bind(button, newMessage);
        assertEquals(View.VISIBLE, button.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> NewMessageButton.bind(button, newMessage));
        assertEquals(View.VISIBLE, button.getVisibility());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.NEW_MESSAGE));
    }

    /** A switch that throws keeps the button and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheButtonAndIsReported() {
        NewMessageButton.bind(button, newMessage, THROWS);

        assertEquals(View.VISIBLE, button.getVisibility());
        String missing = HookStatus.missing(FamilyNames.NEW_MESSAGE).toString();
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
        assertFalse(GhostModeEntry.isNewMessage(new Object()));
        assertTrue(GhostModeEntry.isNewMessage(newMessage));
    }
}
