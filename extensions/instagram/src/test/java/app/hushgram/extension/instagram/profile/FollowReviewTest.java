/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.*;

import android.content.DialogInterface;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Follow without the review sheet (#867): with the switch on, the "Review this account before
 * following" sheet's Follow listener runs at once, as the sheet's Follow button runs it, and the sheet
 * isn't shown. Off, paused, or for a sheet opened while that follow runs, the sheet shows as usual.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowReviewTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    /** The buttons each follow listener was run with, in order. */
    private final List<Integer> clicks = new ArrayList<>();

    @Before public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        clicks.clear();
    }

    @After public void restore() {
        Settings.SKIP_FOLLOW_REVIEW.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    private DialogInterface.OnClickListener follow() {
        return (dialog, which) -> {
            assertNull("the sheet's own button hands no dialog", dialog);
            clicks.add(which);
        };
    }

    @Test public void offShowsTheSheet() {
        assertFalse(Settings.SKIP_FOLLOW_REVIEW.get());
        assertFalse(FollowReview.skip(follow()));
        assertTrue("nothing followed", clicks.isEmpty());
    }

    @Test public void onFollowsAtOnceAsTheSheetsFollowButton() {
        Settings.SKIP_FOLLOW_REVIEW.save(true);
        assertTrue(FollowReview.skip(follow()));
        assertEquals("one follow, through the positive button", List.of(DialogInterface.BUTTON_POSITIVE), clicks);
        assertEquals(List.of(FamilyNames.ASK_BEFORE_FOLLOW + ": invoked 1, 0 found, 0 missing. Counted: " + FollowReview.SKIPPED + " 1"),
                HookStatus.report());
    }

    @Test public void pausedShowsTheSheet() {
        Settings.SKIP_FOLLOW_REVIEW.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(FollowReview.skip(follow()));
        assertTrue(clicks.isEmpty());
    }

    @Test public void aSheetOpenedWhileTheFollowRunsShows() {
        Settings.SKIP_FOLLOW_REVIEW.save(true);
        List<Boolean> inner = new ArrayList<>();
        DialogInterface.OnClickListener reopening = (dialog, which) -> {
            clicks.add(which);
            inner.add(FollowReview.skip(follow()));
        };
        assertTrue(FollowReview.skip(reopening));
        assertEquals("the inner sheet shows rather than following again", List.of(false), inner);
        assertEquals(List.of(DialogInterface.BUTTON_POSITIVE), clicks);
        assertTrue("and the next sheet is skipped again", FollowReview.skip(follow()));
    }

    @Test public void aFollowThatThrowsShowsTheSheet() {
        Settings.SKIP_FOLLOW_REVIEW.save(true);
        DialogInterface.OnClickListener broken = (dialog, which) -> {
            throw new IllegalStateException("broken follow");
        };
        assertFalse(FollowReview.skip(broken));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("threw"));
        assertTrue("the guard is let go", FollowReview.skip(follow()));
    }

    @Test public void anythingButAListenerShowsTheSheet() {
        Settings.SKIP_FOLLOW_REVIEW.save(true);
        assertFalse(FollowReview.skip(null));
        assertFalse(FollowReview.skip("not a listener"));
    }
}
