/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.bubbles

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.direct.lock.PushDisplay
import app.morphe.patches.instagram.direct.lock.findPushDisplay
import app.morphe.patches.instagram.direct.lock.hookFirst
import app.morphe.patches.instagram.feed.confirm.publicStatic
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val BUBBLES_PATCH = "Chat bubbles"

internal const val CHAT_BUBBLES = "$EXTENSION_PACKAGE/direct/ChatBubbles;"

/** What the push display hands the extension: the push's two notifications, the others it posts, and its labels. */
internal const val CHAT_BUBBLE = "$CHAT_BUBBLES->offer(Landroid/app/Notification;Landroid/app/Notification;" +
    "Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/**
 * Gives a chat's message notification Android's bubble, on the conversation shortcut Instagram
 * already sets on it (#747). Included in the default selection with its switch off, so bubbles
 * are the user's pick.
 */
@Suppress("unused")
val chatBubblesPatch = bytecodePatch(
    name = "Chat bubbles",
    description = "Lets a chat's message notification open as an Android bubble that floats over other apps " +
        "(Android 11 or newer). Hidden and locked chats never get one. Starts off. Turn it on in HushGram " +
        "settings > Notifications.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("chatBubbles")
        val display = findChatBubbles()
        display.hookFirst(display.hookCalling(CHAT_BUBBLE, answers = false))
        enableStatus("chatBubbles")
    }
}

/**
 * The push display the lock hooks too, checked the same way, once the extension is known to have
 * the hook. The hook reads the push before Instagram posts it, so the bubble goes out with the
 * notification, and answers nothing: a push it skips goes on as it was.
 */
internal fun BytecodePatchContext.findChatBubbles(): PushDisplay {
    val extension = classDefByOrNull(CHAT_BUBBLES) ?: throw PatchException("$BUBBLES_PATCH: the extension has no $CHAT_BUBBLES")
    extension.publicStatic(CHAT_BUBBLE) ?: throw PatchException("$BUBBLES_PATCH: the extension has no public static $CHAT_BUBBLE")
    return findPushDisplay(BUBBLES_PATCH)
}
