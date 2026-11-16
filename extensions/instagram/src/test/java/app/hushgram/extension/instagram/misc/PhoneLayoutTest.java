/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

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
        PhoneLayout.forgetHingeForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PhoneLayout.forgetHingeForTests();
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

    /** The glass needs the bottom tab bar, which a big screen's layout doesn't have (#582). */
    @Test
    public void theGlassTabBarKeepsAPhoneLayoutOnABigScreen() {
        Settings.PHONE_LAYOUT.resetToDefault();
        Settings.GLASS_TAB_BAR.save(true);
        try {
            assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(1280));
            assertFalse(PhoneLayout.foldable(1));
            assertEquals(411, PhoneLayout.width(411));
        } finally {
            Settings.GLASS_TAB_BAR.resetToDefault();
        }
        assertEquals(1280, PhoneLayout.width(1280));
        assertTrue(PhoneLayout.foldable(1));
    }

    /**
     * Opened up, a foldable keeps Instagram's big screen layout with the glass on, where the phone layout
     * stretched across the inner screen (#695). The switch still keeps the phone layout there.
     */
    @Test
    public void theGlassTabBarLeavesAFoldablesBigScreenLayout() {
        Settings.PHONE_LAYOUT.resetToDefault();
        Settings.GLASS_TAB_BAR.save(true);
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setSystemFeature(PhoneLayout.HINGE_FEATURE, true);
        try {
            assertEquals(673, PhoneLayout.width(673));
            assertTrue(PhoneLayout.foldable(1));
            assertEquals(411, PhoneLayout.width(411));

            Settings.PHONE_LAYOUT.save(true);
            assertEquals(PhoneLayout.PHONE_WIDTH, PhoneLayout.width(673));
            assertFalse(PhoneLayout.foldable(1));
        } finally {
            Settings.GLASS_TAB_BAR.resetToDefault();
        }
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

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier BIG = () -> true;
    private static final BooleanSupplier SMALL = () -> false;

    /** A tablet turns again with the phone layout on, a phone stays in portrait (#700, #714). */
    @Test
    public void theScreensOwnSizePicksWhetherItTurns() {
        assertTrue(PhoneLayout.rotates(0, ON, BIG));
        assertFalse(PhoneLayout.rotates(0, ON, SMALL));
        assertTrue(PhoneLayout.rotates(1, ON, SMALL));
        assertTrue(PhoneLayout.rotates(1, OFF, SMALL));
        assertFalse(PhoneLayout.rotates(0, OFF, BIG));

        assertFalse(PhoneLayout.rotates(0, ON, THROWS));
        assertTrue(PhoneLayout.rotates(1, ON, THROWS));
        String missing = HookStatus.missing(FamilyNames.PHONE_LAYOUT).toString();
        assertTrue(missing, missing.contains("'" + PhoneLayout.SCREEN + "'"));
    }

    /** Instagram's own sorting: past 599 dp wide and at least 480 dp high, each rounded up. */
    @Test
    public void aBigScreenIsWideAndTall() {
        assertTrue(PhoneLayout.big(2f, 2560, 1600));
        assertTrue(PhoneLayout.big(2f, 1600, 2560));
        assertTrue(PhoneLayout.big(2.625f, 1767, 2208));
        assertFalse(PhoneLayout.big(2.625f, 1080, 2400));
        assertFalse(PhoneLayout.big(2.625f, 2400, 1080));
        assertTrue(PhoneLayout.big(1f, 600, 480));
        assertFalse(PhoneLayout.big(1f, 599, 480));
        assertFalse(PhoneLayout.big(1f, 600, 479));
        assertTrue(PhoneLayout.big(2f, 1199, 959));
        assertFalse(PhoneLayout.big(0f, 2560, 1600));
        assertFalse(PhoneLayout.big(Float.NaN, 2560, 1600));
    }

    /** Robolectric's default screen is a phone, so it stays in portrait with the switch on. */
    @Test
    public void aPhoneStaysInPortrait() {
        assertFalse(PhoneLayout.rotates(0));
        assertTrue(PhoneLayout.rotates(1));
        Settings.PHONE_LAYOUT.resetToDefault();
        assertFalse(PhoneLayout.rotates(0));
    }

    /** A tablet sized screen turns with the switch on. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    public void aTabletTurnsWithTheSwitchOn() {
        assertTrue(PhoneLayout.rotates(0));
        Settings.PHONE_LAYOUT.resetToDefault();
        assertFalse(PhoneLayout.rotates(0));
        assertTrue(HookStatus.missing(FamilyNames.PHONE_LAYOUT).toString(),
                HookStatus.missing(FamilyNames.PHONE_LAYOUT).isEmpty());
    }
}
