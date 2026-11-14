/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.SeekBar;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Swipe left for Messages (#639): which touches on Home it takes, and that every other touch stays Instagram's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeLeftSwipeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int TAB_BAR_ID = 0x7f0b0001;
    private static final int FEED_TAB_ID = 0x7f0b0002;
    private static final int DIRECT_TAB_ID = 0x7f0b0003;
    private static final int HEADER_INBOX_ID = 0x7f0b0004;

    /** What the patched dispatch asks with: the switch, as the test sets it. */
    static BooleanSupplier on = () -> true;

    private final Map<String, Integer> ids = new HashMap<>();

    @Before
    public void setUp() {
        HomeLeftSwipe.forgetForTests();
        ids.put(HomeLeftSwipe.TAB_BAR, TAB_BAR_ID);
        ids.put(HomeLeftSwipe.FEED_TAB, FEED_TAB_ID);
        ids.put(HomeLeftSwipe.DIRECT_TAB, DIRECT_TAB_ID);
        ids.put(HomeLeftSwipe.HEADER_INBOX, HEADER_INBOX_ID);
        HomeLeftSwipe.ids = (context, name) -> ids.getOrDefault(name, 0);
        on = () -> true;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        HomeLeftSwipe.forgetForTests();
        on = () -> true;
        Settings.SWIPE_LEFT_MESSAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A view that records each touch it's handed and keeps every one it gets. */
    static class Recorder extends View {
        final List<Integer> actions = new ArrayList<>();

        Recorder(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            return true;
        }
    }

    /** A carousel that can still go left, recording what it's handed. */
    static final class Carousel extends Recorder {
        Carousel(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return direction > 0;
        }
    }

    /** Home's list, by the name it keeps in Instagram. */
    public static final class MainFeedRecyclerView extends FrameLayout {
        public MainFeedRecyclerView(Context context) {
            super(context);
        }
    }

    /**
     * A screen laid out as Instagram's tabs' screen is on Home: the list filling it, holding a post
     * across the middle, a carousel along the top and a video's seek bar near the bottom, the tab bar
     * along the bottom with Home's tab picked and Messages' tab counting its clicks, and the inbox
     * button at the top of Home. Its touch dispatch asks the hook first, as the patched one does.
     */
    public static class TabsScreen extends Activity {
        MainFeedRecyclerView list;
        Recorder post;
        Carousel carousel;
        SeekBar seekBar;
        View feedTab;
        View directTab;
        View inbox;
        int messagesClicks;
        int inboxClicks;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            int match = ViewGroup.LayoutParams.MATCH_PARENT;
            FrameLayout root = new FrameLayout(this);
            list = new MainFeedRecyclerView(this);
            root.addView(list, new FrameLayout.LayoutParams(match, match));
            post = new Recorder(this);
            list.addView(post, new FrameLayout.LayoutParams(match, match));
            carousel = new Carousel(this);
            FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(match, 80, Gravity.TOP);
            top.topMargin = 40;
            list.addView(carousel, top);
            seekBar = new SeekBar(this);
            FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(match, 30, Gravity.BOTTOM);
            bottom.bottomMargin = 60;
            list.addView(seekBar, bottom);

            inbox = new View(this);
            inbox.setId(HEADER_INBOX_ID);
            inbox.setOnClickListener(v -> inboxClicks++);
            root.addView(inbox, new FrameLayout.LayoutParams(40, 40, Gravity.TOP | Gravity.END));

            FrameLayout bar = new FrameLayout(this);
            bar.setId(TAB_BAR_ID);
            root.addView(bar, new FrameLayout.LayoutParams(match, 50, Gravity.BOTTOM));
            feedTab = new View(this);
            feedTab.setId(FEED_TAB_ID);
            feedTab.setSelected(true);
            bar.addView(feedTab, new FrameLayout.LayoutParams(60, match, Gravity.START));
            directTab = new View(this);
            directTab.setId(DIRECT_TAB_ID);
            directTab.setOnClickListener(v -> messagesClicks++);
            bar.addView(directTab, new FrameLayout.LayoutParams(60, match, Gravity.END));
            setContentView(root);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (HomeLeftSwipe.touch(this, event, on)) return true;
            return super.dispatchTouchEvent(event);
        }
    }

    /** The tabs' own screen, by its name. */
    public static final class MainTabActivity extends TabsScreen {
    }

    /** Another of Instagram's screens with the same views on it. */
    public static final class ProfileActivity extends TabsScreen {
    }

    private static <T extends TabsScreen> T screen(Class<T> type) {
        T screen = Robolectric.buildActivity(type).setup().get();
        ShadowLooper.idleMainLooper();
        return screen;
    }

    private static float[] centerOf(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new float[] {at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }

    private static int slop(Context context) {
        return ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** How far left a lift has to be to open Messages on this screen. */
    private static float openDistance(Activity activity) {
        return Math.max(2 * slop(activity), activity.getResources().getDisplayMetrics().widthPixels / 10f);
    }

    /** Sends one touch through the screen's dispatch: down at [from], moves to each of [steps] across, lifts at the last. */
    private static void swipe(Activity screen, float[] from, float... steps) {
        long down = 1000;
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from[0], from[1], 0));
        float x = from[0];
        long time = down;
        for (float step : steps) {
            time += 16;
            x = from[0] + step;
            screen.dispatchTouchEvent(MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE, x, from[1], 0));
        }
        screen.dispatchTouchEvent(MotionEvent.obtain(down, time + 16, MotionEvent.ACTION_UP, x, from[1], 0));
        ShadowLooper.idleMainLooper();
    }

    /** A left swipe across a post opens Messages through its tab, and the post gets a cancel, never a lift. */
    @Test
    public void aLeftSwipeAcrossHomeOpensMessages() {
        MainTabActivity screen = screen(MainTabActivity.class);
        float far = -openDistance(screen) - 10;

        swipe(screen, centerOf(screen.post), -slop(screen) - 2, far / 2, far);

        assertEquals("Messages opened", 1, screen.messagesClicks);
        assertEquals("through the tab, not the header", 0, screen.inboxClicks);
        List<Integer> handed = screen.post.actions;
        assertEquals("the post saw the touch go down", MotionEvent.ACTION_DOWN, (int) handed.get(0));
        assertEquals("then a cancel as the swipe was taken", MotionEvent.ACTION_CANCEL, (int) handed.get(handed.size() - 1));
        assertFalse("and never the lift", handed.contains(MotionEvent.ACTION_UP));
        assertEquals("the touch is over", HomeLeftSwipe.IDLE, HomeLeftSwipe.stateForTests());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HomeLeftSwipe.COUNT_OPENED));
    }

    /** A carousel that can still go left keeps the swipe, every step of it, and Messages stays shut. */
    @Test
    public void aCarouselKeepsItsSwipe() {
        MainTabActivity screen = screen(MainTabActivity.class);
        float far = -openDistance(screen) - 10;

        swipe(screen, centerOf(screen.carousel), -slop(screen) - 2, far);

        assertEquals(0, screen.messagesClicks);
        assertEquals("the carousel got the whole swipe",
            List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), screen.carousel.actions);
    }

    /** A video's seek bar keeps the swipe too. */
    @Test
    public void aSeekBarKeepsItsSwipe() {
        MainTabActivity screen = screen(MainTabActivity.class);
        swipe(screen, centerOf(screen.seekBar), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
    }

    /** Up and down, or to the right, the touch is Instagram's from start to end. */
    @Test
    public void aScrollOrARightSwipeIsLeftAlone() {
        MainTabActivity screen = screen(MainTabActivity.class);
        float[] from = centerOf(screen.post);
        long down = 1000;
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from[0], from[1], 0));
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_MOVE, from[0] - slop(screen) - 2, from[1] - 3 * slop(screen), 0));
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down + 32, MotionEvent.ACTION_MOVE, from[0] - openDistance(screen) - 10, from[1] - 3 * slop(screen), 0));
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down + 48, MotionEvent.ACTION_UP, from[0] - openDistance(screen) - 10, from[1] - 3 * slop(screen), 0));
        ShadowLooper.idleMainLooper();
        assertEquals("a scroll", List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), screen.post.actions);

        screen.post.actions.clear();
        swipe(screen, from, slop(screen) + 2, openDistance(screen) + 10);
        assertEquals("a swipe right", List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), screen.post.actions);
        assertEquals(0, screen.messagesClicks);
    }

    /** A swipe taken but lifted short of the mark opens nothing, and the post still never sees a lift. */
    @Test
    public void aShortSwipeOpensNothing() {
        MainTabActivity screen = screen(MainTabActivity.class);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2);
        assertEquals(0, screen.messagesClicks);
        assertFalse(screen.post.actions.contains(MotionEvent.ACTION_UP));
    }

    /** A second finger on a swipe swallows the rest of the touch and opens nothing. */
    @Test
    public void aSecondFingerSwallowsTheSwipe() {
        MainTabActivity screen = screen(MainTabActivity.class);
        float[] from = centerOf(screen.post);
        float far = from[0] - openDistance(screen) - 10;
        long down = 1000;
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from[0], from[1], 0));
        screen.dispatchTouchEvent(MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_MOVE, from[0] - slop(screen) - 2, from[1], 0));
        assertEquals(HomeLeftSwipe.SWIPING, HomeLeftSwipe.stateForTests());
        assertTrue(HomeLeftSwipe.touch(screen, MotionEvent.obtain(down, down + 32, MotionEvent.ACTION_POINTER_DOWN, far, from[1], 0), on));
        assertEquals(HomeLeftSwipe.SWALLOWING, HomeLeftSwipe.stateForTests());
        assertTrue(HomeLeftSwipe.touch(screen, MotionEvent.obtain(down, down + 48, MotionEvent.ACTION_MOVE, far, from[1], 0), on));
        assertTrue(HomeLeftSwipe.touch(screen, MotionEvent.obtain(down, down + 64, MotionEvent.ACTION_UP, far, from[1], 0), on));
        ShadowLooper.idleMainLooper();
        assertEquals(0, screen.messagesClicks);
        assertEquals(HomeLeftSwipe.IDLE, HomeLeftSwipe.stateForTests());
    }

    /** With another tab picked, the swipe is the tabs' own. */
    @Test
    public void anotherTabLeavesTheSwipeAlone() {
        MainTabActivity screen = screen(MainTabActivity.class);
        screen.feedTab.setSelected(false);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
        assertTrue(screen.post.actions.contains(MotionEvent.ACTION_UP));
    }

    /** On any other screen nothing is taken. */
    @Test
    public void anotherScreenLeavesTheSwipeAlone() {
        ProfileActivity screen = screen(ProfileActivity.class);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
        assertTrue(screen.post.actions.contains(MotionEvent.ACTION_UP));
    }

    /** Off, nothing is taken, and the switch starts off. */
    @Test
    public void offLeavesTheSwipeAlone() {
        assertEquals(Boolean.FALSE, Settings.SWIPE_LEFT_MESSAGES.defaultValue);
        on = () -> false;
        MainTabActivity screen = screen(MainTabActivity.class);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
        assertTrue(screen.post.actions.contains(MotionEvent.ACTION_UP));
    }

    /** The real switch: off by default, on when saved, and paused HushGram reads it as off. */
    @Test
    public void theSettingDecidesAndPauseTurnsItOff() {
        MainTabActivity screen = screen(MainTabActivity.class);
        float[] from = centerOf(screen.post);
        MotionEvent down = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, from[0], from[1], 0);

        HomeLeftSwipe.touch(screen, down);
        assertEquals("off by default", HomeLeftSwipe.IDLE, HomeLeftSwipe.stateForTests());

        Settings.SWIPE_LEFT_MESSAGES.save(true);
        HomeLeftSwipe.touch(screen, down);
        assertEquals("on", HomeLeftSwipe.ARMED, HomeLeftSwipe.stateForTests());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HomeLeftSwipe.touch(screen, down);
        assertEquals("paused", HomeLeftSwipe.IDLE, HomeLeftSwipe.stateForTests());
    }

    /** With no Messages tab on the bar, the inbox button at the top of Home opens it. */
    @Test
    public void withNoMessagesTabTheHeaderInboxOpensIt() {
        ids.put(HomeLeftSwipe.DIRECT_TAB, 0);
        MainTabActivity screen = screen(MainTabActivity.class);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
        assertEquals(1, screen.inboxClicks);
    }

    /** A switch that throws leaves the touch to Instagram and says the hook threw. */
    @Test
    public void aThrowingSwitchFailsOpen() {
        on = () -> {
            throw new IllegalStateException("settings went away");
        };
        MainTabActivity screen = screen(MainTabActivity.class);
        swipe(screen, centerOf(screen.post), -slop(screen) - 2, -openDistance(screen) - 10);
        assertEquals(0, screen.messagesClicks);
        assertTrue(screen.post.actions.contains(MotionEvent.ACTION_UP));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_CREATE).toString();
        assertTrue(missing, missing.contains("'left swipe switch'"));
    }

    /** No screen or no event answers false without looking at anything. */
    @Test
    public void nothingToReadIsLeftAlone() {
        assertFalse(HomeLeftSwipe.touch(null, MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0)));
        assertFalse(HomeLeftSwipe.touch(screen(MainTabActivity.class), null));
    }
}
