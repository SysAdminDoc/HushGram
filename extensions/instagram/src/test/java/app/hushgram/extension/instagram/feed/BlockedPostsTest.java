/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide posts when you block someone (#861): a block that went through takes that account out of every Home list. */
@RunWith(RobolectricTestRunner.class)
public class BlockedPostsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** What the removal bridge was handed, in order. */
    private final List<String> removed = new ArrayList<>();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_BLOCKED_POSTS.save(true);
        HookStatus.clear();
        BlockedPosts.userIdForTests = account -> account instanceof Account ? ((Account) account).id : null;
        BlockedPosts.removalForTests = (list, id) -> removed.add(list + " " + id);
    }

    @After
    public void restore() {
        Settings.HIDE_BLOCKED_POSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        BlockedPosts.userIdForTests = null;
        BlockedPosts.removalForTests = null;
        BlockedPosts.forgetFeedsForTests();
    }

    /** Each Home list Instagram made gets the blocked account's id, on the main thread, and the take is counted. */
    @Test
    public void aBlockTakesTheAccountOutOfEveryHomeList() {
        Object home = new Named("home");
        Object other = new Named("other");
        BlockedPosts.feed(home);
        BlockedPosts.feed(other);
        BlockedPosts.feed(home);
        assertEquals("each list once", 2, BlockedPosts.feedsForTests());
        BlockedPosts.blocked(new Account("17841400000000001"));
        shadowOf(Looper.getMainLooper()).idle();
        Collections.sort(removed);
        assertEquals(Arrays.asList("home 17841400000000001", "other 17841400000000001"), removed);
        assertTrue(report(), report().contains(BlockedPosts.REMOVED + " 1"));
    }

    /** The switch starts off, and off, paused or with no settings yet a block leaves Home as Instagram has it. */
    @Test
    public void itStartsOffAndLeavesHomeAloneWhileOff() {
        BlockedPosts.feed(new Named("home"));
        Settings.HIDE_BLOCKED_POSTS.resetToDefault();
        assertFalse("off to start", Settings.HIDE_BLOCKED_POSTS.get());
        BlockedPosts.blocked(new Account("1"));

        Settings.HIDE_BLOCKED_POSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        BlockedPosts.blocked(new Account("2"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> BlockedPosts.blocked(new Account("3")));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.emptyList(), removed);
        assertFalse(Settings.HIDE_BLOCKED_POSTS.rebootApp);
    }

    /** No account, an account with no id, or no Home list yet hands the removal nothing. */
    @Test
    public void nothingToTakeOutHandsTheRemovalNothing() {
        BlockedPosts.blocked(new Account("4"));
        BlockedPosts.feed(null);
        BlockedPosts.blocked(new Account("5"));
        BlockedPosts.feed(new Named("home"));
        BlockedPosts.blocked(null);
        BlockedPosts.blocked(new Account(""));
        BlockedPosts.blocked(new Object());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.emptyList(), removed);
    }

    /** A removal that throws stays in the extension, counted as a failure, and the next list still gets the id. */
    @Test
    public void aRemovalThatThrowsStaysInTheExtension() {
        BlockedPosts.removalForTests = (list, id) -> {
            if (list.toString().equals("broken")) throw new IllegalStateException("not on Home any more");
            removed.add(list + " " + id);
        };
        BlockedPosts.feed(new Named("broken"));
        BlockedPosts.feed(new Named("home"));
        BlockedPosts.blocked(new Account("6"));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList("home 6"), removed);
        assertTrue(report(), report().contains("blocked posts"));
    }

    private static String report() {
        StringBuilder all = new StringBuilder();
        for (String line : HookStatus.report()) all.append(line).append('\n');
        return all.toString();
    }

    private static final class Named {
        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class Account {
        final String id;

        Account(String id) {
            this.id = id;
        }
    }
}
