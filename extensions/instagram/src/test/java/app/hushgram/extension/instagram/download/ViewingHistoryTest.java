/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

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
import java.util.List;

import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * The viewing history (#228): off until switched on, newest first, no repeats within a minute,
 * about 500 rows, kept in one file on the phone, and cleared on request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ViewingHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ViewingHistory.forgetForTests();
        ViewingHistory.clear(context);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.VIEWING_HISTORY.resetToDefault();
        ViewingHistory.clear(context);
        ViewingHistory.forgetForTests();
    }

    @Test public void itIsOffUntilSwitchedOnAndRecordsNothingMeanwhile() {
        assertFalse(Settings.VIEWING_HISTORY.get());
        assertFalse(ViewingHistory.on());
        ViewingHistory.profile(new Object());
        ViewingHistory.post(new Object());
        ViewingHistory.reel(new Object());
        ViewingHistory.story(new Object());
        assertEquals(0, ViewingHistory.count(context));
        Settings.VIEWING_HISTORY.save(true);
        assertTrue(ViewingHistory.on());
        Settings.VIEWING_HISTORY.save(false);
        assertFalse(ViewingHistory.on());
    }

    @Test public void rowsComeNewestFirstAndTheSameThingWithinAMinuteIsOneRow() {
        ViewingHistory.add(context, Kind.POST, "1_9", "ann", 1_000L);
        ViewingHistory.add(context, Kind.REEL, "2_9", "bob", 2_000L);
        ViewingHistory.add(context, Kind.REEL, "2_9", "bob", 3_000L);
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(2, rows.size());
        assertEquals("2_9", rows.get(0).id);
        assertEquals(Kind.REEL, rows.get(0).kind);
        assertEquals("1_9", rows.get(1).id);
    }

    @Test public void theSameThingSeenMuchLaterMovesUpInsteadOfRepeating() {
        ViewingHistory.add(context, Kind.STORY, "5_1", "cy", 1_000L);
        ViewingHistory.add(context, Kind.PROFILE, "dee", "dee", 2_000L);
        ViewingHistory.add(context, Kind.STORY, "5_1", "cy", 2_000L + 5 * 60_000L);
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(2, rows.size());
        assertEquals("5_1", rows.get(0).id);
        assertEquals("dee", rows.get(1).id);
    }

    @Test public void thePostAndTheReelWithTheSameIdAreDifferentRows() {
        ViewingHistory.add(context, Kind.POST, "7_7", "eve", 1_000L);
        ViewingHistory.add(context, Kind.REEL, "7_7", "eve", 1_500L);
        assertEquals(2, ViewingHistory.count(context));
    }

    @Test public void onlyTheNewestFiveHundredAreKept() {
        for (int i = 0; i < ViewingHistory.MAX_ENTRIES + 25; i++) {
            ViewingHistory.add(context, Kind.POST, i + "_1", "ann", 1_000L * (i + 1));
        }
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(ViewingHistory.MAX_ENTRIES, rows.size());
        assertEquals((ViewingHistory.MAX_ENTRIES + 24) + "_1", rows.get(0).id);
        assertEquals("25_1", rows.get(rows.size() - 1).id);
    }

    @Test public void theListSurvivesAProcessRestartAndClearWipesItAndTheFile() throws Exception {
        ViewingHistory.add(context, Kind.PROFILE, "fay", "fay", 1_000L);
        ViewingHistory.add(context, Kind.POST, "3_3", "gus\twith tab", 2_000L);
        Utils.awaitBackgroundTasksForTests();
        File file = new File(new File(context.getFilesDir(), "hushgram-viewing"), "history.tsv");
        assertTrue(file.isFile());

        ViewingHistory.forgetForTests();
        List<ViewingHistory.Row> back = ViewingHistory.entries(context);
        assertEquals(2, back.size());
        assertEquals("3_3", back.get(0).id);
        assertEquals("gus with tab", back.get(0).owner);

        assertTrue(ViewingHistory.clear(context));
        assertEquals(0, ViewingHistory.count(context));
        assertFalse(file.exists());
        ViewingHistory.forgetForTests();
        assertEquals(0, ViewingHistory.count(context));
    }
}
