/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ContextThemeWrapper;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.EnumSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/** The clock on Home's header that opens the viewing history (#228): where it goes and what a tap does. */
@RunWith(RobolectricTestRunner.class)
public class HistoryHeaderButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> screen;
    private Activity activity;
    private MainFeedActionBar header;
    private LinearLayout end;

    @Before public void prepare() {
        HookStatus.clear();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.THREAD_SEEN, PatchFamily.TYPING);
        screen = Robolectric.buildActivity(Activity.class).setup();
        activity = screen.get();
        header = new MainFeedActionBar(new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault));
        end = new LinearLayout(header.getContext());
        ImageView heart = new ImageView(header.getContext());
        heart.setPaddingRelative(6, 8, 6, 8);
        end.addView(heart);
        header.addView(end);
        header.setDirectInbox(null);
        HomeHeader.standInEndRowForTests(on -> on == header ? end : null);
    }

    @After public void restore() {
        HomeHeader.standInEndRowForTests(null);
        PatchFamily.inBuildForTests = null;
        Settings.HISTORY_BUTTON_ON_HOME.resetToDefault();
        Settings.MENU_BUTTON_ON_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private ImageView placed() {
        Settings.HISTORY_BUTTON_ON_HOME.save(true);
        HistoryHeaderButton.place(header);
        ImageView button = HistoryHeaderButton.in(end);
        assertNotNull(button);
        return button;
    }

    @Test public void theSwitchStartsOffTakesARestartAndAddsNothing() {
        assertEquals(Boolean.FALSE, Settings.HISTORY_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.HISTORY_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.HISTORY_BUTTON_ON_HOME));
        HistoryHeaderButton.place(header);
        assertNull(HistoryHeaderButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void withTheSwitchOnTheButtonGoesInOnceAndTakesTheHeartsPadding() {
        ImageView button = placed();
        assertEquals(1, end.indexOfChild(button));
        assertEquals(6, button.getPaddingStart());
        assertEquals("Viewing history", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        assertTrue(button.getDrawable() instanceof HistoryHeaderButton.Face);
        HistoryHeaderButton.place(header);
        assertSame(button, HistoryHeaderButton.in(end));
        assertEquals(2, end.getChildCount());
    }

    @Test public void itGoesRightBeforeTheMenuButtonSoTheMenuStaysLast() {
        Settings.MENU_BUTTON_ON_HOME.save(true);
        HomeMenuButton.place(header);
        ImageView history = placed();
        assertEquals(1, end.indexOfChild(history));
        assertEquals(2, end.indexOfChild(HomeMenuButton.in(end)));
    }

    @Test public void switchOffOrPausedTakesItOut() {
        placed();
        Settings.HISTORY_BUTTON_ON_HOME.save(false);
        HistoryHeaderButton.place(header);
        assertNull(HistoryHeaderButton.in(end));

        placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HistoryHeaderButton.place(header);
        assertNull(HistoryHeaderButton.in(end));
    }

    @Test public void aTapOpensTheViewingHistoryPage() {
        activity.setContentView(header);
        ImageView button = placed();
        assertNull(ShadowDialog.getLatestDialog());
        assertTrue(button.performClick());
        ShadowLooper.idleMainLooper();
        assertNotNull("the viewing history page", ShadowDialog.getLatestDialog());
    }

    @Test public void aPausedTapDoesNothing() {
        activity.setContentView(header);
        ImageView button = placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        button.performClick();
        ShadowLooper.idleMainLooper();
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test public void theHeaderHookPlacesItOnceTheMainLooperRuns() {
        Settings.HISTORY_BUTTON_ON_HOME.save(true);
        activity.setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        assertNotNull(HistoryHeaderButton.in(end));
    }
}
