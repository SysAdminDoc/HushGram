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

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ViewAnimator;

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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.GhostHeaderButton;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;

/**
 * Centering Instagram's logo on Home's header: when it happens, what it changes, and what it puts
 * back. The stand-in header lays out as Instagram's does, a row of buttons on each side of the title
 * view in one LinearLayout, here with a wider margin on the start side than on the end side.
 */
@RunWith(RobolectricTestRunner.class)
public class HomeLogoTest {
    private static final int WIDTH = 1080;
    private static final int START_MARGIN = 48;
    private static final int END_MARGIN = 12;
    private static final int BUTTON = 100;
    private static final int LOGO = 200;
    private static final String COUNTED = FamilyNames.REELS_TAB + ": invoked 0, 0 found, 0 missing. Counted: ";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's title view, a ViewAnimator that keeps its logo's row in a field. */
    static final class Title extends ViewAnimator {
        LinearLayout logo;

        Title(Context context) {
            super(context);
        }
    }

    private Context context;
    private MainFeedActionBar header;
    private LinearLayout start;
    private LinearLayout end;
    private Title title;
    private LinearLayout logo;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        header = new MainFeedActionBar(context);
        LinearLayout buttons = new LinearLayout(context);
        FrameLayout.LayoutParams around = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BUTTON);
        around.leftMargin = START_MARGIN;
        around.rightMargin = END_MARGIN;
        header.addView(buttons, around);
        start = new LinearLayout(context);
        end = new LinearLayout(context);
        end.addView(button());
        end.addView(button());
        title = new Title(context);
        logo = new LinearLayout(context);
        logo.addView(new View(context), new LinearLayout.LayoutParams(LOGO, 50));
        title.logo = logo;
        title.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));
        buttons.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON));
        buttons.addView(title, new LinearLayout.LayoutParams(0, BUTTON, 1f));
        buttons.addView(end, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, BUTTON));
        HomeHeader.standInStartRowForTests(asked -> asked == header ? start : null);
        HomeHeader.standInEndRowForTests(asked -> asked == header ? end : null);
        HomeHeader.standInTitleForTests(asked -> asked == header ? title : null);
        HookStatus.clear();
    }

    @After public void restore() {
        HomeHeader.standInStartRowForTests(null);
        HomeHeader.standInEndRowForTests(null);
        HomeHeader.standInTitleForTests(null);
        Settings.CENTER_HOME_LOGO.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        Settings.SEARCH_BUTTON_ON_HOME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private View button() {
        View button = new View(context);
        button.setLayoutParams(new LinearLayout.LayoutParams(BUTTON, BUTTON));
        return button;
    }

    /**
     * Measures and lays the header out at full width a few times over, the way a layout that asks
     * for another one gets its second pass on a phone.
     */
    private void lay() {
        for (int pass = 0; pass < 3; pass++) {
            forceAll(header);
            header.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
            header.layout(0, 0, WIDTH, header.getMeasuredHeight());
        }
    }

    private static void forceAll(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) forceAll(group.getChildAt(i));
        }
    }

    /** Where the middle of [view] is across the header. */
    private int middleOf(View view) {
        int left = 0;
        for (View at = view; at != header; at = (View) at.getParent()) left += at.getLeft();
        return left + view.getWidth() / 2;
    }

    private int gravityOf(View view) {
        return ((FrameLayout.LayoutParams) view.getLayoutParams()).gravity;
    }

    @Test public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals(Boolean.FALSE, Settings.CENTER_HOME_LOGO.defaultValue);
        assertTrue(Settings.CENTER_HOME_LOGO.rebootApp);
        assertFalse(HomeLogo.wanted());
        Settings.CENTER_HOME_LOGO.save(true);
        assertTrue(HomeLogo.wanted());
    }

    /** Off, the header keeps Instagram's layout: the logo at the start of the title view, nothing widened. */
    @Test public void offTheHeaderIsLaidOutAsInstagramHasIt() {
        lay();
        int before = middleOf(logo);
        HomeLogo.center(header);
        lay();
        assertEquals("the logo right after the start margin", START_MARGIN + LOGO / 2, before);
        assertEquals(before, middleOf(logo));
        assertEquals(0, start.getMinimumWidth());
        assertEquals(0, end.getMinimumWidth());
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
        assertEquals(List.of(), HookStatus.report());
    }

    /**
     * On, the rows get room so the title view is in the middle of the header, uneven margins
     * included, and the logo is centered in it, keeping its vertical gravity.
     */
    @Test public void onTheLogoSitsInTheMiddleOfTheHeader() {
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(WIDTH / 2, middleOf(logo));
        assertEquals(WIDTH / 2, middleOf(title));
        // Room from each edge to the title view: the start margin and row against the end margin and row.
        assertEquals(2 * BUTTON + END_MARGIN - START_MARGIN, start.getMinimumWidth());
        assertEquals(2 * BUTTON, end.getMinimumWidth());
        assertEquals(Gravity.CENTER, gravityOf(logo));
        assertEquals(List.of(COUNTED + HomeLogo.CENTERED + " 1"), HookStatus.report());
    }

    /** Before the header's first layout the rows' edges aren't known yet; the layout check catches up. */
    @Test public void aHeaderNotLaidOutYetIsCenteredByItsFirstLayout() {
        Settings.CENTER_HOME_LOGO.save(true);
        HomeLogo.center(header);
        lay();
        assertEquals(WIDTH / 2, middleOf(logo));
    }

    /** With the header's buttons hidden, the logo stays in the middle. */
    @Test public void theLogoStaysInTheMiddleWithTheButtonsHidden() {
        end.removeAllViews();
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(WIDTH / 2, middleOf(logo));
        assertEquals(0, start.getMinimumWidth());
        assertEquals(START_MARGIN - END_MARGIN, end.getMinimumWidth());
    }

    /** A button that goes in or comes out after the draw is caught at the header's next layout. */
    @Test public void aButtonAddedOrTakenOutLaterIsCaughtAtTheNextLayout() {
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        end.addView(button());
        start.addView(button());
        lay();
        assertEquals(WIDTH / 2, middleOf(logo));
        assertEquals(3 * BUTTON + END_MARGIN - START_MARGIN, start.getMinimumWidth());
        start.removeAllViews();
        end.removeViewAt(0);
        lay();
        assertEquals(WIDTH / 2, middleOf(logo));
        assertEquals("counted once per draw, not per layout", List.of(COUNTED + HomeLogo.CENTERED + " 1"), HookStatus.report());
    }

    /** Paused, or before the settings are read, nothing is touched. */
    @Test public void pausedAndUnreadyLeaveTheHeaderAlone() {
        Settings.CENTER_HOME_LOGO.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(HomeLogo.wanted());
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(START_MARGIN + LOGO / 2, middleOf(logo));
        assertEquals(0, end.getMinimumWidth());
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse(HomeLogo.wanted());
            HomeLogo.center(header);
        });
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
        assertEquals(List.of(), HookStatus.report());
    }

    /**
     * Turned off, the next draw puts back the widths and gravity it set, but not a width something
     * else has set since, and the layout check stops.
     */
    @Test public void turnedOffItPutsBackOnlyWhatItSet() {
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        start.setMinimumWidth(77);
        Settings.CENTER_HOME_LOGO.save(false);
        HomeLogo.center(header);
        assertEquals("Instagram's own width stays", 77, start.getMinimumWidth());
        assertEquals(0, end.getMinimumWidth());
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        assertEquals("no layout check left behind", 0, end.getMinimumWidth());
        assertEquals(START_MARGIN + 77 + LOGO / 2, middleOf(logo));
    }

    /** Pause puts the header back at the next draw too. */
    @Test public void pausingPutsTheHeaderBack() {
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        lay();
        assertEquals("the layout check does nothing while paused", 2 * BUTTON, end.getMinimumWidth());
        HomeLogo.center(header);
        lay();
        assertEquals(0, start.getMinimumWidth());
        assertEquals(0, end.getMinimumWidth());
        assertEquals(START_MARGIN + LOGO / 2, middleOf(logo));
    }

    /**
     * The feed's name waits in a ViewStub until it first shows; centering the stub centers the name
     * it turns into, since the name takes over the stub's layout params.
     */
    @Test public void aFeedNameStillInItsStubIsCenteredWhenItShows() {
        ViewStub stub = new ViewStub(context, android.R.layout.simple_list_item_1);
        title.addView(stub, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM));
        Settings.CENTER_HOME_LOGO.save(true);
        HomeLogo.center(header);
        View name = stub.inflate();
        assertSame(title, name.getParent());
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, gravityOf(name));

        Settings.CENTER_HOME_LOGO.save(false);
        HomeLogo.center(header);
        assertEquals(Gravity.START | Gravity.BOTTOM, gravityOf(name));
    }

    /** Stands in for a title view whose logo row sits inside another view, as a field still reaches it. */
    static final class WrappedTitle extends FrameLayout {
        LinearLayout logo;

        WrappedTitle(Context context) {
            super(context);
        }
    }

    /** The logo's row is centered through the title view's field, even when it isn't the title's own child. */
    @Test public void theLogoRowTheTitleKeepsIsCenteredWhereverItSits() {
        WrappedTitle wrapped = new WrappedTitle(context);
        FrameLayout wrapper = new FrameLayout(context);
        logo.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title.removeView(logo);
        wrapper.addView(logo);
        wrapped.logo = logo;
        wrapped.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ViewGroup buttons = (ViewGroup) title.getParent();
        buttons.removeView(title);
        buttons.addView(wrapped, 1, new LinearLayout.LayoutParams(0, BUTTON, 1f));
        HomeHeader.standInTitleForTests(asked -> wrapped);

        Settings.CENTER_HOME_LOGO.save(true);
        HomeLogo.center(header);
        lay();
        assertEquals(Gravity.CENTER_HORIZONTAL, gravityOf(logo));
        assertEquals(WIDTH / 2, middleOf(logo));
    }

    @Test public void centeringKeepsTheVerticalPart() {
        assertEquals(Gravity.CENTER_HORIZONTAL, HomeLogo.centered(FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY));
        assertEquals(Gravity.CENTER, HomeLogo.centered(Gravity.START | Gravity.CENTER_VERTICAL));
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, HomeLogo.centered(Gravity.END | Gravity.BOTTOM));
        assertEquals(Gravity.CENTER_HORIZONTAL, HomeLogo.centered(Gravity.LEFT));
    }

    /** A header the stubs can't read is left alone, and that's counted. */
    @Test public void withoutRowsOrATitleNothingChangesAndThatsCounted() {
        Settings.CENTER_HOME_LOGO.save(true);
        HomeHeader.standInTitleForTests(asked -> null);
        HomeLogo.center(header);
        HomeLogo.center(null);
        lay();
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
        assertEquals(0, end.getMinimumWidth());
        assertEquals(List.of(COUNTED + HomeLogo.NOT_FOUND + " 1"), HookStatus.report());
    }

    /** Unpatched, the stubs answer nothing, so there's nothing to center. */
    @Test public void unpatchedTheStubsAnswerNothing() {
        assertNull(HomeHeader.startRow(header));
        assertNull(HomeHeader.title(header));
        HomeHeader.standInStartRowForTests(null);
        HomeHeader.standInTitleForTests(null);
        assertNull(HomeHeader.startRowOf(header));
        assertNull(HomeHeader.titleOf(header));
    }

    /**
     * Through the header's hook: the logo is centered after the Search button goes in, so the room
     * on each side counts that button too.
     */
    @Test public void theHeaderHookCentersAfterTheSearchButtonGoesIn() {
        Settings.CENTER_HOME_LOGO.save(true);
        Settings.SEARCH_BUTTON_ON_HOME.save(true);
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup();
        screen.get().setContentView(header);
        GhostHeaderButton.drew(header);
        ShadowLooper.idleMainLooper();
        lay();
        assertEquals("the Search button went in first", 3, end.getChildCount());
        assertEquals(Gravity.CENTER, gravityOf(logo));
        assertEquals(WIDTH / 2, middleOf(logo));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(HomeLogo.CENTERED + " 1"));
        screen.close();
    }

    /**
     * Instagram's newer header (#310, 452 on the S22): the + button on the start row, both rows given
     * the same minimum width and the logo centered in the title view.
     */
    private void drawTheNewerHeader() {
        start.addView(button());
        start.setMinimumWidth(2 * BUTTON);
        end.setMinimumWidth(2 * BUTTON);
        ((FrameLayout.LayoutParams) logo.getLayoutParams()).gravity = Gravity.CENTER;
    }

    @Test public void theStartSwitchStartsOffAndWinsOverCentering() {
        assertEquals(Boolean.FALSE, Settings.TITLES_AT_START.defaultValue);
        assertTrue(Settings.TITLES_AT_START.rebootApp);
        assertEquals(HomeLogo.AS_IS, HomeLogo.placement());
        Settings.TITLES_AT_START.save(true);
        assertEquals(HomeLogo.START, HomeLogo.placement());
        Settings.CENTER_HOME_LOGO.save(true);
        assertEquals(HomeLogo.START, HomeLogo.placement());
        assertFalse(HomeLogo.wanted());
        Settings.TITLES_AT_START.save(false);
        assertEquals(HomeLogo.MIDDLE, HomeLogo.placement());
    }

    /** At the start, the logo follows the start row's buttons, keeping its vertical gravity. */
    @Test public void atTheStartTheLogoFollowsTheStartRowsButtons() {
        drawTheNewerHeader();
        lay();
        assertEquals("Instagram centers it between the rows", (START_MARGIN + WIDTH - END_MARGIN) / 2, middleOf(logo));
        Settings.TITLES_AT_START.save(true);
        HomeLogo.center(header);
        lay();
        assertEquals(START_MARGIN + BUTTON + LOGO / 2, middleOf(logo));
        assertEquals(0, start.getMinimumWidth());
        assertEquals(0, end.getMinimumWidth());
        assertEquals(Gravity.CENTER_VERTICAL | Gravity.START, gravityOf(logo));
        assertEquals(List.of(COUNTED + HomeLogo.AT_START + " 1"), HookStatus.report());
    }

    /** It wins over the centering switch, and a width Instagram sets again is cleared at the next layout. */
    @Test public void atTheStartWinsOverCenteringAndKeepsTheRowsCleared() {
        drawTheNewerHeader();
        Settings.CENTER_HOME_LOGO.save(true);
        Settings.TITLES_AT_START.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(START_MARGIN + BUTTON + LOGO / 2, middleOf(logo));
        start.setMinimumWidth(2 * BUTTON);
        end.setMinimumWidth(2 * BUTTON);
        lay();
        assertEquals(0, start.getMinimumWidth());
        assertEquals(0, end.getMinimumWidth());
        assertEquals(START_MARGIN + BUTTON + LOGO / 2, middleOf(logo));
    }

    /**
     * With Create hidden the start row has no buttons, and the logo keeps the room the heart's
     * content has from the other edge rather than touching the screen's edge (#407). A button put
     * back in the row clears that room again.
     */
    @Test public void atTheStartAnEmptyStartRowKeepsTheEndButtonsRoom() {
        View heart = end.getChildAt(1);
        heart.setPaddingRelative(0, 0, 60, 0);
        Settings.TITLES_AT_START.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals("12 margin and 60 padding past the heart, less the 48 margin the start has", 24, start.getMinimumWidth());
        assertEquals(START_MARGIN + 24 + LOGO / 2, middleOf(logo));
        start.addView(button());
        HomeLogo.center(header);
        lay();
        assertEquals(0, start.getMinimumWidth());
        assertEquals(START_MARGIN + BUTTON + LOGO / 2, middleOf(logo));
    }

    /**
     * Instagram's heart has no padding: it's a frame wider than its icon that centers it, so the
     * room to copy is what's past the icon (#407).
     */
    @Test public void atTheStartAHeartThatCentersItsIconLendsTheRoomPastIt() {
        ((FrameLayout.LayoutParams) ((View) start.getParent()).getLayoutParams()).leftMargin = 0;
        end.removeViewAt(1);
        FrameLayout heart = new FrameLayout(context);
        heart.addView(new View(context), new FrameLayout.LayoutParams(40, 40, Gravity.CENTER));
        end.addView(heart, new LinearLayout.LayoutParams(BUTTON, BUTTON));
        Settings.TITLES_AT_START.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(30, HomeLogo.contentEndInset(heart));
        assertEquals("12 margin and the 30 past the icon", END_MARGIN + 30, start.getMinimumWidth());
        assertEquals(END_MARGIN + 30 + LOGO / 2, middleOf(logo));
    }

    /** With no start margin and no end buttons either, the logo still keeps 16dp from the edge. */
    @Test public void atTheStartWithNothingToCopyTheLogoKeeps16dp() {
        ((FrameLayout.LayoutParams) ((View) start.getParent()).getLayoutParams()).leftMargin = 0;
        end.removeAllViews();
        Settings.TITLES_AT_START.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        int room = Math.round(HomeLogo.EDGE_DP * context.getResources().getDisplayMetrics().density);
        assertEquals(room, start.getMinimumWidth());
        assertEquals(room + LOGO / 2, middleOf(logo));
    }

    /** Turned off, Instagram's widths and centered logo come back at the next draw. */
    @Test public void turnedOffTheStartPutsBackInstagramsHeader() {
        drawTheNewerHeader();
        Settings.TITLES_AT_START.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        Settings.TITLES_AT_START.save(false);
        HomeLogo.center(header);
        lay();
        assertEquals(2 * BUTTON, start.getMinimumWidth());
        assertEquals(2 * BUTTON, end.getMinimumWidth());
        assertEquals(Gravity.CENTER, gravityOf(logo));
        assertEquals((START_MARGIN + WIDTH - END_MARGIN) / 2, middleOf(logo));
    }

    /** Going from the middle to the start and back off still puts back the gravity Instagram gave. */
    @Test public void fromTheMiddleToTheStartAndOffInstagramsGravityComesBack() {
        Settings.CENTER_HOME_LOGO.save(true);
        lay();
        HomeLogo.center(header);
        lay();
        assertEquals(Gravity.CENTER, gravityOf(logo));
        Settings.TITLES_AT_START.save(true);
        HomeLogo.center(header);
        lay();
        assertEquals(Gravity.CENTER_VERTICAL | Gravity.START, gravityOf(logo));
        assertEquals(START_MARGIN + LOGO / 2, middleOf(logo));
        Settings.TITLES_AT_START.save(false);
        Settings.CENTER_HOME_LOGO.save(false);
        HomeLogo.center(header);
        assertEquals(Gravity.CENTER_VERTICAL, gravityOf(logo));
    }

    @Test public void theStartKeepsTheVerticalPart() {
        assertEquals(Gravity.START, HomeLogo.started(FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY));
        assertEquals(Gravity.CENTER_VERTICAL | Gravity.START, HomeLogo.started(Gravity.CENTER));
        assertEquals(Gravity.BOTTOM | Gravity.START, HomeLogo.started(Gravity.END | Gravity.BOTTOM));
    }
}
