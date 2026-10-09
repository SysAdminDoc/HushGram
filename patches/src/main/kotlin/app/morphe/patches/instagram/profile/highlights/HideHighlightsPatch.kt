/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.highlights

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.profile.suggested.BUILD_ROWS
import app.morphe.patches.instagram.profile.suggested.HEADER_BIND
import app.morphe.patches.instagram.profile.suggested.HEADER_CREATE
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide highlights"
internal const val PROFILE_HIGHLIGHTS = "$EXTENSION_PACKAGE/profile/ProfileHighlights;"
internal const val KEEP_TRAY = "$PROFILE_HIGHLIGHTS->keepTray()I"

/** The header row type of the highlights tray, named in its enum's setup. */
internal const val REEL_TRAY = "ITEM_TYPE_REEL_TRAY"

/**
 * Leaves the row of story highlights out of profiles. Included in the default selection with its
 * switch initially off, so leaving highlights out remains the user's pick.
 *
 * Only the header's list of rows changes. The highlights themselves are still fetched, since the
 * Add to highlight list on a story shares that code, and a highlight opened from a message or a
 * link still plays.
 */
@Suppress("unused")
val hideHighlightsPatch = bytecodePatch(
    name = "Hide highlights",
    description = "Takes the row of story highlights off profiles, yours and other people's. Bios, counts, posts and " +
        "the Add to highlight list on your stories stay. Off until you turn it on. Switch: HushGram settings > " +
        "Profiles.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileHighlights")
        hideHighlights(findHighlightsRow())
        enableStatus("profileHighlights")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The profile header's binder, found by its trace name. */
internal object HighlightsHeaderBindFingerprint : Fingerprint(
    name = "bindView",
    strings = listOf(HEADER_BIND),
    custom = { method, _ -> method.holdsString(HEADER_BIND) },
)

/** The setup of the header's row types, the one enum setup naming the highlights tray. */
internal object HighlightsRowTypesFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf(REEL_TRAY),
    custom = { method, classDef -> classDef.superclass == "Ljava/lang/Enum;" && method.holdsString(REEL_TRAY) },
)

/** Where the hook goes: the header's list of rows, the read of the tray's row type, its register and field. */
internal class HighlightsRowSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val tray: Int,
    val register: Int,
    val field: String,
)

/**
 * Finds the read of the tray's row type, failing before anything changes when it isn't there
 * exactly once, since that's an update this patch hasn't seen: in the header binder's class, the
 * one [BUILD_ROWS] reading the field its row type enum's setup stores [REEL_TRAY] in, read once and
 * handed straight to the call that adds a row by its int type. Nothing after that call may read
 * the register the row type was read into, nor the one its int was: a skipped add writes neither.
 */
internal fun BytecodePatchContext.findHighlightsRow(): HighlightsRowSite {
    val bind = uniqueMethod(PATCH, "profile header binder holding \"$HEADER_BIND\"", HighlightsHeaderBindFingerprint)
    val header = classDefBy(bind.definingClass)
    if (header.methods.none { it.holdsString(HEADER_CREATE) }) refuse("${header.type} has no method holding \"$HEADER_CREATE\"")
    val setup = uniqueMethod(PATCH, "row type setup naming $REEL_TRAY", HighlightsRowTypesFingerprint)
    val field = trayField(setup)

    val readers = header.methods.filter { it.name == BUILD_ROWS }.flatMap { method ->
        method.instructions().indices.filter { method.instructions()[it].readsStatic(field) }.map { method to it }
    }
    val (rows, tray) = readers.singleOrNull()
        ?: refuse("expected one read of $field in ${header.type}->$BUILD_ROWS, found ${readers.size}")
    val code = rows.instructions()
    if ((tray + 1..tray + 2).any { it in rows.jumpTargets() }) {
        refuse("${header.type}->$BUILD_ROWS has a jump into the highlights tray's type read or add")
    }
    val register = (code[tray] as OneRegisterInstruction).registerA
    val typeRead = code.getOrNull(tray + 1)
    val typeField = typeRead?.fieldReference()
    if (typeRead?.opcode != Opcode.IGET || typeField?.definingClass != setup.definingClass || typeField.type != "I" ||
        (typeRead as TwoRegisterInstruction).registerB != register
    ) {
        refuse("${header.type}->$BUILD_ROWS doesn't read the row type's int right after reading $field")
    }
    val typeRegister = typeRead.registerA
    val add = code.getOrNull(tray + 2)
    val added = add?.methodReference()
    if (add == null || add.opcode !in INVOKES || added?.returnType != "V" ||
        added.parameterTypes.map(CharSequence::toString) != listOf("I") || add.argumentRegisters().lastOrNull() != typeRegister
    ) {
        refuse("${header.type}->$BUILD_ROWS doesn't add a row by the highlights tray's type right after reading it")
    }
    if (tray + 3 !in code.indices) refuse("${header.type}->$BUILD_ROWS ends at the highlights tray")
    if (rows.readsAfter(tray + 2, register).isNotEmpty()) {
        refuse("${header.type}->$BUILD_ROWS reads v$register after adding the highlights tray")
    }
    // On the skip the int's register keeps whatever it held before, another type or nothing, so
    // a read after the add would get a stale value or fail verification where the paths meet.
    if (rows.readsAfter(tray + 2, typeRegister).isNotEmpty()) {
        refuse("${header.type}->$BUILD_ROWS reads v$typeRegister, the row type's int, after adding the highlights tray")
    }
    return HighlightsRowSite(rows.definingClass, rows.name, rows.parameterTypes.map(CharSequence::toString), tray, register, field)
}

/**
 * The static field [setup] stores the tray's row type in, followed through the registers rather
 * than taken as the next store: the enum's own constructor that receives the [REEL_TRAY] name, the
 * new-instance that made the object it runs on (through plain copies), and the first store of that
 * same object into one of the enum's fields of its own type. Every step is straight code nothing
 * jumps into, and nothing writes over the registers holding the name or the object in between, so
 * a sibling stored first, or stored into the tray's field, is never taken for the tray.
 */
private fun trayField(setup: Method): String {
    val enum = setup.definingClass
    val code = setup.instructions()
    val named = code.indices.filter { code[it].stringLoaded() == REEL_TRAY }
    val at = named.singleOrNull() ?: refuse("$enum's setup names $REEL_TRAY ${named.size} times")
    val flow = try {
        ControlFlow.of(setup)
    } catch (failure: IllegalArgumentException) {
        refuse("$enum's setup can't be followed: ${failure.message}")
    }
    val targets = setup.jumpTargets()
    // Reached only from the instruction before it, so its registers hold what that one left.
    fun straight(index: Int) = index in 1 until code.size && index !in targets && flow.normal[index - 1] == listOf(index)

    // The constructor that receives the name, while a register still holds it.
    val names = mutableSetOf((code[at] as OneRegisterInstruction).registerA)
    var build = at + 1
    while (true) {
        if (names.isEmpty() || !straight(build)) refuse("$enum's setup doesn't build a row type from $REEL_TRAY")
        val instruction = code[build]
        if (instruction.constructs(enum) && instruction.argumentRegisters().drop(1).any { it in names }) break
        names.follow(instruction)
        build++
    }

    // The object it runs on, back through plain copies to the new-instance that made it.
    val notMade = "$enum's setup doesn't make the $REEL_TRAY row type with new-instance"
    var held = code[build].argumentRegisters().first()
    var made = build
    while (true) {
        if (!straight(made)) refuse(notMade)
        made--
        val instruction = code[made]
        if (!instruction.writes(held)) continue
        if (instruction.opcode == Opcode.NEW_INSTANCE && instruction.typeReferenced() == enum) break
        held = instruction.objectCopiedFrom() ?: refuse(notMade)
    }

    // The first store of that object after its constructor, while a register still holds it.
    val objects = mutableSetOf(held)
    var store = made + 1
    while (true) {
        if (objects.isEmpty() || !straight(store)) refuse("$enum's setup doesn't store $REEL_TRAY")
        val instruction = code[store]
        val field = instruction.fieldReference()
        if (store > build && instruction.opcode == Opcode.SPUT_OBJECT && (instruction as OneRegisterInstruction).registerA in objects &&
            field?.definingClass == enum && field.type == enum
        ) {
            return field.toString()
        }
        objects.follow(instruction)
        store++
    }
}

/** Whether this runs a constructor of [type] itself. */
private fun Instruction.constructs(type: String): Boolean {
    if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val method = methodReference() ?: return false
    return method.name == "<init>" && method.definingClass == type
}

/** Whether this writes [register], or a wide pair covering it. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/** The register a plain object copy reads, or null for anything else. */
private fun Instruction.objectCopiedFrom(): Int? =
    if (opcode in OBJECT_MOVES) (this as TwoRegisterInstruction).registerB else null

/** Keeps a set of registers holding one value up to date past [instruction]: a plain copy of one joins it, any other write leaves. */
private fun MutableSet<Int>.follow(instruction: Instruction) {
    if (!instruction.opcode.setsRegister()) return
    val written = (instruction as? OneRegisterInstruction)?.registerA ?: return
    val copied = instruction.objectCopiedFrom()?.let { it in this } == true
    remove(written)
    if (instruction.opcode.setsWideRegister()) remove(written + 1)
    if (copied) add(written)
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

private fun Instruction.typeReferenced(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

/**
 * Replaces the read of the tray's row type with a call to [KEEP_TRAY], which keeps the read's
 * label, so whatever jumps there asks too. On a 0 the code goes past the add, and otherwise reads
 * the type as before and adds the row.
 */
internal fun BytecodePatchContext.hideHighlights(site: HighlightsRowSite) {
    val rows: MutableMethod = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val register = site.register
    rows.replaceInstruction(site.tray, "invoke-static { }, $KEEP_TRAY")
    val past = rows.getInstruction(site.tray + 3)
    rows.addInstructionsWithLabels(
        site.tray + 1,
        """
            move-result v$register
            if-eqz v$register, :past
            sget-object v$register, ${site.field}
        """,
        ExternalLabel("past", past),
    )
}

private val INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
)

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any { it.stringLoaded() == value }

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.readsStatic(field: String) = opcode == Opcode.SGET_OBJECT && fieldReference()?.toString() == field

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
