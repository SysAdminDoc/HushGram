/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val KEEP_UNREAD = "$THREAD_SEEN->keepUnread(Ljava/lang/Object;Ljava/lang/Object;)Z"

/**
 * Two of the states Instagram's mutation queue reports a queued task in, as it spells them: sent,
 * and given up on for good.
 */
internal const val RECEIPT_SENT = "uploaded"
internal const val RECEIPT_GIVEN_UP = "upload_failed_permanent"

/** What the chat store logs when a seen state names a chat it doesn't hold. */
internal const val STORE_SEEN_MISSING = "Can't find thread to update seen messages."

private const val OBJECT_TYPE = "Ljava/lang/Object;"
private const val TEXT_TYPE = "Ljava/lang/String;"

internal class KeepUnreadTargets(
    /** Where the queue reports a chat receipt's state, with the chat store it passes it to. */
    val lifecycle: MutableMethod,
    /** The bridge reading the account off that store. */
    val bridge: ChatBridge,
)

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Method.parameters() = parameterTypes.map(Any::toString)
private fun Method.holds(text: String) = visualCode().any { it.visualString() == text }

/**
 * Resolve where a chat opened while its receipt is held stops being recorded seen on this phone,
 * before any edit. Instagram's chat screen queues the receipt, and the queue tells one static method
 * of each state the receipt goes through, with the chat store. While the receipt is queued or
 * running, that method has the store record the chat seen, which takes its unread mark off in the
 * inbox. Once it's [RECEIPT_SENT], the store keeps that for good, and once it's [RECEIPT_GIVEN_UP],
 * the store drops it. That method is the only static one taking the receipt [seen]'s handler casts
 * to, the store and the state's name, that names both states and calls the store through its
 * interface. The store's class is the one implementing that interface that logs
 * [STORE_SEEN_MISSING], and it keeps the session of its account, which the bridge reads so the
 * extension can match a mark made on that account.
 */
internal fun BytecodePatchContext.findKeepUnread(seen: ThreadSeenTargets): KeepUnreadTargets {
    val found = classesHolding(RECEIPT_GIVEN_UP, RECEIPT_SENT).flatMap { owner ->
        owner.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameters().let { it.size == 3 && it[0] == seen.mutation && it[2] == TEXT_TYPE } &&
                method.holds(RECEIPT_SENT) && method.holds(RECEIPT_GIVEN_UP)
        }
    }.one("static method the queue reports a chat receipt's state to")
    val storeType = found.parameters()[1]
    val store = classDefByOrNull(storeType) ?: refuse("chat store $storeType is missing")
    if (!AccessFlags.INTERFACE.isSet(store.accessFlags)) refuse("the queue reports a receipt's state to a chat store that isn't an interface")
    if (found.visualCode().none { it.opcode == Opcode.INVOKE_INTERFACE && (it.visualReference() as? MethodReference)?.definingClass == storeType }) {
        refuse("the receipt's state never reaches the chat store")
    }
    val lifecycle = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameters() == found.parameters() && it.returnType == found.returnType
    }
    lifecycle.requireLocals(THREAD_SEEN_PATCH, 2)
    if (0 in lifecycle.jumpTargets()) refuse("a jump reaches the start of the receipt's state report")

    val impl = classesLoadingString(STORE_SEEN_MISSING).filter { storeType in it.interfaces }.one("chat store class")
    val session = impl.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER_SESSION }
        .one("account the chat store keeps")
    requirePublic(mapOf(impl.type to impl), impl.type, session)

    val extension = classDefByOrNull(THREAD_SEEN) ?: refuse("extension is missing")
    requireHook(extension, "keepUnread", listOf(OBJECT_TYPE, OBJECT_TYPE), "Z")
    val bridge = chatBridges(listOf(
        Triple("storeSession", listOf(OBJECT_TYPE), OBJECT_TYPE) to """
            check-cast p0, ${impl.type}
            iget-object p0, p0, $session
            return-object p0
        """,
    )).single()
    return KeepUnreadTargets(lifecycle, bridge)
}

/**
 * Asks the extension at the start of the receipt's state report, with the receipt and the chat
 * store, whether the chat stays unread. A yes returns before the store hears any state of that
 * receipt: it isn't recorded seen while queued, kept seen once sent, or dropped once given up on,
 * which could otherwise undo a mark Mark as read just made. A no runs Instagram's report as it is.
 * Then writes the bridge's body.
 */
internal fun keepChatsUnread(found: KeepUnreadTargets) {
    found.lifecycle.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, $KEEP_UNREAD
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", found.lifecycle.getInstruction(0)),
    )
    found.bridge.stub.addInstructions(0, found.bridge.body)
}
