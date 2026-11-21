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
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * A reel link's own screen steps aside and the reel goes onto the tab screen under it, only with the
 * switch on and only when the tab screen is right under the link or there's nothing at all (#385).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelLinkTabBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String SHORT_URL = "short_url";
    private static final String CONFIG = "ClipsConstants.ARG_CLIPS_VIEWER_CONFIG";
    /** The tab bar flag's name in the stand-in config, as the patch reads it off the real one. */
    private static final String FLAG = "A3V";
    private static final int BAR = 12;

    /** Stands in for Instagram's tab screen. */
    public static final class TabScreen extends Activity {
    }

    /** Stands in for Instagram's viewer config: final flags under renamed fields. */
    public static final class ViewerConfig implements Parcelable {
        final boolean A3V;
        final boolean A2a;

        ViewerConfig(boolean hidesTabBar) {
            A3V = hidesTabBar;
            A2a = true;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(A3V ? 1 : 0);
        }
    }

    private final Object session = new Object();
    private final List<Object[]> shown = new ArrayList<>();
    private Bundle arguments;
    private Activity link;
    private ReelLinkTabBar.Under under;
    private boolean opensTabs;
    private int tabsOpened;

    private final ReelLinkTabBar.Screens screens = new ReelLinkTabBar.Screens() {
        @Override
        public ReelLinkTabBar.Under under(Activity screen) {
            assertSame(link, screen);
            return under;
        }

        @Override
        public boolean openTabs(Activity screen) {
            assertSame(link, screen);
            tabsOpened++;
            return opensTabs;
        }
    };

    @Before
    public void link() {
        ReelLinkTabBar.resetForTests();
        ReelLinkTabBar.tabScreenForTests = TabScreen.class.getName();
        ReelLinkTabBar.viewerConfigForTests = ViewerConfig.class.getName();
        Settings.REEL_LINK_TAB_BAR.save(true);
        arguments = new Bundle();
        arguments.putString(SHORT_URL, "Df9gT2ymXcW");
        arguments.putBoolean(ReelLinkTabBar.BACK_STACK, false);
        arguments.putParcelable(CONFIG, new ViewerConfig(true));
        link = Robolectric.buildActivity(Activity.class).setup().get();
        under = ReelLinkTabBar.Under.TAB_SCREEN;
        opensTabs = true;
    }

    @After
    public void reset() {
        Settings.REEL_LINK_TAB_BAR.resetToDefault();
        ReelLinkTabBar.resetForTests();
    }

    private boolean open() {
        return open(FLAG);
    }

    private boolean open(String flag) {
        return ReelLinkTabBar.open(arguments, link, session, screens, (what, on, as) -> shown.add(new Object[]{what, on, as}), flag);
    }

    @SuppressWarnings("deprecation")
    private static ViewerConfig config(Bundle from) {
        return (ViewerConfig) from.getParcelable(CONFIG);
    }

    private Activity tabScreen(boolean withBar) {
        Activity tabs = Robolectric.buildActivity(TabScreen.class).create().visible().get();
        FrameLayout root = new FrameLayout(tabs);
        if (withBar) {
            View bar = new View(tabs);
            bar.setId(BAR);
            root.addView(bar);
        }
        tabs.setContentView(root);
        return tabs;
    }

    private void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test
    public void theSwitchStartsOffAndLeavesTheLinkToInstagram() {
        Settings.REEL_LINK_TAB_BAR.resetToDefault();

        assertFalse(open());
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    /** The tab screen under the link comes back as the link closes, and the reel goes on its tab. */
    @Test
    public void aLinkOverTheTabScreenClosesAndTheReelGoesOnTheTab() {
        assertTrue(open());
        assertTrue(link.isFinishing());
        assertEquals("the tab screen is already there", 0, tabsOpened);
        assertTrue(shown.isEmpty());

        Activity tabs = Robolectric.buildActivity(TabScreen.class).setup().get();
        idle(ReelLinkTabBar.LOOKS * ReelLinkTabBar.LOOK_GAP_MS + 500);

        assertEquals(1, shown.size());
        Bundle onTab = (Bundle) shown.get(0)[0];
        assertSame(tabs, shown.get(0)[1]);
        assertSame(session, shown.get(0)[2]);
        assertEquals("the link's arguments go along", "Df9gT2ymXcW", onTab.getString(SHORT_URL));
        assertTrue("on a back stack, so Back closes the reel alone", onTab.getBoolean(ReelLinkTabBar.BACK_STACK));
        assertFalse("Instagram's own arguments are left as they were", arguments.getBoolean(ReelLinkTabBar.BACK_STACK));
        assertFalse("the viewer keeps the tab bar", config(onTab).A3V);
        assertTrue("the config's other flags stay", config(onTab).A2a);
        assertTrue("marked as the link's reel", onTab.getBoolean(ReelLinkTabBar.ON_TAB));
        assertFalse(arguments.containsKey(ReelLinkTabBar.ON_TAB));
    }

    /** Before the patch has named the flag, the link stays Instagram's. */
    @Test
    public void anUnnamedFlagLeavesTheLinkToInstagram() {
        assertFalse(open(null));
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
        assertTrue(config(arguments).A3V);
    }

    /** A config without the flag means another build's config, so the link stays Instagram's. */
    @Test
    public void aFlagTheConfigDoesntHaveLeavesTheLinkToInstagram() {
        assertFalse(open("A3W"));
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
        assertTrue(config(arguments).A3V);
    }

    @Test
    public void aLinkWithNoViewerConfigLeavesItToInstagram() {
        arguments.remove(CONFIG);

        assertFalse(open());
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    @Test
    public void theReelGoesOnTheTabAFrameAfterTheTabScreenIsBack() {
        assertTrue(open());
        Activity tabs = tabScreen(true);

        ReelLinkTabBar.resumed(tabs, BAR);
        assertTrue("not before the tab screen has drawn", shown.isEmpty());
        idle(0);

        assertEquals(1, shown.size());
        assertSame(tabs, shown.get(0)[1]);
        assertFalse(ReelLinkTabBar.isWaitingForTests());
        ReelLinkTabBar.resumed(tabs, BAR);
        idle(1000);
        assertEquals("one reel, once", 1, shown.size());
    }

    /** A tab screen still starting gets a few looks for its tab bar before the reel goes in anyway. */
    @Test
    public void aTabScreenStillStartingGetsTheReelOnceItHasItsTabBar() {
        assertTrue(open());
        Activity tabs = tabScreen(false);

        ReelLinkTabBar.resumed(tabs, BAR);
        idle(3 * ReelLinkTabBar.LOOK_GAP_MS);
        assertTrue(shown.isEmpty());

        View bar = new View(tabs);
        bar.setId(BAR);
        ViewGroup content = tabs.findViewById(android.R.id.content);
        ((FrameLayout) content.getChildAt(0)).addView(bar);
        idle(ReelLinkTabBar.LOOK_GAP_MS);
        assertEquals(1, shown.size());
    }

    @Test
    public void aLinkOnItsOwnOpensTheTabScreenFirst() {
        under = ReelLinkTabBar.Under.NOTHING;

        assertTrue(open());
        assertEquals(1, tabsOpened);
        assertTrue(link.isFinishing());

        ReelLinkTabBar.resumed(tabScreen(true), BAR);
        idle(0);
        assertEquals(1, shown.size());
    }

    @Test
    public void nothingToOpenLeavesTheLinkToInstagram() {
        under = ReelLinkTabBar.Under.NOTHING;
        opensTabs = false;

        assertFalse(open());
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    /** A profile or another screen of Instagram's own between them keeps the link as Instagram has it. */
    @Test
    public void anotherScreenUnderTheLinkLeavesItToInstagram() {
        under = ReelLinkTabBar.Under.OTHER;

        assertFalse(open());
        assertFalse(link.isFinishing());
        assertEquals(0, tabsOpened);
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    /** The reel going in on the tab screen comes through the same route, which then runs as Instagram's. */
    @Test
    public void theTabScreenItselfIsLeftToInstagram() {
        link = tabScreen(true);

        assertFalse(ReelLinkTabBar.open(arguments, link, session, screens, (what, on, as) -> shown.add(new Object[]{what}), FLAG));
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    @Test
    public void otherScreensComingBackLeaveTheReelWaiting() {
        assertTrue(open());

        ReelLinkTabBar.resumed(Robolectric.buildActivity(Activity.class).create().get(), BAR);
        idle(1000);
        assertTrue(shown.isEmpty());
        assertTrue(ReelLinkTabBar.isWaitingForTests());

        ReelLinkTabBar.resumed(tabScreen(true), BAR);
        idle(0);
        assertEquals(1, shown.size());
    }

    @Test
    public void aTabScreenBackTooLateGetsNoReel() {
        long[] now = {1000};
        ReelLinkTabBar.clock = () -> now[0];
        assertTrue(open());

        now[0] += ReelLinkTabBar.WAIT_MS + 1;
        ReelLinkTabBar.resumed(tabScreen(true), BAR);
        idle(1000);

        assertTrue(shown.isEmpty());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    @Test
    public void aRouteThatThrowsIsKeptFromInstagram() {
        assertTrue(ReelLinkTabBar.open(arguments, link, session, screens, (what, on, as) -> {
            throw new IllegalStateException("the route moved");
        }, FLAG));

        ReelLinkTabBar.resumed(tabScreen(true), BAR);
        idle(0);
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    @Test
    public void whatsUnderTheLinkComesFromItsTask() {
        assertEquals(ReelLinkTabBar.Under.NOTHING, ReelLinkTabBar.under(1, null));
        assertEquals(ReelLinkTabBar.Under.TAB_SCREEN, ReelLinkTabBar.under(2, ReelLinkTabBar.TAB_SCREEN));
        assertEquals(ReelLinkTabBar.Under.TAB_SCREEN, ReelLinkTabBar.under(2, ReelLinkTabBar.TAB_SCREEN_ALIAS));
        assertEquals(ReelLinkTabBar.Under.OTHER, ReelLinkTabBar.under(2, "com.instagram.modal.ModalActivity"));
        assertEquals(ReelLinkTabBar.Under.OTHER, ReelLinkTabBar.under(3, ReelLinkTabBar.TAB_SCREEN));
        assertEquals(ReelLinkTabBar.Under.OTHER, ReelLinkTabBar.under(2, null));
    }

    /** With no task to read, the link stays Instagram's. */
    @Test
    public void noTaskReadsAsAnotherScreen() {
        assertEquals(ReelLinkTabBar.Under.OTHER, new ReelLinkTabBar.TaskScreens().under(link));
    }

    @Test
    public void wrongArgumentsLeaveTheLinkToInstagram() {
        assertFalse(ReelLinkTabBar.open(new Object(), link, session, screens, (what, on, as) -> shown.add(new Object[]{what}), FLAG));
        assertFalse(ReelLinkTabBar.open(arguments, new Object(), session, screens, (what, on, as) -> shown.add(new Object[]{what}), FLAG));
        assertFalse(link.isFinishing());
        assertFalse(ReelLinkTabBar.isWaitingForTests());
    }

    @Test
    public void theUnpatchedStubsDoNothing() {
        ReelLinkTabBarTargets.show(arguments, link, session);
        assertFalse(link.isFinishing());
        assertNull(ReelLinkTabBarTargets.tabBarHiddenFlag());
        assertTrue(ReelLinkTabBarTargets.fragments(new Object()).isEmpty());
        assertEquals("unpatched, the host's own answer stands", 0, ReelLinkTabBar.keepTabRoot(0, new Object()));
    }

    /** Stands in for a screen on a tab: Instagram's screens hand their arguments to getArguments. */
    public static final class Screen {
        private final Bundle arguments;

        Screen(Bundle arguments) {
            this.arguments = arguments;
        }

        public Bundle getArguments() {
            return arguments;
        }
    }

    private static ReelLinkTabBar.Added showing(Object... screens) {
        return host -> Arrays.asList(screens);
    }

    /** The reel the link put on the tab is marked, so on hosts hiding the bar for pushed screens it counts as the tab's root. */
    @Test
    public void theLinksReelOnTopKeepsTheTabBar() {
        assertTrue(open());
        Robolectric.buildActivity(TabScreen.class).setup();
        idle(ReelLinkTabBar.LOOKS * ReelLinkTabBar.LOOK_GAP_MS + 500);
        Screen reel = new Screen((Bundle) shown.get(0)[0]);

        assertEquals(1, ReelLinkTabBar.keepTabRoot(0, new Object(), showing(new Screen(new Bundle()), reel)));
        assertEquals("the host's yes stands", 1, ReelLinkTabBar.keepTabRoot(1, new Object(), showing(new Screen(new Bundle()))));
    }

    @Test
    public void otherScreensOnTopLeaveTheHostsAnswer() {
        Bundle marked = new Bundle();
        marked.putBoolean(ReelLinkTabBar.ON_TAB, true);
        assertEquals("a reel opened inside Instagram", 0, ReelLinkTabBar.keepTabRoot(0, new Object(), showing(new Screen(marked), new Screen(arguments))));
        assertEquals("a screen without arguments", 0, ReelLinkTabBar.keepTabRoot(0, new Object(), showing(new Screen(null))));
        assertEquals("not a screen at all", 0, ReelLinkTabBar.keepTabRoot(0, new Object(), showing("profile")));
        assertEquals("nothing pushed", 0, ReelLinkTabBar.keepTabRoot(0, new Object(), showing()));
        assertEquals("no list", 0, ReelLinkTabBar.keepTabRoot(0, new Object(), host -> null));
    }

    @Test
    public void withTheSwitchOffTheHostsAnswerStands() {
        Bundle marked = new Bundle();
        marked.putBoolean(ReelLinkTabBar.ON_TAB, true);
        Settings.REEL_LINK_TAB_BAR.resetToDefault();
        assertEquals(0, ReelLinkTabBar.keepTabRoot(0, new Object(), showing(new Screen(marked))));

        Settings.REEL_LINK_TAB_BAR.save(true);
        int[] answer = {-1};
        SettingsContextRule.withoutContext(() -> answer[0] = ReelLinkTabBar.keepTabRoot(0, new Object(), showing(new Screen(marked))));
        assertEquals("before the settings are ready", 0, answer[0]);
    }

    @Test
    public void aListThatThrowsLeavesTheHostsAnswer() {
        assertEquals(0, ReelLinkTabBar.keepTabRoot(0, new Object(), host -> {
            throw new IllegalStateException("no manager");
        }));
    }
}
