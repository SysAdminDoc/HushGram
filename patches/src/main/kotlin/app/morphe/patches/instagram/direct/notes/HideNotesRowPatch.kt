/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.notes

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.instagram.metaai.INBOX_SECTION_MARKER
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
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
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the notes row"
internal const val NOTES_ROW = "$EXTENSION_PACKAGE/direct/NotesRow;"
internal const val KEEP_SECTIONS = "$NOTES_ROW->sections([Ljava/lang/Object;)[Ljava/lang/Object;"

/** The trace name of the method that works out your messages again, the one place their sections are named. */
internal const val INBOX_DIFF = "InboxViewModelGenerator.calculateAndApplyDiffUpdate"

/** The constant name of the notes row's section, which NotesRow drops by name. */
internal const val NOTES_SECTION = "TRAY"

private const val BIT_SET = "Ljava/util/BitSet;"

/** The hook answering whether the row of notes has a Map bubble to add. */
internal const val MAP_SHOWN = "$NOTES_ROW->mapShown(I)I"

/** The trace name of the method turning your notes into the row's items, the one place the Map bubble is added. */
internal const val NOTES_TRAY_ITEMS =
    "instagram.features.direct.inbox.notes.tray.compose.ui.toNotesTrayUiState (NotesTrayComposeItem.kt:319)"

/** The trace name of the Map bubble's own content, which takes the bubble's item. */
internal const val NOTES_MAP_CONTENT =
    "instagram.features.direct.inbox.notes.tray.compose.ui.NotesFriendMapPogContent (NotesFriendMapPogContent.kt:9)"

/** What the row built from views loads for the Map bubble, right after finding it has one to add. */
internal const val NOTES_MAP_ROW = "FriendMapEntryPoint"

/**
 * Leaves the row of notes, and the Map bubble in it, out of your messages, or only the bubble.
 * Included in the default selection with both switches initially off, so leaving either out remains
 * the user's pick.
 *
 * Only the list of sections your messages may show, and the row's question of whether to add the
 * bubble, change. Notes are still fetched, and a note on a profile picture or in a chat still shows.
 */
@Suppress("unused")
val hideNotesRowPatch = bytecodePatch(
    name = "Hide the notes row",
    description = "Takes the row of notes, and the Map bubble in it, off the top of your messages. Your chats " +
        "stay. A second switch takes only the Map bubble. Both start off. Turn them on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("notesRow")
        val sections = findNotesSections()
        val map = findNotesMapSites()
        hideNotesRow(sections)
        map.forEach { hideNotesMap(it) }
        enableStatus("notesRow")
    }
}

/** The inbox generator's diff update, found by its trace name. */
internal object InboxDiffFingerprint : Fingerprint(
    filters = listOf(string(INBOX_DIFF)),
    custom = { method, classDef -> !classDef.type.startsWith(EXTENSION_ROOT) && method.holdsString(INBOX_DIFF) },
)

/** The inbox's lookup of a section's generator, found by the message it fails with. */
internal object InboxSectionLookupFingerprint : Fingerprint(
    filters = listOf(string(INBOX_SECTION_MARKER)),
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
 * update moved it, since that's a build this patch hasn't seen: in the method holding [INBOX_DIFF]
 * (or, when it builds none, the one static method it calls that does), the one construction of a
 * set from an array of an enum whose setup names [NOTES_SECTION] once.
 * That enum has to be the one the inbox's section lookup (the method holding
 * [INBOX_SECTION_MARKER]) takes first, and the set has to be the one it checks: the set's class
 * keeps a [BIT_SET] its constructor fills, and the lookup's class reads one to decide each section.
 * Nothing after the construction may read the register holding the names, since the hook hands
 * the set a copy in that register. Every other patch that drops an inbox section finds it the same
 * way, refusing under its own [patch] name.
 */
internal fun BytecodePatchContext.findNotesSections(patch: String = PATCH): NotesSectionsSite {
    fun refuse(detail: String): Nothing = throw PatchException("$patch: $detail")

    val inboxDiff = uniqueMethod(patch, "inbox generator holding \"$INBOX_DIFF\"", InboxDiffFingerprint)
    // 451 builds the set in the diff update itself. 452 moved it into a static factory the diff
    // update calls with the inbox's mode (07SN.A00(05Ok)07SY), whose every mode jumps to the one
    // construction, so when the diff update builds none the one method it calls that does is used.
    val diff = inboxDiff.takeIf { setConstructions(it).isNotEmpty() } ?: run {
        val builders = inboxDiff.instructions().asSequence()
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { it.methodCalled() }.distinctBy { it.toString() }
            .mapNotNull { called ->
                classDefByOrNull(called.definingClass)?.methods?.firstOrNull { method ->
                    method.name == called.name && method.returnType == called.returnType &&
                        method.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString)
                }
            }
            .filter { setConstructions(it).isNotEmpty() }.toList()
        if (builders.size > 1) {
            refuse("expected ${inboxDiff.definingClass}->${inboxDiff.name} to call one method building the inbox's sections, found ${builders.size}")
        }
        builders.singleOrNull() ?: inboxDiff
    }
    val code = diff.instructions()
    val where = "${diff.definingClass}->${diff.name}"
    val constructions = setConstructions(diff)
    val construction = constructions.singleOrNull()
        ?: refuse("expected $where to build one set from an array of the inbox's sections, found ${constructions.size}")
    val made = code[construction].constructorCalled()!!
    val sections = made.parameterTypes.single().toString().removePrefix("[")
    val set = classDefByOrNull(made.definingClass) ?: refuse("${made.definingClass} isn't in this build")

    val lookup = uniqueMethod(patch, "inbox section lookup holding \"$INBOX_SECTION_MARKER\"", InboxSectionLookupFingerprint)
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

/** The indexes in [method] that construct an object from an array of an enum naming [NOTES_SECTION]. */
private fun BytecodePatchContext.setConstructions(method: Method): List<Int> {
    val code = method.instructions()
    return code.indices.filter { at ->
        val made = code[at].constructorCalled() ?: return@filter false
        val array = made.parameterTypes.singleOrNull()?.toString() ?: return@filter false
        array.startsWith("[L") && namesNotes(array.removePrefix("["))
    }
}

/** Whether [enum] is an enum whose setup names [NOTES_SECTION] exactly once. */
private fun BytecodePatchContext.namesNotes(enum: String): Boolean {
    val classDef = classDefByOrNull(enum) ?: return false
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val setup = classDef.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    return setup.instructions().count { it.stringLoaded() == NOTES_SECTION } == 1
}

/** Hands the sections to [KEEP_SECTIONS], see [askForSections]. */
internal fun BytecodePatchContext.hideNotesRow(site: NotesSectionsSite) = askForSections(site, KEEP_SECTIONS)

/**
 * Hands the sections to [hook], a static `([Ljava/lang/Object;)[Ljava/lang/Object;` method, right
 * before the set is built from them, at the construction's own label so whatever jumps there asks
 * too, and casts the answer back to the array type the constructor takes. A second hook found at
 * the same site afterwards lands between the first one and the construction, so both run, whichever
 * goes in first.
 */
internal fun BytecodePatchContext.askForSections(site: NotesSectionsSite, hook: String) {
    val diff = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val register = site.register
    diff.addInstructionsAtControlFlowLabel(
        site.construction,
        """
            invoke-static/range { v$register .. v$register }, $hook
            move-result-object v$register
            check-cast v$register, [${site.sections}
        """,
    )
}

/** Where a Map bubble hook goes: the method asking, the test of its answer, the register holding that answer, and the field it read it from. */
internal class NotesMapSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val test: Int,
    val register: Int,
    val answer: FieldReference,
)

/**
 * Every place Instagram asks whether there's a Map bubble. It builds the row of notes either from
 * composables or from views, and which one a phone gets is its own choice, so a hook on one route
 * alone leaves the bubble on the other's phones. All of them read the same field of the same data:
 * the composables' list ([findNotesMap]), the views' list, and the first-run tip pointing at the bubble.
 */
internal fun BytecodePatchContext.findNotesMapSites(): List<NotesMapSite> {
    val composed = findNotesMap()
    return listOf(composed, findNotesMapRow(composed.answer), findNotesMapTip(composed.answer))
}

/**
 * Finds the row's question of whether it has a Map bubble to add, failing before anything changes
 * when an update moved it: in the one method loading [NOTES_TRAY_ITEMS], the one object it makes of
 * the type the one method loading [NOTES_MAP_CONTENT] takes is the bubble's item. The nearest boolean
 * read before that has to be a field of the data the item's constructor takes first, tested right
 * after by a branch landing past the item's add, with nothing jumping onto that test and nothing
 * after it reading the answer, since the hook hands the test an int.
 */
internal fun BytecodePatchContext.findNotesMap(): NotesMapSite {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
    fun loading(marker: String) = classesLoadingString(marker).flatMap { it.methods }.filter { loadsString(it, marker) }

    val builders = loading(NOTES_TRAY_ITEMS)
    val builder = builders.singleOrNull() ?: refuse("expected one method loading \"$NOTES_TRAY_ITEMS\", found ${builders.size}")
    val contents = loading(NOTES_MAP_CONTENT)
    val content = contents.singleOrNull() ?: refuse("expected one method loading \"$NOTES_MAP_CONTENT\", found ${contents.size}")

    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.instructions()
    val taken = content.parameterTypes.map(CharSequence::toString).toSet()
    val made = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && code[it].typeMade() in taken }
    val item = made.singleOrNull() ?: refuse("expected $where to make one of what ${content.definingClass}->${content.name} takes, found ${made.size}")
    val itemType = code[item].typeMade()
    val itemRegister = (code[item] as OneRegisterInstruction).registerA

    val built = (item + 1 until code.size).firstOrNull { code[it].constructorCalled()?.definingClass == itemType }
        ?: refuse("$where never constructs $itemType")
    val data = code[built].constructorCalled()!!.parameterTypes.firstOrNull()?.toString()
        ?: refuse("$itemType is built from nothing in $where")
    val added = (built + 1 until code.size).firstOrNull {
        code[it].methodCalled()?.name == "add" && code[it].argumentRegisters().lastOrNull() == itemRegister
    } ?: refuse("$where never adds its $itemType")

    val read = (item - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_BOOLEAN }
        ?: refuse("$where reads no boolean before making its $itemType")
    val field = (code[read] as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != data) refuse("$where reads ${field.definingClass}->${field.name} before making its $itemType, not one of $data")
    val register = (code[read] as TwoRegisterInstruction).registerA
    val test = read + 1
    if (code.getOrNull(test)?.opcode != Opcode.IF_EQZ || (code[test] as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't test ${field.name} right after reading it")
    }
    if (code.target(test) <= added) refuse("$where's test of ${field.name} doesn't skip adding the $itemType")
    if (test in builder.jumpTargets()) refuse("something in $where jumps onto its test of ${field.name}")
    if (builder.readsAfter(test, register).isNotEmpty()) refuse("$where reads v$register again after testing ${field.name}")
    return NotesMapSite(builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), test, register, field)
}

/** The views' list: the one method reading [answer] that also loads [NOTES_MAP_ROW], whose test skips that load. */
private fun BytecodePatchContext.findNotesMapRow(answer: FieldReference): NotesMapSite {
    val reads = readsOf(answer).filter { (method, _) -> loadsString(method, NOTES_MAP_ROW) }
    val (method, read) = reads.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method reading ${answer.name} and loading \"$NOTES_MAP_ROW\", found ${reads.size}",
    )
    val site = notesMapSite(method, read, Opcode.IF_EQZ, answer)
    val code = method.instructions()
    val loaded = (site.test + 1 until code.target(site.test)).any { stringLoadedAt(code, it) == NOTES_MAP_ROW }
    if (!loaded) throw PatchException("$PATCH: ${method.definingClass}->${method.name}'s test of ${answer.name} doesn't skip loading \"$NOTES_MAP_ROW\"")
    return site
}

/** The first-run tip: the one read of [answer] compared with yes, not no, whose match keeps that data in a new object. */
private fun BytecodePatchContext.findNotesMapTip(answer: FieldReference): NotesMapSite {
    val reads = readsOf(answer).filter { (method, read) -> method.instructions().getOrNull(read + 1)?.opcode == Opcode.IF_NE }
    val (method, read) = reads.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method comparing ${answer.name} with yes, found ${reads.size}",
    )
    val site = notesMapSite(method, read, Opcode.IF_NE, answer)
    val code = method.instructions()
    val kept = (site.test + 1 until code.target(site.test)).any {
        code[it].opcode == Opcode.IPUT_OBJECT && ((code[it] as ReferenceInstruction).reference as FieldReference).type == answer.definingClass
    }
    if (!kept) throw PatchException("$PATCH: ${method.definingClass}->${method.name}'s test of ${answer.name} doesn't guard keeping its data")
    return site
}

/** Every read of [answer] outside its own class, as the method and the instruction's index. */
private fun BytecodePatchContext.readsOf(answer: FieldReference): List<Pair<Method, Int>> =
    classesAccessing(answer.definingClass, answer.name, Opcode.IGET_BOOLEAN)
        .filter { it.type != answer.definingClass }
        .flatMap { it.methods }
        .flatMap { method ->
            method.instructions().withIndex().filter { (_, instruction) ->
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                instruction.opcode == Opcode.IGET_BOOLEAN && field?.definingClass == answer.definingClass && field.name == answer.name
            }.map { method to it.index }
        }

/** The site of the read at [read], which [opcode] has to test right after, with nothing jumping onto that test and nothing after it reading the answer. */
private fun notesMapSite(method: Method, read: Int, opcode: Opcode, answer: FieldReference): NotesMapSite {
    val where = "${method.definingClass}->${method.name}"
    val code = method.instructions()
    val register = (code[read] as TwoRegisterInstruction).registerA
    val test = read + 1
    if (code.getOrNull(test)?.opcode != opcode || (code[test] as OneRegisterInstruction).registerA != register) {
        throw PatchException("$PATCH: $where doesn't test ${answer.name} right after reading it")
    }
    if (test in method.jumpTargets()) throw PatchException("$PATCH: something in $where jumps onto its test of ${answer.name}")
    if (method.readsAfter(test, register).isNotEmpty()) throw PatchException("$PATCH: $where reads v$register again after testing ${answer.name}")
    return NotesMapSite(method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), test, register, answer)
}

/** Hands the answer to [MAP_SHOWN] right before [site] tests it. */
internal fun BytecodePatchContext.hideNotesMap(site: NotesMapSite) {
    val builder = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    builder.addInstructions(
        site.test,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $MAP_SHOWN
            move-result v${site.register}
        """,
    )
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any { it.stringLoaded() == value }

private fun Instruction.typeMade(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

/** The index of the instruction the branch at [index] lands on. */
private fun List<Instruction>.target(index: Int): Int {
    val address = IntArray(size + 1)
    forEachIndexed { at, instruction -> address[at + 1] = address[at] + instruction.codeUnits }
    return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
}

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
