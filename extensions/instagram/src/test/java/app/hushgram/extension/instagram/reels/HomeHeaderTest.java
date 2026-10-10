/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When the Home header switches leave the Create button or the notifications heart out, and when the list goes through. */
@RunWith(RobolectricTestRunner.class)
public class HomeHeaderTest {
    /** Icon ids of the stand-in buttons, named as Instagram 450's resources name them. */
    private static final Map<Integer, String> ICONS = Map.of(
            1, "instagram_add_outline_24",
            2, "instagram_direct_outline_24",
            3, "instagram_peek_add_outline_24",
            4, "instagram_add_filled_24");
    private static final IntFunction<String> NAMES = ICONS::get;
    private static final Map<Object, Integer> BUTTON_ICONS = Map.of("create", 1, "direct", 2, "snap", 3, "heart", 0);
    private static final ToIntFunction<Object> ICON = BUTTON_ICONS::get;
    private static final ToIntFunction<Object> HEART = button -> "heart".equals(button) ? 1 : 0;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_HOME_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static List<Object> header() {
        return Arrays.asList("create", "heart", "direct", "snap");
    }

    /** Both switches start off and a restart applies a change, so the header gets Instagram's own list. */
    @Test
    public void offToStartTheListGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.HIDE_HOME_CREATE_BUTTON.defaultValue);
        assertEquals(Boolean.FALSE, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.defaultValue);
        assertTrue(Settings.HIDE_HOME_CREATE_BUTTON.rebootApp);
        assertTrue(Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.rebootApp);
        List<Object> buttons = header();
        assertSame(buttons, HomeHeader.buttons(buttons));
        assertNull(HomeHeader.buttons(null));
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** Unpatched, both stubs answer nothing, so even with both switches on nothing is left out. */
    @Test
    public void unpatchedNothingIsLeftOut() {
        Settings.HIDE_HOME_CREATE_BUTTON.save(true);
        Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.save(true);
        assertEquals(0, HomeHeader.icon("create"));
        assertEquals(0, HomeHeader.heart("heart"));
        List<Object> buttons = header();
        assertSame(buttons, HomeHeader.buttons(buttons));
    }

    @Test
    public void theCreateButtonLeavesAloneAndIsCounted() {
        assertEquals(Arrays.asList("heart", "direct", "snap"), HomeHeader.without(header(), true, false, ICON, HEART, NAMES));
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: "
                + HomeHeader.CREATE_LEFT_OUT + " 1"), HookStatus.report());
    }

    @Test
    public void theHeartLeavesAloneAndIsCounted() {
        assertEquals(Arrays.asList("create", "direct", "snap"), HomeHeader.without(header(), false, true, ICON, HEART, NAMES));
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: "
                + HomeHeader.NOTIFICATIONS_LEFT_OUT + " 1"), HookStatus.report());
    }

    /** Messages and the other buttons stay, in order, when both are taken. */
    @Test
    public void bothLeaveTogetherAndTheOthersKeepTheirOrder() {
        assertEquals(Arrays.asList("direct", "snap"), HomeHeader.without(header(), true, true, ICON, HEART, NAMES));
    }

    /** A header with neither button gets its own list back, not a copy. */
    @Test
    public void aHeaderWithoutThemGetsItsOwnList() {
        List<Object> buttons = Arrays.asList("direct", "snap");
        assertSame(buttons, HomeHeader.without(buttons, true, true, ICON, HEART, NAMES));
    }

    /** Only Instagram's plus icon is Create: not a peek icon, no icon, an icon with no name yet. */
    @Test
    public void onlyThePlusIconCounts() {
        assertTrue(HomeHeader.isCreate(1, NAMES));
        assertTrue(HomeHeader.isCreate(4, NAMES));
        assertFalse(HomeHeader.isCreate(3, NAMES));
        assertFalse(HomeHeader.isCreate(2, NAMES));
        assertFalse(HomeHeader.isCreate(0, NAMES));
        assertFalse(HomeHeader.isCreate(5, NAMES));
        assertFalse(HomeHeader.isCreate(1, icon -> null));
    }

    /** Names come from the app's resources; an id with none is named "" and stays a button. */
    @Test
    public void iconsAreNamedFromResources() {
        assertEquals("ic_delete", HomeHeader.iconName(android.R.drawable.ic_delete));
        assertEquals("", HomeHeader.iconName(0x7f7ffffe));
        SettingsContextRule.withoutContext(() -> assertNull(HomeHeader.iconName(0x7f7ffffd)));
    }

    @Test
    public void pausedAndUnreadyLeaveTheList() {
        Settings.HIDE_HOME_CREATE_BUTTON.save(true);
        List<Object> buttons = header();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(buttons, HomeHeader.buttons(buttons));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(buttons, HomeHeader.buttons(buttons)));
    }

    /** A list that throws is reported and goes through as it came. */
    @Test
    public void aFailureIsReportedAndTheListGoesThrough() {
        Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.save(true);
        List<Object> buttons = new java.util.AbstractList<Object>() {
            @Override public Object get(int index) { throw new IllegalStateException("boom"); }
            @Override public int size() { return 2; }
        };
        assertSame(buttons, HomeHeader.buttons(buttons));
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains("home header buttons"));
    }
}
