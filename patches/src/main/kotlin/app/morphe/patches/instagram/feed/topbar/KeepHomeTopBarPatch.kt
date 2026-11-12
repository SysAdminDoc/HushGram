/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.topbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.media.surface.videoSurfacePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep Home's top bar"
internal const val HOME_TOP_BAR_STATUS = "homeTopBar"
internal const val HOME_TOP_BAR = "$EXTENSION_PACKAGE/feed/HomeTopBar;"
internal const val SLIDING_VIEWS = "$HOME_TOP_BAR->slidingViews(Ljava/util/List;)Ljava/util/List;"

/**
 * The argument Home's fragment reads, and then drops, in the method that hands its header to the
 * scroll controller: on 453's 385914557 only MainFeedFragment (LX/04rY) A0g(Z) holds it.
 */
internal const val NAVBAR_HIDDEN_ARGUMENT = "was_previous_homecoming_tab_navbar_hidden"

private const val LIST = "Ljava/util/List;"

/**
 * Keeps Home's header in place while the feed scrolls (#223, #367, #408). See HomeTopBar in the
 * extension.
 *
 * Home wraps its header in a list of one and hands it, with a listener, the header's height and a
 * flag, to a controller (LX/04mZ->A0W on 453) that slides every view in the list as the feed
 * scrolls. The patch passes the list through the extension right before that call, and with the
 * switch on the controller gets an empty list, which is how Instagram itself leaves it when Home
 * lets go. The patch switches the status method on, and the settings screen then offers the
 * switch. In the default selection with its switch off.
 */
@Suppress("unused")
val keepHomeTopBarPatch = bytecodePatch(
    name = "Keep Home's top bar",
    description = "Keeps the Instagram logo and the buttons beside it at the top of Home while you scroll. " +
        "A second switch shows your feed blurred under them. Both start off. Turn them on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch, videoSurfacePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(HOME_TOP_BAR_STATUS)
        findHeaderHandOff().write(this)
        enableStatus(HOME_TOP_BAR_STATUS)
    }
}

/**
 * Where Home hands its header to the scroll controller: the method, the call's index and the
 * register holding the list of views.
 */
internal class HeaderHandOff(val type: String, val name: String, val shape: String, val call: Int, val list: Int) {
    /**
     * Puts the list through [SLIDING_VIEWS] right before the call, keeping the answer in the same
     * register. Nothing jumps to the call (checked when it was found), so every path runs the hook.
     */
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single { it.name == name && it.shape() == shape }
        method.addInstructions(
            call,
            """
                invoke-static/range { v$list .. v$list }, $SLIDING_VIEWS
                move-result-object v$list
            """,
        )
    }
}

/**
 * Finds the one call, in a method holding [NAVBAR_HIDDEN_ARGUMENT], to an instance method taking
 * (an object, a [LIST], a float, a boolean) and returning nothing: the scroll controller's setup.
 * The list must be what the instruction before the call just made (Home wraps its header there),
 * and nothing may jump to the call. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findHeaderHandOff(): HeaderHandOff {
    val calls = classesLoadingString(NAVBAR_HIDDEN_ARGUMENT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            if (code.indices.none { stringLoadedAt(code, it) == NAVBAR_HIDDEN_ARGUMENT }) return@flatMap emptyList()
            code.indices.filter { code[it].isControllerSetup() }.map { method to it }
        }
    }
    val (method, call) = calls.singleOrNull()
        ?: refuse("expected one call handing a list of views to the scroll controller, found ${calls.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val list = code[call].listRegister()
    if (list > 255) refuse("$where keeps the header's list in v$list, past v255")
    val made = code.getOrNull(call - 1)
    if (made?.opcode != Opcode.MOVE_RESULT_OBJECT || (made as OneRegisterInstruction).registerA != list) {
        refuse("$where doesn't make the header's list right before handing it over")
    }
    if (call in method.jumpTargets()) refuse("something in $where jumps to the hand-off, past where the hook would go")
    return HeaderHandOff(method.definingClass, method.name, method.shape(), call, list)
}

private fun Instruction.isControllerSetup(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val method = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return method.returnType == "V" && parameters.size == 4 && parameters[0].startsWith("L") &&
        parameters[1] == LIST && parameters[2] == "F" && parameters[3] == "Z"
}

/** The register of the call's second parameter, the list, after `this` and the listener. */
private fun Instruction.listRegister(): Int = when (this) {
    is FiveRegisterInstruction -> registerE
    is RegisterRangeInstruction -> startRegister + 2
    else -> refuse("the hand-off isn't a call with registers")
}

private fun Method.shape(): String = parameterTypes.joinToString("", "(", ")") + returnType

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
