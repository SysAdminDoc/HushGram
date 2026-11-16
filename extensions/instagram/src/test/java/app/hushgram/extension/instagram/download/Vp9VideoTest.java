/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ask for VP9 instead of AV1 (#546): each hardware AV1 answer Instagram puts in its device status
 * comes back as no while the switch is on, written the way Instagram wrote it. Every other state,
 * and any value the hook doesn't know, leaves Instagram's own answer in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class Vp9VideoTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private HookStatus.Snapshot saved;

    @Before
    public void setUp() {
        saved = HookStatus.snapshotAndClear();
        PauseForTests.resume();
        Settings.ASK_FOR_VP9.save(true);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        Settings.ASK_FOR_VP9.resetToDefault();
        HookStatus.restore(saved);
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.ASK_FOR_VP9.resetToDefault();
        assertFalse(Settings.ASK_FOR_VP9.get());
        assertSame(Boolean.TRUE, Vp9Video.hardwareAv1(Boolean.TRUE));
    }

    @Test
    public void anOnSwitchSaysNoInTheSameType() {
        assertSame(Boolean.FALSE, Vp9Video.hardwareAv1(Boolean.TRUE));
        assertSame(Boolean.FALSE, Vp9Video.hardwareAv1(Boolean.FALSE));
        assertEquals("false", Vp9Video.hardwareAv1("true"));
        assertEquals("false", Vp9Video.hardwareAv1("TRUE"));
        assertEquals("0", Vp9Video.hardwareAv1("1"));
        assertEquals(0, Vp9Video.hardwareAv1(1));
        assertEquals(0L, Vp9Video.hardwareAv1(1L));
        assertFalse("the call wasn't counted", HookStatus.snapshot().isEmpty());
    }

    @Test
    public void anythingElseGoesInAsItWas() {
        Object other = new Object();
        assertSame(other, Vp9Video.hardwareAv1(other));
        assertSame("unknown", Vp9Video.hardwareAv1("unknown"));
        assertEquals(null, Vp9Video.hardwareAv1(null));
        Double rate = 1.0;
        assertSame(rate, Vp9Video.hardwareAv1(rate));
    }

    @Test
    public void anOffSwitchKeepsInstagramsAnswer() {
        Settings.ASK_FOR_VP9.save(false);
        assertSame(Boolean.TRUE, Vp9Video.hardwareAv1(Boolean.TRUE));
        assertEquals("true", Vp9Video.hardwareAv1("true"));
    }

    @Test
    public void pauseKeepsInstagramsAnswer() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(Boolean.TRUE, Vp9Video.hardwareAv1(Boolean.TRUE));
    }

    @Test
    public void unreadySettingsKeepInstagramsAnswer() {
        SettingsContextRule.withoutContext(() -> assertSame("no context", Boolean.TRUE, Vp9Video.hardwareAv1(Boolean.TRUE)));
        SettingsContextRule.beforeThePauseIsDecided(
                () -> assertSame("pause undecided", Boolean.TRUE, Vp9Video.hardwareAv1(Boolean.TRUE)));
        assertSame("ready again", Boolean.FALSE, Vp9Video.hardwareAv1(Boolean.TRUE));
    }

    @Test
    public void aThrowingSwitchKeepsInstagramsAnswer() {
        Object answer = Vp9Video.hardwareAv1(Boolean.TRUE, () -> {
            throw new IllegalStateException("broken");
        });
        assertSame(Boolean.TRUE, answer);
        assertFalse("the throw wasn't reported", HookStatus.snapshot().isEmpty());
    }
}
