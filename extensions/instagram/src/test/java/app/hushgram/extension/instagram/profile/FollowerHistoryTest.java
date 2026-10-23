/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.profile.FollowerHistory.Event;
import app.hushgram.extension.instagram.profile.FollowerHistory.Kind;
import app.hushgram.extension.instagram.profile.FollowerHistory.Log;
import app.hushgram.extension.instagram.profile.FollowerHistory.Update;
import app.hushgram.extension.shared.SettingsContextRule;

/** Follower history: a dated log of who left, came back and joined, from the lists Recent unfollowers takes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowerHistoryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Context context;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Unfollowers.clear(context);
    }

    @After
    public void restore() {
        Unfollowers.clear(context);
    }

    /** IDs to usernames, in pairs. */
    private static Map<String, String> list(String... pairs) {
        Map<String, String> list = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) list.put(pairs[i], pairs[i + 1]);
        return list;
    }

    private static Map<String, Boolean> follow(String... ids) {
        Map<String, Boolean> following = new HashMap<>();
        for (String id : ids) following.put(id, true);
        return following;
    }

    /** The log after each list in turn, taken at 100, 200, 300 and so on, each counting as many as it holds. */
    private static Log after(List<Map<String, String>> lists, List<Map<String, Boolean>> follows) {
        Log log = Log.NONE;
        Map<String, String> before = Collections.emptyMap();
        long checked = 0;
        for (int i = 0; i < lists.size(); i++) {
            long when = (i + 1) * 100L;
            log = FollowerHistory.update(log, before, checked, lists.get(i), follows.get(i), lists.get(i).size(), when).log;
            before = lists.get(i);
            checked = when;
        }
        return log;
    }

    /** The first list only sets the starting point: nobody is logged as new. */
    @Test
    public void theFirstListLogsNothing() {
        Update first = FollowerHistory.update(Log.NONE, Collections.emptyMap(), 0, list("1", "ana", "2", "bo"),
                follow("2"), 2, 100);
        assertTrue(first.change.first);
        assertEquals(0, first.log.events.size());
        assertEquals(100, first.log.checked);
        assertEquals(2, first.log.followers);
        assertTrue(first.log.youFollow.contains("2"));
    }

    /** Who left is logged with the two checks they left between, newest first, and who joined as new. */
    @Test
    public void leaversAndJoinersAreLoggedBetweenChecks() {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "ana", "2", "bo", "3", "cy"));
        lists.add(list("1", "ana", "3", "cy", "4", "di"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        follows.add(follow("2"));
        follows.add(follow("4"));
        Log log = after(lists, follows);
        assertEquals(2, log.events.size());
        Event left = log.events.get(0);
        assertEquals(Kind.LEFT, left.kind);
        assertEquals("bo", left.username);
        assertEquals(200, left.when);
        assertEquals(100, left.since);
        assertEquals(Boolean.TRUE, left.youFollow);
        Event joined = log.events.get(1);
        assertEquals(Kind.NEW, joined.kind);
        assertEquals("di", joined.username);
        assertEquals(Boolean.TRUE, joined.youFollow);
    }

    /** Someone who left and follows again is logged as back, not new, and the unfollow stays in the log. */
    @Test
    public void aLeaverWhoReturnsIsBack() {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "ana", "2", "bo"));
        lists.add(list("1", "ana"));
        lists.add(list("1", "ana", "2", "bo_again"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        for (int i = 0; i < 3; i++) follows.add(follow("1"));
        Log log = after(lists, follows);
        assertEquals(2, log.events.size());
        assertEquals(Kind.BACK, log.events.get(0).kind);
        assertEquals("bo_again", log.events.get(0).username);
        assertEquals(Kind.LEFT, log.events.get(1).kind);
        // The rows said who you follow, and bo wasn't one.
        assertEquals(Boolean.FALSE, log.events.get(1).youFollow);
    }

    /** Rows that said nothing about who you follow leave it unknown, not "you didn't follow them". */
    @Test
    public void unsaidFollowsStayUnknown() {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "ana", "2", "bo"));
        lists.add(list("1", "ana"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        follows.add(follow());
        follows.add(follow());
        assertEquals(null, after(lists, follows).events.get(0).youFollow);
    }

    /**
     * A list that came up short against the profile's count can miss a row Instagram skipped. When the
     * account is in the next list again, its unfollow is taken off rather than logged as back.
     */
    @Test
    public void anUnfollowFromAShortListIsUndoneWhenTheyReappear() {
        Log log = FollowerHistory.update(Log.NONE, Collections.emptyMap(), 0, list("1", "a", "2", "b", "3", "c"),
                follow(), 3, 100).log;
        // 2 of a profile counting 3: b looks gone.
        Update short_ = FollowerHistory.update(log, list("1", "a", "2", "b", "3", "c"), 100, list("1", "a", "3", "c"),
                follow(), 3, 200);
        assertTrue(short_.log.partial);
        assertEquals(1, short_.log.count(Kind.LEFT));
        Update next = FollowerHistory.update(short_.log, list("1", "a", "3", "c"), 200, list("1", "a", "2", "b", "3", "c"),
                follow(), 3, 300);
        assertEquals(1, next.change.undone);
        assertEquals(0, next.log.events.size());
    }

    /** A whole list doesn't undo anything: someone back after it is really back. */
    @Test
    public void aWholeListsUnfollowStands() {
        Log log = FollowerHistory.update(Log.NONE, Collections.emptyMap(), 0, list("1", "a", "2", "b"), follow(), 2, 100).log;
        Update whole = FollowerHistory.update(log, list("1", "a", "2", "b"), 100, list("1", "a"), follow(), 1, 200);
        Update next = FollowerHistory.update(whole.log, list("1", "a"), 200, list("1", "a", "2", "b"), follow(), 2, 300);
        assertEquals(0, next.change.undone);
        assertEquals(Kind.BACK, next.log.events.get(0).kind);
    }

    /** Only the unfollows after the history was last opened count as new to you. */
    @Test
    public void unseenCountsOnlyLaterUnfollows() {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "a", "2", "b", "3", "c"));
        lists.add(list("1", "a", "3", "c"));
        lists.add(list("1", "a"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        for (int i = 0; i < 3; i++) follows.add(follow());
        Log log = after(lists, follows);
        assertEquals(2, log.unseenLeft());
        assertEquals(1, log.seenAt(250).unseenLeft());
        assertEquals(0, log.seenAt(300).unseenLeft());
    }

    /** The log is cut to its newest changes. */
    @Test
    public void theLogKeepsItsNewest() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < FollowerHistory.MAX_EVENTS + 10; i++) many.put(String.valueOf(i), "u" + i);
        Log log = FollowerHistory.update(Log.NONE, Collections.emptyMap(), 0, many, follow(), many.size(), 100).log;
        Update emptied = FollowerHistory.update(log, many, 100, list("x", "x"), follow(), 1, 200);
        assertEquals(FollowerHistory.MAX_EVENTS, emptied.log.events.size());
    }

    /** What's written reads back the same, with tabs and line breaks in a name kept off the line. */
    @Test
    public void aWrittenLogReadsBack() throws Exception {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "a\tb", "2", "c\nd"));
        lists.add(list("1", "a\tb"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        follows.add(follow("2"));
        follows.add(follow());
        Log log = after(lists, follows).seenAt(150);
        File file = FollowerHistory.file(context, "test");
        FollowerHistory.write(file, log);
        Log read = FollowerHistory.read(file);
        assertEquals(log.checked, read.checked);
        assertEquals(log.previous, read.previous);
        assertEquals(150, read.seen);
        assertEquals(1, read.events.size());
        assertEquals("c d", read.events.get(0).username);
        assertEquals(Boolean.TRUE, read.events.get(0).youFollow);
        assertEquals(100, read.events.get(0).since);
    }

    /** Recent unfollowers' list from before the history began becomes its first changes. */
    @Test
    public void recentUnfollowersCarryOver() {
        Unfollowers.take(context, "1001", list("1", "a", "2", "b"), 100);
        Unfollowers.take(context, "1001", list("1", "a"), 200);
        Log shown = FollowerHistory.shown(context);
        assertEquals(1, shown.count(Kind.LEFT));
        assertEquals("b", shown.events.get(0).username);
    }

    /** CSV rows name the change, quote what needs it and leave unknowns blank. */
    @Test
    public void theCsvQuotesWhatItMust() {
        List<Map<String, String>> lists = new ArrayList<>();
        lists.add(list("1", "a", "2", "b,c"));
        lists.add(list("1", "a"));
        List<Map<String, Boolean>> follows = new ArrayList<>();
        follows.add(follow("1"));
        follows.add(follow("1"));
        String csv = FollowerHistory.csv(after(lists, follows));
        assertTrue(csv, csv.startsWith("change,username,id,found,previous check,you followed them\n"));
        assertTrue(csv, csv.contains("unfollowed,\"b,c\",2,"));
        assertTrue(csv, csv.trim().endsWith(",no"));
    }
}
