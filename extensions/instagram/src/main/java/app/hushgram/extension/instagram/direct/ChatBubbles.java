/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Notification;
import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Chat bubbles (#747): a chat's message notification offers Android's bubble, so the chat can float
 * over whatever's on screen. Instagram already posts its chats as conversations, each with a
 * shortcut of its own; this gives each such notification bubble metadata pointing at that shortcut,
 * and Android does the rest: the bubble button on the notification, and the bubble itself where the
 * phone's bubble settings allow it. A hidden chat or one on a locked list never gets one.
 */
public final class ChatBubbles {
    static final String STEP = "chat bubble";
    /** Counted for each notification handed a bubble, for the diagnostics. */
    static final String BUBBLED = "bubble offered";
    /** Counted for a chat notification Instagram posted with no shortcut, which Android can't bubble. */
    static final String NO_SHORTCUT = "no chat shortcut";

    private ChatBubbles() {
    }

    /**
     * Called by the patch first in the push display, with the push's notifications and the ids it
     * carries, the way {@link ChatLocks#track} is: the notification, the group summary, the others it
     * posts with them, the deep link, the thread id, the thread IG id and the account it was sent to.
     * Changes the notifications in place and never throws.
     */
    public static void offer(Notification notification, Notification summary, Map<?, ?> others,
                             String action, String threadId, String igThreadId, String recipient) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_BUBBLES);
            if (!on()) return;
            Set<String> ids = new LinkedHashSet<>();
            addId(ids, ChatLocks.linkId(action));
            addId(ids, threadId);
            addId(ids, igThreadId);
            if (ids.isEmpty() || kept(ids, ChatAccounts.clean(recipient))) return;
            Context context = Utils.getContext();
            if (context == null) return;
            offer(context, notification);
            if (others != null) {
                for (Object each : others.values()) {
                    if (each instanceof Notification) offer(context, (Notification) each);
                }
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.CHAT_BUBBLES, STEP, t);
        }
    }

    static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.CHAT_BUBBLES.get();
    }

    /** A chat hidden on [account], or on its locked list, keeps its notification as Instagram made it. */
    static boolean kept(Set<String> ids, @Nullable String account) {
        if (HiddenChats.hides(ids, account)) return true;
        for (String id : ids) {
            if (ChatLocks.listed(account, id)) return true;
        }
        return false;
    }

    private static void offer(Context context, Notification notification) {
        if (bubble(context, notification)) {
            HookStatus.counted(FamilyNames.CHAT_BUBBLES, BUBBLED);
        } else if (notification != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && (notification.flags & Notification.FLAG_GROUP_SUMMARY) == 0 && empty(notification.getShortcutId())) {
            HookStatus.counted(FamilyNames.CHAT_BUBBLES, NO_SHORTCUT);
        }
    }

    /**
     * Gives [notification] bubble metadata for its own chat shortcut, in place, and says whether it
     * did. Below Android 11, a group summary, a notification with no shortcut and one that already
     * has bubble metadata stay as they are.
     */
    static boolean bubble(Context context, @Nullable Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || notification == null) return false;
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (notification.getBubbleMetadata() != null) return false;
        String shortcut = notification.getShortcutId();
        if (empty(shortcut)) return false;
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(shortcut).build();
        // recoverBuilder adopts the notification itself, so build() fills in this same object.
        Notification.Builder.recoverBuilder(context, notification).setBubbleMetadata(metadata).build();
        return notification.getBubbleMetadata() != null;
    }

    private static void addId(Set<String> ids, @Nullable String id) {
        if (!empty(id) && id.indexOf(',') < 0) ids.add(id.trim());
    }

    private static boolean empty(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }
}
