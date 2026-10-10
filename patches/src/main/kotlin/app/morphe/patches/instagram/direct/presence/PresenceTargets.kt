/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val ACTIVE_STATUS = "$EXTENSION_PACKAGE/direct/ActiveStatus;"
internal const val SEND_STATUS = "$ACTIVE_STATUS->status(Ljava/lang/Object;)Ljava/lang/Object;"

/** Meta's presence model, which keeps its names in every 450 build. */
internal const val PRESENCE_MODEL = "Lcom/facebook/presence/model/upi/"
internal const val PRESENCE_WRITE_REQUEST = "${PRESENCE_MODEL}PresenceWriteRequest;"
internal const val PRESENCE_STATUS = "${PRESENCE_MODEL}PresenceStatus;"

/** The write request's parameters, in order. Its status is the third. */
internal val PRESENCE_WRITE_PARAMETERS = listOf(
    "${PRESENCE_MODEL}AppState;",
    "${PRESENCE_MODEL}PresencePollingMode;",
    PRESENCE_STATUS,
    "${PRESENCE_MODEL}PresenceWriteRequestType;",
    "Ljava/lang/Long;",
    "Ljava/lang/String;",
)
internal const val STATUS_PARAMETER = 2

/** The names the status enum gives Active and Idle, which the extension goes by. */
internal const val ACTIVE_NAME = "ACTIVE"
internal const val IDLE_NAME = "IDLE"

/**
 * The write request's constructor that Instagram's presence writer calls for every status it sends.
 * Kotlin's serializer has another, taking a bit mask too, which the parameter count leaves out.
 */
internal object PresenceWriteFingerprint : Fingerprint(
    definingClass = PRESENCE_WRITE_REQUEST,
    name = "<init>",
    returnType = "V",
    parameters = PRESENCE_WRITE_PARAMETERS,
)

private fun refuse(why: String): Nothing = throw PatchException("$ACTIVE_STATUS_PATCH: $why")

/**
 * Resolve the write request's constructor and prove what the hook relies on, before any edit: the
 * status parameter goes, untouched since entry, into the request's one status field; nothing jumps
 * to the first instruction; the status enum names Active and Idle the way the extension reads them;
 * and the extension has its method.
 */
internal fun BytecodePatchContext.findPresenceWrite(): MutableMethod {
    val constructor = uniqueMethod(ACTIVE_STATUS_PATCH, "presence write request constructor", PresenceWriteFingerprint)
    if (AccessFlags.STATIC.isSet(constructor.accessFlags)) refuse("the presence write request constructor is static")
    val code = constructor.implementation?.instructions?.toList() ?: refuse("the presence write request constructor has no body")
    val status = constructor.parameterRegisterNumber(STATUS_PARAMETER)
    val stores = code.indices.filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.definingClass == PRESENCE_WRITE_REQUEST && field.type == PRESENCE_STATUS
    }
    val storeAt = stores.singleOrNull() ?: refuse("expected one store of the status field, found ${stores.size}")
    if ((code[storeAt] as TwoRegisterInstruction).registerA != status) refuse("the status field isn't stored from the status parameter")
    constructor.requireParameterIntact(ACTIVE_STATUS_PATCH, STATUS_PARAMETER, listOf(storeAt))
    if (0 in constructor.jumpTargets()) refuse("a jump or exception handler enters the presence write request constructor at its first instruction")

    val names = classDefByOrNull(PRESENCE_STATUS)?.methods?.filter { it.name == "<clinit>" }
        ?.flatMap { it.implementation?.instructions?.toList().orEmpty() }
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet()
        ?: refuse("no presence status enum")
    if (ACTIVE_NAME !in names || IDLE_NAME !in names) refuse("the presence status enum doesn't name $ACTIVE_NAME and $IDLE_NAME")

    classDefByOrNull(ACTIVE_STATUS)?.methods?.singleOrNull {
        it.name == "status" && it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("extension has no public static status(Ljava/lang/Object;)Ljava/lang/Object;")
    return constructor
}
