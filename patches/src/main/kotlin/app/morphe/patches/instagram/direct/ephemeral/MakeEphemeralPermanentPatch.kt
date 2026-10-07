/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.ephemeral

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.privacy.code
import app.morphe.patches.instagram.privacy.describe
import app.morphe.patches.instagram.privacy.exactlyOne
import app.morphe.patches.instagram.privacy.fieldReference
import app.morphe.patches.instagram.privacy.methodsHolding
import app.morphe.patches.instagram.privacy.mutable
import app.morphe.patches.instagram.privacy.refuse
import app.morphe.patches.instagram.privacy.stringLoaded
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val PATCH = "Make ephemeral media permanent"
private const val EPHEMERAL = "$EXTENSION_PACKAGE/direct/EphemeralMedia;"
internal const val MODE = "$EPHEMERAL->mode(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/String;"

/** The names a view-once message's JSON carries, which Instagram keeps. */
internal const val EXPIRES = "url_expire_at_secs"
internal const val VIEW_MODE = "view_mode"
internal val MESSAGE_FIELDS = arrayOf(EXPIRES, VIEW_MODE, "seen_count", "tap_models")

@Suppress("unused")
val makeEphemeralPermanentPatch = bytecodePatch(
    name = "Make ephemeral media permanent",
    description = "A view-once or replay-once photo or video that hasn't expired opens like any other, as often " +
        "as you like. It only changes how this phone treats it. Has its own switch in HushGram's settings.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("ephemeralMedia")
        keepViewable(findMessageParser())
        enableStatus("ephemeralMedia")
    }
}

/** The parser, its return and the registers: the returned message, and the two fields the hook reads and writes. */
internal class ParserSite(
    val method: Method,
    val returnAt: Int,
    val message: Int,
    val expires: String,
    val mode: String,
)

/**
 * The parser that reads a view-once message from its JSON is the one method that loads all four of
 * [MESSAGE_FIELDS] and answers an object. It stores each field it reads in the object it returns:
 * the expiry time right after it loads [EXPIRES] (a Long field) and the view mode right after it
 * loads [VIEW_MODE] (a String field). It returns in one place.
 */
internal fun BytecodePatchContext.findMessageParser(): ParserSite {
    val method = methodsHolding(*MESSAGE_FIELDS).filter { it.returnType == "Ljava/lang/Object;" }
        .exactlyOne(PATCH, "parser of a view-once message")
    val code = method.code()

    /** The store that follows the load of [name]: the field written and the register of the object it's written in. */
    fun storeAfter(name: String, type: String): Pair<String, Int> {
        val loads = code.indices.filter { code[it].stringLoaded() == name }
        val load = loads.exactlyOne(PATCH, "load of \"$name\" in ${method.describe()}")
        val store = (load + 1 until code.size).firstOrNull { index ->
            val put = code[index]
            put.opcode == Opcode.IPUT_OBJECT && put.fieldReference()?.type == type
        } ?: refuse(PATCH, "${method.describe()} never stores \"$name\" in an object")
        return code[store].fieldReference().toString() to (code[store] as TwoRegisterInstruction).registerB
    }

    val (expires, expiresIn) = storeAfter(EXPIRES, "Ljava/lang/Long;")
    val (mode, modeIn) = storeAfter(VIEW_MODE, "Ljava/lang/String;")
    if (expiresIn != modeIn) refuse(PATCH, "the expiry and the view mode are stored in different objects in ${method.describe()}")
    if (expires.substringBefore("->") != mode.substringBefore("->")) {
        refuse(PATCH, "the expiry and the view mode are stored in different classes in ${method.describe()}")
    }
    // The parser has an early return for a malformed message; the one that hands back the filled-in
    // object is the return of the register those fields were stored in.
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == modeIn
    }
    val returnAt = returns.exactlyOne(PATCH, "return of the parsed message in ${method.describe()}")
    return ParserSite(method, returnAt, modeIn, expires, mode)
}

/**
 * The return becomes the first instruction of the hook, so whatever jumps to the return runs it too:
 * the expiry and the view mode are read back from the message, handed to the extension, and the answer
 * is stored as the new view mode before the message is returned.
 */
internal fun BytecodePatchContext.keepViewable(site: ParserSite) {
    val method = mutable(site.method)
    val (expires, mode) = method.freeLocalsAt(PATCH, site.returnAt, 2)
    method.replaceInstruction(site.returnAt, "iget-object v$expires, v${site.message}, ${site.expires}")
    method.addInstructions(
        site.returnAt + 1,
        """
            iget-object v$mode, v${site.message}, ${site.mode}
            invoke-static { v$expires, v$mode }, $MODE
            move-result-object v$mode
            iput-object v$mode, v${site.message}, ${site.mode}
            return-object v${site.message}
        """,
    )
}
