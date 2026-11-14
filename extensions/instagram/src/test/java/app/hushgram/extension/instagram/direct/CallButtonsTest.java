/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The call buttons switch: off, nothing changes; on, call kinds go GONE and nothing else does; off or paused gives them back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CallButtonsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A model stand-in: its kind is its text. */
    private static final CallButtons.Kinds BY_TEXT = model -> model.toString();

    private View view;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_CALL_BUTTONS.save(true);
        CallButtons.access = BY_TEXT;
        view = new View(RuntimeEnvironment.getApplication());
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_CALL_BUTTONS.resetToDefault();
        Settings.HIDE_CHAT_BLEND.resetToDefault();
        Settings.HIDE_CHAT_BACK_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        CallButtons.access = CallButtons.PATCHED;
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEveryCallKindGoesGone() {
        for (String kind : CallButtons.CALL_KINDS) {
            View each = new View(RuntimeEnvironment.getApplication());
            CallButtons.bound(each, kind);
            assertEquals(kind, View.GONE, each.getVisibility());
        }
        assertTrue(CallButtons.CALL_KINDS.contains("VIDEO_CALL") && CallButtons.CALL_KINDS.contains("AUDIO_CALL"));
        assertTrue(HookStatus.missing(FamilyNames.ASK_BEFORE_CALL).toString(), HookStatus.missing(FamilyNames.ASK_BEFORE_CALL).isEmpty());
    }

    @Test
    public void otherKindsAndOngoingCallsStay() {
        for (String kind : new String[]{"ONGOING_VIDEO_CALL", "ONGOING_AUDIO_CALL", "INFO", "MORE", "ADD_PEOPLE", "SPEAKER", "SOMETHING_NEW"}) {
            View each = new View(RuntimeEnvironment.getApplication());
            CallButtons.bound(each, kind);
            assertEquals(kind, View.VISIBLE, each.getVisibility());
        }
    }

    @Test
    public void offToStartLeavesTheButtonsAlone() {
        Settings.HIDE_CALL_BUTTONS.resetToDefault();
        CallButtons.bound(view, "VIDEO_CALL");
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    @Test
    public void turningItOffOrPausingGivesTheButtonBackOnTheNextBind() {
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals(View.GONE, view.getVisibility());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals(View.VISIBLE, view.getVisibility());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals(View.GONE, view.getVisibility());

        Settings.HIDE_CALL_BUTTONS.save(false);
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    @Test
    public void aRecycledViewBoundToAnotherKindShowsAgain() {
        CallButtons.bound(view, "VIDEO_CALL");
        assertEquals(View.GONE, view.getVisibility());
        CallButtons.bound(view, "INFO");
        assertEquals(View.VISIBLE, view.getVisibility());
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals(View.GONE, view.getVisibility());
        CallButtons.bound(view, null);
        assertEquals("no model", View.VISIBLE, view.getVisibility());
        CallButtons.bound(view, "AUDIO_CALL");
        CallButtons.access = model -> null;
        CallButtons.bound(view, "AUDIO_CALL");
        assertEquals("a model with no kind", View.VISIBLE, view.getVisibility());
    }

    @Test
    public void aViewInstagramHidSelfIsNotShownByTheSwitchOff() {
        Settings.HIDE_CALL_BUTTONS.save(false);
        view.setVisibility(View.GONE);
        CallButtons.bound(view, "VIDEO_CALL");
        assertEquals(View.GONE, view.getVisibility());
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        CallButtons.access = model -> {
            throw new IllegalStateException("model changed");
        };
        CallButtons.bound(view, "VIDEO_CALL");
        CallButtons.bound(null, "VIDEO_CALL");
        CallButtons.bound(view, null);
        assertEquals(View.VISIBLE, view.getVisibility());
        Settings.HIDE_CHAT_BACK_BUTTON.save(true);
        assertEquals("BACK", CallButtons.leftButton("BACK"));
    }

    @Test
    public void blendGoesOnlyWithItsOwnSwitch() {
        for (String kind : new String[]{"BLEND", "BLEND_ADD"}) {
            View each = new View(RuntimeEnvironment.getApplication());
            CallButtons.bound(each, kind);
            assertEquals("the call switch alone keeps " + kind, View.VISIBLE, each.getVisibility());
        }
        Settings.HIDE_CALL_BUTTONS.save(false);
        Settings.HIDE_CHAT_BLEND.save(true);
        for (String kind : new String[]{"BLEND", "BLEND_ADD"}) {
            View each = new View(RuntimeEnvironment.getApplication());
            CallButtons.bound(each, kind);
            assertEquals(kind, View.GONE, each.getVisibility());
        }
        CallButtons.bound(view, "VIDEO_CALL");
        assertEquals("the Blend switch alone keeps the call buttons", View.VISIBLE, view.getVisibility());

        CallButtons.bound(view, "BLEND");
        assertEquals(View.GONE, view.getVisibility());
        Settings.HIDE_CHAT_BLEND.save(false);
        CallButtons.bound(view, "BLEND");
        assertEquals("turned off, Blend is back on the next bind", View.VISIBLE, view.getVisibility());
    }

    @Test
    public void theBackArrowIsLeftOutOnlyWithItsSwitchOn() {
        assertEquals("off to start", "BACK", CallButtons.leftButton("BACK"));
        Settings.HIDE_CHAT_BACK_BUTTON.save(true);
        assertEquals(null, CallButtons.leftButton("BACK"));
        assertEquals("a Cancel button stays", "CANCEL", CallButtons.leftButton("CANCEL"));
        assertEquals(null, CallButtons.leftButton(null));
        CallButtons.access = model -> null;
        assertEquals("a model with no kind", "BACK", CallButtons.leftButton("BACK"));
        CallButtons.access = BY_TEXT;

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", "BACK", CallButtons.leftButton("BACK"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(null, CallButtons.leftButton("BACK"));
        assertTrue(HookStatus.missing(FamilyNames.ASK_BEFORE_CALL).toString(), HookStatus.missing(FamilyNames.ASK_BEFORE_CALL).isEmpty());
    }
}
