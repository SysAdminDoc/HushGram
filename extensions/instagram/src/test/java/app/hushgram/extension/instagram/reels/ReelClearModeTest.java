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
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Keep clear mode on: clear mode you turn on for a reel is handed to each next reel through
 * Instagram's own turn-on, once per reel, until you leave it yourself. A swipe that snaps back keeps
 * it on its reel. A viewer that closes, a page with no exit button and anything that fails end it.
 * Off, paused or unready, every reel is as Instagram has it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelClearModeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The reasons Instagram's handler is given, named as Instagram 450 names them. */
    private enum Reason { EXIT_BUTTON, OVERFLOW_MENU, REELS_VIEWER_SWIPE_OR_EXIT }

    /** The pager's scroll states, named as Instagram 452 names them. */
    private enum Scroll { IDLE, DRAGGING, SETTLING }

    private static final class Reel {
        final String id;

        Reel(String id) {
            this.id = id;
        }
    }

    /**
     * Stands in for Instagram's Reels viewer: the reel its pager is on, and a turn-on that does what
     * the patched handler does. Its action passes the pager's reel through the hook and acts on it
     * unless it comes back null, and the handler's own hook runs after.
     */
    private static final class FakeViewer implements ReelClearMode.Viewer {
        final Object handler = new Object();
        final List<String> turnedOn = new ArrayList<>();
        final List<Runnable> posted = new ArrayList<>();
        /** The Reels pager, on screen while {@link #pagerShown}. */
        final Object pager = new Object();
        boolean pagerShown = true;
        RuntimeException showingFailure;
        Reel page;
        long now = 10_000;
        int asked;
        RuntimeException enterFailure;
        /** A reel whose id can't be read. */
        Reel unreadable;

        @Override
        public void enter(Object handler, Object source) {
            asked++;
            if (enterFailure != null) throw enterFailure;
            turnOn(handler, source);
        }

        void turnOn(Object handler, Object source) {
            Object reel = ReelClearMode.current(page);
            if (reel != null) turnedOn.add(((Reel) reel).id + " " + source);
            ReelClearMode.entered(handler, source);
        }

        @Override
        public String id(Object item) {
            if (item == unreadable) throw new IllegalStateException("the reel changed");
            return ((Reel) item).id;
        }

        @Override
        public void post(Runnable work) {
            posted.add(work);
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public boolean showing(Object pager) {
            if (showingFailure != null) throw showingFailure;
            return pager == this.pager && pagerShown;
        }

        void runPosted() {
            List<Runnable> work = new ArrayList<>(posted);
            posted.clear();
            for (Runnable each : work) each.run();
        }
    }

    private FakeViewer viewer;

    @Before
    public void start() {
        ReelClearMode.forget();
        viewer = new FakeViewer();
        ReelClearMode.access = viewer;
        Settings.KEEP_REEL_CLEAR_MODE.save(true);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_REEL_CLEAR_MODE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ReelClearMode.forget();
        HookStatus.clear();
    }

    /** A reel whose exit button Instagram built, as it does for every reel it draws. */
    private static Reel reel(String id) {
        Reel reel = new Reel(id);
        ReelClearMode.built(reel);
        return reel;
    }

    /** You turn clear mode on for the reel on screen, from the menu. */
    private void tapClearMode() {
        viewer.turnOn(viewer.handler, Reason.OVERFLOW_MENU);
    }

    /** A swipe that lands on [next], after which [old]'s exit button leaves the screen. */
    private void swipe(Reel old, Reel next) {
        viewer.page = next;
        ReelClearMode.paged();
        viewer.runPosted();
        ReelClearMode.left(viewer.handler, old, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
    }

    private static boolean counted(int times) {
        return HookStatus.report().toString().contains(ReelClearMode.CARRIED + " " + times);
    }

    private static boolean kept(int times) {
        return HookStatus.report().toString().contains(ReelClearMode.KEPT + " " + times);
    }

    /** A swipe from [reel] that takes its exit button off the screen and snaps back without landing. */
    private void snapBack(Reel reel) {
        viewer.page = reel;
        ReelClearMode.settled(viewer.pager, Scroll.DRAGGING);
        ReelClearMode.left(viewer.handler, reel, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        ReelClearMode.settled(viewer.pager, Scroll.SETTLING);
        viewer.now += 300;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
    }

    /** Whether the hook named [hook] was reported as having thrown. */
    private static boolean reported(String hook) {
        return HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString().contains("'" + hook + "' hook (it threw");
    }

    @Test
    public void clearModeYouTurnOnCarriesToEveryNextReel() {
        assertFalse("starts off", Settings.KEEP_REEL_CLEAR_MODE.defaultValue);
        Reel first = reel("reel 0");
        viewer.page = first;
        tapClearMode();
        assertTrue(ReelClearMode.wanted());
        assertEquals("your own turn-on is Instagram's alone", 0, viewer.asked);

        Reel old = first;
        List<String> expected = new ArrayList<>(Collections.singletonList("reel 0 OVERFLOW_MENU"));
        for (int i = 1; i <= 10; i++) {
            Reel next = reel("reel " + i);
            swipe(old, next);
            expected.add("reel " + i + " OVERFLOW_MENU");
            old = next;
        }

        assertEquals("each next reel is turned on with the reason your own tap gave", expected, viewer.turnedOn);
        assertEquals("Instagram is asked once for each reel", 10, viewer.asked);
        assertTrue(HookStatus.report().toString(), counted(10));
        assertTrue(ReelClearMode.wanted());
        assertEquals("reels started in clear mode", ReelClearMode.CARRIED);
    }

    /** The exit button and the menu are your own way out: the next reels show their buttons, however many follow. */
    @Test
    public void leavingClearModeYourselfEndsItForTheNextReels() {
        for (Reason way : new Reason[]{Reason.EXIT_BUTTON, Reason.OVERFLOW_MENU}) {
            ReelClearMode.forget();
            ReelClearMode.access = viewer;
            viewer.turnedOn.clear();
            viewer.asked = 0;
            Reel first = reel(way + " 1");
            Reel second = reel(way + " 2");
            Reel third = reel(way + " 3");
            viewer.page = first;
            tapClearMode();
            swipe(first, second);
            assertEquals(way.name(), 1, viewer.asked);

            ReelClearMode.left(viewer.handler, second, way);
            assertFalse(way.name(), ReelClearMode.wanted());
            // Instagram reports the exit button gone as well, once it stops drawing it.
            ReelClearMode.left(viewer.handler, second, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
            swipe(second, third);

            assertEquals(way + ": nothing was asked for after you left", 1, viewer.asked);
            assertEquals(Arrays.asList(way + " 1 OVERFLOW_MENU", way + " 2 OVERFLOW_MENU"), viewer.turnedOn);
            assertFalse(ReelClearMode.wanted());
        }
    }

    /**
     * A reel's exit button can leave the screen before the swipe lands. The pager still names that
     * reel then, so the turn-on is held back and tried once more on the pager's next page.
     */
    @Test
    public void aSwipeThatHasntLandedWaitsForThePagersNextPageOnce() {
        Reel first = reel("first");
        Reel second = reel("second");
        viewer.page = first;
        tapClearMode();

        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals("asked once, and held back", 1, viewer.asked);
        assertEquals("the reel that just left clear mode isn't put back in it", 1, viewer.turnedOn.size());
        assertTrue(ReelClearMode.waiting());

        viewer.page = second;
        viewer.now += 400;
        ReelClearMode.paged();
        assertEquals("not before Instagram is done with the page", 1, viewer.asked);
        assertEquals(1, viewer.posted.size());
        ReelClearMode.paged();
        assertEquals("one try for one page", 1, viewer.posted.size());
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "second OVERFLOW_MENU"), viewer.turnedOn);
        assertEquals(2, viewer.asked);
        assertFalse(ReelClearMode.waiting());
        assertTrue(ReelClearMode.wanted());
        assertTrue(HookStatus.report().toString(), counted(1));

        // It carries on from the second reel as from any other.
        Reel third = reel("third");
        swipe(second, third);
        assertEquals("third OVERFLOW_MENU", viewer.turnedOn.get(2));
    }

    /** A page report that still names the same reel ends it: there is one more try, not a loop. */
    @Test
    public void aSecondTryOnTheSameReelEndsIt() {
        Reel first = reel("first");
        viewer.page = first;
        tapClearMode();
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertTrue(ReelClearMode.waiting());

        ReelClearMode.paged();
        viewer.runPosted();

        assertEquals("asked twice in all", 2, viewer.asked);
        assertEquals(Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);
        assertFalse(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        ReelClearMode.paged();
        viewer.runPosted();
        assertEquals("and never again", 2, viewer.asked);
    }

    /**
     * A swipe back takes the exit button off the screen and can snap back without landing. The pager
     * reports no page then, so once it comes to rest the same reel is put back in clear mode, after
     * Instagram is done with the change, and it carries on from there (#233).
     */
    @Test
    public void aSwipeThatSnapsBackKeepsTheSameReelInClearMode() {
        Reel first = reel("first");
        Reel second = reel("second");
        viewer.page = first;
        tapClearMode();

        ReelClearMode.settled(viewer.pager, Scroll.DRAGGING);
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals("asked once, and held back", 1, viewer.asked);
        assertTrue(ReelClearMode.waiting());
        // The reel before it builds its button as the drag shows it, which tries again in vain.
        reel("before");
        viewer.runPosted();
        assertEquals(2, viewer.asked);
        ReelClearMode.settled(viewer.pager, Scroll.SETTLING);
        assertTrue("settling isn't rest", viewer.posted.isEmpty());

        viewer.now += 300;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertEquals("not before Instagram is done with the change", 2, viewer.asked);
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertEquals("one try", 1, viewer.posted.size());
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "first OVERFLOW_MENU"), viewer.turnedOn);
        assertEquals(3, viewer.asked);
        assertTrue(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertTrue(HookStatus.report().toString(), kept(1));
        assertFalse("it wasn't carried to another reel", HookStatus.report().toString().contains(ReelClearMode.CARRIED));
        assertEquals("back in clear mode, a reset keeps its buttons hidden", 0f, ReelClearMode.chrome(1f, 0f), 0f);

        swipe(first, second);
        assertEquals("second OVERFLOW_MENU", viewer.turnedOn.get(2));
        assertTrue(counted(1));
        assertEquals("reels kept in clear mode after a swipe snapped back", ReelClearMode.KEPT);
    }

    /**
     * A swipe that lands reports its page before the pager comes to rest, and a page whose exit
     * button comes late goes on waiting for it. Neither puts the reel that left back in clear mode.
     */
    @Test
    public void aSwipeThatLandsIsntTakenForASnapBack() {
        Reel first = reel("first");
        Reel second = reel("second");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();

        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        viewer.page = second;
        ReelClearMode.paged();
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertEquals("the page's try is the only one", 1, viewer.posted.size());
        viewer.runPosted();
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("nothing waits, so rest asks for nothing", viewer.posted.isEmpty());
        assertEquals(Arrays.asList("first OVERFLOW_MENU", "second OVERFLOW_MENU"), viewer.turnedOn);

        // The page landed before its button was built: rest asks for nothing, the button's try does.
        ReelClearMode.left(viewer.handler, second, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        viewer.page = late;
        ReelClearMode.paged();
        viewer.runPosted();
        assertTrue(ReelClearMode.waiting());
        viewer.now += 200;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("a rest after a page asks for nothing", viewer.posted.isEmpty());
        assertEquals("the reel that left isn't put back", 2, viewer.turnedOn.size());
        assertTrue("the late page still waits for its button", ReelClearMode.waiting());
        viewer.now += 200;
        ReelClearMode.built(late);
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "second OVERFLOW_MENU", "late OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(HookStatus.report().toString(), counted(2));
        assertFalse(HookStatus.report().toString().contains(ReelClearMode.KEPT));
    }

    /**
     * A page reported while the reel waits means the swipe landed, through every try after it. A
     * rest once the page's try waited on a late button doesn't put the reel that left back, even
     * when Instagram's turn-on would still read that reel.
     */
    @Test
    public void aRestAfterAPageWithALateButtonLeavesTheReelThatLeftOut() {
        Reel first = reel("first");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();

        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        viewer.page = late;
        ReelClearMode.paged();
        viewer.runPosted();
        assertTrue("the page's try waits for its button", ReelClearMode.waiting());

        viewer.page = first;
        viewer.now += 200;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("the swipe landed, so rest asks for nothing", viewer.posted.isEmpty());
        viewer.runPosted();
        assertEquals("the reel that left isn't put back", Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);

        viewer.page = late;
        viewer.now += 200;
        ReelClearMode.built(late);
        viewer.runPosted();
        assertEquals(Arrays.asList("first OVERFLOW_MENU", "late OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(HookStatus.report().toString(), counted(1));
        assertFalse(HookStatus.report().toString().contains(ReelClearMode.KEPT));
    }

    /**
     * A swipe can report its page before the reel's exit button leaves, so no page comes while it
     * waits. A first try that finds another reel on the pager means the swipe landed all the same.
     */
    @Test
    public void aRestAfterATryFoundAnotherReelLeavesTheReelThatLeftOut() {
        Reel first = reel("first");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();

        viewer.page = late;
        ReelClearMode.paged();
        assertTrue("nothing waits yet, so the page asks for nothing", viewer.posted.isEmpty());
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals(1, viewer.asked);
        assertTrue("the first try waits for the next reel's button", ReelClearMode.waiting());

        viewer.page = first;
        viewer.now += 200;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("the swipe landed, so rest asks for nothing", viewer.posted.isEmpty());
        viewer.runPosted();
        assertEquals("the reel that left isn't put back", Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);

        viewer.page = late;
        viewer.now += 200;
        ReelClearMode.built(late);
        viewer.runPosted();
        assertEquals(Arrays.asList("first OVERFLOW_MENU", "late OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(HookStatus.report().toString(), counted(1));
        assertFalse(HookStatus.report().toString().contains(ReelClearMode.KEPT));
    }

    /**
     * Leaving Reels takes the exit button away too. A pager at rest off screen, a wait that ran out,
     * a state short of rest, no pager and no wait at all put nothing back in clear mode.
     */
    @Test
    public void aPagerOffScreenOrAWaitRunOutPutsNothingBack() {
        Reel first = reel("first");
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("with nothing waiting, rest asks for nothing", viewer.posted.isEmpty());
        viewer.page = first;
        tapClearMode();
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        assertTrue("nor while the reel is still in clear mode", viewer.posted.isEmpty());

        viewer.pagerShown = false;
        snapBack(first);
        viewer.runPosted();
        assertEquals("tried, and held back", 2, viewer.asked);
        assertEquals(Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue("the wait goes on", ReelClearMode.waiting());

        viewer.pagerShown = true;
        ReelClearMode.settled(null, Scroll.IDLE);
        ReelClearMode.settled(viewer.pager, null);
        ReelClearMode.settled(viewer.pager, Reason.OVERFLOW_MENU);
        assertTrue("no pager or no rest asks for nothing", viewer.posted.isEmpty());

        viewer.now += ReelClearMode.RETRY_WINDOW_MS;
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        viewer.runPosted();
        assertEquals("the wait ran out", 2, viewer.asked);
        assertEquals(1, viewer.turnedOn.size());
        assertFalse(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertFalse(HookStatus.report().toString().contains(ReelClearMode.KEPT));
    }

    /** Turned off before the pager comes to rest, or a pager check that throws, forgets clear mode, and the throw is reported. */
    @Test
    public void aSnapBackWhileOffOrAThrowingCheckForgetsClearMode() {
        Reel first = reel("first");
        viewer.page = first;
        tapClearMode();
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        Settings.KEEP_REEL_CLEAR_MODE.save(false);
        ReelClearMode.settled(viewer.pager, Scroll.IDLE);
        viewer.runPosted();
        assertFalse(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertEquals(1, viewer.asked);

        Settings.KEEP_REEL_CLEAR_MODE.save(true);
        tapClearMode();
        viewer.showingFailure = new IllegalStateException("the pager changed");
        snapBack(first);
        viewer.runPosted();
        assertFalse(ReelClearMode.wanted());
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), reported("clear mode retry"));
        assertEquals("nothing was turned on but your own taps", Arrays.asList("first OVERFLOW_MENU", "first OVERFLOW_MENU"), viewer.turnedOn);
        assertEquals(2, viewer.asked);
    }

    /** The patch's own check asks the pager view: shown, attached and mostly in view. */
    @Test
    public void thePatchedCheckAsksThePagerView() {
        assertFalse("not a view", ReelClearMode.PATCHED.showing(new Object()));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View pager = new View(activity);
        assertFalse("not on screen yet", ReelClearMode.PATCHED.showing(pager));
        activity.setContentView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ShadowLooper.idleMainLooper();
        assertTrue("laid out", pager.getWidth() > 0 && pager.getHeight() > 0);
        assertTrue("on screen", ReelClearMode.PATCHED.showing(pager));

        pager.setTranslationX(pager.getWidth() * 0.4f);
        assertTrue("mostly in view", ReelClearMode.PATCHED.showing(pager));
        pager.setTranslationX(pager.getWidth() * 0.6f);
        assertFalse("mostly off to the side", ReelClearMode.PATCHED.showing(pager));
        pager.setTranslationX(0f);
        pager.setVisibility(View.GONE);
        assertFalse("hidden", ReelClearMode.PATCHED.showing(pager));
    }

    /**
     * Closing the viewer takes the exit button off the screen too, with the pager still on its
     * reel. Nothing is turned on, and a viewer opened later starts as Instagram starts it.
     */
    @Test
    public void closingTheViewerCarriesNothingIntoTheNextOne() {
        Reel first = reel("first");
        viewer.page = first;
        tapClearMode();
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals(1, viewer.turnedOn.size());

        viewer.now += ReelClearMode.RETRY_WINDOW_MS + 1;
        Reel elsewhere = reel("elsewhere");
        viewer.page = elsewhere;
        ReelClearMode.paged();
        viewer.runPosted();

        assertEquals("the wait ran out, so the other viewer's page gets nothing", 1, viewer.asked);
        assertEquals(Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);
        assertFalse(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertEquals(3_000, ReelClearMode.RETRY_WINDOW_MS);
    }

    /**
     * A page Instagram built no exit button for could never leave clear mode again, so it isn't put
     * in it, and once the wait for its button runs out the next reels show their buttons.
     */
    @Test
    public void aPageWithNoExitButtonEndsItWhenTheWaitRunsOut() {
        Reel first = reel("first");
        Reel card = new Reel("a card with no exit button");
        Reel after = reel("after");
        viewer.page = first;
        tapClearMode();

        swipe(first, card);
        assertEquals("Instagram was asked, and found nothing to turn on", 1, viewer.asked);
        assertTrue("its button may still be coming", ReelClearMode.waiting());

        viewer.now += 1_000;
        ReelClearMode.paged();
        viewer.runPosted();
        assertEquals("tried again, and the card still has no button", 2, viewer.asked);
        assertEquals(Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue("the wait keeps its first end", ReelClearMode.waiting());

        viewer.now += ReelClearMode.RETRY_WINDOW_MS;
        swipe(card, after);
        assertEquals(2, viewer.asked);
        assertEquals(1, viewer.turnedOn.size());
        assertFalse(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
    }

    /** A card swiped past while its wait still runs hands clear mode to the reel after it. */
    @Test
    public void aReelRightAfterACardStillGetsClearMode() {
        Reel first = reel("first");
        Reel card = new Reel("a card with no exit button");
        Reel after = reel("after");
        viewer.page = first;
        tapClearMode();

        swipe(first, card);
        viewer.now += 1_500;
        swipe(card, after);

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "after OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
    }

    /**
     * Instagram builds the buttons of reels further on while a swipe is still landing. That isn't
     * the swipe landing: the wait goes on, and the page it lands on gets clear mode (#233).
     */
    @Test
    public void aButtonBuiltWhileTheSwipeLandsDoesntEndTheWait() {
        Reel first = reel("first");
        Reel second = reel("second");
        viewer.page = first;
        tapClearMode();
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals(1, viewer.asked);

        viewer.now += 100;
        reel("third");
        viewer.runPosted();
        assertEquals("tried, and the pager is still on the reel that left", 2, viewer.asked);
        assertTrue(ReelClearMode.wanted());
        assertTrue(ReelClearMode.waiting());

        viewer.now += 300;
        viewer.page = second;
        ReelClearMode.paged();
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "second OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertTrue(HookStatus.report().toString(), counted(1));
    }

    /**
     * A swipe can land before its reel's exit button is built, after the old button already left.
     * The page's try waits on, and the button gets clear mode when it comes (#233).
     */
    @Test
    public void aButtonBuiltAfterItsPageLandedStillGetsClearMode() {
        Reel first = reel("first");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);

        viewer.now += 300;
        viewer.page = late;
        ReelClearMode.paged();
        viewer.runPosted();
        assertEquals("tried on the page, which has no button yet", 2, viewer.asked);
        assertTrue(ReelClearMode.waiting());

        viewer.now += 400;
        ReelClearMode.built(late);
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "late OVERFLOW_MENU"), viewer.turnedOn);
        assertEquals(3, viewer.asked);
        assertTrue(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
    }

    /**
     * The next reel's exit button can be built after its swipe lands and the old button leaves. The
     * turn-on is held back and given to the reel once its button is built (#233).
     */
    @Test
    public void aReelWhoseExitButtonComesLateStillGetsClearMode() {
        Reel first = reel("first");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();

        swipe(first, late);
        assertEquals(1, viewer.asked);
        assertTrue(ReelClearMode.wanted());
        assertTrue(ReelClearMode.waiting());

        viewer.now += 200;
        ReelClearMode.built(late);
        assertEquals("not before Instagram is done with the button", 1, viewer.asked);
        ReelClearMode.built(new Reel("another"));
        assertEquals("one try", 1, viewer.posted.size());
        viewer.runPosted();

        assertEquals(Arrays.asList("first OVERFLOW_MENU", "late OVERFLOW_MENU"), viewer.turnedOn);
        assertTrue(ReelClearMode.wanted());
        assertFalse(ReelClearMode.waiting());
        assertTrue(HookStatus.report().toString(), counted(1));
    }

    /** A button built after the wait is over is no reason to turn clear mode on for that page. */
    @Test
    public void aButtonBuiltAfterTheWaitIsOverAsksForNothing() {
        Reel first = reel("first");
        Reel late = new Reel("late");
        viewer.page = first;
        tapClearMode();
        swipe(first, late);

        viewer.now += ReelClearMode.RETRY_WINDOW_MS + 1;
        ReelClearMode.built(late);
        viewer.runPosted();

        assertEquals(1, viewer.asked);
        assertEquals(1, viewer.turnedOn.size());
    }

    /** A reel that left the screen in a clear mode you didn't ask for through the handler hands nothing on. */
    @Test
    public void onlyTheReelYouPutInClearModeHandsItOn() {
        Reel first = reel("first");
        Reel other = reel("other");
        Reel next = reel("next");
        viewer.page = first;
        tapClearMode();

        viewer.page = next;
        ReelClearMode.left(viewer.handler, other, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals(0, viewer.asked);
        assertTrue("the reel you asked for is still in it", ReelClearMode.wanted());

        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertEquals(1, viewer.asked);
        assertEquals("next OVERFLOW_MENU", viewer.turnedOn.get(1));
    }

    /** Before any turn-on, a reel leaving the screen asks for nothing, and Instagram's turn-on gets its reel as it is. */
    @Test
    public void withoutATurnOnOfYoursNothingIsAsked() {
        Reel first = reel("first");
        Reel second = reel("second");
        assertSame(first, ReelClearMode.current(first));
        assertNull(ReelClearMode.current(null));

        swipe(first, second);
        ReelClearMode.paged();
        viewer.runPosted();

        assertEquals(0, viewer.asked);
        assertFalse(ReelClearMode.wanted());
        assertTrue(viewer.posted.isEmpty());

        // A turn-on that found no reel on the pager turned nothing on.
        viewer.page = null;
        tapClearMode();
        assertFalse(ReelClearMode.wanted());
        assertTrue(viewer.turnedOn.isEmpty());
    }

    /** Off to start, paused and before the settings are read, every reel is as Instagram has it and nothing is counted. */
    @Test
    public void offPausedAndUnreadyLeaveEveryReelAsInstagramHasIt() {
        Reel first = reel("first");
        Reel second = reel("second");
        Reel third = reel("third");

        Settings.KEEP_REEL_CLEAR_MODE.resetToDefault();
        viewer.page = first;
        tapClearMode();
        assertFalse("off to start", ReelClearMode.wanted());
        swipe(first, second);
        assertEquals("off asks for nothing", 0, viewer.asked);

        // Turned off between a turn-on and the swipe, nothing is carried.
        Settings.KEEP_REEL_CLEAR_MODE.save(true);
        viewer.page = first;
        tapClearMode();
        assertTrue(ReelClearMode.wanted());
        Settings.KEEP_REEL_CLEAR_MODE.save(false);
        swipe(first, second);
        assertEquals(0, viewer.asked);
        assertFalse(ReelClearMode.wanted());

        Settings.KEEP_REEL_CLEAR_MODE.save(true);
        viewer.page = first;
        tapClearMode();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        swipe(first, second);
        assertEquals("paused asks for nothing", 0, viewer.asked);
        assertFalse("and forgets, so a resume brings nothing back", ReelClearMode.wanted());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        swipe(second, third);
        assertEquals(0, viewer.asked);

        SettingsContextRule.withoutContext(() -> {
            viewer.page = first;
            tapClearMode();
            assertFalse("unready", ReelClearMode.wanted());
            swipe(first, second);
        });
        assertEquals("unready asks for nothing", 0, viewer.asked);
        assertFalse("nothing was counted", HookStatus.report().toString().contains(ReelClearMode.CARRIED));

        viewer.page = first;
        tapClearMode();
        swipe(first, second);
        assertEquals("and on again once resumed and ready", 1, viewer.asked);
        assertTrue(counted(1));
    }

    /** A turn-on that throws, or a reel that can't be read, forgets clear mode, is reported and asks no more. */
    @Test
    public void aThrowingTurnOnOrReaderForgetsClearModeAndIsReported() {
        Reel first = reel("first");
        Reel second = reel("second");
        Reel third = reel("third");
        viewer.page = first;
        tapClearMode();

        viewer.enterFailure = new IllegalStateException("the handler changed");
        swipe(first, second);
        assertEquals(1, viewer.asked);
        assertFalse(ReelClearMode.wanted());
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), reported("clear mode off"));
        viewer.enterFailure = null;
        swipe(second, third);
        assertEquals("a failed turn-on isn't tried again", 1, viewer.asked);
        assertEquals(Collections.singletonList("first OVERFLOW_MENU"), viewer.turnedOn);

        // A reel whose id can't be read while Instagram turns clear mode on for you stays Instagram's.
        HookStatus.clear();
        viewer.page = first;
        viewer.unreadable = first;
        assertSame("your own turn-on still gets its reel", first, ReelClearMode.current(first));
        ReelClearMode.entered(viewer.handler, Reason.OVERFLOW_MENU);
        assertFalse(ReelClearMode.wanted());
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), reported("clear mode reel"));

        // One that can't be read while a turn-on is being carried gets no clear mode.
        viewer.unreadable = null;
        tapClearMode();
        assertTrue(ReelClearMode.wanted());
        viewer.page = second;
        viewer.unreadable = second;
        ReelClearMode.left(viewer.handler, first, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        viewer.unreadable = null;
        assertEquals("Instagram was asked, and its turn-on was held back", 2, viewer.asked);
        assertFalse(ReelClearMode.wanted());
        assertEquals("nothing was turned on for a reel that couldn't be read", 2, viewer.turnedOn.size());
        swipe(second, third);
        assertEquals(2, viewer.turnedOn.size());
    }

    /** A reel in clear mode draws no seek bar while the switch is on, and every other reel keeps Instagram's answer (#277). */
    @Test
    public void aReelInClearModeDrawsNoSeekBarWhileTheSwitchIsOn() {
        assertFalse("in clear mode, no seek bar", ReelClearMode.scrubber(1, 1));
        assertTrue("out of it, Instagram's yes stands", ReelClearMode.scrubber(1, 0));
        assertFalse("Instagram's no stands", ReelClearMode.scrubber(0, 1));
        assertFalse(ReelClearMode.scrubber(0, 0));
        assertTrue("any non-zero is yes", ReelClearMode.scrubber(2, 0));
        assertFalse(ReelClearMode.scrubber(2, -1));
        assertTrue("nothing wanted is needed", !ReelClearMode.wanted() && !ReelClearMode.scrubber(1, 1));

        Settings.KEEP_REEL_CLEAR_MODE.save(false);
        assertTrue("off, Instagram's seek bar stays", ReelClearMode.scrubber(1, 1));
        Settings.KEEP_REEL_CLEAR_MODE.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused, it stays", ReelClearMode.scrubber(1, 1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue("unready, it stays", ReelClearMode.scrubber(1, 1)));
        assertFalse("and goes again once resumed and ready", ReelClearMode.scrubber(1, 1));
    }

    /**
     * Every reel page shares one alpha for its buttons, and Instagram resets it to shown when it
     * binds a page at rest. While a reel you put in clear mode is in it, that reset keeps the alpha
     * as it is. The fade out, a raise short of shown, a reel waiting for its turn and your own way
     * out all go through (#233).
     */
    @Test
    public void aResetToShownKeepsTheButtonsOfAReelInClearModeHidden() {
        Reel first = reel("first");
        Reel second = reel("second");
        Reel third = reel("third");
        assertEquals("before any turn-on, Instagram's reset stands", 1f, ReelClearMode.chrome(1f, 0f), 0f);

        viewer.page = first;
        tapClearMode();
        assertEquals("the fade out goes through", 0.4f, ReelClearMode.chrome(0.4f, 0.5f), 0f);
        assertEquals("to its end", 0f, ReelClearMode.chrome(0f, 0.1f), 0f);
        assertEquals("a reset to shown keeps the buttons hidden", 0f, ReelClearMode.chrome(1f, 0f), 0f);
        assertEquals("or as far as the fade got", 0.3f, ReelClearMode.chrome(1f, 0.3f), 0f);
        assertEquals("a raise short of shown goes through", 0.6f, ReelClearMode.chrome(0.6f, 0f), 0f);
        assertEquals("shown stays shown", 1f, ReelClearMode.chrome(1f, 1f), 0f);

        swipe(first, second);
        assertEquals("the reel clear mode is carried to keeps it too", 0f, ReelClearMode.chrome(1f, 0f), 0f);

        // The pager still names the reel that left, so it waits for the next page, and no reel is in clear mode until then.
        ReelClearMode.left(viewer.handler, second, Reason.REELS_VIEWER_SWIPE_OR_EXIT);
        assertTrue(ReelClearMode.waiting());
        assertEquals("nothing is held while the next reel waits", 1f, ReelClearMode.chrome(1f, 0f), 0f);
        viewer.page = third;
        ReelClearMode.paged();
        viewer.runPosted();
        assertEquals("and the reel it lands on keeps it", 0f, ReelClearMode.chrome(1f, 0f), 0f);

        Settings.KEEP_REEL_CLEAR_MODE.save(false);
        assertEquals("off, Instagram's reset stands", 1f, ReelClearMode.chrome(1f, 0f), 0f);
        Settings.KEEP_REEL_CLEAR_MODE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused, it stands", 1f, ReelClearMode.chrome(1f, 0f), 0f);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("unready, it stands", 1f, ReelClearMode.chrome(1f, 0f), 0f));
        assertEquals("and is held again once on, resumed and ready", 0f, ReelClearMode.chrome(1f, 0f), 0f);

        ReelClearMode.left(viewer.handler, third, Reason.EXIT_BUTTON);
        assertEquals("leaving clear mode yourself, Instagram's fade back in goes through", 1f, ReelClearMode.chrome(1f, 0.9f), 0f);
        assertEquals("and so does a reset", 1f, ReelClearMode.chrome(1f, 0f), 0f);
    }
}
