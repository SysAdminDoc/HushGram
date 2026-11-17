/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.preview

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.direct.keep.USER_SESSION
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Where Instagram's long press menus open (#728). */
internal const val CHAT_MENU_PLACEMENT = "$EXTENSION_PACKAGE/direct/ChatMenuPlacement;"
internal const val MENU_SHOW = "$CHAT_MENU_PLACEMENT->show(Landroid/widget/PopupWindow;Landroid/view/View;II)V"

internal const val MENU_POPUP_BASE = "Landroid/widget/PopupWindow;"
private const val SHOW_AS_DROP_DOWN = "showAsDropDown"
private const val SET_HEIGHT = "setHeight"
private val DROP_DOWN_PARAMETERS = listOf("Landroid/view/View;", "I", "I")
private val MENU_SHOW_PARAMETERS = listOf(MENU_POPUP_BASE, "Landroid/view/View;", "I", "I")

/** The constructor of Instagram's long press menu, a PopupWindow of its own: context, account, a style and a flag. */
internal val MENU_POPUP_INIT = listOf("Landroid/content/Context;", USER_SESSION, "Ljava/lang/Integer;", "Z")

/** How far before its show Instagram sets the menu's height, and how far before that it measures it. */
private const val HEIGHT_REACH = 12
private const val MEASURE_REACH = 8

private fun refuseShows(why: String): Nothing = throw PatchException("$PREVIEW_CHATS_PATCH: $why")
private fun Instruction.menuCall() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Method.parameterNames() = parameterTypes.map(Any::toString)

/** One method showing Instagram's long press menu, and its class. */
internal class MenuShows(val owner: ClassDef, val method: Method)

/** Whether a type is Instagram's long press menu: a PopupWindow built from a context, the account, a style and a flag. */
private fun BytecodePatchContext.menuTypes(): (String) -> Boolean {
    val known = HashMap<String, Boolean>()
    return { type ->
        known.getOrPut(type) {
            val popup = classDefByOrNull(type)
            popup?.superclass == MENU_POPUP_BASE && popup.methods.any { it.name == "<init>" && it.parameterNames() == MENU_POPUP_INIT }
        }
    }
}

/**
 * Every method that shows Instagram's long press menu below what was pressed: a call of
 * PopupWindow.showAsDropDown(View, int, int) on the menu right after it measured the menu and set
 * its height. Instagram's builds inline one helper for that into each list with a long press menu,
 * your messages' among them. Refuses when there's none, or when the extension has no hook.
 */
internal fun BytecodePatchContext.findMenuShows(): List<MenuShows> {
    val hook = classDefByOrNull(CHAT_MENU_PLACEMENT)?.methods?.any {
        it.name == "show" && it.parameterNames() == MENU_SHOW_PARAMETERS && it.returnType == "V"
    } == true
    if (!hook) refuseShows("the extension has no $MENU_SHOW")
    val isMenu = menuTypes()
    val found = classesCalling(MENU_POPUP_BASE, SHOW_AS_DROP_DOWN, DROP_DOWN_PARAMETERS, "V").flatMap { owner ->
        owner.methods.filter { method -> menuShowsIn(method.implementation?.instructions?.toList() ?: emptyList(), isMenu).isNotEmpty() }
            .map { MenuShows(owner, it) }
    }
    if (found.isEmpty()) refuseShows("no long press menu is shown below what was pressed")
    return found
}

/**
 * The indices in [code] of each showAsDropDown(View, int, int) call on a long press menu (a type
 * [isMenu] answers yes for): one whose receiver had setHeight called on it shortly before, right
 * after a call on that menu's own class measured it.
 */
internal fun menuShowsIn(code: List<Instruction>, isMenu: (String) -> Boolean): List<Int> = code.indices.filter { at ->
    val show = code[at].menuCall() ?: return@filter false
    if (code[at].opcode != Opcode.INVOKE_VIRTUAL || show.name != SHOW_AS_DROP_DOWN || show.returnType != "V" ||
        show.parameterTypes.map(Any::toString) != DROP_DOWN_PARAMETERS
    ) return@filter false
    val receiver = (code[at] as FiveRegisterInstruction).registerC
    val height = (at - 1 downTo maxOf(0, at - HEIGHT_REACH)).firstOrNull { back ->
        val call = code[back].menuCall()
        call != null && call.name == SET_HEIGHT && call.parameterTypes.map(Any::toString) == listOf("I") &&
            (code[back] as? FiveRegisterInstruction)?.registerC == receiver
    } ?: return@filter false
    (height - 1 downTo maxOf(0, height - MEASURE_REACH)).any { back ->
        val call = code[back].menuCall()
        code[back].opcode == Opcode.INVOKE_VIRTUAL && call != null && isMenu(call.definingClass) &&
            (code[back] as FiveRegisterInstruction).registerC == receiver
    }
}

/**
 * Hands each long press menu show to the extension in place of Instagram's call: the same menu,
 * anchor and offsets, so it can open the menu above what was pressed when it wouldn't fit below.
 * Only the call changes, so whatever jumps onto it lands on the hook.
 */
internal fun BytecodePatchContext.placeMenus(found: List<MenuShows>) {
    val isMenu = menuTypes()
    for (each in found) {
        val method = mutableClassDefBy(each.owner.type).methods.single {
            it.name == each.method.name && it.parameterNames() == each.method.parameterNames() && it.returnType == each.method.returnType
        }
        val code = method.implementation!!.instructions.toList()
        val sites = menuShowsIn(code, isMenu)
        if (sites.isEmpty()) refuseShows("${method.definingClass}->${method.name} lost its menu show")
        for (at in sites) {
            val call = code[at] as FiveRegisterInstruction
            val registers = listOf(call.registerC, call.registerD, call.registerE, call.registerF).joinToString { "v$it" }
            method.replaceInstruction(at, "invoke-static { $registers }, $MENU_SHOW")
        }
    }
}
