/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Chat plus menu does to the plus menu's list: the items of the rows the switches name leave it,
 * the gallery and microphone items join it when asked, and with every switch off, paused or on
 * anything unexpected the list is left as Instagram built it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PlusMenuTest {
    /** Stand-in for Instagram's button enum: the names are all the hook reads. */
    private enum Kind { CROSS_APP_SAVES, IMAGINE, DOODLES, GALLERY, VOICE_MESSAGE, STICKER, OTHER }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Map<Kind, Object> byButton = new LinkedHashMap<>();
    private List<Object> menu;

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        resetSwitches();
        HookStatus.clear();
        PlusMenu.inBuildForTests = true;
        for (Kind kind : Kind.values()) byButton.put(kind, new Object());
        menu = new ArrayList<>(Arrays.asList(byButton.get(Kind.CROSS_APP_SAVES), byButton.get(Kind.IMAGINE),
                byButton.get(Kind.DOODLES), byButton.get(Kind.OTHER)));
    }

    @After
    public void restore() {
        resetSwitches();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        PlusMenu.inBuildForTests = null;
    }

    private static void resetSwitches() {
        Settings.HIDE_PLUS_SAVED.resetToDefault();
        Settings.HIDE_PLUS_AI_IMAGES.resetToDefault();
        Settings.HIDE_PLUS_DRAW.resetToDefault();
        Settings.MOVE_TO_PLUS_MENU.resetToDefault();
    }

    private List<Kind> kinds() {
        List<Kind> kinds = new ArrayList<>();
        for (Object row : menu) {
            for (Map.Entry<Kind, Object> entry : byButton.entrySet()) if (entry.getValue() == row) kinds.add(entry.getKey());
        }
        return kinds;
    }

    /** Every switch starts off, and then the menu is exactly what Instagram built. */
    @Test
    public void everySwitchStartsOffAndLeavesTheMenuAlone() {
        assertFalse(Settings.HIDE_PLUS_SAVED.get());
        assertFalse(Settings.HIDE_PLUS_AI_IMAGES.get());
        assertFalse(Settings.HIDE_PLUS_DRAW.get());
        assertFalse(Settings.MOVE_TO_PLUS_MENU.get());
        PlusMenu.menu(menu, byButton);
        assertEquals(Arrays.asList(Kind.CROSS_APP_SAVES, Kind.IMAGINE, Kind.DOODLES, Kind.OTHER), kinds());
        assertFalse(PlusMenu.moved());
    }

    /** Each switch takes out its own row and no other. */
    @Test
    public void eachSwitchTakesOutItsOwnRow() {
        Settings.HIDE_PLUS_SAVED.save(true);
        PlusMenu.menu(menu, byButton);
        assertEquals(Arrays.asList(Kind.IMAGINE, Kind.DOODLES, Kind.OTHER), kinds());
        Settings.HIDE_PLUS_AI_IMAGES.save(true);
        PlusMenu.menu(menu, byButton);
        assertEquals(Arrays.asList(Kind.DOODLES, Kind.OTHER), kinds());
        Settings.HIDE_PLUS_DRAW.save(true);
        PlusMenu.menu(menu, byButton);
        assertEquals(Collections.singletonList(Kind.OTHER), kinds());
    }

    /** Draw alone leaves Saved and AI images. */
    @Test
    public void drawAloneLeavesTheOthers() {
        Settings.HIDE_PLUS_DRAW.save(true);
        PlusMenu.menu(menu, byButton);
        assertEquals(Arrays.asList(Kind.CROSS_APP_SAVES, Kind.IMAGINE, Kind.OTHER), kinds());
    }

    /** Moving adds the bar's own gallery and microphone items once, and takes nothing out. */
    @Test
    public void moveAddsTheBarsOwnItemsOnce() {
        Settings.MOVE_TO_PLUS_MENU.save(true);
        PlusMenu.menu(menu, byButton);
        PlusMenu.menu(menu, byButton);
        assertEquals(Arrays.asList(Kind.CROSS_APP_SAVES, Kind.IMAGINE, Kind.DOODLES, Kind.OTHER, Kind.GALLERY, Kind.VOICE_MESSAGE), kinds());
        assertSame(byButton.get(Kind.GALLERY), menu.get(4));
        assertSame(byButton.get(Kind.VOICE_MESSAGE), menu.get(5));
        assertTrue(PlusMenu.moved());
    }

    /** Patched without Chat plus menu, a saved move doesn't count: there's no menu to hold the buttons. */
    @Test
    public void withoutThePatchASavedMoveDoesNotCount() {
        Settings.MOVE_TO_PLUS_MENU.save(true);
        PlusMenu.inBuildForTests = false;
        assertFalse(PlusMenu.moved());
        assertTrue(Settings.MOVE_TO_PLUS_MENU.get());
        PlusMenu.inBuildForTests = null;
        assertFalse("the stub answers for a build without the patch", PlusMenu.moved());
    }

    /** Paused, the menu is left as built and the choice is kept. */
    @Test
    public void pausedLeavesTheMenuAlone() {
        Settings.HIDE_PLUS_SAVED.save(true);
        Settings.MOVE_TO_PLUS_MENU.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        PlusMenu.menu(menu, byButton);
        assertEquals(4, menu.size());
        assertFalse(PlusMenu.moved());
    }

    /** A null, an unchangeable list or keys that aren't enums change nothing and throw nothing. */
    @Test
    public void anythingUnexpectedIsLeftAlone() {
        Settings.HIDE_PLUS_SAVED.save(true);
        Settings.MOVE_TO_PLUS_MENU.save(true);
        PlusMenu.menu(null, byButton);
        PlusMenu.menu(menu, null);
        assertEquals(4, menu.size());
        Map<Object, Object> odd = new LinkedHashMap<>();
        odd.put("CROSS_APP_SAVES", new Object());
        odd.put(3, new Object());
        PlusMenu.menu(menu, odd);
        assertEquals(4, menu.size());
        List<Object> fixed = Collections.unmodifiableList(new ArrayList<>(menu));
        PlusMenu.menu(fixed, byButton);
        assertEquals(4, fixed.size());
    }
}
