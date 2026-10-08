/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

/** When View live anonymously holds a live viewer's heartbeat, and when it leaves it to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LiveSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheViewerHeartbeatIsHeld() {
        assertTrue(LiveSeen.hold());
        assertTrue(HookStatus.missing(FamilyNames.LIVE_SEEN).toString(), HookStatus.missing(FamilyNames.LIVE_SEEN).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        assertFalse(Settings.VIEW_LIVE_ANONYMOUSLY.get());
        assertFalse(LiveSeen.hold());
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(LiveSeen.hold());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(LiveSeen.hold()));
        assertTrue(LiveSeen.hold());

        assertFalse(LiveSeen.hold(THROWS));
        String missing = HookStatus.missing(FamilyNames.LIVE_SEEN).toString();
        assertTrue(missing, missing.contains("'" + LiveSeen.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
