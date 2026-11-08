/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.edits

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.feedfollow.FEED_HEADER_STATE
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val EDITS_LABEL_STATUS = "editsLabel"
internal const val EDITS_LABELS = "$EXTENSION_PACKAGE/feed/EditsLabel;->labels(Ljava/util/List;)Ljava/util/List;"

/** The Edits kind of a feed header's label, an enum constant whose name its class initializer loads. */
internal const val EDITS_KIND = "BASEL_ATTRIBUTION"

private const val LIST = "Ljava/util/List;"

private fun refuse(detail: String): Nothing = throw PatchException("Hide Made with Edits: $detail")

/** The feed header's label state: the class holding the kinds list, and that list field. */
internal class EditsLabelSite(val state: String, val field: String)

/**
 * The feed header (the one method loading its state's cast message) reads a List field off its label
 * state and walks it, casting each item to the label kind enum, the one whose initializer names the
 * Edits kind. That field is the list the line under the author cycles through.
 */
internal fun BytecodePatchContext.findEditsLabel(): EditsLabelSite {
    val header = classesLoadingString(FEED_HEADER_STATE).flatMap { owner ->
        owner.methods.filter { loadsString(it, FEED_HEADER_STATE) }
    }.singleOrNull() ?: refuse("expected one feed header")
    val kinds = classesLoadingString(EDITS_KIND).filter { it.superclass == "Ljava/lang/Enum;" }.map { it.type }.toSet()
    if (kinds.isEmpty()) refuse("no label kind names $EDITS_KIND")
    val code = header.code()
    val fields = code.indices.mapNotNull { at ->
        val field = code[at].takeIf { it.opcode == Opcode.IGET_OBJECT }?.reference() as? FieldReference ?: return@mapNotNull null
        if (field.type != LIST) return@mapNotNull null
        val walk = (at + 1 until minOf(code.size, at + 24)).firstOrNull { next ->
            (code[next].reference() as? MethodReference)?.let { it.name == "next" && it.definingClass == "Ljava/util/Iterator;" } == true
        } ?: return@mapNotNull null
        val cast = (walk + 1 until minOf(code.size, walk + 4)).firstOrNull { code[it].opcode == Opcode.CHECK_CAST } ?: return@mapNotNull null
        field.takeIf { (code[cast].reference() as TypeReference).type in kinds }
    }.distinctBy { it.toString() }
    val field = fields.singleOrNull() ?: refuse("expected one label kinds list in the feed header, found ${fields.size}")
    return EditsLabelSite(field.definingClass, field.toString())
}

/** Every constructor storing the kinds list hands it to the extension first. */
internal fun BytecodePatchContext.hideEditsLabel(site: EditsLabelSite) {
    val constructors = mutableClassDefBy(site.state).methods.filter { it.name == "<init>" }
    var hooked = 0
    for (constructor in constructors) {
        val code = constructor.code()
        val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].reference()?.toString() == site.field }
        for (at in stores.reversed()) {
            val value = (code[at] as TwoRegisterInstruction).registerA
            constructor.addInstructions(at, """
                invoke-static/range { v$value .. v$value }, $EDITS_LABELS
                move-result-object v$value
            """.trimIndent())
            hooked++
        }
    }
    if (hooked == 0) refuse("no constructor stores ${site.field}")
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Takes "Made with Edits" out of the line under a feed post's author (#570). See EditsLabel in the
 * extension. The song, the place and the other labels keep taking turns there. In the default
 * selection with its switch off.
 */
@Suppress("unused")
val hideEditsLabelPatch = bytecodePatch(
    name = "Hide Made with Edits",
    description = "Takes the \"Made with Edits\" label out of the line under the author of posts in your feed. The " +
        "song, the place and the other labels keep taking turns there. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Ads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(EDITS_LABEL_STATUS)
        hideEditsLabel(findEditsLabel())
        enableStatus(EDITS_LABEL_STATUS)
    }
}
