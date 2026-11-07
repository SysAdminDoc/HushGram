/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

/** What Don't report Blend visits asks for when a Blend opens, and when it leaves the flag to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BlendVisitsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_BLEND_VISITS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_BLEND_VISITS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheVisitIsAlwaysLeftOut() {
        assertTrue(BlendVisits.quiet(0));
        assertTrue(BlendVisits.quiet(1));
        assertTrue(HookStatus.missing(FamilyNames.BLEND_VISITS).toString(), HookStatus.missing(FamilyNames.BLEND_VISITS).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingSendWhatInstagramAsked() {
        Settings.HIDE_BLEND_VISITS.resetToDefault();
        assertFalse(Settings.HIDE_BLEND_VISITS.get());
        assertFalse(BlendVisits.quiet(0));
        assertTrue(BlendVisits.quiet(1));
        Settings.HIDE_BLEND_VISITS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(BlendVisits.quiet(0));
        assertTrue(BlendVisits.quiet(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(BlendVisits.quiet(0)));
        assertTrue(BlendVisits.quiet(0));

        assertFalse(BlendVisits.quiet(0, THROWS));
        assertTrue(BlendVisits.quiet(1, THROWS));
        String missing = HookStatus.missing(FamilyNames.BLEND_VISITS).toString();
        assertTrue(missing, missing.contains("'" + BlendVisits.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void withTheSwitchOnTheVisitTimeIsSkipped() {
        assertTrue(BlendVisits.skipsVisitTime());
        assertTrue(HookStatus.missing(FamilyNames.BLEND_VISITS).toString(), HookStatus.missing(FamilyNames.BLEND_VISITS).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingSendTheVisitTime() {
        Settings.HIDE_BLEND_VISITS.resetToDefault();
        assertFalse(BlendVisits.skipsVisitTime());
        Settings.HIDE_BLEND_VISITS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(BlendVisits.skipsVisitTime());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(BlendVisits.skipsVisitTime()));
        assertTrue(BlendVisits.skipsVisitTime());

        assertFalse(BlendVisits.skipsVisitTime(THROWS));
        String missing = HookStatus.missing(FamilyNames.BLEND_VISITS).toString();
        assertTrue(missing, missing.contains("'" + BlendVisits.SWITCH + "'"));
    }
}
