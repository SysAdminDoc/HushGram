/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Stop swiping between tabs: only the main tabs' list is told no, and only while the switch is on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class TabSwipeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private FrameLayout main;
    private View mainList;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_TAB_SWIPING.save(true);
        TabSwipe.forgetForTests();
        HookStatus.clear();
        main = new FrameLayout(RuntimeEnvironment.getApplication());
        mainList = new View(RuntimeEnvironment.getApplication());
        main.addView(mainList);
    }

    @After
    public void restore() {
        Settings.STOP_TAB_SWIPING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        TabSwipe.forgetForTests();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheMainTabsListIsToldNo() {
        TabSwipe.mainPager(main);
        assertFalse(TabSwipe.input(mainList, 1));
        assertFalse(TabSwipe.input(mainList, 0));
        assertTrue(HookStatus.missing(FamilyNames.TAB_SWIPE).toString(), HookStatus.missing(FamilyNames.TAB_SWIPE).isEmpty());
    }

    @Test
    public void everyOtherPagerKeepsItsAnswer() {
        TabSwipe.mainPager(main);
        FrameLayout other = new FrameLayout(RuntimeEnvironment.getApplication());
        View otherList = new View(RuntimeEnvironment.getApplication());
        other.addView(otherList);
        assertTrue(TabSwipe.input(otherList, 1));
        assertFalse(TabSwipe.input(otherList, 0));
        View loose = new View(RuntimeEnvironment.getApplication());
        assertTrue(TabSwipe.input(loose, 1));
        assertTrue(TabSwipe.input(null, 1));
    }

    @Test
    public void beforeTheMainTabsAreSetUpEveryListKeepsItsAnswer() {
        assertTrue(TabSwipe.input(mainList, 1));
    }

    @Test
    public void offToStartAndOffLeaveTheSwipe() {
        Settings.STOP_TAB_SWIPING.resetToDefault();
        assertFalse(Settings.STOP_TAB_SWIPING.get());
        TabSwipe.mainPager(main);
        assertTrue(TabSwipe.input(mainList, 1));
        Settings.STOP_TAB_SWIPING.save(false);
        assertTrue(TabSwipe.input(mainList, 1));
        Settings.STOP_TAB_SWIPING.save(true);
        assertFalse("read at each touch", TabSwipe.input(mainList, 1));
    }

    @Test
    public void pausedAndUnreadyLeaveTheSwipe() {
        TabSwipe.mainPager(main);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(TabSwipe.input(mainList, 1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(TabSwipe.input(mainList, 1)));

        assertFalse(TabSwipe.input(mainList, 1));
    }

    @Test
    public void aThrowingSwitchLeavesTheSwipeAndIsReported() {
        TabSwipe.mainPager(main);
        assertTrue(TabSwipe.input(mainList, 1, THROWS));

        String missing = HookStatus.missing(FamilyNames.TAB_SWIPE).toString();
        assertTrue(missing, missing.contains("'" + TabSwipe.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
