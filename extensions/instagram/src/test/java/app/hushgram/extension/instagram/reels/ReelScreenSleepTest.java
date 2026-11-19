/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Window;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** When Let the screen sleep when a reel stops (#777) takes the Reels viewer's keep-awake flag off, and when it gives it back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelScreenSleepTest {
    private static final int KEEP = ReelScreenSleep.KEEP_SCREEN_ON;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Set<Object> playingNow = new HashSet<>();
    private final Object player = new Object();
    private boolean on = true;
    private Window window;

    @Before
    public void setUp() {
        ReelScreenSleep.resetForTests();
        ReelScreenSleep.playingForTests = playingNow::contains;
        ReelScreenSleep.switchForTests = () -> on;
        HookStatus.clear();
        window = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
    }

    @After
    public void tearDown() {
        ReelScreenSleep.resetForTests();
        Settings.REEL_SCREEN_SLEEP.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void offToStart() {
        assertFalse(Settings.REEL_SCREEN_SLEEP.get());
    }

    @Test
    public void aPausedReelLetsTheScreenSleep() {
        playReel();
        waitFor(ReelScreenSleep.CHECK_MS * 2);
        assertTrue("a playing reel keeps the screen on", keptOn());

        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS);
        assertFalse("a paused reel lets it sleep", keptOn());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelScreenSleep.STEP + " 1"));
    }

    @Test
    public void playingAgainKeepsTheScreenOnRightAway() {
        playReel();
        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS);
        assertFalse(keptOn());

        playingNow.add(player);
        ReelScreenSleep.started(player);
        waitFor(ReelScreenSleep.STARTED_MS);
        assertTrue("a reel playing again keeps the screen on", keptOn());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ReelScreenSleep.WOKE + " 1"));
    }

    @Test
    public void withTheSwitchOffTheFlagStays() {
        on = false;
        playReel();
        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS * 3);
        assertTrue(keptOn());
    }

    @Test
    public void turningTheSwitchOffGivesTheFlagBack() {
        playReel();
        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS);
        assertFalse(keptOn());

        on = false;
        waitFor(ReelScreenSleep.CHECK_MS);
        assertTrue("off puts Instagram's flag back", keptOn());
    }

    @Test
    public void beforeAnyPlayerStartsNothingChanges() {
        viewerResumes();
        waitFor(ReelScreenSleep.CHECK_MS * 3);
        assertTrue("no player seen, so the reel may be playing on one this doesn't know", keptOn());
    }

    @Test
    public void theViewerLettingGoEndsTheChecks() {
        playReel();
        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS);
        viewerPauses();

        playingNow.add(player);
        ReelScreenSleep.started(player);
        waitFor(ReelScreenSleep.CHECK_MS * 3);
        assertFalse("the flag is Instagram's again once the viewer pauses", keptOn());
    }

    @Test
    public void aResumeAfterLettingGoIsInstagramsAgain() {
        playReel();
        playingNow.remove(player);
        waitFor(ReelScreenSleep.CHECK_MS);
        assertFalse(keptOn());

        // The viewer pauses and resumes (the screen came back on): Instagram sets the flag, and the
        // check takes it off again only once it finds nothing playing.
        viewerPauses();
        viewerResumes();
        assertTrue(keptOn());
        waitFor(ReelScreenSleep.CHECK_MS);
        assertFalse(keptOn());
    }

    @Test
    public void nothingThrows() {
        ReelScreenSleep.keepAwake(null, 1);
        ReelScreenSleep.started(null);
        ReelScreenSleep.playingForTests = p -> {
            throw new IllegalStateException("player went away");
        };
        playReel();
        waitFor(ReelScreenSleep.CHECK_MS * 2);
        assertTrue("a failing check leaves Instagram's flag", keptOn());
    }

    /** A reel starts on [player] with the viewer open. */
    private void playReel() {
        playingNow.add(player);
        ReelScreenSleep.started(player);
        viewerResumes();
    }

    /** What the viewer's helper does as it resumes: the hook, then the flag. */
    private void viewerResumes() {
        ReelScreenSleep.keepAwake(window, 1);
        window.addFlags(KEEP);
    }

    /** What it does as it pauses. */
    private void viewerPauses() {
        ReelScreenSleep.keepAwake(window, 0);
        window.clearFlags(KEEP);
    }

    private boolean keptOn() {
        return (window.getAttributes().flags & KEEP) != 0;
    }

    private static void waitFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
