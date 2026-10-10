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
import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/** The Ghost mode button on Home's header: where it goes, what its tap does, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class GhostHeaderButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Context context;
    private MainFeedActionBar header;
    private LinearLayout row;
    private ImageView inbox;
    private View heart;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.THREAD_SEEN, PatchFamily.TYPING);
        header = new MainFeedActionBar(context);
        row = new LinearLayout(context);
        heart = new View(context);
        inbox = new ImageView(context);
        inbox.setPaddingRelative(7, 9, 7, 9);
        row.addView(heart);
        row.addView(inbox);
        header.addView(row);
        header.setDirectInbox(inbox);
        HookStatus.clear();
    }

    @After public void restore() {
        HomeHeader.standInEndRowForTests(null);
        PatchFamily.inBuildForTests = null;
        Settings.GHOST_BUTTON_ON_HOME.resetToDefault();
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.HIDE_TYPING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private ImageView ghost() {
        return ghostIn(row);
    }

    private static ImageView ghostIn(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof GhostHeaderButton.Ghost) {
                return (ImageView) child;
            }
        }
        return null;
    }

    @Test public void theSwitchStartsOffAndAddsNothingToTheHeader() {
        assertEquals(Boolean.FALSE, Settings.GHOST_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.GHOST_BUTTON_ON_HOME.rebootApp);
        GhostHeaderButton.place(header);
        assertNull(ghost());
        assertEquals(2, row.getChildCount());
    }

    @Test public void withTheSwitchOnTheButtonSitsBesideMessagesAndIsNotAddedTwice() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        ImageView button = ghost();
        assertNotNull(button);
        assertEquals("right before Messages", row.indexOfChild(inbox) - 1, row.indexOfChild(button));
        assertEquals(3, row.getChildCount());
        assertEquals("Messages' padding", 7, button.getPaddingStart());
        assertEquals("Messages' padding", 9, button.getPaddingTop());
        assertEquals("Ghost mode is off. Tap to turn it on.", String.valueOf(button.getContentDescription()));
        GhostHeaderButton.place(header);
        assertSame(button, ghost());
        assertEquals(3, row.getChildCount());
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: "
                + GhostHeaderButton.PLACED + " 1"), HookStatus.report());
    }

    @Test public void aTapTurnsEveryGhostSwitchOnAndOffAndTheIconFollows() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        ImageView button = ghost();
        GhostHeaderButton.Ghost icon = (GhostHeaderButton.Ghost) button.getDrawable();
        assertFalse(icon.isOn());

        button.performClick();
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertTrue(icon.isOn());
        assertEquals("Ghost mode is on. Tap to turn it off.", String.valueOf(button.getContentDescription()));

        button.performClick();
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertFalse(Settings.HIDE_TYPING.savedValue());
        assertFalse(icon.isOn());
        assertEquals("Ghost mode is off. Tap to turn it on.", String.valueOf(button.getContentDescription()));
    }

    @Test public void theIconShowsGhostModeTurnedOnElsewhere() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        GhostHeaderButton.Ghost icon = (GhostHeaderButton.Ghost) ghost().getDrawable();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        assertFalse("one of two is not Ghost mode", icon.isOn());
        Settings.HIDE_TYPING.save(true);
        assertTrue(icon.isOn());
    }

    @Test public void pauseTakesTheButtonOutAndStopsATap() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        ImageView button = ghost();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        button.performClick();
        assertFalse("a paused tap leaves Ghost mode alone", Settings.HIDE_TYPING.savedValue());
        GhostHeaderButton.place(header);
        assertNull(ghost());
        assertEquals(2, row.getChildCount());
    }

    @Test public void turningTheSwitchOffTakesTheButtonOutAtTheNextDraw() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        assertNotNull(ghost());
        Settings.GHOST_BUTTON_ON_HOME.save(false);
        GhostHeaderButton.place(header);
        assertNull(ghost());
    }

    @Test public void withNoGhostPatchInTheBuildThereIsNoButton() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(header);
        assertNull(ghost());
    }

    @Test public void aHeaderWithoutAMessagesButtonIsLeftAloneAndCounted() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        header.setDirectInbox(null);
        GhostHeaderButton.place(header);
        assertNull(ghost());
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: "
                + GhostHeaderButton.NO_ANCHOR + " 1"), HookStatus.report());

        header.setDirectInbox(new ImageView(context));
        GhostHeaderButton.place(header);
        assertNull("a Messages button with no parent", ghost());
    }

    /** Messages is on the tab bar for some accounts on 450, so the header has no Messages button to sit beside. */
    @Test public void withoutMessagesOnTheHeaderTheButtonGoesAtTheEndOfTheEndRow() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        LinearLayout end = new LinearLayout(context);
        ImageView bell = new ImageView(context);
        bell.setPaddingRelative(5, 6, 5, 6);
        end.addView(bell);
        header.addView(end);
        header.setDirectInbox(null);
        HomeHeader.standInEndRowForTests(asked -> asked == header ? end : null);

        GhostHeaderButton.place(header);
        ImageView button = ghostIn(end);
        assertNotNull(button);
        assertNull("nothing goes in Messages' old row", ghost());
        assertEquals("at the end of the row", 1, end.indexOfChild(button));
        assertEquals("a neighbor's padding", 5, button.getPaddingStart());
        assertEquals("a neighbor's padding", 6, button.getPaddingTop());
        GhostHeaderButton.place(header);
        assertSame(button, ghostIn(end));
        assertEquals(2, end.getChildCount());
        assertEquals(List.of(FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: "
                + GhostHeaderButton.PLACED_AT_END + " 1"), HookStatus.report());

        Settings.GHOST_BUTTON_ON_HOME.save(false);
        GhostHeaderButton.place(header);
        assertNull(ghostIn(end));
        assertEquals(1, end.getChildCount());
    }

    @Test public void anEmptyEndRowGetsTheButtonWithItsOwnPadding() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        LinearLayout end = new LinearLayout(context);
        header.addView(end);
        header.setDirectInbox(null);
        HomeHeader.standInEndRowForTests(asked -> end);

        GhostHeaderButton.place(header);
        ImageView button = ghostIn(end);
        assertNotNull(button);
        int pad = Math.round(10 * context.getResources().getDisplayMetrics().density);
        assertEquals(pad, button.getPaddingStart());
        assertEquals(pad, button.getPaddingBottom());
    }

    @Test public void withMessagesOnTheHeaderTheButtonStaysBesideItOverTheEndRow() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        LinearLayout end = new LinearLayout(context);
        header.addView(end);
        HomeHeader.standInEndRowForTests(asked -> end);

        GhostHeaderButton.place(header);
        assertNull(ghostIn(end));
        assertEquals("right before Messages", row.indexOfChild(inbox) - 1, row.indexOfChild(ghost()));
    }

    @Test public void aViewWithNoMessagesGetterIsLeftAlone() {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        GhostHeaderButton.place(new View(context));
        assertNull(ghost());
    }

    @Test public void theHookPlacesTheButtonOnceTheMainLooperRuns() throws Exception {
        Settings.GHOST_BUTTON_ON_HOME.save(true);
        // A view posts behind its own attach, so the header goes on a window as it is on Home.
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup();
        screen.get().setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        assertNotNull(ghost());
        GhostHeaderButton.drew(null);
        screen.close();
    }
}
