/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.screenshots

import app.morphe.patcher.Fingerprint
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
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

internal const val SCREENSHOT_BLOCK_PATCH = "Allow screenshots"
internal const val SCREENSHOT_BLOCK = "$EXTENSION_PACKAGE/direct/ScreenshotBlock;"
internal const val LIFT_BLOCK = "$SCREENSHOT_BLOCK->lift()Z"

/** WindowManager.LayoutParams.FLAG_SECURE: the window shows black in screenshots and recordings. */
internal const val FLAG_SECURE = 0x2000L
internal const val SET_WINDOW_FLAGS = "Landroid/view/Window;->setFlags(II)V"
internal const val ADD_WINDOW_FLAGS = "Landroid/view/Window;->addFlags(I)V"

/** Each window flag call and the extension's stand-in for it, which takes the window first. */
internal val WINDOW_FLAG_STAND_INS = mapOf(
    SET_WINDOW_FLAGS to "$SCREENSHOT_BLOCK->setFlags(Landroid/view/Window;II)V",
    ADD_WINDOW_FLAGS to "$SCREENSHOT_BLOCK->addFlags(Landroid/view/Window;I)V",
)

/** What the secure window helper logs when a window's flag and its count disagree. */
internal const val SECURE_STATE_CHECK = "Inconsistency in window FLAG_SECURE state detected! window state: "

internal fun Method.setsSecureFlag(): Boolean {
    val code = implementation?.instructions ?: return false
    return code.any { (it as? ReferenceInstruction)?.reference?.toString() == SET_WINDOW_FLAGS } &&
        code.any { (it as? WideLiteralInstruction)?.wideLiteral == FLAG_SECURE }
}

/** The indexes of [method]'s virtual calls to Window.setFlags or Window.addFlags. */
internal fun Method.windowFlagCalls(): List<Int> =
    implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            (instruction as ReferenceInstruction).reference.toString() in WINDOW_FLAG_STAND_INS
    }.map { it.index }

/**
 * The helper's (window, reason) method that marks a window secure and counts it: the one instance
 * method of that shape setting FLAG_SECURE through Window.setFlags. Its partner clearing the flag
 * uses clearFlags.
 */
internal object SecureWindowFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/Window;", "Ljava/lang/String;"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && method.setsSecureFlag() },
)

@Suppress("unused")
val allowScreenshotsPatch = bytecodePatch(
    name = "Allow screenshots",
    description = "Lets screenshots and screen recordings work wherever Instagram blocks them, like disappearing " +
        "photos and videos in your chats.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("screenshotBlock")
        val secure = findSecureWindow()
        routeWindowFlags()
        liftScreenshotBlock(secure)
        enableStatus("screenshotBlock")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$SCREENSHOT_BLOCK_PATCH: $why")

/**
 * Where Instagram's secure window helper marks a window. Proved before anything changes: nothing
 * jumps back to its first instruction, where the hook goes, and it has a local for the answer.
 */
internal fun BytecodePatchContext.findSecureWindow(): MutableMethod {
    val secure = uniqueMethod(SCREENSHOT_BLOCK_PATCH, "secure window helper", SecureWindowFingerprint)
    if (0 in secure.jumpTargets()) refuse("something jumps back to the secure window helper's first instruction")
    secure.requireLocals(SCREENSHOT_BLOCK_PATCH, 1)
    return secure
}

/**
 * The helper returns before it marks the window, or counts it, while the switch is on. Without
 * this its own check would find a counted window without the flag and report it.
 */
internal fun liftScreenshotBlock(secure: MutableMethod) {
    secure.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $LIFT_BLOCK
            move-result v0
            if-eqz v0, :secure
            return-void
        """,
        ExternalLabel("secure", secure.getInstruction(0)),
    )
}

/**
 * Every Window.setFlags and Window.addFlags call outside the extension goes to the extension's
 * stand-in instead, with the same registers, so the window comes first. Each call's stand-in is
 * written out before the first one moves, so a call in a form it can't move fails the patch with
 * nothing changed. Returns how many moved.
 */
internal fun BytecodePatchContext.routeWindowFlags(): Int {
    val callers = mutableListOf<Method>()
    val calling = WINDOW_FLAG_STAND_INS.keys.flatMapTo(HashSet()) { call ->
        classesCalling(call.substringBefore("->"), call.substringAfter("->").substringBefore("(")).map { it.type }
    }
    classDefForEach { classDef ->
        if (classDef.type !in calling) return@classDefForEach
        if (!classDef.type.startsWith(EXTENSION_ROOT)) classDef.methods.filterTo(callers) { it.windowFlagCalls().isNotEmpty() }
    }
    if (callers.isEmpty()) refuse("found no Window.setFlags or Window.addFlags call")
    val moves = callers.map { caller ->
        val code = caller.implementation!!.instructions.toList()
        caller to caller.windowFlagCalls().map { index -> index to caller.standInFor(code[index]) }
    }
    var routed = 0
    for ((caller, calls) in moves) {
        val method = mutableClassDefBy(caller.definingClass).methods.single {
            it.name == caller.name && it.returnType == caller.returnType &&
                it.parameterTypes.map(Any::toString) == caller.parameterTypes.map(Any::toString)
        }
        for ((index, smali) in calls) {
            method.replaceInstruction(index, smali)
            routed++
        }
    }
    return routed
}

/** The stand-in call for this method's window flag [call], on the same registers. */
private fun Method.standInFor(call: Instruction): String {
    val standIn = WINDOW_FLAG_STAND_INS.getValue((call as ReferenceInstruction).reference.toString())
    return when (call) {
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount)
            .joinToString(prefix = "invoke-static { ", postfix = " }, $standIn") { "v$it" }
        is RegisterRangeInstruction ->
            "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $standIn"
        else -> refuse("$definingClass->$name calls window flags in a form it can't move")
    }
}
