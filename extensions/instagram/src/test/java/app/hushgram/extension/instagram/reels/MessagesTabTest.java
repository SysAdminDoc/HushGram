/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Messages tab (#298): Messages' button folds off the bar with the switch on, the other
 * buttons stay, a button the bar builds again folds as it lays out, and off or Pause put it back.
 */
@RunWith(RobolectricTestRunner.class)
public class MessagesTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int HOME = 1;
    private static final int MESSAGES = 2;
    private static final int PROFILE = 3;

    private LinearLayout bar;

    @Before
    public void prepare() {
        reset();
        bar = new LinearLayout(RuntimeEnvironment.getApplication());
        for (int id : new int[] {HOME, MESSAGES, PROFILE}) bar.addView(button(id));
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_MESSAGES_TAB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static View button(int id) {
        View button = new View(RuntimeEnvironment.getApplication());
        button.setId(id);
        return button;
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void theSwitchStartsOffAndTheBarKeepsMessages() {
        assertEquals(Boolean.FALSE, Settings.HIDE_MESSAGES_TAB.defaultValue);
        assertFalse("no restart", Settings.HIDE_MESSAGES_TAB.rebootApp);
        MessagesTab.apply(bar, MESSAGES);
        assertEquals(View.VISIBLE, bar.findViewById(MESSAGES).getVisibility());
        assertFalse(report().contains(MessagesTab.COUNT_HIDDEN));
    }

    @Test
    public void onlyMessagesFoldsAndOffPutsItBack() {
        Settings.HIDE_MESSAGES_TAB.save(true);
        MessagesTab.apply(bar, MESSAGES);
        assertEquals(View.GONE, bar.findViewById(MESSAGES).getVisibility());
        assertEquals(View.VISIBLE, bar.findViewById(HOME).getVisibility());
        assertEquals(View.VISIBLE, bar.findViewById(PROFILE).getVisibility());
        assertEquals("the button stays in the bar, so Instagram's tabs keep their places", 3, bar.getChildCount());
        assertTrue(report(), report().contains(MessagesTab.COUNT_HIDDEN));

        Settings.HIDE_MESSAGES_TAB.save(false);
        MessagesTab.apply(bar, MESSAGES);
        assertEquals(View.VISIBLE, bar.findViewById(MESSAGES).getVisibility());
    }

    @Test
    public void aButtonInstagramHidItselfStaysHiddenWithTheSwitchOff() {
        bar.findViewById(MESSAGES).setVisibility(View.INVISIBLE);
        Settings.HIDE_MESSAGES_TAB.save(true);
        MessagesTab.apply(bar, MESSAGES);
        Settings.HIDE_MESSAGES_TAB.save(false);
        MessagesTab.apply(bar, MESSAGES);
        assertEquals(View.INVISIBLE, bar.findViewById(MESSAGES).getVisibility());

        View gone = button(9);
        gone.setVisibility(View.GONE);
        MessagesTab.fold(gone, false);
        assertEquals("a button this never folded is left as it is", View.GONE, gone.getVisibility());
    }

    @Test
    public void aButtonTheBarBuildsAgainFoldsAsItLaysOut() {
        Settings.HIDE_MESSAGES_TAB.save(true);
        MessagesTab.apply(bar, MESSAGES);
        bar.removeViewAt(1);
        View rebuilt = button(MESSAGES);
        bar.addView(rebuilt, 1);
        assertEquals(View.VISIBLE, rebuilt.getVisibility());

        bar.layout(0, 0, 300, 50);
        bar.layout(0, 0, 320, 50);
        assertEquals(View.GONE, rebuilt.getVisibility());
    }

    @Test
    public void pausedTheButtonStays() {
        Settings.HIDE_MESSAGES_TAB.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        MessagesTab.apply(bar, MESSAGES);
        assertEquals(View.VISIBLE, bar.findViewById(MESSAGES).getVisibility());
    }

    @Test
    public void noActivityAndAScreenWithoutTheBarAreLeftAlone() {
        Settings.HIDE_MESSAGES_TAB.save(true);
        MessagesTab.apply(null);
        MessagesTab.apply(org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get());
        assertFalse(report(), report().contains("threw"));
    }
}
