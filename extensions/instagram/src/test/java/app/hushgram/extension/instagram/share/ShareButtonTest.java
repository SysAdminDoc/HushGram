/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide the Share button takes the Share button and its count off Feed's rows and off reels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ShareButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_SHARE_BUTTON.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_SHARE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnFeedAndReelsHaveNoShareButton() {
        assertFalse(ShareButton.feedState(1));
        assertFalse(ShareButton.feedState(0));
        assertTrue(ShareButton.hideInReels());
        assertEquals(List.of(FamilyNames.HIDE_SHARE_BUTTON + ": invoked 3, 0 found, 0 missing. Counted: "
                        + "share off in " + ShareButton.FEED + " 2, share off in " + ShareButton.REELS + " 1"),
                HookStatus.report());
    }

    /** Off, paused, unready or throwing, Instagram decides, and any non-zero int is a yes. */
    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.HIDE_SHARE_BUTTON.resetToDefault();
        assertFalse(Settings.HIDE_SHARE_BUTTON.get());
        assertTrue(ShareButton.feedState(1));
        assertFalse(ShareButton.feedState(0));
        assertTrue("2 is a yes too", ShareButton.feedState(2));
        assertFalse(ShareButton.hideInReels());
        Settings.HIDE_SHARE_BUTTON.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(ShareButton.feedState(1));
        assertFalse(ShareButton.hideInReels());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertTrue(ShareButton.feedState(1));
            assertFalse(ShareButton.hideInReels());
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue(ShareButton.feedState(1));
            assertFalse(ShareButton.hideInReels());
        });
        assertFalse(ShareButton.feedState(1));
        assertTrue(ShareButton.hideInReels());

        assertTrue(ShareButton.feedState(true, THROWS));
        assertFalse(ShareButton.feedState(false, THROWS));
        assertFalse(ShareButton.hideInReels(THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_SHARE_BUTTON).toString();
        assertTrue(missing, missing.contains("'" + ShareButton.FEED + "'"));
        assertTrue(missing, missing.contains("'" + ShareButton.REELS + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
