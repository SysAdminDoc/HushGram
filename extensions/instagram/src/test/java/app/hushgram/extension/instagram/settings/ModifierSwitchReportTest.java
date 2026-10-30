/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BooleanSetting;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The report line of a family whose main switch has switches under it. A main switch that acts on
 * its own reads on with every switch under it off. Turn off double tap to like acts only through
 * the places under it, so it reads off then. A switch under one that's off reads off either way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ModifierSwitchReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        for (PatchFamily family : PatchFamily.values()) {
            for (BooleanSetting setting : family.switches) setting.resetToDefault();
        }
    }

    /**
     * Every family with a switch under another of its switches is named here, so a new one gets a
     * decision: does its main switch act on its own, or only through the ones under it?
     */
    @Test
    public void aMainSwitchThatActsOnItsOwnReadsOnWithEverySwitchUnderItOff() {
        Set<PatchFamily> nested = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : PatchFamily.values()) {
            for (BooleanSetting setting : family.switches) if (isUnderAnother(family, setting)) nested.add(family);
        }
        assertEquals(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM, PatchFamily.THREAD_SEEN, PatchFamily.FOLLOWING_FEED,
                PatchFamily.GLASS_TAB_BAR, PatchFamily.COMMENT_COPY, PatchFamily.DOUBLE_TAP_LIKE, PatchFamily.ACCENT_COLOR,
                PatchFamily.FULL_SCREEN_BARS), nested);

        for (PatchFamily family : nested) {
            for (BooleanSetting setting : family.switches) setting.save(!isUnderAnother(family, setting));
            String line = line(family);
            if (family == PatchFamily.DOUBLE_TAP_LIKE) {
                assertTrue(line, line.contains(": disabled by its switch ("));
            } else {
                assertTrue(line, line.contains(": on ("));
            }

            for (BooleanSetting setting : family.switches) setting.save(isUnderAnother(family, setting));
            line = line(family);
            assertTrue(line, line.contains(": disabled by its switch ("));
        }
    }

    private static boolean isUnderAnother(PatchFamily family, BooleanSetting setting) {
        for (Object parent : setting.getParentSettings()) if (family.switches.contains(parent)) return true;
        return false;
    }

    private static String line(PatchFamily family) {
        return PatchFamily.reportLines(EnumSet.of(family), false).get(0);
    }
}
