/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

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
 * phone stays quiet and nothing is sent to Meta: the caller hears a call nobody picks up.
 *
 * <p>The call's object is one of Instagram's own data classes, and its text is the only thing read
 * from it. Only a ringing call to you counts ({@link #RINGING}): an ended call goes through, so a
 * ring that did start always clears, and so do live invites and calls moved from your other devices.
 *
 * <p>Fails open. Anything unexpected lets the call ring, and a call whose text can't be read is
 * reported, since a list that can't name the caller must never silence people you wanted to hear.
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

    private static final String CALLER_ID = ", callerId=";
    private static final String CALLER_NAME = ", callerName=";
    private static final String AFTER_NAME = ", displayUri=";

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
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_BLOCKER, STEP, failure);
            return false;
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
