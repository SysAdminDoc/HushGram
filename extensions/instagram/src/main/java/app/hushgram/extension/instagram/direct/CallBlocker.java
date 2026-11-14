/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryPriority;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Who can ring your phone with an Instagram call (#578).
 *
 * <p>Instagram turns every incoming call into its ring and full-screen screen in one method, and the
 * patch asks {@link #drop} first with the call's object. A yes returns before Instagram rings, so the
 * phone stays quiet and, unless the decline below is on, nothing is sent to Meta: the caller hears a
 * call nobody picks up.
 *
 * <p>The call's object is one of Instagram's own data classes, and its text is the only thing read
 * from it. Only a ringing call to you counts ({@link #RINGING}): an ended call goes through, so a
 * ring that did start always clears, and so do live invites and calls moved from your other devices.
 *
 * <p>With {@link Settings#CALL_BLOCK_DECLINE} on, a call that doesn't ring is also declined the way
 * the Decline button on Instagram's call notification does it: the same intent, to the same
 * service, carrying the same call object. Instagram then tells the caller, as it does for a tap.
 *
 * <p>Fails open. Anything unexpected lets the call ring, and a call whose text can't be read is
 * reported, since a list that can't name the caller must never silence people you wanted to hear.
 * A decline Android refuses to start is counted, and the call just doesn't ring.
 */
public final class CallBlocker {
    /** The step a failure is reported under. */
    static final String STEP = "incoming call";

    /** What's counted each time a call doesn't ring. */
    static final String STOPPED = "incoming calls stopped";

    /** What's counted when a ringing call's caller couldn't be read. */
    static final String UNREADABLE = "incoming call callers unreadable";

    /** How the text of a call that's ringing starts, and the class name that says it's a call at all. */
    static final String CALL_CLASS = "RtcCallConnectionEntity";
    static final String RINGING = CALL_CLASS + "(state=Incoming,";

    /** What's counted each time a call that didn't ring is declined too. */
    static final String DECLINED = "incoming calls declined";

    /** What's counted when a call that didn't ring couldn't be declined. It stays quiet anyway. */
    static final String NOT_DECLINED = "incoming calls not declined";

    /** The service behind the Decline button on Instagram's call notification, and what it reads. */
    static final String DECLINE_SERVICE = "com.instagram.rtc.signaling.notifications.service.RtcCallActionIntentHandlerService";
    static final String DECLINE_ACTION = "DECLINE";
    static final String CALL_EXTRA = "com.instagram.rtc.notifications.service.entity";

    /** How long a call already declined isn't declined again, when Instagram rings it twice. */
    static final long SAME_CALL_MS = 60_000L;

    private static final String CALLER_ID = ", callerId=";
    private static final String CALLER_NAME = ", callerName=";
    private static final String AFTER_NAME = ", displayUri=";
    private static final String CALL_KEY = ", callKey=";

    /** The calls declined lately, by {@link #callKey}, with when. */
    private static final Map<String, Long> DECLINED_AT = new HashMap<>();

    private CallBlocker() {
    }

    /** Asked first with each call Instagram is about to ring. True means don't ring. Never throws. */
    public static boolean drop(@Nullable Object call) {
        try {
            HookStatus.invoked(FamilyNames.CALL_BLOCKER);
            if (!Utils.settingsReady()) return false;
            CallBlockMode mode = Settings.CALL_BLOCK_MODE.get();
            if (call == null || mode == CallBlockMode.EVERYONE) return false;
            String text = String.valueOf(call);
            if (!text.startsWith(RINGING)) return false;
            if (!drops(mode, text, list(mode))) return false;
            HookStatus.counted(FamilyNames.CALL_BLOCKER, STOPPED);
            if (Settings.CALL_BLOCK_DECLINE.get()) decline(Utils.getContext(), call, text, System.currentTimeMillis());
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_BLOCKER, STEP, failure);
            return false;
        }
    }

    /**
     * Declines [call], a ringing call that won't ring, through the service the Decline button on
     * Instagram's call notification starts. Once per call within {@link #SAME_CALL_MS}. Never throws.
     */
    static void decline(@Nullable Context context, Object call, String text, long now) {
        if (context == null || !(call instanceof Parcelable)) {
            HookStatus.counted(FamilyNames.CALL_BLOCKER, NOT_DECLINED);
            return;
        }
        String key = callKey(text);
        synchronized (DECLINED_AT) {
            for (Iterator<Long> at = DECLINED_AT.values().iterator(); at.hasNext(); ) {
                if (now - at.next() >= SAME_CALL_MS) at.remove();
            }
            if (DECLINED_AT.containsKey(key)) return;
            DECLINED_AT.put(key, now);
        }
        Bundle extras = new Bundle();
        extras.putParcelable(CALL_EXTRA, (Parcelable) call);
        Intent intent = new Intent(DECLINE_ACTION).setClassName(context.getPackageName(), DECLINE_SERVICE).putExtras(extras);
        ComponentName started;
        try {
            started = context.startService(intent);
        } catch (IllegalStateException | SecurityException refused) {
            // Android refuses a service started from the background outside the moments it allows.
            started = null;
        }
        if (started != null) {
            HookStatus.counted(FamilyNames.CALL_BLOCKER, DECLINED);
            return;
        }
        // Not declined, so a ring of the same call later tries again.
        synchronized (DECLINED_AT) {
            DECLINED_AT.remove(key);
        }
        HookStatus.counted(FamilyNames.CALL_BLOCKER, NOT_DECLINED);
    }

    /** What tells one call from another in its text: its call key, or the whole text without one. */
    static String callKey(String text) {
        int at = text.indexOf(CALL_KEY);
        return at < 0 ? text : text.substring(at);
    }

    /** Forgets the calls declined lately, as a new process would. */
    static void forgetForTests() {
        synchronized (DECLINED_AT) {
            DECLINED_AT.clear();
        }
    }

    private static List<String> list(CallBlockMode mode) {
        return mode.usesList() ? StoryPriority.accounts(Settings.CALL_BLOCK_ACCOUNTS.get()) : Collections.emptyList();
    }

    /** Whether a ringing call with this text stays quiet. A caller the list can't be checked against rings. */
    static boolean drops(CallBlockMode mode, String text, List<String> list) {
        if (mode == CallBlockMode.NOBODY) return true;
        String name = StoryPriority.accounts(between(text, CALLER_NAME, AFTER_NAME)).stream().findFirst().orElse(null);
        String id = between(text, CALLER_ID, ", ");
        boolean known = name != null || id != null;
        if (!known) {
            HookStatus.counted(FamilyNames.CALL_BLOCKER, UNREADABLE);
            return false;
        }
        boolean listed = (name != null && list.contains(name)) || (id != null && list.contains(id));
        return mode == CallBlockMode.ONLY_LIST ? !listed : listed;
    }

    /** What sits between two markers in [text], or null when there's nothing there or it says null. */
    @Nullable
    static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) return null;
        from += start.length();
        int to = text.indexOf(end, from);
        if (to <= from) return null;
        String value = text.substring(from, to).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }
}
