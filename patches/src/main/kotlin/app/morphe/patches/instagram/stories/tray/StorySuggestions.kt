/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested stories"

internal const val STORY_NETEGO = "$EXTENSION_PACKAGE/stories/StoriesTray;->netego(Ljava/lang/String;)Z"

/** What the stories viewer's netego mapper throws for a netego type it doesn't know. Only it loads this. */
internal const val UNSUPPORTED_NETEGO = "Unsupported story netego type: "

/** The netego type of the Suggested for you card between stories, which the mapper's class names. */
internal const val SUGGESTED_USERS_NETEGO = "suggested_users_in_story"

/** What the code taking ads and netegos into the stories viewer reports for one that isn't valid. */
internal const val INVALID_NETEGO = "invalid_ad_or_netego_reel_response_item"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val STRING = "Ljava/lang/String;"

/**
 * The viewer's check of an ad or netego item, and the item interface's getter of a netego's type. Some
 * 454 builds have no check method for netegos: Redex copied its netego half into each asker, so
 * [inlined] lists those askers, each reading the netego's kind through [reader] in place, and [type]
 * and [name] are the reader's.
 */
internal class NetegoCheck(
    val type: String,
    val name: String,
    val typeGetter: String,
    val reader: String? = null,
    val inlined: List<InlinedRead> = emptyList(),
)

/** An asker of the viewer's netego read, by class, name and parameters, and where it calls the reader. */
internal class InlinedRead(val type: String, val name: String, val parameters: List<String>, val at: Int)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the check the stories viewer asks before it takes in an ad or a netego (a unit Instagram
 * puts between stories, such as Suggested for you). The chain, each step exactly once:
 * the static `(String)Integer` mapper loading [UNSUPPORTED_NETEGO], in a class naming
 * [SUGGESTED_USERS_NETEGO]; the one static `(ReelResponseItemIntf)Integer` calling it (454 has two
 * more callers of other shapes, where Redex copied that read in), which hands it what a String
 * getter of the item answered, the netego's type; and the one static
 * `(UserSession, ReelResponseItemIntf)Z` calling that, the check, asked by code loading
 * [INVALID_NETEGO]. Some 454 builds copy the check's netego half into its askers instead: then each
 * method loading [INVALID_NETEGO] calls the reader once and leaves the item out on a null, and those
 * reads are what get guarded. Fails before anything changes when any of it isn't so, since that's an
 * update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryNetegoCheck(): NetegoCheck {
    val mappers = classesLoadingString(UNSUPPORTED_NETEGO).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, UNSUPPORTED_NETEGO) }
    }
    val mapper = mappers.singleOrNull() ?: refuse("expected one method holding \"$UNSUPPORTED_NETEGO\", found ${mappers.size}")
    val mapperAt = "${mapper.definingClass}->${mapper.name}"
    if (!mapper.isStatic() || mapper.parameters() != listOf(STRING) || mapper.returnType != INTEGER) {
        refuse("$mapperAt(${mapper.parameters().joinToString("")})${mapper.returnType} isn't a netego type mapper")
    }
    if (classesLoadingString(SUGGESTED_USERS_NETEGO).none { it.type == mapper.definingClass }) {
        refuse("the class of $mapperAt doesn't name $SUGGESTED_USERS_NETEGO")
    }

    // 454 inlines that read into the reel's own netego build and a list helper too, which take or answer something else.
    val askers = callersOf(mapper)
    if (askers.isEmpty()) refuse("nothing asks $mapperAt")
    val readers = askers.filter { it.isStatic() && it.parameters() == listOf(TRAY_ITEM_INTF) && it.returnType == INTEGER }
    val reader = readers.singleOrNull()
        ?: refuse("expected one static ($TRAY_ITEM_INTF)$INTEGER asking $mapperAt, found ${readers.size} of ${askers.size} askers")
    val readerAt = "${reader.definingClass}->${reader.name}"
    val getters = reader.calls().filter {
        it.definingClass == TRAY_ITEM_INTF && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.map { it.toString() }.distinct()
    val getter = getters.singleOrNull() ?: refuse("expected $readerAt to read one String off the story item, found $getters")

    val checks = callersOf(reader).filter {
        it.isStatic() && it.parameters() == listOf(USER_SESSION, TRAY_ITEM_INTF) && it.returnType == "Z"
    }
    if (checks.isEmpty()) {
        val inlined = inlinedReads(reader)
        if (inlined.isNotEmpty()) return NetegoCheck(reader.definingClass, reader.name, getter, reader.toString(), inlined)
    }
    val check = checks.singleOrNull()
        ?: refuse("expected one check of a story item asking $readerAt, found ${checks.size}, and no method holding \"$INVALID_NETEGO\" reads it in place")
    val checkAt = "${check.definingClass}->${check.name}"
    if (callersOf(check).none { loadsString(it, INVALID_NETEGO) }) refuse("nothing asking $checkAt holds \"$INVALID_NETEGO\"")
    if (0 in check.jumpTargets()) refuse("something in $checkAt jumps back to its start")
    return NetegoCheck(check.definingClass, check.name, getter)
}

/**
 * The methods loading [INVALID_NETEGO] that call [reader] themselves: each once, its answer taken and
 * tested for null straight after, nothing jumping to that test. Empty when none does; a method that
 * reads it another way stops the patch.
 */
private fun BytecodePatchContext.inlinedReads(reader: Method): List<InlinedRead> =
    callersOf(reader).filter { loadsString(it, INVALID_NETEGO) }.map { asker ->
        val askerAt = "${asker.definingClass}->${asker.name}"
        val code = asker.implementation!!.instructions.toList()
        val calls = code.indices.filter { at ->
            val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
            code[at].opcode == Opcode.INVOKE_STATIC && called != null && called.definingClass == reader.definingClass &&
                called.name == reader.name && called.parameterTypes.map(CharSequence::toString) == reader.parameters()
        }
        val at = calls.singleOrNull() ?: refuse("expected $askerAt to read the netego once, found ${calls.size}")
        val taken = code.getOrNull(at + 1) as? OneRegisterInstruction
        val test = code.getOrNull(at + 2) as? OneRegisterInstruction
        if (taken == null || code[at + 1].opcode != Opcode.MOVE_RESULT_OBJECT || test == null || code[at + 2].opcode != Opcode.IF_EQZ ||
            test.registerA != taken.registerA
        ) {
            refuse("$askerAt doesn't leave the item out when the netego read answers null")
        }
        if (at + 2 in asker.jumpTargets()) refuse("something in $askerAt jumps to its netego test")
        InlinedRead(asker.definingClass, asker.name, asker.parameters(), at)
    }

/**
 * First thing in the check: reads the item's netego type with the getter and asks [STORY_NETEGO].
 * A yes answers the check no, so the viewer leaves the item out the way it leaves out one that
 * isn't valid. A no runs Instagram's own check. At the top no local is live yet, so v0 is free.
 */
internal fun BytecodePatchContext.hideStorySuggestions(check: NetegoCheck) {
    if (check.inlined.isNotEmpty()) return check.inlined.forEach { hideInlinedRead(it, check) }
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, TRAY_ITEM_INTF)
    }
    method.requireLocals(PATCH, 1)
    val item = method.parameterRegisterNumber(1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-interface/range { v$item .. v$item }, ${check.typeGetter}
            move-result-object v0
            invoke-static { v0 }, $STORY_NETEGO
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}

/**
 * Between an asker's netego read and its null test: a netego [STORY_NETEGO] says to hide reads as
 * null, so the asker leaves it out the way it leaves out one that isn't valid. Any other answer reads
 * the netego again, as Instagram did. Only the read's own register is used, and the item is still in
 * the register the read took.
 */
private fun BytecodePatchContext.hideInlinedRead(read: InlinedRead, check: NetegoCheck) {
    val method = mutableClassDefBy(read.type).methods.single {
        it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
    }
    val item = (method.getInstruction(read.at) as FiveRegisterInstruction).registerC
    val answer = (method.getInstruction(read.at + 1) as OneRegisterInstruction).registerA
    method.addInstructionsWithLabels(
        read.at + 2,
        """
            if-eqz v$answer, :instagram
            invoke-interface/range { v$item .. v$item }, ${check.typeGetter}
            move-result-object v$answer
            invoke-static/range { v$answer .. v$answer }, $STORY_NETEGO
            move-result v$answer
            if-nez v$answer, :hidden
            invoke-static/range { v$item .. v$item }, ${check.reader}
            move-result-object v$answer
            goto :instagram
            :hidden
            const/16 v$answer, 0x0
        """,
        ExternalLabel("instagram", method.getInstruction(read.at + 2)),
    )
}

/** The methods outside the extension calling [target], by its class, name and parameters. */
private fun BytecodePatchContext.callersOf(target: Method): List<Method> =
    classesCalling(target.definingClass, target.name).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.calls().any {
                it.definingClass == target.definingClass && it.name == target.name &&
                    it.parameterTypes.map(CharSequence::toString) == target.parameters() && it.returnType == target.returnType
            }
        }
    }

private fun Method.calls(): List<MethodReference> = implementation?.instructions
    ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
