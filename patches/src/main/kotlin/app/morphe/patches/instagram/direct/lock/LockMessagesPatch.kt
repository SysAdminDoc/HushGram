/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.notifications.NOTIFICATION_GROUPS
import app.morphe.patches.instagram.misc.notifications.NOTIFICATION_MANAGER
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val LOCK_PATCH = "Lock your messages"
internal const val MESSAGES_LOCK = "$EXTENSION_PACKAGE/direct/MessagesLock;"
internal const val HIDE_NOTIFICATION = "$MESSAGES_LOCK->notification(Landroid/app/Notification;)Landroid/app/Notification;"
internal const val HOLD_BANNER = "$MESSAGES_LOCK->holdBanner()Z"

/** What androidx's NotificationManagerCompat.notify checks first: Instagram posts every notification through it. */
internal const val SIDE_CHANNEL = "android.support.useSideChannel"

/** What Instagram's in-app banner logs when no activity can show it, first thing in the method that shows one. */
internal const val NO_BANNER_ACTIVITY = "no foreground activity to render in-app notification"

internal val NOTIFY_PARAMETERS = listOf("Ljava/lang/String;", "I", "Landroid/app/Notification;")

/** notify(tag, id, notification): the instance method that checks [SIDE_CHANNEL]. */
internal object NotifyFingerprint : Fingerprint(
    returnType = "V",
    parameters = NOTIFY_PARAMETERS,
    strings = listOf(SIDE_CHANNEL),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The static method that shows the banner, (Context, notification, the banner's owner). */
internal object BannerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(NO_BANNER_ACTIVITY),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 3 &&
            method.parameterTypes.first().toString() == "Landroid/content/Context;"
    },
)

@Suppress("unused")
val lockMessagesPatch = bytecodePatch(
    name = "Lock your messages",
    description = "Covers your inbox and chats, all of Instagram, or just the single chats you pick under Locked chats, " +
        "until your fingerprint, face or screen lock says it's you. Message notifications only say that a message " +
        "came. Chats you pick under Hidden chats leave your inbox and their notifications don't post. Starts off. " +
        "Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("messagesLock")
        val targets = findLockTargets()
        val posts = findDirectPosts(targets.notify)
        val chats = findChatLockTargets()
        val hidden = findHiddenChatTargets()
        val search = findSearchTargets()
        hideNotificationText(targets.notify)
        hideDirectPosts(posts)
        holdBanner(targets.banner)
        hookChatLocks(chats)
        hideChatsFromInbox(hidden)
        hideChatsFromSearch(search)
        enableStatus("messagesLock")
    }
}

internal class LockTargets(val notify: MutableMethod, val banner: MutableMethod)

private fun refuse(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

/**
 * Both methods, proved before either changes: each must have no jump back to its first
 * instruction, where the hook goes, and the banner needs a local for its answer.
 */
internal fun BytecodePatchContext.findLockTargets(): LockTargets {
    val notify = uniqueMethod(LOCK_PATCH, "notification poster", NotifyFingerprint)
    val banner = uniqueMethod(LOCK_PATCH, "in-app banner", BannerFingerprint)
    if (0 in notify.jumpTargets()) refuse("something jumps back to the notification poster's first instruction")
    if (0 in banner.jumpTargets()) refuse("something jumps back to the in-app banner's first instruction")
    banner.requireLocals(LOCK_PATCH, 1)
    return LockTargets(notify, banner)
}

/** The notification goes through the extension first, which hands back a copy without the message while locked. */
internal fun hideNotificationText(notify: MutableMethod) {
    val notification = notify.parameterRegister(NOTIFY_PARAMETERS.indexOf("Landroid/app/Notification;"))
    notify.addInstructions(
        0,
        """
            invoke-static/range { $notification .. $notification }, $HIDE_NOTIFICATION
            move-result-object $notification
        """,
    )
}

/** The banner returns before it takes its lock or builds anything while the messages are locked. */
internal fun holdBanner(banner: MutableMethod) {
    banner.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_BANNER
            move-result v0
            if-eqz v0, :show
            return-void
        """,
        ExternalLabel("show", banner.getInstruction(0)),
    )
}

/** A post's notification comes last after these, for Android's notify and for Group notifications' stand-in alike. */
private val POST_PARAMETERS = listOf(listOf("I", "Landroid/app/Notification;"), NOTIFY_PARAMETERS)

/**
 * A post of a notification: NotificationManager.notify, or the stand-in Group Instagram's
 * notifications puts in its place, which takes the manager first and the same parameters after.
 */
internal fun Instruction.postsNotification(): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.name != "notify" || call.returnType != "V") return false
    val parameters = call.parameterTypes.map(CharSequence::toString)
    return when (call.definingClass) {
        NOTIFICATION_MANAGER -> (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) && parameters in POST_PARAMETERS
        NOTIFICATION_GROUPS -> (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
            parameters.firstOrNull() == NOTIFICATION_MANAGER && parameters.drop(1) in POST_PARAMETERS
        else -> false
    }
}

/** The registers a call reads, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The call as smali, to put back after the hook. */
private fun Instruction.smali(): String {
    val call = (this as ReferenceInstruction).reference as MethodReference
    val reference = "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    val name = when (opcode) {
        Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
        Opcode.INVOKE_VIRTUAL_RANGE -> "invoke-virtual/range"
        Opcode.INVOKE_STATIC -> "invoke-static"
        Opcode.INVOKE_STATIC_RANGE -> "invoke-static/range"
        else -> error("not a post: $opcode")
    }
    val registers = registers()
    return if (this is RegisterRangeInstruction) "$name { v${registers.first()} .. v${registers.last()} }, $reference"
    else "$name { ${registers.joinToString { "v$it" }} }, $reference"
}

/**
 * Each method of Instagram's that posts a notification outside the poster, where it posts, and for
 * a post whose notification register is read again afterwards, the spare local that keeps the
 * original for those reads.
 */
internal class DirectPosts(val method: MutableMethod, val sites: List<Int>, val spares: Map<Int, Int> = emptyMap())

private fun Method.sameAs(other: Method) = definingClass == other.definingClass && name == other.name &&
    returnType == other.returnType && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/**
 * Every place in Instagram's code that posts a notification straight to Android rather than through
 * the poster, like the update an inline reply posts: found and checked before anything changes.
 * Each post's notification must sit in a register a result can go back to.
 *
 * Only the post may see the copy. Where the method reads the notification's register again after
 * the post (to log it, keep it, or post it once more), the original is kept in a spare local over
 * the post and put back right after, so those reads get Instagram's own notification. The put-back
 * runs only when the post returns, so a handler of the post that reads the register fails the patch.
 * On 450 nothing reads the register after any direct post, so none needs a spare.
 */
internal fun BytecodePatchContext.findDirectPosts(poster: Method): List<DirectPosts> {
    val types = (classesCalling(NOTIFICATION_MANAGER, "notify") + classesCalling(NOTIFICATION_GROUPS, "notify"))
        .map { it.type }.distinct()
    val posts = types.flatMap { type ->
        mutableClassDefBy(type).methods.mapNotNull { method ->
            if (method.sameAs(poster)) return@mapNotNull null
            val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
            val sites = code.indices.filter { code[it].postsNotification() }
            val spares = mutableMapOf<Int, Int>()
            sites.forEach { index ->
                val notification = code[index].registers().last()
                val where = "${method.definingClass}->${method.name}"
                if (notification > 255) refuse("$where posts a notification from v$notification, past v255")
                if (method.readsAfter(index, notification).isEmpty()) return@forEach
                val liveness = RegisterLiveness.of(method)
                if (ControlFlow.of(method).exceptional[index].any { notification in liveness.liveInto(it) }) {
                    refuse("$where reads the notification in v$notification when its post at instruction $index throws")
                }
                spares[index] = method.freeLocalsAt(LOCK_PATCH, index, 1, highest = 255).single()
            }
            if (sites.isEmpty()) null else DirectPosts(method, sites, spares)
        }
    }
    if (posts.isEmpty()) refuse("found no notification Instagram posts outside the poster")
    return posts
}

/**
 * Each direct post hands its notification to the extension first, which hands back a copy without
 * the message while the messages are locked. The post is replaced rather than preceded, because a
 * jump to it lands on what replaces it and would skip anything put in front; it's written back
 * right after, on the same registers. Where the register is read after the post, the original goes
 * into its spare first and comes back right after the post.
 */
internal fun hideDirectPosts(posts: List<DirectPosts>) {
    posts.forEach { post ->
        post.sites.sortedDescending().forEach { index ->
            val call = post.method.getInstruction(index)
            val notification = call.registers().last()
            val again = call.smali()
            val hook = "invoke-static/range { v$notification .. v$notification }, $HIDE_NOTIFICATION"
            val spare = post.spares[index]
            if (spare == null) {
                post.method.replaceInstruction(index, hook)
                post.method.addInstructions(
                    index + 1,
                    """
                        move-result-object v$notification
                        $again
                    """,
                )
            } else {
                post.method.replaceInstruction(index, "move-object/from16 v$spare, v$notification")
                post.method.addInstructions(
                    index + 1,
                    """
                        $hook
                        move-result-object v$notification
                        $again
                        move-object/from16 v$notification, v$spare
                    """,
                )
            }
        }
    }
}
