/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

private const val IMAGE_VIEW = "Landroid/widget/ImageView;"

/** Lays the extension's answer for a menu row's icon color on the row's icon, in place of ImageView.setColorFilter(int) (#187). */
internal const val MENU_ICON = "$ACCENT_THEME->menuIcon(${IMAGE_VIEW}I)V"

/** The menu row adapter's message when a row it lays out as a switch isn't one. */
internal const val MENU_SWITCH_ROW = "null cannot be cast to non-null type com.instagram.ui.menu.SwitchItem"

/** The menu row adapter's message when a row it lays out as a sliding selector isn't one. */
internal const val MENU_SELECTOR_ROW = "null cannot be cast to non-null type com.instagram.ui.menu.SlidingSelectorItem"

/** Where [method], a menu rows' binder, colors a row's icon: the ImageView.setColorFilter(int) calls at [calls]. */
internal class MenuRowIcons(val method: MutableMethod, val calls: List<Int>)

/**
 * The methods laying out the rows of Instagram's menus and coloring their icons (`LX/0TsX;->A0g` on
 * 386314286), a reel's three-dot menu among them: each instance method of a class loading
 * [MENU_SWITCH_ROW], taking a row's holder and its position and answering nothing, which loads both
 * [MENU_SWITCH_ROW] and [MENU_SELECTOR_ROW], with each ImageView.setColorFilter(int) in it. Another
 * adapter loads both messages from a pool and colors no icon this way (`LX/0DX4;->A0g`), so it's left
 * out. The rows color their icons with Instagram's text and icon color, so the icon style never
 * reached them (#187). Answers an empty list with a warning when no such method colors an icon.
 * Refuses when the extension lacks [MENU_ICON].
 */
internal fun BytecodePatchContext.findMenuRowIcons(): List<MenuRowIcons> {
    val binders = classesLoadingString(MENU_SWITCH_ROW).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString).let { it.size == 2 && it[0].startsWith("L") && it[1] == "I" } &&
            loadsString(method, MENU_SWITCH_ROW) && loadsString(method, MENU_SELECTOR_ROW)
    }
    val coloring = binders.map { it to it.code().let { code -> code.indices.filter { at -> code[at].setsColorFilter() } } }.filter { it.second.isNotEmpty() }
    if (coloring.isEmpty()) {
        patchLog.warning("$PATCH: none of ${binders.size} methods laying out menu rows colors an icon, so the icons in Instagram's menus keep its text color")
        return emptyList()
    }
    requireHook(MENU_ICON)
    return coloring.map { (binder, calls) ->
        val mutable = mutableClassDefBy(binder.definingClass).methods.single {
            it.name == binder.name && it.parameterTypes.map(Any::toString) == binder.parameterTypes.map(Any::toString) && it.returnType == binder.returnType
        }
        MenuRowIcons(mutable, calls)
    }
}

/** Whether this calls ImageView.setColorFilter(int) on an image view. */
private fun Instruction.setsColorFilter(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = called() ?: return false
    return call.definingClass == IMAGE_VIEW && call.name == "setColorFilter" && call.returnType == "V" &&
        call.parameterTypes.map(Any::toString) == listOf("I")
}

/**
 * Hands each icon [findMenuRowIcons]' binder colors and its color to the extension, which lays its
 * answer on the icon: with Icons in the accent color on, the theme's icon color in place of its text
 * and icon color, and every other color as it is (#187). Each call becomes the extension's with the
 * same registers, so nothing around it moves.
 */
internal fun BytecodePatchContext.tintMenuIcons() {
    for (found in findMenuRowIcons()) {
        for (at in found.calls) {
            val call = found.method.getInstruction<Instruction>(at)
            val replaced = when (call) {
                is RegisterRangeInstruction -> "invoke-static/range { v${call.startRegister} .. v${call.startRegister + 1} }, $MENU_ICON"
                else -> (call as FiveRegisterInstruction).let { "invoke-static { v${it.registerC}, v${it.registerD} }, $MENU_ICON" }
            }
            found.method.replaceInstruction(at, replaced)
        }
    }
}

private fun BytecodePatchContext.requireHook(hook: String) {
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) refuse("the extension has no public static $hook")
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
