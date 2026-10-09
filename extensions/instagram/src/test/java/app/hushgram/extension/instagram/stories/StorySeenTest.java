/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When a batch of viewed stories stays on the phone instead of going to media/seen/. */
@RunWith(RobolectricTestRunner.class)
public class StorySeenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    private final Object batch = new Object();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        assertFalse("the patch is in the default selection, so the reader turns it on", Settings.VIEW_STORIES_ANONYMOUSLY.get());
    }

    @Test
    public void withTheSwitchOnABatchIsHeldBackAndCounted() {
        FeedFilterCounters.snapshotAndClear();
        assertNull(StorySeen.toSend(null, batch));
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(StorySeen.ROUTE));
        assertTrue(report.toString(), report.toString().contains(StorySeen.HELD_BACK));
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());
    }

    @Test
    public void offPausedOrNotReadyTheBatchGoesOut() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertSame("off", batch, StorySeen.toSend(null, batch));
        assertSame("retry off", batch, StorySeen.toRetry(null, batch));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", batch, StorySeen.toSend(null, batch));
        assertSame("retry paused", batch, StorySeen.toRetry(null, batch));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("settings not ready", batch, StorySeen.toSend(null, batch)));
        SettingsContextRule.withoutContext(() -> assertSame("retry settings not ready", batch, StorySeen.toRetry(null, batch)));
        assertNull("the control: on, the same batch is held back", StorySeen.toSend(null, batch));
        assertNull("retry on", StorySeen.toRetry(null, batch));
    }

    /** The button's switch on with nothing marked holds the batch back just as the switch alone does. */
    @Test
    public void withTheButtonOnAndNothingMarkedTheBatchIsHeldBack() {
        Settings.MARK_STORIES_SEEN.save(true);
        assertNull(StorySeen.toSend(null, batch));
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());
    }
}
