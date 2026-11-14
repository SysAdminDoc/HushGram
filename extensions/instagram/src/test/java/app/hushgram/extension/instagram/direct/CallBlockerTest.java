/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

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
        Settings.CALL_BLOCK_DECLINE.resetToDefault();
        CallBlocker.forgetForTests();
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

    /** A ringing call that, like Instagram's, can travel in an intent, with a call key. */
    static final class Ringing implements Parcelable {
        final String text;

        Ringing(String id, String name, String key) {
            text = "RtcCallConnectionEntity(state=Incoming, recipientUserId=1, threadId=2, callerId=" + id
                    + ", callerName=" + name + ", displayUri=https://x/y.jpg, isAudioCall=true, callKey=" + key + ")";
        }

        @Override public String toString() {
            return text;
        }

        @Override public int describeContents() {
            return 0;
        }

        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeString(text);
        }
    }

    private static ShadowApplication started() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private static void assertDeclined(Intent intent, Object call) {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(CallBlocker.DECLINE_ACTION, intent.getAction());
        assertEquals(new ComponentName(context.getPackageName(), CallBlocker.DECLINE_SERVICE), intent.getComponent());
        assertSame("the call object itself, as the notification's Decline sends it",
                call, intent.getExtras().getParcelable(CallBlocker.CALL_EXTRA));
    }

    @Test public void declineStartsOffSoABlockedCallIsOnlyKeptQuiet() {
        assertFalse(Settings.CALL_BLOCK_DECLINE.get());
        choose(CallBlockMode.NOBODY, "");
        assertTrue(CallBlocker.drop(new Ringing("42", "someone", "k1")));
        assertNull(started().getNextStartedService());
    }

    @Test public void aBlockedCallIsDeclinedThroughInstagramsOwnDecline() {
        choose(CallBlockMode.EXCEPT_LIST, "pest");
        Settings.CALL_BLOCK_DECLINE.save(true);
        Ringing call = new Ringing("42", "pest", "k1");
        assertTrue("it still doesn't ring", CallBlocker.drop(call));
        assertDeclined(started().getNextStartedService(), call);
        assertNull(started().getNextStartedService());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(CallBlocker.DECLINED));
        assertFalse(report, report.contains(CallBlocker.NOT_DECLINED));
    }

    @Test public void aCallThatRingsIsNeverDeclined() {
        Settings.CALL_BLOCK_DECLINE.save(true);
        assertFalse(CallBlocker.drop(new Ringing("42", "someone", "k1")));
        choose(CallBlockMode.EXCEPT_LIST, "pest");
        assertFalse(CallBlocker.drop(new Ringing("43", "friend", "k2")));
        assertNull(started().getNextStartedService());
    }

    @Test public void aCallRungTwiceIsDeclinedOnce() {
        choose(CallBlockMode.NOBODY, "");
        Settings.CALL_BLOCK_DECLINE.save(true);
        assertTrue(CallBlocker.drop(new Ringing("42", "someone", "k1")));
        assertTrue(CallBlocker.drop(new Ringing("42", "someone", "k1")));
        assertTrue(started().getNextStartedService() != null);
        assertNull("the second ring of one call", started().getNextStartedService());
        assertTrue(CallBlocker.drop(new Ringing("42", "someone", "k2")));
        assertTrue("the next call from them", started().getNextStartedService() != null);
    }

    @Test public void theSameCallIsDeclinedAgainOnceAMinuteHasPassed() {
        Context context = RuntimeEnvironment.getApplication();
        Ringing call = new Ringing("42", "someone", "k1");
        CallBlocker.decline(context, call, call.text, 1_000L);
        CallBlocker.decline(context, call, call.text, 1_000L + CallBlocker.SAME_CALL_MS - 1);
        CallBlocker.decline(context, call, call.text, 1_000L + CallBlocker.SAME_CALL_MS + 1);
        assertTrue(started().getNextStartedService() != null);
        assertTrue(started().getNextStartedService() != null);
        assertNull(started().getNextStartedService());
    }

    @Test public void aDeclineAndroidRefusesIsCountedAndTriedAgain() {
        Context refusing = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public ComponentName startService(Intent service) {
                throw new IllegalStateException("Not allowed to start service Intent: app is in background");
            }
        };
        Ringing call = new Ringing("42", "someone", "k1");
        CallBlocker.decline(refusing, call, call.text, 1_000L);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(CallBlocker.NOT_DECLINED));
        CallBlocker.decline(RuntimeEnvironment.getApplication(), call, call.text, 2_000L);
        assertDeclined(started().getNextStartedService(), call);
    }

    @Test public void aCallThatCantTravelStaysQuietUndeclined() {
        choose(CallBlockMode.NOBODY, "");
        Settings.CALL_BLOCK_DECLINE.save(true);
        assertTrue(CallBlocker.drop(call("Incoming", "42", "someone")));
        assertNull(started().getNextStartedService());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(CallBlocker.NOT_DECLINED));
    }

    @Test public void pausedHushGramNeitherKeepsQuietNorDeclines() {
        choose(CallBlockMode.NOBODY, "");
        Settings.CALL_BLOCK_DECLINE.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(CallBlocker.drop(new Ringing("42", "someone", "k1")));
        assertNull(started().getNextStartedService());
    }

    @Test public void aCallIsToldApartByItsCallKey() {
        String first = new Ringing("42", "someone", "RtcCallKey(callKey=a)").text;
        String renamed = new Ringing("42", "someone else", "RtcCallKey(callKey=a)").text;
        String next = new Ringing("42", "someone", "RtcCallKey(callKey=b)").text;
        assertEquals(CallBlocker.callKey(first), CallBlocker.callKey(renamed));
        assertNotEquals(CallBlocker.callKey(first), CallBlocker.callKey(next));
        assertEquals("without one, the whole text", "x(state=Incoming)", CallBlocker.callKey("x(state=Incoming)"));
    }

    @Test public void betweenReadsNothingForMissingOrNull() {
        assertNull(CallBlocker.between("a=1", "b=", ","));
        assertNull(CallBlocker.between("a=null, b", "a=", ","));
        assertEquals("1", CallBlocker.between("a=1, b", "a=", ","));
    }
}
