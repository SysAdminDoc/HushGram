/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.bubbles

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val ACTIVITY_BUBBLES_PATCH = "Hide activity bubbles"
internal const val ACTIVITY_BUBBLES = "$EXTENSION_PACKAGE/feed/ActivityBubbles;"
internal const val HIDE_BUBBLES = "$ACTIVITY_BUBBLES->hide(I)Z"

/**
 * The experiment Instagram checks as it makes the account's activity bubble setting. It decides
 * whether the account gets Instagram's own Hide activity bubbles switch.
 */
internal const val BUBBLE_EXPERIMENT =
    "android_purge_26_q2_FriendlyViewerExperimentUtil_hasHideActivityBubblesSettingWithNoProfileException"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

@Suppress("unused")
val hideActivityBubblesPatch = bytecodePatch(
    name = "Hide activity bubbles",
    description = "Takes the little round pictures off posts and reels that show a friend liked, commented on or " +
        "reshared them. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("activityBubbles")
        hideActivityBubbles(findBubbleSetting())
        enableStatus("activityBubbles")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$ACTIVITY_BUBBLES_PATCH: $why")

/** A read the hook follows: the method, the read's index and the register it fills. */
internal class BubbleRead(val method: Method, val index: Int, val register: Int)

/**
 * Where Instagram reads the account's activity bubble setting: [check] is its static check for a
 * post, reading first whether the account has the setting ([has]) and last the setting ([hide]),
 * and [reads] are every read the hook follows, the check's two among them.
 */
internal class BubbleSetting(val check: Method, val has: FieldReference, val hide: FieldReference, val reads: List<BubbleRead>)

/**
 * The class holding the account's activity bubble setting is the one whose constructor checks
 * [BUBBLE_EXPERIMENT]. Its one static (UserSession, ...)Z method is Instagram's check for a post: it
 * reads whether the account has the setting, leaves with false when it doesn't, and ends returning
 * the setting. Every other read of the setting skips a post's friend pictures when it's true.
 * Proved before anything changes: the check reads exactly two of its class's flags, the first
 * tested right away and the last returned right away, and nothing jumps onto the instruction after
 * any read the hook follows.
 */
internal fun BytecodePatchContext.findBubbleSetting(): BubbleSetting {
    val holders = classesLoadingString(BUBBLE_EXPERIMENT).filter { classDef ->
        classDef.methods.any { it.name == "<init>" && loadsString(it, BUBBLE_EXPERIMENT) }
    }
    val holder = holders.singleOrNull()
        ?: refuse("expected one class checking \"$BUBBLE_EXPERIMENT\" as it's made, found ${holders.size}")
    val checks = holder.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.firstOrNull()?.toString() == USER_SESSION
    }
    val check = checks.singleOrNull() ?: refuse("${holder.type} has ${checks.size} static (UserSession, ...)Z checks, not one")
    val where = "${check.definingClass}->${check.name}"
    val code = check.instructions()
    val own = code.indices.filter { code[it].opcode == Opcode.IGET_BOOLEAN && code[it].field().definingClass == holder.type }
    if (own.size != 2) refuse("$where reads ${own.size} of ${holder.type}'s flags, not two")
    val (gate, last) = own
    val has = code[gate].field()
    val hide = code[last].field()
    if (has.name == hide.name) refuse("$where reads ${has.name} twice")
    val gateRegister = (code[gate] as TwoRegisterInstruction).registerA
    val test = code.getOrNull(gate + 1)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != gateRegister) {
        refuse("$where doesn't test ${has.name} right after reading it")
    }
    val hideRegister = (code[last] as TwoRegisterInstruction).registerA
    val leave = code.getOrNull(last + 1)
    if (leave?.opcode != Opcode.RETURN || (leave as OneRegisterInstruction).registerA != hideRegister) {
        refuse("$where doesn't return ${hide.name} right after reading it")
    }
    val reads = listOf(BubbleRead(check, gate, gateRegister)) + readsOf(holder.type, hide)
    for (read in reads) {
        if (read.index + 1 in read.method.jumpTargets()) {
            refuse("something in ${read.method.definingClass}->${read.method.name} jumps onto the instruction after its read at ${read.index}")
        }
    }
    return BubbleSetting(check, has, hide, reads)
}

/** Every read of [hide] in Instagram, as the method, the read's index and its register. */
private fun BytecodePatchContext.readsOf(holder: String, hide: FieldReference): List<BubbleRead> =
    classesAccessing(holder, hide.name, Opcode.IGET_BOOLEAN).flatMap { it.methods }.flatMap { method ->
        val code = method.instructions()
        code.indices.filter { at ->
            val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
            code[at].opcode == Opcode.IGET_BOOLEAN && field?.definingClass == holder && field.name == hide.name
        }.map { BubbleRead(method, it, (code[it] as TwoRegisterInstruction).registerA) }
    }

/**
 * Each read hands what it read to [HIDE_BUBBLES], which answers true while the switch is on: the
 * check then finds the setting there and on, and every other read finds it on.
 */
internal fun BytecodePatchContext.hideActivityBubbles(setting: BubbleSetting) {
    for (reads in setting.reads.groupBy { it.method.signature() }.values) {
        val method = reads.first().method
        val types = method.parameterTypes.map(CharSequence::toString)
        val mutable = mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(CharSequence::toString) == types
        }
        // From the bottom up, so each read's index still holds when its hook goes in.
        for (read in reads.sortedByDescending { it.index }) {
            val register = "v${read.register}"
            mutable.addInstructions(
                read.index + 1,
                """
                    invoke-static/range { $register .. $register }, $HIDE_BUBBLES
                    move-result $register
                """,
            )
        }
    }
}

private fun Method.signature(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.field(): FieldReference = (this as ReferenceInstruction).reference as FieldReference

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
