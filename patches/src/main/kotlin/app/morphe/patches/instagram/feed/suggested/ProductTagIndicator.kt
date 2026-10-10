/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested posts"

internal const val PRODUCT_TAGS = "$EXTENSION_PACKAGE/feed/ProductTags;"
internal const val PRODUCT_TAG_INDICATOR = "$PRODUCT_TAGS->indicator(I)I"

/** The name Instagram gives the indicator of a post with products tagged in it, its shopping bag. */
internal const val PRODUCTS = "PRODUCTS"

/** Another indicator's name. With [PRODUCTS] it picks the indicator enum out of the app. */
internal const val SHOPPING_ADS = "SHOPPING_ADS"

/**
 * The product indicator's check, found: the method picking a post's indicator, the index of its
 * branch on whether the post's products get it, and the register the answer is in. [write] hands
 * that answer to ProductTags.indicator on its way to the branch.
 */
internal class ProductTagIndicator(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val branchAt: Int,
    val register: Int,
) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // At the branch's own label, so nothing reaches the branch without the extension's answer.
        method.addInstructionsAtControlFlowLabel(
            branchAt,
            """
                invoke-static { v$register }, $PRODUCT_TAG_INDICATOR
                move-result v$register
            """,
        )
    }
}

/**
 * Finds where Instagram picks the products indicator for a post. The indicator enum is the one
 * enum naming both [PRODUCTS] and [SHOPPING_ADS], its products constant is the field its static
 * initializer writes right after loading that name, and in the whole app exactly one method hands
 * that constant back: an instance method taking a post and answering the enum, which reads it
 * straight after a static check's yes. On 450's 385611438 that's LX/04pB->A06 answering LX/04rH.A0I
 * after LX/04rE->A00. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findProductTagIndicator(): ProductTagIndicator {
    val enums = classesHolding(PRODUCTS, SHOPPING_ADS).filter { it.superclass == "Ljava/lang/Enum;" }
    val enum = enums.singleOrNull() ?: refuse("expected one enum naming $PRODUCTS and $SHOPPING_ADS, found ${enums.map { it.type }}")
    val clinit = enum.methods.singleOrNull { it.name == "<clinit>" } ?: refuse("${enum.type} has no static initializer")
    val init = clinit.implementation?.instructions?.toList().orEmpty()
    val named = init.indices.filter { stringLoadedAt(init, it) == PRODUCTS }
    val nameAt = named.singleOrNull() ?: refuse("${enum.type} loads $PRODUCTS ${named.size} times, expected once")
    val writeAt = (nameAt + 1 until init.size).firstOrNull { at ->
        init[at].opcode == Opcode.SPUT_OBJECT && init[at].field()?.let { it.definingClass == enum.type && it.type == enum.type } == true
    } ?: refuse("${enum.type} never keeps its $PRODUCTS constant")
    if ((nameAt + 1 until writeAt).any { stringLoadedAt(init, it) != null }) {
        refuse("${enum.type} loads another name before it keeps its $PRODUCTS constant")
    }
    val products = init[writeAt].field()!!

    val sites = classesAccessing(enum.type, products.name, Opcode.SGET_OBJECT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.filter { code.returnsConstant(it, products) }.map { method to it }
        }
    }
    val (method, readAt) = sites.singleOrNull()
        ?: refuse("expected one method answering ${enum.type}.${products.name} ($PRODUCTS), found ${sites.size}")
    val where = "${method.definingClass}->${method.name}"
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != enum.type ||
        method.parameterTypes.none { it.toString() == MEDIA }
    ) {
        refuse("$where isn't an instance method taking a post and answering ${enum.type}")
    }

    val code = method.implementation!!.instructions.toList()
    val branchAt = readAt - 1
    val branch = code.getOrNull(branchAt)
    val answer = code.getOrNull(readAt - 2)
    val check = code.getOrNull(readAt - 3)
    val checked = (check as? ReferenceInstruction)?.reference as? MethodReference
    if (branch?.opcode != Opcode.IF_EQZ || answer?.opcode != Opcode.MOVE_RESULT ||
        check?.opcode != Opcode.INVOKE_STATIC || checked?.returnType != "Z"
    ) {
        refuse("$where doesn't read $PRODUCTS straight after a static check's yes")
    }
    val register = (branch as OneRegisterInstruction).registerA
    if ((answer as OneRegisterInstruction).registerA != register) refuse("$where branches on something other than its check")
    if (register > 15) refuse("$where keeps its check's answer in v$register, past v15")
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    if (addresses[branchAt] + (branch as OffsetInstruction).codeOffset != addresses[readAt + 2]) {
        refuse("$where doesn't go on to its next indicator after a no")
    }
    return ProductTagIndicator(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        branchAt, register,
    )
}

/** Whether the instruction at [at] reads [constant] and the next one returns it. */
private fun List<Instruction>.returnsConstant(at: Int, constant: FieldReference): Boolean {
    val read = this[at]
    if (read.opcode != Opcode.SGET_OBJECT || read.field()?.toString() != constant.toString()) return false
    val back = getOrNull(at + 1) ?: return false
    return back.opcode == Opcode.RETURN_OBJECT &&
        (back as OneRegisterInstruction).registerA == (read as OneRegisterInstruction).registerA
}

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the products indicator: $detail")
