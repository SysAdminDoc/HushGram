/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

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

/** The menu button on Home's header (#437): when it goes in, what its list holds and what each pick does. */
@RunWith(RobolectricTestRunner.class)
public class HomeMenuButtonTest {
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
        Settings.MENU_BUTTON_ON_HOME.resetToDefault();
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.HIDE_TYPING.resetToDefault();
        Settings.GHOST_MODE_PICKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private ImageView placed() {
        Settings.MENU_BUTTON_ON_HOME.save(true);
        HomeMenuButton.place(header);
        ImageView button = HomeMenuButton.in(end);
        assertNotNull(button);
        return button;
    }

    @Test public void theSwitchStartsOffAndAddsNothing() {
        assertEquals(Boolean.FALSE, Settings.MENU_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.MENU_BUTTON_ON_HOME.rebootApp);
        HomeMenuButton.place(header);
        assertNull(HomeMenuButton.in(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void withTheSwitchOnTheButtonGoesLastOnceAndTakesTheHeartsPadding() {
        ImageView button = placed();
        assertEquals(1, end.indexOfChild(button));
        assertEquals(6, button.getPaddingStart());
        assertEquals("HushGram menu", String.valueOf(button.getContentDescription()));
        assertTrue(button.isClickable());
        assertTrue(button.getDrawable() instanceof HomeMenuButton.Lines);
        HomeMenuButton.place(header);
        assertSame(button, HomeMenuButton.in(end));
        assertEquals(2, end.getChildCount());
    }

    @Test public void switchOffOrPausedTakesItOut() {
        placed();
        Settings.MENU_BUTTON_ON_HOME.save(false);
        HomeMenuButton.place(header);
        assertNull(HomeMenuButton.in(end));

        placed();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HomeMenuButton.place(header);
        assertNull(HomeMenuButton.in(end));
    }

    @Test public void theListHoldsHistoryGhostModeAndSettingsWithGhostModeChecked() {
        PopupMenu popup = new PopupMenu(activity, new ImageView(activity));
        HomeMenuButton.fill(popup.getMenu());
        Menu menu = popup.getMenu();
        assertEquals(3, menu.size());
        assertEquals("Viewing history", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals("Ghost mode", String.valueOf(menu.getItem(1).getTitle()));
        assertTrue(menu.getItem(1).isCheckable());
        assertFalse(menu.getItem(1).isChecked());
        assertEquals("HushGram settings", String.valueOf(menu.getItem(2).getTitle()));

        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        Settings.HIDE_TYPING.save(true);
        PopupMenu again = new PopupMenu(activity, new ImageView(activity));
        HomeMenuButton.fill(again.getMenu());
        assertTrue("on while the ghost switches are", again.getMenu().getItem(1).isChecked());
    }

    @Test public void aBuildWithNoGhostPatchLeavesGhostModeOffTheList() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        PopupMenu popup = new PopupMenu(activity, new ImageView(activity));
        HomeMenuButton.fill(popup.getMenu());
        assertEquals(2, popup.getMenu().size());
    }

    @Test public void pickingGhostModeFlipsItAndHistoryOpensItsPage() {
        PopupMenu popup = new PopupMenu(activity, new ImageView(activity));
        HomeMenuButton.fill(popup.getMenu());
        assertTrue(HomeMenuButton.pick(popup.getMenu().findItem(HomeMenuButton.GHOST), activity));
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertTrue(HomeMenuButton.pick(popup.getMenu().findItem(HomeMenuButton.GHOST), activity));
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());

        assertNull(ShadowDialog.getLatestDialog());
        assertTrue(HomeMenuButton.pick(popup.getMenu().findItem(HomeMenuButton.HISTORY), activity));
        ShadowLooper.idleMainLooper();
        assertNotNull("the viewing history page", ShadowDialog.getLatestDialog());
    }

    @Test public void aPausedPickDoesNothing() {
        PopupMenu popup = new PopupMenu(activity, new ImageView(activity));
        HomeMenuButton.fill(popup.getMenu());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HomeMenuButton.pick(popup.getMenu().findItem(HomeMenuButton.GHOST), activity);
        HomeMenuButton.pick(popup.getMenu().findItem(HomeMenuButton.HISTORY), activity);
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test public void theHeaderHookPlacesItOnceTheMainLooperRuns() {
        Settings.MENU_BUTTON_ON_HOME.save(true);
        activity.setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        assertNotNull(HomeMenuButton.in(end));
    }
}
