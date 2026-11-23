/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which reels Hide reels you've seen keeps on this phone, and which ones the Reels tab leaves out (#830). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SeenReelsTest {
    /** A Reels item carrying its post's media id, as the patch-filled stubs read Instagram's item. */
    static final class Clip {
        final String id;

        Clip(String id) {
            this.id = id;
        }
    }

    private static final String REELS_TAB = "clips_viewer_clips_tab";
    private static final String PROFILE = "clips_viewer_profile";
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };
    private static final Function<Object, String> IDS = item -> item instanceof Clip ? ((Clip) item).id : null;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private SharedPreferences file;

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
        file = RuntimeEnvironment.getApplication().getSharedPreferences("seen_reels_test", Context.MODE_PRIVATE);
        file.edit().clear().commit();
        SeenReels.use(file);
    }

    @After
    public void restore() {
        Settings.HIDE_SEEN_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
        file.edit().clear().commit();
        SeenReels.use(null);
    }

    private static Clip clip(String id) {
        return new Clip(id);
    }

    private static List<Object> items(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** The key drops the author's id Instagram puts after an underscore, so the record and the post meet. */
    @Test
    public void aReelIsKeptByItsMediaId() {
        assertEquals("3512345678901234567", SeenReels.key("3512345678901234567_123456789"));
        assertEquals("3512345678901234567", SeenReels.key("3512345678901234567"));
        assertNull(SeenReels.key(null));
        assertNull(SeenReels.key(" "));
        assertNull(SeenReels.key("_42"));
        assertNull(SeenReels.key("35 12"));
    }

    /** With the switch on a reel reached goes in the file, once, and comes back after the app starts again. */
    @Test
    public void aReelReachedIsKeptOnThePhone() {
        SeenReels.watched("111_9", ON);
        SeenReels.watched("222", ON);
        SeenReels.watched("111", ON);

        assertEquals(2, SeenReels.size());
        assertEquals("222 111", file.getString(SeenReels.KEY, null));
        SeenReels.use(file);
        assertEquals(2, SeenReels.size());
    }

    /** Off, paused or with no id, nothing is kept; a switch that throws keeps nothing and is reported. */
    @Test
    public void offNothingIsKept() {
        SeenReels.watched("111", OFF);
        Settings.HIDE_SEEN_REELS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SeenReels.watched("222");
        PauseForTests.resume();
        SeenReels.watched(null, ON);
        SeenReels.watched("", ON);
        assertEquals(0, SeenReels.size());

        SeenReels.watched("333", THROWS);
        assertEquals(0, SeenReels.size());
        String missing = HookStatus.missing(FamilyNames.SEEN_REELS).toString();
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** Only the last MAX_SEEN reels are remembered, the oldest going first. */
    @Test
    public void theOldestReelGoesFirst() {
        for (int id = 0; id <= SeenReels.MAX_SEEN; id++) SeenReels.watched(String.valueOf(id), ON);

        assertEquals(SeenReels.MAX_SEEN, SeenReels.size());
        List<Object> page = items(clip("0"), clip("1"), clip("new"));
        assertEquals(Arrays.asList(page.get(0), page.get(2)), SeenReels.page(page, REELS_TAB, ON, IDS));
    }

    /** The Reels tab leaves out reels already seen, keeps the rest in order, and counts them. */
    @Test
    public void theReelsTabLeavesOutReelsYouveSeen() {
        SeenReels.watched("111", ON);
        Clip seen = clip("111_77");
        Clip fresh = clip("222_77");
        Object other = new Object();

        List<?> kept = SeenReels.page(items(seen, fresh, other), REELS_TAB, ON, IDS);

        assertEquals(Arrays.asList(fresh, other), kept);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(SeenReels.ROUTE));
    }

    /** A profile's or a chat's reels, the switch off, nothing seen, or a page where every reel was seen come back whole. */
    @Test
    public void otherPagesComeBackWhole() {
        SeenReels.watched("111", ON);
        List<Object> mixed = items(clip("111"), clip("222"));
        assertSame(mixed, SeenReels.page(mixed, PROFILE, ON, IDS));
        assertSame(mixed, SeenReels.page(mixed, REELS_TAB, OFF, IDS));
        List<Object> fresh = items(clip("333"));
        assertSame(fresh, SeenReels.page(fresh, REELS_TAB, ON, IDS));
        List<Object> allSeen = items(clip("111"), new Object());
        assertSame(allSeen, SeenReels.page(allSeen, REELS_TAB, ON, IDS));
        assertNull(SeenReels.page(null, REELS_TAB, ON, IDS));
        List<Object> empty = Collections.emptyList();
        assertSame(empty, SeenReels.page(empty, REELS_TAB, ON, IDS));
    }

    /** What the viewer takes in later loses its seen reels once it holds more than one item, even all of them. */
    @Test
    public void reelsAddedLaterLeaveOutReelsYouveSeen() {
        SeenReels.watched("111", ON);
        SeenReels.watched("222", ON);
        Clip fresh = clip("333");
        List<Object> added = items(clip("111"), fresh);

        assertEquals(Collections.singletonList(fresh), SeenReels.added(added, REELS_TAB, 4, ON, IDS));
        assertEquals(Collections.emptyList(), SeenReels.added(items(clip("111"), clip("222")), REELS_TAB, 2, ON, IDS));
        assertSame(added, SeenReels.added(added, REELS_TAB, 1, ON, IDS));
        assertSame(added, SeenReels.added(added, PROFILE, 4, ON, IDS));
        assertSame(added, SeenReels.added(added, REELS_TAB, 4, OFF, IDS));
        assertNull(SeenReels.added(null, REELS_TAB, 4, ON, IDS));
    }

    /** A reader that throws keeps the page and the list added, and says the hook threw. */
    @Test
    public void aReaderThatThrowsKeepsTheReels() {
        SeenReels.watched("111", ON);
        Function<Object, String> broken = item -> {
            throw new IllegalStateException("no id");
        };
        List<Object> page = items(clip("111"), clip("222"));

        assertSame(page, SeenReels.page(page, REELS_TAB, ON, broken));
        assertSame(page, SeenReels.added(page, REELS_TAB, 4, ON, broken));
        String missing = HookStatus.missing(FamilyNames.SEEN_REELS).toString();
        assertTrue(missing, missing.contains("'reels page'"));
    }

    /** The patch-built stubs answer nothing as built, so an unpatched build keeps every reel. */
    @Test
    public void theStubsAnswerNothingAsBuilt() {
        Settings.HIDE_SEEN_REELS.save(true);
        SeenReels.watched("111", ON);
        List<Object> page = items(clip("111"), clip("222"));
        assertNull(SeenReels.media(page.get(0)));
        assertSame(page, SeenReels.page(page, REELS_TAB));
    }
}
