/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide the Repost button answers that a post can't be reposted. */
@RunWith(RobolectricTestRunner.class)
public class RepostButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.HIDE_REPOST_BUTTON.save(true);
    }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @After
    public void restore() {
        Settings.HIDE_REPOST_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    /** With the switch on, every post reads as one that can't be reposted. */
    @Test
    public void withTheSwitchOnNothingCanBeReposted() {
        assertTrue(RepostButton.hide());
        assertSame(Boolean.FALSE, RepostButton.eligible(Boolean.TRUE));
        assertSame(Boolean.FALSE, RepostButton.eligible(null));
    }

    @Test
    public void withTheSwitchOffTheTreeAnswersAsItDid() {
        Settings.HIDE_REPOST_BUTTON.save(false);
        assertFalse(RepostButton.hide());
        assertSame(Boolean.TRUE, RepostButton.eligible(Boolean.TRUE));
        assertSame(Boolean.FALSE, RepostButton.eligible(Boolean.FALSE));
        assertNull(RepostButton.eligible(null));
    }

    /** The Feed UFI binder gets a last pass because its state can be built before settings are ready. */
    @Test
    public void withTheSwitchOnTheFeedUfiRepostViewsHide() {
        View icon = new View(RuntimeEnvironment.getApplication());
        View count = new View(RuntimeEnvironment.getApplication());

        RepostButton.feedUfi(icon, count);

        assertEquals(View.GONE, icon.getVisibility());
        assertEquals(View.GONE, count.getVisibility());
        assertNull(icon.getContentDescription());
        assertNull(count.getContentDescription());
    }

    @Test
    public void withTheSwitchOffTheFeedUfiViewsStayAsTheyWere() {
        Settings.HIDE_REPOST_BUTTON.save(false);
        View icon = new View(RuntimeEnvironment.getApplication());
        View count = new View(RuntimeEnvironment.getApplication());
        icon.setContentDescription("Repost");
        count.setContentDescription("1");

        RepostButton.feedUfi(icon, count);

        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals(View.VISIBLE, count.getVisibility());
        assertEquals("Repost", icon.getContentDescription());
        assertEquals("1", count.getContentDescription());
    }

    @Test
    @Config(sdk = {28, 37})
    public void componentRenderChecksTheCurrentSwitchPauseAndReadiness() {
        assertTrue(RepostButton.feedComponent());
        Settings.HIDE_REPOST_BUTTON.save(false);
        assertFalse(RepostButton.feedComponent());
        Settings.HIDE_REPOST_BUTTON.save(true);
        assertTrue(RepostButton.feedComponent());
        try {
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse(RepostButton.feedComponent());
        } finally {
            PauseForTests.resume();
        }
        assertTrue(RepostButton.feedComponent());
        SettingsContextRule.withoutContext(() -> assertFalse(RepostButton.feedComponent()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(RepostButton.feedComponent()));
        assertTrue(RepostButton.feedComponent());
    }

    /** Feed's action-row state keeps the Repost button and its count off while the switch is on (#69). */
    @Test
    @Config(sdk = {28, 37})
    public void withTheSwitchOnTheFeedStateIsOff() {
        HookStatus.clear();
        assertFalse(RepostButton.feedState(1));
        assertFalse(RepostButton.feedState(0));
        assertEquals(List.of(FamilyNames.REPOST_BUTTON + ": invoked 2, 0 found, 0 missing. Counted: feed state off 2"),
                HookStatus.report());
    }

    /** Off, the flag is Instagram's, handed over as an int that reads any non-zero as yes. */
    @Test
    @Config(sdk = {28, 37})
    public void withTheSwitchOffTheFeedStateIsAsInstagramBuiltIt() {
        Settings.HIDE_REPOST_BUTTON.save(false);
        HookStatus.clear();
        assertTrue(RepostButton.feedState(1));
        assertFalse(RepostButton.feedState(0));
        assertTrue("2 is a yes too", RepostButton.feedState(2));
        assertEquals(List.of(FamilyNames.REPOST_BUTTON + ": invoked 3, 0 found, 0 missing. Counted: feed state on 3"),
                HookStatus.report());
    }

    /** A state built before the settings can be read, or while HushGram is paused, keeps Instagram's flag. */
    @Test
    @Config(sdk = {28, 37})
    public void theFeedStateWaitsForNeitherTheSettingsNorThePause() {
        SettingsContextRule.withoutContext(() -> assertTrue(RepostButton.feedState(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(RepostButton.feedState(1)));
        try {
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertTrue(RepostButton.feedState(1));
        } finally {
            PauseForTests.resume();
        }
        assertFalse(RepostButton.feedState(1));
    }

    /** A switch that throws leaves the flag as Instagram built it, and the report names the hook. */
    @Test
    public void aThrowingSwitchLeavesTheFeedStateAndIsReported() {
        HookStatus.clear();
        assertTrue(RepostButton.feedState(true, THROWS));
        assertFalse(RepostButton.feedState(false, THROWS));
        String missing = HookStatus.missing(FamilyNames.REPOST_BUTTON).toString();
        assertTrue(missing, missing.contains("'feed state'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    @Config(sdk = {28, 37})
    public void recycledViewsRestoreOwnedStateBeforeNativeWrites() {
        View icon = new View(RuntimeEnvironment.getApplication());
        View count = new View(RuntimeEnvironment.getApplication());
        int[] clicks = {0};
        icon.setOnClickListener(v -> clicks[0]++);
        icon.setContentDescription("Repost");
        count.setVisibility(View.INVISIBLE);
        count.setEnabled(false);
        count.setLongClickable(true);
        count.setContentDescription("1");

        RepostButton.feedUfi(icon, count);
        RepostButton.feedUfi(icon, count);
        assertFalse(icon.isEnabled());
        assertFalse(icon.isClickable());
        assertFalse(count.isLongClickable());
        assertFalse(icon.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(0, clicks[0]);

        Settings.HIDE_REPOST_BUTTON.save(false);
        RepostButton.restoreFeedUfi(icon, count);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals(View.INVISIBLE, count.getVisibility());
        assertTrue(icon.isEnabled());
        assertFalse(count.isEnabled());
        assertTrue(count.isLongClickable());
        assertEquals("Repost", icon.getContentDescription());
        assertEquals("1", count.getContentDescription());
        assertTrue(icon.performClick());
        assertEquals(1, clicks[0]);

        // Native binding owns these fresh values. Off must neither hide nor overwrite them.
        icon.setContentDescription("Native rebound description");
        RepostButton.feedUfi(icon, count);
        RepostButton.restoreFeedUfi(icon, count);
        assertEquals("Native rebound description", icon.getContentDescription());
        RepostButton.restoreFeedUfi(null, null);

        Settings.HIDE_REPOST_BUTTON.save(true);
        RepostButton.feedUfi(icon, count);
        try {
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            RepostButton.restoreFeedUfi(icon, count);
            RepostButton.feedUfi(icon, count);
            assertEquals(View.VISIBLE, icon.getVisibility());
        } finally {
            PauseForTests.resume();
        }
        RepostButton.feedUfi(icon, count);
        SettingsContextRule.withoutContext(() -> {
            RepostButton.restoreFeedUfi(icon, count);
            RepostButton.feedUfi(icon, count);
            assertEquals(View.VISIBLE, icon.getVisibility());
        });
    }
}
