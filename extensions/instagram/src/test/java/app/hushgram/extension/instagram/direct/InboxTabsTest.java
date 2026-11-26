/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Primary and General tabs hook answers for each read of Instagram's test (#832). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class InboxTabsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.INBOX_TABS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.INBOX_TABS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The patch is in Manager's default selection, so its switch starts off, and it takes a restart. */
    @Test
    public void theSwitchStartsOffAndTakesARestart() {
        Settings.INBOX_TABS.resetToDefault();
        assertFalse(Settings.INBOX_TABS.get());
        assertTrue("Instagram reads the test when your messages are set up and when the app starts",
                Settings.INBOX_TABS.rebootApp);
    }

    /** With the switch on, every read says yes whatever Instagram's server said, and the hook counts as reached. */
    @Test
    public void onEveryReadSaysYes() {
        assertTrue("the server said no", InboxTabs.tabs(0));
        assertTrue("the server said yes", InboxTabs.tabs(1));
        assertTrue(HookStatus.missing(FamilyNames.INBOX_TABS).toString(),
                HookStatus.missing(FamilyNames.INBOX_TABS).isEmpty());
    }

    /** Off, paused or before the settings are read, each read keeps Instagram's answer, so a professional account keeps its tabs. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.INBOX_TABS.save(false);
        assertFalse("off", InboxTabs.tabs(0));
        assertTrue("off, Instagram's own yes", InboxTabs.tabs(1));
        Settings.INBOX_TABS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", InboxTabs.tabs(0));
        assertTrue("paused, Instagram's own yes", InboxTabs.tabs(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", InboxTabs.tabs(0));
            assertTrue("no context, Instagram's own yes", InboxTabs.tabs(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", InboxTabs.tabs(0));
            assertTrue("pause undecided, Instagram's own yes", InboxTabs.tabs(1));
        });

        assertTrue("back on", InboxTabs.tabs(0));
    }
}
