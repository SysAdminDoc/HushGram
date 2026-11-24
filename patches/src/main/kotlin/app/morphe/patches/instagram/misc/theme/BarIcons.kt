/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_ACTION_BAR
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_SAVED_STUB
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Accent color"

private const val FILTER = "Landroid/graphics/ColorFilter;"
private const val IMAGE = "Landroid/widget/ImageView;"
private const val PORTER_DUFF_FILTER = "Landroid/graphics/PorterDuffColorFilter;"
private const val SRC_ATOP = "Landroid/graphics/PorterDuff\$Mode;->SRC_ATOP:Landroid/graphics/PorterDuff\$Mode;"

/** The extension's answer to the color a top bar's icon or a profile tab is given (#187). */
internal const val BAR_ICONS = "$ACCENT_THEME->barIcons(I)I"

/** The extension's stand-in for the top bar's maker of the filter it lays on its buttons (#187). */
internal const val BAR_ICON_FILTER = "$ACCENT_THEME->barIconFilter(I)$FILTER"

/** The extension's stand-in for the top bar's ImageView.setColorFilter(int) on its back arrow (#187). */
internal const val SET_BAR_ICON_COLOR = "$ACCENT_THEME->setBarIconColor(${IMAGE}I)V"

/** Hands the extension the Search tab's Saved bookmark as its top bar makes it (#187). */
internal const val SEARCH_SAVED = "$ACCENT_THEME->searchSaved($IMAGE)V"

/**
 * What only the class behind every screen's top bar throws, the one class holding its back arrow,
 * title and buttons (`LX/01pF` on 386314341). Instagram keeps the message as it is through its builds.
 */
internal const val ACTION_BAR_MESSAGE = "Custom action-bar views require action Done-to-checkmark Wave 2"

/** A profile's Posts, Reels, Reposts and Tagged tab. Its layout names it, so it keeps its name. */
internal const val PROFILE_TAB = "Lcom/instagram/profile/intf/tabs/ProfileIconTabView;"

/** The top bar's calls that color its icons: [filters] make a button's color filter, [tints] tint its back arrow. */
internal class ActionBarIcons(val filters: List<Pair<MutableMethod, Int>>, val tints: List<Pair<MutableMethod, Int>>)

/**
 * Finds [ActionBarIcons] in the one class loading [ACTION_BAR_MESSAGE]: each call to a static
 * `(int)ColorFilter` that makes a PorterDuffColorFilter in SRC_ATOP mode, the one the bar lays on its
 * back arrow and buttons with Instagram's text and icon color, and each ImageView.setColorFilter(int).
 * Answers null with a warning when there isn't one such class or it makes no icon's color, so the top
 * bars keep Instagram's colors. Refuses when the extension lacks a hook.
 */
internal fun BytecodePatchContext.findActionBarIcons(): ActionBarIcons? {
    fun skip(why: String): ActionBarIcons? {
        patchLog.warning("$PATCH: $why, so the icons on a screen's top bar keep Instagram's colors")
        return null
    }
    val bars = classesLoadingString(ACTION_BAR_MESSAGE).distinctBy { it.type }
    val bar = bars.singleOrNull() ?: return skip("${bars.size} classes load the top bar's message, not one")
    val makers = HashMap<String, Boolean>()
    fun makesFilter(called: MethodReference): Boolean = makers.getOrPut("${called.definingClass}->${called.name}") {
        classDefByOrNull(called.definingClass)?.methods?.singleOrNull {
            it.name == called.name && it.sameShape(called) && AccessFlags.STATIC.isSet(it.accessFlags)
        }?.makesSrcAtopFilter() == true
    }
    val filters = mutableListOf<Pair<String, Int>>()
    val tints = mutableListOf<Pair<String, Int>>()
    for (method in bar.methods) {
        val code = method.code()
        for (at in code.indices) {
            val called = code[at].called() ?: continue
            when {
                code[at].opcode in STATIC_CALLS && called.returnType == FILTER && called.parameterTypes.map(Any::toString) == listOf("I") &&
                    makesFilter(called) -> filters += method.key() to at
                code[at].opcode in VIRTUAL_CALLS && called.definingClass == IMAGE && called.name == "setColorFilter" &&
                    called.parameterTypes.map(Any::toString) == listOf("I") -> tints += method.key() to at
            }
        }
    }
    if (filters.isEmpty() && tints.isEmpty()) return skip("${bar.type} makes no icon's color")
    for (hook in listOf(BAR_ICON_FILTER, SET_BAR_ICON_COLOR)) requireHook(hook)
    val owner = mutableClassDefBy(bar.type)
    val methods = owner.methods.associateBy { it.key() }
    return ActionBarIcons(filters.map { methods.getValue(it.first) to it.second }, tints.map { methods.getValue(it.first) to it.second })
}

/**
 * Hands the color each of a screen's top bar's buttons and its back arrow is given to the extension,
 * which answers the picked color with Icons in the accent color on (#187). The bar reads Instagram's
 * text and icon color for them, which the title shares, so the icon style the theme gets never reached
 * them and Saved's back arrow, search and + stayed white. Each call to the bar's filter maker becomes a
 * call to the extension's, which makes the same filter from its answer, and each
 * ImageView.setColorFilter(int) a call to the extension's with the view and the color, which lays its
 * answer on that view. The registers stay as they were.
 */
internal fun BytecodePatchContext.tintActionBarIcons() {
    val found = findActionBarIcons() ?: return
    for ((method, at) in found.filters) {
        val call = method.getInstruction<Instruction>(at)
        val color = if (call is RegisterRangeInstruction) call.startRegister else (call as FiveRegisterInstruction).registerC
        val range = call is RegisterRangeInstruction
        method.replaceInstruction(at, if (range) "invoke-static/range { v$color .. v$color }, $BAR_ICON_FILTER" else "invoke-static { v$color }, $BAR_ICON_FILTER")
    }
    for ((method, at) in found.tints) {
        val call = method.getInstruction<Instruction>(at)
        val registers = when (call) {
            is RegisterRangeInstruction -> "v${call.startRegister} .. v${call.startRegister + 1}"
            else -> (call as FiveRegisterInstruction).let { "v${it.registerC}, v${it.registerD}" }
        }
        method.replaceInstruction(at, if (call is RegisterRangeInstruction) "invoke-static/range { $registers }, $SET_BAR_ICON_COLOR" else "invoke-static { $registers }, $SET_BAR_ICON_COLOR")
    }
}

/** Where a profile tab is told its colors: [method], and the color [reads] it takes from the theme for while it isn't selected. */
internal class ProfileTabColors(val method: MutableMethod, val reads: List<Int>)

/**
 * Finds [PROFILE_TAB]'s setActiveColor(int) and each Context.getColor(int) in it with its answer
 * taken right after, the gray of a tab you aren't on. Answers null with a warning when there's no such
 * class or method, so the profile's tabs keep Instagram's colors. Refuses when the extension lacks
 * [BAR_ICONS].
 */
internal fun BytecodePatchContext.findProfileTabColors(): ProfileTabColors? {
    fun skip(why: String): ProfileTabColors? {
        patchLog.warning("$PATCH: $why, so a profile's tabs keep Instagram's colors")
        return null
    }
    val tab = classDefByOrNull(PROFILE_TAB) ?: return skip("no $PROFILE_TAB")
    val setter = tab.methods.singleOrNull {
        it.name == "setActiveColor" && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "V" && it.implementation != null
    } ?: return skip("$PROFILE_TAB has no setActiveColor(int)")
    val code = setter.code()
    val reads = code.indices.filter { at ->
        val called = code[at].called()
        called != null && called.definingClass == "Landroid/content/Context;" && called.name == "getColor" &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
    requireHook(BAR_ICONS)
    return ProfileTabColors(mutableClassDefBy(PROFILE_TAB).methods.single { it.name == setter.name && it.sameShape(setter) }, reads)
}

/**
 * Hands a profile tab's color to the extension as setActiveColor(int) starts, and the gray it reads
 * for while it isn't selected right after reading it, so with Icons in the accent color on Posts,
 * Reels, Reposts and Tagged take the picked color whether you're on them or not (#187). The range form
 * takes any register.
 */
internal fun BytecodePatchContext.tintProfileTabs() {
    val found = findProfileTabColors() ?: return
    val method = found.method
    for (at in found.reads.sortedDescending()) {
        val register = method.getInstruction<OneRegisterInstruction>(at).registerA
        method.addInstructions(at + 1, "invoke-static/range { v$register .. v$register }, $BAR_ICONS\nmove-result v$register")
    }
    val color = method.parameterRegisterNumber(0)
    method.addInstructions(0, "invoke-static/range { v$color .. v$color }, $BAR_ICONS\nmove-result v$color")
}

/** Where the Search tab's top bar keeps the Saved bookmark it made: the store at [at] of the view in [view]. */
internal class SearchSavedStore(val method: MutableMethod, val at: Int, val view: Int)

/**
 * Finds the one store of an image view into a field of [EXPLORE_ACTION_BAR] after the one method
 * loading [EXPLORE_SAVED_STUB] loads it: the bookmark it just inflated. Answers null with a warning
 * when there isn't one such method or store, so the bookmark keeps Instagram's color. Refuses when the
 * extension lacks [SEARCH_SAVED].
 */
internal fun BytecodePatchContext.findSearchSavedStore(): SearchSavedStore? {
    fun skip(why: String): SearchSavedStore? {
        patchLog.warning("$PATCH: $why, so the Search tab's Saved bookmark keeps Instagram's color")
        return null
    }
    val bar = classDefByOrNull(EXPLORE_ACTION_BAR) ?: return skip("no $EXPLORE_ACTION_BAR")
    val makers = bar.methods.filter { method -> method.code().any { it.loads(EXPLORE_SAVED_STUB) } }
    val maker = makers.singleOrNull() ?: return skip("${makers.size} methods of $EXPLORE_ACTION_BAR load the Saved stub, not one")
    val code = maker.code()
    val load = code.indexOfFirst { it.loads(EXPLORE_SAVED_STUB) }
    val stores = (load until code.size).filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.definingClass == EXPLORE_ACTION_BAR && isImageView(field.type)
    }
    val store = stores.firstOrNull() ?: return skip("$EXPLORE_ACTION_BAR->${maker.name} keeps no image view after loading the Saved stub")
    requireHook(SEARCH_SAVED)
    return SearchSavedStore(
        mutableClassDefBy(EXPLORE_ACTION_BAR).methods.single { it.name == maker.name && it.sameShape(maker) },
        store,
        (code[store] as TwoRegisterInstruction).registerA,
    )
}

/**
 * Hands the Search tab's Saved bookmark to the extension right after its top bar keeps it, which lays
 * the picked icon color over it with Icons in the accent color on (#187). The range form takes any
 * register.
 */
internal fun BytecodePatchContext.tintSearchSaved() {
    val found = findSearchSavedStore() ?: return
    found.method.addInstructions(found.at + 1, "invoke-static/range { v${found.view} .. v${found.view} }, $SEARCH_SAVED")
}

/** [tintActionBarIcons], [tintProfileTabs] and [tintSearchSaved]. */
internal fun BytecodePatchContext.tintBarIcons() {
    tintActionBarIcons()
    tintProfileTabs()
    tintSearchSaved()
}

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** Whether [type] is ImageView or a class of the app extending it, however far down. */
private fun BytecodePatchContext.isImageView(type: String): Boolean {
    var current: String? = type
    repeat(8) {
        if (current == IMAGE) return true
        current = current?.let { classDefByOrNull(it)?.superclass } ?: return false
    }
    return false
}

/** Whether this method's body makes a PorterDuffColorFilter in SRC_ATOP mode. */
private fun Method.makesSrcAtopFilter(): Boolean {
    val code = code()
    return code.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == PORTER_DUFF_FILTER } &&
        code.any { it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).text() == SRC_ATOP }
}

private fun BytecodePatchContext.requireHook(hook: String) {
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) throw PatchException("$PATCH: the extension has no public static $hook")
}

private fun Instruction.loads(literal: Int) = (this as? NarrowLiteralInstruction)?.narrowLiteral == literal

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Method.sameShape(other: MethodReference): Boolean =
    returnType == other.returnType && parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

private fun Method.key(): String = "$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
