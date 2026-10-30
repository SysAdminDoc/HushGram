/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Build;
import android.view.Window;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Turn off HDR brightness boosts: on, no headroom and no HDR color mode; off, Instagram's own values. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HdrBoostTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.TURN_OFF_HDR_BOOSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * Every headroom Instagram asks for, a fixed 1.5, a server's 1.7, automatic 0 or the 3.0 its
     * extended range video layer wants (#85), becomes none.
     */
    @Test
    public void onEveryHeadroomBecomesNone() {
        assertEquals(1.0f, HdrBoost.headroom(1.5f), 0);
        assertEquals(1.0f, HdrBoost.headroom(1.7f), 0);
        assertEquals(1.0f, HdrBoost.headroom(0f), 0);
        assertEquals(1.0f, HdrBoost.headroom(3.0f), 0);
        assertEquals(1.0f, HdrBoost.headroom(1.0f), 0);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.HELD_BACK + " 4"));
    }

    /**
     * The screen's HDR/SDR ratio, the headroom Instagram draws its HDR frames for and backs its
     * synthetic HDR off by, reads as none (#85). A screen already at none, or one reporting no
     * ratio (NaN), is left as it said, and only a ratio taken down is counted.
     */
    @Test
    public void onTheScreensRatioReadsAsNoHeadroom() {
        assertEquals(1.0f, HdrBoost.screenRatio(4.93f), 0);
        assertEquals(1.0f, HdrBoost.screenRatio(1.2f), 0);
        assertEquals(1.0f, HdrBoost.screenRatio(1.0f), 0);
        assertTrue(Float.isNaN(HdrBoost.screenRatio(Float.NaN)));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.HELD_BACK + " 2"));

        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        assertEquals(4.93f, HdrBoost.screenRatio(4.93f), 0);
    }

    /** HDR and HDR10 color modes become the default one. Wide color and the default stay. */
    @Test
    public void onHdrColorModesBecomeTheDefault() {
        assertEquals(0, HdrBoost.colorMode(2));
        assertEquals(0, HdrBoost.colorMode(3));
        assertEquals(1, HdrBoost.colorMode(1));
        assertEquals(0, HdrBoost.colorMode(0));
    }

    /** Off, paused or before the settings are ready, Instagram's values go through as they were. */
    @Test
    public void offPausedOrUnreadyKeepInstagramsValues() {
        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        assertEquals(1.5f, HdrBoost.headroom(1.5f), 0);
        assertEquals(2, HdrBoost.colorMode(2));
        assertEquals(3.0f, HdrBoost.screenRatio(3.0f), 0);

        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(1.7f, HdrBoost.headroom(1.7f), 0);
        assertEquals(3, HdrBoost.colorMode(3));
        assertEquals(4.93f, HdrBoost.screenRatio(4.93f), 0);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(1.5f, HdrBoost.headroom(1.5f), 0);
            assertEquals(2, HdrBoost.colorMode(2));
        });
        assertTrue(String.join("\n", HookStatus.report()), !String.join("\n", HookStatus.report()).contains(HdrBoost.HELD_BACK));
    }

    /**
     * Instagram's checks of the screen's HDR support answer none, so it picks SDR streams and keeps
     * synthetic HDR off (#85): an HDR screen reads as not HDR and its HDR types (HDR10, HLG, Dolby
     * Vision) as none. A screen already without HDR stays as it said, and only a changed answer counts.
     */
    @Test
    public void onTheScreenReadsAsWithoutHdr() {
        assertEquals(false, HdrBoost.screenIsHdr(true));
        assertEquals(false, HdrBoost.screenIsHdr(false));
        assertEquals(0, HdrBoost.screenHdrTypes(new int[] {1, 2, 3}).length);
        int[] none = new int[0];
        assertTrue("an empty answer goes back as it came", HdrBoost.screenHdrTypes(none) == none);
        assertEquals(null, HdrBoost.screenHdrTypes(null));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.SCREEN_CHECKS + " 2"));
    }

    /**
     * The extended range layer's extended sRGB (Android's SCRGB, 0x18810000) goes to Android as
     * plain sRGB (0x08810000), so the layer isn't an HDR one. Other dataspaces keep theirs: plain
     * sRGB, BT.2020 with PQ (HDR10) and the unknown one.
     */
    @Test
    public void onAnExtendedRangeLayerIsSentAsSrgb() {
        assertEquals(0x08810000, HdrBoost.layerDataSpace(0x18810000));
        assertEquals(0x08810000, HdrBoost.layerDataSpace(0x08810000));
        assertEquals(0x09C60000, HdrBoost.layerDataSpace(0x09C60000));
        assertEquals(0, HdrBoost.layerDataSpace(0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.SRGB_LAYERS + " 1"));
    }

    /** Off, paused or before the settings are ready, the screen's own answers and the layer's dataspace go through. */
    @Test
    public void offPausedOrUnreadyKeepTheScreensAnswers() {
        int[] types = {2, 3};
        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        assertEquals(true, HdrBoost.screenIsHdr(true));
        assertTrue(HdrBoost.screenHdrTypes(types) == types);
        assertEquals(0x18810000, HdrBoost.layerDataSpace(0x18810000));

        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(true, HdrBoost.screenIsHdr(true));
        assertTrue(HdrBoost.screenHdrTypes(types) == types);
        assertEquals(0x18810000, HdrBoost.layerDataSpace(0x18810000));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(true, HdrBoost.screenIsHdr(true));
            assertTrue(HdrBoost.screenHdrTypes(types) == types);
            assertEquals(0x18810000, HdrBoost.layerDataSpace(0x18810000));
        });
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, !report.contains(HdrBoost.SCREEN_CHECKS) && !report.contains(HdrBoost.SRGB_LAYERS));
    }

    /**
     * Instagram's boolean read of "SDR super white on HDR content" or "Ultra HDR always on", named by
     * the table index in the specifier's bits 32 to 47, answers no while the switch is on (#85). Other
     * options, an index the patch didn't find (-1), and the switch off or paused keep Instagram's.
     */
    @Test
    public void onInstagramsHdrOptionsReadAsOff() {
        long superWhite = 0x0081_051D_0000_1234L;
        long ultraHdr = 0x0081_1099_0000_5986L;
        long another = 0x0081_109A_0000_5986L;
        assertTrue(HdrBoost.optionOff(superWhite, 1309, 4249));
        assertTrue(HdrBoost.optionOff(ultraHdr, 1309, 4249));
        assertTrue(HdrBoost.optionOff(ultraHdr, -1, 4249));
        assertEquals(false, HdrBoost.optionOff(another, 1309, 4249));
        assertEquals(false, HdrBoost.optionOff(superWhite, -1, 4249));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HdrBoost.OPTIONS_OFF + " 3"));

        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        assertEquals(false, HdrBoost.optionOff(ultraHdr, 1309, 4249));
        Settings.TURN_OFF_HDR_BOOSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(false, HdrBoost.optionOff(superWhite, 1309, 4249));
    }

    /** The screen stand-ins read the display Instagram asked about and answer for it. */
    @Test
    public void theDisplayStandInsAnswerForTheDisplay() {
        android.view.Display display = Robolectric.buildActivity(Activity.class).setup().get().getWindowManager().getDefaultDisplay();
        assertEquals(false, HdrBoost.isHdr(display));
        android.view.Display.HdrCapabilities capabilities = display.getHdrCapabilities();
        if (capabilities != null) assertEquals(0, HdrBoost.hdrTypes(capabilities).length);
        if (Build.VERSION.SDK_INT >= 34) assertEquals(0, HdrBoost.modeHdrTypes(display.getMode()).length);
    }

    /** The window's stand-ins set what they answer on the window itself. */
    @Test
    public void theWindowGetsTheAnswer() {
        Window window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        HdrBoost.colorMode(window, 2);
        assertEquals(0, window.getColorMode());
        if (Build.VERSION.SDK_INT >= 35) {
            HdrBoost.windowHeadroom(window, 1.5f);
            assertEquals(1.0f, window.getDesiredHdrHeadroom(), 0);
        }

        Settings.TURN_OFF_HDR_BOOSTS.save(false);
        HdrBoost.colorMode(window, 1);
        assertEquals(1, window.getColorMode());
        if (Build.VERSION.SDK_INT >= 35) {
            HdrBoost.windowHeadroom(window, 1.5f);
            assertEquals(1.5f, window.getDesiredHdrHeadroom(), 0);
        }
    }
}
