/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which accounts the stories row puts first, in whose order, and that anything unclear leaves the row as it came (#451). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryPriorityTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A reel: the account's username (or none) and its state. */
    private static final class Reel {
        final String name;
        final int state;

        Reel(String name, int state) {
            this.name = name;
            this.state = state;
        }

        @Override
        public String toString() {
            return name + ":" + state;
        }
    }

    private static final StoryPriority.Reader READER = new StoryPriority.Reader() {
        @Override
        public int seen(Object reel) {
            return reel instanceof Reel ? ((Reel) reel).state : StoryTrayOrder.PINNED;
        }

        @Override
        public String username(Object reel) {
            return ((Reel) reel).name;
        }
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STORY_PRIORITY.save(true);
        Settings.STORY_PRIORITY_ACCOUNTS.save("zed\nann\nbob");
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STORY_PRIORITY.resetToDefault();
        Settings.STORY_PRIORITY_ACCOUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static Reel reel(String name, int state) {
        return new Reel(name, state);
    }

    private static List<Object> order(List<Object> tray, String... accounts) {
        return StoryPriority.order(tray, () -> Arrays.asList(accounts), READER);
    }

    @Test
    public void listedAccountsWithAnUnseenStoryGoFirstInTheListsOrder() {
        Reel x = reel("x", 0);
        Reel bob = reel("Bob", 0);
        Reel y = reel("y", 0);
        Reel zed = reel("ZED", 0);
        Reel ann = reel("ann", 0);
        List<Object> tray = Arrays.<Object>asList(x, bob, y, zed, ann);
        assertEquals(Arrays.<Object>asList(zed, ann, bob, x, y), order(tray, "zed", "ann", "bob"));
        assertEquals("the list it was handed is untouched", Arrays.<Object>asList(x, bob, y, zed, ann), tray);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(StoryPriority.MOVED));
        assertTrue(HookStatus.missing(FamilyNames.STORY_PRIORITY).isEmpty());
    }

    @Test
    public void aWatchedStoryFallsBackToInstagramsPlacement() {
        Reel x = reel("x", 0);
        Reel ann = reel("ann", 1);
        Reel bob = reel("bob", 0);
        Reel zed = reel("zed", 1);
        List<Object> tray = Arrays.<Object>asList(x, ann, bob, zed);
        assertEquals(Arrays.<Object>asList(bob, x, ann, zed), order(tray, "zed", "ann", "bob"));
    }

    @Test
    public void yourStoryAndSpecialEntriesKeepTheirSlots() {
        Reel own = reel("me", StoryTrayOrder.OWN);
        Reel ad = reel("zed", StoryTrayOrder.PINNED);
        Reel x = reel("x", 0);
        Reel ann = reel("ann", 0);
        Reel y = reel("y", 1);
        List<Object> tray = Arrays.<Object>asList(own, x, ad, y, ann);
        List<Object> ordered = order(tray, "me", "zed", "ann");
        assertEquals(Arrays.<Object>asList(own, ann, ad, x, y), ordered);
    }

    @Test
    public void accountsNotInTheTrayAndNothingToMoveChangeNothing() {
        List<Object> tray = Arrays.<Object>asList(reel("x", 0), reel("y", 0));
        assertSame(tray, order(tray, "nobody", "ann"));
        List<Object> already = Arrays.<Object>asList(reel("ann", 0), reel("x", 0));
        assertSame(already, order(already, "ann"));
        assertSame(tray, order(tray));
    }

    @Test
    public void unknownItemsAndNullFieldsStayInPlace() {
        Reel noName = reel(null, 0);
        Reel ann = reel("ann", 0);
        Reel x = reel("x", 0);
        List<Object> tray = Arrays.<Object>asList(null, "not a reel", noName, x, ann);
        assertEquals(Arrays.<Object>asList(null, "not a reel", ann, noName, x), order(tray, "ann"));
    }

    @Test
    public void theNamesAreReadOneToALineWithoutTheAtSignOrCase() {
        assertEquals(Arrays.asList("ann", "bob", "carl"), StoryPriority.accounts("  @Ann \r\n\n@@BOB,\ncarl\nann"));
        assertEquals(Collections.emptyList(), StoryPriority.accounts(" \n @ \n"));
        assertEquals(Collections.emptyList(), StoryPriority.accounts(null));
    }

    /** #641: a story menu's row puts a name on a line of its own at the end, or takes just that one off. */
    @Test
    public void theStoryMenuAddsOrRemovesOneName() {
        assertEquals("zed\nann\nbob\ncarl", StoryPriority.with("zed\nann\nbob\n", "carl"));
        assertEquals("carl", StoryPriority.with(" \n", "carl"));
        assertEquals("carl", StoryPriority.with(null, "carl"));
        assertEquals("on the list already", "zed\n@Ann", StoryPriority.with("zed\n@Ann", "ann"));
        assertEquals("@Zed\nbob", StoryPriority.without("@Zed\r\n@@ANN, bob\nann", "Ann"));
        assertEquals("", StoryPriority.without("ann", "@ann"));
        assertEquals("", StoryPriority.without(null, "ann"));
    }

    @Test
    public void offPausedAnEmptyListAndUnreadySettingsLeaveTheRowAlone() {
        Reel x = reel("x", 0);
        Reel zed = reel("zed", 0);
        List<Object> tray = Arrays.<Object>asList(x, zed);
        assertEquals(Arrays.<Object>asList(zed, x), StoryPriority.order(tray, StoryPriority::accountsWhenOn, READER));
        Settings.STORY_PRIORITY.save(false);
        assertSame(tray, StoryPriority.order(tray, StoryPriority::accountsWhenOn, READER));
        Settings.STORY_PRIORITY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(tray, StoryPriority.order(tray, StoryPriority::accountsWhenOn, READER));
        PauseForTests.resume();
        Settings.STORY_PRIORITY_ACCOUNTS.save("");
        assertSame(tray, StoryPriority.order(tray, StoryPriority::accountsWhenOn, READER));
    }

    @Test
    public void aFailingReaderGivesTheRowBackAndNullsAreHarmless() {
        List<Object> tray = Arrays.<Object>asList(reel("x", 0), reel("ann", 0));
        StoryPriority.Reader broken = new StoryPriority.Reader() {
            @Override
            public int seen(Object reel) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String username(Object reel) {
                return null;
            }
        };
        assertSame(tray, StoryPriority.order(tray, () -> Arrays.asList("ann"), broken));
        assertEquals(null, StoryPriority.order(null, () -> Arrays.asList("ann"), READER));
    }
}
