/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.notes

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.metaai.INBOX_SECTION_MARKER
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide the notes row"
internal const val NOTES_ROW = "$EXTENSION_PACKAGE/direct/NotesRow;"
internal const val KEEP_SECTIONS = "$NOTES_ROW->sections([Ljava/lang/Object;)[Ljava/lang/Object;"

/** The trace name of the method that works out your messages again, the one place their sections are named. */
internal const val INBOX_DIFF = "InboxViewModelGenerator.calculateAndApplyDiffUpdate"

/** The constant name of the notes row's section, which NotesRow drops by name. */
internal const val NOTES_SECTION = "TRAY"

private const val BIT_SET = "Ljava/util/BitSet;"

/**
 * Leaves the row of notes, and the Map bubble in it, out of your messages. Included in the default
 * selection with its switch initially off, so leaving the row out remains the user's pick.
 *
 * Only the list of sections your messages may show changes. Notes are still fetched, and a note
 * on a profile picture or in a chat still shows.
 */
@Suppress("unused")
val hideNotesRowPatch = bytecodePatch(
    name = "Hide the notes row",
    description = "Takes the row of notes off the top of your messages, the Map bubble in it too. Your chats, search " +
        "and requests stay. Off until you turn it on. Switch: HushGram settings > Messages.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("notesRow")
        hideNotesRow(findNotesSections())
        enableStatus("notesRow")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The inbox generator's diff update, found by its trace name. */
internal object InboxDiffFingerprint : Fingerprint(
    strings = listOf(INBOX_DIFF),
    custom = { method, classDef -> !classDef.type.startsWith(EXTENSION_ROOT) && method.holdsString(INBOX_DIFF) },
)

/** The inbox's lookup of a section's generator, found by the message it fails with. */
internal object InboxSectionLookupFingerprint : Fingerprint(
    strings = listOf(INBOX_SECTION_MARKER),
    custom = { method, classDef -> !classDef.type.startsWith(EXTENSION_ROOT) && method.holdsString(INBOX_SECTION_MARKER) },
)

/** Where the hook goes: the diff update, the construction of its set of sections, the register of their names, and their enum. */
internal class NotesSectionsSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val construction: Int,
    val register: Int,
    val sections: String,
)

/**
 * Finds the one place your messages name their sections, failing before anything changes when an
 * update moved it, since that's a build this patch hasn't seen: in the method holding [INBOX_DIFF],
 * the one construction of a set from an array of an enum whose setup names [NOTES_SECTION] once.
 * That enum has to be the one the inbox's section lookup (the method holding
 * [INBOX_SECTION_MARKER]) takes first, and the set has to be the one it checks: the set's class
 * keeps a [BIT_SET] its constructor fills, and the lookup's class reads one to decide each section.
 * Nothing after the construction may read the register holding the names, since the hook hands
 * the set a copy in that register.
 */
internal fun BytecodePatchContext.findNotesSections(): NotesSectionsSite {
    val diff = uniqueMethod(PATCH, "inbox generator holding \"$INBOX_DIFF\"", InboxDiffFingerprint)
    val code = diff.instructions()
    val where = "${diff.definingClass}->${diff.name}"
    val constructions = code.indices.filter { at ->
        val made = code[at].constructorCalled() ?: return@filter false
        val array = made.parameterTypes.singleOrNull()?.toString() ?: return@filter false
        array.startsWith("[L") && namesNotes(array.removePrefix("["))
    }
    val construction = constructions.singleOrNull()
        ?: refuse("expected $where to build one set from an array of the inbox's sections, found ${constructions.size}")
    val made = code[construction].constructorCalled()!!
    val sections = made.parameterTypes.single().toString().removePrefix("[")
    val set = classDefByOrNull(made.definingClass) ?: refuse("${made.definingClass} isn't in this build")

    val lookup = uniqueMethod(PATCH, "inbox section lookup holding \"$INBOX_SECTION_MARKER\"", InboxSectionLookupFingerprint)
    val lookupClass = classDefBy(lookup.definingClass)
    if (!AccessFlags.STATIC.isSet(lookup.accessFlags) || lookup.parameterTypes.firstOrNull()?.toString() != sections) {
        refuse("${lookupClass.type}->${lookup.name} doesn't look a section up by $sections")
    }
    if (set.fields.none { it.type == BIT_SET && !AccessFlags.STATIC.isSet(it.accessFlags) } ||
        set.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("[$sections") }
            ?.instructions().orEmpty().none { it.methodCalled()?.toString() == "$BIT_SET->set(I)V" }
    ) {
        refuse("${set.type} doesn't fill a $BIT_SET from the sections it's given")
    }
    val checks = lookupClass.methods.filter { method ->
        method.parameterTypes.any { it.toString() == set.type } &&
            method.instructions().any { it.methodCalled()?.toString() == "$BIT_SET->get(I)Z" }
    }
    if (checks.isEmpty()) refuse("${lookupClass.type} never checks a section against ${set.type}")

    val register = code[construction].argumentRegisters().getOrNull(1)
        ?: refuse("$where builds ${set.type} without handing it the sections")
    if (diff.readsAfter(construction, register).isNotEmpty()) {
        refuse("$where reads v$register, the sections, after building ${set.type}")
    }
    return NotesSectionsSite(diff.definingClass, diff.name, diff.parameterTypes.map(CharSequence::toString), construction, register, sections)
}

/** Whether [enum] is an enum whose setup names [NOTES_SECTION] exactly once. */
private fun BytecodePatchContext.namesNotes(enum: String): Boolean {
    val classDef = classDefByOrNull(enum) ?: return false
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val setup = classDef.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    return setup.instructions().count { it.stringLoaded() == NOTES_SECTION } == 1
}

/**
 * Hands the sections to [KEEP_SECTIONS] right before the set is built from them, at the
 * construction's own label so whatever jumps there asks too, and casts the answer back to the
 * array type the constructor takes.
 */
internal fun BytecodePatchContext.hideNotesRow(site: NotesSectionsSite) {
    val diff = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val register = site.register
    diff.addInstructionsAtControlFlowLabel(
        site.construction,
        """
            invoke-static/range { v$register .. v$register }, $KEEP_SECTIONS
            move-result-object v$register
            check-cast v$register, [${site.sections}
        """,
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any { it.stringLoaded() == value }

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.methodCalled(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The constructor this runs on an object, or null when it isn't a constructor call. */
private fun Instruction.constructorCalled(): MethodReference? {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    return methodCalled()?.takeIf { it.name == "<init>" && it.returnType == "V" }
}

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
