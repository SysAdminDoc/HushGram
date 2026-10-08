/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Download voice messages: Save in the menu you get by holding a voice message in a chat, which
 * saves the recording as an audio file through the same save as a post.
 *
 * <p>Instagram works out once per message which actions that menu may show. For Save it asks the
 * message's kind whether it can be saved, and a voice message always says no. The patch hands
 * {@link #offer} that answer and the message right after it's given, and a yes from here lets Save
 * in. Everything else Instagram checks before it shows Save, such as whether the chat allows it,
 * still decides. Only a voice message the server marks permanent gets the yes: one marked to be
 * played once or replayed, one with no mark, and one whose recording isn't on Meta's media servers
 * keep Instagram's no. Instagram's own check for a photo or video sent in a chat asks the same
 * thing, an explicit "permanent", and a voice message from the server carries that mark.
 *
 * <p>A tap on Save hands the message to Instagram's saver, which only knows photos and videos and
 * throws on a voice message. The saver's entry asks {@link #save} first: a voice message is always
 * taken here, and its recording saved when Save could have been offered for it now, and anything
 * else goes on to Instagram's own save.
 */
public final class VoiceMessage {
    private VoiceMessage() {
    }

    /** Instagram's reads, each one native call. */
    interface Native {
        /** The address of the message's recording, or null when it isn't a voice message. */
        String audio(Object message);

        /** The view mode the server gave the message's recording, or null. */
        String viewMode(Object message);
    }

    interface Save {
        boolean audio(Context context, String url, PostDetails details);
    }

    private static final Native NATIVE = new Native() {
        public String audio(Object message) { return VoiceMessage.audio(message); }
        public String viewMode(Object message) { return VoiceMessage.viewMode(message); }
    };

    private static final Save SAVE = MediaSave::saveAudio;

    // What the diagnostic report counts when a voice message's menu is worked out. Fixed text:
    // nothing read from the message goes in.
    static final String OFFERED = "Save offered";
    static final String PLAYED_ONCE = "sent to be played once";
    static final String NO_MODE = "no view mode";
    static final String NOT_SAVED = "Save tapped, nothing saved";
    static final String NOT_META = "recording not on Meta's servers";

    /** The view mode of a recording anyone in the chat can play again, as every voice message used to be. */
    static final String PERMANENT = "permanent";

    /**
     * Whether a message's menu may offer Save: [allowed], Instagram's answer for this kind of
     * message, read as non-zero for yes, or a voice message's yes while the switch is on. Never
     * throws.
     */
    public static boolean offer(int allowed, Object message) {
        return offer(allowed != 0, message, NATIVE);
    }

    static boolean offer(boolean allowed, Object message, Native reads) {
        if (allowed) return true;
        try {
            if (message == null || !on()) return false;
            String address = reads.audio(message);
            if (address == null) return false;
            HookStatus.invoked(FamilyNames.VOICE_MESSAGE);
            String mode = reads.viewMode(message);
            if (!permanent(mode)) {
                HookStatus.counted(FamilyNames.VOICE_MESSAGE, mode == null || mode.isEmpty() ? NO_MODE : PLAYED_ONCE);
                return false;
            }
            if (MediaUrlPolicy.shapeRefusal(address) != null) {
                HookStatus.counted(FamilyNames.VOICE_MESSAGE, NOT_META);
                return false;
            }
            HookStatus.counted(FamilyNames.VOICE_MESSAGE, OFFERED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "voice message menu", failure);
            return false;
        }
    }

    /**
     * Takes [message] when it's a voice message, so Instagram's own save never gets one, and answers
     * whether it took it. The recording is saved only when Save could be offered for the message now.
     * Instagram works out each message's menu once and keeps it, so a Save offered before the switch
     * went off or HushGram paused can still be tapped; that tap is taken too, and nothing is saved or
     * shown. Anything that isn't a voice message goes on to Instagram's own save. [context] is the
     * chat's. Never throws.
     */
    public static boolean save(Context context, Object message) {
        return save(context, message, NATIVE, SAVE);
    }

    static boolean save(Context context, Object message, Native reads, Save save) {
        if (message == null) return false;
        String address;
        try {
            address = reads.audio(message);
            if (address == null) return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "voice message check", failure);
            return false;
        }
        boolean keep;
        try {
            keep = on() && permanent(reads.viewMode(message));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "voice message check", failure);
            keep = false;
        }
        if (!keep) {
            HookStatus.counted(FamilyNames.VOICE_MESSAGE, NOT_SAVED);
            return true;
        }
        try {
            if (!save.audio(context, address, PostDetails.NONE)) failed(context);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "save voice message", failure);
            failed(context);
        }
        return true;
    }

    /**
     * Whether a recording with [viewMode] can be kept: only one marked permanent. No mark, or one
     * this doesn't know, counts as not permanent, so a recording sent to be played once is never
     * offered or saved because its mark was missing or new.
     */
    static boolean permanent(String viewMode) {
        return PERMANENT.equals(viewMode);
    }

    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_VOICE_MESSAGES.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "voice message switch", t);
            return false;
        }
    }

    /**
     * The address of [message]'s recording, the source Instagram's player reads from its voice
     * media, or null when it has none. The patch writes the body as Instagram's own reads; as built
     * it answers null.
     */
    @SuppressWarnings({"unused", "SameReturnValue"})
    public static String audio(Object message) {
        return null;
    }

    /**
     * The {@code view_mode} the server gave [message]'s voice media, or null. The patch writes the
     * body as a read of the field; as built it answers null.
     */
    @SuppressWarnings({"unused", "SameReturnValue"})
    public static String viewMode(Object message) {
        return null;
    }

    /** Download failed, in the phone's language. Never throws. */
    private static void failed(Context context) {
        try {
            Context application = context == null ? null : context.getApplicationContext();
            if (application != null) Feedback.show(application, L10n.t(application, "Download failed"), true);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VOICE_MESSAGE, "save feedback", t);
        }
    }
}
