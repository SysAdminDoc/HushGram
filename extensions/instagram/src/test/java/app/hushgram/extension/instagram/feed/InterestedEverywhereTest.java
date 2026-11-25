/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

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

/** When Interested on every post and reel puts Interested in a menu Instagram builds without it (#815). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class InterestedEverywhereTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.INTERESTED_EVERYWHERE.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.INTERESTED_EVERYWHERE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.INTERESTED_EVERYWHERE.resetToDefault();
        assertFalse(Settings.INTERESTED_EVERYWHERE.get());
        assertFalse("each menu reads it as it opens, so no restart prompt", Settings.INTERESTED_EVERYWHERE.rebootApp);
    }

    /** A reel of an account you follow gets Interested as if you didn't follow it. */
    @Test
    public void aFollowedAccountsReelOffersInterested() {
        assertFalse(InterestedEverywhere.reelFollowing(1));
        assertEquals(List.of(FamilyNames.MENU_ITEMS + ": invoked 1, 0 found, 0 missing. Counted: "
                + InterestedEverywhere.COUNT_ADDED + " 1"), HookStatus.report());
    }

    /** A post Instagram leaves Interested off gets it. */
    @Test
    public void aPostWithoutInterestedOffersIt() {
        assertTrue(InterestedEverywhere.postOffered(0));
        assertEquals(List.of(FamilyNames.MENU_ITEMS + ": invoked 1, 0 found, 0 missing. Counted: "
                + InterestedEverywhere.COUNT_ADDED + " 1"), HookStatus.report());
    }

    /** Where Instagram offers it already, nothing changes and nothing is counted. Any non-zero is a yes. */
    @Test
    public void whereInstagramOffersItNothingChanges() {
        assertFalse(InterestedEverywhere.reelFollowing(0));
        assertTrue(InterestedEverywhere.postOffered(1));
        assertTrue(InterestedEverywhere.postOffered(-1));
        assertEquals(List.of(FamilyNames.MENU_ITEMS + ": invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    /** Off, paused, unready or throwing, the menus are built as Instagram has them. */
    @Test
    public void offPausedUnreadyAndThrowingKeepInstagramsMenus() {
        Settings.INTERESTED_EVERYWHERE.resetToDefault();
        assertTrue(InterestedEverywhere.reelFollowing(1));
        assertFalse(InterestedEverywhere.postOffered(0));
        Settings.INTERESTED_EVERYWHERE.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(InterestedEverywhere.reelFollowing(1));
        assertFalse(InterestedEverywhere.postOffered(0));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertTrue(InterestedEverywhere.reelFollowing(1));
            assertFalse(InterestedEverywhere.postOffered(0));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertTrue(InterestedEverywhere.reelFollowing(1));
            assertFalse(InterestedEverywhere.postOffered(0));
        });

        assertTrue(InterestedEverywhere.reelFollowingWhen(1, THROWS));
        assertFalse(InterestedEverywhere.postOfferedWhen(0, THROWS));
        String missing = HookStatus.missing(FamilyNames.MENU_ITEMS).toString();
        assertTrue(missing, missing.contains("'" + InterestedEverywhere.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
