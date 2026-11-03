/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The extension's bridge for a session's account id, in [INSTAGRAM_CHATS]. */
internal const val ACCOUNT_ID = "accountId"

/** The account id a signed-in session answers, by the name Instagram keeps for it. */
private const val GET_USER_ID = "getUserId"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * The extension's account id bridge and the body that goes in it: the session's own getUserId().
 * "Read messages without the seen receipt", "Lock your messages" and "Preview chats" need it, and any
 * of them can be in a build without the others, so each finds it for itself and [write] puts the body
 * in once, whichever of them gets there first.
 */
internal class AccountIdBridge(val stub: MutableMethod, val getter: String) {
    val body = """
        check-cast p0, $USER_SESSION
        invoke-virtual { p0 }, $getter
        move-result-object p0
        return-object p0
    """

    /** The stub already starts with the body, as the other patch wrote it. */
    fun written(): Boolean {
        val code = stub.implementation?.instructions?.toList() ?: return false
        if (code.size < 2 || code[0].opcode != Opcode.CHECK_CAST || code[1].opcode != Opcode.INVOKE_VIRTUAL) return false
        val cast = ((code[0] as ReferenceInstruction).reference as? TypeReference)?.type
        val call = (code[1] as ReferenceInstruction).reference as? MethodReference ?: return false
        return cast == USER_SESSION && "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" == getter
    }

    /** Writes the body unless it's there already. */
    fun write() {
        if (!written()) stub.addInstructions(0, body)
    }
}

/**
 * The session's account id and the extension's stub for it, found for [patch] before anything
 * changes. Refuses, in [patch]'s name, a build whose session class is missing or an interface, or
 * doesn't answer one public account id, and an extension without the stub.
 */
internal fun BytecodePatchContext.findAccountId(patch: String): AccountIdBridge {
    fun refuse(why: String): Nothing = throw PatchException("$patch: $why")
    val account = classDefByOrNull(USER_SESSION) ?: refuse("$USER_SESSION is missing")
    if (AccessFlags.INTERFACE.isSet(account.accessFlags)) refuse("$USER_SESSION is an interface")
    val getters = account.methods.filter {
        it.name == GET_USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val getter = getters.singleOrNull() ?: refuse("expected one account id $USER_SESSION answers, found ${getters.size}")
    val key = "$USER_SESSION->$GET_USER_ID()$STRING"
    if (!AccessFlags.PUBLIC.isSet(account.accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        refuse("$key isn't public, so the extension can't reach it")
    }
    val chats = classDefByOrNull(INSTAGRAM_CHATS) ?: refuse("extension has no $INSTAGRAM_CHATS")
    val stubs = mutableClassDefBy(chats).methods.filter {
        it.name == ACCOUNT_ID && it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && it.returnType == STRING &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }
    val stub = stubs.singleOrNull() ?: refuse("expected one extension's chat bridge $ACCOUNT_ID, found ${stubs.size}")
    return AccountIdBridge(stub, key)
}
