/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When Hide the Threads button leaves the Threads button out of a profile's top bar, when Hide the
 * Create button leaves out the plus on your own (#254), and when the list goes through.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ThreadsButtonTest {
    /** Icon ids of the stand-in buttons, and their names as Instagram 450's resources give them. */
    private static final Map<Integer, String> ICONS = Map.of(
            1, "instagram_more_vertical_pano_outline_24",
            2, "instagram_app_threads_outline_24",
            3, "instagram_app_threads_pano_outline_24",
            4, "threads_icons_cotton_arrow_download_filled_24",
            6, "instagram_add_outline_24",
            7, "instagram_add_pano_filled_24");
    private static final IntFunction<String> NAMES = ICONS::get;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.HIDE_THREADS_BUTTON.resetToDefault();
        Settings.HIDE_CREATE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_THREADS_BUTTON.resetToDefault();
        Settings.HIDE_CREATE_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, so the bar gets Instagram's own list until the switch goes on. */
    @Test
    public void offToStartTheListGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.HIDE_THREADS_BUTTON.defaultValue);
        assertFalse("the next profile opened shows a change", Settings.HIDE_THREADS_BUTTON.rebootApp);
        List<Object> buttons = Arrays.asList("menu", "threads");
        assertSame(buttons, ThreadsButton.buttons(buttons));
        assertNull(ThreadsButton.buttons(null));
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** Each button with a Threads icon is left out and counted; the others keep their order. */
    @Test
    public void theThreadsButtonIsLeftOut() {
        List<Object> buttons = Arrays.asList("menu", "threads", "pano", "download");
        Map<Object, Integer> icons = Map.of("menu", 1, "threads", 2, "pano", 3, "download", 4);
        assertEquals(Arrays.asList("menu", "download"), ThreadsButton.without(buttons, icons::get, NAMES, true, false));
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 0, 0 found, 0 missing. Counted: "
                + ThreadsButton.LEFT_OUT + " 2"), HookStatus.report());
    }

    /** A bar with no Threads button gets its own list back, not a copy. */
    @Test
    public void aBarWithoutItGetsItsOwnList() {
        List<Object> buttons = Arrays.asList("menu", "download");
        Map<Object, Integer> icons = Map.of("menu", 1, "download", 4);
        assertSame(buttons, ThreadsButton.without(buttons, icons::get, NAMES, true, true));
    }

    /** Off to start, so your profile keeps its Create button until the switch goes on. */
    @Test
    public void createIsOffToStart() {
        assertEquals(Boolean.FALSE, Settings.HIDE_CREATE_BUTTON.defaultValue);
        assertFalse("the next profile opened shows a change", Settings.HIDE_CREATE_BUTTON.rebootApp);
    }

    /** With Create's switch alone, the plus is left out and counted, and the Threads button stays. */
    @Test
    public void theCreateButtonIsLeftOut() {
        List<Object> buttons = Arrays.asList("threads", "create", "menu");
        Map<Object, Integer> icons = Map.of("threads", 2, "create", 6, "menu", 1);
        assertEquals(Arrays.asList("threads", "menu"), ThreadsButton.without(buttons, icons::get, NAMES, false, true));
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 0, 0 found, 0 missing. Counted: "
                + ThreadsButton.CREATE_LEFT_OUT + " 1"), HookStatus.report());
    }

    /** Both switches on leave both out, each counted under its own name. */
    @Test
    public void bothSwitchesLeaveBothOut() {
        List<Object> buttons = Arrays.asList("threads", "create", "menu");
        Map<Object, Integer> icons = Map.of("threads", 2, "create", 6, "menu", 1);
        assertEquals(List.of("menu"), ThreadsButton.without(buttons, icons::get, NAMES, true, true));
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 0, 0 found, 0 missing. Counted: "
                + ThreadsButton.LEFT_OUT + " 1, " + ThreadsButton.CREATE_LEFT_OUT + " 1"), HookStatus.report());
    }

    /** Only the add outline is Create's; other add icons and Threads icons aren't. */
    @Test
    public void onlyTheAddOutlineIsCreate() {
        assertTrue(ThreadsButton.isCreate(6, NAMES));
        assertFalse(ThreadsButton.isCreate(7, NAMES));
        assertFalse(ThreadsButton.isCreate(2, NAMES));
        assertFalse(ThreadsButton.isCreate(0, NAMES));
        assertFalse(ThreadsButton.isCreate(6, icon -> null));
        assertFalse(ThreadsButton.isThreads(6, NAMES));
    }

    /** No icon, an icon with no name yet, and other Threads-looking names aren't the button. */
    @Test
    public void onlyInstagramsThreadsIconsCount() {
        assertTrue(ThreadsButton.isThreads(2, NAMES));
        assertTrue(ThreadsButton.isThreads(3, NAMES));
        assertFalse(ThreadsButton.isThreads(0, NAMES));
        assertFalse(ThreadsButton.isThreads(4, NAMES));
        assertFalse(ThreadsButton.isThreads(5, NAMES));
        assertFalse(ThreadsButton.isThreads(2, icon -> null));
    }

    /** Names come from the app's resources; an id with none is named "" and stays a button. */
    @Test
    public void iconsAreNamedFromResources() {
        assertEquals("ic_delete", ThreadsButton.iconName(android.R.drawable.ic_delete));
        assertEquals("", ThreadsButton.iconName(0x7f7ffffe));
        assertEquals("", ThreadsButton.iconName(0x7f7ffffe));
        SettingsContextRule.withoutContext(() -> assertNull(ThreadsButton.iconName(0x7f7ffffd)));
    }

    /** Unpatched, the stub answers no icon, so even with the switch on nothing is left out. */
    @Test
    public void unpatchedNothingIsLeftOut() {
        Settings.HIDE_THREADS_BUTTON.save(true);
        assertEquals(0, ThreadsButton.icon("threads"));
        List<Object> buttons = Arrays.asList("menu", "threads");
        assertSame(buttons, ThreadsButton.buttons(buttons));
    }

    @Test
    public void pausedAndUnreadyLeaveTheList() {
        Settings.HIDE_THREADS_BUTTON.save(true);
        List<Object> buttons = Arrays.asList("menu", "threads");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(buttons, ThreadsButton.buttons(buttons));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(buttons, ThreadsButton.buttons(buttons)));
    }

    /** 451's Threads chip stays until the switch goes on, then each one asked about is left out and counted. */
    @Test
    public void theThreadsChipIsLeftOutWithTheSwitchOn() {
        assertFalse(ThreadsButton.hideBanner());
        Settings.HIDE_THREADS_BUTTON.save(true);
        assertTrue(ThreadsButton.hideBanner());
        assertTrue(ThreadsButton.hideBanner());
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 3, 0 found, 0 missing. Counted: "
                + ThreadsButton.CHIP_LEFT_OUT + " 2"), HookStatus.report());
    }

    @Test
    public void pausedAndUnreadyKeepTheChip() {
        Settings.HIDE_THREADS_BUTTON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadsButton.hideBanner());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ThreadsButton.hideBanner()));
    }

    /** 451's own-profile plus, built in the action bar, stays until its own switch goes on, then is left out and counted. */
    @Test
    public void theActionBarPlusIsLeftOutWithTheCreateSwitchOn() {
        assertFalse(ThreadsButton.hideCreateInBar());
        Settings.HIDE_THREADS_BUTTON.save(true);
        assertFalse(ThreadsButton.hideCreateInBar());
        Settings.HIDE_CREATE_BUTTON.save(true);
        assertTrue(ThreadsButton.hideCreateInBar());
        assertEquals(List.of(FamilyNames.THREADS_BUTTON + ": invoked 3, 0 found, 0 missing. Counted: "
                + ThreadsButton.CREATE_LEFT_OUT + " 1"), HookStatus.report());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ThreadsButton.hideCreateInBar());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ThreadsButton.hideCreateInBar()));
    }
}
