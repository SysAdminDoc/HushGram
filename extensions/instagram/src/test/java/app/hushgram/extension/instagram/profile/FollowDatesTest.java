/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Follow dates in your lists (#847): your own Followers and Following lists, taken whole, date each
 * account new to them from then on, and their rows say since when.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowDatesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = "1001";
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    /** Instagram's kinds of list as the binder holds them: enum constants, of which only the name is read. */
    enum Kind { FOLLOWERS, FOLLOWING, FOLLOWING_SIMPLIFIED, MUTUAL_FOLLOWERS }

    static final class User {
        final String id;

        User(String id) {
            this.id = id;
        }
    }

    /** A follow list as the patch reads it, with what each question answers. */
    static final class FakeList implements FollowDates.Parts {
        Object kind = Kind.FOLLOWERS;
        String owner = ME;
        String viewer = ME;
        Boolean hasMore = false;
        Integer followers;
        Integer following;
        final Object binder = new Object();
        final Object account = new Object();

        @Override public Object binder(Object list) { return binder; }
        @Override public Object kind(Object binder) { return kind; }
        @Override public String owner(Object binder) { return owner; }
        @Override public String viewer(Object binder) { return viewer; }
        @Override public Boolean hasMore(Object list) { return hasMore; }
        @Override public Object ownerAccount(Object list) { return account; }
        @Override public Integer followerCount(Object user) { return user == account ? followers : null; }
        @Override public Integer followingCount(Object user) { return user == account ? following : null; }
        @Override public String id(Object user) { return ((User) user).id; }
    }

    /** What a keeper was handed. */
    private static final class Kept {
        final String viewer;
        final String kind;
        final List<String> ids;
        final boolean complete;

        Kept(String viewer, String kind, List<String> ids, boolean complete) {
            this.viewer = viewer;
            this.kind = kind;
            this.ids = ids;
            this.complete = complete;
        }
    }

    private final List<Kept> kept = new ArrayList<>();
    private final FollowDates.Keeper keeper =
            (viewer, kind, ids, complete, when, generation) -> kept.add(new Kept(viewer, kind, ids, complete));
    private Context context;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Unfollowers.later = (task, delay) -> task.run();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Unfollowers.clear(context);
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Unfollowers.later = Utils::runOnMainThreadDelayed;
        Unfollowers.clear(context);
        Settings.FOLLOW_DATES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static List<User> users(String... ids) {
        List<User> users = new ArrayList<>();
        for (String id : ids) users.add(new User(id));
        return users;
    }

    private boolean seen(List<User> users, FakeList list) {
        return FollowDates.seen(users, new Object(), list, ON, keeper, 1_000L);
    }

    private static Map<String, Long> since(Object... pairs) {
        Map<String, Long> since = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) since.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        return since;
    }

    /** The first list only marks who was there, undated, and after that anyone new gets the day the list was taken. */
    @Test
    public void theFirstListMarksWhoWasThereAndLaterOnesDateWhoIsNew() {
        FollowDates.Dates first = FollowDates.update(FollowDates.Dates.NONE, Arrays.asList("a", "b"), true, 100);
        assertEquals(100, first.started);
        assertEquals(since("a", 0, "b", 0), first.since);

        FollowDates.Dates second = FollowDates.update(first, Arrays.asList("a", "b", "c"), true, 200);
        assertEquals(100, second.started);
        assertEquals(since("a", 0, "b", 0, "c", 200), second.since);

        FollowDates.Dates third = FollowDates.update(second, Arrays.asList("c", "a", "b"), true, 300);
        assertEquals("a date stays once given", since("c", 200, "a", 0, "b", 0), third.since);
    }

    /**
     * A complete list forgets whoever left it, so a follow that comes back is dated again. One a few
     * short of the count keeps them, since Instagram may only have left them out.
     */
    @Test
    public void aCompleteListForgetsWhoLeftAndAShortOneKeepsThem() {
        FollowDates.Dates before = new FollowDates.Dates(100, since("a", 0, "b", 150));

        FollowDates.Dates shortList = FollowDates.update(before, Arrays.asList("a"), false, 200);
        assertEquals(since("a", 0, "b", 150), shortList.since);

        FollowDates.Dates complete = FollowDates.update(before, Arrays.asList("a"), true, 200);
        assertEquals(since("a", 0), complete.since);

        FollowDates.Dates back = FollowDates.update(complete, Arrays.asList("a", "b"), true, 300);
        assertEquals(since("a", 0, "b", 300), back.since);
    }

    /** Your own Followers list in full is taken by follower count, your own Following list by following count. */
    @Test
    public void yourOwnWholeListsAreTakenByTheirCounts() {
        FakeList followers = new FakeList();
        followers.followers = 3;
        followers.following = 99;
        assertTrue(seen(users("1", "2", "3"), followers));

        FakeList following = new FakeList();
        following.kind = Kind.FOLLOWING_SIMPLIFIED;
        following.followers = 99;
        following.following = 2;
        assertTrue(seen(users("7", "8"), following));

        assertEquals(2, kept.size());
        assertEquals(ME, kept.get(0).viewer);
        assertEquals(Unfollowers.FOLLOWERS_KIND, kept.get(0).kind);
        assertEquals(Arrays.asList("1", "2", "3"), kept.get(0).ids);
        assertTrue(kept.get(0).complete);
        assertEquals("FOLLOWING", kept.get(1).kind);
        assertEquals(Arrays.asList("7", "8"), kept.get(1).ids);
    }

    /** A list that may not be whole, isn't yours, or can't be told apart row by row is left alone. */
    @Test
    public void anythingButYourOwnWholeListIsLeftAlone() {
        FakeList list = new FakeList();
        list.followers = 3;

        list.hasMore = true;
        assertFalse("more to load", seen(users("1", "2", "3"), list));
        list.hasMore = null;
        assertFalse("not known whether there's more", seen(users("1", "2", "3"), list));
        list.hasMore = false;

        list.owner = "2002";
        assertFalse("someone else's list", seen(users("1", "2", "3"), list));
        list.owner = ME;

        list.kind = Kind.MUTUAL_FOLLOWERS;
        assertFalse("not a Followers or Following list", seen(users("1", "2", "3"), list));
        list.kind = Kind.FOLLOWERS;

        assertFalse("one short of three", seen(users("1", "2"), list));
        assertFalse("the same account twice", seen(users("1", "2", "2"), list));
        assertFalse("a row with no ID", seen(users("1", "2", null), list));
        list.followers = null;
        assertFalse("no count", seen(users("1", "2", "3"), list));
        list.followers = 3;

        assertFalse("switched off", FollowDates.seen(users("1", "2", "3"), new Object(), list, OFF, keeper, 1_000L));
        assertTrue(kept.isEmpty());
        assertTrue(HookStatus.missing().toString(), HookStatus.missing().isEmpty());
    }

    /** Instagram builds the same list again and again as it scrolls: one with nothing new is taken once. */
    @Test
    public void theSameListIsTakenOnce() {
        FakeList list = new FakeList();
        list.followers = 2;

        assertTrue(seen(users("1", "2"), list));
        assertFalse(seen(users("1", "2"), list));
        assertTrue(seen(users("2", "1"), list));
        assertEquals(2, kept.size());
    }

    /**
     * On the phone: the list is taken once it settles, written to its file, and read back after a
     * restart, and a row of your own list then says since when its account is there.
     */
    @Test
    public void aTakenListIsKeptAndItsRowsSaySince() throws Exception {
        Settings.FOLLOW_DATES.save(true);
        FakeList list = new FakeList();
        list.followers = 2;
        long now = System.currentTimeMillis();

        assertTrue(FollowDates.seen(users("1", "2"), new Object(), list, ON, FollowDates.STORED, now - 5_000));
        Utils.awaitBackgroundTasksForTests();
        list.followers = 3;
        assertTrue(FollowDates.seen(users("1", "2", "3"), new Object(), list, ON, FollowDates.STORED, now));
        Utils.awaitBackgroundTasksForTests();

        File file = FollowDates.file(context, Unfollowers.hash(ME), Unfollowers.FOLLOWERS_KIND);
        assertTrue(file.getPath(), file.isFile());
        assertFalse("the ID itself never names the file", file.getName().contains(ME));
        FollowDates.forget();

        String day = FollowDates.day(context, now, now);
        String label = FollowDates.label(Kind.FOLLOWERS, ME, ME, new User("3"), list, ON, now);
        assertTrue(label, label != null && label.startsWith("Since ") && label.contains(day));
        assertNull("there before the first list", FollowDates.label(Kind.FOLLOWERS, ME, ME, new User("1"), list, ON, now));
        assertNull("someone else's list", FollowDates.label(Kind.FOLLOWERS, "2002", ME, new User("3"), list, ON, now));
        assertNull("your Following list keeps its own dates", FollowDates.label(Kind.FOLLOWING, ME, ME, new User("3"), list, ON, now));
        assertNull("switched off", FollowDates.label(Kind.FOLLOWERS, ME, ME, new User("3"), list, OFF, now));

        assertTrue(Unfollowers.clear(context));
        assertFalse("Clear unfollower list clears the dates too", file.exists());
        assertNull(FollowDates.label(Kind.FOLLOWERS, ME, ME, new User("3"), list, ON, now));
    }

    /** With the switch off by the time a list settles, nothing is written. */
    @Test
    public void aListThatSettlesWithTheSwitchOffIsNotKept() throws Exception {
        FakeList list = new FakeList();
        list.followers = 1;

        assertTrue(FollowDates.seen(users("1"), new Object(), list, ON, FollowDates.STORED, 1_000));
        Utils.awaitBackgroundTasksForTests();

        assertFalse(FollowDates.file(context, Unfollowers.hash(ME), Unfollowers.FOLLOWERS_KIND).exists());
    }

    /** A file reads back as written, a line cut short is left out, and a missing file is no dates. */
    @Test
    public void theFileReadsBackAsWritten() throws Exception {
        File file = new File(new File(context.getFilesDir(), Unfollowers.DIRECTORY), "test.followers-since.tsv");
        assertEquals(0, FollowDates.read(file).started);
        assertTrue(FollowDates.read(file).since.isEmpty());

        FollowDates.write(file, new FollowDates.Dates(100, since("a", 0, "b", 250)));
        FollowDates.Dates read = FollowDates.read(file);
        assertEquals(100, read.started);
        assertEquals(since("a", 0, "b", 250), read.since);

        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) + "s\tc\t12x\ns\td\n";
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertEquals(since("a", 0, "b", 250), FollowDates.read(file).since);
        assertFalse(new File(file.getParentFile(), file.getName() + ".tmp").exists());
    }

    /** The day leaves the year out when it's this year's and shows it otherwise. */
    @Test
    public void theDayShowsTheYearOnlyWhenItIsNotThisYears() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.NOVEMBER, 24, 12, 0, 0);
        Calendar thisYear = Calendar.getInstance();
        thisYear.set(2026, Calendar.MARCH, 3, 12, 0, 0);
        Calendar lastYear = Calendar.getInstance();
        lastYear.set(2025, Calendar.MARCH, 3, 12, 0, 0);

        String shown = FollowDates.day(context, thisYear.getTimeInMillis(), now.getTimeInMillis());
        assertTrue(shown, shown.contains("Mar") && shown.contains("3") && !shown.contains("2026"));
        String older = FollowDates.day(context, lastYear.getTimeInMillis(), now.getTimeInMillis());
        assertTrue(older, older.contains("2025"));
    }

    /** Only Followers and Following lists have dates, by Instagram's enum constant's name. */
    @Test
    public void onlyFollowersAndFollowingListsHaveDates() {
        assertEquals(Unfollowers.FOLLOWERS_KIND, FollowDates.kindOf(Kind.FOLLOWERS));
        assertEquals("FOLLOWING", FollowDates.kindOf(Kind.FOLLOWING));
        assertEquals("FOLLOWING", FollowDates.kindOf(Kind.FOLLOWING_SIMPLIFIED));
        assertNull(FollowDates.kindOf(Kind.MUTUAL_FOLLOWERS));
        assertNull(FollowDates.kindOf("FOLLOWERS"));
        assertNull(FollowDates.kindOf(null));
    }
}
