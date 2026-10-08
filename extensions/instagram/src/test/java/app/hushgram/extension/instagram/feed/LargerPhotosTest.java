/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

/** Ask for larger photos: a narrower screen reports and asks for 1440, and nothing else changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LargerPhotosTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ASK_FOR_LARGER_PHOTOS.save(true);
        LargerPhotos.forgetForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.ASK_FOR_LARGER_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        LargerPhotos.forgetForTests();
        HookStatus.clear();
    }

    @Test
    public void aPhotoAcrossANarrowerScreenIsAskedForAt1440() {
        assertEquals(1440, LargerPhotos.wanted(1080, 1080, ON));
        assertEquals("a little under the screen is still across it", 1440, LargerPhotos.wanted(1000, 1080, ON));
        assertEquals(1440, LargerPhotos.wanted(720, 720, ON));
        String report = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(report, HookStatus.missing(FamilyNames.FULL_RESOLUTION).isEmpty());
    }

    @Test
    public void thumbnailsWiderScreensAndOffKeepTheirWidth() {
        assertEquals("a grid thumbnail", 360, LargerPhotos.wanted(360, 1080, ON));
        assertEquals("a screen already that wide", 1440, LargerPhotos.wanted(1440, 1440, ON));
        assertEquals("a wider ask", 2048, LargerPhotos.wanted(2048, 1080, ON));
        assertEquals("an unknown screen", 1080, LargerPhotos.wanted(1080, 0, ON));
        assertEquals("switch off", 1080, LargerPhotos.wanted(1080, 1080, OFF));
    }

    @Test
    public void theSwitchStartsOffAndIsReadEachTime() {
        Settings.ASK_FOR_LARGER_PHOTOS.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.ASK_FOR_LARGER_PHOTOS.defaultValue);
        Object[] parts = {420, 1080, 2340};
        LargerPhotos.screen(parts);
        assertArrayEquals(new Object[] {420, 1080, 2340}, parts);
        Settings.ASK_FOR_LARGER_PHOTOS.save(true);
        LargerPhotos.screen(parts);
        assertArrayEquals(new Object[] {420, 1440, 3120}, parts);
    }

    @Test
    public void theReportedScreenKeepsItsShapeInEitherOrientation() {
        Object[] portrait = {420, 1080, 2340};
        LargerPhotos.screen(portrait, ON);
        assertArrayEquals(new Object[] {420, 1440, 3120}, portrait);
        Object[] landscape = {420, 2400, 1080};
        LargerPhotos.screen(landscape, ON);
        assertArrayEquals(new Object[] {420, 3200, 1440}, landscape);
    }

    @Test
    public void aWideEnoughOrUnreadableScreenIsLeftAlone() {
        Object[] wide = {560, 1440, 3088};
        LargerPhotos.screen(wide, ON);
        assertArrayEquals(new Object[] {560, 1440, 3088}, wide);
        Object[] none = {420, 0, 0};
        LargerPhotos.screen(none, ON);
        assertArrayEquals(new Object[] {420, 0, 0}, none);
        Object[] strings = {"420", "1080", "2340"};
        LargerPhotos.screen(strings, ON);
        assertArrayEquals(new Object[] {"420", "1080", "2340"}, strings);
        Object[] short2 = {420, 1080};
        LargerPhotos.screen(short2, ON);
        assertArrayEquals(new Object[] {420, 1080}, short2);
        LargerPhotos.screen(null, ON);
    }

    @Test
    public void pausedAndUnreadyLeaveTheScreen() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Object[] paused = {420, 1080, 2340};
        LargerPhotos.screen(paused);
        assertArrayEquals(new Object[] {420, 1080, 2340}, paused);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Object[] unready = {420, 1080, 2340};
        SettingsContextRule.withoutContext(() -> LargerPhotos.screen(unready));
        assertArrayEquals(new Object[] {420, 1080, 2340}, unready);
    }

    @Test
    public void aThrowingSwitchKeepsWhatInstagramHadAndIsReported() {
        assertEquals(1080, LargerPhotos.wanted(1080, 1080, THROWS));
        Object[] parts = {420, 1080, 2340};
        LargerPhotos.screen(parts, THROWS);
        assertArrayEquals(new Object[] {420, 1080, 2340}, parts);

        String missing = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(missing, missing.contains("'" + LargerPhotos.ASK + "'"));
        assertTrue(missing, missing.contains("'" + LargerPhotos.SCREEN + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
