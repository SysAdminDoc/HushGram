/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Ghost mode from the inbox: a long press on New message turns every ghost switch, and Pause leaves it stock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class GhostModeEntryTest {
    private static BooleanSetting[] GHOST;
    private EnumSet<PatchFamily> ALL_GHOST;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        ALL_GHOST = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.STORY_SEEN, PatchFamily.LIVE_SEEN, PatchFamily.THREAD_SEEN,
                PatchFamily.DM_MEDIA_SEEN, PatchFamily.TYPING, PatchFamily.SCREENSHOT_REPORTS);
        GHOST = new BooleanSetting[] {Settings.VIEW_STORIES_ANONYMOUSLY, Settings.VIEW_LIVE_ANONYMOUSLY,
                Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.VIEW_DM_MEDIA_ANONYMOUSLY, Settings.HIDE_TYPING, Settings.HIDE_SCREENSHOTS};
        for (BooleanSetting setting : GHOST) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        PatchFamily.inBuildForTests = ALL_GHOST;
        ShadowToast.reset();
    }
    @After public void close() {
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : GHOST) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    /** A view with the listener the patch hands the button's configuration, pressed as the system presses it. */
    private boolean longPress() {
        View.OnLongClickListener listener = GhostModeEntry.longPress();
        assertNotNull(listener);
        View button = new View(Robolectric.buildActivity(Activity.class).setup().get());
        button.setOnLongClickListener(listener);
        return button.performLongClick();
    }

    @Test public void aBuildWithoutAGhostPatchGetsNoListener() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.NOTES_ROW);
        assertNull(GhostModeEntry.longPress());
        PatchFamily.inBuildForTests = null;
    }

    @Test public void oneGhostPatchIsEnoughToOfferIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.TYPING);
        assertNotNull(GhostModeEntry.longPress());
        assertTrue(longPress());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertFalse("only the switches in the build are turned", Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
    }

    @Test public void aPressTurnsEverySwitchOnThenOffWithAToastEachTime() {
        assertTrue(longPress());
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.savedValue());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertEquals("Hide ads isn't a ghost switch", Settings.HIDE_ADS.defaultValue, Settings.HIDE_ADS.savedValue());

        assertTrue(longPress());
        for (BooleanSetting setting : GHOST) assertFalse(setting.key, setting.savedValue());
        assertEquals("Ghost mode is off, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void withSomeSwitchesAlreadyOnAPressTurnsThemAllOn() {
        Settings.HIDE_TYPING.save(true);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        assertTrue(longPress());
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.savedValue());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void eachSwitchShowsTheNewStateThroughItsOwnSetting() {
        assertTrue(longPress());
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.get());
        assertTrue("Ghost mode reads as on in the settings page's own check", GhostMode.on(GhostMode.switches(ALL_GHOST)));
    }

    @Test public void pauseStillWinsAndALongPressDoesNothing() {
        View.OnLongClickListener listener = GhostModeEntry.longPress();
        assertNotNull(listener);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        View button = new View(Robolectric.buildActivity(Activity.class).setup().get());
        button.setOnLongClickListener(listener);
        assertFalse("paused, the press is left to Instagram, which has none there", listener.onLongClick(button));
        for (BooleanSetting setting : GHOST) assertFalse(setting.key, setting.savedValue());
        assertNull("no toast while paused", ShadowToast.getLatestToast());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue("the same listener works again once HushGram resumes", button.performLongClick());
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.savedValue());
    }

    /** A button inside a bar, as the newer top bar holds its buttons, so a press that isn't taken can reach the parent. */
    private View button() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View button = new View(activity);
        new FrameLayout(activity).addView(button);
        return button;
    }

    /** The newer top bar: its New message button, bound to the model the maker handed over, gets the long press. */
    @Test public void theNewerBarsNewMessageButtonGetsTheLongPress() {
        Object newMessage = new Object();
        GhostModeEntry.newMessageAction(newMessage);
        View button = button();
        int[] taps = {0};
        button.setOnClickListener(v -> taps[0]++);
        GhostModeEntry.bindAction(button, newMessage);
        assertTrue(button.isLongClickable());
        assertTrue(button.performLongClick());
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.savedValue());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertTrue("a tap still starts a new message", button.performClick());
        assertEquals(1, taps[0]);
    }

    /** The bar reuses its buttons: one it gave the long press and now binds to another action loses it. */
    @Test public void aReusedButtonLosesTheLongPressWithItsNewAction() {
        Object newMessage = new Object();
        GhostModeEntry.newMessageAction(newMessage);
        View button = button();
        GhostModeEntry.bindAction(button, newMessage);
        assertTrue(button.isLongClickable());
        GhostModeEntry.bindAction(button, new Object());
        assertFalse(button.isLongClickable());
        assertFalse(button.performLongClick());
        for (BooleanSetting setting : GHOST) assertFalse(setting.key, setting.savedValue());
    }

    /** Every other button keeps whatever long press Instagram gave it. */
    @Test public void otherButtonsKeepTheirOwnLongPress() {
        View button = button();
        boolean[] instagrams = {false};
        button.setOnLongClickListener(v -> instagrams[0] = true);
        GhostModeEntry.bindAction(button, new Object());
        GhostModeEntry.bindAction(button, null);
        assertTrue(button.performLongClick());
        assertTrue("Instagram's own long press ran", instagrams[0]);
        for (BooleanSetting setting : GHOST) assertFalse(setting.key, setting.savedValue());
    }

    /** Paused, a long press on the newer bar's button does nothing, and a tap still starts a new message. */
    @Test public void pausedTheNewerBarsLongPressDoesNothing() {
        Object newMessage = new Object();
        GhostModeEntry.newMessageAction(newMessage);
        View button = button();
        int[] taps = {0};
        button.setOnClickListener(v -> taps[0]++);
        GhostModeEntry.bindAction(button, newMessage);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(button.performLongClick());
        for (BooleanSetting setting : GHOST) assertFalse(setting.key, setting.savedValue());
        assertNull("no toast while paused", ShadowToast.getLatestToast());
        assertTrue(button.performClick());
        assertEquals(1, taps[0]);
    }

    /** With no ghost patch in the build, the newer bar's New message button gets nothing. */
    @Test public void withoutAGhostPatchTheNewerBarsButtonGetsNothing() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        Object newMessage = new Object();
        GhostModeEntry.newMessageAction(newMessage);
        View button = button();
        GhostModeEntry.bindAction(button, newMessage);
        assertFalse(button.isLongClickable());
    }

    /** Nulls from Instagram, a maker's empty answer or a missing button, are left alone. */
    @Test public void nullsAreLeftAlone() {
        GhostModeEntry.newMessageAction(null);
        GhostModeEntry.bindAction(null, new Object());
        View button = button();
        GhostModeEntry.bindAction(button, null);
        assertFalse(button.isLongClickable());
    }
}
