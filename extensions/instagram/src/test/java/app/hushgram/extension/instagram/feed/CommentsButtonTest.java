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

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide comments takes the Comment button and its count off Feed's action row. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentsButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENTS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheRowHasNoCommentButtonOrCount() {
        assertFalse(CommentsButton.feedState(1));
        assertFalse(CommentsButton.feedState(0));
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: comments off 2"),
                HookStatus.report());
    }

    /** Off, paused, unready or throwing, each flag is Instagram's, and any non-zero int is a yes. */
    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.HIDE_COMMENTS.resetToDefault();
        assertFalse(Settings.HIDE_COMMENTS.get());
        assertTrue(CommentsButton.feedState(1));
        assertFalse(CommentsButton.feedState(0));
        assertTrue("2 is a yes too", CommentsButton.feedState(2));
        Settings.HIDE_COMMENTS.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(CommentsButton.feedState(1));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(CommentsButton.feedState(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(CommentsButton.feedState(1)));
        assertFalse(CommentsButton.feedState(1));

        assertTrue(CommentsButton.feedState(true, THROWS));
        assertFalse(CommentsButton.feedState(false, THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentsButton.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
