/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Whether a post's hidden like count is let through, and what the count reader reports. Runtime
 * decisions only: whether Instagram's server sends the count with such a post needs a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenLikeCountsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final Object POST = new Object();
    private static final Function<Object, Boolean> HID_ITS_LIKES = tree -> Boolean.TRUE;
    private static final Function<Object, Boolean> SHOWS_ITS_LIKES = tree -> Boolean.FALSE;
    private static final Function<Object, Boolean> NOT_READ = tree -> null;
    private static final Object OTHER_POST = new Object();
    private static final Function<Object, Object> TWELVE_LIKES = tree -> 12;
    private static final Function<Object, Object> NO_LIKES_SENT = tree -> null;
    private static final Function<Object, Object> NOTHING_ABOVE_ZERO = tree -> 0;
    private static final Function<Object, Object> COUNTS_BY_POST = tree -> tree == POST ? 12 : null;
    private static final BooleanSupplier ON = HiddenLikeCounts::switchedOn;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        HiddenLikeCounts.reset();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenLikeCounts.reset();
        HookStatus.clear();
    }

    @Test
    public void theKeyIsTheFieldNamesHash() {
        assertEquals("like_and_view_counts_disabled".hashCode(), HiddenLikeCounts.LIKES_HIDDEN_KEY);
    }

    @Test
    public void aPostThatCameWithACountHasItsHiddenCountShown() {
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertFalse(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertFalse("the next row asking gets its own answer", HiddenLikeCounts.hidden(1));
        assertTrue(HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString(),
                HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT + " 1"));
        assertTrue(report, report.contains(HiddenLikeCounts.SHOWN + " 2"));
        assertFalse(report, report.contains(HiddenLikeCounts.NO_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN));
    }

    /** One post with a count doesn't unhide the next one the server sent none for. */
    @Test
    public void aHiddenPostWithoutACountStaysHiddenWhateverAnotherPostCameWith() {
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        HiddenLikeCounts.rowRead(POST, true, ON, COUNTS_BY_POST);
        assertFalse("the post with a count shows it", HiddenLikeCounts.hidden(1));

        HiddenLikeCounts.sawCount(OTHER_POST, null, ON, HID_ITS_LIKES);
        HiddenLikeCounts.rowRead(OTHER_POST, true, ON, COUNTS_BY_POST);
        assertTrue("the post without one stays as Instagram drew it", HiddenLikeCounts.hidden(1));

        HiddenLikeCounts.rowRead(OTHER_POST, true, ON, NOTHING_ABOVE_ZERO);
        assertTrue("a count of zero is no count", HiddenLikeCounts.hidden(1));

        HiddenLikeCounts.rowRead(POST, true, ON, COUNTS_BY_POST);
        assertFalse("the first post still shows its own", HiddenLikeCounts.hidden(1));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.SHOWN + " 2"));
        assertTrue(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN + " 2"));
        assertTrue(report, report.contains(HiddenLikeCounts.NO_COUNT + " 1"));
    }

    /** A row's note is for the next ask only, so a stale one can't unhide a later, different ask. */
    @Test
    public void aRowsNoteIsTakenByTheNextAskOnly() {
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertFalse(HiddenLikeCounts.hidden(1));
        assertTrue(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertFalse("a shown post asking takes the note too", HiddenLikeCounts.hidden(false, ON));
        assertTrue(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        HiddenLikeCounts.rowRead(OTHER_POST, true, ON, NO_LIKES_SENT);
        assertTrue("the latest row read decides", HiddenLikeCounts.hidden(1));
    }

    /** A post that shows its likes, or one the row found no flag for, is never counted as hidden. */
    @Test
    public void rowsOnlyNoteTheirHiddenPosts() {
        HiddenLikeCounts.rowRead(POST, false, ON, TWELVE_LIKES);
        assertTrue(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(POST, null, ON, TWELVE_LIKES);
        assertTrue(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(null, true, ON, TWELVE_LIKES);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN));
    }

    /** The row's count is read by the key of like_count. */
    @Test
    public void theCountKeyIsTheFieldNamesHash() {
        assertEquals("like_count".hashCode(), HiddenLikeCounts.LIKE_COUNT_KEY);
    }

    /** With no count sent, or none above zero, there's no number to show, so the count stays hidden. */
    @Test
    public void noCountLeavesItHidden() {
        HiddenLikeCounts.sawCount(POST, null, ON, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, 0, ON, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, "12", ON, HID_ITS_LIKES);
        HiddenLikeCounts.rowRead(POST, true, ON, NO_LIKES_SENT);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.NO_COUNT + " 3"));
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.SHOWN));
    }

    /** A count that comes with a post showing its likes says nothing about hidden ones. */
    @Test
    public void postsThatDidntHideTheirLikesProveNothing() {
        HiddenLikeCounts.sawCount(POST, 40, ON, SHOWS_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, 40, ON, NOT_READ);
        HiddenLikeCounts.sawCount(null, 40, ON, HID_ITS_LIKES);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.NO_COUNT));
    }

    @Test
    public void aShownCountStaysShown() {
        assertFalse(HiddenLikeCounts.hidden(0));
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        assertFalse(HiddenLikeCounts.hidden(0));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.SHOWN));
    }

    /** Unpatched, the stub reads no flag, so nothing is counted and nothing changes. */
    @Test
    public void unpatchedTheReaderSeesNoFlag() {
        HiddenLikeCounts.sawCount(POST, 12);
        HiddenLikeCounts.rowRead(POST, true);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void offToStartOffPausedAndUnreadyKeepItHidden() {
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        assertFalse(Settings.SHOW_HIDDEN_LIKE_COUNTS.defaultValue);
        assertTrue(HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        assertTrue(HiddenLikeCounts.hidden(1));
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);

        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(HiddenLikeCounts.hidden(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        SettingsContextRule.withoutContext(() -> assertTrue(HiddenLikeCounts.hidden(1)));
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(HiddenLikeCounts.hidden(1)));

        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertFalse(HiddenLikeCounts.hidden(1));
    }

    /** With the switch off the reader doesn't look, so a count seen then doesn't count later. */
    @Test
    public void offTheReaderDoesntLook() {
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new AssertionError("read the flag with the switch off");
        });
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new AssertionError("read the count with the switch off");
        });
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void throwingKeepsItHiddenAndIsReported() {
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES);
        assertTrue(HiddenLikeCounts.hidden(true, THROWS));
        HiddenLikeCounts.rowRead(POST, true, THROWS, TWELVE_LIKES);
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new IllegalStateException("count went away");
        });
        assertTrue("a row that threw leaves it hidden", HiddenLikeCounts.hidden(1));
        HiddenLikeCounts.sawCount(POST, 12, THROWS, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new IllegalStateException("tree went away");
        });

        String missing = HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString();
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.DECISION + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.COUNT_READ + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.ROW_READ + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
