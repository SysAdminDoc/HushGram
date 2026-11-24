/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep story viewers (#710): off until switched on, each page of a story's viewers read through the
 * account bridges and put together in Instagram's order, kept for three hundred stories in one file,
 * and set beside the followers list for who viewed, who often does, and who has no view recorded.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryViewersTest {
    private static final long DAY = StoryViewers.DAY;
    private static final long NOW = 400 * DAY;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private HookStatus.Snapshot saved;
    /** The fake users Instagram hands over: each an id and a username. */
    private RuntimeException readThrows;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        saved = HookStatus.snapshotAndClear();
        StoryViewers.forgetForTests();
        StoryViewers.clear(context);
        StoryViewers.reads = new StoryViewers.Reads() {
            @Override public String id(Object user) {
                if (readThrows != null) throw readThrows;
                return ((String[]) user)[0];
            }

            @Override public String username(Object user) {
                return ((String[]) user)[1];
            }

            // A stand-in answer is {media, total, viewers}, a media {id, taken_at}, a viewer {account}
            // and a request its story id.
            @Override public Object media(Object response) {
                return ((Object[]) response)[0];
            }

            @Override public int total(Object response) {
                return (Integer) ((Object[]) response)[1];
            }

            @Override public List<?> viewers(Object response) {
                return (List<?>) ((Object[]) response)[2];
            }

            @Override public Object user(Object viewer) {
                return ((Object[]) viewer)[0];
            }

            @Override public String story(Object callback) {
                return (String) callback;
            }

            @Override public String mediaId(Object media) {
                return ((String[]) media)[0];
            }

            @Override public Long takenAt(Object media) {
                String taken = ((String[]) media)[1];
                return taken == null ? null : Long.valueOf(taken);
            }
        };
    }

    private static Object response(Object media, int total, Object... accounts) {
        List<Object> viewers = new ArrayList<>();
        for (Object account : accounts) viewers.add(account == null ? null : new Object[]{account});
        return new Object[]{media, total, viewers};
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.STORY_VIEWERS.resetToDefault();
        StoryViewers.clear(context);
        StoryViewers.forgetForTests();
        HookStatus.restore(saved);
    }

    private static Object user(String id, String username) {
        return new String[]{id, username};
    }

    private static LinkedHashMap<String, String> viewers(String... idThenName) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int at = 0; at < idThenName.length; at += 2) map.put(idThenName[at], idThenName[at + 1]);
        return map;
    }

    private void keep(String story, long posted, int total, String... idThenName) {
        StoryViewers.keep(context, new StoryViewers.Page(story, posted, total, viewers(idThenName)), NOW);
    }

    private static List<String> names(List<StoryViewers.Person> people) {
        List<String> names = new ArrayList<>();
        for (StoryViewers.Person person : people) names.add(person.username);
        return names;
    }

    @Test public void theSwitchStartsOffAndNothingIsKeptMeanwhile() throws Exception {
        assertFalse(Settings.STORY_VIEWERS.get());
        StoryViewers.received("3001", 100, 1, Collections.singletonList(user("11", "ana")));
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, StoryViewers.count(context));
        assertTrue("nothing was counted while it was off", HookStatus.snapshot().isEmpty());
    }

    @Test public void aPageIsReadThroughTheBridgesAndKeptOffInstagramsThread() throws Exception {
        Settings.STORY_VIEWERS.save(true);
        StoryViewers.received("3001_77", 100, 3, Arrays.asList(user("11", "ana"), null, user("x", "bad"), user("12", " bo ")));
        Utils.awaitBackgroundTasksForTests();
        StoryViewers.Summary kept = StoryViewers.summary(context, 0, Collections.emptyMap());
        assertEquals(1, kept.stories.size());
        StoryViewers.Story story = kept.stories.get(0);
        assertEquals("the owner after the underscore isn't part of the id", "3001", story.id);
        assertEquals("taken_at is in seconds", 100_000L, story.posted);
        assertEquals(3, story.total);
        assertEquals("a user without a numeric id is skipped", Arrays.asList("11", "12"), story.viewers);
        assertEquals(Arrays.asList("ana", "bo"), names(kept.viewers));
        assertFalse("the call was counted", HookStatus.snapshot().isEmpty());
    }

    @Test public void theHookReadsTheAnswersStoryViewersAndCount() throws Exception {
        Settings.STORY_VIEWERS.save(true);
        StoryViewers.listed("9_77", response(new String[]{"3001_77", "100"}, 4, user("11", "ana"), null, user("12", "bo")));
        Utils.awaitBackgroundTasksForTests();
        StoryViewers.Story story = StoryViewers.summary(context, 0, Collections.emptyMap()).stories.get(0);
        assertEquals("the media's id over the request's", "3001", story.id);
        assertEquals(100_000L, story.posted);
        assertEquals(4, story.total);
        assertEquals(Arrays.asList("11", "12"), story.viewers);
    }

    @Test public void withoutTheMediaTheRequestsStoryIdIsUsed() throws Exception {
        Settings.STORY_VIEWERS.save(true);
        StoryViewers.listed("3002_77", response(null, 1, user("11", "ana")));
        StoryViewers.listed("3003", response(new String[]{"reel", null}, 1, user("12", "bo")));
        Utils.awaitBackgroundTasksForTests();
        StoryViewers.Summary kept = StoryViewers.summary(context, 0, Collections.emptyMap());
        List<String> ids = new ArrayList<>();
        for (StoryViewers.Story story : kept.stories) ids.add(story.id);
        Collections.sort(ids);
        assertEquals(Arrays.asList("3002", "3003"), ids);
    }

    @Test public void theHookDoesNothingWhileOffOrWithoutViewers() throws Exception {
        StoryViewers.listed("3001", response(new String[]{"3001", "100"}, 1, user("11", "ana")));
        Settings.STORY_VIEWERS.save(true);
        StoryViewers.listed("3001", response(new String[]{"3001", "100"}, 0));
        StoryViewers.listed("3001", null);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, StoryViewers.count(context));
        assertTrue("nothing was counted", HookStatus.snapshot().isEmpty());
    }

    @Test public void aThrowingBridgeInTheHookIsReported() throws Exception {
        Settings.STORY_VIEWERS.save(true);
        StoryViewers.listed("3001", new Object[]{null, 1, "not a list"});
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, StoryViewers.count(context));
        assertFalse("the throw was reported", HookStatus.snapshot().isEmpty());
    }

    @Test public void aThrowingReadLeavesInstagramAloneAndIsReported() throws Exception {
        Settings.STORY_VIEWERS.save(true);
        readThrows = new IllegalStateException("moved");
        StoryViewers.received("3001", 100, 1, Collections.singletonList(user("11", "ana")));
        Utils.awaitBackgroundTasksForTests();
        assertEquals(0, StoryViewers.count(context));
        assertFalse("the throw was reported", HookStatus.snapshot().isEmpty());
    }

    @Test public void anIdIsItsLeadingDigitsOrNothing() {
        assertEquals("3001", StoryViewers.digits(" 3001_77 "));
        assertEquals("", StoryViewers.digits("story:3001"));
        assertEquals("", StoryViewers.digits(null));
        assertEquals("", StoryViewers.digits("1234567890123456789012345678901"));
        assertNull("no viewer with an id, no page", StoryViewers.read("3001", 0, 1, Collections.singletonList(user("", "ana"))));
        assertNull("no story id, no page", StoryViewers.read("reel", 0, 1, Collections.singletonList(user("11", "ana"))));
    }

    @Test public void pagesOfOneStoryArePutTogetherInInstagramsOrder() {
        keep("3001", NOW - DAY, 4, "11", "ana", "12", "bo");
        keep("3001", NOW - DAY, 4, "12", "bo", "13", "cy");
        keep("3001", NOW - 2 * DAY, 5, "14", "");
        StoryViewers.Story story = StoryViewers.summary(context, 0, Collections.emptyMap()).stories.get(0);
        assertEquals(Arrays.asList("11", "12", "13", "14"), story.viewers);
        assertEquals("the largest count Instagram gave", 5, story.total);
        assertEquals("the earliest posting time it gave", NOW - 2 * DAY, story.posted);
        assertFalse("four of five isn't the whole list", story.complete());
        keep("3001", 0, 0, "15", "di");
        assertTrue(StoryViewers.summary(context, 0, Collections.emptyMap()).stories.get(0).complete());
    }

    @Test public void aPostingTimeAheadOfThePhonesClockIsKeptAsNow() {
        keep("3001", NOW + DAY, 1, "11", "ana");
        assertEquals(NOW, StoryViewers.summary(context, 0, Collections.emptyMap()).stories.get(0).posted);
    }

    @Test public void theSummarySetsTheViewersBesideTheFollowers() {
        keep("1", NOW - 40 * DAY, 2, "11", "ana", "12", "bo");
        keep("2", NOW - 3 * DAY, 2, "11", "ana", "13", "cy");
        keep("3", NOW - 2 * DAY, 3, "11", "ana", "12", "bo");
        Map<String, String> followers = new HashMap<>();
        followers.put("11", "ana");
        followers.put("12", "bo.new");
        followers.put("14", "dee");
        followers.put("15", "eve");

        StoryViewers.Summary all = StoryViewers.summary(context, 0, followers);
        assertEquals("newest first", Arrays.asList("3", "2", "1"), ids(all.stories));
        assertEquals("the most stories first, then by name; a follower's newer username wins",
                Arrays.asList("ana", "bo.new", "cy"), names(all.viewers));
        assertEquals(Arrays.asList(3, 2, 1), views(all.viewers));
        assertEquals("ana's newest is story 3", NOW - 2 * DAY, all.viewers.get(0).last);
        assertEquals(Arrays.asList("dee", "eve"), names(all.unseen));
        assertEquals("two of four followers viewed one", 50, all.reach());
        assertEquals("two or more and at least half", Arrays.asList("ana", "bo.new"), names(all.frequentViewers()));
        assertEquals("story 3's list isn't whole, so nobody is said to have missed it",
                Collections.emptyList(), all.missed(all.stories.get(0), followers));
        assertEquals(Arrays.asList("bo.new", "dee", "eve"), names(all.missed(all.stories.get(1), followers)));

        StoryViewers.Summary week = StoryViewers.summary(context, NOW - 7 * DAY, followers);
        assertEquals(Arrays.asList("3", "2"), ids(week.stories));
        assertEquals(NOW - 7 * DAY, week.from);
        assertEquals(Arrays.asList("ana", "bo.new", "cy"), names(week.viewers));
        assertEquals(-1, StoryViewers.summary(context, 0, Collections.emptyMap()).reach());
    }

    @Test public void pastThreeHundredStoriesTheOnePostedFirstGoes() {
        for (int story = 0; story <= StoryViewers.MAX_STORIES; story++) {
            keep(String.valueOf(1000 + story), NOW - (StoryViewers.MAX_STORIES - story) * 60_000L - (story == 7 ? DAY : 0), 1, "11", "ana");
        }
        List<String> kept = ids(StoryViewers.summary(context, 0, Collections.emptyMap()).stories);
        assertEquals(StoryViewers.MAX_STORIES, kept.size());
        assertFalse("story 7 was posted first", kept.contains("1007"));
        assertTrue(kept.contains("1000"));
    }

    @Test public void theListSurvivesARestartAndClearWipesIt() {
        keep("3001", NOW - DAY, 2, "11", "ana", "12", "bo");
        StoryViewers.forgetForTests();
        StoryViewers.Summary read = StoryViewers.summary(context, 0, Collections.emptyMap());
        assertEquals(1, read.stories.size());
        assertEquals(Arrays.asList("ana", "bo"), names(read.viewers));
        assertEquals("kept since the first page", NOW, read.since);
        assertTrue(StoryViewers.clear(context));
        StoryViewers.forgetForTests();
        assertEquals(0, StoryViewers.count(context));
        assertEquals(0, StoryViewers.summary(context, 0, Collections.emptyMap()).since);
    }

    /**
     * Search viewers (#833): the list on screen is of the kept story whose viewers hold most of its
     * usernames, in any case; on a tie the one Instagram listed last, then the newest; with no match
     * the one listed last, and nothing when that isn't kept either.
     */
    @Test public void aViewerListOnScreenIsOfTheStoryHoldingMostOfItsNames() {
        assertNull("nothing kept", StoryViewers.storyListing(context, Arrays.asList("ana")));
        keep("101", NOW - 2 * DAY, 2, "11", "ana", "12", "bo");
        keep("102", NOW - DAY, 2, "11", "ana", "13", "cy");
        keep("103", NOW - 3 * DAY, 1, "14", "dee");
        assertEquals("only 101 has bo", "101", StoryViewers.storyListing(context, Arrays.asList("ana", "bo")));
        assertEquals("a tie, neither listed last, the newer", "102", StoryViewers.storyListing(context, Arrays.asList("ANA ")));
        keep("101", 0, 0, "12", "bo");
        assertEquals("a tie goes to the one listed last", "101", StoryViewers.storyListing(context, Arrays.asList("ana")));
        assertEquals("no name kept, the one listed last", "101", StoryViewers.storyListing(context, Arrays.asList("zed")));
        assertEquals("101", StoryViewers.storyListing(context, Collections.emptyList()));
        assertEquals("most wins over listed last", "103", StoryViewers.storyListing(context, Arrays.asList("dee")));

        StoryViewers.forgetForTests();
        assertNull("a new process has listed nothing yet", StoryViewers.storyListing(context, Arrays.asList("zed")));
        assertEquals("read back from the file", "102", StoryViewers.storyListing(context, Arrays.asList("ana")));
        keep("104", NOW, 1, "15", "eve");
        assertTrue(StoryViewers.clear(context));
        assertNull("clear forgets the one listed last too", StoryViewers.storyListing(context, Arrays.asList("eve")));
    }

    private static List<String> ids(List<StoryViewers.Story> stories) {
        List<String> ids = new ArrayList<>();
        for (StoryViewers.Story story : stories) ids.add(story.id);
        return ids;
    }

    private static List<Integer> views(List<StoryViewers.Person> people) {
        List<Integer> views = new ArrayList<>();
        for (StoryViewers.Person person : people) views.add(person.views);
        return views;
    }
}
