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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * When Hide the (i) button in comments leaves the (i) off the row above a sheet's comments, and the
 * row with an (i) out of a reel's comments (#869).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentAboutButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** Stands in for what the row reads: Instagram's data class of the explanation's two strings. */
    private final Object explained = new Object();
    /** Stand in for the reel's caption row and the row with an (i) the comment list adds after it. */
    private final Object caption = new Object();
    private final Object aboutRow = new Object();

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

    /**
     * The row with an (i) that a reel's comments can start with, "About comments on this reel" in
     * English: with the switch on it stays out of the list, so the comments start with the first one.
     */
    @Test
    public void withTheSwitchOnTheReelRowStaysOutOfTheList() {
        List<Object> list = new ArrayList<>(List.of(caption));
        CommentAboutButton.aboutRow(list, aboutRow);
        CommentAboutButton.aboutRow(list, aboutRow);
        assertEquals(List.of(caption), list);
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 2, 0 found, 0 missing. Counted: "
                + CommentAboutButton.COUNT_ROW_HIDDEN + " 2"), HookStatus.report());
    }

    /** Off, paused, unready or throwing, the row goes in right where Instagram puts it. */
    @Test
    public void offPausedUnreadyAndThrowingAddTheReelRow() {
        List<Object> list = new ArrayList<>(List.of(caption));
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        CommentAboutButton.aboutRow(list, aboutRow);
        assertEquals(List.of(caption, aboutRow), list);
        Settings.HIDE_COMMENT_ABOUT_BUTTON.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        CommentAboutButton.aboutRow(list, aboutRow);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> CommentAboutButton.aboutRow(list, aboutRow));
        SettingsContextRule.beforeThePauseIsDecided(() -> CommentAboutButton.aboutRow(list, aboutRow));
        CommentAboutButton.addAboutRow(list, aboutRow, THROWS);
        assertEquals(List.of(caption, aboutRow, aboutRow, aboutRow, aboutRow, aboutRow), list);

        CommentAboutButton.aboutRow(list, aboutRow);
        assertEquals(6, list.size());
        String missing = HookStatus.missing(FamilyNames.HIDE_COMMENTS).toString();
        assertTrue(missing, missing.contains("'" + CommentAboutButton.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** The other comment sheet switches leave the reel row in, as they leave the (i) button. */
    @Test
    public void theOtherSheetSwitchesKeepTheReelRow() {
        Settings.HIDE_COMMENT_ABOUT_BUTTON.resetToDefault();
        Settings.HIDE_COMMENTS.save(true);
        Settings.HIDE_COMMENT_BOX.save(true);
        Settings.HIDE_COMMENT_SEARCH_PROMPT.save(true);
        Settings.HIDE_COMMENT_GIFT_BUTTON.save(true);
        List<Object> list = new ArrayList<>();
        CommentAboutButton.aboutRow(list, aboutRow);
        assertEquals(List.of(aboutRow), list);
        assertEquals(List.of(FamilyNames.HIDE_COMMENTS + ": invoked 1, 0 found, 0 missing. Counted: "
                + CommentAboutButton.COUNT_ROW_SHOWN + " 1"), HookStatus.report());
    }
}
