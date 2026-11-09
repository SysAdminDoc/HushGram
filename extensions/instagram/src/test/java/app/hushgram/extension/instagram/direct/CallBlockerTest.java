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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Call blocker's answers, on call text shaped the way Instagram's call object writes it (#578). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CallBlockerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After public void end() {
        Settings.CALL_BLOCK_MODE.resetToDefault();
        Settings.CALL_BLOCK_ACCOUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static Object call(String state, String id, String name) {
        String text = "RtcCallConnectionEntity(state=" + state + ", recipientUserId=1, threadId=2, callerId=" + id
                + ", callerName=" + name + ", displayUri=https://x/y.jpg, isAudioCall=true)";
        return new Object() {
            @Override public String toString() {
                return text;
            }
        };
    }

    private static void choose(CallBlockMode mode, String list) {
        Settings.CALL_BLOCK_MODE.save(mode);
        Settings.CALL_BLOCK_ACCOUNTS.save(list);
    }

    @Test public void everyoneRingsToStart() {
        assertEquals(CallBlockMode.EVERYONE, Settings.CALL_BLOCK_MODE.get());
        assertFalse(CallBlocker.drop(call("Incoming", "42", "someone")));
    }

    @Test public void nobodyStopsEveryRingingCall() {
        choose(CallBlockMode.NOBODY, "");
        assertTrue(CallBlocker.drop(call("Incoming", "42", "someone")));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(CallBlocker.STOPPED));
    }

    @Test public void onlyTheListRings() {
        choose(CallBlockMode.ONLY_LIST, "@Friend\nsecond");
        assertFalse("capitals and the @ don't matter", CallBlocker.drop(call("Incoming", "42", "friend")));
        assertFalse(CallBlocker.drop(call("Incoming", "43", "Second")));
        assertTrue("anyone else doesn't ring", CallBlocker.drop(call("Incoming", "44", "stranger")));
    }

    @Test public void everyoneButTheListRings() {
        choose(CallBlockMode.EXCEPT_LIST, "pest");
        assertTrue(CallBlocker.drop(call("Incoming", "42", "PEST")));
        assertFalse(CallBlocker.drop(call("Incoming", "43", "friend")));
    }

    @Test public void aNumericIdOnTheListCounts() {
        choose(CallBlockMode.EXCEPT_LIST, "1234567");
        assertTrue(CallBlocker.drop(call("Incoming", "1234567", "whoever")));
    }

    @Test public void aNameWithSpacesAndCommasReadsWhole() {
        choose(CallBlockMode.EXCEPT_LIST, "Jo Smith");
        assertTrue(CallBlocker.drop(call("Incoming", "42", "Jo Smith")));
    }

    @Test public void anEndedCallAlwaysGoesThrough() {
        choose(CallBlockMode.NOBODY, "");
        assertFalse(CallBlocker.drop(call("Ended", "42", "someone")));
        assertFalse(CallBlocker.drop(call("Missed", "42", "someone")));
    }

    @Test public void aTransferFromYourOtherDeviceGoesThrough() {
        choose(CallBlockMode.NOBODY, "");
        assertFalse(CallBlocker.drop(call("IncomingTransfer", "42", "someone")));
    }

    @Test public void otherCallKindsGoThrough() {
        choose(CallBlockMode.NOBODY, "");
        assertFalse(CallBlocker.drop(new Object() {
            @Override public String toString() {
                return "EndCallConnectionEntity(state=Incoming, reason=x)";
            }
        }));
        assertFalse(CallBlocker.drop(null));
    }

    @Test public void aCallerThatCantBeReadRingsAndIsReported() {
        choose(CallBlockMode.ONLY_LIST, "friend");
        assertFalse(CallBlocker.drop(call("Incoming", "null", "null")));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(CallBlocker.UNREADABLE));
    }

    @Test public void anEmptyListStillMeansTheChoice() {
        choose(CallBlockMode.ONLY_LIST, "");
        assertTrue("only your list, and it's empty", CallBlocker.drop(call("Incoming", "42", "someone")));
        choose(CallBlockMode.EXCEPT_LIST, "");
        assertFalse(CallBlocker.drop(call("Incoming", "42", "someone")));
    }

    @Test public void pausedHushGramRings() {
        choose(CallBlockMode.NOBODY, "");
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(CallBlocker.drop(call("Incoming", "42", "someone")));
    }

    @Test public void aThrowingCallRings() {
        choose(CallBlockMode.NOBODY, "");
        assertFalse(CallBlocker.drop(new Object() {
            @Override public String toString() {
                throw new IllegalStateException("boom");
            }
        }));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(CallBlocker.STEP));
    }

    @Test public void betweenReadsNothingForMissingOrNull() {
        assertNull(CallBlocker.between("a=1", "b=", ","));
        assertNull(CallBlocker.between("a=null, b", "a=", ","));
        assertEquals("1", CallBlocker.between("a=1, b", "a=", ","));
    }
}
