/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

/** What the loop hook answers, and how Loop a story and Stop Story auto-advance share a finished story. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryLoopTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOOP_STORIES.save(true);
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        StoryLoop.inBuildForTests = true;
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryLoop.inBuildForTests = null;
        Settings.LOOP_STORIES.resetToDefault();
        Settings.BLOCK_STORY_AUTO_ADVANCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The patch is in Manager's default selection, so its switch starts off and the reader turns it on. */
    @Test
    public void theSwitchStartsOff() {
        Settings.LOOP_STORIES.resetToDefault();
        assertFalse(Settings.LOOP_STORIES.get());
    }

    /** With the switch on, the viewer's loop test says yes whatever Instagram's server said. */
    @Test
    public void onEveryStoryLoops() {
        assertTrue("the server said no", StoryLoop.loop(0));
        assertTrue("the server said yes", StoryLoop.loop(1));
        assertTrue(HookStatus.missing(FamilyNames.STORY_LOOP).toString(), HookStatus.missing(FamilyNames.STORY_LOOP).isEmpty());
    }

    /** Off, paused or before the settings are read, the loop test keeps Instagram's answer. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.LOOP_STORIES.save(false);
        assertFalse("off", StoryLoop.loop(0));
        assertTrue("off, Instagram's own yes", StoryLoop.loop(1));
        Settings.LOOP_STORIES.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryLoop.loop(0));
        assertTrue("paused, Instagram's own yes", StoryLoop.loop(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", StoryLoop.loop(0));
            assertTrue("no context, Instagram's own yes", StoryLoop.loop(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", StoryLoop.loop(0));
            assertTrue("pause undecided, Instagram's own yes", StoryLoop.loop(1));
        });

        assertTrue("back on", StoryLoop.loop(0));
    }

    /**
     * With both switches on, a story the loop check says yes to isn't held, so a finished photo
     * reaches the place where Instagram starts it over, the way a video loops in the player
     * before Stop is asked.
     */
    @Test
    public void bothOnAStoryThatCanLoopLoops() {
        assertTrue(StoryLoop.takesOver());
        assertFalse("both on, nothing is held outright", StoryAdvance.hold());
        assertTrue("both on, the loop check decides", StoryAdvance.holdUnlessItLoops());
        assertFalse("a story that can loop is let through", held(LOOPS));
        assertTrue("and the loop check says it plays again", LOOPS.check());
    }

    /**
     * With both switches on, a story the loop check turns down (an ad, or one of the few special
     * kinds) never reaches the flag Loop answers, so it can't loop, and Stop still holds it.
     */
    @Test
    public void bothOnAStoryThatCantLoopIsStillHeld() {
        assertTrue("a story that loops was seen first", LOOPS.check());
        assertTrue("a story that can't loop is held", held(CANT_LOOP));
        assertTrue("held each time it calls in", held(CANT_LOOP));
        assertFalse("the next story that can loop is let through", held(LOOPS));
    }

    /** Stop alone, or with Loop's switch off, holds every story as before. */
    @Test
    public void stopAloneHoldsEveryStory() {
        Settings.LOOP_STORIES.save(false);
        assertFalse(StoryLoop.takesOver());
        assertTrue("Loop off, Stop holds outright", StoryAdvance.hold());
        assertTrue("Loop off, a story that could loop is held", held(LOOPS));
        assertTrue("Loop off, a story that can't loop is held", held(CANT_LOOP));
    }

    /** Loop alone loops the stories it can and lets the rest move on, as before. */
    @Test
    public void loopAloneLeavesStopOutOfIt() {
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse("Stop off, nothing is held", StoryAdvance.hold());
        assertFalse("Stop off, the loop check isn't asked for Stop", StoryAdvance.holdUnlessItLoops());
        assertFalse(held(LOOPS));
        assertTrue("and the story loops", LOOPS.check());
        assertFalse("a story that can't loop moves on", held(CANT_LOOP));
    }

    /** Paused or before the settings are read, neither switch holds anything. */
    @Test
    public void pausedAndUnreadyHoldNothing() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, Loop stands aside", StoryLoop.takesOver());
        assertFalse("paused", held(LOOPS));
        assertFalse("paused", held(CANT_LOOP));
        assertFalse("paused, Instagram's no stands and the story moves on", LOOPS.check());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", StoryLoop.takesOver());
            assertFalse("no context", held(LOOPS));
            assertFalse("no context", held(CANT_LOOP));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", held(LOOPS));
            assertFalse("pause undecided", held(CANT_LOOP));
        });
    }

    /** A build without Loop a story leaves Stop as it was, though Loop's unused switch reads on. */
    @Test
    public void withoutTheLoopPatchStopHoldsAsBefore() {
        StoryLoop.inBuildForTests = false;
        assertTrue(Settings.LOOP_STORIES.get());
        assertFalse(StoryLoop.takesOver());
        assertTrue(StoryAdvance.hold());
        assertFalse(StoryAdvance.holdUnlessItLoops());
        assertTrue(held(CANT_LOOP));
    }

    /** Instagram's loop check for one finished story. */
    private interface Story {
        boolean check();
    }

    /** A story the check reads the flag for, which Loop answers. */
    private static final Story LOOPS = () -> StoryLoop.loop(0);

    /** An ad or a special kind of story: the check says no before it reads the flag. */
    private static final Story CANT_LOOP = () -> false;

    /**
     * What the patch's guard at the top of the finished story handler does, call for call: hold
     * outright, or ask whether Loop decides and then hold only when the loop check says no. The
     * patch tests trace the same steps through the guard's bytecode.
     */
    private static boolean held(Story story) {
        if (StoryAdvance.hold()) {
            return true;
        }
        if (!StoryAdvance.holdUnlessItLoops()) {
            return false;
        }
        return !story.check();
    }
}
