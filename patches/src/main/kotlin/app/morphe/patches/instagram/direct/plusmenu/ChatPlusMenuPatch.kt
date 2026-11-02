/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.plusmenu

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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

private const val PATCH = "Chat plus menu"
internal const val PLUS_MENU = "$EXTENSION_PACKAGE/direct/PlusMenu;"
internal const val MENU_HOOK = "$PLUS_MENU->menu(Ljava/util/List;Ljava/util/Map;)V"
private const val LIST = "Ljava/util/List;"

/** The names of the rows of the chat composer's plus menu this patch tells apart, in Instagram's button enum. */
internal val PLUS_BUTTON_NAMES = listOf("CROSS_APP_SAVES", "IMAGINE", "DOODLES", "GALLERY")

/**
 * Where the hook goes: the composer's button list builder, the index its loop over the buttons exits
 * to, the registers holding the finished menu list and the map from button to item, and a low register
 * nothing reads at that point.
 */
internal class PlusMenuSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val at: Int,
    val list: Int,
    val map: Int,
    val scratch: Int,
)

/**
 * Takes Saved, AI images and Draw off the plus menu of a chat's message bar, each by its own switch, and
 * can move the gallery and microphone buttons into that menu. Included in the default selection with
 * every switch off, so the menu stays as Instagram builds it until one is turned on.
 *
 * Instagram builds the bar in one method of its composer controller. It makes a map from each button
 * enum to the item that draws it, then a list of the enums the plus menu shows, and loops over that
 * list adding each enum's item to the menu's own list. The patch hands the finished menu list and the
 * map to the extension right where that loop exits. The extension looks the enum names up in the map's
 * keys, removes the items of the rows the switches name from the menu, and with the move switch on
 * adds the gallery and microphone items, the same objects the bar draws.
 */
@Suppress("unused")
val chatPlusMenuPatch = bytecodePatch(
    name = "Chat plus menu",
    description = "Lets you take Saved, AI images and Draw off the plus menu in a chat's message bar, and move the " +
        "gallery and microphone buttons into that menu. Starts with every switch off. Turn them on in HushGram settings > Messages.",
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("plusMenu")
        val site = findPlusMenu()
        handPlusMenu(site)
        enableStatus("plusMenu")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the button list builder: the one non-static method, in one class, that takes a single object,
 * returns a List and reads the enum fields of Saved, AI images, Draw and Gallery. The enum is the one
 * class whose static initializer loads all of [PLUS_BUTTON_NAMES]; each name is found in it as the last
 * string loaded before the static field that gets the new constant. In that method the loop is the one
 * that gets each enum's item from a Map and adds it to a list; the hook goes where the loop exits.
 */
internal fun BytecodePatchContext.findPlusMenu(): PlusMenuSite {
    val enums = classesHolding(*PLUS_BUTTON_NAMES.toTypedArray()).filter { candidate ->
        candidate.superclass == "Ljava/lang/Enum;" && constantFields(candidate).keys.containsAll(PLUS_BUTTON_NAMES)
    }
    val buttons = enums.singleOrNull() ?: refuse("${enums.size} composer button enums, not one")
    val fields = constantFields(buttons)
    val wanted = PLUS_BUTTON_NAMES.map { fields.getValue(it) }
    val readers = wanted.map { field ->
        classesAccessing(buttons.type, field, Opcode.SGET_OBJECT).mapTo(HashSet()) { it.type }
    }
    val builders = classesAccessing(buttons.type, wanted.first(), Opcode.SGET_OBJECT)
        .filter { candidate -> readers.all { candidate.type in it } }
        .flatMap { candidate -> candidate.methods.filter { reads(it, buttons.type, wanted) } }
    val builder = builders.singleOrNull() ?: refuse("${builders.size} button list builders, not one")
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.code()

    val lookups = code.indices.filter { at ->
        code[at].isCall("Ljava/util/Map;", "get") &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            code.getOrNull(at + 2)?.opcode == Opcode.IF_EQZ &&
            code.getOrNull(at + 3)?.let { it.isCall(null, "add") && it.registers().size == 2 } == true
    }
    val lookup = lookups.singleOrNull() ?: refuse("$where has ${lookups.size} loops over a button map, not one")
    val looked = code[lookup].registers().first()
    val list = code[lookup + 3].registers().first()
    val hasNext = (lookup - 1 downTo 0).firstOrNull { code[it].isCall("Ljava/util/Iterator;", "hasNext") }
        ?: refuse("$where has no hasNext before the map lookup")
    if (code.getOrNull(hasNext + 1)?.opcode != Opcode.MOVE_RESULT || code.getOrNull(hasNext + 2)?.opcode != Opcode.IF_EQZ) {
        refuse("$where tests its loop some other way")
    }
    // On 453 the loop copies the map into a low register for each lookup, and that register holds the
    // loop test's answer where the loop exits, so the map is handed on from the register it's copied from.
    val copy = code[lookup - 1].takeIf { it.opcode in OBJECT_MOVES && (it as TwoRegisterInstruction).registerA == looked }
    val map = (copy as? TwoRegisterInstruction)?.registerB ?: looked
    if ((hasNext..lookup + 3).any { code[it].writes(map) }) refuse("$where writes its button map inside the loop")
    val exit = ControlFlow.of(builder).normal[hasNext + 2].firstOrNull() ?: refuse("$where has no loop exit")
    if (exit <= lookup) refuse("$where exits its loop backwards")
    if (list > 15) refuse("$where holds its menu list in register $list")
    val live = RegisterLiveness.of(builder).liveInto(exit)
    val scratch = (0 until minOf(builder.localRegisterCount(), 16)).firstOrNull { it !in live && it != list }
        ?: refuse("$where has no spare register where its loop exits")
    return PlusMenuSite(builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), exit, list, map, scratch)
}

/** Hands the finished menu list and the button map to [MENU_HOOK] where the loop exits. */
internal fun BytecodePatchContext.handPlusMenu(site: PlusMenuSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructionsAtControlFlowLabel(
        site.at,
        """
            move-object/from16 v${site.scratch}, v${site.map}
            invoke-static { v${site.list}, v${site.scratch} }, $MENU_HOOK
        """,
    )
}

/** The static field each enum constant of [enumClass] lands in, by the constant's name. */
private fun constantFields(enumClass: ClassDef): Map<String, String> {
    val initializer = enumClass.methods.singleOrNull { it.name == "<clinit>" } ?: return emptyMap()
    val found = LinkedHashMap<String, String>()
    var lastName: String? = null
    for (instruction in initializer.code()) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference is StringReference) lastName = reference.string
        if (instruction.opcode == Opcode.SPUT_OBJECT && reference is FieldReference &&
            reference.definingClass == enumClass.type && reference.type == enumClass.type && lastName != null) {
            found.putIfAbsent(lastName, reference.name)
        }
    }
    return found
}

/** Whether [method] is a plain List builder over one object that reads every one of [fields] of [enumType]. */
private fun reads(method: Method, enumType: String, fields: List<String>): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != LIST || method.parameterTypes.size != 1) return false
    val read = method.code().mapNotNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.SGET_OBJECT && reference?.definingClass == enumType) reference.name else null
    }.toSet()
    return read.containsAll(fields)
}

/** Whether this is a call of the method [name], on [owner] when one is given. */
private fun Instruction.isCall(owner: String?, name: String): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.name == name && (owner == null || reference.definingClass == owner)
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** Whether this instruction writes [register], as its own or as the high half of a wide value. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && written == register || opcode.setsWideRegister() && written + 1 == register
}

/** The registers an invoke names, receiver first. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
