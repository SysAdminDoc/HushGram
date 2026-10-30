/*
 * Copyright 2026 HushPinterest contributors
 * Taken from HushPinterest's
 * extensions/pinterest/src/test/java/app/hushpinterest/extension/shared/settings/preference/ReopenSettingsAfterRestartTest.java
 * (https://github.com/SysAdminDoc/HushPinterest, commit 44fb93384a319d8e5d324f51b416efdc3d3147e6).
 * Modified for HushGram (Instagram), 2026.
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushgram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.Setting;

/**
 * The marker Restart now leaves for the next start: written before the restart, taken once,
 * cleared before anything opens, and only good for {@link ReopenSettingsAfterRestart#MAX_AGE_MILLIS}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReopenSettingsAfterRestartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private SharedPreferences store;

    @Before
    public void emptyStore() {
        store = RuntimeEnvironment.getApplication().getSharedPreferences("reopen-settings-test", Context.MODE_PRIVATE);
        store.edit().clear().commit();
    }

    @Test
    public void aFreshMarkerComesBackOnceAndIsGone() {
        assertTrue(ReopenSettingsAfterRestart.remember(store, "hushgram_category_page_feed", 10_000));
        assertEquals("hushgram_category_page_feed", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        assertMarkerGone(store);
        assertNull("a marker was taken twice", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        // The first page is a page too: an empty key opens the screen, it doesn't drop the marker.
        ReopenSettingsAfterRestart.remember(store, "", 5);
        assertEquals("", ReopenSettingsAfterRestart.takeFresh(store, 6));
    }

    @Test
    public void anOldMarkerIsDroppedAndCleared() {
        ReopenSettingsAfterRestart.remember(store, "Feed", 1_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 1_000 + ReopenSettingsAfterRestart.MAX_AGE_MILLIS));
        assertMarkerGone(store);
        // Written before the phone restarted, it reads as from the future, and is no good either.
        ReopenSettingsAfterRestart.remember(store, "Feed", 50_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2_000));
        assertMarkerGone(store);
        // Half a marker, or one of the wrong type, is cleared too.
        store.edit().putLong(ReopenSettingsAfterRestart.TIME_KEY, 1).commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone(store);
        store.edit().putInt(ReopenSettingsAfterRestart.PAGE_KEY, 3).putString(ReopenSettingsAfterRestart.TIME_KEY, "x").commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone(store);
        assertNull("no marker opens nothing", ReopenSettingsAfterRestart.takeFresh(store, 2));
    }

    @Test
    public void theMarkerIsClearedBeforeTheScreenOpens() {
        ReopenSettingsAfterRestart.remember(store, "Pause and diagnostics", 100);
        List<String> opened = new ArrayList<>();
        assertTrue(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> {
            assertMarkerGone(store);
            opened.add(page);
        }));
        assertEquals(Collections.singletonList("Pause and diagnostics"), opened);

        // A screen that fails to open isn't tried again on the next start.
        ReopenSettingsAfterRestart.remember(store, "Feed", 100);
        try {
            ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> { throw new IllegalStateException("no activity"); });
            fail("the opener's failure was swallowed");
        } catch (IllegalStateException expected) {
            assertMarkerGone(store);
        }
        assertFalse(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> fail("opened " + page)));
    }

    @Test
    public void theRestartSideWritesToTheSettingsStoreAndCanTakeItBack() {
        SharedPreferences settings = Setting.preferences.preferences;
        assertTrue(ReopenSettingsAfterRestart.remember("hushgram_category_page_privacy"));
        assertTrue("committed before the process ends", settings.contains(ReopenSettingsAfterRestart.PAGE_KEY));
        List<String> opened = new ArrayList<>();
        assertTrue(ReopenSettingsAfterRestart.reopenIfPending(opened::add));
        assertEquals(Collections.singletonList("hushgram_category_page_privacy"), opened);
        assertMarkerGone(settings);

        // A restart that didn't happen leaves nothing for the next start.
        ReopenSettingsAfterRestart.remember("");
        ReopenSettingsAfterRestart.forget();
        assertMarkerGone(settings);
        assertNull(ReopenSettingsAfterRestart.takeFresh(settings, SystemClock.elapsedRealtime()));
    }

    private static void assertMarkerGone(SharedPreferences store) {
        assertFalse(store.contains(ReopenSettingsAfterRestart.PAGE_KEY));
        assertFalse(store.contains(ReopenSettingsAfterRestart.TIME_KEY));
    }
}
