/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reminder

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val REMINDER_PATCH = "Hide the Community Standards reminder"
internal const val KINDNESS_REMINDER = "$EXTENSION_PACKAGE/direct/KindnessReminder;"
internal const val MAY_SHOW = "$KINDNESS_REMINDER->mayShow(I)I"
internal const val TIMES_SHOWN = "$KINDNESS_REMINDER->timesShown(I)I"

/** What the callback showing the reminder in a chat with one person logs once it's up. */
internal const val ONE_PERSON_EVENT = "direct_kindness_reminder"

/** The count a public chat reads to decide whether it has shown the reminder yet. */
internal const val PUBLIC_CHATS_SHOWN = "kindness_reminder_public_chat_shown"

/**
 * Keeps "Help keep Instagram a supportive place" off the bottom of a chat (#588). Included in the
 * default selection with its switch off, so it's the user's pick.
 */
@Suppress("unused")
val hideKindnessReminderPatch = bytecodePatch(
    name = "Hide the Community Standards reminder",
    description = "Takes the banner reminding you to be respectful off the bottom of a chat. Starts off. " +
        "Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("kindnessReminder")
        val onePerson = findOnePersonReminder()
        val publicChats = findPublicChatCount()
        keepOnePersonReminderOut(onePerson)
        keepPublicChatReminderOut(publicChats)
        enableStatus("kindnessReminder")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$REMINDER_PATCH: $why")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod {
    val types = method.parameterTypes.map(CharSequence::toString)
    return mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
}

/**
 * The callback a chat with one person answers once it knows about the other account, whose one
 * boolean says whether to show the reminder. Its first instruction is the test of that boolean, and
 * no is its ordinary way out.
 */
internal fun BytecodePatchContext.findOnePersonReminder(): MutableMethod {
    val found = classesLoadingString(ONE_PERSON_EVENT).flatMap { it.methods }.filter { loadsString(it, ONE_PERSON_EVENT) }
    val callback = found.singleOrNull() ?: refuse("expected one method loading \"$ONE_PERSON_EVENT\", found ${found.size}")
    val method = mutable(callback)
    val where = "${method.definingClass}->${method.name}"
    if (AccessFlags.STATIC.isSet(method.accessFlags)) refuse("$where is static")
    if (method.returnType != "V" || method.parameterTypes.map(CharSequence::toString) != listOf("Z")) {
        refuse("$where doesn't take one boolean and return nothing")
    }
    val flag = method.implementation!!.registerCount - 1
    val first = method.getInstruction(0)
    if (first.opcode != Opcode.IF_EQZ || (first as OneRegisterInstruction).registerA != flag) {
        refuse("$where doesn't start by testing its boolean")
    }
    if (0 in method.jumpTargets()) refuse("$where jumps back to its first instruction")
    return method
}

/** The boolean goes through the extension before anything reads it. */
internal fun keepOnePersonReminderOut(method: MutableMethod) {
    val flag = method.implementation!!.registerCount - 1
    method.addInstructions(
        0,
        """
            invoke-static/range { v$flag .. v$flag }, $MAY_SHOW
            move-result v$flag
        """,
    )
}

/** Where a public chat has just read its count: the hook goes at [index], and [register] holds the count. */
internal class PublicChatCount(val method: MutableMethod, val index: Int, val register: Int)

/**
 * The chat screen's big setup task, the one [Runnable] reading [PUBLIC_CHATS_SHOWN]. The count comes
 * from the session's preferences and goes straight into a compare, proved here before anything changes.
 */
internal fun BytecodePatchContext.findPublicChatCount(): PublicChatCount {
    val found = classesLoadingString(PUBLIC_CHATS_SHOWN).flatMap { it.methods }.filter {
        it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" && loadsString(it, PUBLIC_CHATS_SHOWN)
    }
    val run = found.singleOrNull() ?: refuse("expected one run() loading \"$PUBLIC_CHATS_SHOWN\", found ${found.size}")
    val method = mutable(run)
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val load = code.indices.firstOrNull { stringLoadedAt(code, it) == PUBLIC_CHATS_SHOWN }
        ?: refuse("$where doesn't load \"$PUBLIC_CHATS_SHOWN\" where it can be followed")
    // A pooled name comes back through its own move-result-object first.
    val reads = if (code.getOrNull(load + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) load + 2 else load + 1
    if (reads + 2 >= code.size) refuse("$where doesn't load \"$PUBLIC_CHATS_SHOWN\" where it can be followed")
    val read = code[reads]
    val reference = (read as? ReferenceInstruction)?.reference as? MethodReference
    if ((read.opcode != Opcode.INVOKE_INTERFACE && read.opcode != Opcode.INVOKE_INTERFACE_RANGE) ||
        reference?.returnType != "I" || reference.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/String;", "I")
    ) {
        refuse("$where doesn't read the count right after its name")
    }
    val result = code[reads + 1]
    if (result.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't keep the count")
    val register = (result as OneRegisterInstruction).registerA
    val index = reads + 2
    val compare = code[index]
    if (compare.opcode != Opcode.IF_GE || (compare as TwoRegisterInstruction).registerA != register) {
        refuse("$where doesn't compare the count next")
    }
    if (index in method.jumpTargets()) refuse("$where jumps onto its compare of the count")
    return PublicChatCount(method, index, register)
}

/** The count goes through the extension before the compare reads it. */
internal fun keepPublicChatReminderOut(count: PublicChatCount) {
    count.method.addInstructions(
        count.index,
        """
            invoke-static/range { v${count.register} .. v${count.register} }, $TIMES_SHOWN
            move-result v${count.register}
        """,
    )
}
