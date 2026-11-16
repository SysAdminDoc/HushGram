/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val RING_THICKNESS = "$EXTENSION_PACKAGE/stories/StoryRing;->thickness(F)F"

/**
 * The ring view's two public width setters, which keep their names: the ring around a story you
 * haven't watched, then the thinner one around a watched story. Each writes the one field of the
 * view that holds that width.
 */
internal val WIDTH_SETTERS = listOf("setActiveStrokeWidth", "setInactiveStrokeWidth")

private fun refuse(detail: String): Nothing = throw PatchException("Story ring size: $detail")

/** One place the ring view reads a ring's width: the method's name and parameter types, the read's index, and the register it lands in. */
internal class RingWidthRead(val name: String, val parameters: List<String>, val read: Int, val register: Int)

/**
 * Finds every place the ring view reads the width of its rings (#528). The view keeps the two widths
 * in the fields [WIDTH_SETTERS] write, sets its paints' stroke from them as it's built and drawn, and
 * measures its ring's inset from the wider. Every read the view makes of either is hooked, so the
 * paints and the inset always agree, and a thicker ring grows inward from where its outer edge was.
 *
 * The fields themselves are left alone: other code reads one width and hands it back to a setter
 * (454's `LX/0XLK;->A03` sets the inactive width from the active one), so a scaled field would scale
 * again each time. A read handed straight back, as the view's getters do, is left alone for the same
 * reason. A method of the view that writes a width after reading one would scale it again too, so it
 * fails the patch, as does a setter writing anything but one float field of the view.
 */
internal fun BytecodePatchContext.findRingWidthReads(): List<RingWidthRead> {
    val spinner = classDefByOrNull(GRADIENT_SPINNER) ?: refuse("the build has no $GRADIENT_SPINNER")
    val fields = WIDTH_SETTERS.map { spinner.widthSetBy(it) }
    if (fields.toSet().size != fields.size) refuse("$GRADIENT_SPINNER's width setters write one field, ${fields.first()}")
    val found = mutableListOf<RingWidthRead>()
    for (method in spinner.methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        fun touches(at: Int, opcode: Opcode) = code[at].opcode == opcode && code[at].field() in fields
        val reads = code.indices.filter { touches(it, Opcode.IGET) }
        if (reads.isEmpty()) continue
        if (code.indices.any { it > reads.first() && touches(it, Opcode.IPUT) }) {
            refuse("$GRADIENT_SPINNER->${method.name} writes a ring's width after reading one, so the patch would scale it again")
        }
        for (read in reads) {
            val register = (code[read] as TwoRegisterInstruction).registerA
            val next = code.getOrNull(read + 1)
            if (next?.opcode == Opcode.RETURN && (next as OneRegisterInstruction).registerA == register) continue
            found += RingWidthRead(method.name, method.parameterTypes.map(CharSequence::toString), read, register)
        }
    }
    if (found.isEmpty()) refuse("no method of $GRADIENT_SPINNER reads its rings' widths")
    return found
}

/** The one float field of the ring view that [setter] writes. */
private fun ClassDef.widthSetBy(setter: String): String {
    val setters = methods.filter {
        it.name == setter && !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("F")
    }
    val method = setters.singleOrNull() ?: refuse("expected one $GRADIENT_SPINNER->$setter(F)V, found ${setters.size}")
    val written = method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT }
        .mapNotNull { it.field() }
        .distinct()
    val field = written.singleOrNull()
        ?: refuse("$GRADIENT_SPINNER->$setter writes ${written.size} fields, expected the one holding its width")
    if (!field.startsWith("$GRADIENT_SPINNER->") || !field.endsWith(":F")) {
        refuse("$GRADIENT_SPINNER->$setter writes $field, not a float field of the ring view")
    }
    return field
}

private fun Instruction.field(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()

/**
 * Passes each width the ring view reads through [RING_THICKNESS], right after the read and ahead of
 * any label on the next instruction. Only the path through the read runs its hook: on 454 the
 * view's paint picker reads one width, or jumps there from reading the other, and each is scaled
 * once. The reads in a method go in from the last, so the indices found stay true.
 */
internal fun BytecodePatchContext.thickenRings(reads: List<RingWidthRead>) {
    val spinner = mutableClassDefBy(GRADIENT_SPINNER)
    for ((method, inMethod) in reads.groupBy { it.name to it.parameters }) {
        val target = spinner.methods.single {
            it.name == method.first && it.parameterTypes.map(CharSequence::toString) == method.second
        }
        for (read in inMethod.sortedByDescending { it.read }) {
            target.addInstructions(
                read.read + 1,
                """
                    invoke-static/range { v${read.register} .. v${read.register} }, $RING_THICKNESS
                    move-result v${read.register}
                """,
            )
        }
    }
}
