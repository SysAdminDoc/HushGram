/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Sent follow requests (#690): a follow button showing Requested puts its account on the list,
 * newest first, or brings it up to date; Following takes it off, and so does Follow, but only from
 * the full account model. Off, paused or with anything missing, nothing changes. Each signed-in
 * account keeps its own, in one file that outlasts a restart, up to a thousand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SentRequestsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final Object SESSION = new Object();
    private static final Object MODEL = new Object();
    private static final String REQUESTED = "FollowStatusRequested";
    private static final String FOLLOWING = "FollowStatusFollowing";
    private static final String FOLLOW = "FollowStatusNotFollowing";

    /** Instagram's status enum, whose constants write themselves under a shortened name. */
    private enum Status {
        FOLLOWSTATUSREQUESTED, FOLLOWSTATUSFOLLOWING, FOLLOWSTATUSNOTFOLLOWING, FOLLOWSTATUSUNKNOWN;

        @Override public String toString() {
            return "A0" + ordinal();
        }
    }

    private final Fake reads = new Fake();
    private final List<Runnable> queued = new ArrayList<>();
    private File file;
    private SentRequests.Worker realWorker;
    private SentRequests.Fetch realFetch;

    /** What the account on the button shows, and whether its tree is the full model. */
    private static final class Fake implements SentRequests.Reads {
        String owner = "me";
        String id = "1";
        String username = "amy";
        String name = "Amy Pond";
        boolean user = true;
        String picture = "https://cdn.example/v/amy_n.jpg";
        boolean fail;

        @Override public String owner(Object session) {
            if (fail) throw new IllegalStateException("a read broke");
            return owner;
        }

        @Override public Object tree(Object model) {
            return model;
        }

        @Override public String field(Object model, int field) {
            if (field == SentRequests.ID) return id;
            if (field == SentRequests.USERNAME) return username;
            if (field == SentRequests.FULL_NAME) return name;
            return null;
        }

        @Override public boolean user(Object tree) {
            return user;
        }

        @Override public String picture(Object user) {
            return picture;
        }
    }

    @Before public void setUp() {
        file = new File(new File(folder.getRoot(), SentRequests.DIRECTORY), SentRequests.FILE);
        realWorker = SentRequests.worker;
        realFetch = SentRequests.fetch;
        SentRequests.storage = () -> file;
        SentRequests.worker = task -> {
            task.run();
            return true;
        };
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        HiddenAccounts.resetForTests();
        SentRequests.forgetForTests();
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        SentRequests.forgetForTests();
        SentRequests.storage = SentRequests.STORED;
        SentRequests.worker = realWorker;
        SentRequests.fetch = realFetch;
        Settings.SENT_FOLLOW_REQUESTS.resetToDefault();
        Settings.FEED_ACCOUNT.resetToDefault();
        HiddenAccounts.resetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private boolean seen(Object status) {
        return SentRequests.seen(SESSION, MODEL, status, reads, () -> true, 1_000L);
    }

    private boolean seen(Object status, long now) {
        return SentRequests.seen(SESSION, MODEL, status, reads, () -> true, now);
    }

    private static List<String> usernames(List<SentRequests.Request> requests) {
        List<String> names = new ArrayList<>();
        for (SentRequests.Request each : requests) names.add(each.username);
        return names;
    }

    @Test public void theSwitchStartsOff() {
        assertFalse(Settings.SENT_FOLLOW_REQUESTS.get());
        assertFalse(SentRequests.switchedOn());
        Settings.SENT_FOLLOW_REQUESTS.save(true);
        assertTrue(SentRequests.switchedOn());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, nothing is kept", SentRequests.switchedOn());
    }

    @Test public void aRequestedButtonPutsItsAccountOnTheListNewestFirst() {
        assertTrue(seen(REQUESTED, 1_000L));
        reads.id = "2";
        reads.username = "zed";
        reads.name = "";
        reads.picture = "";
        assertTrue(seen(REQUESTED, 2_000L));

        List<SentRequests.Request> all = SentRequests.all("me");
        assertEquals(Arrays.asList("zed", "amy"), usernames(all));
        SentRequests.Request amy = all.get(1);
        assertEquals("me", amy.owner);
        assertEquals("1", amy.id);
        assertEquals("Amy Pond", amy.name);
        assertEquals("https://cdn.example/v/amy_n.jpg", amy.url);
        assertEquals(1_000L, amy.time);
        assertEquals("the account the page shows is the one the button was seen for", "me", SentRequests.shownOwner());
    }

    @Test public void theEnumsOwnNameCountsToo() {
        assertTrue(seen(Status.FOLLOWSTATUSREQUESTED));
        assertEquals(1, SentRequests.all("me").size());
        assertTrue(seen(Status.FOLLOWSTATUSFOLLOWING));
        assertTrue(SentRequests.all("me").isEmpty());
        assertFalse("another status changes nothing", seen(Status.FOLLOWSTATUSUNKNOWN));
    }

    @Test public void seenAgainItKeepsItsPlaceAndWhenItWasFirstSeen() {
        assertTrue(seen(REQUESTED, 1_000L));
        reads.id = "2";
        reads.username = "zed";
        assertTrue(seen(REQUESTED, 2_000L));
        reads.id = "1";
        reads.username = "amy";
        assertFalse("nothing new, nothing written", seen(REQUESTED, 3_000L));

        reads.username = "amy.pond";
        reads.picture = "";
        assertTrue(seen(REQUESTED, 4_000L));
        List<SentRequests.Request> all = SentRequests.all("me");
        assertEquals(Arrays.asList("zed", "amy.pond"), usernames(all));
        assertEquals(1_000L, all.get(1).time);
        assertEquals("a row without the picture keeps the one seen before", "https://cdn.example/v/amy_n.jpg", all.get(1).url);

        reads.user = false;
        reads.picture = "https://cdn.example/v/other_n.jpg";
        reads.name = "Amy";
        assertTrue(seen(REQUESTED, 5_000L));
        assertEquals("a picture is only read from the full model", "https://cdn.example/v/amy_n.jpg",
                SentRequests.all("me").get(1).url);
    }

    @Test public void followingTakesItOffAndFollowOnlyFromTheFullModel() {
        assertTrue(seen(REQUESTED));
        assertTrue(seen(FOLLOWING));
        assertTrue("taken, it's gone", SentRequests.all("me").isEmpty());

        assertTrue(seen(REQUESTED));
        reads.user = false;
        assertFalse("a row built from an older answer could miss a request sent since", seen(FOLLOW));
        assertEquals(1, SentRequests.all("me").size());
        reads.user = true;
        assertTrue(seen(FOLLOW));
        assertTrue("cancelled or turned down, it's gone", SentRequests.all("me").isEmpty());
        assertFalse("an account not on the list changes nothing", seen(FOLLOW));
    }

    @Test public void offPausedOrMissingAnythingChangesNothing() {
        assertFalse(SentRequests.seen(SESSION, MODEL, REQUESTED, reads, () -> false, 1_000L));
        assertFalse(SentRequests.seen(null, MODEL, REQUESTED, reads, () -> true, 1_000L));
        assertFalse(SentRequests.seen(SESSION, null, REQUESTED, reads, () -> true, 1_000L));
        assertFalse(SentRequests.seen(SESSION, MODEL, null, reads, () -> true, 1_000L));
        assertFalse(seen("FollowStatusUnknown"));
        reads.owner = "";
        assertFalse("no signed-in account", seen(REQUESTED));
        reads.owner = "me";
        reads.id = null;
        assertFalse("no account ID", seen(REQUESTED));
        reads.id = "1";
        reads.username = "";
        assertFalse("no username", seen(REQUESTED));
        assertTrue(SentRequests.all(null).isEmpty());

        // The real hook, with the switch off and then unpatched.
        SentRequests.seen(SESSION, MODEL, REQUESTED);
        Settings.SENT_FOLLOW_REQUESTS.save(true);
        SentRequests.seen(SESSION, MODEL, REQUESTED);
        assertTrue(SentRequests.all(null).isEmpty());
    }

    @Test public void aReadThatThrowsNeverReachesInstagram() {
        reads.fail = true;
        assertFalse(seen(REQUESTED));
        assertTrue(SentRequests.all(null).isEmpty());
    }

    @Test public void eachAccountKeepsItsOwnRequests() {
        assertTrue(seen(REQUESTED));
        reads.owner = "other";
        reads.id = "9";
        reads.username = "kim";
        assertTrue(seen(REQUESTED));
        assertEquals(Arrays.asList("amy"), usernames(SentRequests.all("me")));
        assertEquals(Arrays.asList("kim"), usernames(SentRequests.all("other")));
        assertEquals(Arrays.asList("kim", "amy"), usernames(SentRequests.all(null)));

        reads.owner = "me";
        reads.id = "1";
        assertTrue(seen(FOLLOWING));
        assertEquals("another account's request stays", Arrays.asList("kim"), usernames(SentRequests.all(null)));
    }

    @Test public void theFileKeepsTheListAcrossARestart() throws Exception {
        reads.name = "Amy\tPond";
        assertTrue(seen(REQUESTED, 1_000L));
        assertTrue(file.isFile());
        SentRequests.forgetForTests();
        List<SentRequests.Request> back = SentRequests.all("me");
        assertEquals(1, back.size());
        assertEquals("a tab can't split a line", "Amy Pond", back.get(0).name);
        assertEquals(1_000L, back.get(0).time);

        Files.write(file.toPath(), ("r\tme\t1\tamy\t\t\t5\n" + "r\tme\t2\tzed\n" + "x\tme\t3\tkim\t\t\t5\n"
                + "r\tme\t4\tbo\t\t\tsoon\n" + "r\tme\t\tnoid\t\t\t5\n" + "r\tme\t5\tcy\tCy\thttps://cdn.example/c.jpg\t7\n")
                .getBytes(StandardCharsets.UTF_8));
        SentRequests.forgetForTests();
        assertEquals("a line cut short or not a request is left out", Arrays.asList("amy", "cy"),
                usernames(SentRequests.all("me")));

        // A change seen before the file was read waits for it, then both are kept.
        SentRequests.forgetForTests();
        SentRequests.worker = task -> {
            queued.add(task);
            return true;
        };
        reads.id = "6";
        reads.username = "dee";
        assertTrue(seen(REQUESTED));
        assertEquals(1, queued.size());
        queued.remove(0).run();
        while (!queued.isEmpty()) queued.remove(0).run();
        SentRequests.forgetForTests();
        assertEquals(Arrays.asList("dee", "amy", "cy"), usernames(SentRequests.all("me")));
    }

    @Test public void withNoWorkerTheChangeIsCountedNotLost() {
        SentRequests.worker = task -> false;
        assertTrue(seen(REQUESTED));
        assertFalse(file.exists());
        SentRequests.worker = task -> {
            task.run();
            return true;
        };
        assertEquals("it's applied once the file can be read", Arrays.asList("amy"), usernames(SentRequests.all("me")));
    }

    @Test public void theListKeepsTheNewestThousand() {
        assertTrue(seen(REQUESTED, 0L));
        SentRequests.worker = task -> {
            queued.add(task);
            return true;
        };
        for (int at = 1; at <= SentRequests.MAX; at++) {
            reads.id = "id" + at;
            reads.username = "user" + at;
            assertTrue(seen(REQUESTED, at));
        }
        assertEquals("one write for them all", 1, queued.size());
        queued.remove(0).run();
        List<SentRequests.Request> all = SentRequests.all(null);
        assertEquals(SentRequests.MAX, all.size());
        assertEquals("user" + SentRequests.MAX, all.get(0).username);
        assertFalse("the oldest went", usernames(all).contains("amy"));
        SentRequests.forgetForTests();
        assertEquals("and the file has as many", SentRequests.MAX, SentRequests.all(null).size());
    }

    @Test public void searchGoesByUsernameOrNameInAnyCase() {
        assertTrue(seen(REQUESTED));
        reads.id = "2";
        reads.username = "zed";
        reads.name = "";
        assertTrue(seen(REQUESTED));
        List<SentRequests.Request> all = SentRequests.all("me");
        assertEquals(2, SentRequests.matching(all, "  ").size());
        assertEquals(2, SentRequests.matching(all, null).size());
        assertEquals(Arrays.asList("amy"), usernames(SentRequests.matching(all, "@AMY")));
        assertEquals(Arrays.asList("amy"), usernames(SentRequests.matching(all, "pond")));
        assertTrue(SentRequests.matching(all, "nobody").isEmpty());
    }

    @Test public void removeAndClearForgetItHereOnly() {
        assertTrue(seen(REQUESTED));
        reads.id = "2";
        reads.username = "zed";
        assertTrue(seen(REQUESTED));
        SentRequests.remove(SentRequests.all("me").get(0));
        assertEquals(Arrays.asList("amy"), usernames(SentRequests.all("me")));
        SentRequests.forgetForTests();
        assertEquals("the file too", Arrays.asList("amy"), usernames(SentRequests.all("me")));

        assertTrue(SentRequests.clear());
        assertFalse(file.exists());
        assertFalse(file.getParentFile().exists());
        assertTrue(SentRequests.all(null).isEmpty());
        assertTrue("a request seen after still counts", seen(REQUESTED));
        assertEquals(1, SentRequests.all(null).size());
    }

    @Test public void aPictureIsFetchedOnlyWhenThereIsOne() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger sizes = new AtomicInteger();
        SentRequests.fetch = (application, url, size) -> {
            sizes.set(size);
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        };
        assertTrue(seen(REQUESTED));
        SentRequests.Request amy = SentRequests.all("me").get(0);
        assertNotNull(SentRequests.picture(context, amy, 10));
        assertEquals("never smaller than a row's picture", SentRequests.PICTURE_PX, sizes.get());

        reads.id = "2";
        reads.username = "zed";
        reads.picture = "";
        assertTrue(seen(REQUESTED));
        sizes.set(0);
        assertNull(SentRequests.picture(context, SentRequests.all("me").get(0), 10));
        assertEquals("no address, no fetch", 0, sizes.get());

        SentRequests.fetch = (application, url, size) -> {
            throw new IllegalStateException("offline");
        };
        assertNull(SentRequests.picture(context, amy, 10));
    }

    @Test public void withNoButtonSeenThePageShowsHomesAccountElseEveryone() {
        assertNull(SentRequests.shownOwner());
        Settings.FEED_ACCOUNT.save("home");
        HiddenAccounts.resetForTests();
        assertEquals("home", SentRequests.shownOwner());
        assertTrue(seen(REQUESTED));
        assertEquals("a button seen since wins", "me", SentRequests.shownOwner());
    }
}
