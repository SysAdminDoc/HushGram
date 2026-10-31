/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/** What the two Explore personalization switches answer the hooks in Explore's fragment, timer and top bars. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ExploreMenuTest {
    private static final int MENU_ICON = 0x7f0827c4;
    private static final int OTHER_ICON = 0x7f082274;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.resetToDefault();
        Settings.HIDE_EXPLORE_MENU.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.resetToDefault();
        Settings.HIDE_EXPLORE_MENU.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void bothStartOffAndLeaveExploreAlone() {
        assertFalse(Settings.EXPLORE_WITHOUT_PERSONALIZATION.get());
        assertFalse(Settings.HIDE_EXPLORE_MENU.get());

        assertFalse("resume", ExploreMenu.unpersonalize());
        assertFalse("timer", ExploreMenu.keepUnpersonalized());
        View button = view();
        ExploreMenu.button(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertFalse("older bar", ExploreMenu.oldButton(MENU_ICON, MENU_ICON));

        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(ExploreMenu.UNPERSONALIZED));
        assertFalse(report, report.contains(ExploreMenu.KEPT));
        assertFalse(report, report.contains(ExploreMenu.HIDDEN));
        assertTrue(HookStatus.missing(FamilyNames.EXPLORE_MENU).toString(),
                HookStatus.missing(FamilyNames.EXPLORE_MENU).isEmpty());
    }

    /** The resume gate says yes without counting, and only the reload the patch then makes is counted. */
    @Test
    public void withoutPersonalizationAnswersTheResumeAndTheTimer() {
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.save(true);

        assertTrue("resume", ExploreMenu.unpersonalize());
        assertFalse("not counted before the reload", HookStatus.report().toString().contains(ExploreMenu.UNPERSONALIZED));
        ExploreMenu.reloaded();
        assertTrue("timer", ExploreMenu.keepUnpersonalized());

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ExploreMenu.UNPERSONALIZED + " 1"));
        assertTrue(report, report.contains(ExploreMenu.KEPT + " 1"));
        assertFalse("the menu is a switch of its own", ExploreMenu.oldButton(MENU_ICON, MENU_ICON));
    }

    @Test
    public void hideTheMenuHidesOnlyTheMenuButton() {
        Settings.HIDE_EXPLORE_MENU.save(true);

        View button = view();
        ExploreMenu.button(button);
        assertEquals(View.GONE, button.getVisibility());
        assertTrue("older bar, the menu's icon", ExploreMenu.oldButton(MENU_ICON, MENU_ICON));
        assertFalse("older bar, another icon", ExploreMenu.oldButton(OTHER_ICON, MENU_ICON));
        ExploreMenu.button(null);

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ExploreMenu.HIDDEN + " 2"));
        assertFalse("the grid is a switch of its own", ExploreMenu.unpersonalize());
        assertFalse(ExploreMenu.keepUnpersonalized());
    }

    @Test
    public void pausedAnswersInstagramsOwn() {
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.save(true);
        Settings.HIDE_EXPLORE_MENU.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertAllOff("paused");
        assertTrue(Settings.EXPLORE_WITHOUT_PERSONALIZATION.savedValue());
        assertTrue(Settings.HIDE_EXPLORE_MENU.savedValue());
    }

    @Test
    public void settingsNotReadyAnswerInstagramsOwn() {
        Settings.EXPLORE_WITHOUT_PERSONALIZATION.save(true);
        Settings.HIDE_EXPLORE_MENU.save(true);

        SettingsContextRule.withoutContext(() -> assertAllOff("no context"));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertAllOff("pause undecided"));
    }

    private static void assertAllOff(String when) {
        assertFalse(when + ", resume", ExploreMenu.unpersonalize());
        assertFalse(when + ", timer", ExploreMenu.keepUnpersonalized());
        View button = view();
        ExploreMenu.button(button);
        assertEquals(when + ", newer bar", View.VISIBLE, button.getVisibility());
        assertFalse(when + ", older bar", ExploreMenu.oldButton(MENU_ICON, MENU_ICON));
    }

    private static View view() {
        return new View(RuntimeEnvironment.getApplication());
    }
}
