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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When View live anonymously holds a live viewer's heartbeat, and when it leaves it to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LiveSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static final String LIVE = "17841400000000001";
    private static final String OTHER = "17841400000000002";

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);
        HookStatus.clear();
        LiveSeen.resetForTests();
    }

    @After
    public void restore() {
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        LiveSeen.resetForTests();
    }

    @Test
    public void withTheSwitchOnTheViewerHeartbeatIsHeld() {
        assertTrue(LiveSeen.hold(LIVE));
        assertTrue(LiveSeen.hold(null));
        assertTrue(HookStatus.missing(FamilyNames.LIVE_SEEN).toString(), HookStatus.missing(FamilyNames.LIVE_SEEN).isEmpty());
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        assertFalse(Settings.VIEW_LIVE_ANONYMOUSLY.get());
        assertFalse(LiveSeen.hold(LIVE));
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(LiveSeen.hold(LIVE));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(LiveSeen.hold(LIVE)));
        assertTrue(LiveSeen.hold(LIVE));

        assertFalse(LiveSeen.hold(LIVE, THROWS));
        String missing = HookStatus.missing(FamilyNames.LIVE_SEEN).toString();
        assertTrue(missing, missing.contains("'" + LiveSeen.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** #412: a tap lets the live on screen through from its next heartbeat, and only that live. */
    @Test
    public void aMarkedLiveSendsItsHeartbeatAndTheOthersStayHeld() {
        assertTrue(LiveSeen.hold(LIVE));
        assertFalse(LiveSeen.markedNow());
        LiveSeen.mark();
        assertTrue(LiveSeen.markedNow());
        assertFalse(LiveSeen.hold(LIVE));
        assertFalse(LiveSeen.hold(LIVE));

        LiveSeen.opened();
        assertFalse("a new live starts unmarked", LiveSeen.markedNow());
        assertTrue(LiveSeen.hold(OTHER));
        assertFalse(LiveSeen.markedNow());

        LiveSeen.opened();
        assertFalse("the mark lasts while Instagram is open", LiveSeen.hold(LIVE));
        assertTrue(LiveSeen.markedNow());
    }

    @Test
    public void aTapBeforeAnyHeartbeatMarksTheNextLiveSeen() {
        LiveSeen.opened();
        LiveSeen.mark();
        assertTrue(LiveSeen.markedNow());
        assertTrue("an id-less heartbeat can't take the mark", LiveSeen.hold(null));
        assertFalse(LiveSeen.hold(LIVE));
        assertTrue(LiveSeen.hold(OTHER));

        LiveSeen.opened();
        LiveSeen.mark();
        LiveSeen.opened();
        assertTrue("opening another live drops a waiting tap", LiveSeen.hold(OTHER));
    }

    @Test
    public void withTheSwitchOffAMarkChangesNothing() {
        assertTrue(LiveSeen.hold(LIVE));
        LiveSeen.mark();
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        assertFalse(LiveSeen.hold(OTHER));
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);
        assertTrue(LiveSeen.hold(OTHER));
        assertFalse(LiveSeen.hold(LIVE));
    }
}
