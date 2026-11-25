/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

/** When Hide the (i) button in comments leaves the (i) off the row above a sheet's comments (#869). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentAboutButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** Stands in for what the row reads: Instagram's data class of the explanation's two strings. */
    private final Object explained = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_COMMENT_ABOUT_BUTTON.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        Settings.HIDE_COMMENT_SEARCH_PROMPT.resetToDefault();
        Settings.HIDE_COMMENT_GIFT_BUTTON.resetToDefault();
        Settings.HIDE_COMMENT_BOX.resetToDefault();
        Settings.HIDE_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheRowFindsNothingToExplain() {
        assertNull(CommentAboutButton.disclosure(explained));
        assertNull(CommentAboutButton.disclosure(explained));
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: "
                + CommentAboutButton.COUNT_HIDDEN + " 2"), HookStatus.report());
    }

    /** A sheet with nothing to explain stays without an (i), and isn't counted as one taken off. */
    @Test
    public void aSheetWithoutAnExplanationGetsNone() {
        assertNull(CommentAboutButton.disclosure(null));
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        assertNull(CommentAboutButton.disclosure(null));
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** The (i) has its own switch: the other comment sheet switches leave it where it is. */
    @Test
    public void theOtherSheetSwitchesKeepTheButton() {
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        assertFalse(Settings.HIDE_COMMENT_ABOUT_BUTTON.get());
        Settings.HIDE_COMMENTS.save(true);
        Settings.HIDE_COMMENT_BOX.save(true);
        Settings.HIDE_COMMENT_SEARCH_PROMPT.save(true);
        Settings.HIDE_COMMENT_GIFT_BUTTON.save(true);
        assertSame(explained, CommentAboutButton.disclosure(explained));
    }

    /** Off, paused, unready or throwing, the row gets what it read, so the (i) shows as Instagram has it. */
    @Test
    public void offPausedUnreadyAndThrowingKeepTheButton() {
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        assertSame(explained, CommentAboutButton.disclosure(explained));
        Settings.HIDE_COMMENT_ABOUT_BUTTON.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(explained, CommentAboutButton.disclosure(explained));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(explained, CommentAboutButton.disclosure(explained)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(explained, CommentAboutButton.disclosure(explained)));
        assertNull(CommentAboutButton.disclosure(explained));

        assertSame(explained, CommentAboutButton.disclosure(explained, THROWS));
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentAboutButton.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
