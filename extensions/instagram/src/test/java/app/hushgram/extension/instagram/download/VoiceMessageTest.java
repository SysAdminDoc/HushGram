/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Download voice messages: when a voice message's menu gets Save, and what Save then saves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class VoiceMessageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String RECORDING = "https://cdn.fbsbx.com/v/t59.3654-21/audioclip-1.mp4?_nc_cat=1";
    private final Object message = new Object();
    private final FakeNative reads = new FakeNative();
    private final List<String> saved = new ArrayList<>();
    private boolean saveStarts = true;
    private final VoiceMessage.Save save = (context, url, details) -> {
        saved.add(url);
        return saveStarts;
    };
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.DOWNLOAD_VOICE_MESSAGES.save(true);
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.DOWNLOAD_VOICE_MESSAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOff() {
        Settings.DOWNLOAD_VOICE_MESSAGES.resetToDefault();
        assertFalse(Settings.DOWNLOAD_VOICE_MESSAGES.get());
    }

    @Test public void aVoiceMessageGetsSave() {
        assertTrue(VoiceMessage.offer(false, message, reads));
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 1, 0 found, 0 missing. Counted: Save offered 1"), HookStatus.report());
    }

    /** Instagram's yes stands whatever the switch says, and nothing of the message is read for it. */
    @Test public void instagramsYesStands() {
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertTrue(VoiceMessage.offer(true, message, reads));
        assertTrue(VoiceMessage.offer(1, message));
        assertEquals(0, reads.calls);
    }

    @Test public void offTheMenuIsInstagrams() {
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertEquals("nothing of the message was read", 0, reads.calls);
    }

    @Test public void pausedOrUnreadyTheMenuIsInstagrams() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(VoiceMessage.offer(false, message, reads));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(VoiceMessage.offer(false, message, reads)));
        assertEquals(0, reads.calls);
    }

    /** A photo, a text or anything else that isn't a voice message keeps Instagram's no, uncounted. */
    @Test public void anythingElseKeepsInstagramsNo() {
        reads.address = null;
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertFalse(VoiceMessage.offer(false, null, reads));
        assertTrue(HookStatus.report().isEmpty());
    }

    @Test public void oneSentToBePlayedOnceKeepsInstagramsNo() {
        for (String mode : new String[] {"once", "replayable", "something new"}) {
            reads.mode = mode;
            assertFalse(mode, VoiceMessage.offer(false, message, reads));
        }
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 3, 0 found, 0 missing. Counted: sent to be played once 3"), HookStatus.report());
    }

    @Test public void aPermanentOneGetsSave() {
        reads.mode = "permanent";
        assertTrue(VoiceMessage.offer(false, message, reads));
    }

    /** A voice message whose mark can't be told is kept from Save, as Instagram's own check keeps it. */
    @Test public void anUnmarkedOneKeepsInstagramsNo() {
        for (String mode : new String[] {"", null, "PERMANENT", " permanent"}) {
            reads.mode = mode;
            assertFalse(String.valueOf(mode), VoiceMessage.offer(false, message, reads));
        }
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 4, 0 found, 0 missing. Counted: no view mode 2, sent to be played once 2"), HookStatus.report());
    }

    @Test public void anUnmarkedOneIsntSaved() {
        for (String mode : new String[] {"", null}) {
            reads.mode = mode;
            assertTrue("taken, so Instagram's save doesn't throw on it", VoiceMessage.save(context, message, reads, save));
        }
        assertTrue(saved.isEmpty());
    }

    /** Only Meta's media servers count: a recording anywhere else, or a file on the phone, is never offered. */
    @Test public void aRecordingOffMetasServersKeepsInstagramsNo() {
        for (String address : new String[] {"https://example.com/voice.mp4", "/data/user/0/com.instagram.android/cache/voice.m4a"}) {
            reads.address = address;
            assertFalse(address, VoiceMessage.offer(false, message, reads));
        }
        assertEquals(Collections.singletonList(FamilyNames.VOICE_MESSAGE
                + ": invoked 2, 0 found, 0 missing. Counted: recording not on Meta's servers 2"), HookStatus.report());
    }

    @Test public void aReadThatThrowsKeepsInstagramsNo() {
        reads.broken = true;
        assertFalse(VoiceMessage.offer(false, message, reads));
        assertTrue(String.valueOf(HookStatus.report()), HookStatus.report().get(0).contains("voice message menu"));
    }

    @Test public void saveTakesAVoiceMessageAndSavesItsRecording() {
        assertTrue(VoiceMessage.save(context, message, reads, save));
        assertEquals(Collections.singletonList(RECORDING), saved);
    }

    /** Anything that isn't a voice message goes on to Instagram's own save, on or off. */
    @Test public void saveLeavesEverythingElseToInstagram() {
        reads.address = null;
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertFalse(VoiceMessage.save(context, null, reads, save));
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertTrue(saved.isEmpty());
    }

    /**
     * Instagram keeps each message's menu, so a Save offered while the switch was on can still be
     * tapped after it's turned off or HushGram pauses, or on one sent to be played once. Instagram's
     * own save throws on a voice message, so the message is still taken, and nothing is saved or
     * shown.
     */
    @Test public void aSaveLeftInTheMenuIsTakenAndSavesNothing() {
        Settings.DOWNLOAD_VOICE_MESSAGES.save(false);
        assertTrue("switch off", VoiceMessage.save(context, message, reads, save));
        Settings.DOWNLOAD_VOICE_MESSAGES.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", VoiceMessage.save(context, message, reads, save));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertTrue("settings unread", VoiceMessage.save(context, message, reads, save)));
        reads.mode = "once";
        assertTrue("played once", VoiceMessage.save(context, message, reads, save));
        assertTrue(saved.isEmpty());
        ShadowLooper.idleMainLooper();
        assertNull("nothing shown", ShadowToast.getTextOfLatestToast());
        assertTrue(String.valueOf(HookStatus.report()), HookStatus.report().get(0).contains(VoiceMessage.NOT_SAVED));
    }

    /** A view mode read that throws still keeps the voice message from Instagram's save. */
    @Test public void aModeReadThatThrowsAtSaveIsTakenAndSavesNothing() {
        VoiceMessage.Native throwing = new VoiceMessage.Native() {
            @Override public String audio(Object message) { return RECORDING; }
            @Override public String viewMode(Object message) { throw new ClassCastException("not voice media"); }
        };
        assertTrue(VoiceMessage.save(context, message, throwing, save));
        assertTrue(saved.isEmpty());
    }

    @Test public void aSaveThatCantStartSaysSo() {
        saveStarts = false;
        assertTrue("the message is still taken, so Instagram's save doesn't fail on it too",
                VoiceMessage.save(context, message, reads, save));
        ShadowLooper.idleMainLooper();
        assertEquals("Download failed", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aReadThatThrowsAtSaveLeavesItToInstagram() {
        reads.broken = true;
        assertFalse(VoiceMessage.save(context, message, reads, save));
        assertTrue(saved.isEmpty());
    }

    /** As built, with no patch, every read answers nothing, so no menu changes and every save is Instagram's. */
    @Test public void unpatchedEverythingIsInstagrams() {
        assertFalse(VoiceMessage.offer(0, message));
        assertFalse(VoiceMessage.save(context, message));
        assertNull(VoiceMessage.audio(message));
        assertNull(VoiceMessage.viewMode(message));
        assertTrue(HookStatus.report().isEmpty());
    }

    private static final class FakeNative implements VoiceMessage.Native {
        String address = RECORDING;
        String mode = "permanent";
        boolean broken;
        int calls;

        @Override public String audio(Object message) {
            calls++;
            if (broken) throw new ClassCastException("not a message");
            return address;
        }

        @Override public String viewMode(Object message) {
            calls++;
            return mode;
        }
    }
}
