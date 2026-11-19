/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.newmessage

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.ghost.GhostAction
import app.morphe.patches.instagram.direct.ghost.GhostEntry
import app.morphe.patches.instagram.direct.ghost.IGDS_ACTION_BAR
import app.morphe.patches.instagram.direct.ghost.ghostActionFound
import app.morphe.patches.instagram.direct.ghost.ghostEntriesFound
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val NEW_MESSAGE_PATCH = "Hide New message button and suggestions"

/** Called as either inbox top bar binds a button to what it's bound to. */
internal const val NEW_MESSAGE_BUTTON = "$EXTENSION_PACKAGE/direct/NewMessageButton;->bind(Landroid/view/View;Ljava/lang/Object;)V"

private const val SUGGESTIONS = "$EXTENSION_PACKAGE/direct/NewMessageSuggestions;"

/** Called with the row controller the recipient pickers make. */
internal const val PICKER_ROWS = "$SUGGESTIONS->picker(Ljava/lang/Object;)V"

/** Answers the people a row controller suggests before anything is typed. */
internal const val SUGGESTED_ROWS = "$SUGGESTIONS->suggested(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;"

/** Answers the people a row controller lists for a search. */
internal const val FOUND_ROWS = "$SUGGESTIONS->results(Ljava/lang/Object;Ljava/lang/String;Ljava/util/List;)Ljava/util/List;"

/** What the recipient pickers' controller says when it's built without its list, which only its constructor loads. */
internal const val PICKER_ADAPTER = "igRecyclerViewAdapter must not be null"

/** The row key only the recipient row controller (InteropRecipientAdapterDataController) builds. */
internal const val COMMUNITY_ROW = "_community_row_"

private const val VIEW = "Landroid/view/View;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"

/**
 * Takes the New message button off the top bar of your messages, and the Suggested people off the
 * New message screen and the new group picker (#762). Two switches, both off to start. Included in
 * the default selection so hiding them is the user's pick.
 *
 * The button is found through the ghost entry patch, which already finds both bars' New message
 * button and marks it as Instagram makes it. A build where neither bar or the picker can be read
 * keeps the other part, with a warning in the patch log.
 */
@Suppress("unused")
val hideNewMessagePatch = bytecodePatch(
    name = "Hide New message button and suggestions",
    description = "Takes the New message button off the top of your messages, and the Suggested people off the " +
        "New message screen. Search still works. Both start off. Turn them on in HushGram settings > Messages.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("newMessage")
        hideNewMessageExtras()
        enableStatus("newMessage")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$NEW_MESSAGE_PATCH: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun FieldReference.text() = "$definingClass->$name:$type"

private fun BytecodePatchContext.mutable(type: String, name: String, parameters: List<String>): MutableMethod =
    mutableClassDefBy(type).methods.single { it.name == name && it.parameters() == parameters }

/**
 * Finds every part first and changes nothing until all are found. Each bar and the picker can be
 * missing alone; with none of them there's nothing to hide and the patch fails.
 */
internal fun BytecodePatchContext.hideNewMessageExtras() {
    val older = ghostEntriesFound()?.let { entries ->
        try {
            findOlderBinder(entries)
        } catch (refusal: PatchException) {
            patchLog.warning("${refusal.message}. New message stays on the older inbox top bar.")
            null
        }
    }
    val newer = ghostActionFound()
    if (older == null && newer == null) patchLog.warning("$NEW_MESSAGE_PATCH: neither inbox top bar's New message button was found, so it stays")
    val picker = try {
        findPickerRows()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. The Suggested people stay on the New message screen.")
        null
    }
    if (older == null && newer == null && picker == null) refuse("found neither the New message button nor the recipient picker")
    older?.let(::hookOlderBinder)
    newer?.let { hookNewerBinder(it) }
    picker?.let { hookPickerRows(it) }
}

/**
 * Where the older bar binds a button to its built configuration: right after it reads the tap
 * listener, with the button's register and the listener's.
 */
internal class OlderBinder(
    val method: MutableMethod,
    val insertAt: Int,
    val view: Int,
    val tap: Int,
)

/**
 * The older bar copies each button's configuration into a built one and binds the button to that.
 * [entries] name the configuration's tap field. The one constructor taking a configuration that
 * copies that field straight into an OnClickListener field of its own gives the built tap field,
 * and the one instance method taking a View and a built configuration, reading that field and
 * setting a long press, is the binder.
 */
internal fun BytecodePatchContext.findOlderBinder(entries: List<GhostEntry>): OlderBinder {
    val taps = entries.map { it.tap }.distinct()
    val tap = taps.singleOrNull() ?: refuse("expected the New message buttons to share one tap field, found ${taps.size}")
    val configType = tap.substringBefore("->")
    val tapName = tap.substringAfter("->").substringBefore(":")

    val copies = classesAccessing(configType, tapName, Opcode.IGET_OBJECT).flatMap { owner ->
        owner.methods.filter { it.name == "<init>" && it.parameters() == listOf(configType) }.flatMap { init ->
            val code = init.code()
            code.indices.mapNotNull { at -> copiedTap(owner, code, at, tap) }
        }
    }.distinctBy { it.text() }
    val built = copies.singleOrNull()
        ?: refuse("expected one built configuration copying $tap, found ${copies.size}")

    val binders = classesAccessing(built.definingClass, built.name, Opcode.IGET_OBJECT).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isStatic() && method.returnType == "V" && method.parameters() == listOf(VIEW, built.definingClass) &&
                method.code().any { it.opcode == Opcode.IGET_OBJECT && (it.reference() as? FieldReference)?.text() == built.text() } &&
                method.code().any { (it.reference() as? MethodReference)?.name == "setOnLongClickListener" }
        }
    }
    val binder = binders.singleOrNull()
        ?: refuse("expected one older bar binder reading ${built.text()}, found ${binders.size}")
    val where = "${binder.definingClass}->${binder.name}"
    val code = binder.code()
    val read = code.indices.first { code[it].opcode == Opcode.IGET_OBJECT && (code[it].reference() as? FieldReference)?.text() == built.text() }
    val tapRegister = (code[read] as TwoRegisterInstruction).registerA
    val view = binder.implementation!!.registerCount - 2
    if (code.any { it.writes(view) }) refuse("$where writes over its button")
    if (view > 15 || tapRegister > 15) refuse("$where keeps its button or tap listener past v15")
    val insertAt = read + 1
    if (insertAt >= code.size || insertAt in binder.jumpTargets()) refuse("$where jumps to right after it reads the tap listener")
    return OlderBinder(mutable(binder.definingClass, binder.name, binder.parameters()), insertAt, view, tapRegister)
}

/** The field [owner]'s constructor stores the configuration's [tap] listener into at [at], read just before it, or null. */
private fun copiedTap(owner: ClassDef, code: List<Instruction>, at: Int, tap: String): FieldReference? {
    val read = code[at]
    if (read.opcode != Opcode.IGET_OBJECT || (read.reference() as? FieldReference)?.text() != tap) return null
    val store = code.getOrNull(at + 1) ?: return null
    val field = store.reference() as? FieldReference ?: return null
    if (store.opcode != Opcode.IPUT_OBJECT || field.definingClass != owner.type || field.type != CLICK_LISTENER) return null
    if ((store as TwoRegisterInstruction).registerA != (read as TwoRegisterInstruction).registerA) return null
    return field
}

/** Whether this instruction writes [register], or the wide pair ending at it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && written == register || opcode.setsWideRegister() && written + 1 == register
}

/** The older bar's binder hands the button and its tap listener to the extension before it binds them. */
internal fun hookOlderBinder(found: OlderBinder) {
    found.method.addInstructions(found.insertAt, "invoke-static { v${found.view}, v${found.tap} }, $NEW_MESSAGE_BUTTON")
}

/** The newer bar's binder hands each button and its action model to the extension first. */
internal fun BytecodePatchContext.hookNewerBinder(found: GhostAction) {
    val binder = mutable(IGDS_ACTION_BAR, found.binderName, found.binderParameters)
    // p1 and p2 are the button and its model, next to each other, so a range call reaches them past v15.
    binder.addInstructionsAtControlFlowLabel(0, "invoke-static/range { p1 .. p2 }, $NEW_MESSAGE_BUTTON")
}

/**
 * The recipient row controller, its two ways of filling its rows, and where the pickers' own
 * controller makes it: [markAt] is right after it's stored, and [register] holds it there.
 */
internal class PickerRows(
    val type: String,
    val suggested: String,
    val results: String,
    val maker: MutableMethod,
    val markAt: Int,
    val register: Int,
)

/**
 * The recipient row controller is the one class loading [COMMUNITY_ROW]. It fills its rows from
 * one list of suggestions, `(List)V`, or from a search's matches, `(String, List)V`. The pickers'
 * controller, the class whose constructor loads [PICKER_ADAPTER], makes one and stores it in a
 * field: that one is marked, and the share sheet's rows stay as they are.
 */
internal fun BytecodePatchContext.findPickerRows(): PickerRows {
    val owners = classesLoadingString(COMMUNITY_ROW).filter { owner -> owner.methods.any { loadsString(it, COMMUNITY_ROW) } }
    val rows = owners.singleOrNull() ?: refuse("expected one class loading \"$COMMUNITY_ROW\", found ${owners.size}")
    fun filler(parameters: List<String>, what: String): Method {
        val found = rows.methods.filter { !it.isStatic() && it.returnType == "V" && it.parameters() == parameters && it.implementation != null }
        val method = found.singleOrNull() ?: refuse("expected one $what in ${rows.type}, found ${found.size}")
        if (0 in method.jumpTargets()) refuse("${rows.type}->${method.name} jumps back to its first instruction")
        return method
    }
    val suggested = filler(listOf(LIST), "list of suggestions")
    val results = filler(listOf(STRING, LIST), "list of search matches")

    val sites = classesLoadingString(PICKER_ADAPTER).flatMap { owner ->
        owner.methods.filter { it.name == "<init>" && loadsString(it, PICKER_ADAPTER) }.mapNotNull { init -> storedRows(init, rows.type) }
    }
    val (init, store) = sites.singleOrNull()
        ?: refuse("expected one picker controller making ${rows.type}, found ${sites.size}")
    val register = (init.code()[store] as TwoRegisterInstruction).registerA
    val markAt = store + 1
    if (markAt >= init.code().size || markAt in init.jumpTargets()) refuse("${init.definingClass}-><init> jumps to right after it stores ${rows.type}")
    return PickerRows(rows.type, suggested.name, results.name, mutable(init.definingClass, init.name, init.parameters()), markAt, register)
}

/** Where [init] stores the [rows] it makes, made, built and stored in that order, or null when it makes none. */
private fun storedRows(init: Method, rows: String): Pair<Method, Int>? {
    val code = init.code()
    val made = code.indices.firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE && (code[it].reference() as? TypeReference)?.type == rows }
        ?: return null
    val register = (code[made] as OneRegisterInstruction).registerA
    val built = (made + 1 until code.size).firstOrNull {
        (code[it].opcode == Opcode.INVOKE_DIRECT || code[it].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            (code[it].reference() as? MethodReference)?.let { call -> call.definingClass == rows && call.name == "<init>" } == true
    } ?: refuse("${init.definingClass}-><init> makes $rows without building it")
    val store = (built + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.IPUT_OBJECT && (code[it].reference() as? FieldReference)?.type == rows
    } ?: refuse("${init.definingClass}-><init> doesn't keep the $rows it makes")
    if ((code[store] as TwoRegisterInstruction).registerA != register) refuse("${init.definingClass}-><init> keeps another $rows than the one it makes")
    return init to store
}

/** Marks the pickers' row controller as it's made, and has both fillers ask the extension for their list. */
internal fun BytecodePatchContext.hookPickerRows(found: PickerRows) {
    found.maker.addInstructions(found.markAt, "invoke-static/range { v${found.register} .. v${found.register} }, $PICKER_ROWS")
    mutable(found.type, found.suggested, listOf(LIST)).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $SUGGESTED_ROWS
            move-result-object p1
        """,
    )
    mutable(found.type, found.results, listOf(STRING, LIST)).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p2 }, $FOUND_ROWS
            move-result-object p2
        """,
    )
}
