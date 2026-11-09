/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of the Compose palettes' dark grays take the accent backgrounds' shades (#577). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ComposeSurfaceTest {
    private static final int BASE = 0xff112233;
    private static final int RAISED = 0xff223344;
    private static final int TOP = 0xff334455;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static long compose(int argb) {
        return (long) argb << 32;
    }

    @Before public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_BACKGROUNDS.save(true);
        AccentTheme.darkSurfacesForTests(new int[] {BASE, RAISED, TOP});
        HookStatus.clear();
    }

    @After public void end() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_BACKGROUNDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        AccentTheme.darkSurfacesForTests(null);
        HookStatus.clear();
    }

    @Test public void eachGrayTakesItsStep() {
        assertEquals(compose(BASE), AccentTheme.composeSurface(compose(0xff0c1014)));
        assertEquals(compose(RAISED), AccentTheme.composeSurface(compose(0xff191c1f)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff25292e)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff212328)));
        assertEquals(compose(TOP), AccentTheme.composeSurface(compose(0xff2b3036)));
    }

    @Test public void pureBlacksPageIsThePage() {
        assertEquals(compose(BASE), AccentTheme.composeSurface(compose(0xff000000)));
    }

    @Test public void alphaIsKept() {
        assertEquals(compose(0x80112233), AccentTheme.composeSurface(compose(0x800c1014)));
    }

    @Test public void otherColorsStay() {
        assertEquals(compose(0xff737373), AccentTheme.composeSurface(compose(0xff737373)));
        assertEquals("an already swapped color passes", compose(BASE), AccentTheme.composeSurface(compose(BASE)));
    }

    @Test public void aColorOutsideSrgbStays() {
        long wideGamut = compose(0xff0c1014) | 5;
        assertEquals(wideGamut, AccentTheme.composeSurface(wideGamut));
    }

    @Test public void offStays() {
        Settings.ACCENT_BACKGROUNDS.save(false);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    @Test public void noColorPickedStays() {
        Settings.ACCENT_COLOR.save(false);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    @Test public void pausedStays() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }

    @Test public void noDarkThemeSeenStays() {
        AccentTheme.darkSurfacesForTests(null);
        assertEquals(compose(0xff0c1014), AccentTheme.composeSurface(compose(0xff0c1014)));
    }
}
