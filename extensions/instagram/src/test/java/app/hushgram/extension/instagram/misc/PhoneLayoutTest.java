/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Phone layout on big screens answers Instagram's width sorting and hinge check, and when it leaves them alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PhoneLayoutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PHONE_LAYOUT.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.PHONE_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A Fold's inner screen (673 dp) and a tablet (1280 dp) sort as compact; a phone's width stays. */
    @Test
    public void withTheSwitchOnEveryWindowSortsAsAPhone() {
        assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(673));
        assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(1280));
        assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(600));
        assertEquals(411, PhoneLayout.width(411));
        assertEquals(0, PhoneLayout.width(0));
        assertTrue(PhoneLayout.PHONE_WIDTH < 600);
        assertFalse(PhoneLayout.foldable(1));
        assertFalse(PhoneLayout.foldable(0));
        assertTrue(HookStatus.missing(FamilyNames.PHONE_LAYOUT).toString(),
                HookStatus.missing(FamilyNames.PHONE_LAYOUT).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingKeepInstagramsAnswer() {
        Settings.PHONE_LAYOUT.resetToDefault();
        assertFalse(Settings.PHONE_LAYOUT.get());
        assertEquals(673, PhoneLayout.width(673));
        assertTrue(PhoneLayout.foldable(1));
        Settings.PHONE_LAYOUT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(673, PhoneLayout.width(673));
        assertTrue(PhoneLayout.foldable(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(673, PhoneLayout.width(673));
            assertTrue(PhoneLayout.foldable(1));
        });
        assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(673));

        assertEquals(673, PhoneLayout.width(673, THROWS));
        assertTrue(PhoneLayout.foldable(1, THROWS));
        assertFalse(PhoneLayout.foldable(0, THROWS));
        String missing = HookStatus.missing(FamilyNames.PHONE_LAYOUT).toString();
        assertTrue(missing, missing.contains("'" + PhoneLayout.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
