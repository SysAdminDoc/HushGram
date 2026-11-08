/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Instagram Plus offer check hands back (#559). */
@RunWith(RobolectricTestRunner.class)
public class PlusUpsellTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PLUS_UPSELL.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_PLUS_UPSELL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** It starts off, and needs no restart: each spot asks again the next time it's drawn. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.HIDE_PLUS_UPSELL.resetToDefault();
        assertFalse(Settings.HIDE_PLUS_UPSELL.get());
        assertFalse(Settings.HIDE_PLUS_UPSELL.rebootApp);
    }

    /** On, no spot offers Plus, and the hook counts as reached. */
    @Test
    public void onNothingOffersPlus() {
        assertFalse("Instagram would offer it", PlusUpsell.offer(1));
        assertFalse("Instagram wouldn't", PlusUpsell.offer(0));
        assertTrue(HookStatus.missing(FamilyNames.HIDE_PLUS_UPSELL).toString(),
                HookStatus.missing(FamilyNames.HIDE_PLUS_UPSELL).isEmpty());
    }

    /** The switch only ever takes an offer away: a spot Instagram wouldn't offer Plus in never gets one. */
    @Test
    public void aSpotWithoutAnOfferNeverGetsOne() {
        Settings.HIDE_PLUS_UPSELL.save(false);
        assertFalse("off", PlusUpsell.offer(0));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", PlusUpsell.offer(0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("no context", PlusUpsell.offer(0)));
    }

    /** Off, paused or before the settings are read, Instagram's own answer goes through. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.HIDE_PLUS_UPSELL.save(false);
        assertTrue("off", PlusUpsell.offer(1));
        Settings.HIDE_PLUS_UPSELL.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", PlusUpsell.offer(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue("no context", PlusUpsell.offer(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue("pause undecided", PlusUpsell.offer(1)));

        assertFalse("back on", PlusUpsell.offer(1));
    }

    /**
     * Meta's newer offer is left out only while the switch is on: off, paused or before the
     * settings are read, it's built as Instagram builds it. Asking counts the hook as reached.
     */
    @Test
    public void theNewerOfferIsLeftOutOnlyWhileTheSwitchIsOn() {
        assertTrue("on", PlusUpsell.hide());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_PLUS_UPSELL).toString(),
                HookStatus.missing(FamilyNames.HIDE_PLUS_UPSELL).isEmpty());

        Settings.HIDE_PLUS_UPSELL.save(false);
        assertFalse("off", PlusUpsell.hide());
        Settings.HIDE_PLUS_UPSELL.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", PlusUpsell.hide());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", PlusUpsell.hide()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", PlusUpsell.hide()));

        assertTrue("back on", PlusUpsell.hide());
    }
}
