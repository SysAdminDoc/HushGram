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

    /** The JSON reader Instagram parses a message with, standing in. */
    private static final Object READER = new Object();

    /** A message and its two media, standing in for Instagram's objects. */
    private static final class FakeMessage implements KeepInChat.Message {
        final Object visual = new Object();
        final Object item = new Object();
        final Map<Object, String> modes = new HashMap<>();
        boolean sent;
        boolean throwsOnRead;
        /** The message's user_id, null until the parser has read it. */
        String sender;
        /** The id of the account the reader reads for, null for a reader with no account. */
        String viewer = "42";

        @Override
        public boolean sentByYou(Object message) {
            if (throwsOnRead) throw new IllegalStateException("field went away");
            return sent;
        }

        @Override
        public String senderId(Object message) {
            return sender;
        }

        @Override
        public String viewerId(Object reader) {
            assertTrue("the reader the parser was given", reader == READER);
            return viewer;
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
        KeepInChat.messageRead(mine, READER, mine);
        assertEquals("once", mine.modes.get(mine.visual));

        // The flag first: the message call after the media finds both.
        FakeMessage replay = new FakeMessage();
        replay.sent = true;
        assertEquals("permanent", KeepInChat.viewMode("replayable", replay.item));
        KeepInChat.messageRead(replay, READER, replay);
        assertEquals("replayable", replay.modes.get(replay.item));

        // A second call after the last store does nothing more.
        mine.modes.clear();
        KeepInChat.messageRead(mine, READER, mine);
        assertTrue(mine.modes.isEmpty());
    }

    @Test
    public void mediaYouReceiveAndMessagesWithNoFlagStayKept() {
        FakeMessage theirs = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", theirs.visual));
        KeepInChat.messageRead(theirs, READER, theirs);
        assertTrue("not sent by you: nothing restored", theirs.modes.isEmpty());

        // Media that was never rewritten (a mode Instagram keeps, or a media from before the switch) is never touched.
        FakeMessage sentOld = new FakeMessage();
        sentOld.sent = true;
        assertEquals("permanent", KeepInChat.viewMode("permanent", sentOld.visual));
        KeepInChat.messageRead(sentOld, READER, sentOld);
        assertTrue(sentOld.modes.isEmpty());
    }

    /**
     * #114: the copy of a view once photo you've just sent has no is_sent_by_viewer, and Instagram
     * tells it's yours by its user_id. Whichever comes first, the media or the user_id, the photo
     * gets its own mode back once both are in, and the report counts it.
     */
    @Test
    public void aViewOncePhotoYouSentWithoutTheFlagIsToldByItsSender() {
        FakeMessage mine = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", mine.visual));
        KeepInChat.messageRead(mine, READER, mine);
        assertTrue("no sender read yet: nothing restored", mine.modes.isEmpty());
        mine.sender = "42";
        KeepInChat.messageRead(mine, READER, mine);
        assertEquals("once", mine.modes.get(mine.visual));

        // The user_id first, then the media in message_item_dict.
        FakeMessage replay = new FakeMessage();
        replay.sender = "42";
        assertEquals("permanent", KeepInChat.viewMode("replayable", replay.item));
        KeepInChat.messageRead(replay, READER, replay);
        assertEquals("replayable", replay.modes.get(replay.item));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(KeepInChat.GAVE_BACK + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString(), HookStatus.missing(FamilyNames.KEEP_IN_CHAT).isEmpty());
    }

    @Test
    public void aViewOncePhotoSomeoneElseSentStaysKept() {
        FakeMessage theirs = new FakeMessage();
        theirs.sender = "7";
        assertEquals("permanent", KeepInChat.viewMode("once", theirs.visual));
        KeepInChat.messageRead(theirs, READER, theirs);
        assertTrue(theirs.modes.isEmpty());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(KeepInChat.GAVE_BACK));
        assertFalse(report, report.contains(KeepInChat.NO_VIEWER));
    }

    /** A reader with no account goes by the last account a reader had, and with none the photo stays kept and is counted. */
    @Test
    public void aReaderWithNoAccountGoesByTheLastOneSeen() {
        FakeMessage unknown = new FakeMessage();
        unknown.viewer = null;
        unknown.sender = "42";
        assertEquals("permanent", KeepInChat.viewMode("once", unknown.visual));
        KeepInChat.messageRead(unknown, READER, unknown);
        assertTrue("no account seen yet: stays kept", unknown.modes.isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(KeepInChat.NO_VIEWER + " 1"));

        // A reader with the account comes along (someone else's message), and the next one without it goes by it.
        FakeMessage theirs = new FakeMessage();
        theirs.sender = "7";
        assertEquals("permanent", KeepInChat.viewMode("once", theirs.visual));
        KeepInChat.messageRead(theirs, READER, theirs);
        KeepInChat.messageRead(unknown, READER, unknown);
        assertEquals("once", unknown.modes.get(unknown.visual));
        assertTrue(theirs.modes.isEmpty());
    }

    /**
     * Instagram writes each message to its cache with the view mode its media holds. A kept one is
     * written with the mode the server sent, so the next read decides again; anything else is
     * written as it is.
     */
    @Test
    public void theCacheGetsTheViewModeTheServerSent() {
        FakeMessage theirs = new FakeMessage();
        theirs.sender = "7";
        assertEquals("permanent", KeepInChat.viewMode("replayable", theirs.visual));
        KeepInChat.messageRead(theirs, READER, theirs);
        assertEquals("replayable", KeepInChat.storedViewMode("permanent", theirs.visual));
        assertEquals("written again, the same", "replayable", KeepInChat.storedViewMode("permanent", theirs.visual));

        FakeMessage mine = new FakeMessage();
        mine.sender = "42";
        assertEquals("permanent", KeepInChat.viewMode("once", mine.visual));
        KeepInChat.messageRead(mine, READER, mine);
        assertEquals("given back already", "once", KeepInChat.storedViewMode("once", mine.visual));

        Object permanent = new Object();
        assertEquals("permanent", KeepInChat.viewMode("permanent", permanent));
        assertEquals("a real Keep in chat media", "permanent", KeepInChat.storedViewMode("permanent", permanent));
        assertEquals("permanent", KeepInChat.storedViewMode("permanent", null));
        assertNull(KeepInChat.storedViewMode(null, theirs.visual));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(KeepInChat.SAVED + " 2"));
    }

    @Test
    public void withTheSwitchOffTheCacheGetsWhatItWasGiven() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        Object media = new Object();
        assertEquals("once", KeepInChat.viewMode("once", media));
        assertEquals("once", KeepInChat.storedViewMode("once", media));
        assertEquals("permanent", KeepInChat.storedViewMode("permanent", media));
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains(KeepInChat.SAVED));
    }

    @Test
    public void withTheSwitchOffNothingIsRememberedOrRestored() {
        Settings.KEEP_IN_CHAT.resetToDefault();
        FakeMessage mine = new FakeMessage();
        mine.sent = true;
        assertEquals("once", KeepInChat.viewMode("once", mine.visual));
        KeepInChat.messageRead(mine, READER, mine);
        assertTrue(mine.modes.isEmpty());
    }

    @Test
    public void aMessageThatCantBeReadIsReportedAndLeftKept() {
        FakeMessage mine = new FakeMessage();
        assertEquals("permanent", KeepInChat.viewMode("once", mine.visual));
        mine.throwsOnRead = true;
        KeepInChat.messageRead(mine, READER, mine);
        assertTrue(mine.modes.isEmpty());
        String missing = HookStatus.missing(FamilyNames.KEEP_IN_CHAT).toString();
        assertTrue(missing, missing.contains("'" + KeepInChat.SENDER + "'"));
        KeepInChat.messageRead(null, READER, mine);
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
