/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep online history (#590): off until switched on, each update read through the description
 * Instagram's presence model writes of itself, a change noted only when someone comes online or
 * goes offline, at most fifty changes for each of three hundred people, kept in one file and
 * cleared on request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OnlineHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private HookStatus.Snapshot saved;
    /** What the fake presence model writes for each update. */
    private final Map<Object, String> described = new HashMap<>();
    /** The usernames the fake cache of accounts has, by id. */
    private final Map<String, String> names = new HashMap<>();
    private RuntimeException presenceThrows;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        saved = HookStatus.snapshotAndClear();
        OnlineHistory.forgetForTests();
        OnlineHistory.clear(context);
        OnlineHistory.reads = new OnlineHistory.Reads() {
            @Override public String presence(Object state) {
                if (presenceThrows != null) throw presenceThrows;
                return described.get(state);
            }

            @Override public String username(Object session, String id) {
                return names.get(id);
            }
        };
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.ONLINE_HISTORY.resetToDefault();
        OnlineHistory.clear(context);
        OnlineHistory.forgetForTests();
        HookStatus.restore(saved);
    }

    private static OnlineHistory.Seen seen(String id, boolean active, long last) {
        return new OnlineHistory.Seen(id, "", active, last);
    }

    private void note(long now, OnlineHistory.Seen... seen) {
        OnlineHistory.record(context, Arrays.asList(seen), now);
    }

    /** The changes kept, newest first, as "id on|off time". */
    private List<String> changes() {
        List<String> found = new ArrayList<>();
        for (OnlineHistory.Event event : OnlineHistory.events(context)) {
            found.add(event.id + (event.online ? " on " : " off ") + event.time);
        }
        return found;
    }

    private Object state(String json) {
        Object state = new Object();
        described.put(state, json);
        return state;
    }

    @Test public void theSwitchStartsOffAndNothingIsNotedMeanwhile() throws Exception {
        assertFalse(Settings.ONLINE_HISTORY.get());
        OnlineHistory.received(null, Collections.singletonMap("1", state("{\"user_id\":\"1\",\"is_active\":true}")));
        Utils.awaitBackgroundTasksForTests();
        assertEquals(Collections.emptyList(), changes());
        assertTrue("nothing was counted while it was off", HookStatus.snapshot().isEmpty());
    }

    /** The id comes from the update, or the map's key when the update has none; anything unreadable is left out. */
    @Test public void anUpdateIsReadThroughInstagramsOwnDescription() throws Exception {
        Settings.ONLINE_HISTORY.save(true);
        names.put("11", " ann ");
        Map<Object, Object> updates = new LinkedHashMap<>();
        updates.put("11", state("{\"user_id\":\"11\",\"is_active\":true,\"lastActivityAtMs\":0,\"appTitle\":\"x\"}"));
        updates.put("22", state("{\"is_active\":false,\"lastActivityAtMs\":5000}"));
        updates.put("33", state("not json"));
        updates.put("44", state("{\"user_id\":\"44\"}"));
        updates.put("55", null);
        updates.put("66", new Object());
        long before = System.currentTimeMillis();
        OnlineHistory.received(new Object(), updates);
        Utils.awaitBackgroundTasksForTests();
        long after = System.currentTimeMillis();

        List<OnlineHistory.Event> events = OnlineHistory.events(context);
        assertEquals(2, events.size());
        OnlineHistory.Event online = events.get(0);
        assertEquals("11", online.id);
        assertEquals("ann", online.username);
        assertTrue(online.online);
        assertTrue("noted when it came in", online.time >= before && online.time <= after);
        OnlineHistory.Event offline = events.get(1);
        assertEquals("the map's key stands in for a missing id", "22", offline.id);
        assertEquals("", offline.username);
        assertFalse(offline.online);
        assertEquals("offline since their last activity", 5000L, offline.time);
        assertFalse("the call was counted", HookStatus.snapshot().isEmpty());
    }

    @Test public void aThrowingReadLeavesInstagramAloneAndIsReported() throws Exception {
        Settings.ONLINE_HISTORY.save(true);
        presenceThrows = new IllegalStateException("broken");
        OnlineHistory.received(null, Collections.singletonMap("1", new Object()));
        OnlineHistory.received(null, null);
        OnlineHistory.received(null, Collections.emptyMap());
        Utils.awaitBackgroundTasksForTests();
        assertEquals(Collections.emptyList(), changes());
        assertFalse("the throw was reported", HookStatus.snapshot().isEmpty());
    }

    /** Online now, or offline since their last activity when Instagram gives it, and nothing when it doesn't. */
    @Test public void aFirstSightingIsNotedAsItIs() {
        note(1000, seen("1", true, 0), seen("2", false, 400), seen("3", false, 0));
        assertEquals(Arrays.asList("1 on 1000", "2 off 400"), changes());
    }

    @Test public void comingOnlineAndGoingOfflineAreEachNotedOnce() {
        note(1000, seen("1", false, 500));
        note(2000, seen("1", true, 1900));
        note(3000, seen("1", true, 2900));
        note(4000, seen("1", false, 3500));
        note(5000, seen("1", false, 3500));
        assertEquals(Arrays.asList("1 off 3500", "1 on 2000", "1 off 500"), changes());
    }

    /** Someone who came and went between two updates shows a later last activity, and that's noted. */
    @Test public void aVisitBetweenTwoUpdatesIsNotedByItsLastActivity() {
        note(1000, seen("1", false, 500));
        note(9000, seen("1", false, 7000));
        assertEquals(Arrays.asList("1 off 7000", "1 off 500"), changes());
    }

    @Test public void aLastActivityAheadOfThePhonesClockIsNotedAsNow() {
        note(1000, seen("1", true, 0));
        note(2000, seen("1", false, 99_999));
        assertEquals(Arrays.asList("1 off 2000", "1 on 1000"), changes());
    }

    @Test public void aNewUsernameNamesEveryChange() {
        OnlineHistoryRows.note(context, "1", "ann", true, 0, 1000);
        OnlineHistoryRows.note(context, "1", "ann.b", true, 0, 2000);
        OnlineHistoryRows.note(context, "1", "", false, 2500, 3000);
        List<OnlineHistory.Event> events = OnlineHistory.events(context);
        assertEquals(2, events.size());
        for (OnlineHistory.Event event : events) assertEquals("ann.b", event.username);
    }

    @Test public void onlyTheNewestFiftyChangesEachAreKept() {
        for (int at = 0; at < 60; at++) {
            long now = (at + 1) * 1000L;
            boolean active = at % 2 == 0;
            note(now, seen("1", active, active ? 0 : now - 1));
        }
        List<String> kept = changes();
        assertEquals(OnlineHistory.MAX_EVENTS, kept.size());
        assertEquals("1 off 59999", kept.get(0));
        assertEquals("1 on 11000", kept.get(kept.size() - 1));
    }

    @Test public void pastThreeHundredPeopleTheOneChangedLeastRecentlyGoes() {
        OnlineHistory.Seen[] crowd = new OnlineHistory.Seen[OnlineHistory.MAX_PEOPLE];
        for (int at = 0; at < crowd.length; at++) crowd[at] = seen("p" + at, true, 0);
        note(1000, crowd);
        note(2000, seen("p0", false, 1500));
        note(3000, seen("new", true, 0));
        Set<String> ids = new HashSet<>();
        for (OnlineHistory.Event event : OnlineHistory.events(context)) ids.add(event.id);
        assertEquals(OnlineHistory.MAX_PEOPLE, ids.size());
        assertTrue("changed again, so kept", ids.contains("p0"));
        assertTrue(ids.contains("new"));
        assertFalse("changed least recently, so gone", ids.contains("p1"));
    }

    @Test public void theListSurvivesARestartAndClearWipesIt() {
        OnlineHistoryRows.note(context, "1", "ann", true, 0, 1000);
        OnlineHistoryRows.note(context, "1", "", false, 1500, 2000);
        File file = new File(new File(context.getFilesDir(), "hushgram-online"), "history.json");
        assertTrue(file.isFile());

        OnlineHistoryRows.forget();
        assertEquals(Arrays.asList("1 off 1500", "1 on 1000"), changes());
        assertEquals("ann", OnlineHistory.events(context).get(0).username);
        // Read back offline, so their next update online is a change.
        note(3000, seen("1", true, 0));
        assertEquals("1 on 3000", changes().get(0));

        assertTrue(OnlineHistory.clear(context));
        assertFalse(file.exists());
        assertEquals(Collections.emptyList(), changes());
        OnlineHistoryRows.forget();
        assertEquals("and the file went with it", Collections.emptyList(), changes());
    }
}
