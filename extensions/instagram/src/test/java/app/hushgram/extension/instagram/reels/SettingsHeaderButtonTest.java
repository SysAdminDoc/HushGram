/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ContextThemeWrapper;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.settings.GhostHeaderButton;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/** The gear on Home's header (#404): when it goes in, what its tap asks for, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class SettingsHeaderButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> screen;
    private Activity activity;
    private MainFeedActionBar header;
    private LinearLayout end;
    private ImageView heart;
    private final List<Activity> asked = new ArrayList<>();

    @Before public void prepare() {
        HookStatus.clear();
        screen = Robolectric.buildActivity(Activity.class).setup();
        activity = screen.get();
        header = new MainFeedActionBar(new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault));
        end = new LinearLayout(header.getContext());
        heart = new ImageView(header.getContext());
        heart.setPaddingRelative(6, 8, 6, 8);
        end.addView(heart);
        header.addView(end);
        header.setDirectInbox(null);
        HomeHeader.standInEndRowForTests(on -> on == header ? end : null);
        SettingsHeaderButton.openForTests = on -> {
            asked.add(on);
            return true;
        };
    }

    @After public void restore() {
        SettingsHeaderButton.openForTests = null;
        HomeHeader.standInEndRowForTests(null);
        Settings.SETTINGS_BUTTON_ON_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private ImageView placed() {
        Settings.SETTINGS_BUTTON_ON_HOME.save(true);
        SettingsHeaderButton.place(header);
        ImageView button = SettingsHeaderButton.in(end);
        assertNotNull(button);
        return button;
    }

    @Test public void theSwitchStartsOffAndAddsNothing() {
        assertEquals(Boolean.FALSE, Settings.SETTINGS_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.SETTINGS_BUTTON_ON_HOME.rebootApp);
        SettingsHeaderButton.place(header);
        assertNull(SettingsHeaderButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void withTheSwitchOnTheGearGoesLastInTheEndRowOnce() {
        ImageView button = placed();
        assertEquals(1, end.indexOfChild(button));
        assertEquals("the heart's padding", 6, button.getPaddingStart());
        assertEquals("HushGram settings", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        assertTrue(button.getDrawable() instanceof SettingsHeaderButton.GearIcon);
        SettingsHeaderButton.place(header);
        assertSame(button, SettingsHeaderButton.in(end));
        assertEquals(2, end.getChildCount());
        assertTrue(report(), report().contains(SettingsHeaderButton.PLACED + " 1"));
    }

    @Test public void switchOffOrPausedTakesItOutAtTheNextDraw() {
        placed();
        Settings.SETTINGS_BUTTON_ON_HOME.save(false);
        SettingsHeaderButton.place(header);
        assertNull(SettingsHeaderButton.in(end));

        placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SettingsHeaderButton.place(header);
        assertNull(SettingsHeaderButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void noEndRowIsCountedAndNullHeadersAreIgnored() {
        Settings.SETTINGS_BUTTON_ON_HOME.save(true);
        HomeHeader.standInEndRowForTests(on -> null);
        SettingsHeaderButton.place(header);
        SettingsHeaderButton.place(null);
        assertNull(SettingsHeaderButton.in(end));
        assertTrue(report(), report().contains(SettingsHeaderButton.NO_ROW + " 1"));
    }

    @Test public void aTapAsksForTheSettingsOverTheScreenInFront() {
        placed().performClick();
        assertEquals(1, asked.size());
        assertSame(activity, asked.get(0));
        assertTrue(report(), report().contains(SettingsHeaderButton.OPENED + " 1"));
    }

    @Test public void aRefusedRequestIsCountedAndAPausedTapDoesNothing() {
        ImageView button = placed();
        SettingsHeaderButton.openForTests = on -> false;
        button.performClick();
        assertTrue(report(), report().contains(SettingsHeaderButton.NO_HOST + " 1"));

        SettingsHeaderButton.openForTests = on -> {
            asked.add(on);
            return true;
        };
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        button.performClick();
        assertTrue(asked.isEmpty());
    }

    @Test public void theHeaderHookPlacesItOnceTheMainLooperRuns() {
        Settings.SETTINGS_BUTTON_ON_HOME.save(true);
        activity.setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        assertNotNull(SettingsHeaderButton.in(end));
        assertSame(heart, end.getChildAt(0));
    }
}
