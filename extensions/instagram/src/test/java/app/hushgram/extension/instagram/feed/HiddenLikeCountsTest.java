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
    public void onceACountArrivesAHiddenCountIsShown() {
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        assertFalse(HiddenLikeCounts.hidden(1));
        assertFalse("the second row asking gets the same answer", HiddenLikeCounts.hidden(1));
        assertTrue(HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString(),
                HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT + " 1"));
        assertTrue(report, report.contains(HiddenLikeCounts.SHOWN + " 2"));
        assertFalse(report, report.contains(HiddenLikeCounts.NO_COUNT));
    }

    /** With no count sent, or none above zero, there's no number to show, so the count stays hidden. */
    @Test
    public void noCountLeavesItHidden() {
        HiddenLikeCounts.sawCount(POST, null, ON, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, 0, ON, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, "12", ON, HID_ITS_LIKES);
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
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void offToStartOffPausedAndUnreadyKeepItHidden() {
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        assertFalse(Settings.SHOW_HIDDEN_LIKE_COUNTS.defaultValue);
        assertTrue(HiddenLikeCounts.hidden(1));
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        assertTrue(HiddenLikeCounts.hidden(1));
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(HiddenLikeCounts.hidden(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(HiddenLikeCounts.hidden(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(HiddenLikeCounts.hidden(1)));

        assertFalse(HiddenLikeCounts.hidden(1));
    }

    /** With the switch off the reader doesn't look, so a count seen then doesn't count later. */
    @Test
    public void offTheReaderDoesntLook() {
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new AssertionError("read the flag with the switch off");
        });
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertTrue(HiddenLikeCounts.hidden(1));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void throwingKeepsItHiddenAndIsReported() {
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES);
        assertTrue(HiddenLikeCounts.hidden(true, THROWS));
        HiddenLikeCounts.sawCount(POST, 12, THROWS, HID_ITS_LIKES);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new IllegalStateException("tree went away");
        });

        String missing = HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString();
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.DECISION + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.COUNT_READ + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
