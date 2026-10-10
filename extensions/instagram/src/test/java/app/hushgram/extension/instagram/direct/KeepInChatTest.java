/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Keep in chat tells Instagram a photo or video message's view mode is, and when it leaves it alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class KeepInChatTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_IN_CHAT.save(true);
        HookStatus.clear();
        KeepInChat.clearRemembered();
    }

    @After
    public void restore() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        KeepInChat.clearRemembered();
    }

    /** A message and its two media, standing in for Instagram's objects. */
    private static final class FakeMessage implements KeepInChat.Message {
        final Object visual = new Object();
        final Object item = new Object();
        final Map<Object, String> modes = new HashMap<>();
        boolean sent;
        boolean throwsOnRead;

        @Override
        public boolean sentByYou(Object message) {
            if (throwsOnRead) throw new IllegalStateException("field went away");
            return sent;
        }

        @Override
        public Object visualMedia(Object message) {
            return visual;
        }

        @Override
        public Object itemMedia(Object message) {
            return item;
        }

        @Override
        public void setViewMode(Object media, String mode) {
            modes.put(media, mode);
        }
    }

    @Test
    public void aViewOncePhotoYouSentKeepsItsModeWhicheverKeyComesFirst() {
        // The media is read first, then the message says it was sent by you.
        FakeMessage mine = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", mine.visual));
        mine.sent = true;
        KeepInChat.messageRead(mine, mine);
        assertEquals("once", mine.modes.get(mine.visual));

        // The flag first: the message call after the media finds both.
        FakeMessage replay = new FakeMessage();
        replay.sent = true;
        assertEquals("permanent", KeepInChat.viewMode("replayable", replay.item));
        KeepInChat.messageRead(replay, replay);
        assertEquals("replayable", replay.modes.get(replay.item));

        // A second call after the last store does nothing more.
        mine.modes.clear();
        KeepInChat.messageRead(mine, mine);
        assertTrue(mine.modes.isEmpty());
    }

    @Test
    public void mediaYouReceiveAndMessagesWithNoFlagStayKept() {
        FakeMessage theirs = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", theirs.visual));
        KeepInChat.messageRead(theirs, theirs);
        assertTrue("not sent by you: nothing restored", theirs.modes.isEmpty());

        // Media that was never rewritten (a mode Instagram keeps, or a media from before the switch) is never touched.
        FakeMessage sentOld = new FakeMessage();
        sentOld.sent = true;
        assertEquals("permanent", KeepInChat.viewMode("permanent", sentOld.visual));
        KeepInChat.messageRead(sentOld, sentOld);
        assertTrue(sentOld.modes.isEmpty());
    }

    @Test
    public void withTheSwitchOffNothingIsRememberedOrRestored() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        FakeMessage mine = new FakeMessage();
        mine.sent = true;
        assertEquals("once", KeepInChat.viewMode("once", mine.visual));
        KeepInChat.messageRead(mine, mine);
        assertTrue(mine.modes.isEmpty());
    }

    @Test
    public void aMessageThatCantBeReadIsReportedAndLeftKept() {
        FakeMessage mine = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", mine.visual));
        mine.throwsOnRead = true;
        KeepInChat.messageRead(mine, mine);
        assertTrue(mine.modes.isEmpty());
        String missing = HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString();
        assertTrue(missing, missing.contains("'" + KeepInChat.SENDER + "'"));
        KeepInChat.messageRead(null, mine);
    }

    @Test
    public void withTheSwitchOnViewOnceAndReplayableStayInChat() {
        assertEquals("permanent", KeepInChat.viewMode("once", new Object()));
        assertEquals("permanent", KeepInChat.viewMode("replayable", new Object()));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString(), HookStatus.missing(FamilyNames.KEEP_IN_CHAT).isEmpty());

        assertEquals("permanent", KeepInChat.viewMode("permanent", new Object()));
        assertEquals("a mode Instagram adds later", "later", KeepInChat.viewMode("later", new Object()));
        assertNull(KeepInChat.viewMode(null, new Object()));
    }

    @Test
    public void offPausedUnreadyAndThrowingLeaveItToInstagram() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        assertFalse(Settings.KEEP_IN_CHAT.get());
        assertEquals("once", KeepInChat.viewMode("once", new Object()));
        Settings.KEEP_IN_CHAT.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("replayable", KeepInChat.viewMode("replayable", new Object()));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals("once", KeepInChat.viewMode("once", new Object())));
        assertEquals("permanent", KeepInChat.viewMode("once", new Object()));

        assertEquals("once", KeepInChat.viewMode("once", new Object(), THROWS));
        String missing = HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString();
        assertTrue(missing, missing.contains("'" + KeepInChat.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
