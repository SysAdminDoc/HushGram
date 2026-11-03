/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide activity bubbles answers Instagram's reads of its own setting, and when it leaves them alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ActivityBubblesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_ACTIVITY_BUBBLES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_ACTIVITY_BUBBLES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEveryReadSaysHide() {
        assertTrue(ActivityBubbles.hide(0));
        assertTrue(ActivityBubbles.hide(1));
        assertTrue(HookStatus.missing(FamilyNames.ACTIVITY_BUBBLES).toString(),
                HookStatus.missing(FamilyNames.ACTIVITY_BUBBLES).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingKeepInstagramsAnswer() {
        Settings.HIDE_ACTIVITY_BUBBLES.resetToDefault();
        assertFalse(Settings.HIDE_ACTIVITY_BUBBLES.get());
        assertFalse(ActivityBubbles.hide(0));
        assertTrue(ActivityBubbles.hide(1));
        Settings.HIDE_ACTIVITY_BUBBLES.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ActivityBubbles.hide(0));
        assertTrue(ActivityBubbles.hide(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ActivityBubbles.hide(0)));
        assertTrue(ActivityBubbles.hide(0));

        assertFalse(ActivityBubbles.hide(0, THROWS));
        assertTrue(ActivityBubbles.hide(1, THROWS));
        String missing = HookStatus.missing(FamilyNames.ACTIVITY_BUBBLES).toString();
        assertTrue(missing, missing.contains("'" + ActivityBubbles.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
