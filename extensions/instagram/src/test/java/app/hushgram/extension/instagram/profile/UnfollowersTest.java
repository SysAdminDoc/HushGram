/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Recent unfollowers (#208): only your own Followers list loaded to the end is taken, and a list
 * taken lists who's missing from the last one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class UnfollowersTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = "1001";
    private static final BooleanSupplier ON = () -> true;

    /** Instagram's kinds of list as the binder holds them: enum constants, of which only the name is read. */
    enum Kind { FOLLOWERS, FOLLOWING, MUTUAL_FOLLOWERS }

    /** An account as the list holds it. */
    static final class User {
        final String id;
        final String username;

        User(String id, String username) {
            this.id = id;
            this.username = username;
        }
    }

    /** A follow list as the patch reads it, with what each question answers. */
    static final class FakeList implements Unfollowers.Parts {
        Object kind = Kind.FOLLOWERS;
        String owner = ME;
        String viewer = ME;
        Boolean hasMore = false;
        Integer count;
        final Object binder = new Object();
        final Object account = new Object();

        @Override public Object binder(Object list) { return binder; }
        @Override public Object kind(Object binder) { return kind; }
        @Override public String owner(Object binder) { return owner; }
        @Override public String viewer(Object binder) { return viewer; }
        @Override public Boolean hasMore(Object list) { return hasMore; }
        @Override public Object ownerAccount(Object list) { return account; }
        @Override public Integer followerCount(Object user) { return user == account ? count : null; }
        @Override public String id(Object user) { return ((User) user).id; }
        @Override public String username(Object user) { return ((User) user).username; }
    }

    private final List<Map<String, String>> kept = new ArrayList<>();
    private final Unfollowers.Keeper keeper = (viewer, followers, when, generation) -> kept.add(followers);
    private Context context;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Unfollowers.forgetTaken();
        Unfollowers.clear(context);
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Unfollowers.clear(context);
        Settings.TRACK_UNFOLLOWERS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static List<User> users(String... names) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < names.length; i++) users.add(new User(String.valueOf(2000 + i), names[i]));
        return users;
    }

    private boolean seen(List<User> users, FakeList list) {
        return Unfollowers.seen(users, new Object(), list, ON, keeper, 1_000L);
    }

    /** Your own Followers list with nothing more to load and every follower in it is taken, IDs to usernames in order. */
    @Test
    public void yourWholeFollowersListIsTaken() {
        FakeList list = new FakeList();
        list.count = 3;

        assertTrue(seen(users("ana", "bo", "cy"), list));
        assertEquals(1, kept.size());
        assertEquals(Arrays.asList("2000", "2001", "2002"), new ArrayList<>(kept.get(0).keySet()));
        assertEquals("bo", kept.get(0).get("2001"));
    }

    /** Someone else's Followers list, your Following list, and a list still loading are never taken. */
    @Test
    public void onlyYourOwnFollowersListLoadedToTheEndIsTaken() {
        FakeList someoneElses = new FakeList();
        someoneElses.count = 2;
        someoneElses.owner = "1002";
        assertFalse(seen(users("ana", "bo"), someoneElses));

        FakeList following = new FakeList();
        following.count = 2;
        following.kind = Kind.FOLLOWING;
        assertFalse(seen(users("ana", "bo"), following));

        FakeList loading = new FakeList();
        loading.count = 2;
        loading.hasMore = true;
        assertFalse(seen(users("ana", "bo"), loading));

        FakeList unknown = new FakeList();
        unknown.count = 2;
        unknown.hasMore = null;
        assertFalse(seen(users("ana", "bo"), unknown));

        FakeList notSignedIn = new FakeList();
        notSignedIn.count = 2;
        notSignedIn.viewer = null;
        assertFalse(seen(users("ana", "bo"), notSignedIn));
        assertTrue(kept.isEmpty());
    }

    /** A search in the list finds far fewer than your follower count, so it can't look like everyone else left. */
    @Test
    public void aListFarShortOfTheFollowerCountIsNotTaken() {
        FakeList searched = new FakeList();
        searched.count = 100;
        assertFalse(seen(users("ana", "bo", "cy"), searched));

        // On a small account even one missing could be a search, so it's never guessed at.
        FakeList small = new FakeList();
        small.count = 3;
        assertFalse(seen(users("ana", "bo"), small));

        FakeList noCount = new FakeList();
        assertFalse(seen(users("ana", "bo", "cy"), noCount));
        assertTrue(kept.isEmpty());

        assertFalse(Unfollowers.full(95, 100));
        assertFalse(Unfollowers.full(94, 100));
        assertFalse(Unfollowers.full(19, 20));
        assertFalse(Unfollowers.full(18, 20));
        assertTrue(Unfollowers.full(10, 10));
        assertFalse(Unfollowers.full(9, 10));
        assertFalse(Unfollowers.full(1, 3));
        assertFalse(Unfollowers.full(0, 2));
        assertFalse(Unfollowers.full(12, 10));
        assertFalse(Unfollowers.full(-1, 10));
        assertFalse(Unfollowers.full(3, -1));
    }

    @Test
    public void aTransientTerminalPageDoesNotCreateFalseUnfollowers() {
        List<User> all = new ArrayList<>();
        for (int i = 0; i < 1_157; i++) all.add(new User("id" + i, "user" + i));
        FakeList list = new FakeList();
        list.count = all.size();
        Unfollowers.Keeper save = (viewer, followers, when, generation) -> Unfollowers.take(context, viewer, followers, when);
        assertTrue(Unfollowers.seen(all, list, list, ON, save, 100));
        assertFalse(Unfollowers.seen(all.subList(0, 1_150), list, list, ON, save, 200));
        assertFalse(Unfollowers.seen(all, list, list, ON, save, 300));
        assertTrue(Unfollowers.shown(context).left.isEmpty());

        list.count = 1_156;
        assertTrue(Unfollowers.seen(all.subList(0, 1_156), list, list, ON, save, 400));
        assertEquals(1, Unfollowers.shown(context).left.size());
        assertEquals("id1156", Unfollowers.shown(context).left.get(0).id);
    }

    @Test
    public void aTerminalPageMustMatchTheNativeCountExactly() {
        assertFalse(Unfollowers.full(95, 100));
        assertFalse(Unfollowers.full(19, 20));
        assertFalse(Unfollowers.full(12, 10));
        assertTrue(Unfollowers.full(100, 100));
        assertTrue(Unfollowers.full(0, 0));
    }

    @Test
    public void duplicateIdsDoNotMakeAListComplete() {
        FakeList list = new FakeList();
        list.count = 2;
        assertFalse(seen(Arrays.asList(new User("1", "ana"), new User("1", "ana")), list));
        assertTrue(kept.isEmpty());
    }

    @Test
    public void reorderingTheSameFollowersDoesNotTakeAnotherSnapshot() {
        FakeList list = new FakeList();
        list.count = 2;
        assertTrue(seen(users("ana", "bo"), list));
        List<User> reordered = users("ana", "bo");
        java.util.Collections.reverse(reordered);
        assertFalse(seen(reordered, list));
        assertEquals(1, kept.size());
    }

    @Test
    public void aThrowingKeeperLeavesTheSnapshotRetryable() {
        FakeList list = new FakeList();
        list.count = 2;
        assertFalse(Unfollowers.seen(users("ana", "bo"), list, list, ON,
                (viewer, followers, when, generation) -> { throw new IllegalStateException("save refused"); }, 100));
        assertTrue(seen(users("ana", "bo"), list));
        assertEquals(1, kept.size());
    }

    @Test
    public void aMissingContextLeavesTheSnapshotRetryable() throws Exception {
        FakeList list = new FakeList();
        list.count = 2;
        Field field = Utils.class.getDeclaredField("context");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, null);
            assertFalse(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 100));
        } finally {
            field.set(null, previous);
        }
        assertTrue(seen(users("ana", "bo"), list));
    }

    @Test
    public void anOlderFailureCannotForgetANewerSnapshot() {
        FakeList list = new FakeList();
        list.count = 1;
        assertFalse(Unfollowers.seen(users("ana"), list, list, ON, (viewer, followers, when, generation) -> {
            assertTrue(seen(users("bo"), list));
            throw new IllegalStateException("older save refused");
        }, 100));
        assertFalse(seen(users("bo"), list));
        assertEquals(1, kept.size());
    }

    @Test
    public void theLatestQueuedSnapshotWinsWithoutFalseDepartures() throws Exception {
        Settings.TRACK_UNFOLLOWERS.save(true);
        Unfollowers.take(context, ME, followers("2000", "ana", "2001", "bo", "2002", "cy"), 1);
        whileWorkersHeld(() -> {
            FakeList list = new FakeList();
            list.count = 2;
            assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 100));
            list.count = 3;
            assertTrue(Unfollowers.seen(users("ana", "bo", "cy"), list, list, ON, Unfollowers.STORED, 200));
        });
        Unfollowers.Record record = Unfollowers.shown(context);
        assertEquals(200, record.checked);
        assertEquals(3, record.followers.size());
        assertTrue(record.left.isEmpty());
    }

    @Test
    public void clearingTheListInvalidatesQueuedSaves() throws Exception {
        Settings.TRACK_UNFOLLOWERS.save(true);
        whileWorkersHeld(() -> {
            FakeList list = new FakeList();
            list.count = 2;
            assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 100));
            assertTrue(Unfollowers.clear(context));
        });
        assertEquals(0, Unfollowers.shown(context).checked);
        assertFalse(Unfollowers.file(context, Unfollowers.hash(ME)).exists());
    }

    @Test
    public void switchingOffBeforeAQueuedSavePreservesRetry() throws Exception {
        Settings.TRACK_UNFOLLOWERS.save(true);
        FakeList list = new FakeList();
        list.count = 2;
        whileWorkersHeld(() -> {
            assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 100));
            Settings.TRACK_UNFOLLOWERS.save(false);
        });
        assertEquals(0, Unfollowers.shown(context).checked);
        Settings.TRACK_UNFOLLOWERS.save(true);
        assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 200));
        Utils.awaitBackgroundTasksForTests();
        assertEquals(200, Unfollowers.shown(context).checked);
    }

    @Test
    public void queuedSnapshotsKeepTheirOwnAccount() throws Exception {
        Settings.TRACK_UNFOLLOWERS.save(true);
        whileWorkersHeld(() -> {
            FakeList first = new FakeList();
            first.count = 1;
            FakeList second = new FakeList();
            second.viewer = second.owner = "1002";
            second.count = 1;
            assertTrue(Unfollowers.seen(users("ana"), first, first, ON, Unfollowers.STORED, 100));
            assertTrue(Unfollowers.seen(users("bo"), second, second, ON, Unfollowers.STORED, 200));
        });
        assertEquals("ana", Unfollowers.read(Unfollowers.file(context, Unfollowers.hash(ME))).followers.get("2000"));
        assertEquals("bo", Unfollowers.read(Unfollowers.file(context, Unfollowers.hash("1002"))).followers.get("2000"));
    }

    @Test
    public void aWriteFailureLeavesTheSameListRetryable() throws Exception {
        Settings.TRACK_UNFOLLOWERS.save(true);
        File folder = Unfollowers.file(context, "probe").getParentFile();
        assertTrue(folder.createNewFile());
        FakeList list = new FakeList();
        list.count = 2;
        try {
            assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 100));
            Utils.awaitBackgroundTasksForTests();
            assertEquals(0, Unfollowers.shown(context).checked);
        } finally {
            assertTrue(folder.delete());
        }
        assertTrue(Unfollowers.seen(users("ana", "bo"), list, list, ON, Unfollowers.STORED, 200));
        Utils.awaitBackgroundTasksForTests();
        assertEquals(200, Unfollowers.shown(context).checked);
    }

    private void whileWorkersHeld(Runnable body) throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Field field = Utils.class.getDeclaredField("backgroundThreadPool");
        field.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        int previousCoreSize = pool.getCorePoolSize();
        int workers = pool.getMaximumPoolSize();
        pool.setCorePoolSize(workers);
        CountDownLatch started = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < workers; i++) {
                assertTrue(Utils.runOnBackgroundThread(() -> {
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("workers stayed blocked");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            body.run();
        } finally {
            release.countDown();
            try {
                Utils.awaitBackgroundTasksForTests();
            } finally {
                pool.setCorePoolSize(previousCoreSize);
            }
        }
    }

    /** With no followers at all the empty list is whole, so the ones who were there before show as gone. */
    @Test
    public void noFollowersLeftIsAWholeList() {
        FakeList none = new FakeList();
        none.count = 0;
        assertTrue(seen(users(), none));
        assertEquals(1, kept.size());
        assertTrue(kept.get(0).isEmpty());
        assertTrue(Unfollowers.full(0, 0));
    }

    /** Instagram rebuilds the same rows many times; the same list is taken once, and a changed one again. */
    @Test
    public void theSameListIsTakenOnce() {
        FakeList list = new FakeList();
        list.count = 2;
        assertTrue(seen(users("ana", "bo"), list));
        assertFalse(seen(users("ana", "bo"), list));
        list.count = 3;
        assertTrue(seen(users("ana", "bo", "cy"), list));
        assertEquals(2, kept.size());
    }

    /** With the switch off, nothing is read. */
    @Test
    public void offTakesNothing() {
        FakeList list = new FakeList();
        list.count = 2;
        assertFalse(Unfollowers.seen(users("ana", "bo"), new Object(), list, () -> false, keeper, 1L));
        Settings.TRACK_UNFOLLOWERS.save(false);
        assertFalse(Unfollowers.switchedOn());
        Settings.TRACK_UNFOLLOWERS.save(true);
        assertTrue(Unfollowers.switchedOn());
        assertTrue(kept.isEmpty());
    }

    /** A row that can't be told apart makes the whole list untrustworthy. */
    @Test
    public void aRowWithNoIdStopsTheTake() {
        FakeList list = new FakeList();
        list.count = 2;
        List<User> users = users("ana");
        users.add(new User(null, "bo"));
        assertFalse(seen(users, list));
        assertTrue(kept.isEmpty());
    }

    private static Map<String, String> followers(String... idsAndNames) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < idsAndNames.length; i += 2) map.put(idsAndNames[i], idsAndNames[i + 1]);
        return map;
    }

    /**
     * The first list has nothing to be compared with; the next lists who's missing, newest first,
     * a new username isn't an unfollow, and someone who follows you again drops off.
     */
    @Test
    public void aListTakenListsWhoIsMissingFromTheLastOne() {
        Unfollowers.Record first = Unfollowers.compare(Unfollowers.Record.NONE, followers("1", "ana", "2", "bo", "3", "cy"), 100);
        assertTrue(first.left.isEmpty());
        assertEquals(100, first.checked);

        Unfollowers.Record second = Unfollowers.compare(first, followers("1", "ana_new", "3", "cy"), 200);
        assertEquals(1, second.left.size());
        assertEquals("2", second.left.get(0).id);
        assertEquals("bo", second.left.get(0).username);
        assertEquals(200, second.left.get(0).when);

        Unfollowers.Record third = Unfollowers.compare(second, followers("1", "ana_new"), 300);
        assertEquals(Arrays.asList("3", "2"), Arrays.asList(third.left.get(0).id, third.left.get(1).id));
        assertEquals(200, third.left.get(1).when);

        Unfollowers.Record back = Unfollowers.compare(third, followers("1", "ana_new", "2", "bo"), 400);
        assertEquals(1, back.left.size());
        assertEquals("3", back.left.get(0).id);
    }

    @Test
    public void onlyTheNewestAreKept() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < Unfollowers.MAX_KEPT + 30; i++) many.put("id" + i, "user" + i);
        Unfollowers.Record first = Unfollowers.compare(Unfollowers.Record.NONE, many, 1);
        Unfollowers.Record second = Unfollowers.compare(first, followers("x", "x"), 2);
        assertEquals(Unfollowers.MAX_KEPT, second.left.size());
    }

    /** On the phone the list is kept in a file per account, read back for settings, and cleared. */
    @Test
    public void theRecordIsKeptOnThePhoneAndCleared() throws Exception {
        Unfollowers.take(context, ME, followers("1", "ana", "2", "bo"), 100);
        Unfollowers.take(context, ME, followers("1", "ana"), 200);

        File file = Unfollowers.file(context, Unfollowers.hash(ME));
        assertTrue(file.isFile());
        assertFalse("the file isn't named by the ID", file.getName().contains(ME));
        Unfollowers.Record shown = Unfollowers.shown(context);
        assertEquals(200, shown.checked);
        assertEquals(1, shown.left.size());
        assertEquals("bo", shown.left.get(0).username);
        assertEquals(followers("1", "ana"), shown.followers);

        assertTrue(Unfollowers.clear(context));
        assertFalse(file.exists());
        assertEquals(0, Unfollowers.shown(context).checked);
    }

    /** A tab or line break in a username can't split its line in the file. */
    @Test
    public void aStrangeUsernameReadsBackOnItsLine() throws Exception {
        File file = Unfollowers.file(context, "test");
        Unfollowers.write(file, Unfollowers.compare(Unfollowers.Record.NONE, followers("1", "a\tb\nc"), 5));
        Unfollowers.Record read = Unfollowers.read(file);
        assertEquals(1, read.followers.size());
        assertEquals("a b c", read.followers.get("1"));
        assertEquals(5, read.checked);
    }

    /** The hook on the follow list's rebuild hands the list here first, whatever the order switch says. */
    @Test
    public void theRebuildHookLooksFirst() {
        Settings.TRACK_UNFOLLOWERS.save(true);
        Settings.FOLLOWING_NOT_BACK_FIRST.save(false);
        List<User> users = users("ana");
        // The stubs answer nothing as built, so nothing is taken, nothing throws, and Instagram's list comes back untouched.
        assertTrue(users == FollowingList.ordered(users, new Object()));
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, report.contains(Unfollowers.SEEN));
        assertFalse(Unfollowers.file(context, "x").getParentFile().exists());
        Settings.FOLLOWING_NOT_BACK_FIRST.resetToDefault();
    }
}
