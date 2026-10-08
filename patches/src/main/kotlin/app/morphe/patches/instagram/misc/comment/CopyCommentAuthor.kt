/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.usernameBridge
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val AUTHOR_NATIVE = "$EXTENSION_PACKAGE/comment/CommentAuthorNative;"
internal const val AUTHOR_ROW = "$EXTENSION_PACKAGE/comment/AuthorRow;"
internal const val COMMENT_AUTHOR = "$EXTENSION_PACKAGE/comment/CommentAuthor;"
/** Stands in for the renderer's one read of a row's label text. */
internal const val AUTHOR_LABEL = "$COMMENT_AUTHOR->label(Landroid/content/Context;ILjava/lang/Object;)Ljava/lang/String;"
internal const val GET_STRING = "Landroid/content/Context;->getString(I)Ljava/lang/String;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * Copy username's boundaries on top of the shared surface (#35): the raw comment's getter for who
 * wrote it, the renderer's read of a row's label, and the step that fills User's username bridge.
 */
internal class CopyAuthorPlan(val user: MethodReference, val label: LabelRead, val username: () -> Unit)

/** A row's label as the renderer reads it: the row's label field, then the label's string id. */
internal class LabelRead(val labelField: FieldReference, val idField: FieldReference)

/** Where the renderer turns a row's label into text, on the registers it uses there. */
internal class LabelSite(val at: Int, val row: Int, val id: Int, val context: Int)

/**
 * Copy username's boundaries, or null after the patch log says why. Instagram's rows name their
 * label by a string id and HushGram adds no strings, so the row borrows Copy's id and the
 * renderer's one getString call on a row's label goes through [AUTHOR_LABEL], which answers the
 * row's own text. Copy comment goes in without Copy username on a build where any of it can't be
 * told, and nothing of it is written.
 */
internal fun BytecodePatchContext.findCopyAuthor(surface: CommentSurface, classes: Map<String, ClassDef>): CopyAuthorPlan? = try {
    fun clazz(type: String) = classes[type] ?: refuse("missing native class $type")
    for (type in listOf(AUTHOR_NATIVE, AUTHOR_ROW, COMMENT_AUTHOR, INSTAGRAM_MEDIA)) {
        if (type !in classes) refuse("missing extension boundary $type")
    }
    requirePublicField(clazz(surface.selectedType), surface.rawField)
    val raw = clazz(surface.raw)
    requirePublic(raw)
    val user = commentUserGetter(raw, clazz(surface.pando))
    val label = labelRead(surface, classes)
    labelSite(clazz(surface.renderer.definingClass).methods.filter { it.matches(surface.renderer) }.one("comment menu renderer"), label)
    stub(AUTHOR_NATIVE, "author", listOf(OBJECT), OBJECT)
    stub(COMMENT_AUTHOR, "label", listOf(CONTEXT, "I", OBJECT), STRING)
    validateActionRow(AUTHOR_ROW, AUTHOR_NATIVE)
    CopyAuthorPlan(ImmutableMethodReference.of(user), label, usernameBridge(COPY_PATCH))
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Copy comment goes in without Copy username.")
    null
}

/** Only called once [findCopyAuthor] found every boundary, after Copy's own row is written. */
internal fun BytecodePatchContext.applyCopyAuthor(surface: CommentSurface, plan: CopyAuthorPlan, icon: Int, label: Int) {
    val renderer = mutableClassDefBy(surface.renderer.definingClass).methods.single { it.matches(surface.renderer) }
    // Found again here: the shared hook may have gone in since discovery and moved it.
    val site = labelSite(renderer, plan.label)
    // In place, on the call's own registers, so nothing shifts and every branch still reaches it.
    renderer.replaceInstruction(site.at + 2, "invoke-static { v${site.context}, v${site.id}, v${site.row} }, $AUTHOR_LABEL")
    replace(stub(AUTHOR_NATIVE, "author", listOf(OBJECT), OBJECT), 2, """
        instance-of v0, p0, ${surface.selectedType}
        if-eqz v0, :unsupported
        check-cast p0, ${surface.selectedType}
        iget-object p0, p0, ${surface.rawField}
        if-eqz p0, :unsupported
        invoke-interface { p0 }, ${plan.user}
        move-result-object p0
        return-object p0
        :unsupported
        const/4 v0, 0x0
        return-object v0
    """)
    applyActionRow(surface, AUTHOR_ROW, AUTHOR_NATIVE, icon, label)
    plan.username()
}

/** The native row's one label field and the label's one string id field. */
private fun labelRead(surface: CommentSurface, classes: Map<String, ClassDef>): LabelRead {
    val row = classes[surface.rowConstructor.definingClass] ?: refuse("missing native class ${surface.rowConstructor.definingClass}")
    val labelType = surface.labelConstructor.definingClass
    val label = classes[labelType] ?: refuse("missing native class $labelType")
    val field = row.fields.filter { it.type == labelType && !AccessFlags.STATIC.isSet(it.accessFlags) }.one("native row label field")
    val id = label.fields.filter { it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags) }.one("native label string id")
    return LabelRead(ImmutableFieldReference.of(field), ImmutableFieldReference.of(id))
}

/**
 * The renderer's one read of a row's label text: the row's label, its string id from that label,
 * then Context.getString on that id, its text taken next. Nothing may jump into the middle, so the
 * row's register holds the row on every path to the call, and the row must be in a register the
 * replacement call can name.
 */
internal fun labelSite(renderer: Method, read: LabelRead): LabelSite {
    val code = renderer.code()
    val at = code.indices.filter { at ->
        if (at + 3 >= code.size) return@filter false
        val label = code[at]
        val id = code[at + 1]
        val call = code[at + 2]
        label.opcode == Opcode.IGET_OBJECT && label.field()?.toString() == read.labelField.toString() &&
            id.opcode == Opcode.IGET && id.field()?.toString() == read.idField.toString() &&
            (id as TwoRegisterInstruction).registerB == (label as TwoRegisterInstruction).registerA &&
            call.opcode == Opcode.INVOKE_VIRTUAL && call.call()?.toString() == GET_STRING &&
            call.arguments().getOrNull(1) == id.registerA &&
            code[at + 3].opcode == Opcode.MOVE_RESULT_OBJECT
    }.one("renderer's row label read")
    val label = code[at] as TwoRegisterInstruction
    val id = code[at + 1] as TwoRegisterInstruction
    val context = code[at + 2].arguments().first()
    val row = label.registerB
    if (label.registerA == row || id.registerA == row || context == row) refuse("renderer's label read overwrites its row")
    if (row > 15 || id.registerA > 15 || context > 15) refuse("renderer's label read is outside the low registers")
    val targets = renderer.jumpTargets()
    if (at + 1 in targets || at + 2 in targets) refuse("a branch enters the renderer's label read")
    return LabelSite(at, row, id.registerA, context)
}
