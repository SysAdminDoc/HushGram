/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide posts from this account in a post's menu: when the row shows, what a tap adds, and what the report counts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HideAccountRowTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's Media: who posted it. */
    private static final class Post {
        final String author;

        Post(String author) {
            this.author = author;
        }
    }

    /** Stands in for the menu builder's state, holding its post. */
    private static final class Menu {
        final Post post;

        Menu(Post post) {
            this.post = post;
        }
    }

    private static final Object ROW = new Object();
    private final Function<Object, Object> postOf = menu -> ((Menu) menu).post;
    private final Function<Object, String> authorOf = post -> ((Post) post).author;
    private final List<CharSequence> labels = new ArrayList<>();
    private int adds;

    private final HideAccountRow.Adder adder = (menu, rows, option, label) -> {
        adds++;
        labels.add(label);
        @SuppressWarnings("unchecked")
        ArrayList<Object> list = (ArrayList<Object>) rows;
        list.add(option);
    };

    @Before
    public void start() {
        clear();
        HideAccountRow.inBuildForTests = true;
        HiddenAccounts.homeSession("1", id -> (String) id);
    }

    @After
    public void restore() {
        clear();
    }

    private void clear() {
        Settings.HIDDEN_ACCOUNTS.resetToDefault();
        Settings.FEED_ACCOUNT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenAccounts.resetForTests();
        HideAccountRow.resetForTests();
        ShadowToast.reset();
        HookStatus.clear();
    }

    private ArrayList<Object> offer(String author) {
        ArrayList<Object> rows = new ArrayList<>();
        HideAccountRow.offer(new Menu(new Post(author)), rows, postOf, authorOf, adder, () -> ROW);
        return rows;
    }

    private static String report() {
        return String.join("\n", HookStatus.report(null));
    }

    @Test
    public void someoneElsesPostGetsTheRowOnce() {
        ArrayList<Object> rows = offer("nasa");

        assertEquals(Collections.singletonList(ROW), rows);
        assertEquals(1, adds);
        assertEquals("Hide posts from this account", labels.get(0).toString());
        assertTrue(report(), report().contains(HideAccountRow.OFFERED + " 1"));
    }

    @Test
    public void noRowWhenHiddenAccountsIsNotInTheBuild() {
        HideAccountRow.inBuildForTests = false;

        assertTrue(offer("nasa").isEmpty());
        assertEquals(0, adds);
    }

    @Test
    public void noRowWhilePausedOrBeforeSettingsAreReady() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertTrue(offer("nasa").isEmpty());
        assertEquals(0, adds);
        assertFalse(HideAccountRow.on());
    }

    @Test
    public void noRowForAPostWhoseAuthorCantBeReadOrIsAlreadyHidden() {
        assertTrue(offer(null).isEmpty());
        assertTrue(offer("not a name").isEmpty());
        HiddenAccounts.add("nasa");
        assertTrue("already on the list", offer("NASA").isEmpty());
        assertEquals(0, adds);
        assertEquals(1, offer("esa").size());
    }

    @Test
    public void aMenuWithNoPostOrNoRowOptionIsLeftAlone() {
        ArrayList<Object> rows = new ArrayList<>();
        HideAccountRow.offer(new Menu(null), rows, postOf, authorOf, adder, () -> ROW);
        HideAccountRow.offer(new Menu(new Post("nasa")), rows, postOf, authorOf, adder, () -> null);
        HideAccountRow.offer(null, rows, postOf, authorOf, adder, () -> ROW);
        HideAccountRow.offer(new Menu(new Post("nasa")), null, postOf, authorOf, adder, () -> ROW);

        assertTrue(rows.isEmpty());
        assertEquals(0, adds);
    }

    @Test
    public void aThrowingReadLeavesTheMenuAloneAndIsReported() {
        ArrayList<Object> rows = new ArrayList<>();
        HideAccountRow.offer(new Menu(new Post("nasa")), rows, postOf, post -> {
            throw new IllegalStateException("boom");
        }, adder, () -> ROW);

        assertTrue(rows.isEmpty());
        assertTrue(report(), report().contains(FamilyNames.FEED_SUGGESTIONS));
    }

    @Test
    public void asBuiltTheStubsDoNothing() {
        ArrayList<Object> rows = new ArrayList<>();

        HideAccountRow.offer(new Menu(new Post("nasa")), rows);
        HideAccountRow.addRow(null, rows, ROW, "x");

        assertTrue(rows.isEmpty());
        assertNull(HideAccountRow.menuMedia(new Menu(null)));
        assertNull(HideAccountRow.option());
        assertNull(HiddenAccounts.authorOfPost(new Post("nasa")));
    }

    @Test
    public void theShortMenuKeepsTheRowOnTheEndOnce() {
        Object first = new Object();
        List<Object> kept = Arrays.asList(first, new Object());
        Object made = HideAccountRow.option(name -> ROW);
        assertSame(ROW, made);

        List<?> allowed = HideAccountRow.allow(kept);

        assertEquals(3, allowed.size());
        assertSame(ROW, allowed.get(2));
        assertSame(first, allowed.get(0));
        assertSame("a list that has it comes back as it came", allowed, HideAccountRow.allow(allowed));
    }

    @Test
    public void theShortMenuIsAsItWasOffPausedOrWithoutTheOption() {
        List<Object> kept = Arrays.asList(new Object(), new Object());
        assertSame("no option could be made", kept, HideAccountRow.allow(kept));

        HideAccountRow.option(name -> ROW);
        HideAccountRow.inBuildForTests = false;
        assertSame(kept, HideAccountRow.allow(kept));
        assertNull(HideAccountRow.allow(null));
    }

    @Test
    public void aTapAddsTheAuthorToTheSignedInAccountsListWithAToast() {
        assertEquals("nasa", HideAccountRow.hide(new Post("NASA"), authorOf));

        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());
        assertEquals("Posts from @nasa are hidden from Home and Following", ShadowToast.getTextOfLatestToast());
        assertTrue(report(), report().contains(HideAccountRow.HID + " 1"));
    }

    @Test
    public void theListBelongsToTheCurrentAccount() {
        HideAccountRow.hide(new Post("nasa"), authorOf);
        HiddenAccounts.homeSession("2", id -> (String) id);

        assertTrue(HiddenAccounts.saved().isEmpty());
        assertEquals("esa", HideAccountRow.hide(new Post("esa"), authorOf));
        assertEquals(Collections.singletonList("esa"), HiddenAccounts.saved());
        HiddenAccounts.homeSession("1", id -> (String) id);
        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());
    }

    @Test
    public void tappingForAnAccountAlreadyOnTheListAddsItNoSecondTime() {
        HideAccountRow.hide(new Post("nasa"), authorOf);
        HideAccountRow.hide(new Post("nasa"), authorOf);

        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());
        assertEquals(1, Settings.HIDDEN_ACCOUNTS.savedValue().split("\n").length);
    }

    @Test
    public void aTapThatCantNameTheAuthorSaysSoAndChangesNothing() {
        assertNull(HideAccountRow.hide(new Post(null), authorOf));

        assertTrue(HiddenAccounts.saved().isEmpty());
        assertEquals("Couldn't hide this account", ShadowToast.getTextOfLatestToast());
        assertFalse(report(), report().contains(HideAccountRow.HID));
    }

    @Test
    public void aTapWhilePausedOrWithoutAPostDoesNothing() {
        assertNull(HideAccountRow.hide(null, authorOf));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertNull(HideAccountRow.hide(new Post("nasa"), authorOf));
        assertNull(ShadowToast.getLatestToast());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(HiddenAccounts.saved().isEmpty());
    }
}
